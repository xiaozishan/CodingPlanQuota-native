package io.paimon.quota.ui

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.min

/**
 * 用三次贝塞尔近似超椭圆圆角。0 是标准圆弧；增大平滑度会把曲率更均匀地
 * 分配到转角，1 时端点曲率为零，与直边衔接最平滑。
 */
class SquircleShape(
    val cornerSize: Dp = UiDimens.DefaultCornerSize,
    val smoothing: Float = UiDimens.DefaultSmoothing,
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val width = size.width.coerceAtLeast(0f)
        val height = size.height.coerceAtLeast(0f)
        val radius = with(density) { cornerSize.toPx() }
            .coerceIn(0f, min(width, height) / 2f)
        val smooth = smoothing.coerceIn(0f, 1f)
        val k = CIRCLE_KAPPA + (1f - CIRCLE_KAPPA) * smooth

        // 路径关于水平、垂直轴对称，因此 LTR / RTL 使用同一轮廓。
        val path = Path().apply {
            moveTo(radius, 0f)
            lineTo(width - radius, 0f)
            cubicTo(
                width - radius + k * radius, 0f,
                width, radius - k * radius,
                width, radius,
            )
            lineTo(width, height - radius)
            cubicTo(
                width, height - radius + k * radius,
                width - radius + k * radius, height,
                width - radius, height,
            )
            lineTo(radius, height)
            cubicTo(
                radius - k * radius, height,
                0f, height - radius + k * radius,
                0f, height - radius,
            )
            lineTo(0f, radius)
            cubicTo(
                0f, radius - k * radius,
                radius - k * radius, 0f,
                radius, 0f,
            )
            close()
        }
        return Outline.Generic(path)
    }

    private companion object {
        const val CIRCLE_KAPPA = 0.55228475f
    }
}
