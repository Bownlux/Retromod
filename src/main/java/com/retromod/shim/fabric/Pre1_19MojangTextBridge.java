/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.fabric;

import com.retromod.core.RetromodTransformer;
import com.retromod.shim.common.RebuiltApiClasses;

/** A pre-1.19 Fabric mod's {@code LiteralText} and {@code TranslatableText} on 26.1 and newer, after the remap to Mojang names. */
public final class Pre1_19MojangTextBridge {

    static final String LITERAL = "net/minecraft/network/chat/contents/PlainTextContents$LiteralContents";
    static final String TRANSLATABLE = "net/minecraft/network/chat/contents/TranslatableContents";
    // The same classes under their 1.18 Mojang names, which multi-loader Fabric jars carry.
    static final String TEXT_COMPONENT = "net/minecraft/network/chat/TextComponent";
    static final String TRANSLATABLE_COMPONENT = "net/minecraft/network/chat/TranslatableComponent";
    static final String COMPONENT = "net/minecraft/network/chat/Component";
    static final String MUTABLE = "net/minecraft/network/chat/MutableComponent";
    static final String TEXTS = RebuiltApiClasses.ROOT + "minecraft/network/chat/LegacyOldTexts";

    static final String KEY = "(Ljava/lang/String;)V";
    static final String KEY_ARGS = "(Ljava/lang/String;[Ljava/lang/Object;)V";

    private Pre1_19MojangTextBridge() {}

    /**
     * The intermediary ids of the old text classes name the 26.x text contents records, so the remap turns
     * {@code new LiteralText(s).formatted(f)} into a contents record that has no {@code withStyle}. Creation goes
     * to the component factories and every other use to {@code MutableComponent}, as the 1.19 shim does for Forge.
     */
    public static void register(RetromodTransformer t) {
        for (String literal : new String[] {LITERAL, TEXT_COMPONENT}) {
            factory(t, literal, KEY, "literal");
            retype(t, literal);
        }
        for (String translatable : new String[] {TRANSLATABLE, TRANSLATABLE_COMPONENT}) {
            factory(t, translatable, KEY, "translatable");
            factory(t, translatable, KEY_ARGS, "translatable");
            retype(t, translatable);
        }
        // The getters only the old classes had read the component's contents instead.
        RebuiltApiClasses.registerTree(t, TEXTS);
        getter(t, TRANSLATABLE, "getKey", "()Ljava/lang/String;");
        getter(t, TRANSLATABLE, "getArgs", "()[Ljava/lang/Object;");
        getter(t, TRANSLATABLE, "getArgument", "(I)Ljava/lang/Object;");
        getter(t, LITERAL, "getText", "()Ljava/lang/String;");
        // The old classes' fields reach this pass on the retyped owner; MutableComponent has none of them.
        String[][] fields = {{"text", "textField", "Ljava/lang/String;"}, {"key", "keyField", "Ljava/lang/String;"},
                {"args", "argsField", "[Ljava/lang/Object;"}};
        for (String[] field : fields) {
            t.registerExactTypedFieldStaticBridge(MUTABLE, field[0], field[2], TEXTS, field[1], "(Ljava/lang/Object;)" + field[2],
                    null, null);
        }
        // LiteralText.EMPTY shares its id with the record's text field, so the remap names it text; it is the empty component.
        String component = "L" + COMPONENT + ";";
        t.registerExactFieldRedirect(MUTABLE, "text", component, "net/minecraft/network/chat/CommonComponents", "EMPTY", component);
    }

    private static void factory(RetromodTransformer t, String owner, String desc, String name) {
        t.registerConstructorRedirect(owner, desc, COMPONENT, name, desc.replace(")V", ")L" + MUTABLE + ";"), true);
        t.limitRedirectToModsBefore(owner, "<init>", desc, "1.19");
    }

    private static void retype(RetromodTransformer t, String owner) {
        t.registerClassRedirect(owner, MUTABLE);
        // MutableComponent is final, so a mod's own subclass of the old text class keeps its old superclass.
        t.keepClassRedirectOutOfSubclasses(owner);
        // A mod built for 1.19 or newer means the contents record, which the host still has, and so may a
        // mod that names no version: a 1.19 tooltip mod extends TranslatableContents.
        if (owner.startsWith("net/minecraft/network/chat/contents/")) {
            t.limitClassRedirectToKnownModsBefore(owner, "1.19");
        } else {
            t.limitClassRedirectToModsBefore(owner, "1.19");
        }
    }

    private static void getter(RetromodTransformer t, String owner, String name, String desc) {
        t.registerMethodRedirect(owner, name, desc, TEXTS, name, "(L" + COMPONENT + ";" + desc.substring(1), true);
        t.limitRedirectToModsBefore(owner, name, desc, "1.19");
    }
}
