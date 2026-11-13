package com.example.womensafetyalertsystem;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Foreground service that watches the accelerometer and starts the SOS countdown when the
 * Random Forest (assets/fall_forest.txt) decides an impact was a fall.
 *
 * Watch the decisions in Logcat with the filter:  FallDetection
 */
public class FallDetectionService extends Service implements SensorEventListener {

    private static final String TAG = "FallDetection";
    private static final String CHANNEL_ID = "fall_detection_channel";
    private static final int FOREGROUND_ID = 6002;
    private static final String MODEL_ASSET = "fall_forest.txt";

    // Chosen from the replay tests (see retrain_50hz.py / replay_50hz.py)
    private static final double TRIGGER_G = 2.0;
    private static final double PROBABILITY_THRESHOLD = 0.5;

    private static final int SAMPLING_PERIOD_US = 20_000;   // ask for 50 Hz
    private static final long COOLDOWN_MS = 60_000;          // no second alarm for 60 s
    private static final long HEARTBEAT_MS = 5 * 60_000L;    // write a "still alive" line every 5 minutes

    /**
     * true  = only write decisions to Logcat (use this to measure real-life false alarms).
     * false = start the 10-second SOS countdown when a fall is detected.
     */
    private static final boolean LOG_ONLY_MODE = false;

    private SensorManager sensorManager;
    private HandlerThread sensorThread;
    private Handler mainHandler;
    private Handler sensorHandler;
    private long sensorEventCount;     // only touched on the sensor thread
    private long lastHeartbeatElapsedMs;
    private PowerManager.WakeLock wakeLock;
    private FallDetector detector;
    private long lastAlarmMs = -COOLDOWN_MS;

    @Override
    public void onCreate() {
        super.onCreate();
        mainHandler = new Handler(Looper.getMainLooper());
        createServiceChannel();
        SosAlertHelper.createNotificationChannel(this);
        startForeground(FOREGROUND_ID, buildNotification());

        FallForest forest;
        try (InputStream in = getAssets().open(MODEL_ASSET)) {
            forest = FallForest.load(in);
        } catch (IOException | RuntimeException e) {
            Log.e(TAG, "Could not load " + MODEL_ASSET + " from assets", e);
            stopSelf();
            return;
        }
        detector = new FallDetector(forest, TRIGGER_G, PROBABILITY_THRESHOLD,
                (centerIndex, probability, fall) -> onImpactChecked(probability, fall));

        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        Sensor accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        if (accelerometer == null) {
            Log.e(TAG, "This phone has no accelerometer");
            stopSelf();
            return;
        }

        // Read the sensor on its own thread so the app's screen is never slowed down
        sensorThread = new HandlerThread("FallDetectionSensors");
        sensorThread.start();
        sensorHandler = new Handler(sensorThread.getLooper());
        sensorManager.registerListener(this, accelerometer, SAMPLING_PERIOD_US, sensorHandler);
        lastHeartbeatElapsedMs = SystemClock.elapsedRealtime();
        sensorHandler.postDelayed(heartbeat, HEARTBEAT_MS);

        // Keeps the CPU awake so readings continue with the screen off. This costs battery.
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WomenSafety:FallDetection");
        wakeLock.acquire();

        Log.i(TAG, "Fall detection started (trigger " + TRIGGER_G + " g, threshold "
                + PROBABILITY_THRESHOLD + ", log only = " + LOG_ONLY_MODE + ")");
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        sensorEventCount++;
        if (detector != null) {
            detector.onSensorEvent(event.timestamp, event.values[0], event.values[1], event.values[2]);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // not needed
    }

    /** Called on the sensor thread, about 2 seconds after an impact of at least 2 g. */
    private void onImpactChecked(double probability, boolean fall) {
        Log.i(TAG, String.format("Impact checked: fall probability %.2f -> %s",
                probability, fall ? "FALL" : "not a fall"));
        appendToEventLog(probability, fall);
        if (!fall || LOG_ONLY_MODE) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (now - lastAlarmMs < COOLDOWN_MS) {
            Log.i(TAG, "Ignored: an alarm was already raised in the last minute");
            return;
        }
        lastAlarmMs = now;
        // SosAlertHelper shows toasts, so it must be called on the main thread
        mainHandler.post(() -> SosAlertHelper.startCountdown(getApplicationContext(), "fall detection"));
    }

    /**
     * Every 5 minutes: how many accelerometer readings arrived. At 50 Hz that is about 15,000.
     * A much smaller number (or a gap in the times) means Android paused the app, for example
     * while the screen was off and the phone was unplugged.
     */
    private final Runnable heartbeat = new Runnable() {
        @Override
        public void run() {
            // real_seconds_since_last counts true elapsed time (including any time the phone slept).
            // 300 and about 15,000 readings = healthy. More than 300 with fewer readings = the phone
            // slept. 300 but a wall-clock gap different from 300 = the phone changed its clock.
            long nowElapsed = SystemClock.elapsedRealtime();
            long realSeconds = (nowElapsed - lastHeartbeatElapsedMs) / 1000;
            lastHeartbeatElapsedMs = nowElapsed;
            appendLine("fall_heartbeat2.csv", "time,sensor_readings,real_seconds_since_last",
                    timestamp() + "," + sensorEventCount + "," + realSeconds);
            sensorEventCount = 0;
            if (sensorHandler != null) {
                sensorHandler.postDelayed(this, HEARTBEAT_MS);
            }
        }
    };

    /**
     * Saves one line per checked impact to fall_events.csv so a day of real carrying can be
     * reviewed later. Find it in: Android/data/com.example.womensafetyalertsystem/files/
     */
    private void appendToEventLog(double probability, boolean fall) {
        appendLine("fall_events.csv", "time,probability,decision",
                String.format(Locale.US, "%s,%.3f,%s", timestamp(), probability, fall ? "FALL" : "not_fall"));
    }

    private static String timestamp() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
    }

    private void appendLine(String fileName, String header, String line) {
        try {
            File dir = getExternalFilesDir(null);
            if (dir == null) {
                dir = getFilesDir();
            }
            File file = new File(dir, fileName);
            boolean isNew = !file.exists();
            try (FileWriter writer = new FileWriter(file, true)) {
                if (isNew) {
                    writer.write(header + "\n");
                }
                writer.write(line + "\n");
            }
        } catch (IOException e) {
            Log.w(TAG, "Could not write " + fileName, e);
        }
    }

    private void createServiceChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Fall Detection", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Shown while the app watches for falls");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Women Safety Alert System")
                .setContentText(LOG_ONLY_MODE ? "Fall detection is on (log only)" : "Fall detection is on")
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .build();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        if (sensorHandler != null) {
            sensorHandler.removeCallbacks(heartbeat);
        }
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
        if (sensorThread != null) {
            sensorThread.quitSafely();
        }
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        super.onDestroy();
    }
}