/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.function.Function;

/** Removed members the big content mods call, each rebuilt on its 1.21.11 or 26.x replacement where the host lacks it. */
public final class LegacyContentCallsBridge {

    static final String WORLD = RebuiltApiClasses.ROOT + "minecraft/world/LegacyContentCalls";
    static final String PARTICLES = RebuiltApiClasses.ROOT + "minecraft/world/LegacyItemParticles";
    static final String SIGNS = RebuiltApiClasses.ROOT + "minecraft/world/LegacySignTexts";
    static final String SCREENS = RebuiltApiClasses.ROOT + "minecraft/client/LegacyScreenCalls";

    static final String BIOME = "net/minecraft/world/level/biome/Biome";
    static final String HOLDER = "net/minecraft/world/item/crafting/RecipeHolder";
    static final String SHAPED = "net/minecraft/data/recipes/ShapedRecipeBuilder";
    static final String SHAPELESS = "net/minecraft/data/recipes/ShapelessRecipeBuilder";
    static final String PARTICLE = "net/minecraft/core/particles/ItemParticleOption";
    static final String SIGN = "net/minecraft/world/level/block/entity/SignBlockEntity";
    static final String SCREEN = "net/minecraft/client/gui/screens/Screen";
    static final String MODEL_PART = "net/minecraft/client/model/geom/ModelPart";
    static final String SPLITTER = "net/minecraft/client/StringSplitter";

    static final String POS = "Lnet/minecraft/core/BlockPos;";
    static final String CATEGORY = "Lnet/minecraft/data/recipes/RecipeCategory;";
    static final String ITEM_LIKE = "Lnet/minecraft/world/level/ItemLike;";
    static final String SIGN_TEXT = "Lnet/minecraft/world/level/block/entity/SignText;";
    static final String STYLE = "Lnet/minecraft/network/chat/Style;";

    private LegacyContentCallsBridge() {}

    /** Registers each bridge whose old member the host lacks; offline, the shim's host gate and the target version decide. */
    public static void register(RetromodTransformer t) {
        register(t, ClassResourceInspector::read);
    }

    static void register(RetromodTransformer t, Function<String, ClassNode> host) {
        registerWorld(t, host);
        registerClient(t, host);
    }

    static void registerWorld(RetromodTransformer t, Function<String, ClassNode> host) {
        ClassNode biome = host.apply(BIOME);
        String precipitation = "Lnet/minecraft/world/level/biome/Biome$Precipitation;";
        boolean world = false;
        for (String[] call : new String[][]{{"getPrecipitationAt", "(" + POS + ")" + precipitation},
                {"coldEnoughToSnow", "(" + POS + ")Z"}, {"warmEnoughToRain", "(" + POS + ")Z"}}) {
            if (!lacks(biome, call[0], call[1], "1.21.11")) continue;
            world = true;
            t.registerMethodRedirect(BIOME, call[0], call[1], WORLD, call[0], "(L" + BIOME + ";" + call[1].substring(1), true);
        }
        String oldHolder = "(Lnet/minecraft/resources/Identifier;Lnet/minecraft/world/item/crafting/Recipe;)V";
        if (lacks(host.apply(HOLDER), "<init>", oldHolder, "1.21.11")) {
            world = true;
            t.registerConstructorRedirect(HOLDER, oldHolder, WORLD, "recipeHolder",
                    "(Lnet/minecraft/resources/Identifier;Lnet/minecraft/world/item/crafting/Recipe;)L" + HOLDER + ";");
        }
        for (String[] builder : new String[][]{{SHAPED, "shaped"}, {SHAPELESS, "shapeless"}}) {
            String returned = "L" + builder[0] + ";";
            for (String args : new String[]{CATEGORY + ITEM_LIKE, CATEGORY + ITEM_LIKE + "I"}) {
                String desc = "(" + args + ")" + returned;
                if (!lacks(host.apply(builder[0]), builder[1], desc, "1.21.11")) continue;
                world = true;
                t.registerMethodRedirect(builder[0], builder[1], desc, WORLD, builder[1], desc);
            }
        }
        if (world) RebuiltApiClasses.registerTree(t, WORLD);
        // 26.1 takes the particle's item as a stack template.
        String oldParticle = "(Lnet/minecraft/core/particles/ParticleType;Lnet/minecraft/world/item/ItemStack;)V";
        if (lacks(host.apply(PARTICLE), "<init>", oldParticle, "26.1")) {
            RebuiltApiClasses.registerTree(t, PARTICLES);
            t.registerConstructorRedirect(PARTICLE, oldParticle, PARTICLES, "itemParticle",
                    "(Lnet/minecraft/core/particles/ParticleType;Lnet/minecraft/world/item/ItemStack;)L" + PARTICLE + ";");
        }
        // 26.3 reads a sign's text by slot.
        ClassNode sign = host.apply(SIGN);
        if (lacks(sign, "getFrontText", "()" + SIGN_TEXT, "26.3")) {
            RebuiltApiClasses.registerTree(t, SIGNS);
            String self = "(L" + SIGN + ";";
            t.registerMethodRedirect(SIGN, "getFrontText", "()" + SIGN_TEXT, SIGNS, "getFrontText", self + ")" + SIGN_TEXT, true);
            t.registerMethodRedirect(SIGN, "getBackText", "()" + SIGN_TEXT, SIGNS, "getBackText", self + ")" + SIGN_TEXT, true);
            t.registerMethodRedirect(SIGN, "getText", "(Z)" + SIGN_TEXT, SIGNS, "getText", self + "Z)" + SIGN_TEXT, true);
        }
    }

    static void registerClient(RetromodTransformer t, Function<String, ClassNode> host) {
        boolean client = false;
        ClassNode screen = host.apply(SCREEN);
        String oldInit = "(Lnet/minecraft/client/Minecraft;II)V";
        if (lacks(screen, "init", oldInit, "1.21.11")) {
            client = true;
            // Mods call it on their own screen classes, so subclasses are matched through the hierarchy.
            String helper = "(L" + SCREEN + ";Lnet/minecraft/client/Minecraft;II)V";
            t.registerInheritedMethodRedirect(SCREEN, "init", oldInit, SCREENS, "init", helper);
            t.registerMethodRedirect(SCREEN, "init", oldInit, SCREENS, "init", helper, true);
        }
        for (String check : new String[]{"isCopy", "isCut", "isPaste", "isSelectAll"}) {
            if (!lacks(screen, check, "(I)Z", "1.21.11")) continue;
            client = true;
            t.registerInheritedMethodRedirect(SCREEN, check, "(I)Z", SCREENS, check, "(I)Z");
            t.registerMethodRedirect(SCREEN, check, "(I)Z", SCREENS, check, "(I)Z");
        }
        String copy = "(L" + MODEL_PART + ";)V";
        if (lacks(host.apply(MODEL_PART), "copyFrom", copy, "1.21.11")) {
            client = true;
            t.registerMethodRedirect(MODEL_PART, "copyFrom", copy, SCREENS, "copyFrom", "(L" + MODEL_PART + ";L" + MODEL_PART + ";)V", true);
        }
        ClassNode splitter = host.apply(SPLITTER);
        for (String text : new String[]{"Lnet/minecraft/util/FormattedCharSequence;", "Lnet/minecraft/network/chat/FormattedText;"}) {
            String desc = "(" + text + "I)" + STYLE;
            if (!lacks(splitter, "componentStyleAtWidth", desc, "1.21.11")) continue;
            client = true;
            t.registerMethodRedirect(SPLITTER, "componentStyleAtWidth", desc, SCREENS, "componentStyleAtWidth",
                    "(L" + SPLITTER + ";" + desc.substring(1), true);
        }
        if (client) RebuiltApiClasses.registerTree(t, SCREENS);
    }

    /** Whether the host lacks the member; an unreadable host lacks it from {@code since} on. */
    static boolean lacks(ClassNode owner, String name, String desc, String since) {
        if (owner == null) return RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, since) >= 0;
        for (MethodNode method : owner.methods) {
            if (method.name.equals(name) && method.desc.equals(desc)) return false;
        }
        return true;
    }
}
