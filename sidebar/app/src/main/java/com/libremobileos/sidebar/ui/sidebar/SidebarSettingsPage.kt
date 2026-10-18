package com.libremobileos.sidebar.ui.sidebar

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.rememberNavController
import com.android.settingslib.spa.framework.compose.localNavController
import com.android.settingslib.spa.framework.compose.rememberDrawablePainter
import com.android.settingslib.spa.framework.theme.SettingsDimension
import com.android.settingslib.spa.widget.preference.MainSwitchPreference
import com.android.settingslib.spa.widget.preference.Preference
import com.android.settingslib.spa.widget.preference.PreferenceModel
import com.android.settingslib.spa.widget.preference.SwitchPreference
import com.android.settingslib.spa.widget.preference.SwitchPreferenceModel
import com.android.settingslib.spa.widget.scaffold.SettingsScaffold
import com.android.settingslib.spa.widget.ui.Category
import com.libremobileos.sidebar.R
import com.libremobileos.sidebar.app.SidebarApplication
import com.libremobileos.sidebar.bean.SidebarAppInfo
import kotlin.math.roundToInt

@Composable
fun SidebarSettingsPage(
    viewModel: SidebarSettingsViewModel
) {
    val navController = rememberNavController()
    var mainChecked = rememberSaveable { mutableStateOf(viewModel.getSidebarEnabled()) }
    var showDesignSubmenu = rememberSaveable { mutableStateOf(false) }

    CompositionLocalProvider(navController.localNavController()) {
        if (showDesignSubmenu.value) {
            SidebarDesignSettingsPage(
                viewModel = viewModel,
                onBack = { showDesignSubmenu.value = false }
            )
        } else {
            SettingsScaffold(
                title = stringResource(R.string.sidebar_label)
            ) { paddingValues ->
                Column(
                    modifier = Modifier.padding(paddingValues)
                ) {
                    MainSwitchPreference(object : SwitchPreferenceModel {
                        override val title = stringResource(R.string.enable_sideline)
                        override val checked = { mainChecked.value }
                        override val changeable = { viewModel.isEnabled }
                        override val onCheckedChange: (Boolean) -> Unit = {
                            mainChecked.value = it
                            viewModel.setSidebarEnabled(it)
                        }
                    })
                    if (mainChecked.value) {
                        SidebarSettingSwitch(
                            title = stringResource(R.string.sidebar_predicted_apps),
                            summary = stringResource(R.string.sidebar_predicted_apps_summary),
                            isChecked = viewModel.getPredictedAppsEnabled(),
                            onCheckedChange = { viewModel.setPredictedAppsEnabled(it) }
                        )
                        val designTitle = stringResource(R.string.sidebar_design_title)
                        val designSummary = stringResource(R.string.sidebar_design_summary)
                        Preference(object : PreferenceModel {
                            override val title = designTitle
                            override val summary = { designSummary }
                            override val onClick = { showDesignSubmenu.value = true }
                        })
                        SidebarAppList(viewModel)
                    }
                }
            }
        }
    }
}

@Composable
fun SidebarDesignSettingsPage(
    viewModel: SidebarSettingsViewModel,
    onBack: () -> Unit
) {
    BackHandler { onBack() }
    val sidelineLengthValue = rememberSaveable { mutableStateOf(viewModel.getSidelineLengthPercent().toFloat()) }
    val sidelineWidthValue = rememberSaveable { mutableStateOf(viewModel.getSidelineWidthPercent().toFloat()) }
    val lengthValue = rememberSaveable { mutableStateOf(viewModel.getSidebarLengthPercent().toFloat()) }
    val widthValue = rememberSaveable { mutableStateOf(viewModel.getSidebarWidthDp().toFloat()) }
    val drawerHeightValue = rememberSaveable { mutableStateOf(viewModel.getAllAppsHeightPercent().toFloat()) }
    val windowStyleValue = rememberSaveable { mutableStateOf(viewModel.getFreeformWindowStyle()) }

    val defaultSidelineLength = SidebarApplication.DEFAULT_SIDELINE_LENGTH_PERCENT.toFloat()
    val defaultSidelineWidth = SidebarApplication.DEFAULT_SIDELINE_WIDTH_PERCENT.toFloat()
    val defaultLength = SidebarApplication.DEFAULT_SIDEBAR_LENGTH_PERCENT.toFloat()
    val defaultWidth = SidebarApplication.DEFAULT_SIDEBAR_WIDTH_DP.toFloat()
    val defaultDrawerHeight = SidebarApplication.DEFAULT_ALL_APPS_HEIGHT_PERCENT.toFloat()
    val defaultWindowStyle = SidebarApplication.STYLE_STOCK

    val isAnyModified = sidelineLengthValue.value.roundToInt() != defaultSidelineLength.roundToInt() ||
            sidelineWidthValue.value.roundToInt() != defaultSidelineWidth.roundToInt() ||
            lengthValue.value.roundToInt() != defaultLength.roundToInt() ||
            widthValue.value.roundToInt() != defaultWidth.roundToInt() ||
            drawerHeightValue.value.roundToInt() != defaultDrawerHeight.roundToInt() ||
            windowStyleValue.value != defaultWindowStyle

    SettingsScaffold(
        title = stringResource(R.string.sidebar_design_title)
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 12.dp)
        ) {
            val isStockActive = windowStyleValue.value == SidebarApplication.STYLE_STOCK

            SidebarWindowDesignSelectorItem(
                selectedStyle = windowStyleValue.value,
                onStyleSelected = {
                    windowStyleValue.value = it
                    viewModel.setFreeformWindowStyle(it)
                },
                onReset = {
                    windowStyleValue.value = defaultWindowStyle
                    viewModel.setFreeformWindowStyle(defaultWindowStyle)
                }
            )

            SidebarDesignSliderItem(
                title = stringResource(R.string.sideline_length_title),
                summary = stringResource(R.string.sideline_length_summary),
                value = sidelineLengthValue.value,
                defaultValue = defaultSidelineLength,
                valueRange = 50f..150f,
                unitSuffix = "%",
                enabled = true,
                onValueChange = {
                    sidelineLengthValue.value = it
                    viewModel.setSidelineLengthPercent(it.roundToInt())
                },
                onReset = {
                    sidelineLengthValue.value = defaultSidelineLength
                    viewModel.setSidelineLengthPercent(defaultSidelineLength.roundToInt())
                }
            )

            SidebarDesignSliderItem(
                title = stringResource(R.string.sideline_width_title),
                summary = stringResource(R.string.sideline_width_summary),
                value = sidelineWidthValue.value,
                defaultValue = defaultSidelineWidth,
                valueRange = 50f..200f,
                unitSuffix = "%",
                enabled = true,
                onValueChange = {
                    sidelineWidthValue.value = it
                    viewModel.setSidelineWidthPercent(it.roundToInt())
                },
                onReset = {
                    sidelineWidthValue.value = defaultSidelineWidth
                    viewModel.setSidelineWidthPercent(defaultSidelineWidth.roundToInt())
                }
            )

            SidebarDesignSliderItem(
                title = stringResource(R.string.sidebar_length_title),
                summary = stringResource(R.string.sidebar_length_summary),
                value = lengthValue.value,
                defaultValue = defaultLength,
                valueRange = 50f..150f,
                unitSuffix = "%",
                enabled = true,
                onValueChange = {
                    lengthValue.value = it
                    viewModel.setSidebarLengthPercent(it.roundToInt())
                },
                onReset = {
                    lengthValue.value = defaultLength
                    viewModel.setSidebarLengthPercent(defaultLength.roundToInt())
                }
            )

            SidebarDesignSliderItem(
                title = stringResource(R.string.sidebar_width_title),
                summary = stringResource(R.string.sidebar_width_summary),
                value = widthValue.value,
                defaultValue = defaultWidth,
                valueRange = 38f..72f,
                unitSuffix = " dp",
                enabled = true,
                onValueChange = {
                    widthValue.value = it
                    viewModel.setSidebarWidthDp(it.roundToInt())
                },
                onReset = {
                    widthValue.value = defaultWidth
                    viewModel.setSidebarWidthDp(defaultWidth.roundToInt())
                }
            )

            SidebarDesignSliderItem(
                title = stringResource(R.string.sidebar_drawer_height_title),
                summary = stringResource(R.string.sidebar_drawer_height_summary),
                value = drawerHeightValue.value,
                defaultValue = defaultDrawerHeight,
                valueRange = 40f..100f,
                unitSuffix = "%",
                enabled = true,
                onValueChange = {
                    drawerHeightValue.value = it
                    viewModel.setAllAppsHeightPercent(it.roundToInt())
                },
                onReset = {
                    drawerHeightValue.value = defaultDrawerHeight
                    viewModel.setAllAppsHeightPercent(defaultDrawerHeight.roundToInt())
                }
            )

            Spacer(modifier = Modifier.height(12.dp))

            FilledTonalButton(
                onClick = {
                    sidelineLengthValue.value = defaultSidelineLength
                    sidelineWidthValue.value = defaultSidelineWidth
                    lengthValue.value = defaultLength
                    widthValue.value = defaultWidth
                    drawerHeightValue.value = defaultDrawerHeight
                    windowStyleValue.value = defaultWindowStyle
                    viewModel.resetDesignToDefault()
                },
                enabled = isAnyModified,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text(
                    text = stringResource(R.string.sidebar_reset_all),
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }
}

@Composable
fun SidebarDesignSliderItem(
    title: String,
    summary: String,
    value: Float,
    defaultValue: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    unitSuffix: String,
    enabled: Boolean = true,
    onValueChange: (Float) -> Unit,
    onReset: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                    )
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "${value.roundToInt()}$unitSuffix",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                    modifier = Modifier.padding(end = 4.dp)
                )
                OutlinedButton(
                    onClick = onReset,
                    enabled = enabled && (value.roundToInt() != defaultValue.roundToInt()),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = stringResource(R.string.sidebar_reset),
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Slider(
                value = value,
                onValueChange = onValueChange,
                valueRange = valueRange,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
fun SidebarWindowDesignSelectorItem(
    selectedStyle: String,
    onStyleSelected: (String) -> Unit,
    onReset: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.sidebar_window_style_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.sidebar_window_style_summary),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                OutlinedButton(
                    onClick = onReset,
                    enabled = selectedStyle != SidebarApplication.STYLE_STOCK,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = stringResource(R.string.sidebar_reset),
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                WindowStyleOptionCard(
                    title = stringResource(R.string.sidebar_window_style_stock),
                    isSelected = selectedStyle == SidebarApplication.STYLE_STOCK,
                    modifier = Modifier.weight(1f),
                    onClick = { onStyleSelected(SidebarApplication.STYLE_STOCK) }
                )

                WindowStyleOptionCard(
                    title = stringResource(R.string.sidebar_window_style_new),
                    isSelected = selectedStyle == SidebarApplication.STYLE_NEW,
                    modifier = Modifier.weight(1f),
                    onClick = { onStyleSelected(SidebarApplication.STYLE_NEW) }
                )
            }
        }
    }
}

@Composable
private fun WindowStyleOptionCard(
    title: String,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val borderColor = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    val containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer
    val contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = containerColor,
        border = BorderStroke(if (isSelected) 2.dp else 1.dp, borderColor),
        modifier = modifier.height(48.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            RadioButton(
                selected = isSelected,
                onClick = null
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = contentColor
            )
        }
    }
}

@Composable
fun SidebarAppList(
    viewModel: SidebarSettingsViewModel
) {
    val sidebarApps by viewModel.appListFlow.collectAsState()
    Category(
        title = stringResource(R.string.sidebar_app_setting_label)
    ) {
        LazyColumn {
            items(sidebarApps) { appInfo ->
                SidebarAppListItem(
                    appInfo = appInfo,
                    onCheckedChange = { isChecked ->
                        if (isChecked) {
                            viewModel.addSidebarApp(appInfo)
                        } else {
                            viewModel.deleteSidebarApp(appInfo)
                        }
                    }
                )
            }
        }
    }
}

@Composable
fun SidebarAppListItem(
    appInfo: SidebarAppInfo,
    onCheckedChange: (Boolean) -> Unit
) {
    var appChecked = rememberSaveable { mutableStateOf(appInfo.isSidebarApp) }
    SwitchPreference(
        model = object : SwitchPreferenceModel {
            override val title = appInfo.label
            override val icon = @Composable {
                Image(
                    painter = rememberDrawablePainter(appInfo.icon),
                    contentDescription = appInfo.label,
                    modifier = Modifier.size(SettingsDimension.appIconItemSize)
                )
            }
            override val checked = { appChecked.value }
            override val onCheckedChange: (Boolean) -> Unit = {
                appChecked.value = it
                onCheckedChange(it)
            }
        },
    )
}

@Composable
fun SidebarSettingSwitch(
    title: String,
    summary: String?,
    isChecked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    var myChecked = rememberSaveable { mutableStateOf(isChecked) }
    SwitchPreference(
        model = object : SwitchPreferenceModel {
            override val title = title
            override val summary = { summary ?: "" }
            override val checked = { myChecked.value }
            override val onCheckedChange: (Boolean) -> Unit = {
                myChecked.value = it
                onCheckedChange(it)
            }
        },
    )
}
