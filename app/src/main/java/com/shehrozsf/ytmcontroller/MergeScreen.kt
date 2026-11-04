package com.shehrozsf.ytmcontroller

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
fun MergeScreen(signedIn: Boolean, onSignIn: () -> Unit) {
    if (!signedIn) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                "Sign in to read your playlists. Access is read-only; nothing is changed in your account.",
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = onSignIn) { Text("Sign in with Google") }
        }
        return
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var playlists by remember { mutableStateOf<List<PlaylistInfo>?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val selected = remember { mutableStateListOf<String>() }
    val status by MergePlayer.status.collectAsState()

    LaunchedEffect(Unit) {
        try {
            val token = GoogleAuth.token(context) ?: error("Signed out, sign in again")
            playlists = YouTubeApi.myPlaylists(token)
        } catch (e: Exception) {
            problem = e.message ?: e.toString()
        }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
        item {
            Button(
                enabled = selected.isNotEmpty() && !busy,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    busy = true
                    problem = null
                    scope.launch {
                        try {
                            val token = GoogleAuth.token(context) ?: error("Signed out, sign in again")
                            val tracks = selected.toList().flatMap { YouTubeApi.playlistTracks(token, it) }
                            MergePlayer.play(tracks)
                        } catch (e: Exception) {
                            problem = e.message ?: e.toString()
                        } finally {
                            busy = false
                        }
                    }
                },
            ) { Text(if (busy) "Loading songs…" else "Shuffle & play (${selected.size} selected)") }
        }
        status?.let { s ->
            item {
                Column(Modifier.padding(vertical = 8.dp)) {
                    Text(s, style = MaterialTheme.typography.bodyMedium)
                    Row {
                        if (MergePlayer.hasNextBatch) {
                            TextButton(onClick = {
                                scope.launch { runCatching { MergePlayer.playNextBatch() } }
                            }) { Text("Next batch") }
                        }
                        TextButton(onClick = MergePlayer::stop) { Text("Stop merge") }
                    }
                }
            }
        }
        problem?.let {
            item { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp)) }
        }
        val list = playlists
        if (list == null && problem == null) {
            item { Text("Loading playlists…", modifier = Modifier.padding(vertical = 8.dp)) }
        }
        items(list.orEmpty()) { p ->
            val checked = p.id in selected
            ListItem(
                headlineContent = { Text(p.title) },
                supportingContent = p.itemCount?.let { { Text("$it songs") } },
                leadingContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = checked, onCheckedChange = null)
                        Spacer(Modifier.width(12.dp))
                        Thumbnail(bitmap = null, uri = p.thumbnailUrl, size = 56.dp)
                    }
                },
                modifier = Modifier.clickable { if (checked) selected.remove(p.id) else selected.add(p.id) },
            )
        }
    }
}
