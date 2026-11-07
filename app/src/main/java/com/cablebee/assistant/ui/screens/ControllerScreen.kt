package com.cablebee.assistant.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.VolumeDown
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cablebee.assistant.services.AdbService
import com.cablebee.assistant.ui.components.PageBody
import com.cablebee.assistant.ui.theme.Bg0
import com.cablebee.assistant.ui.theme.Bg1
import com.cablebee.assistant.ui.theme.Bg3
import com.cablebee.assistant.ui.theme.Danger
import com.cablebee.assistant.ui.theme.JetBrainsMono
import com.cablebee.assistant.ui.theme.Primary
import com.cablebee.assistant.ui.theme.Secondary
import com.cablebee.assistant.ui.theme.SpaceMono
import com.cablebee.assistant.ui.theme.TextMuted
import com.cablebee.assistant.ui.theme.TextPrimary
import com.cablebee.assistant.ui.theme.TextSecondary
import com.cablebee.assistant.ui.theme.Warning
import kotlinx.coroutines.launch

// ── Android keyevent constants ────────────────────────────────────────────────

private const val KEY_POWER = 26
private const val KEY_BACK = 4
private const val KEY_HOME = 3
private const val KEY_MENU = 82
private const val KEY_DPAD_UP = 19
private const val KEY_DPAD_DOWN = 20
private const val KEY_DPAD_LEFT = 21
private const val KEY_DPAD_RIGHT = 22
private const val KEY_DPAD_CENTER = 23
private const val KEY_VOL_UP = 24
private const val KEY_VOL_DOWN = 25
private const val KEY_MUTE = 164
private const val KEY_0 = 7

// ── ControllerScreen ──────────────────────────────────────────────────────────

@Composable
fun ControllerScreen(
    adbService: AdbService,
    onActionsChanged: (@Composable RowScope.() -> Unit) -> Unit = {},
    actionsVersion: Int = 0
) {
    val scope = rememberCoroutineScope()

    // AppBar 动作：遥控页无动作（对应 Flutter refreshActions → []）
    LaunchedEffect(actionsVersion) {
        onActionsChanged {}
    }

    fun sendKey(keyCode: Int) {
        scope.launch {
            adbService.shell("input keyevent $keyCode")
        }
    }

    // PageBody(maxWidth 420)：手机上铺满，平板限宽居中（对应 Flutter PageBody）
    PageBody(modifier = Modifier.fillMaxSize(), maxWidth = 420.dp) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(color = Bg0)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top
        ) {
        // ── Top row: Volume- / Power / Volume+ ────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            KeyBtn(
                icon = Icons.Rounded.VolumeDown,
                label = "音量-",
                color = TextMuted,
                onTap = { sendKey(KEY_VOL_DOWN) }
            )
            KeyBtn(
                icon = Icons.Rounded.PowerSettingsNew,
                label = "电源",
                color = Danger,
                size = 56.dp,
                onTap = { sendKey(KEY_POWER) }
            )
            KeyBtn(
                icon = Icons.Rounded.VolumeUp,
                label = "音量+",
                color = TextMuted,
                onTap = { sendKey(KEY_VOL_UP) }
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        // ── D-pad cluster ─────────────────────────────────────────────────
        DpadCluster(onKey = { sendKey(it) })

        Spacer(modifier = Modifier.height(20.dp))

        // ── Navigation row: Menu / Home / Back ────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            KeyBtn(
                icon = Icons.Rounded.Menu,
                label = "菜单",
                color = Secondary,
                onTap = { sendKey(KEY_MENU) }
            )
            KeyBtn(
                icon = Icons.Rounded.Home,
                label = "主页",
                color = Primary,
                onTap = { sendKey(KEY_HOME) }
            )
            KeyBtn(
                icon = Icons.Rounded.ArrowBack,
                label = "返回",
                color = Warning,
                onTap = { sendKey(KEY_BACK) }
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // ── Number pad ────────────────────────────────────────────────────
        NumPad(onKey = { sendKey(it) })
        }
    }
}

// ── D-pad Cluster ─────────────────────────────────────────────────────────────

@Composable
private fun DpadCluster(onKey: (Int) -> Unit) {
    val btnSize = 56.dp
    val centerSize = 68.dp
    val clusterSize = btnSize * 3 + 8.dp  // 176.dp

    Box(modifier = Modifier.size(clusterSize)) {
        // Background circle
        Box(
            modifier = Modifier
                .size(clusterSize)
                .clip(CircleShape)
                .background(color = Bg1)
                .border(1.5.dp, Bg3, CircleShape)
        )

        // Up arrow
        DpadArrow(
            icon = Icons.Rounded.KeyboardArrowUp,
            onTap = { onKey(KEY_DPAD_UP) },
            modifier = Modifier.align(Alignment.TopCenter)
        )

        // Down arrow
        DpadArrow(
            icon = Icons.Rounded.KeyboardArrowDown,
            onTap = { onKey(KEY_DPAD_DOWN) },
            modifier = Modifier.align(Alignment.BottomCenter)
        )

        // Left arrow
        DpadArrow(
            icon = Icons.Rounded.KeyboardArrowLeft,
            onTap = { onKey(KEY_DPAD_LEFT) },
            modifier = Modifier.align(Alignment.CenterStart)
        )

        // Right arrow
        DpadArrow(
            icon = Icons.Rounded.KeyboardArrowRight,
            onTap = { onKey(KEY_DPAD_RIGHT) },
            modifier = Modifier.align(Alignment.CenterEnd)
        )

        // Center OK button
        val interactionSource = remember { MutableInteractionSource() }
        val pressed by interactionSource.collectIsPressedAsState()

        val bgColor by animateColorAsState(
            targetValue = if (pressed) Primary.copy(alpha = 0.8f) else Primary,
            animationSpec = tween(durationMillis = 80),
            label = "okBg"
        )

        Box(
            modifier = Modifier
                .size(centerSize)
                .align(Alignment.Center)
                .shadow(12.dp, CircleShape, clip = false)
                .clip(CircleShape)
                .background(color = bgColor)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = { onKey(KEY_DPAD_CENTER) }
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "OK",
                style = TextStyle(
                    fontFamily = SpaceMono,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.White
                )
            )
        }
    }
}

// ── D-pad Arrow Button ────────────────────────────────────────────────────────

@Composable
private fun DpadArrow(
    icon: ImageVector,
    onTap: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()

    val bgColor by animateColorAsState(
        targetValue = if (pressed) Primary.copy(alpha = 0.2f) else Color.Transparent,
        animationSpec = tween(durationMillis = 80),
        label = "dpadBg"
    )
    val iconColor by animateColorAsState(
        targetValue = if (pressed) Primary else TextSecondary,
        animationSpec = tween(durationMillis = 80),
        label = "dpadIcon"
    )

    Box(
        modifier = modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(color = bgColor)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onTap
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = iconColor,
            modifier = Modifier.size(30.dp)
        )
    }
}

// ── Key Button (circle with icon + label) ─────────────────────────────────────

@Composable
private fun KeyBtn(
    icon: ImageVector,
    label: String,
    color: Color,
    size: androidx.compose.ui.unit.Dp = 52.dp,
    onTap: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()

    val bgColor by animateColorAsState(
        targetValue = if (pressed) color.copy(alpha = 0.25f) else color.copy(alpha = 0.1f),
        animationSpec = tween(durationMillis = 80),
        label = "keyBtnBg"
    )
    val borderColor by animateColorAsState(
        targetValue = if (pressed) color else color.copy(alpha = 0.3f),
        animationSpec = tween(durationMillis = 80),
        label = "keyBtnBorder"
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(color = bgColor)
                .border(1.5.dp, borderColor, CircleShape)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onTap
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon,
                contentDescription = label,
                tint = color,
                modifier = Modifier.size((size.value * 0.42f).dp)
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            label,
            style = TextStyle(
                fontFamily = SpaceMono,
                fontSize = 10.sp,
                color = color.copy(alpha = 0.8f)
            )
        )
    }
}

// ── Number Pad ────────────────────────────────────────────────────────────────

@Composable
private fun NumPad(onKey: (Int) -> Unit) {
    val rows = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf(" ", "0", " ")
    )

    Column {
        rows.forEach { row ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                row.forEach { n ->
                    if (n == " ") {
                        Spacer(modifier = Modifier.size(width = 64.dp, height = 56.dp))
                    } else {
                        NumBtn(n = n, onTap = { onKey(KEY_0 + n.toInt()) })
                    }
                }
            }
        }
    }
}

// ── Number Button ─────────────────────────────────────────────────────────────

@Composable
private fun NumBtn(n: String, onTap: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()

    val bgColor by animateColorAsState(
        targetValue = if (pressed) Primary else Bg1,
        animationSpec = tween(durationMillis = 80),
        label = "numBtnBg"
    )
    val borderColor by animateColorAsState(
        targetValue = if (pressed) Primary else Bg3,
        animationSpec = tween(durationMillis = 80),
        label = "numBtnBorder"
    )
    val textColor by animateColorAsState(
        targetValue = if (pressed) Color.White else TextPrimary,
        animationSpec = tween(durationMillis = 80),
        label = "numBtnText"
    )

    Box(
        modifier = Modifier
            .size(width = 64.dp, height = 56.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(color = bgColor)
            .border(1.5.dp, borderColor, RoundedCornerShape(12.dp))
            .then(
                if (!pressed) Modifier.shadow(
                    4.dp,
                    RoundedCornerShape(12.dp)
                ) else Modifier
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onTap
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            n,
            style = TextStyle(
                fontFamily = SpaceMono,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = textColor
            )
        )
    }
}
