package com.moonblogger.app.ui.components

import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import coil3.compose.AsyncImage
import com.moonblogger.app.R
import kotlinx.coroutines.delay

// Retained for existing empty-state callers; supplied artwork replaces both poses.
enum class KotoPose { Sitting, Sleeping }
enum class KotoSize(val dp: Dp) { Small(56.dp), Medium(88.dp), Large(128.dp) }

/** Exact composited WebP frames and timings, used on Android 8. */
internal val kotoLegacyFrames = intArrayOf(
    R.drawable.koto_frame_0, R.drawable.koto_frame_1, R.drawable.koto_frame_2,
    R.drawable.koto_frame_3, R.drawable.koto_frame_4, R.drawable.koto_frame_5,
)
internal val kotoFrameDurations = longArrayOf(450, 350, 350, 350, 350, 450)

/** Decorative supplied artwork; preserves aspect ratio and obeys reduced motion. */
@Suppress("UNUSED_PARAMETER")
@Composable
fun KotoSprite(
    modifier: Modifier = Modifier,
    pose: KotoPose = KotoPose.Sitting,
    size: KotoSize = KotoSize.Medium,
    sizeDp: Dp = size.dp,
) {
    val motionEnabled = rememberKotoMotionEnabled()
    val imageModifier = modifier.size(sizeDp).semantics { hideFromAccessibility() }
    when {
        !motionEnabled -> Image(
            painter = painterResource(R.drawable.koto_still), contentDescription = null,
            modifier = imageModifier, contentScale = ContentScale.Fit,
        )
        Build.VERSION.SDK_INT >= 28 -> AsyncImage(
            model = R.raw.koto_animated, contentDescription = null, modifier = imageModifier,
            contentScale = ContentScale.Fit,
            placeholder = painterResource(R.drawable.koto_still),
            error = painterResource(R.drawable.koto_still),
        )
        else -> LegacyKotoAnimation(imageModifier)
    }
}

@Composable
private fun LegacyKotoAnimation(modifier: Modifier) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var frame by remember { mutableIntStateOf(0) }
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                delay(kotoFrameDurations[frame])
                frame = (frame + 1) % kotoLegacyFrames.size
            }
        }
    }
    Image(
        painter = painterResource(kotoLegacyFrames[frame]), contentDescription = null,
        modifier = modifier, contentScale = ContentScale.Fit,
    )
}

@Composable
private fun rememberKotoMotionEnabled(): Boolean {
    val resolver = LocalContext.current.contentResolver
    fun readScale() = Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    var enabled by remember(resolver) { mutableStateOf(readScale()) }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { enabled = readScale() }
        }
        resolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer,
        )
        enabled = readScale()
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return enabled
}
