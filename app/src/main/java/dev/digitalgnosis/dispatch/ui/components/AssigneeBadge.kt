package dev.digitalgnosis.dispatch.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.digitalgnosis.dispatch.ui.components.cards.taskAssigneeColor

@Composable
fun AssigneeBadge(
    modifier: Modifier = Modifier,
    assignee: String,
    alpha: Float = 1f,
) {
    val color = taskAssigneeColor(assignee)
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = color.copy(alpha = alpha * 0.2f),
    ) {
        Text(
            text = assignee,
            style = MaterialTheme.typography.labelSmall,
            color = color.copy(alpha = alpha),
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}
