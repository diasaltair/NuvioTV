package com.nuvio.tv.ui.screens.home

import androidx.lifecycle.viewModelScope
import com.nuvio.tv.domain.model.MetaPreview
import kotlinx.coroutines.launch

/**
 * Nuvio RS: enriches the titles around a Netflix row's focus, nearest first, one at a time
 * through the adjacent-prefetch path. Their TMDB backdrop and logo are then in place before
 * focus arrives, so the wide tile opens on its final art instead of swapping it about a second
 * later. A new focus cancels the walk; a fetch already running finishes on its own.
 */
internal fun HomeViewModel.preloadNeighborItemsPipeline(items: List<MetaPreview>) {
    neighborItemsPrefetchJob?.cancel()
    neighborItemsPrefetchJob = viewModelScope.launch {
        for (item in items) {
            if (item.id in prefetchedTmdbIds || item.id in prefetchedExternalMetaIds) continue
            preloadAdjacentItemPipeline(item)
            adjacentItemPrefetchJob?.join()
        }
    }
}
