package echo.music.iad1tya.ui.screens.search

import com.music.innertube.models.BrowseEndpoint
import com.music.innertube.pages.MoodAndGenres
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoverCategoriesTest {

  private fun item(title: String, params: String) =
    MoodAndGenres.Item(
      title = title,
      stripeColor = 0xffff8d40,
      endpoint = BrowseEndpoint("FEmusic_moods_and_genres_category", params),
    )

  @Test
  fun italianCategoriesOpenTheirYouTubePageAndKeepArtwork() {
    val sections =
      buildDiscoverSections(
        listOf(
          MoodAndGenres(
            "Mood e momenti",
            listOf(
              // Workout, which the old title match missed in Italian.
              item("Allenamento", "ggMPOg1uXzIxYkNac21YZ2Z0"),
              item("Viaggi", "ggMPOg1uX044Z2o5WERLckpU"),
            ),
          ),
        ),
      )

    assertEquals(1, sections.size)
    assertEquals("Mood e momenti", sections[0].title)
    val (workout, commute) = sections[0].categories
    assertEquals("Allenamento", workout.title)
    assertNotNull("curated artwork is reused by params", workout.imageUrl)
    assertEquals(
      "youtube_browse/FEmusic_moods_and_genres_category?params=ggMPOg1uXzIxYkNac21YZ2Z0",
      workout.route(),
    )
    assertNull("no artwork for categories we have no picture for", commute.imageUrl)
    assertTrue(commute.route().startsWith("youtube_browse/"))
  }

  @Test
  fun withoutYouTubeListTheCuratedGridSearchesByName() {
    for (input in listOf(null, emptyList<MoodAndGenres>())) {
      val sections = buildDiscoverSections(input)
      assertEquals(1, sections.size)
      assertNull(sections[0].title)
      val hits = sections[0].categories.first { it.title == "Hits" }
      assertEquals("search/Top+Hits", hits.route())
      // Curated cards that map to a YouTube category still open its page.
      val workout = sections[0].categories.first { it.title == "Workout" }
      assertTrue(workout.route().startsWith("youtube_browse/"))
    }
  }
}
