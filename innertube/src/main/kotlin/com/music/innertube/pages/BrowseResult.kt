package com.music.innertube.pages

import com.music.innertube.models.GridRenderer
import com.music.innertube.models.MusicCarouselShelfRenderer
import com.music.innertube.models.YTItem
import com.music.innertube.models.filterBlockedArtists
import com.music.innertube.models.filterDislikedSongs
import com.music.innertube.models.filterExplicit
import com.music.innertube.models.filterVideoSongs
import com.music.innertube.models.filterYoutubeShorts
import com.music.innertube.models.response.BrowseResponse

data class BrowseResult(
  val title: String?,
  val items: List<Item>,
) {
  data class Item(
    val title: String?,
    val items: List<YTItem>,
  )

  /** Artwork of the first item that has one, e.g. the first playlist of a mood page. */
  val artworkUrl: String?
    get() =
      items.firstNotNullOfOrNull { section ->
        section.items.firstNotNullOfOrNull { it.thumbnail?.takeIf(String::isNotBlank) }
      }

  companion object {
    fun fromBrowseResponse(response: BrowseResponse): BrowseResult {
      return BrowseResult(
        title = response.header?.musicHeaderRenderer?.title?.runs?.firstOrNull()?.text,
        items =
          response.contents
            ?.singleColumnBrowseResultsRenderer
            ?.tabs
            ?.firstOrNull()
            ?.tabRenderer
            ?.content
            ?.sectionListRenderer
            ?.contents
            ?.mapNotNull { content ->
              when {
                content.gridRenderer != null -> {
                  BrowseResult.Item(
                    title =
                      content.gridRenderer.header
                        ?.gridHeaderRenderer
                        ?.title
                        ?.runs
                        ?.firstOrNull()
                        ?.text,
                    items =
                      content.gridRenderer.items
                        .mapNotNull(GridRenderer.Item::musicTwoRowItemRenderer)
                        .mapNotNull(RelatedPage.Companion::fromMusicTwoRowItemRenderer),
                  )
                }
                content.musicCarouselShelfRenderer != null -> {
                  BrowseResult.Item(
                    title =
                      content.musicCarouselShelfRenderer.header
                        ?.musicCarouselShelfBasicHeaderRenderer
                        ?.title
                        ?.runs
                        ?.firstOrNull()
                        ?.text,
                    items =
                      content.musicCarouselShelfRenderer.contents
                        .mapNotNull(MusicCarouselShelfRenderer.Content::musicTwoRowItemRenderer)
                        .mapNotNull(RelatedPage.Companion::fromMusicTwoRowItemRenderer),
                  )
                }
                else -> null
              }
            }
            .orEmpty(),
      )
    }
  }

  fun filterBlockedArtists() =
    copy(
      items =
        items.mapNotNull {
          it.copy(
            items =
              it.items.filterBlockedArtists().ifEmpty {
                return@mapNotNull null
              },
          )
        },
    )

  fun filterDislikedSongs() =
    copy(
      items =
        items.mapNotNull {
          it.copy(
            items =
              it.items.filterDislikedSongs().ifEmpty {
                return@mapNotNull null
              },
          )
        },
    )

  fun filterExplicit(enabled: Boolean = true) =
    if (enabled) {
      copy(
        items =
          items.mapNotNull {
            it.copy(
              items =
                it.items.filterExplicit().ifEmpty {
                  return@mapNotNull null
                },
            )
          },
      )
    } else {
      this
    }

  fun filterVideoSongs(disableVideos: Boolean = false) =
    if (disableVideos) {
      copy(
        items =
          items.mapNotNull {
            it.copy(
              items =
                it.items.filterVideoSongs(true).ifEmpty {
                  return@mapNotNull null
                },
            )
          },
      )
    } else {
      this
    }

  fun filterYoutubeShorts(enabled: Boolean = false) =
    if (enabled) {
      copy(
        items =
          items.mapNotNull {
            it.copy(
              items =
                it.items.filterYoutubeShorts(true).ifEmpty {
                  return@mapNotNull null
                },
            )
          },
      )
    } else {
      this
    }
}
