package com.ltots.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.ltots.app.playback.KeepAlive
import com.ltots.app.ui.components.Pill
import com.ltots.app.ui.components.SectionTitle
import com.ltots.app.ui.theme.LocalPalette

/**
 * The switches that keep LToTS playing after the phone locks (see [KeepAlive]): each step with
 * a button that opens the right system screen, and a tick once Android says it's done.
 */
@Composable
fun KeepAliveSteps(modifier: Modifier = Modifier) {
    val pal = LocalPalette.current
    val context = LocalContext.current
    // Re-read when coming back from the system screen.
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }
    val steps = remember(refresh) { KeepAlive.steps(context) }
    Column(modifier) {
        steps.forEachIndexed { i, s ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "${i + 1}. ${s.title}" + if (s.done == true) "  ✓" else "",
                        color = if (s.done == true) pal.accent else pal.text, fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(s.how, color = pal.textDim, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.width(10.dp))
                if (s.done != true) Pill(if (s.id == "recents") "Show me" else "Open", true, { KeepAlive.open(context, s.id) })
            }
        }
    }
}

/** Profile > App settings: always there. */
@Composable
fun KeepAliveSettings() {
    val pal = LocalPalette.current
    SectionTitle("App settings · keep playing in the background")
    Text(
        if (KeepAlive.isXiaomi) "HyperOS closes background apps a while after the phone locks, music included. These three switches stop it for LToTS."
        else "Some phones close background apps to save battery. This keeps LToTS playing with the screen off.",
        color = pal.textFaint, style = MaterialTheme.typography.bodySmall,
    )
    KeepAliveSteps(Modifier.padding(bottom = 10.dp))
}

/** Home: shown once Android's battery manager has closed LToTS, until gone through or dismissed. */
@Composable
fun KeepAliveCard(modifier: Modifier = Modifier) {
    val pal = LocalPalette.current
    val context = LocalContext.current
    Column(modifier) {
        Text("Your phone closed LToTS", color = pal.text, fontWeight = FontWeight.SemiBold)
        Text(
            if (KeepAlive.isXiaomi) "HyperOS's lock-screen cleaner stopped the music in the background. Set these once and it won't happen again."
            else "The phone's battery manager stopped the music in the background. Set this once and it won't happen again.",
            color = pal.textDim, style = MaterialTheme.typography.bodySmall,
        )
        KeepAliveSteps(Modifier.padding(top = 6.dp))
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Spacer(Modifier.weight(1f))
            Pill("Done", false, { KeepAlive.markSetUp(context) })
        }
    }
}
