package com.libremobileos.freeform.server;

import android.app.IActivityManager;
import android.app.IActivityTaskManager;
import android.content.Context;
import android.hardware.input.IInputManager;
import android.os.ServiceManager;
import android.util.Slog;
import android.view.IWindowManager;

public class SystemServiceHolder {

    private static final String TAG = "LMOFreeform/SystemServiceHolder";

    public static IInputManager inputManagerService;
    public static IActivityManager activityManager;
    public static IActivityTaskManager activityTaskManager;
    public static IWindowManager windowManager;

    static void init() {
        getActivityTaskManager();
        getActivityManager();
        getInputManagerService();
        getWindowManager();
    }

    public static IInputManager getInputManagerService() {
        if (inputManagerService == null) {
            inputManagerService = IInputManager.Stub.asInterface(ServiceManager.getService(Context.INPUT_SERVICE));
        }
        return inputManagerService;
    }

    public static IActivityManager getActivityManager() {
        if (activityManager == null) {
            activityManager = IActivityManager.Stub.asInterface(ServiceManager.getService(Context.ACTIVITY_SERVICE));
        }
        return activityManager;
    }

    public static IActivityTaskManager getActivityTaskManager() {
        if (activityTaskManager == null) {
            activityTaskManager = IActivityTaskManager.Stub.asInterface(ServiceManager.getService(Context.ACTIVITY_TASK_SERVICE));
        }
        return activityTaskManager;
    }

    public static IWindowManager getWindowManager() {
        if (windowManager == null) {
            windowManager = IWindowManager.Stub.asInterface(ServiceManager.getService(Context.WINDOW_SERVICE));
        }
        return windowManager;
    }
}

