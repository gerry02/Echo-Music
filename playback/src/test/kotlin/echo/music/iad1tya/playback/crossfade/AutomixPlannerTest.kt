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
    // The blend ends before the outgoing stream does.
    assertTrue(plan.triggerMs + plan.overlapMs <= 199_700)
    // Close tempos get the long blend: 8 bars.
    assertEquals(16_000L, plan.overlapMs)
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
      assertTrue("power $power at $i", power in 0.95f..1.001f)
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

  @Test
  fun sixMinuteSongNeverMixesOutMidSong() {
    // A file cut short at 3:02 once made the analysis think the song ended there.
    val out = track(durationMs = 360_000, mixOut = 180_250, lastSound = 182_000)
    for (incoming in listOf(track(), track(bpm = 90f), null)) {
      val plan = AutomixPlanner.plan(out, incoming, positionMs = 30_000, fadeMs = 6_000)!!
      assertTrue("${plan.style} at ${plan.triggerMs}", plan.triggerMs >= 300_000)
      assertTrue(plan.triggerMs + plan.overlapMs <= 359_700)
    }
    // A real outro in the last minute is used.
    val real = AutomixPlanner.plan(track(durationMs = 360_000, mixOut = 330_250, lastSound = 358_000), track(), 30_000, 6_000)!!
    assertEquals(330_250L, real.triggerMs)
  }

  @Test
  fun shortSongKeepsItsLastQuarterRule() {
    val out = track(durationMs = 120_000, mixOut = 40_250, lastSound = 118_000)
    val plan = AutomixPlanner.plan(out, track(), positionMs = 10_000, fadeMs = 5_000)!!
    assertTrue(plan.triggerMs >= 90_000)
  }

  @Test
  fun bassSwapLandsOnAPhraseBoundary() {
    // 4-bar blend (clashing keys): swap after 2 bars; 8-bar blend: after 4 bars.
    val a = track().copy(keyPitchClass = 0, keyIsMinor = false)
    val b = track().copy(keyPitchClass = 6, keyIsMinor = false)
    val short = AutomixPlanner.plan(a, b, 60_000, 5_000)!!
    assertEquals(8f / 16f, short.bassSwapAt, 0.0001f)
    val long = AutomixPlanner.plan(track(), track(), 60_000, 5_000)!!
    assertEquals(16f / 32f, long.bassSwapAt, 0.0001f)
    // The incoming song is held thin until the swap, then opens.
    assertTrue(AutomixPlanner.mixAt(long, 0.2f).inHighPass > 0.5f)
    assertEquals(0f, AutomixPlanner.mixAt(long, 0.7f).inHighPass, 0.001f)
    // It opens gradually, not in one step at the swap.
    val before = AutomixPlanner.mixAt(long, 0.45f).inHighPass
    assertTrue("$before", before > 0.05f && before < 0.55f)
    // The basslines cross over a bar: halfway through it both are half cut.
    val mid = AutomixPlanner.mixAt(long, 0.5f + 2f / 32f)
    assertEquals(0.5f, mid.outBassCut, 0.05f)
    assertEquals(0.5f, mid.inBassCut, 0.05f)
  }

  @Test
  fun phaseErrorWrapsToTheNearestBeat() {
    val period = 60_000.0 / 140 // 428.6 ms
    // The phone log: 391 ms behind is 37.6 ms ahead of the previous beat.
    assertEquals(37.6, AutomixPlanner.wrapPhaseError(-391.0, period), 0.1)
    assertEquals(-20.0, AutomixPlanner.wrapPhaseError(-20.0, period), 1e-9)
    assertEquals(15.0, AutomixPlanner.wrapPhaseError(15.0 + 2 * period, period), 1e-6)
    assertEquals(-100.0, AutomixPlanner.wrapPhaseError(period - 100.0, period), 1e-6)
    // Never more than half a beat either way.
    for (e in -2000..2000 step 7) {
      val w = AutomixPlanner.wrapPhaseError(e.toDouble(), period)
      assertTrue("$e -> $w", w > -period / 2 - 1e-9 && w <= period / 2 + 1e-9)
    }
  }

  @Test
  fun aBlendStillOffTheBeatWhenHeardFinishesFast() {
    // Small offsets are fine; a big one is tolerated only while the next song is not heard yet.
    assertFalse(AutomixPlanner.shouldBail(20.0, 0.5f))
    assertFalse(AutomixPlanner.shouldBail(-150.0, 0.05f))
    assertTrue(AutomixPlanner.shouldBail(-150.0, AutomixPlanner.PHASE_AUDIBLE_AT))
    assertTrue(AutomixPlanner.shouldBail(31.0, 0.3f))
    // The rest of the blend then takes at most BAIL_BLEND_MS.
    assertEquals(0.3f, AutomixPlanner.bailProgress(0.3f, 0), 1e-6f)
    assertEquals(0.55f, AutomixPlanner.bailProgress(0.3f, AutomixPlanner.BAIL_BLEND_MS / 4), 1e-4f)
    assertEquals(1f, AutomixPlanner.bailProgress(0.3f, AutomixPlanner.BAIL_BLEND_MS), 0f)
  }

  @Test
  fun aSongIsNeverStartedHalfwayThrough() {
    // A 6-minute next song whose analysis put the end of its intro 3 minutes in.
    val next = track(durationMs = 360_000, mixIn = 180_250, firstSound = 0)
    val plan = AutomixPlanner.plan(track(), next, positionMs = 60_000, fadeMs = 5_000)!!
    assertTrue("starts at ${plan.incomingStartMs}", plan.incomingStartMs <= AutomixPlanner.MAX_MIX_IN_MS)
    // Same for a first sound far in.
    val late = track(durationMs = 360_000, mixIn = null, firstSound = 200_000)
    val p2 = AutomixPlanner.plan(track(), late, positionMs = 60_000, fadeMs = 5_000)!!
    assertTrue("starts at ${p2.incomingStartMs}", p2.incomingStartMs <= AutomixPlanner.MAX_MIX_IN_MS)
    // And the 6-minute song itself is not left at 3 minutes.
    val six = track(durationMs = 360_000, mixOut = 180_250, lastSound = 358_000)
    val p3 = AutomixPlanner.plan(six, track(), positionMs = 60_000, fadeMs = 5_000)!!
    assertTrue("leaves at ${p3.triggerMs}", p3.triggerMs >= 300_000)
  }

  @Test
  fun smoothWithBeatsThinsTheDrumsButBalladsStayClean() {
    val beats = AutomixPlanner.plan(track(), track(bpm = 90f), 60_000, 6_000)!!
    assertTrue(beats.filters)
    assertTrue(AutomixPlanner.mixAt(beats, 0.9f).outLowPass > 0.3f)
    // Highs leave with the outgoing song: no high-pass that would leave only its hi-hats.
    assertEquals(0f, AutomixPlanner.mixAt(beats, 0.9f).outHighPass, 0f)
    val ballad = AutomixPlanner.plan(track(confidence = 0f), track(confidence = 0f), 60_000, 6_000)!!
    assertFalse(ballad.filters)
    assertEquals(0f, AutomixPlanner.mixAt(ballad, 0.9f).outLowPass, 0f)
  }

  @Test
  fun bestNextIsMovedUpOnlyWhenClearlyBetter() {
    val out = track(bpm = 124f).copy(keyPitchClass = 9, keyIsMinor = true) // A minor
    val far = track(bpm = 92f)
    val close = track(bpm = 125f).copy(keyPitchClass = 0, keyIsMinor = false) // C major
    // The next one only crossfades; the third beat-matches in a relative key.
    assertEquals(2, AutomixPlanner.pickBestNext(out, listOf(far, null, close, far)))
    // The next one already beat-matches: the order is kept.
    assertNull(AutomixPlanner.pickBestNext(out, listOf(track(bpm = 122f), close)))
    // Nothing better: kept.
    assertNull(AutomixPlanner.pickBestNext(out, listOf(far, null, far)))
  }
}
