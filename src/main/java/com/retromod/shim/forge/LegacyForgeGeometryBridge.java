/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import com.retromod.shim.common.RebuiltApiClasses;
import org.objectweb.asm.tree.ClassNode;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/** Rebuilds Forge 1.20.1's geometry loaders, and the model baking calls old geometry makes, inside each old mod. */
public final class LegacyForgeGeometryBridge {

    static final String REGISTER_GEOMETRY_LOADERS = "net/minecraftforge/client/event/ModelEvent$RegisterGeometryLoaders";
    static final String OLD_GEOMETRY_LOADER = "net/minecraftforge/client/model/geometry/IGeometryLoader";
    static final String OLD_UNBAKED_GEOMETRY = "net/minecraftforge/client/model/geometry/IUnbakedGeometry";
    static final String OLD_BAKING_CONTEXT = "net/minecraftforge/client/model/geometry/IGeometryBakingContext";
    static final String MODEL_BAKER = "net/minecraft/client/resources/model/ModelBaker";
    static final String UNBAKED_MODEL = "net/minecraft/client/resources/model/UnbakedModel";
    /** Minecraft 1.20.1's JSON model; Minecraft 26.1 gave the name to a block render model. */
    static final String BLOCK_MODEL = "net/minecraft/client/renderer/block/model/BlockModel";

    static final String ROOT = LegacyForgeBakedModelBridge.ROOT;
    static final String GEOMETRY_LOADER = ROOT + "LegacyGeometryLoader";
    static final String UNBAKED_GEOMETRY = ROOT + "IUnbakedGeometry";
    static final String BAKING_CONTEXT = ROOT + "LegacyGeometryBakingContext";
    static final String BAKERS = ROOT + "LegacyModelBakers";
    static final String LEGACY_BLOCK_MODEL = ROOT + "LegacyBlockModel";
    /** The rebuilt class whose {@code install()} adds this copy's geometry listeners, on a client only. */
    public static final String INSTALLER = ROOT + "LegacyGeometryLoaders";

    // Minecraft 26.1 renamed the JSON model to CuboidModel and reused its old name, so only older mods mean the old model.
    static final String FIRST_REUSED_BLOCK_MODEL = "26.1";

    private static final String ID = "Lnet/minecraft/resources/Identifier;";
    private static final String STATE = "Lnet/minecraft/client/renderer/block/dispatch/ModelState;";
    private static final String FUNCTION = "Ljava/util/function/Function;";
    private static final String LIST = "Ljava/util/List;";
    private static final String OBJECT = "Ljava/lang/Object;";
    private static final String OLD_MODEL = "L" + LegacyForgeBakedModelBridge.OLD_BAKED_MODEL + ";";
    private static final String BAKER = "L" + MODEL_BAKER + ";";
    private static final String UNBAKED = "L" + UNBAKED_MODEL + ";";
    private static final String BLOCK = "L" + BLOCK_MODEL + ";";

    /** Each removed class an old mod names, and the class rebuilt in its place. */
    static final Map<String, String> REBUILT = rebuilt();

    private LegacyForgeGeometryBridge() {}

    private static Map<String, String> rebuilt() {
        Map<String, String> names = new LinkedHashMap<>();
        names.put(OLD_GEOMETRY_LOADER, GEOMETRY_LOADER);
        names.put(OLD_UNBAKED_GEOMETRY, UNBAKED_GEOMETRY);
        names.put(OLD_BAKING_CONTEXT, BAKING_CONTEXT);
        return names;
    }

    /** Registers the rebuilt geometry API; {@link LegacyForgeModelBridge} calls it right after the baked model API. */
    static void register(RetromodTransformer t, Function<String, ClassNode> host) {
        // The same host as the baked model API, whose geometry event takes the new loaders and which has no old geometry.
        if (!ForgeHostShape.matches(List.of(LegacyForgeBakedModelBridge.MODIFY_BAKING_RESULT, REGISTER_GEOMETRY_LOADERS,
                OLD_GEOMETRY_LOADER), List.of(LegacyForgeBakedModelBridge.OLD_BAKED_MODEL,
                LegacyForgeBakedModelBridge.OLD_REGISTER_ADDITIONAL, OLD_UNBAKED_GEOMETRY), ForgeHostShape.FIRST_CHECKED_VERSION, host)) {
            return;
        }
        for (String rebuilt : List.of(GEOMETRY_LOADER, UNBAKED_GEOMETRY, BAKING_CONTEXT, BAKERS, LEGACY_BLOCK_MODEL)) {
            RebuiltApiClasses.registerTree(t, rebuilt);
        }
        // Forge 26.2 kept IGeometryLoader and IGeometryBakingContext with new shapes, so only older mods move.
        REBUILT.forEach((old, rebuilt) -> {
            t.registerClassRedirect(old, rebuilt);
            t.limitClassRedirectToModsBefore(old, ForgeHostShape.FIRST_CHECKED_VERSION);
        });
        // The old register(String, loader) took the mod's namespace, which the helper finds again.
        instanceCall(t, REGISTER_GEOMETRY_LOADERS, "register", "(Ljava/lang/String;L" + OLD_GEOMETRY_LOADER + ";)V",
                INSTALLER, "register", "(L" + REGISTER_GEOMETRY_LOADERS + ";Ljava/lang/String;L" + GEOMETRY_LOADER + ";)V",
                ForgeHostShape.FIRST_CHECKED_VERSION);
        registerBakerCalls(t);
        registerJsonModelCalls(t);

        // Each jar's copy registers its mods' loaders, Retromod's forge:composite and friends, and puts old models back.
        LegacyClientRenderBootstrapInjector.addPart(t, new LegacyClientRenderBootstrapInjector.Part("geometry",
                List.of(OLD_GEOMETRY_LOADER, OLD_UNBAKED_GEOMETRY, REGISTER_GEOMETRY_LOADERS, BLOCK_MODEL,
                        LegacyForgeBakedModelBridge.OLD_BAKED_MODEL), INSTALLER));
    }

    // The model lookups and bakes old geometry runs while Minecraft bakes, on names Minecraft 26.2 kept with new members.
    private static void registerBakerCalls(RetromodTransformer t) {
        String first = ForgeHostShape.FIRST_CHECKED_VERSION;
        instanceCall(t, MODEL_BAKER, "getModel", "(" + ID + ")" + UNBAKED, BAKERS, "getModel", "(" + BAKER + ID + ")" + UNBAKED, first);
        instanceCall(t, MODEL_BAKER, "bake", "(" + ID + STATE + ")" + OLD_MODEL, BAKERS, "bake",
                "(" + BAKER + ID + STATE + ")" + OLD_MODEL, first);
        instanceCall(t, MODEL_BAKER, "bake", "(" + ID + STATE + FUNCTION + ")" + OLD_MODEL, BAKERS, "bake",
                "(" + BAKER + ID + STATE + FUNCTION + ")" + OLD_MODEL, first);
        instanceCall(t, MODEL_BAKER, "getModelTextureGetter", "()" + FUNCTION, BAKERS, "getModelTextureGetter",
                "(" + BAKER + ")" + FUNCTION, first);
        String bakery = "L" + LegacyForgeBakedModelBridge.MODEL_BAKERY + ";";
        instanceCall(t, LegacyForgeBakedModelBridge.MODEL_BAKERY, "getModel", "(" + ID + ")" + UNBAKED, BAKERS, "getModel",
                "(" + bakery + ID + ")" + UNBAKED, first);
        instanceCall(t, UNBAKED_MODEL, "bake", "(" + BAKER + FUNCTION + STATE + ID + ")" + OLD_MODEL, BAKERS, "bake",
                "(" + UNBAKED + BAKER + FUNCTION + STATE + ID + ")" + OLD_MODEL, first);
        // Minecraft 1.21 dropped the location from bake, and Forge 1.21 added bakeUncached.
        instanceCall(t, UNBAKED_MODEL, "bake", "(" + BAKER + FUNCTION + STATE + ")" + OLD_MODEL, BAKERS, "bake",
                "(" + UNBAKED + BAKER + FUNCTION + STATE + ")" + OLD_MODEL, first);
        instanceCall(t, MODEL_BAKER, "bakeUncached", "(" + UNBAKED + STATE + ")" + OLD_MODEL, BAKERS, "bakeUncached",
                "(" + BAKER + UNBAKED + STATE + ")" + OLD_MODEL, first);
        instanceCall(t, UNBAKED_MODEL, "resolveParents", "(" + FUNCTION + ")V", BAKERS, "resolveParents",
                "(" + UNBAKED + FUNCTION + ")V", first);
        instanceCall(t, UNBAKED_MODEL, "getDependencies", "()Ljava/util/Collection;", BAKERS, "getDependencies",
                "(" + UNBAKED + ")Ljava/util/Collection;", first);
    }

    // The old JSON model, which TravelersBackpack, Sophisticated Backpacks and Patchouli build or read.
    private static void registerJsonModelCalls(RetromodTransformer t) {
        String reused = FIRST_REUSED_BLOCK_MODEL;
        String guiLight = "L" + UNBAKED_MODEL + "$GuiLight;";
        String transforms = "Lnet/minecraft/client/resources/model/cuboid/ItemTransforms;";
        String constructor = "(" + ID + LIST + "Ljava/util/Map;Ljava/lang/Boolean;" + guiLight + transforms + LIST + ")";
        // The host's BlockModel is an interface with no constructor, so only old code can make this call.
        t.registerConstructorRedirect(BLOCK_MODEL, constructor + "V", LEGACY_BLOCK_MODEL, "create", constructor + BLOCK);
        instanceCall(t, BLOCK_MODEL, "getElements", "()" + LIST, BAKERS, "getElements", "(" + OBJECT + ")" + LIST, reused);
        instanceCall(t, BLOCK_MODEL, "resolveParents", "(" + FUNCTION + ")V", BAKERS, "resolveParents",
                "(" + OBJECT + FUNCTION + ")V", reused);
        instanceCall(t, BLOCK_MODEL, "bake", "(" + BAKER + FUNCTION + STATE + ID + ")" + OLD_MODEL, BAKERS, "bake",
                "(" + OBJECT + BAKER + FUNCTION + STATE + ID + ")" + OLD_MODEL, reused);
        instanceCall(t, BLOCK_MODEL, "bake", "(" + BAKER + BLOCK + FUNCTION + STATE + ID + "Z)" + OLD_MODEL, BAKERS, "bake",
                "(" + OBJECT + BAKER + OBJECT + FUNCTION + STATE + ID + "Z)" + OLD_MODEL, reused);
        // The same two bakes in Minecraft 1.21, without the location.
        instanceCall(t, BLOCK_MODEL, "bake", "(" + BAKER + FUNCTION + STATE + ")" + OLD_MODEL, BAKERS, "bake",
                "(" + OBJECT + BAKER + FUNCTION + STATE + ")" + OLD_MODEL, reused);
        instanceCall(t, BLOCK_MODEL, "bake", "(" + BAKER + BLOCK + FUNCTION + STATE + "Z)" + OLD_MODEL, BAKERS, "bake",
                "(" + OBJECT + BAKER + OBJECT + FUNCTION + STATE + "Z)" + OLD_MODEL, reused);
        String sprite = "Lnet/minecraft/client/resources/model/sprite/SpriteId;";
        instanceCall(t, BLOCK_MODEL, "getMaterial", "(Ljava/lang/String;)" + sprite, BAKERS, "getMaterial",
                "(" + OBJECT + "Ljava/lang/String;)" + sprite, reused);
        instanceCall(t, BLOCK_MODEL, "getRootModel", "()" + BLOCK, BAKERS, "getRootModel", "(" + OBJECT + ")" + BLOCK, reused);
        staticCall(t, BLOCK_MODEL, "fromStream", "(Ljava/io/Reader;)" + BLOCK, BAKERS, "fromStream", "(Ljava/io/Reader;)" + BLOCK, reused);
        staticCall(t, BLOCK_MODEL, "fromString", "(Ljava/lang/String;)" + BLOCK, BAKERS, "fromString", "(Ljava/lang/String;)" + BLOCK, reused);
    }

    // An instance call sent to a static helper that takes the receiver first, keyed in every spelling the call can
    // reach it in: with the old class names, and with the rebuilt ones once those redirects have run.
    private static void instanceCall(RetromodTransformer t, String owner, String name, String desc, String helper,
            String helperName, String helperDesc, String firstVersionWithout) {
        for (String spelling : spellings(desc)) {
            t.registerMethodRedirect(owner, name, spelling, helper, helperName, rebuiltNames(helperDesc), true);
            t.limitRedirectToModsBefore(owner, name, spelling, firstVersionWithout);
        }
    }

    private static void staticCall(RetromodTransformer t, String owner, String name, String desc, String helper,
            String helperName, String helperDesc, String firstVersionWithout) {
        t.registerMethodRedirect(owner, name, desc, helper, helperName, helperDesc);
        t.limitRedirectToModsBefore(owner, name, desc, firstVersionWithout);
    }

    private static Set<String> spellings(String desc) {
        Set<String> spellings = new LinkedHashSet<>();
        spellings.add(desc);
        spellings.add(rebuiltNames(desc));
        return spellings;
    }

    // The helpers take and return the rebuilt types.
    static String rebuiltNames(String desc) {
        String renamed = desc.replace("L" + LegacyForgeBakedModelBridge.OLD_BAKED_MODEL + ";",
                "L" + LegacyForgeBakedModelBridge.BAKED_MODEL + ";");
        for (Map.Entry<String, String> name : REBUILT.entrySet()) {
            renamed = renamed.replace("L" + name.getKey() + ";", "L" + name.getValue() + ";");
        }
        return renamed;
    }
}
