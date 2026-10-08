/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import com.retromod.shim.forge.capability.LegacyCapabilitySynthetics;
import com.retromod.util.McReflect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Forge 1.20.1 APIs that NeoForge kept under a new name or package, plus the capability system it
 * removed (#306, #308).
 *
 * <p>Each rename applies only when the NeoForge class exists on the host, so a later NeoForge that
 * drops one of them leaves the old reference failing visibly instead of naming a missing class.
 * The shapes were compared against NeoForge 21.1: the moved interfaces keep the method names and
 * descriptors a Forge mod overrides or calls.
 */
public final class ForgeNeoForgeApiBridge {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-ForgeNeoApi");

    private static final String FORGE = "net/minecraftforge/";
    private static final String NEO = "net/neoforged/neoforge/";

    /** Forge path to NeoForge path, both relative to their package roots. */
    static final String[][] MOVES = {
            // Client extension interfaces. A Forge Item hands one out through initializeClient,
            // which ClientExtensionsBridge routes to NeoForge's RegisterClientExtensionsEvent.
            {"client/extensions/common/IClientItemExtensions", "client/extensions/common/IClientItemExtensions"},
            {"client/extensions/common/IClientItemExtensions$FontContext", "client/extensions/common/IClientItemExtensions$FontContext"},
            {"client/extensions/common/IClientBlockExtensions", "client/extensions/common/IClientBlockExtensions"},
            {"client/extensions/common/IClientFluidTypeExtensions", "client/extensions/common/IClientFluidTypeExtensions"},
            {"client/extensions/common/IClientMobEffectExtensions", "client/extensions/common/IClientMobEffectExtensions"},
            {"client/IItemDecorator", "client/IItemDecorator"},
            {"client/model/geometry/IGeometryLoader", "client/model/geometry/IGeometryLoader"},
            {"client/model/geometry/IUnbakedGeometry", "client/model/geometry/IUnbakedGeometry"},
            {"client/model/geometry/IGeometryBakingContext", "client/model/geometry/IGeometryBakingContext"},
            // Forge's pre-damage hook; NeoForge's is cancellable with the same getters.
            {"event/entity/living/LivingAttackEvent", "event/entity/living/LivingIncomingDamageEvent"},
            // NeoForge fires the tick for every entity; LegacyLivingTickAdapter keeps Forge's
            // living-only delivery.
            {"event/entity/living/LivingEvent$LivingTickEvent", "event/tick/EntityTickEvent$Pre"},
            {"event/entity/player/PlayerSleepInBedEvent", "event/entity/player/CanPlayerSleepEvent"},
            {"registries/RegisterEvent", "registries/RegisterEvent"},
            {"registries/RegisterEvent$RegisterHelper", "registries/RegisterEvent$RegisterHelper"},
            // Same methods; Forge's static registry calls go through LegacyBrewingRegistryBridge.
            {"common/brewing/IBrewingRecipe", "common/brewing/IBrewingRecipe"},
            {"common/brewing/BrewingRecipe", "common/brewing/BrewingRecipe"},
            // Mixin targets and world modifiers.
            {"common/CreativeModeTabRegistry", "common/CreativeModeTabRegistry"},
            {"common/world/ModifiableStructureInfo", "common/world/ModifiableStructureInfo"},
            {"common/world/ModifiableStructureInfo$StructureInfo", "common/world/ModifiableStructureInfo$StructureInfo"},
            {"common/world/ModifiableStructureInfo$StructureInfo$Builder", "common/world/ModifiableStructureInfo$StructureInfo$Builder"},
            {"common/world/StructureModifier", "common/world/StructureModifier"},
            {"common/world/ForgeBiomeModifiers", "common/world/BiomeModifiers"},
            {"common/world/ForgeBiomeModifiers$AddFeaturesBiomeModifier", "common/world/BiomeModifiers$AddFeaturesBiomeModifier"},
            {"common/world/ForgeBiomeModifiers$RemoveFeaturesBiomeModifier", "common/world/BiomeModifiers$RemoveFeaturesBiomeModifier"},
            {"common/world/ForgeBiomeModifiers$AddSpawnsBiomeModifier", "common/world/BiomeModifiers$AddSpawnsBiomeModifier"},
            {"common/world/ForgeBiomeModifiers$RemoveSpawnsBiomeModifier", "common/world/BiomeModifiers$RemoveSpawnsBiomeModifier"},
            {"common/world/ForgeBiomeModifiers$AddCarversBiomeModifier", "common/world/BiomeModifiers$AddCarversBiomeModifier"},
            // Fluids: ForgeFlowingFluid became BaseFlowingFluid with the same nested types.
            {"fluids/FluidType", "fluids/FluidType"},
            {"fluids/FluidType$Properties", "fluids/FluidType$Properties"},
            {"fluids/FluidType$DripstoneDripInfo", "fluids/FluidType$DripstoneDripInfo"},
            {"fluids/ForgeFlowingFluid", "fluids/BaseFlowingFluid"},
            {"fluids/ForgeFlowingFluid$Properties", "fluids/BaseFlowingFluid$Properties"},
            {"fluids/ForgeFlowingFluid$Source", "fluids/BaseFlowingFluid$Source"},
            {"fluids/ForgeFlowingFluid$Flowing", "fluids/BaseFlowingFluid$Flowing"},
            {"fluids/FluidUtil", "fluids/FluidUtil"},
            // Tool actions were renamed item abilities with the same constants.
            {"common/ToolActions", "common/ItemAbilities"},
            {"common/ToolAction", "common/ItemAbility"},
            {"common/IForgeShearable", "common/IShearable"},
            {"common/util/Lazy", "common/util/Lazy"},
            {"common/util/FakePlayer", "common/util/FakePlayer"},
            {"common/util/FakePlayerFactory", "common/util/FakePlayerFactory"},
            // Handler types. ForgeCapabilitiesShim maps these too, but only from 1.21.11.
            {"items/IItemHandler", "items/IItemHandler"},
            {"items/ItemStackHandler", "items/ItemStackHandler"},
            {"items/SlotItemHandler", "items/SlotItemHandler"},
            {"fluids/FluidStack", "fluids/FluidStack"},
            {"fluids/capability/IFluidHandler", "fluids/capability/IFluidHandler"},
            {"fluids/capability/IFluidHandlerItem", "fluids/capability/IFluidHandlerItem"},
            {"fluids/capability/templates/FluidTank", "fluids/capability/templates/FluidTank"},
            {"energy/IEnergyStorage", "energy/IEnergyStorage"},
            {"energy/EnergyStorage", "energy/EnergyStorage"},
            {"client/model/IDynamicBakedModel", "client/model/IDynamicBakedModel"},
            {"items/IItemHandlerModifiable", "items/IItemHandlerModifiable"},
            {"items/ItemHandlerHelper", "items/ItemHandlerHelper"},
            {"items/wrapper/InvWrapper", "items/wrapper/InvWrapper"},
            {"items/wrapper/SidedInvWrapper", "items/wrapper/SidedInvWrapper"},
            {"items/wrapper/CombinedInvWrapper", "items/wrapper/CombinedInvWrapper"},
            {"items/wrapper/RangedWrapper", "items/wrapper/RangedWrapper"},
            // Data generation, which only runs in a datagen launch but is linked by the mod's
            // GatherDataEvent listener.
            {"common/data/ExistingFileHelper", "common/data/ExistingFileHelper"},
            {"common/data/ExistingFileHelper$ResourceType", "common/data/ExistingFileHelper$ResourceType"},
            {"common/data/ExistingFileHelper$IResourceType", "common/data/ExistingFileHelper$IResourceType"},
            {"common/data/ForgeAdvancementProvider", "common/data/AdvancementProvider"},
            {"common/data/DatapackBuiltinEntriesProvider", "common/data/DatapackBuiltinEntriesProvider"},
            {"common/data/BlockTagsProvider", "common/data/BlockTagsProvider"},
            {"client/model/generators/ConfiguredModel$Builder", "client/model/generators/ConfiguredModel$Builder"},
            {"client/model/generators/VariantBlockStateBuilder$PartialBlockstate", "client/model/generators/VariantBlockStateBuilder$PartialBlockstate"},
            {"common/data/ForgeAdvancementProvider$AdvancementGenerator", "common/data/AdvancementProvider$AdvancementGenerator"},
            {"client/model/generators/ModelProvider", "client/model/generators/ModelProvider"},
            {"client/model/generators/ModelBuilder", "client/model/generators/ModelBuilder"},
            {"client/model/generators/BlockModelBuilder", "client/model/generators/BlockModelBuilder"},
            {"client/model/generators/ItemModelBuilder", "client/model/generators/ItemModelBuilder"},
            {"client/model/generators/ModelFile", "client/model/generators/ModelFile"},
            {"client/model/generators/ModelFile$ExistingModelFile", "client/model/generators/ModelFile$ExistingModelFile"},
            {"client/model/generators/ModelFile$UncheckedModelFile", "client/model/generators/ModelFile$UncheckedModelFile"},
            {"client/model/generators/BlockStateProvider", "client/model/generators/BlockStateProvider"},
            {"client/model/generators/BlockModelProvider", "client/model/generators/BlockModelProvider"},
            {"client/model/generators/ItemModelProvider", "client/model/generators/ItemModelProvider"},
            {"client/model/generators/ConfiguredModel", "client/model/generators/ConfiguredModel"},
            {"client/model/generators/VariantBlockStateBuilder", "client/model/generators/VariantBlockStateBuilder"},
    };

    /**
     * Moves outside NeoForge's package root: FML's SPI. The 1.20.5 vanilla spawn types and the
     * spawn placement event are left to {@link com.retromod.shim.common.LegacySpawnPlacementTypeBridge}
     * and {@link ForgeNeoForgeSameShapeMoves}, which own those renames on every host.
     */
    static final String[][] ABSOLUTE_MOVES = {
            {"net/minecraftforge/forgespi/language/IModFileInfo", "net/neoforged/neoforgespi/language/IModFileInfo"},
            {"net/minecraftforge/forgespi/locating/IModFile", "net/neoforged/neoforgespi/locating/IModFile"},
    };

    /**
     * Forge events NeoForge deleted without a replacement. A stand-in event lets the mod's
     * listener class register; the listener never runs, as nothing fires the event.
     */
    static final String[] REMOVED_EVENTS = {
            "net/minecraftforge/event/entity/player/FillBucketEvent",
            // NeoForge's CanContinueSleepingEvent has a different contract (a boolean, no result).
            "net/minecraftforge/event/entity/player/SleepingTimeCheckEvent",
    };

    /** Forge interfaces NeoForge deleted that mods only test with {@code instanceof}. */
    static final String[] REMOVED_MARKERS = {
            "net/minecraftforge/common/IPlantable",
            "net/minecraftforge/fluids/IFluidBlock",
    };

    private ForgeNeoForgeApiBridge() {}

    /** Called by {@link Forge_1_20_to_NeoForge_1_21} on a NeoForge host. */
    public static void register(RetromodTransformer transformer) {
        if (!McReflect.isNeoForge()) return;

        registerEventBusSubscriber(transformer);
        int moved = registerMoves(transformer, ForgeNeoForgeApiBridge::hostHasClass);
        registerClassOwners(transformer, ForgeNeoForgeApiBridge::hostDeclaresClass);
        moved += registerAbsoluteMoves(transformer, ForgeNeoForgeApiBridge::hostHasClass);
        registerRemovedMarkers(transformer, ForgeNeoForgeApiBridge::hostHasClass);
        registerRemovedEvents(transformer, ForgeNeoForgeApiBridge::hostHasClass);
        registerArchitecturyEventBuses(transformer, ForgeNeoForgeApiBridge::hostHasClass);
        LegacyCapabilitySynthetics.register(transformer);
        LegacyCustomRegistryBridge.register(transformer);
        LegacyEntityClientFactoryBridge.register(transformer);
        com.retromod.shim.forge.capability.ForgeCapabilityStorage.listen();
        registerEventCalls(transformer);
        LegacyForgeHooksBridge.register(transformer, ForgeNeoForgeApiBridge::hostHasClass);
        LegacyFluidSupplierBridge.register(transformer);
        LegacyForgeModAttributes.register(transformer);
        LOGGER.info("Bridged {} renamed Forge API classes and the Forge capability system", moved);
    }

    /**
     * {@code @Mod.EventBusSubscriber} became the top-level {@code @EventBusSubscriber}. Without the
     * rename NeoForge never sees the annotation and none of the class's listeners are registered.
     * NeoForge 21.1 picks the bus from each listener's event type, so Forge's {@code bus = FORGE}
     * value is never read.
     */
    static void registerEventBusSubscriber(RetromodTransformer transformer) {
        transformer.registerClassRedirect("net/minecraftforge/fml/common/Mod$EventBusSubscriber",
                "net/neoforged/fml/common/EventBusSubscriber");
        transformer.registerClassRedirect("net/minecraftforge/fml/common/Mod$EventBusSubscriber$Bus",
                "net/neoforged/fml/common/EventBusSubscriber$Bus");
    }

    /**
     * Forge's {@code IEventBus.post} returned the cancel flag; NeoForge's returns the event. Forge's
     * {@code register(instance)} skipped static handlers; NeoForge's rejects the instance. The
     * cancellation calls themselves are rewritten by {@link LegacyEventCancelAdapter}.
     */
    static void registerEventCalls(RetromodTransformer transformer) {
        com.retromod.core.SyntheticEmbedder.registerClassResource(transformer,
                LegacyEventCancelAdapter.HELPER, com.retromod.shim.forge.embedded.LegacyEventCalls.class);
        transformer.registerMethodRedirect("net/neoforged/bus/api/IEventBus", "post",
                "(Lnet/neoforged/bus/api/Event;)Z",
                LegacyEventCancelAdapter.HELPER, "post", "(Ljava/lang/Object;Ljava/lang/Object;)Z", true);
        // Forge's sleep check set its veto through setResult; NeoForge names it setProblem.
        transformer.registerMethodRedirect("net/neoforged/neoforge/event/entity/player/CanPlayerSleepEvent",
                "setResult", "(Lnet/minecraft/world/entity/player/Player$BedSleepingProblem;)V",
                "net/neoforged/neoforge/event/entity/player/CanPlayerSleepEvent", "setProblem",
                "(Lnet/minecraft/world/entity/player/Player$BedSleepingProblem;)V");
        transformer.registerMethodRedirect("net/neoforged/neoforge/event/entity/player/CanPlayerSleepEvent",
                "getResultStatus", "()Lnet/minecraft/world/entity/player/Player$BedSleepingProblem;",
                "net/neoforged/neoforge/event/entity/player/CanPlayerSleepEvent", "getProblem",
                "()Lnet/minecraft/world/entity/player/Player$BedSleepingProblem;");
        transformer.registerMethodRedirect("net/neoforged/bus/api/IEventBus", "register",
                "(Ljava/lang/Object;)V",
                LegacyEventCancelAdapter.HELPER, "register", "(Ljava/lang/Object;Ljava/lang/Object;)V", true);
    }

    static int registerMoves(RetromodTransformer transformer,
            java.util.function.Predicate<String> hostHasClass) {
        int moved = 0;
        for (String[] move : MOVES) {
            String target = NEO + move[1];
            if (!hostHasClass.test(target)) {
                LOGGER.debug("Host has no {}; leaving {} unbridged", target, FORGE + move[0]);
                continue;
            }
            transformer.registerClassRedirect(FORGE + move[0], target);
            moved++;
        }
        return moved;
    }

    /**
     * Marks every moved owner the host declares as a class. Forge's {@code Lazy} was an
     * interface and NeoForge's is a final class, so {@code Lazy.of} and {@code get} calls must
     * become class calls or the mod fails to link.
     */
    static void registerClassOwners(RetromodTransformer transformer,
            java.util.function.Predicate<String> hostDeclaresClass) {
        for (String[] move : MOVES) {
            String target = NEO + move[1];
            if (hostDeclaresClass.test(target)) transformer.registerClassOwner(target);
        }
    }

    private static boolean hostDeclaresClass(String internalName) {
        org.objectweb.asm.tree.ClassNode node = com.retromod.core.ClassResourceInspector.read(internalName);
        return node != null && (node.access & org.objectweb.asm.Opcodes.ACC_INTERFACE) == 0;
    }

    /**
     * Architectury 9 for Forge made a mod hand over its event bus through {@code EventBuses};
     * Architectury 13 for NeoForge finds the bus itself and keeps only the lookups, under
     * {@code EventBusesHooks}. Registration becomes a no-op and the lookups move.
     */
    static void registerArchitecturyEventBuses(RetromodTransformer transformer,
            java.util.function.Predicate<String> hostHasClass) {
        String forge = "dev/architectury/platform/forge/EventBuses";
        String hooks = "dev/architectury/platform/hooks/EventBusesHooks";
        if (hostHasClass.test(forge) || !hostHasClass.test(hooks)) return;
        transformer.registerClassRedirect(forge, hooks);
        String bus = "Lnet/neoforged/bus/api/IEventBus;";
        transformer.registerMethodRedirect(hooks, "registerModEventBus", "(Ljava/lang/String;" + bus + ")V",
                LegacyEventCancelAdapter.HELPER, "ignoreModEventBus", "(Ljava/lang/String;Ljava/lang/Object;)V");
        transformer.registerMethodRedirect(hooks, "onRegistered", "(Ljava/lang/String;Ljava/util/function/Consumer;)V",
                hooks, "whenAvailable", "(Ljava/lang/String;Ljava/util/function/Consumer;)V");
    }

    static int registerAbsoluteMoves(RetromodTransformer transformer,
            java.util.function.Predicate<String> hostHasClass) {
        int moved = 0;
        for (String[] move : ABSOLUTE_MOVES) {
            if (hostHasClass.test(move[0]) || !hostHasClass.test(move[1])) continue;
            transformer.registerClassRedirect(move[0], move[1]);
            moved++;
        }
        // 1.20.5 renamed the attribute operations; the enum and its order are unchanged.
        String operation = "net/minecraft/world/entity/ai/attributes/AttributeModifier$Operation";
        if (hostHasField(operation, "ADD_VALUE")) {
            String desc = "L" + operation + ";";
            transformer.registerFieldRedirect(operation, "ADDITION", desc, operation, "ADD_VALUE", desc);
            transformer.registerFieldRedirect(operation, "MULTIPLY_BASE", desc, operation, "ADD_MULTIPLIED_BASE", desc);
            transformer.registerFieldRedirect(operation, "MULTIPLY_TOTAL", desc, operation, "ADD_MULTIPLIED_TOTAL", desc);
        }
        return moved;
    }

    private static boolean hostHasField(String owner, String field) {
        org.objectweb.asm.tree.ClassNode node = com.retromod.core.ClassResourceInspector.read(owner);
        return node != null && node.fields.stream().anyMatch(f -> f.name.equals(field));
    }

    /**
     * An empty stand-in, embedded per mod, so {@code block instanceof IPlantable} answers false
     * instead of failing to link. Nothing on NeoForge implements the deleted interface.
     */
    static void registerRemovedMarkers(RetromodTransformer transformer,
            java.util.function.Predicate<String> hostHasClass) {
        for (String marker : REMOVED_MARKERS) {
            if (hostHasClass.test(marker)) continue;
            org.objectweb.asm.ClassWriter cw = new org.objectweb.asm.ClassWriter(0);
            cw.visit(org.objectweb.asm.Opcodes.V17, org.objectweb.asm.Opcodes.ACC_PUBLIC
                    | org.objectweb.asm.Opcodes.ACC_ABSTRACT | org.objectweb.asm.Opcodes.ACC_INTERFACE,
                    marker, null, "java/lang/Object", null);
            cw.visitEnd();
            transformer.registerSyntheticClass(marker, cw.toByteArray());
        }
    }

    static void registerRemovedEvents(RetromodTransformer transformer,
            java.util.function.Predicate<String> hostHasClass) {
        String event = "net/neoforged/bus/api/Event";
        for (String removed : REMOVED_EVENTS) {
            if (hostHasClass.test(removed)) continue;
            org.objectweb.asm.ClassWriter cw = new org.objectweb.asm.ClassWriter(org.objectweb.asm.ClassWriter.COMPUTE_MAXS);
            cw.visit(org.objectweb.asm.Opcodes.V17, org.objectweb.asm.Opcodes.ACC_PUBLIC, removed, null, event, null);
            org.objectweb.asm.MethodVisitor init = cw.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
            init.visitCode();
            init.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 0);
            init.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKESPECIAL, event, "<init>", "()V", false);
            init.visitInsn(org.objectweb.asm.Opcodes.RETURN);
            init.visitMaxs(0, 0);
            init.visitEnd();
            cw.visitEnd();
            transformer.registerSyntheticClass(removed, cw.toByteArray());
        }
    }

    /**
     * Reads the class file instead of loading the class: NeoForge's dist cleaner throws for a
     * client-only class on a dedicated server, even with {@code initialize=false}.
     */
    private static boolean hostHasClass(String internalName) {
        return com.retromod.core.ClassResourceInspector.exists(internalName);
    }
}
