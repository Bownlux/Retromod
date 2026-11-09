/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.world.level.levelgen.structure.templatesystem;

import com.mojang.serialization.MapCodec;
import java.lang.reflect.Method;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorType;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;

/** The structure processor class mods extended before 26.2 made the processor an interface keyed by its codec. */
public abstract class LegacyStructureProcessor implements StructureProcessor {

    /** The old hook, given the block as the template stored it and as earlier processors left it. */
    public StructureBlockInfo processBlock(LevelReader level, BlockPos offset, BlockPos pos, StructureBlockInfo blockInfo,
            StructureBlockInfo relativeBlockInfo, StructurePlaceSettings settings) {
        return relativeBlockInfo;
    }

    @Override
    public StructureBlockInfo processBlock(LevelReader level, BlockPos targetPosition, BlockPos referencePos,
            BlockPos templateRelativePos, StructureBlockInfo processedBlockInfo, StructurePlaceSettings settings) {
        // 26.2 passes only where the stored block sat in the template, so the old first block carries that
        // position with the block as it is now.
        StructureBlockInfo stored = new StructureBlockInfo(templateRelativePos, processedBlockInfo.state(),
                processedBlockInfo.nbt());
        return processBlock(level, targetPosition, referencePos, stored, processedBlockInfo, settings);
    }

    /** The processor type the mod registered, which newer hosts keep as the processor's codec. */
    protected abstract StructureProcessorType getType();

    @Override
    @SuppressWarnings("unchecked")
    public MapCodec<? extends StructureProcessor> codec() {
        Object type = getType();
        if (type instanceof MapCodec<?> codec) return (MapCodec<? extends StructureProcessor>) codec;
        // The mod keeps its old type, a () -> CODEC lambda, while the registry holds the codec it answers.
        try {
            Method answer = type.getClass().getMethod("codec");
            // The lambda's class is not public, but it lives in this mod's module with this class.
            answer.setAccessible(true);
            if (answer.invoke(type) instanceof MapCodec<?> codec) return (MapCodec<? extends StructureProcessor>) codec;
        } catch (ReflectiveOperationException | RuntimeException unreadable) {
            throw new IllegalStateException("Retromod could not read the codec of structure processor "
                    + getClass().getName() + " from its type " + type, unreadable);
        }
        throw new IllegalStateException("Retromod could not read the codec of structure processor "
                + getClass().getName() + ": its type " + type + " does not answer a map codec");
    }
}
