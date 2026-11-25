package echo.music.dsp.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@androidx.annotation.OptIn(UnstableApi::class)
class AutomixDuckAudioProcessorTest {

  private val rate = 44_100

  private fun stereoSine(hz: Double, seconds: Double, startSample: Int = 0): ShortArray {
    val n = (rate * seconds).toInt()
    val out = ShortArray(n * 2)
    for (i in 0 until n) {
      val v = (0.5 * sin(2 * PI * hz * (startSample + i) / rate) * 32767).toInt().toShort()
      out[2 * i] = v
      out[2 * i + 1] = v
    }
    return out
  }

  private fun run(p: AutomixDuckAudioProcessor, input: ShortArray): ShortArray {
    val buf = ByteBuffer.allocateDirect(input.size * 2).order(ByteOrder.nativeOrder())
    buf.asShortBuffer().put(input)
    p.queueInput(buf)
    val out = p.getOutput()
    val shorts = ShortArray(out.remaining() / 2)
    out.asShortBuffer().get(shorts)
    return shorts
  }

  private fun rms(x: ShortArray, from: Int = 0): Double {
    var s = 0.0
    for (i in from until x.size) s += x[i].toDouble() * x[i]
    return sqrt(s / (x.size - from))
  }

  private fun processor() =
    AutomixDuckAudioProcessor().apply {
      configure(AudioProcessor.AudioFormat(rate, 2, C.ENCODING_PCM_16BIT))
    }

  @Test
  fun untouchedWhenIdle() {
    val input = stereoSine(60.0, 0.5)
    val output = run(processor(), input)
    for (i in input.indices) assertTrue(abs(input[i] - output[i]) <= 1)
  }

  @Test
  fun highPassSweepThinsTheBassButKeepsTheMids() {
    val bass = processor().apply { setHighPass(1f) }
    // Let the per-sample ease settle, then measure.
    run(bass, stereoSine(60.0, 0.5))
    val bassOut = run(bass, stereoSine(60.0, 0.5, startSample = rate / 2))
    assertTrue("60 Hz rms ${rms(bassOut)}", rms(bassOut) < 0.1 * 0.5 * 32767 / sqrt(2.0))

    val mids = processor().apply { setHighPass(1f) }
    run(mids, stereoSine(4_000.0, 0.5))
    val midOut = run(mids, stereoSine(4_000.0, 0.5, startSample = rate / 2))
    assertEquals(0.5 * 32767 / sqrt(2.0), rms(midOut), 0.05 * 32767)
  }

  @Test
  fun sweepingTheCutoffDoesNotClick() {
    val p = processor()
    val chunk = rate / 100 // 10 ms chunks while the cutoff moves
    var maxJump = 0
    var prev: Short? = null
    var sample = 0
    for (step in 0..100) {
      p.setHighPass(step / 100f)
      val out = run(p, stereoSine(200.0, 0.01, startSample = sample))
      sample += chunk
      for (i in out.indices step 2) {
        prev?.let { maxJump = maxOf(maxJump, abs(out[i] - it)) }
        prev = out[i]
      }
    }
    // A 200 Hz sine at half scale moves at most ~470 per sample; a click would be far more.
    assertTrue("max step $maxJump", maxJump < 800)
  }
}
