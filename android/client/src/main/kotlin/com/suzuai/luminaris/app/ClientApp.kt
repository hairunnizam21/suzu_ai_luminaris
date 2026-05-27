package com.suzuai.luminaris.app

import android.app.Application
import com.suzuai.luminaris.shared.data.BackendStore

class ClientApp : Application() {
    val store by lazy { BackendStore(this) }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: ClientApp
            private set
    }
}
