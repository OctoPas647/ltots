package com.ltots.app.ui.screens

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.ltots.app.crash.CrashLog
import com.ltots.app.crash.CrashNotify
import com.ltots.app.ui.components.DeleteDialog
import com.ltots.app.ui.components.Pill
import com.ltots.app.ui.components.SectionTitle
import com.ltots.app.ui.glass.GlassPanel
import com.ltots.app.ui.theme.LocalPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Profile › Crash reports: every crash, freeze and stop LToTS recorded, newest first. Tap one to
 * read it (Copy, Share, Delete). The same files are in Download/LToTS/Crash reports.
 */
@Composable
fun CrashReportsCard() {
    val pal = LocalPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Reloaded each time the page comes back (after reading or deleting a report).
    var reload by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { reload++ }
    val reports by produceState(emptyList<CrashLog.Entry>(), reload) {
        value = withContext(Dispatchers.IO) { runCatching { CrashLog.list(context) }.getOrDefault(emptyList()) }
    }
    var showAll by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    val fmt = remember { SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()) }

    GlassPanel(Modifier.fillMaxWidth(), tintBoost = 0.05f) {
        Column(Modifier.padding(16.dp)) {
            SectionTitle("Crash reports")
            Text(
                if (reports.isEmpty()) "No crashes recorded. If LToTS ever crashes, freezes or is stopped while playing, the report shows up here."
                else "Crashes, freezes and times Android stopped LToTS while it played. Tap one to read, copy or share it. " +
                    "They're also saved in Download/${CrashLog.EXPORT_FOLDER}.",
                color = pal.textFaint, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp),
            )
            val shown = if (showAll) reports else reports.take(6)
            shown.forEach { e ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                        .clickable { runCatching { context.startActivity(CrashNotify.openIntent(context, e.file, clearTask = false)) } }
                        .padding(horizontal = 8.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(e.kind.label, color = if (e.kind.opensScreen) pal.accent else pal.text, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.width(8.dp))
                            Text(fmt.format(Date(e.at)), color = pal.textFaint, style = MaterialTheme.typography.bodySmall)
                        }
                        Text(e.summary, color = pal.textDim, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            if (reports.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (reports.size > 6) {
                        Pill(if (showAll) "Fewer" else "All ${reports.size}", false, { showAll = !showAll })
                        Spacer(Modifier.width(8.dp))
                    }
                    Pill("Share all", false, {
                        scope.launch {
                            val text = withContext(Dispatchers.IO) {
                                reports.take(15).joinToString("\n\n========================================\n\n") { runCatching { it.file.readText() }.getOrDefault("") }
                            }.take(200_000)
                            runCatching {
                                context.startActivity(
                                    Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share crash reports"),
                                )
                            }
                        }
                    })
                    Spacer(Modifier.width(8.dp))
                    Pill("Save to Downloads", false, {
                        scope.launch {
                            val n = withContext(Dispatchers.IO) { CrashLog.exportPending(context) }
                            Toast.makeText(context, if (n > 0) "Saved $n to Download/${CrashLog.EXPORT_FOLDER}" else "Already in Download/${CrashLog.EXPORT_FOLDER}", Toast.LENGTH_SHORT).show()
                        }
                    })
                    Spacer(Modifier.width(8.dp))
                    Pill("Delete all", false, { confirmClear = true })
                }
            }
        }
    }
    if (confirmClear) {
        DeleteDialog(
            "Delete all crash reports?",
            "They're removed from LToTS. Copies already in Download/${CrashLog.EXPORT_FOLDER} stay.",
            onDismiss = { confirmClear = false },
        ) {
            confirmClear = false
            scope.launch {
                withContext(Dispatchers.IO) { CrashLog.deleteAll(context) }
                reload++
            }
        }
    }
}
