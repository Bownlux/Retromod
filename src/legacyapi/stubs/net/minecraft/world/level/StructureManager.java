// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.world.level;

public class StructureManager {
    public net.minecraft.world.level.levelgen.structure.StructureStart getStartForStructure(
            net.minecraft.world.level.levelgen.structure.Structure structure, net.minecraft.world.level.chunk.StructureAccess access) {
        throw new UnsupportedOperationException();
    }
    public net.minecraft.world.level.levelgen.structure.StructureStart getStructureWithPieceAt(int x, int y, int z,
            net.minecraft.world.level.levelgen.structure.Structure structure) { throw new UnsupportedOperationException(); }
    public net.minecraft.world.level.levelgen.structure.StructureStart getStructureWithPieceAt(net.minecraft.core.BlockPos pos,
            java.util.function.Predicate<net.minecraft.core.Holder<net.minecraft.world.level.levelgen.structure.Structure>> filter) { throw new UnsupportedOperationException(); }
}
