package com.carlmanning.carlsbrain.ui.components

import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration

private val URL_REGEX = Regex("""https?://[^\s<>"]+""")

@Composable
fun LinkifyText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current
) {
    val uriHandler = LocalUriHandler.current
    val linkColor = MaterialTheme.colorScheme.primary

    val annotated = buildAnnotatedString {
        append(text)
        URL_REGEX.findAll(text).forEach { match ->
            addStyle(
                style = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline),
                start = match.range.first,
                end = match.range.last + 1
            )
            addStringAnnotation(
                tag = "URL",
                annotation = match.value,
                start = match.range.first,
                end = match.range.last + 1
            )
        }
    }

    ClickableText(
        text = annotated,
        modifier = modifier,
        style = style,
        onClick = { offset ->
            annotated.getStringAnnotations(tag = "URL", start = offset, end = offset)
                .firstOrNull()
                ?.let { uriHandler.openUri(it.item) }
        }
    )
}
