package com.cablebee.assistant.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cablebee.assistant.models.AdbDevice
import com.cablebee.assistant.models.DeviceState
import com.cablebee.assistant.ui.theme.*

@Composable
fun DeviceCard(
    device: AdbDevice,
    selected: Boolean = false,
    onTap: (() -> Unit)? = null,
    onDisconnect: (() -> Unit)? = null
) {
    val borderColor = if (selected) Primary.copy(alpha = 0.5f) else Bg3
    TintedCard(
        borderColor = borderColor,
        onTap = onTap,
        padding = PaddingValues(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background((if (selected) Primary else TextMuted).copy(alpha = 0.1f))
                    .border(1.dp, (if (selected) Primary else Bg3).copy(alpha = 0.4f), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (device.isWifi) Icons.Default.Wifi else Icons.Default.Usb,
                    contentDescription = null,
                    tint = if (selected) Primary else TextSecondary,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        device.displayName,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 13.sp,
                            fontFamily = SpaceMono
                        ),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    DeviceStateBadge(device.state)
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    device.serial,
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = TextMuted, fontSize = 11.sp, fontFamily = JetBrainsMono
                    )
                )
            }
            if (onDisconnect != null) {
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(
                    onClick = onDisconnect,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Bg2)
                ) {
                    Icon(Icons.Default.LinkOff, contentDescription = "断开", tint = TextMuted, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
fun DeviceHeaderBar(device: AdbDevice, onChangeDevice: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Bg1)
            .border(1.dp, Primary.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
            .clickable(onClick = onChangeDevice)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PulsingDot(active = device.state == DeviceState.ONLINE)
        Spacer(modifier = Modifier.width(10.dp))
        Icon(
            if (device.isWifi) Icons.Default.Wifi else Icons.Default.Usb,
            contentDescription = null, tint = Primary, modifier = Modifier.size(15.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            device.displayName,
            style = MaterialTheme.typography.bodySmall.copy(
                fontWeight = FontWeight.SemiBold, color = TextPrimary, fontSize = 12.sp,
                fontFamily = SpaceMono
            ),
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text("change", style = MaterialTheme.typography.labelSmall.copy(
            color = Primary, fontSize = 10.sp, letterSpacing = 0.3.sp,
            fontFamily = SpaceMono
        ))
        Spacer(modifier = Modifier.width(2.dp))
        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Primary, modifier = Modifier.size(14.dp))
    }
}

@Composable
fun PulsingDot(active: Boolean) {
    if (!active) {
        Box(modifier = Modifier.size(8.dp).clip(RoundedCornerShape(50)).background(TextMuted))
        return
    }
    val transition = rememberInfiniteTransition(label = "pulse")
    val alpha by transition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )
    Box(
        modifier = Modifier
            .size(8.dp)
            .shadow(
                elevation = 4.dp,
                shape = RoundedCornerShape(50),
                ambientColor = Success.copy(alpha = alpha * 0.5f),
                spotColor = Success.copy(alpha = alpha * 0.5f)
            )
            .clip(RoundedCornerShape(50))
            .background(Success.copy(alpha = alpha))
    )
}

@Composable
fun NoDevicePlaceholder(onConnect: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(80.dp)
                .clip(RoundedCornerShape(50))
                .background(Bg1)
                .border(2.dp, Bg3, RoundedCornerShape(50)),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.PhonelinkOff, contentDescription = null, tint = TextMuted, modifier = Modifier.size(36.dp))
        }
        Spacer(modifier = Modifier.height(20.dp))
        Text("No device connected", style = MaterialTheme.typography.bodyLarge.copy(
            fontFamily = SpaceMono,
            fontWeight = FontWeight.Bold, color = TextSecondary, fontSize = 16.sp
        ))
        Spacer(modifier = Modifier.height(8.dp))
        Text("Connect a device via Wi-Fi or USB\nto start using CableBee",
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = JetBrainsMono,
                color = TextMuted, fontSize = 12.sp, lineHeight = 19.sp
            ),
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(28.dp))
        Button(onClick = onConnect, colors = ButtonDefaults.buttonColors(containerColor = Primary)) {
            Icon(Icons.Default.AddLink, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("Connect Device")
        }
    }
}
