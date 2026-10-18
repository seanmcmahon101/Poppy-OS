package dev.rohan.foyer

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.media.tv.TvContract
import android.media.tv.TvInputManager
import android.media.tv.TvView
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView

/**
 * Shows an HDMI input full screen through the standard TV Input Framework (TvView).
 * Fire OS locks Amazon's own input player behind signature permissions, and the one open route
 * (Live TV's passthrough alias) opens Live TV instead of the input, so Foyer plays the input itself.
 * Back returns to Foyer; Home goes wherever Home on Fire sends it (Foyer).
 * A plain Activity with no Compose: it has to be light, since it stays up while you watch.
 */
class InputActivity : Activity() {

    private lateinit var tv: TvView
    private lateinit var note: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.setBackgroundColor(Color.BLACK)
        // Watching a console or set-top box: the Fire screensaver must not kick in over it.
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        tv = TvView(this)
        note = TextView(this).apply {
            setTextColor(0xCCF5F7FA.toInt())
            textSize = 15f
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        setContentView(FrameLayout(this).apply {
            addView(tv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(note, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        })
        tv.isFocusable = true
        tv.isFocusableInTouchMode = true
        tv.requestFocus()
        tv.setCallback(object : TvView.TvInputCallback() {
            override fun onConnectionFailed(inputId: String) = show("Couldn't switch to ${label()}")
            override fun onDisconnected(inputId: String) = show("${label()} disconnected")
            override fun onVideoAvailable(inputId: String) { note.visibility = View.GONE }
            override fun onVideoUnavailable(inputId: String, reason: Int) {
                // Tuning and buffering are transient; only say something when nothing is there.
                if (reason == TvInputManager.VIDEO_UNAVAILABLE_REASON_UNKNOWN ||
                    reason == TvInputManager.VIDEO_UNAVAILABLE_REASON_WEAK_SIGNAL
                ) show("Nothing on ${label()}")
            }
        })
        tune(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        tune(intent)
    }

    private fun tune(intent: Intent) {
        val id = intent.getStringExtra(EXTRA_INPUT_ID) ?: return finish()
        note.visibility = View.GONE
        Log.i(TAG, "tune $id")
        tv.tune(id, TvContract.buildChannelUriForPassthroughInput(id))
    }

    private fun label(): String = intent.getStringExtra(EXTRA_LABEL) ?: "this input"

    private fun show(text: String) {
        note.text = text
        note.visibility = View.VISIBLE
    }

    /**
     * Fire OS doesn't pass remote keys from a third-party TvView on to the HDMI device over CEC
     * (only Amazon's own player can; tested 2026-10-12), so Back can't reach the Apple TV anyway.
     * Back returns to Foyer, and so does Home (via Home on Fire).
     */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = finish()

    override fun onStop() {
        super.onStop()
        // Release the input as soon as we're not visible, so other apps (and Foyer) get the hardware back.
        tv.reset()
        finish()
    }

    companion object {
        const val EXTRA_INPUT_ID = "input_id"
        const val EXTRA_LABEL = "label"
        private const val TAG = "FoyerInput"
    }
}
