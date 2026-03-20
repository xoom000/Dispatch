package dev.digitalgnosis.dispatch.ui.components.dg

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.digitalgnosis.dispatch.ui.theme.LocalDgColorScheme

/**
 * DgCard — standard container card from the DG design system.
 *
 * Elevated variant: uses [backgroundElevated] fill with no stroke.
 * Outlined variant: uses [backgroundSurface] fill with [strokeDefault] border.
 */
@Composable
fun DgCard(
    modifier: Modifier = Modifier,
    outlined: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalDgColorScheme.current
    val cardColors = CardDefaults.cardColors(
        containerColor = if (outlined) colors.backgroundSurface else colors.backgroundElevated,
        contentColor = colors.textPrimary,
    )
    val border = if (outlined) BorderStroke(1.dp, colors.strokeDefault) else null

    if (onClick != null) {
        Card(
            onClick = onClick,
            modifier = modifier,
            shape = RoundedCornerShape(12.dp),
            colors = cardColors,
            border = border,
            elevation = CardDefaults.cardElevation(defaultElevation = if (outlined) 0.dp else 2.dp),
        ) {
            Column(modifier = Modifier.padding(16.dp), content = content)
        }
    } else {
        Card(
            modifier = modifier,
            shape = RoundedCornerShape(12.dp),
            colors = cardColors,
            border = border,
            elevation = CardDefaults.cardElevation(defaultElevation = if (outlined) 0.dp else 2.dp),
        ) {
            Column(modifier = Modifier.padding(16.dp), content = content)
        }
    }
}
