package echo.music.iad1tya.playback.mix

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * Streaming band-limited resampler (windowed sinc, polyphase table) for interleaved float PCM.
 * Every song in a mix is brought to one rate, so the two can be summed sample by sample and the
 * output never changes format (which would restart the audio output with a gap).
 *
 * Output frame j sits at input position j * inRate / outRate (the filter is centred, with no lag):
 * the mapping is exact, so positions in the resampled stream convert back to the song's own time.
 */
class Resampler(val inRate: Int, val outRate: Int, private val channels: Int) {
  /** inRate / outRate: input frames per output frame. */
  private val step = inRate.toDouble() / outRate
  private val passthrough = inRate == outRate
  /** Cutoff relative to the input Nyquist: below 1 when downsampling, against aliasing. */
  private val cutoff = minOf(1.0, outRate.toDouble() / inRate) * 0.97
  private val table: Array<FloatArray> =
    Array(PHASES + 1) { phase ->
      val frac = phase.toDouble() / PHASES
      FloatArray(TAPS) { k ->
        // Tap k weighs input frame (base - HALF + 1 + k) for an output between base and base+1.
        val x = (k - HALF + 1) - frac
        val sinc = if (x == 0.0) 1.0 else sin(PI * cutoff * x) / (PI * cutoff * x)
        // Blackman window over the whole span.
        val w = (x + HALF) / (2.0 * HALF)
        val window = 0.42 - 0.5 * cos(2 * PI * w) + 0.08 * cos(4 * PI * w)
        (cutoff * sinc * window).toFloat()
      }
    }

  /** Buffered input, interleaved; [bufferStart] is the absolute frame of its first frame. */
  private var buffer = FloatArray(4096 * channels)
  private var bufferFrames = 0
  private var bufferStart = 0L
  /** Absolute input position of the next output frame. */
  private var position = 0.0
  private var outputFrames = 0L

  /** Output frames produced so far. */
  val produced: Long
    get() = outputFrames

  /** Absolute input frame position (fractional) the next output frame is taken at. */
  val inputPosition: Double
    get() = position

  /**
   * Feeds [frames] interleaved frames from [input] (from [offset] samples) and returns the output
   * available so far; the last frames wait for the filter's lookahead until more input comes.
   */
  fun process(input: FloatArray, offset: Int, frames: Int): FloatArray {
    if (passthrough) {
      outputFrames += frames
      position += frames
      return input.copyOfRange(offset, offset + frames * channels)
    }
    append(input, offset, frames)
    val lastUsable = bufferStart + bufferFrames - HALF - 1
    val count = maxOf(0, floor((lastUsable - position) / step).toInt() + 1).let { n ->
      if (position > lastUsable) 0 else n
    }
    val out = FloatArray(count * channels)
    for (j in 0 until count) {
      val base = floor(position).toLong()
      val frac = position - base
      val phaseF = frac * PHASES
      val phase = phaseF.toInt().coerceAtMost(PHASES - 1)
      val blend = (phaseF - phase).toFloat()
      val t0 = table[phase]
      val t1 = table[phase + 1]
      val first = (base - HALF + 1 - bufferStart).toInt()
      for (c in 0 until channels) {
        var acc = 0f
        var idx = first * channels + c
        for (k in 0 until TAPS) {
          if (idx >= 0) {
            val w = t0[k] + (t1[k] - t0[k]) * blend
            acc += buffer[idx] * w
          }
          idx += channels
        }
        out[j * channels + c] = acc
      }
      position += step
    }
    outputFrames += count
    // Drop input no longer reachable by the filter.
    val keepFrom = floor(position).toLong() - HALF
    val drop = (keepFrom - bufferStart).toInt().coerceIn(0, bufferFrames)
    if (drop > 0) {
      System.arraycopy(buffer, drop * channels, buffer, 0, (bufferFrames - drop) * channels)
      bufferFrames -= drop
      bufferStart += drop
    }
    return out
  }

  private fun append(input: FloatArray, offset: Int, frames: Int) {
    val need = (bufferFrames + frames) * channels
    if (need > buffer.size) buffer = buffer.copyOf(maxOf(need, buffer.size * 2))
    System.arraycopy(input, offset, buffer, bufferFrames * channels, frames * channels)
    bufferFrames += frames
  }

  companion object {
    private const val HALF = 16
    private const val TAPS = 2 * HALF
    private const val PHASES = 256
  }
}
