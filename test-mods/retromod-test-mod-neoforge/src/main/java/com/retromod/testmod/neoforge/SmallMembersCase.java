/*
 * Retromod Test Mod (NeoForge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.neoforge;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.FoliageColor;
import net.minecraft.world.level.block.state.BlockState;

/** Small vanilla members a 1.21.1 mod still calls, which 1.21.2 to 26.3 removed or reshaped. */
final class SmallMembersCase {

    private SmallMembersCase() {}

    /** Chunk and block positions, a style's font, held-item results, foliage colors, and two calls that lost arguments. */
    static String common() {
        // 26.1 removed new ChunkPos(pos) and made x and z private record fields; the chunk is still
        // the block's section coordinates.
        ChunkPos chunk = new ChunkPos(new BlockPos(33, 64, -17));
        if (chunk.x != 2 || chunk.z != -2) return "fail: chunk read as " + chunk.x + "," + chunk.z;
        // 26.3 removed new BlockPos(vec).
        BlockPos copy = new BlockPos(new Vec3i(1, 2, 3));
        if (copy.getX() != 1 || copy.getY() != 2 || copy.getZ() != 3) return "fail: copied as " + copy;
        // 1.21.9 made a style's font a description; the font file still reads back.
        ResourceLocation font = ResourceLocation.fromNamespaceAndPath("retromod", "runes");
        ResourceLocation read = Style.EMPTY.withFont(font).getFont();
        if (!font.equals(read)) return "fail: font read back as " + read;
        // 1.21.2 folded InteractionResultHolder into InteractionResult; the held item and plain result read back.
        ItemStack stack = ItemStack.EMPTY;
        InteractionResultHolder<ItemStack> success = InteractionResultHolder.success(stack);
        if (success.getResult() != InteractionResult.SUCCESS) return "fail: success read as " + success.getResult();
        if (success.getObject() != stack) return "fail: the held item read back as " + success.getObject();
        if (InteractionResultHolder.pass(stack).getResult() != InteractionResult.PASS) return "fail: pass did not read as PASS";
        // 1.21.4 turned the foliage color getters into constants. Only the color is compared,
        // because newer versions return the same color with a full alpha byte.
        int[] leaves = {FoliageColor.getEvergreenColor(), FoliageColor.getBirchColor(),
                FoliageColor.getDefaultColor(), FoliageColor.getMangroveColor()};
        int[] expected = {0x619961, 0x80A755, 0x48B518, 0x92C648};
        for (int i = 0; i < leaves.length; i++) {
            if ((leaves[i] & 0xFFFFFF) != expected[i]) return "fail: foliage color " + i + " read as " + Integer.toHexString(leaves[i]);
        }
        // Calls on a null receiver prove linkage only.
        String failure = linkage("BlockState.isSolidRender(level, pos)",
                () -> ((BlockState) null).isSolidRender(null, BlockPos.ZERO));
        if (failure == null) {
            failure = linkage("ServerPlayer.displayClientMessage(text, overlay)",
                    () -> ((ServerPlayer) null).displayClientMessage(Component.literal("retromod"), true));
        }
        return failure == null ? "success" : "fail: " + failure;
    }

    /** {@code registry(key)} on the server's registries, and an entity's {@code getServer()}. */
    static String server(MinecraftServer server) {
        // 1.21.2 renamed registry(key) to the lookup the registries already had.
        if (server.registryAccess().registry(Registries.BIOME).isEmpty()) return "fail: the biome registry was not found";
        // 1.21.9 removed Entity.getServer(); the entity's level still has the server.
        Entity item = new ItemEntity(server.overworld(), 0, 64, 0, ItemStack.EMPTY);
        if (item.getServer() != server) return "fail: the entity's server read as " + item.getServer();
        return "success";
    }

    /** Only a linkage error counts: anything thrown after the call links is the null receiver. */
    private static String linkage(String label, Runnable probe) {
        try {
            probe.run();
            return null;
        } catch (IncompatibleClassChangeError | NoClassDefFoundError | VerifyError e) {
            return label + " did not link: " + e.getClass().getSimpleName() + ": " + e.getMessage();
        } catch (Throwable linkedThenRefused) {
            return null;
        }
    }
}
