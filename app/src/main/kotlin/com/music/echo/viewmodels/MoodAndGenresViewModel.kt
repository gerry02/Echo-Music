package echo.music.iad1tya.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.music.innertube.YouTube
import com.music.innertube.pages.MoodAndGenres
import dagger.hilt.android.lifecycle.HiltViewModel
import echo.music.iad1tya.utils.reportException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class MoodAndGenresViewModel @Inject constructor() : ViewModel() {
  val moodAndGenres = MutableStateFlow<List<MoodAndGenres>?>(null)

  /** Cover per category params, borrowed from the category's first playlist. */
  private val _artwork = MutableStateFlow<Map<String, String>>(artworkCache.toMap())
  val artwork = _artwork.asStateFlow()

  init {
    viewModelScope.launch {
      YouTube.moodAndGenres()
        .onSuccess { moodAndGenres.value = it }
        .onFailure { reportException(it) }
    }
  }

  /**
   * YouTube's moods list has only a colour per category: fetch the category page once to use its
   * first playlist cover on the Discover card. Failures just leave the coloured tile.
   */
  fun loadArtwork(browseId: String, params: String) {
    if (artworkCache.containsKey(params) || !requested.add(params)) return
    viewModelScope.launch {
      YouTube.browse(browseId, params)
        .onSuccess { result ->
          result.artworkUrl?.let { url ->
            artworkCache[params] = url
            _artwork.update { it + (params to url) }
          }
        }
        .onFailure { requested.remove(params) }
    }
  }

  private companion object {
    // Kept for the process so the grid does not refetch on every visit.
    val artworkCache = ConcurrentHashMap<String, String>()
    val requested: MutableSet<String> = ConcurrentHashMap.newKeySet()
  }
}
