/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.shim.fabric;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.VersionShim;

/** Fabric 1.16.5 to 1.17: package reorg, MCP to Mojang method renames, tag restructuring, ore config change. */
public class Fabric_1_16_5_to_1_17 implements VersionShim {

    @Override public String getShimName() { return "Fabric 1.16.5 to 1.17"; }
    @Override public String getSourceVersion() { return "1.16.5"; }
    @Override public String getTargetVersion() { return "1.17"; }
    @Override public String getModLoaderType() { return "fabric"; }

    @Override
    public void registerRedirects(RetromodTransformer transformer) {
        // 1.17 flattened net.minecraft.{block,entity,util,world} into net.minecraft.world.{entity,level,phys};
        // class redirects rewrite the owners, method redirects below rename their methods.

        // net/minecraft/entity/* -> net/minecraft/world/entity/*
        transformer.registerClassRedirect("net/minecraft/entity/Entity",
                "net/minecraft/world/entity/Entity");
        transformer.registerClassRedirect("net/minecraft/entity/LivingEntity",
                "net/minecraft/world/entity/LivingEntity");
        transformer.registerClassRedirect("net/minecraft/entity/MobEntity",
                "net/minecraft/world/entity/Mob");
        transformer.registerClassRedirect("net/minecraft/entity/player/PlayerEntity",
                "net/minecraft/world/entity/player/Player");
        transformer.registerClassRedirect("net/minecraft/entity/EntityType",
                "net/minecraft/world/entity/EntityType");

        // World -> Level
        transformer.registerClassRedirect("net/minecraft/world/World",
                "net/minecraft/world/level/Level");
        transformer.registerClassRedirect("net/minecraft/world/WorldView",
                "net/minecraft/world/level/LevelReader");
        transformer.registerClassRedirect("net/minecraft/world/chunk/Chunk",
                "net/minecraft/world/level/chunk/ChunkAccess");
        transformer.registerClassRedirect("net/minecraft/world/chunk/WorldChunk",
                "net/minecraft/world/level/chunk/LevelChunk");

        // blocks/items moved under world/level and world/item
        transformer.registerClassRedirect("net/minecraft/block/BlockState",
                "net/minecraft/world/level/block/state/BlockState");
        transformer.registerClassRedirect("net/minecraft/block/Block",
                "net/minecraft/world/level/block/Block");
        transformer.registerClassRedirect("net/minecraft/block/Blocks",
                "net/minecraft/world/level/block/Blocks");
        transformer.registerClassRedirect("net/minecraft/item/Item",
                "net/minecraft/world/item/Item");
        transformer.registerClassRedirect("net/minecraft/item/Items",
                "net/minecraft/world/item/Items");
        transformer.registerClassRedirect("net/minecraft/item/ItemStack",
                "net/minecraft/world/item/ItemStack");

        // math types -> net.minecraft.core / net.minecraft.world.phys
        transformer.registerClassRedirect("net/minecraft/util/math/BlockPos",
                "net/minecraft/core/BlockPos");
        transformer.registerClassRedirect("net/minecraft/util/math/Direction",
                "net/minecraft/core/Direction");
        transformer.registerClassRedirect("net/minecraft/util/math/Box",
                "net/minecraft/world/phys/AABB");
        transformer.registerClassRedirect("net/minecraft/util/math/Vec3d",
                "net/minecraft/world/phys/Vec3");
        transformer.registerClassRedirect("net/minecraft/util/math/ChunkPos",
                "net/minecraft/world/level/ChunkPos");

        // Identifier -> ResourceLocation
        transformer.registerClassRedirect("net/minecraft/util/Identifier",
                "net/minecraft/resources/ResourceLocation");

        // client: MinecraftClient -> Minecraft, MatrixStack -> PoseStack
        transformer.registerClassRedirect("net/minecraft/client/MinecraftClient",
                "net/minecraft/client/Minecraft");
        transformer.registerClassRedirect("net/minecraft/client/util/math/MatrixStack",
                "com/mojang/blaze3d/vertex/PoseStack");
        transformer.registerClassRedirect("net/minecraft/client/render/VertexConsumer",
                "com/mojang/blaze3d/vertex/VertexConsumer");
        transformer.registerClassRedirect("net/minecraft/client/render/VertexConsumerProvider",
                "net/minecraft/client/renderer/MultiBufferSource");
        transformer.registerClassRedirect("net/minecraft/client/gui/screen/Screen",
                "net/minecraft/client/gui/screens/Screen");
        transformer.registerClassRedirect("net/minecraft/client/gui/hud/InGameHud",
                "net/minecraft/client/gui/Gui");
        transformer.registerClassRedirect("net/minecraft/client/render/entity/model/EntityModel",
                "net/minecraft/client/model/EntityModel");

        // ClientConnection -> Connection
        transformer.registerClassRedirect("net/minecraft/network/ClientConnection",
                "net/minecraft/network/Connection");

        // NOTE: the ClientTickEvents/ServerTickEvents *WorldTick -> *LevelTick inner-interface
        // renames used to live here, which was WRONG: those are 26.x Fabric API renames, and
        // applying them on a pre-26.1 host rewrote mods onto names the installed Fabric API
        // does not have (caught live in the snapshot.8 acceptance pass: Stack Refill 1.16.5
        // died NoClassDefFoundError ServerTickEvents$StartLevelTick on a 1.20.1 server). The
        // correct copies live in Fabric_1_21_11_to_26_1, which the chain applies exactly when
        // the host actually has the new names.

        // tags moved to net.minecraft.registry.tag
        transformer.registerClassRedirect(
            "net/minecraft/tag/ServerTagManagerHolder",
            "com/retromod/shim/fabric/embedded/TagShim"
        );
        transformer.registerClassRedirect(
            "net/minecraft/tag/BlockTags",
            "net/minecraft/registry/tag/BlockTags"
        );
        transformer.registerClassRedirect(
            "net/minecraft/tag/ItemTags",
            "net/minecraft/registry/tag/ItemTags"
        );
        transformer.registerClassRedirect(
            "net/minecraft/tag/EntityTypeTags",
            "net/minecraft/registry/tag/EntityTypeTags"
        );
        transformer.registerClassRedirect(
            "net/minecraft/tag/FluidTags",
            "net/minecraft/registry/tag/FluidTags"
        );
        // OreFeatureConfig ctor changed shape
        transformer.registerConstructorRedirect(
            "net/minecraft/world/gen/feature/OreFeatureConfig",
            "(Lnet/minecraft/structure/rule/RuleTest;Lnet/minecraft/block/BlockState;I)V",
            "com/retromod/shim/fabric/embedded/OreFeatureShim", "create",
            "(Ljava/lang/Object;Ljava/lang/Object;I)Ljava/lang/Object;"
        );

        // MCP -> Mojang method renames.
        transformer.registerMethodRedirect(
            "net/minecraft/world/entity/Entity", "onUpdate",
            "()V",
            "net/minecraft/world/entity/Entity", "tick",
            "()V"
        );
        transformer.registerMethodRedirect(
            "net/minecraft/world/entity/Entity", "attackEntityFrom",
            "(Lnet/minecraft/world/damagesource/DamageSource;F)Z",
            "net/minecraft/world/entity/Entity", "hurt",
            "(Lnet/minecraft/world/damagesource/DamageSource;F)Z"
        );
        transformer.registerMethodRedirect(
            "net/minecraft/world/entity/LivingEntity", "onDeath",
            "(Lnet/minecraft/world/damagesource/DamageSource;)V",
            "net/minecraft/world/entity/LivingEntity", "die",
            "(Lnet/minecraft/world/damagesource/DamageSource;)V"
        );
        // setBlockState -> setBlock (3-arg: BlockPos, BlockState, flags)
        transformer.registerMethodRedirect(
            "net/minecraft/world/level/Level", "setBlockState",
            "(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z",
            "net/minecraft/world/level/Level", "setBlock",
            "(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z"
        );
        // setBlockToAir -> removeBlock (gains a Z param)
        transformer.registerMethodRedirect(
            "net/minecraft/world/level/Level", "setBlockToAir",
            "(Lnet/minecraft/core/BlockPos;)Z",
            "net/minecraft/world/level/Level", "removeBlock",
            "(Lnet/minecraft/core/BlockPos;Z)Z"
        );
        transformer.registerMethodRedirect(
            "net/minecraft/world/level/block/Block", "onBlockPlacedBy",
            "(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/item/ItemStack;)V",
            "net/minecraft/world/level/block/Block", "setPlacedBy",
            "(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/item/ItemStack;)V"
        );
        transformer.registerMethodRedirect(
            "net/minecraft/world/item/Item", "onItemRightClick",
            "(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/InteractionHand;)Lnet/minecraft/world/InteractionResultHolder;",
            "net/minecraft/world/item/Item", "use",
            "(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/InteractionHand;)Lnet/minecraft/world/InteractionResultHolder;"
        );
        transformer.registerMethodRedirect(
            "net/minecraft/world/item/Item", "onItemUseFinish",
            "(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/LivingEntity;)Lnet/minecraft/world/item/ItemStack;",
            "net/minecraft/world/item/Item", "finishUsingItem",
            "(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/LivingEntity;)Lnet/minecraft/world/item/ItemStack;"
        );
        transformer.registerMethodRedirect(
            "net/minecraft/world/item/Item", "onUpdate",
            "(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/Entity;IZ)V",
            "net/minecraft/world/item/Item", "inventoryTick",
            "(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/Entity;IZ)V"
        );
        transformer.registerMethodRedirect(
            "net/minecraft/world/item/Item", "addInformation",
            "(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/level/Level;Ljava/util/List;Lnet/minecraft/world/item/TooltipFlag;)V",
            "net/minecraft/world/item/Item", "appendHoverText",
            "(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/level/Level;Ljava/util/List;Lnet/minecraft/world/item/TooltipFlag;)V"
        );
        transformer.registerMethodRedirect(
            "net/minecraft/world/level/block/entity/BlockEntity", "readFromNBT",
            "(Lnet/minecraft/nbt/CompoundTag;)V",
            "net/minecraft/world/level/block/entity/BlockEntity", "load",
            "(Lnet/minecraft/nbt/CompoundTag;)V"
        );
        transformer.registerMethodRedirect(
            "net/minecraft/world/level/block/entity/BlockEntity", "writeToNBT",
            "(Lnet/minecraft/nbt/CompoundTag;)Lnet/minecraft/nbt/CompoundTag;",
            "net/minecraft/world/level/block/entity/BlockEntity", "save",
            "(Lnet/minecraft/nbt/CompoundTag;)Lnet/minecraft/nbt/CompoundTag;"
        );
        transformer.registerMethodRedirect(
            "net/minecraft/client/gui/screens/Screen", "drawScreen",
            "(IIF)V",
            "net/minecraft/client/gui/screens/Screen", "render",
            "(IIF)V"
        );
        transformer.registerMethodRedirect(
            "net/minecraft/client/gui/screens/Screen", "initGui",
            "()V",
            "net/minecraft/client/gui/screens/Screen", "init",
            "()V"
        );
    }

    @Override
    public String[] getShimClasses() {
        return new String[0];
    }
}
