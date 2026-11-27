package echo.music.iad1tya.playback.mix

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * Changes the tempo of interleaved float PCM without changing its pitch (WSOLA: 40 ms Hann frames
 * at 50 % overlap, each placed within 10 ms of where the tempo puts it, where it best continues
 * the previous one). The speed may change at any time and is exactly accounted for: output frame k
 * is input frame start + the sum of the speeds before it, give or take the 10 ms placement.
 *
 * In [exact] mode at speed 1 it stops searching: frames then follow on from each other and the
 * output is the input itself, sample for sample, from [exactInputPosition] on. That is how the
 * mixed song is handed over to the player playing the same song without a seam.
 */
class TimeStretcher(private val channels: Int, sampleRate: Int, startFrame: Long = 0L) {
  private val frameLen = ((sampleRate * 0.040).toInt() / 2) * 2
  private val hop = frameLen / 2
  private val tolerance = (sampleRate * 0.010).toInt()
  private val window = FloatArray(frameLen) { i -> (0.5 - 0.5 * cos(2 * PI * i / frameLen)).toFloat() }

  /** Input frames per output frame: above 1 plays faster. */
  var speed = 1.0

  /** Frames follow on without search (see the class doc); set at speed 1 to hand over. */
  var exact = false

  private var buffer = FloatArray(frameLen * 4 * channels)
  private var bufferFrames = 0
  private var bufferStart = startFrame
  private var nominal = startFrame.toDouble()
  private var prevStart = -1L
  private val overlap = FloatArray(hop * channels)
  private var produced = 0L

  /** Output frames produced so far. */
  val outputFrames: Long
    get() = produced

  /** Where the tempo puts the next output frame in the input (frames, fractional). */
  val nominalInputPosition: Double
    get() = nominal

  /**
   * The input frame the next output frame is, sample for sample, once frames follow on ([exact]
   * at speed 1); otherwise where the next frame's overlap continues from.
   */
  val exactInputPosition: Long
    get() = if (prevStart < 0) nominal.roundToLong() else prevStart + hop

  fun process(input: FloatArray, offset: Int, frames: Int): FloatArray {
    append(input, offset, frames)
    var out = FloatArray(0)
    var outFrames = 0
    while (true) {
      val first = prevStart < 0
      val natural = prevStart + hop
      val searchFrom = if (first || exact) 0L else nominal.roundToLong() - tolerance
      val searchTo = if (first || exact) 0L else nominal.roundToLong() + tolerance
      val needEnd =
        when {
          first -> nominal.roundToLong() + frameLen
          exact -> natural + frameLen
          else -> maxOf(searchTo + frameLen, natural + hop)
        }
      if (needEnd > bufferStart + bufferFrames) break
      val start =
        when {
          first -> nominal.roundToLong()
          exact -> natural
          else -> bestStart(natural, searchFrom.coerceAtLeast(bufferStart), searchTo)
        }
      if (out.size < (outFrames + hop) * channels) out = out.copyOf(maxOf((outFrames + hop) * channels, out.size * 2))
      val s = ((start - bufferStart) * channels).toInt()
      for (i in 0 until hop) {
        for (c in 0 until channels) {
          val o = (outFrames + i) * channels + c
          val x = buffer[s + i * channels + c]
          // The first frame has nothing to overlap: taken as is, so the start is not faded in.
          out[o] = if (first) x else overlap[i * channels + c] + window[i] * x
          overlap[i * channels + c] = window[hop + i] * buffer[s + (hop + i) * channels + c]
        }
      }
      outFrames += hop
      prevStart = start
      nominal = if (exact) (start + hop).toDouble() else nominal + hop * speed
      dropBefore(minOf(nominal.roundToLong() - tolerance, prevStart + hop) - 1)
    }
    produced += outFrames
    return if (out.size == outFrames * channels) out else out.copyOf(outFrames * channels)
  }

  /** The start in [from]..[to] whose first half best continues the input at [natural]. */
  private fun bestStart(natural: Long, from: Long, to: Long): Long {
    if (to <= from) return from
    val ref = mono(natural, hop)
    var best = from
    var bestScore = Double.NEGATIVE_INFINITY
    // Coarse, on every 4th frame, then refined around the best.
    var p = from
    while (p <= to) {
      val score = similarity(ref, p, 4)
      if (score > bestScore) {
        bestScore = score
        best = p
      }
      p += 4
    }
    val coarse = best
    bestScore = Double.NEGATIVE_INFINITY
    for (q in maxOf(from, coarse - 3)..minOf(to, coarse + 3)) {
      val score = similarity(ref, q, 1)
      if (score > bestScore) {
        bestScore = score
        best = q
      }
    }
    return best
  }

  private fun mono(at: Long, len: Int): FloatArray {
    val s = ((at - bufferStart) * channels).toInt()
    return FloatArray(len) { i ->
      var v = 0f
      for (c in 0 until channels) v += buffer[s + i * channels + c]
      v
    }
  }

  private fun similarity(ref: FloatArray, at: Long, stride: Int): Double {
    val s = ((at - bufferStart) * channels).toInt()
    var dot = 0.0
    var energy = 1e-9
    var i = 0
    while (i < hop) {
      var v = 0f
      for (c in 0 until channels) v += buffer[s + i * channels + c]
      dot += v * ref[i]
      energy += v * v
      i += stride
    }
    return dot / sqrt(energy)
  }

  private fun append(input: FloatArray, offset: Int, frames: Int) {
    val need = (bufferFrames + frames) * channels
    if (need > buffer.size) buffer = buffer.copyOf(maxOf(need, buffer.size * 2))
    System.arraycopy(input, offset, buffer, bufferFrames * channels, frames * channels)
    bufferFrames += frames
  }

  private fun dropBefore(frame: Long) {
    val drop = (frame - bufferStart).toInt().coerceIn(0, bufferFrames)
    if (drop <= 0) return
    System.arraycopy(buffer, drop * channels, buffer, 0, (bufferFrames - drop) * channels)
    bufferFrames -= drop
    bufferStart += drop
  }
}
