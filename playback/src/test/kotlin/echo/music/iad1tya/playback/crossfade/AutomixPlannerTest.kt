package echo.music.iad1tya.playback.crossfade

import echo.music.iad1tya.playback.crossfade.AutomixPlanner.Style
import echo.music.iad1tya.playback.crossfade.AutomixPlanner.Track
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomixPlannerTest {

  /** 120 BPM: a beat is 500 ms, a bar 2 s; bars start at 250 + k * 2000. */
  private fun track(
    bpm: Float = 120f,
    durationMs: Long = 200_000,
    mixIn: Long? = 8_250,
    mixOut: Long? = 182_250,
    lastSound: Long? = 198_000,
    firstSound: Long? = 0,
    confidence: Float = 0.8f,
    anchor: Long = 250,
    trusted: Boolean = true,
  ) =
    Track(
      bpm = bpm,
      confidence = confidence,
      firstBeatOffsetMs = anchor,
      durationMs = durationMs,
      downbeatOffsetMs = anchor,
      headAnchorMs = anchor,
      tailAnchorMs = anchor,
      mixInMs = mixIn,
      mixOutMs = mixOut,
      firstSoundMs = firstSound,
      lastSoundMs = lastSound,
      gridTrusted = trusted,
    )

  private fun assertOnGrid(ms: Long, anchor: Double, period: Double, tolerance: Double = 2.0) {
    val off = (((ms - anchor) % period) + period) % period
    assertTrue("$ms is ${minOf(off, period - off)} ms off the grid", minOf(off, period - off) <= tolerance)
  }

  @Test
  fun closeTemposBeatMatchFromTheOutroOnABar() {
    val plan = AutomixPlanner.plan(track(), track(bpm = 124f), positionMs = 60_000, fadeMs = 5_000)!!
    assertEquals(Style.BEAT_MATCH, plan.style)
    assertEquals(182_250L, plan.triggerMs)
    assertEquals(120f / 124f, plan.incomingSpeed, 0.0001f)
    // The blend ends before the outgoing song goes silent.
    assertTrue(plan.triggerMs + plan.overlapMs <= 198_000)
    // A whole number of bars.
    assertEquals(0.0, (plan.overlapMs / 2000.0) % 1.0, 0.001)
  }

  @Test
  fun incomingIntroPlaysUnderTheOutroAndItsBodyArrivesAsTheBlendEnds() {
    val incoming = track(bpm = 120f, mixIn = 16_250)
    val plan = AutomixPlanner.plan(track(), incoming, positionMs = 60_000, fadeMs = 5_000)!!
    assertEquals(Style.BEAT_MATCH, plan.style)
    // 16 s blend at speed 1: the incoming body (16.25 s) lands at the blend end.
    assertEquals(16_250L - plan.overlapMs, plan.incomingStartMs)
    assertOnGrid(plan.incomingStartMs, 250.0, 2000.0)
  }

  @Test
  fun beatsLineUpThroughTheWholeBlend() {
    val out = track(bpm = 126f, anchor = 410, mixOut = null, lastSound = 199_500)
    val incoming = track(bpm = 122f, anchor = 95, mixIn = 15_833)
    val plan = AutomixPlanner.plan(out, incoming, positionMs = 30_000, fadeMs = 5_000)!!
    assertEquals(Style.BEAT_MATCH, plan.style)
    val outBar = 60_000.0 / 126 * 4
    val inBar = 60_000.0 / 122 * 4
    assertOnGrid(plan.triggerMs, 410.0, outBar, 1.0)
    assertOnGrid(plan.incomingStartMs, 95.0, inBar, 1.0)
    // At every outgoing beat of the blend the incoming song is on a beat too.
    var t = 0.0
    while (t <= plan.overlapMs) {
      val inPos = plan.incomingStartMs + t * plan.incomingSpeed
      assertOnGrid(inPos.toLong(), 95.0, 60_000.0 / 122, 2.0)
      t += 60_000.0 / 126
    }
  }

  @Test
  fun noOutroMeansTheLatestWholeBlendBeforeTheEnd() {
    val plan =
      AutomixPlanner.plan(track(mixOut = null), track(), positionMs = 60_000, fadeMs = 5_000)!!
    assertEquals(Style.BEAT_MATCH, plan.style)
    assertTrue(plan.triggerMs + plan.overlapMs <= 198_000)
    assertTrue(plan.triggerMs + plan.overlapMs > 198_000 - 2_000)
    assertOnGrid(plan.triggerMs, 250.0, 2000.0)
  }

  @Test
  fun farTemposFallBackToASmoothCrossfadeAtTheOutro() {
    val plan = AutomixPlanner.plan(track(), track(bpm = 90f), positionMs = 60_000, fadeMs = 6_000)!!
    assertEquals(Style.SMOOTH, plan.style)
    assertEquals(1f, plan.incomingSpeed)
    assertEquals(182_250L, plan.triggerMs)
    assertEquals(6_000L, plan.overlapMs)
  }

  @Test
  fun octaveTemposStillMatch() {
    val plan = AutomixPlanner.plan(track(bpm = 140f), track(bpm = 71f), 60_000, 5_000)!!
    assertEquals(Style.BEAT_MATCH, plan.style)
    assertEquals(140f / 142f, plan.incomingSpeed, 0.0001f)
  }

  @Test
  fun weakBeatOrUnmeasuredGridGoesSmooth() {
    assertEquals(
      Style.SMOOTH,
      AutomixPlanner.plan(track(), track(confidence = 0.1f), 60_000, 5_000)!!.style,
    )
    assertEquals(
      Style.SMOOTH,
      AutomixPlanner.plan(track(), track(trusted = false), 60_000, 5_000)!!.style,
    )
  }

  @Test
  fun smoothSkipsTrailingAndLeadingSilence() {
    val out = track(confidence = 0f, mixOut = null, lastSound = 190_000)
    val plan = AutomixPlanner.plan(out, track(firstSound = 1_200), 60_000, 5_000)!!
    assertEquals(Style.SMOOTH, plan.style)
    assertEquals(185_000L, plan.triggerMs)
    assertEquals(1_200L, plan.incomingStartMs)
  }

  @Test
  fun lateReplanShrinksTheBlendInWholeBars() {
    val plan = AutomixPlanner.plan(track(), track(), positionMs = 191_000, fadeMs = 5_000)!!
    assertEquals(Style.BEAT_MATCH, plan.style)
    assertTrue(plan.triggerMs >= 192_000)
    assertTrue(plan.triggerMs + plan.overlapMs <= 199_700)
    assertEquals(0.0, (plan.overlapMs / 2000.0) % 1.0, 0.001)
  }

  @Test
  fun tooLateForAnything() {
    assertNull(AutomixPlanner.plan(track(), track(), positionMs = 198_800, fadeMs = 5_000))
  }

  @Test
  fun clashingKeysKeepTheBlendShort() {
    val a = track(mixOut = 166_250).copy(keyPitchClass = 0, keyIsMinor = false) // C
    val b = track(mixIn = 18_250).copy(keyPitchClass = 6, keyIsMinor = false) // F#
    assertFalse(AutomixPlanner.keysCompatible(a, b)!!)
    val plan = AutomixPlanner.plan(a, b, 60_000, 5_000)!!
    assertTrue(plan.overlapMs <= 8_000)
    // A minor and C major are relative keys.
    assertTrue(
      AutomixPlanner.keysCompatible(
        a,
        track().copy(keyPitchClass = 9, keyIsMinor = true),
      )!!
    )
  }

  @Test
  fun djMixSwapsBassOnABarAndKeepsLoudnessInCheck() {
    val plan = AutomixPlanner.plan(track(), track(), 60_000, 5_000)!!
    val beats = Math.round(plan.overlapMs / 500.0)
    assertEquals(0.0, (plan.bassSwapAt * beats / 4.0) % 1.0, 0.001)
    val start = AutomixPlanner.mixAt(plan, 0f)
    assertEquals(1f, start.outGain, 0.001f)
    assertEquals(0f, start.inGain, 0.001f)
    assertEquals(1f, start.inBassCut, 0.001f)
    assertEquals(0f, start.outBassCut, 0.001f)
    val end = AutomixPlanner.mixAt(plan, 1f)
    assertEquals(0f, end.outGain, 0.001f)
    assertEquals(1f, end.inGain, 0.001f)
    assertEquals(0f, end.inBassCut, 0.001f)
    assertEquals(1f, end.outBassCut, 0.001f)
    for (i in 0..100) {
      val m = AutomixPlanner.mixAt(plan, i / 100f)
      val power = m.outGain * m.outGain + m.inGain * m.inGain
      assertTrue("power $power at $i", power in 0.7f..1.41f)
    }
  }

  @Test
  fun tempoReturnsSmoothlyAfterTheBlend() {
    val plan = AutomixPlanner.plan(track(), track(bpm = 124f), 60_000, 5_000)!!
    assertNotNull(plan)
    assertEquals(plan.incomingSpeed, AutomixPlanner.restoreSpeedAt(plan, 0), 0.0001f)
    assertEquals(1f, AutomixPlanner.restoreSpeedAt(plan, plan.restoreMs), 0.0001f)
    var last = AutomixPlanner.restoreSpeedAt(plan, 0)
    for (t in 0..plan.restoreMs step 250) {
      val s = AutomixPlanner.restoreSpeedAt(plan, t)
      assertTrue(abs(s - last) < 0.003f)
      last = s
    }
  }
}
