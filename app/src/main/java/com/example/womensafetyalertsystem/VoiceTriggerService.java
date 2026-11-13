package com.example.womensafetyalertsystem;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import androidx.core.app.NotificationCompat;

import java.util.ArrayList;
import java.util.Locale;

/**
 * Foreground service that continuously listens for the spoken trigger phrase
 * "help me now" and starts the shared SOS countdown when heard.
 * Runs as a foreground service (with a visible low-priority notification)
 * because Android does not allow silent background microphone access.
 */
public class VoiceTriggerService extends Service {

    private static final String CHANNEL_ID = "voice_trigger_channel";
    private static final int FOREGROUND_ID = 6001;
    private static final String TRIGGER_PHRASE = "help me now";

    private SpeechRecognizer speechRecognizer;
    private Handler mainHandler;
    private volatile boolean shouldContinue = true;

    @Override
    public void onCreate() {
        super.onCreate();
        mainHandler = new Handler(Looper.getMainLooper());
        createServiceNotificationChannel();
        SosAlertHelper.createNotificationChannel(this);
        startForeground(FOREGROUND_ID, buildNotification());
        startListening();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    private void createServiceNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Voice Trigger Listening", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Shown while the app listens for the voice trigger phrase");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("Women Safety Alert System")
                .setContentText("Listening for voice trigger: \"" + TRIGGER_PHRASE + "\"")
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .build();
    }

    private void startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            stopSelf();
            return;
        }
        mainHandler.post(() -> {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
            speechRecognizer.setRecognitionListener(recognitionListener);
            beginListeningCycle();
        });
    }

    private void beginListeningCycle() {
        if (!shouldContinue || speechRecognizer == null) return;

        Intent recognizerIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.ENGLISH);
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());

        try {
            speechRecognizer.startListening(recognizerIntent);
        } catch (Exception e) {
            restartListeningAfterDelay();
        }
    }

    private void restartListeningAfterDelay() {
        if (!shouldContinue) return;
        mainHandler.postDelayed(this::beginListeningCycle, 800);
    }

    private final RecognitionListener recognitionListener = new RecognitionListener() {
        @Override public void onReadyForSpeech(Bundle params) {}
        @Override public void onBeginningOfSpeech() {}
        @Override public void onRmsChanged(float rmsdB) {}
        @Override public void onBufferReceived(byte[] buffer) {}
        @Override public void onEndOfSpeech() {}

        @Override
        public void onError(int error) {
            restartListeningAfterDelay();
        }

        @Override
        public void onResults(Bundle results) {
            checkForTrigger(results);
            restartListeningAfterDelay();
        }

        @Override
        public void onPartialResults(Bundle partialResults) {
            checkForTrigger(partialResults);
        }

        @Override public void onEvent(int eventType, Bundle params) {}
    };

    private void checkForTrigger(Bundle results) {
        if (results == null) return;
        ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (matches == null) return;

        for (String phrase : matches) {
            if (phrase != null && phrase.toLowerCase(Locale.ENGLISH).contains(TRIGGER_PHRASE)) {
                SosAlertHelper.startCountdown(VoiceTriggerService.this, "voice trigger");
                break;
            }
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        shouldContinue = false;
        if (speechRecognizer != null) {
            mainHandler.post(() -> {
                try {
                    speechRecognizer.stopListening();
                    speechRecognizer.destroy();
                } catch (Exception ignored) {
                }
            });
        }
        super.onDestroy();
    }
}
