package forge.llm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import forge.card.ColorSet;
import forge.card.MagicColor;
import forge.game.card.Card;
import forge.game.card.CardView;
import forge.game.event.GameEventLandPlayed;
import forge.game.event.GameEventSpellAbilityCast;
import forge.game.event.GameEventTurnBegan;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

/**
 * Remembers what the opponent did over the game, so each prompt can say things a single snapshot can't: how their
 * hand size changed, which turns they skipped a land or a creature, whether they cast spells on our turn, and how
 * much mana they left open. Reports facts only; the primer explains how to read them.
 */
public class OpponentTracker {
    private static final class TurnRecord {
        final int turn;
        final boolean theirs;
        boolean landPlayed;
        final List<String> casts = new ArrayList<>();
        /** On our turns: their hand size and untapped lands as our turn began. */
        int handAtStart = -1;
        int openLands = -1;

        TurnRecord(int turn, boolean theirs) {
            this.turn = turn;
            this.theirs = theirs;
        }
    }

    private final Player me;
    private final Player opp;
    private final Map<Integer, TurnRecord> turns = new LinkedHashMap<>();
    private int lastCreatureTurn = -1;
    private String lastCreature = "";
    private final List<String> instantSpeedPlays = new ArrayList<>();

    public OpponentTracker(Player me, Player opp) {
        this.me = me;
        this.opp = opp;
    }

    private TurnRecord record(int turn) {
        return turns.computeIfAbsent(turn, t -> new TurnRecord(t, me.getGame().getPhaseHandler().isPlayerTurn(opp)));
    }

    private int currentTurn() {
        return me.getGame().getPhaseHandler().getTurn();
    }

    public synchronized void onTurnBegan(GameEventTurnBegan ev) {
        TurnRecord r = new TurnRecord(ev.turnNumber(), ev.turnOwner() != null && ev.turnOwner().getId() == opp.getId());
        turns.put(ev.turnNumber(), r);
        if (!r.theirs) {
            r.handAtStart = opp.getCardsIn(ZoneType.Hand).size();
            int open = 0;
            for (Card c : opp.getLandsInPlay()) {
                if (!c.isTapped()) {
                    open++;
                }
            }
            r.openLands = open;
        }
    }

    public synchronized void onLandPlayed(GameEventLandPlayed ev) {
        if (ev.player() != null && ev.player().getId() == opp.getId()) {
            record(currentTurn()).landPlayed = true;
        }
    }

    public synchronized void onCast(GameEventSpellAbilityCast ev) {
        if (ev.si() == null || ev.sa() == null || !ev.sa().isSpell() || ev.si().getActivatingPlayer() == null
                || ev.si().getActivatingPlayer().getId() != opp.getId()) {
            return;
        }
        CardView host = ev.sa().getHostCard();
        if (host == null) {
            return;
        }
        String name = host.getName();
        int turn = currentTurn();
        TurnRecord r = record(turn);
        boolean creature = host.getCurrentState() != null && host.getCurrentState().isCreature();
        r.casts.add(name + (creature ? " (creature)" : ""));
        if (creature) {
            lastCreatureTurn = turn;
            lastCreature = name;
        }
        if (!r.theirs) {
            instantSpeedPlays.add(name + " (turn " + turn + ")");
        }
    }

    /** The "Opponent read" section. */
    public synchronized String render() {
        int now = currentTurn();
        boolean theirTurnNow = me.getGame().getPhaseHandler().isPlayerTurn(opp);
        List<TurnRecord> theirs = new ArrayList<>();
        List<TurnRecord> ours = new ArrayList<>();
        for (TurnRecord r : turns.values()) {
            (r.theirs ? theirs : ours).add(r);
        }
        StringBuilder sb = new StringBuilder("== Opponent read (facts tracked this game) ==\n");

        sb.append("- Cards in hand: ").append(opp.getCardsIn(ZoneType.Hand).size());
        List<String> handTrend = new ArrayList<>();
        for (TurnRecord r : ours.subList(Math.max(0, ours.size() - 4), ours.size())) {
            if (r.handAtStart >= 0) {
                handTrend.add(String.valueOf(r.handAtStart));
            }
        }
        if (handTrend.size() > 1) {
            sb.append(" (at the start of your recent turns: ").append(String.join(" -> ", handTrend)).append(')');
        }
        sb.append('\n');

        List<String> missed = new ArrayList<>();
        for (TurnRecord r : theirs) {
            // a turn still in progress may get its land later
            if (!r.landPlayed && (r.turn != now || !theirTurnNow)) {
                missed.add("turn " + r.turn);
            }
        }
        sb.append("- Lands: ").append(opp.getLandsInPlay().size()).append(" in play; ")
                .append(missed.isEmpty() ? "no missed land drops" : "no land played on " + String.join(", ", missed)).append('\n');

        if (lastCreatureTurn < 0) {
            sb.append("- Creatures: they have not cast a creature spell yet");
        } else {
            int since = 0;
            for (TurnRecord r : theirs) {
                if (r.turn > lastCreatureTurn && (r.turn != now || !theirTurnNow)) {
                    since++;
                }
            }
            sb.append("- Last creature spell: ").append(lastCreature).append(" on turn ").append(lastCreatureTurn).append(" (")
                    .append(since == 0 ? "on their latest turn" : since + " of their turns ago").append(')');
        }
        sb.append('\n');

        int quiet = 0;
        for (int i = theirs.size() - 1; i >= 0; i--) {
            TurnRecord r = theirs.get(i);
            if (r.turn == now && theirTurnNow) {
                continue;
            }
            if (!r.casts.isEmpty()) {
                break;
            }
            quiet++;
        }
        if (quiet > 0) {
            sb.append("- They cast nothing on their last ").append(quiet).append(" turn(s)\n");
        }

        sb.append("- Spells cast on your turns (instant speed): ").append(instantSpeedPlays.isEmpty() ? "none so far" : String.join(", ", instantSpeedPlays)).append('\n');

        List<String> open = new ArrayList<>();
        for (TurnRecord r : ours.subList(Math.max(0, ours.size() - 3), ours.size())) {
            if (r.openLands >= 0) {
                open.add("turn " + r.turn + ": " + r.openLands);
            }
        }
        if (!open.isEmpty()) {
            sb.append("- Untapped lands they left for your turns: ").append(String.join(", ", open)).append('\n');
        }

        List<String> plays = new ArrayList<>();
        for (TurnRecord r : theirs.subList(Math.max(0, theirs.size() - 6), theirs.size())) {
            List<String> p = new ArrayList<>();
            p.add(r.landPlayed ? "land" : "no land");
            p.addAll(r.casts);
            plays.add("T" + r.turn + ": " + String.join(", ", p));
        }
        if (!plays.isEmpty()) {
            sb.append("- Their recent turns: ").append(String.join("; ", plays)).append('\n');
        }

        String colors = colorsSeen();
        if (!colors.isEmpty()) {
            sb.append("- Colors seen: ").append(colors).append('\n');
        }
        return sb.toString();
    }

    private String colorsSeen() {
        Set<String> names = new LinkedHashSet<>();
        int mask = 0;
        for (ZoneType z : new ZoneType[]{ZoneType.Battlefield, ZoneType.Graveyard, ZoneType.Exile, ZoneType.Stack}) {
            for (Card c : opp.getGame().getCardsIn(z)) {
                if (c.getOwner() == opp && !c.isLand() && !c.isFaceDown()) {
                    mask |= c.getColor().getColor();
                }
            }
        }
        for (MagicColor.Color c : ColorSet.fromMask(mask).getOrderedColors()) {
            names.add(c.getName());
        }
        return String.join(", ", names);
    }
}
