package echo.music.iad1tya.playback.audio

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import echo.music.iad1tya.playback.mix.Resampler
import java.io.Closeable
import java.nio.FloatBuffer

/** Beats and first beats of bars found in a stretch of audio, in ms from its start. */
class TrackedBeats(val beatsMs: DoubleArray, val downbeatsMs: DoubleArray)

/** Finds the beats of a stretch of audio. */
fun interface BeatTracker {
  fun track(pcm: MonoPcm): TrackedBeats?
}

/**
 * Beat This! (CPJKU, MIT), the small model: a neural network that marks every beat and every first
 * beat of a bar, trained on many genres. Far surer than onset statistics about which pulse is the
 * beat and which the offbeat, and where bars start. Runs on ONNX Runtime from the converted model
 * and its log-mel front end, the same way the reference implementation runs it: 22.05 kHz mono,
 * 50 frames a second, windows of 1500 frames, peaks of the logits above 0, downbeats snapped to
 * the nearest beat.
 */
class BeatThisTracker(melModel: ByteArray, beatModel: ByteArray) : BeatTracker, Closeable {
  private val env = OrtEnvironment.getEnvironment()
  private val options = OrtSession.SessionOptions().apply { setIntraOpNumThreads(2) }
  private val mel = env.createSession(melModel, options)
  private val net = env.createSession(beatModel, options)
  private val melInput = mel.inputNames.first()
  private val netInput = net.inputNames.first()

  override fun track(pcm: MonoPcm): TrackedBeats? {
    if (pcm.samples.size < pcm.sampleRate * 4) return null
    val audio =
      if (pcm.sampleRate == RATE) pcm.samples
      else Resampler(pcm.sampleRate, RATE, 1).let { r -> r.process(pcm.samples, 0, pcm.samples.size) }
    val spect = logMel(audio) ?: return null
    val frames = spect.size / MELS
    val (beat, down) = logits(spect, frames)
    val beats = peaks(beat)
    val downs = peaks(down).map { d -> beats.minByOrNull { kotlin.math.abs(it - d) } ?: d }.distinct()
    return TrackedBeats(
      beats.map { it * 1000.0 / FPS }.toDoubleArray(),
      downs.map { it * 1000.0 / FPS }.toDoubleArray(),
    )
  }

  /** Log-mel frames (frames x 128), from the converted front end. */
  private fun logMel(audio: FloatArray): FloatArray? {
    OnnxTensor.createTensor(env, FloatBuffer.wrap(audio), longArrayOf(1, audio.size.toLong())).use { input ->
      mel.run(mapOf(melInput to input)).use { out ->
        val t = out[0] as OnnxTensor
        val buf = t.floatBuffer
        return FloatArray(buf.remaining()).also { buf.get(it) }
      }
    }
  }

  /** Beat and downbeat logits per frame, over windows of 1500 frames as the reference does. */
  private fun logits(spect: FloatArray, frames: Int): Pair<FloatArray, FloatArray> {
    val beat = FloatArray(frames) { -1000f }
    val down = FloatArray(frames) { -1000f }
    val starts = ArrayList<Int>()
    var s = -BORDER
    while (s < frames - BORDER) {
      starts += s
      s += CHUNK - 2 * BORDER
    }
    if (frames > CHUNK - 2 * BORDER) starts[starts.size - 1] = frames - (CHUNK - BORDER)
    // Earlier windows win the overlaps ("keep first"): run the later ones first.
    for (start in starts.asReversed()) {
      val piece = FloatArray(CHUNK * MELS)
      val a = maxOf(start, 0)
      val b = minOf(start + CHUNK, frames)
      if (b > a) System.arraycopy(spect, a * MELS, piece, (a - start) * MELS, (b - a) * MELS)
      OnnxTensor.createTensor(env, FloatBuffer.wrap(piece), longArrayOf(1, CHUNK.toLong(), MELS.toLong())).use { input ->
        net.run(mapOf(netInput to input)).use { out ->
          val ob = (out[0] as OnnxTensor).floatBuffer
          val od = (out[1] as OnnxTensor).floatBuffer
          val keepFrom = maxOf(start + BORDER, 0)
          val keepTo = minOf(start + CHUNK - BORDER, frames)
          for (f in keepFrom until keepTo) {
            beat[f] = ob.get(f - start)
            down[f] = od.get(f - start)
          }
        }
      }
    }
    return beat to down
  }

  /** Frames (fractional) of the local maxima (±3 frames) above 0, adjacent ones merged. */
  private fun peaks(x: FloatArray): List<Double> {
    val idx = ArrayList<Int>()
    for (i in x.indices) {
      if (x[i] <= 0f) continue
      var isMax = true
      for (j in maxOf(0, i - 3)..minOf(x.size - 1, i + 3)) if (x[j] > x[i]) {
        isMax = false
        break
      }
      if (isMax) idx += i
    }
    val out = ArrayList<Double>()
    var run = ArrayList<Int>()
    for (i in idx) {
      if (run.isNotEmpty() && i - run.last() > 1) {
        out += run.average()
        run = ArrayList()
      }
      run += i
    }
    if (run.isNotEmpty()) out += run.average()
    return out
  }

  override fun close() {
    mel.close()
    net.close()
  }

  companion object {
    const val RATE = 22_050
    const val FPS = 50.0
    private const val MELS = 128
    private const val CHUNK = 1500
    private const val BORDER = 6
  }
}
