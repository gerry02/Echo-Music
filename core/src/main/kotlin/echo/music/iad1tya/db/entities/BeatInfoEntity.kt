package echo.music.iad1tya.db.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "beat_info")
data class BeatInfoEntity(
  @PrimaryKey val songId: String,
  val bpm: Float,
  val firstBeatOffsetMs: Long,
  val confidence: Float,
  val analyzedAt: Long = System.currentTimeMillis(),
  /** Where the incoming track should start: first sustained-energy downbeat past the intro. */
  val mixInPointMs: Long? = null,
  /** Where the outgoing track's body ends (outro begins); transition starts here. */
  val mixOutPointMs: Long? = null,
  /** 0=C, 1=C#, ... 11=B. Null when the track's chroma signal was too weak to call a key. */
  val keyPitchClass: Int? = null,
  val keyIsMinor: Boolean? = null,
  /** Version of the analysis that wrote the row; older rows are analyzed again once. */
  @ColumnInfo(defaultValue = "0") val analysisVersion: Int = 0,
  /** A first beat of a bar of the grid measured in the middle of the song. */
  val downbeatOffsetMs: Long? = null,
  /** A first beat of a bar measured near the start of the song. */
  val headAnchorMs: Long? = null,
  /** A first beat of a bar measured near the end of the song. */
  val tailAnchorMs: Long? = null,
  /** Where the audio starts after leading silence. */
  val firstSoundMs: Long? = null,
  /** Where the audio ends before trailing silence. */
  val lastSoundMs: Long? = null,
  /** Length of the audio analyzed, to tell when a different edit of the song plays. */
  val analyzedDurationMs: Long? = null,
  /** Where the first voice starts; null when the song was not analysed for voices. */
  @ColumnInfo(defaultValue = "NULL") val firstVocalMs: Long? = null,
  /** Where the last voice ends. */
  @ColumnInfo(defaultValue = "NULL") val lastVocalMs: Long? = null,
)
