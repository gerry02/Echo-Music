package echo.music.iad1tya.viewmodels

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.music.innertube.YouTube
import com.music.innertube.pages.ChartsPage
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import echo.music.iad1tya.constants.ChartsCountryKey
import echo.music.iad1tya.ui.screens.search.suggestions.AppleMusicScraper
import echo.music.iad1tya.ui.screens.search.suggestions.SuggestionTrack
import echo.music.iad1tya.utils.dataStore
import echo.music.iad1tya.utils.get
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@HiltViewModel
class ChartsViewModel @Inject constructor(@ApplicationContext private val context: Context) :
  ViewModel() {
  private val _chartsPage = MutableStateFlow<ChartsPage?>(null)
  val chartsPage = _chartsPage.asStateFlow()

  private val _isLoading = MutableStateFlow(false)
  val isLoading = _isLoading.asStateFlow()

  private val _error = MutableStateFlow<String?>(null)
  val error = _error.asStateFlow()

  /** ISO code of the charts shown, or null until the first load picks the content country. */
  private val _country = MutableStateFlow<String?>(null)
  val country = _country.asStateFlow()

  /** Apple Music top songs for the same country; empty when Apple has no chart for it. */
  private val _appleTopSongs = MutableStateFlow<List<SuggestionTrack>>(emptyList())
  val appleTopSongs = _appleTopSongs.asStateFlow()

  private var loadJob: Job? = null

  fun loadCharts() {
    loadJob?.cancel()
    loadJob =
      viewModelScope.launch {
        _isLoading.value = true
        _error.value = null

        val code =
          _country.value
            ?: context.dataStore.get(ChartsCountryKey)?.takeIf { it.isNotBlank() }
            ?: YouTube.locale.gl.uppercase()
        _country.value = code

        launch { loadAppleTopSongs(code) }

        YouTube.getChartsPage(country = code)
          .onSuccess { page ->
            _chartsPage.value = page
            page.selectedCountry?.let { _country.value = it }
          }
          .onFailure { e -> _error.value = "Failed to load charts: ${e.message}" }

        _isLoading.value = false
      }
  }

  fun selectCountry(code: String) {
    if (code == _country.value && _chartsPage.value != null) return
    _country.value = code
    _chartsPage.value = null
    _appleTopSongs.value = emptyList()
    viewModelScope.launch { context.dataStore.edit { it[ChartsCountryKey] = code } }
    loadCharts()
  }

  private suspend fun loadAppleTopSongs(code: String) {
    // Apple has no worldwide chart: show the US one there, as Apple Music itself does.
    val appleCode = if (code == ChartsPage.WORLDWIDE) "us" else code.lowercase()
    val songs =
      withContext(Dispatchers.IO) {
        runCatching { AppleMusicScraper.fetchTopSongs(appleCode) }.getOrDefault(emptyList())
      }
    if (_country.value == code) _appleTopSongs.value = songs
  }

  fun loadMore() {
    viewModelScope.launch {
      _chartsPage.value?.continuation?.let { continuation ->
        _isLoading.value = true
        YouTube.getChartsPage(continuation = continuation)
          .onSuccess { newPage ->
            _chartsPage.value =
              _chartsPage.value?.copy(
                sections = _chartsPage.value?.sections.orEmpty() + newPage.sections,
                continuation = newPage.continuation,
              )
          }
          .onFailure { e -> _error.value = "Failed to load more: ${e.message}" }
        _isLoading.value = false
      }
    }
  }
}
