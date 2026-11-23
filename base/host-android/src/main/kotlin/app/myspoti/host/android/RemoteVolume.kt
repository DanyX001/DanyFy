package app.myspoti.host.android

/** Hardware volume stepping math for remote Spotify Connect / Cast playback (`0..100`). */
object RemoteVolume {
    const val STEP = 5

    /** Steps [current] (`0..100`) up or down according to [direction] (`>0`, `<0`, or `0`). */
    fun step(current: Int, direction: Int, stepSize: Int = STEP): Int {
        val delta = when {
            direction > 0 -> stepSize
            direction < 0 -> -stepSize
            else -> 0
        }
        return (current + delta).coerceIn(0, 100)
    }
}
