/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.core.SyntheticEmbedder;
import com.retromod.core.VersionShim;
import com.retromod.util.McReflect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Forge 1.20.x to NeoForge 1.21.x migration shim. The redirects are only valid on a NeoForge
 * runtime: on Forge they rewrite a mod's {@code @Mod} annotation to the NeoForge one, after which
 * Forge can't find {@code @Mod} and reports "has mods that were not found", so
 * {@link #registerRedirects(RetromodTransformer)} returns early off NeoForge.
 */
public class Forge_1_20_to_NeoForge_1_21 implements VersionShim {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-ForgeNeoMig");
    
    @Override
    public String getShimName() {
        return "Forge 1.20 to NeoForge 1.21";
    }
    
    @Override
    public String getSourceVersion() {
        return "1.20";
    }
    
    @Override
    public String getTargetVersion() {
        return "1.21";
    }
    
    @Override
    public String getModLoaderType() {
        return "forge";
    }
    
    @Override
    public void registerRedirects(RetromodTransformer transformer) {
        if (!McReflect.isNeoForge()
                && supportsForgeOfficialNetworkBridge(
                        RetromodVersion.TARGET_MC_VERSION)) {
            registerForgeOfficialNetworkBridge(transformer);
        }

        // These only apply on a NeoForge runtime; on Forge they break @Mod lookup (see class javadoc).
        if (!McReflect.isNeoForge()) {
            LOGGER.debug("Skipping Forge → NeoForge migration redirects (runtime is not NeoForge)");
            return;
        }

        transformer.registerClassRedirect(
            "net/minecraftforge/common/MinecraftForge",
            "net/neoforged/neoforge/common/NeoForge"
        );
        transformer.registerClassRedirect(
            "net/minecraftforge/fml/common/Mod",
            "net/neoforged/fml/common/Mod"
        );
        transformer.registerClassRedirect(
            "net/minecraftforge/eventbus/api/SubscribeEvent",
            "net/neoforged/bus/api/SubscribeEvent"
        );
        transformer.registerClassRedirect(
            "net/minecraftforge/eventbus/api/IEventBus",
            "net/neoforged/bus/api/IEventBus"
        );
        transformer.registerClassRedirect(
            "net/minecraftforge/eventbus/api/Event",
            "net/neoforged/bus/api/Event"
        );
        
        // ForgeRegistries and IForgeRegistry are handled by ForgeRegistryApiShim (field redirects to
        // vanilla Registries ResourceKeys), not class-redirected here: a class redirect would rewrite
        // the GETSTATIC owner before those field redirects could match, and NeoForgeRegistries lacks
        // the BLOCKS/ITEMS/... fields anyway (NoSuchFieldError).
        transformer.registerClassRedirect(
            "net/minecraftforge/registries/DeferredRegister",
            "net/neoforged/neoforge/registries/DeferredRegister"
        );
        transformer.registerClassRedirect(
            "net/minecraftforge/registries/RegistryObject",
            "net/neoforged/neoforge/registries/DeferredHolder"
        );

        // Dist markers: the Dist enum moved from the Forge package to NeoForge. AnnotationPolyfill
        // maps these but doesn't run on the NeoForge path (Fabric + CLI only), so without this a Forge
        // mod using Dist in code hits NoClassDefFoundError. @OnlyIn was deleted; map it to a no-op
        // annotation (metadata only). DistExecutor is supplied as a synthetic by ForgeNeoForgeSynthetics.
        transformer.registerClassRedirect(
            "net/minecraftforge/api/distmarker/Dist",
            "net/neoforged/api/distmarker/Dist"
        );
        transformer.registerClassRedirect(
            "net/minecraftforge/api/distmarker/OnlyIn",
            "java/lang/annotation/Retention"
        );
        transformer.registerClassRedirect(
            "net/minecraftforge/api/distmarker/OnlyIns",
            "java/lang/annotation/Retention"
        );

        // capabilities
        transformer.registerClassRedirect(
            "net/minecraftforge/common/capabilities/Capability",
            "net/neoforged/neoforge/capabilities/BlockCapability"
        );
        transformer.registerClassRedirect(
            "net/minecraftforge/common/capabilities/CapabilityManager",
            "net/neoforged/neoforge/capabilities/Capabilities"
        );
        transformer.registerClassRedirect(
            "net/minecraftforge/common/util/LazyOptional",
            "java/util/Optional"
        );
        
        // ForgeConfigSpec renamed to ModConfigSpec
        transformer.registerClassRedirect(
            "net/minecraftforge/common/ForgeConfigSpec",
            "net/neoforged/neoforge/common/ModConfigSpec"
        );
        transformer.registerClassRedirect(
            "net/minecraftforge/common/ForgeConfigSpec$Builder",
            "net/neoforged/neoforge/common/ModConfigSpec$Builder"
        );
        transformer.registerClassRedirect(
            "net/minecraftforge/common/ForgeConfigSpec$ConfigValue",
            "net/neoforged/neoforge/common/ModConfigSpec$ConfigValue"
        );
        transformer.registerClassRedirect(
            "net/minecraftforge/common/ForgeConfigSpec$BooleanValue",
            "net/neoforged/neoforge/common/ModConfigSpec$BooleanValue"
        );
        transformer.registerClassRedirect(
            "net/minecraftforge/common/ForgeConfigSpec$IntValue",
            "net/neoforged/neoforge/common/ModConfigSpec$IntValue"
        );
        transformer.registerClassRedirect(
            "net/minecraftforge/common/ForgeConfigSpec$DoubleValue",
            "net/neoforged/neoforge/common/ModConfigSpec$DoubleValue"
        );
        transformer.registerClassRedirect(
            "net/minecraftforge/common/ForgeConfigSpec$LongValue",
            "net/neoforged/neoforge/common/ModConfigSpec$LongValue"
        );
        transformer.registerClassRedirect(
            "net/minecraftforge/common/ForgeConfigSpec$EnumValue",
            "net/neoforged/neoforge/common/ModConfigSpec$EnumValue"
        );

        // FML
        transformer.registerClassRedirect(
            "net/minecraftforge/fml/ModLoadingContext",
            "net/neoforged/fml/ModLoadingContext"
        );
        transformer.registerClassRedirect(
            "net/minecraftforge/fml/loading/FMLPaths",
            "net/neoforged/fml/loading/FMLPaths"
        );
        transformer.registerClassRedirect(
            "net/minecraftforge/fml/loading/FMLEnvironment",
            "net/neoforged/fml/loading/FMLEnvironment"
        );
        transformer.registerClassRedirect(
            "net/minecraftforge/fml/LogicalSide",
            "net/neoforged/fml/LogicalSide"
        );
        transformer.registerClassRedirect(
            "net/minecraftforge/fml/util/thread/SidedThreadGroup",
            "net/neoforged/fml/util/thread/SidedThreadGroup"
        );
        transformer.registerClassRedirect(
            "net/minecraftforge/fml/util/thread/SidedThreadGroups",
            "net/neoforged/fml/util/thread/SidedThreadGroups"
        );
        transformer.registerClassRedirect(
            "net/minecraftforge/forgespi/language/IModInfo",
            "net/neoforged/neoforgespi/language/IModInfo"
        );
        transformer.registerFieldRedirect(
            "net/neoforged/fml/loading/FMLEnvironment", "dist",
            "Lnet/neoforged/api/distmarker/Dist;",
            "net/neoforged/fml/loading/FMLEnvironment", "getDist",
            "()Lnet/neoforged/api/distmarker/Dist;"
        );

        // ForgeSpawnEggItem's constructor has the same supplier and color shape as the removed
        // DeferredSpawnEggItem API. The embedded replacement moves the entity type onto the modern
        // item properties before calling SpawnEggItem, so generated Forge content can still bind.
        transformer.registerClassRedirect(
            "net/minecraftforge/common/ForgeSpawnEggItem",
            "net/neoforged/neoforge/common/DeferredSpawnEggItem"
        );
        // FMLJavaModLoadingContext (the get().getModEventBus() entry point NeoForge deleted) is
        // redirected to the B4 synthetic's name in ForgeEventApiShim, an API shim that runs on BOTH
        // the offline CLI/AOT batch and the live runtime. This migration shim only runs at runtime
        // (ServiceLoader), so the redirect lives there, not here, to keep the two paths consistent.

        registerNetworkBridge(transformer);
        
        // client events
        transformer.registerClassRedirect(
            "net/minecraftforge/client/event/RenderGuiOverlayEvent",
            "net/neoforged/neoforge/client/event/RenderGuiLayerEvent"
        );
        transformer.registerClassRedirect(
            "net/minecraftforge/client/event/EntityRenderersEvent",
            "net/neoforged/neoforge/client/event/EntityRenderersEvent"
        );
        
        // data generation
        transformer.registerClassRedirect(
            "net/minecraftforge/data/event/GatherDataEvent",
            "net/neoforged/neoforge/data/event/GatherDataEvent"
        );

        // Mod lifecycle + server events (verified against NeoForge 26.2; used by the
        // snapshot.7 acceptance set Macaw's Roofs/Trapdoors/Bridges). FML lifecycle events
        // stay under net.neoforged.fml; server events move to the neoforge package.
        transformer.registerClassRedirect(
            "net/minecraftforge/fml/event/lifecycle/FMLCommonSetupEvent",
            "net/neoforged/fml/event/lifecycle/FMLCommonSetupEvent"
        );
        transformer.registerClassRedirect(
            "net/minecraftforge/fml/event/lifecycle/FMLClientSetupEvent",
            "net/neoforged/fml/event/lifecycle/FMLClientSetupEvent"
        );
        transformer.registerClassRedirect(
            "net/minecraftforge/event/server/ServerStartingEvent",
            "net/neoforged/neoforge/event/server/ServerStartingEvent"
        );
        // Forge extension interfaces (the IForgeItem/IForgeBlock/... a mod's custom Item/Block/
        // Entity/BlockEntity implements) were renamed on NeoForge to I<Type>Extension. Verified
        // present in neoforge-26.2.0.0-beta-universal.jar. A Forge mod's custom Item implements
        // IForgeItem, so without these it dies at construct with NoClassDefFoundError (Macaw's on
        // NeoForge 26.2). NOTE: Forge_1_21_11_to_26_1 has the FORGE-host counterpart (drop just the
        // "I", stay forge-packaged) gated OFF on NeoForge so it can't clobber these.
        transformer.registerClassRedirect(
            "net/minecraftforge/common/extensions/IForgeItem",
            "net/neoforged/neoforge/common/extensions/IItemExtension"
        );
        transformer.registerClassRedirect(
            "net/minecraftforge/common/extensions/IForgeBlock",
            "net/neoforged/neoforge/common/extensions/IBlockExtension"
        );
        transformer.registerClassRedirect(
            "net/minecraftforge/common/extensions/IForgeEntity",
            "net/neoforged/neoforge/common/extensions/IEntityExtension"
        );
        transformer.registerClassRedirect(
            "net/minecraftforge/common/extensions/IForgeBlockEntity",
            "net/neoforged/neoforge/common/extensions/IBlockEntityExtension"
        );

        transformer.registerFieldRedirect(
            "net/minecraftforge/common/MinecraftForge",
            "EVENT_BUS",
            "net/neoforged/neoforge/common/NeoForge",
            "EVENT_BUS"
        );
    }

    /** True where Forge still exposes the event-channel object-registration bridge. */
    static boolean supportsForgeOfficialNetworkBridge(String targetVersion) {
        return RetromodVersion.compareMcVersions(targetVersion, "1.20.6") >= 0
                && RetromodVersion.compareMcVersions(targetVersion, "26.1") < 0;
    }

    /** Bridges Forge 1.20.1 networking to the Forge 1.20.6 through 1.21.x surface. */
    static void registerForgeOfficialNetworkBridge(RetromodTransformer transformer) {
        String builder = "com/retromod/shim/forge/embedded/LegacyForgeChannelBuilder";
        String eventAdapter = "com/retromod/shim/forge/embedded/LegacyForgeNetworkEventAdapter";
        String oldBuilder = "net/minecraftforge/network/NetworkRegistry$ChannelBuilder";
        String oldEventChannel = "net/minecraftforge/network/event/EventNetworkChannel";
        String eventChannel = "net/minecraftforge/network/EventNetworkChannel";
        String oldClientPayload =
                "net/minecraftforge/network/NetworkEvent$ClientCustomPayloadEvent";
        String oldServerPayload =
                "net/minecraftforge/network/NetworkEvent$ServerCustomPayloadEvent";
        String payload = "net/minecraftforge/event/network/CustomPayloadEvent";
        String oldContext = "net/minecraftforge/network/NetworkEvent$Context";
        String context = "net/minecraftforge/event/network/CustomPayloadEvent$Context";

        SyntheticEmbedder.registerClassResource(transformer, builder,
                com.retromod.shim.forge.embedded.LegacyForgeChannelBuilder.class);
        SyntheticEmbedder.registerClassResource(transformer, eventAdapter,
                com.retromod.shim.forge.embedded.LegacyForgeNetworkEventAdapter.class);

        transformer.registerClassRedirect(oldBuilder, builder);
        transformer.registerClassRedirect(oldEventChannel, eventChannel);
        transformer.registerClassRedirect(oldClientPayload, payload);
        transformer.registerClassRedirect(oldServerPayload, payload);
        transformer.registerClassRedirect(oldContext, context);

        for (String resourceId : new String[]{
                "Lnet/minecraft/resources/ResourceLocation;",
                "Lnet/minecraft/resources/Identifier;"}) {
            transformer.registerMethodRedirect(
                    builder, "named", "(" + resourceId + ")L" + builder + ";",
                    builder, "named", "(Ljava/lang/Object;)L" + builder + ";");
        }
        transformer.registerMethodRedirect(
                builder, "eventNetworkChannel", "()L" + eventChannel + ";",
                builder, "eventNetworkChannel", "()Ljava/lang/Object;");
        transformer.registerMethodRedirect(
                builder, "simpleChannel",
                "()Lnet/minecraftforge/network/simple/SimpleChannel;",
                builder, "simpleChannel", "()Ljava/lang/Object;");

        transformer.registerConvertingRedirect(
                eventChannel, "registerObject", "(Ljava/lang/Object;)V",
                eventChannel, "registerObject", "(Ljava/lang/Object;)L" + eventChannel + ";",
                0, org.objectweb.asm.Opcodes.POP);
        transformer.registerConvertingRedirect(
                eventChannel, "addListener", "(Ljava/util/function/Consumer;)V",
                eventChannel, "addListener", "(Ljava/util/function/Consumer;)L" + eventChannel + ";",
                0, org.objectweb.asm.Opcodes.POP);

        transformer.registerMethodRedirect(
                payload, "getSource", "()Ljava/util/function/Supplier;",
                eventAdapter, "getSource",
                "(Ljava/lang/Object;)Ljava/util/function/Supplier;", true);
        transformer.registerMethodRedirect(
                context, "getNetworkManager", "()Lnet/minecraft/network/Connection;",
                context, "getConnection", "()Lnet/minecraft/network/Connection;");
    }

    /**
     * The SimpleChannel surface bridge (#156); package-visible so the transform-shape test
     * can exercise it without the NeoForge-host gate.
     */
    static void registerNetworkBridge(RetromodTransformer transformer) {
        // network: the old bare class redirects (NetworkRegistry -> PayloadRegistrar, SimpleChannel
        // -> IPayloadHandler) were actively harmful: NeoForge's PayloadRegistrar has never had
        // newSimpleChannel, so every MCreator-style 1.20.1 mod died NoSuchMethodError in <clinit>
        // (#156, Wonderland). Route the whole SimpleChannel surface onto the embedded NetworkShim
        // instead: the mod LOADS and its packet registrations are collected (soft-fail: cross-side
        // sync is inert until the replay bridge lands; tracked as 1.3.0 Forge-to-NeoForge work,
        // together with NetworkHooks/ITeleporter/LivingTickEvent from the same family).
        String netShim = "com/retromod/shim/forge/embedded/NetworkShim";
        String wrapper = netShim + "$SimpleChannelWrapper";
        String builder = netShim + "$MessageBuilder";
        transformer.registerClassRedirect("net/minecraftforge/network/NetworkRegistry", netShim);
        transformer.registerClassRedirect("net/minecraftforge/network/simple/SimpleChannel", wrapper);
        transformer.registerClassRedirect(
            "net/minecraftforge/network/simple/SimpleChannel$MessageBuilder", builder);
        // newSimpleChannel: the mod's Forge-typed descriptor must erase to the shim's Object form
        // (keyed on both the pre- and post-class-redirect spellings; the return CHECKCASTs back).
        // Class remapping runs first on 26.1, so register both spellings of the resource ID.
        for (String owner : new String[]{"net/minecraftforge/network/NetworkRegistry", netShim}) {
            for (String resourceIdDescriptor : new String[]{
                    "Lnet/minecraft/resources/ResourceLocation;",
                    "Lnet/minecraft/resources/Identifier;"}) {
                for (String ret : new String[]{"Lnet/minecraftforge/network/simple/SimpleChannel;",
                                               "L" + wrapper + ";"}) {
                    transformer.registerMethodRedirect(
                        owner, "newSimpleChannel",
                        "(" + resourceIdDescriptor + "Ljava/util/function/Supplier;"
                            + "Ljava/util/function/Predicate;Ljava/util/function/Predicate;)" + ret,
                        netShim, "newSimpleChannel",
                        "(Ljava/lang/Object;Ljava/util/function/Supplier;Ljava/util/function/Predicate;"
                            + "Ljava/util/function/Predicate;)Ljava/lang/Object;");
                }
            }
        }
        // The direction-qualified messageBuilder: NetworkDirection erases to the shim's Object.
        for (String ret : new String[]{
                "Lnet/minecraftforge/network/simple/SimpleChannel$MessageBuilder;",
                "L" + builder + ";"}) {
            transformer.registerMethodRedirect(
                wrapper, "messageBuilder",
                "(Ljava/lang/Class;ILnet/minecraftforge/network/NetworkDirection;)" + ret,
                wrapper, "messageBuilder",
                "(Ljava/lang/Class;ILjava/lang/Object;)L" + builder + ";");
        }
        // send(PacketTarget, msg): the target erases to Object (delivery is best-effort/no-op
        // until the replay bridge lands; the call must not throw).
        transformer.registerMethodRedirect(
            wrapper, "send",
            "(Lnet/minecraftforge/network/PacketDistributor$PacketTarget;Ljava/lang/Object;)V",
            wrapper, "send", "(Ljava/lang/Object;Ljava/lang/Object;)V");

        // Forge 47 returns an IndexedMessageCodec.MessageHandler even when callers only discard
        // it. That class no longer exists on NeoForge. Retype the call to the wrapper's erased
        // Object result so the registration is collected without retaining the deleted class.
        String registrationArgs = "(ILjava/lang/Class;Ljava/util/function/BiConsumer;"
                + "Ljava/util/function/Function;Ljava/util/function/BiConsumer;)";
        String oldHandler = "Lnet/minecraftforge/network/simple/IndexedMessageCodec$MessageHandler;";
        for (String owner : new String[]{
                "net/minecraftforge/network/simple/SimpleChannel", wrapper}) {
            transformer.registerConvertingRedirect(
                    owner, "registerMessage", registrationArgs + oldHandler,
                    wrapper, "registerMessage", registrationArgs + "Ljava/lang/Object;", 0, 0);
        }
    }

    @Override
    public String[] getShimClasses() {
        // Inner classes must be listed explicitly: the embed loaders resolve exactly these
        // resource names (no glob), and the SimpleChannel bridge redirects point AT the inners
        // (NetworkShim$SimpleChannelWrapper etc.), so an outer-only list left them unresolvable
        // in the mod's module (#156).
        return new String[] {
            "com.retromod.shim.forge.embedded.ForgeRegistriesShim",
            "com.retromod.shim.forge.embedded.CapabilityShim",
            "com.retromod.shim.forge.embedded.CapabilityShim$LazyOptionalWrapper",
            "com.retromod.shim.forge.embedded.CapabilityShim$Tokens",
            "com.retromod.shim.forge.embedded.NetworkShim",
            "com.retromod.shim.forge.embedded.NetworkShim$SimpleChannelWrapper",
            "com.retromod.shim.forge.embedded.NetworkShim$MessageBuilder",
            "com.retromod.shim.forge.embedded.NetworkShim$PacketRegistration",
            "com.retromod.shim.forge.embedded.NetworkShim$PayloadWrapper",
            "com.retromod.shim.forge.embedded.NetworkShim$PacketDistributor",
            "com.retromod.shim.forge.embedded.NetworkShim$PacketDistributor$PacketTarget"
        };
    }
}
