package com.cablebee.assistant.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

/**
 * 响应式布局框架（对应 Flutter 版 lib/utils/responsive.dart）
 *
 * 断点与 Flutter 版一致：
 *  - medium: 600dp（大屏手机 / 小平板竖屏）
 *  - expanded: 905dp（平板横屏 / 大平板竖屏）
 *  - maxContentWidth: 720dp（PageBody 内容最大宽度）
 *  - maxWideContentWidth: 1200dp（WidePageBody 内容最大宽度）
 *  - masterDetailBreakpoint: 840dp（主从双栏断点）
 *  - railWidth: 220dp（横屏平板侧边导航宽度）
 *  - tabletSmallestWidth: 600dp（sw600dp，Android 官方平板判定）
 */

private const val MEDIUM_BREAKPOINT = 600
private const val EXPANDED_BREAKPOINT = 905
private const val MASTER_DETAIL_BREAKPOINT = 840
/** 平板设备判定阈值：smallestWidth >= 600dp，横竖屏、分屏旋转都不改变结果 */
private const val TABLET_SMALLEST_WIDTH = 600

val MaxContentWidth = 720.dp
val MaxWideContentWidth = 1200.dp
val RailWidth = 220.dp
/** 窄平板 / 平板竖屏使用的较窄侧边导航宽度，避免内容区被挤得太窄 */
val NarrowRailWidth = 180.dp

/** 弹窗在平板/宽屏上的最大宽度，避免弹窗被拉成贯穿屏幕的长条 */
val MaxDialogWidth = 520.dp
/** 统一弹窗宽度修饰（不可变 Modifier，可直接用于 AlertDialog / Surface 的 modifier） */
val DialogWidthModifier: Modifier = Modifier.widthIn(max = MaxDialogWidth)

enum class ScreenSize { Compact, Medium, Expanded }

/** 当前屏幕宽度（dp） */
@Composable
fun screenWidthDp(): Int = LocalConfiguration.current.screenWidthDp

/** 当前屏幕高度（dp） */
@Composable
fun screenHeightDp(): Int = LocalConfiguration.current.screenHeightDp

@Composable
fun screenSizeOf(): ScreenSize {
    val w = screenWidthDp()
    return when {
        w >= EXPANDED_BREAKPOINT -> ScreenSize.Expanded
        w >= MEDIUM_BREAKPOINT -> ScreenSize.Medium
        else -> ScreenSize.Compact
    }
}

/**
 * 是否为平板设备。
 *
 * 关键：使用 smallestScreenWidthDp（sw600dp）而不是当前宽度 screenWidthDp。
 *  - 手机横屏时当前宽度可能达到 800dp+，用宽度判定会被误判为平板；
 *  - 平板竖屏时当前宽度可能只有 600~800dp，用「>=905 且宽>高」判定会被漏掉。
 * smallestWidth 是设备（窗口）的最短边，与旋转方向无关，因此平板在横屏、竖屏、
 * 分屏下都能稳定判定，手机无论横竖屏都保持手机布局。
 */
@Composable
fun isTablet(): Boolean =
    LocalConfiguration.current.smallestScreenWidthDp >= TABLET_SMALLEST_WIDTH

/** 当前窗口是否属于宽屏布局（用于两列网格等，仍按当前宽度判断） */
@Composable
fun isExpanded(): Boolean = screenWidthDp() >= EXPANDED_BREAKPOINT

/** 是否应该使用主从双栏布局（对应 Flutter Responsive.useMasterDetail） */
@Composable
fun useMasterDetail(): Boolean {
    val w = screenWidthDp()
    val h = screenHeightDp()
    return w >= MASTER_DETAIL_BREAKPOINT && w > h
}

/**
 * PageBody：统一的页面内容容器。
 * 手机上等同于直接放置 child；平板上限制最大宽度并水平居中（对应 Flutter PageBody）。
 */
@Composable
fun PageBody(
    modifier: Modifier = Modifier,
    maxWidth: Dp = MaxContentWidth,
    content: @Composable BoxScope.() -> Unit,
) {
    if (!isTablet()) {
        Box(modifier = modifier, content = content)
    } else {
        Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            Box(
                modifier = Modifier
                    .widthIn(max = maxWidth)
                    .fillMaxWidth(),
                content = content
            )
        }
    }
}

/** 更宽的内容容器（对应 Flutter WidePageBody，最大 1200dp） */
@Composable
fun WidePageBody(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    PageBody(modifier = modifier, maxWidth = MaxWideContentWidth, content = content)
}

/**
 * 响应式弹窗：替代 androidx.compose.ui.window.Dialog。
 * 手机端保持系统默认弹窗宽度；平板上限制最大宽度并居中，避免内容被横向拉伸。
 */
@Composable
fun ResponsiveDialog(
    onDismissRequest: () -> Unit,
    content: @Composable () -> Unit
) {
    Dialog(onDismissRequest = onDismissRequest) {
        Box(modifier = DialogWidthModifier) {
            content()
        }
    }
}
