package com.libremobileos.sidebar.ui.all_app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.libremobileos.sidebar.app.SidebarApplication
import com.libremobileos.sidebar.bean.AppInfo
import com.libremobileos.sidebar.ui.theme.SidebarTheme
import com.libremobileos.sidebar.utils.Logger

/**
 * @author KindBrave
 * @since 2023/10/25
 */
class AllAppActivity: ComponentActivity() {
    private val logger = Logger(TAG)
    private val viewModel: AllAppViewModel by viewModels { AllAppViewModel.Factory }

    companion object {
        private const val PACKAGE = "com.libremobileos.freeform"
        private const val ACTION = "com.libremobileos.freeform.START_FREEFORM"
        private const val TAG = "AllAppActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        logger.d("onCreate")
        window.setBackgroundDrawableResource(android.R.color.transparent)

        val sharedPrefs = getSharedPreferences(SidebarApplication.CONFIG, Context.MODE_PRIVATE)
        val heightPercent = sharedPrefs.getInt(
            SidebarApplication.KEY_ALL_APPS_HEIGHT_PERCENT,
            SidebarApplication.DEFAULT_ALL_APPS_HEIGHT_PERCENT
        )

        val windowStyle = sharedPrefs.getString(
            SidebarApplication.KEY_FREEFORM_WINDOW_STYLE,
            SidebarApplication.STYLE_STOCK
        ) ?: SidebarApplication.STYLE_STOCK

        setContent {
            SidebarTheme {
                AllAppScreen(
                    viewModel = viewModel,
                    heightPercent = heightPercent,
                    windowStyle = windowStyle,
                    onClick = ::onClick,
                    onDismiss = { finish() }
                )
            }
        }
    }

    override fun onDestroy() {
        logger.d("onDestroy")
        super.onDestroy()
    }

    private fun onClick(appInfo: AppInfo) {
        val intent = Intent(ACTION).apply {
            setPackage(PACKAGE)
            putExtra("packageName", appInfo.packageName)
            putExtra("activityName", appInfo.activityName)
            putExtra("userId", appInfo.userId)
        }
        sendBroadcast(intent)
        finish()
    }
}

@Composable
fun AllAppScreen(
    viewModel: AllAppViewModel,
    heightPercent: Int,
    windowStyle: String,
    onClick: (AppInfo) -> Unit,
    onDismiss: () -> Unit
) {
    if (heightPercent >= 100) {
        if (windowStyle == SidebarApplication.STYLE_NEW) {
            CustomAllAppGridView(
                viewModel = viewModel,
                onClick = onClick,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            AllAppGridView(
                viewModel = viewModel,
                onClick = onClick,
                modifier = Modifier.fillMaxSize()
            )
        }
    } else {
        val heightFraction = (heightPercent / 100f).coerceIn(0.35f, 1.0f)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.5f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { onDismiss() },
            contentAlignment = Alignment.BottomCenter
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(heightFraction)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { /* prevent dismiss when clicking drawer surface */ }
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Surface(
                            shape = RoundedCornerShape(2.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                            modifier = Modifier.size(width = 36.dp, height = 4.dp)
                        ) {}
                    }
                    if (windowStyle == SidebarApplication.STYLE_NEW) {
                        CustomAllAppGridView(
                            viewModel = viewModel,
                            onClick = onClick,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        AllAppGridView(
                            viewModel = viewModel,
                            onClick = onClick,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }
    }
}
