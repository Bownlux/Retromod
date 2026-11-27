/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testsupport.fakemc.frapi;

/** Old mod models as they look after translation: a dynamic one, a vanilla adapter, a broken one and one that draws another. */
public final class FakeOldModels {
    private FakeOldModels() {}

    /** Emits one quad unless its north face is culled. */
    public static final class Dynamic implements FakeOldModelBase {
        public boolean isVanillaAdapter() {
            return false;
        }

        public void emitBlockQuads(FakeBlockAndTintGetter level, FakeBlockState state, FakeBlockPos pos,
                java.util.function.Supplier<FakeRandom> random, FakeQuadEmitter context) {
            if (FakeContexts.isFaceCulled(context, FakeDirection.NORTH)) return;
            context.fromBakedQuad(new FakeBakedQuad("dynamic"));
            context.emit();
        }

        public java.util.List<FakeBakedQuad> getQuads(FakeBlockState state, FakeDirection side, FakeRandom random) {
            return side == null ? java.util.List.of(new FakeBakedQuad("plain")) : java.util.List.of();
        }

        public FakeSprite getParticleIcon() {
            return new FakeSprite(0f, 1f, 0f, 1f);
        }
    }

    /** Draws through its quads: one on the north side and one unculled. */
    public static final class Vanilla implements FakeOldModelBase {
        public java.util.List<FakeBakedQuad> getQuads(FakeBlockState state, FakeDirection side, FakeRandom random) {
            if (side == FakeDirection.NORTH) return java.util.List.of(new FakeBakedQuad("north"));
            return side == null ? java.util.List.of(new FakeBakedQuad("unculled", 1)) : java.util.List.of();
        }

        public boolean useAmbientOcclusion() {
            return false;
        }

        public FakeSprite getParticleIcon() {
            return new FakeSprite(0f, 1f, 0f, 1f);
        }
    }

    /** Pushes a quad transform on its context, then reaches a member the host no longer has. */
    public static final class LeavesTransform implements FakeOldModelBase {
        public boolean isVanillaAdapter() {
            return false;
        }

        public void emitBlockQuads(FakeBlockAndTintGetter level, FakeBlockState state, FakeBlockPos pos,
                java.util.function.Supplier<FakeRandom> random, FakeQuadEmitter context) {
            FakeContexts.pushTransform(context, null);
            throw new NoSuchMethodError("a member 26.x removed");
        }

        public java.util.List<FakeBakedQuad> getQuads(FakeBlockState state, FakeDirection side, FakeRandom random) {
            return java.util.List.of();
        }
    }

    /** Reaches a member the host no longer has. */
    public static final class Broken implements FakeOldModelBase {
        public boolean isVanillaAdapter() {
            return false;
        }

        public void emitBlockQuads(FakeBlockAndTintGetter level, FakeBlockState state, FakeBlockPos pos,
                java.util.function.Supplier<FakeRandom> random, FakeQuadEmitter context) {
            throw new NoSuchMethodError("a member 26.x removed");
        }

        public java.util.List<FakeBakedQuad> getQuads(FakeBlockState state, FakeDirection side, FakeRandom random) {
            return java.util.List.of();
        }
    }

    /** Draws another model where it is drawn, through the old context's model consumer. */
    public static final class Delegating implements FakeOldModelBase {
        public final HostModel inner = new HostModel();

        public boolean isVanillaAdapter() {
            return false;
        }

        public void emitBlockQuads(FakeBlockAndTintGetter level, FakeBlockState state, FakeBlockPos pos,
                java.util.function.Supplier<FakeRandom> random, FakeQuadEmitter context) {
            FakeContexts.bakedModelConsumer(context).accept(inner);
        }

        public java.util.List<FakeBakedQuad> getQuads(FakeBlockState state, FakeDirection side, FakeRandom random) {
            return java.util.List.of();
        }
    }

    /** A model as Minecraft or a current mod writes it, which draws the host's way. */
    public static final class HostModel implements FakeBlockStateModel, FakeFabricBlockStateModel {
        @Override
        public void collectParts(FakeRandom random, java.util.List<FakeBlockStateModelPart> parts) {}

        @Override
        public FakeMaterial.Baked particleMaterial() {
            return null;
        }

        @Override
        public int materialFlags() {
            return 0;
        }
    }
}
