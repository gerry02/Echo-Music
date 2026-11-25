package com.music.innertube.pages

import com.music.echo.metadata.cleaner.ApiText
import com.music.innertube.models.*
import com.music.innertube.models.response.BrowseResponse
import java.net.URLDecoder
import java.util.Base64

data class ChartsPage(
  val sections: List<ChartSection>,
  val continuation: String?,
  /** Countries YouTube Music offers charts for, in the order it lists them. */
  val countries: List<Country> = emptyList(),
  /** Code of the country these charts are for ("ZZ" is the whole world). */
  val selectedCountry: String? = null,
) {
  data class ChartSection(val title: String, val items: List<YTItem>, val chartType: ChartType)

  data class Country(val code: String, val name: String)

  enum class ChartType {
    TRENDING,
    TOP,
    GENRE,
    NEW_RELEASES,
    ARTISTS,
  }

  companion object {
    const val WORLDWIDE = "ZZ"

    private val COUNTRY_CODE_REGEX = Regex("""country_menu_\d*([A-Z]{2})""")

    fun fromBrowseResponse(response: BrowseResponse): ChartsPage {
      val contents =
        response.contents
          ?.singleColumnBrowseResultsRenderer
          ?.tabs
          ?.firstOrNull()
          ?.tabRenderer
          ?.content
          ?.sectionListRenderer
          ?.contents
          .orEmpty()

      val countryButton =
        contents
          .flatMap { it.musicShelfRenderer?.subheaders.orEmpty() }
          .flatMap { it.musicSideAlignedItemRenderer?.startItems.orEmpty() }
          .firstNotNullOfOrNull { it.musicSortFilterButtonRenderer }

      val countries =
        countryButton
          ?.menu
          ?.musicMultiSelectMenuRenderer
          ?.options
          .orEmpty()
          .mapNotNull { it.musicMultiSelectMenuItemRenderer }
          .mapNotNull { option ->
            val code = countryCode(option.formItemEntityKey) ?: return@mapNotNull null
            val name = option.title?.runs?.firstOrNull()?.text ?: return@mapNotNull null
            Country(code = code, name = name)
          }
          // The request locale's country is listed first and again in alphabetical order.
          .distinctBy { it.code }

      // Every option carries the same check icon; the button label names the current country.
      val buttonLabel = countryButton?.title?.runs?.firstOrNull()?.text
      val selectedCountry = countries.firstOrNull { it.name == buttonLabel }?.code

      var carouselIndex = 0
      val sections =
        contents.mapNotNull { content ->
          val shelf = content.musicCarouselShelfRenderer ?: return@mapNotNull null
          val title =
            shelf.header?.musicCarouselShelfBasicHeaderRenderer?.title?.runs?.firstOrNull()?.text
              ?: return@mapNotNull null
          val items =
            shelf.contents.mapNotNull { item ->
              item.musicTwoRowItemRenderer?.let(RelatedPage.Companion::fromMusicTwoRowItemRenderer)
                ?: item.musicResponsiveListItemRenderer?.let(::fromResponsiveItem)
            }
          if (items.isEmpty()) return@mapNotNull null
          val type =
            when {
              items.all { it is ArtistItem } -> ChartType.ARTISTS
              carouselIndex++ == 0 -> ChartType.TOP
              else -> ChartType.GENRE
            }
          ChartSection(title = title, items = items, chartType = type)
        }

      return ChartsPage(
        sections = sections,
        continuation =
          response.continuationContents?.sectionListContinuation?.continuations?.getContinuation(),
        countries = countries,
        selectedCountry = selectedCountry,
      )
    }

    /** The entity key is base64 of a proto naming the menu entry, e.g. "...country_menu_316IT". */
    internal fun countryCode(formItemEntityKey: String?): String? {
      if (formItemEntityKey.isNullOrBlank()) return null
      return runCatching {
          val raw = URLDecoder.decode(formItemEntityKey, "UTF-8")
          val decoded = String(Base64.getDecoder().decode(raw), Charsets.ISO_8859_1)
          COUNTRY_CODE_REGEX.find(decoded)?.groupValues?.get(1)
        }
        .getOrNull()
    }

    private fun fromResponsiveItem(renderer: MusicResponsiveListItemRenderer): YTItem? {
      if (renderer.isArtist) {
        val title =
          renderer.flexColumns
            .firstOrNull()
            ?.musicResponsiveListItemFlexColumnRenderer
            ?.text
            ?.runs
            ?.firstOrNull()
            ?.text ?: return null
        return ArtistItem(
          id = renderer.navigationEndpoint?.browseEndpoint?.browseId ?: return null,
          title = ApiText.clean(title),
          thumbnail = renderer.thumbnail?.musicThumbnailRenderer?.getThumbnailUrl(),
          shuffleEndpoint = null,
          radioEndpoint = null,
        )
      }
      if (renderer.playlistItemData?.videoId != null) {
        return RelatedPage.fromMusicResponsiveListItemRenderer(renderer)
      }
      return null
    }
  }
}
