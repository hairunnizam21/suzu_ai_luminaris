package com.suzuai.luminaris.panel.ui.apks

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.suzuai.luminaris.panel.PanelApp
import com.suzuai.luminaris.shared.data.ApkArtifact
import com.suzuai.luminaris.shared.net.BackendClient
import com.suzuai.luminaris.shared.theme.LuminarisColors
import com.suzuai.luminaris.shared.ui.LuminarisCard
import com.suzuai.luminaris.shared.ui.SectionHeader
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Shows every APK the agent has decompiled / recompiled / signed on the server.
 * Each row has Download (DownloadManager → Downloads/), Install (ACTION_VIEW
 * → system installer), and Share buttons.
 */
@Composable
fun ApksScreen() {
    val app = PanelApp.instance
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val (url, token) = app.store.pair.collectAsState(initial = "" to "").value
    val client = remember(url, token) { BackendClient(url, token) }

    var items by remember { mutableStateOf<List<ApkArtifact>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(url, token) {
        if (url.isBlank() || token.isBlank()) return@LaunchedEffect
        loading = true
        runCatching { client.listApks() }
            .onSuccess { items = it; error = null }
            .onFailure { error = "Failed: ${it.message}" }
        loading = false
    }

    Column(
        Modifier.fillMaxSize().background(LuminarisColors.Bg).padding(16.dp),
    ) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            SectionHeader("Rebuilt / decompiled APKs")
            Spacer(Modifier.weight(1f))
            Button(
                colors = ButtonDefaults.buttonColors(
                    containerColor = LuminarisColors.SurfaceAlt,
                    contentColor = LuminarisColors.AccentCyan,
                ),
                onClick = {
                    scope.launch {
                        loading = true
                        runCatching { client.listApks() }
                            .onSuccess { items = it; error = null }
                            .onFailure { error = "Refresh failed: ${it.message}" }
                        loading = false
                    }
                },
            ) { Text("Refresh", style = MaterialTheme.typography.labelMedium) }
        }
        Spacer(Modifier.height(8.dp))

        when {
            loading && items.isEmpty() -> Text("Loading…", color = LuminarisColors.TextDim)
            error != null && items.isEmpty() -> Text(error!!, color = LuminarisColors.AccentRed)
            items.isEmpty() -> Text(
                "No APK artifacts yet. The agent appends to this list whenever it runs " +
                    "apk_decompile, apk_recompile, or apk_sign.",
                color = LuminarisColors.TextDim,
                style = MaterialTheme.typography.bodyMedium,
            )
            else -> LazyColumn(
                contentPadding = PaddingValues(vertical = 4.dp),
            ) {
                items(items) { artifact ->
                    ApkRow(
                        artifact = artifact,
                        downloadUrl = client.apkDownloadUrl(artifact.id),
                        token = token,
                        ctx = ctx,
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
private fun ApkRow(
    artifact: ApkArtifact,
    downloadUrl: String,
    token: String,
    ctx: Context,
) {
    val date = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        .format(Date((artifact.createdAt * 1000).toLong()))

    LuminarisCard {
        Column {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(
                    artifact.name,
                    color = LuminarisColors.Text,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "${(artifact.size / 1024.0).toInt()} KB",
                    color = LuminarisColors.TextDim,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            Text(
                "${artifact.kind} • $date",
                color = LuminarisColors.TextDim,
                style = MaterialTheme.typography.labelSmall,
            )
            Spacer(Modifier.height(10.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(
                    colors = ButtonDefaults.buttonColors(
                        containerColor = LuminarisColors.AccentCyan,
                        contentColor = androidx.compose.ui.graphics.Color.Black,
                    ),
                    onClick = { downloadApk(ctx, artifact.name, downloadUrl, token) },
                ) { Text("Download", style = MaterialTheme.typography.labelMedium) }
                Button(
                    colors = ButtonDefaults.buttonColors(
                        containerColor = LuminarisColors.SurfaceAlt,
                        contentColor = LuminarisColors.Text,
                    ),
                    onClick = { openExternal(ctx, downloadUrl, token) },
                ) { Text("Open in browser", style = MaterialTheme.typography.labelMedium) }
                Button(
                    colors = ButtonDefaults.buttonColors(
                        containerColor = LuminarisColors.SurfaceAlt,
                        contentColor = LuminarisColors.Text,
                    ),
                    onClick = { shareLink(ctx, downloadUrl, token) },
                ) { Text("Share", style = MaterialTheme.typography.labelMedium) }
            }
        }
    }
}

private fun downloadApk(ctx: Context, name: String, downloadUrl: String, token: String) {
    val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    val req = DownloadManager.Request(Uri.parse(downloadUrl))
        .addRequestHeader("Authorization", "Bearer $token")
        .setTitle(name)
        .setDescription("SuzuAI Luminaris APK artifact")
        .setMimeType("application/vnd.android.package-archive")
        .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        .setAllowedOverMetered(true)
    dm.enqueue(req)
}

private fun openExternal(ctx: Context, downloadUrl: String, @Suppress("UNUSED_PARAMETER") token: String) {
    // We can't inject Authorization into ACTION_VIEW. Open via the share sheet
    // with the URL — backend rejects unauthenticated GETs but the user can paste
    // the URL into a curl/wget with their token. For one-tap install rely on
    // DownloadManager + the system installer.
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl))
    runCatching { ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

private fun shareLink(ctx: Context, downloadUrl: String, @Suppress("UNUSED_PARAMETER") token: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, downloadUrl)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    ctx.startActivity(Intent.createChooser(intent, "Share APK link"))
}
