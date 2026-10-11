package dev.ahmed.spotter.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.ahmed.spotter.ui.theme.SpotterTheme

/** The coloured bar at the top of every screen. Draws under the status bar; content and icons use the "on top bar" colour. */
@Composable
fun SpotterTopBar(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val colors = SpotterTheme.colors
    Surface(color = colors.topBar, contentColor = colors.onTopBar, modifier = modifier.fillMaxWidth()) {
        CompositionLocalProvider(LocalContentColor provides colors.onTopBar) {
            Row(
                Modifier.statusBarsPadding().height(56.dp).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                content = content,
            )
        }
    }
}
