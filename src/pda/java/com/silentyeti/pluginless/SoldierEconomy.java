package com.silentyeti.pluginless;

import com.wf.wflib.soldier.api.Pricing;
import com.wf.wflib.soldier.api.Recruiting;
import com.wf.wflib.soldier.api.SoldierRole;
import com.wf.wflib.soldier.api.Wallet;
import net.minecraft.server.level.ServerPlayer;

/**
 * Lets WFLib's soldier recruiting spend Pluginless money: hiring from the PDA, a barracks or a garrison, and the daily wage. WFLib is
 * an optional mod, so nothing else refers to this class and {@link Pluginless} loads it only when WFLib is there. A WFLib that
 * predates the recruiting API is left alone.
 */
final class SoldierEconomy {
    private static final String REASON = "soldiers";

    private SoldierEconomy() {}

    static void init() {
        try {
            Recruiting.wallet(new Wallet() {
                @Override public String unit() { return Config.symbol(); }

                @Override public String format(long amount) { return Config.format(amount * 100); }

                @Override public boolean has(ServerPlayer player, int amount) {
                    return ledger(player).balance(player.getUUID()) >= amount * 100L;
                }

                @Override public boolean take(ServerPlayer player, int amount) {
                    return ledger(player).release(player.getUUID(), Accounts.SERVER, amount * 100L);
                }

                @Override public long balance(ServerPlayer player) {
                    return ledger(player).balance(player.getUUID()) / 100;
                }
            });
            Recruiting.pricing(new Pricing() {
                @Override public int hire(String variant, SoldierRole role) {
                    return SoldierPrices.price(Config.soldierHire(), role.id, Config.soldierRoles());
                }

                @Override public int upkeep(SoldierRole role) {
                    return SoldierPrices.price(Config.soldierWage(), role.id, Config.soldierRoles());
                }

                @Override public boolean upkeepOn() { return Config.soldierUpkeep(); }
            });
        } catch (LinkageError e) {
            Pluginless.LOGGER.info("WFLib has no soldier recruiting API: soldiers stay free of Pluginless prices");
        }
    }

    private static Accounts ledger(ServerPlayer player) {
        return Ledger.of(player.getServer(), player.getUUID().toString(), REASON);
    }
}
