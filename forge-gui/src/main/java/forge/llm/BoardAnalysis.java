package forge.llm;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.combat.Combat;
import forge.game.combat.CombatUtil;
import forge.game.keyword.Keyword;
import forge.game.keyword.KeywordInterface;
import forge.game.phase.PhaseHandler;
import forge.game.phase.PhaseType;
import forge.game.player.Player;

/**
 * Arithmetic the model is bad at and the engine knows exactly: how much the opponent can hit for on their next
 * attack (all their creatures untap first), how much of that our blockers can stop, and how fast our own attackers
 * can win. Counts only the visible board; cards in hand are reported as unknowns.
 */
public final class BoardAnalysis {
    /** The rendered "Board math" section. */
    public final String text;
    /** Lethal is on the table for either side: worth thinking harder. */
    public final boolean critical;

    private BoardAnalysis(String text, boolean critical) {
        this.text = text;
        this.critical = critical;
    }

    public static BoardAnalysis analyze(Player me) {
        StringBuilder sb = new StringBuilder("== Board math (computed by the engine from the visible board) ==\n");
        boolean critical = false;
        for (Player opp : me.getOpponents()) {
            critical |= threat(sb, me, opp);
            critical |= clock(sb, me, opp);
        }
        sb.append("These numbers ignore cards in hand: treat the threat as a minimum and your damage as a maximum.\n");
        return new BoardAnalysis(sb.toString(), critical);
    }

    /** What the opponent's next attack can do to us. Returns true if it looks lethal. */
    private static boolean threat(StringBuilder sb, Player me, Player opp) {
        Game game = me.getGame();
        PhaseHandler ph = game.getPhaseHandler();
        // still to come this turn, or (once their combat has started, or on our turn) on their next turn
        boolean thisTurn = ph.isPlayerTurn(opp) && ph.getPhase() != null && ph.getPhase().isBefore(PhaseType.COMBAT_DECLARE_ATTACKERS);
        boolean afterTheirCombat = ph.isPlayerTurn(opp) && !thisTurn;
        if (afterTheirCombat) {
            return false; // the attack already happened; the combat section shows it
        }

        List<Card> attackers = new ArrayList<>();
        for (Card c : opp.getCreaturesInPlay()) {
            if (thisTurn ? CombatUtil.canAttack(c, me) : CombatUtil.canAttackNextTurn(c, me)) {
                if (damage(c) > 0) {
                    attackers.add(c);
                }
            }
        }
        // our creatures don't untap before their turn: only the untapped ones can block
        List<Card> blockers = new ArrayList<>();
        for (Card c : me.getCreaturesInPlay()) {
            if (CombatUtil.canBlock(c, false)) {
                blockers.add(c);
            }
        }
        String when = thisTurn ? "this turn" : "on their next turn (their creatures untap first)";
        int life = me.getLife();
        int through = attackers.isEmpty() ? 0 : throughBestBlocks(attackers, blockers, !thisTurn);
        if (attackers.isEmpty()) {
            sb.append("Opponent's attack ").append(when).append(": none of their creatures can attack.\n");
        } else {
            int total = 0;
            for (Card c : attackers) {
                total += damage(c);
            }
            sb.append("Opponent's possible attack ").append(when).append(": ").append(attackers.size()).append(" creature(s), ")
                    .append(total).append(" damage if unblocked (").append(list(attackers)).append("). You have ").append(life).append(" life.\n");
            sb.append("  Your creatures able to block then: ").append(blockers.isEmpty() ? "none" : list(blockers));
            if (ph.isPlayerTurn(me)) {
                sb.append(" (creatures you attack with this turn stay tapped and can't block, unless they have vigilance)");
            }
            sb.append(".\n  Even with your best blocks (each blocker stopping one attacker), about ").append(through)
                    .append(" damage gets through");
            if (through >= life) {
                sb.append(" — LETHAL: you lose unless you add blockers, remove attackers or gain life");
            } else if (through * 2 >= life || total >= life) {
                sb.append(" — DANGER: two such attacks, or one with a pump or burn spell, would kill you");
            }
            sb.append(".\n");
        }
        int lands = opp.getLandsInPlay().size();
        int hand = opp.getCardsIn(forge.game.zone.ZoneType.Hand).size();
        sb.append("  Unknowns: they hold ").append(hand).append(" card(s) and have ").append(lands).append(" land(s)")
                .append(hand > 0 && !thisTurn ? " (up to " + (lands + 1) + " mana next turn with a land drop)" : "").append(".\n");
        if (opp.getPoisonCounters() > 0 || me.getPoisonCounters() > 0) {
            sb.append("  Poison: you have ").append(me.getPoisonCounters()).append(", they have ").append(opp.getPoisonCounters()).append(" (10 kills).\n");
        }
        return !attackers.isEmpty() && through >= life;
    }

    /** How fast we can kill the opponent. Returns true if we have lethal now. */
    private static boolean clock(StringBuilder sb, Player me, Player opp) {
        Game game = me.getGame();
        PhaseHandler ph = game.getPhaseHandler();
        // before our combat: this turn; otherwise our next turn
        boolean thisTurn = ph.isPlayerTurn(me) && ph.getPhase() != null && ph.getPhase().isBefore(PhaseType.COMBAT_DECLARE_ATTACKERS);
        List<Card> attackers = new ArrayList<>();
        for (Card c : me.getCreaturesInPlay()) {
            if ((thisTurn ? CombatUtil.canAttack(c, opp) : CombatUtil.canAttackNextTurn(c, opp)) && damage(c) > 0) {
                attackers.add(c);
            }
        }
        if (attackers.isEmpty()) {
            sb.append("Your attack ").append(thisTurn ? "this turn" : "next turn").append(": none of your creatures can attack.\n");
            return false;
        }
        // on our turn their untapped creatures block; for our next turn assume all of them could
        List<Card> blockers = new ArrayList<>();
        for (Card c : opp.getCreaturesInPlay()) {
            if (CombatUtil.canBlock(c, !thisTurn)) {
                blockers.add(c);
            }
        }
        int total = 0;
        int unblockable = 0;
        for (Card a : attackers) {
            total += damage(a);
            if (legalBlockers(a, blockers, !thisTurn).size() < minBlockers(a)) {
                unblockable += damage(a);
            }
        }
        int through = throughBestBlocks(attackers, blockers, !thisTurn);
        int life = opp.getLife();
        sb.append("Your possible attack ").append(thisTurn ? "this turn" : "on your next turn").append(": ").append(attackers.size())
                .append(" creature(s), ").append(total).append(" damage if unblocked; ").append(unblockable)
                .append(" of it can't be blocked by their current creatures. Opponent has ").append(life).append(" life.\n");
        sb.append("  Their creatures able to block: ").append(blockers.isEmpty() ? "none" : list(blockers))
                .append(thisTurn ? "" : " (any they attack with will be tapped on your turn unless they have vigilance)").append(".\n");
        sb.append("  Even with their best blocks about ").append(through).append(" gets through");
        if (through >= life) {
            sb.append(" — YOU HAVE LETHAL").append(thisTurn ? " this turn if you attack with everything" : " next turn if nothing changes");
        } else if (through > 0) {
            int turns = (life + through - 1) / through;
            sb.append(": about ").append(turns).append(" attack(s) like this to win");
        }
        sb.append(".\n");
        return thisTurn && through >= life;
    }

    /**
     * What each possible block does, worked out by the engine (no pump spells or abilities): who dies and how much
     * damage reaches the player. Covers single blocks and, with few blockers, double blocks.
     */
    public static String blockOutcomes(Player me, List<Card> attackers, List<Card> blockers, Combat combat) {
        StringBuilder sb = new StringBuilder("Outcome of each possible block (engine math, before any pump spells or tricks):\n");
        for (Card a : attackers) {
            int dmg = damage(a);
            boolean trample = a.hasKeyword(Keyword.TRAMPLE);
            sb.append("  ").append(GameStateDescriber.ref(a)).append(' ').append(a.getName()).append(' ').append(a.getNetPower()).append('/')
                    .append(a.getNetToughness()).append(": unblocked it deals you ").append(dmg).append('.');
            if (!trample) {
                sb.append(" Without trample, ANY block stops all of its damage to you.");
            }
            sb.append('\n');
            List<Card> legal = legalBlockers(a, blockers, false);
            if (a.hasKeyword(Keyword.MENACE)) {
                sb.append("    (menace: it needs at least two blockers)\n");
            }
            for (Card b : legal) {
                boolean attackerDies = canKill(me, a, b, combat, true);
                boolean blockerDies = canKill(me, a, b, combat, false);
                sb.append("    - ").append(GameStateDescriber.ref(b)).append(' ').append(b.getName()).append(' ').append(b.getNetPower()).append('/')
                        .append(b.getNetToughness()).append(" blocks: ").append(blockerDies ? "your blocker dies" : "your blocker survives")
                        .append(", ").append(attackerDies ? "the attacker dies" : "the attacker survives")
                        .append(trample ? ", " + Math.max(0, dmg - Math.max(0, b.getNetToughness())) + " tramples over to you" : ", you take 0");
                if (blockerDies && !attackerDies) {
                    sb.append(" — a CHUMP block: you give up a creature to save ").append(dmg).append(" life");
                } else if (attackerDies && !blockerDies) {
                    sb.append(" — a free kill");
                } else if (attackerDies) {
                    sb.append(" — a trade");
                }
                sb.append('\n');
            }
            if (legal.size() >= 2 && legal.size() <= 4) {
                for (int i = 0; i < legal.size(); i++) {
                    for (int j = i + 1; j < legal.size(); j++) {
                        sb.append("    - ").append(doubleBlock(a, legal.get(i), legal.get(j))).append('\n');
                    }
                }
            }
        }
        return sb.toString();
    }

    private static boolean canKill(Player me, Card attacker, Card blocker, Combat combat, boolean attackerDies) {
        try {
            return attackerDies ? forge.ai.ComputerUtilCombat.canDestroyAttacker(me, attacker, blocker, combat, true)
                    : forge.ai.ComputerUtilCombat.canDestroyBlocker(me, blocker, attacker, combat, true);
        } catch (Exception e) {
            // rough fallback: plain damage against toughness
            Card src = attackerDies ? blocker : attacker;
            Card dst = attackerDies ? attacker : blocker;
            return damage(src) > 0 && (src.hasKeyword(Keyword.DEATHTOUCH) || damage(src) >= dst.getNetToughness() - dst.getDamage());
        }
    }

    /** Two blockers on one attacker: does it die, and how many of the blockers can its damage kill? */
    private static String doubleBlock(Card a, Card b1, Card b2) {
        int power = damage(b1) + damage(b2);
        boolean deathtouch = (b1.hasKeyword(Keyword.DEATHTOUCH) && damage(b1) > 0) || (b2.hasKeyword(Keyword.DEATHTOUCH) && damage(b2) > 0);
        boolean attackerDies = !a.hasKeyword(Keyword.INDESTRUCTIBLE) && (deathtouch || power >= a.getNetToughness() - a.getDamage());
        // the attacker splits its damage; it kills the blockers it can give lethal damage to, smallest first
        int left = damage(a);
        int killed = 0;
        List<Card> both = new ArrayList<>(List.of(b1, b2));
        both.sort(Comparator.comparingInt(c -> c.getNetToughness() - c.getDamage()));
        for (Card b : both) {
            int need = a.hasKeyword(Keyword.DEATHTOUCH) ? 1 : Math.max(1, b.getNetToughness() - b.getDamage());
            if (left >= need && !b.hasKeyword(Keyword.INDESTRUCTIBLE)) {
                left -= need;
                killed++;
            }
        }
        return GameStateDescriber.ref(b1) + " and " + GameStateDescriber.ref(b2) + " both block: together they deal " + power
                + (attackerDies ? " (the attacker dies)" : " (the attacker survives)") + "; its " + damage(a) + " damage can kill "
                + (killed == 0 ? "neither of them" : killed == 1 ? "only one of them" : "both of them")
                + (a.hasKeyword(Keyword.TRAMPLE) ? ", leftover damage tramples over" : ", you take 0");
    }

    /** Combat damage a creature deals if unblocked. */
    private static int damage(Card c) {
        int p = Math.max(0, c.getNetCombatDamage());
        return c.hasDoubleStrike() ? p * 2 : p;
    }

    private static int minBlockers(Card attacker) {
        return attacker.hasKeyword(Keyword.MENACE) ? 2 : 1;
    }

    private static List<Card> legalBlockers(Card attacker, List<Card> blockers, boolean nextTurn) {
        List<Card> legal = new ArrayList<>();
        for (Card b : blockers) {
            try {
                if (CombatUtil.canBlock(attacker, b, nextTurn)) {
                    legal.add(b);
                }
            } catch (Exception ignored) {
            }
        }
        return legal;
    }

    /**
     * Damage that still gets through when the defender assigns blockers to stop as much as possible: biggest
     * attackers first, each blocked by the smallest legal blocker(s) left. Trample still pushes excess through.
     */
    private static int throughBestBlocks(List<Card> attackers, List<Card> blockers, boolean nextTurn) {
        List<Card> sorted = new ArrayList<>(attackers);
        sorted.sort(Comparator.comparingInt(BoardAnalysis::damage).reversed());
        List<Card> free = new ArrayList<>(blockers);
        free.sort(Comparator.comparingInt(c -> c.getNetPower() + c.getNetToughness()));
        int through = 0;
        for (Card a : sorted) {
            List<Card> legal = legalBlockers(a, free, nextTurn);
            int need = minBlockers(a);
            if (legal.size() < need) {
                through += damage(a);
                continue;
            }
            int toughness = 0;
            for (int i = 0; i < need; i++) {
                Card b = legal.get(i);
                free.remove(b);
                toughness += Math.max(0, b.getNetToughness());
            }
            if (a.hasKeyword(Keyword.TRAMPLE)) {
                through += Math.max(0, damage(a) - toughness);
            }
        }
        return through;
    }

    private static String list(List<Card> cards) {
        List<String> parts = new ArrayList<>();
        for (Card c : cards) {
            String s = GameStateDescriber.ref(c) + " " + c.getName() + " " + c.getNetPower() + "/" + c.getNetToughness();
            List<String> kw = new ArrayList<>();
            for (Keyword k : new Keyword[]{Keyword.FLYING, Keyword.REACH, Keyword.MENACE, Keyword.TRAMPLE, Keyword.DEATHTOUCH,
                    Keyword.FIRST_STRIKE, Keyword.DOUBLE_STRIKE, Keyword.VIGILANCE, Keyword.LIFELINK}) {
                if (c.hasKeyword(k)) {
                    kw.add(k.toString().toLowerCase(java.util.Locale.ROOT));
                }
            }
            if (c.isTapped()) {
                kw.add("tapped now");
            }
            parts.add(kw.isEmpty() ? s : s + " (" + String.join(", ", kw) + ")");
        }
        return String.join(", ", parts);
    }

    // ---------------------------------------------------------------------------------------------------------
    // Keyword glossary

    /** Reminder text for every keyword on these cards that is not in {@code skip}, by keyword name. */
    public static Map<String, String> keywords(Iterable<Card> cards, Set<String> skip) {
        Map<String, String> result = new LinkedHashMap<>();
        for (Card c : cards) {
            List<KeywordInterface> kws;
            try {
                kws = c.getKeywords();
            } catch (Exception e) {
                continue;
            }
            for (KeywordInterface k : kws) {
                Keyword kw = k.getKeyword();
                if (kw == null || kw == Keyword.UNDEFINED) {
                    continue;
                }
                String name = kw.toString();
                if (skip.contains(name) || result.containsKey(name)) {
                    continue;
                }
                String text;
                try {
                    text = k.getReminderText();
                } catch (Exception e) {
                    continue;
                }
                if (text != null && !text.isBlank()) {
                    result.put(name, GameStateDescriber.cleanText(text));
                }
            }
        }
        return result;
    }

    public static String renderKeywords(Map<String, String> kws) {
        StringBuilder sb = new StringBuilder();
        kws.keySet().stream().sorted().forEach(k -> sb.append("- ").append(k).append(": ").append(kws.get(k)).append('\n'));
        return sb.toString();
    }
}
