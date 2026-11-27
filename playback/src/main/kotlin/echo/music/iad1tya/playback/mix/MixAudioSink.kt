package echo.music.iad1tya.playback.mix

import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import timber.log.Timber

/**
 * Puts the [MixEngine] in the player's audio path, between its decoder and its real audio output.
 *
 * In mix mode (a source with the mix on) every song is converted to one fixed format (48 kHz
 * stereo), so the output never restarts between songs, and passes through the engine: the
 * outgoing song is mixed with the next one decoded ahead, on the output's own sample clock. The
 * output gets its own continuous timeline; positions are mapped back to the songs' for the player.
 *
 * The handover: when the engine is ready, the player is moved to the next song at the engine's
 * sample. That seek would flush the output and silence it while the player gets there; announced
 * with [expectHandover], the flush and pause are not passed on and the engine's own audio keeps
 * the output fed until the player's audio of the next song arrives and is joined on its sample.
 *
 * Off mix mode it is a plain pass-through.
 */
@UnstableApi
class MixAudioSink(private val delegate: AudioSink, val engine: MixEngine) : ForwardingAudioSink(delegate) {

  /** Whether songs are brought to the mix format (takes effect from the next song). */
  @Volatile var mixMode = false

  /** Whether the stream playing now is in the mix format (a mix can be armed on it). */
  @Volatile var isMixFormat = false
    private set

  /** The stream whose songs the engine treats as the outgoing one. */
  @Volatile private var outgoingStream = -1
  @Volatile private var handoverExpected = false

  /** Something worth logging happened (the engine's notes, a fallback). */
  var onEvent: ((String) -> Unit)? = null

  private var inRate = 0
  private var inChannels = 0
  private var inEncoding = C.ENCODING_INVALID
  private var resampler: Resampler? = null
  /** Song time (us) of the resampler's output frame 0. */
  private var resamplerBaseUs = Long.MIN_VALUE
  private var streamOffsetUs = 0L
  private var streamId = 0
  private var lastInputEndUs = Long.MIN_VALUE
  private var offsetWarned = false

  private var pendingOut: ByteBuffer? = null
  private var pendingPtsUs = 0L
  /** Continuous timeline of what is written to the real output. */
  private var outputUs = 0L

  /** Output timeline -> player timeline: (output us, player us or null when unknown). */
  private val segments = ArrayDeque<LongArray>()

  private var inHandover = false
  private var handoverStartedAt = 0L
  private var handler: Handler? = null
  private val filler =
    object : Runnable {
      override fun run() = fillDuringHandover()
    }

  fun markOutgoingStream() {
    outgoingStream = streamId
  }

  /** The next seek is the handover: keep the output going through its flush. */
  fun expectHandover() {
    handoverExpected = true
  }

  override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
    val pcm = MimeTypes.AUDIO_RAW == inputFormat.sampleMimeType
    val supported =
      pcm &&
        inputFormat.channelCount in 1..2 &&
        inputFormat.pcmEncoding in
          setOf(C.ENCODING_PCM_16BIT, C.ENCODING_PCM_FLOAT, C.ENCODING_PCM_24BIT, C.ENCODING_PCM_32BIT)
    if (!mixMode || !supported) {
      if (isMixFormat) {
        // Leaving the mix format: the output is reconfigured for the song's own format.
        isMixFormat = false
        resetMixState()
      }
      super.configure(inputFormat, specifiedBufferSize, outputChannels)
      return
    }
    if (inputFormat.sampleRate != inRate || inputFormat.channelCount != inChannels || inputFormat.pcmEncoding != inEncoding) {
      inRate = inputFormat.sampleRate
      inChannels = inputFormat.channelCount
      inEncoding = inputFormat.pcmEncoding
      resampler = null
    }
    if (!isMixFormat) {
      super.configure(MIX_FORMAT, 0, null)
      isMixFormat = true
    }
  }

  override fun setOutputStreamOffsetUs(outputStreamOffsetUs: Long) {
    streamOffsetUs = outputStreamOffsetUs
    streamId++
    resampler = null
    if (!isMixFormat) super.setOutputStreamOffsetUs(outputStreamOffsetUs)
  }

  override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
    if (!isMixFormat) return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
    if (handler == null) Looper.myLooper()?.let { handler = Handler(it) }
    pendingOut?.let {
      if (!super.handleBuffer(it, pendingPtsUs, 1)) return false
      pendingOut = null
    }
    if (!buffer.hasRemaining()) return true
    val songUs = presentationTimeUs - streamOffsetUs
    if (songUs > MAX_SONG_US && !offsetWarned) {
      // No stream offset reached this sink: positions would be nonsense, so no mixing.
      offsetWarned = true
      onEvent?.invoke("mix sink: no stream offset (buffer at $presentationTimeUs us): mixing off")
    }
    if (songUs > MAX_SONG_US) {
      synchronized(engine) { if (engine.isActive) engine.reset() }
    }
    val frames = buffer.remaining() / (inChannels * bytesPerSample(inEncoding))
    val stereo = toStereoFloat(buffer, frames)
    buffer.position(buffer.limit())

    // A new resampler at each stream change or jump in time: its frame 0 is this buffer's time.
    if (resampler == null || abs(songUs - lastInputEndUs) > 2_000) {
      resampler = Resampler(inRate, MIX_RATE, 2)
      resamplerBaseUs = songUs
    }
    lastInputEndUs = songUs + frames * 1_000_000L / inRate
    val r = resampler!!
    val firstOutFrame = r.produced
    val resampled = r.process(stereo, 0, frames)
    val n = resampled.size / 2
    if (n == 0) return true
    val firstSongUs = resamplerBaseUs + firstOutFrame * 1_000_000L / MIX_RATE
    val songFrame = firstSongUs * MIX_RATE / 1_000_000L

    var playerUs: Long? = firstSongUs + streamOffsetUs
    val mixed =
      synchronized(engine) {
        when {
          !engine.isActive -> resampled
          streamId == outgoingStream && engine.state != MixEngine.State.HANDING_OVER ->
            engine.processOutgoing(resampled, n, songFrame)
          engine.state == MixEngine.State.HANDING_OVER -> {
            if (inHandover) endFiller("the player's audio arrived")
            val out = engine.processIncoming(resampled, n, songFrame)
            if (engine.state == MixEngine.State.DONE) onEvent?.invoke("handover: ${engine.note}")
            out
          }
          else -> {
            // The outgoing song ended before the handover: the next one plays from where it is.
            onEvent?.invoke("mix dropped: the song changed before the handover (${engine.note})")
            engine.reset()
            resampled
          }
        }
      }
    if (mixed.isEmpty()) return true
    writeOut(mixed, playerUs)
    pendingOut?.let {
      if (!super.handleBuffer(it, pendingPtsUs, 1)) return true
      pendingOut = null
    }
    return true
  }

  override fun flush() {
    // The handover's seek can flush more than once (the renderer is reset for the new song):
    // every flush until the player's audio of the next song arrives belongs to it.
    if (isMixFormat && inHandover) return
    if (isMixFormat && handoverExpected) {
      // The handover's seek: the output keeps playing the engine's audio meanwhile.
      handoverExpected = false
      resampler = null
      lastInputEndUs = Long.MIN_VALUE
      synchronized(engine) { engine.startHandover() }
      startFiller()
      return
    }
    if (isMixFormat) {
      synchronized(engine) {
        if (engine.isActive) {
          onEvent?.invoke("mix cancelled by a seek (${engine.state})")
          engine.reset()
        }
      }
    }
    endFiller(null)
    pendingOut = null
    resampler = null
    lastInputEndUs = Long.MIN_VALUE
    segments.clear()
    super.flush()
  }

  override fun pause() {
    if (inHandover) return // the handover's seek pauses the player for a moment: keep sounding
    super.pause()
  }

  override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
    if (!isMixFormat) return super.getCurrentPositionUs(sourceEnded)
    val out = super.getCurrentPositionUs(sourceEnded)
    if (out == AudioSink.CURRENT_POSITION_NOT_SET) return out
    while (segments.size > 1 && segments[1][0] <= out) segments.removeFirst()
    val seg = segments.firstOrNull() ?: return AudioSink.CURRENT_POSITION_NOT_SET
    if (seg[0] > out || seg[1] == UNKNOWN) return AudioSink.CURRENT_POSITION_NOT_SET
    return seg[1] + (out - seg[0])
  }

  override fun hasPendingData(): Boolean =
    if (!isMixFormat) super.hasPendingData() else pendingOut != null || inHandover || super.hasPendingData()

  override fun isEnded(): Boolean = if (!isMixFormat) super.isEnded() else pendingOut == null && super.isEnded()

  override fun playToEndOfStream() {
    if (isMixFormat) {
      pendingOut?.let { if (super.handleBuffer(it, pendingPtsUs, 1)) pendingOut = null }
      if (pendingOut != null) return
    }
    super.playToEndOfStream()
  }

  override fun reset() {
    // The handover's seek may reset the renderer: the output keeps playing through it.
    if (isMixFormat && (inHandover || handoverExpected)) return
    synchronized(engine) { engine.reset() }
    resetMixState()
    isMixFormat = false
    super.reset()
  }

  // -----------------------------------------------------------------------------------------------

  private fun resetMixState() {
    endFiller(null)
    pendingOut = null
    resampler = null
    lastInputEndUs = Long.MIN_VALUE
    segments.clear()
    inRate = 0
    inChannels = 0
    inEncoding = C.ENCODING_INVALID
  }

  /** Queues [x] (48 kHz stereo float) for the output; [playerUs] is its first frame's player time. */
  private fun writeOut(x: FloatArray, playerUs: Long?) {
    val frames = x.size / 2
    val buf = ByteBuffer.allocateDirect(frames * 4).order(ByteOrder.nativeOrder())
    for (v in x) buf.putShort((v.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
    buf.flip()
    val last = segments.lastOrNull()
    val pu = playerUs ?: UNKNOWN
    val continuous = last != null && last[1] != UNKNOWN && pu != UNKNOWN && abs(last[1] + (outputUs - last[0]) - pu) < 1_000
    if (!continuous) segments.addLast(longArrayOf(outputUs, pu))
    pendingOut = buf
    pendingPtsUs = outputUs
    outputUs += frames * 1_000_000L / MIX_RATE
  }

  private fun startFiller() {
    inHandover = true
    handoverStartedAt = android.os.SystemClock.elapsedRealtime()
    handler?.post(filler)
  }

  private fun endFiller(reason: String?) {
    if (!inHandover) return
    inHandover = false
    handler?.removeCallbacks(filler)
    reason?.let { Timber.tag("Automix").d("handover filler ended: %s", it) }
  }

  private fun fillDuringHandover() {
    if (!inHandover) return
    if (android.os.SystemClock.elapsedRealtime() - handoverStartedAt > MAX_FILL_MS) {
      onEvent?.invoke("handover: the player took too long, mix dropped")
      synchronized(engine) { engine.reset() }
      endFiller(null)
      return
    }
    try {
      pendingOut?.let {
        if (super.handleBuffer(it, pendingPtsUs, 1)) pendingOut = null
      }
      val played = super.getCurrentPositionUs(false)
      val buffered = if (played == AudioSink.CURRENT_POSITION_NOT_SET) 0L else outputUs - played
      if (pendingOut == null && buffered < FILL_AHEAD_US) {
        val chunk = synchronized(engine) { engine.fill(MIX_RATE / 100) }
        writeOut(chunk, null)
        if (super.handleBuffer(pendingOut!!, pendingPtsUs, 1)) pendingOut = null
      }
    } catch (e: Exception) {
      Timber.tag("Automix").w(e, "handover filler failed")
    }
    handler?.postDelayed(filler, 5)
  }

  private fun bytesPerSample(encoding: Int) =
    when (encoding) {
      C.ENCODING_PCM_16BIT -> 2
      C.ENCODING_PCM_24BIT -> 3
      else -> 4
    }

  private fun toStereoFloat(buffer: ByteBuffer, frames: Int): FloatArray {
    val b = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
    val out = FloatArray(frames * 2)
    for (f in 0 until frames) {
      for (c in 0 until inChannels) {
        val v =
          when (inEncoding) {
            C.ENCODING_PCM_16BIT -> b.short / 32768f
            C.ENCODING_PCM_FLOAT -> b.float
            C.ENCODING_PCM_24BIT -> {
              val lo = b.get().toInt() and 0xFF
              val mid = b.get().toInt() and 0xFF
              val hi = b.get().toInt()
              ((hi shl 16) or (mid shl 8) or lo) / 8_388_608f
            }
            else -> b.int / 2_147_483_648f
          }
        out[f * 2 + c] = v
      }
      if (inChannels == 1) out[f * 2 + 1] = out[f * 2]
    }
    return out
  }

  companion object {
    const val MIX_RATE = 48_000
    private const val UNKNOWN = Long.MIN_VALUE
    private const val FILL_AHEAD_US = 150_000L
    private const val MAX_FILL_MS = 3_000L
    /** No song is a day long: a position past this means the stream offset is missing. */
    private const val MAX_SONG_US = 86_400_000_000L
    val MIX_FORMAT: Format =
      Format.Builder()
        .setSampleMimeType(MimeTypes.AUDIO_RAW)
        .setPcmEncoding(C.ENCODING_PCM_16BIT)
        .setChannelCount(2)
        .setSampleRate(MIX_RATE)
        .build()
  }
}
