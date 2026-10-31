package io.csync.app

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt

@Composable
fun HostList(profiles: List<HostProfile>, activeHostId: String, onAdd: () -> Unit,
             onEdit: (HostProfile) -> Unit, onDelete: (HostProfile) -> Unit,
             onReorder: (List<HostProfile>) -> Unit) {
    var order by remember(profiles) { mutableStateOf(profiles) }
    var dragging by remember { mutableStateOf<String?>(null) }
    var offset by remember { mutableFloatStateOf(0f) }
    var travel by remember { mutableFloatStateOf(0f) }
    var startOrder by remember { mutableStateOf(profiles) }
    val heights = remember { mutableMapOf<String, Int>() }
    val gap = with(LocalDensity.current) { 8.dp.toPx() }
    val finish by rememberUpdatedState {
        dragging = null
        offset = 0f
        if (order != profiles) onReorder(order)
    }
    val cancel by rememberUpdatedState { dragging = null; offset = 0f; order = profiles }
    val start by rememberUpdatedState { id: String ->
        dragging = id; offset = 0f; travel = 0f; startOrder = order
    }
    val drag by rememberUpdatedState { id: String, delta: Float ->
        travel += delta
        fun top(items: List<HostProfile>, index: Int): Float = items.take(index).sumOf { heights[it.id] ?: 100 }.toFloat() + gap * index
        val originalIndex = startOrder.indexOfFirst { it.id == id }
        if (originalIndex >= 0) {
            val originalTop = top(startOrder, originalIndex)
            val center = originalTop + (heights[id] ?: 100) / 2f + travel
            val target = startOrder.indices.minByOrNull { index ->
                kotlin.math.abs(top(startOrder, index) + (heights[startOrder[index].id] ?: 100) / 2f - center)
            } ?: originalIndex
            order = startOrder.toMutableList().apply { add(target, removeAt(originalIndex)) }
            offset = originalTop + travel - top(order, target)
        }
    }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("添加主机", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                FilledTonalIconButton(onClick = onAdd) { Icon(painterResource(R.drawable.ic_material_add), "添加主机") }
            }
            Text("拖动右侧手柄调整顺序，优先连接最上方主机。", style = MaterialTheme.typography.bodyMedium)
            if (order.isEmpty()) Text("尚未添加主机，点击 + 输入电脑 IP 配对。", Modifier.padding(vertical = 16.dp))
            order.forEachIndexed { index, profile -> key(profile.id) {
                OutlinedCard(onClick = { onEdit(profile) }, modifier = Modifier.fillMaxWidth()
                    .onSizeChanged { heights[profile.id] = it.height }
                    .zIndex(if (dragging == profile.id) 1f else 0f)
                    .offset { IntOffset(0, if (dragging == profile.id) offset.roundToInt() else 0) },
                    colors = CardDefaults.outlinedCardColors(containerColor = if (dragging == profile.id) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)) {
                    Row(Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(profile.name, style = MaterialTheme.typography.titleMedium)
                            Text("${profile.host}:${profile.port}", style = MaterialTheme.typography.bodyMedium)
                            Text(if (profile.id == activeHostId) "已连接 · 优先级 ${index + 1}" else "优先级 ${index + 1}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        }
                        IconButton(onClick = { onDelete(profile) }) { Icon(painterResource(R.drawable.ic_material_delete), "删除 ${profile.name}") }
                        Box(Modifier.size(48.dp).semantics {
                            contentDescription = "拖动调整 ${profile.name} 的优先级"
                            customActions = listOf(
                                CustomAccessibilityAction("上移") {
                                    if (index > 0) { onReorder(order.toMutableList().apply { add(index - 1, removeAt(index)) }); true } else false
                                },
                                CustomAccessibilityAction("下移") {
                                    if (index < order.lastIndex) { onReorder(order.toMutableList().apply { add(index + 1, removeAt(index)) }); true } else false
                                }
                            )
                        }.pointerInput(profile.id) {
                            detectDragGestures(
                                onDragStart = { start(profile.id) },
                                onDragEnd = { finish() },
                                onDragCancel = { cancel() },
                                onDrag = { change, amount -> change.consume(); drag(profile.id, amount.y) }
                            )
                        }, contentAlignment = Alignment.Center) {
                            Icon(painterResource(R.drawable.ic_material_drag_handle), null)
                        }
                    }
                }
            } }
        }
    }
}
