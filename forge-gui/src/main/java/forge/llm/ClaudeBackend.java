package forge.llm;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.BadRequestException;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.JsonOutputFormat;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.ThinkingConfigAdaptive;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;

/**
 * Sends LLM player requests to the Claude API through the official Anthropic SDK. Loaded by reflection from
 * {@link LlmClient}, so builds without the SDK on the classpath (Android, iOS) never touch this class.
 */
public class ClaudeBackend implements LlmClient.Backend {
    /** Marks where the game-specific part of the duel system prompt starts; see PlayerControllerLlm. */
    private static final String GAME_SECTION = "\n# This game\n";
    private static final Gson GSON = new Gson();

    private final AnthropicClient client;
    private final AtomicLong inputTokens = new AtomicLong();
    private final AtomicLong cacheReadTokens = new AtomicLong();
    private final AtomicLong cacheWriteTokens = new AtomicLong();
    private final AtomicLong outputTokens = new AtomicLong();

    /** Token use so far, to keep an eye on cost and on whether the prompt cache works. */
    @Override
    public String stats() {
        return "tokens: " + inputTokens.get() + " uncached in, " + cacheReadTokens.get() + " cache read, "
                + cacheWriteTokens.get() + " cache write, " + outputTokens.get() + " out";
    }

    public ClaudeBackend() {
        LlmConfig cfg = LlmConfig.get();
        AnthropicOkHttpClient.Builder b = AnthropicOkHttpClient.builder().fromEnv()
                .timeout(Duration.ofSeconds(Math.max(60, cfg.timeoutSeconds)));
        if (cfg.claudeApiKey != null && !cfg.claudeApiKey.isBlank()) {
            b.apiKey(cfg.claudeApiKey.trim());
        }
        client = b.build();
    }

    @Override
    public LlmClient.Raw complete(String purpose, String system, String user, JsonObject schema, String effort, int maxTokens) throws IOException {
        LlmConfig cfg = LlmConfig.get();
        MessageCreateParams.Builder p = MessageCreateParams.builder()
                .model(cfg.claudeModel)
                .maxTokens(Math.max(maxTokens, cfg.claudeMaxTokens))
                .systemOfTextBlockParams(systemBlocks(system))
                .addUserMessage(user)
                // summarized thinking costs nothing extra and keeps the transcripts readable
                .thinking(ThinkingConfigAdaptive.builder().display(ThinkingConfigAdaptive.Display.SUMMARIZED).build())
                // on a safety-classifier refusal, let the API retry on another model instead of failing the turn
                .putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
                .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"));
        OutputConfig.Builder out = OutputConfig.builder();
        String capped = capEffort(effort, cfg.claudeMaxEffort);
        if (capped != null) {
            out.effort(OutputConfig.Effort.of(capped));
        }
        if (schema != null && cfg.useJsonSchema) {
            JsonOutputFormat.Schema.Builder s = JsonOutputFormat.Schema.builder();
            Map<String, Object> map = GSON.fromJson(schema, new TypeToken<Map<String, Object>>() { }.getType());
            for (Map.Entry<String, Object> e : map.entrySet()) {
                s.putAdditionalProperty(e.getKey(), JsonValue.from(e.getValue()));
            }
            out.format(JsonOutputFormat.builder().schema(s.build()).build());
        }
        p.outputConfig(out.build());

        Message m;
        try {
            m = client.messages().create(p.build());
        } catch (BadRequestException e) {
            String msg = String.valueOf(e.getMessage());
            if (msg.toLowerCase(java.util.Locale.ROOT).contains("too long")) {
                throw new LlmClient.ContextOverflowException(msg);
            }
            throw new IOException("Claude API rejected the request: " + msg, e);
        } catch (AnthropicServiceException | AnthropicIoException e) {
            // the SDK already retried rate limits, overloads and connection errors
            throw new IOException("Claude API error: " + e.getMessage(), e);
        }

        long in = m.usage().inputTokens();
        long read = m.usage().cacheReadInputTokens().orElse(0L);
        long write = m.usage().cacheCreationInputTokens().orElse(0L);
        long outTok = m.usage().outputTokens();
        inputTokens.addAndGet(in);
        cacheReadTokens.addAndGet(read);
        cacheWriteTokens.addAndGet(write);
        outputTokens.addAndGet(outTok);
        String usage = in + " in, " + read + " cache read, " + write + " cache write, " + outTok + " out";

        StringBuilder text = new StringBuilder();
        StringBuilder thinking = new StringBuilder();
        for (ContentBlock block : m.content()) {
            block.text().ifPresent(t -> text.append(t.text()));
            block.thinking().ifPresent(t -> thinking.append(t.thinking()));
        }
        if (m.stopReason().map(r -> r.equals(StopReason.REFUSAL)).orElse(false)) {
            System.err.println("[LLM] Claude declined the " + purpose + " request; the Forge AI decides this one.");
            return new LlmClient.Raw("", thinking.toString(), usage);
        }
        if (m.stopReason().map(r -> r.equals(StopReason.MAX_TOKENS)).orElse(false)) {
            System.err.println("[LLM] Claude hit max_tokens on " + purpose + "; consider raising claudeMaxTokens.");
        }
        return new LlmClient.Raw(text.toString(), thinking.toString(), usage);
    }

    private static final List<String> EFFORT_ORDER = List.of("low", "medium", "high", "xhigh", "max");

    /** The requested effort, lowered to the cap. Empty request: the cap itself, so the model's default never exceeds it. */
    static String capEffort(String requested, String cap) {
        String r = requested == null ? "" : requested.trim().toLowerCase(java.util.Locale.ROOT);
        String c = cap == null ? "" : cap.trim().toLowerCase(java.util.Locale.ROOT);
        if (!EFFORT_ORDER.contains(c)) {
            return EFFORT_ORDER.contains(r) ? r : null;
        }
        if (!EFFORT_ORDER.contains(r)) {
            return c;
        }
        return EFFORT_ORDER.indexOf(r) > EFFORT_ORDER.indexOf(c) ? c : r;
    }

    /**
     * Two cached blocks: the game-independent part (instructions and strategy primer, identical in every duel) and
     * the rest, so a new duel still reuses the cached primer.
     */
    private static List<TextBlockParam> systemBlocks(String system) {
        List<TextBlockParam> blocks = new ArrayList<>();
        int split = system.indexOf(GAME_SECTION);
        if (split > 0) {
            blocks.add(cached(system.substring(0, split)));
            blocks.add(cached(system.substring(split)));
        } else {
            blocks.add(cached(system));
        }
        return blocks;
    }

    private static TextBlockParam cached(String text) {
        return TextBlockParam.builder().text(text).cacheControl(CacheControlEphemeral.builder().build()).build();
    }
}
