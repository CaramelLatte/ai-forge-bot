package forge.llm;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.RandomAccessFile;
import java.io.Reader;
import java.io.Writer;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * After every game the LLM reviews what happened and writes notes for its future self. Runs on its own thread so
 * the game (or the adventure) can continue while the model thinks.
 */
public class LlmReflector {
    public static class GameReport {
        public String context;
        public String opponent;
        public String opponentColors;
        public boolean won;
        public int turns;
        public String endReason;
        public int startingLife;
        public int opponentStartingLife;
        public int finalLife;
        public int opponentFinalLife;
        public List<String> decisions = new ArrayList<>();
        public List<String> gameLog = new ArrayList<>();
        public List<String> opponentCardsSeen = new ArrayList<>();
        public List<String> deckCardNames = new ArrayList<>();
        /** Rules text of the cards involved, so lessons are based on what the cards actually do. */
        public String cardReference = "";
        public int llmCalls;
        /** Set once the result is in the win/loss record, so a resumed report is not counted twice. */
        public boolean recorded;
        public int gameNumber;
        /** Filled in by reflection. */
        public String summary;
    }

    private static final LlmReflector INSTANCE = new LlmReflector();

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "LLM reflection");
        t.setDaemon(true);
        return t;
    });
    private volatile Future<?> last;
    private volatile String lastSummary = "";

    public static LlmReflector get() {
        return INSTANCE;
    }

    public String getLastSummary() {
        return lastSummary;
    }

    public boolean isBusy() {
        Future<?> f = last;
        return f != null && !f.isDone();
    }

    public void submit(GameReport report) {
        File saved = savePending(report);
        enqueue(report, saved, saved == null ? null : claim(saved));
    }

    private void enqueue(GameReport report, File saved, FileLock claim) {
        last = executor.submit(() -> {
            try {
                process(report, saved);
            } catch (Exception e) {
                System.err.println("[LLM] reflection failed: " + e);
                e.printStackTrace();
            } finally {
                release(claim);
                if (saved != null) {
                    saved.delete();
                    new File(saved.getPath() + ".lock").delete();
                }
            }
        });
    }

    /**
     * Marks a pending report as being worked on by this process, so another Forge process (say a practice run next
     * to Adventure) does not pick it up too. Locks a side file: on Windows a locked file can't be rewritten.
     * Returns null if another process holds it.
     */
    private static FileLock claim(File report) {
        try {
            RandomAccessFile raf = new RandomAccessFile(new File(report.getPath() + ".lock"), "rw");
            FileLock lock = raf.getChannel().tryLock();
            if (lock == null) {
                raf.close();
            }
            return lock;
        } catch (OverlappingFileLockException e) {
            return null; // already ours, being processed
        } catch (IOException e) {
            System.err.println("[LLM] could not lock " + report + ": " + e.getMessage());
            return null;
        }
    }

    private static void release(FileLock lock) {
        if (lock == null) {
            return;
        }
        try {
            lock.release();
            lock.channel().close();
        } catch (IOException ignored) {
        }
    }

    private static File pendingDir() {
        return new File(LlmConfig.getDir(), "pending");
    }

    /** Reports are kept on disk until processed, so quitting the game mid-reflection loses nothing. */
    private static File savePending(GameReport report) {
        File dir = pendingDir();
        dir.mkdirs();
        return writeReport(report, new File(dir, "game-" + System.currentTimeMillis() + ".json"));
    }

    private static File writeReport(GameReport report, File f) {
        try {
            try (Writer w = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8)) {
                new Gson().toJson(report, w);
            }
            return f;
        } catch (Exception e) {
            System.err.println("[LLM] could not save game report: " + e.getMessage());
            return null;
        }
    }

    private boolean resumed = false;

    /** Processes reports left over from a previous session. Safe to call more than once. */
    public synchronized void resumePending() {
        if (resumed) {
            return;
        }
        resumed = true;
        File[] files = pendingDir().listFiles((d, n) -> n.endsWith(".json"));
        if (files == null) {
            return;
        }
        java.util.Arrays.sort(files);
        for (File f : files) {
            FileLock claim = claim(f);
            if (claim == null) {
                continue; // another running Forge is on it
            }
            try (Reader r = new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8)) {
                GameReport report = new Gson().fromJson(r, GameReport.class);
                if (report != null) {
                    System.out.println("[LLM] Resuming reflection on an unfinished game vs " + report.opponent);
                    enqueue(report, f, claim);
                } else {
                    release(claim);
                }
            } catch (Exception e) {
                release(claim);
                System.err.println("[LLM] bad pending report " + f + ": " + e.getMessage());
                f.renameTo(new File(f.getPath() + ".broken"));
            }
        }
    }

    /** Blocks until queued reflections are done (used between practice games so lessons apply to the next one). */
    public void awaitIdle(long timeoutSeconds) {
        Future<?> f = executor.submit(() -> { });
        try {
            f.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (Exception ignored) {
        }
    }

    private static final String SYSTEM_BASE =
            "You are coaching yourself: you are an AI that plays Magic: The Gathering and you are reviewing a game you just played, "
            + "to get better over many games. Be honest and specific. Good lessons are concrete, actionable and reusable "
            + "(\"Hold back a blocker when the opponent has more attackers than you have life to spare\"), not vague (\"play better\"). "
            + "Do not repeat lessons you already have; rate them instead. Answer with one JSON object only.\n\n"
            + "You also play with the strategy guide below in every game. Lessons must agree with it and with the cards' exact rules "
            + "text: never write a lesson that contradicts the guide (for example valuing flash on a creature already in play, or "
            + "giving an opponent's creature a helpful effect). Don't restate the guide either; lessons should add what it can't "
            + "know: how specific cards, decks and opponents play. When an existing lesson contradicts the guide, rate it \"wrong\".";

    /** The coaching instructions plus the current strategy primer. */
    private static String system() {
        String primer = LlmPrimer.get();
        return primer.isEmpty() ? SYSTEM_BASE : SYSTEM_BASE + "\n\n" + primer;
    }

    private void process(GameReport report, File saved) {
        LlmMemory mem = LlmMemory.get();
        if (!report.recorded) {
            report.gameNumber = mem.recordGame(report.context, report.opponent, report.opponentColors, report.won, report.turns,
                    report.opponentCardsSeen);
            report.recorded = true;
            if (saved != null) {
                writeReport(report, saved);
            }
        }
        LlmConfig cfg = LlmConfig.get();
        if (!cfg.reflectAfterGame) {
            return;
        }

        // fit the prompt into the context window: ~3 characters per token, leaving room for the answer
        int budget = Math.max(6000, (cfg.contextTokens - cfg.reflectionMaxTokens - 300) * 3 - system().length());
        int logLines = cfg.reflectionLogLines;
        int decisionLines = 80;
        boolean withCards = true;
        String prompt = buildPrompt(report, mem, logLines, decisionLines, withCards);
        while (prompt.length() > budget && (logLines > 20 || decisionLines > 15 || withCards)) {
            if (logLines > 20) {
                logLines = logLines * 2 / 3;
            } else if (decisionLines > 15) {
                decisionLines = decisionLines * 2 / 3;
            } else {
                withCards = false;
            }
            prompt = buildPrompt(report, mem, logLines, decisionLines, withCards);
        }

        JsonObject schema = schema();
        JsonObject r;
        try {
            try {
                r = LlmClient.get().chat("reflect", system(), prompt, schema, cfg.reflectionReasoningEffort, cfg.reflectionMaxTokens).json;
            } catch (LlmClient.ContextOverflowException e) {
                System.err.println("[LLM] reflection prompt too long for the context window; retrying shorter. Consider raising contextTokens.");
                prompt = buildPrompt(report, mem, Math.min(logLines, 30), Math.min(decisionLines, 20), false);
                r = LlmClient.get().chat("reflect", system(), prompt, schema, cfg.reflectionReasoningEffort, cfg.reflectionMaxTokens).json;
            }
        } catch (Exception e) {
            System.err.println("[LLM] reflection request failed: " + e);
            return;
        }
        if (r == null) {
            return;
        }
        if (r.has("summary")) {
            report.summary = r.get("summary").getAsString();
            mem.setSummary(report.gameNumber, report.summary);
            lastSummary = (report.won ? "Won" : "Lost") + " vs " + report.opponent + ": " + report.summary;
            System.out.println("[LLM] Reflection: " + report.summary);
        }
        if (r.has("lesson_feedback") && r.get("lesson_feedback").isJsonArray()) {
            for (JsonElement el : r.getAsJsonArray("lesson_feedback")) {
                if (el.isJsonObject() && el.getAsJsonObject().has("id")) {
                    JsonObject o = el.getAsJsonObject();
                    mem.rateLesson(o.get("id").getAsString(), o.has("verdict") ? o.get("verdict").getAsString() : "");
                }
            }
        }
        if (r.has("new_lessons") && r.get("new_lessons").isJsonArray()) {
            int n = 0;
            for (JsonElement el : r.getAsJsonArray("new_lessons")) {
                if (!el.isJsonObject() || n >= 3) {
                    continue;
                }
                JsonObject o = el.getAsJsonObject();
                String text = o.has("text") ? o.get("text").getAsString() : "";
                String scope = o.has("scope") ? o.get("scope").getAsString().toLowerCase(Locale.ROOT).trim() : "general";
                String card = o.has("card") ? o.get("card").getAsString().trim() : "";
                String fullScope;
                if (scope.startsWith("opp")) {
                    fullScope = LlmMemory.SCOPE_OPPONENT + LlmMemory.opponentKey(report.opponent);
                } else if (scope.startsWith("card") && !card.isEmpty()) {
                    fullScope = LlmMemory.SCOPE_CARD + card.toLowerCase(Locale.ROOT);
                } else if (scope.startsWith("world")) {
                    fullScope = LlmMemory.SCOPE_WORLD;
                } else {
                    fullScope = LlmMemory.SCOPE_GENERAL;
                }
                LlmMemory.Lesson l = mem.addLesson(fullScope, text);
                if (l != null) {
                    n++;
                    System.out.println("[LLM] New lesson " + l.id + " (" + fullScope + "): " + l.text);
                }
            }
        }

        consolidateIfNeeded(LlmMemory.SCOPE_GENERAL, cfg.maxGeneralLessons);
        consolidateIfNeeded(LlmMemory.SCOPE_OPPONENT + LlmMemory.opponentKey(report.opponent), cfg.maxOpponentLessons);
        consolidateIfNeeded(LlmMemory.SCOPE_WORLD, 12);
        for (String card : report.deckCardNames) {
            consolidateIfNeeded(LlmMemory.SCOPE_CARD + card.toLowerCase(Locale.ROOT), cfg.maxCardLessons);
        }
    }

    private static String buildPrompt(GameReport report, LlmMemory mem, int logLines, int decisionLines, boolean withCards) {
        StringBuilder sb = new StringBuilder();
        if (report.context != null) {
            sb.append("Context: ").append(report.context).append('\n');
        }
        sb.append("Result: you ").append(report.won ? "WON" : "LOST").append(" on turn ").append(report.turns);
        if (report.endReason != null && !report.endReason.isEmpty()) {
            sb.append(" (").append(report.endReason).append(')');
        }
        sb.append(". Life: you started at ").append(report.startingLife).append(" and ended at ").append(report.finalLife)
                .append("; the opponent started at ").append(report.opponentStartingLife).append(" and ended at ").append(report.opponentFinalLife).append(".\n");
        sb.append("Opponent: ").append(report.opponent);
        if (report.opponentColors != null && !report.opponentColors.isEmpty()) {
            sb.append(" (").append(report.opponentColors).append(')');
        }
        sb.append(". Cards they showed: ").append(report.opponentCardsSeen.isEmpty() ? "none" : String.join(", ", report.opponentCardsSeen)).append('\n');
        if (withCards && report.cardReference != null && !report.cardReference.isEmpty()) {
            sb.append("\n== Card reference (exact rules text; base lessons on it) ==\n").append(report.cardReference).append('\n');
        } else {
            sb.append("Your deck contained: ").append(String.join(", ", report.deckCardNames)).append("\n\n");
        }

        sb.append("== Your decisions during the game, with the reasoning you had at the time ==\n");
        List<String> decisions = report.decisions;
        if (decisions.size() > decisionLines) {
            decisions = decisions.subList(decisions.size() - decisionLines, decisions.size());
        }
        for (String d : decisions) {
            sb.append("- ").append(d).append('\n');
        }
        sb.append("\n== Game log ==\n");
        List<String> log = report.gameLog;
        if (log.size() > logLines) {
            sb.append("(earlier lines omitted)\n");
            log = log.subList(log.size() - logLines, log.size());
        }
        for (String l : log) {
            sb.append(l).append('\n');
        }

        sb.append("\n== Lessons you already have ==\n");
        appendScope(sb, mem, "General", LlmMemory.SCOPE_GENERAL);
        appendScope(sb, mem, "About this opponent", LlmMemory.SCOPE_OPPONENT + LlmMemory.opponentKey(report.opponent));
        for (String card : report.deckCardNames) {
            appendScope(sb, mem, "About " + card, LlmMemory.SCOPE_CARD + card.toLowerCase(Locale.ROOT));
        }
        appendScope(sb, mem, "About exploring the world", LlmMemory.SCOPE_WORLD);

        sb.append("\n== Task ==\n")
                .append("1. summary: two or three sentences on why you won or lost, naming the turning point.\n")
                .append("2. new_lessons: at most 3 new lessons. scope is one of \"general\" (applies to any game), \"opponent\" (about this opponent), ")
                .append("\"card\" (how to use one of YOUR cards; put its exact name in card), or \"world\" (which fights to pick or avoid on the adventure map). ")
                .append("Leave card empty unless scope is card. It is fine to return no new lessons if you learned nothing new.\n")
                .append("3. lesson_feedback: for existing lessons that mattered in this game, give the id and verdict \"helpful\" (following it worked, or ignoring it hurt) ")
                .append("or \"wrong\" (it was bad advice here). Skip lessons that were not relevant.\n")
                .append("Reply {\"summary\": \"...\", \"new_lessons\": [{\"scope\": \"general\", \"card\": \"\", \"text\": \"...\"}], \"lesson_feedback\": [{\"id\": \"L1\", \"verdict\": \"helpful\"}]}");

        return sb.toString();
    }

    private static void appendScope(StringBuilder sb, LlmMemory mem, String title, String scope) {
        List<LlmMemory.Lesson> lessons = mem.lessonsInScope(scope);
        if (lessons.isEmpty()) {
            return;
        }
        sb.append(title).append(":\n");
        for (LlmMemory.Lesson l : lessons) {
            sb.append("- [").append(l.id).append("] (score ").append(l.score).append(") ").append(l.text).append('\n');
        }
    }

    private static JsonObject str() {
        JsonObject o = new JsonObject();
        o.addProperty("type", "string");
        return o;
    }

    private static JsonObject obj(Map<String, JsonObject> props) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "object");
        JsonObject p = new JsonObject();
        JsonArray req = new JsonArray();
        for (Map.Entry<String, JsonObject> e : props.entrySet()) {
            p.add(e.getKey(), e.getValue());
            req.add(e.getKey());
        }
        o.add("properties", p);
        o.add("required", req);
        o.addProperty("additionalProperties", false);
        return o;
    }

    private static JsonObject arr(JsonObject items) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "array");
        o.add("items", items);
        return o;
    }

    private static JsonObject schema() {
        Map<String, JsonObject> lesson = new LinkedHashMap<>();
        lesson.put("scope", str());
        lesson.put("card", str());
        lesson.put("text", str());
        Map<String, JsonObject> fb = new LinkedHashMap<>();
        fb.put("id", str());
        fb.put("verdict", str());
        Map<String, JsonObject> root = new LinkedHashMap<>();
        root.put("summary", str());
        root.put("new_lessons", arr(obj(lesson)));
        root.put("lesson_feedback", arr(obj(fb)));
        return obj(root);
    }

    /** When a scope holds too many lessons, ask the model to merge them into fewer, sharper ones. */
    private void consolidateIfNeeded(String scope, int max) {
        LlmMemory mem = LlmMemory.get();
        List<LlmMemory.Lesson> lessons = mem.lessonsInScope(scope);
        if (lessons.size() <= max) {
            return;
        }
        int target = Math.max(2, (int) Math.round(max * 0.6));
        StringBuilder sb = new StringBuilder();
        sb.append("Your notebook section \"").append(scope).append("\" has grown too long. Here are the lessons, with scores ")
                .append("(higher = confirmed more often):\n");
        for (LlmMemory.Lesson l : lessons) {
            sb.append("- (score ").append(l.score).append(") ").append(l.text).append('\n');
        }
        sb.append("\nRewrite them into at most ").append(target).append(" lessons: merge duplicates, drop ones that are vague, contradicted ")
                .append("by better-scored lessons, or contradicted by the strategy guide or the cards' rules (for example targeting the opponent's ")
                .append("creature with an effect that helps it), and keep the most useful specifics. Don't copy advice the guide already gives. ")
                .append("Reply {\"lessons\": [\"...\", \"...\"]}.");
        Map<String, JsonObject> root = new LinkedHashMap<>();
        root.put("lessons", arr(str()));
        try {
            JsonObject r = LlmClient.get().chat("consolidate", system(), sb.toString(), obj(root),
                    LlmConfig.get().reflectionReasoningEffort, LlmConfig.get().reflectionMaxTokens).json;
            if (r == null || !r.has("lessons")) {
                return;
            }
            List<String> texts = new ArrayList<>();
            for (JsonElement el : r.getAsJsonArray("lessons")) {
                String t = el.getAsString().trim();
                if (!t.isEmpty() && texts.size() < target) {
                    texts.add(t);
                }
            }
            if (texts.size() >= 2) {
                mem.replaceScope(scope, texts);
                        System.out.println("[LLM] Consolidated " + lessons.size() + " lessons in " + scope + " into " + texts.size());
            }
        } catch (Exception e) {
            System.err.println("[LLM] consolidation failed: " + e);
        }
    }
}
