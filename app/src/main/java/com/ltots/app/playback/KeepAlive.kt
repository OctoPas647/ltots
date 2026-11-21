package com.ltots.app.playback

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Keeping LToTS alive in the background on phones whose battery manager closes apps by itself.
 *
 * HyperOS / MIUI (Xiaomi, Redmi, POCO) run a "lock-screen clean" (LockScreenClean in Android's
 * exit record): a while after the screen locks it force-stops background apps it doesn't trust,
 * playing or not — and a force-stopped app can't come back by itself until it's opened again.
 * No app can switch that off; the person can, with three switches:
 *  1. **Autostart** on (Security app > Permissions > Autostart);
 *  2. **Battery saver: No restrictions** (App info > Battery saver);
 *  3. **Lock LToTS in Recents** (open Recents, hold LToTS' card, tap the lock).
 * Other phones only need Android's own "unrestricted" battery use.
 *
 * This finds out what can be checked, opens the right screen for each step, and remembers
 * when Android's battery manager stopped LToTS so Home can ask once.
 */
object KeepAlive {
    private const val PREFS = "keep_alive"

    /** Xiaomi, Redmi or POCO (HyperOS / MIUI). */
    val isXiaomi: Boolean
        get() = Build.MANUFACTURER.lowercase().let { it == "xiaomi" || it == "redmi" || it == "poco" } ||
            Build.BRAND.lowercase().let { it == "xiaomi" || it == "redmi" || it == "poco" }

    /** One thing to set, with whether it's known to be done (null: Android can't tell). */
    data class Step(val id: String, val title: String, val how: String, val done: Boolean?)

    private val _needsSetup = MutableStateFlow(false)
    /** Android's battery manager stopped LToTS and the setup hasn't been gone through since. */
    val needsSetup: StateFlow<Boolean> = _needsSetup

    fun init(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _needsSetup.value = p.getLong("killed_at", 0L) > p.getLong("setup_at", 0L)
    }

    /** Called when an exit record says the battery manager (not the person) stopped LToTS. */
    fun onKilledByCleaner(context: Context, at: Long) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong("killed_at", at).apply()
        _needsSetup.value = true
    }

    /** The setup card was gone through or dismissed. */
    fun markSetUp(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong("setup_at", System.currentTimeMillis()).apply()
        _needsSetup.value = false
    }

    /** Whether an exit record's note is a battery manager / cleaner, not a person's force stop. */
    fun isCleaner(description: String?): Boolean {
        val d = description?.lowercase() ?: return false
        return listOf("lockscreenclean", "powerkeeper", "greeze", "millet", "autoclean", "oneclickclean", "deviceidle", "powerguru", "battery")
            .any { it in d }
    }

    fun ignoringBatteryOptimizations(context: Context): Boolean =
        runCatching { context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName) }.getOrDefault(false)

    fun steps(context: Context): List<Step> = buildList {
        val battery = ignoringBatteryOptimizations(context)
        if (isXiaomi) {
            add(Step("autostart", "Autostart on", "Lets LToTS start again by itself and keep playing after the phone locks.", null))
            add(Step("battery", "Battery saver: No restrictions", "In LToTS' battery settings, pick \"No restrictions\".", battery))
            add(Step("recents", "Lock LToTS in Recents", "Open Recents, hold LToTS' card and tap the lock icon. Locked apps aren't cleared when the phone locks.", null))
        } else {
            add(Step("battery", "Unrestricted battery use", "Lets LToTS keep playing with the screen off.", battery))
        }
    }

    /** Opens the screen for one step. Returns false when nothing could be opened. */
    fun open(context: Context, id: String): Boolean {
        val pkg = context.packageName
        val candidates: List<Intent> = when (id) {
            "autostart" -> listOf(
                Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")),
                Intent("miui.intent.action.OP_AUTO_START").addCategory(Intent.CATEGORY_DEFAULT),
                appDetails(pkg),
            )
            "battery" -> buildList {
                if (isXiaomi) {
                    add(
                        Intent("miui.intent.action.HIDDEN_APPS_CONFIG_ACTIVITY")
                            .putExtra("package_name", pkg).putExtra("package_label", "LToTS"),
                    )
                    add(
                        Intent().setComponent(ComponentName("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"))
                            .putExtra("package_name", pkg).putExtra("package_label", "LToTS"),
                    )
                }
                if (!ignoringBatteryOptimizations(context)) {
                    @Suppress("BatteryLife")
                    add(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$pkg")))
                }
                add(appDetails(pkg))
            }
            "recents" -> listOf(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))
            else -> listOf(appDetails(pkg))
        }
        for (i in candidates) {
            val ok = runCatching {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(i)
            }.isSuccess
            if (ok) return true
        }
        return false
    }

    private fun appDetails(pkg: String) = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))

    /** What the exit report adds when the battery manager stopped LToTS. */
    fun adviceText(): String = buildString {
        appendLine("What this means: the phone's battery manager closed LToTS, not a bug in LToTS.")
        if (isXiaomi) {
            appendLine("HyperOS clears background apps a while after the phone locks (\"LockScreenClean\").")
            appendLine("To stop it: Profile > App settings > Keep playing in the background, or:")
            appendLine("  1. Autostart: on (Security > Permissions > Autostart)")
            appendLine("  2. App info > Battery saver: No restrictions")
            appendLine("  3. Recents: hold LToTS' card and tap the lock")
        } else {
            appendLine("To stop it: App info > Battery > Unrestricted.")
        }
    }
}
