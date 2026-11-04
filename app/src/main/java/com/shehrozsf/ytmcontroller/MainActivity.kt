package com.shehrozsf.ytmcontroller

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.NotificationListenerService
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var hasAccess by mutableStateOf(false)
    private var signedIn by mutableStateOf(false)
    /** Keys the services rejected at startup (missing keys are caught without a request). */
    private var invalidKeys by mutableStateOf<List<String>>(emptyList())

    private val consent = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        signedIn = runCatching { GoogleAuth.tokenFromConsent(this, it.data) }.getOrNull() != null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Caches.install(this)
        val appContext = applicationContext
        VideoIdLookup.accessToken = { GoogleAuth.token(appContext) }
        lifecycleScope.launch {
            signedIn = runCatching { GoogleAuth.token(appContext) }.getOrNull() != null
        }
        handleShare(intent)
        // Test present keys once per launch. Offline / unreachable gives no verdict, so it
        // never blocks; only an explicit rejection from the service does.
        if (LastFm.hasKey && Holodex.hasKey) lifecycleScope.launch {
            val lastFm = async { LastFm.checkKey() }
            val holodex = async { Holodex.checkKey() }
            invalidKeys = listOfNotNull(
                "lastfm.apiKey".takeIf { lastFm.await() == KeyCheck.INVALID },
                "holodex.apiKey".takeIf { holodex.await() == KeyCheck.INVALID },
            )
        }

        setContent {
            YtmTheme {
                Surface(Modifier.fillMaxSize()) {
                    when {
                        // Built without a key, or a service rejected one: say so; closing the
                        // message closes the app.
                        !LastFm.hasKey || !Holodex.hasKey || invalidKeys.isNotEmpty() ->
                            KeysMissingDialog(invalidKeys, onClose = ::finishAndRemoveTask)
                        hasAccess -> MainScreen(signedIn, ::signIn)
                        else -> AccessScreen(::openAccessSettings)
                    }
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        Caches.flush()
    }

    /** Android is short on memory: let go of decoded thumbnails (they reload from disk). */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        ArtworkLoader.trim(level)
    }

    /** singleTop: a share while the app is open arrives here instead of a new window. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShare(intent)
    }

    /** A link shared from YouTube / YTM opens the add-to-Up-next dialog. */
    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND || intent.type != "text/plain") return
        intent.getStringExtra(Intent.EXTRA_TEXT)?.let(QueueEditor::offerShared)
    }

    private fun signIn() {
        lifecycleScope.launch {
            val result = runCatching { GoogleAuth.authorize(this@MainActivity) }.getOrNull() ?: return@launch
            val pending = result.pendingIntent
            if (result.hasResolution() && pending != null) {
                consent.launch(IntentSenderRequest.Builder(pending.intentSender).build())
            } else {
                signedIn = result.accessToken != null
            }
        }
    }

    override fun onResume() {
        super.onResume()
        hasAccess = packageName in NotificationManagerCompat.getEnabledListenerPackages(this)
        // The system often doesn't rebind the listener after a reinstall; nudge it.
        if (hasAccess) {
            NotificationListenerService.requestRebind(ComponentName(this, MediaListenerService::class.java))
        }
    }

    private fun openAccessSettings() {
        val self = ComponentName(this, MediaListenerService::class.java)
        startActivity(
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, self.flattenToString())
        )
    }
}

/**
 * Shown when the app was built without an API key, or a service rejected one. Every way of
 * closing it (the button, Back, tapping outside) closes the app; keys are compiled in, so
 * only fixing secrets.properties and rebuilding helps.
 */
@Composable
private fun KeysMissingDialog(invalid: List<String>, onClose: () -> Unit) {
    val missing = listOfNotNull(
        "lastfm.apiKey".takeIf { !LastFm.hasKey },
        "holodex.apiKey".takeIf { !Holodex.hasKey },
    )
    val problems = listOfNotNull(
        missing.takeIf { it.isNotEmpty() }?.let { "This build has no ${it.joinToString(" or ")}." },
        invalid.takeIf { it.isNotEmpty() }?.let { "The service rejected ${it.joinToString(" and ")} as invalid." },
    )
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Keys are missing") },
        text = {
            Text(
                problems.joinToString(" ") + " Fix secrets.properties in the project folder and rebuild the app.",
            )
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close app") } },
    )
}

@Composable
private fun AccessScreen(onGrant: () -> Unit) {
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "To see and control what YouTube Music is playing, this app needs notification access.",
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onGrant) { Text("Grant access") }
    }
}

@Composable
private fun MainScreen(signedIn: Boolean, onSignIn: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    var linkDialog by remember { mutableStateOf<LinkDialog?>(null) }
    // A link shared from YouTube / YTM opens the dialog on Now playing.
    val shared by QueueEditor.shared.collectAsState()
    LaunchedEffect(shared) {
        val text = shared ?: return@LaunchedEffect
        QueueEditor.consumeShared()
        tab = 0
        linkDialog = LinkDialog(at = null, afterTitle = null, text = text)
    }
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        PrimaryTabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Now playing") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Merge") })
        }
        when (tab) {
            0 -> NowPlayingScreen(onAddLink = { linkDialog = it })
            else -> MergeScreen(signedIn, onSignIn)
        }
    }
    linkDialog?.let { AddLinkDialog(it, signedIn, onDismiss = { linkDialog = null }) }
}

/** An open add-link dialog. [at] null = let the user pick Play next / End of Up next. */
private data class LinkDialog(val at: InsertAt?, val afterTitle: String?, val text: String = "")

@Composable
private fun AddLinkDialog(request: LinkDialog, signedIn: Boolean, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(request.text) }
    val scope = rememberCoroutineScope()
    // "Choose from my playlists": the signed-in user's own playlists, private ones and Liked
    // songs included (those have no shareable link, or one that only works for its owner).
    var picking by remember { mutableStateOf(false) }
    var myPlaylists by remember { mutableStateOf<List<PlaylistInfo>?>(null) }
    var pickError by remember { mutableStateOf<String?>(null) }
    val target = remember(text) { QueueEditor.parseLink(text) }
    // A link can point at a song inside a playlist; then the user picks which they meant.
    val canChoose = target?.videoId != null && target.hasPlaylist
    var wholePlaylistChoice by remember(target) { mutableStateOf(false) }
    val wholePlaylist = if (canChoose) wholePlaylistChoice else target?.videoId == null
    var at by remember { mutableStateOf(request.at ?: InsertAt.Next) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add song or playlist") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Song or playlist link") },
                    placeholder = { Text("music.youtube.com/watch?v=… or /playlist?list=…") },
                    supportingText = { Text("YouTube or YT Music. A playlist adds all its songs (up to 50).") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                when {
                    !signedIn -> Text(
                        "To add your private playlists or Liked songs, sign in on the Merge tab.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    !picking -> TextButton(onClick = {
                        picking = true
                        pickError = null
                        // Already loaded once in this dialog: just show it again.
                        if (myPlaylists == null) scope.launch {
                            try {
                                val token = VideoIdLookup.accessToken?.invoke() ?: error("not signed in")
                                myPlaylists = YouTubeApi.myPlaylists(token)
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                pickError = "Couldn't load your playlists: ${e.message}"
                            }
                        }
                    }) { Text("Choose from my playlists (incl. private)") }
                    pickError != null -> Text(pickError!!, color = MaterialTheme.colorScheme.error)
                    myPlaylists == null -> Text("Loading your playlists…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    // Plain scrolling Column: a LazyColumn inside AlertDialog crashes (intrinsic measurement).
                    else -> Column(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
                        myPlaylists!!.forEach { p ->
                            Row(
                                Modifier.fillMaxWidth()
                                    .clickable {
                                        text = "https://music.youtube.com/playlist?list=${p.id}"
                                        picking = false
                                    }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Thumbnail(bitmap = null, uri = p.thumbnailUrl, size = 36.dp)
                                Text(
                                    p.title + (p.itemCount?.let { " · $it" } ?: ""),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
                when {
                    text.isBlank() -> Unit
                    target == null -> Text("Not a YouTube song or playlist link", color = MaterialTheme.colorScheme.error)
                    canChoose -> SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        SegmentedButton(
                            selected = !wholePlaylistChoice,
                            onClick = { wholePlaylistChoice = false },
                            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                        ) { Text("This song") }
                        SegmentedButton(
                            selected = wholePlaylistChoice,
                            onClick = { wholePlaylistChoice = true },
                            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                        ) { Text("Whole playlist") }
                    }
                    else -> Text(if (wholePlaylist) "Playlist link" else "Song link", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (request.at == null) {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        SegmentedButton(
                            selected = at == InsertAt.Next,
                            onClick = { at = InsertAt.Next },
                            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                        ) { Text("Play next") }
                        SegmentedButton(
                            selected = at == InsertAt.End,
                            onClick = { at = InsertAt.End },
                            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                        ) { Text("End of Up next") }
                    }
                } else {
                    Text("After “${request.afterTitle}”", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    "The current song restarts for a moment while the queue is rebuilt.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = target != null,
                onClick = {
                    target?.let { QueueEditor.insert(it, wholePlaylist, at) }
                    onDismiss()
                },
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NowPlayingScreen(onAddLink: (LinkDialog) -> Unit) {
    val track by NowPlaying.track.collectAsState()
    val playback by NowPlaying.playback.collectAsState()
    val queue by NowPlaying.queue.collectAsState()
    val queueStatus by QueueEditor.status.collectAsState()
    val queueBusy by QueueEditor.busy.collectAsState()

    val t = track
    if (t == null) {
        NothingPlaying()
        return
    }

    val activeId = playback?.activeQueueId
    val upNext = remember(queue, activeId) {
        val i = queue.indexOfFirst { it.id == activeId }
        if (i >= 0) queue.drop(i + 1) else queue
    }

    var showSuggestions by rememberSaveable { mutableStateOf(false) }
    // Loaded only when the Suggestions tab is open, once per song: SuggestionFeed caches each
    // song's list, so switching tabs or screens shows it instantly. Errors aren't cached and retry.
    var suggestions by remember(t.title, t.artist) { mutableStateOf(SuggestionFeed.cached(t.title, t.artist)) }
    var suggestionsError by remember(t.title, t.artist) { mutableStateOf<String?>(null) }
    LaunchedEffect(showSuggestions, t.title, t.artist) {
        if (showSuggestions && suggestions == null) {
            suggestionsError = null
            try {
                suggestions = SuggestionFeed.load(t.title, t.artist, t.durationMs)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e // song changed or screen left mid-load; not an error
            } catch (e: Exception) {
                suggestionsError = "Couldn't load suggestions: ${e.message}"
            }
        }
    }
    val scope = rememberCoroutineScope()
    var playMessage by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item { Player(t, playback) }
        item {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 32.dp, bottom = 8.dp)) {
                SegmentedButton(
                    selected = !showSuggestions,
                    onClick = { showSuggestions = false },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                ) { Text("Up next") }
                SegmentedButton(
                    selected = showSuggestions,
                    onClick = { showSuggestions = true },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                ) { Text("Suggestions") }
            }
        }
        if (!showSuggestions) {
            item {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Add a song or a whole playlist by link. Long-press a song to insert after it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(enabled = !queueBusy, onClick = { onAddLink(LinkDialog(at = null, afterTitle = null)) }) {
                        Text("Add song / playlist")
                    }
                }
            }
            queueStatus?.let { item { ListNote(it) } }
            if (upNext.isEmpty()) item { ListNote("Nothing queued") }
            items(upNext) { entry ->
                ListItem(
                    headlineContent = { Text(entry.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = entry.subtitle?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
                    leadingContent = { Thumbnail(entry.art, entry.artUri) },
                    modifier = Modifier.combinedClickable(
                        onClick = { NowPlaying.jumpTo(entry.id) },
                        onLongClick = {
                            if (!queueBusy) onAddLink(LinkDialog(at = InsertAt.After(entry.id), afterTitle = entry.title))
                        },
                    ),
                )
            }
        } else {
            val result = suggestions
            when {
                suggestionsError != null -> item { ListNote(suggestionsError!!) }
                result == null -> item { ListNote("Loading suggestions…") }
                result.items.isEmpty() -> item { ListNote("No suggestions for this song. ${result.basis}") }
                // Where the list came from (and how many rows each source gave), so an
                // identical list for two songs by the same artist reads as expected, not stale.
                else -> item { ListNote(result.basis) }
            }
            playMessage?.let { item { ListNote(it) } }
            items(result?.items.orEmpty(), key = { "${it.artist}\u0000${it.title}" }) { s ->
                ListItem(
                    headlineContent = { Text(s.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = {
                        Text(
                            "${s.artist} · ${s.sources.joinToString(" + ") { it.label }}",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    leadingContent = { SuggestionThumbnail(s) },
                    modifier = Modifier.clickable {
                        // YTM rows carry their video ID; Last.fm rows need a search first.
                        val known = s.videoId?.takeIf(::isValidVideoId)
                        if (known != null) {
                            playMessage = null
                            NowPlaying.playSong(known)
                            return@clickable
                        }
                        playMessage = "Finding “${s.title}”…"
                        scope.launch {
                            // Last.fm has no lengths, so match on title alone (durationMs = 0).
                            val id = VideoIdLookup.find(s.title, s.artist, durationMs = 0)
                            playMessage = if (id == null) "Couldn't find “${s.title}” on YouTube" else null
                            id?.let(NowPlaying::playSong)
                        }
                    },
                )
            }
        }
    }
}

/**
 * The row's own image (always there for YTM rows, rarely for Last.fm), else the album cover
 * looked up on Last.fm. Rows load lazily, so only songs that scroll into view cost a request.
 */
@Composable
private fun SuggestionThumbnail(s: Suggestion) {
    // Known answers (list image or an earlier lookup) apply on the first frame: no flicker.
    val known = s.imageUrl?.let(Cover::Found) ?: LastFm.cachedCover(s.title, s.artist)
    val cover by produceState(known, s) {
        value = known ?: LastFm.coverArt(s.title, s.artist)
    }
    // null = still asking Last.fm, which shows as loading, same as downloading the image.
    Thumbnail(bitmap = null, uri = (cover as? Cover.Found)?.url, pending = cover == null)
}

@Composable
private fun ListNote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(16.dp),
    )
}

@Composable
private fun NothingPlaying() {
    val status by NowPlaying.status.collectAsState()
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Nothing playing in YouTube Music", style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            status,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** Album-art colours (or the theme's, when there's no art), animated between tracks. */
private class AnimatedPlaybarColors(
    val background: Color,
    val onBackground: Color,
    val accent: Color,
)

@Composable
private fun animatedPlaybarColors(): AnimatedPlaybarColors {
    val art by NowPlaying.playbarColors.collectAsState()
    val theme = MaterialTheme.colorScheme
    val spec = tween<Color>(durationMillis = 600)
    // Without art: a raised dark-grey card with YTM's red progress bar.
    val background by animateColorAsState(art?.background ?: theme.surfaceContainerHigh, spec, label = "playbarBackground")
    val onBackground by animateColorAsState(art?.onBackground ?: theme.onSurface, spec, label = "playbarOnBackground")
    val accent by animateColorAsState(art?.accent ?: YtmColors.Red, spec, label = "playbarAccent")
    return AnimatedPlaybarColors(background, onBackground, accent)
}

@Composable
private fun Player(t: Track, playback: Playback?) {
    val colors = animatedPlaybarColors()
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(colors.background)
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        t.art?.let {
            Image(
                it.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(280.dp).clip(RoundedCornerShape(16.dp)),
            )
            Spacer(Modifier.height(24.dp))
        }
        Text(
            t.title,
            style = MaterialTheme.typography.headlineSmall,
            color = colors.onBackground,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            t.artist,
            style = MaterialTheme.typography.bodyLarge,
            color = colors.onBackground.copy(alpha = 0.72f),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Progress(playback, t.durationMs, colors)
        Spacer(Modifier.height(16.dp))
        // System-player style: every control is content-coloured on a translucent circle.
        val control = ButtonDefaults.filledTonalButtonColors(
            containerColor = colors.onBackground.copy(alpha = 0.15f),
            contentColor = colors.onBackground,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FilledTonalButton(onClick = NowPlaying::previous, colors = control) { Text("Prev") }
            FilledTonalButton(onClick = NowPlaying::playPause, colors = control) {
                Text(if (playback?.isPlaying == true) "Pause" else "Play")
            }
            FilledTonalButton(onClick = NowPlaying::next, colors = control) { Text("Next") }
        }
        GenerateRadio(t, colors)
    }
}

@Composable
private fun GenerateRadio(t: Track, colors: AnimatedPlaybarColors) {
    val scope = rememberCoroutineScope()
    var message by remember(t.title, t.artist) { mutableStateOf<String?>(null) }

    TextButton(colors = ButtonDefaults.textButtonColors(contentColor = colors.onBackground), onClick = {
        message = "Finding song…"
        scope.launch {
            val id = VideoIdLookup.find(t.title, t.artist, t.durationMs)
            message = when {
                id == null -> "Couldn't find this song on YouTube"
                NowPlaying.isOnRadioOf(id) -> "Already playing this song's radio"
                else -> {
                    NowPlaying.startRadio(id)
                    null
                }
            }
        }
    }) { Text("Generate Radio") }
    message?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onBackground.copy(alpha = 0.72f))
    }
}

@Composable
private fun Progress(playback: Playback?, durationMs: Long, colors: AnimatedPlaybarColors) {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(playback) {
        while (playback?.isPlaying == true) {
            now = SystemClock.elapsedRealtime()
            delay(500)
        }
    }
    val position = (playback?.positionAt(now) ?: 0L).coerceIn(0L, durationMs.coerceAtLeast(0L))

    Column(Modifier.fillMaxWidth()) {
        SeekBar(
            fraction = if (durationMs > 0) position.toFloat() / durationMs else 0f,
            color = colors.accent,
            // One UI's unplayed track is a fixed #66FCFCFC (white at 40%).
            trackColor = colors.onBackground.copy(alpha = 0.4f),
        )
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatTime(position), style = MaterialTheme.typography.labelMedium, color = colors.onBackground)
            Text(formatTime(durationMs), style = MaterialTheme.typography.labelMedium, color = colors.onBackground)
        }
    }
}

/**
 * A plain rounded bar: no end-of-track dot and no gap between played and unplayed parts.
 * The played part gets a thin dark outline so it stays visible when the preset colour is
 * close to the background.
 */
@Composable
private fun SeekBar(fraction: Float, color: Color, trackColor: Color) {
    Canvas(Modifier.fillMaxWidth().height(6.dp)) {
        val radius = CornerRadius(size.height / 2)
        drawRoundRect(trackColor, cornerRadius = radius)
        val played = size.width * fraction.coerceIn(0f, 1f)
        if (played > 0f) {
            // Never narrower than the bar is tall, so the start reads as a rounded pill.
            val bar = Size(played.coerceAtLeast(size.height), size.height)
            drawRoundRect(color, size = bar, cornerRadius = radius)
            val stroke = 1.dp.toPx()
            drawRoundRect(
                Color.Black.copy(alpha = 0.35f),
                topLeft = Offset(stroke / 2, stroke / 2),
                size = Size(bar.width - stroke, bar.height - stroke),
                cornerRadius = CornerRadius(radius.x - stroke / 2),
                style = Stroke(stroke),
            )
        }
    }
}

private fun formatTime(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}
