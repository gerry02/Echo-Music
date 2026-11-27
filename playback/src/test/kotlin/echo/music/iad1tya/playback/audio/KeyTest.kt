package echo.music.iad1tya.playback.audio

import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Test

class KeyTest {

  /** Notes (MIDI numbers, weights) held together for [seconds], with a few harmonics each. */
  private fun chord(notes: List<Pair<Int, Double>>, seconds: Double, rate: Int = 44_100): MonoPcm {
    val n = (rate * seconds).toInt()
    val x = FloatArray(n)
    for ((midi, w) in notes) {
      val f = 440.0 * 2.0.pow((midi - 69) / 12.0)
      for (h in 1..4) for (i in 0 until n) x[i] += (w / h * 0.1 * sin(2 * PI * f * h * i / rate)).toFloat()
    }
    return MonoPcm(x, rate)
  }

  @Test
  fun findsTheKeyOfAScaleAndItsChords() {
    // C major: the scale, the tonic triad stronger.
    val cMajor = chord(listOf(60 to 1.5, 62 to 0.6, 64 to 1.2, 65 to 0.6, 67 to 1.3, 69 to 0.6, 71 to 0.5, 48 to 1.0), 12.0)
    assertEquals(0 to false, BeatDsp.keyOf(cMajor))
    // Every pitch class may win: the same shifted up a fourth is F major.
    val fMajor = chord(listOf(65 to 1.5, 67 to 0.6, 69 to 1.2, 70 to 0.6, 72 to 1.3, 74 to 0.6, 76 to 0.5, 53 to 1.0), 12.0)
    assertEquals(5 to false, BeatDsp.keyOf(fMajor))
    // A minor: its tonic triad and the natural minor scale.
    val aMinor = chord(listOf(57 to 1.6, 60 to 1.2, 64 to 1.2, 62 to 0.5, 65 to 0.5, 67 to 0.4, 59 to 0.4, 45 to 1.2), 12.0)
    assertEquals(9 to true, BeatDsp.keyOf(aMinor))
  }
}
