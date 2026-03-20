package dev.digitalgnosis.dispatch.ui.components.dg

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.digitalgnosis.dispatch.ui.theme.LocalDgColorScheme

/**
 * DgFilledButton — primary filled action button from the DG design system.
 *
 * Uses [LocalDgColorScheme] semantic tokens for colors.
 */
@Composable
fun DgFilledButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: ImageVector? = null,
) {
    val colors = LocalDgColorScheme.current
    Button(
        onClick = onClick,
        modifier = modifier.semantics { contentDescription = text },
        enabled = enabled,
        shape = RoundedCornerShape(8.dp),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = colors.brandPrimary,
            contentColor = colors.brandOnPrimary,
            disabledContainerColor = colors.strokeDefault,
            disabledContentColor = colors.textDisabled,
        ),
    ) {
        if (leadingIcon != null) {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
        }
        Text(text)
    }
}

/**
 * DgOutlinedButton — secondary outlined action button from the DG design system.
 */
@Composable
fun DgOutlinedButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: ImageVector? = null,
) {
    val colors = LocalDgColorScheme.current
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.semantics { contentDescription = text },
        enabled = enabled,
        shape = RoundedCornerShape(8.dp),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = colors.brandPrimary,
            disabledContentColor = colors.textDisabled,
        ),
    ) {
        if (leadingIcon != null) {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
        }
        Text(text)
    }
}

/**
 * DgTextButton — low-emphasis text-only action button from the DG design system.
 */
@Composable
fun DgTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = LocalDgColorScheme.current
    TextButton(
        onClick = onClick,
        modifier = modifier.semantics { contentDescription = text },
        enabled = enabled,
        colors = ButtonDefaults.textButtonColors(
            contentColor = colors.brandPrimary,
            disabledContentColor = colors.textDisabled,
        ),
    ) {
        Text(text)
    }
}
