/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

/** Keeps 1.20.4's {@code ItemParser.parseForItem}, 1.20.5's {@code parse} and their result working where newer hosts replaced them. */
public final class LegacyItemParserBridge {

    static final String ITEM_PARSER = "net/minecraft/commands/arguments/item/ItemParser";
    static final String OLD_RESULT = ITEM_PARSER + "$ItemResult";
    static final String PARSER = RebuiltApiClasses.ROOT + "minecraft/commands/arguments/item/LegacyItemParser";
    static final String RESULT = RebuiltApiClasses.ROOT + "minecraft/commands/arguments/item/LegacyItemResult";
    static final String ITEM_INPUT = "net/minecraft/commands/arguments/item/ItemInput";
    private static final String READER = "Lcom/mojang/brigadier/StringReader;";
    private static final String ARGUMENTS = "(Lnet/minecraft/core/HolderLookup;Lcom/mojang/brigadier/StringReader;)";
    // The rebuilt parser takes the lookup as the getter it extends, the type its item lookup is declared on.
    static final String HELPER_DESC = "(Lnet/minecraft/core/HolderGetter;Lcom/mojang/brigadier/StringReader;)L" + RESULT + ";";

    private LegacyItemParserBridge() {}

    /** Registers the rebuilt parser and result on hosts whose ItemParser has no {@code parseForItem}; offline, from 1.20.5. */
    public static void register(RetromodTransformer transformer) {
        ClassNode parser = ClassResourceInspector.read(ITEM_PARSER);
        if (!hostDropped(parser)) return;
        registerRedirects(transformer);
        if (hostParsesToInput(parser)) {
            registerParseRedirects(transformer);
        } else {
            // The host still has the 1.20.5 result, so only an older mod's result is the rebuilt one.
            transformer.limitClassRedirectToModsBefore(OLD_RESULT, "1.20.5");
        }
    }

    /**
     * Sends 1.20.5 to 1.21's {@code parse}, whose result 26.1 replaced with {@code ItemInput}, to the rebuilt
     * parse, so a mod such as Carry On reads the item and components through the result it was built for.
     */
    static void registerParseRedirects(RetromodTransformer transformer) {
        for (String result : new String[] {OLD_RESULT, RESULT}) {
            transformer.registerMethodRedirect(ITEM_PARSER, "parse", "(" + READER + ")L" + result + ";",
                    PARSER, "parse", "(L" + ITEM_PARSER + ";" + READER + ")L" + RESULT + ";", true);
        }
    }

    /** Whether the host's {@code parse(StringReader)} returns {@code ItemInput}; {@code parser} is null offline, where 26.1 decides. */
    static boolean hostParsesToInput(ClassNode parser) {
        if (parser == null) return RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, "26.1") >= 0;
        for (MethodNode method : parser.methods) {
            if (method.name.equals("parse") && method.desc.equals("(" + READER + ")L" + ITEM_INPUT + ";")) return true;
        }
        return false;
    }

    /** Unconditional registration for transform-shape tests. */
    static void registerRedirects(RetromodTransformer transformer) {
        RebuiltApiClasses.registerTree(transformer, PARSER);
        transformer.registerClassRedirect(OLD_RESULT, RESULT);
        // The call is matched before or after the class redirect renames the result in its descriptor.
        for (String result : new String[] {OLD_RESULT, RESULT}) {
            transformer.registerMethodRedirect(ITEM_PARSER, "parseForItem", ARGUMENTS + "L" + result + ";",
                    PARSER, "parseForItem", HELPER_DESC);
        }
    }

    /** Whether the host's ItemParser lost the static {@code parseForItem}; {@code parser} is null offline, where the version decides. */
    static boolean hostDropped(ClassNode parser) {
        if (parser == null) return RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, "1.20.5") >= 0;
        for (MethodNode method : parser.methods) {
            if (method.name.equals("parseForItem") && (method.access & Opcodes.ACC_STATIC) != 0) return false;
        }
        return true;
    }
}
