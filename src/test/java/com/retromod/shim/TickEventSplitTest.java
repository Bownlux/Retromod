/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.shim.api.forge.ForgeEventApiShim;
import com.retromod.shim.forge.embedded.TickEventPhaseSynthetic;
import com.retromod.util.McReflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * NeoForge split Forge's one fire-twice tick event into {@code Pre}/{@code Post} and removed the
 * {@code phase} field and {@code Phase} enum, so a Forge mod that listens on the abstract parent
 * and checks {@code event.phase == Phase.END} fails to construct (#184, S33R More Food, an MCreator
 * mod whose generated tick boilerplate is shared by countless others).
 */
class TickEventSplitTest {

    private static final String SERVER_TICK = "net/minecraftforge/event/TickEvent$ServerTickEvent";
    private static final String PLAYER_TICK = "net/minecraftforge/event/TickEvent$PlayerTickEvent";
    private static final String PHASE = "net/minecraftforge/event/TickEvent$Phase";
    private static final String SERVER_TICK_POST =
            "net/neoforged/neoforge/event/tick/ServerTickEvent$Post";

    private boolean savedForce;

    @AfterEach
    void restore() {
        McReflect.setForceNeoForge(savedForce);
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    /** The MCreator tick boilerplate: listen on the abstract event, act only at END. */
    private static byte[] mcreatorTickListener() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/TickMod", null, "java/lang/Object", null);
        MethodVisitor m = cw.visitMethod(Opcodes.ACC_PUBLIC, "tick",
                "(L" + SERVER_TICK + ";)V", null, null);
        m.visitCode();
        Label end = new Label();
        m.visitVarInsn(Opcodes.ALOAD, 1);
        m.visitFieldInsn(Opcodes.GETFIELD, SERVER_TICK, "phase", "L" + PHASE + ";");
        m.visitFieldInsn(Opcodes.GETSTATIC, PHASE, "END", "L" + PHASE + ";");
        m.visitJumpInsn(Opcodes.IF_ACMPNE, end);
        // (work would go here)
        m.visitLabel(end);
        m.visitInsn(Opcodes.RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();

        MethodVisitor p = cw.visitMethod(Opcodes.ACC_PUBLIC, "onPlayer",
                "(L" + PLAYER_TICK + ";)V", null, null);
        p.visitCode();
        p.visitVarInsn(Opcodes.ALOAD, 1);
        p.visitFieldInsn(Opcodes.GETFIELD, PLAYER_TICK, "player",
                "Lnet/minecraft/world/entity/player/Player;");
        p.visitInsn(Opcodes.POP);
        p.visitInsn(Opcodes.RETURN);
        p.visitMaxs(0, 0);
        p.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static ClassNode transform() {
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        new ForgeEventApiShim().registerRedirects(t);
        byte[] out = t.transformClass(mcreatorTickListener(), "test/TickMod");
        assertNotNull(out, "the listener should be re-emitted");
        ClassNode cn = new ClassNode();
        new ClassReader(out).accept(cn, 0);
        return cn;
    }

    private static MethodNode method(ClassNode cn, String name) {
        return cn.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
    }

    private static boolean calls(MethodNode m, String owner, String name) {
        for (var insn : m.instructions.toArray()) {
            if (insn instanceof MethodInsnNode c && c.owner.equals(owner) && c.name.equals(name)) {
                return true;
            }
        }
        return false;
    }

    @Test
    @DisplayName("#184: the listener binds to a concrete Post event and its phase check stays true")
    void bindsToPostAndKeepsPhaseCheck() {
        savedForce = McReflect.isForceNeoForge();
        McReflect.setForceNeoForge(true);

        ClassNode cn = transform();
        MethodNode tick = method(cn, "tick");

        assertEquals("(L" + SERVER_TICK_POST + ";)V", tick.desc,
                "the listener must take the concrete Post event, or NeoForge refuses to register it");

        // event.phase is now a call returning END, and Phase.END is the synthetic's constant, so
        // the mod's own == END comparison still passes and its end-tick work runs.
        assertTrue(calls(tick, TickEventPhaseSynthetic.INTERNAL, TickEventPhaseSynthetic.PHASE_OF_NAME),
                "phase read should bridge to the synthetic");
        boolean readsEnd = false;
        for (var insn : tick.instructions.toArray()) {
            if (insn instanceof FieldInsnNode f && f.getOpcode() == Opcodes.GETSTATIC
                    && f.owner.equals(TickEventPhaseSynthetic.INTERNAL) && f.name.equals("END")) {
                readsEnd = true;
            }
        }
        assertTrue(readsEnd, "Phase.END should resolve to the synthetic's END constant");
        assertFalse(tick.desc.contains("minecraftforge"),
                "no Forge name may survive in the listener signature");
    }

    @Test
    @DisplayName("#184: PlayerTickEvent.player becomes getEntity() on the concrete Post event")
    void playerFieldBecomesGetter() {
        savedForce = McReflect.isForceNeoForge();
        McReflect.setForceNeoForge(true);

        MethodNode onPlayer = method(transform(), "onPlayer");
        assertEquals("(Lnet/neoforged/neoforge/event/tick/PlayerTickEvent$Post;)V", onPlayer.desc);
        assertTrue(calls(onPlayer, "net/neoforged/neoforge/event/tick/PlayerTickEvent$Post", "getEntity"),
                "the removed player field must read through getEntity()");
    }

    @Test
    @DisplayName("The Phase synthetic loads, its constants are distinct, and of() answers END")
    void phaseSyntheticShape() throws Exception {
        byte[] bytes = TickEventPhaseSynthetic.generate();
        String binaryName = TickEventPhaseSynthetic.INTERNAL.replace('/', '.');
        Class<?> phase = new ClassLoader(getClass().getClassLoader()) {
            Class<?> load() { return defineClass(binaryName, bytes, 0, bytes.length); }
        }.load();

        Object start = phase.getField("START").get(null);
        Object end = phase.getField("END").get(null);
        assertNotNull(start);
        assertNotNull(end);
        assertNotSame(start, end, "START and END must be distinct for identity comparison to work");

        // of(receiver) stands in for a Post event's phase, which is always END.
        Object answered = phase.getMethod(TickEventPhaseSynthetic.PHASE_OF_NAME, Object.class)
                .invoke(null, new Object());
        assertSame(end, answered, "a Post event's phase must compare equal to END");
    }
}
