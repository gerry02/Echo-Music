package echo.music.iad1tya.playback.crossfade

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PositionWatchTest {

  @Test
  fun aPositionHeldAtTheSeekTargetIsNotBelieved() {
    // The phone log: after a seek to 261 ms the player kept reporting 261 ms.
    val w = PositionWatch()
    for (t in 0L..600L step 20) assertFalse("at $t", w.steady(true, 261L, t))
  }

  @Test
  fun aPositionMovingWithTimeIsBelievedAfterAMoment() {
    val w = PositionWatch()
    var steadyAt = -1L
    for (t in 0L..600L step 20) {
      // Held for 300 ms, then plays.
      val pos = 261L + maxOf(0L, t - 300)
      if (w.steady(true, pos, t) && steadyAt < 0) steadyAt = t
    }
    assertTrue("steady at $steadyAt", steadyAt in 440L..520L)
  }

  @Test
  fun aSeekOrAPauseStartsOver() {
    val w = PositionWatch()
    for (t in 0L..400L step 20) w.steady(true, t, t)
    assertTrue(w.steady(true, 420L, 420L))
    // A jump (seek) is not steady.
    assertFalse(w.steady(true, 5_000L, 460L))
    // Paused: not steady, and moving again needs the full wait.
    assertFalse(w.steady(false, 5_000L, 480L))
    assertFalse(w.steady(true, 5_000L, 500L))
    assertFalse(w.steady(true, 5_040L, 540L))
  }
}
