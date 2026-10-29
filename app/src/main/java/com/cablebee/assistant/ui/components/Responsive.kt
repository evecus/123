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
 */

private const val MEDIUM_BREAKPOINT = 600
private const val EXPANDED_BREAKPOINT = 905
private const val MASTER_DETAIL_BREAKPOINT = 840

val MaxContentWidth = 720.dp
val MaxWideContentWidth = 1200.dp
val RailWidth = 220.dp

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

@Composable
fun isTablet(): Boolean = screenWidthDp() >= MEDIUM_BREAKPOINT

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
