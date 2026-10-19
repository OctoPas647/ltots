package com.ltots.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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

/** Names of the five profile slots, on the Look tab and the EQ tab. */
fun customName(i: Int) = "Custom ${i + 1}"

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

/** Pop-up list of the Custom 1-5 profiles, in the app's colours. Anchor it in a Box with its button. */
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
