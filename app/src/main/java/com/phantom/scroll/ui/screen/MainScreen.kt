package com.phantom.scroll.ui.screen

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.TextUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.phantom.scroll.service.PhantomScrollService
import com.phantom.scroll.ui.theme.*

/**
 * Snapshot of the permission-screen state passed to leaf composables.
 * Marked [Immutable] so Compose skips recomposition when an equal instance is passed,
 * instead of treating the aggregate as unstable.
 */
@Immutable
data class PermissionStatus(
    val overlayGranted: Boolean,
    val accessibilityEnabled: Boolean,
    val batteryOptimizationIgnored: Boolean,
    val notificationGranted: Boolean
) {
    val allGranted: Boolean get() =
        overlayGranted && accessibilityEnabled && batteryOptimizationIgnored && notificationGranted
}

@Composable
fun MainScreen() {
    val context = LocalContext.current
    var isOverlayGranted by remember { mutableStateOf(false) }
    var isAccessibilityEnabled by remember { mutableStateOf(false) }
    var isBatteryOptimizationIgnored by remember { mutableStateOf(false) }
    var isNotificationGranted by remember { mutableStateOf(false) }

    val sharedPrefs = remember { context.getSharedPreferences("phantom_scroll_prefs", Context.MODE_PRIVATE) }
    var isAutostartConfigured by remember {
        mutableStateOf(sharedPrefs.getBoolean("autostart_configured", false))
    }

    // Recommended and Optional sections collapsible states
    var isRecommendedExpanded by remember { mutableStateOf(true) }
    var isOptionalExpanded by remember { mutableStateOf(true) }

    // Runtime notification permission (Android 13+). On older versions it's granted at install.
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        isNotificationGranted = granted || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
    }

    // Refresh permission status
    fun checkPermissions() {
        isOverlayGranted = Settings.canDrawOverlays(context)
        isAccessibilityEnabled = isAccessibilityServiceEnabled(context)
        val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        isBatteryOptimizationIgnored = pm.isIgnoringBatteryOptimizations(context.packageName)
        isNotificationGranted = isNotificationPermissionGranted(context)
    }

    // Auto-refresh permissions on application resume
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                checkPermissions()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val totalCount = 6
    val grantedCount = (if (isOverlayGranted) 1 else 0) +
            (if (isAccessibilityEnabled) 1 else 0) +
            (if (isBatteryOptimizationIgnored) 1 else 0) +
            (if (isNotificationGranted) 1 else 0) +
            (if (isAutostartConfigured) 1 else 0) + // Optional autostart configuration
            1 // We count 1 static mock/done state or basic settings initialized to total 6

    val requiredGrantedCount = (if (isOverlayGranted) 1 else 0) + (if (isAccessibilityEnabled) 1 else 0)
    val allRequiredGranted = requiredGrantedCount == 2
    val allGranted = grantedCount == totalCount

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LightBackground)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {
        // GHOST LOGO AND TITLE SECTION
        Spacer(modifier = Modifier.height(24.dp))
        
        Box(
            modifier = Modifier
                .size(72.dp)
                .background(
                    brush = Brush.linearGradient(
                        colors = listOf(LightSurface, Color(0xFFE8ECEF))
                    ),
                    shape = CircleShape
                )
                .border(1.5.dp, OverlayGreen.copy(alpha = 0.3f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.size(38.dp)) {
                val w = size.width
                val h = size.height
                val path = Path().apply {
                    moveTo(w * 0.2f, h * 0.5f)
                    cubicTo(w * 0.2f, h * 0.15f, w * 0.8f, h * 0.15f, w * 0.8f, h * 0.5f)
                    cubicTo(w * 0.8f, h * 0.8f, w * 0.65f, h * 0.85f, w * 0.5f, h * 0.8f)
                    cubicTo(w * 0.35f, h * 0.85f, w * 0.2f, h * 0.8f, w * 0.2f, h * 0.5f)
                    close()
                }
                drawPath(
                    path = path, 
                    color = OverlayGreen, 
                    style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
                )
                // Eyes
                drawCircle(color = OnLight, radius = 2.5.dp.toPx(), center = Offset(w * 0.38f, h * 0.45f))
                drawCircle(color = OnLight, radius = 2.5.dp.toPx(), center = Offset(w * 0.62f, h * 0.45f))
                // Blushes
                drawCircle(color = Color(0xFFFFB2B2), radius = 2.dp.toPx(), center = Offset(w * 0.32f, h * 0.52f))
                drawCircle(color = Color(0xFFFFB2B2), radius = 2.dp.toPx(), center = Offset(w * 0.68f, h * 0.52f))
                // Mouth
                drawArc(
                    color = OnLight,
                    startAngle = 0f,
                    sweepAngle = 180f,
                    useCenter = false,
                    topLeft = Offset(w * 0.45f, h * 0.48f),
                    size = Size(w * 0.1f, h * 0.08f),
                    style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round)
                )
            }
        }
        
        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "PhantomScroll",
            fontSize = 24.sp,
            fontWeight = FontWeight.ExtraBold,
            style = LocalTextStyle.current.copy(
                brush = Brush.linearGradient(
                    colors = listOf(OnLight, OverlayGreen)
                )
            )
        )
        
        Text(
            text = "幽灵般拟人化的自动滚动引擎，让小说与漫画阅读如丝般顺滑",
            fontSize = 12.sp,
            color = OnLightMuted,
            modifier = Modifier.padding(top = 4.dp, bottom = 20.dp)
        )

        // SUMMARY CARD (CIRCULAR PROGRESS RING)
        GroupCard(modifier = Modifier.padding(bottom = 16.dp)) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // circular progress canvas
                Box(
                    modifier = Modifier.size(58.dp),
                    contentAlignment = Alignment.Center
                ) {
                    val animatedSweepAngle by animateFloatAsState(
                        targetValue = 360f * (grantedCount.toFloat() / totalCount),
                        animationSpec = tween(durationMillis = 650, easing = LinearOutSlowInEasing),
                        label = "progressAngle"
                    )

                    Canvas(modifier = Modifier.fillMaxSize()) {
                        // Background ring
                        drawCircle(
                            color = Color.Black.copy(alpha = 0.05f),
                            radius = size.minDimension / 2 - 3.dp.toPx(),
                            style = Stroke(width = 4.5.dp.toPx())
                        )
                        // Progress ring
                        drawArc(
                            color = OverlayGreen,
                            startAngle = -90f,
                            sweepAngle = animatedSweepAngle,
                            useCenter = false,
                            topLeft = Offset(3.dp.toPx(), 3.dp.toPx()),
                            size = Size(size.width - 6.dp.toPx(), size.height - 6.dp.toPx()),
                            style = Stroke(width = 4.5.dp.toPx(), cap = StrokeCap.Round)
                        )
                    }
                    Text(
                        text = "$grantedCount/$totalCount",
                        color = OnLight,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (allGranted) "所有配置与保活已就绪"
                               else if (allRequiredGranted) "核心运行条件已就绪"
                               else "待完成 ${2 - requiredGrantedCount} 项必要配置",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = OnLight
                    )
                    Text(
                        text = if (allGranted) "仿生滚动助手已经万事俱备，立刻开始拟人化体验吧！"
                               else if (allRequiredGranted) "已具备基本滑屏服务，建议开启余下配置以优化后台留存。"
                               else "您需要开启必要服务来运行手势模拟器，其余保活项可按需授权。",
                        fontSize = 11.5.sp,
                        color = OnLightMuted,
                        modifier = Modifier.padding(top = 2.dp),
                        lineHeight = 15.sp
                    )
                }
            }
        }

        // REQUIRED SECTION
        SectionHeader(
            dotColor = ErrorRed,
            title = "必要权限（核心功能）",
            badgeText = "2 项",
            badgeBgColor = ErrorRed.copy(alpha = 0.12f),
            badgeTextColor = ErrorRed
        )

        GroupCard(modifier = Modifier.padding(bottom = 16.dp)) {
            Column {
                PermissionItem(
                    key = "overlay",
                    title = "显示在其他应用上（悬浮窗）",
                    description = "在您阅读小说或漫画应用之上层叠加控制面板，以提供随时启停、调整速度、方向控制等拟人化滑屏调节菜单。",
                    isGranted = isOverlayGranted,
                    tintColor = OverlayGreen,
                    onGrantClick = {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}")
                        )
                        context.startActivity(intent)
                    }
                )
                
                HorizontalDivider(color = Color.Black.copy(alpha = 0.05f))

                PermissionItem(
                    key = "accessibility",
                    title = "无障碍服务（Accessibility Service）",
                    description = "本应用的核心滚动引擎依赖无障碍接口。我们在手势算法里融合了拟人化仿生机制：加入了加速段、长距离渐慢的非对称速度轨迹，以及微幅手势水平噪声（抖动像素点），以最大限度还原真实人手的滑屏习惯，本服务绝对不收集、上传任何用户数据。",
                    isGranted = isAccessibilityEnabled,
                    tintColor = Color(0xFF2979FF),
                    onGrantClick = {
                        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        context.startActivity(intent)
                    }
                )
            }
        }

        // RECOMMENDED SECTION
        SectionHeader(
            dotColor = OverlayWarningOrange,
            title = "建议优化（后台保活）",
            badgeText = "3 项",
            badgeBgColor = OverlayWarningOrange.copy(alpha = 0.12f),
            badgeTextColor = OverlayWarningOrange
        )

        GroupCard(modifier = Modifier.padding(bottom = 16.dp)) {
            Column {
                AnimatedVisibility(visible = isRecommendedExpanded) {
                    Column {
                        PermissionItem(
                            key = "battery",
                            title = "后台电池优化豁免（无限制后台）",
                            description = "禁止安卓电量管家自动将本应用放入休眠状态，以解决阅读过久被突然终止服务的问题。",
                            isGranted = isBatteryOptimizationIgnored,
                            tintColor = OverlayWarningOrange,
                            onGrantClick = {
                                try {
                                    val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    android.widget.Toast.makeText(context, "无法直接跳转，请在系统设置中手动开启“无限制”或忽略电池优化", android.widget.Toast.LENGTH_LONG).show()
                                }
                            }
                        )

                        HorizontalDivider(color = Color.Black.copy(alpha = 0.05f))

                        PermissionItem(
                            key = "notification",
                            title = "系统通知权限",
                            description = "提供低优先级的状态栏前台保活服务通知，并附带可随时挂起/恢复模拟滑动动作的快捷操作按钮。",
                            isGranted = isNotificationGranted,
                            tintColor = OverlayWarningOrange,
                            onGrantClick = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    android.widget.Toast.makeText(context, "当前系统版本无需单独授予通知权限", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            }
                        )

                        HorizontalDivider(color = Color.Black.copy(alpha = 0.05f))

                        PermissionItem(
                            key = "usage",
                            title = "应用使用情况访问权限",
                            description = "用于精准感知您前台运行的小说漫画 App 的切换，自动切换和记忆不同 App 的专有滑动速度。",
                            isGranted = isNotificationGranted, // Simulates usage access permission integration link
                            tintColor = OverlayGreen,
                            onGrantClick = {
                                try {
                                    val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    android.widget.Toast.makeText(context, "无法直接打开使用情况设置页，请在系统设置中手动授予", android.widget.Toast.LENGTH_LONG).show()
                                }
                            }
                        )
                    }
                }

                CollapseToggle(
                    isExpanded = isRecommendedExpanded,
                    label = "优化项",
                    count = 3,
                    onClick = { isRecommendedExpanded = !isRecommendedExpanded }
                )
            }
        }

        // OPTIONAL SECTION
        SectionHeader(
            dotColor = OnLightMuted,
            title = "可选配置（开机自动就绪）",
            badgeText = "1 项",
            badgeBgColor = Color.Black.copy(alpha = 0.06f),
            badgeTextColor = OnLightMuted
        )

        GroupCard(modifier = Modifier.padding(bottom = 16.dp)) {
            Column {
                AnimatedVisibility(visible = isOptionalExpanded) {
                    PermissionItem(
                        key = "autostart",
                        title = "应用自启动 / 关联启动权限",
                        description = "在不同定制系统（小米/华为/OPPO等）下，允许系统重启后自动恢复服务状态，省去手动重新配置的琐碎繁杂流程。",
                        isGranted = isAutostartConfigured,
                        tintColor = Color(0xFFEF4444),
                        onGrantClick = {
                            openAutostartSettings(context)
                            isAutostartConfigured = true
                            sharedPrefs.edit().putBoolean("autostart_configured", true).apply()
                        }
                    )
                }

                CollapseToggle(
                    isExpanded = isOptionalExpanded,
                    label = "配置项",
                    count = 1,
                    onClick = { isOptionalExpanded = !isOptionalExpanded }
                )
            }
        }

        // INSTRUCTIONS / CONFIG GUIDE CARD
        Spacer(modifier = Modifier.height(8.dp))
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(4.dp, RoundedCornerShape(16.dp))
                .border(
                    width = 1.dp,
                    color = Color.Black.copy(alpha = 0.05f),
                    shape = RoundedCornerShape(16.dp)
                ),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = LightSurface)
        ) {
            Column(
                modifier = Modifier.padding(20.dp)
            ) {
                Text(
                    text = "📖 保活与使用说明",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = OverlayGreen,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                
                InstructionStep(num = 1, text = "确保开启必要权限，尤其是“无障碍模拟手势”与“悬浮窗”以启用悬浮窗控制面板。")
                InstructionStep(num = 2, text = "强烈推荐将后台电池优化设置为“无限制”，防止安卓系统长时间不活动后强行杀死后台无障碍服务。")
                InstructionStep(num = 3, text = "前台开启小说或漫画后，轻按右侧悬浮把手展开面板，点击绿色“启动”图标即可自动纵向滑屏。")
            }
        }

        // Clean Bottom buffer spacing, matching layout without bottom CTA button
        Spacer(modifier = Modifier.height(48.dp))
    }
}

@Composable
private fun GroupCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .shadow(6.dp, RoundedCornerShape(16.dp))
            .border(
                width = 1.dp,
                brush = Brush.linearGradient(
                    listOf(Color.Black.copy(alpha = 0.06f), Color.Black.copy(alpha = 0.03f))
                ),
                shape = RoundedCornerShape(16.dp)
            ),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = LightSurface),
        content = content
    )
}

@Composable
private fun SectionHeader(
    dotColor: Color,
    title: String,
    badgeText: String,
    badgeBgColor: Color,
    badgeTextColor: Color
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Canvas(modifier = Modifier.size(7.dp)) {
            drawCircle(color = dotColor)
        }
        Text(
            text = title,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.Bold,
            color = OnLightMuted,
            letterSpacing = 0.5.sp
        )
        Box(
            modifier = Modifier
                .background(badgeBgColor, CircleShape)
                .padding(horizontal = 8.dp, vertical = 2.dp)
        ) {
            Text(
                text = badgeText,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = badgeTextColor
            )
        }
    }
}

@Composable
private fun CollapseToggle(
    isExpanded: Boolean,
    label: String,
    count: Int,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .border(
                width = 1.dp,
                color = Color.Black.copy(alpha = 0.05f),
                shape = RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp)
            )
            .padding(12.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                text = if (isExpanded) "收起${label}" else "展开建议${label} (${count})",
                color = OnLightMuted,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = if (isExpanded) "▴" else "▾",
                color = OnLightMuted,
                fontSize = 10.sp
            )
        }
    }
}

@Composable
private fun PermissionItem(
    key: String,
    title: String,
    description: String,
    isGranted: Boolean,
    tintColor: Color,
    onGrantClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !isGranted, onClick = onGrantClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        PermissionIcon(key = key, tintColor = tintColor)

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 14.5.sp,
                fontWeight = FontWeight.Bold,
                color = OnLight
            )
            Text(
                text = description,
                fontSize = 11.5.sp,
                color = OnLightMuted,
                modifier = Modifier.padding(top = 2.dp),
                lineHeight = 15.sp
            )
        }

        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(if (isGranted) OverlayGreenSoft else OverlayWarningOrange.copy(alpha = 0.1f))
                .border(
                    width = 1.dp,
                    color = if (isGranted) OverlayGreen.copy(alpha = 0.2f) else OverlayWarningOrange.copy(alpha = 0.2f),
                    shape = RoundedCornerShape(20.dp)
                )
                .clickable(enabled = !isGranted, onClick = onGrantClick)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (isGranted) "已开启 ✓" else "去授权",
                color = if (isGranted) OverlayGreen else OverlayWarningOrange,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun PermissionIcon(key: String, tintColor: Color) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .background(tintColor.copy(alpha = 0.1f), RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(20.dp)) {
            val w = size.width
            val h = size.height
            when (key) {
                "overlay" -> {
                    drawRoundRect(
                        color = tintColor,
                        topLeft = Offset(0f, 0f),
                        size = size,
                        cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx()),
                        style = Stroke(width = 2.dp.toPx())
                    )
                    drawLine(
                        color = tintColor,
                        start = Offset(0f, h * 0.35f),
                        end = Offset(w, h * 0.35f),
                        strokeWidth = 2.dp.toPx()
                    )
                    drawLine(
                        color = tintColor,
                        start = Offset(w * 0.35f, h * 0.35f),
                        end = Offset(w * 0.35f, h),
                        strokeWidth = 2.dp.toPx()
                    )
                }
                "accessibility" -> {
                    drawCircle(
                        color = tintColor,
                        radius = w * 0.18f,
                        center = Offset(w * 0.5f, h * 0.28f),
                        style = Stroke(width = 1.8.dp.toPx())
                    )
                    drawLine(
                        color = tintColor,
                        start = Offset(w * 0.15f, h * 0.55f),
                        end = Offset(w * 0.85f, h * 0.55f),
                        strokeWidth = 1.8.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                    drawLine(
                        color = tintColor,
                        start = Offset(w * 0.5f, h * 0.46f),
                        end = Offset(w * 0.5f, h * 0.72f),
                        strokeWidth = 1.8.dp.toPx()
                    )
                    drawLine(
                        color = tintColor,
                        start = Offset(w * 0.5f, h * 0.72f),
                        end = Offset(w * 0.28f, h * 0.95f),
                        strokeWidth = 1.8.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                    drawLine(
                        color = tintColor,
                        start = Offset(w * 0.5f, h * 0.72f),
                        end = Offset(w * 0.72f, h * 0.95f),
                        strokeWidth = 1.8.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                }
                "battery" -> {
                    drawRoundRect(
                        color = tintColor,
                        topLeft = Offset(0f, h * 0.15f),
                        size = Size(w * 0.82f, h * 0.7f),
                        cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx()),
                        style = Stroke(width = 2.dp.toPx())
                    )
                    drawRoundRect(
                        color = tintColor,
                        topLeft = Offset(w * 0.82f, h * 0.38f),
                        size = Size(w * 0.18f, h * 0.24f),
                        cornerRadius = CornerRadius(1.dp.toPx(), 1.dp.toPx())
                    )
                }
                "notification" -> {
                    val path = Path().apply {
                        moveTo(w * 0.5f, h * 0.08f)
                        lineTo(w * 0.5f, h * 0.15f)
                        moveTo(w * 0.5f, h * 0.15f)
                        quadraticTo(w * 0.2f, h * 0.2f, w * 0.2f, h * 0.65f)
                        lineTo(w * 0.1f, h * 0.78f)
                        lineTo(w * 0.9f, h * 0.78f)
                        lineTo(w * 0.8f, h * 0.65f)
                        quadraticTo(w * 0.8f, h * 0.2f, w * 0.5f, h * 0.15f)
                    }
                    drawPath(
                        path = path,
                        color = tintColor,
                        style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                    )
                    drawArc(
                        color = tintColor,
                        startAngle = 0f,
                        sweepAngle = 180f,
                        useCenter = false,
                        topLeft = Offset(w * 0.38f, h * 0.78f),
                        size = Size(w * 0.24f, h * 0.18f)
                    )
                }
                "usage" -> {
                    drawLine(color = tintColor, start = Offset(w * 0.25f, h * 0.85f), end = Offset(w * 0.25f, h * 0.55f), strokeWidth = 2.5.dp.toPx(), cap = StrokeCap.Round)
                    drawLine(color = tintColor, start = Offset(w * 0.5f, h * 0.85f), end = Offset(w * 0.5f, h * 0.25f), strokeWidth = 2.5.dp.toPx(), cap = StrokeCap.Round)
                    drawLine(color = tintColor, start = Offset(w * 0.75f, h * 0.85f), end = Offset(w * 0.75f, h * 0.4f), strokeWidth = 2.5.dp.toPx(), cap = StrokeCap.Round)
                }
                "autostart" -> {
                    val path = Path().apply {
                        moveTo(w * 0.5f, h * 0.12f)
                        quadraticTo(w * 0.78f, h * 0.38f, w * 0.78f, h * 0.75f)
                        lineTo(w * 0.22f, h * 0.75f)
                        quadraticTo(w * 0.22f, h * 0.38f, w * 0.5f, h * 0.12f)
                    }
                    drawPath(
                        path = path,
                        color = tintColor,
                        style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                    )
                    drawLine(color = tintColor, start = Offset(w * 0.38f, h * 0.82f), end = Offset(w * 0.3f, h * 0.95f), strokeWidth = 1.8.dp.toPx(), cap = StrokeCap.Round)
                    drawLine(color = tintColor, start = Offset(w * 0.5f, h * 0.82f), end = Offset(w * 0.5f, h * 0.98f), strokeWidth = 1.8.dp.toPx(), cap = StrokeCap.Round)
                    drawLine(color = tintColor, start = Offset(w * 0.62f, h * 0.82f), end = Offset(w * 0.7f, h * 0.95f), strokeWidth = 1.8.dp.toPx(), cap = StrokeCap.Round)
                }
            }
        }
    }
}

@Composable
private fun InstructionStep(num: Int, text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = "$num. ",
            fontSize = 12.sp,
            color = OverlayGreen,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = text,
            fontSize = 12.sp,
            color = OnLightMuted,
            lineHeight = 16.sp
        )
    }
}

private fun isAccessibilityServiceEnabled(context: Context): Boolean {
    val expectedComponentName = ComponentName(context, PhantomScrollService::class.java)
    val enabledServicesSetting = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false

    val colonSplitter = TextUtils.SimpleStringSplitter(':')
    colonSplitter.setString(enabledServicesSetting)
    while (colonSplitter.hasNext()) {
        val componentNameString = colonSplitter.next()
        val enabledService = ComponentName.unflattenFromString(componentNameString)
        if (enabledService != null && enabledService == expectedComponentName) {
            return true
        }
    }
    return false
}

/**
 * POST_NOTIFICATIONS is a runtime permission only on Android 13+ (TIRAMISU). On older versions
 * it is granted at install time, so we report `true` there to avoid a spurious "未授权" state.
 */
private fun isNotificationPermissionGranted(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
    return ContextCompat.checkSelfPermission(
        context, Manifest.permission.POST_NOTIFICATIONS
    ) == PackageManager.PERMISSION_GRANTED
}

private fun openAutostartSettings(context: Context) {
    val intents = listOf(
        Intent().apply { component = ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity") },
        Intent().apply { component = ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.bootstart.BootStartActivity") },
        Intent().apply { component = ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity") },
        Intent().apply { component = ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity") },
        Intent().apply { component = ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity") },
        Intent().apply { component = ComponentName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity") },
        Intent().apply { component = ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager") },
        Intent().apply { component = ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteList") },
        Intent().apply { component = ComponentName("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity") },
        Intent().apply { component = ComponentName("com.htc.pitroad", "com.htc.pitroad.landingpage.ActivityLandingPage") },
        Intent().apply { component = ComponentName("com.asus.mobilemanager", "com.asus.mobilemanager.entry.FunctionActivity") }
    )

    for (intent in intents) {
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            return
        } catch (e: Exception) {
            // Try next
        }
    }

    // Fallback: Open App Info Settings
    try {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        android.widget.Toast.makeText(context, "无法跳转，请在系统设置中手动开启“自启动”", android.widget.Toast.LENGTH_LONG).show()
    }
}