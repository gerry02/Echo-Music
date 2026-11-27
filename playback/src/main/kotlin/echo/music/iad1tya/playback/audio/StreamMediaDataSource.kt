package echo.music.iad1tya.playback.audio

import android.media.MediaDataSource
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.io.IOException

/**
 * A song's bytes through the player's own data-source chain (its cache first, under the same key),
 * for MediaExtractor. The mix decodes the next song with it: the bytes it fetches are the ones the
 * player plays next anyway, cached once for both.
 */
@UnstableApi
class StreamMediaDataSource(factory: DataSource.Factory, private val key: String) : MediaDataSource() {

  private val source = factory.createDataSource()
  private var openAt = -1L
  private var length = -1L

  override fun getSize(): Long = length

  override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
    if (length > 0 && position >= length) return -1
    if (size == 0) return 0
    if (openAt != position) {
      closeSource()
      val opened =
        source.open(DataSpec.Builder().setUri(Uri.parse(key)).setKey(key).setPosition(position).build())
      if (opened != C.LENGTH_UNSET.toLong() && length < 0) length = position + opened
      openAt = position
    }
    val read =
      try {
        source.read(buffer, offset, size)
      } catch (e: IOException) {
        closeSource()
        throw e
      }
    if (read == C.RESULT_END_OF_INPUT) {
      closeSource()
      return -1
    }
    openAt += read
    return read
  }

  private fun closeSource() {
    if (openAt >= 0) {
      try {
        source.close()
      } catch (_: Exception) {}
    }
    openAt = -1L
  }

  override fun close() = closeSource()
}
