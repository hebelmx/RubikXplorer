package dev.cubentia.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/** DESIGN.md `rounded`: xs stickers, sm keys, md cards and buttons, lg the cube stage well. */
object Radii {
    val xs = 2.dp
    val sm = 4.dp
    val md = 8.dp
    val lg = 12.dp
}

val RubikShapes =
    Shapes(
        extraSmall = RoundedCornerShape(Radii.xs),
        small = RoundedCornerShape(Radii.sm),
        medium = RoundedCornerShape(Radii.md),
        large = RoundedCornerShape(Radii.lg),
    )
