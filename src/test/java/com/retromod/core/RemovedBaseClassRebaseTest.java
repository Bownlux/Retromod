/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import static org.junit.jupiter.api.Assertions.*;

/**
 * #260 (Fang's Textiles and Trinkets): a mod that extends a deleted vanilla item class.
 *
 * <p>The class-move table pointed {@code RecordItem} at {@code JukeboxSong}, which is a final
 * record. A move is a flat rename with no idea which slot it lands in, so the mod's {@code extends}
 * clause was rewritten to a class nothing can inherit from and the mod died at class load with
 * {@code IncompatibleClassChangeError}, nowhere near the transform that caused it.
 *
 * <p>The replacement rebases the inheritance edge onto a generated {@code Item} subclass whose
 * constructors are built from the {@code super(...)} calls actually seen, so no list of historical
 * constructor shapes has to be kept correct by hand.
 */
class RemovedBaseClassRebaseTest {

    private static final String REMOVED = "net/minecraft/world/item/RecordItem";
    private static final String GENERATED = "com/retromod/generated/LegacyRecordItem";
    private static final String ITEM = "net/minecraft/world/item/Item";
    private static final String PROPS = "Lnet/minecraft/world/item/Item$Properties;";
    private static final String SOUND = "Lnet/minecraft/sounds/SoundEvent;";
    private static final String MOD_CLASS = "test/removed/DiscItem";

    private RetromodTransformer transformer;

    @BeforeEach
    void setUp() {
        transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        com.retromod.shim.common.RemovedItemBaseBridge.register(transformer);
    }

    @AfterEach
    void tearDown() {
        transformer.clearRedirectsForTesting();
    }

    /** A music disc as an MCreator mod writes one: extends the removed base, calls its ctor. */
    private static byte[] discItem(String superCtorDesc) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, MOD_CLASS, null, REMOVED, null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        for (org.objectweb.asm.Type arg : org.objectweb.asm.Type.getArgumentTypes(superCtorDesc)) {
            if (arg.getSort() == org.objectweb.asm.Type.INT) mv.visitInsn(Opcodes.ICONST_0);
            else mv.visitInsn(Opcodes.ACONST_NULL);
        }
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, REMOVED, "<init>", superCtorDesc, false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * A subclass whose {@code super(...)} descriptor is written verbatim, without parsing it.
     *
     * <p>{@link #discItem} reads the descriptor to push arguments, which a deliberately malformed
     * one breaks in the test itself rather than in the code under test. A hostile jar has no such
     * scruples, so this builds the call the way a hostile jar would.
     */
    private static byte[] rawDiscItem(String superCtorDesc) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, MOD_CLASS, null, REMOVED, null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, REMOVED, "<init>", superCtorDesc, false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(9, 9);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private ClassNode transform(byte[] input) {
        ClassNode cn = new ClassNode();
        new ClassReader(transformer.transformClass(input, MOD_CLASS)).accept(cn, 0);
        return cn;
    }

    private static MethodInsnNode superCallOf(ClassNode cn) {
        MethodNode ctor = cn.methods.stream()
                .filter(m -> m.name.equals("<init>")).findFirst().orElseThrow();
        for (AbstractInsnNode insn : ctor.instructions) {
            if (insn instanceof MethodInsnNode call && call.name.equals("<init>")) return call;
        }
        return fail("the constructor lost its super() call");
    }

    private ClassNode generatedBase() {
        byte[] bytes = transformer.getSyntheticClasses().get(GENERATED);
        assertNotNull(bytes, GENERATED + " must be registered as a synthetic so it gets embedded");
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        return cn;
    }

    @Test
    @DisplayName("the extends clause and the super() call both move to the generated base")
    void rebasesTheInheritanceEdge() {
        String legacyCtor = "(I" + SOUND + PROPS + "I)V";
        ClassNode cn = transform(discItem(legacyCtor));

        assertEquals(GENERATED, cn.superName,
                "the mod must extend the generated base, never the final JukeboxSong record");
        assertEquals(GENERATED, superCallOf(cn).owner);
        assertEquals(legacyCtor, superCallOf(cn).desc,
                "the descriptor is unchanged; only the owner moves");
    }

    @Test
    @DisplayName("the generated base grows the constructor the mod actually calls")
    void absorbsTheObservedConstructor() {
        String legacyCtor = "(I" + SOUND + PROPS + "I)V";
        transform(discItem(legacyCtor));

        ClassNode base = generatedBase();
        assertEquals(ITEM, base.superName);
        assertTrue(base.methods.stream()
                        .anyMatch(m -> m.name.equals("<init>") && m.desc.equals(legacyCtor)),
                "without this constructor the rebased super() call has nothing to link against");
    }

    @Test
    @DisplayName("a second mod with a different constructor shape is absorbed too")
    void absorbsSeveralConstructorShapes() {
        String withLength = "(I" + SOUND + PROPS + "I)V";
        String withoutLength = "(I" + SOUND + PROPS + ")V";
        transform(discItem(withLength));
        transform(discItem(withoutLength));

        ClassNode base = generatedBase();
        for (String desc : new String[]{withLength, withoutLength}) {
            assertTrue(base.methods.stream()
                            .anyMatch(m -> m.name.equals("<init>") && m.desc.equals(desc)),
                    "the base must keep every shape seen in this run, not just the last: " + desc);
        }
    }

    @Test
    @DisplayName("a mod with implausibly many constructor shapes cannot grow the base without limit")
    void capsAbsorbedConstructors() {
        // The descriptors come from the mod, and the base is rebuilt on each new one, so the cost is
        // quadratic in the number of distinct shapes. Measured before the cap: 1500 shapes produced
        // a 1.25 MB class in about four seconds, which would stall startup before the mod ran.
        for (int i = 0; i < 400; i++) {
            StringBuilder desc = new StringBuilder("(");
            for (int arg = 0; arg <= i; arg++) desc.append('I');
            desc.append(PROPS).append(")V");
            transform(discItem(desc.toString()));
        }
        ClassNode base = generatedBase();
        long ctors = base.methods.stream().filter(m -> m.name.equals("<init>")).count();
        assertTrue(ctors <= 65,
                "the generated base must stop absorbing shapes; it carries " + ctors);
    }

    @Test
    @DisplayName("a descriptor the JVM would reject never reaches the shared base")
    void refusesDescriptorsTheJvmWouldReject() {
        // The descriptor is the mod's own super(...) call and only has to satisfy ASM's parser. A
        // constructor may take 255 argument slots including this, so three hundred int arguments
        // would produce a base the JVM rejects with ClassFormatError. That base is shared by every
        // mod extending the same removed class, so one bad jar would break all of them.
        String tooManyArguments = "(" + "I".repeat(300) + PROPS + ")V";
        String unparsableType = "(QBogus;" + PROPS + ")V";
        for (String hostile : new String[]{tooManyArguments, unparsableType}) {
            transform(rawDiscItem(hostile));
            assertFalse(generatedBase().methods.stream()
                            .anyMatch(m -> m.name.equals("<init>") && m.desc.equals(hostile)),
                    "a descriptor the JVM would reject must not become a constructor");
        }

        // The base is still usable afterwards for an ordinary mod.
        String ordinary = "(I" + SOUND + PROPS + "I)V";
        transform(discItem(ordinary));
        assertTrue(generatedBase().methods.stream()
                        .anyMatch(m -> m.name.equals("<init>") && m.desc.equals(ordinary)),
                "one bad jar must not poison the base for the next mod");
    }

    @Test
    @DisplayName("the removed base is no longer renamed onto a final class")
    void removedBaseIsNotAClassMove() {
        // The table row that caused #260. It must stay out: a class move cannot express a
        // deletion, and the nearest surviving name was a final record.
        assertNull(transformer.getClassRedirects().get(REMOVED),
                REMOVED + " must not be registered as a class move");
    }
}
