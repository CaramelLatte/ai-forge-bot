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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/**
 * Long term memory of the LLM player: lessons it wrote for itself after games, what it knows about each opponent,
 * and a short history of results. Persisted as JSON in {@code <userDir>/llm/memory.json} so it is easy to read
 * (and edit) by hand.
 *
 * Lessons carry a score. Reflection marks lessons as helpful (+) or wrong (-); lessons that drop too low are
 * forgotten, and when there are too many general lessons the reflector asks the LLM to merge them.
 */
public class LlmMemory {
    public static final String SCOPE_GENERAL = "general";
    public static final String SCOPE_WORLD = "world";
    public static final String SCOPE_OPPONENT = "opponent:";
    public static final String SCOPE_CARD = "card:";

    public static class Lesson {
        public String id;
        public String scope;
        public String text;
        public int score = 1;
        public int createdGame;
        public int lastConfirmedGame;
    }

    public static class OpponentRecord {
        public String name;
        public int wins;
        public int losses;
        public String colors = "";
        /** card name -> number of games it was seen in */
        public Map<String, Integer> cardsSeen = new LinkedHashMap<>();
    }

    public static class GameRecord {
        public int game;
        public long time;
        public String context;
        public String opponent;
        public boolean won;
        public int turns;
        public String summary;
    }

    public static class Data {
        public int gamesPlayed;
        public int nextLessonId = 1;
        public List<Lesson> lessons = new ArrayList<>();
        public Map<String, OpponentRecord> opponents = new LinkedHashMap<>();
        public List<GameRecord> history = new ArrayList<>();
    }

    private static final int MAX_HISTORY = 200;
    private static final int FORGET_BELOW = -1;
    private static LlmMemory instance;

    private final File file;
    private final File lockFile;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private Data data;
    /** Modification time of the file when it was last read or written by us. */
    private long loadedStamp = -1;

    private LlmMemory(File file) {
        this.file = file;
        this.lockFile = new File(file.getPath() + ".lock");
        load();
    }

    public static synchronized LlmMemory get() {
        if (instance == null) {
            instance = new LlmMemory(new File(LlmConfig.getDir(), "memory.json"));
        }
        return instance;
    }

    private void load() {
        data = null;
        loadedStamp = file.lastModified();
        if (file.exists()) {
            try (Reader r = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
                data = gson.fromJson(r, Data.class);
            } catch (Exception e) {
                System.err.println("[LLM] Could not read memory " + file + ": " + e.getMessage());
                // keep the broken file around rather than overwriting what the bot learned
                file.renameTo(new File(file.getPath() + ".broken-" + System.currentTimeMillis()));
            }
        }
        if (data == null) {
            data = new Data();
        }
    }

    private void write() {
        try {
            file.getParentFile().mkdirs();
            File tmp = new File(file.getPath() + ".tmp");
            try (Writer w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
                gson.toJson(data, w);
            }
            if (!tmp.renameTo(file)) {
                file.delete();
                tmp.renameTo(file);
            }
            loadedStamp = file.lastModified();
        } catch (Exception e) {
            System.err.println("[LLM] Could not save memory: " + e.getMessage());
        }
    }

    /** Picks up changes another process (e.g. a practice run next to Adventure) wrote in the meantime. */
    private void refresh() {
        if (file.lastModified() != loadedStamp) {
            load();
        }
    }

    private interface Change<T> {
        T apply();
    }

    /**
     * Applies a change as a transaction: lock the file against other processes, re-read it, change, write it back.
     * Without this, two processes sharing the memory would overwrite each other's lessons.
     */
    private synchronized <T> T change(Change<T> c) {
        file.getParentFile().mkdirs();
        try (RandomAccessFile raf = new RandomAccessFile(lockFile, "rw"); FileLock lock = raf.getChannel().lock()) {
            load();
            T result = c.apply();
            write();
            return result;
        } catch (IOException e) {
            System.err.println("[LLM] memory lock failed (" + e.getMessage() + "), writing without it");
            refresh();
            T result = c.apply();
            write();
            return result;
        }
    }

    public synchronized int getGamesPlayed() {
        refresh();
        return data.gamesPlayed;
    }

    public static String opponentKey(String name) {
        return name == null ? "unknown" : name.trim().toLowerCase(Locale.ROOT);
    }

    public synchronized OpponentRecord getOpponent(String name) {
        refresh();
        return data.opponents.get(opponentKey(name));
    }

    public synchronized List<Lesson> lessonsInScope(String scope) {
        refresh();
        return inScope(scope);
    }

    private List<Lesson> inScope(String scope) {
        return data.lessons.stream().filter(l -> l.scope.equalsIgnoreCase(scope))
                .sorted(Comparator.comparingInt((Lesson l) -> -l.score).thenComparing(l -> l.id))
                .collect(Collectors.toList());
    }

    private Lesson findLesson(String id) {
        for (Lesson l : data.lessons) {
            if (l.id.equalsIgnoreCase(id)) {
                return l;
            }
        }
        return null;
    }

    public Lesson addLesson(String scope, String text) {
        return change(() -> add(scope, text));
    }

    private Lesson add(String scope, String text) {
        if (text == null || text.trim().isEmpty()) {
            return null;
        }
        text = text.trim();
        // don't store the same lesson twice
        for (Lesson l : data.lessons) {
            if (l.scope.equalsIgnoreCase(scope) && l.text.equalsIgnoreCase(text)) {
                l.score++;
                l.lastConfirmedGame = data.gamesPlayed;
                return l;
            }
        }
        Lesson l = new Lesson();
        l.id = "L" + data.nextLessonId++;
        l.scope = scope;
        l.text = text.length() > 400 ? text.substring(0, 400) : text;
        l.createdGame = data.gamesPlayed;
        l.lastConfirmedGame = data.gamesPlayed;
        data.lessons.add(l);
        return l;
    }

    /** verdict: helpful / wrong / irrelevant */
    public void rateLesson(String id, String verdict) {
        change(() -> {
            rate(id, verdict);
            return null;
        });
    }

    private void rate(String id, String verdict) {
        Lesson l = findLesson(id);
        if (l == null || verdict == null) {
            return;
        }
        switch (verdict.toLowerCase(Locale.ROOT)) {
            case "helpful":
                l.score++;
                l.lastConfirmedGame = data.gamesPlayed;
                break;
            case "wrong":
                l.score -= 2;
                break;
            default:
                break;
        }
        if (l.score < FORGET_BELOW) {
            data.lessons.remove(l);
        }
    }

    /** Replaces all lessons of a scope, used by consolidation. */
    public void replaceScope(String scope, List<String> texts) {
        change(() -> {
            List<Lesson> old = inScope(scope);
            int bestScore = old.stream().mapToInt(l -> l.score).max().orElse(1);
            data.lessons.removeAll(old);
            for (String t : texts) {
                Lesson l = add(scope, t);
                if (l != null) {
                    // merged lessons keep some of the weight of what they were built from
                    l.score = Math.max(1, Math.min(bestScore, 3));
                }
            }
            return null;
        });
    }

    /** Records a finished game and returns its number. */
    public int recordGame(String context, String opponent, String opponentColors, boolean won, int turns,
                          Collection<String> opponentCardsSeen) {
        return change(() -> record(context, opponent, opponentColors, won, turns, opponentCardsSeen).game);
    }

    private GameRecord record(String context, String opponent, String opponentColors, boolean won, int turns,
                                              Collection<String> opponentCardsSeen) {
        data.gamesPlayed++;
        OpponentRecord rec = data.opponents.computeIfAbsent(opponentKey(opponent), k -> {
            OpponentRecord r = new OpponentRecord();
            r.name = opponent;
            return r;
        });
        if (won) {
            rec.wins++;
        } else {
            rec.losses++;
        }
        if (opponentColors != null && !opponentColors.isEmpty()) {
            rec.colors = opponentColors;
        }
        for (String c : opponentCardsSeen) {
            rec.cardsSeen.merge(c, 1, Integer::sum);
        }
        GameRecord g = new GameRecord();
        g.game = data.gamesPlayed;
        g.time = System.currentTimeMillis();
        g.context = context;
        g.opponent = opponent;
        g.won = won;
        g.turns = turns;
        data.history.add(g);
        while (data.history.size() > MAX_HISTORY) {
            data.history.remove(0);
        }
        return g;
    }

    /** Attaches the reflection summary to a game record. */
    public void setSummary(int game, String summary) {
        change(() -> {
            for (GameRecord g : data.history) {
                if (g.game == game) {
                    g.summary = summary;
                }
            }
            return null;
        });
    }

    public synchronized String winRateSummary(int lastN) {
        refresh();
        List<GameRecord> h = data.history;
        List<GameRecord> recent = h.subList(Math.max(0, h.size() - lastN), h.size());
        long wins = recent.stream().filter(g -> g.won).count();
        long allWins = h.stream().filter(g -> g.won).count();
        return String.format("last %d games: %d won; all time: %d/%d won (%d games played in total)",
                recent.size(), wins, allWins, h.size(), data.gamesPlayed);
    }

    // ---------------------------------------------------------------------------------------------------------
    // Rendering for prompts. Output must be deterministic for a given memory state so prompt caching works.

    private static void renderLessons(StringBuilder sb, List<Lesson> lessons, int max) {
        int n = 0;
        for (Lesson l : lessons) {
            if (n++ >= max) {
                break;
            }
            sb.append("- [").append(l.id).append("] ").append(l.text).append('\n');
        }
    }

    /** Notes for a duel. Goes into the (stable) system prompt. */
    public synchronized String renderForDuel(String opponentName, Collection<String> ourCardNames) {
        refresh();
        LlmConfig cfg = LlmConfig.get();
        StringBuilder sb = new StringBuilder();
        List<Lesson> general = lessonsInScope(SCOPE_GENERAL);
        if (!general.isEmpty()) {
            sb.append("General lessons you wrote for yourself after earlier games (the strategy guide above wins if they disagree):\n");
            renderLessons(sb, general, cfg.maxGeneralLessons);
        }
        List<Lesson> opp = lessonsInScope(SCOPE_OPPONENT + opponentKey(opponentName));
        OpponentRecord rec = getOpponent(opponentName);
        if (rec != null || !opp.isEmpty()) {
            sb.append("\nWhat you know about opponent \"").append(opponentName).append("\":\n");
            if (rec != null) {
                sb.append("- Your record against them: ").append(rec.wins).append(" wins, ").append(rec.losses).append(" losses.");
                if (!rec.colors.isEmpty()) {
                    sb.append(" Colors: ").append(rec.colors).append('.');
                }
                sb.append('\n');
                if (!rec.cardsSeen.isEmpty()) {
                    List<String> top = rec.cardsSeen.entrySet().stream()
                            .sorted((a, b) -> b.getValue() - a.getValue() != 0 ? b.getValue() - a.getValue() : a.getKey().compareTo(b.getKey()))
                            .limit(25).map(Map.Entry::getKey).collect(Collectors.toList());
                    sb.append("- Cards they have played before: ").append(String.join(", ", top)).append('\n');
                }
            }
            renderLessons(sb, opp, cfg.maxOpponentLessons);
        }
        List<String> cardNotes = new ArrayList<>();
        if (ourCardNames != null) {
            for (String card : ourCardNames.stream().distinct().sorted().collect(Collectors.toList())) {
                List<Lesson> notes = lessonsInScope(SCOPE_CARD + card.toLowerCase(Locale.ROOT));
                // best-scored first; extra notes are usually near-duplicates waiting to be merged
                for (Lesson l : notes.subList(0, Math.min(notes.size(), cfg.maxCardLessons))) {
                    cardNotes.add("- [" + l.id + "] " + card + ": " + l.text);
                }
            }
        }
        if (!cardNotes.isEmpty()) {
            sb.append("\nNotes about cards in your deck:\n");
            for (String s : cardNotes) {
                sb.append(s).append('\n');
            }
        }
        return sb.toString();
    }

    /** Notes for overworld decisions. */
    public synchronized String renderForWorld() {
        refresh();
        StringBuilder sb = new StringBuilder();
        List<Lesson> world = lessonsInScope(SCOPE_WORLD);
        if (!world.isEmpty()) {
            sb.append("Your lessons about exploring the world:\n");
            renderLessons(sb, world, 12);
        }
        if (!data.opponents.isEmpty()) {
            sb.append("\nYour record against enemies (wins-losses):\n");
            data.opponents.values().stream()
                    .sorted(Comparator.comparing(r -> r.name))
                    .forEach(r -> sb.append("- ").append(r.name).append(": ").append(r.wins).append('-').append(r.losses)
                            .append(r.colors.isEmpty() ? "" : " (" + r.colors + ")").append('\n'));
        }
        sb.append("\nOverall: ").append(winRateSummary(10)).append('\n');
        return sb.toString();
    }

}
