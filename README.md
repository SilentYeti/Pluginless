# Pluginless

The general idea of this project is to be a replacement for server plugins, with more functionality
and configurability. A modded server cannot run Bukkit or Paper plugins, so the things every server
owner expects (an economy, ranks, an auction house, homes and warps) end up coming from a stack of
unrelated mods that do not know about each other. Pluginless puts them in one mod, built to work
together.

Everything is reachable two ways: by command, and through a GUI. The GUIs open from the **ROTH
terminal**, a handheld item, or from the matching command, and both routes show the same screen.

## Status

Early. The foundation is in place and the modules are built on top of it one at a time.

| Module | State |
| --- | --- |
| Money ledger, `/balance`, `/pay`, `/baltop`, `/eco` | Working |
| ROTH terminal and `/roth` (home screen, shows your balance) | Working |
| Ranks and per-rank command permissions | Planned |
| Bank (interest, loans, account tiers) | Planned |
| Auction house and buy/sell orders | Planned |
| Player and server shops | Planned |
| Companies and a stock market | Planned |
| Essentials-style commands (homes, warps, kits, `/tpa`) and cosmetics | Planned |
| WarForge Remaintained compat (faction treasury, taxes, territory shops) | Planned |

## Commands

| Command | Who | What it does |
| --- | --- | --- |
| `/balance [player]`, `/bal` | Everyone | Shows your balance, or another player's |
| `/pay <player> <amount>` | Everyone | Sends money to a player, online or not |
| `/baltop` | Everyone | Lists the ten richest players |
| `/roth` | Everyone | Opens the terminal screen without the item |
| `/pluginless`, `/pluginless help` | Everyone | Lists the Pluginless commands you are allowed to use |
| `/eco set\|add\|remove <player> <amount>` | Operators | Changes a balance directly |

A player gets an account the first time they join. Commands that name a player refuse anyone who has
never joined, so money cannot be sent to an account nobody owns.

## Configuration

`config/pluginless.toml`, the same file name on every supported version:

| Option | Default | Meaning |
| --- | --- | --- |
| `currencySymbol` | `$` | Shown in front of every amount |
| `startBalance` | `100.0` | Balance of a player's new account |

Balances are stored in the world save.

## Supported versions

NeoForge only. [LDLib2](https://github.com/Low-Drag-MC/LDLib2), which the GUIs are built with, is
required and has no Fabric build.

| Minecraft | NeoForge | LDLib2 | Java |
| --- | --- | --- | --- |
| 1.21.1 | 21.1.248 | 2.2.26 | 21 |
| 26.1 | 26.1.2.116 | 26.1.2.42 | 25 |
| 26.2 | 26.2.0.90 | 26.2.2.42 | 25 |
| 26.3 | 26.3.0.76 | 26.3.2.41 | 25 |

## Building

All versions build from one source tree with [Stonecutter](https://stonecutter.kikugie.dev/).
1.21.1 is the source of truth; code that differs on newer versions sits behind `//? if >=26.1`
comments.

```sh
./gradlew chiseledBuild          # every version; jars land in versions/<mc>/build/libs/
./gradlew ":26.1:build"          # one version
./gradlew ":1.21.1:runClient"    # a dev client
```

Gradle itself needs a JDK 21 or newer to run; the Java 25 toolchain for the 26.x versions is found
or downloaded automatically.

To add a Minecraft version, list it in `settings.gradle.kts` and add
`versions/<mc>/gradle.properties` with its NeoForge and LDLib2 versions.

## Testing

```sh
./gradlew chiseledBuild          # also runs the unit tests for the money rules
tools/selftest.py                # in-game checks on every version
tools/selftest.py 26.1 26.3      # or only some
```

`tools/selftest.py` boots a real dev server per version on a fresh world and runs the checks in
`SelfTest.java`, which drive the real commands and items with fake players. It prints PASS or FAIL
for each check and exits non-zero if any failed. New features add their checks there. It verifies
server behaviour only, not how a screen looks.

## License

LGPL-3.0-or-later. See `LICENSE.LESSER` and `LICENSE`.
