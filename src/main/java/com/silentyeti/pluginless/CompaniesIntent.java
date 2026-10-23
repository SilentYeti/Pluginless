package com.silentyeti.pluginless;

import java.util.List;

final class CompaniesIntent {
    /** Fixed command roots and single tokens: client input cannot append commands, selectors or arguments. */
    static String command(String verb, List<String> args) {
        int count = switch (verb) {
            case "create", "leave", "dissolve", "shop_close" -> 1;
            case "deposit", "withdraw", "fire", "shop_open" -> 2;
            case "hire", "wage", "shop_buy", "shop_sell", "shop_stock", "shop_unstock" -> 3;
            case "shop_offer" -> 5;
            default -> -1;
        };
        if (args.size() != count || args.stream().anyMatch(a -> a.length() > 64 || !a.matches("[A-Za-z0-9_:./-]+"))) return null;
        return (verb.startsWith("shop_") ? "shop " + verb.substring(5) : "company " + verb) + " " + String.join(" ", args);
    }
}
