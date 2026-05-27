package com.suzuai.luminaris.panel

import android.app.Application
import com.suzuai.luminaris.shared.data.BackendStore

/**
 * Application-level singletons for the Server Panel APK.
 * Manual DI to keep the APK small (no Hilt dep).
 */
class PanelApp : Application() {
    val store by lazy { BackendStore(this) }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: PanelApp
            private set
    }
}
