package echo.music.iad1tya.playback.audio

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BeatAnalyzerTest {

  /** Distance of [ms] from the nearest point of a grid, in ms. */
  private fun gridError(ms: Long, anchor: Double, period: Double): Double {
    val offset = (((ms - anchor) % period) + period) % period
    return minOf(offset, period - offset)
  }

  private fun assertOnBar(label: String, ms: Long?, song: SyntheticSong, toleranceMs: Double = 15.0) {
    assertNotNull("$label missing", ms)
    val error = gridError(ms!!, song.firstDownbeatMs + song.shiftMs, song.barMs)
    assertTrue("$label=$ms is ${"%.1f".format(error)} ms off a downbeat", error <= toleranceMs)
  }

  @Test
  fun findsTempoBeatsAndBarsAt120() {
    val song = SyntheticSong(bpm = 120.0, firstDownbeatMs = 250.0, durationMs = 200_000)
    val r = BeatAnalyzer.analyzePcm(song.windows())!!

    assertEquals(120f, r.bpm, 0.3f)
    assertTrue("confidence ${r.confidence}", r.confidence > 0.3f)
    assertTrue(gridError(r.firstBeatOffsetMs, song.firstDownbeatMs, song.beatMs) <= 15.0)
    assertOnBar("downbeat", r.downbeatOffsetMs, song)
    assertOnBar("head anchor", r.headAnchorMs, song)
    assertOnBar("tail anchor", r.tailAnchorMs, song)
  }

  @Test
  fun gridHoldsAtTheEdgesForAnAwkwardTempo() {
    // 128 BPM is not a whole number of analysis frames per beat: an extrapolated grid drifts.
    val song = SyntheticSong(bpm = 128.0, firstDownbeatMs = 730.0, durationMs = 245_000)
    val r = BeatAnalyzer.analyzePcm(song.windows())!!

    assertEquals(128f, r.bpm, 0.2f)
    assertOnBar("head anchor", r.headAnchorMs, song)
    assertOnBar("tail anchor", r.tailAnchorMs, song)
    // The stored grid itself is good near the end too, 3+ minutes from where it was measured.
    val period = 60_000.0 / r.bpm
    val tailBeat = song.bodyEndMs - 4 * song.barMs
    val predicted =
      r.firstBeatOffsetMs + Math.round((tailBeat - r.firstBeatOffsetMs) / period) * period
    assertTrue("grid drifted ${abs(predicted - tailBeat)} ms", abs(predicted - tailBeat) <= 25.0)
  }

  @Test
  fun findsTheIntroEndOnADownbeat() {
    val song = SyntheticSong(bpm = 120.0, firstDownbeatMs = 250.0, durationMs = 200_000, introBars = 4)
    val r = BeatAnalyzer.analyzePcm(song.windows())!!
    assertNotNull(r.mixInPointMs)
    assertEquals(song.bodyStartMs, r.mixInPointMs!!.toDouble(), 20.0)
  }

  @Test
  fun hotStartHasNoIntro() {
    val song = SyntheticSong(bpm = 100.0, firstDownbeatMs = 0.0, durationMs = 180_000, introBars = 0)
    val r = BeatAnalyzer.analyzePcm(song.windows())!!
    assertNull(r.mixInPointMs)
    assertOnBar("head anchor", r.headAnchorMs, song)
  }

  @Test
  fun findsTheOutroStartAndTheLastSound() {
    val song = SyntheticSong(bpm = 120.0, firstDownbeatMs = 250.0, durationMs = 200_000)
    val r = BeatAnalyzer.analyzePcm(song.windows())!!
    assertNotNull(r.mixOutPointMs)
    assertEquals(song.bodyEndMs, r.mixOutPointMs!!.toDouble(), 20.0)
    assertNotNull(r.lastSoundMs)
    assertEquals(song.soundEndMs, r.lastSoundMs!!.toDouble(), 60.0)
    assertEquals(0.0, r.firstSoundMs!!.toDouble(), 60.0)
  }

  @Test
  fun probeMeasuresAShiftedStreamWithAKnownTempo() {
    // The same song as another file that starts 180 ms later.
    val song =
      SyntheticSong(bpm = 120.0, firstDownbeatMs = 250.0, durationMs = 200_000, shiftMs = 180.0)
    val w = song.windows()
    val r =
      BeatAnalyzer.analyzePcm(
        BeatAnalyzer.Windows(durationMs = w.durationMs, head = w.head, headStartMs = 0L),
        knownBpm = 120f,
      )!!
    assertOnBar("probed head anchor", r.headAnchorMs, song)
    assertEquals(song.bodyStartMs + 180.0, r.mixInPointMs!!.toDouble(), 20.0)
  }
}
