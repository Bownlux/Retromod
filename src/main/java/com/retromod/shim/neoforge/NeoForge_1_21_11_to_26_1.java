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
        SyntheticEmbedder.registerClassResource(
                transformer,
                "com/retromod/shim/neoforge/embedded/ReloadListenerEventShim",
                NeoForge_1_21_11_to_26_1.class);

        // Vanilla 1.21.11 to 26.1 class moves, shared with the Fabric 26.1 shim. Also
        // in mojang-class-moves-26.1.tsv, but registering here too makes them apply on
        // both the CLI/audit path and the runtime even if the mapper isn't loaded.
        // Double-registration with the same target is an idempotent Map.put. (#64)
        com.retromod.shim.common.Common_1_21_11_to_26_1_ClassMoves.register(transformer);

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

        // Listener.setGain(float) removed in 26.1 (volume went per-source); mods like
        // Dynamic FPS call it to mute/unmute, so redirect to a no-op.

        // CommandSourceStack.hasPermission(int) bridges to the new PermissionSet system.
        transformer.registerMethodRedirect(
            "net/minecraft/commands/CommandSourceStack", "hasPermission",
            "(I)Z",
            "com/retromod/shim/fabric/embedded/ItemSafetyShim", "hasPermission",
            "(Ljava/lang/Object;I)Z",
            true  // devirtualize
        );

        transformer.registerMethodRedirect(
            "com/mojang/blaze3d/audio/Listener", "setGain",
            "(F)V",
            "com/retromod/shim/fabric/embedded/ItemSafetyShim", "noOp",
            "(Ljava/lang/Object;F)V",
            true  // devirtualize: instance method to static method
        );

        // Item components are data-driven in 26.1; getDefaultInstance() before binding
        // NPEs, so route through a safe wrapper.
        transformer.registerMethodRedirect(
            "net/minecraft/world/item/Item", "getDefaultInstance",
            "()Lnet/minecraft/world/item/ItemStack;",
            "com/retromod/shim/fabric/embedded/ItemSafetyShim", "safeGetDefaultInstance",
            "(Ljava/lang/Object;)Ljava/lang/Object;",
            true  // devirtualize: instance method → static method
        );

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
            // Reuses Fabric's loader-agnostic ItemSafetyShim for safe
            // getDefaultInstance() and the setGain() no-op.
            "com.retromod.shim.fabric.embedded.ItemSafetyShim",
            // Bridges the removed 1-arg AddReloadListenerEvent.addListener onto 26.2's id-requiring form (#139).
            "com.retromod.shim.neoforge.embedded.ReloadListenerEventShim"
        };
    }
}
