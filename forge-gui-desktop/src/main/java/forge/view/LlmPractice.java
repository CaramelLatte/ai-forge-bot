package forge.view;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import forge.deck.Deck;
import forge.deck.io.DeckSerializer;
import forge.game.Game;
import forge.game.GameEndReason;
import forge.game.GameRules;
import forge.game.GameType;
import forge.game.Match;
import forge.game.player.RegisteredPlayer;
import forge.llm.LlmClient;
import forge.llm.LlmConfig;
import forge.llm.LlmMemory;
import forge.llm.LlmReflector;
import forge.llm.LobbyPlayerLlm;
import forge.localinstance.properties.ForgeConstants;
import forge.model.FModel;
import forge.player.GamePlayerUtil;
import forge.util.Aggregates;

/**
 * Headless practice games: the LLM player against the Forge AI, no window needed. By default the LLM gets a random
 * Adventure starter deck and faces a random Adventure enemy deck, so what it learns carries over to Adventure mode.
 * Every game goes through the same reflection/memory loop as in Adventure.
 *
 * <pre>forge llm-practice [-n games] [-deck my.dck] [-opp enemy.dck ...] [-life 20] [-oppLife 20] [--claude]</pre>
 */
public class LlmPractice {
    public static void run(String[] args) {
        FModel.initialize(null, null);

        int games = 1;
        String myDeckPath = null;
        List<String> oppDeckPaths = new ArrayList<>();
        int life = 20;
        int oppLife = 20;
        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "-n" -> games = Integer.parseInt(args[++i]);
                case "-deck" -> myDeckPath = args[++i];
                case "-opp" -> {
                    while (i + 1 < args.length && !args[i + 1].startsWith("-")) {
                        oppDeckPaths.add(args[++i]);
                    }
                }
                case "-life" -> life = Integer.parseInt(args[++i]);
                case "-oppLife" -> oppLife = Integer.parseInt(args[++i]);
                case "--claude" -> LlmConfig.handleLaunchArg(args[i]);
                default -> {
                    System.out.println("Unknown argument " + args[i]);
                    System.out.println("Usage: llm-practice [-n games] [-deck my.dck] [-opp enemy.dck ...] [-life 20] [-oppLife 20] [--claude]");
                    return;
                }
            }
        }

        LlmConfig cfg = LlmConfig.get();
        System.out.println("LLM practice: " + games + " game(s) with model " + cfg.activeModel() + (cfg.useClaude() ? "" : " at " + cfg.baseUrl));
        System.out.println("Memory: " + LlmConfig.getDir() + "memory.json (" + LlmMemory.get().getGamesPlayed() + " games so far)");

        LlmReflector.get().resumePending();
        LlmReflector.get().awaitIdle(1800);

        File starterDir = new File(ForgeConstants.ADVENTURE_COMMON_DIR, "decks" + File.separator + "starter");
        File enemyDir = new File(ForgeConstants.ADVENTURE_COMMON_DIR, "decks" + File.separator + "standard");
        int wins = 0;
        for (int g = 0; g < games; g++) {
            File myFile = myDeckPath != null ? new File(myDeckPath) : randomDeck(starterDir, "Adventure - Low");
            File oppFile = !oppDeckPaths.isEmpty() ? new File(Aggregates.random(oppDeckPaths)) : randomDeck(enemyDir, null);
            Deck myDeck = myFile == null ? null : DeckSerializer.fromFile(myFile);
            Deck oppDeck = oppFile == null ? null : DeckSerializer.fromFile(oppFile);
            if (myDeck == null || oppDeck == null) {
                System.out.println("Could not load decks: " + myFile + " / " + oppFile);
                return;
            }
            String oppName = enemyName(oppFile);

            LobbyPlayerLlm llm = new LobbyPlayerLlm("LLM", null);
            llm.setOpponentKey(oppName);
            llm.setContext("This is a practice game against the Forge AI, which is playing the deck of the adventure enemy \""
                    + oppName + "\". You are playing the deck \"" + myDeck.getName() + "\".");
            RegisteredPlayer me = new RegisteredPlayer(myDeck);
            me.setPlayer(llm);
            me.setStartingLife(life);
            RegisteredPlayer opp = new RegisteredPlayer(oppDeck);
            opp.setPlayer(GamePlayerUtil.createAiPlayer(oppName, 0));
            opp.setStartingLife(oppLife);

            GameRules rules = new GameRules(GameType.Constructed);
            rules.setGamesPerMatch(1);
            rules.setManaBurn(false);
            rules.setWarnAboutAICards(false);
            Match match = new Match(rules, Arrays.asList(me, opp), "LLM practice");

            System.out.println("\n=== Game " + (g + 1) + "/" + games + ": " + myDeck.getName() + " vs " + oppName + " (" + oppFile.getName() + ") ===");
            Game game = match.createGame();
            game.setNoGUIUser();
            long start = System.currentTimeMillis();
            try {
                TimeLimitedCodeBlock.runWithTimeout(() -> match.startGame(game), 3, TimeUnit.HOURS);
            } catch (TimeoutException e) {
                System.out.println("Game took too long, calling it a draw");
                game.setGameOver(GameEndReason.Draw);
            } catch (Exception | StackOverflowError e) {
                e.printStackTrace();
                game.setGameOver(GameEndReason.Draw);
            }
            boolean won = game.getOutcome() != null && !game.getOutcome().isDraw() && game.getOutcome().getWinningLobbyPlayer() == llm;
            if (won) {
                wins++;
            }
            System.out.printf("Result: %s in %d turns, %.1f minutes. Running score %d/%d. LLM: %s%n",
                    won ? "WON" : "LOST", game.getPhaseHandler().getTurn(), (System.currentTimeMillis() - start) / 60000.0,
                    wins, g + 1, LlmClient.get().getStats());
            // let the reflection finish so its lessons apply to the next game
            System.out.println("Reflecting...");
            LlmReflector.get().awaitIdle(600);
            System.out.println(LlmReflector.get().getLastSummary());
        }
        System.out.println("\nDone. " + wins + "/" + games + " won. " + LlmMemory.get().winRateSummary(20));
    }

    private static File randomDeck(File dir, String prefix) {
        File[] files = dir.listFiles((d, name) -> name.toLowerCase(Locale.ROOT).endsWith(".dck") && (prefix == null || name.startsWith(prefix)));
        return files == null || files.length == 0 ? null : Aggregates.random(Arrays.asList(files));
    }

    /** "black_knight.dck" -> "Black Knight" */
    private static String enemyName(File deckFile) {
        String n = deckFile.getName().replaceAll("\\.dck$", "").replace('_', ' ');
        StringBuilder sb = new StringBuilder();
        for (String w : n.split(" ")) {
            if (!w.isEmpty()) {
                sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1)).append(' ');
            }
        }
        return sb.toString().trim();
    }
}
