package com.silentyeti.pluginless;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Browse, bid and buy, list items from an inventory slot, and manage your own listings. What the player is browsing
 * (category, sort, search, page, a price they are weighing) is kept here per player, so each snapshot is one page.
 */
final class AuctionPage implements PdaPage {
    static final int PAGE = 8;
    private static final Pattern SEARCH = Pattern.compile("[A-Za-z0-9 _:.'-]{0,24}");
    private static final Pattern ITEM = Pattern.compile("[a-z0-9_.-]+:[a-z0-9/._-]+");

    private record Query(String category, String sort, String search, int page, long quote) {}

    private static final Map<UUID, Query> QUERIES = new HashMap<>();

    public String id() { return "auction"; }

    public JsonObject view(MinecraftServer server, ServerPlayer player) {
        var id = player.getUUID();
        var book = Auctions.store(server).value;
        long now = System.currentTimeMillis();
        Query query;
        synchronized (QUERIES) { query = QUERIES.getOrDefault(id, new Query("", "", "", 0, 0)); }
        var view = new JsonObject();
        var found = book.view(query.search(), query.category().isEmpty() ? null : Features.settings.auctionCategories.get(query.category()), query.sort(), now);
        int pages = Math.max(1, (found.size() + PAGE - 1) / PAGE);
        int page = Math.min(query.page(), pages - 1);
        var q = new JsonObject();
        q.addProperty("category", query.category());
        q.addProperty("sort", query.sort());
        q.addProperty("search", query.search());
        q.addProperty("page", page);
        q.addProperty("pages", pages);
        q.addProperty("total", found.size());
        view.add("query", q);
        var categories = new JsonArray();
        Features.settings.auctionCategories.keySet().forEach(categories::add);
        view.add("categories", categories);
        var rows = new JsonArray();
        for (var listing : found.subList(Math.min(page * PAGE, found.size()), Math.min(found.size(), page * PAGE + PAGE))) rows.add(row(server, listing, id, now));
        view.add("listings", rows);
        var mine = new JsonArray();
        for (var listing : book.listings.values()) if (listing.seller().equals(id)) mine.add(row(server, listing, id, now));
        view.add("mine", mine);
        view.addProperty("slots", Auctions.slots(server, id));
        view.addProperty("boxed", book.boxes.getOrDefault(id, List.of()).size());
        view.addProperty("min", Config.auctionMinCents());
        view.addProperty("max", Config.auctionMaxCents());
        view.addProperty("tax", Config.auctionTax());
        view.addProperty("raise", Config.auctionRaise());
        view.addProperty("hours", Config.auctionMillis() / 3_600_000L);
        view.addProperty("fee", query.quote() > 0 ? Auctions.fee(server, id, query.quote()) : 0);
        view.add("watching", PdaHub.strings(book.watches.getOrDefault(id, List.of())));
        return view;
    }

    private static JsonObject row(MinecraftServer server, AuctionBook.Listing listing, UUID viewer, long now) {
        var row = new JsonObject();
        row.addProperty("id", listing.id());
        row.addProperty("name", listing.name());
        row.addProperty("item", listing.itemId());
        row.addProperty("count", listing.count());
        row.addProperty("auction", listing.auction());
        row.addProperty("price", listing.auction() ? Math.max(listing.price(), listing.bid()) : listing.price());
        row.addProperty("minBid", listing.auction() ? AuctionBook.minimumBid(listing, Config.auctionRaise()) : 0);
        row.addProperty("bids", listing.bidder() != null);
        row.addProperty("leading", viewer.equals(listing.bidder()));
        row.addProperty("seller", Commands.name(server, listing.seller()));
        row.addProperty("mine", viewer.equals(listing.seller()));
        row.addProperty("seconds", Math.max(0, (listing.expires() - now) / 1000));
        return row;
    }

    public Result act(ServerPlayer player, String verb, List<String> args, int slot) {
        switch (verb) {
            case "query" -> {
                if (args.size() != 5) return Result.fail("pluginless.pda.invalid");
                var category = args.get(0);
                var sort = args.get(1);
                var search = args.get(2);
                if (!category.isEmpty() && !Features.settings.auctionCategories.containsKey(category) || !List.of("", "price", "expensive", "oldest").contains(sort)
                        || !SEARCH.matcher(search).matches()) return Result.fail("pluginless.pda.invalid");
                int page;
                long quote;
                try { page = Integer.parseInt(args.get(3)); quote = Long.parseLong(args.get(4)); }
                catch (NumberFormatException e) { return Result.fail("pluginless.pda.invalid"); }
                if (page < 0 || page > 10_000 || quote < 0 || quote > PdaCommands.MAX_CENTS) return Result.fail("pluginless.pda.invalid");
                synchronized (QUERIES) { QUERIES.put(player.getUUID(), new Query(category, sort, search, page, quote)); }
                return Result.ok("");
            }
            case "buy", "cancel" -> {
                var id = args.size() == 1 ? number(args.get(0)) : null;
                return id == null ? Result.fail("pluginless.pda.invalid") : PdaCommands.run(player, "ah " + verb + " " + id);
            }
            case "bid" -> {
                var id = args.size() == 2 ? number(args.get(0)) : null;
                var amount = args.size() == 2 ? PdaCommands.amount(args.get(1)) : null;
                return id == null || amount == null ? Result.fail("pluginless.pda.invalid") : PdaCommands.run(player, "ah bid " + id + " " + amount);
            }
            case "sell", "auction" -> {
                var amount = args.size() == 1 ? PdaCommands.amount(args.get(0)) : null;
                if (amount == null || slot < 0 || slot > 35) return Result.fail("pluginless.pda.invalid");
                return PdaCommands.run(player, "ah " + verb + " " + amount + " " + slot);
            }
            case "collect" -> {
                return PdaCommands.run(player, "ah collect");
            }
            case "watch", "unwatch" -> {
                if (args.size() != 1 || !ITEM.matcher(args.get(0)).matches()) return Result.fail("pluginless.pda.invalid");
                return PdaCommands.run(player, "ah " + verb + " " + args.get(0));
            }
            default -> {
                return Result.fail("pluginless.pda.invalid");
            }
        }
    }

    private static String number(String value) {
        try { return Long.parseLong(value) > 0 ? value : null; } catch (NumberFormatException e) { return null; }
    }

    static void left(UUID player) {
        synchronized (QUERIES) { QUERIES.remove(player); }
    }
}
