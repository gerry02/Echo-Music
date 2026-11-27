package echo.music.iad1tya.playback.mix

import echo.music.iad1tya.playback.audio.BeatAnalyzer
import echo.music.iad1tya.playback.audio.MonoPcm
import echo.music.iad1tya.playback.crossfade.AutomixPlanner
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * PC test bench: the whole mix (analysis, plan, engine, handover to the "player") on two real
 * audio files, rendered to a WAV to listen to. No app build, no phone.
 *
 *   gradlew :playback:testGmsDebugUnitTest --tests "*MixBench*" -Pmix.a=song1.mp3 -Pmix.b=song2.flac
 *
 * Optional: -Pmix.out=out.wav, -Pmix.shift=<ms> (where the simulated player lands off the asked
 * position, to hear the handover cope). Needs ffmpeg on the PATH to decode the files.
 */
class MixBench {

  private val rate = 48_000

  @Test
  fun render() {
    val aPath = System.getProperty("mix.a")
    val bPath = System.getProperty("mix.b")
    assumeTrue("set -Pmix.a and -Pmix.b to run the bench", aPath != null && bPath != null)
    val outPath =
      System.getProperty("mix.out")
        ?: File(File(aPath!!).parentFile, "${File(aPath).nameWithoutExtension} -- ${File(bPath!!).nameWithoutExtension}.wav").path
    val shiftMs = System.getProperty("mix.shift")?.toLongOrNull() ?: 40L
    val report = StringBuilder()
    fun log(s: String) {
      println(s)
      report.appendLine(s)
    }

    val a = decode(aPath!!)
    val b = decode(bPath!!)
    val aMs = a.size / 2 * 1000L / rate
    val bMs = b.size / 2 * 1000L / rate
    log("A: ${File(aPath).name}, ${aMs / 1000} s")
    log("B: ${File(bPath).name}, ${bMs / 1000} s")

    val ra = analyze(a) ?: error("A: analysis failed")
    val rb = analyze(b) ?: error("B: analysis failed")
    log("A analysis: %.2f bpm conf %.2f tailBar %s mixOut %s end %s; %s".format(ra.bpm, ra.confidence, ra.tailAnchorMs, ra.mixOutPointMs, ra.lastSoundMs, ra.phaseNote))
    log("B analysis: %.2f bpm conf %.2f headBar %s mixIn %s start %s; %s".format(rb.bpm, rb.confidence, rb.headAnchorMs, rb.mixInPointMs, rb.firstSoundMs, rb.phaseNote))

    val easeMs = 4_000L
    val plan =
      AutomixPlanner.plan(
        track(ra, aMs),
        track(rb, bMs),
        positionMs = 0,
        fadeMs = 8_000,
        tailRoomMs = 1_000L,
      ) ?: error("no plan")
    log(
      "plan: ${plan.reason}: mix at %.2f s for %.1f s, B from %.2f s at x%.4f, bass swap at %.2f"
        .format(plan.triggerMs / 1000.0, plan.overlapMs / 1000.0, plan.incomingStartMs / 1000.0, plan.incomingSpeed, plan.bassSwapAt)
    )

    val engine = MixEngine(rate)
    val bStart = plan.incomingStartMs * rate / 1000
    // As the app does: the player moves to B 8 beats in; A's tail from its own decode.
    val beatMs = plan.overlapMs * plan.beatFraction
    val handoverAfterMs = if (plan.style == AutomixPlanner.Style.BEAT_MATCH) (beatMs * 8).toLong() else plan.overlapMs / 4
    val aFromMs = (plan.triggerMs + handoverAfterMs - 3_000).coerceAtLeast(0L)
    engine.arm("a", "b", plan, arraySource(b, bStart), arraySource(a, aFromMs * rate / 1000), aFromMs, handoverAfterMs)

    // From 20 s before the mix: the outgoing song, block by block like a player.
    val from = ((plan.triggerMs - LEAD_MS) * rate / 1000).coerceAtLeast(0L).toInt()
    val out = Pcm()
    var f = from
    val aFrames = a.size / 2
    while (!engine.handoverDue && f < aFrames) {
      val n = minOf(BLOCK, aFrames - f)
      out.add(engine.processOutgoing(a.copyOfRange(f * 2, (f + n) * 2), n, f.toLong()))
      f += n
    }
    check(engine.handoverDue) { "the handover never came: ${engine.note}" }
    log("the player moves to B at A %.2f s, %d ms into the blend".format(f / rate.toDouble(), handoverAfterMs))

    // The player is moved to B (at the stretch's speed): 150 ms to get there, landing [shiftMs] off.
    val switchOut = out.frames
    engine.switchToNext()
    val gap = rate * 150 / 1000
    out.add(engine.fill(gap))
    var p = engine.incomingPositionFrame!! + shiftMs * rate / 1000
    val endAt = out.frames + 40 * rate
    while (out.frames < endAt && p + BLOCK < b.size / 2) {
      val block = b.copyOfRange((p * 2).toInt(), ((p + BLOCK) * 2).toInt())
      out.add(if (engine.state == MixEngine.State.DONE) block else engine.processIncoming(block, BLOCK, p))
      p += BLOCK
    }
    log("handover: ${engine.note}")
    check(engine.state == MixEngine.State.DONE) { "the blend never finished: ${engine.note}" }
    // What is heard: from the switch the phone's output plays at the blend's stretch (the player's
    // speed), applied here the same way.
    val all = out.toArray()
    val heard =
      if (plan.incomingSpeed == 1f) all
      else {
        val pre = all.copyOfRange(0, (switchOut * 2).toInt())
        val st = TimeStretcher(2, rate).apply { speed = plan.incomingSpeed.toDouble() }
        val post = st.process(all, (switchOut * 2).toInt(), (out.frames - switchOut).toInt())
        pre + post
      }
    log("in the WAV: mix starts at %.1f s, blend ends at %.1f s".format(
      (plan.triggerMs * rate / 1000 - from) / rate.toDouble(),
      ((plan.triggerMs + plan.overlapMs) * rate / 1000 - from) / rate.toDouble(),
    ))

    writeWav(File(outPath), heard)
    File(outPath.removeSuffix(".wav") + ".txt").writeText(report.toString())
    log("written: $outPath")
  }

  /** -Pmix.scan=<folder>: tempo and confidence of every song in it, to pick pairs to mix. */
  @Test
  fun scan() {
    val dir = System.getProperty("mix.scan")
    assumeTrue("set -Pmix.scan to scan a folder", dir != null)
    val files =
      File(dir!!).listFiles { f -> f.extension.lowercase() in setOf("mp3", "flac", "m4a", "opus", "webm", "ogg", "wav") }
        ?.sortedBy { it.name }
        ?.let { all ->
          // -Pmix.max=N: an even sample of N songs.
          val max = System.getProperty("mix.max")?.toIntOrNull()
          if (max == null || max >= all.size) all else all.filterIndexed { i, _ -> i % ((all.size + max - 1) / max) == 0 }
        } ?: emptyList()
    val out = System.getProperty("mix.out")?.let { File(it) } ?: File(dir, "mixbench-scan.txt")
    out.writeText("")
    val lines = ArrayList<String>()
    var trackedSure = 0
    var dspSure = 0
    for (f in files) {
      val x = runCatching { decode(f.path) }.getOrNull()
      val r = x?.let { runCatching { analyze(it, useTracker = true) }.getOrNull() }
      val d = x?.let { runCatching { analyze(it, useTracker = false) }.getOrNull() }
      if ((r?.confidence ?: 0f) >= AutomixPlanner.MIN_CONFIDENCE) trackedSure++
      if ((d?.confidence ?: 0f) >= AutomixPlanner.MIN_CONFIDENCE) dspSure++
      val line =
        "beat this %7.2f bpm conf %.2f | old %7.2f bpm conf %.2f | %s".format(
          r?.bpm ?: 0f, r?.confidence ?: 0f, d?.bpm ?: 0f, d?.confidence ?: 0f, f.name,
        )
      println(line)
      lines += line
      out.appendText(line + System.lineSeparator()) // written as it goes: stopping loses nothing
    }
    val summary = "clear beat: beat this ${trackedSure}/${files.size}, old ${dspSure}/${files.size}"
    println(summary)
    out.appendText(summary + System.lineSeparator())

  }

  private fun track(r: BeatAnalyzer.Result, durationMs: Long) =
    AutomixPlanner.Track(
      bpm = r.bpm,
      confidence = r.confidence,
      firstBeatOffsetMs = r.firstBeatOffsetMs,
      durationMs = durationMs,
      downbeatOffsetMs = r.downbeatOffsetMs,
      headAnchorMs = r.headAnchorMs,
      tailAnchorMs = r.tailAnchorMs,
      mixInMs = r.mixInPointMs,
      mixOutMs = r.mixOutPointMs,
      firstSoundMs = r.firstSoundMs,
      lastSoundMs = r.lastSoundMs,
      keyPitchClass = r.keyPitchClass,
      keyIsMinor = r.keyIsMinor,
    )

  /** The same windows the phone analyzes: 18 s mid, the first 20 s, the last 30 s. */
  private fun analyze(x: FloatArray, useTracker: Boolean = System.getProperty("mix.tracker") != "dsp"): BeatAnalyzer.Result? {
    BeatAnalyzer.tracker = if (useTracker) beatThis else null
    val frames = x.size / 2
    fun mono(fromMs: Long, lenMs: Long): Pair<MonoPcm, Long> {
      val start = (fromMs * rate / 1000).toInt().coerceIn(0, frames)
      val end = ((fromMs + lenMs) * rate / 1000).toInt().coerceIn(start, frames)
      val m = FloatArray(end - start) { i -> (x[(start + i) * 2] + x[(start + i) * 2 + 1]) / 2 }
      return MonoPcm(m, rate) to start * 1000L / rate
    }
    val durationMs = frames * 1000L / rate
    val middle = mono(durationMs / 2 - 9_000, 18_000)
    val head = mono(0, 20_000)
    val tail = mono(durationMs - 30_000, 30_000)
    return BeatAnalyzer.analyzePcm(
      BeatAnalyzer.Windows(
        durationMs = durationMs,
        middle = middle.first,
        middleStartMs = middle.second,
        head = head.first,
        headStartMs = head.second,
        tail = tail.first,
        tailStartMs = tail.second,
      )
    )
  }

  /** Decodes any audio file to 48 kHz stereo float with ffmpeg. */
  private fun decode(path: String): FloatArray {
    val proc =
      ProcessBuilder("ffmpeg", "-v", "error", "-i", path, "-f", "s16le", "-ac", "2", "-ar", "$rate", "-")
        .redirectError(ProcessBuilder.Redirect.INHERIT)
        .start()
    val bytes = proc.inputStream.readBytes()
    check(proc.waitFor() == 0) { "ffmpeg could not decode $path" }
    val shorts = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
    return FloatArray(shorts.remaining()) { shorts.get(it) / 32768f }
  }

  private fun arraySource(x: FloatArray, from: Long) =
    object : PcmSource {
      var pos = from
      override fun read(dst: FloatArray, maxFrames: Int): Int {
        val n = minOf(maxFrames, (x.size / 2 - pos).toInt())
        if (n <= 0) return 0
        System.arraycopy(x, (pos * 2).toInt(), dst, 0, n * 2)
        pos += n
        return n
      }
    }

  private class Pcm {
    private val parts = ArrayList<FloatArray>()
    var frames = 0L
      private set

    fun add(x: FloatArray) {
      parts += x
      frames += x.size / 2
    }

    fun toArray(): FloatArray {
      val all = FloatArray((frames * 2).toInt())
      var o = 0
      for (p in parts) {
        System.arraycopy(p, 0, all, o, p.size)
        o += p.size
      }
      return all
    }
  }

  private fun writeWav(file: File, x: FloatArray) {
    val data = ByteBuffer.allocate(x.size * 2).order(ByteOrder.LITTLE_ENDIAN)
    for (v in x) data.putShort((v.coerceIn(-1f, 1f) * 32767).toInt().toShort())
    RandomAccessFile(file, "rw").use { f ->
      f.setLength(0)
      val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
      h.put("RIFF".toByteArray()).putInt(36 + x.size * 2).put("WAVE".toByteArray())
      h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(2).putInt(rate).putInt(rate * 4).putShort(4).putShort(16)
      h.put("data".toByteArray()).putInt(x.size * 2)
      f.write(h.array())
      f.write(data.array())
    }
  }

  companion object {
    /** Beat This! from the app's assets, on the desktop ONNX Runtime. */
    private val beatThis by lazy {
      val dir = File("src/main/assets/beatthis")
      echo.music.iad1tya.playback.audio.BeatThisTracker(
        File(dir, "mel_spectrogram.onnx").readBytes(),
        File(dir, "beat_this_small.onnx").readBytes(),
      )
    }
    private const val BLOCK = 1_152
    private const val LEAD_MS = 20_000L
    /** Room after the ease to move the player to the next song before the outgoing one ends. */
    const val HANDOVER_ROOM_MS = 3_000L
  }
}
