package dev.digitalgnosis.dispatch.ui.components.cards

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.digitalgnosis.dispatch.data.PulsePost
import dev.digitalgnosis.dispatch.ui.components.TagBadge
import dev.digitalgnosis.dispatch.ui.theme.DgChannelActivity
import dev.digitalgnosis.dispatch.ui.theme.DgChannelCmail
import dev.digitalgnosis.dispatch.ui.theme.DgDeptBoardroom
import dev.digitalgnosis.dispatch.ui.theme.DgDeptDispatch
import dev.digitalgnosis.dispatch.ui.theme.DgDeptEngineering
import dev.digitalgnosis.dispatch.ui.theme.DgDeptIT
import dev.digitalgnosis.dispatch.ui.theme.DgStatusErrorDark
import dev.digitalgnosis.dispatch.ui.theme.DgStatusParked
import dev.digitalgnosis.dispatch.ui.theme.DgStatusWarning
import dev.digitalgnosis.dispatch.util.formatRelativeAge

@Composable
fun PulsePostCard(
    modifier: Modifier = Modifier,
    post: PulsePost,
    showChannel: Boolean,
) {
    val accent = pulseChannelColor(post.channel)

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp),
        colors = CardDefaults.cardColors(
            containerColor = accent.copy(alpha = 0.08f),
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            // Accent dot
            Icon(
                imageVector = Icons.Default.FiberManualRecord,
                contentDescription = null,
                modifier = Modifier
                    .size(10.dp)
                    .padding(top = 4.dp),
                tint = accent,
            )
            Spacer(modifier = Modifier.width(8.dp))

            // Content
            Column(modifier = Modifier.weight(1f)) {
                // Department + channel
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = post.dept,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = accent,
                    )
                    if (showChannel && post.channel.isNotBlank()) {
                        Text(
                            text = "#${post.channel}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        )
                    }
                }

                // Message
                Text(
                    text = post.msg,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )

                // Tags
                if (post.tags.isNotEmpty()) {
                    Row(
                        modifier = Modifier.padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        post.tags.take(4).forEach { tag ->
                            TagBadge(tag = tag)
                        }
                    }
                }
            }

            // Timestamp
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = formatRelativeAge(post.ts),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            )
        }
    }
}

fun pulseChannelColor(channel: String): Color = when (channel) {
    "company" -> DgDeptEngineering
    "engineering" -> DgDeptDispatch
    "research" -> DgDeptBoardroom
    "activity" -> DgChannelActivity
    "priorities" -> DgStatusWarning
    "alerts" -> DgStatusErrorDark
    "boardroom" -> DgStatusParked
    "releases" -> DgChannelCmail
    else -> DgDeptIT
}
