/*
 * Retromod Test Mod
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.tests;

import com.retromod.testmod.Test;
import com.retromod.testmod.TestResult;
import net.minecraft.block.BlockState;
import net.minecraft.client.color.world.FoliageColors;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Identifier;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3i;

/** Small vanilla members a 1.21.1 mod still calls, which 1.21.2 to 26.3 removed or reshaped. */
public final class Test58SmallVanillaMembers implements Test {

    @Override
    public String description() {
        return "chunk and block positions, a style's font, held-item results, foliage colors, and two calls that lost arguments";
    }

    @Override
    public TestResult run() {
        // 26.1 removed new ChunkPos(pos) and made x and z private record fields; the chunk is still
        // the block's section coordinates.
        ChunkPos chunk = new ChunkPos(new BlockPos(33, 64, -17));
        if (chunk.x != 2 || chunk.z != -2) return TestResult.fail("chunk read as " + chunk.x + "," + chunk.z);
        // 26.3 removed new BlockPos(vec).
        BlockPos copy = new BlockPos(new Vec3i(1, 2, 3));
        if (copy.getX() != 1 || copy.getY() != 2 || copy.getZ() != 3) return TestResult.fail("copied as " + copy);
        // 1.21.9 made a style's font a description; the font file still reads back.
        Identifier font = new Identifier("retromod", "runes");
        Identifier read = Style.EMPTY.withFont(font).getFont();
        if (!font.equals(read)) return TestResult.fail("font read back as " + read);
        // 1.21.2 folded TypedActionResult into ActionResult; the held item and plain result read back.
        ItemStack stack = ItemStack.EMPTY;
        TypedActionResult<ItemStack> success = TypedActionResult.success(stack);
        if (success.getResult() != ActionResult.SUCCESS) return TestResult.fail("success read as " + success.getResult());
        if (success.getValue() != stack) return TestResult.fail("the held item read back as " + success.getValue());
        if (TypedActionResult.pass(stack).getResult() != ActionResult.PASS) return TestResult.fail("pass did not read as PASS");
        // 1.21.4 turned the foliage color getters into constants. Only the color is compared,
        // because newer versions return the same color with a full alpha byte.
        int[] leaves = {FoliageColors.getSpruceColor(), FoliageColors.getBirchColor(),
                FoliageColors.getDefaultColor(), FoliageColors.getMangroveColor()};
        int[] expected = {0x619961, 0x80A755, 0x48B518, 0x92C648};
        for (int i = 0; i < leaves.length; i++) {
            if ((leaves[i] & 0xFFFFFF) != expected[i]) return TestResult.fail("foliage color " + i + " read as " + Integer.toHexString(leaves[i]));
        }
        // Calls on a null receiver prove linkage only.
        String failure = linkage("BlockState.isOpaqueFullCube(world, pos)",
                () -> ((BlockState) null).isOpaqueFullCube(null, BlockPos.ORIGIN));
        if (failure == null) {
            failure = linkage("ServerPlayerEntity.sendMessage(text, overlay)",
                    () -> ((ServerPlayerEntity) null).sendMessage(Text.literal("retromod"), true));
        }
        return failure == null ? TestResult.success() : TestResult.fail(failure);
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
