package com.libremobileos.sidebar.service

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.android.settingslib.spa.framework.compose.rememberDrawablePainter
import com.libremobileos.sidebar.bean.AppInfo

@Composable
fun CustomSidebarComposeView(
    viewModel: ServiceViewModel,
    launchApp: (AppInfo) -> Unit,
    closeSidebar: () -> Unit,
    modifier: Modifier = Modifier
) {
    val hyperOSAppList by viewModel.hyperOSAppListFlow.collectAsState()
    val isBackgroundDark by viewModel.isBackgroundDarkFlow.collectAsState()

    val dockWidth = 56.dp
    val itemSize = 56.dp
    val iconSize = 42.dp

    val targetContainerColor = if (isBackgroundDark) {
        Color(0x3BFFFFFF)
    } else {
        Color(0x6E1E2024)
    }

    val targetBorderColor = if (isBackgroundDark) {
        Color(0x4DFFFFFF)
    } else {
        Color(0x2E000000)
    }

    val animatedContainerColor by animateColorAsState(
        targetValue = targetContainerColor,
        animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
        label = "sidebarContainerColor"
    )

    val animatedBorderColor by animateColorAsState(
        targetValue = targetBorderColor,
        animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
        label = "sidebarBorderColor"
    )

    val containerShape = RoundedCornerShape(28.dp)

    Box(
        modifier = modifier
            .width(dockWidth)
            .clip(containerShape)
            .background(animatedContainerColor)
            .border(width = 1.dp, color = animatedBorderColor, shape = containerShape)
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = PaddingValues(vertical = 10.dp)
        ) {
            items(
                items = hyperOSAppList,
                key = { "${it.packageName}/${it.activityName}/${it.userId}" }
            ) { appInfo ->
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(itemSize)
                        .padding(vertical = 4.dp, horizontal = 6.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .clickable {
                            launchApp(appInfo)
                        }
                ) {
                    Image(
                        painter = rememberDrawablePainter(drawable = appInfo.icon),
                        contentDescription = appInfo.label,
                        modifier = Modifier
                            .size(iconSize)
                            .clip(RoundedCornerShape(12.dp))
                    )
                }
            }
        }
    }
}
