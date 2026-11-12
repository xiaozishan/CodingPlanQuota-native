package io.paimon.quota.ui

import androidx.compose.ui.unit.dp

/** 统一形状参数；内外圆角保持同心：26 = 10 + 16。 */
object UiDimens {
    val DefaultCornerSize = 26.dp
    const val DefaultSmoothing = 0.6f

    val CardContentPadding = 16.dp
    val InnerCornerSize = DefaultCornerSize - CardContentPadding
    val PanelCornerSize = InnerCornerSize
    val ControlCornerSize = InnerCornerSize
    val BorderWidth = 0.8.dp
}

object UiShapes {
    val Card = SquircleShape()
    val Panel = SquircleShape(UiDimens.PanelCornerSize)
    val Control = SquircleShape(UiDimens.ControlCornerSize)
}
