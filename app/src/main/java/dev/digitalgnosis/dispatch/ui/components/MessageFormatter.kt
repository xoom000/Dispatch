package dev.digitalgnosis.dispatch.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp

val symbolPattern by lazy {
    Regex("""(https?://[^\s\t\n]+)|(`[^`]+`)|(@\w+)|(\*[\w\s]+\*)|(_[\w\s]+_)|(~[\w\s]+~)""")
}

enum class SymbolAnnotationType { PERSON, LINK }

@Composable
fun messageFormatter(text: String, primary: Boolean): AnnotatedString {
    val tokens = symbolPattern.findAll(text)
    val codeBackground = if (primary) {
        MaterialTheme.colorScheme.secondary
    } else {
        MaterialTheme.colorScheme.surface
    }
    val linkColor = if (primary) {
        MaterialTheme.colorScheme.inversePrimary
    } else {
        MaterialTheme.colorScheme.primary
    }

    return buildAnnotatedString {
        var cursor = 0
        for (token in tokens) {
            append(text.slice(cursor until token.range.first))
            when (token.value.first()) {
                'h' -> {
                    val start = length
                    append(token.value)
                    addStyle(SpanStyle(color = linkColor), start, length)
                    addStringAnnotation(
                        tag = SymbolAnnotationType.LINK.name,
                        annotation = token.value,
                        start = start,
                        end = length
                    )
                }
                '`' -> {
                    val inner = token.value.trim('`')
                    val start = length
                    append(inner)
                    addStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            background = codeBackground,
                            baselineShift = BaselineShift(0.2f)
                        ),
                        start, length
                    )
                }
                '@' -> {
                    val start = length
                    append(token.value)
                    addStyle(SpanStyle(color = linkColor, fontWeight = FontWeight.Bold), start, length)
                    addStringAnnotation(
                        tag = SymbolAnnotationType.PERSON.name,
                        annotation = token.value.substring(1),
                        start = start,
                        end = length
                    )
                }
                '*' -> {
                    val inner = token.value.trim('*')
                    val start = length
                    append(inner)
                    addStyle(SpanStyle(fontWeight = FontWeight.Bold), start, length)
                }
                '_' -> {
                    val inner = token.value.trim('_')
                    val start = length
                    append(inner)
                    addStyle(SpanStyle(fontStyle = FontStyle.Italic), start, length)
                }
                '~' -> {
                    val inner = token.value.trim('~')
                    val start = length
                    append(inner)
                    addStyle(SpanStyle(textDecoration = TextDecoration.LineThrough), start, length)
                }
                else -> append(token.value)
            }
            cursor = token.range.last + 1
        }
        if (cursor <= text.lastIndex) {
            append(text.slice(cursor..text.lastIndex))
        }
    }
}
