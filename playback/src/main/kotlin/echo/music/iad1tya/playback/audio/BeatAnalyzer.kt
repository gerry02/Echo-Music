package echo.music.iad1tya.playback.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToLong
import timber.log.Timber

/**
 * Offline beat analysis for automix. Classical DSP, no ML (see [BeatDsp]): the tempo comes from 18 s
 * in the middle of the song; the beats and bars near the start and the end, where the mixes happen,
 * are measured there again instead of being extrapolated from the middle, so the grid holds to the
 * millisecond where it is used.
 */
object BeatAnalyzer {

  /** Bumped when the analysis changes so stored rows are redone once. */
  const val VERSION = 3

  data class Result(
    val bpm: Float,
    /** A beat of the grid measured in the middle: beats are at this + k * 60000 / bpm. */
    val firstBeatOffsetMs: Long,
    val confidence: Float,
    /** Where the intro ends (energy reaches the song's body), on a downbeat. Null: starts hot. */
    val mixInPointMs: Long? = null,
    /** Where the outro starts (energy leaves the body), on a downbeat. Null: loud to the end. */
    val mixOutPointMs: Long? = null,
    /** 0=C, 1=C#, ... 11=B. Null when the chroma signal was too weak to call a key. */
    val keyPitchClass: Int? = null,
    val keyIsMinor: Boolean? = null,
    /** A first beat of a bar of the middle grid. */
    val downbeatOffsetMs: Long? = null,
    /** A first beat of a bar measured in the first seconds of the song. */
    val headAnchorMs: Long? = null,
    /** A first beat of a bar measured in the last seconds of the song. */
    val tailAnchorMs: Long? = null,
    /** Where the audio starts after leading silence. */
    val firstSoundMs: Long? = null,
    /** Where the audio ends before trailing silence. */
    val lastSoundMs: Long? = null,
    /** Length of the analyzed audio, to tell another edit of the song apart. */
    val durationMs: Long = 0L,
    /** How the beat phase was chosen, for the log. */
    val phaseNote: String = "",
  )

  /** Decoded parts of a song, positions in ms from its start. */
  class Windows(
    val durationMs: Long,
    val middle: MonoPcm? = null,
    val middleStartMs: Long = 0L,
    val head: MonoPcm? = null,
    val headStartMs: Long = 0L,
    val tail: MonoPcm? = null,
    val tailStartMs: Long = 0L,
  )

  private const val TAG = "BeatAnalyzer"

  /** Analysis window: 18s taken from the middle of the track. */
  private const val WINDOW_US = 18_000_000L

  /** Windows where the mixes happen. */
  private const val HEAD_WINDOW_US = 20_000_000L
  private const val TAIL_WINDOW_US = 30_000_000L

  /** Canonical BPM range; octave-fold estimates into it (61.9 -> 123.8, 160 -> 80...). */
  private const val MIN_CANONICAL_BPM = 70f
  private const val MAX_CANONICAL_BPM = 140f
  private const val ENERGY_BLOCK_MS = 500
  private const val SOUND_BLOCK_MS = 10
  private const val MAX_INTRO_SKIP_MS = 20_000L
  private const val MAX_OUTRO_CUT_MS = 45_000L

  /** Comb strength from which a window has a beat of its own (1 = none). */
  private const val MIN_LOCAL_BEAT_STRENGTH = 1.3f

  /** Beat vs offbeat margin below which the beat is not trusted for a beat-matched mix. */
  private const val MIN_HALF_BEAT_MARGIN = 0.04

  /** Bar-phase confidence from which a window's own downbeat beats the middle grid's. */
  private const val LOCAL_BAR_CONFIDENCE = 0.2f

  /** How far before the stated duration a tail window may stop and still count as reaching it. */
  private const val TAIL_END_SLACK_MS = 2_500L

  /** Silence threshold, about -50 dBFS RMS. */
  private const val SILENCE_RMS = 0.0032f

  /**
   * Hard cap on bytes fetched for analysis: a 10-minute song at 160 kbit/s. A file cut short looks
   * like a song that ends early, so a tail window that stops before the end is discarded anyway.
   */
  private const val MAX_FETCH_BYTES = 12L * 1024 * 1024

  fun analyzeUri(
    context: Context,
    uri: Uri,
    shouldCancel: () -> Boolean = { false },
  ): Result? =
    analyzeSource(shouldCancel = shouldCancel) { extractor ->
      extractor.setDataSource(context, uri, null)
    }

  /**
   * @param result null when the (possibly partial) data couldn't be analyzed.
   * @param complete true when the copied bytes are known to be the whole stream — only then is a
   *   failed analysis worth negative-caching.
   */
  class CachedAnalysis(val result: Result?, val complete: Boolean)

  /**
   * Fetches the track through the given data-source chain into a temp file and analyzes that.
   * Cached data is served locally; otherwise this costs one audio download — once per track, results
   * are stored in the DB.
   */
  @UnstableApi
  fun analyzeStream(
    dataSourceFactory: androidx.media3.datasource.DataSource.Factory,
    mediaId: String,
    tempDir: java.io.File,
    shouldCancel: () -> Boolean = { false },
  ): CachedAnalysis? {
    val tempFile = java.io.File(tempDir, "beat_${mediaId.hashCode()}.tmp")
    try {
      var copied = 0L
      var reachedEnd = false
      val source = dataSourceFactory.createDataSource()
      try {
        source.open(DataSpec.Builder().setUri(Uri.parse(mediaId)).setKey(mediaId).build())
        java.io.FileOutputStream(tempFile).use { out ->
          val buf = ByteArray(64 * 1024)
          while (copied < MAX_FETCH_BYTES) {
            if (shouldCancel()) {
              Timber.tag(TAG).d("Stream fetch for %s cancelled at %d bytes", mediaId, copied)
              return null
            }
            val read = source.read(buf, 0, buf.size)
            if (read == C.RESULT_END_OF_INPUT) {
              reachedEnd = true
              break
            }
            out.write(buf, 0, read)
            copied += read
          }
        }
      } catch (e: Exception) {
        Timber.tag(TAG)
          .d("Stream fetch for %s stopped at %d bytes (%s)", mediaId, copied, e.message)
      } finally {
        try {
          source.close()
        } catch (_: Exception) {}
      }

      Timber.tag(TAG).d("Stream fetch for %s: %d bytes, complete=%s", mediaId, copied, reachedEnd)
      if (copied < 256 * 1024) return null

      if (shouldCancel()) return null
      val result =
        analyzeSource(shouldCancel = shouldCancel) { extractor ->
          extractor.setDataSource(tempFile.absolutePath)
        }
      return CachedAnalysis(result, reachedEnd)
    } finally {
      tempFile.delete()
    }
  }

  /**
   * Measures the start and/or the end of the stream actually played (e.g. a lossless file of the
   * song analyzed from YouTube) with the tempo already known, reading only what [source] holds.
   */
  fun probe(
    source: MediaDataSource,
    knownBpm: Float,
    head: Boolean,
    tail: Boolean,
    shouldCancel: () -> Boolean = { false },
  ): Result? =
    analyzeSource(
      knownBpm = knownBpm,
      wantMiddle = false,
      wantHead = head,
      wantTail = tail,
      shouldCancel = shouldCancel,
    ) { extractor ->
      extractor.setDataSource(source)
    }

  private fun analyzeSource(
    knownBpm: Float? = null,
    wantMiddle: Boolean = true,
    wantHead: Boolean = true,
    wantTail: Boolean = true,
    shouldCancel: () -> Boolean = { false },
    setSource: (MediaExtractor) -> Unit,
  ): Result? {
    val extractor = MediaExtractor()
    try {
      if (shouldCancel()) return null
      setSource(extractor)

      var trackIndex = -1
      var format: MediaFormat? = null
      for (i in 0 until extractor.trackCount) {
        val f = extractor.getTrackFormat(i)
        if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
          trackIndex = i
          format = f
          break
        }
      }
      if (trackIndex < 0 || format == null) return null
      extractor.selectTrack(trackIndex)
      val durationUs =
        if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION)
        else 0L

      fun window(startUs: Long, lengthUs: Long): Pair<MonoPcm, Long>? {
        if (shouldCancel()) return null
        return try {
          extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
          val actualStartMs = max(0L, extractor.sampleTime) / 1000
          decodeMono(extractor, format, lengthUs, shouldCancel)?.let { it to actualStartMs }
        } catch (e: Exception) {
          Timber.tag(TAG).d("Window at %d us not readable (%s)", startUs, e.message)
          null
        }
      }

      val middle =
        if (wantMiddle) window(max(0L, durationUs / 2 - WINDOW_US / 2), WINDOW_US) else null
      if (wantMiddle && middle == null) return null
      val head = if (wantHead && durationUs > HEAD_WINDOW_US) window(0L, HEAD_WINDOW_US) else null
      // Only a tail that reaches the real end tells where the song ends: in a file cut short the
      // data runs out mid-song, which would read as the song's last sound.
      val tail =
        if (wantTail && durationUs > TAIL_WINDOW_US) {
          window(durationUs - TAIL_WINDOW_US, TAIL_WINDOW_US)?.takeIf { (pcm, startMs) ->
            val reaches = startMs + pcm.durationMs >= durationUs / 1000 - TAIL_END_SLACK_MS
            if (!reaches) Timber.tag(TAG).d("Tail window stops at %d ms of %d: ignored", startMs + pcm.durationMs, durationUs / 1000)
            reaches
          }
        } else null
      if (shouldCancel()) return null

      return analyzePcm(
        Windows(
          durationMs = durationUs / 1000,
          middle = middle?.first,
          middleStartMs = middle?.second ?: 0L,
          head = head?.first,
          headStartMs = head?.second ?: 0L,
          tail = tail?.first,
          tailStartMs = tail?.second ?: 0L,
        ),
        knownBpm,
      )
    } catch (e: Exception) {
      Timber.tag(TAG).w(e, "Beat analysis failed")
      return null
    } finally {
      extractor.release()
    }
  }

  private class LocalGrid(val beatMs: Double, val spectrum: BeatDsp.Spectrum, val startMs: Long)

  /** Everything from the decoded windows; pure, so unit tests can feed it synthetic songs. */
  fun analyzePcm(windows: Windows, knownBpm: Float? = null): Result? {
    val midSpectrum = windows.middle?.let { BeatDsp.spectrum(it) }

    var periodMs: Double
    var confidence: Float
    if (midSpectrum != null) {
      val (periodFrames, conf) =
        BeatDsp.tempoPeriod(midSpectrum.flux, midSpectrum.frameRate) ?: return null
      periodMs = midSpectrum.frameToMs(periodFrames.toDouble())
      confidence = conf
    } else if (knownBpm != null && knownBpm > 0f) {
      periodMs = 60_000.0 / knownBpm
      confidence = 1f
    } else {
      return null
    }
    // Octave-fold into the canonical range.
    while (60_000.0 / periodMs < MIN_CANONICAL_BPM) periodMs /= 2
    while (60_000.0 / periodMs >= MAX_CANONICAL_BPM) periodMs *= 2

    val headSpectrum = windows.head?.let { BeatDsp.spectrum(it) }
    val tailSpectrum = windows.tail?.let { BeatDsp.spectrum(it) }
    if (midSpectrum != null) {
      // The autocorrelation peak is good to ~0.3 %, which over three minutes is half a beat:
      // sharpen it with combs over every window, then with beats minutes apart.
      val spectra = listOfNotNull(midSpectrum, headSpectrum, tailSpectrum)
      fun score(p: Double): Double =
        spectra.sumOf { s ->
          (BeatDsp.beatPhase(s.flux, s.msToFrame(p), spread = 0.0)?.strength ?: 0f).toDouble()
        }
      var best = periodMs
      var bestScore = score(periodMs)
      for (step in -30..30) {
        val p = periodMs * (1 + step * 0.0005)
        val sc = score(p)
        if (sc > bestScore) {
          bestScore = sc
          best = p
        }
      }
      periodMs = best
    }

    // Middle grid: beat phase and bar phase.
    var midBeatMs: Double? = null
    var midDownbeatMs: Double? = null
    if (midSpectrum != null) {
      val periodFrames = midSpectrum.msToFrame(periodMs)
      val comb = BeatDsp.beatPhase(midSpectrum.flux, periodFrames) ?: return null
      midBeatMs = windows.middleStartMs + midSpectrum.frameToMs(comb.phaseFrames)
    }
    val key = midSpectrum?.let { BeatDsp.key(it) }

    // Local beat grids where the mixes happen.
    fun local(spectrum: BeatDsp.Spectrum?, startMs: Long): LocalGrid? {
      spectrum ?: return null
      val periodFrames = spectrum.msToFrame(periodMs)
      val comb = BeatDsp.beatPhase(spectrum.flux, periodFrames) ?: return null
      if (comb.strength < MIN_LOCAL_BEAT_STRENGTH) return null
      return LocalGrid(startMs + spectrum.frameToMs(comb.phaseFrames), spectrum, startMs)
    }
    var headGrid = local(headSpectrum, windows.headStartMs)
    var tailGrid = local(tailSpectrum, windows.tailStartMs)

    // Two local beats minutes apart pin the period far tighter than any one window can.
    if (headGrid != null && tailGrid != null) {
      val span = tailGrid.beatMs - headGrid.beatMs
      val beats = (span / periodMs).roundToLong()
      if (beats > 16) {
        val refined = span / beats
        if (abs(refined / periodMs - 1) < 0.001) {
          periodMs = refined
          headGrid = local(headSpectrum, windows.headStartMs) ?: headGrid
          tailGrid = local(tailSpectrum, windows.tailStartMs) ?: tailGrid
        }
      }
    }

    // Beat or offbeat: one decision for the whole song, every window voting on the same grid, so
    // the start and the end can never sit half a beat apart from each other.
    val reference = midBeatMs ?: headGrid?.beatMs ?: tailGrid?.beatMs
    var phaseNote = ""
    if (reference != null) {
      // Each window's beat on the reference grid's side (a comb may have locked on the offbeat).
      fun aligned(beatMs: Double): Double {
        val rel = positiveMod(beatMs - reference, periodMs)
        return if (rel > periodMs / 4 && rel < periodMs * 3 / 4) beatMs - periodMs / 2 else beatMs
      }
      var onBeat = 0.0
      var offBeat = 0.0
      val votes = ArrayList<String>()
      fun vote(name: String, spectrum: BeatDsp.Spectrum?, startMs: Long, beatMs: Double?) {
        if (spectrum == null || beatMs == null) return
        val (a, b) =
          BeatDsp.halfBeatScores(
            spectrum,
            spectrum.msToFrame(positiveMod(aligned(beatMs) - startMs, periodMs)),
            spectrum.msToFrame(periodMs),
          )
        onBeat += a
        offBeat += b
        votes += "%s %.2f/%.2f".format(name, a, b)
      }
      vote("mid", midSpectrum, windows.middleStartMs, midBeatMs)
      vote("head", headGrid?.spectrum, windows.headStartMs, headGrid?.beatMs)
      vote("tail", tailGrid?.spectrum, windows.tailStartMs, tailGrid?.beatMs)
      val shift = if (offBeat > onBeat * 1.05) periodMs / 2 else 0.0
      val margin = if (onBeat + offBeat > 0) abs(onBeat - offBeat) / (onBeat + offBeat) else 0.0
      // Too close to call: a mix on the beat could land on the offbeat. Better smooth.
      if (margin < MIN_HALF_BEAT_MARGIN) confidence = minOf(confidence, 0.2f)
      midBeatMs = midBeatMs?.let { aligned(it) + shift }
      headGrid = headGrid?.let { LocalGrid(aligned(it.beatMs) + shift, it.spectrum, it.startMs) }
      tailGrid = tailGrid?.let { LocalGrid(aligned(it.beatMs) + shift, it.spectrum, it.startMs) }
      phaseNote =
        "${if (shift > 0) "offbeat->shifted" else "on beat"} margin %.2f [%s]"
          .format(margin, votes.joinToString(", "))
    }
    if (midSpectrum != null && midBeatMs != null) {
      val periodFrames = midSpectrum.msToFrame(periodMs)
      val phaseFrames = midSpectrum.msToFrame(positiveMod(midBeatMs - windows.middleStartMs, periodMs))
      val bar = BeatDsp.downbeatPhase(midSpectrum, phaseFrames, periodFrames)
      midDownbeatMs = windows.middleStartMs + midSpectrum.frameToMs(phaseFrames) + (bar?.first ?: 0) * periodMs
      phaseNote += " bar ${bar?.first ?: "?"} conf %.2f".format(bar?.second ?: 0f)
    }

    // Bar phase of a local grid: from the middle grid's bars when known, else its own.
    fun localDownbeat(grid: LocalGrid?): Double? {
      grid ?: return null
      val barMs = periodMs * 4
      val periodFrames = grid.spectrum.msToFrame(periodMs)
      val phaseFrames = grid.spectrum.msToFrame(positiveMod(grid.beatMs - grid.startMs, periodMs))
      val bar = BeatDsp.downbeatPhase(grid.spectrum, phaseFrames, periodFrames)
      if (bar != null && (bar.second >= LOCAL_BAR_CONFIDENCE || midDownbeatMs == null)) {
        return grid.startMs + grid.spectrum.frameToMs(phaseFrames) + bar.first * periodMs
      }
      if (midDownbeatMs != null) {
        return (0 until 4)
          .map { grid.beatMs + it * periodMs }
          .minByOrNull { candidate ->
            val offset = ((candidate - midDownbeatMs) % barMs + barMs) % barMs
            minOf(offset, barMs - offset)
          }
      }
      return grid.beatMs
    }
    val headAnchor = localDownbeat(headGrid)
    val tailAnchor = localDownbeat(tailGrid)

    val firstSound = windows.head?.let { firstSoundMs(it, windows.headStartMs) }
    val lastSound = windows.tail?.let { lastSoundMs(it, windows.tailStartMs) }

    // A bar grid near the start, best measured there.
    val headBars = headAnchor ?: midDownbeatMs ?: midBeatMs
    val tailBars = tailAnchor ?: midDownbeatMs ?: midBeatMs

    val mixIn =
      windows.head?.let { head ->
        detectIntroEnd(BeatDsp.energyEnvelope(head, ENERGY_BLOCK_MS), windows.headStartMs)
          // The body starts within the energy block that crossed the threshold.
          ?.let { raw ->
            if (headBars != null) snapUp((raw - ENERGY_BLOCK_MS).toDouble(), headBars, periodMs * 4)
            else raw.toDouble()
          }
          ?.roundToLong()
      }
    val mixOut =
      windows.tail?.let { tail ->
        detectOutroStart(
            BeatDsp.energyEnvelope(tail, ENERGY_BLOCK_MS),
            windows.tailStartMs,
            windows.durationMs,
          )
          ?.let { raw -> if (tailBars != null) snapNearest(raw.toDouble(), tailBars, periodMs * 4) else raw.toDouble() }
          ?.roundToLong()
      }

    val gridAnchor = midBeatMs ?: headGrid?.beatMs ?: tailGrid?.beatMs ?: return null
    val barAnchor = midDownbeatMs ?: headAnchor ?: tailAnchor
    return Result(
      bpm = (60_000.0 / periodMs).toFloat(),
      firstBeatOffsetMs = positiveMod(gridAnchor, periodMs).roundToLong(),
      confidence = confidence,
      mixInPointMs = mixIn,
      mixOutPointMs = mixOut,
      keyPitchClass = key?.first,
      keyIsMinor = key?.second,
      downbeatOffsetMs = barAnchor?.let { positiveMod(it, periodMs * 4).roundToLong() },
      headAnchorMs = headAnchor?.roundToLong(),
      tailAnchorMs = tailAnchor?.roundToLong(),
      firstSoundMs = firstSound,
      lastSoundMs = lastSound,
      durationMs = windows.durationMs,
      phaseNote = phaseNote,
    )
  }

  private fun positiveMod(value: Double, period: Double): Double = ((value % period) + period) % period

  /** The first grid point at or after [value]; a few ms of tolerance keep a point on itself. */
  internal fun snapUp(value: Double, anchor: Double, period: Double): Double {
    val k = ceil((value - anchor - 5) / period)
    return anchor + k * period
  }

  internal fun snapNearest(value: Double, anchor: Double, period: Double): Double {
    val k = floor((value - anchor) / period + 0.5)
    return anchor + k * period
  }

  private fun firstSoundMs(pcm: MonoPcm, startMs: Long): Long? {
    val env = BeatDsp.energyEnvelope(pcm, SOUND_BLOCK_MS)
    val threshold = max(SILENCE_RMS, 0.02f * BeatDsp.percentile(env, 0.9f))
    val i = env.indexOfFirst { it >= threshold }
    return if (i < 0) null else startMs + i.toLong() * SOUND_BLOCK_MS
  }

  private fun lastSoundMs(pcm: MonoPcm, startMs: Long): Long? {
    val env = BeatDsp.energyEnvelope(pcm, SOUND_BLOCK_MS)
    val threshold = max(SILENCE_RMS, 0.02f * BeatDsp.percentile(env, 0.9f))
    val i = env.indexOfLast { it >= threshold }
    return if (i < 0) null else startMs + (i + 1).toLong() * SOUND_BLOCK_MS
  }

  /** First block where energy reaches and sustains near body level. Null when the track starts hot. */
  private fun detectIntroEnd(env: FloatArray, startMs: Long): Long? {
    if (env.size < 8) return null
    val ref = BeatDsp.percentile(env, 0.75f)
    if (ref <= 0f) return null
    var candidate = -1
    for (i in 0 until env.size - 4) {
      if (
        env[i] >= 0.55f * ref &&
          env[i + 1] >= 0.4f * ref &&
          env[i + 2] >= 0.4f * ref &&
          env[i + 3] >= 0.4f * ref
      ) {
        candidate = i
        break
      }
    }
    if (candidate <= 1) return null // starts hot
    val ms = startMs + candidate.toLong() * ENERGY_BLOCK_MS
    return if (ms > MAX_INTRO_SKIP_MS) null else ms
  }

  /** Last moment the tail is still at body loudness. Null when loud almost to the end. */
  private fun detectOutroStart(env: FloatArray, windowStartMs: Long, durationMs: Long): Long? {
    if (env.size < 8 || durationMs <= 0) return null
    val ref = BeatDsp.percentile(env, 0.75f)
    if (ref <= 0f) return null
    var lastLoud = -1
    for (i in env.indices.reversed()) {
      if (env[i] >= 0.5f * ref) {
        lastLoud = i
        break
      }
    }
    if (lastLoud < 0) return null
    val ms = windowStartMs + (lastLoud + 1).toLong() * ENERGY_BLOCK_MS
    if (durationMs - ms < 3_000) return null
    return max(ms, durationMs - MAX_OUTRO_CUT_MS)
  }

  private fun decodeMono(
    extractor: MediaExtractor,
    format: MediaFormat,
    maxDurationUs: Long,
    shouldCancel: () -> Boolean = { false },
  ): MonoPcm? {
    val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
    val codec = MediaCodec.createDecoderByType(mime)
    val out = ArrayList<FloatArray>()
    var sampleRate = 0
    var decodedUs = 0L
    try {
      codec.configure(format, null, null, 0)
      codec.start()
      var inputDone = false
      var outputDone = false
      val info = MediaCodec.BufferInfo()

      while (!outputDone && decodedUs < maxDurationUs) {
        if (shouldCancel()) return null
        if (!inputDone) {
          val inIndex = codec.dequeueInputBuffer(10_000)
          if (inIndex >= 0) {
            val buffer = codec.getInputBuffer(inIndex)!!
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) {
              codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
              inputDone = true
            } else {
              codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
              extractor.advance()
            }
          }
        }

        val outIndex = codec.dequeueOutputBuffer(info, 10_000)
        if (outIndex >= 0) {
          if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
          if (info.size > 0) {
            val outFormat = codec.outputFormat
            val channels = outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            sampleRate = outFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val buf = codec.getOutputBuffer(outIndex)!!
            buf.position(info.offset)
            buf.limit(info.offset + info.size)
            buf.order(java.nio.ByteOrder.LITTLE_ENDIAN)
            val shorts = buf.asShortBuffer()
            val frames = shorts.remaining() / channels
            val mono = FloatArray(frames)
            for (f in 0 until frames) {
              var acc = 0f
              for (c in 0 until channels) acc += shorts.get(f * channels + c) / 32768f
              mono[f] = acc / channels
            }
            out.add(mono)
            decodedUs += frames * 1_000_000L / max(1, sampleRate)
          }
          codec.releaseOutputBuffer(outIndex, false)
        }
      }
    } catch (e: Exception) {
      Timber.tag(TAG).d("Decode stopped (%s)", e.message)
      if (out.isEmpty()) return null
    } finally {
      try {
        codec.stop()
      } catch (_: Exception) {}
      codec.release()
    }
    if (sampleRate == 0 || out.isEmpty()) return null

    val total = out.sumOf { it.size }
    val samples = FloatArray(total)
    var pos = 0
    for (chunk in out) {
      chunk.copyInto(samples, pos)
      pos += chunk.size
    }
    return MonoPcm(samples, sampleRate).takeIf { it.samples.size >= BeatDsp.FFT_SIZE * 8 }
  }
}
