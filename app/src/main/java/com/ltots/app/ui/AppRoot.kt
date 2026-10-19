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
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.Dp
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

/**
 * Space the floating nav, the mini player (when shown) and the system navigation bar take at the
 * bottom, plus a little extra room; screens end their scrolling content with this much space.
 */
val LocalBottomChrome = compositionLocalOf { 200.dp }

/** Bottom space a screen should leave so its last item can scroll clear of the bars. */
@Composable
fun bottomChrome(): Dp = LocalBottomChrome.current

@Composable
fun AppRoot() {
    val controller by PlayerConnection.controller.collectAsState()
    val player = rememberPlayerUi(controller)
    var tab by rememberSaveable { mutableStateOf(Tab.LIBRARY) }
    val artwork = rememberArtwork(player.trackId, player.artworkData)
    val backdrop = rememberLayerBackdrop()
    // The page itself (background + the open tab): the floating bars at the bottom bend and blur
    // it as it scrolls under them, like liquid glass on iOS.
    val pageBackdrop = rememberLayerBackdrop()
    // Smoothness: on a phone that can't keep up, the bars bend only the background (Balanced) or
    // nothing (Light), instead of re-rendering the whole page under them on every frame.
    val effects = rememberEffects()
    val bendPage = effects == com.ltots.app.data.Effects.FULL

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

        // nav bar 68 + its padding 10+10, mini player 64 + gap 10, system bar, and 32 extra.
        val miniShown = player.hasMedia && tab != Tab.PLAYER
        val sysBar = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val chrome = sysBar + 88.dp + (if (miniShown) 74.dp else 0.dp) + 32.dp

        CompositionLocalProvider(LocalBackdrop provides backdrop, LocalBottomChrome provides chrome, LocalEffects provides effects) {
          Box(Modifier.fillMaxSize().then(if (bendPage) Modifier.layerBackdrop(pageBackdrop) else Modifier)) {
            // The glass panels on the pages refract this layer.
            AppBackground(artwork, Modifier.fillMaxSize().layerBackdrop(backdrop))
            Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout.only(WindowInsetsSides.Top)))) {
                Crossfade(tab, label = "tab") { t ->
                    when (t) {
                        Tab.LIBRARY -> LibraryScreen(player, onOpenPlayer = { tab = Tab.PLAYER })
                        Tab.PLAYER -> NowPlayingScreen(player, artwork, onOpenEq = { tab = Tab.EQ }, onOpenSound = { tab = Tab.SOUND })
                        Tab.EQ -> EqScreen(player)
                        Tab.DOWNLOADS -> DownloadsScreen(player, onOpenPlayer = { tab = Tab.PLAYER })
                        Tab.LOOK -> LookScreen(artwork)
                        Tab.SOUND -> SoundScreen()
                    }
                }
            }
          }

          CompositionLocalProvider(
              LocalBackdrop provides (if (bendPage) pageBackdrop else backdrop),
              com.ltots.app.ui.glass.LocalGlassOverContent provides true,
          ) {
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
                Icon(if (player.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Play/pause", tint = pal.accent, modifier = Modifier.size(30.dp))
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
