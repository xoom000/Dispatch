package dev.digitalgnosis.dispatch.ui.components.bubbles

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.compose.SubcomposeAsyncImage
import coil3.compose.SubcomposeAsyncImageContent
import coil3.request.ImageRequest
import coil3.request.crossfade
import dev.digitalgnosis.dispatch.ui.components.SymbolAnnotationType
import dev.digitalgnosis.dispatch.ui.components.messageFormatter
import kotlinx.coroutines.launch

// ── Image URL detection ────────────────────────────────────────────────────────

private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "svg")

/**
 * Returns true if the URL looks like a direct image link.
 * Checks file extension (before any query string).
 */
fun String.isImageUrl(): Boolean {
    val path = substringBefore('?').substringBefore('#').lowercase()
    return IMAGE_EXTENSIONS.any { path.endsWith(".$it") }
}

/**
 * Extract all raw http(s) URLs from a text string.
 * Does not depend on the AnnotatedString — works on plain text.
 */
fun extractUrls(text: String): List<String> =
    Regex("""https?://[^\s\t\n]+""").findAll(text).map { it.value }.toList()

// ── File path detection ────────────────────────────────────────────────────────

private val PATH_PATTERN = Regex("""(/[\w./-]+(?:\.\w+)+)|([\w-]+/[\w./-]+(?:\.\w+)+)""")

/**
 * Returns true if the string looks like a Unix file path (not a URL).
 */
fun String.looksLikeFilePath(): Boolean =
    (startsWith('/') || contains('/')) && !startsWith("http") && length < 300

// ── Composables ────────────────────────────────────────────────────────────────

/**
 * Rich text content for agent/nigel bubbles.
 *
 * Features:
 *   1. Clickable URLs — tap opens browser (via messageFormatter's LINK annotations)
 *   2. Long-press on URL — copies URL to clipboard with snackbar confirmation
 *   3. Inline image preview — URLs with image extensions render as Coil AsyncImage
 *      below the text. Uses SubcomposeAsyncImage for loading/error states.
 *   4. Tap-to-copy on inline images
 *
 * Image loading: uses Coil 3 AsyncImage with explicit null URI guard.
 * Never pass a null/blank URL — check before calling.
 *
 * @param text The raw bubble text.
 * @param primary True for Nigel (outgoing) bubbles, false for Agent (incoming).
 * @param textColor The color to render text in.
 * @param snackbarHostState Shared snackbar host for copy confirmations.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BubbleRichContent(
    text: String,
    primary: Boolean,
    textColor: androidx.compose.ui.graphics.Color,
    snackbarHostState: SnackbarHostState,
) {
    val uriHandler = LocalUriHandler.current
    val clipboardManager = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val styledText = messageFormatter(text = text, primary = primary)

    // Extract image URLs from text for inline preview
    val imageUrls = remember(text) { extractUrls(text).filter { it.isImageUrl() } }

    Column {
        // ── Clickable + long-press-to-copy text ──────────────────────────────
        ClickableText(
            text = styledText,
            style = MaterialTheme.typography.bodyLarge.copy(
                fontSize = 16.sp,
                color = textColor,
            ),
            onClick = { offset ->
                styledText.getStringAnnotations(start = offset, end = offset)
                    .firstOrNull()?.let { annotation ->
                        when (annotation.tag) {
                            SymbolAnnotationType.LINK.name -> {
                                try {
                                    uriHandler.openUri(annotation.item)
                                } catch (_: Exception) {
                                    // Malformed URL — copy instead
                                    clipboardManager.setText(AnnotatedString(annotation.item))
                                    scope.launch {
                                        snackbarHostState.showSnackbar("Copied URL")
                                    }
                                }
                            }
                            else -> {} // PERSON annotations: no-op for now
                        }
                    }
            },
        )

        // ── Inline image previews ─────────────────────────────────────────────
        if (imageUrls.isNotEmpty()) {
            Spacer(modifier = Modifier.height(6.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                imageUrls.forEach { url ->
                    BubbleImagePreview(
                        url = url,
                        onCopy = {
                            clipboardManager.setText(AnnotatedString(url))
                            scope.launch { snackbarHostState.showSnackbar("Copied image URL") }
                        },
                        onOpen = {
                            try { uriHandler.openUri(url) } catch (_: Exception) {}
                        },
                    )
                }
            }
        }
    }
}

/**
 * Inline image thumbnail inside a chat bubble.
 *
 * Uses [SubcomposeAsyncImage] so we can show a loading indicator and a broken-image
 * placeholder without crashing on null/invalid URIs (the Glide null URI gotcha —
 * Coil handles null gracefully but we guard it explicitly anyway).
 *
 * Tap: open in browser. Long-press: copy URL.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BubbleImagePreview(
    url: String,
    onCopy: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (url.isBlank()) return

    val context = LocalContext.current
    val shape = RoundedCornerShape(8.dp)

    Box(
        modifier = modifier
            .widthIn(max = 280.dp)
            .clip(shape)
            .combinedClickable(
                onClick = onOpen,
                onLongClick = onCopy,
            )
    ) {
        SubcomposeAsyncImage(
            model = ImageRequest.Builder(context)
                .data(url)
                .crossfade(true)
                .build(),
            contentDescription = "Image attachment",
            contentScale = ContentScale.FillWidth,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 240.dp)
                .clip(shape),
        ) {
            when (painter.state) {
                is AsyncImagePainter.State.Loading -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                }
                is AsyncImagePainter.State.Error -> {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        shape = shape,
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Default.BrokenImage,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = url.substringAfterLast('/').take(40),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
                else -> SubcomposeAsyncImageContent()
            }
        }
    }
}

/**
 * Tappable file path chip — shown in bubble detail area for file attachments.
 * Tap copies the path to clipboard. Long-press does the same.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FilePathChip(
    path: String,
    textColor: androidx.compose.ui.graphics.Color,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    val clipboardManager = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val shape = RoundedCornerShape(6.dp)

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f),
        shape = shape,
        modifier = modifier
            .combinedClickable(
                onClick = {
                    clipboardManager.setText(AnnotatedString(path))
                    scope.launch { snackbarHostState.showSnackbar("Copied path") }
                },
                onLongClick = {
                    clipboardManager.setText(AnnotatedString(path))
                    scope.launch { snackbarHostState.showSnackbar("Copied path") }
                },
            ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.InsertDriveFile,
                contentDescription = null,
                tint = textColor.copy(alpha = 0.6f),
                modifier = Modifier.size(12.dp),
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = path,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                ),
                color = textColor.copy(alpha = 0.8f),
            )
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                imageVector = Icons.Default.ContentCopy,
                contentDescription = "Copy path",
                tint = textColor.copy(alpha = 0.4f),
                modifier = Modifier.size(10.dp),
            )
        }
    }
}
