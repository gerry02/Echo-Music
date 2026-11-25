package echo.music.iad1tya.playback.crossfade

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Decides how one song hands over to the next, from the beat analysis of both. Pure: no player, so
 * every rule is covered by unit tests.
 *
 * Two styles. When both songs have a clear beat and tempos within [MAX_TEMPO_CHANGE], a DJ blend:
 * the incoming song is time-stretched to the outgoing tempo, both start on the first beat of a bar,
 * the incoming intro plays over the outgoing outro, and the basslines swap on a bar in the middle.
 * Otherwise a smooth equal-power crossfade, still placed where the outgoing song's body ends and
 * past the incoming song's leading silence.
 */
object AutomixPlanner {

  /** Largest time stretch of the incoming song; beyond it the change of tempo is audible. */
  const val MAX_TEMPO_CHANGE = 0.06f
  const val MIN_CONFIDENCE = 0.3f
  private const val MAX_BLEND_MS = 20_000.0
  private const val MIN_BLEND_BEATS = 8
  /** Room kept before the outgoing stream's real end. */
  private const val END_MARGIN_MS = 300L
  private const val MIN_SMOOTH_MS = 1_500L
  /** How long the incoming song takes to return to its own tempo after a blend: 8 bars. */
  private const val RESTORE_BEATS = 32

  data class Track(
    val bpm: Float,
    val confidence: Float,
    val firstBeatOffsetMs: Long,
    val durationMs: Long,
    val downbeatOffsetMs: Long? = null,
    val headAnchorMs: Long? = null,
    val tailAnchorMs: Long? = null,
    val mixInMs: Long? = null,
    val mixOutMs: Long? = null,
    val firstSoundMs: Long? = null,
    val lastSoundMs: Long? = null,
    val keyPitchClass: Int? = null,
    val keyIsMinor: Boolean? = null,
    /** False when the beat positions were not measured on the stream that plays. */
    val gridTrusted: Boolean = true,
  ) {
    val periodMs: Double
      get() = 60_000.0 / bpm

    val hasBeat: Boolean
      get() = gridTrusted && bpm > 0f && confidence >= MIN_CONFIDENCE

    /** A downbeat to build the bar grid near the start / the end from. */
    val headBars: Double
      get() = (headAnchorMs ?: downbeatOffsetMs ?: firstBeatOffsetMs).toDouble()

    val tailBars: Double
      get() = (tailAnchorMs ?: downbeatOffsetMs ?: firstBeatOffsetMs).toDouble()
  }

  enum class Style {
    BEAT_MATCH,
    SMOOTH,
  }

  data class Plan(
    val style: Style,
    /** Outgoing position where the blend starts. */
    val triggerMs: Long,
    /** Incoming position it starts from. */
    val incomingStartMs: Long,
    /** Length of the blend in real time. */
    val overlapMs: Long,
    /** Playback speed of the incoming song during the blend (1 = its own tempo). */
    val incomingSpeed: Float = 1f,
    /** Real time over which the incoming song returns to speed 1 after the blend. */
    val restoreMs: Long = 0L,
    /** Blend progress (0..1) where the basslines swap, on a bar. */
    val bassSwapAt: Float = 0.5f,
    /** One beat as a fraction of the blend: how fast the bass swap goes. */
    val beatFraction: Float = 0.05f,
    val reason: String = "",
  )

  /**
   * @param positionMs where the outgoing song is now: a plan never starts in the past.
   * @param fadeMs the user's crossfade length, used by the smooth style.
   */
  fun plan(out: Track, incoming: Track?, positionMs: Long, fadeMs: Long): Plan? {
    val earliest = positionMs + 1_000
    if (incoming != null && out.hasBeat && incoming.hasBeat) {
      val ratio = foldRatio(out.bpm / incoming.bpm)
      if (abs(ratio - 1f) <= MAX_TEMPO_CHANGE) {
        beatMatch(out, incoming, ratio, earliest)?.let { return it }
      }
    }
    return smooth(out, incoming, earliest, fadeMs)
  }

  /** Tempo ratio folded by octaves (a 70 BPM song against a 140 BPM one is a 1:1 match). */
  fun foldRatio(raw: Float): Float {
    var r = raw
    if (!r.isFinite() || r <= 0f) return 1f
    while (r > 1.4f) r /= 2f
    while (r < 0.71f) r *= 2f
    return r
  }

  /** Same key, relative major/minor, or a fifth apart (neighbours on the Camelot wheel). */
  fun keysCompatible(a: Track, b: Track): Boolean? {
    val pa = a.keyPitchClass ?: return null
    val pb = b.keyPitchClass ?: return null
    // Compare on the circle of fifths, minors folded onto their relative major.
    fun fifths(pc: Int, minor: Boolean?): Int {
      val major = if (minor == true) (pc + 3) % 12 else pc
      return (major * 7) % 12
    }
    val d = abs(fifths(pa, a.keyIsMinor) - fifths(pb, b.keyIsMinor)) % 12
    return minOf(d, 12 - d) <= 1
  }

  private fun beatMatch(out: Track, incoming: Track, speed: Float, earliest: Long): Plan? {
    val outPeriod = out.periodMs
    val outBar = outPeriod * 4
    val inPeriod = incoming.periodMs
    val inBar = inPeriod * 4
    val audibleEnd = (out.lastSoundMs ?: out.durationMs).coerceAtMost(out.durationMs).toDouble()
    val hardEnd = (out.durationMs - END_MARGIN_MS).toDouble()

    val inFirstBar =
      snapUp((incoming.firstSoundMs ?: 0L).toDouble(), incoming.headBars, inBar).let {
        if (it < 0) it + inBar else it
      }
    val outroBeats = out.mixOutMs?.let { ((audibleEnd - it) / outPeriod).toInt() } ?: 0
    val introBeats = incoming.mixInMs?.let { ((it - inFirstBar) / inPeriod).toInt() } ?: 0

    var beats = 16
    for (candidate in intArrayOf(32, 16)) {
      if (candidate <= maxOf(outroBeats, introBeats, 16) && candidate * outPeriod <= MAX_BLEND_MS) {
        beats = candidate
        break
      }
    }
    if (keysCompatible(out, incoming) == false) beats = minOf(beats, 16)
    while (beats > MIN_BLEND_BEATS && beats * outPeriod > MAX_BLEND_MS) beats -= 4

    // Start on the outro if there is one, else as late as a whole blend still fits.
    var trigger =
      out.mixOutMs
        ?.toDouble()
        ?.takeIf { it + beats * outPeriod <= hardEnd }
        ?.let { snapNearest(it, out.tailBars, outBar) }
        ?: snapDown(minOf(audibleEnd, hardEnd) - beats * outPeriod, out.tailBars, outBar)
    if (trigger < earliest) trigger = snapUp(earliest.toDouble(), out.tailBars, outBar)
    // Late (a seek near the end): shrink the blend by whole bars to what is left.
    while (beats > MIN_BLEND_BEATS && trigger + beats * outPeriod > hardEnd) beats -= 4
    if (trigger + beats * outPeriod > hardEnd) return null

    val overlapMs = beats * outPeriod
    // The incoming body arrives as the blend ends: start its intro that many beats earlier.
    val incomingStart =
      incoming.mixInMs?.let { (it - beats * inPeriod).coerceAtLeast(inFirstBar) } ?: inFirstBar

    val swapBeat = (Math.round(beats / 2.0 / 4.0) * 4).toInt().coerceIn(4, beats)
    return Plan(
      style = Style.BEAT_MATCH,
      triggerMs = trigger.roundToLong(),
      incomingStartMs = incomingStart.roundToLong().coerceAtLeast(0L),
      overlapMs = overlapMs.roundToLong(),
      incomingSpeed = speed,
      restoreMs = if (speed == 1f) 0L else (RESTORE_BEATS * outPeriod).roundToLong(),
      bassSwapAt = swapBeat.toFloat() / beats,
      beatFraction = 1f / beats,
      reason = "beat match ${beats} beats",
    )
  }

  private fun smooth(out: Track, incoming: Track?, earliest: Long, fadeMs: Long): Plan? {
    val fade = fadeMs.coerceIn(2_000L, 12_000L).toDouble()
    val hardEnd = (out.durationMs - END_MARGIN_MS).toDouble()
    val audibleEnd = (out.lastSoundMs ?: out.durationMs).coerceAtMost(out.durationMs).toDouble()
    var trigger =
      out.mixOutMs?.toDouble()?.takeIf { it + fade <= audibleEnd } ?: (minOf(audibleEnd, hardEnd) - fade)
    if (out.hasBeat) {
      val bar = out.periodMs * 4
      val snapped = snapNearest(trigger, out.tailBars, bar)
      if (snapped + fade <= hardEnd) trigger = snapped
    }
    if (trigger < earliest) trigger = earliest.toDouble()
    val overlap = minOf(fade, hardEnd - trigger)
    if (overlap < MIN_SMOOTH_MS) return null
    return Plan(
      style = Style.SMOOTH,
      triggerMs = trigger.roundToLong(),
      incomingStartMs = incoming?.firstSoundMs?.coerceIn(0L, 10_000L) ?: 0L,
      overlapMs = overlap.roundToLong(),
      reason = when {
        incoming == null -> "smooth: next not analyzed"
        !out.hasBeat || !incoming.hasBeat -> "smooth: no clear beat"
        else -> "smooth: tempos too far apart"
      },
    )
  }

  internal fun snapUp(value: Double, anchor: Double, period: Double): Double =
    anchor + ceil((value - anchor - 1) / period) * period

  internal fun snapDown(value: Double, anchor: Double, period: Double): Double =
    anchor + floor((value - anchor + 1) / period) * period

  internal fun snapNearest(value: Double, anchor: Double, period: Double): Double =
    anchor + floor((value - anchor) / period + 0.5) * period

  /** Volumes and filters of both songs at a point of the blend. */
  data class Mix(
    val outGain: Float,
    val inGain: Float,
    /** Bass cut on the outgoing song, 0..1. */
    val outBassCut: Float = 0f,
    /** Bass cut on the incoming song, 0..1. */
    val inBassCut: Float = 0f,
    /** High-pass sweep on the outgoing song, 0 (open) .. 1 (thin). */
    val outHighPass: Float = 0f,
  )

  fun mixAt(plan: Plan, progress: Float): Mix {
    val p = progress.coerceIn(0f, 1f)
    if (plan.style == Style.SMOOTH) {
      val g = EqualPowerCurve.calculateGains(p)
      return Mix(g.outgoingGain, g.incomingGain)
    }
    // Incoming rises over the first 55 %, outgoing falls over the last 55 %.
    var inGain = sin(PI / 2 * ramp(p, 0f, 0.55f)).toFloat()
    var outGain = sin(PI / 2 * (1f - ramp(p, 0.45f, 1f))).toFloat()
    // Both near full in the middle: keep the sum within ~+1.5 dB of one song.
    val power = inGain * inGain + outGain * outGain
    if (power > 1.4f) {
      val k = sqrt(1.4f / power)
      inGain *= k
      outGain *= k
    }
    // Bass swap over one beat, centred on the swap bar.
    val half = plan.beatFraction / 2
    val swap = ramp(p, plan.bassSwapAt - half, plan.bassSwapAt + half)
    return Mix(
      outGain = outGain,
      inGain = inGain,
      outBassCut = swap,
      inBassCut = 1f - swap,
      outHighPass = ramp(p, plan.bassSwapAt, 1f),
    )
  }

  /** Incoming playback speed at [sinceBlendEndMs] after the blend, easing back to 1. */
  fun restoreSpeedAt(plan: Plan, sinceBlendEndMs: Long): Float {
    if (plan.restoreMs <= 0L || plan.incomingSpeed == 1f) return 1f
    val t = ramp(sinceBlendEndMs.toFloat(), 0f, plan.restoreMs.toFloat())
    // Smoothstep: no sudden change of tempo at either end.
    val s = t * t * (3 - 2 * t)
    return plan.incomingSpeed + (1f - plan.incomingSpeed) * s
  }

  private fun ramp(x: Float, from: Float, to: Float): Float =
    if (to <= from) (if (x >= to) 1f else 0f) else ((x - from) / (to - from)).coerceIn(0f, 1f)
}
