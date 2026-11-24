package com.meda.portico.setup

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import android.widget.Toast
import com.meda.portico.R
import com.meda.portico.core.ring.Channel
import com.meda.portico.ring.Channels

/**
 * 0.6.69: the Android system settings pages Portico links to. They are reached only from Settings > "Android settings"
 * (the Status page reports the problem and points there); this is the one place that builds their intents.
 */
object AndroidPages {
    /** App details: battery use and background restriction. */
    fun appDetails(context: Context): Intent =
        Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))

    fun notifications(context: Context): Intent =
        Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(AndroidSettings.EXTRA_APP_PACKAGE, context.packageName)

    /** "Full-screen calls": the full-screen-intent page (Android 14+), else the app details. */
    fun fullScreen(context: Context): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            Intent(AndroidSettings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${context.packageName}"))
        } else {
            appDetails(context)
        }

    /** 0.6.74: "Display over other apps" for this app (lets the ring screen open directly while the phone is in use). */
    fun overOtherApps(context: Context): Intent =
        Intent(AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))

    /** The "Status" notification channel's page. */
    fun statusChannel(context: Context): Intent {
        Channels.ensure(context) // the channel must exist for its page to open
        return Channels.settingsIntent(context, Channel.STATUS)
    }

    fun open(context: Context, intent: Intent) {
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, R.string.fix_no_settings_screen, Toast.LENGTH_LONG).show()
        }
    }
}
