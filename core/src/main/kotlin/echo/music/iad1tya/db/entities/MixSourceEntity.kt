package echo.music.iad1tya.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * The DJ mix choice for one source of songs: a playlist, an album, a radio... ([sourceKey], e.g.
 * "yt-playlist:PL..", "local-playlist:..", "album:..", "radio:<videoId>"). Sources without a row
 * play without the mix.
 */
@Entity(tableName = "mix_source")
data class MixSourceEntity(
  @PrimaryKey val sourceKey: String,
  /** Songs from this source are beat-mixed into each other. */
  val mix: Boolean = false,
  /** With the mix on, the best-mixing of the next songs is moved up to play next. */
  val pickBestNext: Boolean = false,
)
