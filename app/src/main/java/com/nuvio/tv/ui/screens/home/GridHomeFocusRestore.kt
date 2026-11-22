package com.nuvio.tv.ui.screens.home

/** Where a grid card sits: the section header it follows and its place among that section's cards. */
data class GridFocusSlot(val sectionKey: String, val index: Int)

internal fun gridFocusSlots(items: List<Pair<GridItem, String>>): Map<String, GridFocusSlot> {
    val slots = HashMap<String, GridFocusSlot>()
    var section: String? = null
    var index = 0
    items.forEach { (item, key) ->
        when (item) {
            is GridItem.SectionDivider, is GridItem.CollectionHeader -> {
                section = key
                index = 0
            }
            is GridItem.Content -> section?.let { slots[key] = GridFocusSlot(it, index++) }
            else -> Unit
        }
    }
    return slots
}

/**
 * Cards that can take focus when the saved one has left its section: the card now in its place first,
 * then the ones before it. Empty when the section itself is gone.
 */
internal fun gridReplacementFocusKeys(items: List<Pair<GridItem, String>>, slot: GridFocusSlot): List<String> {
    val sectionKeys = gridFocusSlots(items)
        .filterValues { it.sectionKey == slot.sectionKey }
        .entries
        .sortedBy { it.value.index }
        .map { it.key }
    if (sectionKeys.isEmpty()) return emptyList()
    val start = slot.index.coerceIn(0, sectionKeys.lastIndex)
    return (start downTo 0).map { sectionKeys[it] }
}

internal fun gridItemsWithStableKeys(gridItems: List<GridItem>): List<Pair<GridItem, String>> {
    val occurrences = mutableMapOf<String, Int>()
    return gridItems.map { item ->
        val key = when (item) {
            is GridItem.Hero -> "hero"
            is GridItem.SectionDivider -> "divider_${item.addonBaseUrl.hashCode()}_${item.catalogId}_${item.addonId}_${item.type}"
            is GridItem.Content -> {
                val base = "content_${item.addonBaseUrl.hashCode()}_${item.catalogId}_${item.item.id}"
                val count = occurrences.getOrDefault(base, 0)
                occurrences[base] = count + 1
                "${base}_$count"
            }
            is GridItem.SeeAll -> "see_all_${item.addonBaseUrl.hashCode()}_${item.catalogId}_${item.addonId}_${item.type}"
            is GridItem.LiveRow -> item.key // Live TV fork
            is GridItem.CollectionHeader -> "col_header_${item.collectionId}"
            is GridItem.CollectionFolder -> "col_folder_${item.collectionId}_${item.folder.id}"
        }
        item to key
    }
}
