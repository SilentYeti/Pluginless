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
 * Companies and their shops, without Minecraft. A company's money is an ordinary account, so transfers,
 * the transaction log and debt rules apply to it unchanged. Shops hold plain items as counts per item id.
 */
public final class CompanyBook {
    public static final class Company {
        public long id;
        public String name, description = "";
        public UUID owner;
        /** manager or employee; the owner is not listed. */
        public final Map<UUID, String> members = new LinkedHashMap<>();
        /** Cents paid to a member on each payday. */
        public final Map<UUID, Long> wages = new HashMap<>();

        /** spend and hire: owner and managers. shops: owner and managers. stock: everyone in the company. */
        public boolean can(UUID player, String permission) {
            if (player.equals(owner)) return true;
            String role = members.get(player);
            return role != null && (role.equals("manager") ? !permission.equals("own") : permission.equals("stock"));
        }
    }

    public record Offer(long sellPrice, long buyPrice, long buyLimit) {}

    public static final class Shop {
        public long id, company;
        public String name, description = "", icon = "";
        public final Map<String, Offer> offers = new LinkedHashMap<>();
        public final Map<String, Long> stock = new HashMap<>();

        /** An admin shop belongs to no company: unlimited stock, and the server account pays and is paid. */
        public boolean admin() { return company == 0; }
        public long stored() { return stock.values().stream().mapToLong(Long::longValue).sum(); }
    }

    public final Map<Long, Company> companies = new LinkedHashMap<>();
    public final Map<Long, Shop> shops = new LinkedHashMap<>();
    public long nextId = 1, lastPayroll;

    public static UUID account(long company) {
        return company == 0 ? Accounts.SERVER : new UUID(0, 1000 + company);
    }

    public static boolean name(String name) {
        return name != null && name.matches("[A-Za-z0-9_]{3,24}");
    }

    public Company company(String name) {
        return companies.values().stream().filter(company -> company.name.equalsIgnoreCase(name)).findFirst().orElse(null);
    }

    public Shop shop(String name) {
        return shops.values().stream().filter(shop -> shop.name.equalsIgnoreCase(name)).findFirst().orElse(null);
    }

    public long owned(UUID player) {
        return companies.values().stream().filter(company -> company.owner.equals(player)).count();
    }

    public Company create(UUID owner, String name, long fee, long limit, Accounts accounts) {
        if (!name(name) || company(name) != null || owned(owner) >= limit) return null;
        if (fee > 0 && !accounts.release(owner, Accounts.SERVER, fee)) return null;
        var company = new Company();
        company.id = nextId++;
        company.name = name;
        company.owner = owner;
        companies.put(company.id, company);
        return company;
    }

    public boolean deposit(UUID player, Company company, long cents, Accounts accounts) {
        return accounts.release(player, account(company.id), cents);
    }

    /** Money leaving the company to a person is income for that person. */
    public boolean withdraw(UUID player, Company company, long cents, Accounts accounts) {
        return company.can(player, "spend") && accounts.transfer(account(company.id), player, cents);
    }

    public boolean hire(UUID by, Company company, UUID target, String role) {
        boolean manager = role.equals("manager");
        if (!manager && !role.equals("employee") || target.equals(company.owner)) return false;
        // Only the owner appoints or changes a manager.
        if (!company.can(by, manager || "manager".equals(company.members.get(target)) ? "own" : "hire")) return false;
        company.members.put(target, role);
        return true;
    }

    /** A member may always leave; removing someone else needs the same right as hiring them. */
    public boolean fire(UUID by, Company company, UUID target) {
        String role = company.members.get(target);
        if (role == null || !by.equals(target) && !company.can(by, role.equals("manager") ? "own" : "hire")) return false;
        company.members.remove(target);
        company.wages.remove(target);
        return true;
    }

    public boolean wage(UUID by, Company company, UUID target, long cents) {
        if (!company.can(by, "spend") || cents < 0 || !company.members.containsKey(target)) return false;
        if (cents == 0) company.wages.remove(target);
        else company.wages.put(target, cents);
        return true;
    }

    /** Pays every wage the company can cover; returns the companies that missed at least one. */
    public List<Company> payroll(Accounts accounts) {
        var failed = new ArrayList<Company>();
        for (var company : companies.values()) {
            boolean missed = false;
            for (var wage : company.wages.entrySet()) {
                if (!accounts.transfer(account(company.id), wage.getKey(), wage.getValue())) missed = true;
            }
            if (missed) failed.add(company);
        }
        return failed;
    }

    /** The owner takes the account; shop stock is returned through the map given back. */
    public Map<String, Long> dissolve(UUID by, Company company, Accounts accounts) {
        if (!by.equals(company.owner)) return null;
        long balance = accounts.balance(account(company.id));
        if (balance > 0 && !accounts.transfer(account(company.id), company.owner, balance)) return null;
        var returned = new HashMap<String, Long>();
        for (var shop : List.copyOf(shops.values())) {
            if (shop.company != company.id) continue;
            shop.stock.forEach((item, count) -> returned.merge(item, count, Long::sum));
            shops.remove(shop.id);
        }
        companies.remove(company.id);
        return returned;
    }

    public Shop open(UUID by, Company company, String name, long limit) {
        if (!name(name) || shop(name) != null) return null;
        if (company != null && (!company.can(by, "shops") || shops.values().stream().filter(shop -> shop.company == company.id).count() >= limit)) return null;
        var shop = new Shop();
        shop.id = nextId++;
        shop.company = company == null ? 0 : company.id;
        shop.name = name;
        shops.put(shop.id, shop);
        return shop;
    }

    /** Who may act on a shop: company staff with the permission, or an operator for an admin shop. */
    public boolean can(UUID player, Shop shop, String permission, boolean operator) {
        if (shop.admin()) return operator;
        var company = companies.get(shop.company);
        return company != null && company.can(player, permission);
    }

    public boolean offer(Shop shop, String item, long sellPrice, long buyPrice, long buyLimit) {
        if (sellPrice < 0 || buyPrice < 0 || buyLimit < 0 || sellPrice > OrderBook.MAX_PRICE || buyPrice > OrderBook.MAX_PRICE) return false;
        if (sellPrice == 0 && buyPrice == 0) shop.offers.remove(item);
        else shop.offers.put(item, new Offer(sellPrice, buyPrice, buyLimit));
        return true;
    }

    /** How many of the item the shop can still take in. */
    public long room(Shop shop, long storage) {
        return shop.admin() ? Long.MAX_VALUE : Math.max(0, storage - shop.stored());
    }

    /** A customer buys from the shop: the tax goes to the server and the rest to the company. */
    public boolean buy(UUID buyer, Shop shop, String item, long quantity, long tax, Accounts accounts) {
        var offer = shop.offers.get(item);
        if (offer == null || offer.sellPrice() == 0 || quantity <= 0 || quantity > OrderBook.MAX_QUANTITY) return false;
        if (!shop.admin() && shop.stock.getOrDefault(item, 0L) < quantity) return false;
        long cost = offer.sellPrice() * quantity;
        if (accounts.balance(buyer) < cost) return false;
        tax = shop.admin() ? 0 : Math.max(0, Math.min(cost, tax));
        if (tax > 0 && !accounts.release(buyer, Accounts.SERVER, tax)) return false;
        if (cost > tax && !accounts.release(buyer, account(shop.company), cost - tax)) {
            if (tax > 0) accounts.refund(buyer, tax);
            return false;
        }
        if (!shop.admin() && shop.stock.merge(item, -quantity, Long::sum) == 0) shop.stock.remove(item);
        return true;
    }

    /** Whether the shop would buy this from a customer right now; the caller then takes the items and calls sell. */
    public boolean wants(Shop shop, String item, long quantity, long storage, Accounts accounts) {
        var offer = shop.offers.get(item);
        return offer != null && offer.buyPrice() > 0 && quantity > 0 && quantity <= OrderBook.MAX_QUANTITY && (shop.admin() || offer.buyLimit() >= quantity)
                && room(shop, storage) >= quantity && (shop.admin() || accounts.balance(account(shop.company)) >= offer.buyPrice() * quantity);
    }

    /** A customer sells to the shop. False only when the company can no longer pay; nothing has changed then. */
    public boolean sell(UUID seller, Shop shop, String item, long quantity, long storage, Accounts accounts, java.util.function.LongPredicate serverPays) {
        if (!wants(shop, item, quantity, storage, accounts)) return false;
        var offer = shop.offers.get(item);
        long pay = offer.buyPrice() * quantity;
        if (shop.admin() ? !serverPays.test(pay) : !accounts.transfer(account(shop.company), seller, pay)) return false;
        if (!shop.admin()) {
            shop.stock.merge(item, quantity, Long::sum);
            shop.offers.put(item, new Offer(offer.sellPrice(), offer.buyPrice(), offer.buyLimit() - quantity));
        }
        return true;
    }

    /** Every sell offer for items matching the search, cheapest first. */
    public List<Map.Entry<Shop, Map.Entry<String, Offer>>> find(String search) {
        String wanted = search.toLowerCase(Locale.ROOT);
        var found = new ArrayList<Map.Entry<Shop, Map.Entry<String, Offer>>>();
        for (var shop : shops.values()) {
            var company = companies.get(shop.company);
            boolean byShop = shop.name.toLowerCase(Locale.ROOT).contains(wanted) || company != null && company.name.toLowerCase(Locale.ROOT).contains(wanted);
            for (var offer : shop.offers.entrySet()) {
                if (byShop || offer.getKey().contains(wanted)) found.add(Map.entry(shop, offer));
            }
        }
        found.sort(Comparator.comparingLong(entry -> entry.getValue().getValue().sellPrice() == 0 ? Long.MAX_VALUE : entry.getValue().getValue().sellPrice()));
        return found;
    }
}
