package com.suzuai.luminaris.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.suzuai.luminaris.app.ui.ClientRoot
import com.suzuai.luminaris.shared.theme.LuminarisColors
import com.suzuai.luminaris.shared.theme.LuminarisTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        setContent {
            LuminarisTheme {
                Box(Modifier.fillMaxSize().background(LuminarisColors.Bg)) {
                    ClientRoot()
                }
            }
        }
    }
}
