package forge.llm;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Minimal client for an OpenAI-compatible chat completions endpoint. Uses HttpURLConnection so it also works on
 * Android. With provider "claude" requests go to the Claude API instead ({@link ClaudeBackend}, desktop only).
 * Calls are blocking; callers decide which thread they run on.
 */
public class LlmClient {
    public static final class Response {
        public final String content;
        public final String reasoning;
        /** First JSON object found in the content, or null. */
        public final JsonObject json;
        public final long millis;

        Response(String content, String reasoning, JsonObject json, long millis) {
            this.content = content;
            this.reasoning = reasoning;
            this.json = json;
            this.millis = millis;
        }
    }

    /** Text and reasoning from a backend other than the OpenAI-compatible one. */
    public static final class Raw {
        final String content;
        final String reasoning;
        /** Token use of this call, for the transcript. */
        final String usage;

        public Raw(String content, String reasoning, String usage) {
            this.content = content;
            this.reasoning = reasoning;
            this.usage = usage;
        }
    }

    /** Another way to reach a model (the Claude API). */
    public interface Backend {
        Raw complete(String purpose, String system, String user, JsonObject schema, String effort, int maxTokens) throws IOException;

        default String stats() {
            return "";
        }
    }

    private volatile Backend claude;

    /** Loaded by name so builds without the Anthropic SDK (mobile) never link it. */
    private Backend claude() throws IOException {
        if (claude == null) {
            synchronized (this) {
                if (claude == null) {
                    try {
                        claude = (Backend) Class.forName("forge.llm.ClaudeBackend").getDeclaredConstructor().newInstance();
                    } catch (ReflectiveOperationException | LinkageError e) {
                        throw new IOException("The Claude API backend is not available in this build: " + e, e);
                    }
                }
            }
        }
        return claude;
    }

    private static final LlmClient INSTANCE = new LlmClient();
    private static final Gson GSON = new Gson();

    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicInteger failures = new AtomicInteger();
    private final AtomicLong totalMillis = new AtomicLong();
    /** Set when the server refuses response_format so we stop sending it. */
    private volatile boolean schemaUnsupported = false;

    public static LlmClient get() {
        return INSTANCE;
    }

    public String getStats() {
        int n = calls.get();
        Backend c = claude;
        String extra = c == null ? "" : c.stats();
        return n + " calls, " + failures.get() + " failed, avg " + (n == 0 ? 0 : totalMillis.get() / n) + " ms" + (extra.isEmpty() ? "" : ", " + extra);
    }

    /**
     * @param purpose short tag used in transcripts ("priority", "attack", "reflect"...)
     * @param system system prompt (keep it stable between calls so the server can reuse its prompt cache)
     * @param user per-call prompt
     * @param schema JSON schema for the answer, or null for free text
     */
    public Response chat(String purpose, String system, String user, JsonObject schema, String reasoningEffort, int maxTokens) throws IOException {
        LlmConfig cfg = LlmConfig.get();
        if (cfg.useClaude()) {
            long start = System.currentTimeMillis();
            calls.incrementAndGet();
            Raw raw;
            try {
                raw = claude().complete(purpose, system, user, schema, reasoningEffort, maxTokens);
            } catch (IOException e) {
                failures.incrementAndGet();
                throw e;
            }
            return finish(purpose, system, user, schema, raw.content, raw.reasoning, raw.usage, start);
        }
        return chatLocal(purpose, system, user, schema, reasoningEffort, maxTokens, cfg.model, cfg.baseUrl);
    }

    /**
     * For world-map decisions, which don't need much intelligence: the {@code mapModel} on the local server when one
     * is set (even when the card game is played by Claude), otherwise the same model as everything else.
     */
    public Response chatMap(String purpose, String system, String user, JsonObject schema, String reasoningEffort, int maxTokens) throws IOException {
        LlmConfig cfg = LlmConfig.get();
        if (cfg.mapModel == null || cfg.mapModel.isBlank()) {
            return chat(purpose, system, user, schema, reasoningEffort, maxTokens);
        }
        String base = cfg.mapBaseUrl == null || cfg.mapBaseUrl.isBlank() ? cfg.baseUrl : cfg.mapBaseUrl;
        return chatLocal(purpose, system, user, schema, reasoningEffort, maxTokens, cfg.mapModel.trim(), base);
    }

    /** A request to the OpenAI-compatible server at {@code baseUrl}. */
    private Response chatLocal(String purpose, String system, String user, JsonObject schema, String reasoningEffort, int maxTokens,
                               String model, String baseUrl) throws IOException {
        LlmConfig cfg = LlmConfig.get();
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        JsonArray messages = new JsonArray();
        messages.add(message("system", system));
        messages.add(message("user", user));
        body.add("messages", messages);
        body.addProperty("temperature", cfg.temperature);
        body.addProperty("max_tokens", maxTokens);
        body.addProperty("stream", false);
        if (reasoningEffort != null && !reasoningEffort.isEmpty()) {
            body.addProperty("reasoning_effort", reasoningEffort);
        }
        boolean withSchema = schema != null && cfg.useJsonSchema && !schemaUnsupported;
        if (withSchema) {
            JsonObject rf = new JsonObject();
            rf.addProperty("type", "json_schema");
            JsonObject js = new JsonObject();
            js.addProperty("name", purpose.replaceAll("[^a-zA-Z0-9_]", "_"));
            js.addProperty("strict", true);
            js.add("schema", schema);
            rf.add("json_schema", js);
            body.add("response_format", rf);
        }

        long start = System.currentTimeMillis();
        calls.incrementAndGet();
        String raw;
        try {
            raw = post(cfg, baseUrl, body);
        } catch (HttpStatusException e) {
            String msg = e.getMessage().toLowerCase();
            if (msg.contains("context") && (msg.contains("exceed") || msg.contains("length") || msg.contains("too long"))) {
                failures.incrementAndGet();
                throw new ContextOverflowException(e.getMessage());
            }
            try {
                if (withSchema && e.status == 400 && (msg.contains("response_format") || msg.contains("schema") || msg.contains("grammar"))) {
                    // server does not understand json_schema, go without it from now on
                    System.err.println("[LLM] Server rejected JSON schema output, continuing without it: " + e.getMessage());
                    schemaUnsupported = true;
                    body.remove("response_format");
                }
                // otherwise a transient server error (model reloading, malformed generation): try once more
                raw = post(cfg, baseUrl, body);
            } catch (IOException e2) {
                failures.incrementAndGet();
                throw e2;
            }
        } catch (IOException e) {
            // one retry, local servers sometimes drop a connection while swapping models
            try {
                raw = post(cfg, baseUrl, body);
            } catch (IOException e2) {
                failures.incrementAndGet();
                throw e2;
            }
        }
        JsonObject resp = JsonParser.parseString(raw).getAsJsonObject();
        JsonObject msg = resp.getAsJsonArray("choices").get(0).getAsJsonObject().getAsJsonObject("message");
        String content = getString(msg, "content");
        String reasoning = getString(msg, "reasoning");
        if (reasoning == null) {
            reasoning = getString(msg, "reasoning_content");
        }
        return finish(purpose, system, user, schema, content, reasoning, null, start);
    }

    private Response finish(String purpose, String system, String user, JsonObject schema, String content, String reasoning, String usage, long start) {
        long millis = System.currentTimeMillis() - start;
        totalMillis.addAndGet(millis);
        if (content == null) {
            content = "";
        }
        // some servers leave the harmony/think markup in the content
        content = content.replaceAll("(?s)<think>.*?</think>", "").trim();
        JsonObject json = schema == null ? null : extractJson(content);
        if (schema != null && json == null) {
            failures.incrementAndGet();
        }
        transcript(purpose, system, user, content, reasoning, usage, millis);
        return new Response(content, reasoning, json, millis);
    }

    private static JsonObject message(String role, String content) {
        JsonObject m = new JsonObject();
        m.addProperty("role", role);
        m.addProperty("content", content);
        return m;
    }

    private static String getString(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? null : e.getAsString();
    }

    /** Pulls the first balanced {...} block out of a reply and parses it. */
    public static JsonObject extractJson(String text) {
        if (text == null) {
            return null;
        }
        int start = text.indexOf('{');
        while (start >= 0) {
            int depth = 0;
            boolean inString = false;
            for (int i = start; i < text.length(); i++) {
                char ch = text.charAt(i);
                if (inString) {
                    if (ch == '\\') {
                        i++;
                    } else if (ch == '"') {
                        inString = false;
                    }
                    continue;
                }
                if (ch == '"') {
                    inString = true;
                } else if (ch == '{') {
                    depth++;
                } else if (ch == '}') {
                    depth--;
                    if (depth == 0) {
                        try {
                            JsonElement el = JsonParser.parseString(text.substring(start, i + 1));
                            if (el.isJsonObject()) {
                                return el.getAsJsonObject();
                            }
                        } catch (Exception ignored) {
                        }
                        break;
                    }
                }
            }
            start = text.indexOf('{', start + 1);
        }
        return null;
    }

    /** The prompt did not fit in the model's context window. Callers can retry with a shorter prompt. */
    public static final class ContextOverflowException extends IOException {
        ContextOverflowException(String msg) {
            super(msg);
        }
    }

    private static final class HttpStatusException extends IOException {
        final int status;

        HttpStatusException(int status, String msg) {
            super("HTTP " + status + ": " + msg);
            this.status = status;
        }
    }

    private static String post(LlmConfig cfg, String baseUrl, JsonObject body) throws IOException {
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        HttpURLConnection conn = (HttpURLConnection) new URL(base + "/chat/completions").openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(cfg.timeoutSeconds * 1000);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        if (cfg.apiKey != null && !cfg.apiKey.isEmpty()) {
            conn.setRequestProperty("Authorization", "Bearer " + cfg.apiKey);
        }
        try (OutputStream os = conn.getOutputStream()) {
            os.write(GSON.toJson(body).getBytes(StandardCharsets.UTF_8));
        }
        int status = conn.getResponseCode();
        InputStream in = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
        String text = in == null ? "" : readAll(in);
        conn.disconnect();
        if (status >= 400) {
            throw new HttpStatusException(status, text.length() > 300 ? text.substring(0, 300) : text);
        }
        return text;
    }

    private static String readAll(InputStream in) throws IOException {
        try (InputStream is = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private synchronized void transcript(String purpose, String system, String user, String content, String reasoning, String usage, long millis) {
        if (!LlmConfig.get().logTranscripts) {
            return;
        }
        try {
            File dir = new File(LlmConfig.getDir(), "logs");
            dir.mkdirs();
            String day = new SimpleDateFormat("yyyyMMdd").format(new Date());
            JsonObject entry = new JsonObject();
            entry.addProperty("time", new SimpleDateFormat("HH:mm:ss").format(new Date()));
            entry.addProperty("purpose", purpose);
            entry.addProperty("millis", millis);
            entry.addProperty("systemHash", system.hashCode());
            entry.addProperty("user", user);
            entry.addProperty("reasoning", reasoning);
            entry.addProperty("content", content);
            if (usage != null) {
                entry.addProperty("usage", usage);
            }
            try (Writer w = new OutputStreamWriter(new FileOutputStream(new File(dir, "transcript-" + day + ".jsonl"), true), StandardCharsets.UTF_8)) {
                w.write(GSON.toJson(entry));
                w.write('\n');
            }
            // system prompts are long and repeat, store each distinct one once
            File sys = new File(dir, "system-" + Integer.toHexString(system.hashCode()) + ".txt");
            if (!sys.exists()) {
                try (Writer w = new OutputStreamWriter(new FileOutputStream(sys), StandardCharsets.UTF_8)) {
                    w.write(system);
                }
            }
        } catch (IOException e) {
            System.err.println("[LLM] transcript write failed: " + e.getMessage());
        }
    }
}
