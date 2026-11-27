package echo.music.iad1tya.playback.mix

import echo.music.iad1tya.playback.crossfade.AutomixPlanner
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * The next song's audio, decoded ahead, at the mixer's rate and channel count, from the frame the
 * blend starts it at. [read] never blocks: it returns what is decoded so far (0 if nothing yet).
 */
fun interface PcmSource {
  fun read(dst: FloatArray, maxFrames: Int): Int
}

/**
 * The beat mix on one clock: everything here counts samples, so two songs line up to the sample
 * whatever the phone's audio latency. Pure: the Android side feeds it the playing song's samples
 * (with their song position) and gets the mixed samples back; unit tests and the PC test bench
 * feed it files.
 *
 * One transition, in order:
 * 1. [processOutgoing] for the outgoing song's samples: untouched until the plan's trigger, then
 *    mixed with the next song (decoded ahead by [PcmSource], time-stretched to the outgoing tempo,
 *    started at the plan's incoming frame exactly at the trigger frame), with the plan's gains,
 *    bass swap and filter sweeps.
 * 2. After the blend the outgoing song is silent: its samples are replaced by the next song's while
 *    the stretch eases back to the next song's own tempo, then plays it sample for sample.
 * 3. [handoverFrame] is then where the player should be moved to (the next song, at that frame).
 *    While the player gets there, [fill] keeps the audio going. Its first samples of the next song
 *    ([processIncoming]) are matched to ours by correlation (decoders may differ by a few samples),
 *    and from the matching sample on the player's own audio is passed through: no seam.
 */
class MixEngine(val rate: Int, val channels: Int = 2) {

  enum class State {
    IDLE,
    WAITING,
    BLENDING,
    EASING,
    READY,
    HANDING_OVER,
    DONE,
  }

  var state = State.IDLE
    private set

  /** What happened, for the log. */
  var note: String = ""
    private set

  private var plan: AutomixPlanner.Plan? = null
  private var source: PcmSource? = null
  var outgoingId: String? = null
    private set
  var incomingId: String? = null
    private set

  private var triggerFrame = 0L
  private var blendFrames = 1L
  private var easeFrames = 0L
  private var easeStartedAt = 0L
  private var speed = 1.0
  private lateinit var stretcher: TimeStretcher
  private lateinit var stripA: ChannelStrip
  private lateinit var stripB: ChannelStrip

  /** Raw next-song frames fed to the stretcher, kept a few seconds for the handover match. */
  private var history = FloatArray(0)
  private var historyStart = 0L
  private var historyFrames = 0
  private var nextRawFrame = 0L

  /** Raw frames read ahead of the stretcher for a handover match, and a scratch chunk. */
  private var ahead = FloatArray(0)
  private var aheadFrames = 0
  private var rawChunk = FloatArray(0)

  /** Stretched next-song output not yet mixed. */
  private var pending = FloatArray(0)
  private var pendingFrames = 0

  /** Output frames of the next song produced so far (from the trigger). */
  private var producedIn = 0L

  /** Player frames of the next song received during the handover, waiting to be matched. */
  private var playerBuffer = FloatArray(0)
  private var playerBufferStart = 0L
  private var playerBufferFrames = 0
  /** How alike the player's audio and ours were at the match (1 = the same samples). */
  private var lastMatchScore = 0.0
  /** The match found no common sample: the player plays other audio than we decoded. */
  private var differentAudio = false
  /** Our next raw frame when the player's first block of the next song came. */
  private var firstPlayerQ = 0L
  /** Player frame = our raw frame - offset, once matched. */
  private var matchOffset: Long? = null

  fun arm(
    outgoingId: String,
    incomingId: String,
    plan: AutomixPlanner.Plan,
    source: PcmSource,
    easeMs: Long,
  ) {
    reset()
    this.outgoingId = outgoingId
    this.incomingId = incomingId
    this.plan = plan
    this.source = source
    triggerFrame = msToFrames(plan.triggerMs)
    blendFrames = msToFrames(plan.overlapMs).coerceAtLeast(1)
    easeFrames = if (plan.incomingSpeed == 1f) 0L else msToFrames(easeMs)
    speed = plan.incomingSpeed.toDouble()
    val start = msToFrames(plan.incomingStartMs)
    stretcher = TimeStretcher(channels, rate, start).also { it.speed = speed }
    nextRawFrame = start
    historyStart = start
    stripA = ChannelStrip(rate, channels).apply { gain = 1f }
    stripB = ChannelStrip(rate, channels)
    state = State.WAITING
    note = "armed: mix at frame $triggerFrame, next from frame $start, speed ${plan.incomingSpeed}"
  }

  fun reset() {
    state = State.IDLE
    plan = null
    source = null
    outgoingId = null
    incomingId = null
    pendingFrames = 0
    producedIn = 0L
    historyFrames = 0
    aheadFrames = 0
    playerBufferFrames = 0
    matchOffset = null
    differentAudio = false
  }

  val isActive: Boolean
    get() = state != State.IDLE && state != State.DONE

  /**
   * The outgoing song's [frames] interleaved frames from [block], the first at [songFrame] of it.
   * Returns as many frames, mixed. Before the trigger they come back unchanged.
   */
  fun processOutgoing(block: FloatArray, frames: Int, songFrame: Long): FloatArray {
    val p = plan ?: return block
    val out = FloatArray(frames * channels)
    var f = 0
    while (f < frames) {
      val t = songFrame + f
      val n = minOf(SUB_BLOCK, frames - f).let { if (t < triggerFrame) minOf(it, (triggerFrame - t).toInt()) else it }
      if (t < triggerFrame) {
        // Before the blend: the outgoing song alone, through its strip so its filters are warm.
        stripA.gain = 1f
        stripA.processAdd(block, f * channels, out, f * channels, n)
      } else {
        if (state == State.WAITING) {
          state = State.BLENDING
          note = "blend started at frame $t"
        }
        val progress = ((t - triggerFrame).toDouble() / blendFrames).coerceIn(0.0, 1.0).toFloat()
        val mix = AutomixPlanner.mixAt(p, progress)
        val outGone = progress >= 1f
        if (outGone && state == State.BLENDING) {
          state = if (easeFrames > 0) State.EASING else State.READY
          easeStartedAt = producedIn
          if (state == State.READY) enterExact()
        }
        stripA.gain = if (outGone) 0f else mix.outGain
        stripA.bassCut = mix.outBassCut
        stripA.highPass = mix.outHighPass
        stripA.lowPass = mix.outLowPass
        stripB.gain = mix.inGain
        stripB.bassCut = mix.inBassCut
        stripB.highPass = mix.inHighPass
        stripB.lowPass = 0f
        stripA.processAdd(block, f * channels, out, f * channels, n)
        val b = takeIncoming(n)
        stripB.processAdd(b, 0, out, f * channels, n)
      }
      f += n
    }
    return out
  }

  /** The next song's sample, at our output, that the player should be moved to; null until due. */
  val handoverFrame: Long?
    get() = if (state == State.READY || state == State.HANDING_OVER) nextOutputRawFrame() else null

  /**
   * The next song's frame our next output sample is of, as far as it is playing: exact once it
   * plays at its own tempo ([handoverFrame]), within the stretcher's 10 ms during the blend. For the
   * player's clock, which follows the next song as soon as it is handed over.
   */
  val incomingPositionFrame: Long?
    get() =
      when (state) {
        State.BLENDING, State.EASING ->
          (stretcher.nominalInputPosition - pendingFrames * stretcher.speed).toLong()
        State.READY, State.HANDING_OVER -> nextOutputRawFrame()
        else -> null
      }

  /** The player was asked to move to the next song: from now on [fill] and [processIncoming]. */
  fun startHandover() {
    if (state == State.READY) {
      state = State.HANDING_OVER
      note = "handing over at frame ${nextOutputRawFrame()}"
    }
  }

  /** Our next song's audio while the player gets ready (nothing of the player to mix with). */
  fun fill(frames: Int): FloatArray {
    val out = FloatArray(frames * channels)
    if (state == State.IDLE || state == State.DONE) return out
    stripB.gain = 1f
    stripB.bassCut = 0f
    stripB.highPass = 0f
    val b = takeIncoming(frames)
    stripB.processAdd(b, 0, out, 0, frames)
    return out
  }

  /**
   * The player's own audio of the next song, the first frame at [songFrame], during the handover.
   * Returns what to output: ours until the player's matches, then the player's. The frame count can
   * differ from [frames]: fewer (even none) while a player that landed behind catches up, more
   * when the player's audio is joined after ours.
   */
  fun processIncoming(block: FloatArray, frames: Int, songFrame: Long): FloatArray {
    if (state != State.HANDING_OVER) return block
    appendPlayer(block, frames, songFrame)
    if (matchOffset == null) {
      if (playerBufferFrames < MATCH_FRAMES + MATCH_RANGE) {
        // Not enough of it to match yet: keep ours going, as many frames as came in.
        return fill(frames)
      }
      // Our decode must reach past the player's first frames by the whole search range.
      readAheadTo(playerBufferStart + MATCH_FRAMES + MATCH_RANGE + 1)
      matchOffset = findOffset()
      note = "player matched at offset ${matchOffset} frames (score ${"%.3f".format(lastMatchScore)})"
      if (lastMatchScore < MIN_MATCH_SCORE) {
        // Not the same audio (another master or edit of the song, or another stream): no sample
        // to splice on. Fade over to the player where it is instead of jumping inside it.
        differentAudio = true
        note += ": the player plays different audio, crossfaded over to it"
      }
    }
    if (differentAudio && playerBufferFrames < WEAK_FADE_FRAMES) {
      // Enough of the player's audio for the longer crossfade first.
      return fill(frames)
    }
    val offset = matchOffset!!
    // Our next frame is raw frame q; the player's same sample is q - offset.
    val q = nextOutputRawFrame()
    // Other audio: its time runs alongside ours from its first block (what we played meanwhile
    // stands for those frames of it).
    val wantPlayer = if (differentAudio) playerBufferStart + (q - firstPlayerQ) else q - offset
    val out = FloatArray((playerBufferFrames + frames) * channels)
    var produced = 0
    if (wantPlayer < playerBufferStart) {
      // The player is ahead of us: ours until it is reached.
      val gap = (playerBufferStart - wantPlayer).toInt().coerceAtMost(frames)
      val ours = fill(gap)
      System.arraycopy(ours, 0, out, 0, gap * channels)
      produced = gap
      if (gap == frames) return out.copyOf(produced * channels)
    } else {
      // The player is behind: skip what we already played. If that is all it gave so far, output
      // nothing for this block: a player fills the audio output faster than it plays, so its next
      // blocks come at once and catch up with ours.
      val behind = wantPlayer - playerBufferStart
      if (behind > playerBufferFrames - rate / 100) return FloatArray(0)
      dropPlayer(behind.toInt())
    }
    // Switch, with a 5 ms crossfade against our own copy of the same sample (equal power over
    // 300 ms when the player's audio is not the same).
    val fade = (if (differentAudio) WEAK_FADE_FRAMES else rate / 200).coerceAtMost(playerBufferFrames)
    val ours = fill(fade)
    for (i in 0 until fade) {
      val t = (i + 1).toFloat() / (fade + 1)
      val wIn = if (differentAudio) kotlin.math.sin(t * Math.PI / 2).toFloat() else t
      val wOut = if (differentAudio) kotlin.math.cos(t * Math.PI / 2).toFloat() else 1 - t
      for (c in 0 until channels) {
        val k = i * channels + c
        out[(produced + i) * channels + c] = ours[k] * wOut + playerBuffer[k] * wIn
      }
    }
    val rest = playerBufferFrames - fade
    System.arraycopy(playerBuffer, fade * channels, out, (produced + fade) * channels, rest * channels)
    produced += fade + rest
    playerBufferFrames = 0
    state = State.DONE
    note += "; joined the player"
    return out.copyOf(produced * channels)
  }

  // ---------------------------------------------------------------------------------------------

  private fun enterExact() {
    stretcher.speed = 1.0
    stretcher.exact = true
  }

  /** Our raw next-song frame the next output sample is. */
  private fun nextOutputRawFrame(): Long = stretcher.exactInputPosition - pendingFrames

  private fun takeIncoming(frames: Int): FloatArray {
    while (pendingFrames < frames) {
      if (state == State.EASING) {
        val t = ((producedIn - easeStartedAt).toDouble() / easeFrames).coerceIn(0.0, 1.0)
        val s = t * t * (3 - 2 * t)
        stretcher.speed = speed + (1.0 - speed) * s
        if (t >= 1.0) {
          state = State.READY
          enterExact()
          note = "eased to its own tempo"
        }
      }
      val got = nextRaw(PULL_FRAMES)
      if (got <= 0) break
      val stretched = stretcher.process(rawChunk, 0, got)
      appendPending(stretched, stretched.size / channels)
    }
    val n = minOf(frames, pendingFrames)
    val out = FloatArray(frames * channels)
    System.arraycopy(pending, 0, out, 0, n * channels)
    System.arraycopy(pending, n * channels, pending, 0, (pendingFrames - n) * channels)
    pendingFrames -= n
    producedIn += frames
    if (n < frames && note.indexOf("underrun") < 0) note += "; next song not decoded in time (underrun)"
    return out
  }

  /**
   * Up to [max] next raw frames of the next song into [rawChunk], for the stretcher: those read
   * ahead for a handover match first, then fresh from the source (kept in the history too).
   */
  private fun nextRaw(max: Int): Int {
    if (rawChunk.size < max * channels) rawChunk = FloatArray(max * channels)
    if (aheadFrames > 0) {
      val n = minOf(max, aheadFrames)
      System.arraycopy(ahead, 0, rawChunk, 0, n * channels)
      System.arraycopy(ahead, n * channels, ahead, 0, (aheadFrames - n) * channels)
      aheadFrames -= n
      return n
    }
    val got = source?.read(rawChunk, max) ?: 0
    if (got > 0) {
      keepHistory(rawChunk, got)
      nextRawFrame += got
    }
    return got
  }

  /** Reads the source ahead (into the history) until it holds raw frame [until]. */
  private fun readAheadTo(until: Long) {
    val tmp = FloatArray(PULL_FRAMES * channels)
    while (nextRawFrame <= until) {
      val got = source?.read(tmp, PULL_FRAMES) ?: 0
      if (got <= 0) break
      keepHistory(tmp, got)
      nextRawFrame += got
      val need = (aheadFrames + got) * channels
      if (need > ahead.size) ahead = ahead.copyOf(maxOf(need, ahead.size * 2))
      System.arraycopy(tmp, 0, ahead, aheadFrames * channels, got * channels)
      aheadFrames += got
    }
  }

  private fun appendPending(x: FloatArray, frames: Int) {
    val need = (pendingFrames + frames) * channels
    if (need > pending.size) pending = pending.copyOf(maxOf(need, pending.size * 2))
    System.arraycopy(x, 0, pending, pendingFrames * channels, frames * channels)
    pendingFrames += frames
  }

  private fun keepHistory(x: FloatArray, frames: Int) {
    val need = (historyFrames + frames) * channels
    if (need > history.size) history = history.copyOf(maxOf(need, history.size * 2))
    System.arraycopy(x, 0, history, historyFrames * channels, frames * channels)
    historyFrames += frames
    val max = HISTORY_SECONDS * rate
    if (historyFrames > max) {
      val drop = historyFrames - max
      System.arraycopy(history, drop * channels, history, 0, max * channels)
      historyFrames = max
      historyStart += drop
    }
  }

  private fun appendPlayer(x: FloatArray, frames: Int, songFrame: Long) {
    if (playerBufferFrames == 0) {
      playerBufferStart = songFrame
      firstPlayerQ = nextOutputRawFrame()
    }
    val need = (playerBufferFrames + frames) * channels
    if (need > playerBuffer.size) playerBuffer = playerBuffer.copyOf(maxOf(need, playerBuffer.size * 2))
    System.arraycopy(x, 0, playerBuffer, playerBufferFrames * channels, frames * channels)
    playerBufferFrames += frames
  }

  private fun dropPlayer(n: Int) {
    val d = n.coerceIn(0, playerBufferFrames)
    System.arraycopy(playerBuffer, d * channels, playerBuffer, 0, (playerBufferFrames - d) * channels)
    playerBufferFrames -= d
    playerBufferStart += d
  }

  /**
   * Offset between the player's decode and ours of the same song: the shift (within
   * [MATCH_RANGE]) at which its first [MATCH_FRAMES] frames best match our history.
   */
  private fun findOffset(): Long {
    var best = 0L
    var bestScore = Double.NEGATIVE_INFINITY
    for (d in -MATCH_RANGE..MATCH_RANGE) {
      // Player frame playerBufferStart + i is our raw frame playerBufferStart + i + d.
      val ourStart = playerBufferStart + d - historyStart
      if (ourStart < 0 || ourStart + MATCH_FRAMES > historyFrames) continue
      var dot = 0.0
      var e1 = 1e-12
      var e2 = 1e-12
      for (i in 0 until MATCH_FRAMES step 2) {
        var a = 0f
        var b = 0f
        for (c in 0 until channels) {
          a += playerBuffer[i * channels + c]
          b += history[((ourStart + i) * channels + c).toInt()]
        }
        dot += a * b
        e1 += a * a
        e2 += b * b
      }
      val score = dot / sqrt(e1 * e2)
      if (score > bestScore) {
        bestScore = score
        best = d.toLong()
      }
    }
    lastMatchScore = bestScore
    return best
  }

  private fun msToFrames(ms: Long): Long = (ms * rate / 1000.0).roundToLong()

  companion object {
    private const val SUB_BLOCK = 64
    private const val PULL_FRAMES = 2048
    private const val HISTORY_SECONDS = 6
    /** Frames of the player's audio compared, and how far either way (~25 ms at 48 kHz). */
    const val MATCH_FRAMES = 2048
    const val MATCH_RANGE = 1200
    /**
     * Below this the player's audio and ours are not the same samples (the same decode scores
     * about 1, two decoders of one file over 0.95): not spliced, crossfaded.
     */
    const val MIN_MATCH_SCORE = 0.8
    private const val WEAK_FADE_FRAMES = 14_400
  }
}
