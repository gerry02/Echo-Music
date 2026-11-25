package echo.music.iad1tya.ui.screens.search

import androidx.compose.ui.graphics.Color
import com.music.innertube.pages.MoodAndGenres

data class DiscoverCategory(
  val title: String,
  val searchQuery: String,
  val startColor: Color,
  val endColor: Color,
  val imageUrl: String?,
  /** YouTube Music `FEmusic_moods_and_genres_category` params; the same in every language. */
  val ytParams: String? = null,
  val browseId: String? = null,
)

/**
 * Artwork for the YouTube Music moods and genres we have a picture for. Also the offline fallback
 * grid (searching by name) when the YouTube list cannot be loaded.
 */
private val CURATED_CATEGORIES =
  listOf(
    DiscoverCategory(
      title = "Apple Music Live",
      searchQuery = "Live Concert Music",
      startColor = Color(0xFF1E2124),
      endColor = Color(0xFF0F1012),
      imageUrl =
        "https://images.unsplash.com/photo-1501386761578-eac5c94b800a?w=450&auto=format&fit=crop&q=80",
    ),
    DiscoverCategory(
      title = "Hits",
      searchQuery = "Top Hits",
      startColor = Color(0xFFF59E0B),
      endColor = Color(0xFFD97706),
      imageUrl =
        "https://images.unsplash.com/photo-1516450360452-9312f5e86fc7?w=450&auto=format&fit=crop&q=80",
    ),
    DiscoverCategory(
      title = "Alternative",
      searchQuery = "Alternative Rock",
      startColor = Color(0xFF84CC16),
      endColor = Color(0xFF65A30D),
      imageUrl =
        "https://images.unsplash.com/photo-1465847899084-d164df4dedc6?w=450&auto=format&fit=crop&q=80",
    ),
    DiscoverCategory(
      title = "Pop",
      searchQuery = "Pop Hits",
      startColor = Color(0xFFEC4899),
      endColor = Color(0xFFDB2777),
      imageUrl =
        "https://images.unsplash.com/photo-1529626455594-4ff0802cfb7e?w=450&auto=format&fit=crop&q=80",
      ytParams = "ggMPOg1uX1BmNzc2V2p0YXJ5",
    ),
    DiscoverCategory(
      title = "Hip-Hop",
      searchQuery = "Hip Hop",
      startColor = Color(0xFF3B82F6),
      endColor = Color(0xFF1D4ED8),
      imageUrl =
        "https://images.unsplash.com/photo-1508700115892-45ecd05ae2ad?w=450&auto=format&fit=crop&q=80",
      ytParams = "ggMPOg1uX2E4aVJuU05GOVQ0",
    ),
    DiscoverCategory(
      title = "Country",
      searchQuery = "Country Music",
      startColor = Color(0xFFD97706),
      endColor = Color(0xFFB45309),
      imageUrl =
        "https://images.unsplash.com/photo-1511671782779-c97d3d27a1d4?w=450&auto=format&fit=crop&q=80",
      ytParams = "ggMPOg1uX1RXcFlyZEpRb1d3",
    ),
    DiscoverCategory(
      title = "Dance",
      searchQuery = "Dance Electronic",
      startColor = Color(0xFF10B981),
      endColor = Color(0xFF047857),
      imageUrl =
        "https://images.unsplash.com/photo-1514525253161-7a46d19cd819?w=450&auto=format&fit=crop&q=80",
      ytParams = "ggMPOg1uX1NPTld3SDN3WGs4",
    ),
    DiscoverCategory(
      title = "Rock",
      searchQuery = "Classic Rock",
      startColor = Color(0xFFEF4444),
      endColor = Color(0xFFB91C1C),
      imageUrl =
        "https://images.unsplash.com/photo-1498038432885-c6f3f1b912ee?w=450&auto=format&fit=crop&q=80",
      ytParams = "ggMPOg1uXzJKTm5jUEZ5Uzlu",
    ),
    DiscoverCategory(
      title = "Latin",
      searchQuery = "Latin Music",
      startColor = Color(0xFFF43F5E),
      endColor = Color(0xFFBE123C),
      imageUrl =
        "https://images.unsplash.com/photo-1518609878373-06d740f60d8b?w=450&auto=format&fit=crop&q=80",
      ytParams = "ggMPOg1uX29wWTRjMHV1dWN5",
    ),
    DiscoverCategory(
      title = "R&B",
      searchQuery = "R&B Soul",
      startColor = Color(0xFF8B5CF6),
      endColor = Color(0xFF6D28D9),
      imageUrl =
        "https://images.unsplash.com/photo-1534528741775-53994a69daeb?w=450&auto=format&fit=crop&q=80",
      ytParams = "ggMPOg1uX2JxQ2hxc2J5UFhR",
    ),
    DiscoverCategory(
      title = "Indie",
      searchQuery = "Indie Pop Folk",
      startColor = Color(0xFF06B6D4),
      endColor = Color(0xFF0E7490),
      imageUrl =
        "https://images.unsplash.com/photo-1470225620780-dba8ba36b745?w=450&auto=format&fit=crop&q=80",
      ytParams = "ggMPOg1uX21NWWpBbU01SDgy",
    ),
    DiscoverCategory(
      title = "Workout",
      searchQuery = "Workout Motivation Music",
      startColor = Color(0xFFF97316),
      endColor = Color(0xFFC2410C),
      imageUrl =
        "https://images.unsplash.com/photo-1517838277536-f5f99be501cd?w=450&auto=format&fit=crop&q=80",
      ytParams = "ggMPOg1uXzIxYkNac21YZ2Z0",
    ),
    DiscoverCategory(
      title = "Chill",
      searchQuery = "Chill Vibes",
      startColor = Color(0xFF6366F1),
      endColor = Color(0xFF4338CA),
      imageUrl =
        "https://images.unsplash.com/photo-1518495973542-4542c06a5843?w=450&auto=format&fit=crop&q=80",
      ytParams = "ggMPOg1uX1JOQWZFeDByc2Jm",
    ),
    DiscoverCategory(
      title = "Party",
      searchQuery = "Party Hits",
      startColor = Color(0xFFA855F7),
      endColor = Color(0xFF7E22CE),
      imageUrl =
        "https://images.unsplash.com/photo-1492684223066-81342ee5ff30?w=450&auto=format&fit=crop&q=80",
      ytParams = "ggMPOg1uX2w1aW1CRDFTSUNo",
    ),
    DiscoverCategory(
      title = "Focus",
      searchQuery = "Deep Focus Study",
      startColor = Color(0xFF14B8A6),
      endColor = Color(0xFF0F766E),
      imageUrl =
        "https://images.unsplash.com/photo-1434030216411-0b793f4b4173?w=450&auto=format&fit=crop&q=80",
      ytParams = "ggMPOg1uX0NvNGNhWThMYWRh",
    ),
    DiscoverCategory(
      title = "Electronic",
      searchQuery = "Electronic EDM",
      startColor = Color(0xFF0284C7),
      endColor = Color(0xFF0369A1),
      imageUrl =
        "https://images.unsplash.com/photo-1511671782779-c97d3d27a1d4?w=450&auto=format&fit=crop&q=80",
    ),
    DiscoverCategory(
      title = "Classical",
      searchQuery = "Classical Essentials",
      startColor = Color(0xFFCA8A04),
      endColor = Color(0xFFA16207),
      imageUrl =
        "https://images.unsplash.com/photo-1507838153414-b4b713384a76?w=450&auto=format&fit=crop&q=80",
      ytParams = "ggMPOg1uX1N4VmduTmdUR3dm",
    ),
    DiscoverCategory(
      title = "Jazz",
      searchQuery = "Jazz Classics",
      startColor = Color(0xFF4338CA),
      endColor = Color(0xFF312E81),
      imageUrl =
        "https://images.unsplash.com/photo-1511192336575-5a79af67a629?w=450&auto=format&fit=crop&q=80",
      ytParams = "ggMPOg1uX3lPcDFRaE9wM1BS",
    ),
    DiscoverCategory(
      title = "K-Pop",
      searchQuery = "K-Pop Hits",
      startColor = Color(0xFFD946EF),
      endColor = Color(0xFFA21CAF),
      imageUrl =
        "https://images.unsplash.com/photo-1516280440614-37939bbacd81?w=450&auto=format&fit=crop&q=80",
      ytParams = "ggMPOg1uX0JrbjBDOFFPSzJW",
    ),
    DiscoverCategory(
      title = "Metal",
      searchQuery = "Heavy Metal",
      startColor = Color(0xFF991B1B),
      endColor = Color(0xFF7F1D1D),
      imageUrl =
        "https://images.unsplash.com/photo-1526478806334-5fd488fcaabc?w=450&auto=format&fit=crop&q=80",
      ytParams = "ggMPOg1uXzdlSXhKZ0hMV1Z4",
    ),
    DiscoverCategory(
      title = "Soul",
      searchQuery = "Soul Classics",
      startColor = Color(0xFFC2410C),
      endColor = Color(0xFF9A3412),
      imageUrl =
        "https://images.unsplash.com/photo-1516450360452-9312f5e86fc7?w=450&auto=format&fit=crop&q=80",
    ),
    DiscoverCategory(
      title = "Bollywood",
      searchQuery = "Bollywood Hits",
      startColor = Color(0xFFF59E0B),
      endColor = Color(0xFFB45309),
      imageUrl =
        "https://images.unsplash.com/photo-1533174072545-7a4b6ad7a6c3?w=450&auto=format&fit=crop&q=80",
      ytParams = "ggMPOg1uX0JrcUJGUHhxaTFV",
    ),
    DiscoverCategory(
      title = "Sleep",
      searchQuery = "Sleep Relaxing Music",
      startColor = Color(0xFF334155),
      endColor = Color(0xFF1E293B),
      imageUrl =
        "https://images.unsplash.com/photo-1507525428034-b723cf961d3e?w=450&auto=format&fit=crop&q=80",
      ytParams = "ggMPOg1uX1MxaFQ3Z0JMZkN4",
    ),
    DiscoverCategory(
      title = "Acoustic",
      searchQuery = "Acoustic Chill",
      startColor = Color(0xFF78350F),
      endColor = Color(0xFF451A03),
      imageUrl =
        "https://images.unsplash.com/photo-1510915361894-db8b60106cb1?w=450&auto=format&fit=crop&q=80",
      ytParams = "ggMPOg1uXzBTRFBmQ3N4b0R6",
    ),
  )

data class DiscoverSection(val title: String?, val categories: List<DiscoverCategory>)

/**
 * Discover grid from YouTube Music's own (localized) moods and genres, each opening its real
 * category page. Categories we have artwork for keep it; the others get YouTube's stripe colour.
 * Without a YouTube list (offline, error) the curated grid is shown and searches by name.
 */
fun buildDiscoverSections(
  moodAndGenres: List<MoodAndGenres>?,
  curated: List<DiscoverCategory> = CURATED_CATEGORIES,
): List<DiscoverSection> {
  val sections =
    moodAndGenres.orEmpty().mapNotNull { group ->
      val categories =
        group.items.mapNotNull { item ->
          val params = item.endpoint.params ?: return@mapNotNull null
          val art = curated.firstOrNull { it.ytParams == params }
          val stripe = Color(item.stripeColor.toInt())
          DiscoverCategory(
            title = item.title,
            searchQuery = art?.searchQuery ?: item.title,
            startColor = art?.startColor ?: stripe.darken(0.35f),
            endColor = art?.endColor ?: stripe.darken(0.6f),
            imageUrl = art?.imageUrl,
            ytParams = params,
            browseId = item.endpoint.browseId,
          )
        }
      if (categories.isEmpty()) null else DiscoverSection(group.title, categories)
    }
  return sections.ifEmpty { listOf(DiscoverSection(null, curated)) }
}

/** Navigation route a category opens: its YouTube Music page, or a search when it has none. */
fun DiscoverCategory.route(): String =
  if (ytParams != null) {
    "youtube_browse/${browseId ?: MOODS_AND_GENRES_CATEGORY}?params=$ytParams"
  } else {
    "search/${java.net.URLEncoder.encode(searchQuery, "UTF-8")}"
  }

private const val MOODS_AND_GENRES_CATEGORY = "FEmusic_moods_and_genres_category"

private fun Color.darken(fraction: Float): Color =
  Color(
    red = red * (1f - fraction),
    green = green * (1f - fraction),
    blue = blue * (1f - fraction),
    alpha = 1f,
  )
