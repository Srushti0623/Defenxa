package com.example.womensafetyalertsystem;

import android.util.Log;

import androidx.annotation.NonNull;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * The online half of the chatbot: sends the conversation to the Claude Messages API.
 * The key lives in ApiConfig.ANTHROPIC_API_KEY. A key inside an app can be extracted from the APK,
 * so this is fine for a college demo but a real release should call your own server instead.
 */
public class LlmClient {

    public interface Listener {
        void onSuccess(String reply);
        void onError(String message);
    }

    private static final String TAG = "LlmClient";
    /** The AI part is switched off in this release. Set to true (and add a key) only if you bring it back. */
    private static final boolean AI_ENABLED = false;
    private static final String API_URL = "https://api.anthropic.com/v1/messages";
    private static final String API_VERSION = "2023-06-01";
    /** Change this if you want a different Claude model. */
    private static final String MODEL = "claude-haiku-5-5";
    private static final int MAX_TOKENS = 350;
    private static final MediaType JSON_TYPE = MediaType.get("application/json; charset=utf-8");

    private static final String OFFLINE_MESSAGE =
            "I couldn’t reach the AI service. Check your internet connection. I can still help offline with the topics below.";

    private static final String SYSTEM_PROMPT =
            "You are the Defenxa safety assistant inside a women's personal-safety Android app.\n"
                    + "Style: calm, kind and practical. Plain text only: no markdown and no asterisks. Keep answers under 110 words. "
                    + "Use short sentences. A few short lines starting with '• ' are fine.\n"
                    + "Scope: personal safety, safe travel, harassment and stalking, digital safety, and how to use the app. "
                    + "Politely decline unrelated topics.\n"
                    + "If someone may be in immediate danger, tell them to call 112 (India's emergency number) and press the SOS button, "
                    + "and keep it short. Helplines in India: 112 emergency, 1091 women helpline, 181 women helpline (domestic abuse), "
                    + "1930 cyber crime, 14416 Tele-MANAS mental health. For thoughts of self-harm, be caring and point to 14416 and 112.\n"
                    + "You cannot send alerts, call anyone, see the user's location, or access their contacts. Never claim you did. "
                    + "Do not ask for the user's address, phone number or other personal details.\n"
                    + "Do not give legal or medical diagnoses; suggest a professional or a helpline. Never encourage confronting or "
                    + "fighting an attacker; prefer getting to a safe public place and getting help.\n"
                    + "App facts (do not invent others): the red SOS button sends an SMS with a link to the last known location to the "
                    + "saved emergency contacts immediately. Pressing Volume Down 3 times (needs Accessibility access), saying 'Help me now', "
                    + "and fall detection each start a 10-second countdown with a Cancel button before the SMS is sent. Fall detection uses "
                    + "the phone's motion sensor and can make mistakes. Safe Route compares walking routes by how much each stays within "
                    + "300 m of police stations and hospitals, using OpenStreetMap data; it does not use crime data, lighting or crowds. "
                    + "If you are unsure about the app, say so.\n"
                    + "If asked, say you are an AI and can make mistakes.";

    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(40, TimeUnit.SECONDS)
            .build();

    public static boolean isConfigured() {
        String key = ApiConfig.ANTHROPIC_API_KEY;
        return AI_ENABLED && key != null && !key.trim().isEmpty() && !key.startsWith("PASTE_");
    }

    /** history = list of {"user"|"assistant", text}, starting with "user" and ending with the new question. */
    public void ask(List<String[]> history, final Listener listener) {
        final String bodyJson;
        try {
            bodyJson = buildBody(history);
        } catch (JSONException e) {
            listener.onError("Something went wrong preparing the question.");
            return;
        }

        Request request = new Request.Builder()
                .url(API_URL)
                .addHeader("x-api-key", ApiConfig.ANTHROPIC_API_KEY)
                .addHeader("anthropic-version", API_VERSION)
                .addHeader("content-type", "application/json")
                .post(RequestBody.create(bodyJson, JSON_TYPE))
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                Log.w(TAG, "Request failed", e);
                listener.onError(OFFLINE_MESSAGE);
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                try (Response r = response) {
                    String text = r.body() != null ? r.body().string() : "";
                    if (r.isSuccessful()) {
                        String reply = cleanUp(parseReply(text));
                        if (reply.isEmpty()) {
                            listener.onError("The AI service sent an empty answer. Please try again.");
                        } else {
                            listener.onSuccess(reply);
                        }
                    } else {
                        Log.w(TAG, "HTTP " + r.code() + ": " + text);
                        listener.onError(messageForError(r.code()));
                    }
                } catch (JSONException e) {
                    Log.w(TAG, "Could not read the answer", e);
                    listener.onError("I couldn’t read the AI service’s answer. Please try again.");
                }
            }
        });
    }

    private static String buildBody(List<String[]> history) throws JSONException {
        JSONObject body = new JSONObject();
        body.put("model", MODEL);
        body.put("max_tokens", MAX_TOKENS);
        body.put("system", SYSTEM_PROMPT);
        JSONArray messages = new JSONArray();
        for (String[] turn : history) {
            messages.put(new JSONObject().put("role", turn[0]).put("content", turn[1]));
        }
        body.put("messages", messages);
        return body.toString();
    }

    private static String parseReply(String json) throws JSONException {
        JSONObject root = new JSONObject(json);
        JSONArray content = root.optJSONArray("content");
        StringBuilder sb = new StringBuilder();
        if (content != null) {
            for (int i = 0; i < content.length(); i++) {
                JSONObject block = content.getJSONObject(i);
                if ("text".equals(block.optString("type"))) {
                    sb.append(block.optString("text"));
                }
            }
        }
        return sb.toString();
    }

    /** The chat bubble shows plain text, so remove any markdown the model adds despite the instructions. */
    private static String cleanUp(String s) {
        return s.replace("**", "").replace("__", "").replace("\n- ", "\n• ")
                .replace("\n* ", "\n• ").replaceAll("(?m)^#+\\s*", "").trim();
    }

    private static String messageForError(int code) {
        switch (code) {
            case 401:
            case 403:
                return "The AI service rejected the key. Check ANTHROPIC_API_KEY in ApiConfig.java.";
            case 404:
                return "The AI service doesn’t know that model name (error 404). Check MODEL in LlmClient.java.";
            case 400:
                return "The AI service couldn’t answer (error 400). Check that your account has credit.";
            case 429:
            case 500:
            case 502:
            case 503:
            case 529:
                return "The AI service is busy right now. Please try again in a moment.";
            default:
                return "Something went wrong with the AI service (error " + code + ").";
        }
    }
}
