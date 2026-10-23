package com.silentyeti.pluginless;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The auction house on a server: AuctionBook holds the rules, this adds items, money, messages and saving. */
final class Auctions {
    private static final Codec<AuctionBook.Listing> LISTING = RecordCodecBuilder.create(instance -> instance.group(
            Codec.LONG.fieldOf("id").forGetter(AuctionBook.Listing::id),
            UUIDUtil.STRING_CODEC.fieldOf("seller").forGetter(AuctionBook.Listing::seller),
            Codec.STRING.fieldOf("item").forGetter(AuctionBook.Listing::item),
            Codec.STRING.fieldOf("itemId").forGetter(AuctionBook.Listing::itemId),
            Codec.STRING.fieldOf("name").forGetter(AuctionBook.Listing::name),
            Codec.INT.fieldOf("count").forGetter(AuctionBook.Listing::count),
            Codec.LONG.fieldOf("price").forGetter(AuctionBook.Listing::price),
            Codec.LONG.fieldOf("listed").forGetter(AuctionBook.Listing::listed),
            Codec.LONG.fieldOf("expires").forGetter(AuctionBook.Listing::expires),
            Codec.BOOL.optionalFieldOf("auction", false).forGetter(AuctionBook.Listing::auction),
            Codec.LONG.optionalFieldOf("bid", 0L).forGetter(AuctionBook.Listing::bid),
            UUIDUtil.STRING_CODEC.optionalFieldOf("bidder").forGetter(listing -> java.util.Optional.ofNullable(listing.bidder()))
    ).apply(instance, (id, seller, item, itemId, name, count, price, listed, expires, auction, bid, bidder) ->
            new AuctionBook.Listing(id, seller, item, itemId, name, count, price, listed, expires, auction, bid, bidder.orElse(null))));
    private static final Codec<AuctionBook.Sale> SALE = RecordCodecBuilder.create(instance -> instance.group(
            Codec.LONG.fieldOf("time").forGetter(AuctionBook.Sale::time),
            Codec.LONG.fieldOf("unitPrice").forGetter(AuctionBook.Sale::unitPrice)
    ).apply(instance, AuctionBook.Sale::new));
    private static final Codec<AuctionBook.Notice> NOTICE = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("kind").forGetter(AuctionBook.Notice::kind),
            Codec.STRING.fieldOf("name").forGetter(AuctionBook.Notice::name),
            Codec.LONG.fieldOf("cents").forGetter(AuctionBook.Notice::cents)
    ).apply(instance, AuctionBook.Notice::new));
    static final Codec<AuctionBook> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            LISTING.listOf().fieldOf("listings").forGetter(book -> List.copyOf(book.listings.values())),
            Codec.unboundedMap(UUIDUtil.STRING_CODEC, Codec.STRING.listOf()).fieldOf("boxes").forGetter(book -> book.boxes),
            Codec.unboundedMap(UUIDUtil.STRING_CODEC, NOTICE.listOf()).fieldOf("notices").forGetter(book -> book.notices),
            Codec.unboundedMap(UUIDUtil.STRING_CODEC, Codec.STRING.listOf()).fieldOf("watches").forGetter(book -> book.watches),
            Codec.unboundedMap(Codec.STRING, SALE.listOf()).fieldOf("history").forGetter(book -> book.history),
            Codec.LONG.fieldOf("nextId").forGetter(book -> book.nextId)
    ).apply(instance, (listings, boxes, notices, watches, history, nextId) -> {
        var book = new AuctionBook();
        listings.forEach(listing -> book.listings.put(listing.id(), listing));
        boxes.forEach((id, items) -> book.boxes.put(id, new ArrayList<>(items)));
        notices.forEach((id, list) -> book.notices.put(id, new ArrayList<>(list)));
        watches.forEach((id, list) -> book.watches.put(id, new ArrayList<>(list)));
        history.forEach((item, sales) -> book.history.put(item, new ArrayList<>(sales)));
        book.nextId = nextId;
        return book;
    }));

    static Store<AuctionBook> store(MinecraftServer server) {
        return Store.open(server, "auctions", CODEC, AuctionBook::new);
    }

    static long slots(MinecraftServer server, UUID player) {
        return Math.round(RankGate.ranks.number(RankGate.rankOf(server, player), "auction_slots", Config.auctionSlots()));
    }

    static long fee(MinecraftServer server, UUID seller, long price) {
        return Taxes.percent(price, Taxes.tier(Features.settings.auctionFeeTiers, price), Income.perk(server, seller, "fee_multiplier"));
    }

    static boolean sell(ServerPlayer player, long price) {
        return sell(player, price, false);
    }

    static boolean sell(ServerPlayer player, long price, boolean auction) {
        return sell(player, price, auction, -1);
    }

    /** {@code slot}: an inventory slot 0..35, or -1 for the main hand (the PDA screens hold the PDA, so they pick a slot). */
    static boolean sell(ServerPlayer player, long price, boolean auction, int slot) {
        var server = player.level().getServer();
        if (slot < -1 || slot > 35) return false;
        var stack = slot < 0 ? player.getMainHandItem() : player.getInventory().getItem(slot);
        if (server == null || stack.isEmpty() || price < Config.auctionMinCents() || price > Config.auctionMaxCents()) return false;
        var store = store(server);
        String itemId = Stacks.id(stack);
        if (Features.settings.auctionBlockedItems.contains(itemId) || store.value.count(player.getUUID()) >= slots(server, player.getUUID())) return false;
        // Serialise before charging: an item that cannot be stored must cost nothing.
        String item = Stacks.write(server, stack);
        long fee = fee(server, player.getUUID(), price);
        if (fee > 0 && !Ledger.collect(server, player.getUUID(), fee, "auction.fee")) return false;
        long now = System.currentTimeMillis();
        var listing = auction ? store.value.listAuction(player.getUUID(), item, itemId, stack.getHoverName().getString(), stack.getCount(), price, now, Config.auctionMillis())
                : store.value.list(player.getUUID(), item, itemId, stack.getHoverName().getString(), stack.getCount(), price, now, Config.auctionMillis());
        stack.setCount(0);
        player.inventoryMenu.broadcastChanges();
        for (var watcher : store.value.watchers(itemId)) {
            if (!watcher.equals(player.getUUID())) store.value.notices.computeIfAbsent(watcher, key -> new ArrayList<>()).add(new AuctionBook.Notice("watch", listing.name(), price));
        }
        store.setDirty();
        deliver(server);
        return true;
    }

    /** Null on success, otherwise the reason. */
    static String buy(ServerPlayer player, long id) {
        var server = player.level().getServer();
        var store = store(server);
        var listing = store.value.listings.get(id);
        if (listing == null) return "gone";
        long tax = Taxes.percent(listing.price(), Config.auctionTax(), Income.perk(server, listing.seller(), "fee_multiplier"));
        String problem = store.value.buy(player.getUUID(), id, System.currentTimeMillis(), Config.auctionGraceMillis(),
                Ledger.of(server, player.getUUID().toString(), "auction"), tax);
        if (problem == null) { store.setDirty(); deliver(server); }
        return problem;
    }

    /** Null when the bid stands, otherwise the reason. */
    static String bid(ServerPlayer player, long id, long amount) {
        var server = player.level().getServer();
        var store = store(server);
        String problem = store.value.bid(player.getUUID(), id, amount, System.currentTimeMillis(), Config.auctionGraceMillis(), Config.auctionRaise(),
                Ledger.of(server, player.getUUID().toString(), "auction.bid"));
        if (problem == null) { store.setDirty(); deliver(server); }
        return problem;
    }

    static boolean cancel(ServerPlayer player, long id, boolean admin) {
        var server = player.level().getServer();
        var store = store(server);
        if (!store.value.cancel(player.getUUID(), id, admin, Ledger.of(server, player.getUUID().toString(), "auction.cancel"))) return false;
        store.setDirty();
        deliver(server);
        return true;
    }

    /** Moves boxed items into free inventory slots; stops when the inventory is full. */
    static int collect(ServerPlayer player) {
        var server = player.level().getServer();
        var store = store(server);
        int moved = 0;
        while (player.getInventory().getFreeSlot() >= 0) {
            var box = store.value.boxes.get(player.getUUID());
            if (box == null || box.isEmpty()) break;
            // Parse before taking: an item whose mod was removed stays in the box instead of vanishing.
            net.minecraft.world.item.ItemStack stack;
            try { stack = Stacks.read(server, box.get(0)); }
            catch (RuntimeException e) { Pluginless.LOGGER.error("auction item of {} cannot be restored", player.getScoreboardName(), e); break; }
            store.value.take(player.getUUID());
            store.setDirty();
            player.getInventory().add(stack);
            moved++;
        }
        if (moved > 0) player.inventoryMenu.broadcastChanges();
        return moved;
    }

    static boolean watch(ServerPlayer player, String itemId, boolean on) {
        if (!itemId.matches("[a-z0-9_.-]+:[a-z0-9/._-]+")) return false;
        var store = store(player.level().getServer());
        var list = store.value.watches.computeIfAbsent(player.getUUID(), key -> new ArrayList<>());
        // shortcut: 50 watched items per player, raise it if anyone needs more
        boolean changed = on ? !list.contains(itemId) && list.size() < 50 && list.add(itemId) : list.remove(itemId);
        if (changed) store.setDirty();
        return changed;
    }

    static void tick(MinecraftServer server, long now) {
        var store = store(server);
        if (!store.value.expire(now, Ledger.of(server, Accounts.SERVER.toString(), "auction"),
                (seller, price) -> Taxes.percent(price, Config.auctionTax(), Income.perk(server, seller, "fee_multiplier"))).isEmpty()) store.setDirty();
        deliver(server);
    }

    /** Sold, expired and watch notices wait here until the player is online. */
    static void deliver(MinecraftServer server) {
        var store = store(server);
        for (var player : server.getPlayerList().getPlayers()) {
            var notices = store.value.notices.remove(player.getUUID());
            if (notices == null) continue;
            for (var notice : notices) {
                var text = Component.translatable("pluginless.auction.notice." + notice.kind(), notice.name(), Config.format(notice.cents()));
                player.sendSystemMessage(text);
                Notices.push(player.getUUID(), "auction", text.getString(), System.currentTimeMillis());
            }
            store.setDirty();
        }
    }

    static Component list(MinecraftServer server, String search, String category, String sort) {
        var book = store(server).value;
        long now = System.currentTimeMillis();
        var found = book.view(search, Features.settings.auctionCategories.get(category), sort, now);
        var result = Component.translatable("pluginless.auction.list", found.size());
        // shortcut: the first 30 matches, add paging when a server's listings outgrow search and filters
        for (var listing : found.subList(0, Math.min(30, found.size()))) {
            result.append(Component.translatable("pluginless.auction.line", listing.id(), listing.count(), listing.name(), Config.format(listing.auction() ? Math.max(listing.price(), listing.bid()) : listing.price()),
                    Commands.name(server, listing.seller()), (listing.expires() - now) / 3_600_000));
        }
        return result;
    }

    static Component view(MinecraftServer server, long id) {
        var book = store(server).value;
        var listing = book.listings.get(id);
        if (listing == null) return Component.translatable("pluginless.auction.gone");
        var sales = book.history.getOrDefault(listing.itemId(), List.of());
        var result = Component.translatable("pluginless.auction.view", listing.id(), listing.count(), listing.name(), listing.itemId(), Config.format(listing.price()),
                Commands.name(server, listing.seller()), sales.isEmpty() ? "-" : Config.format(sales.get(sales.size() - 1).unitPrice()));
        if (listing.auction()) result.append(Component.translatable("pluginless.auction.view.bid", listing.bidder() == null ? "-" : Config.format(listing.bid()),
                Config.format(AuctionBook.minimumBid(listing, Config.auctionRaise()))));
        for (String line : Stacks.contents(listing.item())) result.append(Component.literal("\n  " + line));
        return result;
    }

    static Component box(MinecraftServer server, UUID player) {
        var book = store(server).value;
        return Component.translatable("pluginless.auction.box", book.boxes.getOrDefault(player, List.of()).size(), book.count(player), slots(server, player));
    }

    static Component sales(MinecraftServer server, UUID player) {
        var result = Component.translatable("pluginless.auction.sales", Commands.name(server, player));
        for (var t : Ledger.history(server, player)) {
            if (t.reason().startsWith("auction")) result.append(Component.translatable("pluginless.bank.transaction", java.time.Instant.ofEpochMilli(t.time()).toString(),
                    Bank.endpoint(server, t.from()), Bank.endpoint(server, t.to()), Config.format(t.amount()), t.reason()));
        }
        return result;
    }
}
