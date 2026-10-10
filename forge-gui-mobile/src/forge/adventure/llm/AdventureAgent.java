package forge.adventure.llm;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.EventListener;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.ui.Cell;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;
import com.github.tommyettinger.textra.TextraLabel;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import forge.Forge;
import forge.adventure.character.EnemySprite;
import forge.adventure.data.EnemyData;
import forge.adventure.player.AdventurePlayer;
import forge.adventure.pointofintrest.PointOfInterest;
import forge.adventure.scene.DuelScene;
import forge.adventure.scene.GameScene;
import forge.adventure.scene.NewGameScene;
import forge.adventure.scene.RewardScene;
import forge.adventure.scene.Scene;
import forge.adventure.scene.StartScene;
import forge.adventure.scene.TileMapScene;
import forge.adventure.stage.GameStage;
import forge.adventure.stage.MapStage;
import forge.adventure.stage.WorldStage;
import forge.adventure.util.Config;
import forge.adventure.util.Controls;
import forge.adventure.util.Current;
import forge.adventure.world.WorldSave;
import forge.deck.Deck;
import forge.llm.LlmClient;
import forge.llm.LlmConfig;
import forge.llm.LlmMemory;
import forge.llm.LlmReflector;
import forge.llm.LobbyPlayerLlm;
import forge.llm.PlayerControllerLlm;

/**
 * Lets the LLM play Adventure mode on its own: it walks the overworld, picks fights, heals in towns and plays the
 * duels through {@link PlayerControllerLlm}. Driven from the render loop ({@link #tick(float)}) on the libGDX
 * thread; LLM requests for overworld decisions run in the background while the player stands still (enemies only
 * move while the player moves, so the world is effectively paused).
 *
 * Enable with {@code -Dforge.llm.agent=true}, the console command {@code llm on}, or F9 on the map.
 */
public class AdventureAgent {
    private static AdventureAgent instance;

    public static AdventureAgent get() {
        if (instance == null) {
            instance = new AdventureAgent();
        }
        return instance;
    }

    /** True when the LLM is in control of Adventure mode. */
    public static boolean isActive() {
        return instance != null && instance.enabled;
    }

    public static boolean autostartRequested() {
        return Boolean.getBoolean("forge.llm.agent") || "1".equals(System.getenv("FORGE_LLM_AGENT"));
    }

    private enum GoalType { FIGHT, AVOID, EXPLORE, HEAL }

    private static final class Goal {
        GoalType type;
        EnemySprite enemy;
        PointOfInterest poi;
        Vector2 direction;
        float duration;
        float elapsed;
        String description;
    }

    private static final String[] DIRECTION_NAMES = {"E", "NE", "N", "NW", "W", "SW", "S", "SE"};
    private static final float DIALOG_READ_DELAY = 1.5f;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "LLM adventure agent");
        t.setDaemon(true);
        return t;
    });

    private boolean enabled = false;
    private Scene lastScene;
    private float sceneTimer;
    private float actionTimer;
    private boolean startRequested;
    private boolean exitRequested;
    private boolean deathPending;
    private boolean autoSellMarked;
    /** The save slot the agent loaded this session, which autosaves are copied into; 0 if none. */
    private int playingSlot;

    /**
     * Save slot that should receive a copy of each autosave: the slot the agent itself loaded, while it is in control.
     * Never a slot it didn't load, so turning the agent on over another game can't overwrite its save.
     */
    public static int mirrorSlot() {
        return isActive() ? instance.playingSlot : 0;
    }

    private CompletableFuture<Goal> pending;
    private Goal goal;
    private float decisionCooldown;
    private final Set<EnemySprite> knownEnemies = Collections.newSetFromMap(new IdentityHashMap<>());

    private final Vector2 lastPos = new Vector2();
    private float stuckCheckTimer;
    private int stuckCount;
    private float detourTime;
    private final Vector2 detourDir = new Vector2();
    private boolean detourLeft;

    private final List<String> events = Collections.synchronizedList(new ArrayList<>());
    private String status = "idle";
    private String lastReasoning = "";

    private AdventureAgent() {
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean on) {
        enabled = on;
        goal = null;
        pending = null;
        startRequested = false;
        stopMoving();
        status = on ? "starting" : "off";
        if (on) {
            LlmReflector.get().resumePending();
        }
        System.out.println("[LLM] Adventure agent " + (on ? "enabled" : "disabled") + " (model " + LlmConfig.get().activeModel() + ")");
    }

    public String getStatus() {
        return "LLM agent " + (enabled ? "ON" : "OFF") + ": " + status
                + (lastReasoning.isEmpty() ? "" : "\nLast thought: " + lastReasoning)
                + "\n" + LlmMemory.get().winRateSummary(10)
                + "\nLLM: " + LlmClient.get().getStats()
                + (LlmReflector.get().isBusy() ? "\n(reflecting on the last game...)" : "");
    }

    /** Whether walking onto this location should enter it (only the town we are heading to heal in). */
    public boolean wantsToEnter(PointOfInterest poi) {
        return goal != null && goal.type == GoalType.HEAL && goal.poi == poi;
    }

    private TextraLabel statusLabel;
    private String statusLabelText = "";

    private float screenshotPoll;

    /**
     * Debug aid: when {@code <userDir>/llm/screenshot.request} exists, the next frame is saved to
     * {@code screenshot.png} next to it and the request file is removed. Lets tools see the game window.
     */
    public void pollScreenshotRequest(float delta) {
        screenshotPoll += delta;
        if (screenshotPoll < 1f) {
            return;
        }
        screenshotPoll = 0;
        java.io.File req = new java.io.File(LlmConfig.getDir(), "screenshot.request");
        if (!req.exists()) {
            return;
        }
        req.delete();
        try {
            com.badlogic.gdx.graphics.Pixmap pm = com.badlogic.gdx.utils.ScreenUtils.getFrameBufferPixmap(0, 0,
                    com.badlogic.gdx.Gdx.graphics.getBackBufferWidth(), com.badlogic.gdx.Gdx.graphics.getBackBufferHeight());
            com.badlogic.gdx.graphics.PixmapIO.writePNG(new com.badlogic.gdx.files.FileHandle(new java.io.File(LlmConfig.getDir(), "screenshot.png")), pm, -1, true);
            pm.dispose();
        } catch (Exception e) {
            System.err.println("[LLM] screenshot failed: " + e);
        }
    }

    /** One line at the top of the map screens so a watching human knows what the agent is up to. */
    public void renderStatus() {
        if (!enabled) {
            return;
        }
        Scene s = Forge.getCurrentScene();
        if (!(s instanceof GameScene) && !(s instanceof TileMapScene)) {
            return;
        }
        String text = "[%175]LLM: " + status + (pending != null ? " ..." : "");
        if (statusLabel == null) {
            statusLabel = Controls.newTextraLabel(text);
        }
        if (!text.equals(statusLabelText)) {
            statusLabelText = text;
            statusLabel.setText(text);
            statusLabel.pack();
        }
        statusLabel.setPosition(6, Forge.getScreenHeight() - statusLabel.getHeight() - 4);
        Forge.getGraphics().setProjectionMatrix(Forge.camera.combined);
        Forge.getGraphics().begin(Forge.getScreenWidth(), Forge.getScreenHeight());
        statusLabel.draw(Forge.getGraphics().getBatch(), 1f);
        Forge.getGraphics().end();
    }

    private void event(String e) {
        System.out.println("[LLM] " + e);
        events.add(e);
        while (events.size() > 8) {
            events.remove(0);
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // Duels

    /** The lobby player that takes the human's seat in an adventure duel. */
    public LobbyPlayerLlm createLobbyPlayer(EnemySprite enemy) {
        AdventurePlayer adv = Current.player();
        LobbyPlayerLlm lp = new LobbyPlayerLlm(adv.getName(), null);
        EnemyData data = enemy.getData();
        lp.setOpponentKey(data.getName());
        lp.setContext("This is a duel in Forge's Adventure mode (an RPG where every fight is a game of Magic) against the enemy \""
                + enemy.getName() + "\"" + (data.colors == null || data.colors.isEmpty() ? "" : " (colors " + colorNames(data.colors) + ")")
                + (data.boss ? ", a BOSS" : "") + ". Winning earns loot; losing costs gold and adventure health.");
        lp.setOnGameFinished(report -> event("Duel vs " + enemy.getName() + ": " + (report.won ? "WON" : "LOST") + " on turn " + report.turns
                + " (life " + report.finalLife + " vs " + report.opponentFinalLife + ")"));
        status = "dueling " + enemy.getName();
        return lp;
    }

    private static String colorNames(String colors) {
        List<String> n = new ArrayList<>();
        for (char c : colors.toUpperCase(Locale.ROOT).toCharArray()) {
            switch (c) {
                case 'W' -> n.add("White");
                case 'U' -> n.add("Blue");
                case 'B' -> n.add("Black");
                case 'R' -> n.add("Red");
                case 'G' -> n.add("Green");
                case 'C' -> n.add("Colorless");
                default -> { }
            }
        }
        return n.isEmpty() ? colors : String.join("/", n);
    }

    // ---------------------------------------------------------------------------------------------------------
    // Main loop

    public void tick(float delta) {
        if (!enabled) {
            return;
        }
        if (Forge.isTransitioning()) {
            return;
        }
        Scene s = Forge.getCurrentScene();
        if (s != lastScene) {
            if (lastScene instanceof GameScene) {
                // whatever we were walking towards is resolved (duel, town...) or stale now
                goal = null;
                stopMovingNow();
            }
            lastScene = s;
            sceneTimer = 0;
            actionTimer = 0;
            exitRequested = false;
            startRequested = false;
            autoSellMarked = false;
        } else {
            sceneTimer += delta;
        }
        actionTimer += delta;
        try {
            if ((s instanceof GameScene || s instanceof TileMapScene) && handleOpenDialog()) {
                // a dialog (death screen, quest message...) blocks the map until it is dismissed
                return;
            }
            if (s instanceof StartScene) {
                handleStart();
            } else if (s instanceof NewGameScene) {
                if (sceneTimer > 1f && !startRequested) {
                    startRequested = true;
                    status = "creating a new adventure";
                    NewGameScene.instance().start();
                }
            } else if (s instanceof GameScene) {
                handleWorld(delta);
            } else if (s instanceof TileMapScene) {
                handleMap();
            } else if (s instanceof RewardScene) {
                handleRewards();
            } else if (s instanceof DuelScene) {
                // PlayerControllerLlm is playing
            } else if (s != null && sceneTimer > 5f && actionTimer > 5f) {
                // some menu we never asked for (inventory, deck editor...): go back
                actionTimer = 0;
                Forge.switchToLast();
            }
        } catch (Exception e) {
            System.err.println("[LLM] agent error: " + e);
            e.printStackTrace();
            goal = null;
        }
    }

    private void handleStart() {
        if (sceneTimer < 2f || startRequested) {
            if (sceneTimer > 30f) {
                startRequested = false; // loading failed, try again
                sceneTimer = 0;
            }
            return;
        }
        startRequested = true;
        int slot = LlmConfig.get().adventureSaveSlot;
        if (WorldSave.getCurrentSave().getWorld().getData() != null) {
            status = "resuming the adventure";
            StartScene.instance().Resume();
        } else if (slot > 0 && new java.io.File(WorldSave.getSaveFile(slot)).exists()) {
            status = "loading save slot " + slot;
            playingSlot = slot;
            StartScene.instance().loadSlot(slot);
        } else if (Config.instance().getSettingData().lastActiveSave != null
                && WorldSave.isSafeFile(Config.instance().getSettingData().lastActiveSave)) {
            status = "loading the last save";
            StartScene.instance().Continue();
        } else {
            status = "starting a new adventure";
            Forge.switchScene(NewGameScene.instance());
        }
    }

    /**
     * First "done" turns the cards over; once they are face up, tick the "All" auto-sell box so every card reward is
     * sold (the deck is built by hand), then collect.
     */
    private void handleRewards() {
        if (sceneTimer < 2.5f || actionTimer < 1.5f) {
            return;
        }
        actionTimer = 0;
        RewardScene rewards = RewardScene.instance();
        if (!autoSellMarked && rewards.allRewardsFlipped()) {
            autoSellMarked = true;
            if (rewards.setAutoSellAll(true)) {
                status = "marking card rewards for auto-sale";
                return; // collect on the next action, so a watching human sees the marks
            }
        }
        status = "collecting loot";
        rewards.done();
    }

    private void handleMap() {
        MapStage stage = MapStage.getInstance();
        if (stage.isDialogOnlyInput()) {
            clickDialog(stage);
            return;
        }
        // we only ever enter towns to heal (entering heals automatically), so leave right away
        if (sceneTimer > 2f && !exitRequested) {
            exitRequested = true;
            PointOfInterest poi = TileMapScene.instance().rootPoint;
            status = "leaving " + (poi == null ? "the location" : poi.getDisplayName());
            if (poi != null) {
                // no health number here: the model kept reading an old "health 12/12" from this list as its current health
                event("Visited " + poi.getDisplayName() + " and healed there");
            }
            stage.exitDungeon(false, false);
        }
    }

    /**
     * Dismisses a dialog that is on screen for either map stage. Checks the dialog itself rather than
     * {@link GameStage#isDialogOnlyInput()}, because some dialogs (the "You Died" screen is shown by the world stage
     * on the HUD, possibly while a location is still loaded) don't leave that flag set on the stage we look at.
     */
    private boolean handleOpenDialog() {
        for (GameStage stage : new GameStage[]{WorldStage.getInstance(), MapStage.getInstance()}) {
            if (isDialogShowing(stage)) {
                stopMoving();
                if (deathPending) {
                    status = "died — accepting the death screen";
                }
                clickDialog(stage);
                return true;
            }
        }
        if (deathPending && !Forge.advFreezePlayerControls) {
            deathPending = false; // respawned
        }
        return false;
    }

    private static boolean isDialogShowing(GameStage stage) {
        com.badlogic.gdx.scenes.scene2d.ui.Dialog d = stage.getDialog();
        return d != null && d.getStage() != null && d.isVisible() && d.getButtonTable().getCells().size > 0;
    }

    /** Called when the adventure health reaches 0: the game shows "You Died", then respawns at the start. */
    public void onDeath() {
        deathPending = true;
        goal = null;
        status = "died";
        event("You DIED: a lost duel took your adventure health to 0. You were sent back to the start and lost gold. "
                + "Heal in a town whenever your health is low instead of fighting.");
        LlmMemory.get().addLesson(LlmMemory.SCOPE_WORLD, "When adventure health is at or below half, or one more lost duel "
                + "would take it to 0, go heal in a town before fighting anything: dying sends you back to the start and costs gold.");
    }

    /** Clicks the first visible button of the stage dialog, after a moment so a watching human can read it. */
    private void clickDialog(GameStage stage) {
        if (actionTimer < DIALOG_READ_DELAY) {
            return;
        }
        actionTimer = 0;
        for (Cell<?> cell : stage.getDialog().getButtonTable().getCells()) {
            Actor a = cell.getActor();
            if (a != null && a.isVisible() && click(a)) {
                return;
            }
        }
        // nothing clickable yet (text still typing); try again shortly
    }

    /**
     * Notifies every click listener, like a real tap. Buttons carry several: the button's own (toggles it and fires
     * the ChangeEvent that map dialogs listen for), a click sound, and often the actual action (e.g. the "You Died"
     * OK button), so stopping at the first one leaves such dialogs open forever.
     */
    private static boolean click(Actor a) {
        boolean clicked = false;
        for (EventListener l : a.getListeners().toArray(EventListener.class)) { // copy: a click may change listeners
            if (l instanceof ClickListener) {
                InputEvent ev = new InputEvent();
                ev.setType(InputEvent.Type.touchUp);
                ev.setListenerActor(a);
                ev.setTarget(a);
                ((ClickListener) l).clicked(ev, 0, 0);
                clicked = true;
            }
        }
        return clicked;
    }

    // ---------------------------------------------------------------------------------------------------------
    // Overworld

    private void stopMovingNow() {
        try {
            WorldStage.getInstance().setTouchKnobInput(0, 0);
        } catch (Exception ignored) {
        }
    }

    private void stopMoving() {
        if (!(Forge.getCurrentScene() instanceof GameScene)) {
            return;
        }
        try {
            WorldStage.getInstance().setTouchKnobInput(0, 0);
        } catch (Exception ignored) {
        }
    }

    private void handleWorld(float delta) {
        WorldStage ws = WorldStage.getInstance();
        if (ws.isDialogOnlyInput()) {
            stopMoving();
            clickDialog(ws);
            return;
        }
        if (ws.isPaused() || Forge.advFreezePlayerControls || ws.getPlayerSprite() == null) {
            return;
        }
        if (pending != null) {
            stopMoving();
            if (!pending.isDone()) {
                return;
            }
            try {
                goal = pending.get();
            } catch (Exception e) {
                goal = null;
            }
            pending = null;
            resetStuck(ws);
            if (goal != null) {
                status = goal.description;
            }
        }
        if (goal == null) {
            stopMoving();
            decisionCooldown -= delta;
            if (decisionCooldown <= 0) {
                requestDecision(ws);
            }
            return;
        }
        executeGoal(ws, delta);
    }

    private void resetStuck(WorldStage ws) {
        lastPos.set(ws.getPlayerSprite().getX(), ws.getPlayerSprite().getY());
        stuckCheckTimer = 0;
        stuckCount = 0;
        detourTime = 0;
    }

    private void endGoal(String note) {
        if (note != null) {
            event(note);
        }
        goal = null;
        decisionCooldown = 0.5f;
        stopMoving();
    }

    private void executeGoal(WorldStage ws, float delta) {
        goal.elapsed += delta;
        Vector2 me = ws.getPlayerSprite().getCenter();
        Vector2 dir;
        switch (goal.type) {
            case FIGHT -> {
                if (!ws.getEnemySprites().contains(goal.enemy)) {
                    endGoal(goal.enemy.getName() + " disappeared before you reached it");
                    return;
                }
                if (goal.elapsed > 45f) {
                    endGoal("Could not reach " + goal.enemy.getName());
                    return;
                }
                dir = goal.enemy.getCenter().sub(me);
            }
            case AVOID -> {
                if (goal.elapsed > goal.duration || !ws.getEnemySprites().contains(goal.enemy)) {
                    endGoal(null);
                    return;
                }
                dir = me.cpy().sub(goal.enemy.getCenter());
            }
            case HEAL -> {
                if (goal.elapsed > goal.duration) {
                    endGoal("Could not reach the town " + goal.poi.getDisplayName());
                    return;
                }
                dir = goal.poi.getCenter().sub(me);
            }
            default -> {
                if (goal.elapsed > goal.duration) {
                    endGoal(null);
                    return;
                }
                // stop exploring to decide about a newly spotted enemy
                if (goal.elapsed > 1f) {
                    for (EnemySprite e : ws.getEnemySprites()) {
                        if (!knownEnemies.contains(e)) {
                            endGoal(null);
                            return;
                        }
                    }
                }
                dir = goal.direction.cpy();
            }
        }
        steer(ws, dir, delta);
    }

    private void steer(WorldStage ws, Vector2 dir, float delta) {
        if (dir.len() < 0.01f) {
            stopMoving();
            return;
        }
        dir.nor();
        if (detourTime > 0) {
            detourTime -= delta;
            dir.set(detourDir);
        }
        avoidPointsOfInterest(ws, dir);
        ws.setTouchKnobInput(dir.x, dir.y);

        // stuck on terrain? try going around
        stuckCheckTimer += delta;
        if (stuckCheckTimer >= 1f) {
            stuckCheckTimer = 0;
            Vector2 pos = new Vector2(ws.getPlayerSprite().getX(), ws.getPlayerSprite().getY());
            if (pos.dst(lastPos) < 4f) {
                stuckCount++;
                detourLeft = !detourLeft;
                detourDir.set(dir).rotateDeg((detourLeft ? 90 : -90) + (stuckCount > 3 ? (detourLeft ? 45 : -45) : 0));
                detourTime = 0.8f + 0.4f * stuckCount;
                if (stuckCount > 8) {
                    endGoal("Path blocked while trying to " + goal.description);
                }
            } else if (detourTime <= 0) {
                stuckCount = Math.max(0, stuckCount - 1);
            }
            lastPos.set(pos);
        }
    }

    /**
     * Walking over a town, cave or dungeon enters it. Bend the path around every location we are not heading for:
     * push away from it and slide along its edge, so we circle past instead of bouncing back and forth.
     */
    private void avoidPointsOfInterest(WorldStage ws, Vector2 dir) {
        Vector2 me = ws.getPlayerSprite().getCenter();
        PointOfInterest target = goal != null && goal.type == GoalType.HEAL ? goal.poi : null;
        Vector2 steer = new Vector2(dir);
        for (PointOfInterest poi : WorldSave.getCurrentSave().getWorld().getAllPointOfInterest()) {
            if (poi == target || !poi.getActive()) {
                continue;
            }
            com.badlogic.gdx.math.Rectangle r = poi.getBoundingRectangle();
            Vector2 center = r.getCenter(new Vector2());
            float radius = Math.max(r.width, r.height) / 2f + ws.getPlayerSprite().getWidth();
            float margin = 24f;
            Vector2 away = me.cpy().sub(center);
            float dist = away.len();
            if (dist > radius + margin || dist < 0.01f) {
                continue;
            }
            away.nor();
            // only care if we are heading towards it
            if (away.dot(dir) > 0.3f) {
                continue;
            }
            float strength = Math.min(1f, (radius + margin - dist) / margin) * 1.5f;
            Vector2 tangent = new Vector2(-away.y, away.x);
            if (tangent.dot(dir) < 0) {
                tangent.scl(-1);
            }
            steer.add(away.scl(strength)).add(tangent.scl(1.2f));
        }
        if (steer.len() > 0.01f) {
            dir.set(steer.nor());
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // Overworld decisions

    private static String direction(Vector2 v) {
        float angle = v.angleDeg();
        int idx = Math.round(angle / 45f) % 8;
        return DIRECTION_NAMES[idx];
    }

    private static Vector2 directionVector(String name) {
        for (int i = 0; i < DIRECTION_NAMES.length; i++) {
            if (DIRECTION_NAMES[i].equalsIgnoreCase(name.trim())) {
                return new Vector2(1, 0).rotateDeg(i * 45f);
            }
        }
        return null;
    }

    private static final class Snapshot {
        final Map<String, EnemySprite> enemies = new LinkedHashMap<>();
        final Map<String, PointOfInterest> towns = new LinkedHashMap<>();
        String prompt;
        boolean mustHeal;
    }

    /** Adventure health left after one more lost duel ({@link AdventurePlayer#defeated()}); below 1 means death. */
    private static int healthAfterLoss(AdventurePlayer adv) {
        return (int) (adv.getLife() - adv.getMaxLife() * adv.getDifficulty().lifeLoss);
    }

    /** Low enough that walking into another fight is not allowed: half health or less, or one loss from dying. */
    private static boolean healthLow(AdventurePlayer adv) {
        return adv.getLife() < adv.getMaxLife() && (adv.getLife() * 2 <= adv.getMaxLife() || healthAfterLoss(adv) < 1);
    }

    private void requestDecision(WorldStage ws) {
        decisionCooldown = 2f;
        Snapshot snap = snapshot(ws);
        knownEnemies.clear();
        knownEnemies.addAll(ws.getEnemySprites());
        status = "thinking about where to go";
        pending = CompletableFuture.supplyAsync(() -> decide(snap), executor);
    }

    private Snapshot snapshot(WorldStage ws) {
        Snapshot snap = new Snapshot();
        AdventurePlayer adv = Current.player();
        Vector2 me = ws.getPlayerSprite().getCenter();
        float tile = WorldSave.getCurrentSave().getWorld().getTileSize();

        StringBuilder sb = new StringBuilder();
        sb.append("== Your status ==\n");
        sb.append("Adventure health: ").append(adv.getLife()).append('/').append(adv.getMaxLife())
                .append(" = ").append(Math.round(100f * adv.getLife() / Math.max(1, adv.getMaxLife()))).append("%")
                .append(adv.getLife() < adv.getMaxLife() ? " (NOT full)" : " (full)")
                .append(". This is also your starting life total in duels.\n");
        int afterLoss = healthAfterLoss(adv);
        sb.append("If you lose your next duel: ").append(afterLoss < 1 ? "you DIE (sent back to the start, lose gold)"
                : "health drops to " + afterLoss + "/" + adv.getMaxLife()).append('\n');
        snap.mustHeal = healthLow(adv);
        sb.append("Gold: ").append(adv.getGold()).append(", mana shards: ").append(adv.getShards()).append('\n');
        Deck deck = adv.getSelectedDeck();
        if (deck != null) {
            sb.append("Your deck: \"").append(deck.getName()).append("\", ").append(deck.getMain().countAll()).append(" cards, colors ")
                    .append(PlayerControllerLlm.deckColors(deck)).append('\n');
        }
        sb.append("Difficulty: ").append(adv.getDifficulty().name).append(" (losing a duel costs ")
                .append(Math.round(adv.getDifficulty().lifeLoss * 100)).append("% of max health and ")
                .append(Math.round(adv.getDifficulty().goldLoss * 100)).append("% of your gold)\n");

        List<EnemySprite> enemies = new ArrayList<>(ws.getEnemySprites());
        enemies.sort((a, b) -> Float.compare(a.getCenter().dst(me), b.getCenter().dst(me)));
        sb.append("\n== Enemies nearby (they chase you while you move) ==\n");
        if (enemies.isEmpty()) {
            sb.append("none in sight\n");
        }
        int i = 1;
        for (EnemySprite e : enemies) {
            if (i > 8) {
                break;
            }
            String id = "E" + i++;
            snap.enemies.put(id, e);
            EnemyData d = e.getData();
            Vector2 v = e.getCenter().sub(me);
            int life = Math.round(d.life * adv.getDifficulty().enemyLifeFactor);
            sb.append(id).append(". ").append(e.getName());
            if (!e.getName().equals(d.getName())) {
                sb.append(" (a ").append(d.getName()).append(')');
            }
            sb.append(" — ").append(Math.round(v.len() / tile)).append(" tiles ").append(direction(v))
                    .append(", colors ").append(d.colors == null || d.colors.isEmpty() ? "?" : colorNames(d.colors))
                    .append(", starts with ").append(life).append(" life");
            if (d.boss) {
                sb.append(", BOSS");
            }
            if (d.gamesPerMatch > 1) {
                sb.append(", best of ").append(d.gamesPerMatch);
            }
            LlmMemory.OpponentRecord rec = LlmMemory.get().getOpponent(d.getName());
            if (rec != null) {
                sb.append(", your record vs this enemy type ").append(rec.wins).append('-').append(rec.losses);
            }
            sb.append('\n');
        }

        List<PointOfInterest> towns = new ArrayList<>();
        for (PointOfInterest poi : WorldSave.getCurrentSave().getWorld().getAllPointOfInterest()) {
            String type = poi.getData() == null ? "" : poi.getData().type;
            if (("town".equals(type) || "capital".equals(type)) && poi.getActive()) {
                towns.add(poi);
            }
        }
        towns.sort((a, b) -> Float.compare(a.getCenter().dst(me), b.getCenter().dst(me)));
        sb.append("\n== Nearest towns (entering one heals you to full for free) ==\n");
        for (int t = 0; t < Math.min(3, towns.size()); t++) {
            PointOfInterest poi = towns.get(t);
            String id = "T" + (t + 1);
            snap.towns.put(id, poi);
            Vector2 v = poi.getCenter().sub(me);
            sb.append(id).append(". ").append(poi.getDisplayName()).append(" — ").append(Math.round(v.len() / tile)).append(" tiles ")
                    .append(direction(v)).append('\n');
        }

        List<String> recent;
        synchronized (events) {
            recent = new ArrayList<>(events);
        }
        if (!recent.isEmpty()) {
            sb.append("\n== Recent events (oldest first; your CURRENT health is the one in Your status) ==\n");
            for (String e : recent) {
                sb.append("- ").append(e).append('\n');
            }
        }
        String reflection = LlmReflector.get().getLastSummary();
        if (!reflection.isEmpty()) {
            sb.append("Your review of the last duel: ").append(reflection).append('\n');
        }

        sb.append("\n== Decision ==\nChoose what to do next:\n")
                .append("- fight: walk into an enemy to duel it (target = E#)\n")
                .append("- avoid: walk away from an enemy for a few seconds (target = E#)\n")
                .append("- explore: walk in a direction to find other enemies (target = N, NE, E, SE, S, SW, W, NW)\n")
                .append(adv.getLife() < adv.getMaxLife() ? "- heal: go to a town to restore health (target = T#)\n"
                        : "(heal is not available: your health is already full)\n")
                .append("Winning duels gives cards and gold, which is how you get stronger. Pick fights you can win; avoid ones you keep losing. ")
                .append("Heal before your health gets low, because duels start at your current health.\n")
                .append("Your health right now: ").append(adv.getLife()).append('/').append(adv.getMaxLife()).append(". ")
                .append(snap.mustHeal ? "This is LOW: you must heal now (fighting or exploring will be refused while a town is known).\n"
                        : adv.getLife() < adv.getMaxLife() ? "Not full: prefer healing first if a town is close.\n" : "\n")
                .append("Reply {\"reasoning\": \"...\", \"action\": \"fight|avoid|explore|heal\", \"target\": \"...\"}");
        snap.prompt = sb.toString();
        return snap;
    }

    private static final String WORLD_SYSTEM_BASE =
            "You are playing Forge's Adventure mode, an overworld RPG where every fight with an enemy is a game of Magic: The Gathering "
            + "that you play yourself. You are deciding where to walk on the world map. Answer with one JSON object only and keep "
            + "\"reasoning\" to one sentence.\n";

    private static JsonObject worldSchema() {
        JsonObject str = new JsonObject();
        str.addProperty("type", "string");
        JsonObject props = new JsonObject();
        props.add("reasoning", str);
        props.add("action", str);
        props.add("target", str);
        JsonObject o = new JsonObject();
        o.addProperty("type", "object");
        o.add("properties", props);
        JsonArray req = new JsonArray();
        req.add("reasoning");
        req.add("action");
        req.add("target");
        o.add("required", req);
        o.addProperty("additionalProperties", false);
        return o;
    }

    /** Runs on the agent thread. */
    private Goal decide(Snapshot snap) {
        String system = WORLD_SYSTEM_BASE + "\n" + LlmMemory.get().renderForWorld();
        JsonObject r = null;
        try {
            LlmConfig cfg = LlmConfig.get();
            r = LlmClient.get().chatMap("world", system, snap.prompt, worldSchema(), cfg.reasoningEffort, cfg.maxTokens).json;
        } catch (Exception e) {
            System.err.println("[LLM] world decision failed: " + e);
        }
        Goal g = r == null ? null : toGoal(snap, r);
        if (g == null) {
            g = fallbackGoal(snap);
            lastReasoning = "(fallback) " + g.description;
        } else if (snap.mustHeal && (g.type == GoalType.FIGHT || g.type == GoalType.EXPLORE) && !snap.towns.isEmpty()) {
            // one more loss could kill us: don't let the model gamble on another duel
            g = healGoal(snap.towns.values().iterator().next());
            lastReasoning = "(overridden: health is low) " + lastReasoning;
        }
        System.out.println("[LLM] World decision: " + g.description + (lastReasoning.isEmpty() ? "" : " — " + lastReasoning));
        return g;
    }

    private Goal toGoal(Snapshot snap, JsonObject r) {
        String action = r.has("action") ? r.get("action").getAsString().toLowerCase(Locale.ROOT).trim() : "";
        String target = r.has("target") ? r.get("target").getAsString().trim() : "";
        lastReasoning = r.has("reasoning") ? r.get("reasoning").getAsString() : "";
        String key = target.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        Goal g = new Goal();
        switch (action) {
            case "fight", "avoid" -> {
                EnemySprite e = snap.enemies.get(key);
                if (e == null) {
                    return null;
                }
                g.type = action.equals("fight") ? GoalType.FIGHT : GoalType.AVOID;
                g.enemy = e;
                g.duration = 5f;
                g.description = (action.equals("fight") ? "fight " : "avoid ") + e.getName();
            }
            case "heal" -> {
                if (Current.player().getLife() >= Current.player().getMaxLife()) {
                    return null; // nothing to heal
                }
                PointOfInterest poi = snap.towns.get(key);
                if (poi == null && !snap.towns.isEmpty()) {
                    poi = snap.towns.values().iterator().next();
                }
                if (poi == null) {
                    return null;
                }
                return healGoal(poi);
            }
            case "explore" -> {
                Vector2 dir = directionVector(key);
                if (dir == null) {
                    dir = new Vector2(1, 0).rotateDeg((float) (Math.random() * 360));
                }
                g.type = GoalType.EXPLORE;
                g.direction = dir;
                g.duration = 12f;
                g.description = "explore " + (directionVector(key) == null ? "" : key);
            }
            default -> {
                return null;
            }
        }
        return g;
    }

    private static Goal healGoal(PointOfInterest poi) {
        Goal g = new Goal();
        g.type = GoalType.HEAL;
        g.poi = poi;
        g.duration = 120f;
        g.description = "walk to " + poi.getDisplayName() + " to heal";
        return g;
    }

    /** Simple rules for when the LLM is unavailable. */
    private Goal fallbackGoal(Snapshot snap) {
        if (snap.mustHeal && !snap.towns.isEmpty()) {
            return healGoal(snap.towns.values().iterator().next());
        }
        Goal g = new Goal();
        for (EnemySprite e : snap.enemies.values()) {
            if (!e.getData().boss) {
                g.type = GoalType.FIGHT;
                g.enemy = e;
                g.description = "fight " + e.getName();
                return g;
            }
        }
        g.type = GoalType.EXPLORE;
        g.direction = new Vector2(1, 0).rotateDeg((float) (Math.random() * 360));
        g.duration = 10f;
        g.description = "explore";
        return g;
    }
}
