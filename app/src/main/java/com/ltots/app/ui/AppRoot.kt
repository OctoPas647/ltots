package com.ltots.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.ltots.app.playback.PlayerConnection
import com.ltots.app.ui.glass.AppBackground
import com.ltots.app.ui.glass.GlassPanel
import com.ltots.app.ui.glass.LocalBackdrop
import com.ltots.app.ui.screens.DownloadsScreen
import com.ltots.app.ui.screens.EqScreen
import com.ltots.app.ui.screens.LibraryScreen
import com.ltots.app.ui.screens.LookScreen
import com.ltots.app.ui.screens.NowPlayingScreen
import com.ltots.app.ui.screens.SoundScreen
import com.ltots.app.ui.theme.LocalPalette

enum class Tab(val label: String, val icon: ImageVector) {
    LIBRARY("Library", Icons.Rounded.LibraryMusic),
    PLAYER("Playing", Icons.Rounded.MusicNote),
    EQ("EQ", Icons.Rounded.GraphicEq),
    DOWNLOADS("Get", Icons.Rounded.Download),
    LOOK("Look", Icons.Rounded.Palette),
    SOUND("Sound", Icons.Rounded.Headphones),
}

/** Space the floating nav (and mini player) take at the bottom; screens pad their content by this. */
val BottomChrome = PaddingValues(bottom = 168.dp)

@Composable
fun AppRoot() {
    val controller by PlayerConnection.controller.collectAsState()
    val player = rememberPlayerUi(controller)
    var tab by rememberSaveable { mutableStateOf(Tab.LIBRARY) }
    val artwork = rememberArtwork(player.trackId, player.artworkData)
    val backdrop = rememberLayerBackdrop()

    // Dynamic colours: the theme follows the current cover.
    LaunchedEffect(artwork) { com.ltots.app.ui.theme.DynamicColor.onArtwork(artwork) }

    // Shared links open the Downloads tab; notifications can ask for a tab too.
    val shared by Inbox.sharedText.collectAsState()
    val wantTab by Inbox.openTab.collectAsState()
    LaunchedEffect(shared) { if (shared != null) tab = Tab.DOWNLOADS }
    LaunchedEffect(wantTab) {
        wantTab?.let { name -> Tab.entries.firstOrNull { it.name == name }?.let { tab = it } }
        Inbox.openTab.value = null
    }

    Box(Modifier.fillMaxSize()) {
        // Everything glass refracts this layer.
        AppBackground(artwork, Modifier.fillMaxSize().layerBackdrop(backdrop))

        CompositionLocalProvider(LocalBackdrop provides backdrop) {
            Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout.only(WindowInsetsSides.Top)))) {
                Crossfade(tab, label = "tab") { t ->
                    when (t) {
                        Tab.LIBRARY -> LibraryScreen(player, onOpenPlayer = { tab = Tab.PLAYER })
                        Tab.PLAYER -> NowPlayingScreen(player, artwork, onOpenEq = { tab = Tab.EQ }, onOpenSound = { tab = Tab.SOUND })
                        Tab.EQ -> EqScreen(player)
                        Tab.DOWNLOADS -> DownloadsScreen(player, onOpenPlayer = { tab = Tab.PLAYER })
                        Tab.LOOK -> LookScreen()
                        Tab.SOUND -> SoundScreen()
                    }
                }
            }

            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                AnimatedVisibility(
                    visible = player.hasMedia && tab != Tab.PLAYER,
                    enter = fadeIn() + slideInVertically { it / 2 },
                    exit = fadeOut() + slideOutVertically { it / 2 },
                ) {
                    MiniPlayer(player, artwork) { tab = Tab.PLAYER }
                }
                NavBar(tab) { tab = it }
            }
        }
    }
}

@Composable
private fun MiniPlayer(player: PlayerUi, artwork: android.graphics.Bitmap?, onOpen: () -> Unit) {
    val pal = LocalPalette.current
    GlassPanel(
        Modifier.fillMaxWidth().height(64.dp),
        shape = RoundedCornerShape(22.dp),
        tintBoost = 0.08f,
    ) {
        Row(
            Modifier.fillMaxSize().clip(RoundedCornerShape(22.dp)).clickable(onClick = onOpen).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(pal.text.copy(alpha = 0.08f)), contentAlignment = Alignment.Center) {
                if (artwork != null) Image(artwork.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else Icon(Icons.Rounded.MusicNote, null, tint = pal.textDim)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(player.title, color = pal.text, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(player.artist, color = pal.textDim, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = { player.togglePlay() }) {
                Icon(if (player.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Play/pause", tint = pal.text, modifier = Modifier.size(30.dp))
            }
            IconButton(onClick = { player.controller?.seekToNext() }) {
                Icon(Icons.Rounded.SkipNext, "Next", tint = pal.text)
            }
        }
        // Thin progress line along the bottom edge. The position is read while drawing, in its
        // own layer: as the song plays only this line is redrawn, not the mini player or its glass.
        val accent = pal.accent
        Box(
            Modifier.align(Alignment.BottomStart).padding(horizontal = 18.dp).fillMaxWidth().height(2.dp)
                .graphicsLayer()
                .drawBehind {
                    val frac = if (player.durationMs > 0) (player.positionMs.toFloat() / player.durationMs).coerceIn(0f, 1f) else 0f
                    if (frac > 0f) {
                        drawRoundRect(accent, size = androidx.compose.ui.geometry.Size(size.width * frac, size.height),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.dp.toPx()))
                    }
                }
        )
    }
}

@Composable
private fun NavBar(selected: Tab, onSelect: (Tab) -> Unit) {
    val pal = LocalPalette.current
    GlassPanel(Modifier.fillMaxWidth().height(68.dp), shape = RoundedCornerShape(34.dp), tintBoost = 0.04f) {
        Row(Modifier.fillMaxSize().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Tab.entries.forEach { t ->
                val on = t == selected
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(26.dp))
                        .background(if (on) pal.accent.copy(alpha = 0.22f) else androidx.compose.ui.graphics.Color.Transparent)
                        .clickable { onSelect(t) }.padding(vertical = 7.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(t.icon, t.label, tint = if (on) pal.accent else pal.textDim, modifier = Modifier.size(24.dp))
                    Text(
                        t.label, style = MaterialTheme.typography.labelSmall,
                        color = if (on) pal.text else pal.textFaint,
                    )
                }
            }
        }
    }
}
