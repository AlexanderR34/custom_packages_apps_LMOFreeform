package com.libremobileos.freeform.server.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Context.CONTEXT_IGNORE_SECURITY
import android.content.Context.CONTEXT_INCLUDE_CODE
import android.util.Slog
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup

@SuppressLint("WrongConstant", "DiscouragedApi")
class RemoteResourceHolder(context: Context, private val resPkg: String) {

    private lateinit var remoteContext: Context

    companion object {
        private const val TAG = "LMOFreeform/RemoteResourceHolder"
    }

    init {
        try {
            remoteContext = context.createPackageContext(resPkg, CONTEXT_INCLUDE_CODE or CONTEXT_IGNORE_SECURITY)
        } catch (e: Exception) {
            e.printStackTrace()
            Slog.e(TAG, "init", e)
            runCatching { remoteContext = context.createPackageContext(resPkg, CONTEXT_INCLUDE_CODE or CONTEXT_IGNORE_SECURITY) }
        }
    }

    fun getLayout(layoutName: String): ViewGroup? {
        if (!this::remoteContext.isInitialized) {
            Slog.e(TAG, "getLayout: remoteContext is not initialized for $resPkg")
            return null
        }
        return try {
            val freeformLayoutId = remoteContext.resources.getIdentifier(layoutName, "layout", resPkg)
            if (freeformLayoutId == 0) {
                Slog.e(TAG, "can not find layout resource id for $layoutName in $resPkg")
                return null
            }
            val r = LayoutInflater.from(remoteContext).inflate(freeformLayoutId, null, false)
            if (null == r) Slog.e(TAG, "can not find layout $layoutName")
            r as ViewGroup
        } catch (e: Exception) {
            e.printStackTrace()
            Slog.e(TAG, "getLayout", e)
            null
        }
    }

    fun <T : View> getLayoutChildViewByTag(layout: ViewGroup, tagName: String): T? {
        return try {
            val r = layout.findViewWithTag<T>(tagName)
            if (null == r) Slog.e(TAG, "can not find tag $tagName in $layout")
            r
        } catch (e: Exception) {
            e.printStackTrace()
            Slog.e(TAG, "getLayoutChildViewByTag", e)
            null
        }
    }
}
