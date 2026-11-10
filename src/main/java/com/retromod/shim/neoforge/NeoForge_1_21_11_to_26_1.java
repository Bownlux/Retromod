/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 *
 * NeoForge 1.21.11 to 26.1 shim. NeoForge already uses Mojang names, so its mods
 * mostly need metadata patching (ForgeModTransformer), not remapping. This shim
 * covers the vanilla MC API changes that still bite: boat/chest-boat splits,
 * AbstractWidget field-to-accessor moves, removed/renamed methods and fields.
 * Class/package moves live in mojang-class-moves-26.1.tsv.
 */
package com.retromod.shim.neoforge;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.SyntheticEmbedder;
import com.retromod.core.VersionShim;

/**
 * Compatibility shim for NeoForge mods built for 1.21.11 to run on 26.1+.
 * NeoForge already uses Mojang names, so this only handles vanilla MC API
 * changes (renamed/removed methods, field visibility, split entity types).
 */
public class NeoForge_1_21_11_to_26_1 implements VersionShim {

    @Override
    public String getShimName() {
        return "NeoForge 1.21.11 to 26.1";
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
        return "neoforge";
    }

    @Override
    public void registerRedirects(RetromodTransformer transformer) {
        // Mods built for 1.21.11 also reach the members 26.1 and 26.2 removed; each row is checked on the host.
        com.retromod.shim.common.LegacyRemovedMemberBridge.register(transformer);
        SyntheticEmbedder.registerClassResource(
                transformer,
                "com/retromod/shim/neoforge/embedded/ReloadListenerEventShim",
                NeoForge_1_21_11_to_26_1.class);

        // Vanilla 1.21.11 to 26.1 class moves, shared with the Fabric 26.1 shim. Also
        // in mojang-class-moves-26.1.tsv, but registering here too makes them apply on
        // both the CLI/audit path and the runtime even if the mapper isn't loaded.
        // Double-registration with the same target is an idempotent Map.put. (#64)
        com.retromod.shim.common.Common_1_21_11_to_26_1_ClassMoves.register(transformer);
        com.retromod.shim.forge.LegacyTradeBridge.register(transformer);

        // EntityType.BOAT/CHEST_BOAT split into per-wood types in 26.1; OAK is the
        // common default for old mods.

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

        // 26.x removed NeoForge's static FMLEnvironment.dist field (replaced by the
        // getDist() accessor), so the standard client/server side check NoSuchFieldErrors.
        // A field-to-method redirect (newDesc starts with '(') rewrites GETSTATIC into
        // INVOKESTATIC getDist(); return types match, so no cast is needed.
        transformer.registerFieldRedirect(
            "net/neoforged/fml/loading/FMLEnvironment", "dist",
            "Lnet/neoforged/api/distmarker/Dist;",
            "net/neoforged/fml/loading/FMLEnvironment", "getDist",
            "()Lnet/neoforged/api/distmarker/Dist;"
        );

        // AbstractWidget x/y/width/height became private in 26.1, now via getters/setters.
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

        // CommandSourceStack.hasPermission(int) bridges to the new PermissionSet system.
        transformer.registerMethodRedirect(
            "net/minecraft/commands/CommandSourceStack", "hasPermission",
            "(I)Z",
            "com/retromod/shim/fabric/embedded/ItemSafetyShim", "hasPermission",
            "(Ljava/lang/Object;I)Z",
            true  // devirtualize
        );

        // Listener.setGain(float) is gone from 1.21.9; mods like Dynamic FPS call it to
        // mute/unmute, so it becomes a no-op.
        com.retromod.shim.common.Common_1_21_11_to_26_1_ClassMoves.registerListenerSetGainNoOp(transformer);

        // Window.getWindow() renamed to record-style handle() in 26.1.
        transformer.registerMethodRedirect(
            "com/mojang/blaze3d/platform/Window", "getWindow",
            "()J",
            "com/mojang/blaze3d/platform/Window", "handle",
            "()J"
        );

        // KeyMapping.boundKey field renamed to key in 26.1.
        transformer.registerFieldRedirect(
            "net/minecraft/client/KeyMapping", "boundKey",
            "Lcom/mojang/blaze3d/platform/InputConstants$Key;",
            "net/minecraft/client/KeyMapping", "key",
            "Lcom/mojang/blaze3d/platform/InputConstants$Key;"
        );

        // AbstractContainerScreen.findSlot renamed to getHoveredSlot in 26.1.
        transformer.registerMethodRedirect(
            "net/minecraft/client/gui/screens/inventory/AbstractContainerScreen", "findSlot",
            "(DD)Lnet/minecraft/world/inventory/Slot;",
            "net/minecraft/client/gui/screens/inventory/AbstractContainerScreen", "getHoveredSlot",
            "(DD)Lnet/minecraft/world/inventory/Slot;"
        );

        // GameNarrator.sayNow(Component) is saySystemNow from 1.21.6 (corpus scan: Revamped
        // Phantoms ClientPacketListenerMixin).
        com.retromod.shim.common.Common_1_21_11_to_26_1_ClassMoves.registerNarratorSayNowRename(transformer);

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
        // every FlyingMob.getDefaultDimensions reference/injection point re-owners to Mob. Mojang key
        // (NeoForge/Forge mods + their mixin @At targets are Mojang-named); repairs Revamped Phantoms'
        // PhantomMixin @ModifyExpressionValue (#50, retired from the blocklist).
        transformer.registerMethodRedirect(
            "net/minecraft/world/entity/FlyingMob", "getDefaultDimensions",
            "(Lnet/minecraft/world/entity/Pose;)Lnet/minecraft/world/entity/EntityDimensions;",
            "net/minecraft/world/entity/Mob", "getDefaultDimensions",
            "(Lnet/minecraft/world/entity/Pose;)Lnet/minecraft/world/entity/EntityDimensions;"
        );

        // DataResult became an interface in DFU 9.x and get() was removed; redirect to polyfill.
        transformer.registerMethodRedirect(
            "com/mojang/serialization/DataResult", "get",
            "()Lcom/mojang/datafixers/util/Either;",
            "com/retromod/polyfill/minecraft/DataResultPolyfill", "get",
            "(Ljava/lang/Object;)Ljava/lang/Object;",
            true  // devirtualize: instance method → static method
        );

        // No handler-interface redirects: the old capability interfaces (IItemHandler,
        // IItemHandlerModifiable, IFluidHandler, IEnergyStorage) still ship in 26.1
        // (deprecated since the 1.21.9 Transfer Rework). The earlier "dropped-I-prefix"
        // redirects pointed at classes that don't exist or at the wrong thing
        // (EnergyStorage is the impl, not a renamed interface), so they broke every
        // handler mod they touched. ForgeSpawnEggItem was likewise dropped: spawn eggs
        // went data-driven, so that redirect swapped one missing class for another.
        // The rework did remove the capabilities holding those handlers, which a registration
        // bridge restores beside the resource handler ones. The 1.21.9 shim registers it too,
        // and this one covers a mod built for 1.21.9 or newer, whose chain starts after it.
        LegacyHandlerCapabilityBridge.register(transformer);
        // 26.1 removed BlockEntityType.Builder, and data attachments serialize with a MapCodec.
        com.retromod.shim.common.LegacyBlockEntityTypeBuilderBridge.register(transformer);
        LegacyAttachmentCodecBridge.register(transformer);
        com.retromod.shim.forge.LegacyLootFromBridge.register(transformer);
        // Minecraft 1.21.4 removed BlockEntityWithoutLevelRenderer and NeoForge 1.21.4 getCustomRenderer, so old item
        // renderers extend a base rebuilt inside each mod and draw through an item model type each mod registers.
        LegacyNeoForgeItemRendererBridge.register(transformer);
        // NeoForge 1.21.4 replaced RegisterAdditional with standalone models; old listeners still need the name to resolve.
        LegacyNeoForgeRegisterAdditionalBridge.register(transformer);
        // NeoForge 1.21.5 removed the dynamic model API with BakedModel, which old models and their callers still name.
        LegacyNeoForgeBakedModelBridge.register(transformer);
        // NeoForge keys AddLayers player renderers by model type, and its getSkins() returns those types.
        LegacyNeoForgeAddLayersBridge.register(transformer);
        // NeoForge 26.1 replaced the block color event with block tint sources, and 1.21.4 removed the item color event.
        LegacyNeoForgeColorBridge.register(transformer);

        // BlockEvent$BreakEvent moved into its own subpackage in 26.1 (same ctor, still
        // cancellable). Block-break is one of the most common event subscriptions.
        transformer.registerClassRedirect(
            "net/neoforged/neoforge/event/level/BlockEvent$BreakEvent",
            "net/neoforged/neoforge/event/level/block/BreakBlockEvent"
        );

        // RenderLevelStageEvent stage-inner renames (26.1 render-pipeline pass split).
        // $AfterTripwireBlocks was removed with no successor, left unmapped. NeoForge
        // events dispatch by exact type, so a class redirect retargets both the
        // listener parameter and the subscription, no SAM/lambda hazard.
        transformer.registerClassRedirect(
            "net/neoforged/neoforge/client/event/RenderLevelStageEvent$AfterEntities",
            "net/neoforged/neoforge/client/event/RenderLevelStageEvent$AfterOpaqueFeatures"
        );
        transformer.registerClassRedirect(
            "net/neoforged/neoforge/client/event/RenderLevelStageEvent$AfterParticles",
            "net/neoforged/neoforge/client/event/RenderLevelStageEvent$AfterTranslucentParticles"
        );

        // RecipesUpdatedEvent renamed to RecipesReceivedEvent in 26.1; the old name was
        // deleted, so recipe-viewer mods (EMI) crash at construct time. (#82)
        transformer.registerClassRedirect(
            "net/neoforged/neoforge/client/event/RecipesUpdatedEvent",
            "net/neoforged/neoforge/client/event/RecipesReceivedEvent"
        );

        registerReloadListenerEvents(transformer);

        // jspecify annotations: catch javax.annotation for mods that skip the
        // 1.21.10->1.21.11 shim via a direct BFS path.
        transformer.registerClassRedirect(
            "javax/annotation/Nullable",
            "org/jspecify/annotations/Nullable"
        );
        transformer.registerClassRedirect(
            "javax/annotation/Nonnull",
            "org/jspecify/annotations/NonNull"
        );
    }

    @Override
    public String[] getShimClasses() {
        return new String[] {
            // Reuses Fabric's loader-agnostic ItemSafetyShim for hasPermission(int)
            // and the setGain() no-op.
            "com.retromod.shim.fabric.embedded.ItemSafetyShim",
            // Bridges the removed 1-arg AddReloadListenerEvent.addListener onto 26.2's id-requiring form (#139).
            "com.retromod.shim.neoforge.embedded.ReloadListenerEventShim"
        };
    }

    /**
     * The reload listener events a 1.21.1 mod subscribes to, under the names NeoForge gave them by
     * 1.21.11. The 1.21.11 shim calls this too, on a host that has only the new names.
     */
    static void registerReloadListenerEvents(RetromodTransformer transformer) {
        // AddReloadListenerEvent renamed to AddServerReloadListenersEvent around 26.x, and its
        // addListener now REQUIRES an id (inherited from SortedReloadListenerEvent). Both are game-bus
        // events (extend bus/api/Event, not IModBusEvent), so the class redirect retargets the
        // @SubscribeEvent parameter + subscription and fixes the NoClassDefFoundError (#139). The
        // one-arg addListener(listener) call is then bridged to the two-arg addListener(id, listener)
        // via ReloadListenerEventShim (synthesizes an id); accessors (getRegistryAccess/... ) are
        // unchanged and survive the class redirect. (verified vs NeoForge 26.2 universal jar)
        transformer.registerClassRedirect(
            "net/neoforged/neoforge/event/AddReloadListenerEvent",
            "net/neoforged/neoforge/event/AddServerReloadListenersEvent"
        );
        // Register the addListener bridge under BOTH the old and remapped owner: the ClassRemapper
        // rewrites the call owner before the method-redirect pass sees it, so the remapped name is what
        // matches, but registering the old name too is a harmless belt-and-suspenders.
        for (String owner : new String[]{
                "net/neoforged/neoforge/event/AddServerReloadListenersEvent",
                "net/neoforged/neoforge/event/AddReloadListenerEvent"}) {
            transformer.registerMethodRedirect(
                owner, "addListener",
                "(Lnet/minecraft/server/packs/resources/PreparableReloadListener;)V",
                "com/retromod/shim/neoforge/embedded/ReloadListenerEventShim", "addListener",
                "(Ljava/lang/Object;Ljava/lang/Object;)V",
                true  // devirtualize: event.addListener(l) -> shim.addListener(event, l)
            );
        }

        // The client companion changed in the same way: the old event was deleted, and its
        // registerReloadListener(listener) call became addListener(id, listener). Caelum 2.0.0.0
        // reaches both during mod construction, so moving only the event parameter still leaves a
        // NoSuchMethodError when the handler fires (#188). Reuse the server bridge because the new
        // client and server events share SortedReloadListenerEvent's two-argument method.
        String oldClientReload =
                "net/neoforged/neoforge/client/event/RegisterClientReloadListenersEvent";
        String newClientReload =
                "net/neoforged/neoforge/client/event/AddClientReloadListenersEvent";
        transformer.registerClassRedirect(oldClientReload, newClientReload);
        for (String owner : new String[]{oldClientReload, newClientReload}) {
            transformer.registerMethodRedirect(
                owner, "registerReloadListener",
                "(Lnet/minecraft/server/packs/resources/PreparableReloadListener;)V",
                "com/retromod/shim/neoforge/embedded/ReloadListenerEventShim", "addListener",
                "(Ljava/lang/Object;Ljava/lang/Object;)V",
                true
            );
        }
    }
}
