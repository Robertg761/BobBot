package com.bobbot

import android.app.Application
import com.bobbot.service.Shortcuts
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class BobBotApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Conversation shortcuts follow the bot roster wherever it loads, app or service.
        runCatching { Shortcuts.attach(this) }
    }
}
