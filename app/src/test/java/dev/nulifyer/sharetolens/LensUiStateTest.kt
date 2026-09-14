package dev.nulifyer.sharetolens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LensUiStateTest {
    @Test
    fun readyHasNoSearchOrigin() {
        assertNull(LensUiState.Ready.originOrNull())
    }

    @Test
    fun activeStatesKeepTheirEntryOrigin() {
        assertEquals(
            SearchOrigin.Share,
            LensUiState.Preparing(SearchOrigin.Share).originOrNull(),
        )
        assertEquals(
            SearchOrigin.Launcher,
            LensUiState.Results(SearchOrigin.Launcher, "https://lens.google.com", emptyList())
                .originOrNull(),
        )
    }

    @Test
    fun rootBackClosesReadyAndSharedFlows() {
        assertTrue(LensUiState.Ready.shouldCloseAtRoot())
        assertTrue(LensUiState.Preparing(SearchOrigin.Share).shouldCloseAtRoot())
        assertTrue(
            LensUiState.Results(SearchOrigin.Share, "https://lens.google.com", emptyList())
                .shouldCloseAtRoot(),
        )
    }

    @Test
    fun rootBackReturnsLauncherFlowsToReady() {
        assertFalse(LensUiState.Uploading(SearchOrigin.Launcher, 50).shouldCloseAtRoot())
        assertFalse(
            LensUiState.Error(SearchOrigin.Launcher, LensFailure.Generic).shouldCloseAtRoot(),
        )
    }
}
