package forge.llm;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import forge.localinstance.properties.ForgeConstants;

/**
 * Settings for the LLM player. Stored as JSON in {@code <userDir>/llm/config.json}; a default file is
 * written on first use so it can be edited by hand. Any field can also be overridden with a
 * {@code -Dforge.llm.<field>=value} system property (e.g. {@code -Dforge.llm.model=openai/gpt-oss-20b}).
 */
public class LlmConfig {
    /**
     * "local" for the OpenAI-compatible server below, or "claude" for the Claude API (desktop builds only). The
     * launch argument {@code --claude} sets this for one run.
     */
    public String provider = "local";
    /** Claude API key. Empty to use the ANTHROPIC_API_KEY environment variable. */
    public String claudeApiKey = "";
    public String claudeModel = "claude-haiku-5-5";
    /** Claude thinks before answering and that counts toward max_tokens, so it needs more room than a local model. */
    public int claudeMaxTokens = 16000;
    /**
     * Highest effort ever sent to Claude ("low", "medium" or "high"); higher requests, such as the medium-effort
     * post-game review, are lowered to it. The critical effort boost is never used with Claude.
     */
    public String claudeMaxEffort = "medium";

    /** OpenAI-compatible endpoint (LM Studio, llama.cpp server, Ollama /v1, vLLM...). */
    public String baseUrl = "http://localhost:1234/v1";
    public String apiKey = "";
    public String model = "openai/gpt-oss-120b";
    /**
     * Smaller, faster local model for Adventure world-map decisions (where to walk, what to fight), used even when
     * Claude plays the card game. Empty to use the main model for those too.
     */
    public String mapModel = "openai/gpt-oss-20b";
    /** Server for {@link #mapModel}; empty for the same server as {@link #baseUrl}. */
    public String mapBaseUrl = "";
    /** Passed as reasoning_effort for reasoning models (gpt-oss). Empty to omit. */
    public String reasoningEffort = "low";
    /** Used instead when lethal is on the board for either side, where a mistake loses the game. Empty to disable. */
    public String criticalReasoningEffort = "medium";
    /** Reflection after a game is not time critical, so it can think harder. */
    public String reflectionReasoningEffort = "medium";
    public double temperature = 0.4;
    public int maxTokens = 1500;
    public int reflectionMaxTokens = 4000;
    public int timeoutSeconds = 180;
    /**
     * Context window the model was loaded with (per parallel slot). Long prompts (mostly post-game reflection) are
     * trimmed to fit. LM Studio loads models with 8192 by default; duel prompts with the strategy primer need about
     * 10k, so 32k or more is recommended.
     */
    public int contextTokens = 32768;
    /** Ask the server to constrain output with a JSON schema. Falls back to plain text if the server rejects it. */
    public boolean useJsonSchema = true;

    /** Show the Forge AI's preferred play next to the options. Helps a weak model, may cause over-reliance. */
    public boolean showAiHint = true;
    /** Let the LLM pick targets for the spells it casts (otherwise the Forge AI picks targets). */
    public boolean llmTargeting = true;
    /** Ask the LLM for responses during the opponent's turn (instants, flash, activated abilities). */
    public boolean respondOnOpponentTurn = true;

    /** Run a reflection pass after each game and store the lessons in memory. */
    public boolean reflectAfterGame = true;
    /** When there are more general lessons than this, they get consolidated by the LLM. */
    public int maxGeneralLessons = 20;
    public int maxOpponentLessons = 6;
    /** Notes kept per card; more get merged by the LLM, and only this many are shown in a duel. */
    public int maxCardLessons = 3;
    /** Number of game log lines handed to the reflection prompt. */
    public int reflectionLogLines = 160;

    /**
     * Adventure save slot the agent plays in: it loads this slot at startup and copies every autosave into it, so
     * progress builds on that save. 0 to just continue the last active save like the Continue button.
     */
    public int adventureSaveSlot = 1;

    /** Write every prompt/response pair to {@code <userDir>/llm/logs}. */
    public boolean logTranscripts = true;

    private static LlmConfig instance;

    public boolean useClaude() {
        return "claude".equalsIgnoreCase(provider) || "anthropic".equalsIgnoreCase(provider);
    }

    /** The model that answers, for status lines. */
    public String activeModel() {
        String main = useClaude() ? claudeModel + " (Claude API)" : model;
        return mapModel == null || mapModel.isBlank() ? main : main + ", map: " + mapModel;
    }

    /** Handles the {@code --claude} launch argument. Returns true if it was one. */
    public static boolean handleLaunchArg(String arg) {
        if ("--claude".equalsIgnoreCase(arg)) {
            System.setProperty("forge.llm.provider", "claude");
            reload();
            return true;
        }
        return false;
    }

    /**
     * Folder for config, memory, logs and pending reviews. {@code -Dforge.llm.dir=/path} points a run somewhere else,
     * so test runs can't touch the real memory.
     */
    public static String getDir() {
        String dir = System.getProperty("forge.llm.dir");
        if (dir != null && !dir.isBlank()) {
            return dir.endsWith(File.separator) ? dir : dir + File.separator;
        }
        return ForgeConstants.USER_DIR + "llm" + File.separator;
    }

    public static synchronized LlmConfig get() {
        if (instance == null) {
            instance = load();
        }
        return instance;
    }

    public static synchronized void reload() {
        instance = load();
    }

    private static LlmConfig load() {
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        File file = new File(getDir(), "config.json");
        LlmConfig cfg = null;
        if (file.exists()) {
            try (Reader r = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
                cfg = gson.fromJson(r, LlmConfig.class);
            } catch (Exception e) {
                System.err.println("[LLM] Could not read " + file + ": " + e.getMessage());
            }
        }
        if (cfg == null) {
            cfg = new LlmConfig();
        }
        // rewrite so new fields show up with their defaults
        try {
            file.getParentFile().mkdirs();
            try (Writer w = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) {
                gson.toJson(cfg, w);
            }
        } catch (Exception e) {
            System.err.println("[LLM] Could not write " + file + ": " + e.getMessage());
        }
        cfg.applySystemProperties();
        return cfg;
    }

    private void applySystemProperties() {
        for (java.lang.reflect.Field f : LlmConfig.class.getFields()) {
            if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                continue;
            }
            String v = System.getProperty("forge.llm." + f.getName());
            if (v == null) {
                continue;
            }
            try {
                Class<?> t = f.getType();
                if (t == String.class) {
                    f.set(this, v);
                } else if (t == int.class) {
                    f.setInt(this, Integer.parseInt(v));
                } else if (t == double.class) {
                    f.setDouble(this, Double.parseDouble(v));
                } else if (t == boolean.class) {
                    f.setBoolean(this, Boolean.parseBoolean(v));
                }
            } catch (Exception e) {
                System.err.println("[LLM] Bad value for forge.llm." + f.getName() + ": " + v);
            }
        }
    }
}
