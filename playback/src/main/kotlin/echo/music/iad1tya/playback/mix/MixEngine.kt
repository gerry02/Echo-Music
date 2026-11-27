package echo.music.iad1tya.playback.mix

import echo.music.iad1tya.playback.crossfade.AutomixPlanner
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * A song's audio, decoded ahead, at the mixer's rate and channel count, from a known frame.
 * [read] never blocks: it returns what is decoded so far (0 if nothing yet).
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
 * 1. Before the plan's trigger the outgoing song's samples ([processOutgoing]) come back untouched.
 * 2. From the trigger the next song (decoded ahead, time-stretched to the outgoing tempo, started
 *    at the plan's incoming frame exactly on the trigger frame) is mixed in with the plan's gains,
 *    bass swap and filters.
 * 3. A few bars in ([handoverDue]) the player is moved to the next song, so the app shows it while
 *    the blend goes on; the player then plays the next song at the blend's tempo (its own speed is
 *    set to the stretch, which applies after this mixer). [switchToNext] marks that moment, sample
 *    exact: from it the outgoing song's tail comes from its own decode ([arm]'s outgoing source,
 *    matched by correlation to the player's last samples), pre-stretched by the inverse so it keeps
 *    its tempo; our decode of the next song plays raw until the player's audio of it arrives
 *    ([processIncoming]) and is joined on its sample.
 * 4. When the blend is over the output is the player's own audio: [State.DONE].
 */
class MixEngine(val rate: Int, val channels: Int = 2) {

  enum class State {
    IDLE,
    /** Armed, before the trigger. */
    WAITING,
    /** The blend, the player on the outgoing song. */
    BLENDING,
    /** The player moved to the next song; its audio not joined yet. */
    SWITCHING,
    /** The blend, the player on the next song, the outgoing tail from our decode. */
    MIXING,
    DONE,
  }

  var state = State.IDLE
    private set

  /** What happened, for the log. */
  var note: String = ""
    private set

  private var plan: AutomixPlanner.Plan? = null
  var outgoingId: String? = null
    private set
  var incomingId: String? = null
    private set

  private var triggerFrame = 0L
  private var blendFrames = 1L
  private var handoverAtFrame = 0L
  private var speed = 1.0
  private lateinit var stripA: ChannelStrip
  private lateinit var stripB: ChannelStrip

  /** The next song: its decode, stretched while the player is on the outgoing one. */
  private val inc = Stream()
  /** The outgoing song's own decode, for its tail once the player is on the next song. */
  private val out = Stream()
  private var outSource: PcmSource? = null

  /** The player's last outgoing samples, to match our decode of it at the switch. */
  private val playerA = Ring()
  /** Outgoing song frame the next of the player's samples is. */
  private var nextA = 0L
  /** Outgoing song frame (of our decode) the next output sample is, after the switch. */
  private var outPosition = 0.0

  /** Player frames of the next song received after the switch, waiting to be matched. */
  private var playerBuffer = FloatArray(0)
  private var playerBufferStart = 0L
  private var playerBufferFrames = 0
  private var lastMatchScore = 0.0
  private var differentAudio = false
  private var firstPlayerQ = 0L
  private var matchOffset: Long? = null

  /**
   * @param incoming the next song from [AutomixPlanner.Plan.incomingStartMs].
   * @param outgoing the outgoing song from [outgoingFromMs] (before the handover).
   * @param handoverAfterMs how long into the blend the player moves to the next song.
   */
  fun arm(
    outgoingId: String,
    incomingId: String,
    plan: AutomixPlanner.Plan,
    incoming: PcmSource,
    outgoing: PcmSource?,
    outgoingFromMs: Long,
    handoverAfterMs: Long,
  ) {
    reset()
    this.outgoingId = outgoingId
    this.incomingId = incomingId
    this.plan = plan
    triggerFrame = msToFrames(plan.triggerMs)
    blendFrames = msToFrames(plan.overlapMs).coerceAtLeast(1)
    handoverAtFrame = triggerFrame + msToFrames(handoverAfterMs).coerceIn(0L, blendFrames - 1)
    speed = plan.incomingSpeed.toDouble()
    inc.start(incoming, msToFrames(plan.incomingStartMs), speed)
    outSource = outgoing
    out.start(outgoing, msToFrames(outgoingFromMs), 1.0 / speed)
    stripA = ChannelStrip(rate, channels).apply { gain = 1f }
    stripB = ChannelStrip(rate, channels)
    state = State.WAITING
    note = "armed: mix at frame $triggerFrame, next from frame ${inc.startFrame}, speed ${plan.incomingSpeed}"
  }

  fun reset() {
    state = State.IDLE
    plan = null
    outgoingId = null
    incomingId = null
    inc.clear()
    out.clear()
    outSource = null
    outMatch = null
    outMatchGaveUp = false
    bothAudibleFrames = 0L
    playerA.clear()
    playerBufferFrames = 0
    matchOffset = null
    differentAudio = false
  }

  val isActive: Boolean
    get() = state != State.IDLE && state != State.DONE

  /**
   * Time to move the player to the next song: a few bars into the blend, once our own decode of
   * the outgoing song is ready and matched to the player's (it carries the outgoing tail from
   * then on). Without it the player stays on the outgoing song to the end of the blend: the screen
   * changes later, but the blend is heard whole.
   */
  val handoverDue: Boolean
    get() {
      if (state != State.BLENDING || nextA < handoverAtFrame) return false
      if (progressAt(nextA.toDouble()) >= 1f) return true
      if (outMatch == null && !outMatchGaveUp) {
        outMatch = out.matchOffset(playerA, nextA, channels)
        if (outMatch == null && progressAt(nextA.toDouble()) > 0.5f) {
          outMatchGaveUp = true
          note += "; outgoing decode not ready or not matching: the player stays on it to the end of the blend"
        }
      }
      return outMatch != null
    }

  private var outMatch: Pair<Long, Double>? = null
  private var outMatchGaveUp = false

  /** Output frames in which both songs were heard (each above -20 dB), for the log. */
  var bothAudibleFrames = 0L
    private set

  val bothAudibleMs: Long
    get() = bothAudibleFrames * 1000 / rate

  /** The next song's frame our next output sample is of (exact once it plays raw). */
  val incomingPositionFrame: Long?
    get() =
      when (state) {
        State.BLENDING -> inc.nominalPosition().toLong()
        State.SWITCHING -> inc.exactPosition()
        else -> null
      }

  /** Where the player should be sent: the next song's frame now (null before the blend). */
  val handoverFrame: Long?
    get() = if (state == State.BLENDING) inc.nominalPosition().toLong() else null

  /** The blend's stretch of the next song: the player's speed from the switch on. */
  val incomingSpeed: Double
    get() = speed

  /**
   * The outgoing song's [frames] interleaved frames from [block], the first at [songFrame] of it.
   * Returns as many frames, mixed. Before the trigger they come back unchanged. After the switch
   * the player may still hand over a few of them: they stand for frames of our own decode.
   */
  fun processOutgoing(block: FloatArray, frames: Int, songFrame: Long): FloatArray {
    val p = plan ?: return block
    if (state == State.SWITCHING || state == State.MIXING) return fill(frames)
    if (state == State.DONE || state == State.IDLE) return block
    playerA.add(block, frames, songFrame, channels)
    nextA = songFrame + frames
    val result = FloatArray(frames * channels)
    var f = 0
    while (f < frames) {
      val t = songFrame + f
      val n = minOf(SUB_BLOCK, frames - f).let { if (t < triggerFrame) minOf(it, (triggerFrame - t).toInt()) else it }
      if (t < triggerFrame) {
        stripA.gain = 1f
        stripA.processAdd(block, f * channels, result, f * channels, n)
      } else {
        if (state == State.WAITING) {
          state = State.BLENDING
          note = "blend started at frame $t"
        }
        applyMix(p, progressAt(t.toDouble()))
        if (stripA.gain > AUDIBLE && stripB.gain > AUDIBLE) bothAudibleFrames += n
        stripA.processAdd(block, f * channels, result, f * channels, n)
        val b = inc.take(n, channels)
        stripB.processAdd(b, 0, result, f * channels, n)
      }
      f += n
    }
    return result
  }

  /**
   * The player was moved to the next song (its speed set to [incomingSpeed]): what comes out from
   * now on is the outgoing tail from our decode, pre-stretched to keep its tempo, and the next song
   * raw. Called once, at the exact point the output's speed changes (or the flush, at speed 1).
   */
  fun switchToNext() {
    if (state != State.BLENDING && state != State.WAITING) return
    if (state == State.WAITING) {
      note = "switched before the blend"
    }
    // Our decode of the outgoing song, matched to the player's last samples of it (at the end of
    // the blend it is no longer needed).
    val d = if (progressAt(nextA.toDouble()) >= 1f) null else out.matchOffset(playerA, nextA, channels)
    if (d == null && progressAt(nextA.toDouble()) >= 1f) {
      out.disable()
    } else if (d == null) {
      note += "; outgoing decode not matched, its tail fades out"
      out.disable()
    } else {
      out.seekRaw(nextA + d.first, channels)
      note += "; outgoing tail matched at offset ${d.first} (score ${"%.3f".format(d.second)})"
    }
    outPosition = nextA.toDouble()
    inc.goRaw()
    state = State.SWITCHING
  }

  /** Mixed output when the player gives nothing of the next song yet (its seek, a gap). */
  fun fill(frames: Int): FloatArray {
    if (state != State.SWITCHING && state != State.MIXING) return FloatArray(frames * channels)
    return mixAfterSwitch(inc.take(frames, channels), frames)
  }

  /**
   * The player's own audio of the next song, the first frame at [songFrame], after the switch.
   * Returns what to output: the outgoing tail over our next song until the player's matches, then
   * over the player's. The frame count can differ from [frames]: fewer (even none) while a player
   * that landed behind catches up, more when the player's audio is joined after ours.
   */
  fun processIncoming(block: FloatArray, frames: Int, songFrame: Long): FloatArray {
    when (state) {
      State.MIXING -> return mixAfterSwitch(block, frames)
      State.SWITCHING -> Unit
      else -> return block
    }
    appendPlayer(block, frames, songFrame)
    if (matchOffset == null) {
      if (playerBufferFrames < MATCH_FRAMES + MATCH_RANGE) return fill(frames)
      inc.readAheadTo(playerBufferStart + MATCH_FRAMES + MATCH_RANGE + 1, channels)
      val m = inc.findOffset(playerBuffer, playerBufferStart, channels)
      matchOffset = m.first
      lastMatchScore = m.second
      note += "; player matched at offset ${m.first} frames (score ${"%.3f".format(m.second)})"
      if (lastMatchScore < MIN_MATCH_SCORE) {
        differentAudio = true
        note += ": the player plays different audio, crossfaded over to it"
      }
    }
    if (differentAudio && playerBufferFrames < WEAK_FADE_FRAMES) return fill(frames)
    val offset = matchOffset!!
    val q = inc.exactPosition()
    val wantPlayer = if (differentAudio) playerBufferStart + (q - firstPlayerQ) else q - offset
    // The next song's raw frames to output now: ours until the player's, then the player's.
    val raw = FloatArray((playerBufferFrames + frames) * channels)
    var produced = 0
    if (wantPlayer < playerBufferStart) {
      val gap = (playerBufferStart - wantPlayer).toInt().coerceAtMost(frames)
      System.arraycopy(inc.take(gap, channels), 0, raw, 0, gap * channels)
      produced = gap
      if (gap == frames) return mixAfterSwitch(raw.copyOf(produced * channels), produced)
    } else {
      val behind = wantPlayer - playerBufferStart
      if (behind > playerBufferFrames - rate / 100) return FloatArray(0)
      dropPlayer(behind.toInt())
    }
    val fade = (if (differentAudio) WEAK_FADE_FRAMES else rate / 200).coerceAtMost(playerBufferFrames)
    val ours = inc.take(fade, channels)
    for (i in 0 until fade) {
      val t = (i + 1).toFloat() / (fade + 1)
      val wIn = if (differentAudio) kotlin.math.sin(t * Math.PI / 2).toFloat() else t
      val wOut = if (differentAudio) kotlin.math.cos(t * Math.PI / 2).toFloat() else 1 - t
      for (c in 0 until channels) {
        val k = i * channels + c
        raw[(produced + i) * channels + c] = ours[k] * wOut + playerBuffer[k] * wIn
      }
    }
    val rest = playerBufferFrames - fade
    System.arraycopy(playerBuffer, fade * channels, raw, (produced + fade) * channels, rest * channels)
    produced += fade + rest
    playerBufferFrames = 0
    state = State.MIXING
    note += "; joined the player"
    return mixAfterSwitch(raw.copyOf(produced * channels), produced)
  }

  // ---------------------------------------------------------------------------------------------

  private fun progressAt(outgoingFrame: Double): Float =
    ((outgoingFrame - triggerFrame) / blendFrames).coerceIn(0.0, 1.0).toFloat()

  /**
   * Each song plays at its own loudness level: the output's normalization follows the song the
   * player is on, so before the switch the next song is brought to its level relative to the
   * outgoing one's, and after it the outgoing tail relative to the next one's. No jump at either
   * end of the blend.
   */
  fun setLevels(nextBeforeSwitch: Float, outgoingAfterSwitch: Float) {
    nextLevel = nextBeforeSwitch
    outgoingLevel = outgoingAfterSwitch
  }

  private var nextLevel = 1f
  private var outgoingLevel = 1f

  private val switched: Boolean
    get() = state == State.SWITCHING || state == State.MIXING || state == State.DONE

  private fun applyMix(p: AutomixPlanner.Plan, progress: Float) {
    val mix = AutomixPlanner.mixAt(p, progress)
    stripA.gain = (if (progress >= 1f) 0f else mix.outGain) * (if (switched) outgoingLevel else 1f)
    stripA.bassCut = mix.outBassCut
    stripA.highPass = mix.outHighPass
    stripA.lowPass = mix.outLowPass
    stripB.gain = mix.inGain * (if (switched) 1f else nextLevel)
    stripB.bassCut = mix.inBassCut
    stripB.highPass = mix.inHighPass
    stripB.lowPass = 0f
  }

  /** [nextSong] (raw, [frames]) through its strip, with the outgoing tail over it. */
  private fun mixAfterSwitch(nextSong: FloatArray, frames: Int): FloatArray {
    val p = plan ?: return nextSong
    val result = FloatArray(frames * channels)
    var f = 0
    var tailDone = false
    while (f < frames) {
      val n = minOf(SUB_BLOCK, frames - f)
      val progress = progressAt(outPosition)
      applyMix(p, progress)
      stripB.processAdd(nextSong, f * channels, result, f * channels, n)
      if (progress < 1f && out.enabled) {
        if (stripA.gain > AUDIBLE && stripB.gain > AUDIBLE) bothAudibleFrames += n
        val a = out.take(n, channels)
        stripA.processAdd(a, 0, result, f * channels, n)
      } else if (progress >= 1f) {
        tailDone = true
      }
      outPosition += n / speed
      f += n
    }
    if ((tailDone || !out.enabled && progressAt(outPosition) >= 1f) && state == State.MIXING) {
      state = State.DONE
      out.clear()
      note += "; blend over"
    }
    return result
  }

  private fun appendPlayer(x: FloatArray, frames: Int, songFrame: Long) {
    if (playerBufferFrames == 0) {
      playerBufferStart = songFrame
      firstPlayerQ = inc.exactPosition()
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

  private fun msToFrames(ms: Long): Long = (ms * rate / 1000.0).roundToLong()

  /** The last frames the player gave of a song, with the song frame of the first one. */
  private inner class Ring {
    var data = FloatArray(0)
    var start = 0L
    var frames = 0

    fun add(x: FloatArray, n: Int, songFrame: Long, ch: Int) {
      if (frames == 0 || kotlin.math.abs(songFrame - (start + frames)) > 2) {
        frames = 0
        start = songFrame
      }
      val need = (frames + n) * ch
      if (need > data.size) data = data.copyOf(maxOf(need, data.size * 2))
      System.arraycopy(x, 0, data, frames * ch, n * ch)
      frames += n
      val max = RING_FRAMES
      if (frames > max) {
        val drop = frames - max
        System.arraycopy(data, drop * ch, data, 0, max * ch)
        frames = max
        start += drop
      }
    }

    fun clear() {
      frames = 0
    }
  }

  /**
   * One song's decode through a time stretcher: raw frames from [PcmSource] (kept a few seconds
   * for matching), stretched at [speed] (input frames per output frame) or raw.
   */
  private inner class Stream {
    var source: PcmSource? = null
    var startFrame = 0L
    var enabled = false
    private var stretcher: TimeStretcher? = null
    private var history = FloatArray(0)
    private var historyStart = 0L
    private var historyFrames = 0
    private var nextRawFrame = 0L
    /** Raw frames read but not yet given to the stretcher (from [feedFrom] on). */
    private var feedFrom = 0L
    private var pending = FloatArray(0)
    private var pendingFrames = 0
    private var chunk = FloatArray(0)
    private var underrun = false

    fun start(src: PcmSource?, from: Long, speed: Double) {
      clear()
      source = src
      startFrame = from
      historyStart = from
      nextRawFrame = from
      feedFrom = from
      enabled = src != null
      stretcher = TimeStretcher(channels, rate, from).also { it.speed = speed }
    }

    fun clear() {
      source = null
      enabled = false
      stretcher = null
      historyFrames = 0
      pendingFrames = 0
      underrun = false
    }

    fun disable() {
      enabled = false
    }

    fun nominalPosition(): Double {
      val s = stretcher ?: return 0.0
      return s.nominalInputPosition - pendingFrames * s.speed
    }

    fun exactPosition(): Long = (stretcher?.exactInputPosition ?: 0L) - pendingFrames

    /** From now on raw (speed 1, sample exact). */
    fun goRaw() {
      stretcher?.let {
        it.speed = 1.0
        it.exact = true
      }
    }

    /** Starts the stretched output over at raw frame [frame], dropping what was produced. */
    fun seekRaw(frame: Long, ch: Int) {
      readAheadTo(frame + rate, ch)
      val s = TimeStretcher(channels, rate, frame).also { it.speed = stretcher?.speed ?: 1.0 }
      stretcher = s
      pendingFrames = 0
      feedFrom = frame
    }

    fun take(frames: Int, ch: Int): FloatArray {
      val result = FloatArray(frames * ch)
      val s = stretcher
      if (!enabled || s == null) return result
      while (pendingFrames < frames) {
        // Feed the stretcher from the history first (read ahead), then fresh from the source.
        var fed = false
        if (feedFrom < historyStart + historyFrames) {
          val from = maxOf(feedFrom, historyStart)
          val n = minOf(PULL_FRAMES.toLong(), historyStart + historyFrames - from).toInt()
          val o = ((from - historyStart) * ch).toInt()
          val stretched = s.process(history, o, n)
          feedFrom = from + n
          appendPending(stretched, stretched.size / ch, ch)
          fed = true
        } else if (readMore(ch) > 0) {
          continue
        }
        if (!fed) break
      }
      val n = minOf(frames, pendingFrames)
      System.arraycopy(pending, 0, result, 0, n * ch)
      System.arraycopy(pending, n * ch, pending, 0, (pendingFrames - n) * ch)
      pendingFrames -= n
      if (n < frames && !underrun) {
        underrun = true
        note += "; a song was not decoded in time (underrun)"
      }
      return result
    }

    fun readAheadTo(until: Long, ch: Int) {
      while (nextRawFrame <= until) if (readMore(ch) <= 0) break
    }

    private fun readMore(ch: Int): Int {
      val src = source ?: return 0
      if (chunk.size < PULL_FRAMES * ch) chunk = FloatArray(PULL_FRAMES * ch)
      val got = src.read(chunk, PULL_FRAMES)
      if (got <= 0) return 0
      val need = (historyFrames + got) * ch
      if (need > history.size) history = history.copyOf(maxOf(need, history.size * 2))
      System.arraycopy(chunk, 0, history, historyFrames * ch, got * ch)
      historyFrames += got
      nextRawFrame += got
      // Keep a few seconds behind what was fed.
      val keepFrom = minOf(feedFrom, nextRawFrame) - HISTORY_FRAMES
      val drop = (keepFrom - historyStart).toInt().coerceIn(0, historyFrames)
      if (drop > 0) {
        System.arraycopy(history, drop * ch, history, 0, (historyFrames - drop) * ch)
        historyFrames -= drop
        historyStart += drop
      }
      return got
    }

    private fun appendPending(x: FloatArray, frames: Int, ch: Int) {
      val need = (pendingFrames + frames) * ch
      if (need > pending.size) pending = pending.copyOf(maxOf(need, pending.size * 2))
      System.arraycopy(x, 0, pending, pendingFrames * ch, frames * ch)
      pendingFrames += frames
    }

    /**
     * Offset between another decode of this song ([other], its first frame at song frame [otherStart])
     * and ours: other's frame f sounds like our raw frame f + offset. With the match score.
     */
    fun findOffset(other: FloatArray, otherStart: Long, ch: Int): Pair<Long, Double> {
      var best = 0L
      var bestScore = Double.NEGATIVE_INFINITY
      for (d in -MATCH_RANGE..MATCH_RANGE) {
        val ourStart = otherStart + d - historyStart
        if (ourStart < 0 || ourStart + MATCH_FRAMES > historyFrames) continue
        var dot = 0.0
        var e1 = 1e-12
        var e2 = 1e-12
        for (i in 0 until MATCH_FRAMES step 2) {
          var a = 0f
          var b = 0f
          for (c in 0 until ch) {
            a += other[i * ch + c]
            b += history[((ourStart + i) * ch + c).toInt()]
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
      return best to bestScore
    }

    /** The match of the player's last samples ([ring], up to frame [end]) with our decode. */
    fun matchOffset(ring: Ring, end: Long, ch: Int): Pair<Long, Double>? {
      if (!enabled || ring.frames < MATCH_FRAMES) return null
      readAheadTo(end + MATCH_RANGE + 1, ch)
      val from = end - MATCH_FRAMES
      val o = ((from - ring.start) * ch).toInt()
      if (o < 0) return null
      val slice = ring.data.copyOfRange(o, o + MATCH_FRAMES * ch)
      val m = findOffset(slice, from, ch)
      return if (m.second >= MIN_MATCH_SCORE) m else null
    }
  }

  companion object {
    private const val SUB_BLOCK = 64
    private const val PULL_FRAMES = 2048
    private const val HISTORY_FRAMES = 6 * 48_000L
    private const val RING_FRAMES = 8192
    /** Frames compared, and how far either way (~25 ms at 48 kHz). */
    const val MATCH_FRAMES = 2048
    const val MATCH_RANGE = 1200
    /**
     * Below this two decodes are not the same samples (the same decode scores about 1, two
     * decoders of one file over 0.95): not spliced, crossfaded.
     */
    const val MIN_MATCH_SCORE = 0.8
    private const val WEAK_FADE_FRAMES = 14_400
    /** A gain above this (-20 dB) counts as heard. */
    private const val AUDIBLE = 0.1f
  }
}
