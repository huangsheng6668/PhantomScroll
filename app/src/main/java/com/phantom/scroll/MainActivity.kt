package com.phantom.scroll

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.phantom.scroll.ui.screen.MainScreen
import com.phantom.scroll.ui.theme.PhantomScrollTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PhantomScrollTheme {
                MainScreen()
            }
        }
    }
}
