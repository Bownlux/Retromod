/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 * 
 * Forge Registry System API Compatibility Shim
 */
package com.retromod.shim.api.forge;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.core.MinecraftVersionedApiShim;
import com.retromod.shim.forge.RegistryIdBridgeSynthetic;
import com.retromod.util.McReflect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Forge registry system API shim: DeferredRegister/RegistryObject and registry key changes.
 */
public class ForgeRegistryApiShim implements MinecraftVersionedApiShim {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-ForgeRegistryApiShim");

    @Override
    public String getShimName() {
        return "Forge Registry System API Compatibility";
    }
    
    @Override
    public String getSourceVersion() {
        return "1.20.1";
    }
    
    @Override
    public String getTargetVersion() {
        return "1.21.0";
    }
    
    @Override
    public String getModLoaderType() {
        return "forge";
    }
    
    @Override
    public void registerRedirects(RetromodTransformer transformer) {
        // These redirects map Forge package names to NeoForge ones, so they are only correct on a
        // NeoForge runtime; on Forge they would NoClassDefFoundError on net/neoforged/* classes.
        if (!McReflect.isNeoForge()) {
            LOGGER.debug("Skipping Forge → NeoForge registry API migration (runtime is not NeoForge)");
            return;
        }
        registerRegistryRedirects(transformer);
    }

    /**
     * Registry migration redirects, factored out of the gated {@link #registerRedirects} so tests can
     * drive them without a NeoForge runtime ({@link McReflect#isNeoForge()} has no test seam).
     */
    void registerRegistryRedirects(RetromodTransformer transformer) {
        transformer.registerClassRedirect(
            "net/minecraftforge/registries/DeferredRegister",
            "net/neoforged/neoforge/registries/DeferredRegister"
        );
        
        // RegistryObject -> DeferredHolder
        transformer.registerClassRedirect(
            "net/minecraftforge/registries/RegistryObject",
            "net/neoforged/neoforge/registries/DeferredHolder"
        );
        
        // DeferredRegister.create(IForgeRegistry, String) -> create(Registry, String).
        // Keyed on the NeoForge owner and the post-ClassRemapper descriptor, not the Forge originals:
        // ClassRemapper runs before this visitor, so by visitMethodInsn the DeferredRegister redirect
        // above has already rewritten the call owner and return type to neoforged, and the method gate
        // is on the post-remap owner. The IForgeRegistry parameter is left un-redirected (a synthetic
        // marker interface, see below), so it is still net/minecraftforge/.../IForgeRegistry here.
        // The value flowing into that param is a vanilla BuiltInRegistries.<field> instance
        // (DefaultedRegistry/Registry) because the ForgeRegistries reads below are field-redirected to
        // it, so IForgeRegistry param -> Registry selects NeoForge's create(Registry, String) overload.
        // We map to the instance, not the ResourceKey, since the same field read also feeds the
        // getValue()/getKey() lookups below and only an instance serves both.
        transformer.registerMethodRedirect(
            "net/neoforged/neoforge/registries/DeferredRegister",
            "create",
            "(Lnet/minecraftforge/registries/IForgeRegistry;Ljava/lang/String;)Lnet/neoforged/neoforge/registries/DeferredRegister;",
            "net/neoforged/neoforge/registries/DeferredRegister",
            "create",
            "(Lnet/minecraft/core/Registry;Ljava/lang/String;)Lnet/neoforged/neoforge/registries/DeferredRegister;"
        );

        // #87: MC 26.x (1.21.3+) requires the registry id stamped on Block/Item Properties BEFORE the
        // object is constructed (BlockBehaviour.<init> -> requireNonNull(id, "Block id not set")). A Forge
        // 1.20.1 mod's DeferredRegister.register(String, Supplier) builds the object inside the supplier
        // with no id, so it dies at RegisterEvent. Route registration + Properties creation through the
        // RegistryIdBridge synthetic, which threads the id via a ThreadLocal (set by the register wrapper,
        // read by the Properties factories). Gated to 26.1+ hosts: pre-1.21.3 has no Properties.setId, so
        // these would NoSuchMethodError there.
        if (RetromodVersion.isUnobfuscatedTarget(RetromodVersion.TARGET_MC_VERSION)) {
            final String B = RegistryIdBridgeSynthetic.INTERNAL;
            // Register the synthetic HERE, co-located with the redirects that target it, so the
            // pair is always registered together. The entry points also register it via
            // ForgeNeoForgeSynthetics, but registerSyntheticClass is idempotent, and this makes
            // the shim self-contained: a redirect to a synthetic that isn't registered would be
            // dropped by the transformer's phantom-target sweep (#119) and silently no-op.
            transformer.registerSyntheticClass(B, RegistryIdBridgeSynthetic.generate());
            // dr.register(name, supplier) -> RegistryIdBridge.register(dr, name, supplier) [devirtualize: receiver becomes arg 0]
            transformer.registerMethodRedirect(
                "net/neoforged/neoforge/registries/DeferredRegister", "register",
                "(Ljava/lang/String;Ljava/util/function/Supplier;)Lnet/neoforged/neoforge/registries/DeferredHolder;",
                B, "register", RegistryIdBridgeSynthetic.REGISTER_DESC, true);
            // DeferredRegister.createBlocks(...) hands back the Blocks subclass, and its register
            // overload returns DeferredBlock, so a call site keyed on the base class never matched
            // and the block was built with no id (#285, the standard 1.21 NeoForge idiom). The
            // devirtualized call casts the DeferredHolder back to the declared return type.
            String[][] typedRegisters = {
                {"Blocks", "DeferredBlock"},
                {"Items", "DeferredItem"},
            };
            for (String[] typed : typedRegisters) {
                transformer.registerMethodRedirect(
                    "net/neoforged/neoforge/registries/DeferredRegister$" + typed[0], "register",
                    "(Ljava/lang/String;Ljava/util/function/Supplier;)"
                        + "Lnet/neoforged/neoforge/registries/" + typed[1] + ";",
                    B, "register", RegistryIdBridgeSynthetic.REGISTER_DESC, true);
            }
            // Block Properties factories -> id-stamping helpers (no-op when the thread-local isn't set).
            transformer.registerMethodRedirect(
                "net/minecraft/world/level/block/state/BlockBehaviour$Properties", "of",
                "()Lnet/minecraft/world/level/block/state/BlockBehaviour$Properties;",
                B, "blockOf", RegistryIdBridgeSynthetic.BLOCK_OF_DESC);
            transformer.registerMethodRedirect(
                "net/minecraft/world/level/block/state/BlockBehaviour$Properties", "ofFullCopy",
                "(Lnet/minecraft/world/level/block/state/BlockBehaviour;)Lnet/minecraft/world/level/block/state/BlockBehaviour$Properties;",
                B, "blockOfFullCopy", RegistryIdBridgeSynthetic.BLOCK_COPY_DESC);
            transformer.registerMethodRedirect(
                "net/minecraft/world/level/block/state/BlockBehaviour$Properties", "ofLegacyCopy",
                "(Lnet/minecraft/world/level/block/state/BlockBehaviour;)Lnet/minecraft/world/level/block/state/BlockBehaviour$Properties;",
                B, "blockOfLegacyCopy", RegistryIdBridgeSynthetic.BLOCK_COPY_DESC);
            // new Item.Properties() -> id-stamping factory.
            transformer.registerConstructorRedirect(
                "net/minecraft/world/item/Item$Properties", "()V",
                B, "itemProps", RegistryIdBridgeSynthetic.ITEM_PROPS_DESC);
        }

        // Do not class-redirect ForgeRegistries: a class redirect rewrites the GETSTATIC owner before
        // the field redirects below can match, and NeoForgeRegistries has no BLOCKS/ITEMS/... so the
        // mod's <clinit> hits NoSuchFieldError. ForgeRegistries and IForgeRegistry are gone on
        // NeoForge; the field reads below carry a registry instance into create() above and the
        // getValue()/getKey() lookups below. IForgeRegistry is supplied as a synthetic marker
        // interface by ForgeNeoForgeSynthetics.

        // IForgeRegistryEntry was deleted with no replacement.
        transformer.registerClassRedirect(
            "net/minecraftforge/registries/IForgeRegistryEntry",
            "java/lang/Object"
        );

        // ForgeRegistries.<plural> (IForgeRegistry) field reads -> the vanilla
        // BuiltInRegistries.<singular> instance. Field resolution matches name and descriptor, so each
        // redirect carries that field's declared type: BLOCK/ITEM/ENTITY_TYPE/FLUID are
        // DefaultedRegistry, the other ten are plain Registry. Both are <: Registry, so they satisfy
        // create(Registry, String) and the Registry.get/getKey/containsKey lookups below.
        forgeReg(transformer, "BLOCKS", "BLOCK", DEFAULTED_REGISTRY);
        forgeReg(transformer, "ITEMS", "ITEM", DEFAULTED_REGISTRY);
        forgeReg(transformer, "ENTITY_TYPES", "ENTITY_TYPE", DEFAULTED_REGISTRY);
        forgeReg(transformer, "FLUIDS", "FLUID", DEFAULTED_REGISTRY);
        forgeReg(transformer, "BLOCK_ENTITY_TYPES", "BLOCK_ENTITY_TYPE", REGISTRY);
        forgeReg(transformer, "MENU_TYPES", "MENU", REGISTRY);
        forgeReg(transformer, "SOUND_EVENTS", "SOUND_EVENT", REGISTRY);
        forgeReg(transformer, "POTIONS", "POTION", REGISTRY);
        forgeReg(transformer, "MOB_EFFECTS", "MOB_EFFECT", REGISTRY);
        forgeReg(transformer, "PARTICLE_TYPES", "PARTICLE_TYPE", REGISTRY);
        forgeReg(transformer, "RECIPE_SERIALIZERS", "RECIPE_SERIALIZER", REGISTRY);
        forgeReg(transformer, "RECIPE_TYPES", "RECIPE_TYPE", REGISTRY);
        forgeReg(transformer, "ATTRIBUTES", "ATTRIBUTE", REGISTRY);
        forgeReg(transformer, "CREATIVE_MODE_TABS", "CREATIVE_MODE_TAB", REGISTRY);
        // Worldgen and AI registries, read by mods that create their own DeferredRegister (#306).
        // Descriptors verified against BuiltInRegistries on 1.21.1.
        forgeReg(transformer, "FEATURES", "FEATURE", REGISTRY);
        forgeReg(transformer, "WORLD_CARVERS", "CARVER", REGISTRY);
        forgeReg(transformer, "BLOCK_STATE_PROVIDER_TYPES", "BLOCKSTATE_PROVIDER_TYPE", REGISTRY);
        forgeReg(transformer, "FOLIAGE_PLACER_TYPES", "FOLIAGE_PLACER_TYPE", REGISTRY);
        forgeReg(transformer, "TREE_DECORATOR_TYPES", "TREE_DECORATOR_TYPE", REGISTRY);
        forgeReg(transformer, "STAT_TYPES", "STAT_TYPE", REGISTRY);
        forgeReg(transformer, "COMMAND_ARGUMENT_TYPES", "COMMAND_ARGUMENT_TYPE", REGISTRY);
        forgeReg(transformer, "POI_TYPES", "POINT_OF_INTEREST_TYPE", REGISTRY);
        forgeReg(transformer, "SCHEDULES", "SCHEDULE", REGISTRY);
        forgeReg(transformer, "ACTIVITIES", "ACTIVITY", REGISTRY);
        forgeReg(transformer, "VILLAGER_PROFESSIONS", "VILLAGER_PROFESSION", DEFAULTED_REGISTRY);
        forgeReg(transformer, "MEMORY_MODULE_TYPES", "MEMORY_MODULE_TYPE", DEFAULTED_REGISTRY);
        forgeReg(transformer, "SENSOR_TYPES", "SENSOR_TYPE", DEFAULTED_REGISTRY);
        forgeReg(transformer, "CHUNK_STATUS", "CHUNK_STATUS", DEFAULTED_REGISTRY);
        registerDatapackRegistryStandIns(transformer);

        // Registry instance lookups: ForgeRegistries.X.getValue(loc), .getKey(v), ...
        // After the field redirects above, ForgeRegistries.X is a registry instance, but the call
        // sites still invoke IForgeRegistry.<method> (the synthetic marker interface). Re-point those
        // to the matching net/minecraft/core/Registry method; the receiver is <: Registry so they
        // resolve. getValue maps to Registry.get; getKey/containsKey are unchanged. Stays an instance
        // call on the registry receiver, with owner+name fixed.
        registryLookup(transformer, "getValue",
                "(Lnet/minecraft/resources/ResourceLocation;)Ljava/lang/Object;",
                "get", "(Lnet/minecraft/resources/ResourceLocation;)Ljava/lang/Object;");
        registryLookup(transformer, "getKey",
                "(Ljava/lang/Object;)Lnet/minecraft/resources/ResourceLocation;",
                "getKey", "(Ljava/lang/Object;)Lnet/minecraft/resources/ResourceLocation;");
        registryLookup(transformer, "containsKey",
                "(Lnet/minecraft/resources/ResourceLocation;)Z",
                "containsKey", "(Lnet/minecraft/resources/ResourceLocation;)Z");
        // Forge's entry and key views have the vanilla shapes under vanilla names (#306).
        registryLookup(transformer, "getEntries", "()Ljava/util/Set;", "entrySet", "()Ljava/util/Set;");
        registryLookup(transformer, "getKeys", "()Ljava/util/Set;", "keySet", "()Ljava/util/Set;");
        String registryCalls = "com/retromod/shim/forge/embedded/LegacyRegistryCalls";
        com.retromod.core.SyntheticEmbedder.registerClassResource(transformer, registryCalls,
                com.retromod.shim.forge.embedded.LegacyRegistryCalls.class);
        transformer.registerMethodRedirect("net/minecraftforge/registries/IForgeRegistry", "getValues",
                "()Ljava/util/Collection;", registryCalls, "getValues",
                "(Ljava/lang/Object;)Ljava/util/Collection;", true);

        transformer.registerClassRedirect(
            "net/minecraftforge/registries/ForgeRegistries$Keys",
            "net/neoforged/neoforge/registries/NeoForgeRegistries$Keys"
        );
        // Forge's ForgeRegistries.Keys also held the vanilla registry keys. NeoForge kept only its
        // own registries in NeoForgeRegistries.Keys, so a vanilla key read through the class
        // redirect above fails with NoSuchFieldError. Send those reads to Registries, which names
        // the same ResourceKey in singular form. Keyed on the post-ClassRemapper owner.
        for (String[] key : VANILLA_REGISTRY_KEYS) {
            transformer.registerFieldRedirect(
                "net/neoforged/neoforge/registries/NeoForgeRegistries$Keys", key[0], RESOURCE_KEY,
                "net/minecraft/core/registries/Registries", key[1], RESOURCE_KEY);
        }

        // NeoForge 1.21 and newer get a Forge-shaped builder from LegacyCustomRegistryBridge instead.
        // NeoForge's builder has neither the no-argument constructor nor setName, so this rename
        // alone cannot link, and it would hide the Forge name the bridge's stand-in replaces.
        if (!com.retromod.shim.forge.LegacyCustomRegistryBridge.handlesHost()) {
            transformer.registerClassRedirect(
                "net/minecraftforge/registries/RegistryBuilder",
                "net/neoforged/neoforge/registries/RegistryBuilder"
            );
        }

        // legacy GameRegistry
        transformer.registerClassRedirect(
            "net/minecraftforge/fml/common/registry/GameRegistry",
            "com/retromod/shim/api/forge/embedded/GameRegistryShim"
        );

        // legacy ObjectHolder, no longer used
        transformer.registerClassRedirect(
            "net/minecraftforge/registries/ObjectHolder",
            "java/lang/Deprecated"
        );
    }

    private static final String RESOURCE_KEY = "Lnet/minecraft/resources/ResourceKey;";

    /**
     * Forge 1.20.1 {@code ForgeRegistries.Keys} fields that name a vanilla registry, paired with the
     * {@code Registries} field for the same key. Forge-only keys stay on NeoForgeRegistries.Keys.
     * {@code SCHEDULE} is absent from Registries on 1.21.11 and newer, so that read still fails there.
     */
    private static final String[][] VANILLA_REGISTRY_KEYS = {
        {"ACTIVITIES", "ACTIVITY"}, {"ATTRIBUTES", "ATTRIBUTE"}, {"BIOMES", "BIOME"},
        {"BLOCK_ENTITY_TYPES", "BLOCK_ENTITY_TYPE"},
        {"BLOCK_STATE_PROVIDER_TYPES", "BLOCK_STATE_PROVIDER_TYPE"}, {"BLOCKS", "BLOCK"},
        {"CHUNK_STATUS", "CHUNK_STATUS"}, {"COMMAND_ARGUMENT_TYPES", "COMMAND_ARGUMENT_TYPE"},
        {"ENCHANTMENTS", "ENCHANTMENT"}, {"ENTITY_TYPES", "ENTITY_TYPE"}, {"FEATURES", "FEATURE"},
        {"FLUIDS", "FLUID"}, {"FOLIAGE_PLACER_TYPES", "FOLIAGE_PLACER_TYPE"}, {"ITEMS", "ITEM"},
        {"MEMORY_MODULE_TYPES", "MEMORY_MODULE_TYPE"}, {"MENU_TYPES", "MENU"},
        {"MOB_EFFECTS", "MOB_EFFECT"}, {"PAINTING_VARIANTS", "PAINTING_VARIANT"},
        {"PARTICLE_TYPES", "PARTICLE_TYPE"}, {"POI_TYPES", "POINT_OF_INTEREST_TYPE"},
        {"POTIONS", "POTION"}, {"RECIPE_SERIALIZERS", "RECIPE_SERIALIZER"},
        {"RECIPE_TYPES", "RECIPE_TYPE"}, {"SCHEDULES", "SCHEDULE"}, {"SENSOR_TYPES", "SENSOR_TYPE"},
        {"SOUND_EVENTS", "SOUND_EVENT"}, {"STAT_TYPES", "STAT_TYPE"},
        {"TREE_DECORATOR_TYPES", "TREE_DECORATOR_TYPE"},
        {"VILLAGER_PROFESSIONS", "VILLAGER_PROFESSION"}, {"WORLD_CARVERS", "CARVER"},
    };

    /** The two BuiltInRegistries field shapes on 26.1. */
    private static final String DEFAULTED_REGISTRY = "Lnet/minecraft/core/DefaultedRegistry;";
    private static final String REGISTRY = "Lnet/minecraft/core/Registry;";

    /**
     * Enchantments and painting variants became datapack registries in 1.21, so BuiltInRegistries
     * has no field to read. A host that still has one gets the plain field redirect; a newer host
     * gets a stand-in that only answers {@code key()}, which is all {@code DeferredRegister.create}
     * needs. The mod's Java entries for those registries cannot bind there.
     */
    static void registerDatapackRegistryStandIns(RetromodTransformer transformer) {
        String helper = "com/retromod/shim/api/forge/embedded/LegacyDatapackRegistries";
        String[][] registries = {{"ENCHANTMENTS", "ENCHANTMENT", "enchantments"},
                {"PAINTING_VARIANTS", "PAINTING_VARIANT", "paintingVariants"}};
        org.objectweb.asm.tree.ClassNode builtIn =
                com.retromod.core.ClassResourceInspector.read("net/minecraft/core/registries/BuiltInRegistries");
        for (String[] registry : registries) {
            boolean builtInField = builtIn != null
                    && builtIn.fields.stream().anyMatch(field -> field.name.equals(registry[1]));
            if (builtInField) {
                forgeReg(transformer, registry[0], registry[1], REGISTRY);
                continue;
            }
            if (builtIn == null) continue;
            com.retromod.core.SyntheticEmbedder.registerClassResource(transformer, helper,
                    com.retromod.shim.api.forge.embedded.LegacyDatapackRegistries.class);
            transformer.registerFieldRedirect("net/minecraftforge/registries/ForgeRegistries", registry[0],
                    "Lnet/minecraftforge/registries/IForgeRegistry;", helper, registry[2], "()Ljava/lang/Object;");
        }
    }

    /**
     * ForgeRegistries.&lt;plural&gt; field read (typed IForgeRegistry, removed on NeoForge) ->
     * the vanilla BuiltInRegistries.&lt;singular&gt; instance. {@code registryDesc} is that field's
     * declared type ({@link #DEFAULTED_REGISTRY} or {@link #REGISTRY}); field resolution matches name
     * and descriptor, so it must be exact.
     */
    private static void forgeReg(RetromodTransformer t, String forgeField, String vanillaField,
                                 String registryDesc) {
        t.registerFieldRedirect(
            "net/minecraftforge/registries/ForgeRegistries", forgeField,
            "Lnet/minecraftforge/registries/IForgeRegistry;",
            "net/minecraft/core/registries/BuiltInRegistries", vanillaField,
            registryDesc);
    }

    /**
     * IForgeRegistry.&lt;method&gt; instance call (on a now-real registry instance) -> the matching
     * net/minecraft/core/Registry method. Keyed on the IForgeRegistry synthetic-marker owner that the
     * call site still names; the receiver is &lt;: Registry, so the redirected call resolves. Same arg
     * count as the source, so it stays an instance call rather than being devirtualized.
     */
    private static void registryLookup(RetromodTransformer t, String forgeMethod, String forgeDesc,
                                       String neoMethod, String neoDesc) {
        t.registerMethodRedirect(
            "net/minecraftforge/registries/IForgeRegistry", forgeMethod, forgeDesc,
            "net/minecraft/core/Registry", neoMethod, neoDesc);
    }

    @Override
    public String[] getShimClasses() {
        return new String[] {
            "com.retromod.shim.api.forge.embedded.RegistryShim",
            "com.retromod.shim.api.forge.embedded.GameRegistryShim"
        };
    }
}
