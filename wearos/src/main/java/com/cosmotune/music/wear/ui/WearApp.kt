package com.cosmotune.music.wear.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeText
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import com.cosmotune.music.wear.R
import com.cosmotune.music.wear.data.Link
import com.cosmotune.music.wear.data.PhoneLink
import kotlinx.coroutines.delay

private const val PLAYER = "player"
private const val QUEUE = "queue"
private const val MORE = "more"
private const val OUTPUT = "output"
private const val PODIO = "podio"
private const val STANDALONE_PODIO = "standalone-podio"

/** Player at the root; Up Next and More swipe away back to it, as every watch app does. */
@Composable
fun WearApp(link: PhoneLink, openStandaloneRequest: Int = 0) {
    val nav = rememberSwipeDismissableNavController()
    LaunchedEffect(openStandaloneRequest) {
        if (openStandaloneRequest > 0) nav.navigate(STANDALONE_PODIO) { launchSingleTop = true }
    }
    val ambient = LocalAmbient.current
    val np by link.nowPlaying.collectAsStateWithLifecycle()
    val showAmbient = ambient.active && np != null
    // Each Podio list and item page is its own destination, so Back walks back through them one at a time.
    val podioPlaces = remember { mutableStateMapOf<Int, PodioDest>() }
    var podioNext by remember { mutableIntStateOf(0) }
    val openPodio: (PodioDest) -> Unit = { dest ->
        val id = podioNext++
        podioPlaces[id] = dest
        nav.navigate("$PODIO/$id")
    }
    // No pill behind the clock: the disc and its orbit run right up to the bezel.
    AppScaffold(timeText = { TimeText(backgroundColor = Color.Transparent) }) {
        // Kept composed under the ambient player so waking up lands exactly
        // where it was. Alpha 0 is skipped at draw time, so it costs nothing
        // while the always-on screen is up.
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = if (showAmbient) 0f else 1f }) {
        SwipeDismissableNavHost(navController = nav, startDestination = PLAYER) {
            composable(PLAYER) {
                PlayerRoute(
                    link = link,
                    openQueue = { nav.navigate(QUEUE) },
                    openMore = { nav.navigate(MORE) },
                    openPodio = { nav.navigate(PODIO) },
                )
            }
            composable(QUEUE) {
                val queue by link.queue.collectAsStateWithLifecycle()
                QueueScreen(
                    queue = queue,
                    playingId = np?.mediaId,
                    onRequest = link::requestQueue,
                    onPlay = { index ->
                        link.skipTo(index)
                        nav.popBackStack()
                    },
                )
            }
            composable(MORE) {
                MoreScreen(np = np, link = link, onOpenOutput = { nav.navigate(OUTPUT) }, onOpenPodio = { nav.navigate(PODIO) })
            }
            composable(PODIO) {
                PodioScreen(link, PodioDest(), open = openPodio, back = { nav.popBackStack() },
                    onPlayer = { nav.popBackStack(PLAYER, inclusive = false) }, onOutput = { nav.navigate(OUTPUT) },
                    onStandalone = { nav.navigate(STANDALONE_PODIO) })
            }
            composable("$PODIO/{id}") { entry ->
                val dest = entry.arguments?.getString("id")?.toIntOrNull()?.let { podioPlaces[it] } ?: PodioDest()
                PodioScreen(link, dest, open = openPodio, back = { nav.popBackStack() },
                    onPlayer = { nav.popBackStack(PLAYER, inclusive = false) }, onOutput = { nav.navigate(OUTPUT) },
                    onStandalone = { nav.navigate(STANDALONE_PODIO) })
            }
            composable(STANDALONE_PODIO) {
                StandalonePodioScreen()
            }
            composable(OUTPUT) {
                // Straight back to the player once a choice is made.
                OutputScreen(link = link, onDone = { nav.popBackStack(PLAYER, inclusive = false) })
            }
        }
        }
        np?.takeIf { showAmbient }?.let { AmbientPlayer(it, ambient) }
    }
}

@Composable
private fun PlayerRoute(link: PhoneLink, openQueue: () -> Unit, openMore: () -> Unit, openPodio: () -> Unit) {
    val state by link.link.collectAsStateWithLifecycle()
    val heard by link.heard.collectAsStateWithLifecycle()
    val np by link.nowPlaying.collectAsStateWithLifecycle()
    val artwork by link.artwork.collectAsStateWithLifecycle()
    val volume by link.volume.collectAsStateWithLifecycle()
    val actions = remember(link) {
        PlayerActions(
            togglePlay = link::togglePlay,
            next = link::next,
            previous = link::previous,
            toggleLike = link::toggleLike,
            volume = link::adjustVolume,
            seekTo = link::seekTo,
            openQueue = openQueue,
            openMore = openMore,
        )
    }

    // Try again answers at once: the search for the phone is shown for a moment even when it fails straight away.
    var trying by remember { mutableStateOf(false) }
    LaunchedEffect(trying) {
        if (trying) {
            delay(2_500)
            trying = false
        }
    }
    ScreenScaffold {
        val playing = np
        when {
            // The last state the phone sent is still the best thing to show
            // while it is being asked again; only "no phone at all" replaces it.
            state == Link.NoPhone && trying -> Connecting("Looking for your phone…")
            state == Link.NoPhone -> NoPhone(onRetry = { trying = true; link.retry() }, onPodio = openPodio)
            playing != null -> NowPlayingScreen(np = playing, artwork = artwork, volume = volume, actions = actions)
            !heard -> Connecting()
            else -> NothingPlaying(onPodio = openPodio)
        }
    }
}

@Composable
private fun Connecting(text: String? = null) {
    Box(Modifier.fillMaxSize().background(WearOrbit.Ground), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(modifier = Modifier.size(32.dp))
            Spacer(Modifier.height(10.dp))
            Text(text ?: stringResource(R.string.connecting), style = WearType.subtitle, color = WearOrbit.Dim)
        }
    }
}

@Composable
private fun NoPhone(onRetry: () -> Unit, onPodio: () -> Unit) {
    Message(
        title = stringResource(R.string.no_phone_title),
        body = "Connect your phone, or stream public radio and podcasts on this watch.",
        action = stringResource(R.string.retry),
        onAction = onRetry,
        secondary = "Open Podio", onSecondary = onPodio,
        orbit = false,
    )
}

@Composable
private fun NothingPlaying(onPodio: () -> Unit) {
    Message(
        title = "Ready to listen",
        body = "Browse radio and podcasts on this watch or through your phone.",
        action = "Open Podio",
        onAction = onPodio,
        orbit = true,
    )
}

@Composable
private fun Message(title: String, body: String, action: String, onAction: () -> Unit, orbit: Boolean,
    secondary: String? = null, onSecondary: (() -> Unit)? = null) {
    Box(Modifier.fillMaxSize().background(WearOrbit.Ground), contentAlignment = Alignment.Center) {
        // Nothing playing: the orbit round the edge of the screen with no song on it yet.
        if (orbit) androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            drawOrbit(fraction = null, color = WearOrbit.Peach, stroke = size.minDimension * 0.017f, inset = size.minDimension * 0.033f)
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = wearEdgeInset() + WearTextInset),
        ) {
            if (!orbit && secondary == null) {
                Icon(
                    painter = painterResource(R.drawable.ic_phone),
                    contentDescription = null,
                    tint = WearOrbit.Dim,
                    modifier = Modifier.size(26.dp),
                )
                Spacer(Modifier.height(6.dp))
            }
            Text(title, style = WearType.title, color = WearOrbit.Ink, textAlign = TextAlign.Center)
            Spacer(Modifier.height(4.dp))
            Text(body, style = WearType.rowSub, color = WearOrbit.Dim, textAlign = TextAlign.Center)
            Spacer(Modifier.height(12.dp))
            Pill(text = action, onClick = onAction)
            if (secondary != null && onSecondary != null) { Spacer(Modifier.height(8.dp)); Pill(text = secondary, onClick = onSecondary, primary = false) }
        }
    }
}

/** A button as an Orbit chip: solid ink with the ground-coloured label when [primary], ink at 12 % otherwise. */
@Composable
private fun Pill(text: String, onClick: () -> Unit, primary: Boolean = true) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        Modifier
            .graphicsLayer {
                scaleX = if (pressed) 0.95f else 1f
                scaleY = if (pressed) 0.95f else 1f
            }
            .clip(CircleShape)
            .background(if (primary) WearOrbit.Ink else WearOrbit.Round)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 9.dp),
    ) {
        Text(text, style = WearType.button, color = if (primary) WearOrbit.OnInk else WearOrbit.Ink)
    }
}
