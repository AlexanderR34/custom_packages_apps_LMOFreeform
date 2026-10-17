package com.libremobileos.sidebar.app

import android.app.Application
import android.os.Build

/**
 * @author KindBrave
 * @since 2023/9/25
 */
class SidebarApplication : Application() {
    companion object {
        const val CONFIG = "config"

        const val KEY_SIDEBAR_LENGTH_PERCENT = "sidebar_length_percent"
        const val KEY_SIDEBAR_WIDTH_DP = "sidebar_width_dp"
        const val KEY_ALL_APPS_HEIGHT_PERCENT = "sidebar_all_apps_height_percent"

        const val DEFAULT_SIDEBAR_LENGTH_PERCENT = 100
        const val DEFAULT_SIDEBAR_WIDTH_DP = 50
        const val DEFAULT_ALL_APPS_HEIGHT_PERCENT = 100

        const val KEY_FREEFORM_WINDOW_STYLE = "freeform_window_style"
        const val STYLE_STOCK = "view_freeform"
        const val STYLE_NEW = "view_freeform_new"
        const val DEFAULT_FREEFORM_WINDOW_STYLE = STYLE_STOCK
    }
}
