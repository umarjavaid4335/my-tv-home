package dev.mytvhome.home;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Voice assistant brain: Gemini (owner's Google AI Studio key, free tier) with function calling.
 * The model can only act through the small allow-list of TV tools below; it never builds intents itself.
 * The key is read from assets/secrets/assistant.json, injected at build time from private/ (never in git).
 */
final class Assistant {
    /** TV actions, implemented by HomeActivity and always run on the main thread. */
    interface Tv {
        String openApp(String name);
        String volume(String action, int level);
        String setBackground(String name);
        String weatherSummary();
        String showMovies(JSONArray movies);
        String youtube(String query);
        List<String> appNames();
        List<String> backgroundNames();
    }

    interface Callback {
        void onAnswer(String text);
        void onError(String message);
    }

    // Free-tier limits are per model and per minute, so a rate-limited or busy model falls through to the next.
    private static final String[] CHAT_MODELS = {"gemini-2.5-flash", "gemini-2.5-flash-lite", "gemini-3-flash-preview"};
    private static final String[] HEAR_MODELS = {"gemini-2.5-flash-lite", "gemini-2.5-flash"};
    private static final String URL_FMT = "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent?key=%s";
    private static final int MAX_TOOL_ROUNDS = 4, KEEP_TURNS = 8;

    private final Tv tv;
    private final String key;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<JSONObject> history = new ArrayList<>();   // short memory for follow-ups
    private volatile boolean busy;

    Assistant(Context c, Tv tv) {
        this.tv = tv;
        String k = null;
        try (InputStream in = c.getAssets().open("secrets/assistant.json")) {
            k = new JSONObject(read(in)).optString("google_api_key", null);
        } catch (Exception ignored) {
            // no key built in: ask() reports it
        }
        key = k;
    }

    boolean ready() { return key != null && !key.isEmpty(); }

    void ask(final String question, final Callback cb) {
        if (!ready()) { cb.onError("The assistant has no Google API key yet."); return; }
        if (busy) return;
        busy = true;
        new Thread(() -> {
            try {
                final String answer = converse(question);
                main.post(() -> cb.onAnswer(answer));
            } catch (Exception e) {
                Log.w("MyHome", "assistant failed", e);
                main.post(() -> cb.onError(friendly(e)));
            } finally {
                busy = false;
            }
        }, "assistant").start();
    }

    interface Heard { void onText(String text); void onError(String message); }

    /** Speech -> text with Gemini (Flash-Lite: fast, generous free quota). Empty text = nothing understood. */
    void transcribe(final byte[] wav, final Heard cb) {
        if (!ready()) { cb.onError("The assistant has no Google API key yet."); return; }
        new Thread(() -> {
            try {
                JSONObject body = new JSONObject()
                        .put("contents", new JSONArray().put(turn("user", new JSONArray()
                                .put(new JSONObject().put("inline_data", new JSONObject().put("mime_type", "audio/wav")
                                        .put("data", android.util.Base64.encodeToString(wav, android.util.Base64.NO_WRAP))))
                                .put(new JSONObject().put("text", "Someone is speaking a request to their TV. Transcribe it "
                                        + "exactly, in the language spoken (English, or Urdu in Urdu script). If there is no "
                                        + "clear human speech, set speech=false and text=\"\".")))))
                        // Structured output, so "no speech" can never be mistaken for a question.
                        .put("generationConfig", new JSONObject().put("temperature", 0).put("maxOutputTokens", 200)
                                .put("thinkingConfig", new JSONObject().put("thinkingBudget", 0))
                                .put("responseMimeType", "application/json")
                                .put("responseSchema", new JSONObject().put("type", "OBJECT").put("properties", new JSONObject()
                                        .put("speech", new JSONObject().put("type", "BOOLEAN"))
                                        .put("text", new JSONObject().put("type", "STRING")))
                                        .put("required", new JSONArray().put("speech").put("text"))));
                JSONObject res = post(HEAR_MODELS, body);
                JSONArray parts = res.getJSONArray("candidates").getJSONObject(0).getJSONObject("content").optJSONArray("parts");
                JSONObject heard = new JSONObject(parts == null ? "{}" : parts.getJSONObject(0).optString("text", "{}"));
                String t = heard.optBoolean("speech") ? heard.optString("text", "").trim() : "";
                final String text = t.matches("(?s).*\\p{L}.*") ? t : "";   // e.g. "00:00 00:01" from noise = nothing
                main.post(() -> cb.onText(text));
            } catch (Exception e) {
                Log.w("MyHome", "transcribe failed", e);
                main.post(() -> cb.onError(friendly(e)));
            }
        }, "transcribe").start();
    }

    private String converse(String question) throws Exception {
        List<JSONObject> contents = new ArrayList<>(history);
        JSONObject user = turn("user", new JSONArray().put(new JSONObject().put("text", question)));
        contents.add(user);
        for (int round = 0; round <= MAX_TOOL_ROUNDS; round++) {
            JSONObject res = post(CHAT_MODELS, request(contents));
            JSONObject content = res.getJSONArray("candidates").getJSONObject(0).getJSONObject("content");
            JSONArray parts = content.optJSONArray("parts");
            if (parts == null) parts = new JSONArray();
            contents.add(turn("model", parts));
            JSONArray responses = new JSONArray();
            StringBuilder text = new StringBuilder();
            for (int i = 0; i < parts.length(); i++) {
                JSONObject p = parts.getJSONObject(i);
                if (p.has("functionCall")) {
                    JSONObject call = p.getJSONObject("functionCall");
                    String result = runTool(call.getString("name"), call.optJSONObject("args"));
                    responses.put(new JSONObject().put("functionResponse", new JSONObject()
                            .put("name", call.getString("name"))
                            .put("response", new JSONObject().put("result", result))));
                } else if (p.has("text")) {
                    text.append(p.getString("text"));
                }
            }
            if (responses.length() == 0) {
                String answer = text.toString().trim();
                remember(user, turn("model", new JSONArray().put(new JSONObject().put("text", answer))));
                return answer.isEmpty() ? "Done." : answer;
            }
            contents.add(turn("user", responses));
        }
        return "Done.";
    }

    private void remember(JSONObject user, JSONObject model) {
        history.add(user);
        history.add(model);
        while (history.size() > KEEP_TURNS) history.remove(0);
    }

    /** Runs a tool on the main thread and waits for its short text result. */
    private String runTool(final String name, final JSONObject a) throws InterruptedException {
        final String[] out = {"error: unknown tool"};
        final JSONObject args = a != null ? a : new JSONObject();
        final CountDownLatch done = new CountDownLatch(1);
        main.post(() -> {
            try {
                switch (name) {
                    case "open_app": out[0] = tv.openApp(args.optString("name")); break;
                    case "volume": out[0] = tv.volume(args.optString("action"), args.optInt("level", -1)); break;
                    case "set_background": out[0] = tv.setBackground(args.optString("name")); break;
                    case "get_weather": out[0] = tv.weatherSummary(); break;
                    case "show_movies": out[0] = tv.showMovies(args.optJSONArray("movies")); break;
                    case "youtube_search": out[0] = tv.youtube(args.optString("query")); break;
                    default: break;
                }
            } catch (Exception e) {
                out[0] = "error: " + e.getMessage();
            }
            done.countDown();
        });
        done.await(10, TimeUnit.SECONDS);
        Log.i("MyHome", "tool " + name + " -> " + out[0]);
        return out[0];
    }

    private JSONObject request(List<JSONObject> contents) throws Exception {
        String now = new SimpleDateFormat("EEEE d MMMM yyyy, h:mm a", Locale.UK).format(new Date());
        String system = "You are the voice assistant on a family TV in Lahore, Pakistan. Now: " + now + " (Asia/Karachi).\n"
                + "Replies are shown on screen and read aloud: keep them to one or two short, warm sentences, no markdown, no lists.\n"
                + "Reply in the language the user spoke (English or Urdu; Urdu in Urdu script).\n"
                + "Use tools to act on the TV: open_app for 'open/play/watch <app>', volume for louder/quieter/mute/set level, "
                + "set_background to change the home screen photo, get_weather for weather (Lahore, Celsius), "
                + "youtube_search to play or find something on YouTube (songs, trailers, videos).\n"
                + "You cannot open a specific title inside Netflix or Prime Video; open the app instead.\n"
                + "For movie or show recommendations ALWAYS call show_movies with 3 to 5 real titles that suit the request "
                + "(family-friendly unless asked otherwise), then say one line about them. Prefer titles likely on Netflix, "
                + "Prime Video or YouTube in Pakistan; set 'service' only if you are fairly sure, else 'unknown'.\n"
                + "Installed apps: " + tv.appNames() + ". Backgrounds: " + tv.backgroundNames() + ".\n"
                + "You cannot browse the web; for very recent news or live scores say you can't check live information.\n"
                + "There are no smart lights or other smart-home devices yet; say so if asked.";
        JSONArray decls = new JSONArray()
                .put(fn("open_app", "Open an installed TV app by its name.",
                        props().put("name", str("App name, e.g. Netflix, YouTube, Prime Video")), "name"))
                .put(fn("volume", "Change the TV volume.",
                        props().put("action", new JSONObject().put("type", "STRING")
                                .put("enum", new JSONArray().put("up").put("down").put("mute").put("unmute").put("set")))
                                .put("level", new JSONObject().put("type", "INTEGER").put("description", "0-100, only for action=set")),
                        "action"))
                .put(fn("set_background", "Change the home screen background photo.",
                        props().put("name", str("Background title or 'next'")), "name"))
                .put(fn("get_weather", "Current weather and 4-day forecast for Lahore.", props(), null))
                .put(fn("youtube_search", "Open YouTube search results, e.g. songs, trailers, cooking videos, news clips.",
                        props().put("query", str("What to search on YouTube")), "query"))
                .put(fn("show_movies", "Show movie/show recommendation cards on the TV.",
                        props().put("movies", new JSONObject().put("type", "ARRAY").put("items", new JSONObject()
                                .put("type", "OBJECT").put("properties", new JSONObject()
                                        .put("title", str("Exact title"))
                                        .put("year", new JSONObject().put("type", "INTEGER"))
                                        .put("kind", str("movie or series"))
                                        .put("reason", str("Why it fits, max 12 words"))
                                        .put("service", str("netflix, prime, youtube or unknown")))
                                .put("required", new JSONArray().put("title").put("year").put("reason")))), "movies"));
        return new JSONObject()
                .put("systemInstruction", new JSONObject().put("parts", new JSONArray().put(new JSONObject().put("text", system))))
                .put("contents", new JSONArray(contents))
                .put("tools", new JSONArray().put(new JSONObject().put("functionDeclarations", decls)))
                .put("generationConfig", new JSONObject().put("temperature", 0.5).put("maxOutputTokens", 700)
                        .put("thinkingConfig", new JSONObject().put("thinkingBudget", 0)));
    }

    private JSONObject post(String[] models, JSONObject body) throws Exception {
        Exception last = null;
        for (String m : models) {
            try {
                return post(m, body);
            } catch (IllegalStateException e) {            // only rate limits / overload fall through
                last = e;
                String msg = String.valueOf(e.getMessage());
                if (!msg.startsWith("HTTP 429") && !msg.startsWith("HTTP 503")) throw e;
                Log.i("MyHome", m + " unavailable (" + msg.substring(0, 8) + "), trying next model");
            }
        }
        throw last;
    }

    private JSONObject post(String model, JSONObject body) throws Exception {
        if (model.startsWith("gemini-3")) {                // 2.5-style thinkingBudget isn't valid for Gemini 3
            body = new JSONObject(body.toString());
            JSONObject gc = body.optJSONObject("generationConfig");
            if (gc != null) gc.remove("thinkingConfig");
        }
        HttpURLConnection c = (HttpURLConnection) new URL(String.format(URL_FMT, model, key)).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(30000);
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        try (OutputStream o = c.getOutputStream()) { o.write(body.toString().getBytes("UTF-8")); }
        int code = c.getResponseCode();
        String text = read(code < 400 ? c.getInputStream() : c.getErrorStream());
        c.disconnect();
        if (code != 200) throw new IllegalStateException("HTTP " + code + " " + text.substring(0, Math.min(200, text.length())));
        return new JSONObject(text);
    }

    private static String friendly(Exception e) {
        String m = String.valueOf(e.getMessage());
        if (m.contains("HTTP 429")) return "The assistant is busy (free quota reached). Try again in a minute.";
        if (m.contains("HTTP 4")) return "The assistant's key was rejected.";
        return "I couldn't reach the assistant. Check the internet connection.";
    }

    private static JSONObject turn(String role, JSONArray parts) throws Exception {
        return new JSONObject().put("role", role).put("parts", parts);
    }

    private static JSONObject props() { return new JSONObject(); }

    private static JSONObject str(String d) throws Exception {
        return new JSONObject().put("type", "STRING").put("description", d);
    }

    private static JSONObject fn(String name, String desc, JSONObject properties, String required) throws Exception {
        JSONObject params = new JSONObject().put("type", "OBJECT").put("properties", properties);
        if (required != null) params.put("required", new JSONArray().put(required));
        JSONObject f = new JSONObject().put("name", name).put("description", desc);
        if (properties.length() > 0) f.put("parameters", params);
        return f;
    }

    static String read(InputStream in) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        for (int n; (n = in.read(buf)) > 0; ) b.write(buf, 0, n);
        return b.toString("UTF-8");
    }
}
