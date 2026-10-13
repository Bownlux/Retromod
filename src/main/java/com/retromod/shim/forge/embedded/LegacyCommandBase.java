/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.embedded;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Stands in for 1.12.2's {@code CommandBase}, the parent of every mod command. The mod's own
 * {@code getName}, {@code execute}, and the rest are found by their shape when the command runs,
 * because their names depend on whether the SRG table knew them. The static helpers mods called
 * are kept under both their MCP and SRG names. Reference parameters and results are
 * {@code Object} because calls on stand-ins are erased to that shape and cast back.
 */
public abstract class LegacyCommandBase {

    public int getRequiredPermissionLevel() { return 4; }
    public int func_82362_a() { return getRequiredPermissionLevel(); }

    public Object getAliases() { return Collections.emptyList(); }
    public Object func_71514_a() { return getAliases(); }

    public static int parseInt(Object input) throws LegacyCommandException {
        try {
            return Integer.parseInt(String.valueOf(input));
        } catch (NumberFormatException e) {
            throw new LegacyCommandException.NumberInvalid("commands.generic.num.invalid", new Object[]{input});
        }
    }
    public static int func_175755_a(Object input) throws LegacyCommandException { return parseInt(input); }

    public static int parseInt(Object input, int min, int max) throws LegacyCommandException {
        int value = parseInt(input);
        if (value < min || value > max) {
            throw new LegacyCommandException.NumberInvalid("commands.generic.num.tooSmall", new Object[]{value, min});
        }
        return value;
    }
    public static int func_175764_a(Object input, int min, int max) throws LegacyCommandException {
        return parseInt(input, min, max);
    }

    public static double parseDouble(Object input) throws LegacyCommandException {
        try {
            return Double.parseDouble(String.valueOf(input));
        } catch (NumberFormatException e) {
            throw new LegacyCommandException.NumberInvalid("commands.generic.num.invalid", new Object[]{input});
        }
    }
    public static double func_175765_c(Object input) throws LegacyCommandException { return parseDouble(input); }

    /** {@code buildString(args, start)}: the arguments from {@code start}, joined by spaces. */
    public static Object buildString(Object args, int start) {
        String[] words = (String[]) args;
        return String.join(" ", Arrays.asList(words).subList(Math.min(start, words.length), words.length));
    }
    public static Object func_180529_a(Object args, int start) { return buildString(args, start); }

    /** {@code getListOfStringsMatchingLastWord(args, options...)} for tab completion. */
    public static Object getListOfStringsMatchingLastWord(Object args, Object options) {
        String[] words = (String[]) args;
        String last = words.length == 0 ? "" : words[words.length - 1].toLowerCase(Locale.ROOT);
        Collection<?> candidates = options instanceof Object[] array ? Arrays.asList(array) : (Collection<?>) options;
        List<String> out = new ArrayList<>();
        for (Object candidate : candidates) {
            String text = String.valueOf(candidate);
            if (text.toLowerCase(Locale.ROOT).startsWith(last)) out.add(text);
        }
        return out;
    }
    public static Object func_71530_a(Object args, Object options) { return getListOfStringsMatchingLastWord(args, options); }
    public static Object func_175762_a(Object args, Object options) { return getListOfStringsMatchingLastWord(args, options); }

    /** {@code getCommandSenderAsPlayer(sender)}: the sender when it is a player. */
    public static Object getCommandSenderAsPlayer(Object sender) throws LegacyCommandException {
        if (LegacyCommands.isPlayer(sender)) return sender;
        throw new LegacyCommandException("commands.generic.player.unspecified", new Object[0]);
    }
    public static Object func_71521_c(Object sender) throws LegacyCommandException { return getCommandSenderAsPlayer(sender); }

    /** {@code getPlayer(server, sender, name)}: an online player by name. */
    public static Object getPlayer(Object server, Object sender, Object name) throws LegacyCommandException {
        Object player = LegacyCommands.onlinePlayer(server, String.valueOf(name));
        if (player == null) {
            throw new LegacyCommandException.PlayerNotFound("commands.generic.player.notFound", new Object[]{name});
        }
        return player;
    }
    public static Object func_184888_a(Object server, Object sender, Object name) throws LegacyCommandException {
        return getPlayer(server, sender, name);
    }
}
