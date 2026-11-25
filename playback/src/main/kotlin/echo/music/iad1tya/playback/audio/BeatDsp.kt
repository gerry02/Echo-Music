package echo.music.iad1tya.playback.audio

import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Mono PCM in -1..1. */
class MonoPcm(val samples: FloatArray, val sampleRate: Int) {
  val durationMs: Long
    get() = if (sampleRate <= 0) 0L else samples.size * 1000L / sampleRate
}

/**
 * The signal processing behind the beat analysis, on plain PCM so it runs in JVM unit tests: an STFT
 * gives an onset envelope (spectral flux), a low-band onset envelope (kick drum) and a chroma per
 * frame; from those come the tempo, the beat phase, the bar phase (downbeat) and the key.
 */
object BeatDsp {

  const val FFT_SIZE = 1024
  const val HOP_SIZE = 512
  const val MIN_BPM = 60f
  const val MAX_BPM = 180f

  /** Below this the bins hold the kick and the bass, which mark the first beat of a bar. */
  private const val LOW_BAND_HZ = 150f

  class Spectrum(
    /** Detrended spectral flux per hop: onsets. */
    val flux: FloatArray,
    /** Spectral flux below [LOW_BAND_HZ] per hop. */
    val lowFlux: FloatArray,
    /** 12 pitch-class energies per hop, each normalized to sum 1 (zeros when silent). */
    val chroma: Array<FloatArray>,
    val frameRate: Float,
  ) {
    val frames: Int
      get() = flux.size

    fun frameToMs(frame: Double): Double = frame / frameRate * 1000.0

    fun msToFrame(ms: Double): Double = ms / 1000.0 * frameRate
  }

  private val window = FloatArray(FFT_SIZE) { 0.5f - 0.5f * cos(2.0 * Math.PI * it / FFT_SIZE).toFloat() }

  fun spectrum(pcm: MonoPcm): Spectrum? {
    val samples = pcm.samples
    val numFrames = (samples.size - FFT_SIZE) / HOP_SIZE
    if (numFrames < 8) return null
    val bins = FFT_SIZE / 2
    val binHz = pcm.sampleRate.toFloat() / FFT_SIZE
    val lowBins = (LOW_BAND_HZ / binHz).toInt().coerceIn(1, bins - 1)
    val chromaMinBin = (65f / binHz).toInt().coerceAtLeast(1)
    val chromaMaxBin = (2000f / binHz).toInt().coerceAtMost(bins - 1)
    val pitchClassOfBin = IntArray(bins) { b ->
      if (b < chromaMinBin || b > chromaMaxBin) -1
      else {
        val midi = 69.0 + 12.0 * (ln(b * binHz / 440.0) / ln(2.0))
        ((midi.roundToInt() % 12) + 12) % 12
      }
    }

    val flux = FloatArray(numFrames)
    val lowFlux = FloatArray(numFrames)
    val chroma = Array(numFrames) { FloatArray(12) }
    val prevMag = FloatArray(bins)
    val re = FloatArray(FFT_SIZE)
    val im = FloatArray(FFT_SIZE)

    for (frame in 0 until numFrames) {
      val offset = frame * HOP_SIZE
      for (i in 0 until FFT_SIZE) {
        re[i] = samples[offset + i] * window[i]
        im[i] = 0f
      }
      fft(re, im)
      var sum = 0f
      var lowSum = 0f
      val c = chroma[frame]
      for (b in 0 until bins) {
        val raw = sqrt(re[b] * re[b] + im[b] * im[b])
        val mag = ln(1f + 10f * raw)
        val diff = mag - prevMag[b]
        if (diff > 0 && frame > 0) {
          sum += diff
          if (b <= lowBins) lowSum += diff
        }
        prevMag[b] = mag
        val pc = pitchClassOfBin[b]
        if (pc >= 0) c[pc] += raw
      }
      flux[frame] = sum
      lowFlux[frame] = lowSum
      val total = c.sum()
      if (total > 0f) for (i in 0 until 12) c[i] /= total
    }
    return Spectrum(detrend(flux), detrend(lowFlux), chroma, pcm.sampleRate.toFloat() / HOP_SIZE)
  }

  /** Subtract a local mean so periodicity sees onsets, not slow dynamics. */
  private fun detrend(values: FloatArray): FloatArray {
    val n = values.size
    val half = 8
    val out = FloatArray(n)
    // Running sum for an O(n) moving average.
    val prefix = DoubleArray(n + 1)
    for (i in 0 until n) prefix[i + 1] = prefix[i] + values[i]
    for (i in 0 until n) {
      val lo = max(0, i - half)
      val hi = min(n - 1, i + half)
      val mean = (prefix[hi + 1] - prefix[lo]) / (hi - lo + 1)
      out[i] = max(0f, (values[i] - mean).toFloat())
    }
    return out
  }

  /**
   * Autocorrelation over the beat-period lag range. Returns (periodInFrames, confidence), favoring
   * candidates whose double period is also supported (down-weights half-period picks).
   */
  fun tempoPeriod(flux: FloatArray, frameRate: Float): Pair<Float, Float>? {
    val minLag = (frameRate * 60f / MAX_BPM).roundToInt()
    val maxLag = (frameRate * 60f / MIN_BPM).roundToInt()
    if (flux.size < maxLag * 2) return null

    val ac = FloatArray(maxLag * 2 + 2)
    for (lag in minLag..min(maxLag * 2 + 1, flux.size - 1)) {
      var sum = 0f
      for (i in 0 until flux.size - lag) sum += flux[i] * flux[i + lag]
      ac[lag] = sum / (flux.size - lag)
    }

    var mean = 0f
    for (lag in minLag..maxLag) mean += ac[lag]
    mean /= maxLag - minLag + 1
    if (mean <= 0f) return null

    var bestLag = -1
    var bestScore = 0f
    for (lag in minLag..maxLag) {
      if (lag > minLag && lag < maxLag && (ac[lag] < ac[lag - 1] || ac[lag] < ac[lag + 1])) continue
      // Reward candidates whose 2x lag also correlates (true beat vs half-beat).
      val score = ac[lag] + 0.5f * ac[lag * 2]
      if (score > bestScore) {
        bestScore = score
        bestLag = lag
      }
    }
    if (bestLag < 0) return null

    // Parabolic interpolation around the peak for sub-frame period accuracy.
    val refined =
      if (bestLag in minLag + 1 until maxLag) {
        val y0 = ac[bestLag - 1]
        val y1 = ac[bestLag]
        val y2 = ac[bestLag + 1]
        val denom = y0 - 2 * y1 + y2
        if (denom != 0f) bestLag + 0.5f * (y0 - y2) / denom else bestLag.toFloat()
      } else bestLag.toFloat()

    // Peak prominence over the median: flat (beatless) material scores near 0.
    val sortedAc = ac.copyOfRange(minLag, maxLag + 1).sorted()
    val median = sortedAc[sortedAc.size / 2]
    val confidence =
      if (ac[bestLag] > 0f) ((ac[bestLag] - median) / ac[bestLag]).coerceIn(0f, 1f) else 0f
    return refined to confidence
  }

  /** Linear interpolation of [values] at a fractional index; 0 outside. */
  private fun at(values: FloatArray, index: Double): Float {
    if (index < 0 || index > values.size - 1) return 0f
    val i = floor(index).toInt()
    val f = (index - i).toFloat()
    val a = values[i]
    val b = if (i + 1 < values.size) values[i + 1] else a
    return a + (b - a) * f
  }

  /** Where the beats fall in a window: the phase (frames) of a grid with a known period. */
  class BeatPhase(val phaseFrames: Double, val strength: Float)

  /**
   * Comb filter with a fractional period: the phase maximizing flux at phase + k*period. Strength is
   * the best comb sum over the mean across phases: 1 for no pulse, higher the clearer the beat.
   */
  fun beatPhase(flux: FloatArray, periodFrames: Double, spread: Double = 0.5): BeatPhase? {
    if (periodFrames < 2 || flux.size < periodFrames * 4) return null
    val step = 0.25
    val candidates = (periodFrames / step).toInt()
    var bestPhase = 0.0
    var bestSum = -1.0
    var total = 0.0
    for (c in 0 until candidates) {
      val phase = c * step
      var sum = 0.0
      var t = phase
      while (t < flux.size - 1) {
        // A small triangular spread tolerates a few ms of expressive timing.
        sum += at(flux, t)
        if (spread > 0) sum += spread * (at(flux, t - 1) + at(flux, t + 1))
        t += periodFrames
      }
      total += sum
      if (sum > bestSum) {
        bestSum = sum
        bestPhase = phase
      }
    }
    val mean = total / candidates
    if (mean <= 0.0) return null
    return BeatPhase(bestPhase, (bestSum / mean).toFloat())
  }

  /**
   * The comb can lock onto the offbeats (loud hi-hats or claps on the "and"): the grid then has the
   * right tempo but sits half a beat off, and two songs mixed on it sound against each other. The
   * beat is where the kick and the bass hit. Returns how much of the grid at [phaseFrames] (first)
   * and at half a beat later (second) looks like the beat: low-band onsets, broadband onsets at half
   * weight, each as a share of both, so windows of any loudness add up.
   */
  fun halfBeatScores(spectrum: Spectrum, phaseFrames: Double, periodFrames: Double): Pair<Double, Double> {
    fun sums(phase: Double): Pair<Double, Double> {
      var low = 0.0
      var all = 0.0
      var t = phase
      while (t < spectrum.frames - 1) {
        low += at(spectrum.lowFlux, t)
        all += at(spectrum.flux, t)
        t += periodFrames
      }
      return low to all
    }
    val (lowA, allA) = sums(phaseFrames)
    val (lowB, allB) = sums(phaseFrames + periodFrames / 2)
    val lowTotal = lowA + lowB
    val allTotal = allA + allB
    var a = 0.0
    var b = 0.0
    if (lowTotal > 0) {
      a += lowA / lowTotal
      b += lowB / lowTotal
    }
    if (allTotal > 0) {
      a += 0.5 * allA / allTotal
      b += 0.5 * allB / allTotal
    }
    return a to b
  }

  /** The first beat of a bar among a grid of beats: (index 0..3 of the first downbeat, confidence). */
  fun downbeatPhase(spectrum: Spectrum, phaseFrames: Double, periodFrames: Double): Pair<Int, Float>? {
    val beats = ArrayList<Double>()
    var t = phaseFrames
    while (t < spectrum.frames - 1) {
      beats.add(t)
      t += periodFrames
    }
    if (beats.size < 8) return null

    val spread = max(1, (periodFrames / 8).roundToInt())
    val accent = FloatArray(beats.size)
    val change = FloatArray(beats.size)
    for ((i, beat) in beats.withIndex()) {
      val center = beat.roundToInt()
      var peak = 0f
      for (f in center - spread..center + spread) {
        if (f in 0 until spectrum.frames) peak = max(peak, spectrum.lowFlux[f])
      }
      accent[i] = peak
      // Harmony tends to change on the first beat: chroma of the beat before vs the beat after.
      val before = meanChroma(spectrum, beat - periodFrames, beat)
      val after = meanChroma(spectrum, beat, beat + periodFrames)
      change[i] = if (before != null && after != null) 1f - cosine(before, after) else 0f
    }
    val accentMean = accent.average().toFloat()
    val changeMean = change.average().toFloat()
    val scores = FloatArray(4)
    val counts = IntArray(4)
    for (i in beats.indices) {
      var s = 0f
      if (accentMean > 0f) s += accent[i] / accentMean
      if (changeMean > 0f) s += 0.75f * change[i] / changeMean
      scores[i % 4] += s
      counts[i % 4]++
    }
    for (c in 0 until 4) if (counts[c] > 0) scores[c] /= counts[c]
    val order = (0 until 4).sortedByDescending { scores[it] }
    val best = order[0]
    if (scores[best] <= 0f) return null
    val confidence = ((scores[best] - scores[order[1]]) / scores[best]).coerceIn(0f, 1f)
    return best to confidence
  }

  private fun meanChroma(spectrum: Spectrum, fromFrame: Double, toFrame: Double): FloatArray? {
    val lo = max(0, fromFrame.roundToInt())
    val hi = min(spectrum.frames - 1, toFrame.roundToInt())
    if (hi <= lo) return null
    val out = FloatArray(12)
    for (f in lo until hi) for (c in 0 until 12) out[c] += spectrum.chroma[f][c]
    return out
  }

  private fun cosine(a: FloatArray, b: FloatArray): Float {
    var dot = 0f
    var na = 0f
    var nb = 0f
    for (i in a.indices) {
      dot += a[i] * b[i]
      na += a[i] * a[i]
      nb += b[i] * b[i]
    }
    if (na <= 0f || nb <= 0f) return 1f
    return dot / sqrt(na * nb)
  }

  // Krumhansl-Schmuckler key profiles: relative pull of each scale degree on the tonic.
  private val MAJOR_PROFILE =
    floatArrayOf(6.35f, 2.23f, 3.48f, 2.33f, 4.38f, 4.09f, 2.52f, 5.19f, 2.39f, 3.66f, 2.29f, 2.88f)
  private val MINOR_PROFILE =
    floatArrayOf(6.33f, 2.68f, 3.52f, 5.38f, 2.60f, 3.53f, 2.54f, 4.75f, 3.98f, 2.69f, 3.34f, 3.17f)

  /** (pitch class 0=C..11=B, isMinor), or null without tonal energy. */
  fun key(spectrum: Spectrum): Pair<Int, Boolean>? {
    val chroma = FloatArray(12)
    for (frame in spectrum.chroma) for (c in 0 until 12) chroma[c] += frame[c]
    val sum = chroma.sum()
    if (sum <= 0f) return null
    for (i in chroma.indices) chroma[i] /= sum

    var bestScore = Float.NEGATIVE_INFINITY
    var best = 0 to false
    for (tonic in 0 until 12) {
      val major = correlate(chroma, MAJOR_PROFILE, tonic)
      if (major > bestScore) {
        bestScore = major
        best = tonic to false
      }
      val minor = correlate(chroma, MINOR_PROFILE, tonic)
      if (minor > bestScore) {
        bestScore = minor
        best = tonic to true
      }
    }
    return best
  }

  private fun correlate(chroma: FloatArray, profile: FloatArray, tonic: Int): Float {
    var sum = 0f
    for (degree in 0 until 12) sum += chroma[(degree + tonic) % 12] * profile[degree]
    return sum
  }

  /** RMS energy per block of [blockMs]. */
  fun energyEnvelope(pcm: MonoPcm, blockMs: Int): FloatArray {
    val blockSize = (pcm.sampleRate * blockMs / 1000).coerceAtLeast(1)
    val numBlocks = pcm.samples.size / blockSize
    val env = FloatArray(numBlocks)
    for (b in 0 until numBlocks) {
      var sum = 0f
      val offset = b * blockSize
      for (i in 0 until blockSize) {
        val s = pcm.samples[offset + i]
        sum += s * s
      }
      env[b] = sqrt(sum / blockSize)
    }
    return env
  }

  fun percentile(values: FloatArray, p: Float): Float {
    if (values.isEmpty()) return 0f
    val sorted = values.sorted()
    return sorted[((sorted.size - 1) * p).roundToInt().coerceIn(0, sorted.size - 1)]
  }

  /** In-place iterative radix-2 FFT. Arrays must be a power-of-two length. */
  fun fft(re: FloatArray, im: FloatArray) {
    val n = re.size
    var j = 0
    for (i in 1 until n) {
      var bit = n shr 1
      while (j and bit != 0) {
        j = j xor bit
        bit = bit shr 1
      }
      j = j or bit
      if (i < j) {
        val tr = re[i]
        re[i] = re[j]
        re[j] = tr
        val ti = im[i]
        im[i] = im[j]
        im[j] = ti
      }
    }
    var len = 2
    while (len <= n) {
      val ang = -2.0 * Math.PI / len
      val wRe = cos(ang).toFloat()
      val wIm = sin(ang).toFloat()
      var i = 0
      while (i < n) {
        var curRe = 1f
        var curIm = 0f
        for (k in 0 until len / 2) {
          val uRe = re[i + k]
          val uIm = im[i + k]
          val vRe = re[i + k + len / 2] * curRe - im[i + k + len / 2] * curIm
          val vIm = re[i + k + len / 2] * curIm + im[i + k + len / 2] * curRe
          re[i + k] = uRe + vRe
          im[i + k] = uIm + vIm
          re[i + k + len / 2] = uRe - vRe
          im[i + k + len / 2] = uIm - vIm
          val nRe = curRe * wRe - curIm * wIm
          curIm = curRe * wIm + curIm * wRe
          curRe = nRe
        }
        i += len
      }
      len = len shl 1
    }
  }
}
