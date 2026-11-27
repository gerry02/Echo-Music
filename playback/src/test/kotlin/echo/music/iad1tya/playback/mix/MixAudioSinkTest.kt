package echo.music.iad1tya.playback.mix

import androidx.media3.common.AudioAttributes
import androidx.media3.common.AuxEffectInfo
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.audio.AudioSink
import echo.music.iad1tya.playback.RoutingAudioSink
import echo.music.iad1tya.playback.crossfade.AutomixPlanner
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mix sink driven the way ExoPlayer drives an audio sink, through the app's routing sink:
 * renderer timestamps with the 10^12 us offset, a stream offset per song, and the handover's seek
 * flushing (twice) and resetting the output.
 */
class MixAudioSinkTest {

  private val rate = 48_000

  /** The real output: records what it is given. */
  private class RecordingSink : AudioSink {
    val written = ArrayList<Short>()
    var flushes = 0

    override fun setListener(listener: AudioSink.Listener) {}
    override fun supportsFormat(format: Format) = true
    override fun getFormatSupport(format: Format) = AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
    /** What is heard lags what was written by the output's buffer, 200 ms. */
    override fun getCurrentPositionUs(sourceEnded: Boolean) =
      maxOf(0L, written.size / 2 * 1_000_000L / 48_000 - 200_000L)
    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {}
    override fun play() {}
    override fun handleDiscontinuity() {}
    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
      val b = buffer.order(ByteOrder.nativeOrder())
      while (b.remaining() >= 2) written += b.short
      return true
    }
    override fun playToEndOfStream() {}
    override fun isEnded() = false
    override fun hasPendingData() = false
    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {}
    override fun getPlaybackParameters() = PlaybackParameters.DEFAULT
    override fun setSkipSilenceEnabled(skipSilenceEnabled: Boolean) {}
    override fun getSkipSilenceEnabled() = false
    override fun setAudioAttributes(audioAttributes: AudioAttributes) {}
    override fun getAudioAttributes(): AudioAttributes? = null
    override fun setAudioSessionId(audioSessionId: Int) {}
    override fun setAuxEffectInfo(auxEffectInfo: AuxEffectInfo) {}
    override fun getAudioTrackBufferSizeUs() = 0L
    override fun enableTunnelingV21() {}
    override fun disableTunneling() {}
    override fun setVolume(volume: Float) {}
    override fun pause() {}
    override fun flush() {
      flushes++
    }
    override fun reset() {}
  }

  private fun noise(seconds: Double, seed: Long): FloatArray {
    var s = seed
    return FloatArray((rate * seconds).toInt() * 2) {
      s = s * 6364136223846793005L + 1442695040888963407L
      ((s ushr 40).toInt() % 2001 - 1000) / 4000f
    }
  }

  private fun pcm16(x: FloatArray, fromFrame: Int, frames: Int): ByteBuffer {
    val b = ByteBuffer.allocateDirect(frames * 4).order(ByteOrder.nativeOrder())
    for (i in 0 until frames * 2) b.putShort((x[fromFrame * 2 + i] * 32767).toInt().toShort())
    b.flip()
    return b
  }

  private val format =
    Format.Builder()
      .setSampleMimeType(MimeTypes.AUDIO_RAW)
      .setPcmEncoding(C.ENCODING_PCM_16BIT)
      .setChannelCount(2)
      .setSampleRate(48_000)
      .build()

  @Test
  fun aWholeMixThroughTheRoutingSinkBlendsThenHandsOverWithoutCancellingItself() = wholeMix(sameOffset = false)

  /** After a seek ExoPlayer may start the next song at the same renderer offset: no offset call. */
  @Test
  fun theHandoverWorksWhenTheNextSongKeepsTheSameStreamOffset() = wholeMix(sameOffset = true)

  private fun wholeMix(sameOffset: Boolean) {
    val a = noise(40.0, 1)
    val b = noise(60.0, 2)
    val out = RecordingSink()
    val engine = MixEngine(rate)
    val mix = MixAudioSink(out, engine).apply { mixMode = true }
    val events = ArrayList<String>()
    mix.onEvent = { events += it }
    val sink = RoutingAudioSink(mix, RecordingSink()) { false }

    // Song A, as ExoPlayer feeds it: offset 10^12 us.
    val offsetA = 1_000_000_000_000L
    sink.configure(format, 0, null)
    sink.setOutputStreamOffsetUs(offsetA)
    sink.play()
    val block = 1_024
    var f = 0
    fun feedA(untilFrame: Int) {
      while (f < untilFrame) {
        val n = minOf(block, untilFrame - f)
        sink.handleBuffer(pcm16(a, f, n), offsetA + f * 1_000_000L / rate, 1)
        f += n
      }
    }
    // The plan: mix at 10 s for 8 s, B from 6 s, same tempo.
    val plan =
      AutomixPlanner.Plan(
        style = AutomixPlanner.Style.BEAT_MATCH,
        triggerMs = 10_000,
        incomingStartMs = 6_000,
        overlapMs = 8_000,
        incomingSpeed = 1f,
        bassSwapAt = 0.5f,
        beatFraction = 1f / 16,
        filters = true,
      )
    feedA(rate * 2) // two seconds in, then the mix is armed
    val source =
      object : PcmSource {
        var pos = 6L * rate
        override fun read(dst: FloatArray, maxFrames: Int): Int {
          val n = minOf(maxFrames, (b.size / 2 - pos).toInt())
          System.arraycopy(b, (pos * 2).toInt(), dst, 0, n * 2)
          pos += n
          return n
        }
      }
    synchronized(engine) {
      engine.arm("a", "b", plan, source, 0)
      mix.markOutgoingStream()
    }
    // Up to just before the trigger: still waiting, A untouched.
    feedA(rate * 10 - block)
    assertEquals("not mixing before its time: ${engine.note}", MixEngine.State.WAITING, engine.state)
    // Through the blend.
    feedA(rate * 18 + block)
    assertEquals(MixEngine.State.READY, engine.state)
    // Still the outgoing song's time for the player.
    val beforeUs = sink.getCurrentPositionUs(false) - offsetA
    assertTrue("before the handover the player is at A's ${beforeUs / 1000} ms", abs(beforeUs - 17_800_000L) < 100_000)
    val handoverOut = out.written.size / 2
    val q = engine.handoverFrame!!

    // The handover: the player is sent to B; ExoPlayer flushes twice and resets the renderer.
    mix.expectHandover()
    sink.flush()
    sink.pause()
    sink.flush()
    sink.reset()
    assertEquals("the handover cancelled itself: $events", MixEngine.State.HANDING_OVER, engine.state)
    assertEquals("the real output was flushed during the handover", 0, out.flushes)
    // From the handover on the player hears the next song: its clock is in B's time at once.
    val offsetB = if (sameOffset) offsetA else offsetA + 40_000_000L
    if (!sameOffset) sink.setOutputStreamOffsetUs(offsetB)
    val afterUs = sink.getCurrentPositionUs(false) - offsetB
    val heardB = q * 1_000_000L / rate - 200_000L
    assertTrue("right after the handover the player is at ${afterUs / 1000} ms, B is at ${heardB / 1000} ms", abs(afterUs - heardB) < 30_000)
    // B, from where it was sent (40 ms off).
    sink.configure(format, 0, null)
    sink.play()
    var p = (q + rate * 40 / 1000).toInt()
    var lastPos = sink.getCurrentPositionUs(false)
    repeat(80) {
      sink.handleBuffer(pcm16(b, p, block), offsetB + p * 1_000_000L / rate, 1)
      p += block
      val pos = sink.getCurrentPositionUs(false)
      if (pos != AudioSink.CURRENT_POSITION_NOT_SET) {
        assertTrue("the clock went back: $lastPos -> $pos", pos >= lastPos - 1_000)
        lastPos = pos
      }
    }
    // The player's clock is where B is heard, never on the old song.
    val endUs = sink.getCurrentPositionUs(false) - offsetB
    val heardEnd = (q + (out.written.size / 2 - handoverOut)) * 1_000_000L / rate - 200_000L
    assertTrue("at the end the player is at ${endUs / 1000} ms, B is heard at ${heardEnd / 1000} ms", abs(endUs - heardEnd) < 30_000)
    assertEquals("not joined: ${engine.note}; $events", MixEngine.State.DONE, engine.state)

    // From the handover on, the output is B itself.
    val w = out.written
    for (k in handoverOut until w.size / 2 - block) {
      for (c in 0 until 2) {
        val expected = (b[((q + (k - handoverOut)) * 2 + c).toInt()] * 32767).toInt()
        assertTrue("frame $k: ${w[k * 2 + c]} vs $expected; ${engine.note}", abs(w[k * 2 + c] - expected) <= 3)
      }
    }
  }
}
