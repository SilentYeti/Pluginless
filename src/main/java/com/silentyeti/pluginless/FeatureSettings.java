package com.silentyeti.pluginless;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Collection settings in features.json; scalar settings live in Config. */
public final class FeatureSettings {
    public Map<String, Double> commandCosts = new LinkedHashMap<>();
    public List<String> rtpDimensions = List.of("minecraft:overworld");
    public List<String> blockedWords = List.of("admin", "moderator");
    public Map<String, String> titles = new LinkedHashMap<>(Map.of("builder", "&e[Builder]"));

    public Map<String, Double> mobRewards = new LinkedHashMap<>(Map.of("minecraft:zombie", 1.0, "minecraft:skeleton", 1.0, "minecraft:creeper", 1.5));
    public Map<String, Double> sellPrices = new LinkedHashMap<>(Map.of("minecraft:iron_ingot", 2.0, "minecraft:gold_ingot", 4.0, "minecraft:diamond", 25.0));
    /** Listing fee percent by price: the highest threshold (in currency units) the price reaches applies. */
    public Map<String, Double> auctionFeeTiers = new LinkedHashMap<>(Map.of("0", 1.0, "10000", 2.0, "100000", 2.5));
    /** A category matches an item whose id contains any of its fragments. */
    public Map<String, List<String>> auctionCategories = new LinkedHashMap<>(Map.of(
            "tools", List.of("pickaxe", "_axe", "shovel", "_hoe", "shears"), "combat", List.of("sword", "bow", "helmet", "chestplate", "leggings", "boots", "shield"),
            "food", List.of("bread", "apple", "cooked", "stew", "carrot", "potato")));
    /** Plain items that trade on the order market. */
    public List<String> marketItems = List.of("minecraft:cobblestone", "minecraft:oak_log", "minecraft:coal", "minecraft:iron_ingot",
            "minecraft:gold_ingot", "minecraft:redstone", "minecraft:diamond", "minecraft:wheat");
    public List<String> auctionBlockedItems = List.of("minecraft:bedrock", "minecraft:command_block");

    public String problem() {
        if (mobRewards == null || sellPrices == null || auctionFeeTiers == null || auctionCategories == null || auctionBlockedItems == null) return "a configuration collection is null";
        for (var prices : List.of(mobRewards, sellPrices)) for (var entry : prices.entrySet()) {
            if (entry.getKey() == null || !entry.getKey().matches("[a-z0-9_.-]+:[a-z0-9/._-]+") || !amount(entry.getValue())) return "invalid reward or sell price";
        }
        for (var tier : auctionFeeTiers.entrySet()) {
            if (tier.getKey() == null || !tier.getKey().matches("[0-9]{1,12}") || tier.getValue() == null || !(tier.getValue() >= 0 && tier.getValue() <= 100)) return "invalid auction fee tier";
        }
        for (var category : auctionCategories.entrySet()) {
            if (!key(category.getKey()) || category.getValue() == null || category.getValue().stream().anyMatch(part -> part == null || part.isBlank())) return "invalid auction category";
        }
        if (marketItems == null || marketItems.stream().anyMatch(id -> id == null || !id.matches("[a-z0-9_.-]+:[a-z0-9/._-]+"))) return "invalid market item";
        if (auctionBlockedItems.stream().anyMatch(id -> id == null || id.isBlank())) return "invalid blocked auction item";
        if (commandCosts == null || rtpDimensions == null || blockedWords == null || titles == null) return "a configuration collection is null";
        for (var entry : commandCosts.entrySet()) {
            if (entry.getKey() == null || !List.of("spawn", "setspawn", "home", "sethome", "delhome", "homes", "tpa", "tpahere", "tpaccept", "tpdeny", "rtp", "back", "warp", "setwarp", "delwarp").contains(entry.getKey())
                    || entry.getValue() == null || !Double.isFinite(entry.getValue()) || entry.getValue() < 0 || entry.getValue() > 1e15) return "invalid command cost";
        }
        if (rtpDimensions.stream().anyMatch(id -> id == null || !id.matches("[a-z0-9_.-]+:[a-z0-9/._-]+"))) return "invalid RTP dimension";
        if (blockedWords.stream().anyMatch(word -> word == null || word.isBlank())) return "invalid blocked word";
        for (var title : titles.entrySet()) {
            if (!key(title.getKey()) || title.getKey().equals("off") || title.getValue() == null || title.getValue().length() > 256
                    || title.getValue().codePoints().anyMatch(Character::isISOControl)) return "invalid title";
        }
        return null;
    }

    private static boolean amount(Double value) {
        return value != null && Double.isFinite(value) && value >= 0 && value <= 1e12;
    }

    public static boolean key(String name) {
        return name != null && name.matches("[a-zA-Z0-9_-]{1,32}");
    }
}
