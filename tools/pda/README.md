# Pluginless PDA smoke harness

Run from the Pluginless checkout (Java 21, Python 3 and `xdotool` required; `Xvfb` for headless mode):

```sh
python3 tools/pda/run.py
```

For a visible instance on your desktop that stays open:

```sh
python3 tools/pda/run.py --show
```

`--show` inherits your real DISPLAY and Wayland session (Minecraft uses X11/XWayland), does not start Xvfb or force software rendering, and targets input only at the launched client's process. After the smoke/script finishes, the client and seeded server stay up until you close Minecraft. Stop by closing the Minecraft window or pressing Ctrl+C in the runner terminal; the test server stops too. The PDA starts in your main hand, already raised with Terminal open. Right-click or Esc lowers it; right-click raises it again; use HOME on its screen to choose an app. Use `--apps pluginless:pluginless` to open Pluginless when it is included in the selected jar.

This builds sibling `../WFLib-main` and Pluginless's `1.21.1` jar, then starts a **dedicated server and a separate client**, both NeoForge 21.1.248 / Minecraft 1.21.1. A small development-only mod supplies the seed and control bridge; no feature sources, production Gradle files or release jars include it. Runtime dependencies are the existing Gradle-cache versions used by WFLib/Pluginless: LDLib2 2.2.26-all, AHF 1.0.8-SNAPSHOT, GemRender 0.1.17-SNAPSHOT and Flywheel 1.0.6. Build the two projects normally first if those artifacts are not cached. `--wflib /path/to/WFLib-main` selects another sibling checkout; `--skip-build` reuses existing jars for quick iteration. Jars are copied into each run directory before launch, so a worker rebuild cannot replace the running test artifacts. Original paths, loaded copies and SHA-256 hashes are recorded, including the selected cached snapshot bytes.

Every invocation creates a fresh `build/pda-harness/run-*` directory. It does not delete or reuse a previous world. The server binds **127.0.0.1 only**, uses an ephemeral port and offline test identities, and accepts the Minecraft EULA for this disposable test run. Never copy these server settings to a public server. Default input goes to a private Xvfb display with master audio muted (`soundCategory_master:0.0` in that run’s disposable client `options.txt`); `--show` uses the desktop with normal audio. Processes started by the runner are stopped when it exits, including after a failure.

Seed: superflat world, fixed world seed 160016; server treasury 100,000 currency units; `harness_seller` starts with 10,000 and lists eight diamonds for 25 through the real `/ah sell` command; default YETI and GOLD stocks are frozen. `PdaTester` joins with 25,000 currency units, creative mode, and `wflib:pda` selected in the main hand. The harness fails if funding or listing fails. This is a GUI fixture, not an economy simulation or a security test.

The default run right-clicks to focus the held PDA, verifies `PdaClient.focused()`, captures the actual framebuffer, opens `wflib:terminal` through `PdaApps.get` / `PdaClient.open`, waits for rendering and captures it. Screenshots are captured by Minecraft, not mock renders. A missing or invisible app, failed seed, failed process, timeout or missing screenshot fails the run. Opening an app programmatically verifies the app view; it does not verify the home tile's click wiring.

To open the Pluginless app:

```sh
python3 tools/pda/run.py --apps pluginless:pluginless
```

The integration registers one `pluginless:pluginless` app with internal pages. If your default built jar does not include it yet, pass `--pluginless-jar /path/to/worker/versions/1.21.1/build/libs/pluginless-0.1.0+1.21.1.jar`; the runner uses that artifact instead of building the shared Pluginless checkout. Missing/invisible apps fail. `--apps` with no following IDs captures only the PDA home page.

Each reusable page scenario is a separate JSON file under `scenarios/`: `bank`, `auction`, `market`, `stocks`, `companies`, `ranks`, `qol` (Homes & Warps), and `cosmetics`.

```sh
python3 tools/pda/run.py --skip-build \
  --pluginless-jar /path/to/worker/versions/1.21.1/build/libs/pluginless-0.1.0+1.21.1.jar \
  --apps pluginless:pluginless --script tools/pda/scenarios/bank.json
```

Repeat `--script` to run several independent page files in the same client/server instance, for example `--script tools/pda/scenarios/bank.json --script tools/pda/scenarios/auction.json`.

Each scenario closes/reopens the app, checks the Home snapshot, moves the PDA pointer to its tile and sends a real mouse click. It asserts the selected page and receipt of its server snapshot, captures `pluginless-PAGE.png`, then clicks BACK and checks Home again. The assertions read the public `PdaClientState.page()` and `view()` methods; they never select the page directly. A tile still marked SOON fails the page assertion, so a screenshot of Home cannot pass as a finished page. Snapshot assertions establish delivery, not correctness of every field or feature action.

To check label alignment across all eight pages and five Cosmetics tabs, then apply colour/gradient and receive your own profile-bearing head:

```sh
python3 tools/pda/run.py --apps pluginless:pluginless \
  --ranks tools/pda/fixtures/cosmetics.json \
  --script tools/pda/scenarios/layout-and-cosmetics.json
```

`assert_layout` measures and draws the current app tree with the loaded game font into an empty clip. It checks nonempty button contents for border containment, direct text labels for vertical centering, equal-height buttons on the same row for overlapping rectangles, and buttons extending beyond the PDA width, including disabled controls. Deliberately misaligned and overlapping controls must fail before the real layout may pass. This checks the current visible state; it does not prove every possible translation, resource-pack font, inventory or server-configured string fits. The fixture grants cosmetics to the test rank only; production players still need their configured perks/grants, while operators can use valid cosmetics. `assert_page` accepts optional `values` for exact server-snapshot assertions; `assert_inventory` checks an item count and optional `own_profile` on delivered skulls.

`audit_text` compares raw text signal getters with their declared capacities, source snapshot strings with the old character-shortening convention, and WFLib Text buffers with the template values. For fitted Pluginless text it reads `PdaTextAudit` diagnostics, measures displayed lines with the loaded font, and moves the cursor over each clipped label to check that its tooltip actually contains the complete source. Marked overflow and full tooltip lines are retained in the report. These audits collect failures across the scenario: the runner writes `result.json` with `passed: false` and exits unsuccessfully after visiting all pages. An action receipt's `ok: true` only means the audit executed; inspect its `text_audit.errors` or the aggregate `text_errors`. Opaque custom charts are not inspected.

Run the long-text audit against a built combined jar:

```sh
python3 tools/pda/run.py --skip-build --apps pluginless:pluginless \
  --ranks tools/pda/fixtures/cosmetics.json \
  --stocks tools/pda/fixtures/long-text-stocks.json --long-text \
  --script tools/pda/scenarios/text-audit.json
```

The fixtures include the original YETI description, a 629-character stock description, a 24-character company name, and a 32-character home name seeded through real server commands. `audit_text` accepts `requires`, an array of source substrings that must actually appear among inspected labels, to prevent a wrong selection from passing a long-text test. Use `--pluginless-jar` to test an isolated product branch. Every run snapshots its jars and records SHA-256 hashes. The scenario covers each main page, Stocks tabs, and Cosmetics tabs; it does not exhaust every data-dependent state or font. Serialize GUI runs with other workers.

Tile coordinates match the foundation's four-column 86x40 grid with 8-unit gaps, at `(8,18)` below WFLib's and Pluginless's 18-unit bars. A layout change needs updated scenario coordinates. Page workers can append feature-specific steps to their scenario without changing the runner.

To drive keys, typing, cursor movement and clicks in an app, append a JSON script:

```sh
python3 tools/pda/run.py --apps wflib:terminal --script tools/pda/terminal-input.json
```

Keys, typing and clicks use real X11 events. `pointer` moves the PDA cursor through its public steering API (pinned WFLib 1.8.36 gain); it avoids depending on mouse sensitivity for repeatable app tests. The Terminal script asserts that typing reached the real text model and Enter cleared it before capturing the result. Mouse coordinates are window pixels (1280x800); they are not PDA layout units. The PDA is a model in the world view, so app controls must be clicked at the coordinates visible in the screenshot. Scripts can use `app` (`id`), `home`, `state`, `key` (`key`, xdotool key syntax), `text` (`text`), `move` (`x`, `y`, window pixels), `pointer` (`x`, `y`, PDA layout units), `assert_text` (`text`, requires a focused WFLib text field with that content), `assert_page` (`page`, checks Pluginless app focus, selected page and server snapshot; saves the snapshot in its receipt), `click` (`button`, defaults to 1), `wait` (`seconds`) and `screenshot` (`name`, letters/digits/hyphen/underscore only). `state` requires a focused PDA. Unknown actions fail. UI changes may require updated click coordinates.

Artifacts in the printed run directory:

- `pda-home.png`, one PNG per requested app, and any scripted screenshots;
- `result.json`: successful checks and seed receipt; absence means the run did not pass;
- `jars.json`: loaded artifact paths and SHA-256 hashes;
- `server.log`, `client.log`, `xvfb.log`, plus each game's `logs/`;
- `server/world` and server/client configuration, retained for investigation;
- `control/`: seed/readiness receipts and action responses.

A screenshot passing means the requested view rendered; inspect the image to judge layout. This harness does not assert every feature's balances, transactions, permissions or visual correctness. Extend scenario scripts alongside the apps; the full feature suite remains separate (`tools/selftest.py`).

## Prism instance for manual PDA testing

The separate **Pluginless PDA test** instance uses Minecraft 1.21.1, NeoForge 21.1.248 and Java 21. It lives in `~/.local/share/PrismLauncher/instances/Pluginless-PDA-Test`. Select it in Prism and click Launch, or run:

```sh
prismlauncher --launch Pluginless-PDA-Test
```

If Prism was already open while the folder was created and does not show it, reopen the launcher. First launch may download the pinned Minecraft/NeoForge files. No existing Prism instance, account or global launcher settings are changed.

Open Singleplayer → **Pluginless PDA test**. This is a copy of the disposable superflat harness world, with Creative and cheats enabled, the seeded treasury/stocks/diamond listing, and a PDA in the singleplayer inventory. Select it and right-click, then HOME → Pluginless. If needed, `/give @s wflib:pda` supplies another PDA. A different real player identity does not inherit PdaTester's ledger balance: use `/eco set <your-player-name> 25000` to fund yourself. The seeded seller and server treasury remain available. Audio is on (`soundCategory_master:1.0` on initial setup).

`minecraft/mods` contains symlinks to the combined Pluginless output, WFLib 1.8.36 and the same cached AHF/LDLib2/GemRender/Flywheel versions used by the harness. Rebuilding the linked output updates what the next Prism launch loads. Close Minecraft before rebuilding. There is no development harness mod in this instance.

To refresh links and the hash/source-commit receipt after a rebuild:

```sh
python3 tools/pda/prism.py
```

To select a different combined build, use `--pluginless-jar /path/to/versions/1.21.1/build/libs/pluginless-0.1.0+1.21.1.jar`; `--wflib-jar` can select a new WFLib output. `pluginless-pda-instance.json` in the instance folder records exact linked paths, hashes at refresh and source checkout HEADs. Symlinks track later rebuilds, so refresh the receipt when recording a new test.

For initial setup elsewhere, provide `--instance /path/to/a/new/instance`, `--pluginless-jar ...` and optionally `--seed-world /path/to/a/stopped/harness/server/world`. The helper refuses unmarked existing instances, unmanaged mod collisions and overwriting an existing world. Refreshing a marked instance preserves options, launcher settings and played saves.

Prism's [official CLI documentation](https://www.prismlauncher.org/wiki/getting-started/command-line-interface/) describes `--launch` and instance folder IDs.
