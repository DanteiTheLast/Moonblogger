package com.moonblogger.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.moonblogger.app.ui.MoonBloggerRoot
import com.moonblogger.app.ui.theme.MoonBloggerTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val container = (application as MoonBloggerApp).container

        setContent {
            MoonBloggerTheme {
                MoonBloggerRoot(container)
            }
        }
    }
}
