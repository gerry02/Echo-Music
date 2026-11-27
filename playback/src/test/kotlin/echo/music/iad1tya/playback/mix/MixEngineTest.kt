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
    // Mix at A's bar at 20.25 s; B starts at its beat 10 (95 + 10 beats).
    val inStart = (95 + 10 * bPeriod).toLong()
    val e = MixEngine(rate)
    e.arm("a", "b", plan(20_250, inStart, 8_000, 120f / 124f, 16), source(b, inStart * rate / 1000), 4_000)
    val out = runOutgoing(e, a, 0, rate * 30)
    // Clicks heard during the second half of the blend (loud enough), against A's grid.
    var worst = 0.0
    var checked = 0
    for (k in 8 until 16) {
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
    assertEquals(8, checked)
  }

  private fun handover(playerShift: Long, decoderOffset: Int, music: Boolean = false) {
    val a = noise(40.0, 1)
    // Music repeats itself: clicks and a held tone match well at many shifts, only one exactly.
    val b =
      if (music) clicks(70.0, 95.0, 60_000.0 / 124, 3_000.0).also { x ->
        for (i in x.indices) x[i] += (0.2 * sin(2 * PI * 220 * (i / 2) / rate)).toFloat()
      } else noise(70.0, 2)
    val e = MixEngine(rate)
    val inStart = 6_000L
    e.arm("a", "b", plan(10_000, inStart, 8_000, 0.97f, 16), source(b, inStart * rate / 1000), 4_000)
    // Blend (8 s) and ease (4 s) on the outgoing song's samples.
    var out = runOutgoing(e, a, 0, rate * 23)
    assertEquals(MixEngine.State.READY, e.state)
    val q = e.handoverFrame!!
    e.startHandover()
    // 120 ms for the player to get there: our own audio meanwhile.
    out += e.fill(rate * 120 / 1000)
    // The player starts near where it was sent ([playerShift] off), decoding [decoderOffset]
    // samples apart from us: its frame p sounds like our frame p + decoderOffset.
    var p = q + rate * 120 / 1000 + playerShift
    val tail = ArrayList<FloatArray>()
    repeat(60) {
      val n = 1_024
      val block = FloatArray(n * 2) { i -> b[((p + decoderOffset) * 2 + i).toInt()] }
      tail += e.processIncoming(block, n, p)
      p += n
    }
    out += tail.fold(FloatArray(0)) { acc, x -> acc + x }
    assertEquals(MixEngine.State.DONE, e.state)
    // From the handover on, the output is the next song itself, with no jump.
    val startOut = rate * 23
    for (i in 0 until (out.size / 2 - startOut)) {
      for (c in 0 until 2) {
        val expected = b[((q + i) * 2 + c).toInt()]
        val got = out[(startOut + i) * 2 + c]
        if (abs(expected - got) > 2e-3f) {
          throw AssertionError("frame ${startOut + i} (next song ${q + i}): $got, expected $expected; ${e.note}")
        }
      }
    }
  }

  @Test
  fun handoverIsSeamlessWhenThePlayerLandsLate() = handover(playerShift = -700, decoderOffset = 0)

  @Test
  fun handoverIsSeamlessWhenThePlayerLandsEarly() = handover(playerShift = 900, decoderOffset = 0)

  @Test
  fun handoverIsSeamlessWhenThePlayerLandsWellAhead() = handover(playerShift = 6_000, decoderOffset = 0)

  @Test
  fun handoverOnRepetitiveMusicPicksTheExactSample() = handover(playerShift = 1_920, decoderOffset = 0, music = true)

  @Test
  fun handoverMatchesADecoderThatIsSamplesApart() = handover(playerShift = 200, decoderOffset = 312)
}
