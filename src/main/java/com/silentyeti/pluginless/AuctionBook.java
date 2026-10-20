package com.silentyeti.pluginless;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.ToLongBiFunction;

/**
 * Listings, collection boxes and sale history, without Minecraft. An item is an opaque string here.
 * Every method runs on the server thread and finishes in one step: a listing leaves the map exactly once,
 * so an item can never be both sold and returned.
 */
public final class AuctionBook {
    /** An auction listing starts at price and holds the highest bid in escrow; bid is 0 and bidder null until the first one. */
    public record Listing(long id, UUID seller, String item, String itemId, String name, int count, long price, long listed, long expires,
                          boolean auction, long bid, UUID bidder) {
        public Listing(long id, UUID seller, String item, String itemId, String name, int count, long price, long listed, long expires) {
            this(id, seller, item, itemId, name, count, price, listed, expires, false, 0, null);
        }
    }
    /** A bid in the last two minutes pushes the end back to two minutes from now. */
    public static final long EXTENSION = 120_000;
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

    public Listing listAuction(UUID seller, String item, String itemId, String name, int count, long start, long now, long duration) {
        var listing = list(seller, item, itemId, name, count, start, now, duration);
        var auction = new Listing(listing.id(), seller, item, itemId, name, count, start, now, listing.expires(), true, 0, null);
        listings.put(auction.id(), auction);
        return auction;
    }

    /** The lowest acceptable next bid: the start price, then the current bid plus the raise (at least one cent). */
    public static long minimumBid(Listing listing, double raisePercent) {
        return listing.bidder() == null ? listing.price() : listing.bid() + Math.max(1, Taxes.percent(listing.bid(), raisePercent, 1));
    }

    /** Null when the bid stands, otherwise why not: gone, own, grace, fixed, low or funds. The bid is escrowed and the outbid bidder refunded in this one step. */
    public String bid(UUID bidder, long id, long amount, long now, long grace, double raisePercent, Accounts accounts) {
        var listing = listings.get(id);
        if (listing == null || now >= listing.expires()) return "gone";
        if (!listing.auction()) return "fixed";
        if (listing.seller().equals(bidder)) return "own";
        if (now - listing.listed() < grace) return "grace";
        if (listing.bidder() != null && listing.bidder().equals(bidder) && amount > listing.bid()) {
            // raising your own bid only escrows the difference
            if (amount < minimumBid(listing, raisePercent)) return "low";
            if (!accounts.release(bidder, Accounts.ESCROW, amount - listing.bid())) return "funds";
        } else {
            if (amount < minimumBid(listing, raisePercent)) return "low";
            if (!accounts.release(bidder, Accounts.ESCROW, amount)) return "funds";
            if (listing.bidder() != null) {
                if (!accounts.release(Accounts.ESCROW, listing.bidder(), listing.bid())) { accounts.release(Accounts.ESCROW, bidder, amount); return "funds"; }
                notices.computeIfAbsent(listing.bidder(), key -> new ArrayList<>()).add(new Notice("outbid", listing.name(), amount));
            }
        }
        long expires = listing.expires() - now < EXTENSION ? now + EXTENSION : listing.expires();
        listings.put(id, new Listing(id, listing.seller(), listing.item(), listing.itemId(), listing.name(), listing.count(), listing.price(), listing.listed(), expires, true, amount, bidder));
        return null;
    }

    /** Null when the sale happened, otherwise why not: gone, grace, own or funds. */
    public String buy(UUID buyer, long id, long now, long grace, Accounts accounts, long tax) {
        var listing = listings.get(id);
        if (listing == null || now >= listing.expires()) return "gone";
        if (listing.auction()) return "auction";
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
        record(listing, listing.price(), now);
        return null;
    }

    private void record(Listing listing, long price, long now) {
        var sales = history.computeIfAbsent(listing.itemId(), key -> new ArrayList<>());
        sales.add(new Sale(now, price / Math.max(1, listing.count())));
        // shortcut: the last 200 sales per item, move to a time-bucketed store when charts need longer history
        if (sales.size() > 200) sales.remove(0);
    }

    /** Returns the item to the seller's box; anyOwner is for an admin removing someone else's listing. A seller cannot cancel once there is a bid; an admin removal refunds the bidder. */
    public boolean cancel(UUID by, long id, boolean anyOwner, Accounts accounts) {
        var listing = listings.get(id);
        if (listing == null || !anyOwner && !listing.seller().equals(by) || !anyOwner && listing.bidder() != null) return false;
        if (listing.bidder() != null && !accounts.release(Accounts.ESCROW, listing.bidder(), listing.bid())) return false;
        listings.remove(id);
        boxes.computeIfAbsent(listing.seller(), key -> new ArrayList<>()).add(listing.item());
        return true;
    }

    /** Ends what is over: an auction with a bid is paid out of escrow (tax from the final price by seller), anything else goes back to its seller. */
    public List<Listing> expire(long now, Accounts accounts, ToLongBiFunction<UUID, Long> tax) {
        var expired = listings.values().stream().filter(listing -> now >= listing.expires()).toList();
        for (var listing : expired) {
            if (listing.bidder() != null) {
                long cut = Math.min(tax.applyAsLong(listing.seller(), listing.bid()), listing.bid());
                if (cut > 0) accounts.release(Accounts.ESCROW, Accounts.SERVER, cut);
                if (listing.bid() > cut) accounts.transfer(Accounts.ESCROW, listing.seller(), listing.bid() - cut);
                listings.remove(listing.id());
                boxes.computeIfAbsent(listing.bidder(), key -> new ArrayList<>()).add(listing.item());
                notices.computeIfAbsent(listing.seller(), key -> new ArrayList<>()).add(new Notice("sold", listing.name(), listing.bid() - cut));
                notices.computeIfAbsent(listing.bidder(), key -> new ArrayList<>()).add(new Notice("won", listing.name(), listing.bid()));
                record(listing, listing.bid(), now);
                continue;
            }
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
