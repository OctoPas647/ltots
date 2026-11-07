package com.ltots.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Person
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
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.ltots.app.CrashReport
import com.ltots.app.playback.PlayerConnection
import com.ltots.app.ui.glass.AppBackground
import com.ltots.app.ui.glass.GlassPanel
import com.ltots.app.ui.glass.LocalBackdrop
import com.ltots.app.ui.components.Pill
import com.ltots.app.ui.screens.DownloadsScreen
import com.ltots.app.ui.screens.EqScreen
import com.ltots.app.ui.screens.LibraryScreen
import com.ltots.app.ui.screens.LookScreen
import com.ltots.app.ui.screens.NowPlayingScreen
import com.ltots.app.ui.screens.ProfileScreen
import com.ltots.app.ui.screens.SoundScreen
import com.ltots.app.ui.theme.LocalPalette
import com.ltots.app.ui.theme.ThemeState
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** The tabs in the bar, left to right (also the pages you can swipe between). */
enum class Tab(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Rounded.Home),
    LIBRARY("Library", Icons.Rounded.LibraryMusic),
    PLAYER("Playing", Icons.Rounded.MusicNote),
    DOWNLOADS("Get", Icons.Rounded.Download),
    /** Profile and Look share this tab (a Profile / Look switch at its top). */
    PROFILE("Profile", Icons.Rounded.Person),
}

/** Pages that slide in over the tabs, from the Playing tab; any tab in the bar closes them. */
enum class Overlay { SOUND, EQ }

/**
 * Space the floating nav, the mini player (when shown) and the system navigation bar take at the
 * bottom, plus a little extra room; screens end their scrolling content with this much space.
 * Always the full-size bar's space, also while the bar is compact: lists would jump otherwise.
 */
val LocalBottomChrome = compositionLocalOf { 200.dp }

/** Height of the status bar (and a camera cut-out) at the top, which the pages draw under. */
val LocalTopInset = compositionLocalOf { 0.dp }

/**
 * Top space a page should leave so it starts below the status bar. Scrolling pages put it
 * inside the scroll, so their content scrolls on under the (transparent) status bar.
 */
@Composable
fun topInset(): Dp = LocalTopInset.current

/** Bottom space a screen should leave so its last item can scroll clear of the bars. */
@Composable
fun bottomChrome(): Dp = LocalBottomChrome.current

// Sizes of the bars at the bottom, full size and compact (while scrolling down a page).
private val NAV_FULL = 68.dp
private val NAV_COMPACT = 50.dp
private val MINI_FULL = 64.dp
private val MINI_COMPACT = 50.dp

@Composable
fun AppRoot() {
    val controller by PlayerConnection.controller.collectAsState()
    val player = rememberPlayerUi(controller)
    // Opens on Home, like YouTube Music and Spotify. The pager's page is the tab.
    val pager = rememberPagerState(initialPage = Tab.HOME.ordinal) { Tab.entries.size }
    val tab = Tab.entries[pager.currentPage.coerceIn(0, Tab.entries.lastIndex)]
    // Sound (output and signal path) or the EQ, slid in over the tabs from the Playing tab.
    var overlay by rememberSaveable { mutableStateOf<Overlay?>(null) }
    // The listening history on the Playing tab (it shows there anyway when nothing plays).
    var historyOpen by rememberSaveable { mutableStateOf(false) }
    // The Profile tab shows Look instead of the profiles.
    var lookOpen by rememberSaveable { mutableStateOf(false) }
    val artwork = rememberArtwork(player.trackId, player.artworkData)
    // The next song's cover, fetched ahead so it shows the moment that song starts.
    val appContext = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(player.trackId) { runCatching { prefetchArtwork(appContext, player.controller) } }
    val backdrop = rememberLayerBackdrop()
    // The page itself (background + the open tab): the floating bars at the bottom bend and blur
    // it as it scrolls under them, like liquid glass on iOS.
    val pageBackdrop = rememberLayerBackdrop()
    // Smoothness: on a phone that can't keep up, the bars bend only the background (Balanced) or
    // nothing (Light), instead of re-rendering the whole page under them on every frame.
    val effects = rememberEffects()
    val bendPage = effects == com.ltots.app.data.Effects.FULL

    // Swiping between tabs: a setting (Profile > Look > Pages), and always off with gesture
    // navigation, whose back gesture owns the screen's edges.
    val cfg by ThemeState.config.collectAsState()
    val gestureNav = rememberGestureNav()
    val swipe = cfg.swipeTabs && !gestureNav

    // The bottom bar and mini player shrink while a page scrolls down (see BarCollapse).
    val bar = rememberBarCollapse()

    // Dynamic colours: the theme follows the current cover.
    LaunchedEffect(artwork) { com.ltots.app.ui.theme.DynamicColor.onArtwork(artwork) }

    // ---- moving between tabs ----------------------------------------------------------------
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    // A tab further than one page away is jumped to, then comes in with this (fade + rise):
    // sliding across the pages in between would build them all on the way.
    val pageEnter = remember { Animatable(1f) }
    val enterPage = remember { mutableIntStateOf(-1) }
    val enterRisePx = with(density) { 28.dp.toPx() }
    // The highlight behind the selected tab: follows a swipe exactly, springs on a tap.
    val indicator = remember { Animatable(pager.currentPage.toFloat()) }
    // Non-state flags (read inside effects only).
    val tapping = remember { booleanArrayOf(false) }
    val switchJob = remember { arrayOfNulls<Job>(1) }

    fun goTo(t: Tab, why: String) {
        if (overlay != null) CrashReport.step("close ${overlay?.name} ($why)")
        overlay = null
        if (t != Tab.PLAYER) historyOpen = false
        val from = pager.currentPage
        val to = t.ordinal
        CrashReport.step("go to ${t.name} from ${Tab.entries.getOrNull(from)?.name} ($why)")
        if (from == to && !pager.isScrollInProgress) { bar.expand(); return }
        switchJob[0]?.cancel()
        tapping[0] = true
        scope.launch { indicator.animateTo(to.toFloat(), Springs.page) }
        switchJob[0] = scope.launch {
            try {
                if (abs(to - from) <= 1) {
                    pager.animateScrollToPage(to, animationSpec = Springs.page)
                } else {
                    enterPage.intValue = to
                    pageEnter.snapTo(0f)
                    pager.scrollToPage(to)
                    pageEnter.animateTo(1f, Springs.enter)
                }
            } finally {
                tapping[0] = false
            }
        }
    }

    /** Straight to [t] without animating (links from outside the app, notifications). */
    suspend fun jumpTo(t: Tab) {
        switchJob[0]?.cancel()
        if (t != Tab.PLAYER) historyOpen = false
        pager.scrollToPage(t.ordinal)
        indicator.snapTo(t.ordinal.toFloat())
    }

    // A swipe moves the highlight with the finger (a tap animates it itself).
    LaunchedEffect(pager) {
        snapshotFlow { pager.currentPage + pager.currentPageOffsetFraction }.collect { pos ->
            if (!tapping[0] && pager.isScrollInProgress) indicator.snapTo(pos.coerceIn(0f, Tab.entries.lastIndex.toFloat()))
        }
    }
    // Each time a page settles: the bars back to full size, and the crash report knows where we are.
    LaunchedEffect(pager) {
        var last = pager.settledPage
        CrashReport.note("tab", Tab.entries[last].name)
        snapshotFlow { pager.settledPage }.collect { page ->
            if (page == last) return@collect
            val t = Tab.entries.getOrNull(page) ?: return@collect
            CrashReport.step("tab ${Tab.entries.getOrNull(last)?.name} → ${t.name}")
            CrashReport.note("tab", t.name)
            if (t != Tab.PLAYER) historyOpen = false
            if (!tapping[0]) indicator.snapTo(page.toFloat())
            bar.expand()
            last = page
        }
    }
    // What the crash report says about the UI. Each is keyed on its own value, so it updates even
    // when only a page redraws (the Profile / Look switch is read inside its page, not up here).
    val swipeNote = when {
        gestureNav -> "off (gesture navigation)"
        cfg.swipeTabs -> "on"
        else -> "off (setting)"
    }
    LaunchedEffect(swipeNote) { CrashReport.note("swipe between tabs", swipeNote) }
    LaunchedEffect(effects) { CrashReport.note("smoothness", effects.name) }
    LaunchedEffect(overlay) { CrashReport.note("overlay", overlay?.name ?: "none") }
    LaunchedEffect(lookOpen) { CrashReport.note("profile tab shows", if (lookOpen) "Look" else "Profile") }

    // Shared links open the Get tab; notifications can ask for a tab too.
    val shared by Inbox.sharedText.collectAsState()
    val wantTab by Inbox.openTab.collectAsState()
    LaunchedEffect(shared) { if (shared != null) { overlay = null; jumpTo(Tab.DOWNLOADS) } }
    val sharedFile by Inbox.sharedFile.collectAsState()
    LaunchedEffect(sharedFile) { if (sharedFile != null) { overlay = null; jumpTo(Tab.DOWNLOADS) } }
    LaunchedEffect(wantTab) {
        wantTab?.let { name ->
            CrashReport.step("open $name (link)")
            when (name) {
                "SOUND" -> { overlay = Overlay.SOUND }
                // The EQ and Look were tabs of their own; links to them still work.
                "EQ" -> { jumpTo(Tab.PLAYER); overlay = Overlay.EQ }
                "LOOK" -> { overlay = null; jumpTo(Tab.PROFILE); lookOpen = true }
                else -> { Tab.entries.firstOrNull { it.name == name }?.let { overlay = null; jumpTo(it) } }
            }
            Unit
        }
        Inbox.openTab.value = null
    }

    Box(Modifier.fillMaxSize()) {

        // nav bar 68 + its padding 10+10, mini player 64 + gap 10, system bar, and 32 extra.
        val miniShown = player.hasMedia && (tab != Tab.PLAYER || overlay != null || historyOpen)
        val sysBar = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val chrome = sysBar + NAV_FULL + 20.dp + (if (miniShown) MINI_FULL + 10.dp else 0.dp) + 32.dp
        val top = WindowInsets.statusBars.union(WindowInsets.displayCutout.only(WindowInsetsSides.Top)).asPaddingValues().calculateTopPadding()

        CompositionLocalProvider(LocalBackdrop provides backdrop, LocalBottomChrome provides chrome, LocalEffects provides effects, LocalTopInset provides top) {
          Box(Modifier.fillMaxSize().then(if (bendPage) Modifier.layerBackdrop(pageBackdrop) else Modifier)) {
            // The glass panels on the pages refract this layer.
            AppBackground(artwork, Modifier.fillMaxSize().layerBackdrop(backdrop))
            // The pages reach up under the status bar, which stays transparent; each page leaves
            // room for it at its top (topInset()). A camera cutout at the side (landscape,
            // some phones) is kept clear here. Every vertical scroll on any page passes the
            // bar's connection on its way up, which shrinks and grows the bars.
            Box(
                Modifier.fillMaxSize()
                    .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))
                    .nestedScroll(bar.connection)
            ) {
                // Each place keeps its own remembered state (scroll positions…) while hidden.
                val holder = rememberSaveableStateHolder()
                AnimatedContent(
                    targetState = overlay,
                    transitionSpec = {
                        // iOS push: the new page slides in from the right over the old one, which
                        // drifts a little left and fades; going back is the same in reverse.
                        val opening = targetState != null
                        val move = spring(dampingRatio = 1f, stiffness = Spring.StiffnessMediumLow, visibilityThreshold = IntOffset.VisibilityThreshold)
                        val fade = spring<Float>(dampingRatio = 1f, stiffness = Spring.StiffnessMediumLow)
                        (if (opening) slideInHorizontally(move) { it } + fadeIn(fade, 0.6f)
                        else slideInHorizontally(move) { -it / 4 } + fadeIn(fade))
                            .togetherWith(
                                if (opening) slideOutHorizontally(move) { -it / 4 } + fadeOut(fade)
                                else slideOutHorizontally(move) { it } + fadeOut(fade)
                            ).apply { targetContentZIndex = if (opening) 1f else 0f }
                    },
                    label = "overlay",
                ) { o ->
                    holder.SaveableStateProvider(o?.name ?: "tabs") {
                        when (o) {
                            Overlay.SOUND -> SoundScreen(onBack = { CrashReport.step("close SOUND (back)"); overlay = null })
                            Overlay.EQ -> EqScreen(player, onBack = { CrashReport.step("close EQ (back)"); overlay = null })
                            null -> TabPages(
                                pager, swipe, pageEnter, enterPage, enterRisePx,
                            ) { t ->
                                when (t) {
                                    Tab.HOME -> com.ltots.app.ui.screens.HomeScreen(
                                        player,
                                        onOpenPlayer = { goTo(Tab.PLAYER, "from Home"); historyOpen = false },
                                        onOpenGet = { goTo(Tab.DOWNLOADS, "from Home") },
                                    )
                                    Tab.LIBRARY -> LibraryScreen(
                                        player, onOpenPlayer = { goTo(Tab.PLAYER, "from Library"); historyOpen = false },
                                        onOpenHistory = { goTo(Tab.PLAYER, "history from Library"); historyOpen = true },
                                    )
                                    Tab.PLAYER -> NowPlayingScreen(
                                        player, artwork,
                                        onOpenEq = { CrashReport.step("open EQ"); overlay = Overlay.EQ },
                                        onOpenSound = { CrashReport.step("open SOUND"); overlay = Overlay.SOUND },
                                        showHistory = historyOpen, onShowHistory = { historyOpen = it },
                                    )
                                    Tab.DOWNLOADS -> DownloadsScreen(player, onOpenPlayer = { goTo(Tab.PLAYER, "from Get") })
                                    Tab.PROFILE -> ProfileTab(artwork, lookOpen) { look ->
                                        CrashReport.step(if (look) "Profile tab → Look" else "Profile tab → Profile")
                                        lookOpen = look
                                    }
                                }
                            }
                        }
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
                    visible = miniShown,
                    enter = fadeIn(spring(stiffness = Spring.StiffnessMediumLow)) +
                        slideInVertically(spring(stiffness = Spring.StiffnessMediumLow, visibilityThreshold = IntOffset.VisibilityThreshold)) { it / 2 },
                    exit = fadeOut(spring(stiffness = Spring.StiffnessMediumLow)) +
                        slideOutVertically(spring(stiffness = Spring.StiffnessMediumLow, visibilityThreshold = IntOffset.VisibilityThreshold)) { it / 2 },
                ) {
                    MiniPlayer(player, artwork, bar) {
                        CrashReport.step("open PLAYER (mini player)")
                        historyOpen = false
                        goTo(Tab.PLAYER, "mini player")
                    }
                }
                NavBar(bar, indicator, tab) { goTo(it, "tab bar") }
            }
          }
        }
        // Sign-in pages (song-finder sites, YouTube Music) open over everything.
        com.ltots.app.ui.components.SignInOverlay()
    }
}

/**
 * The tabs side by side. With swiping on they follow the finger and settle on a spring; with it
 * off they only move from the bar. Only the page on screen is built (plus its neighbour while
 * moving); each page keeps its scroll position and other remembered state while away.
 */
@Composable
private fun TabPages(
    pager: PagerState,
    swipe: Boolean,
    pageEnter: Animatable<Float, AnimationVector1D>,
    enterPage: androidx.compose.runtime.MutableIntState,
    enterRisePx: Float,
    page: @Composable (Tab) -> Unit,
) {
    HorizontalPager(
        state = pager,
        modifier = Modifier.fillMaxSize(),
        userScrollEnabled = swipe,
        beyondViewportPageCount = 0,
        key = { Tab.entries[it].name },
        flingBehavior = PagerDefaults.flingBehavior(pager, snapAnimationSpec = Springs.page),
    ) { i ->
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                // Read here (drawing), not while composing: the entrance redraws, never rebuilds.
                if (i == enterPage.intValue) {
                    val e = pageEnter.value
                    alpha = e.coerceIn(0f, 1f)
                    translationY = (1f - e) * enterRisePx
                }
            }
        ) {
            page(Tab.entries[i])
        }
    }
}

/** The Profile tab: the profiles and app settings, or Look, with a switch between them at the top. */
@Composable
private fun ProfileTab(artwork: android.graphics.Bitmap?, showLook: Boolean, onShowLook: (Boolean) -> Unit) {
    val switch: @Composable () -> Unit = {
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("Profile", !showLook, { if (showLook) onShowLook(false) })
            Pill("Look", showLook, { if (!showLook) onShowLook(true) })
        }
    }
    val holder = rememberSaveableStateHolder()
    AnimatedContent(
        targetState = showLook,
        transitionSpec = {
            // Look is to the right of Profile, as on the switch.
            val move = spring(dampingRatio = 1f, stiffness = Spring.StiffnessMediumLow, visibilityThreshold = IntOffset.VisibilityThreshold)
            val fade = spring<Float>(dampingRatio = 1f, stiffness = Spring.StiffnessMediumLow)
            val dir = if (targetState) 1 else -1
            (slideInHorizontally(move) { dir * it / 3 } + fadeIn(fade))
                .togetherWith(slideOutHorizontally(move) { -dir * it / 3 } + fadeOut(fade))
        },
        label = "profileLook",
    ) { look ->
        holder.SaveableStateProvider(if (look) "look" else "profile") {
            if (look) LookScreen(artwork, top = switch) else ProfileScreen(top = switch)
        }
    }
}

/** Height between [full] and [compact] as the bar shrinks; read while measuring, so no rebuilds. */
private fun Modifier.collapsingHeight(bar: BarCollapse, full: Dp, compact: Dp): Modifier = layout { measurable, constraints ->
    val h = bar.lerp(full.toPx(), compact.toPx()).roundToInt()
        .coerceIn(constraints.minHeight, constraints.maxHeight)
    val p = measurable.measure(constraints.copy(minHeight = h, maxHeight = h))
    layout(p.width, h) { p.place(0, 0) }
}

@Composable
private fun MiniPlayer(player: PlayerUi, artwork: android.graphics.Bitmap?, bar: BarCollapse, onOpen: () -> Unit) {
    val pal = LocalPalette.current
    GlassPanel(
        Modifier.fillMaxWidth().collapsingHeight(bar, MINI_FULL, MINI_COMPACT),
        shape = RoundedCornerShape(22.dp),
        tintBoost = 0.08f,
    ) {
        Row(
            Modifier.fillMaxSize().clip(RoundedCornerShape(22.dp)).clickable(onClick = onOpen).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(44.dp)
                    .graphicsLayer {
                        // Shrinks towards its left edge, so the gap to the text stays the same.
                        val s = bar.lerp(1f, 0.76f)
                        scaleX = s; scaleY = s
                        transformOrigin = TransformOrigin(0f, 0.5f)
                    }
                    .clip(RoundedCornerShape(12.dp)).background(pal.text.copy(alpha = 0.08f)),
                contentAlignment = Alignment.Center,
            ) {
                if (artwork != null) Image(artwork.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else Icon(Icons.Rounded.MusicNote, null, tint = pal.textDim)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f).graphicsLayer { translationX = bar.lerp(0f, -8.dp.toPx()) }) {
                // Compact: the artist fades out and the title moves down to the middle.
                Text(
                    player.title, color = pal.text, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.graphicsLayer { translationY = bar.lerp(0f, 8.dp.toPx()) },
                )
                Text(
                    player.artist, color = pal.textDim, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.graphicsLayer { alpha = (1f - bar.value * 1.8f).coerceIn(0f, 1f) },
                )
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
                        drawRoundRect(accent, size = Size(size.width * frac, size.height),
                            cornerRadius = CornerRadius(1.dp.toPx()))
                    }
                }
        )
    }
}

@Composable
private fun NavBar(bar: BarCollapse, indicator: Animatable<Float, AnimationVector1D>, selected: Tab, onSelect: (Tab) -> Unit) {
    val pal = LocalPalette.current
    val highlight = pal.accent.copy(alpha = 0.22f)
    GlassPanel(Modifier.fillMaxWidth().collapsingHeight(bar, NAV_FULL, NAV_COMPACT), shape = RoundedCornerShape(34.dp), tintBoost = 0.04f) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 6.dp, vertical = 5.dp)
                // The highlight behind the selected tab, as one pill that slides between them.
                .drawBehind {
                    val n = Tab.entries.size
                    val w = size.width / n
                    val x = indicator.value.coerceIn(0f, (n - 1).toFloat()) * w
                    drawRoundRect(highlight, topLeft = Offset(x, 0f), size = Size(w, size.height), cornerRadius = CornerRadius(size.height / 2f))
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Tab.entries.forEach { t ->
                val on = t == selected
                Box(
                    Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(50)).clickable { onSelect(t) },
                    contentAlignment = Alignment.Center,
                ) {
                    // Full size: icon above its label. Compact: the label fades away and the
                    // (smaller) icon moves to the middle. All of it while drawing, no rebuilds.
                    Icon(
                        t.icon, null, tint = if (on) pal.accent else pal.textDim,
                        modifier = Modifier.size(24.dp).graphicsLayer {
                            val s = bar.lerp(1f, 0.86f)
                            scaleX = s; scaleY = s
                            translationY = bar.lerp(-8.dp.toPx(), 0f)
                        },
                    )
                    Text(
                        t.label, style = MaterialTheme.typography.labelSmall,
                        color = if (on) pal.text else pal.textFaint, maxLines = 1,
                        modifier = Modifier.graphicsLayer {
                            alpha = (1f - bar.value * 1.8f).coerceIn(0f, 1f)
                            translationY = bar.lerp(13.dp.toPx(), 19.dp.toPx())
                        },
                    )
                }
            }
        }
    }
}
