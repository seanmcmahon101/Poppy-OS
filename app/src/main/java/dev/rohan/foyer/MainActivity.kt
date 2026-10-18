package dev.rohan.foyer

import android.content.BroadcastReceiver
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import dev.rohan.foyer.data.AndroidPlatform
import dev.rohan.foyer.data.SettingsHandoff
import dev.rohan.foyer.ui.FoyerScreen

class MainActivity : ComponentActivity() {

    private lateinit var model: FoyerModel
    private lateinit var platform: AndroidPlatform
    private var started = false

    private val timeReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = model.tick()
    }
    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = model.refreshApps()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        platform = AndroidPlatform(applicationContext)
        model = FoyerModel(applicationContext, platform, lifecycleScope)
        model.start()
        setContent { FoyerScreen(model, BuildConfig.IS_DEV) }

        // This is home: Back never leaves it (the UI consumes Back first; this is the backstop).
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = Unit
        })
        registerReceiver(packageReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        })
    }

    override fun onStart() {
        super.onStart()
        registerReceiver(timeReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        })
        if (started) model.resume() else model.tick()
        started = true
    }

    override fun onStop() {
        unregisterReceiver(timeReceiver)
        super.onStop()
        // Foyer stops once Amazon's Settings screen is in front: that's the moment to hop to the
        // main grid. A short beat lets Home on Fire register the Settings window first.
        val hopIn = SettingsHandoff.take()
        if (hopIn >= 0) window.decorView.postDelayed({ platform.openSettingsGrid() }, hopIn)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) model.onWindowFocused()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        model.onHomeIntent()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) model.releaseImages()
    }

    override fun onDestroy() {
        unregisterReceiver(packageReceiver)
        super.onDestroy()
    }
}
