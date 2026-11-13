package com.example.womensafetyalertsystem;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

public class VolumeKeyAccessibilityService extends AccessibilityService {

    private static final int TRIGGER_COUNT = 3;
    private static final long TRIGGER_WINDOW_MS = 2000;

    private int pressCount = 0;
    private long firstPressTime = 0;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        SosAlertHelper.createNotificationChannel(this);
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_DOWN && event.getAction() == KeyEvent.ACTION_DOWN) {
            long now = System.currentTimeMillis();

            if (pressCount == 0 || (now - firstPressTime) > TRIGGER_WINDOW_MS) {
                pressCount = 1;
                firstPressTime = now;
            } else {
                pressCount++;
            }

            vibrateOnce();

            if (pressCount >= TRIGGER_COUNT) {
                pressCount = 0;
                SosAlertHelper.startCountdown(this, "volume button");
            }
        }
        return false;
    }

    private void vibrateOnce() {
        Vibrator vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        if (vibrator == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(150, VibrationEffect.DEFAULT_AMPLITUDE));
        } else {
            vibrator.vibrate(150);
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Not used, but required to override
    }

    @Override
    public void onInterrupt() {
        // Not used, but required to override
    }
}