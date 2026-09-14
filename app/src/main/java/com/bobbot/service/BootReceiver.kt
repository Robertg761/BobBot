package com.bobbot.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.bobbot.data.prefs.AppPrefs
import kotlinx.coroutines.runBlocking

/**
 * Brings the link back up after a reboot (or an app update) so bots can reach the phone
 * without the user opening BobBot first.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED &&
            action != "android.intent.action.QUICKBOOT_POWERON"
        ) return

        val app = context.applicationContext
        try {
            // DataStore reads are fast and this receiver has a 10s budget; blocking is fine here.
            val prefs = AppPrefs(app)
            val (notifications, connection) = runBlocking {
                prefs.currentNotifications() to prefs.current()
            }
            if (notifications.enabled && connection.setupComplete) {
                LinkService.start(app)
            }
        } catch (e: Throwable) {
            Log.w("BootReceiver", "could not restart link service", e)
        }
    }
}
