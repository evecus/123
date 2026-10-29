package com.cablebee.assistant.ui.components

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.cablebee.assistant.models.DeviceState
import com.cablebee.assistant.ui.theme.*

@Composable
fun DeviceStateBadge(state: DeviceState) {
    val (color, label) = when (state) {
        DeviceState.ONLINE -> Success to "online"
        DeviceState.OFFLINE -> TextMuted to "offline"
        DeviceState.UNAUTHORIZED -> Warning to "auth?"
        DeviceState.CONNECTING -> Secondary to "connecting"
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.12f))
            .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(4.dp))
            .padding(horizontal = 7.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(5.dp)
                .clip(RoundedCornerShape(50))
                .background(color)
        )
        Spacer(modifier = Modifier.width(5.dp))
        Text(label, style = MaterialTheme.typography.labelSmall.copy(
            fontFamily = SpaceMono,
            fontWeight = FontWeight.SemiBold, color = color, fontSize = 10.sp,
            letterSpacing = 0.3.sp
        ))
    }
}

@Composable
fun SectionHeader(
    title: String,
    subtitle: String? = null,
    trailing: @Composable (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, top = 20.dp, end = 16.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = SpaceMono,
                fontWeight = FontWeight.SemiBold, color = TextMuted, fontSize = 11.sp,
                letterSpacing = 1.2.sp
            ))
            if (subtitle != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(subtitle, style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = JetBrainsMono,
                    color = TextSecondary, fontSize = 12.sp
                ))
            }
        }
        if (trailing != null) trailing()
    }
}

@Composable
fun TintedCard(
    modifier: Modifier = Modifier,
    borderColor: Color? = null,
    onTap: (() -> Unit)? = null,
    padding: PaddingValues = PaddingValues(16.dp),
    content: @Composable () -> Unit
) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Bg1)
            .border(1.dp, borderColor ?: Bg3, shape)
            .then(if (onTap != null) Modifier.clickable(onClick = onTap) else Modifier)
            .padding(padding)
    ) {
        content()
    }
}

@Composable
fun TerminalBox(
    text: String,
    showCopyButton: Boolean = true,
    maxLines: Int = 20
) {
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Bg1)
            .border(1.dp, Bg3, RoundedCornerShape(8.dp))
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row {
                Box(modifier = Modifier.size(10.dp).clip(RoundedCornerShape(50)).background(Color(0xFFFF5F56)))
                Spacer(modifier = Modifier.width(6.dp))
                Box(modifier = Modifier.size(10.dp).clip(RoundedCornerShape(50)).background(Color(0xFFFFBD2E)))
                Spacer(modifier = Modifier.width(6.dp))
                Box(modifier = Modifier.size(10.dp).clip(RoundedCornerShape(50)).background(Color(0xFF27C93F)))
            }
            Spacer(modifier = Modifier.weight(1f))
            if (showCopyButton && text.isNotEmpty()) {
                Icon(
                    Icons.Default.ContentCopy,
                    contentDescription = "复制",
                    tint = TextMuted,
                    modifier = Modifier
                        .size(14.dp)
                        .clickable {
                            clipboardManager.setText(AnnotatedString(text))
                            Toast.makeText(context, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
                        }
                )
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        SelectionContainer {
            Text(
                text = if (text.isEmpty()) "// output will appear here" else text,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = JetBrainsMono, fontSize = 12.sp,
                    color = if (text.isEmpty()) TextMuted else Primary.copy(alpha = 0.9f),
                    lineHeight = 19.sp
                ),
                maxLines = maxLines,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun ActionTile(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    iconColor: Color = Primary,
    onTap: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
    enabled: Boolean = true
) {
    val alpha = if (enabled) 1f else 0.4f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (enabled && onTap != null) Modifier.clickable(onClick = onTap) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(iconColor.copy(alpha = 0.1f))
                .border(1.dp, iconColor.copy(alpha = 0.2f), RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = title, tint = iconColor, modifier = Modifier.size(18.dp))
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium.copy(
                fontFamily = SpaceMono,
                fontWeight = FontWeight.SemiBold, color = TextPrimary, fontSize = 13.sp
            ))
            if (subtitle != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(subtitle, style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = JetBrainsMono,
                    color = TextSecondary, fontSize = 11.sp
                ))
            }
        }
        if (trailing != null) trailing()
        else Icon(Icons.Default.ChevronRight, contentDescription = null, tint = TextMuted, modifier = Modifier.size(18.dp))
    }
}

@Composable
fun StatCell(label: String, value: String, valueColor: Color = TextPrimary, icon: ImageVector? = null) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Bg1)
            .border(1.dp, Bg3, RoundedCornerShape(10.dp))
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = TextMuted, modifier = Modifier.size(12.dp))
                Spacer(modifier = Modifier.width(4.dp))
            }
            Text(label, style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = SpaceMono,
                color = TextMuted, fontSize = 10.sp, letterSpacing = 0.8.sp
            ))
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(value, style = MaterialTheme.typography.bodyLarge.copy(
            fontFamily = JetBrainsMono,
            fontWeight = FontWeight.Bold, color = valueColor, fontSize = 14.sp
        ))
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, message: String? = null, action: @Composable (() -> Unit)? = null) {
    Column(
        modifier = Modifier.fillMaxSize().padding(40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(icon, contentDescription = null, tint = TextMuted, modifier = Modifier.size(48.dp))
        Spacer(modifier = Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.bodyLarge.copy(
            fontFamily = SpaceMono,
            fontWeight = FontWeight.SemiBold, color = TextSecondary, fontSize = 15.sp
        ), textAlign = TextAlign.Center)
        if (message != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(message, style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = JetBrainsMono,
                color = TextMuted, fontSize = 12.sp
            ), textAlign = TextAlign.Center)
        }
        if (action != null) {
            Spacer(modifier = Modifier.height(24.dp))
            action()
        }
    }
}

@Composable
fun CbeeLoader(message: String? = null) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(32.dp),
            strokeWidth = 2.dp,
            color = Primary
        )
        if (message != null) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(message, style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = JetBrainsMono,
                color = TextSecondary, fontSize = 12.sp
            ))
        }
    }
}

// ── Confirm Dialog ────────────────────────────────────────────────────────────

@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmText: String = "Confirm",
    destructive: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = Bg1,
            border = BorderStroke(1.dp, Bg3)
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontFamily = SpaceMono,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    message,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = JetBrainsMono,
                        fontSize = 13.sp,
                        color = TextSecondary,
                        lineHeight = 19.5.sp
                    )
                )
                Spacer(modifier = Modifier.height(24.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(
                            "Cancel",
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontFamily = SpaceMono,
                                color = TextSecondary
                            )
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = onConfirm,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (destructive) Danger else Primary,
                            contentColor = Bg0
                        )
                    ) {
                        Text(confirmText)
                    }
                }
            }
        }
    }
}

// ── Input Dialog ──────────────────────────────────────────────────────────────

@Composable
fun InputDialog(
    title: String,
    hint: String? = null,
    initialValue: String? = null,
    confirmText: String = "OK",
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(initialValue ?: "") }
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = Bg1,
            border = BorderStroke(1.dp, Bg3)
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontFamily = SpaceMono,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                )
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    placeholder = hint?.let { { Text(it) } },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = JetBrainsMono,
                        fontSize = 14.sp,
                        color = TextPrimary
                    )
                )
                Spacer(modifier = Modifier.height(24.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(
                            "Cancel",
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontFamily = SpaceMono,
                                color = TextSecondary
                            )
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = { onConfirm(text) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Primary,
                            contentColor = Bg0
                        )
                    ) {
                        Text(confirmText)
                    }
                }
            }
        }
    }
}

// ── Picker Dialog ─────────────────────────────────────────────────────────────

@Composable
fun <T> PickerDialog(
    title: String,
    options: List<T>,
    labels: List<String>,
    current: T? = null,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = Bg1,
            border = BorderStroke(1.dp, Bg3)
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontFamily = SpaceMono,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                )
                Spacer(modifier = Modifier.height(16.dp))
                options.forEachIndexed { index, option ->
                    val isSelected = option == current
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onSelect(option)
                                onDismiss()
                            }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            labels.getOrElse(index) { option.toString() },
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontFamily = JetBrainsMono,
                                fontSize = 13.sp,
                                color = if (isSelected) Primary else TextPrimary,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        if (isSelected) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = null,
                                tint = Primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

// ── Snackbar ──────────────────────────────────────────────────────────────────

suspend fun showSnackbar(
    snackbarHostState: SnackbarHostState,
    message: String,
    isSuccess: Boolean = true
) {
    snackbarHostState.showSnackbar(
        message = message,
        duration = SnackbarDuration.Short
    )
}

@Composable
fun CbeeSnackbarHost(
    snackbarHostState: SnackbarHostState,
    isSuccess: Boolean = true,
    modifier: Modifier = Modifier
) {
    SnackbarHost(
        hostState = snackbarHostState,
        modifier = modifier
    ) { data ->
        Snackbar(
            snackbarData = data,
            containerColor = if (isSuccess) Success else Danger,
            contentColor = Bg0
        )
    }
}

/**
 * 底部悬浮提示：不透明背景，无关闭按钮，[durationMs] 后自动消失。
 * 请放在页面最外层 Box 内，用 Alignment.BottomCenter（位于底部导航栏上方的内容区内）。
 */
@Composable
fun FloatingToast(
    message: String?,
    ok: Boolean = true,
    durationMs: Long = 3000L,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (message.isNullOrBlank()) return

    LaunchedEffect(message) {
        kotlinx.coroutines.delay(durationMs)
        onDismiss()
    }

    val bg = if (ok) Success else Danger
    val fg = Bg0
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(bg) // 不透明实心背景
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (ok) Icons.Filled.CheckCircle else Icons.Filled.Error,
            contentDescription = null,
            tint = fg,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            message,
            modifier = Modifier.weight(1f),
            fontFamily = JetBrainsMono,
            fontSize = 12.sp,
            color = fg,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** 根据文案粗略判断成功/失败（含 ✓✗ 或「失败」关键字） */
fun toastIsOk(message: String): Boolean {
    if (message.startsWith("✗") || message.contains("失败")) return false
    if (message.startsWith("✓")) return true
    return !message.contains("error", ignoreCase = true)
}
