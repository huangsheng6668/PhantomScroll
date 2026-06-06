package com.phantom.scroll.ui.overlay

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phantom.scroll.config.ScrollConfig
import com.phantom.scroll.ui.theme.*
import kotlinx.coroutines.launch

enum class PanelState {
    Expanded,
    Snapping,
    Collapsed
}

@Composable
fun FloatingPanel(
    config: ScrollConfig,
    onUpdatePosition: (x: Int, y: Int) -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    val density = LocalDensity.current

    val screenWidth by config.screenWidth.collectAsState()
    val screenHeight by config.screenHeight.collectAsState()

    var panelState by remember { mutableStateOf(PanelState.Expanded) }
    var isLeftEdge by remember { mutableStateOf(true) }

    // Floating window raw coordinates in WindowManager space
    var currentX by remember { mutableIntStateOf(0) }
    var currentY by remember { mutableIntStateOf(200) }

    val isRunning by config.isRunning.collectAsState()
    val duration by config.scrollDuration.collectAsState()
    val interval by config.scrollInterval.collectAsState()
    val distanceRatio by config.scrollDistanceRatio.collectAsState()

    // Panel dimensions
    val panelWidth = 130.dp
    val handleWidth = 8.dp
    val handleHeight = 36.dp
    val touchTargetWidth = 32.dp
    val touchTargetHeight = 64.dp

    // Pre-calculate pixel values from density
    val panelWidthPx = with(density) { panelWidth.toPx().toInt() }
    val touchTargetWidthPx = with(density) { touchTargetWidth.toPx().toInt() }

    // Keep track of last seen screen dimensions to avoid redundant updates
    var lastScreenWidth by remember { mutableIntStateOf(screenWidth) }
    var lastScreenHeight by remember { mutableIntStateOf(screenHeight) }

    LaunchedEffect(screenWidth, screenHeight) {
        if (screenWidth != lastScreenWidth || screenHeight != lastScreenHeight) {
            val targetX = if (isLeftEdge) {
                0
            } else {
                if (panelState == PanelState.Collapsed) {
                    screenWidth - touchTargetWidthPx
                } else {
                    screenWidth - panelWidthPx
                }
            }
            val targetY = currentY.coerceIn(0, screenHeight - 200)

            currentX = targetX
            currentY = targetY
            onUpdatePosition(targetX, targetY)

            lastScreenWidth = screenWidth
            lastScreenHeight = screenHeight
            android.util.Log.d("FloatingPanel", "Orientation changed. Adjusted position to X=$targetX, Y=$targetY")
        }
    }

    Box(
        modifier = Modifier
            .wrapContentSize()
    ) {
        when (panelState) {
            PanelState.Collapsed -> {
                // Outer interactive Box to expand touch target (32.dp x 64.dp)
                Box(
                    modifier = Modifier
                        .size(width = touchTargetWidth, height = touchTargetHeight)
                        .clickable {
                            panelState = PanelState.Expanded
                            if (!isLeftEdge) {
                                currentX = screenWidth - panelWidthPx
                                onUpdatePosition(currentX, currentY)
                            }
                        },
                    contentAlignment = if (isLeftEdge) Alignment.CenterStart else Alignment.CenterEnd
                ) {
                    // Inner visual handle: keep original visual footprint (8.dp x 36.dp)
                    Box(
                        modifier = Modifier
                            .size(width = handleWidth, height = handleHeight)
                            .shadow(
                                6.dp,
                                shape = if (isLeftEdge) RoundedCornerShape(topEnd = 8.dp, bottomEnd = 8.dp)
                                else RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp)
                            )
                            .clip(
                                if (isLeftEdge) RoundedCornerShape(topEnd = 8.dp, bottomEnd = 8.dp)
                                else RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp)
                            )
                            .background(
                                Brush.horizontalGradient(
                                    if (isLeftEdge) {
                                        listOf(PhantomCyan.copy(alpha = 0.8f), PhantomBlue.copy(alpha = 0.5f))
                                    } else {
                                        listOf(PhantomBlue.copy(alpha = 0.5f), PhantomCyan.copy(alpha = 0.8f))
                                    }
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        // Small vertical bar indicator
                        Box(
                            modifier = Modifier
                                .size(width = 1.5.dp, height = 12.dp)
                                .background(Color.White.copy(alpha = 0.7f), CircleShape)
                        )
                    }
                }
            }

            PanelState.Expanded -> {
                // Interactive Controller Panel
                Card(
                    modifier = Modifier
                        .width(panelWidth)
                        .shadow(16.dp, shape = RoundedCornerShape(16.dp))
                        .border(
                            width = 1.dp,
                            brush = Brush.linearGradient(
                                listOf(PhantomCyan.copy(alpha = 0.7f), PhantomPurple.copy(alpha = 0.3f))
                            ),
                            shape = RoundedCornerShape(16.dp)
                        )
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = { /* Drag started */ },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    currentX = (currentX + dragAmount.x.toInt())
                                        .coerceIn(0, screenWidth - 100)
                                    currentY = (currentY + dragAmount.y.toInt())
                                        .coerceIn(0, screenHeight - 200)
                                    onUpdatePosition(currentX, currentY)
                                },
                                onDragEnd = {
                                    // Snap to nearest horizontal edge
                                    panelState = PanelState.Snapping
                                    val leftTarget = 0
                                    val rightTarget = screenWidth - panelWidthPx
                                    val middle = screenWidth / 2
                                    val panelCenterX = currentX + panelWidthPx / 2

                                    val targetX = if (panelCenterX > middle) {
                                        isLeftEdge = false
                                        rightTarget
                                    } else {
                                        isLeftEdge = true
                                        leftTarget
                                    }

                                    coroutineScope.launch {
                                        val anim = Animatable(currentX.toFloat())
                                        anim.animateTo(
                                            targetValue = targetX.toFloat(),
                                            animationSpec = tween(durationMillis = 250)
                                        ) {
                                            currentX = this.value.toInt()
                                            onUpdatePosition(currentX, currentY)
                                        }
                                        // Collapse to edge handle after animation
                                        panelState = PanelState.Collapsed
                                        // Position handle at correct edge
                                        currentX = if (isLeftEdge) 0 else screenWidth - touchTargetWidthPx
                                        onUpdatePosition(currentX, currentY)
                                    }
                                }
                            )
                        },
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = DarkSurfaceTranslucent
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .padding(6.dp)
                            .fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Title bar
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "☽ Phantom",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = PhantomCyan
                            )
                            // Quick-fold button
                            Box(
                                modifier = Modifier
                                    .size(16.dp)
                                    .clip(CircleShape)
                                    .background(Color.White.copy(alpha = 0.1f))
                                    .clickable {
                                        panelState = PanelState.Collapsed
                                        currentX = if (isLeftEdge) 0 else screenWidth - touchTargetWidthPx
                                        onUpdatePosition(currentX, currentY)
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text("—", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        // ===== Duration Slider (200ms - 1500ms) =====
                        SliderSection(
                            label = "速度",
                            valueText = "${duration}ms",
                            value = duration.toFloat(),
                            range = 200f..1500f,
                            onValueChange = { config.scrollDuration.value = it.toLong() }
                        )

                        // ===== Interval Slider (500ms - 10000ms) =====
                        SliderSection(
                            label = "间隔",
                            valueText = String.format("%.1fs", interval / 1000f),
                            value = interval.toFloat(),
                            range = 500f..10000f,
                            onValueChange = { config.scrollInterval.value = it.toLong() }
                        )

                        // ===== Distance Slider (30% - 95%) =====
                        SliderSection(
                            label = "距离",
                            valueText = "${(distanceRatio * 100).toInt()}%",
                            value = distanceRatio,
                            range = 0.30f..0.95f,
                            onValueChange = { config.scrollDistanceRatio.value = it }
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        // Play/Pause toggle button
                        Button(
                            onClick = { config.isRunning.value = !isRunning },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(26.dp)
                                .shadow(2.dp, RoundedCornerShape(13.dp)),
                            shape = RoundedCornerShape(13.dp),
                            contentPadding = PaddingValues(0.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isRunning) ErrorRed else SuccessGreen
                            )
                        ) {
                            Text(
                                text = if (isRunning) "⏸ 暂停" else "▶ 开始",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.sp
                            )
                        }
                    }
                }
            }

            PanelState.Snapping -> {
                // Brief loading card shown during snap animation
                Card(
                    modifier = Modifier.width(panelWidth),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = DarkSurfaceTranslucent)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = PhantomCyan)
                    }
                }
            }
        }
    }
}

/**
 * Reusable slider section with label, value display, and themed track colors.
 */
@Composable
private fun SliderSection(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, fontSize = 9.sp, color = TextSecondary)
            Text(valueText, fontSize = 10.sp, color = PhantomCyan, fontWeight = FontWeight.Bold)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            modifier = Modifier.height(24.dp),
            colors = SliderDefaults.colors(
                thumbColor = PhantomCyan,
                activeTrackColor = PhantomCyan,
                inactiveTrackColor = TextTertiary
            )
        )
    }
}
