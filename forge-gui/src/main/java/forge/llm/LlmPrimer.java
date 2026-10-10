package forge.llm;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import forge.localinstance.properties.ForgeConstants;

/**
 * The strategy guide every duel prompt starts with: {@code res/llm/primer.md}, or {@code <userDir>/llm/primer.md}
 * when that exists, so it can be tuned without rebuilding. Read when a duel starts, so edits apply to the next one.
 * Its text never depends on the game, which keeps the beginning of the system prompt identical across duels and
 * lets the server reuse its prompt cache.
 */
public final class LlmPrimer {
    private LlmPrimer() {
    }

    public static String get() {
        for (File f : new File[]{new File(LlmConfig.getDir(), "primer.md"), new File(ForgeConstants.RES_DIR, "llm" + File.separator + "primer.md")}) {
            if (f.isFile()) {
                try {
                    return Files.readString(f.toPath(), StandardCharsets.UTF_8).trim();
                } catch (IOException e) {
                    System.err.println("[LLM] Could not read " + f + ": " + e.getMessage());
                }
            }
        }
        System.err.println("[LLM] No strategy primer found (res/llm/primer.md)");
        return "";
    }
}
