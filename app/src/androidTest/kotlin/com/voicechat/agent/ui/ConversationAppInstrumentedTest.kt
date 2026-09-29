package com.voicechat.agent.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.voicechat.agent.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Minimal on-device Compose smoke test (Tests.md).
 *
 * It launches the real activity (Room + Compose + the unconfigured model) and
 * checks the conversation surface renders. It does not exercise providers or
 * speech; richer UI flows run on the JVM under Robolectric (M06).
 */
@RunWith(AndroidJUnit4::class)
class ConversationAppInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun theAppLaunchesOnDeviceAndShowsTheConversationList() {
        composeRule.onNodeWithTag(ConversationTestTags.NEW_CONVERSATION).assertIsDisplayed()
    }
}
