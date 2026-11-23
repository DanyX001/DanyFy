package app.danyfy.shared.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.danyfy.shared.platform.PlatformAudioCallbacks
import app.danyfy.shared.platform.PlatformRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class PlayerController(
    private val ytmApi: YtmApi,
    private val libraryStore: LibraryStore,
    private val registry: PlatformRegistry = PlatformRegistry,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : PlatformAudioCallbacks {

    var currentTrack by mutableStateOf<TrackItem?>(null)
        private set
    var isPlaying by mutableStateOf(false)
        private set
    var isBuffering by mutableStateOf(false)
        private set
    var positionMs by mutableLongStateOf(0L)
        private set
    var durationMs by mutableLongStateOf(0L)
        private set
    var progress by mutableFloatStateOf(0f)
        private set

    val queue = mutableStateListOf<TrackItem>()
    var queueIndex by mutableIntStateOf(0)
        private set

    var shuffleOn by mutableStateOf(false)
        private set
    // 0 = off, 1 = repeat all, 2 = repeat one
    var repeatMode by mutableIntStateOf(0)
        private set

    var errorMessage by mutableStateOf<String?>(null)
        private set

    private var activeCandidates = emptyList<AudioStreamCandidate>()
    private var candidateIndex = 0

    init {
        registry.audioCallbacks = this
    }

    fun playTrack(track: TrackItem, newQueue: List<TrackItem> = emptyList()) {
        currentTrack = track
        errorMessage = null
        positionMs = 0L
        durationMs = track.durationSeconds * 1000L
        progress = 0f
        isBuffering = true
        isPlaying = false

        libraryStore.addRecent(track)

        if (newQueue.isNotEmpty()) {
            queue.clear()
            queue.addAll(newQueue)
            val idx = queue.indexOfFirst { it.id == track.id }
            queueIndex = if (idx >= 0) idx else 0
        } else if (queue.isEmpty() || queue.none { it.id == track.id }) {
            queue.clear()
            queue.add(track)
            queueIndex = 0
        }

        resolveAndStream(track)

        // If queue is small, fetch radio queue in background
        if (queue.size <= 2 && track.id.isNotBlank()) {
            fetchRadioQueue(track.id)
        }
    }

    private fun resolveAndStream(track: TrackItem) {
        scope.launch {
            try {
                isBuffering = true
                val candidates = ytmApi.resolveStreams(track.id)
                if (candidates.isEmpty()) {
                    isBuffering = false
                    errorMessage = "Unable to resolve playable audio stream"
                    return@launch
                }
                activeCandidates = candidates
                candidateIndex = 0
                startPlaybackWithCandidate(track, candidates[0])
            } catch (t: Throwable) {
                isBuffering = false
                errorMessage = "Network error: ${t.message}"
            }
        }
    }

    private fun startPlaybackWithCandidate(track: TrackItem, candidate: AudioStreamCandidate) {
        val headers = mapOf("User-Agent" to candidate.userAgent)
        registry.audio.playStream(
            url = candidate.url,
            headers = headers,
            trackId = track.id,
            title = track.title,
            artist = track.artist,
            album = track.album,
            artworkUrl = track.artworkUrl,
            durationMs = if (durationMs > 0) durationMs else track.durationSeconds * 1000L,
        )
    }

    fun togglePlayPause() {
        if (isPlaying) {
            registry.audio.pause()
            isPlaying = false
        } else {
            if (currentTrack != null) {
                registry.audio.resume()
                isPlaying = true
            } else if (queue.isNotEmpty()) {
                playTrack(queue[0])
            }
        }
    }

    fun next() {
        if (queue.isEmpty()) return
        if (repeatMode == 2 && currentTrack != null) {
            seekTo(0f)
            return
        }
        val nextIdx = queueIndex + 1
        if (nextIdx < queue.size) {
            queueIndex = nextIdx
            playTrack(queue[nextIdx])
        } else if (repeatMode == 1 && queue.isNotEmpty()) {
            queueIndex = 0
            playTrack(queue[0])
        }
    }

    fun previous() {
        if (queue.isEmpty()) return
        if (positionMs > 4000L) {
            seekTo(0f)
            return
        }
        val prevIdx = queueIndex - 1
        if (prevIdx >= 0) {
            queueIndex = prevIdx
            playTrack(queue[prevIdx])
        } else {
            seekTo(0f)
        }
    }

    fun seekTo(fraction: Float) {
        val targetMs = (fraction.coerceIn(0f, 1f) * (if (durationMs > 0) durationMs else 1L)).toLong()
        progress = fraction
        positionMs = targetMs
        registry.audio.seekTo(targetMs)
    }

    fun toggleShuffle() {
        shuffleOn = !shuffleOn
        if (shuffleOn && queue.size > 1) {
            val current = currentTrack
            val remaining = queue.filter { it.id != current?.id }.shuffled()
            queue.clear()
            if (current != null) queue.add(current)
            queue.addAll(remaining)
            queueIndex = 0
        }
    }

    fun cycleRepeat() {
        repeatMode = (repeatMode + 1) % 3
    }

    private fun fetchRadioQueue(videoId: String) {
        scope.launch {
            try {
                val radio = ytmApi.nextRadio(videoId)
                val newTracks = radio.filter { r -> queue.none { it.id == r.id } }
                if (newTracks.isNotEmpty()) {
                    queue.addAll(newTracks)
                }
            } catch (_: Throwable) {}
        }
    }

    // PlatformAudioCallbacks implementation
    override fun onPlaybackStateChanged(isPlaying: Boolean, isBuffering: Boolean) {
        this.isPlaying = isPlaying
        this.isBuffering = isBuffering
    }

    override fun onPositionDiscontinuity(positionMs: Long, durationMs: Long) {
        this.positionMs = positionMs
        if (durationMs > 0) {
            this.durationMs = durationMs
            this.progress = (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
        }
    }

    override fun onTrackEnded() {
        next()
    }

    override fun onPlaybackError(message: String) {
        // Fallback to next candidate if available
        val nextCand = candidateIndex + 1
        if (nextCand < activeCandidates.size && currentTrack != null) {
            candidateIndex = nextCand
            startPlaybackWithCandidate(currentTrack!!, activeCandidates[nextCand])
        } else {
            isBuffering = false
            isPlaying = false
            errorMessage = message
        }
    }
}
