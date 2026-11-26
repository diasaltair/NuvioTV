package com.nuvio.tv.testing

import com.nuvio.tv.livetv.home.HomeLiveRows
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf

/** Live TV fork: Home and catalog order take the Live TV/Sports rows; tests get none. */
fun testHomeLiveRows(): HomeLiveRows = mockk(relaxed = true) {
    every { rows } returns flowOf(emptyList())
    every { shownRows } returns flowOf(emptyList())
    every { names } returns MutableStateFlow(emptyMap())
    every { emptyRowIds } returns MutableStateFlow(emptySet())
    every { vodRows } returns MutableStateFlow(emptyMap())
}
