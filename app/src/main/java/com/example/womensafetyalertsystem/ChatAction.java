package com.example.womensafetyalertsystem;

/** A tappable button shown under a chatbot message. */
public class ChatAction {
    public static final int DIAL = 1;               // value = phone number to dial
    public static final int START_SOS = 2;          // starts the 10-second SOS countdown
    public static final int OPEN_ROUTE = 3;         // opens the Safe Route screen
    public static final int OPEN_ACCESSIBILITY = 4; // opens Android Accessibility settings
    public static final int ASK = 5;                // value = a question to send to the chatbot

    public final String label;
    public final int type;
    public final String value;

    public ChatAction(String label, int type, String value) {
        this.label = label;
        this.type = type;
        this.value = value;
    }
}
