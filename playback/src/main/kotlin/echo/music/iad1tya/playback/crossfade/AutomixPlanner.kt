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
  const val MAX_TEMPO_CHANGE = 0.08f
  const val MIN_CONFIDENCE = 0.3f
  private const val MAX_BLEND_MS = 24_000.0
  private const val MIN_BLEND_BEATS = 8
  /** A blend never starts earlier than this before the end, nor in the first 3/4 of a short song. */
  const val MAX_MIX_LEAD_MS = 60_000L
  /** Trailing silence longer than this is taken for an analysis error, not silence. */
  private const val MAX_TRAILING_SILENCE_MS = 20_000L
  /** Room kept before the outgoing stream's real end. */
  private const val END_MARGIN_MS = 300L
  private const val MIN_SMOOTH_MS = 1_500L
  /** How long the incoming song takes to return to its own tempo after a blend: 8 bars. */
  private const val RESTORE_BEATS = 32
  /**
   * The furthest into the next song a blend may start it (past its intro): a "mix in" later than
   * this, or than [MAX_MIX_IN_SHARE] of the song, is an analysis error, not an intro.
   */
  const val MAX_MIX_IN_MS = 45_000L
  private const val MAX_MIX_IN_SHARE = 0.2

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

    /** Where the audio really ends; a "last sound" far from the end is not believed. */
    val audibleEndMs: Double
      get() =
        (lastSoundMs?.takeIf { it >= durationMs - MAX_TRAILING_SILENCE_MS } ?: durationMs)
          .coerceAtMost(durationMs)
          .toDouble()

    /** The earliest a blend out of this song may start: inside its last minute (or quarter). */
    val earliestMixOutMs: Double
      get() = (durationMs - minOf(MAX_MIX_LEAD_MS.toDouble(), durationMs * 0.25)).coerceAtLeast(0.0)

    /** The outro start, when it lies where an outro can be. */
    val trustedMixOutMs: Long?
      get() = mixOutMs?.takeIf { it >= earliestMixOutMs && it < durationMs }

    /** The intro end, when it lies where an intro can end: a mix never starts mid-song. */
    val trustedMixInMs: Long?
      get() =
        mixInMs?.takeIf {
          it in 0..MAX_MIX_IN_MS && (durationMs <= 0 || it <= durationMs * MAX_MIX_IN_SHARE)
        }

    /** Where the first sound is, when it is near the start. */
    val trustedFirstSoundMs: Long?
      get() = firstSoundMs?.takeIf { it in 0..MAX_MIX_IN_MS }
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
    /** Whether the blend filters the songs (high-pass sweeps, bass swap). */
    val filters: Boolean = false,
    val reason: String = "",
  )

  /** Length of the filtered blend between two songs with a beat that are not beat-synced. */
  const val FILTERED_BLEND_MS = 8_000L

  /**
   * @param positionMs where the outgoing song is now: a plan never starts in the past.
   * @param fadeMs the user's crossfade length, used by the smooth style.
   * @param beatSync whether a beat-matched blend may be planned: off, two songs with a beat get a
   *   filtered blend of at least [FILTERED_BLEND_MS] on the outgoing bar grid instead.
   * @param tailRoomMs time the blend must end before the outgoing song does (the mix engine eases
   *   the next song back to its own tempo and hands it over to the player there).
   */
  fun plan(
    out: Track,
    incoming: Track?,
    positionMs: Long,
    fadeMs: Long,
    beatSync: Boolean = true,
    tailRoomMs: Long = 0L,
  ): Plan? {
    val earliest = positionMs + 1_000
    if (beatSync && incoming != null && out.hasBeat && incoming.hasBeat) {
      val ratio = foldRatio(out.bpm / incoming.bpm)
      if (abs(ratio - 1f) <= MAX_TEMPO_CHANGE) {
        beatMatch(out, incoming, ratio, earliest, tailRoomMs)?.let { return it }
      }
    }
    return smooth(out, incoming, earliest, fadeMs, beatSync, tailRoomMs)
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

  /**
   * How well [candidate] mixes after [out]: 0 = only a smooth crossfade, up to 2 for the same tempo
   * (a beat match), +1 for a compatible key. Null candidates (not analyzed) score nothing.
   */
  fun mixScore(out: Track, candidate: Track?): Float {
    if (candidate == null) return 0f
    var score = 0f
    if (out.hasBeat && candidate.hasBeat) {
      val off = abs(foldRatio(out.bpm / candidate.bpm) - 1f)
      if (off <= MAX_TEMPO_CHANGE) score += 2f - off / MAX_TEMPO_CHANGE
    }
    if (keysCompatible(out, candidate) == true) score += 1f
    return score
  }

  /**
   * Among the next few songs ([candidates], the first being the one that would play next), the one
   * to move up so it plays next: only when it mixes clearly better than that one. Null keeps the
   * order.
   */
  fun pickBestNext(out: Track, candidates: List<Track?>): Int? {
    if (candidates.size < 2) return null
    val scores = candidates.map { mixScore(out, it) }
    val best = scores.indices.maxByOrNull { scores[it] } ?: return null
    // A key alone never reorders a next song that already beat-matches.
    return if (best != 0 && scores[best] >= scores[0] + 1.5f) best else null
  }

  private fun beatMatch(
    out: Track,
    incoming: Track,
    speed: Float,
    earliest: Long,
    tailRoomMs: Long = 0L,
  ): Plan? {
    val outPeriod = out.periodMs
    val outBar = outPeriod * 4
    val inPeriod = incoming.periodMs
    val inBar = inPeriod * 4
    val audibleEnd = out.audibleEndMs
    val hardEnd = (out.durationMs - END_MARGIN_MS - tailRoomMs).toDouble()

    val inFirstBar = snapUp((incoming.trustedFirstSoundMs ?: 0L).toDouble(), incoming.headBars, inBar)

    // 8 bars when the tempo allows, 4 when the keys clash (two harmonies at once).
    var beats = if (32 * outPeriod <= MAX_BLEND_MS) 32 else 16
    if (keysCompatible(out, incoming) == false) beats = minOf(beats, 16)
    while (beats > MIN_BLEND_BEATS && beats * outPeriod > MAX_BLEND_MS) beats -= 4

    // Start on the outro if there is one, else as late as a whole blend still fits; never
    // before the song's last minute.
    var trigger =
      out.trustedMixOutMs
        ?.toDouble()
        ?.takeIf { it + beats * outPeriod <= hardEnd }
        ?.let { snapNearest(it, out.tailBars, outBar) }
        ?: snapDown(minOf(audibleEnd, hardEnd) - beats * outPeriod, out.tailBars, outBar)
    val floor = maxOf(earliest.toDouble(), out.earliestMixOutMs)
    if (trigger < floor) trigger = snapUp(floor, out.tailBars, outBar)
    // Late (a seek near the end): shrink the blend by whole bars to what is left.
    while (beats > MIN_BLEND_BEATS && trigger + beats * outPeriod > hardEnd) beats -= 4
    if (trigger + beats * outPeriod > hardEnd) return null

    val overlapMs = beats * outPeriod
    // The incoming body arrives as the blend ends: start its intro that many beats earlier.
    val incomingStart =
      incoming.trustedMixInMs?.let { (it - beats * inPeriod).coerceAtLeast(inFirstBar) }
        ?.coerceAtMost(MAX_MIX_IN_MS.toDouble()) ?: inFirstBar

    // Swap the basslines on a phrase boundary near the middle: 4 bars into an 8-bar blend.
    val phrase = if (beats >= 32) 16 else if (beats >= 16) 8 else 4
    val swapBeat =
      (Math.round(beats / 2.0 / phrase) * phrase).toInt().coerceIn(4, maxOf(4, beats - 4))
    return Plan(
      style = Style.BEAT_MATCH,
      triggerMs = trigger.roundToLong(),
      incomingStartMs = incomingStart.roundToLong().coerceAtLeast(0L),
      overlapMs = overlapMs.roundToLong(),
      incomingSpeed = speed,
      restoreMs = if (speed == 1f) 0L else (RESTORE_BEATS * outPeriod).roundToLong(),
      bassSwapAt = swapBeat.toFloat() / beats,
      beatFraction = 1f / beats,
      filters = true,
      reason = "beat match ${beats} beats",
    )
  }

  private fun smooth(
    out: Track,
    incoming: Track?,
    earliest: Long,
    fadeMs: Long,
    beatSync: Boolean = true,
    tailRoomMs: Long = 0L,
  ): Plan? {
    val bothBeat = incoming != null && out.hasBeat && incoming.hasBeat
    // Two songs with drums blend longer: the filters keep them from clashing while they overlap.
    val fade =
      (if (bothBeat) maxOf(fadeMs, FILTERED_BLEND_MS) else fadeMs).coerceIn(2_000L, 12_000L).toDouble()
    val hardEnd = (out.durationMs - END_MARGIN_MS - tailRoomMs).toDouble()
    val audibleEnd = out.audibleEndMs
    var trigger =
      out.trustedMixOutMs?.toDouble()?.takeIf { it + fade <= audibleEnd }
        ?: (minOf(audibleEnd, hardEnd) - fade)
    if (out.hasBeat) {
      val bar = out.periodMs * 4
      val snapped = snapNearest(trigger, out.tailBars, bar)
      if (snapped + fade <= hardEnd) trigger = snapped
    }
    trigger = maxOf(trigger, out.earliestMixOutMs, earliest.toDouble())
    val overlap = minOf(fade, hardEnd - trigger)
    if (overlap < MIN_SMOOTH_MS) return null
    return Plan(
      style = Style.SMOOTH,
      triggerMs = trigger.roundToLong(),
      incomingStartMs = incoming?.trustedFirstSoundMs?.coerceIn(0L, 10_000L) ?: 0L,
      overlapMs = overlap.roundToLong(),
      // Two beats that do not line up clash least with the drums of each thinned in turn.
      filters = bothBeat,
      reason = when {
        incoming == null -> "smooth: next not analyzed"
        !out.hasBeat || !incoming.hasBeat -> "smooth: no clear beat"
        !beatSync -> "smooth: filtered blend (beat sync off)"
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
    /** High-pass sweep on the outgoing song, 0 (open) .. 1 (thin, ~900 Hz). */
    val outHighPass: Float = 0f,
    /** Low-pass sweep on the outgoing song, 0 (open) .. 1 (dull, ~700 Hz): its hi-hats and
     *  sibilance leave instead of piling onto the incoming song's. */
    val outLowPass: Float = 0f,
    /** High-pass on the incoming song, 0 (open) .. 1 (thin). */
    val inHighPass: Float = 0f,
  )

  /** Incoming high-pass before the bass swap: about 200 Hz, its kick and bass held back. */
  private const val IN_HIGH_PASS = 0.6f

  fun mixAt(plan: Plan, progress: Float): Mix {
    val p = progress.coerceIn(0f, 1f)
    if (plan.style == Style.SMOOTH) {
      val g = EqualPowerCurve.calculateGains(p)
      if (!plan.filters) return Mix(g.outgoingGain, g.incomingGain)
      // One bassline at a time, handed over in the middle; the incoming song comes in thin and
      // fills out, the outgoing one leaves dull: their drums never stack at full range.
      val swap = smoothstep(ramp(p, 0.4f, 0.6f))
      return Mix(
        outGain = g.outgoingGain,
        inGain = g.incomingGain,
        outBassCut = swap,
        inBassCut = 1f - swap,
        outLowPass = 0.8f * smoothstep(ramp(p, 0.3f, 1f)),
        inHighPass = 0.5f * (1f - smoothstep(ramp(p, 0f, 0.6f))),
      )
    }
    // Equal power, the incoming song a little ahead: it rises over the first 3/4 while the
    // outgoing one falls over the last 3/4, the sum held at constant power.
    var inGain = sin(PI / 2 * ramp(p, 0f, 0.75f)).toFloat()
    var outGain = sin(PI / 2 * (1f - ramp(p, 0.25f, 1f))).toFloat()
    val power = inGain * inGain + outGain * outGain
    if (power > 1f) {
      val k = 1f / sqrt(power)
      inGain *= k
      outGain *= k
    }
    // Bass swap over one bar from the phrase boundary: short enough that two basslines never
    // muddy, long enough not to be heard as a cut.
    val bar = 4 * plan.beatFraction
    val swap = smoothstep(ramp(p, plan.bassSwapAt, plan.bassSwapAt + bar))
    // The incoming song's low mids open gradually from halfway to the swap until its bass is in,
    // like a DJ turning the low EQ up, instead of all at once.
    val open = smoothstep(ramp(p, plan.bassSwapAt / 2, plan.bassSwapAt + bar))
    return Mix(
      outGain = outGain,
      inGain = inGain,
      outBassCut = swap,
      inBassCut = 1f - swap,
      // After the swap the outgoing song keeps no bass (shelf) and loses its highs (low-pass):
      // what fades out last is its mids, which mask least.
      outLowPass = smoothstep(ramp(p, plan.bassSwapAt, 1f)),
      inHighPass = IN_HIGH_PASS * (1f - open),
    )
  }

  private fun smoothstep(t: Float): Float = t * t * (3 - 2 * t)

  private fun ramp(x: Float, from: Float, to: Float): Float =
    if (to <= from) (if (x >= to) 1f else 0f) else ((x - from) / (to - from)).coerceIn(0f, 1f)
}
