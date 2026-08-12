package com.moonblogger.app

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.moonblogger.app.ui.theme.MoonBloggerTheme
import org.junit.Rule
import org.junit.Test

/**
 * Smoke test instrumentado: verifica que el tema Compose se compone sin
 * errores (necesita emulador/dispositivo: `./gradlew connectedDebugAndroidTest`).
 */
class MoonBloggerThemeTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun themeComposesContent() {
        composeTestRule.setContent {
            MoonBloggerTheme {
                Text("MoonBlogger")
            }
        }
        composeTestRule.onNodeWithText("MoonBlogger").assertIsDisplayed()
    }
}
