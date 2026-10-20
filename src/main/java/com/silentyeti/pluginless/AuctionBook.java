package com.silentyeti.pluginless;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Listings, collection boxes and sale history, without Minecraft. An item is an opaque string here.
 * Every method runs on the server thread and finishes in one step: a listing leaves the map exactly once,
 * so an item can never be both sold and returned.
 */
public final class AuctionBook {
    public record Listing(long id, UUID seller, String item, String itemId, String name, int count, long price, long listed, long expires) {}
    public record Sale(long time, long unitPrice) {}
    public record Notice(String kind, String name, long cents) {}

    public final Map<Long, Listing> listings = new LinkedHashMap<>();
    public final Map<UUID, List<String>> boxes = new HashMap<>();
    public final Map<UUID, List<Notice>> notices = new HashMap<>();
    public final Map<UUID, List<String>> watches = new HashMap<>();
    public final Map<String, List<Sale>> history = new HashMap<>();
    public long nextId = 1;

    public long count(UUID seller) {
        return listings.values().stream().filter(listing -> listing.seller().equals(seller)).count();
    }

    public Listing list(UUID seller, String item, String itemId, String name, int count, long price, long now, long duration) {
        var listing = new Listing(nextId++, seller, item, itemId, name, count, price, now, now + duration);
        listings.put(listing.id(), listing);
        return listing;
    }

    /** Null when the sale happened, otherwise why not: gone, grace, own or funds. */
    public String buy(UUID buyer, long id, long now, long grace, Accounts accounts, long tax) {
        var listing = listings.get(id);
        if (listing == null || now >= listing.expires()) return "gone";
        if (listing.seller().equals(buyer)) return "own";
        if (now - listing.listed() < grace) return "grace";
        if (accounts.balance(buyer) < listing.price()) return "funds";
        tax = Math.min(tax, listing.price());
        if (tax > 0 && !accounts.transfer(buyer, Accounts.SERVER, tax)) return "funds";
        if (listing.price() > tax && !accounts.transfer(buyer, listing.seller(), listing.price() - tax)) {
            if (tax > 0) accounts.refund(buyer, tax);
            return "funds";
        }
        listings.remove(id);
        boxes.computeIfAbsent(buyer, key -> new ArrayList<>()).add(listing.item());
        notices.computeIfAbsent(listing.seller(), key -> new ArrayList<>()).add(new Notice("sold", listing.name(), listing.price() - tax));
        var sales = history.computeIfAbsent(listing.itemId(), key -> new ArrayList<>());
        sales.add(new Sale(now, listing.price() / Math.max(1, listing.count())));
        // shortcut: the last 200 sales per item, move to a time-bucketed store when charts need longer history
        if (sales.size() > 200) sales.remove(0);
        return null;
    }

    /** Returns the item to the seller's box; anyOwner is for an admin removing someone else's listing. */
    public boolean cancel(UUID by, long id, boolean anyOwner) {
        var listing = listings.get(id);
        if (listing == null || !anyOwner && !listing.seller().equals(by)) return false;
        listings.remove(id);
        boxes.computeIfAbsent(listing.seller(), key -> new ArrayList<>()).add(listing.item());
        return true;
    }

    public List<Listing> expire(long now) {
        var expired = listings.values().stream().filter(listing -> now >= listing.expires()).toList();
        for (var listing : expired) {
            listings.remove(listing.id());
            boxes.computeIfAbsent(listing.seller(), key -> new ArrayList<>()).add(listing.item());
            notices.computeIfAbsent(listing.seller(), key -> new ArrayList<>()).add(new Notice("expired", listing.name(), 0));
        }
        return expired;
    }

    /** The oldest boxed item, removed from the box; null when the box is empty. */
    public String take(UUID player) {
        var box = boxes.get(player);
        if (box == null || box.isEmpty()) return null;
        var item = box.remove(0);
        if (box.isEmpty()) boxes.remove(player);
        return item;
    }

    public List<UUID> watchers(String itemId) {
        return watches.entrySet().stream().filter(entry -> entry.getValue().contains(itemId)).map(Map.Entry::getKey).toList();
    }

    /** Search matches the item id or display name; a category is a list of item-id fragments. */
    public List<Listing> view(String search, List<String> category, String sort, long now) {
        String wanted = search.toLowerCase(Locale.ROOT);
        Comparator<Listing> order = switch (sort) {
            case "price" -> Comparator.comparingLong(Listing::price);
            case "expensive" -> Comparator.comparingLong(Listing::price).reversed();
            case "oldest" -> Comparator.comparingLong(Listing::listed);
            default -> Comparator.comparingLong(Listing::listed).reversed();
        };
        return listings.values().stream()
                .filter(listing -> now < listing.expires())
                .filter(listing -> listing.itemId().contains(wanted) || listing.name().toLowerCase(Locale.ROOT).contains(wanted))
                .filter(listing -> category == null || category.stream().anyMatch(listing.itemId()::contains))
                .sorted(order).toList();
    }
}
