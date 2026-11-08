package com.ltots.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Opened by the home-screen icon (whichever colour is showing): starts [MainActivity] in LToTS's
 * own task and closes at once. So the task in Recents always belongs to MainActivity, which is
 * never switched off, instead of to a colour icon that "Icon follows the song colour" switches
 * off when you leave the app (HyperOS then removed LToTS from Recents).
 */
class LaunchActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setClass(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED),
        )
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
