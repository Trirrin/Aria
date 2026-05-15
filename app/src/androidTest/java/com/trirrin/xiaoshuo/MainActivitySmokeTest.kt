package com.trirrin.xiaoshuo

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivitySmokeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun mainTabsRenderAndSwitch() {
        composeRule.onNodeWithText("Xiao Shuo").assertIsDisplayed()
        listOf("Library", "Outline", "Draft", "Bible", "History", "Settings").forEach { tab ->
            composeRule.onNodeWithText(tab).assertIsDisplayed()
            composeRule.onNodeWithText(tab).performClick()
        }
        composeRule.onNodeWithText("Provider").assertIsDisplayed()
    }
}
