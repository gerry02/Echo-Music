package echo.music.iad1tya.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.music.innertube.models.AlbumItem
import com.music.innertube.models.ArtistItem
import com.music.innertube.models.PlaylistItem
import com.music.innertube.models.SongItem
import com.music.innertube.models.YTItem
import com.music.innertube.pages.ChartsPage
import echo.music.iad1tya.LocalPlayerAwareWindowInsets
import echo.music.iad1tya.LocalPlayerConnection
import echo.music.iad1tya.R
import echo.music.iad1tya.constants.ListItemHeight
import echo.music.iad1tya.models.toMediaMetadata
import echo.music.iad1tya.playback.queues.YouTubeQueue
import echo.music.iad1tya.ui.component.IconButton
import echo.music.iad1tya.ui.component.LocalMenuState
import echo.music.iad1tya.ui.component.NavigationTitle
import echo.music.iad1tya.ui.component.YouTubeGridItem
import echo.music.iad1tya.ui.component.shimmer.GridItemPlaceHolder
import echo.music.iad1tya.ui.component.shimmer.ShimmerHost
import echo.music.iad1tya.ui.component.shimmer.TextPlaceholder
import echo.music.iad1tya.ui.menu.YouTubeAlbumMenu
import echo.music.iad1tya.ui.menu.YouTubeArtistMenu
import echo.music.iad1tya.ui.menu.YouTubePlaylistMenu
import echo.music.iad1tya.ui.menu.YouTubeSongMenu
import echo.music.iad1tya.ui.screens.search.suggestions.SuggestionTrack
import echo.music.iad1tya.ui.screens.search.suggestions.SuggestionsViewModel
import echo.music.iad1tya.ui.utils.backToMain
import echo.music.iad1tya.viewmodels.ChartsViewModel

/**
 * YouTube Music charts for one country (chart playlists, genre charts, top artists), plus the
 * Apple Music top songs of the same country, played through a YouTube Music match.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChartsScreen(
  navController: NavController,
  viewModel: ChartsViewModel = hiltViewModel(),
  suggestionsViewModel: SuggestionsViewModel = hiltViewModel(),
) {
  val menuState = LocalMenuState.current
  val haptic = LocalHapticFeedback.current
  val playerConnection = LocalPlayerConnection.current ?: return
  val isPlaying by playerConnection.isEffectivelyPlaying.collectAsState()
  val mediaMetadata by playerConnection.mediaMetadata.collectAsState()
  val coroutineScope = rememberCoroutineScope()

  val chartsPage by viewModel.chartsPage.collectAsState()
  val isLoading by viewModel.isLoading.collectAsState()
  val error by viewModel.error.collectAsState()
  val country by viewModel.country.collectAsState()
  val appleTopSongs by viewModel.appleTopSongs.collectAsState()

  var showCountryPicker by rememberSaveable { mutableStateOf(false) }
  // The country list comes with the first page; keep it while another country loads.
  var countries by remember { mutableStateOf(emptyList<ChartsPage.Country>()) }
  LaunchedEffect(chartsPage) {
    chartsPage?.countries?.takeIf { it.isNotEmpty() }?.let { countries = it }
  }

  LaunchedEffect(Unit) { if (chartsPage == null) viewModel.loadCharts() }

  val countryName = countries.firstOrNull { it.code == country }?.name ?: country.orEmpty()

  fun openItem(item: YTItem) {
    when (item) {
      is SongItem ->
        if (item.id == mediaMetadata?.id) {
          playerConnection.togglePlayPause()
        } else {
          playerConnection.playQueue(YouTubeQueue.radio(item.toMediaMetadata()))
        }
      is AlbumItem -> navController.navigate("album/${item.id}")
      is ArtistItem -> navController.navigate("artist/${item.id}")
      is PlaylistItem -> navController.navigate("online_playlist/${item.id}")
    }
  }

  fun showItemMenu(item: YTItem) {
    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    menuState.show {
      when (item) {
        is SongItem ->
          YouTubeSongMenu(song = item, navController = navController, onDismiss = menuState::dismiss)
        is AlbumItem ->
          YouTubeAlbumMenu(
            albumItem = item,
            navController = navController,
            onDismiss = menuState::dismiss,
          )
        is ArtistItem -> YouTubeArtistMenu(artist = item, onDismiss = menuState::dismiss)
        is PlaylistItem ->
          YouTubePlaylistMenu(
            playlist = item,
            coroutineScope = coroutineScope,
            onDismiss = menuState::dismiss,
          )
      }
    }
  }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(stringResource(R.string.charts)) },
        navigationIcon = {
          IconButton(
            onClick = { navController.navigateUp() },
            onLongClick = { navController.backToMain() },
          ) {
            Icon(painter = painterResource(R.drawable.arrow_back), contentDescription = null)
          }
        },
        actions = {
          if (countries.isNotEmpty()) {
            AssistChip(
              onClick = { showCountryPicker = true },
              label = { Text(countryName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
              trailingIcon = {
                Icon(
                  painter = painterResource(R.drawable.expand_more),
                  contentDescription = null,
                  modifier = Modifier.size(18.dp),
                )
              },
              modifier = Modifier.padding(end = 8.dp),
            )
          }
        },
      )
    },
  ) { paddingValues ->
    Box(modifier = Modifier.fillMaxSize().padding(top = paddingValues.calculateTopPadding())) {
      val page = chartsPage
      when {
        page == null && error != null && !isLoading ->
          Column(
            modifier = Modifier.align(Alignment.Center).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
          ) {
            Text(
              text = stringResource(R.string.error_unknown),
              style = MaterialTheme.typography.bodyLarge,
            )
            TextButton(onClick = { viewModel.loadCharts() }) {
              Text(stringResource(R.string.retry))
            }
          }
        page == null ->
          ShimmerHost(modifier = Modifier.fillMaxSize()) {
            repeat(3) {
              TextPlaceholder(
                height = 36.dp,
                modifier = Modifier.padding(12.dp).fillMaxWidth(0.5f),
              )
              Row { repeat(2) { GridItemPlaceHolder() } }
            }
          }
        else ->
          LazyColumn(
            contentPadding =
              LocalPlayerAwareWindowInsets.current
                .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
                .asPaddingValues(),
          ) {
            page.sections.forEachIndexed { index, section ->
              item(key = "title_$index") { NavigationTitle(title = section.title) }
              item(key = "row_$index") {
                LazyRow(
                  contentPadding = PaddingValues(horizontal = 8.dp),
                ) {
                  items(items = section.items.distinctBy { it.id }, key = { it.id }) { item ->
                    YouTubeGridItem(
                      item = item,
                      isActive =
                        when (item) {
                          is SongItem -> mediaMetadata?.id == item.id
                          is AlbumItem -> mediaMetadata?.album?.id == item.id
                          else -> false
                        },
                      isPlaying = isPlaying,
                      coroutineScope = coroutineScope,
                      modifier =
                        Modifier.combinedClickable(
                          onClick = { openItem(item) },
                          onLongClick = { showItemMenu(item) },
                        ),
                    )
                  }
                }
              }
              // Apple Music's chart sits right after YouTube's top charts.
              if (index == 0 && appleTopSongs.isNotEmpty()) {
                appleTopSongsSection(
                  songs = appleTopSongs,
                  onPlay = { track -> suggestionsViewModel.playTrack(track, playerConnection) },
                )
              }
            }
            if (page.sections.isEmpty() && appleTopSongs.isNotEmpty()) {
              appleTopSongsSection(
                songs = appleTopSongs,
                onPlay = { track -> suggestionsViewModel.playTrack(track, playerConnection) },
              )
            }
          }
      }

      if (isLoading && chartsPage != null) {
        CircularProgressIndicator(modifier = Modifier.align(Alignment.TopCenter).padding(8.dp))
      }
    }
  }

  if (showCountryPicker) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = { showCountryPicker = false }, sheetState = sheetState) {
      Text(
        text = stringResource(R.string.charts_country),
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
      )
      LazyColumn(modifier = Modifier.fillMaxWidth()) {
        items(items = countries, key = { it.code }) { option ->
          val selected = option.code == country
          Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier =
              Modifier.fillMaxWidth()
                .clickable {
                  showCountryPicker = false
                  viewModel.selectCountry(option.code)
                }
                .padding(horizontal = 24.dp, vertical = 14.dp),
          ) {
            Text(
              text = option.name,
              style = MaterialTheme.typography.bodyLarge,
              fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
              color =
                if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface,
              modifier = Modifier.weight(1f),
            )
            if (selected) {
              Icon(
                painter = painterResource(R.drawable.check),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
              )
            }
          }
        }
      }
    }
  }
}

private fun androidx.compose.foundation.lazy.LazyListScope.appleTopSongsSection(
  songs: List<SuggestionTrack>,
  onPlay: (SuggestionTrack) -> Unit,
) {
  item(key = "apple_title") { NavigationTitle(title = "Apple Music Top 100") }
  item(key = "apple_rows") {
    LazyHorizontalGrid(
      rows = GridCells.Fixed(4),
      contentPadding = PaddingValues(horizontal = 8.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      modifier = Modifier.fillMaxWidth().height(ListItemHeight * 4),
    ) {
      items(items = songs, key = { it.rank }) { track -> AppleTrackRow(track, onPlay) }
    }
  }
}

@Composable
private fun AppleTrackRow(track: SuggestionTrack, onPlay: (SuggestionTrack) -> Unit) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier =
      Modifier.width(300.dp)
        .height(ListItemHeight)
        .clip(RoundedCornerShape(8.dp))
        .clickable { onPlay(track) }
        .padding(horizontal = 8.dp),
  ) {
    Text(
      text = track.rank.toString(),
      style = MaterialTheme.typography.titleMedium,
      fontWeight = FontWeight.Bold,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.width(32.dp),
    )
    AsyncImage(
      model = track.thumbnailUrl?.replace("1400x1400", "226x226"),
      contentDescription = null,
      contentScale = ContentScale.Crop,
      modifier = Modifier.size(ListItemHeight - 16.dp).clip(RoundedCornerShape(6.dp)),
    )
    Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
      Text(
        text = track.title,
        style = MaterialTheme.typography.bodyLarge,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        text = track.artist,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}
