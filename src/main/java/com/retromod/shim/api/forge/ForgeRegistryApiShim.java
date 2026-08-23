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

        transformer.registerClassRedirect(
            "net/minecraftforge/registries/ForgeRegistries$Keys",
            "net/neoforged/neoforge/registries/NeoForgeRegistries$Keys"
        );

        transformer.registerClassRedirect(
            "net/minecraftforge/registries/RegistryBuilder",
            "net/neoforged/neoforge/registries/RegistryBuilder"
        );

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

    /** The two BuiltInRegistries field shapes on 26.1. */
    private static final String DEFAULTED_REGISTRY = "Lnet/minecraft/core/DefaultedRegistry;";
    private static final String REGISTRY = "Lnet/minecraft/core/Registry;";

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
