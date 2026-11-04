package dev.rubikxplorer.app.ui.freecube
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import dev.rubikxplorer.app.R
import dev.rubikxplorer.app.render.Cube3DRenderer
import dev.rubikxplorer.app.ui.theme.Spacing
import dev.rubikxplorer.domain.input.ViewOrbit

@Composable
internal fun ViewControls(renderer: Cube3DRenderer) {
    ToolbarButton(R.drawable.ic_reset_view, R.string.reset_view, "stage:resetView", true) { renderer.resetView() }
    var menu by remember { mutableStateOf(false) }
    Box {
        ToolbarButton(R.drawable.ic_more_vert, R.string.view_options, "stage:viewMenu", true) { menu = true }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            val directions =
                listOf(
                    Triple("left", R.string.rotate_view_left, ViewOrbit.STEP_DEGREES to 0.0),
                    Triple("right", R.string.rotate_view_right, -ViewOrbit.STEP_DEGREES to 0.0),
                    Triple("up", R.string.rotate_view_up, 0.0 to -ViewOrbit.STEP_DEGREES),
                    Triple("down", R.string.rotate_view_down, 0.0 to ViewOrbit.STEP_DEGREES),
                )
            directions.forEach { (tag, label, angles) ->
                DropdownMenuItem(
                    text = { Text(stringResource(label)) },
                    modifier = Modifier.testTag("viewRotate:$tag").heightIn(min = Spacing.touchMin),
                    onClick = {
                        renderer.rotateView(angles.first, angles.second)
                        menu = false
                    },
                )
            }
        }
    }
}
