package app.myspoti.engine.android

import androidx.annotation.Keep
import java.io.File

/**
 * Native Rust bridge for `:engine-android` (`liborpheon_rust.so`):
 * - Boosts the `Media3DrmPlayer` playback looper thread to `ANDROID_PRIORITY_URGENT_AUDIO` (-19)
 * - Extracts the Widevine `pssh` box from MP4 init chunks in native Rust
 */
@Keep
internal object OrpheonRustBridge {
    val available: Boolean = runCatching {
        try {
            System.loadLibrary("orpheon_rust")
            true
        } catch (_: UnsatisfiedLinkError) {
            val candidates = listOf(
                File("../rust-audio/target/release/liborpheon_rust.so"),
                File("rust-audio/target/release/liborpheon_rust.so"),
                File("base/rust-audio/target/release/liborpheon_rust.so"),
                File("/root/orpheon-v1.0.0-Beta-opt.9-src/base/rust-audio/target/release/liborpheon_rust.so"),
            )
            val hostSo = candidates.firstOrNull { it.isFile }
            if (hostSo != null) {
                System.load(hostSo.absolutePath)
                true
            } else {
                false
            }
        }
    }.getOrDefault(false)

    fun boostAudioThread(): Int = if (available) runCatching { nativeBoostAudioThread() }.getOrDefault(-1) else -1

    fun extractWidevinePssh(initBytes: ByteArray): ByteArray? =
        if (available) runCatching { nativeExtractWidevinePssh(initBytes) }.getOrNull() else null

    @JvmStatic external fun nativeBoostAudioThread(): Int
    @JvmStatic external fun nativeExtractWidevinePssh(initBytes: ByteArray): ByteArray?
}
