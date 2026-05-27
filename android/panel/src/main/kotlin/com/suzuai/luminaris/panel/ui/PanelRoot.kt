package com.suzuai.luminaris.panel.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.suzuai.luminaris.panel.PanelApp
import com.suzuai.luminaris.panel.ui.apks.ApksScreen
import com.suzuai.luminaris.panel.ui.backup.BackupScreen
import com.suzuai.luminaris.panel.ui.config.ConfigScreen
import com.suzuai.luminaris.panel.ui.logs.LogsScreen
import com.suzuai.luminaris.panel.ui.setup.SetupScreen
import com.suzuai.luminaris.shared.theme.LuminarisColors
import kotlinx.coroutines.launch

private enum class PanelDest(val label: String, val icon: ImageVector) {
    Config("Server config", Icons.Outlined.Tune),
    Logs("Live logs", Icons.Outlined.Description),
    Apks("APK artifacts", Icons.Outlined.FolderZip),
    Backup("Backup / Transfer", Icons.Outlined.Backup),
    Setup("Connection", Icons.Outlined.Settings),
}

@Composable
fun PanelRoot() {
    val app = PanelApp.instance
    val (url, token) = app.store.pair.collectAsState(initial = "" to "").value

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    val configured = url.isNotBlank() && token.isNotBlank()
    var current by remember(configured) {
        mutableStateOf(if (configured) PanelDest.Config else PanelDest.Setup)
    }

    LaunchedEffect(configured) {
        if (!configured) current = PanelDest.Setup
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = LuminarisColors.Surface,
                drawerTonalElevation = 0.dp,
                modifier = Modifier.fillMaxWidth(0.82f),
                drawerShape = RoundedCornerShape(topEnd = 16.dp, bottomEnd = 16.dp),
            ) {
                DrawerContent(
                    current = current,
                    canShowMain = configured,
                    onPick = {
                        current = it
                        scope.launch { drawerState.close() }
                    },
                )
            }
        },
    ) {
        Column(Modifier.fillMaxSize().background(LuminarisColors.Bg)) {
            PanelTopBar(
                title = current.label,
                subtitle = if (configured) url else "Not connected",
                onMenu = { scope.launch { drawerState.open() } },
            )
            Box(Modifier.fillMaxSize()) {
                when (current) {
                    PanelDest.Setup -> SetupScreen(
                        onSaved = { current = PanelDest.Config },
                    )
                    PanelDest.Config -> ConfigScreen()
                    PanelDest.Logs -> LogsScreen()
                    PanelDest.Apks -> ApksScreen()
                    PanelDest.Backup -> BackupScreen()
                }
            }
        }
    }
}

@Composable
private fun PanelTopBar(title: String, subtitle: String, onMenu: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(LuminarisColors.Surface)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onMenu) {
            Icon(Icons.Outlined.Menu, contentDescription = "Menu", tint = LuminarisColors.Text)
        }
        Spacer(Modifier.width(4.dp))
        Column {
            Text(
                "Luminaris Panel",
                color = LuminarisColors.AccentCyan,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
            )
            Text(
                "$title  •  $subtitle",
                color = LuminarisColors.TextDim,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(LuminarisColors.Divider))
}

@Composable
private fun DrawerContent(
    current: PanelDest,
    canShowMain: Boolean,
    onPick: (PanelDest) -> Unit,
) {
    Column(Modifier.padding(top = 18.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Suzu",
                color = LuminarisColors.AccentCyan,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "panel",
                color = LuminarisColors.Muted,
                style = MaterialTheme.typography.labelMedium,
            )
        }
        Spacer(Modifier.height(8.dp))

        PanelDest.values().forEach { dest ->
            val enabled = canShowMain || dest == PanelDest.Setup
            DrawerRow(
                icon = dest.icon,
                label = dest.label,
                active = current == dest,
                enabled = enabled,
                onClick = { if (enabled) onPick(dest) },
            )
        }
    }
}

@Composable
private fun DrawerRow(
    icon: ImageVector,
    label: String,
    active: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val bg = if (active) LuminarisColors.SurfaceAlt else LuminarisColors.Surface
    val tint = when {
        !enabled -> LuminarisColors.Muted
        active -> LuminarisColors.AccentCyan
        else -> LuminarisColors.Text
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .let { if (enabled) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(
            label,
            color = tint,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
        )
    }
}
