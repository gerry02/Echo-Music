package echo.music.iad1tya.playback.audio

import android.media.MediaDataSource
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.ContentMetadata
import java.io.IOException

/**
 * A song's bytes as far as [cache] holds them, for MediaExtractor. Never reaches the network: a
 * read past what is cached fails, so measuring a lossless stream costs no download from its host.
 */
@UnstableApi
class CacheMediaDataSource(cache: Cache, private val key: String) : MediaDataSource() {

  private val length: Long = ContentMetadata.getContentLength(cache.getContentMetadata(key))
  private val source = CacheDataSource(cache, null) // no upstream: cache misses throw
  private var openAt = -1L

  val isUsable: Boolean
    get() = length > 0

  override fun getSize(): Long = if (length > 0) length else -1L

  override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
    if (length > 0 && position >= length) return -1
    if (size == 0) return 0
    if (openAt != position) {
      closeSource()
      source.open(
        DataSpec.Builder()
          .setUri(Uri.parse("cache://$key"))
          .setKey(key)
          .setPosition(position)
          .build(),
      )
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
