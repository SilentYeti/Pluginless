package com.silentyeti.pluginless;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/** Player company management and shop trading, using the commands' existing validation and accounting. */
final class CompaniesPage implements PdaPage {
    static final int ROWS = 8;
    private static final class Selection {
        String tab = "mine", search = "";
        long company, shop;
        int page, memberPage, offerPage;
    }
    private final Map<ServerPlayer, Selection> selections = new WeakHashMap<>();

    public String id() { return "companies"; }

    public JsonObject view(MinecraftServer server, ServerPlayer player) {
        var state = selections.computeIfAbsent(player, key -> new Selection());
        var book = Companies.store(server).value;
        var who = player.getUUID();
        var view = new JsonObject();
        view.addProperty("tab", state.tab);
        view.addProperty("search", state.search);
        view.addProperty("fee", Config.companyFeeCents());
        var companies = book.companies.values().stream()
                .filter(c -> !state.tab.equals("mine") || c.owner.equals(who) || c.members.containsKey(who))
                .filter(c -> matches(c.name + " " + c.description, state.search)).toList();
        var shops = book.shops.values().stream().filter(s -> {
            var owner = book.companies.get(s.company);
            return matches(s.name + " " + s.description + " " + (owner == null ? "server" : owner.name)
                    + " " + String.join(" ", s.offers.keySet()), state.search);
        }).toList();
        int count = state.tab.equals("shops") ? shops.size() : companies.size();
        state.page = clamp(state.page, count);
        view.addProperty("page", state.page + 1);
        view.addProperty("pages", Math.max(1, (count + ROWS - 1) / ROWS));
        var rows = new JsonArray();
        if (state.tab.equals("shops")) {
            for (var shop : shops.stream().skip((long) state.page * ROWS).limit(ROWS).toList()) {
                var row = new JsonObject();
                row.addProperty("id", shop.id); row.addProperty("name", shop.name);
                var owner = book.companies.get(shop.company);
                row.addProperty("line", owner == null ? "SERVER SHOP" : owner.name);
                rows.add(row);
            }
        } else {
            for (var company : companies.stream().skip((long) state.page * ROWS).limit(ROWS).toList()) {
                var row = new JsonObject();
                row.addProperty("id", company.id); row.addProperty("name", company.name);
                row.addProperty("line", company.owner.equals(who) ? "OWNER" : company.members.getOrDefault(who, "PUBLIC"));
                rows.add(row);
            }
        }
        view.add("rows", rows);
        var company = book.companies.get(state.company);
        if (!companies.contains(company)) company = null;
        if (company == null && !companies.isEmpty()) company = companies.get(0);
        if (company != null) {
            state.company = company.id;
            var detail = new JsonObject();
            detail.addProperty("name", company.name);
            detail.addProperty("owner", Commands.name(server, company.owner));
            detail.addProperty("balance", Ledger.of(server).balance(CompanyBook.account(company.id)));
            detail.addProperty("manage", company.can(who, "hire"));
            detail.addProperty("spend", company.can(who, "spend") && !Shares.locked(server, company));
            detail.addProperty("own", company.owner.equals(who));
            detail.addProperty("member", company.members.containsKey(who));
            long interval = Config.payrollMillis();
            detail.addProperty("payroll", interval == 0 ? "OFF" : "EVERY " + interval / 60000 + " MIN");
            detail.addProperty("next", interval == 0 ? 0 : Math.max(0, interval - (System.currentTimeMillis() - book.lastPayroll)) / 1000);
            long total = company.wages.values().stream().mapToLong(Long::longValue).sum();
            detail.addProperty("wages", total);
            var members = new JsonArray();
            state.memberPage = clamp(state.memberPage, company.members.size());
            for (var member : company.members.entrySet().stream().skip((long) state.memberPage * ROWS).limit(ROWS).toList()) {
                var row = new JsonObject();
                row.addProperty("name", Commands.name(server, member.getKey()));
                row.addProperty("role", member.getValue());
                row.addProperty("wage", company.wages.getOrDefault(member.getKey(), 0L));
                members.add(row);
            }
            detail.add("members", members);
            detail.addProperty("memberPage", state.memberPage + 1);
            detail.addProperty("memberPages", Math.max(1, (company.members.size() + ROWS - 1) / ROWS));
            view.add("company", detail);
        }
        var shop = book.shops.get(state.shop);
        if (!shops.contains(shop)) shop = null;
        if (shop == null && !shops.isEmpty()) shop = shops.get(0);
        if (shop != null) {
            state.shop = shop.id;
            var detail = new JsonObject();
            detail.addProperty("name", shop.name);
            detail.addProperty("admin", shop.admin());
            var owner = book.companies.get(shop.company);
            detail.addProperty("owner", owner == null ? "SERVER" : owner.name);
            detail.addProperty("edit", !shop.admin() && book.can(who, shop, "shops", false));
            detail.addProperty("stock", !shop.admin() && book.can(who, shop, "stock", false));
            detail.addProperty("earnings", owner != null && owner.can(who, "stock")
                    ? Config.format(Ledger.of(server).balance(CompanyBook.account(owner.id))) : "");
            var history = new JsonArray();
            if (owner != null && owner.can(who, "stock")) {
                var account = CompanyBook.account(owner.id);
                var transactions = Ledger.history(server, account);
                for (int i = transactions.size() - 1; i >= Math.max(0, transactions.size() - 5); i--) {
                    var transaction = transactions.get(i);
                    var row = new JsonObject();
                    row.addProperty("amount", transaction.to().equals(account.toString()) ? transaction.amount() : -transaction.amount());
                    row.addProperty("reason", transaction.reason());
                    history.add(row);
                }
            }
            detail.add("history", history);
            var offers = new JsonArray();
            state.offerPage = clamp(state.offerPage, shop.offers.size());
            for (var offer : shop.offers.entrySet().stream().skip((long) state.offerPage * ROWS).limit(ROWS).toList()) {
                var row = new JsonObject();
                row.addProperty("item", offer.getKey());
                row.addProperty("sell", offer.getValue().sellPrice());
                row.addProperty("buy", offer.getValue().buyPrice());
                row.addProperty("limit", offer.getValue().buyLimit());
                row.addProperty("stock", shop.stock.getOrDefault(offer.getKey(), 0L));
                offers.add(row);
            }
            detail.add("offers", offers);
            detail.addProperty("offerPage", state.offerPage + 1);
            detail.addProperty("offerPages", Math.max(1, (shop.offers.size() + ROWS - 1) / ROWS));
            view.add("shop", detail);
        }
        return view;
    }

    private static boolean matches(String text, String search) {
        return text.toLowerCase(java.util.Locale.ROOT).contains(search.toLowerCase(java.util.Locale.ROOT));
    }

    private static int clamp(int page, int count) { return Math.max(0, Math.min(page, Math.max(0, (count - 1) / ROWS))); }

    public Result act(ServerPlayer player, String verb, List<String> args, int slot) {
        var state = selections.computeIfAbsent(player, key -> new Selection());
        if (args.size() == 1) {
            String arg = args.get(0);
            if (verb.equals("tab") && List.of("mine", "directory", "shops").contains(arg)) {
                state.tab = arg; state.page = 0; state.search = "";
                return Result.ok("");
            }
            if (verb.equals("search") && arg.length() <= PdaHub.MAX_ARG_LENGTH) {
                state.search = arg; state.page = 0;
                return Result.ok("");
            }
            if (List.of("page", "members", "offers").contains(verb) && List.of("-1", "1").contains(arg)) {
                int delta = Integer.parseInt(arg);
                switch (verb) {
                    case "page" -> state.page += delta;
                    case "members" -> state.memberPage += delta;
                    case "offers" -> state.offerPage += delta;
                }
                return Result.ok("");
            }
            if (verb.equals("select") && arg.matches("[0-9]{1,18}")) {
                if (state.tab.equals("shops")) { state.shop = Long.parseLong(arg); state.offerPage = 0; }
                else { state.company = Long.parseLong(arg); state.memberPage = 0; }
                return Result.ok("");
            }
        }
        if (verb.equals("shop_stock")) {
            if (args.size() != 3 || slot < 0 || slot >= 36) return Result.fail("pluginless.pda.invalid");
            var stack = player.getInventory().getItem(slot);
            if (stack.isEmpty() || !Stacks.id(stack).equals(args.get(1))) return Result.fail("pluginless.shop.failed");
            args = List.of(args.get(0), Stacks.id(stack), args.get(2));
        }
        String command = CompaniesIntent.command(verb, args);
        if (command == null) return Result.fail("pluginless.pda.invalid");
        // Even an operator cannot edit an admin shop through the player PDA.
        if (verb.startsWith("shop_") && !List.of("shop_open", "shop_buy", "shop_sell").contains(verb)) {
            var shop = Companies.store(player.level().getServer()).value.shop(args.get(0));
            if (shop == null || shop.admin()) return Result.fail("pluginless.shop.failed");
        }
        try {
            var source = player.createCommandSourceStack();
            int result = player.level().getServer().getCommands().getDispatcher().execute(command, source);
            return result > 0 ? Result.ok("pluginless.feature.done")
                    : Result.fail(verb.startsWith("shop_") ? "pluginless.shop.failed" : "pluginless.company.failed");
        } catch (CommandSyntaxException e) {
            return Result.fail("pluginless.pda.invalid");
        }
    }

}
