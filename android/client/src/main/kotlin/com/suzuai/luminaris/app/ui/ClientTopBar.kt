package com.suzuai.luminaris.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.suzuai.luminaris.shared.theme.LuminarisColors

@Composable
fun ClientTopBar(title: String, subtitle: String, onMenu: () -> Unit) {
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
                "Luminaris",
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
