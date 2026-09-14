package com.libremobileos.freeform

import android.app.PendingIntent
import android.os.Build
import android.os.IBinder
import android.os.ServiceManager
import android.util.Log
import com.libremobileos.freeform.ILMOFreeformUIService
import java.util.Date

object LMOFreeformServiceManager {
    private const val TAG = "LMOFreeformServiceManager"
    private var iLMOFreeformService: ILMOFreeformUIService? = null

    fun init() {
        try {
            val r = ServiceManager.getService("lmo_freeform")
            if (r != null) {
                iLMOFreeformService = ILMOFreeformUIService.Stub.asInterface(r)
                iLMOFreeformService?.ping()
                Log.d(TAG, "Initialized LMOFreeformServiceManager successfully")
            } else {
                Log.w(TAG, "ServiceManager.getService(lmo_freeform) returned null")
            }
        } catch (e: Exception) {
            Log.e(TAG, "init failed: $e", e)
        }
    }

    private fun getService(): ILMOFreeformUIService? {
        if (iLMOFreeformService == null || !(iLMOFreeformService?.asBinder()?.isBinderAlive ?: false)) {
            init()
        }
        return iLMOFreeformService
    }

    fun ping(): Boolean {
        return try {
            getService()?.ping() == true
        } catch (e: Exception) {
            Log.e(TAG, "ping failed: $e", e)
            false
        }
    }

    fun createWindow(packageName: String, activityName: String, userId: Int, taskId: Int,
            width: Int, height: Int, densityDpi: Int) {
        val service = getService()
        if (service == null) {
            Log.e(TAG, "createWindow failed: lmo_freeform service is null")
            return
        }
        try {
            service.startAppInFreeform(
                packageName,
                activityName,
                userId,
                taskId,
                null,
                width,
                height,
                densityDpi
            )
        } catch (e: Exception) {
            Log.e(TAG, "createWindow exception: $e", e)
        }
    }

    fun createWindow(pendingIntent: PendingIntent?, width: Int, height: Int, densityDpi: Int) {
        val service = getService()
        if (service == null) {
            Log.e(TAG, "createWindow (pendingIntent) failed: lmo_freeform service is null")
            return
        }
        try {
            service.startAppInFreeform(
                pendingIntent?.creatorPackage ?: "pendingIntentCreatorPackage",
                "unknownActivity-${Date().time}",
                -100,
                -1,
                pendingIntent,
                width,
                height,
                densityDpi
            )
        } catch (e: Exception) {
            Log.e(TAG, "createWindow exception: $e", e)
        }
    }

    fun removeFreeform(freeformId: String) {
        try {
            getService()?.removeFreeform(freeformId)
        } catch (e: Exception) {
            Log.e(TAG, "removeFreeform exception: $e", e)
        }
    }
}
