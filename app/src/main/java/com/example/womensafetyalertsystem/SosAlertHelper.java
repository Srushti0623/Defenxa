package com.example.womensafetyalertsystem;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.telephony.SmsManager;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

/**
 * Shared logic for triggering an SOS: the 10-second cancel countdown,
 * the warning vibration, the cancel notification, and the actual SMS send.
 * Used by both VolumeKeyAccessibilityService and VoiceTriggerService so the
 * send logic exists in exactly one place.
 */
public class SosAlertHelper {

    private static final String PREFS_NAME = "SafetyAppPrefs";
    private static final String KEY_NUMBER_1 = "emergency_number_1";
    private static final String KEY_NUMBER_2 = "emergency_number_2";

    private static final long COUNTDOWN_MS = 10000; // 10 seconds
    private static final String CHANNEL_ID = "sos_alert_channel";
    private static final int NOTIFICATION_ID = 5001;
    public static final String ACTION_CANCEL_SOS = "com.example.womensafetyalertsystem.ACTION_CANCEL_SOS";

    private static Handler countdownHandler;
    private static Runnable sendSOSRunnable;
    private static BroadcastReceiver cancelReceiver;
    private static boolean countdownActive = false;

    public static void createNotificationChannel(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "SOS Alerts", NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("Countdown alerts before sending emergency SOS");
            channel.enableVibration(true);
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    /**
     * Starts the 10-second SOS countdown. Safe to call from any trigger source
     * (volume button, voice, fall detection later). triggerSource is shown in
     * the notification text, e.g. "volume button" or "voice trigger".
     */
    public static synchronized void startCountdown(Context rawContext, String triggerSource) {
        Context context = rawContext.getApplicationContext();

        if (countdownActive) {
            return; // already counting down, ignore extra triggers
        }

        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String number1 = prefs.getString(KEY_NUMBER_1, "");
        if (number1.isEmpty()) {
            Toast.makeText(context, "No emergency number saved", Toast.LENGTH_LONG).show();
            return;
        }

        countdownActive = true;
        vibrateWarning(context);
        registerCancelReceiver(context);
        showCancelNotification(context, triggerSource);

        countdownHandler = new Handler(Looper.getMainLooper());
        sendSOSRunnable = () -> {
            countdownActive = false;
            dismissNotification(context);
            unregisterCancelReceiver(context);
            triggerSOS(context);
        };
        countdownHandler.postDelayed(sendSOSRunnable, COUNTDOWN_MS);

        Toast.makeText(context, "SOS in 10 seconds. Tap Cancel to stop.", Toast.LENGTH_LONG).show();
    }

    private static void cancelCountdown(Context context) {
        if (countdownHandler != null && sendSOSRunnable != null) {
            countdownHandler.removeCallbacks(sendSOSRunnable);
        }
        countdownActive = false;
        dismissNotification(context);
        unregisterCancelReceiver(context);
        Toast.makeText(context, "SOS cancelled", Toast.LENGTH_SHORT).show();
    }

    private static void vibrateWarning(Context context) {
        Vibrator vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        if (vibrator == null) return;
        long[] pattern = {0, 300, 200, 300, 200, 300};
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1));
        } else {
            vibrator.vibrate(pattern, -1);
        }
    }

    private static void showCancelNotification(Context context, String triggerSource) {
        Intent cancelIntent = new Intent(ACTION_CANCEL_SOS);
        cancelIntent.setPackage(context.getPackageName());

        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags |= PendingIntent.FLAG_MUTABLE;
        }
        PendingIntent cancelPendingIntent = PendingIntent.getBroadcast(context, 0, cancelIntent, flags);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("SOS Alert - Sending in 10s")
                .setContentText("Triggered by " + triggerSource + ". Tap Cancel if not intentional")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setOngoing(true)
                .setAutoCancel(false)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancelPendingIntent);

        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, builder.build());
        }
    }

    private static void dismissNotification(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.cancel(NOTIFICATION_ID);
        }
    }

    private static void registerCancelReceiver(Context context) {
        if (cancelReceiver != null) return; // already registered
        cancelReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                if (ACTION_CANCEL_SOS.equals(intent.getAction())) {
                    cancelCountdown(ctx);
                }
            }
        };
        IntentFilter filter = new IntentFilter(ACTION_CANCEL_SOS);
        ContextCompat.registerReceiver(context, cancelReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    private static void unregisterCancelReceiver(Context context) {
        if (cancelReceiver != null) {
            try {
                context.unregisterReceiver(cancelReceiver);
            } catch (IllegalArgumentException ignored) {
                // was not registered
            }
            cancelReceiver = null;
        }
    }

    private static void triggerSOS(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String number1 = prefs.getString(KEY_NUMBER_1, "");
        String number2 = prefs.getString(KEY_NUMBER_2, "");

        if (number1.isEmpty()) {
            Toast.makeText(context, "No emergency number saved", Toast.LENGTH_LONG).show();
            return;
        }

        LocationManager locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        Location location = null;
        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                location = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            }
            if (location == null && locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                location = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            }
        } catch (SecurityException e) {
            // permission not granted; location stays null
        }

        String message;
        if (location != null) {
            String mapsLink = "https://maps.google.com/?q=" + location.getLatitude() + "," + location.getLongitude();
            message = "EMERGENCY! I need help. My current location: " + mapsLink;
        } else {
            message = "EMERGENCY! I need help. Unable to fetch exact location.";
        }

        try {
            SmsManager smsManager = SmsManager.getDefault();
            smsManager.sendTextMessage(number1, null, message, null, null);
            if (!number2.isEmpty()) {
                smsManager.sendTextMessage(number2, null, message, null, null);
            }
            Toast.makeText(context, "SOS alert sent!", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(context, "SOS failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
}

