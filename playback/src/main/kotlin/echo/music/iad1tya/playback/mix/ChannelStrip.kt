package echo.music.iad1tya.playback.mix

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * One song's side of the mixer, like a DJ mixer's channel: gain, a low-shelf bass cut, and
 * high-pass / low-pass sweeps. Settings ease per sample toward their targets, and the swept
 * filters are topology-preserving state-variable filters (Zavalishin), so nothing clicks while
 * they move. Same sound as the two-player blend's filters, on float samples inside the mixer.
 */
class ChannelStrip(private val sampleRate: Int, private val channels: Int) {
  /** Targets, set once per block from [echo.music.iad1tya.playback.crossfade.AutomixPlanner.mixAt]. */
  var gain = 0f
  var bassCut = 0f
  var highPass = 0f
  var lowPass = 0f
  /**
   * Echo send (0..1): the song, through its filters, fed into a beat-synced delay whose repeats go
   * on (and fade) after the song's own gain is down: a DJ's echo out.
   */
  var echo = 0f
  /** Delay between repeats, in frames (set with [setEchoDelay]). */
  private var echoDelay = 0
  private var echoBuffer = FloatArray(0)
  private var echoPos = 0
  private var curEcho = 0f

  /** The echo's repeat time ([frames], e.g. half a beat) and how much each repeat keeps. */
  fun setEchoDelay(frames: Int, feedback: Float = 0.5f) {
    echoDelay = frames.coerceAtLeast(1)
    echoFeedback = feedback
    if (echoBuffer.size < echoDelay * channels) echoBuffer = FloatArray(echoDelay * channels)
  }

  private var echoFeedback = 0.5f

  private var curGain = 0f
  private var curBass = 0f
  private var curHp = 0f
  private var curLp = 0f
  private var initialized = false

  private val ease = (1000.0 / (EASE_MS * sampleRate)).toFloat().coerceIn(0f, 1f)

  // Bass shelf, fixed coefficients (only its wet amount moves).
  private val b0: Double
  private val b1: Double
  private val b2: Double
  private val a1: Double
  private val a2: Double
  private val x1 = DoubleArray(channels)
  private val x2 = DoubleArray(channels)
  private val y1 = DoubleArray(channels)
  private val y2 = DoubleArray(channels)

  private var hpA1 = 1.0
  private var hpA2 = 0.0
  private var hpA3 = 0.0
  private val hpIc1 = DoubleArray(channels)
  private val hpIc2 = DoubleArray(channels)
  private var lpA1 = 1.0
  private var lpA2 = 0.0
  private var lpA3 = 0.0
  private val lpIc1 = DoubleArray(channels)
  private val lpIc2 = DoubleArray(channels)
  private var countdown = 0

  init {
    val aVal = sqrt(10.0.pow(MAX_DUCK_DB / 20.0))
    val omega = 2.0 * PI * SHELF_HZ / sampleRate
    val sinOmega = sin(omega)
    val cosOmega = cos(omega)
    val alpha = sinOmega / 2.0 * sqrt(2.0)
    val sqrtA = sqrt(aVal)
    val ap = aVal + 1.0
    val am = aVal - 1.0
    val t = 2.0 * sqrtA * alpha
    val a0 = ap + am * cosOmega + t
    b0 = aVal * (ap - am * cosOmega + t) / a0
    b1 = 2.0 * aVal * (am - ap * cosOmega) / a0
    b2 = aVal * (ap - am * cosOmega - t) / a0
    a1 = -2.0 * (am + ap * cosOmega) / a0
    a2 = (ap + am * cosOmega - t) / a0
    updateCoefficients()
  }

  /** Jumps to the targets: the first block of a song starts where it should, not eased in. */
  fun snap() {
    curGain = gain
    curBass = bassCut
    curHp = highPass
    curLp = lowPass
    curEcho = echo
    updateCoefficients()
    initialized = true
  }

  /**
   * Processes [frames] interleaved frames of [src] from [srcOffset] and adds the result into [dst]
   * from [dstOffset] (the mixer sums both songs' strips).
   */
  fun processAdd(src: FloatArray, srcOffset: Int, dst: FloatArray, dstOffset: Int, frames: Int) {
    if (!initialized) snap()
    for (f in 0 until frames) {
      curGain += (gain - curGain) * ease
      curBass += (bassCut - curBass) * ease
      curHp += (highPass - curHp) * ease
      curLp += (lowPass - curLp) * ease
      curEcho += (echo - curEcho) * ease
      if (--countdown <= 0) {
        countdown = UPDATE_SAMPLES
        updateCoefficients()
      }
      val hpWet = (curHp * 20f).coerceAtMost(1f).toDouble()
      val lpWet = (curLp * 20f).coerceAtMost(1f).toDouble()
      for (c in 0 until channels) {
        val x = src[srcOffset + f * channels + c].toDouble()
        val shelf = b0 * x + b1 * x1[c] + b2 * x2[c] - a1 * y1[c] - a2 * y2[c]
        x2[c] = x1[c]
        x1[c] = x
        y2[c] = y1[c]
        y1[c] = shelf
        var v = x + (shelf - x) * curBass
        // High-pass.
        run {
          val v3 = v - hpIc2[c]
          val v1 = hpA1 * hpIc1[c] + hpA2 * v3
          val v2 = hpIc2[c] + hpA2 * hpIc1[c] + hpA3 * v3
          hpIc1[c] = 2 * v1 - hpIc1[c]
          hpIc2[c] = 2 * v2 - hpIc2[c]
          val hp = v - SVF_K * v1 - v2
          v += (hp - v) * hpWet
        }
        // Low-pass.
        run {
          val v3 = v - lpIc2[c]
          val v1 = lpA1 * lpIc1[c] + lpA2 * v3
          val v2 = lpIc2[c] + lpA2 * lpIc1[c] + lpA3 * v3
          lpIc1[c] = 2 * v1 - lpIc1[c]
          lpIc2[c] = 2 * v2 - lpIc2[c]
          v += (v2 - v) * lpWet
        }
        var outV = (v * curGain).toFloat()
        if (echoDelay > 0) {
          // The repeats: what was sent a delay ago, fed back a little quieter each time, darker.
          val k = echoPos * channels + c
          val delayed = echoBuffer[k]
          echoBuffer[k] = (v.toFloat() * curEcho + delayed * echoFeedback)
          outV += delayed
        }
        dst[dstOffset + f * channels + c] += outV
      }
      if (echoDelay > 0) echoPos = (echoPos + 1) % echoDelay
    }
  }

  private fun updateCoefficients() {
    val nyq = sampleRate * 0.45
    val hpHz = MIN_HP_HZ * (MAX_HP_HZ / MIN_HP_HZ).pow(curHp.toDouble())
    var g = tan(PI * hpHz.coerceAtMost(nyq) / sampleRate)
    hpA1 = 1.0 / (1.0 + g * (g + SVF_K))
    hpA2 = g * hpA1
    hpA3 = g * hpA2
    val lpHz = MAX_LP_HZ * (MIN_LP_HZ / MAX_LP_HZ).pow(curLp.toDouble())
    g = tan(PI * lpHz.coerceAtMost(nyq) / sampleRate)
    lpA1 = 1.0 / (1.0 + g * (g + SVF_K))
    lpA2 = g * lpA1
    lpA3 = g * lpA2
  }

  companion object {
    private const val EASE_MS = 15.0
    private const val SHELF_HZ = 150.0
    private const val MAX_DUCK_DB = -10.0
    private const val MIN_HP_HZ = 20.0
    private const val MAX_HP_HZ = 900.0
    private const val MAX_LP_HZ = 20_000.0
    private const val MIN_LP_HZ = 700.0
    private const val SVF_K = 1.4142135623730951
    private const val UPDATE_SAMPLES = 32
  }
}
