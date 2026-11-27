package echo.music.iad1tya.playback.mix

import echo.music.iad1tya.playback.crossfade.AutomixPlanner
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MixEngineTest {

  private val rate = 48_000

  /** A source reading [x] (interleaved stereo) from frame [from], [chunk] frames at a time. */
  private fun source(x: FloatArray, from: Long, chunk: Int = 4096) =
    object : PcmSource {
      var pos = from
      override fun read(dst: FloatArray, maxFrames: Int): Int {
        val n = minOf(maxFrames, chunk, (x.size / 2 - pos).toInt())
        if (n <= 0) return 0
        System.arraycopy(x, (pos * 2).toInt(), dst, 0, n * 2)
        pos += n
        return n
      }
    }

  private fun clicks(seconds: Double, firstMs: Double, periodMs: Double, hz: Double): FloatArray {
    val n = (rate * seconds).toInt()
    val x = FloatArray(n * 2)
    var t = firstMs
    while (t < seconds * 1000) {
      val start = (t * rate / 1000).toInt()
      for (i in 0 until rate / 100) {
        val f = start + i
        if (f >= n) break
        val v = (0.8 * sin(2 * PI * hz * i / rate) * (1 - i / (rate / 100.0))).toFloat()
        x[f * 2] = v
        x[f * 2 + 1] = v
      }
      t += periodMs
    }
    return x
  }

  private fun noise(seconds: Double, seed: Int): FloatArray {
    var s = seed.toLong()
    return FloatArray((rate * seconds).toInt() * 2) {
      s = (s * 6364136223846793005L + 1442695040888963407L)
      ((s ushr 40).toInt() % 2001 - 1000) / 4000f
    }
  }

  private fun plan(
    triggerMs: Long,
    incomingStartMs: Long,
    overlapMs: Long,
    speed: Float,
    beats: Int,
  ) =
    AutomixPlanner.Plan(
      style = AutomixPlanner.Style.BEAT_MATCH,
      triggerMs = triggerMs,
      incomingStartMs = incomingStartMs,
      overlapMs = overlapMs,
      incomingSpeed = speed,
      bassSwapAt = 0.5f,
      beatFraction = 1f / beats,
      filters = false,
    )

  /** Runs the outgoing song [a] through the engine in player-sized blocks from [fromFrame]. */
  private fun runOutgoing(e: MixEngine, a: FloatArray, fromFrame: Int, toFrame: Int): FloatArray {
    val out = ArrayList<FloatArray>()
    var f = fromFrame
    while (f < toFrame) {
      val n = minOf(1_152, toFrame - f)
      val block = a.copyOfRange(f * 2, (f + n) * 2)
      out += e.processOutgoing(block, n, f.toLong())
      f += n
    }
    return out.fold(FloatArray(0)) { acc, x -> acc + x }
  }

  @Test
  fun theNextSongsBeatsLandOnTheOutgoingBeatsToTheSample() {
    // Outgoing: silent, 120 BPM grid from 250 ms. Incoming: 124 BPM clicks from 95 ms, stretched.
    val a = FloatArray(rate * 40 * 2)
    val bPeriod = 60_000.0 / 124
    val b = clicks(60.0, 95.0, bPeriod, 3_000.0)
    val inStart = (95 + 10 * bPeriod).toLong()
    val e = MixEngine(rate)
    // No handover during the blend here (the player stays on the outgoing song).
    e.arm("a", "b", plan(20_250, inStart, 8_000, 120f / 124f, 16), source(b, inStart * rate / 1000), null, 0, 7_999)
    val out = runOutgoing(e, a, 0, (rate * 27.5).toInt())
    var worst = 0.0
    var checked = 0
    for (k in 8 until 14) {
      val expected = (20_250 + k * 500.0) * rate / 1000
      val from = (expected - 0.03 * rate).toInt()
      val to = (expected + 0.03 * rate).toInt()
      var onset = -1
      val peak = (from until to).maxOf { abs(out[it * 2]) }
      for (f in from until to) if (abs(out[f * 2]) > peak * 0.3f) { onset = f; break }
      assertTrue("no click near beat $k", onset >= 0)
      worst = maxOf(worst, abs(onset - expected))
      checked++
    }
    // Within 10 ms (the stretcher's placement) of the outgoing beat, whatever the device.
    assertTrue("worst ${worst / rate * 1000} ms", worst / rate * 1000 < 10.5)
    assertEquals(6, checked)
  }

  /**
   * The whole transition with the early handover: blend on the player's outgoing song, the player
   * moved to the next song 2 s in (landing [playerShift] off, decoding [decoderOffset] samples apart),
   * the outgoing tail from its own decode, and the player's own audio once the blend is over.
   */
  private fun handover(
    playerShift: Long,
    decoderOffset: Int,
    speed: Float = 1f,
    music: Boolean = false,
    playerAudio: FloatArray? = null,
  ): MixEngine {
    val a = noise(40.0, 1)
    val b =
      if (music) clicks(70.0, 95.0, 60_000.0 / 124, 3_000.0).also { x ->
        for (i in x.indices) x[i] += (0.2 * sin(2 * PI * 220 * (i / 2) / rate)).toFloat()
      } else noise(70.0, 2)
    val e = MixEngine(rate)
    val inStart = 6_000L
    // The whole transition in the mixer: the blend, the stretch eased back (4 s), then the player.
    e.arm(
      "a",
      "b",
      plan(10_000, inStart, 8_000, speed, 16),
      source(b, inStart * rate / 1000),
      null,
      0,
      8_000,
      easeMs = 4_000,
    )
    // The outgoing song through the player until the handover is due.
    var f = 0
    val outs = ArrayList<FloatArray>()
    while (!e.handoverDue) {
      val n = 1_152
      outs += e.processOutgoing(a.copyOfRange(f * 2, (f + n) * 2), n, f.toLong())
      f += n
      assertTrue("handover never due: ${e.note}", f < rate * 30)
    }
    val q0 = e.handoverFrame!!
    // A couple more outgoing buffers arrive before the switch reaches the output.
    outs += e.processOutgoing(a.copyOfRange(f * 2, (f + 1_152) * 2), 1_152, f.toLong())
    f += 1_152
    e.switchToNext()
    assertEquals(MixEngine.State.SWITCHING, e.state)
    // A late outgoing buffer after the switch: it stands for our decode, as many frames.
    assertEquals(1_152, e.processOutgoing(a.copyOfRange(f * 2, (f + 1_152) * 2), 1_152, f.toLong()).size / 2)
    // 120 ms for the player to get there.
    outs += e.fill(rate * 120 / 1000)
    val q = e.incomingPositionFrame!!
    var p = q + playerShift
    val src = playerAudio ?: b
    var lastBlock = FloatArray(0)
    var lastOut = FloatArray(0)
    repeat(400) {
      if (e.state == MixEngine.State.DONE && lastBlock.isNotEmpty()) return@repeat
      val n = 1_024
      val block = FloatArray(n * 2) { i -> src[((p + decoderOffset) * 2 + i).toInt()] }
      lastOut = e.processIncoming(block, n, p)
      lastBlock = block
      p += n
    }
    assertEquals("not done: ${e.note}", MixEngine.State.DONE, e.state)
    // Once the blend is over, the player's own audio passes through untouched.
    val block = FloatArray(1_024 * 2) { i -> src[((p + decoderOffset) * 2 + i).toInt()] }
    val passed = e.processIncoming(block, 1_024, p)
    assertTrue(passed.contentEquals(block))
    assertTrue(q0 > 0)
    // Both songs were heard together for most of the 8 s blend.
    assertTrue("both heard ${e.bothAudibleMs} ms: ${e.note}", e.bothAudibleMs > 5_000)
    return e
  }

  @Test
  fun withoutTheOutgoingDecodeThePlayerStaysOnItToTheEndOfTheBlend() {
    // The outgoing song's own decode is missing (on the phone: its decode failed): no early
    // handover, the whole blend plays on the player's outgoing song.
    val a = noise(40.0, 1)
    val b = noise(70.0, 2)
    val e = MixEngine(rate)
    e.arm("a", "b", plan(10_000, 6_000, 8_000, 1f, 16), source(b, 6L * rate), null, 9_000, 2_000)
    var f = 0
    while (!e.handoverDue) {
      e.processOutgoing(a.copyOfRange(f * 2, (f + 1_152) * 2), 1_152, f.toLong())
      f += 1_152
      assertTrue("never due: ${e.note}", f < rate * 25)
    }
    // Due only once the blend is over (10 s + 8 s), all of it heard together.
    assertTrue("due at ${f / rate.toDouble()} s", f >= rate * 18)
    assertTrue("both heard ${e.bothAudibleMs} ms", e.bothAudibleMs > 5_000)
  }

  @Test
  fun earlyHandoverWhenThePlayerLandsLate() {
    handover(playerShift = -700, decoderOffset = 0)
  }

  @Test
  fun earlyHandoverWhenThePlayerLandsEarly() {
    handover(playerShift = 900, decoderOffset = 0)
  }

  @Test
  fun earlyHandoverWithAStretchedBlend() {
    handover(playerShift = 200, decoderOffset = 0, speed = 0.97f)
  }

  @Test
  fun earlyHandoverOnRepetitiveMusicPicksTheExactSample() {
    val e = handover(playerShift = 1_920, decoderOffset = 0, music = true)
    assertTrue(e.note, e.note.contains("player matched at offset 0 frames"))
  }

  @Test
  fun earlyHandoverMatchesADecoderThatIsSamplesApart() {
    val e = handover(playerShift = 200, decoderOffset = 312)
    assertTrue(e.note, e.note.contains("player matched at offset 312 frames"))
  }

  @Test
  fun whenThePlayerPlaysOtherAudioItIsCrossfadedNotSpliced() {
    val e = handover(playerShift = 0, decoderOffset = 0, playerAudio = noise(70.0, 9))
    assertTrue(e.note, e.note.contains("different audio"))
  }
}
