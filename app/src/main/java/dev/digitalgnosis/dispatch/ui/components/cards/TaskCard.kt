package dev.digitalgnosis.dispatch.ui.components.cards

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.digitalgnosis.dispatch.data.WhiteboardTask
import dev.digitalgnosis.dispatch.ui.components.AssigneeBadge
import dev.digitalgnosis.dispatch.ui.theme.DgDeptBoardroom
import dev.digitalgnosis.dispatch.ui.theme.DgDeptDefault
import dev.digitalgnosis.dispatch.ui.theme.DgDeptDispatch
import dev.digitalgnosis.dispatch.ui.theme.DgDeptEngineering
import dev.digitalgnosis.dispatch.ui.theme.DgDeptHunter
import dev.digitalgnosis.dispatch.ui.theme.DgDeptIT
import dev.digitalgnosis.dispatch.ui.theme.DgDeptResearch
import dev.digitalgnosis.dispatch.ui.theme.DgStatusActive
import dev.digitalgnosis.dispatch.ui.theme.DgStatusError
import dev.digitalgnosis.dispatch.ui.theme.DgStatusNeutral
import dev.digitalgnosis.dispatch.ui.theme.DgStatusParked
import dev.digitalgnosis.dispatch.util.formatRelativeTime

@Composable
fun TaskCard(
    modifier: Modifier = Modifier,
    task: WhiteboardTask,
    onTapThread: ((String) -> Unit)? = null,
    dimmed: Boolean = false,
) {
    val alpha = if (dimmed) 0.5f else 1f
    val hasThread = task.threadId != null && onTapThread != null

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .then(
                if (hasThread) Modifier.clickable { onTapThread?.invoke(task.threadId!!) }
                else Modifier
            ),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha * 0.7f),
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            // Status icon
            Icon(
                imageVector = taskStatusIcon(task.status),
                contentDescription = task.status,
                tint = taskStatusColor(task.status).copy(alpha = alpha),
                modifier = Modifier
                    .size(18.dp)
                    .padding(top = 2.dp),
            )

            Spacer(modifier = Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                // Title row with priority dot
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (task.priority == "high") {
                        Surface(
                            shape = CircleShape,
                            color = DgStatusError,
                            modifier = Modifier.size(6.dp),
                        ) {}
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    Text(
                        text = task.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (dimmed) FontWeight.Normal else FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Assignee badge + thread indicator
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AssigneeBadge(assignee = task.assignee, alpha = alpha)

                    if (hasThread) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(
                            imageVector = Icons.Default.Forum,
                            contentDescription = "Has thread",
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = alpha * 0.7f),
                            modifier = Modifier.size(14.dp),
                        )
                    }

                    // Updated time
                    val age = formatRelativeTime(task.updated)
                    if (age.isNotBlank()) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = age,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha * 0.5f),
                        )
                    }
                }

                // Note
                if (!task.note.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = task.note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha * 0.8f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

fun taskStatusColor(status: String): Color = when (status) {
    "active" -> DgStatusActive
    "blocked" -> DgStatusError
    "done" -> DgStatusNeutral
    "parked" -> DgStatusParked
    else -> DgStatusNeutral
}

fun taskStatusIcon(status: String): ImageVector = when (status) {
    "active" -> Icons.Default.Circle
    "blocked" -> Icons.Default.Warning
    "done" -> Icons.Default.CheckCircle
    "parked" -> Icons.Default.PauseCircle
    else -> Icons.Default.Circle
}

fun taskAssigneeColor(assignee: String): Color = when (assignee) {
    "engineering" -> DgDeptEngineering
    "dispatch" -> DgDeptDispatch
    "boardroom", "ceo" -> DgDeptBoardroom
    "hunter" -> DgDeptHunter
    "research" -> DgDeptResearch
    "it" -> DgDeptIT
    "nigel" -> DgStatusParked
    else -> DgDeptDefault
}
