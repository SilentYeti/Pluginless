package com.silentyeti.pluginless;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSource;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.regex.Pattern;

/**
 * Runs a Pluginless command as the player, the way the ROTH screens do, and captures what it answers. A PDA action is
 * therefore subject to the same rank rules, cooldowns, fees and checks as typing the command; nothing is built twice.
 * Arguments are validated here so a client can never smuggle in more command than the page meant.
 */
public final class PdaCommands {
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");
    private static final Pattern WORD = Pattern.compile("[A-Za-z0-9_.:-]{1,48}");
    public static final long MAX_CENTS = 1_000_000_000_000_000L;

    private PdaCommands() {}

    /** A player name; null when it is not a plain name. */
    public static String name(String value) {
        return value != null && NAME.matcher(value).matches() ? value : null;
    }

    /** An identifier such as a ticker or item id; null when it carries anything else. */
    public static String word(String value) {
        return value != null && WORD.matcher(value).matches() ? value : null;
    }

    /** Cents as the decimal the commands take, e.g. 1250 -> "12.50"; null when out of range. */
    public static String amount(String cents) {
        try {
            long value = Long.parseLong(cents);
            if (value <= 0 || value > MAX_CENTS) return null;
            return "%d.%02d".formatted(value / 100, value % 100);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Runs {@code command} (no leading slash); fails with a generic message when it is rejected or throws. */
    public static PdaPage.Result run(ServerPlayer player, String command) {
        var server = player.level().getServer();
        if (server == null) return PdaPage.Result.fail("pluginless.pda.error");
        var lines = new ArrayList<String>();
        var capture = new CommandSource() {
            public void sendSystemMessage(Component message) { lines.add(message.getString()); }
            public boolean acceptsSuccess() { return true; }
            public boolean acceptsFailure() { return true; }
            public boolean shouldInformAdmins() { return false; }
        };
        var source = player.createCommandSourceStack().withSource(capture);
        int result;
        try {
            result = server.getCommands().getDispatcher().execute(command, source);
        } catch (CommandSyntaxException e) {
            return PdaPage.Result.fail(e.getRawMessage().getString());
        } catch (RuntimeException e) {
            Pluginless.LOGGER.error("PDA command failed: {}", command, e);
            return PdaPage.Result.fail("pluginless.pda.error");
        }
        var message = lines.isEmpty() ? "" : lines.get(lines.size() - 1);
        return new PdaPage.Result(result > 0, message);
    }
}
