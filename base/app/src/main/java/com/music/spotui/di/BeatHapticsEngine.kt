package com.music.spotui.di

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.music.spotui.rust.OrpheonRustAudio
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Real-time, audio-synchronized Bass & Vocal Beat Haptic Engine for DanyFy.
 *
 * Driven by:
 * 1. The native Rust multi-band DSP + circular tempo/phase tracker (`liborpheon_rust.so`)
 *    inside [EqualizerProcessor], detecting sub-bass/kick (`30-145 Hz`), snare (`145-360 Hz`),
 *    and center-weighted vocal phrases (`320-2600 Hz`, side-suppressed).
 * 2. [AudioSyncClock] + [BeatSyncSink], compensating Media3's `0.5-2.0 s` `AudioTrack` ring
 *    buffer so each physical pulse hits when the sound actually reaches the listener's ear, and
 *    cancelling future vibrations per sink token immediately on pause, seek, or track stop.
 * 3. Collision-free priority queue (Bass > Snare > Vocal) enforcing musical refractory spacing
 *    so the vibrator never turns into random buzzing or overlapping mush.
 */
object BeatHapticsEngine {
    const val KIND_BASS = 0
    const val KIND_SNARE = 1
    const val KIND_VOCAL = 2

    private const val MAX_PENDING_EVENTS = 24

    @Volatile var enabled: Boolean = false
        private set
    @Volatile var mode: String = "rhythm_hybrid" // rhythm_hybrid | bass_kick | vocals | full_beat | sub_thump | pulse_bpm
        private set
    @Volatile var intensity: String = "medium" // light | medium | strong | max
        private set
    @Volatile var sensitivity: String = "normal" // low | normal | high
        private set
    @Volatile var motor: String = "auto" // auto | erm_strong | crisp
        private set
    @Volatile var channel: String = "alarm" // alarm | touch | media
        private set
    @Volatile var rhythmLock: Boolean = true
        private set
    @Volatile var trackBpm: Double = 120.0
        private set
    @Volatile var detectedBpm: Double = 0.0
        private set

    /** True when PCM detection in native DSP / AudioProcessors should bypass offload. */
    val needsPcm: Boolean
        get() = enabled && mode != "pulse_bpm"

    @Volatile private var ownerExtensionId: String? = null
    @Volatile private var lastPcmFlowMonoMs: Long = 0L
    @Volatile private var lastFiredAnyMonoMs: Long = 0L
    @Volatile private var lastFiredBassMonoMs: Long = 0L
    @Volatile private var lastFiredSnareMonoMs: Long = 0L
    @Volatile private var lastFiredVocalMonoMs: Long = 0L
    @Volatile private var cachedVibrator: Vibrator? = null
    @Volatile private var cachedHasAmp: Boolean? = null

    internal var monoClockMs: () -> Long = { System.nanoTime() / 1_000_000L }

    private val queueLock = Any()
    private val pendingQueue = ArrayList<ScheduledEvent>(MAX_PENDING_EVENTS)

    private val pcmBeatsCount = AtomicLong(0L)
    private val bassBeatsCount = AtomicLong(0L)
    private val vocalBeatsCount = AtomicLong(0L)
    private val bpmBeatsCount = AtomicLong(0L)
    private val pulsesFiredCount = AtomicLong(0L)

    private class ScheduledEvent(
        val sourceToken: Long,
        val fireMonoMs: Long,
        val kind: Int,
        val strength: Int,
        @Volatile var cancelled: Boolean = false,
        var task: Runnable? = null,
    )

    private val handler: Handler? by lazy {
        runCatching {
            val t = HandlerThread("beat-haptics", android.os.Process.THREAD_PRIORITY_BACKGROUND).apply {
                isDaemon = true
                start()
            }
            Handler(t.looper)
        }.getOrNull()
    }

    fun unpackOffset(packed: Long): Int = (packed and 0xFFFFFFL).toInt()
    fun unpackStrength(packed: Long): Int = ((packed ushr 24) and 0xFFL).toInt()
    fun unpackKind(packed: Long): Int = ((packed ushr 32) and 0xFL).toInt()
    fun unpackBpm(packed: Long): Double {
        val x10 = ((packed ushr 36) and 0xFFFL).toInt()
        return if (x10 in 500..2200) x10 / 10.0 else 0.0
    }

    fun isOwnedBy(extensionId: String): Boolean =
        ownerExtensionId == null || ownerExtensionId == extensionId

    fun onExtensionStopped(extensionId: String) {
        if (extensionId == "io.danyfy.beathaptics" || ownerExtensionId == extensionId) {
            ownerExtensionId = null
            configure(enabled = false)
            clearPending(0L)
        }
    }

    fun notePcmFlowing() {
        lastPcmFlowMonoMs = monoClockMs()
    }

    private fun isPcmFlowingNow(): Boolean {
        val last = lastPcmFlowMonoMs
        return last > 0L && (monoClockMs() - last) < 2_500L
    }

    private fun resolveVibrator(): Vibrator? {
        cachedVibrator?.let { return it }
        val resolved = runCatching {
            val ctx = Graph.app
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                val dv = vm?.defaultVibrator
                if (dv != null && runCatching { dv.hasVibrator() }.getOrDefault(false)) {
                    return@runCatching dv
                }
            }
            @Suppress("DEPRECATION")
            ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }.getOrNull()
        if (resolved != null && runCatching { resolved.hasVibrator() }.getOrDefault(false)) {
            cachedVibrator = resolved
            return resolved
        }
        return null
    }

    private fun hasAmplitudeControl(vib: Vibrator): Boolean {
        cachedHasAmp?.let { return it }
        val supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            runCatching { vib.hasAmplitudeControl() }.getOrDefault(false)
        cachedHasAmp = supported
        return supported
    }

    private fun isErmActive(): Boolean {
        if (motor == "erm_strong") return true
        if (motor == "crisp") return false
        val vib = resolveVibrator() ?: return false
        return !hasAmplitudeControl(vib)
    }

    private fun motorSpinLeadMs(): Long = when {
        motor == "crisp" -> 10L
        isErmActive() -> 26L
        else -> 14L
    }

    private fun audioAttrsFor(usage: Int): AudioAttributes = AudioAttributes.Builder()
        .setUsage(usage)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    private val bpmTickRunnable = object : Runnable {
        override fun run() {
            if (!enabled || mode != "pulse_bpm") return
            val playing = runCatching { SongPlayer.isPlayingSafe() }.getOrDefault(false)
            if (playing) {
                bpmBeatsCount.incrementAndGet()
                triggerBeatHaptic(strength = 205, kind = KIND_BASS)
            }
            val activeBpm = (if (detectedBpm in 55.0..200.0) detectedBpm else trackBpm).coerceIn(60.0, 200.0)
            val intervalMs = (60_000.0 / activeBpm).toLong().coerceIn(280L, 1_000L)
            handler?.postDelayed(this, intervalMs)
        }
    }

    fun configure(
        enabled: Boolean = this.enabled,
        mode: String = this.mode,
        intensity: String = this.intensity,
        sensitivity: String = this.sensitivity,
        motor: String = this.motor,
        channel: String = this.channel,
        bpm: Double? = null,
        rhythmLock: Boolean = this.rhythmLock,
        extensionId: String? = null,
    ) {
        val prevNeedsPcm = this.needsPcm
        this.enabled = enabled
        val normMode = mode.lowercase()
        this.mode = when (normMode) {
            "rhythm_hybrid", "bass_kick", "vocals", "full_beat", "sub_thump", "pulse_bpm" -> normMode
            else -> "rhythm_hybrid"
        }
        this.intensity = intensity.lowercase()
        this.sensitivity = sensitivity.lowercase()
        this.motor = motor.lowercase()
        this.channel = channel.lowercase()
        this.rhythmLock = rhythmLock
        if (extensionId != null) {
            ownerExtensionId = extensionId
        }
        if (bpm != null && bpm in 40.0..240.0) {
            this.trackBpm = bpm
        } else if (!enabled) {
            this.detectedBpm = 0.0
        }

        val modeCode = when (this.mode) {
            "full_beat" -> 1
            "sub_thump" -> 2
            "pulse_bpm" -> 3
            "rhythm_hybrid" -> 4
            "vocals" -> 5
            else -> 0 // bass_kick
        }
        val sensCode = when (this.sensitivity) {
            "low" -> 0
            "high" -> 2
            else -> 1 // normal
        }
        val useNativeDsp = enabled && this.mode != "pulse_bpm"
        if (OrpheonRustAudio.available) {
            runCatching {
                OrpheonRustAudio.nativeConfigureBeatDetector(
                    useNativeDsp,
                    modeCode,
                    sensCode,
                    this.rhythmLock,
                )
            }
        }
        if (!useNativeDsp) {
            clearPending(0L)
        }
        if (prevNeedsPcm != this.needsPcm) {
            runCatching { LocalPlayer.applyOffload() }
        }
        handler?.let { h ->
            h.removeCallbacks(bpmTickRunnable)
            if (enabled && this.mode == "pulse_bpm") {
                h.postDelayed(bpmTickRunnable, 120L)
            }
        }
    }

    /** Called when player state changes on main/player threads to start/pause cleanly. */
    fun onPlaybackStateChanged(isPlaying: Boolean) {
        if (!isPlaying) {
            clearPending(0L)
            return
        }
        if (!enabled || mode != "pulse_bpm") return
        handler?.let { h ->
            h.removeCallbacks(bpmTickRunnable)
            h.postDelayed(bpmTickRunnable, 80L)
        }
    }

    /** Backward-compatible entry point for immediate mono-strength triggers. */
    fun onPcmBeat(strength: Int) {
        onPcmEvent(
            sourceToken = 0L,
            strength = strength,
            kind = KIND_BASS,
            delayMs = 0L,
            trackedBpm = 0.0,
        )
    }

    /**
     * Schedules a single analyzed audio beat/vocal onset to fire when its PCM buffer reaches
     * the speaker, deduplicating close collisions so bass kicks and vocal taps never overlap
     * into continuous buzz. Returns `true` if accepted into the schedule.
     */
    fun onPcmEvent(
        sourceToken: Long,
        strength: Int,
        kind: Int,
        delayMs: Long,
        trackedBpm: Double = 0.0,
    ): Boolean {
        if (!enabled || strength <= 0 || mode == "pulse_bpm" || delayMs < 0L) return false

        val normKind = kind.coerceIn(KIND_BASS, KIND_VOCAL)
        val clampedStrength = strength.coerceIn(1, 255)
        if (trackedBpm in 50.0..220.0) {
            detectedBpm = if (detectedBpm <= 0.0) trackedBpm else detectedBpm * 0.65 + trackedBpm * 0.35
        }

        val nowMs = monoClockMs()
        val compensatedDelayMs = (delayMs - motorSpinLeadMs()).coerceAtLeast(0L)
        val targetFireMs = nowMs + compensatedDelayMs

        val activeBpm = (if (detectedBpm in 55.0..200.0) detectedBpm else trackBpm).coerceIn(55.0, 200.0)
        val beatPeriodMs = 60_000.0 / activeBpm

        val sameKindGapMs = when (normKind) {
            KIND_BASS -> if (rhythmLock) (beatPeriodMs * 0.42).roundToLong().coerceIn(220L, 320L) else 190L
            KIND_SNARE -> if (rhythmLock) 210L else 170L
            else -> if (rhythmLock) 320L else 240L
        }
        val crossKindGapMs = when {
            isErmActive() -> if (rhythmLock) 185L else 145L
            rhythmLock -> 155L
            else -> 120L
        }

        val newPriority = kindPriority(normKind)
        val replaced = ArrayList<ScheduledEvent>(2)
        val event = ScheduledEvent(
            sourceToken = sourceToken,
            fireMonoMs = targetFireMs,
            kind = normKind,
            strength = clampedStrength,
        )

        synchronized(queueLock) {
            // Drop expired queue items
            val it = pendingQueue.iterator()
            while (it.hasNext()) {
                if (it.next().fireMonoMs < nowMs - 40L) it.remove()
            }

            // Respect already-fired physical pulses
            if (lastFiredAnyMonoMs > 0L && (targetFireMs - lastFiredAnyMonoMs) < crossKindGapMs) {
                return false
            }
            val lastSame = when (normKind) {
                KIND_BASS -> lastFiredBassMonoMs
                KIND_SNARE -> lastFiredSnareMonoMs
                else -> lastFiredVocalMonoMs
            }
            if (lastSame > 0L && (targetFireMs - lastSame) < sameKindGapMs) {
                return false
            }

            // Check collisions against future queued vibrations
            for (existing in pendingQueue) {
                val dt = abs(existing.fireMonoMs - targetFireMs)
                if (existing.kind == normKind && dt < sameKindGapMs) {
                    if (clampedStrength > existing.strength + 25 && existing.sourceToken == sourceToken) {
                        replaced += existing
                    } else {
                        return false
                    }
                } else if (dt < crossKindGapMs) {
                    if (newPriority > kindPriority(existing.kind)) {
                        // A true bass kick takes priority over a colliding vocal tail
                        replaced += existing
                    } else {
                        return false
                    }
                }
            }

            if (replaced.isNotEmpty()) {
                for (r in replaced) {
                    r.cancelled = true
                    pendingQueue.remove(r)
                }
            }
            if (pendingQueue.size >= MAX_PENDING_EVENTS) {
                // Drop lowest priority / oldest queued item to stay bounded
                val victim = pendingQueue.minWithOrNull(
                    compareBy<ScheduledEvent> { kindPriority(it.kind) }.thenBy { it.fireMonoMs }
                )
                if (victim != null) {
                    victim.cancelled = true
                    replaced += victim
                    pendingQueue.remove(victim)
                }
            }
            pendingQueue += event
        }

        val h = handler
        for (r in replaced) {
            r.task?.let { h?.removeCallbacks(it) }
        }

        pcmBeatsCount.incrementAndGet()
        if (h != null) {
            val task = Runnable {
                val shouldRun = synchronized(queueLock) {
                    val removed = pendingQueue.remove(event)
                    removed && !event.cancelled
                }
                if (shouldRun && enabled && mode != "pulse_bpm") {
                    triggerBeatHaptic(event.strength, event.kind)
                }
            }
            event.task = task
            if (compensatedDelayMs == 0L) {
                h.postAtTime(task, sourceToken, SystemClock.uptimeMillis())
            } else {
                h.postAtTime(task, sourceToken, SystemClock.uptimeMillis() + compensatedDelayMs)
            }
        }
        return true
    }

    private fun kindPriority(kind: Int): Int = when (kind) {
        KIND_BASS -> 3
        KIND_SNARE -> 2
        else -> 1
    }

    internal fun pendingCount(sourceToken: Long = 0L): Int = synchronized(queueLock) {
        if (sourceToken == 0L) pendingQueue.size else pendingQueue.count { it.sourceToken == sourceToken }
    }

    internal fun resetStateForTest() {
        clearPending(0L)
        synchronized(queueLock) {
            pendingQueue.clear()
        }
        enabled = false
        mode = "rhythm_hybrid"
        intensity = "medium"
        sensitivity = "normal"
        motor = "auto"
        channel = "alarm"
        rhythmLock = true
        trackBpm = 120.0
        detectedBpm = 0.0
        ownerExtensionId = null
        lastPcmFlowMonoMs = 0L
        lastFiredAnyMonoMs = 0L
        lastFiredBassMonoMs = 0L
        lastFiredSnareMonoMs = 0L
        lastFiredVocalMonoMs = 0L
        pcmBeatsCount.set(0L)
        bassBeatsCount.set(0L)
        vocalBeatsCount.set(0L)
        bpmBeatsCount.set(0L)
        pulsesFiredCount.set(0L)
        monoClockMs = { System.nanoTime() / 1_000_000L }
    }

    /**
     * Cancels all pending vibrations associated with [sourceToken] (or everything when `0L`).
     * Called immediately on pause, seek/flush, player reset, or engine shutdown.
     */
    fun clearPending(sourceToken: Long = 0L) {
        val toRemove = ArrayList<ScheduledEvent>()
        synchronized(queueLock) {
            val it = pendingQueue.iterator()
            while (it.hasNext()) {
                val ev = it.next()
                if (sourceToken == 0L || ev.sourceToken == sourceToken) {
                    ev.cancelled = true
                    toRemove += ev
                    it.remove()
                }
            }
        }
        handler?.let { h ->
            if (sourceToken == 0L) {
                h.removeCallbacksAndMessages(null)
            } else {
                h.removeCallbacksAndMessages(sourceToken)
            }
            for (ev in toRemove) {
                ev.task?.let { h.removeCallbacks(it) }
            }
            if (enabled && mode == "pulse_bpm" && sourceToken == 0L) {
                val playing = runCatching { SongPlayer.isPlayingSafe() }.getOrDefault(false)
                if (playing) h.postDelayed(bpmTickRunnable, 220L)
            }
        }
    }

    /** Fires a single haptic pulse with [strength] in `1..255`, [kind], and optional [customDurationMs]. */
    fun pulse(strength: Int = 215, customDurationMs: Long? = null, kind: Int = KIND_BASS) {
        handler?.post {
            triggerBeatHaptic(strength.coerceIn(1, 255), kind.coerceIn(KIND_BASS, KIND_VOCAL), customDurationMs, force = true)
        }
    }

    /** Fires a 4-beat Bass + Vocal contrast demo pattern so the user can feel the difference immediately. */
    fun pulseTestPattern() {
        val h = handler ?: return
        clearPending(0L)
        h.post {
            val strengths = intArrayOf(230, 165, 245, 175)
            val kinds = intArrayOf(KIND_BASS, KIND_VOCAL, KIND_BASS, KIND_VOCAL)
            val offsets = longArrayOf(0L, 320L, 640L, 960L)
            for (i in strengths.indices) {
                val s = strengths[i]
                val k = kinds[i]
                h.postDelayed({ triggerBeatHaptic(s, k, force = true) }, offsets[i])
            }
        }
    }

    private fun triggerBeatHaptic(
        strength: Int,
        kind: Int = KIND_BASS,
        customDurationMs: Long? = null,
        force: Boolean = false,
    ) {
        if (!enabled && !force) return
        val vib = resolveVibrator() ?: return
        val now = monoClockMs()
        if (!force && lastFiredAnyMonoMs > 0L && (now - lastFiredAnyMonoMs) < 95L) return
        lastFiredAnyMonoMs = now
        when (kind) {
            KIND_BASS -> {
                lastFiredBassMonoMs = now
                if (!force) bassBeatsCount.incrementAndGet()
            }
            KIND_SNARE -> {
                lastFiredSnareMonoMs = now
                if (!force) bassBeatsCount.incrementAndGet()
            }
            else -> {
                lastFiredVocalMonoMs = now
                if (!force) vocalBeatsCount.incrementAndGet()
            }
        }

        val hwAmp = hasAmplitudeControl(vib)
        val useErmProfile = motor == "erm_strong" || (motor != "crisp" && !hwAmp)
        val isAccent = strength >= 200
        val isVocal = kind == KIND_VOCAL
        val isSnare = kind == KIND_SNARE

        val (durMs, amp) = when {
            useErmProfile -> {
                val base = when (kind) {
                    KIND_VOCAL -> when (intensity) {
                        "light" -> 28L
                        "strong" -> 42L
                        "max" -> 50L
                        else -> 34L
                    }
                    KIND_SNARE -> when (intensity) {
                        "light" -> 32L
                        "strong" -> 54L
                        "max" -> 68L
                        else -> 44L
                    }
                    else -> when (intensity) {
                        "light" -> 38L
                        "strong" -> 72L
                        "max" -> 90L
                        else -> 54L
                    }
                }
                val d = customDurationMs?.coerceAtLeast(28L) ?: if (!isVocal && isAccent) base + 10L else base
                d.coerceIn(26L, 120L) to -1
            }
            motor == "crisp" -> {
                val (scale, base) = when (intensity) {
                    "light" -> 0.55f to 18L
                    "strong" -> 1.10f to 36L
                    "max" -> 1.35f to 46L
                    else -> 0.85f to 26L
                }
                val shapedScale = when {
                    isVocal -> scale * 0.68f
                    isSnare -> scale * 0.88f
                    else -> scale
                }
                val shapedBase = when {
                    isVocal -> (base * 0.60f).roundToLong()
                    isSnare -> (base * 0.78f).roundToLong()
                    else -> if (isAccent) base + 6L else base
                }
                val d = customDurationMs ?: shapedBase
                val minA = if (isVocal) 60 else 85
                val maxA = if (isVocal) 185 else 255
                val a = (strength * shapedScale).roundToInt().coerceIn(minA, maxA)
                d.coerceIn(12L, if (isVocal) 28L else 70L) to a
            }
            else -> {
                val (scale, base, minAmp) = when (intensity) {
                    "light" -> Triple(0.70f, 24L, 115)
                    "strong" -> Triple(1.15f, 54L, 205)
                    "max" -> Triple(1.35f, 68L, 235)
                    else -> Triple(0.95f, 38L, 165)
                }
                val shapedScale = when {
                    isVocal -> scale * 0.72f
                    isSnare -> scale * 0.90f
                    else -> scale
                }
                val shapedBase = when {
                    isVocal -> (base * 0.60f).roundToLong()
                    isSnare -> (base * 0.78f).roundToLong()
                    else -> if (isAccent) base + 8L else base
                }
                val d = customDurationMs?.coerceAtLeast(18L) ?: shapedBase
                val minA = if (isVocal) 80 else minAmp
                val maxA = if (isVocal) 205 else 255
                val a = (strength * shapedScale).roundToInt().coerceIn(minA, maxA)
                d.coerceIn(16L, if (isVocal) 38L else 95L) to a
            }
        }

        pulsesFiredCount.incrementAndGet()
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val effect = if (!isVocal && !isSnare && intensity == "max" && isAccent && customDurationMs == null) {
                    if (useErmProfile) {
                        VibrationEffect.createWaveform(longArrayOf(0L, 52L, 26L, 38L), -1)
                    } else {
                        VibrationEffect.createWaveform(longArrayOf(0L, 42L, 22L, 30L), intArrayOf(0, 255, 0, 210), -1)
                    }
                } else {
                    VibrationEffect.createOneShot(
                        durMs,
                        if (amp < 0 || !hwAmp) VibrationEffect.DEFAULT_AMPLITUDE else amp,
                    )
                }
                vibrateWithChannel(vib, effect)
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(durMs)
            }
        }
    }

    private fun vibrateWithChannel(vib: Vibrator, effect: VibrationEffect) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val fired = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val usage = when (channel) {
                    "touch" -> VibrationAttributes.USAGE_TOUCH
                    "media" -> VibrationAttributes.USAGE_MEDIA
                    else -> VibrationAttributes.USAGE_ALARM
                }
                val attrs = VibrationAttributes.Builder().setUsage(usage).build()
                vib.vibrate(effect, attrs)
            } else {
                val usage = when (channel) {
                    "touch" -> AudioAttributes.USAGE_ASSISTANCE_SONIFICATION
                    "media" -> AudioAttributes.USAGE_MEDIA
                    else -> AudioAttributes.USAGE_ALARM
                }
                @Suppress("DEPRECATION")
                vib.vibrate(effect, audioAttrsFor(usage))
            }
            true
        }.getOrDefault(false)
        if (!fired) {
            runCatching { vib.vibrate(effect) }
        }
    }

    /** Handles JSON commands from `myspoti.haptics` in extensions. */
    fun handleExtensionCommand(extensionId: String, commandJson: String): String {
        val obj = runCatching { Json.parseToJsonElement(commandJson).jsonObject }.getOrNull()
            ?: return stateJson()
        val cmd = obj["cmd"]?.jsonPrimitive?.content ?: "state"
        when (cmd) {
            "configure" -> {
                val en = obj["enabled"]?.jsonPrimitive?.booleanOrNull ?: enabled
                val m = obj["mode"]?.jsonPrimitive?.content ?: mode
                val intns = obj["intensity"]?.jsonPrimitive?.content ?: intensity
                val sens = obj["sensitivity"]?.jsonPrimitive?.content ?: sensitivity
                val mot = obj["motor"]?.jsonPrimitive?.content ?: motor
                val ch = obj["channel"]?.jsonPrimitive?.content ?: channel
                val bpm = obj["bpm"]?.jsonPrimitive?.doubleOrNull
                val strict = obj["rhythmLock"]?.jsonPrimitive?.booleanOrNull ?: rhythmLock
                configure(en, m, intns, sens, mot, ch, bpm, strict, extensionId)
            }
            "pulse" -> {
                val s = obj["strength"]?.jsonPrimitive?.intOrNull ?: 215
                val d = obj["durationMs"]?.jsonPrimitive?.intOrNull?.toLong()
                val k = obj["kind"]?.jsonPrimitive?.intOrNull ?: KIND_BASS
                pulse(s, d, k)
            }
            "test" -> {
                pulseTestPattern()
            }
        }
        return stateJson()
    }

    private fun stateJson(): String {
        val vib = resolveVibrator()
        val hasVib = vib != null && runCatching { vib.hasVibrator() }.getOrDefault(false)
        val hasAmp = vib != null && hasAmplitudeControl(vib)
        val liveBpm = if (detectedBpm in 50.0..220.0 && mode != "pulse_bpm") detectedBpm else trackBpm
        return buildJsonObject {
            put("enabled", enabled)
            put("mode", mode)
            put("intensity", intensity)
            put("sensitivity", sensitivity)
            put("motor", motor)
            put("channel", channel)
            put("rhythmLock", rhythmLock)
            put("bpm", (liveBpm * 10.0).roundToInt() / 10.0)
            put("detectedBpm", (detectedBpm * 10.0).roundToInt() / 10.0)
            put("pcmFlowing", isPcmFlowingNow())
            put("hasVibrator", hasVib)
            put("hasAmplitudeControl", hasAmp)
            put("pcmBeats", pcmBeatsCount.get())
            put("bassBeats", bassBeatsCount.get())
            put("vocalBeats", vocalBeatsCount.get())
            put("bpmBeats", bpmBeatsCount.get())
            put("pulsesFired", pulsesFiredCount.get())
        }.toString()
    }
}
