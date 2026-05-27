package com.suzuai.luminaris.app.ui.sidebar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.AutoMode
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.QuestionAnswer
import androidx.compose.material.icons.outlined.RateReview
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.ViewList
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.suzuai.luminaris.shared.theme.LuminarisColors

/**
 * Devin-style sidebar. Five primary entries (Sessions / Ask / Wiki / Review /
 * Automations) plus a hidden Setup tab that's only used to enter the backend
 * URL + token on first launch.
 */
enum class ClientTab(val label: String, val icon: ImageVector) {
    Sessions("Sessions", Icons.Outlined.ViewList),
    Ask("Ask", Icons.Outlined.QuestionAnswer),
    Wiki("Wiki", Icons.Outlined.MenuBook),
    Review("Review", Icons.Outlined.RateReview),
    Automations("Automations", Icons.Outlined.AutoMode),
    Setup("Connection", Icons.Outlined.Settings),
}

@Composable
fun ClientSidebar(
    current: ClientTab,
    canShowMain: Boolean,
    onPick: (ClientTab) -> Unit,
) {
    Column(Modifier.padding(top = 18.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.AutoAwesome,
                contentDescription = null,
                tint = LuminarisColors.AccentCyan,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                "Luminaris",
                color = LuminarisColors.AccentCyan,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            )
        }
        Text(
            "Devin-style agent",
            color = LuminarisColors.Muted,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(start = 18.dp, bottom = 12.dp),
        )

        Spacer(Modifier.height(4.dp))

        ClientTab.values().forEach { tab ->
            val enabled = canShowMain || tab == ClientTab.Setup
            SidebarRow(
                icon = tab.icon,
                label = tab.label,
                active = current == tab,
                enabled = enabled,
                onClick = { if (enabled) onPick(tab) },
            )
        }
    }
}

@Composable
private fun SidebarRow(
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
