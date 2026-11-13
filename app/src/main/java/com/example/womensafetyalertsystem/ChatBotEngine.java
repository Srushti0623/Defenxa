package com.example.womensafetyalertsystem;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The offline, rule-based half of the chatbot. It has no Android or network code.
 * match() returns a ready answer, or null when no rule applies (then the offline fallback is shown).
 * Urgent topics (danger, self-harm) are always answered here, never by the AI service.
 * Helpline numbers are for India; check them before publishing, because numbers can change.
 */
public class ChatBotEngine {

    public static class Reply {
        public final String intent;
        public final String text;
        public final List<ChatAction> actions;

        Reply(String intent, String text, ChatAction... actions) {
            this.intent = intent;
            this.text = text;
            this.actions = new ArrayList<>(Arrays.asList(actions));
        }
    }

    // ---- keyword lists. A trailing * means "starts with" (follow* matches following). ----
    private static final String[] SELF_HARM = {"suicide", "suicidal", "kill myself", "end my life",
            "want to die", "self harm", "hurt myself", "dont want to live", "end it all", "no reason to live"};
    private static final String[] EMERGENCY = {"following me", "followed me", "being followed",
            "someone is following", "is following", "stalking me", "chasing me", "attacked me",
            "attacking me", "grabbed me", "kidnap*", "in danger", "need help now", "feel unsafe",
            "feeling unsafe", "not safe here", "scared", "afraid", "threatening me", "threatened me",
            "an emergency"};
    private static final String[] HARASSMENT = {"harass*", "eve teasing", "eve teaser", "abuse", "abused",
            "abusive", "domestic violence", "blackmail*", "cyberbully*", "bullying", "rape", "raped",
            "molest*", "assault*", "sexual assault", "groped", "groping", "touched me", "catcall*",
            "stalk*", "revenge porn", "morphed", "obscene"};
    private static final String[] HELPLINE = {"helpline*", "hotline*", "national emergency number",
            "police number", "number to call", "numbers to call", "who to call", "who should i call",
            "call police", "contact police", "women helpline", "cyber crime", "cybercrime",
            "report a crime", "file a complaint"};
    private static final String[] VOLUME = {"volume*"};
    private static final String[] VOICE = {"voice*", "microphone", "mic", "speech", "help me now", "speak*", "listening"};
    private static final String[] FALL = {"fall*", "fell", "motion sensor", "accelerometer", "sensor*"};
    private static final String[] TIPS = {"tip*", "advice", "precaution*", "walking alone", "walk alone",
            "alone at night", "late night", "night travel", "cab", "taxi", "uber", "ola", "rapido", "auto",
            "hostel", "travel safely", "stay safe", "safety measures", "safe at night", "self defence",
            "self defense", "pepper spray", "how to be safe"};
    private static final String[] ROUTE = {"route*", "navigat*", "direction*", "destination", "map", "maps",
            "safe path", "path", "safest way", "safe way", "reach home"};
    private static final String[] CONTACTS = {"contact*", "emergency number*", "save number*",
            "add number*", "trusted person", "family member*", "phone number*"};
    private static final String[] SOS = {"sos", "alert*", "panic", "send location", "share location",
            "emergency button", "red button"};
    private static final String[] PRIVACY = {"privacy", "private", "data", "track*", "tracking", "secure",
            "security", "permission*", "who can see", "store*", "location access"};
    private static final String[] MENU = {"what can you do", "what do you do", "features", "about defenxa",
            "what is defenxa", "about the app", "about app", "who are you", "what are you", "menu",
            "options", "capabilities"};
    private static final String[] GREETING = {"hi", "hii", "hiii", "hello", "hey", "namaste", "hola",
            "good morning", "good afternoon", "good evening"};
    private static final String[] THANKS = {"thanks", "thank you", "thx", "thank u"};
    private static final String[] BYE = {"bye", "goodbye", "see you", "see ya"};

    private static final String HELPLINE_LIST =
            "• 112 – Emergency (police, fire, ambulance)\n"
                    + "• 1091 – Women helpline\n"
                    + "• 181 – Women helpline (domestic abuse)\n"
                    + "• 1930 – Cyber crime\n"
                    + "• 14416 – Tele-MANAS (mental health, 24/7)";

    // ------------------------------------------------------------------------------------

    /** Returns an answer, or null if no rule applies. */
    public Reply match(String raw) {
        String t = normalize(raw);
        if (t.isEmpty()) {
            return null;
        }
        String padded = " " + t + " ";
        int words = t.split(" ").length;

        if (hasAny(padded, SELF_HARM)) return selfHarm();
        if (isExact(t, "help", "help me", "help me now", "please help", "please help me",
                "i need help", "need help", "help me please")) return emergency();
        if (hasAny(padded, EMERGENCY)) return emergency();
        if (hasAny(padded, HARASSMENT)) return harassment();
        if (hasAny(padded, HELPLINE)) return helplines();
        if (hasAny(padded, VOLUME)) return volume();
        if (hasAny(padded, VOICE)) return voice();
        if (hasAny(padded, FALL)) return fall();
        if (hasAny(padded, TIPS)) return tips();
        if (hasAny(padded, ROUTE)) return route();
        if (hasAny(padded, CONTACTS)) return contacts();
        if (hasAny(padded, SOS)) return sos();
        if (hasAny(padded, PRIVACY)) return privacy();
        if (hasAny(padded, MENU)) return menu();
        if (words <= 4 && hasAny(padded, GREETING)) return greeting();
        if (words <= 5 && hasAny(padded, THANKS)) {
            return new Reply("thanks", "You’re welcome. Stay safe! Ask me anything else about safety or the app.");
        }
        if (words <= 4 && hasAny(padded, BYE)) {
            return new Reply("bye", "Take care and stay safe. I’m here whenever you need me.");
        }
        if (isExact(t, "ok", "okay", "k", "cool", "got it", "alright")) {
            return new Reply("ack", "👍 Ask me anything else about safety or the app.");
        }
        return null;
    }

    public Reply welcome(boolean aiAvailable) {
        String text = "Hi, I’m the Defenxa assistant. Ask me how the app works, for safety tips, or for helplines. "
                + "If you are in immediate danger, call 112 or press SOS.";
        if (aiAvailable) {
            text += "\n\nQuestions I can’t answer offline are sent to an AI service over the internet, "
                    + "so please don’t share personal details here.";
        }
        return new Reply("welcome", text);
    }

    /** Used when the AI service can’t be reached or isn’t set up. */
    public Reply offlineFallback() {
        return new Reply("offline",
                "I don’t have an answer for that yet. I can help with the topics below.",
                ask("How does SOS work?"), ask("Safe route"), ask("Helplines"), ask("Safety tips"));
    }

    // ---- answers ----------------------------------------------------------------------

    private Reply selfHarm() {
        return new Reply("selfharm",
                "I’m really sorry you’re hurting this much. You don’t have to carry it alone. "
                        + "Please reach out right now to someone you trust, or call Tele-MANAS (14416), "
                        + "a free mental health helpline in India that is open 24/7. "
                        + "If you might act on these thoughts or are in immediate danger, call 112 now.",
                dial("📞 Call 14416", "14416"), dial("📞 Call 112", "112"));
    }

    private Reply emergency() {
        return new Reply("emergency",
                "If you are in immediate danger:\n"
                        + "1. Call 112 now.\n"
                        + "2. Move toward people and light: a shop, petrol station or busy road.\n"
                        + "3. Press SOS to alert your emergency contacts.\n"
                        + "Stay on a call with someone you trust if you can. "
                        + "If you only wanted to know about the app, tap “What can you do?”.",
                dial("📞 Call 112", "112"),
                new ChatAction("🚨 Start SOS countdown", ChatAction.START_SOS, null),
                new ChatAction("📍 Plan a safer route", ChatAction.OPEN_ROUTE, null),
                ask("What can you do?"));
    }

    private Reply harassment() {
        return new Reply("harassment",
                "I’m sorry this is happening. It is not your fault. If you are in danger right now, call 112.\n\n"
                        + "• Women helpline: 181 or 1091 for support and guidance.\n"
                        + "• Online harassment or blackmail: call 1930 or report at cybercrime.gov.in.\n"
                        + "• Keep evidence: screenshots, messages, dates and phone numbers.\n"
                        + "• Tell someone you trust, and report to the police when you feel ready.",
                dial("📞 181", "181"), dial("📞 1091", "1091"), dial("📞 1930", "1930"), dial("📞 112", "112"));
    }

    private Reply helplines() {
        return new Reply("helplines",
                "Helplines in India (tap to dial):\n" + HELPLINE_LIST
                        + "\n\nNumbers can change, so check an official source if a call doesn’t connect.",
                dial("📞 112", "112"), dial("📞 1091", "1091"), dial("📞 181", "181"), dial("📞 1930", "1930"));
    }

    private Reply volume() {
        return new Reply("volume",
                "Press Volume Down 3 times within 2 seconds to start the 10-second SOS countdown, even when the app is closed. "
                        + "It needs Accessibility access: Settings → Accessibility → Defenxa → turn on. "
                        + "After reinstalling the app you may need to switch it on again.",
                new ChatAction("⚙️ Open Accessibility settings", ChatAction.OPEN_ACCESSIBILITY, null));
    }

    private Reply voice() {
        return new Reply("voice",
                "Turn on Voice trigger on the Home screen (it asks for microphone permission). "
                        + "Then say “Help me now” to start the 10-second SOS countdown. "
                        + "It works in English and shows a notification while it listens, which uses some battery.");
    }

    private Reply fall() {
        return new Reply("fall",
                "Fall detection watches your phone’s motion sensor. If it decides you have fallen, it starts the 10-second "
                        + "SOS countdown. Tap Cancel if you are OK. It works best with the phone in a trouser pocket, and it can "
                        + "make mistakes, such as rough handling of the phone, so keep an eye on the Cancel button. "
                        + "Turn it on with the Fall detection switch on the Home screen.");
    }

    private Reply route() {
        return new Reply("route",
                "Safe Route compares walking routes to your destination. Each route is scored by how much of it stays "
                        + "within 300 m of a police station or hospital (from OpenStreetMap), and the best one is shown in green. "
                        + "It does not use crime data, street lighting or crowds, so treat it as one input and trust your judgment.",
                new ChatAction("📍 Open route planner", ChatAction.OPEN_ROUTE, null));
    }

    private Reply contacts() {
        return new Reply("contacts",
                "On the Home screen, enter at least one emergency contact number (the second is optional) and tap "
                        + "Save contacts. SOS messages are sent to these numbers.");
    }

    private Reply sos() {
        return new Reply("sos",
                "The red SOS button on the Home screen sends an SMS with a link to your last known location to your saved "
                        + "emergency contacts right away. Save at least one contact first, and allow SMS and Location permission.\n\n"
                        + "The other triggers (volume button, voice, fall detection) start a 10-second countdown with a Cancel "
                        + "button before the SMS is sent.");
    }

    private Reply privacy() {
        return new Reply("privacy",
                "• Your saved emergency numbers and settings stay on your phone.\n"
                        + "• Location is used when you send an SOS or open the route map. SOS shares it only by SMS with your contacts.\n"
                        + "• Permissions: SMS (to send SOS), Location (SOS link and map), Microphone (voice trigger only), "
                        + "Notifications (countdown).\n"
                        + "• The route screen sends your destination and route points to OpenStreetMap-based map services.\n"
                        + "• Chat messages stay on your phone. They are not sent anywhere.");
    }

    private Reply tips() {
        return new Reply("tips",
                "A few safety habits:\n"
                        + "• Share your trip with someone you trust.\n"
                        + "• Prefer well-lit, busy routes and keep one earphone out.\n"
                        + "• Keep your phone charged and know where the SOS button is.\n"
                        + "• Check the cab’s plate and driver details before getting in, and share them.\n"
                        + "• If something feels wrong, move to a public place and trust your instincts.\n\n"
                        + "Ask me about a specific situation, like late-night travel or a cab ride.");
    }

    private Reply menu() {
        return new Reply("menu",
                "I can help with:\n"
                        + "• How SOS, volume-button SOS, voice trigger and fall detection work\n"
                        + "• Planning a safer walking route\n"
                        + "• Helplines in India\n"
                        + "• Safety tips and general safety information\n\n"
                        + "In immediate danger, press SOS or call 112.",
                ask("How does SOS work?"), ask("Helplines"), ask("Safety tips"));
    }

    private Reply greeting() {
        return new Reply("greeting",
                "Hi! I’m the Defenxa assistant. Ask me how the app works, for safety tips, or for helplines. "
                        + "If you are in immediate danger, call 112 or press SOS.",
                ask("What can you do?"));
    }

    // ---- helpers ----------------------------------------------------------------------

    private static ChatAction dial(String label, String number) {
        return new ChatAction(label, ChatAction.DIAL, number);
    }

    private static ChatAction ask(String question) {
        return new ChatAction(question, ChatAction.ASK, question);
    }

    static String normalize(String raw) {
        if (raw == null) return "";
        String s = raw.toLowerCase().replace("’", "").replace("'", "");
        s = s.replaceAll("[^a-z0-9]+", " ").trim();
        return s;
    }

    private static boolean isExact(String t, String... options) {
        for (String o : options) {
            if (t.equals(o)) return true;
        }
        return false;
    }

    private static boolean hasAny(String padded, String[] phrases) {
        for (String p : phrases) {
            boolean prefix = p.endsWith("*");
            String core = prefix ? p.substring(0, p.length() - 1) : p;
            String needle = prefix ? " " + core : " " + core + " ";
            if (padded.contains(needle)) return true;
        }
        return false;
    }
}
