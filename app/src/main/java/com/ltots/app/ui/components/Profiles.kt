package com.ltots.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ltots.app.ui.glass.GlassPanel
import com.ltots.app.ui.theme.LocalPalette
import com.ltots.app.ui.theme.LocalThemeConfig

/** The glass "Custom 1 ▾" button that opens a profile menu. */
@Composable
fun ProfilePill(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val pal = LocalPalette.current
    val shape = RoundedCornerShape(50)
    GlassPanel(modifier, shape = shape, strength = 0.7f) {
        Row(
            Modifier.clip(shape).clickable(onClick = onClick).padding(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Person, null, tint = pal.accent, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(label, color = pal.text, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Icon(Icons.Rounded.ArrowDropDown, null, tint = pal.textDim)
        }
    }
}

/** Pop-up list of profiles, in the app's colours. Anchor it in a Box with its button. */
@Composable
fun ProfileMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    title: String,
    hint: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    val pal = LocalPalette.current
    val cfg = LocalThemeConfig.current
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(20.dp),
        containerColor = when {
            cfg.pureBlack -> Color(0xFF111114)
            pal.dark -> Color(0xFF1C1830)
            else -> Color(0xFFF7F5FF)
        },
        border = BorderStroke(1.dp, pal.line),
        modifier = Modifier.widthIn(min = 260.dp, max = 320.dp),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 6.dp)) {
            Text(title.uppercase(), color = pal.textFaint, style = MaterialTheme.typography.labelSmall)
            Text(hint, color = pal.textDim, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp))
        }
        content()
    }
}

/** The "+" row at the bottom of a profile menu, shown until there are as many as allowed. */
@Composable
fun ProfileAddRow(label: String, onClick: () -> Unit) {
    val pal = LocalPalette.current
    DropdownMenuItem(
        text = { Text(label, color = pal.accent, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium) },
        onClick = onClick,
        leadingIcon = { Icon(Icons.Rounded.Add, null, tint = pal.accent) },
    )
}

/** Small icon buttons at the end of a profile row (rename, delete, save…). */
@Composable
fun ProfileRowActions(content: @Composable RowScope.() -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, content = content)
}

@Composable
fun ProfileIconButton(icon: ImageVector, description: String, onClick: () -> Unit, tint: Color? = null) {
    val pal = LocalPalette.current
    IconButton(onClick = onClick, modifier = Modifier.size(36.dp)) {
        Icon(icon, description, tint = tint ?: pal.textDim, modifier = Modifier.size(20.dp))
    }
}

/** Asks for a new name; [onDone] gets it (only when it isn't blank). */
@Composable
fun RenameDialog(title: String, current: String, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    val pal = LocalPalette.current
    var name by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(current, androidx.compose.ui.text.TextRange(0, current.length))) }
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(pal.text.copy(alpha = 0.08f)).padding(12.dp)
            ) {
                androidx.compose.foundation.text.BasicTextField(
                    name, { if (it.text.length <= 30) name = it }, singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(color = pal.text, fontSize = 16.sp),
                    cursorBrush = SolidColor(pal.accent),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = {
                        if (name.text.isNotBlank()) { onDone(name.text.trim()); onDismiss() }
                    }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
            LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        },
        confirmButton = {
            TextButton(onClick = { if (name.text.isNotBlank()) { onDone(name.text.trim()); onDismiss() } }, enabled = name.text.isNotBlank()) { Text("Rename") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** "Delete X?" — [onConfirm] runs only if they confirm. */
@Composable
fun DeleteDialog(title: String, text: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = { onConfirm(); onDismiss() }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** One profile slot in a [ProfileMenu]. */
@Composable
fun ProfileRow(
    name: String,
    status: String,
    active: Boolean,
    onClick: () -> Unit,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val pal = LocalPalette.current
    DropdownMenuItem(
        text = {
            Column {
                Text(name, color = if (active) pal.accent else pal.text, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                Text(status, color = pal.textFaint, style = MaterialTheme.typography.bodySmall)
            }
        },
        onClick = onClick,
        leadingIcon = leading,
        trailingIcon = trailing,
    )
}
