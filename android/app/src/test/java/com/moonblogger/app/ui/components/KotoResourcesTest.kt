package com.moonblogger.app.ui.components

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.os.Build
import org.robolectric.RuntimeEnvironment
import com.moonblogger.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class KotoResourcesTest {
    private val context = RuntimeEnvironment.getApplication() as Context

    @Test fun suppliedStillAndLegacyFramesDecodeWithoutDensityScaling() {
        for (resource in kotoLegacyFrames + R.drawable.koto_still) {
            val bitmap = BitmapFactory.decodeResource(context.resources, resource)
            assertNotNull(bitmap)
            assertEquals(512, bitmap.width)
            assertEquals(512, bitmap.height)
            bitmap.recycle()
        }
    }

    @Test fun launcherResourcesInflateOnMinimumAndModernAndroid() {
        assertNotNull(context.getDrawable(R.mipmap.ic_launcher))
        assertNotNull(context.getDrawable(R.mipmap.ic_launcher_round))
    }

    @Test @Config(sdk = [35]) fun suppliedWebPDecodesAsAnimationOnModernAndroid() {
        assertTrue(Build.VERSION.SDK_INT >= 28)
        val image = ImageDecoder.decodeDrawable(ImageDecoder.createSource(context.resources, R.raw.koto_animated))
        assertTrue(image is AnimatedImageDrawable)
        assertEquals(512, image.intrinsicWidth)
        assertEquals(512, image.intrinsicHeight)
    }
}
