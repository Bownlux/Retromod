/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import com.retromod.shim.common.LegacyBakedItemBridge;
import com.retromod.shim.common.LegacyItemDrawBridge;
import com.retromod.shim.common.RebuiltApiClasses;
import com.retromod.shim.common.HostBuffers;
import com.retromod.shim.common.RenderBufferSynthetics;
import org.objectweb.asm.tree.ClassNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Rebuilds Forge 1.20.1's BakedModel API, its model locations and its baking-result maps inside each old mod. */
public final class LegacyForgeBakedModelBridge {

    static final String OLD_BAKED_MODEL = "net/minecraft/client/resources/model/BakedModel";
    static final String OLD_FORGE_BAKED_MODEL = "net/minecraftforge/client/extensions/IForgeBakedModel";
    static final String OLD_DYNAMIC_MODEL = "net/minecraftforge/client/model/IDynamicBakedModel";
    static final String OLD_WRAPPER = "net/minecraftforge/client/model/BakedModelWrapper";
    static final String OLD_ITEM_OVERRIDES = "net/minecraft/client/renderer/block/model/ItemOverrides";
    static final String OLD_MODEL_LOCATION = "net/minecraft/client/resources/model/ModelResourceLocation";
    static final String OLD_REGISTER_ADDITIONAL = "net/minecraftforge/client/event/ModelEvent$RegisterAdditional";
    static final String MODIFY_BAKING_RESULT = "net/minecraftforge/client/event/ModelEvent$ModifyBakingResult";
    static final String BAKING_COMPLETED = "net/minecraftforge/client/event/ModelEvent$BakingCompleted";
    static final String MODEL_MANAGER = "net/minecraft/client/resources/model/ModelManager";
    static final String MODEL_BAKERY = "net/minecraft/client/resources/model/ModelBakery";
    static final String BLOCK_MODEL_SHAPER = "net/minecraft/client/renderer/block/BlockModelShaper";
    static final String IDENTIFIER = "net/minecraft/resources/Identifier";

    static final String ROOT = RebuiltApiClasses.ROOT + "forge/client/model/";
    static final String BAKED_MODEL = ROOT + "BakedModel";
    static final String LOCATIONS = ROOT + "LegacyModelLocations";
    static final String MAPS = ROOT + "LegacyModelMaps";
    static final String ITEM_DRAWS = ROOT + "LegacyForgeItemDraws";
    static final String REGISTER_ADDITIONAL = RebuiltApiClasses.ROOT + "forge/client/event/LegacyRegisterAdditional";
    /** The rebuilt class whose {@code install()} adds this copy's model event listeners, on a client only. */
    public static final String INSTALLER = ROOT + "LegacyModelEvents";

    private static final String STATE = "Lnet/minecraft/world/level/block/state/BlockState;";
    private static final String ID = "L" + IDENTIFIER + ";";
    private static final String STRING = "Ljava/lang/String;";
    private static final String MAP = "Ljava/util/Map;";

    /** Each removed class an old mod names, and the class rebuilt in its place. */
    static final Map<String, String> REBUILT = rebuilt();

    private LegacyForgeBakedModelBridge() {}

    private static Map<String, String> rebuilt() {
        Map<String, String> names = new LinkedHashMap<>();
        names.put(OLD_BAKED_MODEL, BAKED_MODEL);
        // Forge 1.20.1 split the old model's members across BakedModel and this extension; the rebuilt model has both.
        names.put(OLD_FORGE_BAKED_MODEL, BAKED_MODEL);
        names.put(OLD_DYNAMIC_MODEL, ROOT + "IDynamicBakedModel");
        names.put(OLD_WRAPPER, ROOT + "BakedModelWrapper");
        names.put(OLD_ITEM_OVERRIDES, ROOT + "ItemOverrides");
        // An old model location was an Identifier subclass and goes wherever an Identifier goes. Identifier is final
        // now, so a location is an Identifier whose path carries the variant.
        names.put(OLD_MODEL_LOCATION, IDENTIFIER);
        names.put(OLD_REGISTER_ADDITIONAL, REGISTER_ADDITIONAL);
        return names;
    }

    /** Registers the rebuilt model API; {@link LegacyForgeModelBridge} calls it behind its own gate. */
    static void register(RetromodTransformer t, Function<String, ClassNode> host) {
        // The host keeps the baking-result event and has no old model or RegisterAdditional; offline, a Forge target from 26.2.
        if (!ForgeHostShape.matches(List.of(MODIFY_BAKING_RESULT), List.of(OLD_BAKED_MODEL, OLD_REGISTER_ADDITIONAL),
                ForgeHostShape.FIRST_CHECKED_VERSION, host)) {
            return;
        }
        for (String rebuilt : List.of(BAKED_MODEL, LOCATIONS, MAPS, ITEM_DRAWS, REGISTER_ADDITIONAL, ROOT + "IDynamicBakedModel",
                ROOT + "BakedModelWrapper", ROOT + "LegacyBlockStateModel", ROOT + "LegacyItemModel")) {
            RebuiltApiClasses.registerTree(t, rebuilt);
        }
        // Class redirects first, so the member redirects below also match the calls once the classes are renamed.
        REBUILT.forEach((old, rebuilt) -> {
            t.registerClassRedirect(old, rebuilt);
            // A mod built for a host that has these names again keeps them.
            t.limitClassRedirectToModsBefore(old, ForgeHostShape.FIRST_CHECKED_VERSION);
        });
        registerLocations(t);
        registerModelMaps(t);
        registerItemDraws(t);

        // RegisterAdditional keeps Forge 1.20.1's priority and listener order rules in Retromod's dispatch.
        LegacyEventBaseAdapter.dispatchAsLegacy(t, List.of(OLD_REGISTER_ADDITIONAL));
        // Each jar's copy posts RegisterAdditional and keeps the latest bake while its mods construct.
        LegacyClientRenderBootstrapInjector.addPart(t, new LegacyClientRenderBootstrapInjector.Part("baked-models",
                List.of(OLD_REGISTER_ADDITIONAL, BAKING_COMPLETED, MODIFY_BAKING_RESULT, OLD_BAKED_MODEL, OLD_MODEL_LOCATION),
                INSTALLER));
    }

    private static void registerLocations(RetromodTransformer t) {
        String location = "L" + OLD_MODEL_LOCATION + ";";
        // Forge 1.20.1's constructors, and Forge 1.21's record constructor, build the location's Identifier.
        String[][] constructors = {
            {"(" + ID + STRING + ")V", "(" + ID + STRING + ")" + ID},
            {"(" + STRING + STRING + STRING + ")V", "(" + STRING + STRING + STRING + ")" + ID},
            {"(" + STRING + STRING + ")V", "(" + STRING + STRING + ")" + ID},
            {"(" + STRING + ")V", "(" + STRING + ")" + ID},
        };
        for (String[] constructor : constructors) {
            String factory = constructor[0].equals("(" + STRING + ")V") ? "parse" : "of";
            t.registerConstructorRedirect(OLD_MODEL_LOCATION, constructor[0], LOCATIONS, factory, constructor[1]);
        }
        // The static factories, Forge 1.20.1's getVariant(), and Forge 1.21's record accessors.
        staticCall(t, OLD_MODEL_LOCATION, "vanilla", "(" + STRING + STRING + ")" + location, LOCATIONS, "vanilla",
                "(" + STRING + STRING + ")" + ID);
        staticCall(t, OLD_MODEL_LOCATION, "inventory", "(" + ID + ")" + location, LOCATIONS, "inventory", "(" + ID + ")" + ID);
        staticCall(t, OLD_MODEL_LOCATION, "standalone", "(" + ID + ")" + location, LOCATIONS, "standalone", "(" + ID + ")" + ID);
        for (String[] accessor : new String[][]{{"getVariant", STRING}, {"variant", STRING}, {"id", ID}}) {
            String helper = accessor[0].equals("variant") ? "getVariant" : accessor[0];
            t.registerMethodRedirect(OLD_MODEL_LOCATION, accessor[0], "()" + accessor[1], LOCATIONS, helper,
                    "(" + ID + ")" + accessor[1], true);
            t.limitRedirectToModsBefore(OLD_MODEL_LOCATION, accessor[0], "()" + accessor[1], ForgeHostShape.FIRST_CHECKED_VERSION);
        }
        // The block state's location, which BlockModelShaper built and Minecraft 26.2 no longer has.
        staticCall(t, BLOCK_MODEL_SHAPER, "stateToModelLocation", "(" + STATE + ")" + location, LOCATIONS, "forState",
                "(" + STATE + ")" + ID);
        staticCall(t, BLOCK_MODEL_SHAPER, "stateToModelLocation", "(" + ID + STATE + ")" + location, LOCATIONS, "forState",
                "(" + ID + STATE + ")" + ID);
        // Calls made on the old type itself, its instanceof checks and the missing model's location are answered
        // before the remap, where the old type still tells them apart from plain Identifier calls.
        t.enablePostProcess(LegacyModelLocationAdapter.FLAG);
    }

    private static void registerModelMaps(RetromodTransformer t) {
        // The host kept these events and ModelManager under their names with new results, so old calls are limited.
        String models = "()" + MAP;
        instanceCall(t, MODIFY_BAKING_RESULT, "getModels", models, MAPS, "getModels", "(L" + MODIFY_BAKING_RESULT + ";)" + MAP);
        instanceCall(t, BAKING_COMPLETED, "getModels", models, MAPS, "getModels", "(L" + BAKING_COMPLETED + ";)" + MAP);
        String manager = "L" + MODEL_MANAGER + ";";
        String oldModel = "L" + OLD_BAKED_MODEL + ";";
        String model = "L" + BAKED_MODEL + ";";
        for (String key : List.of("L" + OLD_MODEL_LOCATION + ";", ID)) {
            instanceCall(t, MODEL_MANAGER, "getModel", "(" + key + ")" + oldModel, MAPS, "getModel",
                    "(" + manager + ID + ")" + model);
        }
        instanceCall(t, MODEL_MANAGER, "getMissingModel", "()" + oldModel, MAPS, "getMissingModel", "(" + manager + ")" + model);
    }

    private static void registerItemDraws(RetromodTransformer t) {
        // The item and block renderers' stand-ins hand out and take 26.x block models on every loader; Forge's old
        // model is this rebuilt one, so its calls go to Forge helpers. Keyed on the old names, the class redirects
        // above and the 26.1 bridge's renames reach each call's final shape.
        String oldModel = "L" + OLD_BAKED_MODEL + ";";
        String model = "L" + BAKED_MODEL + ";";
        String stack = "Lnet/minecraft/world/item/ItemStack;";
        String state = "Lnet/minecraft/world/level/block/state/BlockState;";
        String pose = "Lcom/mojang/blaze3d/vertex/PoseStack;";
        String consumer = "Lcom/mojang/blaze3d/vertex/VertexConsumer;";
        String items = "L" + LegacyItemDrawBridge.ITEMS + ";";
        String[][] calls = {
            {LegacyItemDrawBridge.OLD_ITEM_RENDERER, "getModel", "(" + stack + "Lnet/minecraft/world/level/Level;"
                    + "Lnet/minecraft/world/entity/LivingEntity;I)", items},
            {LegacyItemDrawBridge.OLD_ITEM_RENDERER, "render", "(" + stack + "Lnet/minecraft/world/item/ItemDisplayContext;Z" + pose
                    + "L" + HostBuffers.HOST_BUFFERS + ";II" + oldModel + ")", items},
            {LegacyItemDrawBridge.OLD_ITEM_RENDERER, "renderModelLists", "(" + oldModel + stack + "II" + pose + consumer + ")", items},
            {LegacyBakedItemBridge.OLD_ITEM_SHAPER, "getItemModel", "(" + stack + ")", "L" + LegacyBakedItemBridge.ITEM_SHAPER + ";"},
            {LegacyBakedItemBridge.OLD_ITEM_SHAPER, "getItemModel", "(Lnet/minecraft/world/item/Item;)", "L" + LegacyBakedItemBridge.ITEM_SHAPER + ";"},
            {LegacyBakedItemBridge.OLD_BLOCK_DISPATCHER, "getBlockModel", "(" + state + ")", "L" + LegacyBakedItemBridge.BLOCKS + ";"},
            {LegacyBakedItemBridge.OLD_BLOCK_SHAPER, "getBlockModel", "(" + state + ")", "L" + LegacyBakedItemBridge.BLOCK_SHAPER + ";"},
            {LegacyBakedItemBridge.MODEL_BLOCK_RENDERER, "renderModel", "(Lcom/mojang/blaze3d/vertex/PoseStack$Pose;" + consumer + state
                    + oldModel + "FFFII)", "L" + LegacyBakedItemBridge.MODEL_BLOCK_RENDERER + ";"},
        };
        for (String[] call : calls) {
            String args = call[2].substring(1);
            // Members that hand out a model end in one; the draws end in void.
            boolean handsOut = call[1].startsWith("get");
            String oldDesc = call[2] + (handsOut ? oldModel : "V");
            String helperDesc = "(" + call[3] + args.replace(oldModel, model).replace("L" + HostBuffers.HOST_BUFFERS + ";",
                    "L" + RenderBufferSynthetics.MBS + ";") + (handsOut ? model : "V");
            t.registerMethodRedirect(call[0], call[1], oldDesc, ITEM_DRAWS, call[1], helperDesc, true);
            t.limitRedirectToModsBefore(call[0], call[1], oldDesc, ForgeHostShape.FIRST_CHECKED_VERSION);
        }
    }

    // An instance call on a class the host keeps, sent to a static helper that takes the receiver first.
    private static void instanceCall(RetromodTransformer t, String owner, String name, String oldDesc, String helper,
            String helperName, String helperDesc) {
        t.registerMethodRedirect(owner, name, oldDesc, helper, helperName, helperDesc, true);
        // A mod built for a host that has the member again keeps it.
        t.limitRedirectToModsBefore(owner, name, oldDesc, ForgeHostShape.FIRST_CHECKED_VERSION);
    }

    private static void staticCall(RetromodTransformer t, String owner, String name, String oldDesc, String helper,
            String helperName, String helperDesc) {
        t.registerMethodRedirect(owner, name, oldDesc, helper, helperName, helperDesc);
        t.limitRedirectToModsBefore(owner, name, oldDesc, ForgeHostShape.FIRST_CHECKED_VERSION);
    }
}
