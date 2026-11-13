package com.example.womensafetyalertsystem;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.telephony.SmsManager;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.util.ArrayList;
import java.util.List;

public class HomeActivity extends AppCompatActivity {

    private static final int PERMISSION_REQUEST_CODE = 100;
    private static final String PREFS_NAME = "SafetyAppPrefs";
    private static final String KEY_NUMBER_1 = "emergency_number_1";
    private static final String KEY_NUMBER_2 = "emergency_number_2";
    private static final String KEY_VOICE_TRIGGER_ENABLED = "voice_trigger_enabled";
    private static final String KEY_FALL_DETECTION_ENABLED = "fall_detection_enabled";

    private EditText etEmergencyNumber1, etEmergencyNumber2;
    private Switch switchVoiceTrigger;
    private Switch switchFallDetection;
    private String pendingNumber1, pendingNumber2;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_home);

        requestStartupPermissions();

        etEmergencyNumber1 = findViewById(R.id.etEmergencyNumber1);
        etEmergencyNumber2 = findViewById(R.id.etEmergencyNumber2);
        Button btnSaveNumber = findViewById(R.id.btnSaveNumber);
        Button btnSOS = findViewById(R.id.btnSOS);
        Button btnSafeRoute = findViewById(R.id.btnSafeRoute);
        btnSafeRoute.setOnClickListener(v -> {
            startActivity(new Intent(HomeActivity.this, SafeRouteActivity.class));
        });
        Button btnChat = findViewById(R.id.btnChat);
        btnChat.setOnClickListener(v -> startActivity(new Intent(HomeActivity.this, ChatActivity.class)));
        switchVoiceTrigger = findViewById(R.id.switchVoiceTrigger);
        switchFallDetection = findViewById(R.id.switchFallDetection);

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        etEmergencyNumber1.setText(prefs.getString(KEY_NUMBER_1, ""));
        etEmergencyNumber2.setText(prefs.getString(KEY_NUMBER_2, ""));

        // Restore the switches' saved state without re-triggering their listeners
        boolean voiceTriggerEnabled = prefs.getBoolean(KEY_VOICE_TRIGGER_ENABLED, false);
        switchVoiceTrigger.setChecked(voiceTriggerEnabled);
        if (voiceTriggerEnabled) {
            // Service may have been killed by the OS; restart it to be safe
            startVoiceTriggerService();
        }

        boolean fallDetectionEnabled = prefs.getBoolean(KEY_FALL_DETECTION_ENABLED, false);
        switchFallDetection.setChecked(fallDetectionEnabled);
        if (fallDetectionEnabled) {
            startFallDetectionService();
        }

        switchVoiceTrigger.setOnCheckedChangeListener((CompoundButton buttonView, boolean isChecked) -> {
            if (isChecked) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                        != PackageManager.PERMISSION_GRANTED) {
                    // Ask for mic permission; the switch will be re-checked once granted
                    switchVoiceTrigger.setChecked(false);
                    ActivityCompat.requestPermissions(this,
                            new String[]{Manifest.permission.RECORD_AUDIO}, 300);
                    Toast.makeText(this, "Microphone permission is needed for voice trigger", Toast.LENGTH_LONG).show();
                    return;
                }
                startVoiceTriggerService();
                prefs.edit().putBoolean(KEY_VOICE_TRIGGER_ENABLED, true).apply();
                Toast.makeText(this, "Voice trigger enabled: say \"Help me now\"", Toast.LENGTH_LONG).show();
            } else {
                stopVoiceTriggerService();
                prefs.edit().putBoolean(KEY_VOICE_TRIGGER_ENABLED, false).apply();
                Toast.makeText(this, "Voice trigger disabled", Toast.LENGTH_SHORT).show();
            }
        });

        switchFallDetection.setOnCheckedChangeListener((CompoundButton buttonView, boolean isChecked) -> {
            if (isChecked) {
                startFallDetectionService();
                prefs.edit().putBoolean(KEY_FALL_DETECTION_ENABLED, true).apply();
                Toast.makeText(this, "Fall detection enabled", Toast.LENGTH_SHORT).show();
            } else {
                stopFallDetectionService();
                prefs.edit().putBoolean(KEY_FALL_DETECTION_ENABLED, false).apply();
                Toast.makeText(this, "Fall detection disabled", Toast.LENGTH_SHORT).show();
            }
        });

        btnSaveNumber.setOnClickListener(v -> {
            String number1 = etEmergencyNumber1.getText().toString().trim();
            String number2 = etEmergencyNumber2.getText().toString().trim();

            if (number1.isEmpty()) {
                etEmergencyNumber1.setError("Please enter at least one number");
                etEmergencyNumber1.requestFocus();
                return;
            }
            if (number1.length() < 10) {
                etEmergencyNumber1.setError("Enter a valid 10-digit number");
                etEmergencyNumber1.requestFocus();
                return;
            }
            if (!number2.isEmpty() && number2.length() < 10) {
                etEmergencyNumber2.setError("Enter a valid 10-digit number");
                etEmergencyNumber2.requestFocus();
                return;
            }

            prefs.edit()
                    .putString(KEY_NUMBER_1, number1)
                    .putString(KEY_NUMBER_2, number2)
                    .apply();
            Toast.makeText(this, "Emergency numbers saved!", Toast.LENGTH_SHORT).show();

            if (!isAccessibilityServiceEnabled()) {
                showAccessibilitySetupDialog();
            }
        });

        btnSOS.setOnClickListener(v -> {
            SharedPreferences p = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            String number1 = p.getString(KEY_NUMBER_1, "");
            String number2 = p.getString(KEY_NUMBER_2, "");

            if (number1.isEmpty()) {
                Toast.makeText(this, "Please save an emergency number first", Toast.LENGTH_LONG).show();
                return;
            }
            checkPermissionsAndSendAlert(number1, number2);
        });

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });
    }

    private void startVoiceTriggerService() {
        Intent serviceIntent = new Intent(this, VoiceTriggerService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
    }

    private void stopVoiceTriggerService() {
        stopService(new Intent(this, VoiceTriggerService.class));
    }

    private void startFallDetectionService() {
        Intent serviceIntent = new Intent(this, FallDetectionService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
    }

    private void stopFallDetectionService() {
        stopService(new Intent(this, FallDetectionService.class));
    }

    /**
     * Requests SMS, location, and (on Android 13+) notification permission together
     * at app startup, so the SOS countdown notification can display.
     * RECORD_AUDIO is requested separately, only when the user turns on the voice
     * trigger switch, since it is an opt-in feature.
     */
    private void requestStartupPermissions() {
        List<String> permissionsNeeded = new ArrayList<>();

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.SEND_SMS);
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.POST_NOTIFICATIONS);
            }
        }

        if (!permissionsNeeded.isEmpty()) {
            ActivityCompat.requestPermissions(this,
                    permissionsNeeded.toArray(new String[0]),
                    PERMISSION_REQUEST_CODE);
        }
    }

    private boolean isAccessibilityServiceEnabled() {
        String service = getPackageName() + "/" + VolumeKeyAccessibilityService.class.getCanonicalName();
        try {
            int enabled = android.provider.Settings.Secure.getInt(
                    getContentResolver(),
                    android.provider.Settings.Secure.ACCESSIBILITY_ENABLED);
            if (enabled == 1) {
                String enabledServices = android.provider.Settings.Secure.getString(
                        getContentResolver(),
                        android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
                return enabledServices != null && enabledServices.contains(service);
            }
        } catch (Exception e) {
            return false;
        }
        return false;
    }

    private void showAccessibilitySetupDialog() {
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Volume button SOS")
                .setMessage("Defenxa uses Android’s Accessibility Service only to detect when you press the Volume Down button three times, so it can start an SOS countdown even when the app is closed. It does not read your screen, collect what you type, or take actions for you. You can turn it off at any time in Settings > Accessibility.\n\nTap Continue to open the settings, or Not now to skip.")
                .setPositiveButton("Continue", (dialog, which) -> {
                    android.content.Intent intent = new android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS);
                    startActivity(intent);
                })
                .setNegativeButton("Not now", null)
                .show();
    }

    private void checkPermissionsAndSendAlert(String number1, String number2) {
        pendingNumber1 = number1;
        pendingNumber2 = number2;
        boolean smsGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED;
        boolean locationGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;

        if (smsGranted && locationGranted) {
            sendSOSAlert(number1, number2);
        } else {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.SEND_SMS, Manifest.permission.ACCESS_FINE_LOCATION},
                    PERMISSION_REQUEST_CODE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == 300) {
            // Result of the RECORD_AUDIO request triggered by the voice trigger switch
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                switchVoiceTrigger.setChecked(true);
            } else {
                Toast.makeText(this, "Microphone permission denied. Voice trigger cannot be enabled.", Toast.LENGTH_LONG).show();
            }
            return;
        }

        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (allGranted && pendingNumber1 != null) {
                sendSOSAlert(pendingNumber1, pendingNumber2);
            } else if (pendingNumber1 != null) {
                Toast.makeText(this, "Permissions are required to send SOS alert", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void sendSOSAlert(String number1, String number2) {
        LocationManager locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        Location location = null;

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                location = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            }
            if (location == null && locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                location = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            }
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

            if (number2 != null && !number2.isEmpty()) {
                smsManager.sendTextMessage(number2, null, message, null, null);
            }

            Toast.makeText(this, "SOS alert sent!", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Failed to send SOS: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
}