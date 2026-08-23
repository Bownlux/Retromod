/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 *
 * Fabric API changes: https://fabricmc.net/2026/03/14/261.html
 *                     https://docs.fabricmc.net/develop/porting/26.1/fabric-api
 */
package com.retromod.shim.fabric;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.VersionShim;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Fabric 1.21.11 mods to 26.1+. The largest shim in the chain: 26.1 dropped all
 * obfuscation and Fabric API renamed hundreds of classes/methods to match. Vanilla
 * class/package moves go through mojang-class-moves-26.1.tsv and the intermediary to
 * Mojang remap; this shim covers the Fabric API side.
 */
public class Fabric_1_21_11_to_26_1 implements VersionShim {

    @Override
    public String getShimName() {
        return "Fabric 1.21.11 to 26.1";
    }

    @Override
    public String getSourceVersion() {
        return "1.21.11";
    }

    @Override
    public String getTargetVersion() {
        return "26.1";
    }

    @Override
    public String getModLoaderType() {
        return "fabric";
    }

    @Override
    public void registerRedirects(RetromodTransformer transformer) {

        // Vanilla content holder fields whose type became ResourceKey<X> in MC 1.21.
        registerRegistryRefRedirects(transformer);

        // Vanilla class moves shared with the NeoForge 26.1 shim (#64).
        com.retromod.shim.common.Common_1_21_11_to_26_1_ClassMoves.register(transformer);
        // The bundled Fabric intermediary table still expands these stable field ids to their
        // pre-1.21.11 RULE_* names. Mojang-named loaders already carry the current names.
        com.retromod.shim.common.Common_1_21_11_to_26_1_ClassMoves
                .registerFabricGameRuleFieldRenames(transformer);

        // Legacy Block.randomTicks accessors now land on the inherited
        // BlockBehaviour.isRandomlyTicking field. The target field is final and every
        // BlockState caches its value, so the Mixin pass adds @Mutable and the call adapter
        // refreshes those states after a proven accessor setter runs (#228).
        String block = "net/minecraft/world/level/block/Block";
        String blockBehaviour = "net/minecraft/world/level/block/state/BlockBehaviour";
        transformer.registerFieldRedirect(
                "net/minecraft/class_2248", "randomTicks", "Z",
                blockBehaviour, "isRandomlyTicking", "Z");
        transformer.registerFieldRedirect(
                block, "randomTicks", "Z",
                blockBehaviour, "isRandomlyTicking", "Z");
        transformer.registerFieldRedirect(
                block, "field_10641", "Z",
                blockBehaviour, "isRandomlyTicking", "Z");
        transformer.registerFieldRedirect(
                block, "isRandomlyTicking", "Z",
                blockBehaviour, "isRandomlyTicking", "Z");
        transformer.registerMutableAccessorDestination(
                blockBehaviour, "isRandomlyTicking", "Z");
        com.retromod.core.SyntheticEmbedder.registerClassResource(
                transformer,
                com.retromod.shim.fabric.embedded.LegacyBlockRandomTickBridge.INTERNAL_NAME,
                com.retromod.shim.fabric.embedded.LegacyBlockRandomTickBridge.class);

        // Networking: S2C/C2S -> Clientbound/Serverbound
        transformer.registerClassRedirect(
            "net/fabricmc/fabric/api/networking/v1/C2SConfigurationChannelEvents",
            "net/fabricmc/fabric/api/client/networking/v1/ServerboundConfigurationChannelEvents"
        );
        transformer.registerClassRedirect(
            "net/fabricmc/fabric/api/networking/v1/C2SPlayChannelEvents",
            "net/fabricmc/fabric/api/client/networking/v1/ServerboundPlayChannelEvents"
        );
        // Client-side Fabric API already kept C2S channel events in client/networking before the
        // direction terminology changed. The original redirects above cover the older common
        // package, but not Pathmind's 1.21.11 spelling. Register the holder and callback types so
        // both GETSTATIC and the lambda's invokedynamic descriptor move together (#208).
        for (String kind : new String[]{"Configuration", "Play"}) {
            String oldOwner = "net/fabricmc/fabric/api/client/networking/v1/C2S"
                    + kind + "ChannelEvents";
            String newOwner = "net/fabricmc/fabric/api/client/networking/v1/Serverbound"
                    + kind + "ChannelEvents";
            transformer.registerClassRedirect(oldOwner, newOwner);
            transformer.registerClassRedirect(oldOwner + "$Register", newOwner + "$Register");
            transformer.registerClassRedirect(oldOwner + "$Unregister", newOwner + "$Unregister");
        }
        transformer.registerClassRedirect(
            "net/fabricmc/fabric/api/networking/v1/S2CConfigurationChannelEvents",
            "net/fabricmc/fabric/api/networking/v1/ClientboundConfigurationChannelEvents"
        );
        transformer.registerClassRedirect(
            "net/fabricmc/fabric/api/networking/v1/S2CPlayChannelEvents",
            "net/fabricmc/fabric/api/networking/v1/ClientboundPlayChannelEvents"
        );
        transformer.registerClassRedirect(
            "net/fabricmc/fabric/api/networking/v1/PacketByteBufs",
            "net/fabricmc/fabric/api/networking/v1/FriendlyByteBufs"
        );
        transformer.registerClassRedirect(
            "net/fabricmc/fabric/api/networking/v1/FabricServerConfigurationNetworkHandler",
            "net/fabricmc/fabric/api/networking/v1/FabricServerConfigurationPacketListenerImpl"
        );

        // PayloadTypeRegistry's static accessors were renamed twice; 26.1 has only
        // the newest spelling. Map both old generations to gen-3 (#94, CoroUtil/Watut):
        //   gen-1 (<= 0.111):     configurationClientbound() / ...Serverbound() / playClientbound() / playServerbound()
        //   gen-2 (~0.115-0.119): configurationS2C() / configurationC2S() / playS2C() / playC2S()
        //   gen-3 (26.1 host):    clientboundConfiguration() / serverboundConfiguration() / clientboundPlay() / serverboundPlay()
        // S2C = server->client = clientbound; C2S = serverbound. All static, no-arg,
        // returning PayloadTypeRegistry, so only the name differs.
        String PTR = "net/fabricmc/fabric/api/networking/v1/PayloadTypeRegistry";
        String PTR_DESC = "()Lnet/fabricmc/fabric/api/networking/v1/PayloadTypeRegistry;";
        String[][] ptrRenames = {
            // gen-1 -> gen-3
            {"configurationClientbound", "clientboundConfiguration"},
            {"configurationServerbound", "serverboundConfiguration"},
            {"playClientbound",          "clientboundPlay"},
            {"playServerbound",          "serverboundPlay"},
            // gen-2 -> gen-3
            {"configurationS2C", "clientboundConfiguration"},
            {"configurationC2S", "serverboundConfiguration"},
            {"playS2C",          "clientboundPlay"},
            {"playC2S",          "serverboundPlay"},
        };
        for (String[] r : ptrRenames) {
            transformer.registerMethodRedirect(PTR, r[0], PTR_DESC, PTR, r[1], PTR_DESC);
        }

        // World -> Level renames. ClientWorldEvents, ServerEntityWorldChangeEvents and
        // ServerWorldEvents go through FabricRenamedSamBridgesShim/FabricServerWorldEventsShim
        // instead: both their SAM methods and holder fields renamed (lambda trap + NoSuchFieldError).
        transformer.registerClassRedirect(
            "net/fabricmc/fabric/api/client/rendering/v1/WorldRenderEvents",
            "net/fabricmc/fabric/api/client/rendering/v1/level/LevelRenderEvents"
        );
        transformer.registerClassRedirect(
            "net/fabricmc/fabric/api/client/rendering/v1/WorldRenderContext",
            "net/fabricmc/fabric/api/client/rendering/v1/level/LevelRenderContext"
        );
        // Tick-event inner interfaces: $Start/EndWorldTick -> $Start/EndLevelTick. The
        // outer classes and SAM methods are unchanged; only the parameter went
        // ClientWorld/ServerWorld -> ClientLevel/ServerLevel, which the harvest already
        // remaps, so this is a redirect not a lambda trap. The 1.16.5->1.17 shim has the
        // same entries but only covers <=1.16 mods; a 1.19-1.21 mod reaches 26.1 here.
        transformer.registerClassRedirect(
            "net/fabricmc/fabric/api/client/event/lifecycle/v1/ClientTickEvents$StartWorldTick",
            "net/fabricmc/fabric/api/client/event/lifecycle/v1/ClientTickEvents$StartLevelTick"
        );
        transformer.registerClassRedirect(
            "net/fabricmc/fabric/api/client/event/lifecycle/v1/ClientTickEvents$EndWorldTick",
            "net/fabricmc/fabric/api/client/event/lifecycle/v1/ClientTickEvents$EndLevelTick"
        );
        transformer.registerClassRedirect(
            "net/fabricmc/fabric/api/event/lifecycle/v1/ServerTickEvents$StartWorldTick",
            "net/fabricmc/fabric/api/event/lifecycle/v1/ServerTickEvents$StartLevelTick"
        );
        transformer.registerClassRedirect(
            "net/fabricmc/fabric/api/event/lifecycle/v1/ServerTickEvents$EndWorldTick",
            "net/fabricmc/fabric/api/event/lifecycle/v1/ServerTickEvents$EndLevelTick"
        );
        // The outer event holders renamed with their callback types. Redirecting only the nested
        // interfaces leaves GETSTATIC START_WORLD_TICK/END_WORLD_TICK intact, so the callback lambda
        // links but client initialization still aborts with NoSuchFieldError (#208, Pathmind).
        String clientTicks = "net/fabricmc/fabric/api/client/event/lifecycle/v1/ClientTickEvents";
        String serverTicks = "net/fabricmc/fabric/api/event/lifecycle/v1/ServerTickEvents";
        for (String owner : new String[]{clientTicks, serverTicks}) {
            transformer.registerFieldRedirect(owner, "START_WORLD_TICK", owner, "START_LEVEL_TICK");
            transformer.registerFieldRedirect(owner, "END_WORLD_TICK", owner, "END_LEVEL_TICK");
        }

        // ScreenHandler -> Menu renames
        transformer.registerClassRedirect(
            "net/fabricmc/fabric/api/screenhandler/v1/ExtendedScreenHandlerFactory",
            "net/fabricmc/fabric/api/menu/v1/ExtendedMenuProvider"
        );
        transformer.registerClassRedirect(
            "net/fabricmc/fabric/api/screenhandler/v1/ExtendedScreenHandlerType",
            "net/fabricmc/fabric/api/menu/v1/ExtendedMenuType"
        );
        // The nested factory moved with its outer class; without this a mod referencing it dies
        // NoClassDefFoundError even though the outer redirect fired (#147, VillagerViewer). Target
        // verified present in fabric-menu-api-v1 on 26.2: ExtendedMenuType$ExtendedFactory.
        transformer.registerClassRedirect(
            "net/fabricmc/fabric/api/screenhandler/v1/ExtendedScreenHandlerType$ExtendedFactory",
            "net/fabricmc/fabric/api/menu/v1/ExtendedMenuType$ExtendedFactory"
        );
        transformer.registerClassRedirect(
            "net/fabricmc/fabric/api/screenhandler/v1/FabricScreenHandlerFactory",
            "net/fabricmc/fabric/api/menu/v1/FabricMenuProvider"
        );

        // ItemGroup -> CreativeTab renames. ItemGroupEvents goes through
        // FabricItemGroupEventsShim instead (modifyEntriesEvent->modifyOutputEvent and the
        // SAM modifyEntries->modifyOutput, a lambda trap). Only the data-class renames live
        // here; keep FabricItemGroupEntries -> FabricCreativeModeTabOutput since the shim's
        // addAfter->insertAfter keys on the new owner.
        transformer.registerClassRedirect(
            "net/fabricmc/fabric/api/itemgroup/v1/FabricItemGroup",
            "net/fabricmc/fabric/api/creativetab/v1/FabricCreativeModeTab"
        );
        transformer.registerClassRedirect(
            "net/fabricmc/fabric/api/itemgroup/v1/FabricItemGroupEntries",
            "net/fabricmc/fabric/api/creativetab/v1/FabricCreativeModeTabOutput"
        );

        // Rendering renames.
        transformer.registerClassRedirect(
            "net/fabricmc/fabric/api/client/rendering/v1/BlockRenderLayerMap",
            "com/retromod/generated/legacyfabric/BlockRenderLayerMap"
        );
        // EntityModelLayerRegistry, LivingEntityFeatureRendererRegistrationCallback,
        // DrawItemStackOverlayCallback, TooltipComponentCallback,
        // ServerChunkEvents$LevelTypeChange and LandPathNodeTypesRegistry go through
        // FabricEntityModelLayerShim/FabricRenamedSamBridgesShim: their SAM methods renamed
        // (lambda traps).
        transformer.registerClassRedirect(
            "net/fabricmc/fabric/api/client/model/BakedModelManager",
            "net/fabricmc/fabric/api/client/model/loading/v1/FabricModelManager"
        );

        // Registry renames
        transformer.registerClassRedirect(
            "net/fabricmc/fabric/api/event/registry/FuelRegistryEvents",
            "net/fabricmc/fabric/api/registry/FuelValueEvents"
        );
        transformer.registerClassRedirect(
            "net/fabricmc/fabric/api/registry/CompostingChanceRegistry",
            "net/fabricmc/fabric/api/registry/CompostableRegistry"
        );
        transformer.registerClassRedirect(
            "net/fabricmc/fabric/api/registry/SculkSensorFrequencyRegistry",
            "net/fabricmc/fabric/api/registry/VibrationFrequencyRegistry"
        );

        // Data generation renames
        transformer.registerClassRedirect(
            "net/fabricmc/fabric/api/datagen/v1/FabricDataOutput",
            "net/fabricmc/fabric/api/datagen/v1/FabricPackOutput"
        );
        transformer.registerClassRedirect(
            "net/fabricmc/fabric/api/datagen/v1/provider/FabricBlockLootTableGenerator",
            "net/fabricmc/fabric/api/datagen/v1/provider/FabricBlockLootSubProvider"
        );
        transformer.registerClassRedirect(
            "net/fabricmc/fabric/api/datagen/v1/provider/FabricTagProvider",
            "net/fabricmc/fabric/api/datagen/v1/provider/FabricTagsProvider"
        );
        transformer.registerClassRedirect(
            "net/fabricmc/fabric/api/datagen/v1/provider/FabricLootTableProvider",
            "net/fabricmc/fabric/api/datagen/v1/provider/FabricLootTableSubProvider"
        );

        // Entity renames
        transformer.registerClassRedirect(
            "net/fabricmc/fabric/api/object/builder/v1/entity/FabricEntityTypeBuilder",
            "net/fabricmc/fabric/api/object/builder/v1/entity/FabricEntityType$Builder"
        );
        transformer.registerClassRedirect(
            "net/fabricmc/fabric/api/entity/FabricTrackedDataRegistry",
            "net/fabricmc/fabric/api/object/builder/v1/entity/FabricEntityDataRegistry"
        );

        // Transfer API renames
        transformer.registerClassRedirect(
            "net/fabricmc/fabric/api/transfer/v1/storage/base/FabricReadView",
            "net/fabricmc/fabric/api/serialization/v1/value/FabricValueInput"
        );
        transformer.registerClassRedirect(
            "net/fabricmc/fabric/api/transfer/v1/storage/base/FabricWriteView",
            "net/fabricmc/fabric/api/serialization/v1/value/FabricValueOutput"
        );
        transformer.registerClassRedirect(
            "net/fabricmc/fabric/api/transfer/v1/item/InventoryStorage",
            "net/fabricmc/fabric/api/transfer/v1/item/ContainerStorage"
        );

        // KeyBinding -> KeyMapping
        transformer.registerClassRedirect(
            "net/fabricmc/fabric/api/client/keybinding/v1/KeyBindingHelper",
            "net/fabricmc/fabric/api/client/keymapping/v1/KeyMappingHelper"
        );

        // KeyMapping ctor: String category -> KeyMapping.Category record (1.21.9).
        // Mojang names, since the class-name remap and classRedirects ran first.
        transformer.registerConstructorRedirect(
            "net/minecraft/client/KeyMapping",
            "(Ljava/lang/String;Lcom/mojang/blaze3d/platform/InputConstants$Type;ILjava/lang/String;)V",
            "com/retromod/shim/fabric/embedded/KeyBindingShim", "create",
            "(Ljava/lang/String;Ljava/lang/Object;ILjava/lang/String;)Ljava/lang/Object;"
        );

        // Button gained a 7th CreateNarration param in 26.1.
        // new Button(...) calls -> ButtonShim factory.
        transformer.registerConstructorRedirect(
            "net/minecraft/client/gui/components/Button",
            "(IIIILnet/minecraft/network/chat/Component;Lnet/minecraft/client/gui/components/Button$OnPress;)V",
            "com/retromod/shim/fabric/embedded/ButtonShim", "create",
            "(IIIILjava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"
        );
        // super(...) calls in Button subclasses: add DEFAULT_NARRATION as the 7th arg.
        transformer.registerSuperConstructorRedirect(
            "net/minecraft/client/gui/components/Button",
            "(IIIILnet/minecraft/network/chat/Component;Lnet/minecraft/client/gui/components/Button$OnPress;)V",
            "(IIIILnet/minecraft/network/chat/Component;Lnet/minecraft/client/gui/components/Button$OnPress;Lnet/minecraft/client/gui/components/Button$CreateNarration;)V",
            "net/minecraft/client/gui/components/Button", "DEFAULT_NARRATION",
            "Lnet/minecraft/client/gui/components/Button$CreateNarration;"
        );

        // TranslatableContents ctor: 1-arg/2-arg forms removed, now (key, fallback, args).
        // Super-ctor redirect inserts the missing params (null fallback, empty args) into
        // the INVOKESPECIAL, keeping NEW/DUP/INVOKESPECIAL intact for the verifier.
        transformer.registerSuperConstructorRedirect(
            "net/minecraft/network/chat/contents/TranslatableContents",
            "(Ljava/lang/String;)V",
            "(Ljava/lang/String;Ljava/lang/String;[Ljava/lang/Object;)V"
        );
        transformer.registerSuperConstructorRedirect(
            "net/minecraft/network/chat/contents/TranslatableContents",
            "(Ljava/lang/String;[Ljava/lang/Object;)V",
            "(Ljava/lang/String;Ljava/lang/String;[Ljava/lang/Object;)V"
        );

        // Window.getWindow() -> handle() (record-style accessor in 26.1)
        transformer.registerMethodRedirect(
            "com/mojang/blaze3d/platform/Window", "getWindow",
            "()J",
            "com/mojang/blaze3d/platform/Window", "handle",
            "()J"
        );

        // Screen.render -> extractRenderState: 26.1 split rendering into extract + render
        // phases. Handles direct calls only; overrides need mixin handling.
        transformer.registerMethodRedirect(
            "net/minecraft/client/gui/screens/Screen", "render",
            "(Lnet/minecraft/client/gui/GuiGraphics;IIF)V",
            "net/minecraft/client/gui/screens/Screen", "extractRenderState",
            "(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V"
        );

        // AbstractContainerScreen.render -> extractRenderState
        transformer.registerMethodRedirect(
            "net/minecraft/client/gui/screens/inventory/AbstractContainerScreen", "render",
            "(Lnet/minecraft/client/gui/GuiGraphics;IIF)V",
            "net/minecraft/client/gui/screens/inventory/AbstractContainerScreen", "extractRenderState",
            "(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V"
        );

        transformer.registerClassRedirect(
            "net/minecraft/client/gui/GuiGraphics",
            "net/minecraft/client/gui/GuiGraphicsExtractor"
        );

        // Tesselator.getBuilder() -> begin() (signature also changed)
        transformer.registerMethodRedirect(
            "com/mojang/blaze3d/vertex/Tesselator", "getBuilder",
            "()Lcom/mojang/blaze3d/vertex/BufferBuilder;",
            "com/mojang/blaze3d/vertex/Tesselator", "begin",
            "(Lcom/mojang/blaze3d/vertex/VertexFormat$Mode;Lcom/mojang/blaze3d/vertex/VertexFormat;)Lcom/mojang/blaze3d/vertex/BufferBuilder;"
        );

        // Tesselator.end() -> clear()
        transformer.registerMethodRedirect(
            "com/mojang/blaze3d/vertex/Tesselator", "end",
            "()V",
            "com/mojang/blaze3d/vertex/Tesselator", "clear",
            "()V"
        );

        // VertexConsumer.endVertex() removed (26.1 auto-ends) -> no-op
        transformer.registerMethodRedirect(
            "com/mojang/blaze3d/vertex/VertexConsumer", "endVertex",
            "()V",
            "com/retromod/shim/fabric/embedded/ItemSafetyShim", "noOpVoid",
            "(Ljava/lang/Object;)V",
            true  // devirtualize
        );

        // SoundEvent ctor removed -> SoundEvent.createVariableRangeEvent(Identifier)
        transformer.registerConstructorRedirect(
            "net/minecraft/sounds/SoundEvent",
            "(Lnet/minecraft/resources/Identifier;)V",
            "net/minecraft/sounds/SoundEvent", "createVariableRangeEvent",
            "(Lnet/minecraft/resources/Identifier;)Lnet/minecraft/sounds/SoundEvent;"
        );

        // Window.window field -> Window.handle in 26.1
        transformer.registerFieldRedirect(
            "com/mojang/blaze3d/platform/Window", "window",
            "J",
            "com/mojang/blaze3d/platform/Window", "handle",
            "J"
        );

        // Util.backgroundExecutor() removed (now a private field) -> a stand-in executor.
        transformer.registerMethodRedirect(
            "net/minecraft/util/Util", "backgroundExecutor",
            "()Ljava/util/concurrent/ExecutorService;",
            "com/retromod/shim/fabric/embedded/ItemSafetyShim", "getBackgroundExecutor",
            "()Ljava/util/concurrent/ExecutorService;"
        );

        // Registry.SOUND_EVENT -> Registries.SOUND_EVENT; the type also went Registry
        // to ResourceKey<Registry<SoundEvent>>.
        transformer.registerFieldRedirect(
            "net/minecraft/core/Registry", "SOUND_EVENT",
            "Lnet/minecraft/core/Registry;",
            "net/minecraft/core/registries/Registries", "SOUND_EVENT",
            "Lnet/minecraft/resources/ResourceKey;"
        );

        // Leave the field shape alone (consistently wrong but working): a class redirect
        // in the chain remaps Registry -> BuiltInRegistries for the chained .get() owner
        // too, so field type and call-owner agree and the verifier accepts it. Fixing
        // only the field type makes them inconsistent -> VerifyError. The fix is to
        // correct the bad Registry -> BuiltInRegistries mapping in the harvest; until
        // then RegistryTests Test 14 fails.

        // TagKey.id() (yarn) -> TagKey.location() (26.1 record, returns ResourceLocation).
        // The record-component heuristic was rewriting this to a nonexistent comp_327();
        // force the explicit mapping (TagTests).
        transformer.registerMethodRedirect(
            "net/minecraft/registry/tag/TagKey", "id",
            "()Lnet/minecraft/util/Identifier;",
            "net/minecraft/tags/TagKey", "location",
            "()Lnet/minecraft/resources/ResourceLocation;"
        );
        // Catch the case where the heuristic still rewrote id -> comp_327 upstream.
        transformer.registerMethodRedirect(
            "net/minecraft/tags/TagKey", "comp_327",
            "()Lnet/minecraft/resources/ResourceLocation;",
            "net/minecraft/tags/TagKey", "location",
            "()Lnet/minecraft/resources/ResourceLocation;"
        );
        // And the hybrid descriptor where the package was remapped but the class name
        // was not (Identifier left as-is), giving a nonexistent net/minecraft/resources/Identifier.
        transformer.registerMethodRedirect(
            "net/minecraft/tags/TagKey", "comp_327",
            "()Lnet/minecraft/resources/Identifier;",
            "net/minecraft/tags/TagKey", "location",
            "()Lnet/minecraft/resources/ResourceLocation;"
        );
        transformer.registerMethodRedirect(
            "net/minecraft/tags/TagKey", "id",
            "()Lnet/minecraft/resources/Identifier;",
            "net/minecraft/tags/TagKey", "location",
            "()Lnet/minecraft/resources/ResourceLocation;"
        );

        // SoundEvent.getId() -> getLocation() (record since 1.21). The intermediary
        // method_14833 has no intermediary-to-mojang entry (removed, not renamed), so it
        // survives the remap and needs an explicit redirect (test-mod Test 14,
        // NoSuchMethodError on method_14833). Source class is the post-remap Mojang name.
        transformer.registerMethodRedirect(
            "net/minecraft/sounds/SoundEvent", "method_14833",
            "()Lnet/minecraft/resources/Identifier;",
            "net/minecraft/sounds/SoundEvent", "getLocation",
            "()Lnet/minecraft/resources/Identifier;"
        );
        // Also the yarn-style getId() name, in case the bytecode arrives that way.
        transformer.registerMethodRedirect(
            "net/minecraft/sounds/SoundEvent", "getId",
            "()Lnet/minecraft/resources/Identifier;",
            "net/minecraft/sounds/SoundEvent", "getLocation",
            "()Lnet/minecraft/resources/Identifier;"
        );

        // TitleScreen.COPYRIGHT_TEXT became private in 26.1; redirect to reflection bridge
        transformer.registerFieldRedirect(
            "net/minecraft/client/gui/screens/TitleScreen", "COPYRIGHT_TEXT",
            "Lnet/minecraft/network/chat/Component;",
            "com/retromod/shim/fabric/embedded/ItemSafetyShim", "getTitleScreenCopyright",
            "()Ljava/lang/Object;"
        );

        // GUI/Screen renames
        transformer.registerClassRedirect(
            "net/fabricmc/fabric/api/client/rendering/v1/SpecialGuiElementRegistry",
            "net/fabricmc/fabric/api/client/rendering/v1/PictureInPictureRendererRegistry"
        );

        // Command API renames
        transformer.registerClassRedirect(
            "net/fabricmc/fabric/api/client/command/v2/ClientCommandManager",
            "net/fabricmc/fabric/api/client/command/v2/ClientCommands"
        );

        // Brewing renames
        transformer.registerClassRedirect(
            "net/fabricmc/fabric/api/registry/FabricBrewingRecipeRegistryBuilder",
            "net/fabricmc/fabric/api/registry/FabricPotionBrewingBuilder"
        );

        // Particle API factory -> provider naming. The inner SAM kept its method name
        // (create); only its param type changed (FabricSpriteProvider -> FabricSpriteSet,
        // redirected below), so the inner redirect is lambda-safe.
        transformer.registerClassRedirect(
            "net/fabricmc/fabric/api/client/particle/v1/ParticleFactoryRegistry",
            "net/fabricmc/fabric/api/client/particle/v1/ParticleProviderRegistry"
        );
        transformer.registerClassRedirect(
            "net/fabricmc/fabric/api/client/particle/v1/ParticleFactoryRegistry$PendingParticleFactory",
            "net/fabricmc/fabric/api/client/particle/v1/ParticleProviderRegistry$PendingParticleProvider"
        );
        transformer.registerClassRedirect(
            "net/fabricmc/fabric/api/client/particle/v1/FabricSpriteProvider",
            "net/fabricmc/fabric/api/client/particle/v1/FabricSpriteSet"
        );
        transformer.registerClassRedirect(
            "net/fabricmc/fabric/api/client/particle/v1/ParticleRendererRegistry",
            "net/fabricmc/fabric/api/client/particle/v1/ParticleGroupRegistry"
        );
        transformer.registerClassRedirect(
            "net/fabricmc/fabric/api/particle/v1/FabricBlockStateParticleEffect",
            "net/fabricmc/fabric/api/particle/v1/FabricBlockParticleOption"
        );
        // Rendering/texture + item/poi/entity-data/gamerule renames.
        transformer.registerClassRedirect(
            "net/fabricmc/fabric/api/client/rendering/v1/AtlasSourceRegistry",
            "net/fabricmc/fabric/api/client/rendering/v1/SpriteSourceRegistry"
        );
        transformer.registerClassRedirect(
            "net/fabricmc/fabric/api/item/v1/ComponentTooltipAppenderRegistry",
            "net/fabricmc/fabric/api/item/v1/ItemComponentTooltipProviderRegistry"
        );
        transformer.registerClassRedirect(
            "net/fabricmc/fabric/api/object/builder/v1/world/poi/PointOfInterestHelper",
            "net/fabricmc/fabric/api/object/builder/v1/world/poi/PoiHelper"
        );
        transformer.registerClassRedirect(
            "net/fabricmc/fabric/api/object/builder/v1/entity/FabricTrackedDataRegistry",
            "net/fabricmc/fabric/api/object/builder/v1/entity/FabricEntityDataRegistry"
        );
        transformer.registerClassRedirect(
            "net/fabricmc/fabric/api/gamerule/v1/FabricGameRuleVisitor",
            "net/fabricmc/fabric/api/gamerule/v1/FabricGameRuleTypeVisitor"
        );


        // Fabric API method renames within classes that still exist.

        // ScreenEvents render -> extract. The inner interfaces renamed
        // (AfterRender -> AfterExtract) and so did the SAM (afterRender -> afterExtract).
        // Synthetic interfaces with the old method names let old lambdas resolve; each
        // extends the renamed interface and bridges via a default method, so registration
        // into Event<AfterExtract> and the callbacks both work without reflection.
        transformer.registerSyntheticClass(
            "net/fabricmc/fabric/api/client/screen/v1/ScreenEvents$AfterRender",
            generateScreenEventInterface("AfterRender", "afterRender"));
        transformer.registerSyntheticClass(
            "net/fabricmc/fabric/api/client/screen/v1/ScreenEvents$BeforeRender",
            generateScreenEventInterface("BeforeRender", "beforeRender"));
        // Static calls to ScreenEvents.beforeRender/afterRender
        transformer.registerMethodRedirect(
            "net/fabricmc/fabric/api/client/screen/v1/ScreenEvents", "beforeRender",
            "(Lnet/minecraft/client/gui/screens/Screen;)Lnet/fabricmc/fabric/api/event/Event;",
            "net/fabricmc/fabric/api/client/screen/v1/ScreenEvents", "beforeExtract",
            "(Lnet/minecraft/client/gui/screens/Screen;)Lnet/fabricmc/fabric/api/event/Event;"
        );
        transformer.registerMethodRedirect(
            "net/fabricmc/fabric/api/client/screen/v1/ScreenEvents", "afterRender",
            "(Lnet/minecraft/client/gui/screens/Screen;)Lnet/fabricmc/fabric/api/event/Event;",
            "net/fabricmc/fabric/api/client/screen/v1/ScreenEvents", "afterExtract",
            "(Lnet/minecraft/client/gui/screens/Screen;)Lnet/fabricmc/fabric/api/event/Event;"
        );

        // KeyBindingHelper.registerKeyBinding -> KeyMappingHelper.registerKeyMapping.
        // Old Yarn owner + Mojang-resolved descriptor.
        transformer.registerMethodRedirect(
            "net/fabricmc/fabric/api/client/keybinding/v1/KeyBindingHelper", "registerKeyBinding",
            "(Lnet/minecraft/client/KeyMapping;)Lnet/minecraft/client/KeyMapping;",
            "net/fabricmc/fabric/api/client/keymapping/v1/KeyMappingHelper", "registerKeyMapping",
            "(Lnet/minecraft/client/KeyMapping;)Lnet/minecraft/client/KeyMapping;"
        );
        // Same under the post-redirect owner, for mods using the new Fabric API names.
        transformer.registerMethodRedirect(
            "net/fabricmc/fabric/api/client/keymapping/v1/KeyMappingHelper", "registerKeyBinding",
            "(Lnet/minecraft/client/KeyMapping;)Lnet/minecraft/client/KeyMapping;",
            "net/fabricmc/fabric/api/client/keymapping/v1/KeyMappingHelper", "registerKeyMapping",
            "(Lnet/minecraft/client/KeyMapping;)Lnet/minecraft/client/KeyMapping;"
        );

        // Screens utility method renames
        transformer.registerMethodRedirect(
            "net/fabricmc/fabric/api/client/screen/v1/Screens", "getButtons",
            "(Lnet/minecraft/client/gui/screens/Screen;)Ljava/util/List;",
            "net/fabricmc/fabric/api/client/screen/v1/Screens", "getWidgets",
            "(Lnet/minecraft/client/gui/screens/Screen;)Ljava/util/List;"
        );
        transformer.registerMethodRedirect(
            "net/fabricmc/fabric/api/client/screen/v1/Screens", "getTextRenderer",
            "(Lnet/minecraft/client/gui/screens/Screen;)Lnet/minecraft/client/gui/Font;",
            "net/fabricmc/fabric/api/client/screen/v1/Screens", "getFont",
            "(Lnet/minecraft/client/gui/screens/Screen;)Lnet/minecraft/client/gui/Font;"
        );
        transformer.registerMethodRedirect(
            "net/fabricmc/fabric/api/client/screen/v1/Screens", "getClient",
            "(Lnet/minecraft/client/gui/screens/Screen;)Lnet/minecraft/client/Minecraft;",
            "net/fabricmc/fabric/api/client/screen/v1/Screens", "getMinecraft",
            "(Lnet/minecraft/client/gui/screens/Screen;)Lnet/minecraft/client/Minecraft;"
        );

        // FabricRegistryBuilder.createSimple() -> create()
        transformer.registerMethodRedirect(
            "net/fabricmc/fabric/api/event/registry/FabricRegistryBuilder", "createSimple",
            "(Lnet/minecraft/resources/ResourceKey;)Lnet/fabricmc/fabric/api/event/registry/FabricRegistryBuilder;",
            "net/fabricmc/fabric/api/event/registry/FabricRegistryBuilder", "create",
            "(Lnet/minecraft/resources/ResourceKey;)Lnet/fabricmc/fabric/api/event/registry/FabricRegistryBuilder;"
        );

        // FabricItem.Settings -> FabricItem.Properties
        transformer.registerClassRedirect(
            "net/fabricmc/fabric/api/item/v1/FabricItem$Settings",
            "net/fabricmc/fabric/api/item/v1/FabricItem$Properties"
        );

        // Networking: createC2SPacket -> createServerboundPacket, createS2CPacket -> createClientboundPacket
        transformer.registerMethodRedirect(
            "net/fabricmc/fabric/api/networking/v1/ServerPlayNetworking", "createC2SPacket",
            "(Lnet/minecraft/resources/Identifier;Lnet/minecraft/network/FriendlyByteBuf;)Lnet/minecraft/network/protocol/Packet;",
            "net/fabricmc/fabric/api/networking/v1/ServerPlayNetworking", "createServerboundPacket",
            "(Lnet/minecraft/resources/Identifier;Lnet/minecraft/network/FriendlyByteBuf;)Lnet/minecraft/network/protocol/Packet;"
        );
        transformer.registerMethodRedirect(
            "net/fabricmc/fabric/api/networking/v1/ServerPlayNetworking", "createS2CPacket",
            "(Lnet/minecraft/resources/Identifier;Lnet/minecraft/network/FriendlyByteBuf;)Lnet/minecraft/network/protocol/Packet;",
            "net/fabricmc/fabric/api/networking/v1/ServerPlayNetworking", "createClientboundPacket",
            "(Lnet/minecraft/resources/Identifier;Lnet/minecraft/network/FriendlyByteBuf;)Lnet/minecraft/network/protocol/Packet;"
        );

        // PlayChannelHandler -> PlayPayloadHandler. Re-registered here for mods that
        // skip the 1.21.6 shim.
        transformer.registerClassRedirect(
            "net/fabricmc/fabric/api/networking/v1/ServerPlayNetworking$PlayChannelHandler",
            "com/retromod/shim/fabric/embedded/HudRenderCallbackShim" // placeholder; real bridge in FabricNetworkingPolyfill
        );

        // DataResult.get() removed (class became interface in DFU 9.x) -> polyfill,
        // instance to static. Object types since DFU isn't on the compile classpath.
        transformer.registerMethodRedirect(
            "com/mojang/serialization/DataResult", "get",
            "()Lcom/mojang/datafixers/util/Either;",
            "com/retromod/polyfill/minecraft/DataResultPolyfill", "get",
            "(Ljava/lang/Object;)Ljava/lang/Object;",
            true  // devirtualize: instance method -> static method
        );

        // Vanilla method/field renames (1.21.x -> 26.1), affecting mixin accessors and
        // direct field/method references.

        // AbstractWidget x/y/width/height went private in 26.1; route field access
        // through getX()/setX() etc.
        transformer.registerFieldAccessorRedirect(
            "net/minecraft/client/gui/components/AbstractWidget", "x",
            "getX", "()I", "setX", "(I)V"
        );
        transformer.registerFieldAccessorRedirect(
            "net/minecraft/client/gui/components/AbstractWidget", "y",
            "getY", "()I", "setY", "(I)V"
        );
        transformer.registerFieldAccessorRedirect(
            "net/minecraft/client/gui/components/AbstractWidget", "width",
            "getWidth", "()I", "setWidth", "(I)V"
        );
        transformer.registerFieldAccessorRedirect(
            "net/minecraft/client/gui/components/AbstractWidget", "height",
            "getHeight", "()I", "setHeight", "(I)V"
        );
        // active/visible are still public fields in 26.1, no accessor needed.

        // KeyMapping.boundKey -> key
        transformer.registerFieldRedirect(
            "net/minecraft/client/KeyMapping", "boundKey",
            "Lcom/mojang/blaze3d/platform/InputConstants$Key;",
            "net/minecraft/client/KeyMapping", "key",
            "Lcom/mojang/blaze3d/platform/InputConstants$Key;"
        );

        // AbstractContainerScreen.findSlot -> getHoveredSlot
        transformer.registerMethodRedirect(
            "net/minecraft/client/gui/screens/inventory/AbstractContainerScreen", "findSlot",
            "(DD)Lnet/minecraft/world/inventory/Slot;",
            "net/minecraft/client/gui/screens/inventory/AbstractContainerScreen", "getHoveredSlot",
            "(DD)Lnet/minecraft/world/inventory/Slot;"
        );

        // GameNarrator.sayNow(Component) renamed to saySystemNow in 26.1 (verified absent in
        // 26.1-snapshot-10, present in 1.21.11; corpus scan: Revamped Phantoms ClientPacketListenerMixin).
        transformer.registerMethodRedirect(
            "net/minecraft/client/GameNarrator", "sayNow",
            "(Lnet/minecraft/network/chat/Component;)V",
            "net/minecraft/client/GameNarrator", "saySystemNow",
            "(Lnet/minecraft/network/chat/Component;)V"
        );

        // FriendlyByteBuf.readJsonWithCodec renamed to readLenientJsonWithCodec in 26.1 (same
        // descriptor; writeJsonWithCodec unchanged; corpus scan: NoChatReports MixinFriendlyByteBuf).
        transformer.registerMethodRedirect(
            "net/minecraft/network/FriendlyByteBuf", "readJsonWithCodec",
            "(Lcom/mojang/serialization/Codec;)Ljava/lang/Object;",
            "net/minecraft/network/FriendlyByteBuf", "readLenientJsonWithCodec",
            "(Lcom/mojang/serialization/Codec;)Ljava/lang/Object;"
        );

        // FlyingMob was DELETED in ~1.21.2 (absent in 26.1/26.2); its former subclasses (Phantom,
        // Ghast) now extend Mob directly, and getDefaultDimensions is inherited from LivingEntity, so
        // every FlyingMob.getDefaultDimensions reference/injection point re-owners to Mob. Intermediary
        // key (Fabric mixin @At target = Lclass_1307;method_55694(class_4050)class_4048) -> Mojang Mob;
        // repairs Revamped Phantoms' PhantomMixin @ModifyExpressionValue (#50, retired from the
        // blocklist: getDefaultDimensions IS still declared by Phantom on 26.x, calling
        // Mob.getDefaultDimensions at the wrapped expression).
        transformer.registerMethodRedirect(
            "net/minecraft/class_1307", "method_55694",
            "(Lnet/minecraft/class_4050;)Lnet/minecraft/class_4048;",
            "net/minecraft/world/entity/Mob", "getDefaultDimensions",
            "(Lnet/minecraft/world/entity/Pose;)Lnet/minecraft/world/entity/EntityDimensions;"
        );

        // Item.getDefaultInstance() -> safe wrapper. 26.1 binds item components during
        // data pack loading, so mods creating ItemStacks at class init crash; the wrapper
        // catches the NPE and returns EMPTY.
        transformer.registerMethodRedirect(
            "net/minecraft/world/item/Item", "getDefaultInstance",
            "()Lnet/minecraft/world/item/ItemStack;",
            "com/retromod/shim/fabric/embedded/ItemSafetyShim", "safeGetDefaultInstance",
            "(Ljava/lang/Object;)Ljava/lang/Object;",
            true  // devirtualize: instance method -> static method
        );

        // CommandSourceStack.hasPermission(int) removed in 26.1 (permission rework).
        // Old int levels 0-4 map to the PermissionLevel enum; bridge checks the new
        // PermissionSet system via reflection.
        transformer.registerMethodRedirect(
            "net/minecraft/commands/CommandSourceStack", "hasPermission",
            "(I)Z",
            "com/retromod/shim/fabric/embedded/ItemSafetyShim", "hasPermission",
            "(Ljava/lang/Object;I)Z",
            true  // devirtualize
        );

        // Listener.setGain(float) removed in 26.1 (volume moved to per-source). Dynamic
        // FPS calls this to mute/unmute; no-op redirect.
        transformer.registerMethodRedirect(
            "com/mojang/blaze3d/audio/Listener", "setGain",
            "(F)V",
            "com/retromod/shim/fabric/embedded/ItemSafetyShim", "noOp",
            "(Ljava/lang/Object;F)V",
            true  // devirtualize: instance method -> static method
        );

        // EntityType.BOAT/CHEST_BOAT split into per-wood types in 26.1; default to OAK_*.
        transformer.registerFieldRedirect(
            "net/minecraft/world/entity/EntityType", "BOAT",
            "Lnet/minecraft/world/entity/EntityType;",
            "net/minecraft/world/entity/EntityType", "OAK_BOAT",
            "Lnet/minecraft/world/entity/EntityType;"
        );
        transformer.registerFieldRedirect(
            "net/minecraft/world/entity/EntityType", "CHEST_BOAT",
            "Lnet/minecraft/world/entity/EntityType;",
            "net/minecraft/world/entity/EntityType", "OAK_CHEST_BOAT",
            "Lnet/minecraft/world/entity/EntityType;"
        );

        // ItemTooltipCallback.getTooltip went 3 -> 4 params in 26.1; old 3-param lambdas
        // AbstractMethodError on hover. Redirect the EVENT field to a dummy event
        // (GETSTATIC EVENT -> INVOKESTATIC getDummyTooltipEvent()).
        transformer.registerFieldRedirect(
            "net/fabricmc/fabric/api/client/item/v1/ItemTooltipCallback", "EVENT",
            "Lnet/fabricmc/fabric/api/event/Event;",
            "com/retromod/shim/fabric/embedded/ItemSafetyShim", "getDummyTooltipEvent",
            "()Ljava/lang/Object;"
        );

        // ScreenMouseEvents allowMouseClick/allowMouseRelease/after/beforeMouseScroll
        // changed callback signatures in 26.1 (individual params -> event object). Left
        // unredirected: a dummy-event redirect returns Object that gets CHECKCAST to Event
        // -> fatal VerifyError, whereas leaving them gives an ArrayStoreException that
        // Fabric's event system catches as non-fatal. Event.register() is also left
        // unredirected: the shim can't reach Fabric's ArrayBackedEvent across
        // module/classloader boundaries, and a global redirect would break all registrations.

        // Font text rendering: draw/drawShadow removed in 26.1 (now drawInBatch).
        // Font.draw(PoseStack, String, ...) [intermediary method_1729]
        transformer.registerMethodRedirect(
            "net/minecraft/client/gui/Font", "draw",
            "(Lcom/mojang/blaze3d/vertex/PoseStack;Ljava/lang/String;FFI)I",
            "com/retromod/shim/fabric/embedded/FontBridge", "drawString",
            "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/String;FFI)I",
            true  // devirtualize: instance method -> static method
        );

        // Font.draw(PoseStack, Component, ...) [intermediary method_27528]
        transformer.registerMethodRedirect(
            "net/minecraft/client/gui/Font", "draw",
            "(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/network/chat/Component;FFI)I",
            "com/retromod/shim/fabric/embedded/FontBridge", "drawComponent",
            "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;FFI)I",
            true  // devirtualize
        );

        // Font.drawShadow(PoseStack, String, ...) [intermediary method_27517]
        transformer.registerMethodRedirect(
            "net/minecraft/client/gui/Font", "drawShadow",
            "(Lcom/mojang/blaze3d/vertex/PoseStack;Ljava/lang/String;FFI)I",
            "com/retromod/shim/fabric/embedded/FontBridge", "drawShadowString",
            "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/String;FFI)I",
            true  // devirtualize
        );

        // Font.drawShadow(PoseStack, Component, ...)
        transformer.registerMethodRedirect(
            "net/minecraft/client/gui/Font", "drawShadow",
            "(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/network/chat/Component;FFI)I",
            "com/retromod/shim/fabric/embedded/FontBridge", "drawShadowComponent",
            "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;FFI)I",
            true  // devirtualize
        );

        // Font.drawShadow(PoseStack, FormattedCharSequence, ...)
        transformer.registerMethodRedirect(
            "net/minecraft/client/gui/Font", "drawShadow",
            "(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/util/FormattedCharSequence;FFI)I",
            "com/retromod/shim/fabric/embedded/FontBridge", "drawShadowFCS",
            "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;FFI)I",
            true  // devirtualize
        );

        // RenderSystem static methods removed in 26.1 (pipeline-based now, not a GL
        // state machine); redirect to no-ops.
        transformer.registerMethodRedirect(
            "com/mojang/blaze3d/systems/RenderSystem", "enableTexture",
            "()V",
            "com/retromod/shim/fabric/embedded/FontBridge", "noOpVoid",
            "()V"
        );
        transformer.registerMethodRedirect(
            "com/mojang/blaze3d/systems/RenderSystem", "disableTexture",
            "()V",
            "com/retromod/shim/fabric/embedded/FontBridge", "noOpVoid",
            "()V"
        );
        transformer.registerMethodRedirect(
            "com/mojang/blaze3d/systems/RenderSystem", "setShaderTexture",
            "(ILnet/minecraft/resources/ResourceLocation;)V",
            "com/retromod/shim/fabric/embedded/FontBridge", "noOpSetShaderTexture",
            "(ILjava/lang/Object;)V"
        );

        for (String method : new String[]{"enableBlend", "disableBlend",
                "enableDepthTest", "disableDepthTest", "defaultBlendFunc"}) {
            transformer.registerMethodRedirect(
                "com/mojang/blaze3d/systems/RenderSystem", method,
                "()V",
                "com/retromod/shim/fabric/embedded/ItemSafetyShim", "noOpStatic",
                "()V"
            );
        }
        transformer.registerMethodRedirect(
            "com/mojang/blaze3d/systems/RenderSystem", "setShaderColor",
            "(FFFF)V",
            "com/retromod/shim/fabric/embedded/ItemSafetyShim", "noOpColor",
            "(FFFF)V"
        );

        // AbstractContainerScreen.slotClicked: 4th param went ClickAction -> ContainerInput
        // in 26.1 (name unchanged). MouseTweaks' @Invoker targets the old descriptor and
        // won't match; non-fatal, the mod still initializes without it.

        // fabric-convention-tags-v1 removed in 26.1 -> v2
        transformer.registerClassRedirect(
            "net/fabricmc/fabric/api/tag/convention/v1/ConventionalBlockTags",
            "net/fabricmc/fabric/api/tag/convention/v2/ConventionalBlockTags"
        );
        transformer.registerClassRedirect(
            "net/fabricmc/fabric/api/tag/convention/v1/ConventionalItemTags",
            "net/fabricmc/fabric/api/tag/convention/v2/ConventionalItemTags"
        );
        transformer.registerClassRedirect(
            "net/fabricmc/fabric/api/tag/convention/v1/ConventionalBiomeTags",
            "net/fabricmc/fabric/api/tag/convention/v2/ConventionalBiomeTags"
        );
        transformer.registerClassRedirect(
            "net/fabricmc/fabric/api/tag/convention/v1/ConventionalFluidTags",
            "net/fabricmc/fabric/api/tag/convention/v2/ConventionalFluidTags"
        );
        transformer.registerClassRedirect(
            "net/fabricmc/fabric/api/tag/convention/v1/ConventionalEnchantmentTags",
            "net/fabricmc/fabric/api/tag/convention/v2/ConventionalEnchantmentTags"
        );

        // fabric-rendering-api-v1 world subpackage -> level
        transformer.registerClassRedirect(
            "net/fabricmc/fabric/api/client/rendering/v1/world/WorldRenderContext",
            "net/fabricmc/fabric/api/client/rendering/v1/level/LevelRenderContext"
        );

        // Chunk force-loading: 26.1 renamed ServerChunkCache.addRegionTicket(TicketType,ChunkPos,int,T)
        // to addTicketWithRadius(TicketType,ChunkPos,int) AND dropped the value arg. A plain rename
        // can't (the arity changed); the arg-dropping redirect pops the dropped value then calls the
        // 3-arg method. 1.18-era chunk-loading mods (Chunky) hit this, crashing worlds on load.
        transformer.registerArgDropMethodRedirect(
            "net/minecraft/server/level/ServerChunkCache", "addRegionTicket",
            "(Lnet/minecraft/server/level/TicketType;Lnet/minecraft/world/level/ChunkPos;ILjava/lang/Object;)V",
            "net/minecraft/server/level/ServerChunkCache", "addTicketWithRadius",
            "(Lnet/minecraft/server/level/TicketType;Lnet/minecraft/world/level/ChunkPos;I)V"
        );
    }

    @Override
    public String[] getShimClasses() {
        return new String[] {
            // HudRenderCallbackShim is declared in the 1.21.5->1.21.6 shim.
            "com.retromod.shim.fabric.embedded.KeyBindingShim",
            "com.retromod.shim.fabric.embedded.ItemSafetyShim",
            "com.retromod.shim.fabric.embedded.ButtonShim",
            "com.retromod.shim.fabric.embedded.FontBridge",
            "com.retromod.shim.fabric.embedded.LegacyBlockRandomTickBridge"
        };
    }

    /**
     * Synthetic ScreenEvents inner interface (AfterRender/BeforeRender) extending its 26.1
     * replacement (AfterExtract/BeforeExtract), with a default method bridging the renamed
     * SAM so old lambdas store into the new event arrays and the renamed callback delegates
     * to the old one.
     */
    private static byte[] generateScreenEventInterface(String simpleName, String methodName) {
        String pkg = "net/fabricmc/fabric/api/client/screen/v1/ScreenEvents$";
        String className = pkg + simpleName;

        // Parent: AfterRender -> AfterExtract, BeforeRender -> BeforeExtract.
        String parentSimple = simpleName.replace("Render", "Extract");
        String parentClass = pkg + parentSimple;
        String parentMethod = methodName.replace("Render", "Extract")
                                        .replace("render", "extract");

        // Param type is the renamed GuiGraphicsExtractor.
        String desc = "(Lnet/minecraft/client/gui/screens/Screen;"
                + "Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V";

        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V21,
            Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE,
            className, null, "java/lang/Object",
            new String[]{ parentClass });

        // Abstract SAM: afterRender/beforeRender.
        cw.visitMethod(
            Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
            methodName, desc, null, null
        ).visitEnd();

        // Default bridge: afterExtract/beforeExtract delegates to the old SAM.
        MethodVisitor mv = cw.visitMethod(
            Opcodes.ACC_PUBLIC,
            parentMethod, desc, null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);  // this
        mv.visitVarInsn(Opcodes.ALOAD, 1);  // screen
        mv.visitVarInsn(Opcodes.ALOAD, 2);  // extractor
        mv.visitVarInsn(Opcodes.ILOAD, 3);  // mouseX
        mv.visitVarInsn(Opcodes.ILOAD, 4);  // mouseY
        mv.visitVarInsn(Opcodes.FLOAD, 5);  // tickDelta
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, className, methodName, desc, true);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(6, 6);
        mv.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * Field-to-method redirects for the MC 1.21+ registry-key migration of vanilla content
     * holders. Fields like {@code Enchantments.SHARPNESS} are now typed
     * {@code ResourceKey<Enchantment>}, so reading them as the value throws NoSuchFieldError.
     * Each redirect routes the GETSTATIC to a {@link com.retromod.polyfill.registry.RegistryRefLookup}
     * runtime lookup; the transformer's CHECKCAST handles the cast at the use site. Field
     * names use Mojang spelling, since ClassRemapper runs first.
     */
    private void registerRegistryRefRedirects(RetromodTransformer transformer) {
        registerEnchantmentRedirects(transformer);
        registerMobEffectRedirects(transformer);
        registerNbtCompatRedirects(transformer);
    }

    /**
     * NBT getters changed in MC 1.21.5+: {@code CompoundTag.getString(String)} etc. now
     * return {@code Optional<X>}, so the old {@code ()String}-shaped INVOKEVIRTUAL throws
     * NoSuchMethodError. Redirect each to a {@link com.retromod.polyfill.registry.NbtCompatLookup}
     * helper (devirtualized, receiver-first) that resolves whichever signature the runtime
     * has, unwraps the Optional, and returns the primitive/string the caller expects (NbtTests).
     */
    private void registerNbtCompatRedirects(RetromodTransformer transformer) {
        String compoundTag = "net/minecraft/nbt/CompoundTag";
        String listTag     = "net/minecraft/nbt/ListTag";
        String lookup      = "com/retromod/polyfill/registry/NbtCompatLookup";

        // CompoundTag.getX(String): receiver-first static descriptors.
        transformer.registerMethodRedirect(
            compoundTag, "getString", "(Ljava/lang/String;)Ljava/lang/String;",
            lookup, "compoundGetString",
            "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/String;",
            true
        );
        transformer.registerMethodRedirect(
            compoundTag, "getInt", "(Ljava/lang/String;)I",
            lookup, "compoundGetInt",
            "(Ljava/lang/Object;Ljava/lang/String;)I",
            true
        );
        transformer.registerMethodRedirect(
            compoundTag, "getLong", "(Ljava/lang/String;)J",
            lookup, "compoundGetLong",
            "(Ljava/lang/Object;Ljava/lang/String;)J",
            true
        );
        transformer.registerMethodRedirect(
            compoundTag, "getFloat", "(Ljava/lang/String;)F",
            lookup, "compoundGetFloat",
            "(Ljava/lang/Object;Ljava/lang/String;)F",
            true
        );
        transformer.registerMethodRedirect(
            compoundTag, "getDouble", "(Ljava/lang/String;)D",
            lookup, "compoundGetDouble",
            "(Ljava/lang/Object;Ljava/lang/String;)D",
            true
        );
        transformer.registerMethodRedirect(
            compoundTag, "getBoolean", "(Ljava/lang/String;)Z",
            lookup, "compoundGetBoolean",
            "(Ljava/lang/Object;Ljava/lang/String;)Z",
            true
        );

        // ListTag.getX(int)
        transformer.registerMethodRedirect(
            listTag, "getString", "(I)Ljava/lang/String;",
            lookup, "listGetString",
            "(Ljava/lang/Object;I)Ljava/lang/String;",
            true
        );
        transformer.registerMethodRedirect(
            listTag, "getInt", "(I)I",
            lookup, "listGetInt",
            "(Ljava/lang/Object;I)I",
            true
        );
    }

    private void registerEnchantmentRedirects(RetromodTransformer transformer) {
        String enchOwner = "net/minecraft/world/item/enchantment/Enchantments";
        String enchType  = "Lnet/minecraft/world/item/enchantment/Enchantment;";
        String lookup    = "com/retromod/polyfill/registry/RegistryRefLookup";
        String objRet    = "()Ljava/lang/Object;";

        // Mojang field name == polyfill method name here (both are the uppercased registry id).
        String[] names = {
            "SHARPNESS", "SMITE", "BANE_OF_ARTHROPODS", "KNOCKBACK",
            "FIRE_ASPECT", "LOOTING", "SWEEPING_EDGE",
            "PROTECTION", "FIRE_PROTECTION", "FEATHER_FALLING",
            "BLAST_PROTECTION", "PROJECTILE_PROTECTION",
            "RESPIRATION", "AQUA_AFFINITY", "THORNS",
            "DEPTH_STRIDER", "FROST_WALKER", "BINDING_CURSE",
            "SOUL_SPEED", "SWIFT_SNEAK",
            "EFFICIENCY", "SILK_TOUCH", "UNBREAKING", "FORTUNE",
            "POWER", "PUNCH", "FLAME", "INFINITY",
            "LUCK_OF_THE_SEA", "LURE",
            "LOYALTY", "IMPALING", "RIPTIDE", "CHANNELING",
            "MULTISHOT", "QUICK_CHARGE", "PIERCING",
            "MENDING", "VANISHING_CURSE"
        };
        for (String n : names) {
            transformer.registerFieldRedirect(
                enchOwner, n, enchType,
                lookup, n, objRet
            );
        }
    }

    private void registerMobEffectRedirects(RetromodTransformer transformer) {
        String effOwner = "net/minecraft/world/effect/MobEffects";
        String effType  = "Lnet/minecraft/world/effect/MobEffect;";
        String lookup   = "com/retromod/polyfill/registry/RegistryRefLookup";
        String objRet   = "()Ljava/lang/Object;";

        // (Mojang field, polyfill method) pairs. The polyfill methods use the yarn field
        // names (SPEED, HASTE, ...), matching the yarn-compiled test mod.
        String[][] pairs = {
            {"MOVEMENT_SPEED",   "SPEED"},
            {"MOVEMENT_SLOWDOWN","SLOWNESS"},
            {"DIG_SPEED",        "HASTE"},
            {"DIG_SLOWDOWN",     "MINING_FATIGUE"},
            {"DAMAGE_BOOST",     "STRENGTH"},
            {"HEAL",             "INSTANT_HEALTH"},
            {"HARM",             "INSTANT_DAMAGE"},
            {"JUMP",             "JUMP_BOOST"},
            {"CONFUSION",        "NAUSEA"},
            {"REGENERATION",     "REGENERATION"},
            {"DAMAGE_RESISTANCE","RESISTANCE"},
            {"FIRE_RESISTANCE",  "FIRE_RESISTANCE"},
            {"WATER_BREATHING",  "WATER_BREATHING"},
            {"INVISIBILITY",     "INVISIBILITY"},
            {"BLINDNESS",        "BLINDNESS"},
            {"NIGHT_VISION",     "NIGHT_VISION"},
            {"HUNGER",           "HUNGER"},
            {"WEAKNESS",         "WEAKNESS"},
            {"POISON",           "POISON"},
            {"WITHER",           "WITHER"},
            {"HEALTH_BOOST",     "HEALTH_BOOST"},
            {"ABSORPTION",       "ABSORPTION"},
            {"SATURATION",       "SATURATION"},
            {"GLOWING",          "GLOWING"},
            {"LEVITATION",       "LEVITATION"},
            {"LUCK",             "LUCK"},
            {"UNLUCK",           "UNLUCK"},
            {"SLOW_FALLING",     "SLOW_FALLING"},
            {"CONDUIT_POWER",    "CONDUIT_POWER"},
            {"DOLPHINS_GRACE",   "DOLPHINS_GRACE"},
            {"BAD_OMEN",         "BAD_OMEN"},
            {"HERO_OF_THE_VILLAGE","HERO_OF_THE_VILLAGE"},
            {"DARKNESS",         "DARKNESS"}
        };
        for (String[] pair : pairs) {
            transformer.registerFieldRedirect(
                effOwner, pair[0], effType,
                lookup, pair[1], objRet
            );
        }
    }
}
