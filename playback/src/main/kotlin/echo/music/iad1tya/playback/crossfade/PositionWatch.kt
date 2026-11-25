package echo.music.iad1tya.playback.crossfade

/**
 * Tells when a player's reported position can be believed. A player that has just started or
 * seeked holds its position where it was asked to start until its audio really comes out, often
 * for hundreds of ms: a beat offset measured then is that wait, not where the song is. The
 * position is trusted once it has moved with time for [STEADY_MS].
 */
class PositionWatch {
  private var lastPositionMs = -1L
  private var lastAtMs = 0L
  private var movingSinceMs = 0L

  fun reset() {
    lastPositionMs = -1L
    movingSinceMs = 0L
  }

  /** Feed every poll: [positionMs] as the player reports it, [nowMs] the wall clock. */
  fun steady(isPlaying: Boolean, positionMs: Long, nowMs: Long): Boolean {
    if (!isPlaying) {
      reset()
      return false
    }
    if (lastPositionMs < 0) {
      lastPositionMs = positionMs
      lastAtMs = nowMs
      return false
    }
    val elapsed = nowMs - lastAtMs
    if (elapsed < MIN_SAMPLE_MS) return movingSinceMs != 0L && nowMs - movingSinceMs >= STEADY_MS
    val moved = positionMs - lastPositionMs
    lastPositionMs = positionMs
    lastAtMs = nowMs
    // Moving at least at a third of real time (any blend speed) and not jumping back or ahead.
    val moving = moved * 3 >= elapsed && moved <= elapsed * 2
    if (!moving) {
      movingSinceMs = 0L
      return false
    }
    if (movingSinceMs == 0L) movingSinceMs = nowMs
    return nowMs - movingSinceMs >= STEADY_MS
  }

  companion object {
    const val MIN_SAMPLE_MS = 30L
    const val STEADY_MS = 150L
  }
}
