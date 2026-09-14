package dev.nulifyer.sharetolens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.nulifyer.sharetolens.ui.ShareToLensScreen
import dev.nulifyer.sharetolens.ui.theme.ShareToLensTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReadyScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun readyStateShowsBothImageSourcesAndPrivacyCopy() {
        composeRule.setContent {
            ShareToLensTheme(dynamicColor = false) {
                ShareToLensScreen(
                    state = LensUiState.Ready,
                    onChoosePhoto = {},
                    onTakePhoto = {},
                    onRetry = {},
                    onNewSearch = {},
                    onExit = {},
                    onOpenLink = {},
                )
            }
        }

        composeRule.onNodeWithText("Choose a photo").assertIsDisplayed()
        composeRule.onNodeWithText("Take a photo").assertIsDisplayed()
        composeRule.onNodeWithText(
            "Processed on this device. File metadata is removed before upload.",
        ).assertIsDisplayed()
    }
}
