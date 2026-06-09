package com.phantom.scroll.ui.screen

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.text.TextUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.phantom.scroll.service.PhantomScrollService
import com.phantom.scroll.ui.theme.*

@Composable
fun MainScreen() {
    val context = LocalContext.current
    var isOverlayGranted by remember { mutableStateOf(false) }
    var isAccessibilityEnabled by remember { mutableStateOf(false) }
    var isBatteryOptimizationIgnored by remember { mutableStateOf(false) }

    // Refresh permission status
    fun checkPermissions() {
        isOverlayGranted = Settings.canDrawOverlays(context)
        isAccessibilityEnabled = isAccessibilityServiceEnabled(context)
        val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        isBatteryOptimizationIgnored = pm.isIgnoringBatteryOptimizations(context.packageName)
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)       // 1. Fill background to cover system bars
            .statusBarsPadding()               // 2. Padding for status bar
            .navigationBarsPadding()           // 3. Padding for navigation bar
            .padding(24.dp),                   // 4. Content margin
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {
        Spacer(modifier = Modifier.height(40.dp))

        // GHOST LOGO AND TITLE
        Text(
            text = "👻",
            fontSize = 64.sp,
            modifier = Modifier.padding(bottom = 12.dp)
        )
        
        Text(
            text = "PhantomScroll",
            fontSize = 32.sp,
            fontWeight = FontWeight.ExtraBold,
            color = Color.White
        )
        
        Text(
            text = "幽灵般拟人化的自动翻页服务",
            fontSize = 14.sp,
            color = TextSecondary,
            modifier = Modifier.padding(top = 4.dp, bottom = 32.dp)
        )

        // STATUS CARD LIST
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(8.dp, RoundedCornerShape(16.dp))
                .border(
                    width = 1.dp,
                    brush = Brush.linearGradient(
                        listOf(Color.White.copy(alpha = 0.1f), Color.White.copy(alpha = 0.05f))
                    ),
                    shape = RoundedCornerShape(16.dp)
                ),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = DarkSurface)
        ) {
            Column(
                modifier = Modifier.padding(20.dp)
            ) {
                Text(
                    text = "系统权限检查",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                // 1. Overlay permission status
                PermissionItem(
                    title = "悬浮窗显示权限",
                    description = "允许在小说或漫画应用上方显示控制悬浮窗",
                    isGranted = isOverlayGranted,
                    onGrantClick = {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}")
                        )
                        context.startActivity(intent)
                    }
                )

                HorizontalDivider(
                    color = TextTertiary.copy(alpha = 0.3f),
                    modifier = Modifier.padding(vertical = 14.dp)
                )

                // 2. Accessibility permission status
                PermissionItem(
                    title = "无障碍模拟手势权限",
                    description = "允许在此服务中向屏幕发送拟人化滑动手势",
                    isGranted = isAccessibilityEnabled,
                    onGrantClick = {
                        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        context.startActivity(intent)
                    }
                )

                HorizontalDivider(
                    color = TextTertiary.copy(alpha = 0.3f),
                    modifier = Modifier.padding(vertical = 14.dp)
                )

                // 3. Battery optimization status
                PermissionItem(
                    title = "忽略后台电池优化 (推荐)",
                    description = "防止系统在后台强杀无障碍服务进程",
                    isGranted = isBatteryOptimizationIgnored,
                    onGrantClick = {
                        try {
                            // Complies with Google Play policy by using settings page instead of direct request dialog
                            val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            android.widget.Toast.makeText(context, "无法直接跳转，请在系统设置中手动开启“无限制”或忽略电池优化", android.widget.Toast.LENGTH_LONG).show()
                        }
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // INSTRUCTIONS CARD
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(4.dp, RoundedCornerShape(16.dp)),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = DarkSurface.copy(alpha = 0.5f))
        ) {
            Column(
                modifier = Modifier.padding(20.dp)
            ) {
                Text(
                    text = "📖 保活与使用说明",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = PhantomCyan,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                
                InstructionStep(num = 1, text = "授予上述全部权限，尤其是“忽略后台电池优化”以防服务被系统强杀。")
                InstructionStep(num = 2, text = "建议在系统多任务卡片界面将本应用“加锁”，并允许其“自启动”。")
                InstructionStep(num = 3, text = "权限就绪后，屏幕边缘会自动出现微型条状半圆手柄。")
                InstructionStep(num = 4, text = "点击或向内滑动该手柄即可展开/折叠参数控制面板。")
                InstructionStep(num = 5, text = "启动自动滑动后即可安心阅读，如需退出服务可从通知栏点击“停止服务”。")
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        // Bottom state verification button
        Button(
            onClick = { checkPermissions() },
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .shadow(12.dp, RoundedCornerShape(25.dp)),
            shape = RoundedCornerShape(25.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isOverlayGranted && isAccessibilityEnabled && isBatteryOptimizationIgnored)
                    SuccessGreen else PhantomBlue
            )
        ) {
            Text(
                text = if (isOverlayGranted && isAccessibilityEnabled && isBatteryOptimizationIgnored)
                    "✓ 权限与保活已就绪" else "刷新权限状态",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp
            )
        }
    }
}

@Composable
private fun PermissionItem(
    title: String,
    description: String,
    isGranted: Boolean,
    onGrantClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White
            )
            Text(
                text = description,
                fontSize = 12.sp,
                color = TextSecondary,
                modifier = Modifier.padding(top = 2.dp)
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        if (isGranted) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = "已授权",
                tint = SuccessGreen,
                modifier = Modifier.size(28.dp)
            )
        } else {
            Button(
                onClick = onGrantClick,
                colors = ButtonDefaults.buttonColors(containerColor = WarningOrange),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                modifier = Modifier.height(32.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text("授权", fontSize = 11.sp, color = Color.Black, fontWeight = FontWeight.Bold)
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
            color = PhantomCyan,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = text,
            fontSize = 12.sp,
            color = TextSecondary,
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
