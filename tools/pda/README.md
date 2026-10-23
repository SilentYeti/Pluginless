# Pluginless PDA smoke harness

Run from the Pluginless checkout (Java 21, Python 3 and `xdotool` required; `Xvfb` for headless mode):

```sh
python3 tools/pda/run.py
```

For a visible instance on your desktop that stays open:

```sh
python3 tools/pda/run.py --show
```

`--show` inherits your real DISPLAY and Wayland session (Minecraft uses X11/XWayland), does not start Xvfb or force software rendering, and targets input only at the launched client's process. After the smoke/script finishes, the client and seeded server stay up until you close Minecraft. Stop by closing the Minecraft window or pressing Ctrl+C in the runner terminal; the test server stops too. The PDA starts in your main hand, already raised with Terminal open. Right-click or Esc lowers it; right-click raises it again; use HOME on its screen to choose an app. Pluginless's own app is still pending task 17.

This builds sibling `../WFLib-main` and Pluginless's `1.21.1` jar, then starts a **dedicated server and a separate client**, both NeoForge 21.1.248 / Minecraft 1.21.1. A small development-only mod supplies the seed and control bridge; no feature sources, production Gradle files or release jars include it. Runtime dependencies are the existing Gradle-cache versions used by WFLib/Pluginless: LDLib2 2.2.26-all, AHF 1.0.8-SNAPSHOT, GemRender 0.1.17-SNAPSHOT and Flywheel 1.0.6. Build the two projects normally first if those artifacts are not cached. `--wflib /path/to/WFLib-main` selects another sibling checkout; `--skip-build` reuses existing jars for quick iteration. Exact loaded jar paths and SHA-256 hashes are recorded, including the selected cached snapshot bytes.

Every invocation creates a fresh `build/pda-harness/run-*` directory. It does not delete or reuse a previous world. The server binds **127.0.0.1 only**, uses an ephemeral port and offline test identities, and accepts the Minecraft EULA for this disposable test run. Never copy these server settings to a public server. Default input goes to a private Xvfb display with master audio muted (`soundCategory_master:0.0` in that run’s disposable client `options.txt`); `--show` uses the desktop with normal audio. Processes started by the runner are stopped when it exits, including after a failure.

Seed: superflat world, fixed world seed 160016; server treasury 100,000 currency units; `harness_seller` starts with 10,000 and lists eight diamonds for 25 through the real `/ah sell` command; default YETI and GOLD stocks are frozen. `PdaTester` joins with 25,000 currency units, creative mode, and `wflib:pda` selected in the main hand. The harness fails if funding or listing fails. This is a GUI fixture, not an economy simulation or a security test.

The default run right-clicks to focus the held PDA, verifies `PdaClient.focused()`, captures the actual framebuffer, opens `wflib:terminal` through `PdaApps.get` / `PdaClient.open`, waits for rendering and captures it. Screenshots are captured by Minecraft, not mock renders. A missing or invisible app, failed seed, failed process, timeout or missing screenshot fails the run. Opening an app programmatically verifies the app view; it does not verify the home tile's click wiring.

To cover new Pluginless apps once they are registered:

```sh
python3 tools/pda/run.py --apps pluginless:pluginless
```

Task 17 reserves `pluginless:pluginless` for one app with internal pages (home, bank, auction, market, stocks, companies, ranks, qol, cosmetics). It is not built yet: this command intentionally fails until the app is registered. Page selection via the planned `pda_pl_page` signal remains pending the approved integration; this harness makes no assumption about that interface. `--apps` with no following IDs captures only the PDA home page.

To drive keys, typing, cursor movement and clicks in an app, append a JSON script:

```sh
python3 tools/pda/run.py --apps wflib:terminal --script tools/pda/terminal-input.json
```

Keys, typing and clicks use real X11 events. `pointer` moves the PDA cursor through its public steering API (pinned WFLib 1.8.36 gain); it avoids depending on mouse sensitivity for repeatable app tests. The Terminal script asserts that typing reached the real text model and Enter cleared it before capturing the result. Mouse coordinates are window pixels (1280x800); they are not PDA layout units. The PDA is a model in the world view, so app controls must be clicked at the coordinates visible in the screenshot. Scripts can use `app` (`id`), `home`, `state`, `key` (`key`, xdotool key syntax), `text` (`text`), `move` (`x`, `y`, window pixels), `pointer` (`x`, `y`, PDA layout units), `assert_text` (`text`, requires a focused WFLib text field with that content), `click` (`button`, defaults to 1), `wait` (`seconds`) and `screenshot` (`name`, letters/digits/hyphen/underscore only). `state` requires a focused PDA. Unknown actions fail. UI changes may require updated click coordinates.

Artifacts in the printed run directory:

- `pda-home.png`, one PNG per requested app, and any scripted screenshots;
- `result.json`: successful checks and seed receipt; absence means the run did not pass;
- `jars.json`: loaded artifact paths and SHA-256 hashes;
- `server.log`, `client.log`, `xvfb.log`, plus each game's `logs/`;
- `server/world` and server/client configuration, retained for investigation;
- `control/`: seed/readiness receipts and action responses.

A screenshot passing means the requested view rendered; inspect the image to judge layout. This harness does not assert every feature's balances, transactions, permissions or visual correctness. Extend scenario scripts alongside the apps; the full feature suite remains separate (`tools/selftest.py`).
