package com.libremobileos.freeform.server;

import static com.libremobileos.freeform.server.Debug.dlog;

import android.hardware.display.DisplayManagerInternal;
import android.util.Slog;
import android.view.InputEvent;
import android.view.Surface;

import com.libremobileos.freeform.ILMOFreeformDisplayCallback;

public class LMOFreeformService {
    private static final String TAG = "LMOFreeform/LMOFreeformService";

    private DisplayManagerInternal displayManager = null;

    public LMOFreeformService(DisplayManagerInternal displayManager) {
        this.displayManager = displayManager;
    }

    public void createFreeform(String name, ILMOFreeformDisplayCallback callback,
                               int width, int height, int densityDpi, boolean secure,
                               boolean ownContentOnly, boolean shouldShowSystemDecorations, Surface surface,
                               float refreshRate, long presentationDeadlineNanos) {
        displayManager.createFreeformLocked(name, callback,
                width, height, densityDpi, secure,
                ownContentOnly, shouldShowSystemDecorations, surface,
                refreshRate, presentationDeadlineNanos);
        dlog(TAG, "createFreeform");
    }

    public void injectInputEvent(InputEvent event, int displayId) {
        try {
            event.setDisplayId(displayId);
            android.hardware.input.IInputManager im = SystemServiceHolder.getInputManagerService();
            if (im != null) {
                im.injectInputEvent(event, 0);
            } else {
                Slog.e(TAG, "injectInputEvent failed: inputManagerService is null");
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public boolean isRunning() {
        return null != SystemServiceHolder.getInputManagerService();
    }
}
