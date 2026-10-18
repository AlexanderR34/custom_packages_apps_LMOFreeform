package com.libremobileos.sidebar.service

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.android.settingslib.spa.framework.compose.rememberDrawablePainter
import com.libremobileos.sidebar.R
import com.libremobileos.sidebar.bean.AppInfo

@Composable
fun CustomSidebarComposeView(
    viewModel: ServiceViewModel,
    launchApp: (AppInfo) -> Unit,
    closeSidebar: () -> Unit,
    modifier: Modifier = Modifier
) {
    val hyperOSAppList by viewModel.hyperOSAppListFlow.collectAsState()

    val dockWidth = 60.dp
    val iconSize = 44.dp
    val buttonSize = 44.dp

    // Solid Monet dynamic color adapting to wallpaper theme
    val containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
    val borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
    val containerShape = RoundedCornerShape(26.dp)

    Box(
        modifier = modifier
            .width(dockWidth)
            .clip(containerShape)
            .background(containerColor)
            .border(width = 1.dp, color = borderColor, shape = containerShape)
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(vertical = 12.dp)
        ) {
            items(
                items = hyperOSAppList,
                key = { "${it.packageName}/${it.activityName}/${it.userId}" }
            ) { appInfo ->
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(buttonSize)
                        .clip(RoundedCornerShape(12.dp))
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

            // Red edit/manage button at the bottom as shown in HyperOS design reference
            item {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(buttonSize)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFFE53935))
                        .clickable {
                            viewModel.openSidebarSettings()
                            closeSidebar()
                        }
                ) {
                    Icon(
                        painter = painterResource(R.drawable.edit_24px),
                        contentDescription = stringResource(R.string.sidebar_settings_description),
                        tint = Color.White,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
    }
}
