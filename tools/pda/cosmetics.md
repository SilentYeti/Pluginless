# Cosmetics page verification

Build `:1.21.1:test :1.21.1:jar`, then run:

```sh
python3 tools/pda/run.py --skip-build --apps pluginless:pluginless --wflib /path/to/WFLib-main --script tools/pda/cosmetics-input.json
```

The script clicks the Cosmetics home tile, selects a colour, types a custom hex colour and nickname through the real prompt, selects a gradient preset, and captures all five tabs. The default harness player has no cosmetic grants: apply buttons stay locked while draft previews still work. Inspect the screenshots for the expected custom colour, gradient and `SnowYeti` nickname.

`SelfTest.cosmeticsPda` verifies real server mutations and rejection paths: stale permissions, clearing a locked nickname, rank command denial, shared cooldowns, malformed and injected intents, selected inventory slots, occupied helmets, and server-priced skull delivery. Run `tools/selftest.py 1.21.1` for those checks with the rest of the feature suite. Only one instance of that runner should use its fixed port at a time.

The PDA sends inventory indices 0–35. The inventory grid labels them 1–36 for players; `/hat <slot>` accepts the actual 0–35 index. Existing `/hat` still uses the held item. The PDA never swaps an occupied helmet.
