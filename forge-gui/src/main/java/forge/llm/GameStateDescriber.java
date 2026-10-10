package forge.llm;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.google.common.collect.Multiset;

import forge.game.Game;
import forge.game.GameEntity;
import forge.game.card.Card;
import forge.game.card.CardCollectionView;
import forge.game.card.CounterType;
import forge.game.combat.Combat;
import forge.game.phase.PhaseHandler;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.zone.ZoneType;

/**
 * Turns a game into compact text from one player's point of view. Only information that player can see is
 * included. Cards are referred to by Forge's card id ("#123") so answers can point at them unambiguously.
 *
 * Rules text is printed the first time a card name shows up in a description and omitted afterwards, which keeps
 * boards with many copies of the same card short.
 */
public class GameStateDescriber {
    private final Player me;
    private final Set<String> namesWithTextShown = new HashSet<>();

    public GameStateDescriber(Player me) {
        this.me = me;
    }

    /** Card names whose rules text the reader already knows (e.g. from the deck list in the system prompt). */
    public void markKnown(Iterable<String> names) {
        for (String n : names) {
            namesWithTextShown.add(n);
        }
    }

    public static String cleanText(String s) {
        if (s == null) {
            return "";
        }
        s = s.replaceAll("<[^>]+>", "").replace("\\n", "\n").replace("\r", "");
        s = s.replaceAll("\\([^()]*\\)", ""); // reminder text
        s = s.replaceAll("\n+", " / ").replaceAll("\\s+", " ").trim();
        if (s.startsWith("/ ")) {
            s = s.substring(2);
        }
        return s.length() > 350 ? s.substring(0, 350) + "..." : s;
    }

    public static String rulesText(Card c) {
        String t;
        try {
            t = c.getAbilityText(c.getCurrentState());
        } catch (Exception e) {
            t = c.getOracleText();
        }
        return cleanText(t);
    }

    public static String ref(Card c) {
        return "#" + c.getId();
    }

    /** One line describing a card. */
    public String card(Card c, boolean onBattlefield) {
        StringBuilder sb = new StringBuilder();
        sb.append(ref(c)).append(' ').append(c.getName());
        if (!c.isLand() && c.getManaCost() != null && !c.getManaCost().isNoCost()) {
            sb.append(' ').append(c.getManaCost().getSimpleString().isEmpty() ? "{0}" : c.getManaCost().toString());
        }
        sb.append(" [").append(c.getType().toString()).append(']');
        if (c.isCreature()) {
            sb.append(' ').append(c.getNetPower()).append('/').append(c.getNetToughness());
            if (onBattlefield && c.getDamage() > 0) {
                sb.append(" (").append(c.getDamage()).append(" damage marked)");
            }
        }
        if (c.isPlaneswalker()) {
            sb.append(" loyalty ").append(c.getCurrentLoyalty());
        }
        if (onBattlefield) {
            List<String> status = new ArrayList<>();
            if (c.isTapped()) {
                status.add("tapped");
            }
            if (c.isCreature() && c.hasSickness() && c.getController() == me) {
                status.add("summoning sick");
            }
            if (c.isToken()) {
                status.add("token");
            }
            Multiset<CounterType> counters = c.getCounters();
            if (counters != null) {
                for (Multiset.Entry<CounterType> e : counters.entrySet()) {
                    status.add(e.getCount() + " " + e.getElement().getName() + " counter" + (e.getCount() > 1 ? "s" : ""));
                }
            }
            if (c.getAttachedTo() != null) {
                status.add("attached to " + ref(c.getAttachedTo()) + " " + c.getAttachedTo().getName());
            }
            if (c.getController() != c.getOwner()) {
                status.add("owned by " + c.getOwner().getName());
            }
            if (!status.isEmpty()) {
                sb.append(" (").append(String.join(", ", status)).append(')');
            }
        }
        boolean basicLand = c.isBasicLand() && !c.isCreature();
        if (!basicLand) {
            // tokens and modified permanents have text that differs between copies, always show it
            boolean showText = c.isToken() || !namesWithTextShown.contains(c.getName()) || (onBattlefield && c.isCreature());
            String text = rulesText(c);
            if (showText && !text.isEmpty()) {
                sb.append(" — ").append(text);
            }
            namesWithTextShown.add(c.getName());
        }
        return sb.toString();
    }

    private void zone(StringBuilder sb, String title, CardCollectionView cards, boolean battlefield) {
        sb.append(title).append(cards.isEmpty() ? ": (none)\n" : ":\n");
        // lands last and compressed, they are the bulk of most boards
        List<Card> lands = new ArrayList<>();
        for (Card c : cards) {
            if (battlefield && c.isLand() && !c.isCreature()) {
                lands.add(c);
            } else {
                sb.append("  ").append(card(c, battlefield)).append('\n');
            }
        }
        if (!lands.isEmpty()) {
            sb.append("  Lands: ");
            List<String> parts = new ArrayList<>();
            for (Card l : lands) {
                String s = ref(l) + " " + l.getName() + (l.isTapped() ? " (tapped)" : "");
                if (!l.isBasicLand() && !namesWithTextShown.contains(l.getName())) {
                    s += " — " + rulesText(l);
                    namesWithTextShown.add(l.getName());
                }
                parts.add(s);
            }
            sb.append(String.join("; ", parts)).append('\n');
        }
    }

    private static String names(CardCollectionView cards, int max) {
        List<String> n = new ArrayList<>();
        int i = 0;
        for (Card c : cards) {
            if (i++ >= max) {
                n.add("... " + (cards.size() - max) + " more");
                break;
            }
            n.add(c.getName());
        }
        return String.join(", ", n);
    }

    public static String phaseName(Game game) {
        PhaseHandler ph = game.getPhaseHandler();
        return ph.getPhase() == null ? "start of game" : ph.getPhase().nameForUi;
    }

    public String header() {
        Game game = me.getGame();
        PhaseHandler ph = game.getPhaseHandler();
        Player active = ph.getPlayerTurn();
        return "Turn " + ph.getTurn() + ", " + phaseName(game) + " — " + (active == me ? "YOUR turn" : active.getName() + "'s turn");
    }

    /** Full board state. */
    public String describe() {
        Game game = me.getGame();
        StringBuilder sb = new StringBuilder();
        sb.append(header()).append('\n');
        sb.append("You: ").append(me.getLife()).append(" life, ").append(me.getCardsIn(ZoneType.Library).size()).append(" cards in library");
        if (me.getPoisonCounters() > 0) {
            sb.append(", ").append(me.getPoisonCounters()).append(" poison");
        }
        if (me.getNumManaShards() > 0) {
            sb.append(", ").append(me.getNumManaShards()).append(" mana shards");
        }
        sb.append(", lands played this turn: ").append(me.getLandsPlayedThisTurn()).append('\n');
        sb.append("Untapped mana sources: ").append(manaSources()).append('\n');
        for (Player opp : me.getOpponents()) {
            sb.append("Opponent ").append(opp.getName()).append(": ").append(opp.getLife()).append(" life, ")
                    .append(opp.getCardsIn(ZoneType.Hand).size()).append(" cards in hand, ")
                    .append(opp.getCardsIn(ZoneType.Library).size()).append(" in library");
            if (opp.getPoisonCounters() > 0) {
                sb.append(", ").append(opp.getPoisonCounters()).append(" poison");
            }
            sb.append('\n');
        }
        sb.append('\n');
        zone(sb, "Your battlefield", me.getCardsIn(ZoneType.Battlefield), true);
        for (Player opp : me.getOpponents()) {
            zone(sb, opp.getName() + "'s battlefield", opp.getCardsIn(ZoneType.Battlefield), true);
        }
        zone(sb, "Your hand", me.getCardsIn(ZoneType.Hand), false);
        if (!me.getCardsIn(ZoneType.Command).isEmpty()) {
            zone(sb, "Your command zone", me.getCardsIn(ZoneType.Command), false);
        }
        sb.append("Your graveyard: ").append(names(me.getCardsIn(ZoneType.Graveyard), 30)).append('\n');
        for (Player opp : me.getOpponents()) {
            sb.append(opp.getName()).append("'s graveyard: ").append(names(opp.getCardsIn(ZoneType.Graveyard), 30)).append('\n');
        }
        CardCollectionView exile = game.getCardsIn(ZoneType.Exile);
        if (!exile.isEmpty()) {
            List<String> visible = new ArrayList<>();
            for (Card c : exile) {
                if (!c.isFaceDown() || c.getOwner() == me) {
                    visible.add(c.getName() + " (" + c.getOwner().getName() + ")");
                }
            }
            if (!visible.isEmpty()) {
                sb.append("Exile: ").append(String.join(", ", visible)).append('\n');
            }
        }
        sb.append(stack());
        sb.append(combat());
        return sb.toString();
    }

    /** Untapped permanents that can make mana right now, with the colors each can make. */
    public String manaSources() {
        List<String> parts = new ArrayList<>();
        for (Card c : me.getCardsIn(ZoneType.Battlefield)) {
            if (c.isTapped() || c.getManaAbilities().isEmpty()) {
                continue;
            }
            if (c.isCreature() && c.hasSickness()) {
                continue;
            }
            Set<String> colors = new java.util.LinkedHashSet<>();
            for (SpellAbility ma : c.getManaAbilities()) {
                if (ma.getManaPart() == null) {
                    continue;
                }
                if (ma.getManaPart().isAnyMana()) {
                    colors.add("any color");
                    continue;
                }
                String produced = ma.getManaPart().isComboMana() ? ma.getManaPart().getComboColors(ma) : ma.getManaPart().getOrigProduced();
                for (String p : produced.split(" ")) {
                    if (!p.isEmpty() && !"Combo".equals(p)) {
                        colors.add(p);
                    }
                }
            }
            parts.add(ref(c) + " " + c.getName() + (colors.isEmpty() ? "" : " (" + String.join("/", colors) + ")"));
        }
        return parts.isEmpty() ? "none" : parts.size() + " — " + String.join(", ", parts);
    }

    public String stack() {
        Game game = me.getGame();
        if (game.getStack().isEmpty()) {
            return "Stack: empty\n";
        }
        StringBuilder sb = new StringBuilder("Stack (top first):\n");
        for (SpellAbilityStackInstance si : game.getStack()) {
            Player who = si.getActivatingPlayer();
            sb.append("  - ").append(who == me ? "YOUR " : who.getName() + "'s ")
                    .append(cleanText(si.getStackDescription()));
            SpellAbility sa = si.getSpellAbility();
            if (sa != null && sa.usesTargeting()) {
                List<String> t = new ArrayList<>();
                for (GameEntity e : sa.getTargets().getTargetEntities()) {
                    t.add(entity(e));
                }
                if (!t.isEmpty()) {
                    sb.append(" (targets: ").append(String.join(", ", t)).append(')');
                }
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    public String combat() {
        Combat combat = me.getGame().getCombat();
        if (combat == null || combat.getAttackers().isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("Combat:\n");
        for (Card att : combat.getAttackers()) {
            sb.append("  ").append(ref(att)).append(' ').append(att.getName()).append(' ').append(att.getNetPower()).append('/').append(att.getNetToughness())
                    .append(" (").append(att.getController() == me ? "yours" : att.getController().getName() + "'s").append(") attacks ")
                    .append(entity(combat.getDefenderByAttacker(att)));
            CardCollectionView blockers = combat.getBlockers(att);
            if (blockers != null && !blockers.isEmpty()) {
                List<String> b = new ArrayList<>();
                for (Card bl : blockers) {
                    b.add(ref(bl) + " " + bl.getName() + " " + bl.getNetPower() + "/" + bl.getNetToughness());
                }
                sb.append(", blocked by ").append(String.join(", ", b));
            } else if (combat.isBlocked(att)) {
                sb.append(", blocked");
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    public String entity(GameEntity e) {
        if (e == null) {
            return "?";
        }
        if (e instanceof Player) {
            return e == me ? "you" : "player " + ((Player) e).getName();
        }
        if (e instanceof Card) {
            Card c = (Card) e;
            return ref(c) + " " + c.getName() + (c.getController() == me ? " (yours)" : " (" + c.getController().getName() + "'s)");
        }
        return e.toString();
    }
}
