package com.suzuai.luminaris.shared.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.suzuai.luminaris.shared.theme.LuminarisColors

/**
 * A flat dark card with a 1dp cyan-tinted outline. Used everywhere because
 * Material's default cards are too soft for the Luminaris look.
 */
@Composable
fun LuminarisCard(
    modifier: Modifier = Modifier,
    padding: Dp = 14.dp,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(LuminarisColors.Surface)
            .border(1.dp, LuminarisColors.Outline, RoundedCornerShape(12.dp))
            .padding(padding),
    ) { content() }
}

/** A coloured status dot + label, used for "ready / not ready" rows. */
@Composable
fun StatusPill(ok: Boolean, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(50))
                .background(if (ok) LuminarisColors.AccentGreen else LuminarisColors.AccentRed),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            label,
            color = if (ok) LuminarisColors.AccentGreen else LuminarisColors.AccentRed,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
        )
    }
}

/** Section header used inside cards. */
@Composable
fun SectionHeader(title: String) {
    Text(
        text = title,
        color = LuminarisColors.AccentCyan,
        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

/** A thin horizontal divider matching Luminaris outlines. */
@Composable
fun HairlineDivider(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(LuminarisColors.Divider),
    )
}
