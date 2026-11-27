package echo.music.iad1tya.playback.mix

import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import java.nio.ByteOrder
import timber.log.Timber

/**
 * Decodes the next song ahead of its blend, on its own thread, from [startMs] on: MediaExtractor
 * and MediaCodec (Opus from YouTube, FLAC from the lossless addon, anything the phone decodes),
 * brought to stereo at [outRate]. The first frame [read] returns is the song's sample at [startMs]
 * exactly. Up to [bufferSeconds] are kept decoded ahead of what the mixer has taken.
 */
class IncomingDecoder(
  private val openSource: () -> MediaDataSource,
  private val startMs: Long,
  private val outRate: Int,
  private val bufferSeconds: Int = 20,
  private val maxSeconds: Int = 120,
) : PcmSource {

  private val lock = Object()
  private val ring = FloatArray(outRate * 2 * bufferSeconds)
  private var readPos = 0L
  private var writePos = 0L

  @Volatile private var stopped = false
  @Volatile var failed: String? = null
    private set
  @Volatile var finished = false
    private set

  /** Frames decoded and waiting. */
  val available: Int
    get() = synchronized(lock) { (writePos - readPos).toInt() }

  private val thread = Thread({ run() }, "MixIncomingDecoder").apply { isDaemon = true }

  fun start(): IncomingDecoder {
    thread.start()
    return this
  }

  fun stop() {
    stopped = true
    synchronized(lock) { lock.notifyAll() }
  }

  override fun read(dst: FloatArray, maxFrames: Int): Int =
    synchronized(lock) {
      val n = minOf(maxFrames.toLong(), writePos - readPos).toInt()
      val cap = ring.size / 2
      for (i in 0 until n) {
        val r = ((readPos + i) % cap).toInt()
        dst[i * 2] = ring[r * 2]
        dst[i * 2 + 1] = ring[r * 2 + 1]
      }
      readPos += n
      if (n > 0) lock.notifyAll()
      n
    }

  private fun push(x: FloatArray, frames: Int) {
    var off = 0
    val cap = ring.size / 2
    while (off < frames && !stopped) {
      synchronized(lock) {
        while (!stopped && writePos - readPos >= cap) lock.wait(50)
        val room = (cap - (writePos - readPos)).toInt()
        val n = minOf(room, frames - off)
        for (i in 0 until n) {
          val w = ((writePos + i) % cap).toInt()
          ring[w * 2] = x[(off + i) * 2]
          ring[w * 2 + 1] = x[(off + i) * 2 + 1]
        }
        writePos += n
        off += n
      }
    }
  }

  private fun run() {
    val extractor = MediaExtractor()
    var codec: MediaCodec? = null
    var source: MediaDataSource? = null
    try {
      source = openSource()
      extractor.setDataSource(source)
      var track = -1
      for (i in 0 until extractor.trackCount) {
        if (extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
          track = i
          break
        }
      }
      check(track >= 0) { "no audio track" }
      extractor.selectTrack(track)
      val format = extractor.getTrackFormat(track)
      val startUs = startMs * 1000
      extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
      // A file without a seek index lands back at its start: walk the compressed samples, without
      // decoding them, to just before the start (decoding the whole song first takes too long).
      if (extractor.sampleTime in 0 until startUs - 3_000_000) {
        while (extractor.sampleTime in 0 until startUs - 500_000) {
          if (!extractor.advance() || stopped) break
        }
      }
      codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
      if (Build.VERSION.SDK_INT >= 24) format.setInteger(MediaFormat.KEY_PCM_ENCODING, android.media.AudioFormat.ENCODING_PCM_FLOAT)
      codec.configure(format, null, null, 0)
      codec.start()

      val info = MediaCodec.BufferInfo()
      var inputDone = false
      var resampler: Resampler? = null
      var rate = 0
      var channels = 0
      var float = false
      var skipped = false
      val endUs = startUs + maxSeconds * 1_000_000L
      var decodedUs = 0L
      while (!stopped) {
        if (!inputDone) {
          val inIndex = codec.dequeueInputBuffer(5_000)
          if (inIndex >= 0) {
            val buf = codec.getInputBuffer(inIndex)!!
            val size = extractor.readSampleData(buf, 0)
            if (size < 0 || extractor.sampleTime > endUs) {
              codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
              inputDone = true
            } else {
              codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
              extractor.advance()
            }
          }
        }
        val outIndex = codec.dequeueOutputBuffer(info, 5_000)
        if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED || (outIndex >= 0 && rate == 0)) {
          val f = codec.outputFormat
          rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
          channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
          float =
            Build.VERSION.SDK_INT >= 24 &&
              f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
              f.getInteger(MediaFormat.KEY_PCM_ENCODING) == android.media.AudioFormat.ENCODING_PCM_FLOAT
          resampler = Resampler(rate, outRate, 2)
        }
        if (outIndex < 0) continue
        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
          codec.releaseOutputBuffer(outIndex, false)
          break
        }
        val buf = codec.getOutputBuffer(outIndex)!!
        buf.position(info.offset)
        buf.limit(info.offset + info.size)
        buf.order(ByteOrder.nativeOrder())
        val frames = info.size / (channels * if (float) 4 else 2)
        val stereo = FloatArray(frames * 2)
        if (float) {
          val fb = buf.asFloatBuffer()
          for (i in 0 until frames) {
            val l = fb.get(i * channels)
            stereo[i * 2] = l
            stereo[i * 2 + 1] = if (channels > 1) fb.get(i * channels + 1) else l
          }
        } else {
          val sb = buf.asShortBuffer()
          for (i in 0 until frames) {
            val l = sb.get(i * channels) / 32768f
            stereo[i * 2] = l
            stereo[i * 2 + 1] = if (channels > 1) sb.get(i * channels + 1) / 32768f else l
          }
        }
        codec.releaseOutputBuffer(outIndex, false)
        // Drop what comes before the start exactly: the seek lands on the sync sample before it.
        var from = 0
        if (!skipped) {
          val skip = ((startUs - info.presentationTimeUs) * rate / 1_000_000L).toInt()
          if (skip >= frames) continue
          from = skip.coerceAtLeast(0)
          skipped = true
        }
        val out = resampler!!.process(stereo, from * 2, frames - from)
        push(out, out.size / 2)
        decodedUs += (frames - from) * 1_000_000L / rate
      }
      finished = true
    } catch (e: Exception) {
      failed = e.message ?: e.javaClass.simpleName
      Timber.tag("Automix").w(e, "Next song decode failed")
    } finally {
      runCatching { codec?.stop() }
      runCatching { codec?.release() }
      runCatching { extractor.release() }
      runCatching { source?.close() }
    }
  }
}
