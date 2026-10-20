# Pluginless roadmap

What is built, what is not, and the plan for each feature. Every feature gets commands **and** a screen in the ROTH terminal; that rule is not repeated under each heading.

Items marked **Decide** are open questions; they are collected again at the bottom.

## Status

### Done

- [x] Project setup: NeoForge, LGPL-3.0, one source tree building for 1.21.1, 26.1, 26.2 and 26.3
- [x] Test harness (`tools/selftest.py`): boots a server per version and runs fake players through real commands
- [x] Money core: balances in cents, starting balance, currency symbol, overdraft and overflow protection
- [x] `/balance`, `/pay`, `/baltop`, `/eco set|add|remove`
- [x] ROTH terminal item, opening a screen that shows the live balance
- [x] `/pluginless` help listing
- [x] Ranks core: `ranks.json`, allow/deny for any command or subcommand, wildcards, inheritance, name prefixes, `/rank`, `/rank list`, `/rank set`, `/rank reload`
- [x] Shared groundwork: colour and gradient text, cooldowns on any command, rank perks, never-joined players, transaction log, server account, scheduler
- [x] QOL commands: `/spawn`, homes, `/tpa`, `/rtp`, `/back`, warps, teleport warm-up, command costs
- [x] Cosmetics: `/nick`, `/realname`, `/namecolor`, `/namegradient`, `/skull`, `/hat`, titles (`/nametitle`), cosmetics screen
- [x] Income and taxes: paychecks, daily login reward, mob-kill rewards, `/sell` and `/worth`, transfer tax, `/taxes`
- [x] Auction house: fixed-price listings, bidding auctions with escrow, collection box, listing fee and sales tax, grace period, search and filters, watch list, offline notices, price history
- [x] Company shares: voting shares on the order market, takeovers, owner-chosen dividends, issuing more, bankruptcy, optional protections
- [x] Admin stocks: `stocks.json` with randomness, drift, return to a level, cycles, momentum, shocks and player pressure; `/stock nudge`, `trend`, `shock`, `set`, `freeze`
- [x] Companies: founding fee, owner/manager/employee roles, company account, payroll, dissolving
- [x] Shops: company-owned menu shops with their own stock, sell and buy offers, admin shops, directory, sales tax
- [x] Item market: buy and sell orders with escrow, instant buy and sell, trading fee, order limit, price chart
- [x] Cosmetic grants per player from the console, for website stores
- [x] Bank: `/bank` screen with transfers and history, payment requests, server loans and debt
- [x] Ranks: colours and gradients, chat format, perks, playtime rewards, kits (`/kit`), timed ranks, promotion tracks, console grants for website stores, rank screen in the terminal

### Not done

- [ ] Ranks: several ranks per player (undecided), HTTP endpoint for websites (only if needed)
- [ ] Bank: wallet/bank split, interest on savings, physical money (all undecided)
- [ ] QOL commands: the wider Essentials set (undecided)
- [ ] Cosmetics: particle trails
- [ ] Income and taxes: jobs and wealth tax (undecided); shop and chunk taxes come with those modules
- [ ] Auction house: buy orders
- [ ] Borrowing to invest and short selling (loans and debt in the bank are done)
- [ ] Faction compat: Open Parties and Claims first, then WarForge (1.21.1 only) and FTB Chunks; no adapter code exists yet
- [ ] Court system
- [ ] ROTH terminal: a crafting recipe for the item (the home screen already links every built module)

## Shared groundwork

Small pieces several features need. Building them once first keeps each module short.

- [x] **Text formatting**: one function turning `&c`, `&#ff8800` and `<gradient:#ff0000:#0000ff>text</gradient>` into coloured text. Used by rank prefixes, nicknames, name colours and shop signs.
- [x] **Cooldowns**: one config map, `"cooldowns": { "tpa": 30, "skull": 3600 }`, applied at the same point where ranks already gate commands. This puts a cooldown on *any* command from any mod, not only ours, with no per-command code.
- [x] **Rank perks**: a free-form `perks` map on each rank (numbers and flags) that modules read, e.g. `"homes": 5`, `"auction_slots": 10`, `"income_multiplier": 1.5`, `"cooldown.tpa": 5`.
- [x] **Offline players**: `/pay`, `/rank set` and `/eco` accept players who have never joined, so website rank grants work before first join.
- [x] **Transaction log**: every money movement recorded with who, whom, amount and reason. Feeds the bank history screen, tax reports and abuse investigation.
- [x] **Server account**: a balance that belongs to nobody, where taxes and fees go and admin shops pay from. **Decide**: collected money goes there, or is simply destroyed.
- [x] **Scheduler**: one place for things that happen on a timer (paychecks, tax collection, auction expiry, timed ranks, playtime rewards).

## Ranks

Built: command access, inheritance, prefixes. Remaining:

- [x] **Colours and gradients** for the prefix and the name itself, through the shared text formatting. A rank sets `prefix`, `nameColor` and optionally `suffix`.
- [x] **Chat format** per rank (colour of the message text, bracket style).
- [x] **Perks** read from the `perks` map:
  - income multiplier on paychecks and rewards
  - lower or no cooldowns, no teleport warm-up
  - limits: homes, auction listings, shops, companies
  - lower tax and fee rates
  - which cosmetics are unlocked (nick, name colour, gradient)
- [x] **Playtime rewards**: "after N hours of play, give items or money", once or repeating, per rank. Counts time online; **Decide** whether AFK time counts.
- [x] **Kits**: `/kit <name>` with its own cooldown, gated by rank. The "free items" perk and kits are the same mechanism.
- [x] **Timed ranks**: `/rank set <player> <rank> 30d`, reverting to the previous rank on expiry.
- [x] **Promotion tracks**: an ordered list of ranks with `/rank promote` and `/rank demote`; optional auto-promotion on playtime or by paying.
- [ ] **Several ranks at once**: today a player has one rank. A donor rank and a staff rank on the same player needs either two slots or a list with weights. **Decide**.
- [x] **Website hook**: how a store tells the server to grant a rank. Two routes:
  1. *Console commands* — store plugins such as Tebex and CraftingStore already work by running `rank set <player> <rank>` on the console. This works today, including for players who have never joined.
  2. *HTTP endpoint* inside the mod — `POST /rank` with a secret key, for a custom website. More to build and secure.

  Plan: route 1 first, route 2 only if your website cannot run console commands. **Decide**.
- [x] **Rank GUI**: view ranks and their members; operators edit allow/deny lists and perks without touching the file.

## Bank

A screen for managing your money and sending it to others.

- [x] Bank screen: balance, a "send money" form (player picker + amount), recent transactions
- [x] `/bank` opens it; `/pay` stays as the command equivalent
- [x] Transaction history, from the shared log
- [x] Payment requests: ask another player for an amount, they accept or decline
- [ ] **Decide — wallet and bank split**: one balance (simple, what exists today), or cash on hand plus a bank balance, where cash can be lost on death and the bank is safe. The split makes banks matter and creates risk, but adds a deposit/withdraw step to everything.
- [ ] **Decide — interest** on bank balances. It prints money, so it needs a cap or a funding source such as tax revenue.
- [ ] **Decide — physical money**: coin/note items that can be withdrawn, traded by hand, dropped and stolen.
- [x] Loans and debt: required by margin trading and short selling (see Market and shares). A debt is repaid from income before anything else and blocks new borrowing.

## QOL commands

All get a configurable cooldown through the shared cooldown map, and rank perks can shorten it.

- [x] `/spawn`, `/setspawn`
- [x] `/home`, `/sethome`, `/delhome`, `/homes`; home limit per rank
- [x] `/tpa`, `/tpahere`, `/tpaccept`, `/tpdeny`; requests expire
- [x] `/rtp` with configurable radius, per-dimension allow list, safe-landing check
- [x] `/back` to the last death or teleport point
- [x] `/warp`, `/setwarp`, `/delwarp`
- [x] Teleport warm-up: stand still N seconds, cancelled by moving or taking damage
- [x] Optional money cost per command (e.g. `/rtp` costs $50), a useful money sink
- [ ] **Decide — how far past the four you listed**: `/msg` and `/r`, `/afk`, `/seen`, `/enderchest`, `/workbench`, `/trash`, `/heal`, `/feed`, `/fly`, `/repair`, `/invsee`, `/vanish`, mutes and jails. Each is small; together they are what "replaces Essentials" means.

## Cosmetics

- [x] `/nick <name>` and `/nick off`; length limit, blocked words, and no impersonating another player's real name. `/realname <nick>` to look up who is behind one.
- [x] `/namecolor <colour>` and `/namegradient <from> <to>`, unlocked per rank
- [x] `/skull <player>` giving that player's head, with a configurable cooldown and optional price
- [x] `/hat` to wear the held item
- [x] Titles/tags: unlockable labels shown next to the name, chosen from a list
- [x] Cosmetics screen: preview and pick name colour, gradient and title
- [x] Unlocked by rank perk
- [x] Unlocked per player from a website: a console command, `cosmetic grant <player> <cosmetic>` and `cosmetic revoke`, which a store runs the same way it grants ranks. Works for players who have never joined. Cosmetics are not sold for in-game money.
- [ ] Particle trails and similar need client-side work; later.

## Income and taxes

Money needs ways in and ways out, or prices only ever rise.

**Ways in**

- [x] Paycheck every N minutes of play, scaled by rank multiplier
- [x] Daily login reward, with an optional streak bonus
- [x] Mob-kill rewards, configurable per mob, with an anti-farm limit
- [x] Selling items to the server at fixed prices (`/sell`, `/worth`), the admin-shop side of Shops
- [ ] **Decide — jobs** (miner, farmer, hunter paying per action). Popular on plugin servers, but a large feature of its own.

**Ways out**

- [x] Transfer tax on `/pay` and bank transfers
- [x] Auction listing fee and sales tax
- [x] Shop sales tax (`shopSalesTaxPercent`)
- [ ] Claimed-chunk tax (see Faction compat)
- [x] Command costs (QOL commands)
- [ ] **Decide — wealth tax**: a small percentage of balances above a threshold, per day. Stops hoarding; unpopular.

Every rate is configurable, can be zero, and can be lowered by rank perk. A `/taxes` screen shows a player what they paid and why.

## Auction house

- [x] `/ah` opens the listings; `/ah sell <price>` lists the held item
- [x] Fixed-price listings
- [x] Bidding auctions (`/ah auction`, `/ah bid`)
- [x] Listings expire after a configurable time; unsold items go to a collection box, not straight to the inventory
- [x] Sale proceeds paid even when the seller is offline
- [x] Search, category filter, sort by price or time
- [x] Listing limit per rank, minimum and maximum price, blocked-items list
- [x] Listing fee and sales tax
- [x] Price history per item, which later feeds market charts
- [ ] Buy orders ("I will pay $X for 64 iron"): later

Taken from how existing plugins and mods do it (researched 2026-10-11):

- [x] Short grace period after listing (Hypixel uses 20 seconds) before anyone can buy, so a mistyped price can be cancelled
- [x] Bidding rules: minimum raise as a percentage of the current bid, and a bid in the last two minutes extends the timer to two minutes
- [x] Fee charged at listing time and not refunded, scaled by price tier; it discourages absurd prices and spam relisting
- [x] Preview the contents of a shulker box or bundle before buying
- [x] Watch list: get notified when an item you are looking for is listed (Fadah)
- [x] Message on next login for anything sold or expired while offline
- [x] Admin tools: remove a listing, view a player's sales history
- [x] **Sale safety**: a purchase is one indivisible step on the server thread — check the listing still exists, take the money, remove the listing, hand over the item. The duplication bugs other plugins had to patch all came from these steps being separable (an expiry and a purchase landing together, or two claims of the same item).
- [ ] **Decide — a second, instant market for bulk goods**: Hypixel splits trade in two. The auction house is for unique items; the Bazaar is for stackable commodities, with buy orders, sell offers and instant buy/sell against the best order. Settled: yes, see Market and shares.

## Companies

Companies exist only in menus and commands. They have no place in the world and no link to factions, claims or territory: a company and a faction are unrelated things, and a player can belong to both.

- [x] Create a company for a fee; name, description, owner
- [x] Roles: owner, manager, employee, each with set permissions (spend, hire, manage shops)
- [x] Company account, separate from members' balances, with its own history
- [x] Payroll: wages paid to employees on a timer from the company account
- [x] Company screen: members, account, shops (shares are added with the share market)
- [x] `/company` commands for everything the screen does
- [x] Dissolving: the account goes to the owner and shop stock is returned (shares are handled with the share market)
- [x] Limit on companies per player, per rank
- [ ] **Decide — what a company is for** beyond a shared account: owning shops (planned), issuing shares (planned), contracts between companies.

## Shops

Shops are menus, not places: there are no chests, signs or blocks in the world. Only companies can open shops: every shop belongs to a company, and its income goes to the company account. A solo player who wants a shop founds a one-person company.

- [x] Shop directory in the terminal and `/shops`: browse all shops, search by item or owner, sort by price
- [x] `/shop <name>` opens one shop directly
- [x] **Stock held by the shop itself**: company staff with the shop permission move items from their inventory into the shop's storage through the menu, and take them back the same way
- [x] Sell offers (shop sells to players) and buy offers (shop buys from players, paid from the company account, up to a set quantity)
- [x] Out-of-stock and storage-full handling; storage size limit from the owner's rank
- [x] Purchases while the buyer's inventory is full go to a collection box. It is the item market's box (`/market collect`), not the auction house's, because both hold plain items by count.
- [x] Shop page: name, description, icon item (the icon is shown as an item id for now, not drawn)
- [x] Admin shops with unlimited stock, for server-set prices
- [x] Sales tax; shop limit per company
- [x] Sales log on the company screen

## Market and shares

One order-matching system, used twice: first for bulk items, later for company shares.

### Item market (built first)

The instant market for stackable goods, next to the auction house, which stays the place for unique items.

- [x] Buy orders and sell offers at a chosen price per unit, matched automatically
- [x] Instant buy and instant sell against the best existing order
- [x] Sell offers hold the items and buy orders hold the money until filled or cancelled, so an order can never fail to pay out
- [x] Filled orders are collected from the market screen, including while the player was offline
- [x] Which items can be traded is a configurable list; items with custom data (enchanted, renamed) stay on the auction house
- [x] Trading fee, order limit per rank
- [x] Price history and a chart per item. LDLib2 has no chart widget, so the chart is drawn as a line of block characters in text, which works on every version.

### Company shares (built after companies and shops)

Deliberately cut-throat: shares carry real control and nothing protects investors from a dishonest owner or an owner from a hostile buyer. Scams, takeovers and bankruptcies are part of the game. Each rule below is a server setting, so an admin who wants a tamer market can switch protections on.

- [x] Owner sells shares once the company exists: share count, starting price, how many the owner keeps. Proceeds go to the company account.
- [x] **Shares are votes**: whoever holds more than half the shares can take the company — replace the owner, and with it gain the account, the shops and the payroll. Sell too much of your own company and an enemy can buy it out from under you.
- [x] **Dividends are the owner's choice**: paid by hand from the company account, any amount, any time, or never. Nothing checks where the money came from, so paying old investors with new investors' money (a Ponzi scheme) works until the money runs out.
- [x] **Owner can withdraw** from the company account freely, including everything raised from selling shares, and walk away
- [x] **Owner can issue more shares** at any time, shrinking everyone else's stake. It also shrinks their own, which can cost them the majority.
- [x] **Bankruptcy**: a company that cannot cover wages or other dues goes bankrupt; shops close, shares become worthless, whatever is left in the account is split among shareholders
- [x] Shares trade on the same order system as items, with no price-move limit
- [x] Public company page: account balance, money movements including dividends, and largest shareholders, always visible. **Decide** whether owners may hide these.
- [x] Portfolio screen: holdings, dividends received, price chart
- [x] Server-wide announcements for takeovers and bankruptcies
- [x] Optional protections, all off by default: minimum share the founder must keep, lock-up period on money raised, daily price-move limit (built as a limit against the last trade price). Trading with yourself is always blocked by the order matching, so it is not a setting.

### Admin stocks

Invented stocks defined by admins, independent of companies. Players buy from and sell to the server at the current price, so they always trade instantly and the server needs no active companies for the market to be lively.

Defined in `config/pluginless/stocks.json`, one entry per stock:

- [x] Ticker, display name, description, starting price, floor and ceiling
- [x] **Randomness**: how far the price can move per update, and how often it updates
- [x] **Patterns**, combinable per stock:
  - *Drift*: a steady pull up or down over time
  - *Return to a level*: wanders but is pulled back towards a target price
  - *Cycles*: rises and falls over a set period (daily, weekly)
  - *Momentum*: recent moves tend to continue, giving runs and reversals
  - *Shocks*: a chance per day of a sudden jump or crash of a set size
- [x] **Player pressure**: how much buying pushes the price up and selling pushes it down; zero makes the stock purely pattern-driven
- [x] Buy/sell spread or fee per stock, and a holding limit per player
- [x] Optional headlines: messages broadcast when a shock or big move happens

Admin controls:

- [x] `/stock nudge <ticker> <percent> [over <time>]` — push a stock up or down, at once or gradually so it looks natural
- [x] `/stock set <ticker> <price>`, `/stock trend <ticker> <up|down> <strength> <time>`, `/stock shock <ticker> <percent>`
- [x] `/stock freeze <ticker>` and `/stock unfreeze`, to halt trading
- [x] `/stock reload`, and an admin screen with the same controls and a live chart
- [x] Nudges are silent to players but written to the server log; operators only for now. **Decide** whether lower staff ranks may use them, since anyone who can nudge can enrich themselves.

Admin stocks create and destroy money: every winning trade is paid by the server. The spread, the holding limit and a configurable cap on total payouts per day are the brakes.

### Borrowing to invest and betting on a fall

Both apply to admin stocks and company shares, not to the item market. Both let a player lose more than they put in.

**Borrowing to invest (margin)**

- [ ] Borrow against your holdings up to a configurable multiple (e.g. 2x: $1,000 of your own money controls $2,000 of stock)
- [ ] Interest charged on the borrowed amount per day
- [ ] **Forced sale**: if the holdings fall to a set fraction of the loan, they are sold automatically to repay it, with a warning shortly before
- [ ] **Debt**: if the forced sale does not cover the loan, the rest stays as a debt on the player. Income is taken towards it until cleared, and no new borrowing or company founding is allowed meanwhile.
- [ ] Borrowing multiple and interest rate adjustable per rank
- [ ] All loans come from the server; players and companies do not lend. Interest paid is money leaving the economy, and an unpaid debt is money the server created.

**Betting on a fall (short selling)**

- [ ] Open a short on a stock with money put up as security; profit if the price falls, loss if it rises
- [ ] Losses are unlimited in principle, so the position is closed automatically once losses reach the security, any shortfall becoming debt as above
- [ ] Daily fee for keeping a short open
- [ ] *Admin stocks*: the short is a bet against the server, like every other admin-stock trade
- [ ] *Company shares*: the shares are borrowed from holders who offer theirs for lending and earn the fee. This keeps it player against player. If the server paid out instead, an owner could bet against their own company, wreck it on purpose, and collect newly created money without limit.
- [ ] An owner shorting their own company through borrowed shares stays possible; the loser is whoever bought those shares
- [ ] Open short interest shown on each stock's page

Depends on a loan and debt system in the bank, which this now makes a required part of the plan.

## Court system

Player-run trials in a physical courtroom. The guiding rule is that a case cannot be botched: the court moves through fixed stages, and at each stage the judge only has the actions that are valid for it.

### How a case runs

1. **Courtroom**: marked out with four border blocks at the corners. The mod checks they form a rectangle within the size limits and shows the outline. A courtroom is saved and reused, not rebuilt per case.
2. **Filing**: someone files a case against a player or a company, picking the charge from the list of offences.
3. **Slots**: judge, two lawyers (prosecution and defence), the accused, jurors and a set number of observers. Everyone opts in to their slot except the accused. The lawyer slots may stay empty, the judge then hearing both sides directly.
4. **In session**: everyone holding a slot is teleported in. Nobody else can enter, and anyone else already inside is moved out.
5. **Recess**: called by the judge. The room opens; the accused stays held.
6. **Verdict and sentence**: the jury votes on the verdict; the judge declares the sentence from the options the offence allows.
7. **Closed**: everyone is returned to where they were before the case, and the case is written to the public record.

- [ ] Border block item and courtroom validation (rectangle, minimum and maximum size, height)
- [ ] Case stages enforced by the mod; the judge's screen shows only the actions valid right now
- [ ] Slot sign-up screen and `/court` commands for every action
- [ ] Teleport in, saved return positions, teleport back on close
- [ ] Entry barrier during a session; lifted on recess
- [ ] Accused held: heavy movement limit, cannot leave the courtroom, cannot teleport or use ender pearls
- [ ] Judge controls: start, recess, resume, remove a disruptive person, verdict, sentence
- [ ] Courtroom chat: what is said inside is kept to the room and saved as a transcript

### Limits against abuse

The accused is the only person brought in against their will, so nearly every limit protects them.

**Against using court as a kidnapping tool**

- [ ] Only appointed or elected judges can hear cases; filing can be limited by rank too
- [ ] Filing costs a fee, refunded on a guilty verdict and lost on a dismissal
- [ ] Summons first: the accused gets notice and a set time to come voluntarily or prepare before any forced teleport
- [ ] No summons while the accused is in combat, and none while they are offline
- [ ] Cooldown before the same player can be accused again, and no second trial for the same charge
- [ ] Limit on cases per judge and per accuser per day

**Against holding someone forever**

- [ ] Maximum session length, maximum recess length and maximum number of recesses; when a limit runs out the case is dismissed and the accused released
- [ ] Judge disconnects: automatic recess, then dismissal if they are not back within a set time
- [ ] Accused disconnects: the case pauses and resumes on their return, up to a limit, after which the judge may rule without them
- [ ] A server restart mid-case releases everyone and reopens the case at the slot stage

**Against a rigged court**

- [ ] The judge cannot be the accuser, and cannot share a faction or company with either side
- [ ] The accused chooses their own defence lawyer, or defends themselves; the prosecution cannot fill that slot
- [ ] Fines go to the server account or the victim, never to the judge or the court's owner
- [ ] Sentences come from the offence's configured range, with hard caps on every kind
- [ ] Appeal: one appeal per case, heard by a different and higher-ranked judge

**Against harm inside the courtroom**

- [ ] No damage, no fighting, no block breaking or placing in the courtroom during a session
- [ ] The accused keeps their inventory during the trial; items can only be taken as a sentence after a guilty verdict
- [ ] The courtroom is checked for hazards (lava, void, suffocation) before anyone is teleported in
- [ ] A courtroom cannot be marked out on land claimed by someone else

**Oversight**

- [ ] Public record of every case: parties, charge, verdict, sentence, transcript
- [ ] Operators can dismiss any case at once and overturn any sentence
- [ ] Judges whose cases are often overturned or dismissed are visible in the record

### Charges and sentences

- [ ] **Offences are a configured list** (`config/pluginless/offences.json`): each has a name, description, and the sentences a judge may choose from with their limits. A judge cannot invent a charge or exceed an offence's range.
- [ ] **Fines**: paid to the server account or the victim, capped per offence as an amount and as a share of the accused's balance. Unpaid fines become debt.
- [ ] **Asset confiscation**: money, company shares, items from shop storage, and items from the convict's inventory and ender chest, up to the offence's limit. Chests and other containers placed in the world are out of reach. Seized assets go to the victim or the server, never to the judge.
- [ ] Item seizure is chosen by the judge from a screen showing the convict's inventory and ender chest, limited to a number of stacks set by the offence; every seized item is listed in the case record
- [ ] **Forced labour**: the convict must deliver a quota (e.g. 500 cobblestone, 64 iron) set by the offence. Until it is met they are held to a labour area, with teleport and trade commands blocked. Time counts only while online; a maximum duration releases them even if the quota is unmet. Needs labour areas defined by admins.
- [ ] **Server ban**: temporary only, length capped per offence. The ban message names the case so it can be looked up. Bans above a configurable length need an operator to confirm before they take effect, since this is the one sentence that removes a player entirely.
- [ ] Sentences can be combined where the offence allows it

### Jury

- [ ] The observer slots include a jury; jurors vote guilty or not guilty in secret and the majority decides. The judge runs the trial and chooses the sentence, but not the verdict.
- [ ] Jurors are drawn at random from online volunteers, not picked by either side, so a jury cannot be stacked
- [ ] No juror from the accuser's or the accused's faction or company
- [ ] Minimum jury size to proceed; if too few are available the judge decides alone or the case waits (configurable)
- [ ] A juror who leaves is replaced from the volunteers or the vote goes ahead without them

### Judges

- [ ] Both ways supported, chosen in config: *appointed* (any player whose rank allows it) or *elected* (players vote; winners serve a term)
- [ ] Elections: candidacy needs a minimum playtime, one vote per player with the same minimum, term length and number of seats configurable
- [ ] Recall: a judge is removed early if enough of their cases are overturned on appeal, or by operators

### Companies on trial

- [ ] A company can be the accused, represented in the slot by its owner or someone the owner names
- [ ] Fines come from the company account; confiscation can take shop stock and the company's own unsold shares
- [ ] A fine the company cannot pay makes it bankrupt under the normal rules
- [ ] Forced labour and bans do not apply to a company; charging the owner personally is a separate case

## Faction compat

This covers claimed land only. Companies and shops are separate and are not affected by anything here.

Both are optional: the features switch on only when the faction mod is installed.

- [ ] **Chunk tax**: a configurable amount per claimed chunk per period, charged to the faction or team.
  - flat rate, or rising with size (first 10 chunks cheap, each beyond costs more)
  - per-dimension rates
  - **Decide — who pays**: a shared faction treasury that members deposit into, or the leader's own balance
  - **Decide — when unpaid**: warning and grace period, then no new claims, then claims removed one by one
- [ ] **Faction treasury**: a shared account with deposit/withdraw rights by faction role
- [ ] **One claim-mod adapter**: the tax and treasury code asks a small interface "who are the groups, who is in them, how many chunks does each hold". Each supported mod is one short adapter behind it, so adding a mod later does not touch the tax code.

Which claim mods have NeoForge builds (checked 2026-10-11):

| Mod | 1.21.1 | 26.1 | 26.2 | 26.3 | Groups | Notes |
|---|---|---|---|---|---|---|
| WarForge Remaintained | yes | no | no | no | Factions | 1.21.1 only, by decision |
| Open Parties and Claims | yes | yes | yes | yes | Parties | 23M downloads, updated this month; the only chunk-claim mod covering every version we build |
| FTB Chunks + FTB Teams | yes | yes | no | no | Teams | Claims are in FTB Chunks, teams in FTB Teams; no 26.2 or 26.3 builds yet |
| Flan | yes | yes | yes | yes | None | Claims are free-form areas owned by one player, not chunks; would be taxed per block of area, and has no teams |

- [ ] **Open Parties and Claims**: first adapter to build, since it works everywhere. Claims belong to players and parties group players, so the tax is per player's claim count, optionally paid from a party treasury.
- [ ] **WarForge Remaintained** (1.21.1 only): its API currently exposes claims and siege reinforcement only, so claim counts are readable, but anything deeper (war costs, siege rewards, loot paid to the winner) may need additions on the WarForge side.
- [ ] **FTB Chunks + FTB Teams** (1.21.1 and 26.1): tax counts FTB Chunks claims per team.
- [ ] **Flan**: only if wanted; it needs a per-area rate instead of a per-chunk one. **Decide**.
- [ ] Cadmus (the other well-known claim mod) is not on Modrinth; not checked on CurseForge.

## Suggested build order

1. Shared groundwork (formatting, cooldowns, perks, offline players, log, scheduler)
2. Ranks: colours, perks, timed ranks, website hook
3. QOL commands and cosmetics (small, and they exercise cooldowns and perks)
4. Bank
5. Income and taxes
6. Auction house
7. Item market
8. Companies, then shops
9. Admin stocks
10. Faction compat
11. Company shares
12. Court system

Admin stocks need only the bank and the chart, so they can move earlier if wanted. Company shares are last because they depend on companies, and they reuse the item market's order matching.

## Decisions needed

| # | Question | Default if undecided |
|---|---|---|
| 1 | Taxes and fees: go to a server account, or destroyed? | Server account |
| 2 | One rank per player, or several? | One, plus a separate donor slot |
| 3 | Website hook: console commands or HTTP endpoint? | Console commands |
| 4 | Does AFK time count for playtime rewards? | No |
| 5 | Wallet/bank split, or one balance? | One balance |
| 6 | Bank interest? | Off |
| 7 | Physical money items? | No |
| 8 | Which extra Essentials commands? | `/msg`, `/back`, `/warp`, `/afk`, `/seen` first |
| 9 | ~~How are cosmetics unlocked?~~ | Settled: by rank, or granted per player from a website; not bought with in-game money |
| 10 | Jobs system? | Not in the first pass |
| 11 | Wealth tax? | Available, off by default |
| 12 | ~~Chest shops, menu shops, or both?~~ | Settled: menus and commands only, nothing placed in the world |
| 13 | ~~What the stock market trades~~ | Settled: bulk items, admin-defined stocks, and company shares |
| 14 | ~~Shareholder votes?~~ | Settled: yes, a majority holder can take the company |
| 15 | Chunk tax: who pays, and what happens when unpaid? | Faction treasury; grace period, then claims frozen |
| 16 | What companies are for beyond shops and shares | Shops and shares only |
| 17 | Support Flan (area claims, no teams)? | No |
| 18 | ~~Bazaar-style instant market for bulk items?~~ | Settled: yes, built before shares |
| 19 | May company owners hide their finances from investors? | No, always public |
| 20 | ~~Margin and short selling?~~ | Settled: yes, both |
| 21 | Which staff may nudge admin stocks? | Operators only |
| 22 | ~~Can any player open a shop, or only companies?~~ | Settled: only companies |
| 23 | ~~Who lends for margin?~~ | Settled: the server only |
| 24 | ~~Which sentences can a court give?~~ | Settled: fines, asset confiscation, forced labour, server ban |
| 25 | ~~Can a case start without lawyers?~~ | Settled: yes |
| 26 | ~~Charges: free text or a configured list?~~ | Settled: configured list |
| 27 | ~~Judges appointed by rank or elected?~~ | Settled as both, chosen in config (confirm) |
| 28 | ~~Jury voting on the verdict?~~ | Settled: yes |
| 29 | ~~Can companies be put on trial?~~ | Settled: yes |
| 30 | ~~How far can confiscation reach?~~ | Settled: money, shares, shop stock, inventory and ender chest; not placed containers |
| 31 | ~~Dissolving a company as a sentence?~~ | Settled: no |
