package com.suzuai.luminaris.panel.ui.backup

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.suzuai.luminaris.panel.PanelApp
import com.suzuai.luminaris.shared.theme.LuminarisColors
import com.suzuai.luminaris.shared.ui.LuminarisCard
import com.suzuai.luminaris.shared.ui.SectionHeader
import com.suzuai.luminaris.shared.ui.StatusPill
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Backup / Transfer screen.
 *
 * Export hands off to Android DownloadManager so the file lands in Downloads/.
 * Import uses Storage Access Framework — user picks a zip, we POST it as
 * multipart to /v1/admin/backup/import.
 *
 * After import, panel forgets the local URL+token so the user must re-enter
 * them (since the new bundle might point at a different deployment).
 */
@Composable
fun BackupScreen() {
    val app = PanelApp.instance
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val (url, token) = app.store.pair.collectAsState(initial = "" to "").value

    var status by remember { mutableStateOf<String?>(null) }
    var ok by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null || url.isBlank() || token.isBlank()) return@rememberLauncherForActivityResult
        busy = true; status = null
        scope.launch {
            runCatching {
                val bytes = withContext(Dispatchers.IO) {
                    ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                }
                uploadBackup(url, token, bytes)
            }.onSuccess {
                ok = true
                status = "Restored: $it"
            }.onFailure {
                ok = false
                status = "Import failed: ${it.message}"
            }
            busy = false
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(LuminarisColors.Bg)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        LuminarisCard {
            Column {
                SectionHeader("Export bundle")
                Text(
                    "Download a single zip containing every byte of state: SSH + AI provider " +
                        "config, all sessions and messages, every signed APK artifact. Keep " +
                        "this file somewhere safe — it contains your secrets.",
                    color = LuminarisColors.TextDim,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    enabled = !busy && url.isNotBlank() && token.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = LuminarisColors.AccentCyan,
                        contentColor = androidx.compose.ui.graphics.Color.Black,
                    ),
                    onClick = { downloadBundle(ctx, url, token) },
                ) {
                    Text("Download luminaris-bundle.zip", fontWeight = FontWeight.SemiBold)
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        LuminarisCard {
            Column {
                SectionHeader("Import bundle")
                Text(
                    "Restore a previously exported bundle into the currently connected " +
                        "backend. This REPLACES every existing session, config and APK on the " +
                        "server. Tap the button, pick a .zip from your phone.",
                    color = LuminarisColors.TextDim,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = !busy && url.isNotBlank() && token.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = LuminarisColors.SurfaceAlt,
                            contentColor = LuminarisColors.AccentCyan,
                        ),
                        onClick = { picker.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) },
                    ) { Text(if (busy) "Uploading…" else "Pick & import .zip", fontWeight = FontWeight.SemiBold) }
                }
                if (status != null) {
                    Spacer(Modifier.height(10.dp))
                    StatusPill(ok = ok, label = status!!)
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        LuminarisCard {
            Column {
                SectionHeader("Clone to a new server")
                Text(
                    "Provision a fresh Ubuntu 22.04 VPS, SSH in as root and run:",
                    color = LuminarisColors.TextDim,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "git clone https://github.com/hairunnizam21/suzu_ai_luminaris.git\n" +
                        "cd suzu_ai_luminaris/server\n" +
                        "./install.sh\n" +
                        "./run.sh",
                    color = LuminarisColors.Text,
                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Then point this panel app at the new URL, paste the SUZU_TOKEN printed " +
                        "by install.sh, and use “Import bundle” above to restore your work.",
                    color = LuminarisColors.TextDim,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

private fun downloadBundle(ctx: Context, url: String, token: String) {
    val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    val req = DownloadManager.Request(Uri.parse("$url/v1/admin/backup/export"))
        .addRequestHeader("Authorization", "Bearer $token")
        .setTitle("luminaris-bundle.zip")
        .setDescription("SuzuAI Luminaris backup")
        .setMimeType("application/zip")
        .setDestinationInExternalPublicDir(
            Environment.DIRECTORY_DOWNLOADS,
            "luminaris-bundle-${System.currentTimeMillis()}.zip",
        )
        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        .setAllowedOverMetered(true)
    dm.enqueue(req)
}

private suspend fun uploadBackup(url: String, token: String, bytes: ByteArray): String =
    withContext(Dispatchers.IO) {
        val client = OkHttpClient.Builder().build()
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                "bundle.zip",
                bytes.toRequestBody("application/zip".toMediaType()),
            )
            .build()
        val req = Request.Builder()
            .url("$url/v1/admin/backup/import")
            .header("Authorization", "Bearer $token")
            .post(body)
            .build()
        client.newCall(req).execute().use { resp ->
            val txt = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) error("HTTP ${resp.code}: ${txt.take(200)}")
            txt
        }
    }
