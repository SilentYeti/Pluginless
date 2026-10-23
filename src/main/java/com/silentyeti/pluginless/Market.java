package com.silentyeti.pluginless;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The item market on a server: OrderBook matches the orders, this moves the items and saves the book. */
final class Market {
    private static final Codec<OrderBook.Order> ORDER = RecordCodecBuilder.create(instance -> instance.group(
            Codec.LONG.fieldOf("id").forGetter(OrderBook.Order::id),
            UUIDUtil.STRING_CODEC.fieldOf("owner").forGetter(OrderBook.Order::owner),
            Codec.STRING.fieldOf("asset").forGetter(OrderBook.Order::asset),
            Codec.BOOL.fieldOf("buy").forGetter(OrderBook.Order::buy),
            Codec.LONG.fieldOf("price").forGetter(OrderBook.Order::price),
            Codec.LONG.fieldOf("remaining").forGetter(OrderBook.Order::remaining)
    ).apply(instance, OrderBook.Order::new));
    private static final Codec<AuctionBook.Sale> SALE = RecordCodecBuilder.create(instance -> instance.group(
            Codec.LONG.fieldOf("time").forGetter(AuctionBook.Sale::time),
            Codec.LONG.fieldOf("unitPrice").forGetter(AuctionBook.Sale::unitPrice)
    ).apply(instance, AuctionBook.Sale::new));
    static final Codec<OrderBook> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            ORDER.listOf().fieldOf("orders").forGetter(book -> List.copyOf(book.orders.values())),
            Codec.unboundedMap(UUIDUtil.STRING_CODEC, Codec.unboundedMap(Codec.STRING, Codec.LONG)).fieldOf("goods").forGetter(book -> book.goods),
            Codec.unboundedMap(Codec.STRING, SALE.listOf()).fieldOf("history").forGetter(book -> book.history),
            Codec.LONG.fieldOf("nextId").forGetter(book -> book.nextId)
    ).apply(instance, (orders, goods, history, nextId) -> {
        var book = new OrderBook();
        orders.forEach(order -> book.orders.put(order.id(), order));
        goods.forEach((id, held) -> book.goods.put(id, new HashMap<>(held)));
        history.forEach((asset, sales) -> book.history.put(asset, new ArrayList<>(sales)));
        book.nextId = nextId;
        return book;
    }));

    static Store<OrderBook> store(MinecraftServer server) {
        return Store.open(server, "market", CODEC, OrderBook::new);
    }

    private static OrderBook.Fee fee(MinecraftServer server) {
        return (seller, proceeds) -> Taxes.percent(proceeds, Config.marketFee(), Income.perk(server, seller, "fee_multiplier"));
    }

    /** Only plain items trade here: anything renamed, enchanted or damaged belongs on the auction house. */
    private static boolean plain(ItemStack stack, String itemId) {
        return !stack.isEmpty() && stack.isComponentsPatchEmpty() && Stacks.id(stack).equals(itemId);
    }

    static boolean order(ServerPlayer player, String itemId, boolean buy, long price, long quantity) {
        var server = player.level().getServer();
        if (server == null || !Features.settings.marketItems.contains(itemId) || quantity <= 0 || quantity > OrderBook.MAX_QUANTITY) return false;
        var store = store(server);
        long limit = Math.round(RankGate.ranks.number(RankGate.rankOf(server, player.getUUID()), "market_orders", Config.marketOrders()));
        if (store.value.count(player.getUUID()) >= limit) return false;
        if (!buy && (price <= 0 || price > OrderBook.MAX_PRICE || !takePlain(player, itemId, quantity))) return false;
        // After the seller's items are taken the order always succeeds; a refused buy took nothing.
        boolean placed = store.value.place(player.getUUID(), itemId, buy, price, quantity, System.currentTimeMillis(),
                Ledger.of(server, player.getUUID().toString(), "market"), fee(server));
        store.setDirty();
        return placed;
    }

    /** How many plain items of this kind are in the inventory, i.e. what a sell order could take. */
    static long held(ServerPlayer player, String itemId) {
        long held = 0;
        for (int i = 0; i < 36; i++) if (plain(player.getInventory().getItem(i), itemId)) held += player.getInventory().getItem(i).getCount();
        return held;
    }

    /** Removes exactly the quantity of plain items from the inventory, or nothing. */
    static boolean takePlain(ServerPlayer player, String itemId, long quantity) {
        var inventory = player.getInventory();
        long held = 0;
        for (int i = 0; i < 36; i++) if (plain(inventory.getItem(i), itemId)) held += inventory.getItem(i).getCount();
        if (quantity <= 0 || held < quantity) return false;
        long left = quantity;
        for (int i = 0; i < 36 && left > 0; i++) {
            var stack = inventory.getItem(i);
            if (plain(stack, itemId)) { int taken = (int) Math.min(left, stack.getCount()); stack.shrink(taken); left -= taken; }
        }
        player.inventoryMenu.broadcastChanges();
        return true;
    }

    /** Puts plain items in a player's collection box: shop purchases, returned stock. */
    static void give(MinecraftServer server, UUID player, String itemId, long quantity) {
        var store = store(server);
        store.value.goods.computeIfAbsent(player, key -> new HashMap<>()).merge(itemId, quantity, Long::sum);
        store.setDirty();
    }

    /** Fills the whole quantity now against the best waiting orders, or does nothing. */
    static boolean instant(ServerPlayer player, String itemId, boolean buy, long quantity) {
        long price = store(player.level().getServer()).value.quote(player.getUUID(), itemId, buy, quantity);
        return price > 0 && order(player, itemId, buy, price, quantity);
    }

    static boolean cancel(ServerPlayer player, long id) {
        var server = player.level().getServer();
        var store = store(server);
        if (!store.value.cancel(player.getUUID(), id, Ledger.of(server, player.getUUID().toString(), "market.cancel"))) return false;
        store.setDirty();
        return true;
    }

    /** Moves collected goods into free inventory slots, a stack at a time. */
    static long collect(ServerPlayer player) {
        var store = store(player.level().getServer());
        long moved = 0;
        for (String asset : List.copyOf(store.value.goods.getOrDefault(player.getUUID(), Map.of()).keySet())) {
            var item = BuiltInRegistries.ITEM.getOptional(ResourceLocation.parse(asset));
            if (item.isEmpty()) continue;
            while (player.getInventory().getFreeSlot() >= 0) {
                long taken = store.value.take(player.getUUID(), asset, new ItemStack(item.get()).getMaxStackSize());
                if (taken == 0) break;
                player.getInventory().add(new ItemStack(item.get(), (int) taken));
                moved += taken;
            }
        }
        if (moved > 0) { store.setDirty(); player.inventoryMenu.broadcastChanges(); }
        return moved;
    }

    static Component book(MinecraftServer server, String itemId) {
        var book = store(server).value;
        var prices = book.history.getOrDefault(itemId, List.of()).stream().map(AuctionBook.Sale::unitPrice).toList();
        var result = Component.translatable("pluginless.market.book", itemId, prices.isEmpty() ? "-" : Config.format(prices.get(prices.size() - 1)), OrderBook.spark(prices, 40));
        for (boolean buy : new boolean[]{false, true}) {
            result.append(Component.translatable(buy ? "pluginless.market.bids" : "pluginless.market.asks"));
            var side = book.side(itemId, buy);
            for (var order : side.subList(0, Math.min(5, side.size())))
                result.append(Component.translatable("pluginless.market.level", order.remaining(), Config.format(order.price())));
        }
        return result;
    }

    static Component mine(MinecraftServer server, UUID player) {
        var book = store(server).value;
        var result = Component.translatable("pluginless.market.mine", book.goods.getOrDefault(player, Map.of()).toString());
        for (var order : book.orders.values()) {
            if (order.owner().equals(player)) result.append(Component.translatable("pluginless.market.order", order.id(),
                    Component.translatable(order.buy() ? "pluginless.market.buying" : "pluginless.market.selling"), order.remaining(), order.asset(), Config.format(order.price())));
        }
        return result;
    }
}
