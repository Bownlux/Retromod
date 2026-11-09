/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.core.VersionShim;
import com.retromod.util.McReflect;

/** Updates Forge 1.21.11 mods for the vanilla 26.1 changes and Forge's own 26.1 renames. */
public class Forge_1_21_11_to_26_1 implements VersionShim {

    @Override
    public String getShimName() {
        return "Forge 1.21.11 to 26.1";
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
        return "forge";
    }

    @Override
    public void registerRedirects(RetromodTransformer transformer) {
        // The vanilla 26.1 set, shared with the NeoForge and Fabric shims, the CLI and AOT.
        com.retromod.shim.common.Common_1_21_11_to_26_1_ClassMoves.register(transformer);
        // Mods built for 1.21.11 also reach the members 26.1 and 26.2 removed; each row is checked on the host.
        com.retromod.shim.common.LegacyRemovedMemberBridge.register(transformer);

        // 26.1 removed BlockEntityType.Builder; the type takes the supplier and blocks directly.
        com.retromod.shim.common.LegacyBlockEntityTypeBuilderBridge.register(transformer);

        // Forge 64.x for MC 26.1 renamed net.minecraft.resources.ResourceLocation
        // to .Identifier; pre-26.1 mods reference the old name and crash with
        // NoClassDefFoundError. The constructor became private too, handled below.
        transformer.registerClassRedirect(
            "net/minecraft/resources/ResourceLocation",
            "net/minecraft/resources/Identifier"
        );

        // 2-arg constructor became private; redirect to the static factory.
        // The lookup key uses the post-class-remap owner (Identifier), since
        // ClassRemapper renames class names before the constructor lookup runs.
        // Forge 64.x pairs the yarn class name with the Mojang factory name
        // fromNamespaceAndPath, not yarn's of (which gives NoSuchMethodError).
        transformer.registerConstructorRedirect(
            "net/minecraft/resources/Identifier",
            "(Ljava/lang/String;Ljava/lang/String;)V",
            "net/minecraft/resources/Identifier",
            "fromNamespaceAndPath",
            "(Ljava/lang/String;Ljava/lang/String;)Lnet/minecraft/resources/Identifier;"
        );
        // 1-arg colon-separated form: new Identifier("ns:path"), factory parse(String).
        transformer.registerConstructorRedirect(
            "net/minecraft/resources/Identifier",
            "(Ljava/lang/String;)V",
            "net/minecraft/resources/Identifier",
            "parse",
            "(Ljava/lang/String;)Lnet/minecraft/resources/Identifier;"
        );

        // Since 1.21.2 the registry id must be on Block/Item Properties before construction, and
        // Forge's DeferredRegister.register(String, Supplier) builds the object with no id
        // (RegisterEvent dies "Block/Item id not set", as on NeoForge in #87, hit in-game on
        // Macaw's). Forge has no id-aware register overload, so the supplier is wrapped and the
        // id comes from the returned RegistryObject.getKey(). Registered here as well as at
        // 1.21.11, so an offline chain that starts at 1.21.11 keeps it.
        if (!McReflect.isNeoForge()) {
            RegistryIdBridgeSynthetic.registerForgeRedirects(transformer);
        }

        // A Forge mod bound for NeoForge takes NeoForge's item renderer bridge here, since an offline Forge chain has no
        // NeoForge shim. The bridge registers nothing for a Forge target.
        com.retromod.shim.neoforge.LegacyNeoForgeItemRendererBridge.register(transformer);
        com.retromod.shim.neoforge.LegacyNeoForgeRegisterAdditionalBridge.register(transformer);
        com.retromod.shim.neoforge.LegacyNeoForgeBakedModelBridge.register(transformer);
        // NeoForge keys AddLayers player renderers by model type, and its getSkins() returns those types.
        com.retromod.shim.neoforge.LegacyNeoForgeAddLayersBridge.register(transformer);
        // NeoForge 26.1 replaced the block color event with block tint sources, and 1.21.4 removed the item color event.
        com.retromod.shim.neoforge.LegacyNeoForgeColorBridge.register(transformer);

        // Forge 26.x builds network channels its own way; old SimpleChannel code opens one through it.
        // Capabilities and registries have their own shims, ForgeCapabilitiesShim and ForgeRegistryApiShim.
        LegacyForgeNetworkBridge.register(transformer);

        // EntityType.BOAT/CHEST_BOAT split into per-wood types in 26.1; OAK is
        // the common default for old mods.
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
            true
        );

        // Listener.setGain(float) is gone from 1.21.9; no-op it.
        com.retromod.shim.common.Common_1_21_11_to_26_1_ClassMoves.registerListenerSetGainNoOp(transformer);

        // Window.getWindow() renamed to record-style accessor handle().
        transformer.registerMethodRedirect(
            "com/mojang/blaze3d/platform/Window", "getWindow",
            "()J",
            "com/mojang/blaze3d/platform/Window", "handle",
            "()J"
        );

        // KeyMapping.boundKey renamed to key.
        transformer.registerFieldRedirect(
            "net/minecraft/client/KeyMapping", "boundKey",
            "Lcom/mojang/blaze3d/platform/InputConstants$Key;",
            "net/minecraft/client/KeyMapping", "key",
            "Lcom/mojang/blaze3d/platform/InputConstants$Key;"
        );

        // AbstractContainerScreen.findSlot renamed to getHoveredSlot.
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
        // every FlyingMob.getDefaultDimensions reference/injection point re-owners to Mob (Mojang key;
        // Forge mods are Mojang-named). Repairs the #50-class @ModifyExpressionValue on Phantom.
        transformer.registerMethodRedirect(
            "net/minecraft/world/entity/FlyingMob", "getDefaultDimensions",
            "(Lnet/minecraft/world/entity/Pose;)Lnet/minecraft/world/entity/EntityDimensions;",
            "net/minecraft/world/entity/Mob", "getDefaultDimensions",
            "(Lnet/minecraft/world/entity/Pose;)Lnet/minecraft/world/entity/EntityDimensions;"
        );

        // DataResult became an interface in DFU 9.x and dropped get(); use the polyfill.
        transformer.registerMethodRedirect(
            "com/mojang/serialization/DataResult", "get",
            "()Lcom/mojang/datafixers/util/Either;",
            "com/retromod/polyfill/minecraft/DataResultPolyfill", "get",
            "(Ljava/lang/Object;)Ljava/lang/Object;",
            true
        );

        // ForgeRegistries is not class-redirected here. Its field reads are migrated to
        // vanilla Registries ResourceKeys by ForgeRegistryApiShim via field redirects;
        // a class redirect would rewrite the GETSTATIC owner first (ClassRemapper runs
        // before field redirects) and re-break them.

        // Forge dropped the "I" prefix on its extension interfaces: IForgeItem -> ForgeItem, etc.
        // These target net/minecraftforge/common/extensions/, which only exists on a FORGE 26.1
        // host. On a NeoForge host the Forge -> NeoForge migration (Forge_1_20_to_NeoForge_1_21)
        // maps the same interfaces to NeoForge's IItemExtension/IBlockExtension/... instead;
        // registering the Forge-package rename on NeoForge too would clobber that (the redirect map
        // is last-writer-wins across ServiceLoader-discovered shims) and crash a Forge mod's custom
        // Item at construct with NoClassDefFoundError: net/minecraftforge/common/extensions/ForgeItem
        // (Macaw's on NeoForge 26.2). So gate these to Forge hosts only.
        // ALSO version-gated to a 26.1 host: the I-drop was Forge 26.1 (64.x) only - Forge 26.2
        // (65.x) RESTORED the I-names (verified: forge-26.2-65.0.0-universal ships IForgeItem and
        // no ForgeItem), so applying the rename on a 26.2 host recreates the same
        // NoClassDefFoundError: net/minecraftforge/common/extensions/ForgeItem it once fixed.
        if (!McReflect.isNeoForge()
                && RetromodVersion.mcVersionExceeds("26.2", RetromodVersion.TARGET_MC_VERSION)) {
            transformer.registerClassRedirect(
                "net/minecraftforge/common/extensions/IForgeItem",
                "net/minecraftforge/common/extensions/ForgeItem"
            );
            transformer.registerClassRedirect(
                "net/minecraftforge/common/extensions/IForgeBlock",
                "net/minecraftforge/common/extensions/ForgeBlock"
            );
            transformer.registerClassRedirect(
                "net/minecraftforge/common/extensions/IForgeEntity",
                "net/minecraftforge/common/extensions/ForgeEntity"
            );
            transformer.registerClassRedirect(
                "net/minecraftforge/common/extensions/IForgeBlockEntity",
                "net/minecraftforge/common/extensions/ForgeBlockEntity"
            );
        }

        // Catch javax.annotation refs for mods that skip intermediate shims via direct BFS.
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
            // Loader-agnostic; reused from Fabric for the permission and Listener redirects.
            "com.retromod.shim.fabric.embedded.ItemSafetyShim"
        };
    }
}
