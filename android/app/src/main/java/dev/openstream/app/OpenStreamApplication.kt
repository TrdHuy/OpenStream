package dev.openstream.app

import android.app.Application
import dev.openstream.app.stream.StreamConfig
import dev.openstream.app.stream.StreamConfigStore

class OpenStreamApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        StreamConfig.installRuntimeConfig(StreamConfigStore.load(this))
    }
}
