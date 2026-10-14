package com.moonblogger.app.ui.theme
import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import com.moonblogger.app.R
private val pixel = FontFamily(Font(R.font.pixelify_sans))
private val reading = FontFamily(Font(R.font.nunito))
private val base = Typography()
val MoonTypography = Typography(
    displayLarge = base.displayLarge.copy(fontFamily = pixel), displayMedium = base.displayMedium.copy(fontFamily = pixel), displaySmall = base.displaySmall.copy(fontFamily = pixel),
    headlineLarge = base.headlineLarge.copy(fontFamily = pixel), headlineMedium = base.headlineMedium.copy(fontFamily = pixel), headlineSmall = base.headlineSmall.copy(fontFamily = pixel),
    titleLarge = base.titleLarge.copy(fontFamily = pixel), titleMedium = base.titleMedium.copy(fontFamily = pixel), titleSmall = base.titleSmall.copy(fontFamily = pixel),
    bodyLarge = base.bodyLarge.copy(fontFamily = reading), bodyMedium = base.bodyMedium.copy(fontFamily = reading), bodySmall = base.bodySmall.copy(fontFamily = reading),
    labelLarge = base.labelLarge.copy(fontFamily = reading), labelMedium = base.labelMedium.copy(fontFamily = reading), labelSmall = base.labelSmall.copy(fontFamily = reading),
)
