package com.libremobileos.sidebar.service

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.android.settingslib.spa.framework.compose.rememberDrawablePainter
import com.libremobileos.sidebar.R
import com.libremobileos.sidebar.bean.AppInfo

@Composable
fun SidebarComposeView(
    viewModel: ServiceViewModel,
    launchApp: (AppInfo) -> Unit,
    closeSidebar: () -> Unit,
    modifier: Modifier = Modifier
) {
    val sidebarAppList by viewModel.sidebarAppListFlow.collectAsState()
    val sidebarWidthDp by viewModel.sidebarWidthDpFlow.collectAsState()

    val itemSize = sidebarWidthDp.dp
    val iconPadding = (sidebarWidthDp * 0.16f).dp
    val editPadding = (sidebarWidthDp * 0.20f).dp

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        shape = RoundedCornerShape(16.dp),
        modifier = modifier.width(itemSize)
    ) {
        LazyColumn {
            item {
                Icon(
                    painter = rememberDrawablePainter(
                        drawable = viewModel.allAppActivity.icon
                    ),
                    contentDescription = viewModel.allAppActivity.label,
                    modifier = Modifier
                        .size(itemSize)
                        .padding(iconPadding)
                        .clickable {
                            launchApp(viewModel.allAppActivity)
                        }
                )
            }
            items(sidebarAppList) { appInfo ->
                Image(
                    painter = rememberDrawablePainter(
                        drawable = appInfo.icon
                    ),
                    contentDescription = appInfo.label,
                    modifier = Modifier
                        .size(itemSize)
                        .padding(iconPadding)
                        .clickable {
                            launchApp(appInfo)
                        }
                )
            }
            item {
                Icon(
                    painter = painterResource(R.drawable.edit_24px),
                    contentDescription = stringResource(R.string.sidebar_settings_description),
                    modifier = Modifier
                        .size(itemSize)
                        .padding(editPadding)
                        .clickable {
                            viewModel.openSidebarSettings()
                            closeSidebar()
                        }
                )
            }
        }
    }
}
