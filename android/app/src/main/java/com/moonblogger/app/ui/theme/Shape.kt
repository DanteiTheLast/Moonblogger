package com.moonblogger.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Tokens de forma — espejo de `web/app/tokens.css`:
 * --radius-sm 0.75rem=12dp, --radius-md 1.25rem=20dp, --radius-lg 2rem=32dp.
 */
val MoonShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),   // --radius-sm
    medium = RoundedCornerShape(20.dp),  // --radius-md
    large = RoundedCornerShape(32.dp),   // --radius-lg
    extraLarge = RoundedCornerShape(32.dp),
)
