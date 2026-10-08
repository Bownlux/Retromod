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

        // Vanilla, so it applies on Forge too: RecordItem was removed in 1.21.
        com.retromod.shim.common.RemovedItemBaseBridge.registerRemovedIn(transformer, "1.21");
        com.retromod.shim.common.LegacySpawnPlacementTypeBridge.register(transformer);
        com.retromod.shim.common.LegacyMobTypeBridge.register(transformer);
        com.retromod.shim.common.LegacyAttributeApiBridge.register(transformer);
        com.retromod.shim.common.LegacyItemAttributeOverrideAdapter.register(transformer);
        com.retromod.shim.common.LegacyDispenserBridge.register(transformer);
        com.retromod.shim.common.LegacyDyeColorBridge.register(transformer);
        com.retromod.shim.common.LegacyFoodPropertiesBridge.register(transformer);
        com.retromod.shim.common.LegacyToolItemBridge.register(transformer);
        com.retromod.shim.common.Legacy1205MemberBridge.register(transformer);
        com.retromod.shim.common.LegacyCodecTypeBridge.register(transformer);
        com.retromod.shim.common.LegacyRegistryNbtBridge.register(transformer);
        com.retromod.shim.common.LegacyTreeGrowerBridge.register(transformer);
        com.retromod.shim.common.LegacySynchedDataBridge.register(transformer);
        com.retromod.shim.common.LegacyFallingBlockBridge.register(transformer);
        com.retromod.shim.fabric.Pre1_20_5MojangArmorMaterialBridge.register(transformer);
        com.retromod.shim.fabric.Pre1_20_5MojangParticleTypeBridge.register(transformer);
        com.retromod.shim.api.common.LegacyFrameworkNetworkBridge.register(transformer);
        com.retromod.shim.common.LegacyPotionUtilsBridge.register(transformer);
        com.retromod.shim.common.LegacyHolderConstantBridge.register(transformer);
        com.retromod.shim.common.RemovedItemBaseBridge.registerRemovedIn(transformer, "1.20.5");

        // These only apply on a NeoForge runtime; on Forge they break @Mod lookup (see class javadoc).
        if (!McReflect.isNeoForge()) {
            LOGGER.debug("Skipping Forge → NeoForge migration redirects (runtime is not NeoForge)");
            return;
        }

        ForgeNeoForgeSameShapeMoves.register(transformer);
        LegacyBrewingRegistryBridge.register(transformer);
        LegacyForgeEventFactoryBridge.register(transformer, com.retromod.core.ClassResourceInspector::exists);
        LegacyModLoaderBridge.register(transformer, com.retromod.core.ClassResourceInspector::exists);
        LegacyTeleporterBridge.register(transformer, com.retromod.core.ClassResourceInspector::exists);
        LegacyDespawnEventBridge.register(transformer, com.retromod.core.ClassResourceInspector::exists);
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

        // Capabilities are supplied whole by LegacyCapabilitySynthetics (ForgeNeoForgeApiBridge).
        // A class redirect here would rename the mod's references before those synthetics apply.
        
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
        // FMLEnvironment.dist stays a field read here. getDist() only exists from NeoForge 1.21.9,
        // and redirecting on every host broke each migrated mod that checked its side on 1.21.1
        // through 1.21.8 with NoSuchMethodError. NeoForge_1_21_8_to_1_21_9 redirects it where the
        // getter exists.

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
        ForgeMenuBridge.register(transformer);
        ForgeNeoForgeApiBridge.register(transformer);
        com.retromod.shim.forge.capability.LegacyGenericListenerBridge.register(transformer);
        
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
        // A level mixin implements IForgeLevel to hand out multipart entities (#308, More
        // Hitboxes). Both keep their shape on NeoForge 1.21.1 through 26.3.
        transformer.registerClassRedirect(
            "net/minecraftforge/common/extensions/IForgeLevel",
            "net/neoforged/neoforge/common/extensions/ILevelExtension"
        );
        transformer.registerClassRedirect(
            "net/minecraftforge/entity/PartEntity",
            "net/neoforged/neoforge/entity/PartEntity"
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
        // instead: the mod loads, its registrations are collected, and NetworkShim registers each
        // channel as a NeoForge payload when NeoForge locks its network registry.
        String netShim = "com/retromod/shim/forge/embedded/NetworkShim";
        String wrapper = netShim + "$SimpleChannelWrapper";
        String builder = netShim + "$MessageBuilder";
        transformer.registerClassRedirect("net/minecraftforge/network/NetworkRegistry", netShim);
        transformer.registerClassRedirect("net/minecraftforge/network/simple/SimpleChannel", wrapper);
        transformer.registerClassRedirect(
            "net/minecraftforge/network/simple/SimpleChannel$MessageBuilder", builder);
        transformer.registerClassRedirect(
            "net/minecraftforge/network/NetworkDirection", netShim + "$NetworkDirection");
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
        registerNetworkDeliveryBridge(transformer, netShim, wrapper);

        // Forge 47 returns an IndexedMessageCodec.MessageHandler even when callers only discard
        // it. That class no longer exists on NeoForge. Retype the call to the wrapper's erased
        // Object result so the registration is collected without retaining the deleted class.
        String handlerArgs = "ILjava/lang/Class;Ljava/util/function/BiConsumer;"
                + "Ljava/util/function/Function;Ljava/util/function/BiConsumer;";
        String oldHandler = "Lnet/minecraftforge/network/simple/IndexedMessageCodec$MessageHandler;";
        // The second form adds an Optional<NetworkDirection>, which erases to Optional.
        for (String registrationArgs : new String[]{
                "(" + handlerArgs + ")", "(" + handlerArgs + "Ljava/util/Optional;)"}) {
            for (String owner : new String[]{
                    "net/minecraftforge/network/simple/SimpleChannel", wrapper}) {
                transformer.registerConvertingRedirect(
                        owner, "registerMessage", registrationArgs + oldHandler,
                        wrapper, "registerMessage", registrationArgs + "Ljava/lang/Object;", 0, 0);
            }
        }
    }

    /**
     * The parts of Forge's networking surface that NetworkShim delivers through NeoForge payloads:
     * the handler context, the packet targets, and the send variants that name Minecraft types.
     * Retromod cannot name those types, so each is erased to Object here and cast back at the
     * mod's call site.
     */
    private static void registerNetworkDeliveryBridge(RetromodTransformer transformer,
            String netShim, String wrapper) {
        String forgeContext = "net/minecraftforge/network/NetworkEvent$Context";
        String context = netShim + "$Context";
        String forgeDistributor = "net/minecraftforge/network/PacketDistributor";
        String distributor = netShim + "$PacketDistributor";
        String forgeTargetPoint = forgeDistributor + "$TargetPoint";
        String targetPoint = distributor + "$TargetPoint";
        String forgeTarget = "Lnet/minecraftforge/network/PacketDistributor$PacketTarget;";
        String forgeDirection = "Lnet/minecraftforge/network/NetworkDirection;";
        String direction = "L" + netShim + "$NetworkDirection;";
        String resourceKey = "Lnet/minecraft/resources/ResourceKey;";

        transformer.registerClassRedirect(forgeContext, context);
        transformer.registerClassRedirect(forgeDistributor, distributor);
        transformer.registerClassRedirect(forgeDistributor + "$PacketTarget", distributor + "$PacketTarget");
        transformer.registerClassRedirect(forgeTargetPoint, targetPoint);

        for (String owner : new String[]{forgeContext, context}) {
            transformer.registerMethodRedirect(owner, "getSender",
                    "()Lnet/minecraft/server/level/ServerPlayer;", context, "getSender", "()Ljava/lang/Object;");
            transformer.registerMethodRedirect(owner, "getNetworkManager",
                    "()Lnet/minecraft/network/Connection;", context, "getNetworkManager", "()Ljava/lang/Object;");
        }

        for (String owner : new String[]{"net/minecraftforge/network/simple/SimpleChannel", wrapper}) {
            for (String target : new String[]{forgeTarget, "L" + distributor + "$PacketTarget;"}) {
                transformer.registerMethodRedirect(owner, "send",
                        "(" + target + "Ljava/lang/Object;)V",
                        wrapper, "send", "(Ljava/lang/Object;Ljava/lang/Object;)V");
            }
            for (String sendDirection : new String[]{forgeDirection, direction}) {
                transformer.registerMethodRedirect(owner, "sendTo",
                        "(Ljava/lang/Object;Lnet/minecraft/network/Connection;" + sendDirection + ")V",
                        wrapper, "sendTo", "(Ljava/lang/Object;Ljava/lang/Object;" + direction + ")V");
            }
            transformer.registerMethodRedirect(owner, "isRemotePresent",
                    "(Lnet/minecraft/network/Connection;)Z", wrapper, "isRemotePresent", "(Ljava/lang/Object;)Z");
        }

        String pointFactory = "(Ljava/lang/Object;DDDDLjava/lang/Object;)L" + targetPoint + ";";
        String pointFactoryNoExclusion = "(DDDDLjava/lang/Object;)L" + targetPoint + ";";
        for (String owner : new String[]{forgeTargetPoint, targetPoint}) {
            transformer.registerConstructorRedirect(owner,
                    "(Lnet/minecraft/server/level/ServerPlayer;DDDD" + resourceKey + ")V",
                    targetPoint, "of", pointFactory);
            transformer.registerConstructorRedirect(owner, "(DDDD" + resourceKey + ")V",
                    targetPoint, "of", pointFactoryNoExclusion);
            transformer.registerMethodRedirect(owner, "p",
                    "(DDDD" + resourceKey + ")Ljava/util/function/Supplier;",
                    targetPoint, "p", "(DDDDLjava/lang/Object;)Ljava/util/function/Supplier;");
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
            "com.retromod.shim.forge.embedded.NetworkShim$PacketDistributor",
            "com.retromod.shim.forge.embedded.NetworkShim$PacketDistributor$PacketTarget",
            "com.retromod.shim.forge.embedded.NetworkShim$PacketDistributor$TargetPoint",
            "com.retromod.shim.forge.embedded.NetworkShim$NetworkDirection",
            "com.retromod.shim.forge.embedded.NetworkShim$Context",
            "com.retromod.shim.forge.embedded.NetworkShim$PayloadBridge",
            "com.retromod.shim.forge.embedded.NetworkShim$Payload",
            "com.retromod.shim.forge.embedded.NetworkShim$CodecHandler",
            "com.retromod.shim.forge.embedded.NetworkShim$HandlerHandler"
        };
    }
}
