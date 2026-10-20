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
| ROTH terminal and `/roth` (links every screen below) | Working |
| Ranks: per-rank command permissions, inheritance, name prefixes | Working |
| Rank GUI, colours, chat formats, perks, kits, rewards and timed ranks | Working |
| Bank: one balance, transfers/history, payment requests, server loans and debt | Working |
| Income: paychecks, daily reward, mob rewards, `/sell`; transfer tax and `/taxes` | Working |
| Auction house: fixed-price listings, collection box, fees, watch list | Working |
| Item market: buy and sell orders for bulk items, instant buy/sell, price chart | Working |
| Companies: roles, company account, payroll | Working |
| Shops: company-owned menu shops, admin shops, directory | Working |
| Admin stocks: configurable price behaviour, trading against the server, admin nudges | Working |
| Company shares: voting shares, takeovers, dividends, bankruptcy | Working |
| Margin (borrowing to invest) | Done for admin stocks; company shares and short selling planned |
| Court system | Planned |
| Homes, spawn, warps, teleport requests, random teleport, back and cosmetics | Working |
| Faction compat: claim-group interface and an Open Parties and Claims adapter (read-only: parties, members, chunk counts; optional, off without the mod) | Working |
| Faction compat: WarForge Remaintained (1.21.1 only), FTB Chunks, chunk tax, faction treasury | Planned, no code yet |

## Commands

| Command | Who | What it does |
| --- | --- | --- |
| `/balance [player]`, `/bal` | Everyone | Shows your balance, or another player's |
| `/pay <player> <amount>` | Everyone | Sends money to a player, online or not |
| `/baltop` | Everyone | Lists the ten richest players |
| `/roth` | Everyone | Opens the terminal screen without the item |
| `/pluginless`, `/pluginless help` | Everyone | Lists the Pluginless commands you are allowed to use |
| `/rank`, `/rank list` | Everyone | Shows your rank, or the names of all ranks |
| `/rank set <player> <rank> [duration]` | Operators | Gives a player a permanent rank or a timed rank, such as `30d` |
| `/rank gui` | Everyone | Opens ranks and kits in the ROTH terminal |
| `/rank rewards` | Everyone | Shows your rank's playtime rewards and claim counts |
| `/rank promote <player> <track>` | Operators, or the player buying their own promotion | Steps up a promotion track |
| `/rank demote <player> <track>` | Operators | Steps down a promotion track |
| `/kit [name]` | Rank permission | Lists available kits or claims a kit |
| `/rank reload` | Operators | Re-reads `ranks.json` without a restart |
| `/eco set\|add\|remove <player> <amount>` | Operators | Changes a balance directly |
| `/balance gui` | Everyone | Opens the balance screen |
| `/bank`, `/bank balance`, `/bank history` | Everyone | Opens banking, or prints balance/debt and the ten latest transactions |
| `/bank request <player> <amount>`, `/bank accept`, `/bank decline` | Everyone | Requests payment; acceptance pays the stored amount once |
| `/bank borrow <amount>`, `/bank repay <amount>` | Everyone | Borrows server funds within the rank limit, or repays debt |
| `/qol` | Everyone | Opens homes and teleports in the terminal |
| `/spawn`, `/setspawn` | Everyone / staff | Teleports to spawn / saves spawn at your position |
| `/home [name]`, `/sethome [name]`, `/delhome [name]`, `/homes` | Everyone | Uses, saves, deletes or lists homes; the default name is `home` |
| `/tpa <player>`, `/tpahere <player>`, `/tpaccept`, `/tpdeny` | Everyone | Requests a teleport to an online player, or asks them to come to you; accepts/denies it |
| `/rtp`, `/back` | Everyone | Finds a safe random landing / returns to the last death or teleport departure |
| `/warp [name]`, `/setwarp <name>`, `/delwarp <name>` | Everyone / staff | Lists or uses public warps / saves or deletes one |
| `/cosmetics` | Everyone | Opens name previews and cosmetic choices |
| `/nick <name>`, `/nick off`, `/realname <nick>` | Rank perk / everyone for lookup | Sets or clears a nickname; looks up its real owner |
| `/namecolor <colour>`, `/namecolor off` | Rank perk | Sets a name colour, e.g. `#ff8800`, `&#ff8800` or `&c`, or restores the rank colour |
| `/namegradient <from> <to>`, `/namegradient off` | Rank perk | Sets a two-colour name gradient, e.g. `#ff0000 #0000ff`, or clears it |
| `/skull <player>`, `/hat` | Rank perk | Gives a player head / wears one held item in an empty helmet slot |
| `/nametitle [name]`, `/nametitle off` | Rank perk for choosing | Lists configured titles or chooses/clears one |
| `/cosmetic grant\|revoke <player> <cosmetic>` | Operators and the console | Unlocks one cosmetic (`nick`, `namecolor`, `namegradient`, `skull`, `hat`, `title.<name>`) for one player, whatever their rank. Meant for website stores; works before the player's first join |
| `/sell`, `/worth` | Everyone | Sells the held stack to the server at its fixed price / shows that price |
| `/taxes`, `/taxes gui` | Everyone | Shows what you have paid to the server, by reason |
| `/ah` | Everyone | Opens the auction house screen |
| `/ah list [search]`, `/ah view <id>` | Everyone | Lists listings / shows one, including what a shulker box or bundle holds |
| `/ah sell <price>` | Everyone | Lists the held stack; charges the listing fee |
| `/ah auction <start price>`, `/ah bid <id> <amount>` | Everyone | Starts a bidding auction with the held stack / bids on one; bids are held in escrow and the outbid bidder is refunded at once |
| `/ah buy <id>`, `/ah cancel <id>`, `/ah collect` | Everyone | Buys a listing, cancels your own, or moves boxed items to your inventory |
| `/ah watch <item id>`, `/ah unwatch <item id>` | Everyone | Tells you when that item is listed |
| `/ah remove <id>`, `/ah history <player>` | Operators | Returns any listing to its seller / shows a player's auction money movements |
| `/market` | Everyone | Opens the item market screen |
| `/market book <item>` | Everyone | Shows the best buy and sell orders, the last price and a price chart |
| `/market buy\|sell <item> <quantity> <price each>` | Everyone | Places an order; it fills at once against any matching order and the rest waits |
| `/market instabuy\|instasell <item> <quantity>` | Everyone | Fills the whole quantity now against the best waiting orders, or does nothing |
| `/market orders`, `/market cancel <id>`, `/market collect` | Everyone | Lists your orders, cancels one, or moves bought and returned items to your inventory |
| `/company`, `/company list`, `/company info <company>` | Everyone | Opens the company screen, lists companies, or shows one with its members, account and recent money movements |
| `/company create <name>` | Everyone without debt | Founds a company for the fee |
| `/company deposit\|withdraw <company> <amount>` | Anyone / owner and managers | Moves money into or out of the company account |
| `/company hire <company> <player> <employee\|manager>`, `/company fire <company> <player>`, `/company leave <company>` | Owner and managers; only the owner appoints or removes a manager | Manages members |
| `/company wage <company> <player> <amount>` | Owner and managers | Sets what a member is paid each payday; 0 removes it |
| `/company describe <company> <text>`, `/company dissolve <company>` | Owner and managers / owner | Sets the description; dissolving pays the account to the owner and returns shop stock |
| `/shops [search]` | Everyone | Opens the shop directory, or lists offers matching an item, shop or company, cheapest first |
| `/shop <name>` | Everyone | Opens that shop |
| `/shop buy\|sell <shop> <item> <quantity>` | Everyone | Buys from the shop, or sells to it |
| `/shop open <company> <name>`, `/shop close <shop>` | Owner and managers | Opens or closes a company shop |
| `/shop offer <shop> <item> <sell price> <buy price> <buy quantity>` | Owner and managers | Sets what the shop sells and buys an item for; 0 means it does not |
| `/shop stock\|unstock <shop> <item> <quantity>` | Company staff | Moves items between your inventory and the shop |
| `/shop describe <shop> <text>`, `/shop icon <shop> <item>` | Owner and managers | Sets the shop page |
| `/shop admin <name>` | Operators | Creates a server shop with unlimited stock |
| `/stock`, `/stock list`, `/stock info <ticker>`, `/stock portfolio` | Everyone | Opens the stock screen, or shows prices with a chart, one stock, or your shares |
| `/stock buy\|sell <ticker> <shares>` | Everyone | Trades with the server at the current price |
| `/margin`, `/margin borrow\|repay <amount>` | Everyone | Server loan against your stocks, up to `marginMultiple` (rank perk `margin_multiple`), interest `marginDailyInterest` per day (`margin_interest`); warned below `marginWarnRatio` x loan, everything sold below `marginLiquidateRatio` x loan, any shortfall becomes bank debt |
| `/stock nudge <ticker> <percent> [duration]` | Operators | Pushes a price up or down on the next update, or spread over a duration such as `2h` |
| `/stock trend <ticker> <up\|down> <percent per day> <duration>` | Operators | Pulls a price steadily for a while |
| `/stock shock <ticker> <percent>`, `/stock set <ticker> <price>` | Operators | Moves a price at once (with the stock's headline), or sets it |
| `/stock freeze\|unfreeze <ticker>`, `/stock reload` | Operators | Halts or resumes trading and price movement; re-reads `stocks.json` |
| `/shares`, `/shares portfolio` | Everyone | Opens the share screen, or lists your shares, orders and dividends received |
| `/shares buy\|sell <company> <quantity> <price each>`, `/shares cancel <id>` | Everyone | Places or cancels a share order; orders match like the item market |
| `/shares issue <company> <count> <keep> <price>` | Company owner | Creates shares: the owner keeps some, the company offers the rest and gets the proceeds |
| `/shares dividend <company> <amount>` | Owner and managers | Splits that amount of the company account among shareholders |
| `/shares takeover <company>` | Anyone holding over half the shares | Becomes the owner |

Named targets work before first join, including console grants from website store integrations.
Selectors still require exactly one online player. Names are resolved through the server profile cache;
a lookup with no profile uses the lower-cased name's offline UUID. Provisional balances, ranks and
claims follow the player to their joining UUID. Opening an existing account never resets its balance.

`@server` names the server account in `/balance`, `/pay` and `/eco`; it starts at zero and is excluded
from `/baltop`. Fees collected by shared money operations go there, and server-funded payouts draw
from it. All money changes, including starting balances and operator changes, record actor, source,
destination, cents, reason and timestamp in the world save. Failed operations do not record a transfer.

## Configuration

`config/pluginless.toml`, the same file name on every supported version:

| Option | Default | Meaning |
| --- | --- | --- |
| `currencySymbol` | `$` | Shown in front of every amount |
| `startBalance` | `100.0` | Balance of a player's new account |
| `teleportWarmupSeconds` | `3` | Stand-still time before a teleport; zero disables it |
| `teleportRequestSeconds` | `60` | Time to accept or deny a teleport request |
| `rtpRadius` | `5000` | Maximum random-teleport radius in blocks, around world origin |
| `rtpAttempts` | `16` | Maximum candidate columns searched per random teleport |
| `nickMaxLength` | `24` | Nickname limit, from 1 to 32 characters |
| `skullPrice` | `0.0` | Optional player-head price in currency units, paid to the server |
| `paymentRequestSeconds` | `120` | Time to accept or decline a payment request |
| `loanLimit` | `1000.0` | Default borrowing limit in currency units |
| `loanDailyInterest` | `1.0` | Daily percentage on unpaid loan principal; zero disables loan interest |
| `serverAccountUnlimited` | `true` | When the server account cannot cover a payout (income, loans), issue new money instead of refusing |
| `paycheckMinutes`, `paycheckAmount` | `30`, `50.0` | Paycheck interval of online time and amount; zero minutes disables it |
| `dailyReward`, `dailyStreakBonus`, `dailyStreakMaxDays` | `25.0`, `5.0`, `7` | First-login-of-the-day reward, plus the bonus per consecutive day up to the cap |
| `mobRewardHourlyCap` | `100` | Paid mob kills per player per hour |
| `transferTaxPercent` | `0.0` | Charged to the sender on top of a `/pay` amount |
| `auctionSalesTaxPercent` | `2.0` | Kept from the seller's proceeds |
| `auctionMinRaisePercent` | `5.0` | How much a bid must beat the highest bid by (at least one cent); a bid in the last two minutes moves the end to two minutes from then |
| `auctionListingHours`, `auctionGraceSeconds` | `48`, `20` | Listing lifetime, and the time after listing before anyone can buy |
| `auctionSlots` | `14` | Listings per player; the rank perk `auction_slots` overrides it |
| `auctionMinPrice`, `auctionMaxPrice` | `0.01`, `1e9` | Allowed listing prices |
| `marketFeePercent` | `1.25` | Kept from the seller on every market trade |
| `marketOrders` | `20` | Open orders per player; the rank perk `market_orders` overrides it |
| `companyFee`, `companiesPerPlayer` | `500.0`, `1` | Cost of founding a company, and how many one player may own (rank perk `companies`) |
| `payrollHours` | `24` | Time between paydays; zero disables payroll |
| `shopsPerCompany`, `shopStorage` | `3`, `3456` | Shops per company, and items one shop can hold (owner's rank perk `shop_storage`) |
| `shopSalesTaxPercent` | `2.0` | Paid to the server on each sale by a company shop |
| `bankruptcyOnUnpaidWages` | `true` | A company that cannot pay a wage on payday goes bankrupt |
| `sharesFounderMinPercent`, `sharesLockupHours`, `sharesPriceMoveLimitPercent` | `0` | Optional investor protections, all off: a floor on the owner's stake, a wait before money raised can be withdrawn, and a limit on how far an order may be from the last trade |

Balances, rank assignments, homes, spawn, warps, back locations, name cosmetics and debt are stored in the world save.

`config/pluginless/features.json` is created with these defaults:

```json
{
  "commandCosts": {},
  "rtpDimensions": ["minecraft:overworld"],
  "blockedWords": ["admin", "moderator"],
  "titles": { "builder": "&e[Builder]" }
}
```

`commandCosts` maps QOL command roots to prices in currency units, for example `"rtp": 50` or
`"home": 1`. Supported keys are `spawn`, `setspawn`, `home`, `sethome`, `delhome`, `homes`, `tpa`,
`tpahere`, `tpaccept`, `tpdeny`, `rtp`, `back`, `warp`, `setwarp`, `delwarp`. Successful changes and
`/homes` collect their configured fee immediately. Teleport fees are collected at completion;
movement, damage, missing targets, unsafe landings or insufficient funds cost nothing. A requester's
`tpa`/`tpahere` fee and the accepting player's `tpaccept` fee are both collected at completion.
Listing warps is free. Fees do not buy cosmetic unlocks.

`rtpDimensions` lists dimension registry IDs allowed for `/rtp`; it searches the current dimension.
`blockedWords` are case-insensitive nickname substrings. `titles` maps selectable IDs to formatted
labels. These collection settings take effect on server startup. Broken files are kept untouched,
never applied, and reported in the server log; the last valid settings stay active.

## Ranks

Ranks live in `config/pluginless/ranks.json`, created on first start:

```json
{
  "defaultRank": "default",
  "ranks": {
    "default": { "prefix": "", "allow": [], "deny": [] },
    "moderator": {
      "inherits": "default",
      "prefix": "&9[Mod] ",
      "allow": ["kick", "ban", "pardon", "tp", "teleport"],
      "deny": []
    },
    "admin": { "inherits": "moderator", "prefix": "&c[Admin] ", "allow": ["*"], "deny": [] }
  }
}
```

- **`allow`** lets a rank use commands the game would refuse, such as operator commands.
- **`deny`** takes away commands the game would allow.
- Anything a rank has no rule for behaves as it does without Pluginless.
- This works for every command on the server, from Minecraft or from any mod.

A command is named by its words without arguments: `pay`, `eco set`, `team modify`. An entry can also
end in `*` (`eco*`), or be `*` for everything. The most specific entry wins, and a deny beats an allow
of the same specificity. So `"deny": ["*"], "allow": ["pay", "balance"]` is a whitelist, and
`"allow": ["eco"], "deny": ["eco set"]` gives `/eco` without `/eco set`.

A rank with `inherits` falls back to that rank wherever it has no rule of its own. Operators are never
restricted by ranks, so a mistake in the file cannot lock everyone out. If the file is broken,
`/rank reload` says what is wrong and the previous ranks stay in force.

Rank configuration also supports:

| Key | Default | Meaning |
| --- | --- | --- |
| `cooldowns` | `{}` | Command words to cooldown seconds; the most specific subcommand wins |
| `kits` | `{}` | Named item/money grants with their own cooldown |
| `tracks` | `{}` | Ordered ranks with optional playtime thresholds and promotion prices |
| Rank `nameColor`, `suffix` | `""` | Name colour and text after the name |
| Rank `chatFormat` | absent | Custom chat using `{prefix}`, `{name}`, `{suffix}`, `{message}`; absent retains vanilla chat |
| Rank `messageColor` | `""` | The message colour; player messages keep their literal `&` codes |
| Rank `perks` | `{}` | Inherited numbers and flags, with child values overriding parents |
| Rank `rewards` | `[]` | Playtime grants, inherited and deduplicated by reward ID |

Prefixes and suffixes use `&c`, `&#ff8800`, legacy decoration codes and
`<gradient:#ff0000:#0000ff>text</gradient>`. `nameColor` accepts a colour code, `#rrggbb`,
or a gradient opening tag such as `<gradient:#ff0000:#0000ff>`.
Custom chat formats broadcast a server message so the complete bracket and name layout can change.

Perks currently consumed are `income_multiplier` (money rewards and kits, default 1),
`cooldown.<command>` (seconds, including spaces in subcommand names), `kit.<name>` (boolean
access), `cooldown.kit.<name>` (seconds), and `fee_multiplier` (paid promotions, default 1).
Round 2 also reads `homes` (number, default 1), `teleport_no_warmup` (flag), `loan_limit` (currency
units, defaults to `loanLimit`), and the cosmetic flags `nick`, `namecolor`, `namegradient`, `skull`,
`hat` and `title.<id>`. Cosmetic flags default to false and are inherited; operators also need the
perk. Setting a flag to false on a child rank locks it again. Clearing a saved cosmetic is always
allowed. Other numbers and flags remain available through the inherited `number`, `flag` and `perks`
lookups for later modules. Kits can also be allowed or denied using `kit <name>` rank rules.
Operators bypass command cooldowns; kit claim cooldowns still apply. Command cooldowns are retained
for the server session, while kit claims are saved across restarts. A shorter rank cooldown applies
immediately to the time since the last use.

Each kit has `cooldown` in seconds (default 86400), `money` in currency units (default 0), and
`items` mapping registry IDs to positive counts (default `{}`). A reward has the same `money` and
`items`, plus a stable `id`, positive `hours`, and `repeat` (default false). Reward IDs identify claims
across ranks, so promoting through inherited rewards does not grant them twice. Online time is saved;
no AFK tracker exists yet, so it currently includes AFK time. A reward with a full inventory or an
overflowing balance remains unclaimed and retries. No items are dropped; failed kits leave their cooldown untouched.

For example, entries inside the corresponding maps:

```json
{
  "cooldowns": { "pay": 10 },
  "kits": {
    "starter": { "cooldown": 86400, "money": 0, "items": { "minecraft:bread": 8 } }
  },
  "tracks": {
    "main": {
      "ranks": ["default", "moderator", "admin"],
      "autoHours": { "moderator": 100 },
      "prices": { "moderator": 500 }
    }
  }
}
```

A rank grants the example kit with `"perks": { "kit.starter": true }`. Reward example:
`"rewards": [{ "id": "first_hour", "hours": 1, "repeat": false, "money": 10, "items": {} }]`.
Track `autoHours` maps destination ranks to total online hours; `prices` maps destination ranks to
currency-unit prices. Operators promote freely; other players can only buy their own configured next
rank. Promotion fees go to the server account and use the current rank's fee multiplier. Temporary
ranks do not auto-promote. Timed grants use wall-clock deadlines and expire even while offline or
after a restart. Replacing a timed grant keeps its original permanent rank as the reversion target;
a permanent grant or manual track step cancels the timer. Durations accept positive integer `s`, `m`,
`h`, `d` and `w` units.

The ROTH rank screen shows ranks, members, online time, command rules and inherited perks. It offers
grant, promotion and kit forms using the same commands. Operators can load a rank and save its allow
and deny lists or a numeric/boolean perk. Edits are validated and saved atomically; a broken file is
never applied or overwritten. All new persistent data uses the existing `Store` codec mechanism.

## Teleports and cosmetics

The terminal home page links the balance, bank, ranks/kits, cosmetics, auction house, item market, stocks, shares, companies, shops, taxes and homes/teleports screens.
All screen actions invoke the same rank-gated commands and shared cooldowns as chat. Player, home,
warp, palette and title pickers are server-side previous/next choices; text fields also accept new
home/warp names and custom hex colours. Permission and amount checks happen on the server.

`/setspawn`, `/setwarp` and `/delwarp` require operator permission unless ranks explicitly allow
those commands. Other QOL commands are available by default. Home and warp names use letters,
numbers, underscores or hyphens, up to 32 characters. Re-saving an existing home does not consume
another slot. `/spawn` defaults to the world's spawn until staff save a position. Every teleport uses
the same warm-up and safe-landing checks: solid support, enough standing space, world/build bounds,
no fluids or nearby vanilla damaging blocks. Mod-specific damage effects need additional hazard
checks before their dimensions should be allowed. A small coordinate tolerance ignores floating-point
jitter; turning your head is allowed. `/back` remembers Pluginless departures, death locations and
vanilla/NeoForge teleport or dimension-travel events. A now-unsafe back destination is refused.
Requests expire and cannot replace another unanswered request; they and pending warm-ups are cleared
on logout/restart. Random search performs bounded synchronous chunk loads, so a large radius can
stall a server tick. Nonstandard mod teleports that do not publish NeoForge events cannot be observed.

Nicknames are plain letters, numbers and underscores. They cannot use blocked words, another known
or online player's real name, or another saved nickname. A newly joining real-name owner clears any
conflicting nickname. Name colour, gradient and title choices persist, but losing the relevant rank
perk hides them. Names render through `Text`/`Formatting` and the existing display-name, tab and chat
hooks, including custom rank chat. Titles use `title.<id>`, for example `title.builder`. Particle
trails and money-purchased cosmetic unlocks are not implemented. `/hat` requires an empty helmet
slot, so it never removes another item or a binding-cursed helmet. `/skull` needs an empty inventory
slot and leaves texture resolution to Minecraft's unresolved name profile.

For example, a rank's `perks` can contain:

```json
{
  "homes": 5,
  "teleport_no_warmup": true,
  "loan_limit": 2500,
  "nick": true,
  "namecolor": true,
  "namegradient": true,
  "skull": true,
  "hat": true,
  "title.builder": true
}
```

Cooldowns remain in `ranks.json`, e.g. `"cooldowns": { "tpa": 30, "rtp": 60, "skull": 3600 }`;
the existing `cooldown.<command>` perks can shorten or remove them. The bank and QOL screens use
known-player pickers, and `/pay`, payment requests and player-head commands also resolve never-joined
names through the existing player helper.

## Bank and debt

There is one balance per player. The bank screen shows it alongside debt, borrowing limit and daily
loan rate; a player picker and amount field send money or request payment. It also shows the ten most
recent shared transactions and offers accept/decline, borrow and repay controls. A request stores the
requester's UUID and amount on the server; acceptance transfers that amount once. Requests may target
offline known/never-joined players, expire on the configured deadline, and are session-local.

Loans draw only from the existing server account: insufficient server funds fail without creating
money or debt. Any outstanding debt blocks further borrowing. `loan_limit` overrides the configured
limit, clamped to 0–1e15 currency units. Daily interest is simple, charged on unpaid loan principal
for each full 24-hour period, including offline time and restarts. The daily charge rounds up to cents;
catch-up uses the same daily rounding. Repayment pays accrued interest and other obligations before
reducing principal. The largest possible debt is `Long.MAX_VALUE` cents; interest saturates there.

Incoming transfers, issued deposits, kits, rewards and positive operator adjustments repay debt to
the server before any remainder becomes spendable. The original credit and its debt repayment both
appear in the shared log. Identity migration and returned fees are not income. Initial account funding
retains the existing starting-balance rule. Manual repayment uses the existing balance and is capped
at the debt. No wallet/bank split, savings interest or physical money exists.

Future modules call the server-thread API `Bank.debt(server, player)`,
`Bank.addDebt(server, player, cents, reason)` and `Bank.repay(server, player, cents)`. Adding an
obligation creates no money and does not accrue loan interest; only borrowed principal does.
Debt follows provisional accounts to the joining UUID. A merge shares the earlier daily clock.
Catch-up uses the current configured rate, so changing it also affects unaccrued offline periods. Liability entries (`debt.loan`, `debt.add.*`,
`debt.interest`) share the transaction log but describe obligations rather than balance transfers;
the bank screen marks them as debt entries. The log and recent-history filter remain in the world
save and scan the account history; large histories will need an indexed store.

## Income, taxes and the auction house

Income is multiplied by the rank perk `income_multiplier`; tax and fee rates by `fee_multiplier`. All income comes from the
server account and all taxes and fees go to it. `config/pluginless/features.json` holds the lists:

| Key | Meaning |
| --- | --- |
| `mobRewards` | Entity id to reward per kill |
| `sellPrices` | Item id to the price the server pays per item |
| `auctionFeeTiers` | Listing fee percent by price: the highest threshold the price reaches applies. Charged when listing, never refunded |
| `auctionCategories` | Category name to item-id fragments, for the category filter |
| `auctionBlockedItems` | Item ids that cannot be listed |
| `marketItems` | Item ids that trade on the item market |

Bought, cancelled, removed and expired items go to a collection box (`/ah collect`), never straight into an inventory. A
purchase is one step on the server thread, so a listing can only ever be sold, cancelled or expired once. Limits: paycheck
progress and the kill cap reset on restart and AFK time counts; `/ah list` shows the first 30 matches; price history keeps
the last 200 sales per item.

The item market trades plain items only: anything renamed, enchanted or damaged belongs on the auction house. A buy order's
money and a sell order's items are held until the order fills or is cancelled, so a matched order always pays out. Trades
happen at the price of the order that was already waiting.

## Companies and shops

Companies and shops exist only in menus and commands: nothing is placed in the world and they are unrelated to factions.
Only a company can open a shop, and shop income goes to the company account. Owners can do everything; managers can spend,
hire employees and run shops; employees can stock shops. Shops trade plain items only and hold their own stock. Bought and
returned items go to the same collection box as the item market (`/market collect`) when the inventory is full. A company
that cannot cover a wage on payday goes bankrupt unless `bankruptcyOnUnpaidWages` is off.

## Company shares

Shares are deliberately unprotected. A share is a vote: whoever holds more than half of the shares in players' hands can
take the company with `/shares takeover`, gaining its account, shops and payroll. The owner decides whether dividends are
ever paid, can withdraw everything investors paid in, and can issue more shares at any time. A bankrupt or dissolved
company's account is split among its shareholders and the shares cease to exist. `/company info` shows every company's
account, money movements and largest shareholders to anyone. Shares waiting in a sell order still vote; shares the company
has not sold yet do not.

## Admin stocks

`config/pluginless/stocks.json` defines invented stocks that players trade against the server. Per stock:

| Key | Meaning |
| --- | --- |
| `name`, `description` | Shown to players |
| `start`, `floor`, `ceiling` | Starting price and the bounds it can never leave |
| `volatility`, `updateSeconds` | Largest random move per update in percent, and how often the price updates |
| `drift` | Steady pull, percent per day (negative falls) |
| `target`, `pull` | Return to a level: the share of the gap to `target` closed per day; `target` 0 switches it off |
| `cycleAmplitude`, `cycleHours` | A rise and fall of that many percent over each period |
| `momentum` | Share of the previous move carried into the next, below 1 |
| `shockChance`, `shockSize` | Chance per day of a sudden move of that many percent, up or down |
| `pressure` | Percent the price moves per 100 shares players buy or sell; 0 makes it pattern-only |
| `spread` | Gap in percent between the buying and selling price |
| `holdingLimit` | Most shares one player may hold |
| `headline` | Broadcast on a shock; `{name}`, `{price}` and `{move}` are filled in; empty for silence |

`dailyPayoutCap` (top level) is the most the server pays to sellers in one day; 0 removes the cap. A trade moves the price
before it is priced, so buying and selling straight back always loses the spread and the pressure. Admin commands are written
to the server log and are not announced, except a shock's headline. Winning trades are paid by the server account, which
issues new money when `serverAccountUnlimited` is on.

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
