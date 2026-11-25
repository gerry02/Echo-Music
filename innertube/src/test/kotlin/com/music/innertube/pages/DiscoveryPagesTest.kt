package com.music.innertube.pages

import com.music.innertube.models.ArtistItem
import com.music.innertube.models.PlaylistItem
import com.music.innertube.models.body.BrowseBody
import com.music.innertube.models.response.BrowseResponse
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fixtures are trimmed live `youtubei/v1/browse` responses (WEB_REMIX, hl=it, gl=IT), saved on
 * 2026-11-25: `FEmusic_charts` without formData, with `selectedValues=["US"]`, and
 * `FEmusic_moods_and_genres`, and the "Allenamento" (workout) `FEmusic_moods_and_genres_category`
 * page.
 */
class DiscoveryPagesTest {

  @OptIn(ExperimentalSerializationApi::class)
  private val json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
  }

  private fun load(name: String): BrowseResponse {
    val text =
      javaClass.classLoader!!.getResource("discovery/$name")!!.readText(Charsets.UTF_8)
    return json.decodeFromString(BrowseResponse.serializer(), text)
  }

  @Test
  fun chartsItalyListsCountriesAndPlayableCharts() {
    val page = ChartsPage.fromBrowseResponse(load("charts_it.json"))

    assertEquals("IT", page.selectedCountry)
    val codes = page.countries.map { it.code }
    assertEquals("Italy is listed once even though YouTube repeats it", 1, codes.count { it == "IT" })
    assertTrue(ChartsPage.WORLDWIDE in codes)
    assertTrue("US" in codes)
    assertTrue(page.countries.size > 60)
    assertEquals("Italia", page.countries.first { it.code == "IT" }.name)

    val top = page.sections.first()
    assertEquals(ChartsPage.ChartType.TOP, top.chartType)
    val playlists = top.items.filterIsInstance<PlaylistItem>()
    assertTrue(playlists.isNotEmpty())
    // Chart playlists open as online playlists: the "VL" browse prefix must be gone.
    assertTrue(playlists.all { it.id.startsWith("PL") || it.id.startsWith("OL") })

    val artists = page.sections.first { it.chartType == ChartsPage.ChartType.ARTISTS }
    assertTrue(artists.items.all { it is ArtistItem })
    assertTrue(artists.items.all { it.id.startsWith("UC") })
  }

  @Test
  fun chartsForAnotherCountryFollowFormData() {
    val page = ChartsPage.fromBrowseResponse(load("charts_us.json"))

    assertEquals("US", page.selectedCountry)
    assertTrue(page.sections.first().items.isNotEmpty())
    assertTrue(page.sections.any { it.chartType == ChartsPage.ChartType.GENRE })
    // Podcast shelves have no playable music items and are dropped.
    assertFalse(page.sections.any { section -> section.items.isEmpty() })
  }

  @Test
  fun countryCodeIsReadFromEntityKey() {
    assertEquals(
      "IT",
      ChartsPage.countryCode("EidleHBsb3JlX2NoYXJ0c19jb3VudHJ5X21lbnVfMzE2NzY2NTY3SVQgkQEoAQ%3D%3D"),
    )
    assertEquals(null, ChartsPage.countryCode("not base64 at all"))
    assertEquals(null, ChartsPage.countryCode(null))
  }

  @Test
  fun formDataIsSentOnlyWhenGiven() {
    val withCountry = json.encodeToString(BrowseBody.FormData.serializer(), BrowseBody.FormData(listOf("IT")))
    assertEquals("""{"selectedValues":["IT"]}""", withCountry)
  }

  @Test
  fun moodsAndGenresAreLocalizedAndBrowsable() {
    val sections =
      load("moods_it.json")
        .contents
        ?.singleColumnBrowseResultsRenderer
        ?.tabs
        ?.firstOrNull()
        ?.tabRenderer
        ?.content
        ?.sectionListRenderer
        ?.contents
        .orEmpty()
        .mapNotNull(MoodAndGenres.Companion::fromSectionListRendererContent)

    assertEquals(2, sections.size)
    val items = sections.flatMap { it.items }
    assertNotNull(items.firstOrNull { it.title == "Allenamento" })
    assertTrue(items.size > 30)
    assertTrue(
      items.all {
        it.endpoint.browseId == "FEmusic_moods_and_genres_category" && !it.endpoint.params.isNullOrBlank()
      },
    )
  }

  @Test
  fun moodPageGivesPlaylistsAndAnArtworkForItsCard() {
    val result = BrowseResult.fromBrowseResponse(load("mood_workout_it.json"))

    assertEquals("Allenamento", result.title)
    assertEquals(5, result.items.size)
    assertEquals("In palestra", result.items.first().title)
    assertTrue(result.items.first().items.all { it is PlaylistItem })
    // The moods list itself carries only a colour: the card borrows the first playlist cover.
    val artwork = result.artworkUrl
    assertNotNull(artwork)
    assertEquals(result.items.first().items.first().thumbnail, artwork)
    assertTrue(artwork!!.startsWith("https://"))
  }

  @Test
  fun artworkIsNullWhenNoItemHasOne() {
    assertEquals(null, BrowseResult(title = null, items = emptyList()).artworkUrl)
  }

  @Test
  fun everyChartItemHasAThumbnail() {
    for (name in listOf("charts_it.json", "charts_us.json")) {
      val page = ChartsPage.fromBrowseResponse(load(name))
      for (section in page.sections) {
        assertTrue(
          "$name / ${section.title}",
          section.items.all { !it.thumbnail.isNullOrBlank() },
        )
      }
    }
  }
}
