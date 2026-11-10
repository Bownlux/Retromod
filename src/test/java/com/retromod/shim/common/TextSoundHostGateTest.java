/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.core.VersionShim;
import com.retromod.shim.ShimRegistry;
import com.retromod.shim.fabric.Fabric_1_21_10_to_1_21_11;
import com.retromod.util.McReflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** Old text, sound, math and NBT calls are bridged from the host version that changed them, not only on 26.x. */
class TextSoundHostGateTest {

    private static final String SOUND_MANAGER = "net/minecraft/client/sounds/SoundManager";
    private static final String PLAY_OLD = "(Lnet/minecraft/client/resources/sounds/SoundInstance;)V";
    private static final String PLAY_NEW =
            "(Lnet/minecraft/client/resources/sounds/SoundInstance;)Lnet/minecraft/client/sounds/SoundEngine$PlayResult;";
    private static final String LISTENER = "com/mojang/blaze3d/audio/Listener";
    private static final String ITEM_SAFETY = "com/retromod/shim/fabric/embedded/ItemSafetyShim";
    private static final String NARRATOR = "net/minecraft/client/GameNarrator";
    private static final String COMPONENT = "net/minecraft/network/chat/Component";
    private static final String CLICK = "net/minecraft/network/chat/ClickEvent";
    private static final String HOVER = "net/minecraft/network/chat/HoverEvent";
    private static final String TEXT_EVENTS = "com/retromod/polyfill/minecraft/RetroTextEvents";
    private static final String COMPOUND = "net/minecraft/nbt/CompoundTag";
    private static final String TRACING = "net/minecraft/TracingExecutor";
    private static final String EXECUTOR = "()Ljava/util/concurrent/ExecutorService;";

    private RetromodTransformer transformer;
    private String previousTarget;

    @BeforeEach
    void setUp() {
        transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        previousTarget = RetromodVersion.TARGET_MC_VERSION;
    }

    @AfterEach
    void tearDown() {
        transformer.clearRedirectsForTesting();
        RetromodVersion.TARGET_MC_VERSION = previousTarget;
        McReflect.setForceNeoForge(false);
    }

    @ParameterizedTest(name = "{0} {1}: PlayResult form = {2}")
    @DisplayName("SoundManager.play gets the PlayResult form from 1.21.6")
    @CsvSource({
        "neoforge, 1.21.5,  false",
        "neoforge, 1.21.6,  true",
        "neoforge, 1.21.11, true",
        "neoforge, 26.1,    true",
        "forge,    1.21.5,  false",
        "forge,    1.21.6,  true",
    })
    void soundPlayFrom1216(String loader, String host, boolean bridged) {
        loadShimsFor(loader, host);
        List<AbstractInsnNode> body = transformCall("(L" + SOUND_MANAGER
                + ";Lnet/minecraft/client/resources/sounds/SoundInstance;)V", mv -> {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitMethodInsn(INVOKEVIRTUAL, SOUND_MANAGER, "play", PLAY_OLD, false);
        });

        MethodInsnNode play = call(body, SOUND_MANAGER, "play");
        assertNotNull(play, loader + " " + host + " must still call SoundManager.play");
        assertEquals(bridged ? PLAY_NEW : PLAY_OLD, play.desc, loader + " " + host);
        assertEquals(bridged, next(body, play).getOpcode() == POP,
                "the PlayResult must be popped exactly when the call returns one");
    }

    @ParameterizedTest(name = "{0} {1}: no-op = {2}")
    @DisplayName("Listener.setGain becomes a no-op from 1.21.9")
    @CsvSource({
        "neoforge, 1.21.8,  false",
        "neoforge, 1.21.9,  true",
        "neoforge, 1.21.11, true",
        "neoforge, 26.1,    true",
        "forge,    1.21.8,  false",
        "forge,    1.21.9,  true",
        "forge,    26.1,    true",
        "fabric,   26.1,    true",
    })
    void listenerGainFrom1219(String loader, String host, boolean bridged) {
        loadShimsFor(loader, host);
        List<AbstractInsnNode> body = transformCall("(L" + LISTENER + ";F)V", mv -> {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitVarInsn(FLOAD, 1);
            mv.visitMethodInsn(INVOKEVIRTUAL, LISTENER, "setGain", "(F)V", false);
        });

        String where = loader + " " + host;
        MethodInsnNode setGain = call(body, LISTENER, "setGain");
        MethodInsnNode noOp = call(body, ITEM_SAFETY, "noOp");
        if (bridged) {
            assertNull(setGain, where + " must not call the removed Listener.setGain");
            assertNotNull(noOp, where + " must call the no-op instead");
            assertEquals(INVOKESTATIC, noOp.getOpcode(), where + ": the no-op is static");
            assertEquals("(Ljava/lang/Object;F)V", noOp.desc, where + ": the no-op takes the listener and the gain");
        } else {
            assertNotNull(setGain, where + " keeps Listener.setGain");
            assertEquals("(F)V", setGain.desc, where);
            assertNull(noOp, where + " must not route the call through the no-op");
        }
    }

    @ParameterizedTest(name = "{0} {1}: saySystemNow = {2}")
    @DisplayName("GameNarrator.sayNow(Component) becomes saySystemNow from 1.21.6")
    @CsvSource({
        "neoforge, 1.21.5, false",
        "neoforge, 1.21.6, true",
        "neoforge, 26.1,   true",
        "forge,    1.21.5, false",
        "forge,    1.21.6, true",
        "forge,    26.1,   true",
    })
    void narratorFrom1216(String loader, String host, boolean bridged) {
        loadShimsFor(loader, host);
        List<AbstractInsnNode> body = transformCall("(L" + NARRATOR + ";L" + COMPONENT + ";)V", mv -> {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitMethodInsn(INVOKEVIRTUAL, NARRATOR, "sayNow", "(L" + COMPONENT + ";)V", false);
        });

        String expected = bridged ? "saySystemNow" : "sayNow";
        assertNotNull(call(body, NARRATOR, expected), loader + " " + host + " must call " + expected);
    }

    @ParameterizedTest(name = "{0} {1}: double form = {2}")
    @DisplayName("Mth.sin and Mth.cos take a double from 1.21.11")
    @CsvSource({
        "neoforge, 1.21.10, false",
        "neoforge, 1.21.11, true",
        "neoforge, 26.1,    true",
        "forge,    1.21.10, false",
        "forge,    1.21.11, true",
    })
    void mthTrigFrom12111(String loader, String host, boolean bridged) {
        loadShimsFor(loader, host);
        assertTrigCalls("net/minecraft/util/Mth", "sin", "cos", bridged, loader + " " + host);
    }

    @ParameterizedTest(name = "fabric {0}: double form = {1}")
    @DisplayName("Fabric 1.21.11 gets the same widening under the intermediary names")
    @CsvSource({
        "1.21.10, false",
        "1.21.11, true",
    })
    void fabricIntermediaryTrigFrom12111(String host, boolean bridged) {
        loadShimsFor("fabric", host);
        assertTrigCalls("net/minecraft/class_3532", "method_15374", "method_15362", bridged, "fabric " + host);
    }

    @Test
    @DisplayName("the intermediary form is not registered on 26.x, where the remap reaches the Mojang form")
    void fabricIntermediaryTrigLeaves26xAlone() {
        RetromodVersion.TARGET_MC_VERSION = "26.1";
        new Fabric_1_21_10_to_1_21_11().registerRedirects(transformer);
        assertTrigCalls("net/minecraft/class_3532", "method_15374", "method_15362", false, "fabric 26.1");
    }

    @ParameterizedTest(name = "{0} {1}: factory = {2}")
    @DisplayName("new ClickEvent and new HoverEvent go through the factory from 1.21.5")
    @CsvSource({
        "neoforge, 1.21.4, false",
        "neoforge, 1.21.5, true",
        "neoforge, 26.1,   true",
        "forge,    1.21.4, false",
        "forge,    1.21.5, true",
    })
    void textEventsFrom1215(String loader, String host, boolean bridged) {
        loadShimsFor(loader, host);
        String clickInit = "(L" + CLICK + "$Action;Ljava/lang/String;)V";
        String hoverInit = "(L" + HOVER + "$Action;Ljava/lang/Object;)V";
        List<AbstractInsnNode> body = transformCall(
                "(L" + CLICK + "$Action;L" + HOVER + "$Action;Ljava/lang/Object;)V", mv -> {
            mv.visitTypeInsn(NEW, CLICK);
            mv.visitInsn(DUP);
            mv.visitVarInsn(ALOAD, 0);
            mv.visitLdcInsn("https://example.org");
            mv.visitMethodInsn(INVOKESPECIAL, CLICK, "<init>", clickInit, false);
            mv.visitInsn(POP);
            mv.visitTypeInsn(NEW, HOVER);
            mv.visitInsn(DUP);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitVarInsn(ALOAD, 2);
            mv.visitMethodInsn(INVOKESPECIAL, HOVER, "<init>", hoverInit, false);
            mv.visitInsn(POP);
        });

        String where = loader + " " + host;
        if (bridged) {
            assertNotNull(call(body, TEXT_EVENTS, "clickEvent"), where + " must build the click event in the factory");
            assertNotNull(call(body, TEXT_EVENTS, "hoverEvent"), where + " must build the hover event in the factory");
            assertFalse(hasType(body, NEW, CLICK) || hasType(body, NEW, HOVER), where + " must drop the NEW");
            assertTrue(hasType(body, CHECKCAST, CLICK) && hasType(body, CHECKCAST, HOVER),
                    where + " must cast the factory result back to the event type");
            assertTrue(transformer.getSyntheticClasses().containsKey(TEXT_EVENTS), where + " must embed the factory");
        } else {
            MethodInsnNode click = call(body, CLICK, "<init>");
            MethodInsnNode hover = call(body, HOVER, "<init>");
            assertTrue(click != null && click.desc.equals(clickInit), where + " keeps the ClickEvent constructor");
            assertTrue(hover != null && hover.desc.equals(hoverInit), where + " keeps the HoverEvent constructor");
        }
    }

    @ParameterizedTest(name = "{0} {1}: keySet = {2}")
    @DisplayName("CompoundTag.getAllKeys becomes keySet from 1.21.5, and on 26.x too")
    @CsvSource({
        "neoforge, 1.21.4, false",
        "neoforge, 1.21.5, true",
        "neoforge, 26.1,   true",
        "forge,    1.21.4, false",
        "forge,    1.21.5, true",
        "forge,    26.1,   true",
    })
    void allKeysFrom1215(String loader, String host, boolean bridged) {
        loadShimsFor(loader, host);
        List<AbstractInsnNode> body = transformCall("(L" + COMPOUND + ";)V", mv -> {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKEVIRTUAL, COMPOUND, "getAllKeys", "()Ljava/util/Set;", false);
            mv.visitInsn(POP);
        });

        String expected = bridged ? "keySet" : "getAllKeys";
        MethodInsnNode keys = call(body, COMPOUND, expected);
        assertNotNull(keys, loader + " " + host + " must call " + expected);
        assertEquals("()Ljava/util/Set;", keys.desc);
    }

    @ParameterizedTest(name = "{0} {1}: unwrapped = {2}, owner {3}")
    @DisplayName("Util's executor getters unwrap the TracingExecutor from 1.21.2, under the host's Util")
    @CsvSource({
        "neoforge, 1.21.1,  false, net/minecraft/Util",
        "neoforge, 1.21.2,  true,  net/minecraft/Util",
        "neoforge, 1.21.10, true,  net/minecraft/Util",
        "neoforge, 1.21.11, true,  net/minecraft/util/Util",
        "neoforge, 26.1,    true,  net/minecraft/util/Util",
        "forge,    1.21.1,  false, net/minecraft/Util",
        "forge,    1.21.2,  true,  net/minecraft/Util",
        "forge,    1.21.11, true,  net/minecraft/util/Util",
    })
    void utilExecutorsFrom1212(String loader, String host, boolean bridged, String util) {
        loadShimsFor(loader, host);
        // The class-move pass, not a shim, moves Util on a host that has util/Util.
        if (util.endsWith("/util/Util")) {
            transformer.registerClassRedirect("net/minecraft/Util", util);
        }
        List<AbstractInsnNode> body = transformCall("()V", mv -> {
            for (String pool : new String[]{"backgroundExecutor", "ioPool", "nonCriticalIoPool"}) {
                mv.visitMethodInsn(INVOKESTATIC, "net/minecraft/Util", pool, EXECUTOR, false);
                mv.visitInsn(POP);
            }
        });

        String where = loader + " " + host;
        for (String pool : new String[]{"backgroundExecutor", "ioPool", "nonCriticalIoPool"}) {
            MethodInsnNode getter = call(body, util, pool);
            assertNotNull(getter, where + " must call " + util + "." + pool);
            if (bridged) {
                assertEquals("()L" + TRACING + ";", getter.desc, where + ": " + pool + " returns the wrapper");
                MethodInsnNode unwrap = (MethodInsnNode) next(body, getter);
                assertEquals(TRACING + ".service" + EXECUTOR, unwrap.owner + "." + unwrap.name + unwrap.desc,
                        where + ": the wrapper must be unwrapped right after " + pool);
            } else {
                assertEquals(EXECUTOR, getter.desc, where + " keeps the ExecutorService form of " + pool);
            }
        }
    }

    /** Asserts the float sin and cos calls kept or lost their old form, with an F2D in front of the new one. */
    private void assertTrigCalls(String mth, String sin, String cos, boolean bridged, String where) {
        List<AbstractInsnNode> body = transformCall("(F)V", mv -> {
            for (String name : new String[]{sin, cos}) {
                mv.visitVarInsn(FLOAD, 0);
                mv.visitMethodInsn(INVOKESTATIC, mth, name, "(F)F", false);
                mv.visitInsn(POP);
            }
        });
        for (String name : new String[]{sin, cos}) {
            MethodInsnNode trig = call(body, mth, name);
            assertNotNull(trig, where + " must still call " + name);
            assertEquals(bridged ? "(D)F" : "(F)F", trig.desc, where + ": " + name);
            assertEquals(bridged, previous(body, trig).getOpcode() == F2D,
                    where + ": the float must be widened exactly when the double form is called");
        }
    }

    /** Registers what a host of this loader and version would, in the in-game order. */
    private void loadShimsFor(String loader, String host) {
        RetromodVersion.TARGET_MC_VERSION = host;
        McReflect.setForceNeoForge("neoforge".equals(loader));
        List<String> types = switch (loader) {
            case "neoforge" -> List.of("neoforge", "forge", "common");
            case "forge" -> List.of("forge", "common");
            default -> List.of("fabric", "common");
        };
        for (VersionShim shim : ServiceLoader.load(VersionShim.class)) {
            if (types.contains(shim.getModLoaderType()) && ShimRegistry.isAvailableOnHost(shim, host)) {
                shim.registerRedirects(transformer);
            }
        }
    }

    /** Transforms an old mod method with this body and returns its instructions. */
    private List<AbstractInsnNode> transformCall(String desc, Consumer<MethodVisitor> body) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, "test/mod/OldCalls", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "call", desc, null, null);
        mv.visitCode();
        body.accept(mv);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();

        ClassNode out = new ClassNode();
        new ClassReader(transformer.transformClass(cw.toByteArray(), "test/mod/OldCalls")).accept(out, 0);
        List<AbstractInsnNode> insns = new ArrayList<>();
        for (MethodNode method : out.methods) {
            if (method.name.equals("call")) method.instructions.forEach(insns::add);
        }
        return insns;
    }

    private static MethodInsnNode call(List<AbstractInsnNode> body, String owner, String name) {
        for (AbstractInsnNode insn : body) {
            if (insn instanceof MethodInsnNode c && c.owner.equals(owner) && c.name.equals(name)) return c;
        }
        return null;
    }

    private static boolean hasType(List<AbstractInsnNode> body, int opcode, String type) {
        return body.stream().anyMatch(i -> i instanceof TypeInsnNode t && t.getOpcode() == opcode && t.desc.equals(type));
    }

    /** The next real instruction after {@code insn}, skipping labels and line numbers. */
    private static AbstractInsnNode next(List<AbstractInsnNode> body, AbstractInsnNode insn) {
        for (int i = body.indexOf(insn) + 1; i < body.size(); i++) {
            if (body.get(i).getOpcode() >= 0) return body.get(i);
        }
        return null;
    }

    /** The real instruction before {@code insn}, skipping labels and line numbers. */
    private static AbstractInsnNode previous(List<AbstractInsnNode> body, AbstractInsnNode insn) {
        for (int i = body.indexOf(insn) - 1; i >= 0; i--) {
            if (body.get(i).getOpcode() >= 0) return body.get(i);
        }
        return null;
    }
}
