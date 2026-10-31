/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.mapping;

import com.retromod.core.RetromodTransformer;
import com.retromod.shim.common.Mc26_1To26_2CoreMoves;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A NESTED intermediary class name ({@code net/minecraft/class_X$class_Y}) must remap whole. The tsv
 * carries the combined entry ({@code class_327$class_6415 -> Font$DisplayMode}), but the FQ pattern
 * used to stop at {@code $} and map only the outer, leaving a {@code Font$class_6415} hybrid that
 * doesn't resolve on 26.x (the inner id has no top-level mapping). This bit access-widener and
 * mixin-refmap remapping (found in-game: cloth-config's AW, AppleSkin's refmap) and descriptor
 * remapping. A leading nested pattern now matches + looks up the whole nested name first.
 */
class NestedIntermediaryClassRemapTest {

    private final IntermediaryToMojangMapper m = IntermediaryToMojangMapper.getInstance();

    @Test
    @DisplayName("nested class_X$class_Y remaps whole (no intermediary inner left as a hybrid)")
    void nestedRemapsWhole() {
        // sanity: the combined entry is in the tsv
        assertEquals("net/minecraft/client/gui/Font$DisplayMode",
                m.mapClass("net/minecraft/class_327$class_6415"), "combined tsv entry present");

        assertEquals("net/minecraft/client/gui/Font$DisplayMode",
                m.remapString("net/minecraft/class_327$class_6415"),
                "remapString must map the whole nested name, not leave Font$class_6415");
        assertEquals("com/mojang/blaze3d/platform/GlStateManager$FboMode",
                m.remapString("net/minecraft/class_4493$class_1010"),
                "GlStateManager$class_1010 hybrid must become GlStateManager$FboMode");
        // descriptor form (as it appears in an access widener / refmap / method descriptor)
        assertEquals("Lnet/minecraft/client/gui/Font$DisplayMode;",
                m.remapDescriptor("Lnet/minecraft/class_327$class_6415;"),
                "the nested name inside a descriptor remaps whole too");
    }

    @Test
    @DisplayName("MinMaxBounds maps to its 26.1 package and follows the 26.2 move to predicates")
    void minMaxBoundsPackageMove() {
        // 26.1 keeps MinMaxBounds in advancements/criterion and 26.2 moved it to advancements/predicates.
        // The table holds the 26.1 name and the 26.2 shim carries it on, because a 26.2 name in the
        // table left every 26.1.x host without the class. class_2096$class_2100 is MinMaxBounds$Ints.
        assertEquals("net/minecraft/advancements/criterion/MinMaxBounds", m.mapClass("net/minecraft/class_2096"));
        assertEquals("net/minecraft/advancements/criterion/MinMaxBounds$Ints",
                m.remapString("net/minecraft/class_2096$class_2100"), "the nested $Ints stays beside it");

        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        try {
            IntermediaryToMojangMapper.applyTo(transformer);
            Mc26_1To26_2CoreMoves.register(transformer);
            assertEquals("Lnet/minecraft/advancements/predicates/MinMaxBounds$Ints;",
                    fieldDescriptorAfterTransform(transformer, "Lnet/minecraft/class_2096$class_2100;"),
                    "on 26.2 and newer the shim's move finishes the job, as jade's version predicate needs");
        } finally {
            transformer.clearRedirectsForTesting();
        }
    }

    /** Transforms a class with one field of the given type, and returns the field's new descriptor. */
    private static String fieldDescriptorAfterTransform(RetromodTransformer transformer, String descriptor) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/HoldsAField", null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PUBLIC, "bounds", descriptor, null, null).visitEnd();
        writer.visitEnd();
        ClassNode output = new ClassNode();
        new ClassReader(transformer.transformClass(writer.toByteArray(), "test/HoldsAField.class")).accept(output, 0);
        return output.fields.get(0).desc;
    }

    @Test
    @DisplayName("GlStateManager inner state classes complete the platform->opengl move (26.1)")
    void glStateManagerInnersMoved() {
        var moves = m.getClassMoves();
        assertEquals("com/mojang/blaze3d/opengl/GlStateManager$BlendState",
                moves.get("com/mojang/blaze3d/platform/GlStateManager$BlendState"),
                "BlendState should follow its enclosing class");
        assertEquals("com/mojang/blaze3d/opengl/GlStateManager$DepthState",
                moves.get("com/mojang/blaze3d/platform/GlStateManager$DepthState"));
        assertEquals("com/mojang/blaze3d/opengl/GlConst",
                moves.get("com/mojang/blaze3d/platform/GlConst"));
        assertEquals("com/mojang/blaze3d/opengl/GlDebug$LogEntry",
                moves.get("com/mojang/blaze3d/platform/GlDebug$LogEntry"));
    }

    @Test
    @DisplayName("top-level and anonymous-inner names are unaffected by the nested pattern")
    void topLevelAndAnonUnaffected() {
        // top-level: no $class_N, so the nested pattern doesn't touch it; the FQ path still maps it
        assertEquals("net/minecraft/client/gui/Font", m.remapString("net/minecraft/class_327"),
                "a plain top-level class still remaps");
        // an anonymous inner keeps its numeric index; outer maps, $1 stays $1 (which IS the Mojang form)
        String anon = m.remapString("net/minecraft/class_4493$1");
        assertTrue(anon.endsWith("$1"), "anonymous inner keeps its $1 index: " + anon);
        assertTrue(anon.startsWith("com/mojang/blaze3d/platform/GlStateManager"),
                "the anonymous inner's outer still maps: " + anon);
    }
}
