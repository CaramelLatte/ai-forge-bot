# LLM Player

Forge can hand a seat to a large language model running behind an OpenAI-compatible API (LM Studio, llama.cpp's
`llama-server`, Ollama's `/v1`, vLLM...). The model can play Adventure mode on its own: it walks the world map, picks
fights, heals in towns, and plays the duels. After every game it reviews what happened and writes notes for itself,
which go into the prompts of later games.

## What the model decides

In a duel the model makes the decisions that matter:

- keeping or mulliganing an opening hand
- which lands, spells and abilities to play and when, including targets and X values, and whether to respond to the
  opponent's spells
- attacks and blocks

The Forge AI handles everything else, such as paying mana, ordering triggers and the choices inside effects. It also
takes over whenever the model can't be reached or gives an answer that can't be used.

On the world map the model chooses between **fight** (walk into an enemy), **avoid** (walk away from an enemy),
**explore** (walk in a direction to find enemies) and **heal** (walk into a town, which restores health). When its
health is at or below half, or one more lost duel would kill it, a fight or explore choice is replaced with healing
at the nearest town. Menus, reward screens and dialogs (including the "You Died" screen) are clicked through
automatically. On reward screens every card reward is marked for auto-sale (the "All" checkbox), since the deck is
built by hand.

The agent plays in save slot `adventureSaveSlot` (default 1): it loads that slot at startup and copies every
autosave into it, so it keeps building on that save across sessions. It only writes to a slot it loaded itself, so
pressing F9 over another game never touches its save. Set `adventureSaveSlot` to 0 to load the last active save,
like the Continue button.

## Strategy guide and computed facts

Every duel prompt starts with a strategy guide, `forge-gui/res/llm/primer.md`: a checklist for each decision, turn
structure and timing, survival and lethal math, reading the opponent, combat, what keywords mean for decisions,
targeting, sequencing and mulligans. To change it without rebuilding, copy it to `~/.forge/llm/primer.md` and edit
the copy. It is read at the start of each duel. Its text never depends on the game, so the start of the system
prompt stays identical across duels and stays in LM Studio's prompt cache.

The game engine also works out things the model tends to get wrong and adds them to each request:

- **Keywords in this game**: the exact rules text of every keyword on the model's cards, plus the opponent's as
  they show up.
- **Board math**: the opponent's possible attack on their next turn (counting all their creatures, since they
  untap first), how much gets through the model's best blocks, the model's own clock, and lethal warnings.
- **Opponent read**: hand size over time, missed land drops, turns since their last creature, spells cast on the
  model's turns, mana they left open, and their colors.
- **Block outcomes**: for each possible block, who dies and how much damage gets through, with chump blocks,
  trades and free kills labeled.
- **Mulligans**: land count and spell costs.

When lethal is on the board for either side, the request uses `criticalReasoningEffort` (default `medium`)
instead of `reasoningEffort`.

## Learning

Everything the model learns is stored in `<forge user dir>/llm/memory.json` (on Linux `~/.forge/llm/memory.json`):

- **Lessons** the model wrote after games. Each lesson has a scope: `general`, a specific opponent, one of its own
  cards, or `world` (which fights to pick). Lessons that apply to the current game go into the duel's system prompt.
- **Lesson scores.** After each game the model marks lessons that mattered as `helpful` or `wrong`. Lessons that keep
  being wrong are forgotten. When a scope fills up, the model merges its lessons into fewer, sharper ones. Each
  card keeps at most `maxCardLessons` notes (default 3). The review sees the strategy guide and drops lessons that
  contradict it.
- **Opponent records**: wins and losses against each enemy type, their colors, and the cards they have been seen to
  play. The world-map decisions use these records too.

The file is plain JSON, so you can read it, edit it, or delete it to start over. Reflection runs in the background.
Games waiting to be reviewed are kept in `~/.forge/llm/pending/` and are picked up on the next start, so quitting
Forge never loses a game.

## Setup

1. Start an OpenAI-compatible server with a capable model. On LM Studio, load the model and enable the local server
   (the default is `http://localhost:1234/v1`).
   Give the model a context window of at least 16k tokens. LM Studio loads models with 8192 by default, which is
   too small for the post-game reflection. Either set the default context length for the model in LM Studio
   (My Models → the model's settings), so loads made on demand use it too, or load it yourself:
   `lms load openai/gpt-oss-120b -c 32768 --parallel 2`. With `--parallel 2`, a reflection can run while the next
   game is played. Then set `contextTokens` (below) to the context each request gets; with LM Studio's split
   context, that is the total divided by the parallel count.
2. Run Forge once, or create `~/.forge/llm/config.json` yourself. The file is written with its defaults on first use:

| Setting | Default | Meaning |
|---|---|---|
| `baseUrl` | `http://localhost:1234/v1` | API endpoint |
| `model` | `openai/gpt-oss-120b` | Model id as the server knows it |
| `mapModel` | `openai/gpt-oss-20b` | Smaller local model for Adventure world-map decisions, used even when Claude plays the duels; empty to use the main model |
| `mapBaseUrl` | empty | Server for `mapModel`; empty for `baseUrl` |
| `apiKey` | empty | Sent as a bearer token if set |
| `reasoningEffort` | `low` | `reasoning_effort` for in-game decisions (empty to leave it out) |
| `reflectionReasoningEffort` | `medium` | Same, for post-game reflection |
| `useJsonSchema` | `true` | Ask the server for schema-constrained JSON. Switched off automatically if the server rejects it |
| `showAiHint` | `true` | Show the model what the Forge AI would do |
| `llmTargeting` | `true` | Let the model choose targets (otherwise the Forge AI does) |
| `respondOnOpponentTurn` | `true` | Ask about instant-speed plays during the opponent's turn |
| `reflectAfterGame` | `true` | Write lessons after each game |
| `maxGeneralLessons` | `20` | General lessons kept before they are consolidated |
| `contextTokens` | `32768` | Context window per request; long prompts are trimmed to fit |
| `logTranscripts` | `true` | Log every prompt and answer to `~/.forge/llm/logs/` |

Any setting can be overridden on the command line with `-Dforge.llm.<setting>=value`.

### Claude API

Instead of a local server, the desktop builds (Adventure and headless practice) can play with Claude. Put your key
in `claudeApiKey` in `~/.forge/llm/config.json` (or set `ANTHROPIC_API_KEY`), then add `--claude` to the launch
command, or set `provider` to `claude` to make it the default:

```sh
./forge-adventure-llm.sh --claude
java -jar ../forge-gui-desktop/target/forge-gui-desktop-*-jar-with-dependencies.jar llm-practice -n 5 --claude
```

| Setting | Default | Meaning |
|---|---|---|
| `provider` | `local` | `local` for the server above, `claude` for the Claude API |
| `claudeApiKey` | empty | API key; empty to use `ANTHROPIC_API_KEY` |
| `claudeModel` | `claude-haiku-5-5` | Claude model id (`claude-sonnet-5-5` or `claude-opus-5-5` play stronger at 20-40x the cost) |
| `claudeMaxTokens` | `16000` | Output limit per request; Claude's thinking counts toward it |
| `claudeMaxEffort` | `medium` | Highest effort ever sent to Claude; higher requests are lowered to it |

`reasoningEffort` and `reflectionReasoningEffort` set Claude's effort level, capped at `claudeMaxEffort`. The
critical effort boost is never used with Claude: it costs more and switching effort breaks the prompt cache. The system prompt is sent as two cached blocks: the strategy guide, which is reused across games, and the
game-specific part. A full game on Opus 5.5 cost about $0.50 (mostly output and cache writes); Haiku 5.5 is
$0.10/$0.50 per million input/output tokens with cache reads at $0.01, so a game costs a few cents. Each call's
token use is logged in the transcript's `usage` field and totalled in the end-of-game stats line. The Anthropic SDK is bundled only with the desktop builds.

## Running

**Adventure mode**, with the model in control from the start (it continues the last save or starts a new adventure):

```sh
mvn -DskipTests -pl forge-gui-mobile-dev -am install
forge-gui-mobile-dev/forge-adventure-llm.sh width=1280 height=720
```

You can also start Adventure normally and hand over control with **F9** on the map, or with the console commands
`llm on`, `llm off`, `llm status` and `llm reload` (reloads `config.json`). Duels are shown in spectator view; touching
the battlefield pauses the game.

A status line at the top of the map shows what the agent is doing. To see the game window from scripts (for example
when the game runs on another screen), create the file `~/.forge/llm/screenshot.request`; the next frame is saved
to `~/.forge/llm/screenshot.png` and the request file is deleted.

While the agent is in control, walking over a town, cave or dungeon only enters it if it is the town the agent is
heading to for healing. It walks past every other location.

**Headless practice**: the model plays the Forge AI with no window, using a random Adventure starter deck against a
random Adventure enemy deck by default. Practice games feed the same memory as Adventure duels.

```sh
mvn -DskipTests -pl forge-gui-desktop -am install
cd forge-gui
java -jar ../forge-gui-desktop/target/forge-gui-desktop-*-jar-with-dependencies.jar llm-practice -n 10
# options: -deck my.dck   -opp enemy1.dck enemy2.dck   -life 20   -oppLife 20
```

## Performance notes

Each decision is one request, and decisions with nothing meaningful to choose never reach the model. When the model
lists several actions in one answer (for example, play a land and then cast a spell), they are carried out without
further requests. The system prompt (rules reminders, decklist and lessons) stays the same for a whole game, so the
server's prompt cache can reuse it; the changing game state comes last. With gpt-oss-120b on a Strix Halo machine, a
practice game takes about 3 to 6 minutes, at roughly 10 seconds per decision.

## Code

- `forge-gui/src/main/java/forge/llm/`: the client, config, memory, reflection, and `PlayerControllerLlm` (a
  `PlayerControllerAi` that asks the model). Nothing in it is specific to Adventure, so it can also back other game
  modes. `BoardAnalysis` does the board math, block outcomes and keyword glossary, `OpponentTracker` builds the
  opponent read, and `LlmPrimer` loads the strategy guide.
- `forge-gui/res/llm/primer.md`: the strategy guide.
- `forge-gui-mobile/src/forge/adventure/llm/AdventureAgent.java`: the Adventure overworld agent.
- `forge-gui-desktop/src/main/java/forge/view/LlmPractice.java`: headless practice mode.
