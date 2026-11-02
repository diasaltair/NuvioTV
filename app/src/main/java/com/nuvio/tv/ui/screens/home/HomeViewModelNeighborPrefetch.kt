package com.nuvio.tv.ui.screens.home

import androidx.lifecycle.viewModelScope
import com.nuvio.tv.domain.model.MetaPreview
import kotlinx.coroutines.launch

/**
 * Nuvio RS: enriches Netflix-row titles before focus reaches them, so their TMDB backdrop and
 * logo are in place and the wide tile opens on its final art instead of swapping it later.
 *
 * Two kinds of request share one queue, run one at a time through the adjacent-prefetch path:
 * - neighbours of the focused title ([neighbors] true) go first and replace the previous
 *   neighbours, since focus has moved on from them;
 * - landing titles of the other visible rows (where focus lands when moving up or down) go
 *   last and stay queued until done.
 * Everything runs on the main thread, like the rest of the prefetch bookkeeping.
 */
internal fun HomeViewModel.queueNetflixEnrichment(items: List<MetaPreview>, neighbors: Boolean) {
    if (neighbors) {
        netflixEnrichQueue.removeAll { it.second }
        items.asReversed().forEach { netflixEnrichQueue.addFirst(it to true) }
    } else {
        items.forEach { item ->
            if (netflixEnrichQueue.none { it.first.id == item.id }) netflixEnrichQueue.addLast(item to false)
        }
    }
    if (netflixEnrichJob?.isActive == true) return
    netflixEnrichJob = viewModelScope.launch {
        while (true) {
            val item = netflixEnrichQueue.removeFirstOrNull()?.first ?: break
            if (item.id in prefetchedTmdbIds || item.id in prefetchedExternalMetaIds) continue
            preloadAdjacentItemPipeline(item)
            adjacentItemPrefetchJob?.join()
        }
    }
}
