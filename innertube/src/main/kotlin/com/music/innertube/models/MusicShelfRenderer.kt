package com.music.innertube.models

import kotlinx.serialization.Serializable

@Serializable
data class MusicShelfRenderer(
  val title: Runs?,
  val contents: List<Content>?,
  val continuations: List<Continuation>?,
  val bottomEndpoint: NavigationEndpoint?,
  val moreContentButton: Button?,
  val subheaders: List<Subheader>? = null,
) {
  /** Filter row above a shelf, e.g. the country picker on the charts page. */
  @Serializable
  data class Subheader(
    val musicSideAlignedItemRenderer: MusicSideAlignedItemRenderer? = null,
  ) {
    @Serializable
    data class MusicSideAlignedItemRenderer(
      val startItems: List<Item>? = null,
    ) {
      @Serializable
      data class Item(
        val musicSortFilterButtonRenderer: MusicSortFilterButtonRenderer? = null,
      )
    }

    @Serializable
    data class MusicSortFilterButtonRenderer(
      val title: Runs? = null,
      val menu: Menu? = null,
    ) {
      @Serializable
      data class Menu(
        val musicMultiSelectMenuRenderer: MusicMultiSelectMenuRenderer? = null,
      )
    }

    @Serializable
    data class MusicMultiSelectMenuRenderer(
      val options: List<Option>? = null,
    ) {
      @Serializable
      data class Option(
        val musicMultiSelectMenuItemRenderer: MusicMultiSelectMenuItemRenderer? = null,
      )

      @Serializable
      data class MusicMultiSelectMenuItemRenderer(
        val title: Runs? = null,
        val formItemEntityKey: String? = null,
      )
    }
  }

  @Serializable
  data class Content(
    val musicResponsiveListItemRenderer: MusicResponsiveListItemRenderer?,
    val continuationItemRenderer: ContinuationItemRenderer?,
    val musicMultiRowListItemRenderer: MusicMultiRowListItemRenderer? = null, // podcast episode
  )
}

fun List<MusicShelfRenderer.Content>.getItems(): List<MusicResponsiveListItemRenderer> =
  mapNotNull {
    it.musicResponsiveListItemRenderer
  }

fun List<MusicShelfRenderer.Content>.getContinuation(): String? = firstOrNull {
  it.continuationItemRenderer != null
}
  ?.continuationItemRenderer
  ?.continuationEndpoint
  ?.continuationCommand
  ?.token
