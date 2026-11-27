package echo.music.iad1tya.playback.audio

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import echo.music.iad1tya.playback.mix.Resampler
import java.io.Closeable
import java.nio.FloatBuffer
import java.nio.LongBuffer

/** Where a stretch of audio has a voice (singing, rap), as ms ranges from its start. */
fun interface VocalDetector {
  fun vocals(pcm: MonoPcm): List<LongRange>
}

/**
 * Silero VAD (MIT) on ONNX Runtime: a small voice activity model, 32 ms steps at 16 kHz. Made for
 * speech, it finds rap well and singing mostly; what the mix needs is where a song's last line ends
 * and the next one's first line starts, so two voices are never laid over each other.
 */
class SileroVocalDetector(model: ByteArray) : VocalDetector, Closeable {
  private val env = OrtEnvironment.getEnvironment()
  private val session = env.createSession(model, OrtSession.SessionOptions().apply { setIntraOpNumThreads(1) })

  override fun vocals(pcm: MonoPcm): List<LongRange> {
    if (pcm.samples.size < pcm.sampleRate) return emptyList()
    val x =
      if (pcm.sampleRate == RATE) pcm.samples
      else Resampler(pcm.sampleRate, RATE, 1).let { it.process(pcm.samples, 0, pcm.samples.size) }
    var state = FloatArray(2 * 128)
    val context = FloatArray(CONTEXT)
    val probs = ArrayList<Float>()
    val input = FloatArray(CONTEXT + CHUNK)
    val sr = OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(RATE.toLong())), LongArray(0))
    sr.use {
      var i = 0
      while (i + CHUNK <= x.size) {
        System.arraycopy(context, 0, input, 0, CONTEXT)
        System.arraycopy(x, i, input, CONTEXT, CHUNK)
        OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, (CONTEXT + CHUNK).toLong())).use { inT ->
          OnnxTensor.createTensor(env, FloatBuffer.wrap(state), longArrayOf(2, 1, 128)).use { stT ->
            session.run(mapOf("input" to inT, "state" to stT, "sr" to sr)).use { out ->
              val p = (out[0] as OnnxTensor).floatBuffer.get(0)
              val st = (out[1] as OnnxTensor).floatBuffer
              state = FloatArray(st.remaining()).also { st.get(it) }
              probs += p
            }
          }
        }
        System.arraycopy(x, i + CHUNK - CONTEXT, context, 0, CONTEXT)
        i += CHUNK
      }
    }
    return segments(probs)
  }

  /** Steps above 0.5 joined into ranges; gaps under 300 ms bridged, ranges under 500 ms dropped. */
  private fun segments(p: List<Float>): List<LongRange> {
    val stepMs = CHUNK * 1000.0 / RATE
    val out = ArrayList<LongRange>()
    var start = -1
    var lastOn = -1
    for (k in p.indices) {
      if (p[k] > 0.5f) {
        if (start < 0) start = k
        lastOn = k
      } else if (start >= 0 && (k - lastOn) * stepMs > 300) {
        if ((lastOn - start + 1) * stepMs >= 500) out += (start * stepMs).toLong()..((lastOn + 1) * stepMs).toLong()
        start = -1
      }
    }
    if (start >= 0 && (lastOn - start + 1) * stepMs >= 500) out += (start * stepMs).toLong()..((lastOn + 1) * stepMs).toLong()
    return out
  }

  override fun close() = session.close()

  companion object {
    private const val RATE = 16_000
    private const val CHUNK = 512
    private const val CONTEXT = 64
  }
}
