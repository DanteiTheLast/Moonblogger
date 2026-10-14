package com.moonblogger.app.ui.theme
import android.content.Context
import org.robolectric.RuntimeEnvironment
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ThemePreferenceTest {
 @Test fun `theme defaults to system and persists explicit choice`() {
  val context = RuntimeEnvironment.getApplication()
  context.getSharedPreferences("appearance", Context.MODE_PRIVATE).edit().clear().commit()
  assertEquals(ThemePreference.SYSTEM, ThemeController(context).preference)
  ThemeController(context).select(ThemePreference.DARK)
  assertEquals(ThemePreference.DARK, ThemeController(context).preference)
  ThemeController(context).select(ThemePreference.LIGHT)
  assertEquals(ThemePreference.LIGHT, ThemeController(context).preference)
 }
}
