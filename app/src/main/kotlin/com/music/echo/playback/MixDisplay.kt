package echo.music.iad1tya.playback

import androidx.media3.common.MediaItem
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The song a mix shows before the player moves to it: from halfway through the blend the screen,
 * the notification and the car show the next song (it is the one heard), while the player still
 * plays the outgoing one under the mixer until the transition is over.
 */
object MixDisplay {
  @Volatile var item: MediaItem? = null
    private set
  @Volatile private var position: (() -> Long?)? = null
  private val listeners = CopyOnWriteArrayList<() -> Unit>()

  /** Where [item] is heard, in ms. */
  fun positionMs(): Long? = position?.invoke()

  fun show(item: MediaItem, position: () -> Long?) {
    this.item = item
    this.position = position
    listeners.forEach { it() }
  }

  fun clear() {
    if (item == null) return
    item = null
    position = null
    listeners.forEach { it() }
  }

  fun addListener(l: () -> Unit) {
    listeners += l
  }

  fun removeListener(l: () -> Unit) {
    listeners -= l
  }
}
