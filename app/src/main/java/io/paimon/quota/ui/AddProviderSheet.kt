package io.paimon.quota.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.paimon.quota.data.ProviderKind
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddProviderSheet(
    onDismiss: () -> Unit,
    onSelect: (ProviderKind) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val visible = remember {
        MutableTransitionState(false).apply { targetState = true }
    }
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.background.luminance() < 0.5f
    val sheetShape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = sheetShape,
        containerColor = scheme.surface.copy(alpha = if (dark) 0.94f else 0.90f),
        contentColor = scheme.onSurface,
        tonalElevation = 0.dp,
        scrimColor = Color.Black.copy(alpha = if (dark) 0.62f else 0.32f),
        dragHandle = {
            BottomSheetDefaults.DragHandle(
                color = scheme.onSurface.copy(alpha = 0.24f),
                width = 42.dp,
            )
        },
    ) {
        AnimatedVisibility(
            visibleState = visible,
            enter = fadeIn(tween(260)) + slideInVertically(tween(360)) { it / 10 },
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.White.copy(alpha = if (dark) 0.075f else 0.34f),
                                Color.Transparent,
                            ),
                        ),
                    )
                    .navigationBarsPadding()
                    .padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
            ) {
                Text(
                    "添加供应商",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "选择服务，下一步再填写凭据",
                    modifier = Modifier.padding(top = 5.dp, bottom = 18.dp),
                    color = scheme.onSurfaceVariant,
                    fontSize = 13.sp,
                )
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(144.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp),
                    contentPadding = PaddingValues(bottom = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(ProviderKind.entries, key = { it.name }) { kind ->
                        Surface(
                            onClick = {
                                scope.launch {
                                    // 先等抽屉完整收起，再进入编辑页，避免两层转场叠在一起。
                                    sheetState.hide()
                                    onSelect(kind)
                                }
                            },
                            shape = UiShapes.Panel,
                            color = scheme.surfaceVariant.copy(alpha = if (dark) 0.54f else 0.48f),
                            border = androidx.compose.foundation.BorderStroke(
                                UiDimens.BorderWidth,
                                Color.White.copy(alpha = if (dark) 0.10f else 0.64f),
                            ),
                        ) {
                            Text(
                                kind.label,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 16.dp),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 2,
                            )
                        }
                    }
                }
            }
        }
    }
}
