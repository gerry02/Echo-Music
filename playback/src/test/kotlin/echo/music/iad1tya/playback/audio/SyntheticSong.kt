package echo.music.iad1tya.playback.audio

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * A 4/4 song built from drums and chords, with a quiet intro, a quieter outro and trailing silence,
 * so the analysis has known beats, bars and sections to find.
 */
class SyntheticSong(
  val bpm: Double,
  /** Where the first bar starts. */
  val firstDownbeatMs: Double,
  val durationMs: Long,
  /** The body starts on this bar; before it only hi-hats and a soft pad play. */
  val introBars: Int = 4,
  /** The body ends this many ms before [durationMs] (rounded to a bar); a soft pad follows. */
  val outroMs: Double = 16_000.0,
  val trailingSilenceMs: Double = 2_000.0,
  val sampleRate: Int = 44_100,
  /** Constant offset added to the clock: the same song as another stream that starts later. */
  val shiftMs: Double = 0.0,
) {
  val beatMs = 60_000.0 / bpm
  val barMs = beatMs * 4

  val bodyStartMs = firstDownbeatMs + introBars * barMs
  val bodyEndMs: Double = run {
    val target = durationMs - trailingSilenceMs - outroMs
    firstDownbeatMs + Math.round((target - firstDownbeatMs) / barMs) * barMs
  }
  val soundEndMs = durationMs - trailingSilenceMs

  private val chordRoots = doubleArrayOf(220.0, 174.61, 261.63, 196.0) // A F C G

  fun sample(ms: Double, noise: Random): Float {
    val t = ms - shiftMs
    if (t < 0 || t >= soundEndMs) return 0f
    val sinceFirst = t - firstDownbeatMs
    val beatIndex = Math.floorDiv(Math.floor(sinceFirst / beatMs).toLong(), 1L)
    val inBeat = sinceFirst - beatIndex * beatMs
    val beatInBar = Math.floorMod(beatIndex, 4L).toInt()
    val bar = Math.floorDiv(beatIndex, 4L)
    val body = t >= bodyStartMs && t < bodyEndMs
    var s = 0.0

    if (body && sinceFirst >= 0) {
      // Kick on 1 and 3, the 1 harder; snare on 2 and 4.
      if (beatInBar == 0 || beatInBar == 2) {
        val amp = if (beatInBar == 0) 0.9 else 0.6
        val env = exp(-inBeat / 60.0)
        val f = 50.0 + 60.0 * exp(-inBeat / 20.0)
        s += amp * env * sin(2 * PI * f * inBeat / 1000.0)
      } else {
        s += 0.35 * exp(-inBeat / 40.0) * (noise.nextDouble() * 2 - 1)
      }
    }
    // Hi-hat on every eighth, quiet.
    val inEighth = ((sinceFirst % (beatMs / 2)) + beatMs / 2) % (beatMs / 2)
    if (sinceFirst >= 0) s += 0.08 * exp(-inEighth / 8.0) * (noise.nextDouble() * 2 - 1)

    // Chord, changing on every bar.
    val root = chordRoots[Math.floorMod(bar, chordRoots.size.toLong()).toInt()]
    val padAmp = if (body) 0.15 else 0.05
    for (ratio in doubleArrayOf(1.0, 1.26, 1.5)) {
      s += padAmp / 3 * sin(2 * PI * root * ratio * t / 1000.0)
    }
    return s.toFloat().coerceIn(-1f, 1f)
  }

  fun pcm(startMs: Double, lengthMs: Double, seed: Int = 1): MonoPcm {
    val noise = Random(seed)
    val n = (lengthMs * sampleRate / 1000).toInt()
    val out = FloatArray(n) { sample(startMs + it * 1000.0 / sampleRate, noise) }
    return MonoPcm(out, sampleRate)
  }

  /** The windows BeatAnalyzer decodes from a real file. */
  fun windows(): BeatAnalyzer.Windows {
    val playedDuration = durationMs + shiftMs.toLong()
    val midStart = (playedDuration / 2 - 9_000).toDouble()
    val tailStart = (playedDuration - 30_000).toDouble()
    return BeatAnalyzer.Windows(
      durationMs = playedDuration,
      middle = pcm(midStart, 18_000.0),
      middleStartMs = midStart.toLong(),
      head = pcm(0.0, 20_000.0),
      headStartMs = 0L,
      tail = pcm(tailStart, 30_000.0),
      tailStartMs = tailStart.toLong(),
    )
  }
}
