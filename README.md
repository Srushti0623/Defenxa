# Defenxa

Defenxa is an Android personal-safety app (Java). It helps you send an emergency SMS with your location and plan walking routes that stay near police stations and hospitals.

> **Not a replacement for emergency services.** In immediate danger, call your local emergency number (112 in India). SMS delivery, location accuracy and fall detection can fail or make mistakes.

## Features

- **SOS button** – sends an SMS with a Google Maps link to your last known location to up to two saved emergency contacts.
- **Hands-free triggers** – each starts a 10-second countdown with a Cancel button before the SMS is sent:
   - Press **Volume Down 3 times** (uses an Android Accessibility Service that you enable yourself)
   - Say **"Help me now"** (Android speech recogniser, optional)
   - **Fall detection** (phone accelerometer + a Random Forest model, optional)
- **Safe Route** – compares walking routes to a destination and scores each by how much of it stays within 300 m of a police station or hospital (OpenStreetMap data). It does **not** use crime data, lighting or crowd information.
- **Safety assistant** – an offline, rule-based chatbot for app help, helplines (India) and safety tips.

## Tech

| Area | What is used |
|---|---|
| Language | Java (app), XML (layouts), Gradle Kotlin DSL (build), Python (model training only) |
| IDE | Android Studio |
| Libraries | AndroidX, Material Components, OSMDroid 6.1.18, OkHttp 4.12.0 |
| Online services | OpenStreetMap tiles, Nominatim, Overpass API, OpenRouteService |
| Android APIs | SmsManager, LocationManager, SpeechRecognizer, SensorManager, AccessibilityService, foreground services |
| ML model | Random Forest (scikit-learn) exported to `app/src/main/assets/fall_forest.txt` and run in plain Java on the phone |

Not used: Firebase, Google Maps SDK, any backend server.

## Run it

1. Install Android Studio and open this folder.
2. In `app/src/main/java/com/example/womensafetyalertsystem/`, copy `ApiConfig.java.example` to `ApiConfig.java` (remove `.example`).
3. Paste your **OpenRouteService** key into `ORS_API_KEY` (free keys are available from openrouteservice.org). `ApiConfig.java` is listed in `.gitignore`, so your key is never uploaded.
4. Connect an Android phone with USB debugging on (a real phone is needed for SMS, sensors and microphone) and click **Run**.
5. In the app: register and log in (see note below), allow the permissions, and save an emergency contact. **Use your own number when testing.**
6. For volume-button SOS, enable the app under Settings → Accessibility.

> **Login note:** the Login and Register screens only check that the form is filled in (password of 6+ characters). They do not create, store or verify accounts.

## Fall-detection model

- Trained on the **SisFall** dataset (waist-worn sensor, 38 volunteers, 19 daily activities, 15 fall types, 200 Hz), resampled to 50 Hz, using 19 accelerometer features.
- Live rule: a 2.0 g impact peak, evaluated 2 seconds later with the Random Forest (80 trees); probability of 0.5 or higher counts as a fall.
- Results on unseen people (live-rule replay of whole recordings): about **97.6–97.9 %** of falls caught, about **2.1–2.5 false alarms per hour** of daily activity.
- **Limits:** the data is waist-worn lab data, so a phone in a pocket behaves differently and may trigger on rough handling. Only one elderly volunteer performed falls, so older-adult performance is not established. Treat it as a prototype.
- Dataset: Sucerquia, López, Vargas-Bonilla, *SisFall: A Fall and Movement Dataset*, Sensors 2017, 17(1), 198. The dataset is **not** included in this repository.

## Known limitations

- Sending SMS from the background is restricted by Google Play, so this project is distributed as source and as an APK, not through Play.
- The SOS location is the phone's last known location and may be old or missing.
- Voice trigger and fall detection run continuously and use battery. Some phones stop background apps, so check battery settings.
- Safe Route scores depend on what is mapped in OpenStreetMap and on public services with usage limits.
- An optional AI chat client exists in the code but is switched off (`AI_ENABLED = false` in `LlmClient.java`).

## Privacy

The app keeps your saved numbers and settings on the phone and has no server of its own. See the privacy policy page for details.

## Credits

Map data © OpenStreetMap contributors. Routing by OpenRouteService (HeiGIT). Fall dataset: SisFall (see above).
