package forge.llm;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.google.common.eventbus.Subscribe;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import forge.LobbyPlayer;
import forge.ai.AiPlayDecision;
import forge.ai.ComputerUtil;
import forge.ai.ComputerUtilAbility;
import forge.ai.ComputerUtilCost;
import forge.ai.PlayerControllerAi;
import forge.deck.CardPool;
import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.game.Game;
import forge.game.GameEntity;
import forge.game.GameLogEntry;
import forge.game.GameLogEntryType;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.combat.Combat;
import forge.game.combat.CombatUtil;
import forge.game.event.GameEventGameOutcome;
import forge.game.event.GameEventLandPlayed;
import forge.game.event.GameEventSpellAbilityCast;
import forge.game.event.GameEventTurnBegan;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.TargetRestrictions;
import forge.game.zone.ZoneType;
import forge.item.PaperCard;

/**
 * A player controller that asks an LLM for the decisions that matter: mulligans, what to play and when (including
 * targets), attacks and blocks. Everything else (paying mana, ordering triggers, choosing cards for effects...) is
 * left to the Forge AI it extends, which is also the fallback whenever the LLM is unreachable or answers nonsense.
 */
public class PlayerControllerLlm extends PlayerControllerAi {
    private final LobbyPlayerLlm lobby;
    private final LlmConfig cfg = LlmConfig.get();
    private final List<String> decisionLog = new ArrayList<>();
    private final Set<String> opponentCardsSeen = new HashSet<>();
    /** Rules text of the opponent's cards we have seen, by name. */
    private final Map<String, String> opponentCardText = new LinkedHashMap<>();
    private String deckReference = "";
    /** Keywords already explained in the system prompt; the opponent's other keywords are explained per request. */
    private final Set<String> knownKeywords = new HashSet<>();
    private final Map<String, String> opponentKeywords = new LinkedHashMap<>();
    private OpponentTracker tracker;

    private String systemPrompt;
    private List<String> deckCardNames = Collections.emptyList();
    private int logCursor = 0;

    /** Remaining steps of a multi-action plan, consumed on the next priority calls of the same step. */
    private final List<PlannedAction> plan = new ArrayList<>();
    private String planWindow = "";
    /** Window in which the LLM already decided to pass, so we don't ask again until something changes. */
    private String passedWindow = "";
    /** Abilities that failed to be played in the current window. */
    private final Set<String> failedInWindow = new HashSet<>();
    private String failedWindowKey = "";

    private int consecutiveFailures = 0;
    private long llmDownUntil = 0;
    private int llmCalls = 0;
    private boolean finished = false;

    private static final class PlannedAction {
        final String key;
        final List<String> targets;
        final int x;
        final String why;

        PlannedAction(String key, List<String> targets, int x, String why) {
            this.key = key;
            this.targets = targets;
            this.x = x;
            this.why = why;
        }
    }

    public PlayerControllerLlm(Game game, Player p, LobbyPlayerLlm lp) {
        super(game, p, lp);
        this.lobby = lp;
        game.subscribeToEvents(this);
    }

    public LobbyPlayerLlm getLobby() {
        return lobby;
    }

    public List<String> getDecisionLog() {
        return decisionLog;
    }

    // ---------------------------------------------------------------------------------------------------------
    // LLM plumbing

    private boolean llmAvailable() {
        return System.currentTimeMillis() >= llmDownUntil && !finished;
    }

    private JsonObject ask(String purpose, String user, JsonObject schema) {
        return ask(purpose, user, schema, false);
    }

    /**
     * Sends a prompt and returns the parsed JSON answer, or null (caller falls back to the Forge AI).
     * {@code critical} (lethal on the board for either side) uses the higher critical reasoning effort.
     */
    private JsonObject ask(String purpose, String user, JsonObject schema, boolean critical) {
        if (!llmAvailable()) {
            return null;
        }
        // a cloud model never gets the critical boost: it costs money and the effort switch also breaks its prompt cache
        String effort = critical && !cfg.useClaude() && cfg.criticalReasoningEffort != null && !cfg.criticalReasoningEffort.isEmpty()
                ? cfg.criticalReasoningEffort : cfg.reasoningEffort;
        try {
            LlmClient.Response r;
            try {
                r = LlmClient.get().chat(purpose, getSystemPrompt(), user, schema, effort, cfg.maxTokens);
            } catch (LlmClient.ContextOverflowException e) {
                // drop the event recap, the least important part, and try once more
                String shorter = user.replaceAll("(?s)== What happened since your last decision ==.*?\n\n", "");
                System.err.println("[LLM] " + purpose + ": prompt too long for the context window, retrying shorter. Consider a larger context (contextTokens).");
                r = LlmClient.get().chat(purpose, getSystemPrompt(), shorter, schema, effort, cfg.maxTokens);
            }
            llmCalls++;
            if (r.json == null) {
                System.err.println("[LLM] " + purpose + ": unparseable answer: " + r.content);
                consecutiveFailures++;
            } else {
                consecutiveFailures = 0;
            }
            return r.json;
        } catch (Exception e) {
            System.err.println("[LLM] " + purpose + " failed: " + e);
            consecutiveFailures++;
            if (consecutiveFailures >= 3) {
                // don't stall the game on a dead server; retry in a minute
                llmDownUntil = System.currentTimeMillis() + 60_000;
                consecutiveFailures = 0;
                System.err.println("[LLM] Server unavailable, Forge AI plays for the next minute.");
            }
            return null;
        }
    }

    private String getSystemPrompt() {
        if (systemPrompt == null) {
            systemPrompt = buildSystemPrompt();
        }
        return systemPrompt;
    }

    private Player opponent() {
        return player.getOpponents().isEmpty() ? null : player.getOpponents().get(0);
    }

    private String opponentName() {
        if (lobby.getOpponentKey() != null) {
            return lobby.getOpponentKey();
        }
        Player o = opponent();
        return o == null ? "unknown" : o.getName();
    }

    private String buildSystemPrompt() {
        StringBuilder sb = new StringBuilder();
        // game-independent text first, so this prefix is identical in every duel and stays in the server's prompt cache
        sb.append("You are an expert Magic: The Gathering player, playing inside the Forge rules engine.\n")
                .append("You make the important decisions: mulligans, which lands/spells/abilities to play and when, targets, attacks and blocks. ")
                .append("Mana payment and minor choices are handled for you automatically.\n")
                .append("Answer every request with one JSON object in the requested format and nothing else. ")
                .append("Keep \"reasoning\" to one or two short sentences.\n")
                .append("Cards are referred to by id, like #123. Use exactly the ids and option numbers you are given.\n")
                .append("Each request shows the current state, then \"Board math\" and \"Opponent read\" sections computed by the game engine, then the decision.\n\n");
        String primer = LlmPrimer.get();
        if (!primer.isEmpty()) {
            sb.append(primer).append("\n\n");
        }

        sb.append("# This game\n\nYou are playing as \"").append(player.getName()).append("\".\n");
        if (lobby.getContext() != null) {
            sb.append(lobby.getContext()).append('\n');
        }

        Deck deck = player.getRegisteredPlayer() == null ? null : player.getRegisteredPlayer().getDeck();
        if (deck != null) {
            CardPool main = deck.getMain();
            sb.append("\n== Your deck (").append(main.countAll()).append(" cards) ==\n");
            Map<String, Integer> counts = new LinkedHashMap<>();
            Map<String, PaperCard> byName = new HashMap<>();
            for (Map.Entry<PaperCard, Integer> e : main) {
                counts.merge(e.getKey().getName(), e.getValue(), Integer::sum);
                byName.putIfAbsent(e.getKey().getName(), e.getKey());
            }
            List<String> names = new ArrayList<>(counts.keySet());
            Collections.sort(names);
            deckCardNames = names;
            int deckStart = sb.length();
            for (String n : names) {
                PaperCard pc = byName.get(n);
                sb.append(counts.get(n)).append("x ").append(n);
                try {
                    if (!pc.getRules().getType().isBasicLand()) {
                        String cost = pc.getRules().getManaCost() == null ? "" : pc.getRules().getManaCost().toString();
                        if (!cost.isEmpty() && !"no cost".equalsIgnoreCase(cost)) {
                            sb.append(' ').append(cost);
                        }
                        sb.append(" [").append(pc.getRules().getType()).append(']');
                        if (pc.getRules().getPower() != null && !pc.getRules().getPower().isEmpty()) {
                            sb.append(' ').append(pc.getRules().getPower()).append('/').append(pc.getRules().getToughness());
                        }
                        String text = GameStateDescriber.cleanText(pc.getRules().getOracleText());
                        if (!text.isEmpty()) {
                            sb.append(" — ").append(text);
                        }
                    }
                } catch (Exception ignored) {
                }
                sb.append('\n');
            }
            deckReference = sb.substring(deckStart);
            if (deck.has(DeckSection.Commander)) {
                sb.append("Commander: ");
                for (Map.Entry<PaperCard, Integer> e : deck.get(DeckSection.Commander)) {
                    sb.append(e.getKey().getName()).append(' ');
                }
                sb.append('\n');
            }
        }
        Map<String, String> kws = BoardAnalysis.keywords(player.getAllCards(), Collections.emptySet());
        knownKeywords.addAll(kws.keySet());
        if (!kws.isEmpty()) {
            sb.append("\n== Keywords in this game (exact rules; keywords on the opponent's cards are added to each request as you see them) ==\n")
                    .append(BoardAnalysis.renderKeywords(kws));
        }
        String mem = LlmMemory.get().renderForDuel(opponentName(), deckCardNames);
        if (!mem.isEmpty()) {
            sb.append("\n== Your notes from previous games ==\n").append(mem)
                    .append("Apply these notes when they fit the situation; they are your own experience.\n");
        }
        return sb.toString();
    }

    private GameStateDescriber describer() {
        GameStateDescriber d = new GameStateDescriber(player);
        d.markKnown(deckCardNames);
        return d;
    }

    /** True when the last {@link #situation} found lethal on the board for either side. */
    private boolean critical;

    /**
     * The current state plus everything computed about it: the opponent's keywords we have not explained yet, the
     * board math and the opponent read. Sets {@link #critical}.
     */
    private String situation(GameStateDescriber d) {
        StringBuilder sb = new StringBuilder("== Current state ==\n").append(d.describe()).append('\n');
        trackOpponentCards();
        if (!opponentKeywords.isEmpty()) {
            sb.append("== Keywords on the opponent's cards ==\n").append(BoardAnalysis.renderKeywords(opponentKeywords)).append('\n');
        }
        BoardAnalysis board = BoardAnalysis.analyze(player);
        critical = board.critical;
        sb.append(board.text).append('\n');
        if (tracker != null) {
            sb.append(tracker.render()).append('\n');
        }
        return sb.toString();
    }

    private OpponentTracker tracker() {
        if (tracker == null && opponent() != null) {
            tracker = new OpponentTracker(player, opponent());
        }
        return tracker;
    }

    @Subscribe
    public void onTurnBegan(GameEventTurnBegan ev) {
        if (tracker() != null) {
            tracker.onTurnBegan(ev);
        }
    }

    @Subscribe
    public void onLandPlayed(GameEventLandPlayed ev) {
        if (tracker() != null) {
            tracker.onLandPlayed(ev);
        }
    }

    @Subscribe
    public void onSpellCast(GameEventSpellAbilityCast ev) {
        if (tracker() != null) {
            tracker.onCast(ev);
        }
    }

    /** Game log entries since the previous decision, oldest first. */
    private String recentEvents() {
        List<GameLogEntry> all = getGame().getGameLog().getAllEntries();
        if (logCursor > all.size()) {
            logCursor = 0;
        }
        List<String> lines = new ArrayList<>();
        for (int i = logCursor; i < all.size(); i++) {
            GameLogEntry e = all.get(i);
            if (e.type() == GameLogEntryType.MANA || e.type() == GameLogEntryType.PHASE) {
                continue;
            }
            lines.add(GameStateDescriber.cleanText(e.message()));
        }
        logCursor = all.size();
        trackOpponentCards();
        if (lines.isEmpty()) {
            return "";
        }
        if (lines.size() > 25) {
            lines = lines.subList(lines.size() - 25, lines.size());
        }
        return "== What happened since your last decision ==\n- " + String.join("\n- ", lines) + "\n\n";
    }

    private void trackOpponentCards() {
        List<Card> visible = new ArrayList<>();
        for (Player opp : player.getOpponents()) {
            for (ZoneType z : new ZoneType[]{ZoneType.Battlefield, ZoneType.Graveyard, ZoneType.Exile, ZoneType.Stack}) {
                for (Card c : getGame().getCardsIn(z)) {
                    if (c.isFaceDown() || (c.getController() != opp && c.getOwner() != opp)) {
                        continue;
                    }
                    visible.add(c);
                    if (!c.isToken() && c.getOwner() == opp && !c.isBasicLand() && z != ZoneType.Stack) {
                        opponentCardsSeen.add(c.getName());
                        opponentCardText.computeIfAbsent(c.getName(), n -> GameStateDescriber.rulesText(c));
                    }
                }
            }
        }
        Set<String> skip = new HashSet<>(knownKeywords);
        skip.addAll(opponentKeywords.keySet());
        opponentKeywords.putAll(BoardAnalysis.keywords(visible, skip));
    }

    private void log(String what, String reasoning) {
        String phase = GameStateDescriber.phaseName(getGame());
        String line = "T" + getGame().getPhaseHandler().getTurn() + " " + phase + ": " + what;
        if (reasoning != null && !reasoning.isEmpty()) {
            line += " — because: " + reasoning;
        }
        decisionLog.add(line);
        System.out.println("[LLM] " + line);
    }

    private static String reasoning(JsonObject o) {
        JsonElement e = o == null ? null : o.get("reasoning");
        return e == null || e.isJsonNull() ? "" : e.getAsString();
    }

    // ---------------------------------------------------------------------------------------------------------
    // JSON schemas

    private static JsonObject prop(String type) {
        JsonObject o = new JsonObject();
        o.addProperty("type", type);
        return o;
    }

    private static JsonObject object(Map<String, JsonObject> props) {
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

    private static JsonObject array(JsonObject items) {
        JsonObject o = prop("array");
        o.add("items", items);
        return o;
    }

    private static Map<String, JsonObject> props(Object... kv) {
        Map<String, JsonObject> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], (JsonObject) kv[i + 1]);
        }
        return m;
    }

    private static final JsonObject KEEP_SCHEMA = object(props("reasoning", prop("string"), "keep", prop("boolean")));
    private static final JsonObject PRIORITY_SCHEMA = object(props(
            "reasoning", prop("string"),
            "actions", array(object(props("option", prop("integer"), "targets", array(prop("string")), "x", prop("integer"))))));
    private static final JsonObject ATTACK_SCHEMA = object(props(
            "reasoning", prop("string"),
            "attacks", array(object(props("attacker", prop("string"), "defender", prop("string"))))));
    private static final JsonObject BLOCK_SCHEMA = object(props(
            "reasoning", prop("string"),
            "blocks", array(object(props("blocker", prop("string"), "attacker", prop("string"))))));
    private static final JsonObject TARGET_SCHEMA = object(props("reasoning", prop("string"), "targets", array(prop("string"))));

    // ---------------------------------------------------------------------------------------------------------
    // Mulligan

    @Override
    public boolean mulliganKeepHand(Player firstPlayer, int cardsToReturn) {
        boolean aiKeep = super.mulliganKeepHand(firstPlayer, cardsToReturn);
        StringBuilder sb = new StringBuilder();
        GameStateDescriber d = describer();
        sb.append("The game is starting. ").append(firstPlayer == player ? "You are on the play (you go first, no draw on turn 1)." : "You are on the draw.").append('\n');
        sb.append("Your opening hand:\n");
        for (Card c : player.getCardsIn(ZoneType.Hand)) {
            sb.append("  ").append(d.card(c, false)).append('\n');
        }
        // counted for the model, which miscounts lands in a listed hand
        int lands = 0;
        int tapped = 0;
        List<String> costs = new ArrayList<>();
        for (Card c : player.getCardsIn(ZoneType.Hand)) {
            if (c.isLand()) {
                lands++;
                String text = c.getOracleText() == null ? "" : c.getOracleText().toLowerCase(Locale.ROOT);
                if (text.contains("enters tapped") || text.contains("enters the battlefield tapped")) {
                    tapped++;
                }
            } else {
                costs.add(String.valueOf(c.getCMC()));
            }
        }
        sb.append("Counted for you: ").append(lands).append(" land(s)").append(tapped > 0 ? " (" + tapped + " of them enter tapped)" : "")
                .append(" and ").append(costs.size()).append(" spell(s) with mana values ").append(costs.isEmpty() ? "-" : String.join(", ", costs)).append(".\n");
        if (cardsToReturn > 0) {
            sb.append("If you keep you must put ").append(cardsToReturn).append(" card(s) from this hand on the bottom of your library.\n");
        }
        sb.append("Forge AI would ").append(aiKeep ? "keep" : "mulligan").append(".\n");
        sb.append("Rule of thumb: keep any 7-card hand with 2 to 5 lands; 3 lands is ideal, not low. Mulligan 0-1 or 6-7 lands. ")
                .append("Each mulligan costs a whole card, which is worse than a slow start, so keep smaller hands even more readily.\n");
        sb.append("Decide whether to keep this hand. Reply {\"reasoning\": \"...\", \"keep\": true|false}.");
        JsonObject r = ask("mulligan", sb.toString(), KEEP_SCHEMA);
        if (r == null || !r.has("keep")) {
            return aiKeep;
        }
        boolean keep = r.get("keep").getAsBoolean();
        if (!keep && cardsToReturn >= 3) {
            keep = true; // going below 4 cards is almost never right
        }
        log((keep ? "Kept" : "Mulliganed") + " a " + player.getCardsIn(ZoneType.Hand).size() + "-card hand", reasoning(r));
        return keep;
    }

    // ---------------------------------------------------------------------------------------------------------
    // Priority

    private String windowKey() {
        Game g = getGame();
        return g.getPhaseHandler().getTurn() + "|" + g.getPhaseHandler().getPhase() + "|" + g.getStack().size() + "|"
                + (g.getStack().isEmpty() ? "" : g.getStack().peekAbility().getId());
    }

    /** Coarse state fingerprint: if this did not change since we passed, we won't ask again. */
    private String passKey() {
        return windowKey() + "|" + player.getCardsIn(ZoneType.Hand).size() + "|" + getGame().getCardsIn(ZoneType.Battlefield).size()
                + "|" + player.getLife() + "|" + getGame().getCombat();
    }

    private static String actionKey(SpellAbility sa) {
        return sa.getHostCard().getId() + "|" + sa.getDescription() + "|" + sa.isSpell() + "|" + sa.isLandAbility();
    }

    /** Everything the player could legally start doing right now, except mana abilities. */
    private List<SpellAbility> candidates() {
        Game game = getGame();
        List<SpellAbility> raw = new ArrayList<>();
        for (Card c : ComputerUtilAbility.getAvailableCards(game, player)) {
            List<SpellAbility> abilities;
            try {
                abilities = c.getAllPossibleAbilities(player, true);
            } catch (Exception e) {
                continue;
            }
            for (SpellAbility sa : abilities) {
                if (sa.isManaAbility()) {
                    continue;
                }
                sa.setActivatingPlayer(player);
                raw.add(sa);
            }
        }
        List<SpellAbility> withAlts;
        try {
            withAlts = ComputerUtilAbility.getOriginalAndAltCostAbilities(raw, player);
        } catch (Exception e) {
            withAlts = raw;
        }
        List<SpellAbility> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (SpellAbility sa : withAlts) {
            try {
                if (sa.isLandAbility()) {
                    if (!player.canPlayLand(sa.getHostCard(), false, sa)) {
                        continue;
                    }
                } else if (!sa.canPlay() || !ComputerUtilCost.canPayCost(sa, player, false)) {
                    continue;
                } else if (sa.usesTargeting() && sa.getTargetRestrictions() != null
                        && sa.getTargetRestrictions().getAllCandidates(sa, false).size() < sa.getMinTargets()) {
                    continue; // nothing to target
                }
            } catch (Exception e) {
                continue;
            }
            if (failedInWindow.contains(actionKey(sa))) {
                continue;
            }
            // identical cards in hand give identical options
            Card host = sa.getHostCard();
            String dedupe = host.isInZone(ZoneType.Hand) ? host.getName() + "|" + sa.getDescription() + "|" + sa.isLandAbility() : actionKey(sa);
            if (seen.add(dedupe)) {
                result.add(sa);
            }
        }
        return result;
    }

    private String describeOption(SpellAbility sa, GameStateDescriber d) {
        Card host = sa.getHostCard();
        StringBuilder sb = new StringBuilder();
        if (sa.isLandAbility()) {
            sb.append("Play land ").append(GameStateDescriber.ref(host)).append(' ').append(host.getName()).append(legendWarning(sa));
            return sb.toString();
        }
        String zone = host.getZone() == null || host.isInZone(ZoneType.Hand) || host.isInZone(ZoneType.Battlefield) ? ""
                : " (from " + host.getZone().getZoneType().name().toLowerCase(Locale.ROOT) + ")";
        String cost = sa.getPayCosts() == null ? "" : sa.getPayCosts().toSimpleString();
        if (sa.isSpell()) {
            sb.append("Cast ").append(GameStateDescriber.ref(host)).append(' ').append(host.getName()).append(zone);
            if (!cost.isEmpty()) {
                sb.append(" paying ").append(cost);
            }
            // alternative costs, kicker etc. are separate abilities: say which variant this is
            if (sa != host.getFirstSpellAbility()) {
                String desc = GameStateDescriber.cleanText(sa.getDescription());
                if (!desc.isEmpty() && desc.length() < 160) {
                    sb.append(" (").append(desc).append(')');
                }
            }
        } else {
            sb.append("Activate ").append(GameStateDescriber.ref(host)).append(' ').append(host.getName()).append(zone).append(": ")
                    .append(GameStateDescriber.cleanText(sa.getDescription()));
        }
        sb.append(legendWarning(sa));
        if (sa.usesTargeting()) {
            sb.append(targetInfo(sa, d));
        }
        return sb.toString();
    }

    /** The legend rule, spelled out where it bites: playing a legend you already control loses one of the two. */
    private String legendWarning(SpellAbility sa) {
        Card host = sa.getHostCard();
        if (!(sa.isSpell() || sa.isLandAbility()) || !host.getType().isLegendary() || !host.isPermanent()) {
            return "";
        }
        for (Card c : player.getCardsIn(ZoneType.Battlefield)) {
            if (c != host && c.getName().equals(host.getName()) && c.getType().isLegendary()) {
                return " [LEGEND RULE: you already control " + GameStateDescriber.ref(c) + " " + c.getName()
                        + "; if this resolves you keep only one of the two and the other goes to your graveyard]";
            }
        }
        return "";
    }

    private String targetInfo(SpellAbility sa, GameStateDescriber d) {
        TargetRestrictions tr = sa.getTargetRestrictions();
        if (tr == null) {
            return "";
        }
        List<GameEntity> cands;
        try {
            cands = tr.getAllCandidates(sa, false);
        } catch (Exception e) {
            return "";
        }
        int min = sa.getMinTargets();
        int max = sa.getMaxTargets();
        StringBuilder sb = new StringBuilder(" — targets (choose ");
        sb.append(min == max ? String.valueOf(min) : min + " to " + max).append("): ");
        List<String> c = new ArrayList<>();
        for (GameEntity e : cands) {
            c.add(d.entity(e));
        }
        if (c.size() > 30) {
            c = c.subList(0, 30);
            c.add("...");
        }
        sb.append(c.isEmpty() ? "no legal targets" : String.join(", ", c));
        return sb.toString();
    }

    private String passMeaning() {
        Game game = getGame();
        if (!game.getStack().isEmpty()) {
            return "Pass priority (let the top of the stack resolve)";
        }
        if (game.getPhaseHandler().isPlayerTurn(player)) {
            PhaseType ph = game.getPhaseHandler().getPhase();
            if (ph == PhaseType.MAIN1) {
                return "Pass (go to combat; you will get another chance to play things in your second main phase)";
            }
            if (ph == PhaseType.MAIN2) {
                return "Pass (end your turn)";
            }
        }
        return "Pass priority / do nothing now";
    }

    private boolean shouldConsiderAsking(List<SpellAbility> cands) {
        Game game = getGame();
        if (cands.isEmpty()) {
            return false;
        }
        boolean myTurn = game.getPhaseHandler().isPlayerTurn(player);
        PhaseType phase = game.getPhaseHandler().getPhase();
        boolean stackEmpty = game.getStack().isEmpty();
        if (!stackEmpty) {
            // our own spell on top: let it resolve
            return game.getStack().peekAbility().getActivatingPlayer() != player
                    && (myTurn || cfg.respondOnOpponentTurn);
        }
        if (myTurn) {
            if (phase == PhaseType.MAIN1 || phase == PhaseType.MAIN2) {
                return true;
            }
            // combat tricks once blocks are known
            return phase == PhaseType.COMBAT_DECLARE_BLOCKERS;
        }
        if (!cfg.respondOnOpponentTurn) {
            return false;
        }
        // on the opponent's turn: after blocks (tricks) and at their end step (instant speed plays)
        return phase == PhaseType.COMBAT_DECLARE_BLOCKERS || phase == PhaseType.END_OF_TURN;
    }

    @Override
    public List<SpellAbility> chooseSpellAbilityToPlay() {
        try {
            return chooseWithLlm();
        } catch (Exception e) {
            System.err.println("[LLM] priority decision crashed, falling back to Forge AI: " + e);
            e.printStackTrace();
            plan.clear();
            return super.chooseSpellAbilityToPlay();
        }
    }

    private List<SpellAbility> chooseWithLlm() {
        if (!llmAvailable()) {
            return super.chooseSpellAbilityToPlay();
        }
        String window = windowKey();
        if (!window.equals(failedWindowKey)) {
            failedInWindow.clear();
            failedWindowKey = window;
        }
        List<SpellAbility> cands = candidates();
        if (!shouldConsiderAsking(cands)) {
            plan.clear();
            return null;
        }

        // continue a plan made earlier in this step
        String step = getGame().getPhaseHandler().getTurn() + "|" + getGame().getPhaseHandler().getPhase();
        if (!plan.isEmpty() && step.equals(planWindow) && getGame().getStack().isEmpty()) {
            PlannedAction next = plan.remove(0);
            for (SpellAbility sa : cands) {
                if (actionKey(sa).equals(next.key)) {
                    List<SpellAbility> ready = prepare(sa, next.targets, next.x, next.why);
                    if (ready != null) {
                        return ready;
                    }
                    break;
                }
            }
            plan.clear();
        } else if (!step.equals(planWindow)) {
            plan.clear();
        }

        String passKey = passKey();
        if (passKey.equals(passedWindow)) {
            return null;
        }

        // what would the Forge AI do? (also sets up its targets on the chosen ability)
        SpellAbility aiChoice = null;
        if (cfg.showAiHint) {
            try {
                List<SpellAbility> ai = super.chooseSpellAbilityToPlay();
                if (ai != null && !ai.isEmpty()) {
                    aiChoice = ai.get(0);
                }
            } catch (Exception ignored) {
            }
        }

        GameStateDescriber d = describer();
        StringBuilder sb = new StringBuilder();
        sb.append(recentEvents());
        sb.append(situation(d));
        sb.append("== Decision: you have priority ==\nOptions:\n  0. ").append(passMeaning()).append('\n');
        int aiIndex = -1;
        for (int i = 0; i < cands.size(); i++) {
            SpellAbility sa = cands.get(i);
            sb.append("  ").append(i + 1).append(". ").append(describeOption(sa, d)).append('\n');
            if (aiChoice != null && (sa == aiChoice || actionKey(sa).equals(actionKey(aiChoice)))) {
                aiIndex = i + 1;
            }
        }
        if (cfg.showAiHint) {
            sb.append("(Forge's built-in AI would choose option ").append(aiIndex < 0 ? 0 : aiIndex).append(". It is a decent but simple player.)\n");
        }
        sb.append("\nReply {\"reasoning\": \"...\", \"actions\": [{\"option\": N, \"targets\": [\"#id\" or \"player NAME\"], \"x\": -1}]}.\n")
                .append("List one or more actions to take in order (e.g. play a land, then cast a spell). An empty list passes priority. ")
                .append("Give targets only for options that need them. Set x only for spells with X in their cost, otherwise -1.");

        JsonObject r = ask("priority", sb.toString(), PRIORITY_SCHEMA, critical);
        if (r == null) {
            return aiChoice == null ? null : Collections.singletonList(aiChoice);
        }
        JsonArray actions = r.has("actions") && r.get("actions").isJsonArray() ? r.getAsJsonArray("actions") : new JsonArray();
        List<PlannedAction> steps = new ArrayList<>();
        for (JsonElement el : actions) {
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject a = el.getAsJsonObject();
            int opt = a.has("option") ? a.get("option").getAsInt() : 0;
            if (opt <= 0 || opt > cands.size()) {
                continue;
            }
            List<String> targets = new ArrayList<>();
            if (a.has("targets") && a.get("targets").isJsonArray()) {
                for (JsonElement t : a.getAsJsonArray("targets")) {
                    targets.add(t.getAsString());
                }
            }
            int x = a.has("x") && !a.get("x").isJsonNull() ? a.get("x").getAsInt() : -1;
            steps.add(new PlannedAction(actionKey(cands.get(opt - 1)), targets, x, reasoning(r)));
        }
        String why = reasoning(r);
        if (steps.isEmpty()) {
            passedWindow = passKey;
            if (getGame().getPhaseHandler().isPlayerTurn(player) && getGame().getStack().isEmpty()) {
                log("Passed", why);
            } else if (!getGame().getStack().isEmpty()) {
                log("Did not respond to the stack", why);
            }
            return null;
        }
        PlannedAction first = steps.remove(0);
        plan.clear();
        plan.addAll(steps);
        planWindow = step;
        for (SpellAbility sa : cands) {
            if (actionKey(sa).equals(first.key)) {
                List<SpellAbility> ready = prepare(sa, first.targets, first.x, why);
                if (ready != null) {
                    return ready;
                }
                break;
            }
        }
        // could not do what was asked; try again without it (failedInWindow prevents loops)
        plan.clear();
        return chooseWithLlm();
    }

    private GameEntity resolveEntity(String ref, List<GameEntity> candidates) {
        if (ref == null) {
            return null;
        }
        String s = ref.trim();
        String digits = s.replaceAll("^.*?#(\\d+).*$", "$1");
        if (!digits.equals(s) || s.matches("\\d+")) {
            int id = Integer.parseInt(s.matches("\\d+") ? s : digits);
            for (GameEntity e : candidates) {
                if (e instanceof Card && e.getId() == id) {
                    return e;
                }
            }
        }
        String low = s.toLowerCase(Locale.ROOT);
        for (GameEntity e : candidates) {
            if (e instanceof Player) {
                Player p = (Player) e;
                if ((p == player && (low.equals("you") || low.equals("me") || low.contains("myself")))
                        || low.contains(p.getName().toLowerCase(Locale.ROOT))
                        || (p != player && (low.equals("opponent") || low.equals("player opponent")))) {
                    return e;
                }
            }
        }
        for (GameEntity e : candidates) {
            if (e instanceof Card && low.contains(((Card) e).getName().toLowerCase(Locale.ROOT))) {
                return e;
            }
        }
        return null;
    }

    private boolean applyTargets(SpellAbility sa, List<String> refs) {
        TargetRestrictions tr = sa.getTargetRestrictions();
        if (tr == null || refs == null || refs.isEmpty()) {
            return false;
        }
        List<GameEntity> cands = tr.getAllCandidates(sa, false);
        sa.resetTargets();
        for (String ref : refs) {
            GameEntity e = resolveEntity(ref, cands);
            if (e != null && sa.canTarget(e) && !sa.getTargets().contains(e) && sa.getTargets().size() < sa.getMaxTargets()) {
                sa.getTargets().add(e);
            }
        }
        return sa.isTargetNumberValid();
    }

    /** Separate targeting question, used when the plan named no (valid) target. */
    private boolean askTargets(SpellAbility sa, String intent) {
        GameStateDescriber d = describer();
        StringBuilder sb = new StringBuilder();
        sb.append(situation(d));
        sb.append("== Decision: choose targets ==\nYou are playing: ").append(describeOption(sa, d)).append('\n');
        if (intent != null && !intent.isEmpty()) {
            sb.append("Your plan was: ").append(intent).append('\n');
        }
        sb.append("Reply {\"reasoning\": \"...\", \"targets\": [\"#id\" or \"player NAME\"]}.");
        JsonObject r = ask("targets", sb.toString(), TARGET_SCHEMA, critical);
        if (r == null || !r.has("targets")) {
            return false;
        }
        List<String> refs = new ArrayList<>();
        for (JsonElement t : r.getAsJsonArray("targets")) {
            refs.add(t.getAsString());
        }
        return applyTargets(sa, refs);
    }

    private String targetsText(SpellAbility sa) {
        if (!sa.usesTargeting() || sa.getTargets().isEmpty()) {
            return "";
        }
        GameStateDescriber d = new GameStateDescriber(player);
        List<String> t = new ArrayList<>();
        for (GameEntity e : sa.getTargets().getTargetEntities()) {
            t.add(d.entity(e));
        }
        return " targeting " + String.join(", ", t);
    }

    /**
     * Gets an ability ready to be played: targets, X. Returns null (and remembers the failure for this window)
     * when it can't be played as requested.
     */
    private List<SpellAbility> prepare(SpellAbility sa, List<String> targets, int x, String why) {
        sa.setActivatingPlayer(player);
        if (sa.isLandAbility()) {
            log("Played land " + sa.getHostCard().getName(), why);
            return Collections.singletonList(sa);
        }
        // let the Forge AI set up the ability first: it fills in X, sub-ability targets and other details
        AiPlayDecision aiOpinion;
        try {
            aiOpinion = getAi().canPlaySa(sa);
        } catch (Exception e) {
            aiOpinion = AiPlayDecision.CantPlaySa;
        }
        if (x >= 0 && sa.getPayCosts() != null && sa.getPayCosts().hasXInAnyCostPart()) {
            sa.setXManaCostPaid(x);
        }
        if (sa.usesTargeting()) {
            boolean ok = false;
            if (cfg.llmTargeting) {
                ok = applyTargets(sa, targets) || askTargets(sa, why);
            }
            if (!ok) {
                // fall back to the AI's own targeting
                sa.resetTargets();
                try {
                    getAi().canPlaySa(sa);
                } catch (Exception ignored) {
                }
                ok = sa.isTargetNumberValid();
            }
            if (!ok) {
                fail(sa, "no valid targets");
                return null;
            }
        }
        // sub-abilities with their own targets: rely on the AI's choice, or let it choose now
        SpellAbility sub = sa.getSubAbility();
        while (sub != null) {
            if (sub.usesTargeting() && !sub.isTargetNumberValid()) {
                try {
                    getAi().doTrigger(sub, true);
                } catch (Exception ignored) {
                }
                if (!sub.isTargetNumberValid()) {
                    fail(sa, "sub-ability targets");
                    return null;
                }
            }
            sub = sub.getSubAbility();
        }
        if (!ComputerUtilCost.canPayCost(sa, player, false)) {
            fail(sa, "can't pay the cost");
            return null;
        }
        String verb = sa.isSpell() ? "Cast " : "Activated ";
        log(verb + sa.getHostCard().getName() + targetsText(sa)
                + (aiOpinion != AiPlayDecision.WillPlay ? " (Forge AI disagreed: " + aiOpinion + ")" : ""), why);
        return Collections.singletonList(sa);
    }

    private void fail(SpellAbility sa, String reason) {
        failedInWindow.add(actionKey(sa));
        log("Could not play " + sa.getHostCard().getName() + " (" + reason + ")", null);
        sa.resetTargets();
    }

    @Override
    public boolean playChosenSpellAbility(SpellAbility sa) {
        if (sa.isLandAbility()) {
            if (sa.canPlay()) {
                sa.resolve();
            }
            return true;
        }
        // targets are already chosen, so no deferred targeting here
        boolean ok = ComputerUtil.handlePlayingSpellAbility(player, sa, null);
        if (!ok) {
            failedInWindow.add(actionKey(sa));
        }
        return true;
    }

    // ---------------------------------------------------------------------------------------------------------
    // Combat

    @Override
    public void declareAttackers(Player attacker, Combat combat) {
        // ask the Forge AI first so its plan can be shown as a hint, then clear it
        try {
            super.declareAttackers(attacker, combat);
        } catch (Exception e) {
            System.err.println("[LLM] AI attack suggestion failed: " + e);
        }
        Map<Card, GameEntity> aiAttacks = new LinkedHashMap<>();
        for (Card c : combat.getAttackers()) {
            aiAttacks.put(c, combat.getDefenderByAttacker(c));
        }
        if (!llmAvailable()) {
            return;
        }
        CardCollection possible = new CardCollection();
        for (Card c : player.getCreaturesInPlay()) {
            if (CombatUtil.canAttack(c)) {
                possible.add(c);
            }
        }
        if (possible.isEmpty()) {
            return;
        }
        List<GameEntity> defenders = new ArrayList<>(combat.getDefenders());

        GameStateDescriber d = describer();
        StringBuilder sb = new StringBuilder();
        sb.append(recentEvents());
        sb.append(situation(d));
        sb.append("== Decision: declare attackers ==\nCreatures that can attack:\n");
        for (Card c : possible) {
            sb.append("  ").append(GameStateDescriber.ref(c)).append(' ').append(c.getName()).append(' ')
                    .append(c.getNetPower()).append('/').append(c.getNetToughness()).append('\n');
        }
        sb.append("Possible defenders: ");
        List<String> defs = new ArrayList<>();
        for (GameEntity e : defenders) {
            defs.add(d.entity(e));
        }
        sb.append(String.join(", ", defs)).append('\n');
        sb.append("Opponent's untapped creatures that could block: ");
        List<String> blockers = new ArrayList<>();
        for (Player opp : player.getOpponents()) {
            for (Card c : opp.getCreaturesInPlay()) {
                if (!c.isTapped()) {
                    blockers.add(GameStateDescriber.ref(c) + " " + c.getName() + " " + c.getNetPower() + "/" + c.getNetToughness());
                }
            }
        }
        sb.append(blockers.isEmpty() ? "none" : String.join(", ", blockers)).append('\n');
        if (cfg.showAiHint) {
            List<String> hint = new ArrayList<>();
            for (Card c : aiAttacks.keySet()) {
                hint.add(GameStateDescriber.ref(c) + " " + c.getName());
            }
            sb.append("(Forge's built-in AI would attack with: ").append(hint.isEmpty() ? "nothing" : String.join(", ", hint)).append(")\n");
        }
        sb.append("\nReply {\"reasoning\": \"...\", \"attacks\": [{\"attacker\": \"#id\", \"defender\": \"\"}]}. ")
                .append("Leave defender empty to attack the opponent player; name a planeswalker/battle id to attack it instead. An empty list means no attack.");

        JsonObject r = ask("attack", sb.toString(), ATTACK_SCHEMA, critical);
        if (r == null || !r.has("attacks")) {
            return; // keep the AI's attack
        }
        for (Card c : new ArrayList<>(combat.getAttackers())) {
            combat.removeFromCombat(c);
        }
        Player defaultDefender = null;
        for (GameEntity e : defenders) {
            if (e instanceof Player) {
                defaultDefender = (Player) e;
                break;
            }
        }
        List<String> declared = new ArrayList<>();
        for (JsonElement el : r.getAsJsonArray("attacks")) {
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject a = el.getAsJsonObject();
            GameEntity att = resolveEntity(a.has("attacker") ? a.get("attacker").getAsString() : null, new ArrayList<>(possible));
            if (!(att instanceof Card) || combat.isAttacking((Card) att)) {
                continue;
            }
            String defRef = a.has("defender") ? a.get("defender").getAsString() : "";
            GameEntity def = defRef.isEmpty() ? defaultDefender : resolveEntity(defRef, defenders);
            if (def == null) {
                def = defaultDefender;
            }
            if (def != null && CombatUtil.canAttack((Card) att, def)) {
                combat.addAttacker((Card) att, def);
                declared.add(((Card) att).getName() + (def instanceof Player ? "" : " -> " + d.entity(def)));
            }
        }
        if (!CombatUtil.validateAttackers(combat)) {
            // e.g. a creature that must attack was left out: use the AI's declaration
            for (Card c : new ArrayList<>(combat.getAttackers())) {
                combat.removeFromCombat(c);
            }
            for (Map.Entry<Card, GameEntity> e : aiAttacks.entrySet()) {
                combat.addAttacker(e.getKey(), e.getValue());
            }
            log("Attack declaration was illegal, used Forge AI's attack instead", reasoning(r));
            return;
        }
        log(declared.isEmpty() ? "Did not attack" : "Attacked with " + String.join(", ", declared), reasoning(r));
    }

    @Override
    public void declareBlockers(Player defender, Combat combat) {
        // work out who can block before the AI's suggestion occupies the blockers
        CardCollection attackers = new CardCollection();
        for (Card a : combat.getAttackers()) {
            if (combat.getDefenderPlayerByAttacker(a) == defender || combat.getDefenderByAttacker(a) instanceof Card
                    && ((Card) combat.getDefenderByAttacker(a)).getController() == defender) {
                attackers.add(a);
            }
        }
        CardCollection possible = new CardCollection();
        for (Card c : defender.getCreaturesInPlay()) {
            if (CombatUtil.canBlock(c, combat)) {
                possible.add(c);
            }
        }

        String outcomes = "";
        if (!attackers.isEmpty() && !possible.isEmpty()) {
            try {
                outcomes = BoardAnalysis.blockOutcomes(defender, attackers, possible, combat);
            } catch (Exception e) {
                System.err.println("[LLM] block outcome math failed: " + e);
            }
        }

        CardCollection before = new CardCollection(combat.getAllBlockers());
        try {
            super.declareBlockers(defender, combat);
        } catch (Exception e) {
            System.err.println("[LLM] AI block suggestion failed: " + e);
        }
        Map<Card, List<Card>> aiBlocks = new LinkedHashMap<>();
        for (Card b : combat.getAllBlockers()) {
            if (!before.contains(b)) {
                aiBlocks.put(b, new ArrayList<>(combat.getAttackersBlockedBy(b)));
            }
        }
        if (!llmAvailable() || attackers.isEmpty()) {
            return;
        }
        if (possible.isEmpty()) {
            List<String> names = new ArrayList<>();
            for (Card a : attackers) {
                names.add(a.getName());
            }
            log("Could not block " + String.join(", ", names) + ": none of your creatures can legally block", null);
            return;
        }

        GameStateDescriber d = describer();
        StringBuilder sb = new StringBuilder();
        sb.append(recentEvents());
        sb.append(situation(d));
        sb.append("== Decision: declare blockers ==\nAttacking creatures:\n");
        for (Card a : attackers) {
            sb.append("  ").append(GameStateDescriber.ref(a)).append(' ').append(a.getName()).append(' ')
                    .append(a.getNetPower()).append('/').append(a.getNetToughness()).append(" attacking ")
                    .append(d.entity(combat.getDefenderByAttacker(a))).append('\n');
        }
        sb.append("Your creatures that can block:\n");
        for (Card c : possible) {
            sb.append("  ").append(GameStateDescriber.ref(c)).append(' ').append(c.getName()).append(' ')
                    .append(c.getNetPower()).append('/').append(c.getNetToughness()).append('\n');
        }
        int unblocked = 0;
        for (Card a : attackers) {
            unblocked += Math.max(0, a.getNetCombatDamage()) * (a.hasDoubleStrike() ? 2 : 1);
        }
        sb.append("If you block nothing you take ").append(unblocked).append(" damage (you have ").append(defender.getLife()).append(" life")
                .append(unblocked >= defender.getLife() ? " — that is LETHAL, you must block" : "").append(").\n");
        sb.append(outcomes);
        if (!outcomes.isEmpty()) {
            sb.append("Chump blocks are only worth a card when they prevent lethal or near-lethal damage. Free kills and good trades are usually worth taking.\n");
        }
        if (unblocked >= defender.getLife()) {
            critical = true;
        }
        if (cfg.showAiHint) {
            List<String> hint = new ArrayList<>();
            for (Map.Entry<Card, List<Card>> e : aiBlocks.entrySet()) {
                for (Card a : e.getValue()) {
                    hint.add(GameStateDescriber.ref(e.getKey()) + " blocks " + GameStateDescriber.ref(a));
                }
            }
            sb.append("(Forge's built-in AI would block: ").append(hint.isEmpty() ? "no blocks" : String.join(", ", hint)).append(")\n");
        }
        sb.append("\nYou are at ").append(defender.getLife()).append(" life. Reply {\"reasoning\": \"...\", \"blocks\": [{\"blocker\": \"#id\", \"attacker\": \"#id\"}]}. ")
                .append("Several blockers may block the same attacker. An empty list means no blocks.");

        JsonObject r = ask("block", sb.toString(), BLOCK_SCHEMA, critical);
        if (r == null || !r.has("blocks")) {
            return; // keep the AI's blocks
        }
        for (Card b : aiBlocks.keySet()) {
            combat.undoBlockingAssignment(b);
        }
        List<String> declared = new ArrayList<>();
        Set<Card> used = new HashSet<>();
        for (JsonElement el : r.getAsJsonArray("blocks")) {
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject b = el.getAsJsonObject();
            GameEntity bl = resolveEntity(b.has("blocker") ? b.get("blocker").getAsString() : null, new ArrayList<>(possible));
            GameEntity at = resolveEntity(b.has("attacker") ? b.get("attacker").getAsString() : null, new ArrayList<>(attackers));
            if (!(bl instanceof Card) || !(at instanceof Card)) {
                continue;
            }
            Card blocker = (Card) bl;
            Card att = (Card) at;
            if (used.contains(blocker) && !CombatUtil.canBlockMoreCreatures(blocker, combat.getAttackersBlockedBy(blocker))) {
                continue;
            }
            if (CombatUtil.canBlock(att, blocker, combat)) {
                combat.addBlocker(att, blocker);
                used.add(blocker);
                declared.add(blocker.getName() + " blocks " + att.getName());
            }
        }
        String invalid = CombatUtil.validateBlocks(combat, defender);
        if (invalid != null) {
            for (Card b : new ArrayList<>(used)) {
                combat.undoBlockingAssignment(b);
            }
            for (Map.Entry<Card, List<Card>> e : aiBlocks.entrySet()) {
                for (Card a : e.getValue()) {
                    combat.addBlocker(a, e.getKey());
                }
            }
            log("Block declaration was illegal (" + invalid + "), used Forge AI's blocks instead", reasoning(r));
            return;
        }
        log(declared.isEmpty() ? "Did not block" : String.join("; ", declared), reasoning(r));
    }

    // ---------------------------------------------------------------------------------------------------------
    // End of game

    /** e.g. "Red, Green" */
    public static String deckColors(Deck deck) {
        int mask = 0;
        for (Map.Entry<PaperCard, Integer> e : deck.getMain()) {
            if (!e.getKey().getRules().getType().isLand()) {
                mask |= e.getKey().getRules().getColor().getColor();
            }
        }
        List<String> names = new ArrayList<>();
        for (forge.card.MagicColor.Color c : forge.card.ColorSet.fromMask(mask).getOrderedColors()) {
            names.add(c.getName());
        }
        return names.isEmpty() ? "colorless" : String.join(", ", names);
    }

    @Subscribe
    public void onGameOutcome(GameEventGameOutcome ev) {
        if (finished) {
            return;
        }
        finished = true;
        trackOpponentCards();
        Game game = getGame();
        boolean won = player.getOutcome() != null && player.getOutcome().hasWon();
        LobbyPlayer winner = game.getOutcome() == null ? null : game.getOutcome().getWinningLobbyPlayer();
        if (winner != null) {
            won = winner == lobby;
        }
        Player opp = opponent();
        String colors = "";
        if (opp != null && opp.getRegisteredPlayer() != null && opp.getRegisteredPlayer().getDeck() != null) {
            try {
                colors = deckColors(opp.getRegisteredPlayer().getDeck());
            } catch (Exception ignored) {
            }
        }
        LlmReflector.GameReport report = new LlmReflector.GameReport();
        report.context = lobby.getContext();
        report.opponent = opponentName();
        report.opponentColors = colors;
        report.won = won;
        report.turns = game.getPhaseHandler().getTurn();
        report.endReason = game.getOutcome() == null ? "" : String.valueOf(game.getOutcome().getWinCondition());
        report.finalLife = player.getLife();
        report.startingLife = player.getStartingLife();
        report.opponentStartingLife = opp == null ? 0 : opp.getStartingLife();
        report.opponentFinalLife = opp == null ? 0 : opp.getLife();
        report.decisions = new ArrayList<>(decisionLog);
        report.opponentCardsSeen = new ArrayList<>(opponentCardsSeen);
        report.deckCardNames = new ArrayList<>(deckCardNames);
        StringBuilder ref = new StringBuilder("Your deck:\n").append(deckReference);
        if (!opponentCardText.isEmpty()) {
            ref.append("Opponent's cards you saw:\n");
            for (Map.Entry<String, String> e : opponentCardText.entrySet()) {
                ref.append(e.getKey()).append(" — ").append(e.getValue()).append('\n');
            }
        }
        report.cardReference = ref.toString();
        report.llmCalls = llmCalls;
        List<String> log = new ArrayList<>();
        for (GameLogEntry e : game.getGameLog().getAllEntries()) {
            if (e.type() != GameLogEntryType.MANA && e.type() != GameLogEntryType.PHASE) {
                log.add(GameStateDescriber.cleanText(e.message()));
            }
        }
        report.gameLog = log;
        System.out.println("[LLM] Game over: " + (won ? "WON" : "LOST") + " vs " + report.opponent + " on turn " + report.turns
                + " (" + llmCalls + " LLM calls, " + LlmClient.get().getStats() + ")");
        LlmReflector.get().submit(report);
        lobby.notifyGameFinished(report);
    }
}
