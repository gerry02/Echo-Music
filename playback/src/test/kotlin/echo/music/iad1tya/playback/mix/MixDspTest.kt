package echo.music.iad1tya.playback.mix

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MixDspTest {

  private fun sine(hz: Double, rate: Int, seconds: Double, channels: Int = 2): FloatArray {
    val n = (rate * seconds).toInt()
    return FloatArray(n * channels) { i -> (0.5 * sin(2 * PI * hz * (i / channels) / rate)).toFloat() }
  }

  /** Feeds [x] in uneven blocks, the way a player delivers buffers. */
  private fun feed(x: FloatArray, channels: Int, process: (FloatArray, Int, Int) -> FloatArray): FloatArray {
    val out = ArrayList<FloatArray>()
    var f = 0
    val frames = x.size / channels
    var block = 333
    while (f < frames) {
      val n = minOf(block, frames - f)
      out += process(x, f * channels, n)
      f += n
      block = if (block == 333) 1024 else 333
    }
    return out.fold(FloatArray(0)) { acc, a -> acc + a }
  }

  private fun zeroCrossingHz(x: FloatArray, channels: Int, rate: Int, from: Int, to: Int): Double {
    var crossings = 0
    for (f in from + 1 until to) if ((x[(f - 1) * channels] < 0) != (x[f * channels] < 0)) crossings++
    return crossings / 2.0 / ((to - from).toDouble() / rate)
  }

  private fun rms(x: FloatArray, from: Int, to: Int): Double {
    var s = 0.0
    for (i in from until to) s += x[i].toDouble() * x[i]
    return sqrt(s / (to - from))
  }

  @Test
  fun resamplingKeepsPitchLevelAndTiming() {
    val r = Resampler(44_100, 48_000, 2)
    val input = sine(1_000.0, 44_100, 2.0)
    val out = feed(input, 2) { x, o, n -> r.process(x, o, n) }
    val frames = out.size / 2
    // Two seconds in, two seconds out (less the filter's lookahead).
    assertTrue("$frames", abs(frames - 96_000) < 40)
    assertEquals(1_000.0, zeroCrossingHz(out, 2, 48_000, 4_800, frames - 4_800), 2.0)
    assertEquals(rms(input, 9_000, input.size - 9_000), rms(out, 9_600, out.size - 9_600), 0.01)
  }

  @Test
  fun resamplingPutsASampleWhereItsTimeSays() {
    // An impulse at input frame 22050 (0.5 s) comes out at 0.5 s of the output.
    val r = Resampler(44_100, 48_000, 1)
    val input = FloatArray(44_100)
    input[22_050] = 1f
    val out = feed(input, 1) { x, o, n -> r.process(x, o, n) }
    val peak = out.indices.maxByOrNull { abs(out[it]) }!!
    assertTrue("peak at $peak", abs(peak - 24_000) <= 1)
  }

  /** Clicks every [periodMs] from [firstMs]: short 2 kHz bursts. */
  private fun clicks(rate: Int, seconds: Double, firstMs: Double, periodMs: Double, channels: Int = 2): FloatArray {
    val n = (rate * seconds).toInt()
    val x = FloatArray(n * channels)
    var t = firstMs
    while (t < seconds * 1000) {
      val start = (t * rate / 1000).toInt()
      for (i in 0 until rate / 200) {
        val f = start + i
        if (f >= n) break
        val v = (0.8 * sin(2 * PI * 2_000 * i / rate) * (1 - i / (rate / 200.0))).toFloat()
        for (c in 0 until channels) x[f * channels + c] = v
      }
      t += periodMs
    }
    return x
  }

  /** Onsets (frames) of bursts in [x]: where the level first rises above [threshold]. */
  private fun onsets(x: FloatArray, channels: Int, rate: Int, threshold: Float): List<Int> {
    val res = ArrayList<Int>()
    var quietSince = Int.MAX_VALUE / 2
    var lastLoud = -rate
    for (f in 0 until x.size / channels) {
      val v = abs(x[f * channels])
      if (v > threshold) {
        if (f - lastLoud > rate / 20) res += f
        lastLoud = f
      }
    }
    return res
  }

  @Test
  fun stretchingChangesTheTempoNotThePitch() {
    val rate = 48_000
    val st = TimeStretcher(2, rate).apply { speed = 1.05 }
    val input = sine(440.0, rate, 4.0)
    val out = feed(input, 2) { x, o, n -> st.process(x, o, n) }
    val frames = out.size / 2
    assertTrue("$frames", abs(frames - 4.0 / 1.05 * rate) < 0.05 * rate)
    assertEquals(440.0, zeroCrossingHz(out, 2, rate, 4_800, frames - 4_800), 3.0)
  }

  @Test
  fun stretchedBeatsKeepTheirNewSpacing() {
    val rate = 48_000
    val st = TimeStretcher(2, rate).apply { speed = 120.0 / 124.0 }
    // 124 BPM clicks played at 120 BPM: one every 500 ms.
    val input = clicks(rate, 8.0, 100.0, 60_000.0 / 124)
    val out = feed(input, 2) { x, o, n -> st.process(x, o, n) }
    val on = onsets(out, 2, rate, 0.3f)
    assertTrue("${on.size}", on.size >= 10)
    for (k in on.indices) {
      val expected = (100.0 / (120.0 / 124.0) + k * 500.0) * rate / 1000
      assertTrue("click $k at ${on[k]}, expected ${expected.toInt()}", abs(on[k] - expected) < 0.012 * rate)
    }
  }

  @Test
  fun exactModeIsTheInputSampleForSample() {
    val rate = 48_000
    val input = FloatArray(rate * 3 * 2) { i -> ((i * 7919 % 2001) - 1000) / 1000f }
    val st = TimeStretcher(2, rate, startFrame = 0).apply { speed = 0.97 }
    val first = st.process(input, 0, rate)
    st.speed = 1.0
    st.exact = true
    val q = st.exactInputPosition
    val out = st.process(input, rate * 2, rate * 2)
    // From q on, the output is the input itself (after the one splice frame already produced).
    val hop = (rate * 0.040).toInt() / 2
    for (i in hop until out.size / 2 - hop) {
      for (c in 0 until 2) {
        val expected = input[((q + i - hop + hop) * 2 + c).toInt()]
        assertEquals("frame $i", expected, out[i * 2 + c], 1e-4f)
      }
    }
    assertTrue(first.isNotEmpty())
  }
}
