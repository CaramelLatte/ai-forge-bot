package forge.llm;

import java.util.Set;
import java.util.function.Consumer;

import forge.ai.AIOption;
import forge.ai.LobbyPlayerAi;
import forge.ai.PlayerControllerAi;
import forge.game.Game;
import forge.game.player.Player;
import forge.game.player.PlayerController;

/**
 * Lobby player whose in-game controller is driven by an LLM ({@link PlayerControllerLlm}).
 */
public class LobbyPlayerLlm extends LobbyPlayerAi {
    private String context;
    private String opponentKey;
    private Consumer<LlmReflector.GameReport> onGameFinished;

    public LobbyPlayerLlm(String name, Set<AIOption> options) {
        super(name, options);
        setAiProfile("Default");
    }

    /** One or two sentences that tell the model where this game happens, e.g. which adventure enemy it fights. */
    public void setContext(String context) {
        this.context = context;
    }

    public String getContext() {
        return context;
    }

    /** Name under which the opponent is remembered (an enemy type rather than a randomized display name). */
    public void setOpponentKey(String opponentKey) {
        this.opponentKey = opponentKey;
    }

    public String getOpponentKey() {
        return opponentKey;
    }

    public void setOnGameFinished(Consumer<LlmReflector.GameReport> onGameFinished) {
        this.onGameFinished = onGameFinished;
    }

    void notifyGameFinished(LlmReflector.GameReport report) {
        if (onGameFinished != null) {
            try {
                onGameFinished.accept(report);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public Player createIngamePlayer(Game game, final int id) {
        Player p = new Player(getName(), game, id);
        p.setFirstController(new PlayerControllerLlm(game, p, this));
        return p;
    }

    @Override
    public PlayerController createMindSlaveController(Player master, Player slave) {
        // controlling someone else's turn is rare; let the Forge AI handle it
        return new PlayerControllerAi(slave.getGame(), slave, this);
    }
}
