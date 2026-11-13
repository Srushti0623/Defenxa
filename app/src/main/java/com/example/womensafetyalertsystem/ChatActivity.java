package com.example.womensafetyalertsystem;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.util.ArrayList;
import java.util.List;

/**
 * Hybrid safety chatbot. Rules answer common and urgent questions instantly and offline (ChatBotEngine).
 * Everything else goes to an AI service (LlmClient) when it is set up and the phone is online.
 */
public class ChatActivity extends AppCompatActivity {

    private static final int MAX_HISTORY = 10;
    private static final String[] SUGGESTIONS = {
            "What can you do?", "How does SOS work?", "I feel unsafe",
            "Helplines", "Safety tips", "How does Safe Route work?"};

    private ScrollView chatScroll;
    private LinearLayout chatContainer;
    private LinearLayout suggestionRow;
    private EditText etMessage;
    private Button btnSend;

    private final ChatBotEngine engine = new ChatBotEngine();
    private final LlmClient llm = new LlmClient();
    /** Conversation sent to the AI: {"user"|"assistant", text}, always alternating and starting with "user". */
    private final List<String[]> history = new ArrayList<>();
    private boolean waiting = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_chat);

        chatScroll = findViewById(R.id.chatScroll);
        chatContainer = findViewById(R.id.chatContainer);
        suggestionRow = findViewById(R.id.suggestionRow);
        etMessage = findViewById(R.id.etMessage);
        btnSend = findViewById(R.id.btnSend);

        // Keep the input bar above the keyboard and the navigation bar
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            v.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, ime.bottom));
            return insets;
        });

        buildSuggestionChips();
        btnSend.setOnClickListener(v -> sendTypedMessage());
        showReply(engine.welcome(LlmClient.isConfigured()));
    }

    // ---- sending ----------------------------------------------------------------------

    private void sendTypedMessage() {
        if (waiting) return;
        String text = etMessage.getText().toString().trim();
        if (text.isEmpty()) return;
        etMessage.setText("");
        handleUserMessage(text);
    }

    private void handleUserMessage(String text) {
        addBubble(text, true);
        history.add(new String[]{"user", text});

        ChatBotEngine.Reply rule = engine.match(text);
        if (rule != null) {
            showReply(rule);
            history.add(new String[]{"assistant", rule.text});
            return;
        }
        if (!LlmClient.isConfigured()) {
            removeLastUserTurn();
            showReply(engine.offlineFallback());
            return;
        }
        askAi();
    }

    private void askAi() {
        setWaiting(true);
        final TextView typing = addBubble("…", false);
        llm.ask(recentHistory(), new LlmClient.Listener() {
            @Override
            public void onSuccess(String reply) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    typing.setText(reply);
                    history.add(new String[]{"assistant", reply});
                    setWaiting(false);
                    scrollToBottom();
                });
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    typing.setText(message);
                    removeLastUserTurn();
                    addActions(engine.offlineFallback().actions);
                    setWaiting(false);
                });
            }
        });
    }

    private List<String[]> recentHistory() {
        int from = Math.max(0, history.size() - MAX_HISTORY);
        List<String[]> recent = new ArrayList<>(history.subList(from, history.size()));
        while (!recent.isEmpty() && "assistant".equals(recent.get(0)[0])) {
            recent.remove(0);   // the API needs the first message to be from the user
        }
        return recent;
    }

    private void removeLastUserTurn() {
        if (!history.isEmpty() && "user".equals(history.get(history.size() - 1)[0])) {
            history.remove(history.size() - 1);
        }
    }

    private void setWaiting(boolean value) {
        waiting = value;
        btnSend.setEnabled(!value);
        btnSend.setAlpha(value ? 0.5f : 1f);
    }

    // ---- actions ----------------------------------------------------------------------

    private void runAction(ChatAction action) {
        try {
            switch (action.type) {
                case ChatAction.DIAL:
                    // ACTION_DIAL only opens the dialer with the number filled in; the user presses call
                    startActivity(new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + action.value)));
                    break;
                case ChatAction.START_SOS:
                    SosAlertHelper.startCountdown(getApplicationContext(), "chat assistant");
                    break;
                case ChatAction.OPEN_ROUTE:
                    startActivity(new Intent(this, SafeRouteActivity.class));
                    break;
                case ChatAction.OPEN_ACCESSIBILITY:
                    startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                    break;
                case ChatAction.ASK:
                    if (!waiting) handleUserMessage(action.value);
                    break;
                default:
                    break;
            }
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "This phone can’t open that.", Toast.LENGTH_SHORT).show();
        }
    }

    // ---- drawing the chat -------------------------------------------------------------

    private void showReply(ChatBotEngine.Reply reply) {
        addBubble(reply.text, false);
        addActions(reply.actions);
    }

    private TextView addBubble(String text, boolean fromUser) {
        TextView bubble = new TextView(this);
        bubble.setText(text);
        bubble.setTextSize(15);
        bubble.setLineSpacing(0f, 1.1f);
        bubble.setPadding(dp(14), dp(10), dp(14), dp(10));
        bubble.setMaxWidth((int) (getResources().getDisplayMetrics().widthPixels * 0.8f));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(4), 0, dp(4));
        if (fromUser) {
            lp.gravity = Gravity.END;
            bubble.setBackgroundResource(R.drawable.ws_bubble_user);
            bubble.setTextColor(Color.WHITE);
        } else {
            lp.gravity = Gravity.START;
            bubble.setBackgroundResource(R.drawable.ws_bubble_bot);
            bubble.setTextColor(ContextCompat.getColor(this, R.color.ws_text));
        }
        chatContainer.addView(bubble, lp);
        scrollToBottom();
        return bubble;
    }

    private void addActions(List<ChatAction> actions) {
        for (ChatAction action : actions) {
            TextView chip = makeChip(action.label);
            chip.setOnClickListener(v -> runAction(action));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.gravity = Gravity.START;
            lp.setMargins(0, dp(3), 0, dp(3));
            chatContainer.addView(chip, lp);
        }
        scrollToBottom();
    }

    private void buildSuggestionChips() {
        for (String question : SUGGESTIONS) {
            TextView chip = makeChip(question);
            chip.setOnClickListener(v -> {
                if (!waiting) handleUserMessage(question);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd(dp(8));
            suggestionRow.addView(chip, lp);
        }
    }

    private TextView makeChip(String label) {
        TextView chip = new TextView(this);
        chip.setText(label);
        chip.setTextSize(14);
        chip.setTypeface(null, Typeface.BOLD);
        chip.setTextColor(ContextCompat.getColor(this, R.color.ws_primary));
        chip.setBackgroundResource(R.drawable.ws_chip_bg);
        chip.setPadding(dp(14), dp(8), dp(14), dp(8));
        chip.setClickable(true);
        chip.setFocusable(true);
        return chip;
    }

    private void scrollToBottom() {
        chatScroll.post(() -> chatScroll.fullScroll(View.FOCUS_DOWN));
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}

