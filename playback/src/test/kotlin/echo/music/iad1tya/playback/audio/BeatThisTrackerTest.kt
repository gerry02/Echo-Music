package echo.music.iad1tya.playback.audio

import java.io.File
import kotlin.math.abs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The analysis with Beat This! (the app's model, on the desktop ONNX Runtime). */
class BeatThisTrackerTest {

  @Before
  fun withTracker() {
    BeatAnalyzer.tracker = tracker
  }

  @After
  fun noTracker() {
    BeatAnalyzer.tracker = null
  }

  private fun gridError(ms: Double, anchor: Double, period: Double): Double {
    val offset = (((ms - anchor) % period) + period) % period
    return minOf(offset, period - offset)
  }

  @Test
  fun findsTheBeatAndTheBarsOnAFourOnTheFloorSongWithLoudOffbeats() {
    // The case the onset statistics could not decide: loud hats on every "and".
    val song = SyntheticSong(bpm = 124.0, firstDownbeatMs = 310.0, durationMs = 210_000, offbeatAccent = 0.7)
    val r = BeatAnalyzer.analyzePcm(song.windows())
    assertNotNull(r)
    r!!
    assertTrue("${r.phaseNote}", r.phaseNote.startsWith("beat this"))
    assertEquals(124f, r.bpm, 0.3f)
    assertTrue("confidence ${r.confidence}: ${r.phaseNote}", r.confidence >= 0.5f)
    assertTrue("beat ${r.firstBeatOffsetMs}", gridError(r.firstBeatOffsetMs.toDouble(), song.firstDownbeatMs, song.beatMs) <= 20.0)
    val tail = r.tailAnchorMs
    assertNotNull(tail)
    assertTrue("tail bar $tail", gridError(tail!!.toDouble(), song.firstDownbeatMs, song.barMs) <= 20.0)
  }

  @Test
  fun tracksTheTempoOfASlowerSong() {
    val song = SyntheticSong(bpm = 96.0, firstDownbeatMs = 120.0, durationMs = 190_000)
    val r = BeatAnalyzer.analyzePcm(song.windows())!!
    assertTrue("${r.bpm}", abs(r.bpm - 96f) < 0.3f || abs(r.bpm - 192f) < 0.6f)
  }

  companion object {
    private val tracker by lazy {
      val dir = File("src/main/assets/beatthis")
      BeatThisTracker(File(dir, "mel_spectrogram.onnx").readBytes(), File(dir, "beat_this_small.onnx").readBytes())
    }
  }
}
