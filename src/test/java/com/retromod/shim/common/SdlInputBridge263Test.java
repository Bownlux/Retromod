/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.VersionShim;
import com.retromod.shim.ShimRegistry;
import com.retromod.shim.fabric.Fabric_26_2_to_26_3;
import com.retromod.shim.forge.Forge_26_2_to_26_3;
import com.retromod.shim.neoforge.NeoForge_26_2_to_26_3;
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
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * Every path a pre-26.3 key code takes into Minecraft is routed through the translating polyfill,
 * and the two 26.3 input changes that crash outright are bridged.
 */
class SdlInputBridge263Test {

    private static final String POLY = "com/retromod/polyfill/minecraft/RetroKeyMapping";
    private static final String CONSTANTS = "com/mojang/blaze3d/platform/InputConstants";
    private static final String TYPE = CONSTANTS + "$Type";
    private static final String KEY_MAPPING = "net/minecraft/client/KeyMapping";
    private static final String CATEGORY = "Lnet/minecraft/client/KeyMapping$Category;";
    private static final String GLFW = "org/lwjgl/glfw/GLFW";

    private RetromodTransformer transformer;
    private String previousTarget;

    @BeforeEach
    void setUp() {
        transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        previousTarget = com.retromod.core.RetromodVersion.TARGET_MC_VERSION;
        com.retromod.core.RetromodVersion.TARGET_MC_VERSION = "26.3";
    }

    @AfterEach
    void tearDown() {
        transformer.clearRedirectsForTesting();
        com.retromod.core.RetromodVersion.TARGET_MC_VERSION = previousTarget;
    }

    @Test
    @DisplayName("literal key codes reach the translating bridge")
    void inputPathsAreBridged() {
        Mc26_2To26_3CoreMoves.register(transformer);
        List<String> out = trace(transformer.transformClass(clientSetup(), "test/mod/Keys"));

        assertTrue(out.contains("field " + TYPE + ".KEYBOARD"),
                "26.3 removed Type.KEYSYM, which crashes the typed constructor: " + out);
        assertTrue(out.contains("call " + POLY + ".isKeyDown"),
                "a literal GLFW code must be translated: " + out);
        assertTrue(out.contains("call " + POLY + ".getOrCreate"), out.toString());
        assertTrue(out.contains("call " + POLY + ".createTypedWithCategory"),
                "the default key code must be translated: " + out);
        assertFalse(out.contains("call " + KEY_MAPPING + ".<init>"),
                "no constructor call may bypass the translation: " + out);
    }

    @Test
    @DisplayName("a code read at runtime is already the host's and is not translated again")
    void runtimeCodesPassThrough() {
        Mc26_2To26_3CoreMoves.register(transformer);
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, "test/mod/Keys", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "held",
                "(Lcom/mojang/blaze3d/platform/Window;L" + TYPE + ";I)Z", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 1);
        mv.visitVarInsn(ILOAD, 2);
        mv.visitMethodInsn(INVOKEVIRTUAL, TYPE, "getOrCreate", "(I)L" + CONSTANTS + "$Key;", false);
        mv.visitInsn(POP);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ILOAD, 2);
        mv.visitMethodInsn(INVOKESTATIC, CONSTANTS, "isKeyDown",
                "(Lcom/mojang/blaze3d/platform/Window;I)Z", false);
        mv.visitInsn(IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();

        List<String> out = trace(transformer.transformClass(cw.toByteArray(), "test/mod/Keys"));
        assertTrue(out.contains("call " + TYPE + ".getOrCreate"),
                "a mouse event's button 1 is the left button; translating it would pick the right one: "
                + out);
        assertTrue(out.contains("call " + POLY + ".isKeyDownUntranslated"),
                "the window is still dropped, but the code passes through: " + out);
    }

    @Test
    @DisplayName("the Fabric keybind factory translates its code too")
    void fabricKeybindFactoryTranslates() throws Exception {
        // On 26.x Fabric the 4-argument constructor goes through KeyBindingShim, not RetroKeyMapping.
        byte[] shim;
        try (var in = getClass().getClassLoader().getResourceAsStream(
                "com/retromod/shim/fabric/embedded/KeyBindingShim.class")) {
            shim = in.readAllBytes();
        }
        ClassNode cn = new ClassNode();
        new ClassReader(shim).accept(cn, 0);
        MethodNode create = cn.methods.stream().filter(m -> m.name.equals("create"))
                .findFirst().orElseThrow();
        boolean translates = false;
        for (AbstractInsnNode insn : create.instructions) {
            if (insn instanceof MethodInsnNode c && c.owner.equals(POLY) && c.name.equals("hostCode")) {
                translates = true;
            }
        }
        assertTrue(translates, "a Fabric keybind would otherwise bind the wrong key on 26.3");
    }

    @Test
    @DisplayName("an old NeoForge conflict-context keybind is rebuilt on 26.x")
    void conflictContextKeybindIsBridged() {
        Common_1_21_11_to_26_1_ClassMoves.registerKeyMappingBridges26x(transformer);
        String desc = "(Ljava/lang/String;Lnet/neoforged/neoforge/client/settings/IKeyConflictContext;"
                + "L" + TYPE + ";ILjava/lang/String;)V";
        List<String> out = trace(transformer.transformClass(constructs(desc, mv -> {
            mv.visitLdcInsn("key.mod.open");
            mv.visitInsn(ACONST_NULL);
            mv.visitInsn(ACONST_NULL);
            mv.visitIntInsn(BIPUSH, 82);
            mv.visitLdcInsn("key.categories.mod");
        }), "test/mod/Keys"));

        assertTrue(out.contains("call " + POLY + ".createConflict"),
                "NeoForge removed the String-category overload at 1.21.9: " + out);
    }

    @Test
    @DisplayName("GLFW polls, which 26.3 no longer ships, reach the host on every loader")
    void glfwPollsAreBridgedOnEveryLoader() {
        List<VersionShim> shims = List.of(new Fabric_26_2_to_26_3(), new NeoForge_26_2_to_26_3(),
                new Forge_26_2_to_26_3());
        for (VersionShim shim : shims) {
            transformer.clearRedirectsForTesting();
            shim.registerRedirects(transformer);
            List<String> out = trace(transformer.transformClass(glfwPolls(), "test/mod/Polls"));
            String loader = shim.getShimName();

            assertTrue(out.stream().noneMatch(s -> s.startsWith("call " + GLFW + ".")),
                    loader + ": a GLFW call fails with NoClassDefFoundError on 26.3: " + out);
            assertTrue(out.contains("call com/mojang/blaze3d/Blaze3D.getTime"),
                    loader + ": glfwGetTime becomes the SDL clock that replaced it: " + out);
            assertTrue(out.contains("call " + POLY + ".glfwGetKey"),
                    loader + ": a literal GLFW key must be translated: " + out);
            assertTrue(out.contains("call " + POLY + ".glfwGetKeyUntranslated"),
                    loader + ": a key read at runtime is the host's already: " + out);
            assertTrue(out.contains("call " + POLY + ".glfwGetMouseButton"),
                    loader + ": a literal GLFW button must be translated: " + out);
            assertTrue(out.contains("call " + POLY + ".glfwGetMouseButtonUntranslated"),
                    loader + ": a button read at runtime is the host's already: " + out);
            assertTrue(out.contains("call " + POLY + ".glfwGetCursorPos"), loader + ": " + out);
        }
    }

    @ParameterizedTest(name = "{0} on {1}")
    @DisplayName("the shim host gate keeps GLFW calls on 26.2 and bridges them on 26.3")
    @CsvSource({"fabric,26.2,false", "fabric,26.3,true", "neoforge,26.2,false", "neoforge,26.3,true",
            "forge,26.2,false", "forge,26.3,true"})
    void hostGateDecidesTheGlfwBridge(String loader, String host, boolean bridged) {
        loadShimsFor(loader, host);
        List<String> out = trace(transformer.transformClass(glfwPolls(), "test/mod/Polls"));

        assertEquals(!bridged, out.contains("call " + GLFW + ".glfwGetTime"),
                loader + " " + host + ": " + out);
        assertEquals(!bridged, out.contains("call " + GLFW + ".glfwGetKey"), out.toString());
        assertEquals(bridged, out.contains("call com/mojang/blaze3d/Blaze3D.getTime"), out.toString());
        assertEquals(bridged, out.stream().anyMatch(s -> s.startsWith("call " + POLY + ".glfw")),
                out.toString());
    }

    /**
     * Register what a host of this version registers, through the same loader filter and
     * {@link ShimRegistry#isAvailableOnHost} gate the entry points apply.
     */
    private void loadShimsFor(String loader, String host) {
        com.retromod.core.RetromodVersion.TARGET_MC_VERSION = host;
        // RetromodNeoForge also takes Forge shims, for Forge mods it runs; RetromodForge does not
        // take NeoForge ones.
        List<String> loaderTypes = switch (loader) {
            case "fabric" -> List.of("fabric", "common");
            case "forge" -> List.of("forge", "common");
            case "neoforge" -> List.of("neoforge", "forge", "common");
            default -> throw new IllegalArgumentException("unknown loader " + loader);
        };
        for (VersionShim shim : ServiceLoader.load(VersionShim.class)) {
            if (!loaderTypes.contains(shim.getModLoaderType())) continue;
            if (ShimRegistry.isAvailableOnHost(shim, host)) shim.registerRedirects(transformer);
        }
    }

    /**
     * The polls a 1.21.x client mod makes, the way Hold My Items times its animations: the clock
     * each frame, a literal key and button, the same with values read at runtime, and the cursor.
     */
    private static byte[] glfwPolls() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, "test/mod/Polls", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "poll", "(JII)V", null, null);
        mv.visitCode();
        mv.visitMethodInsn(INVOKESTATIC, GLFW, "glfwGetTime", "()D", false);
        mv.visitInsn(POP2);
        mv.visitVarInsn(LLOAD, 0);
        mv.visitIntInsn(SIPUSH, 340);
        mv.visitMethodInsn(INVOKESTATIC, GLFW, "glfwGetKey", "(JI)I", false);
        mv.visitInsn(POP);
        mv.visitVarInsn(LLOAD, 0);
        mv.visitVarInsn(ILOAD, 2);
        mv.visitMethodInsn(INVOKESTATIC, GLFW, "glfwGetKey", "(JI)I", false);
        mv.visitInsn(POP);
        mv.visitVarInsn(LLOAD, 0);
        mv.visitInsn(ICONST_0);
        mv.visitMethodInsn(INVOKESTATIC, GLFW, "glfwGetMouseButton", "(JI)I", false);
        mv.visitInsn(POP);
        mv.visitVarInsn(LLOAD, 0);
        mv.visitVarInsn(ILOAD, 3);
        mv.visitMethodInsn(INVOKESTATIC, GLFW, "glfwGetMouseButton", "(JI)I", false);
        mv.visitInsn(POP);
        mv.visitVarInsn(LLOAD, 0);
        mv.visitInsn(ICONST_1);
        mv.visitIntInsn(NEWARRAY, T_DOUBLE);
        mv.visitInsn(ICONST_1);
        mv.visitIntInsn(NEWARRAY, T_DOUBLE);
        mv.visitMethodInsn(INVOKESTATIC, GLFW, "glfwGetCursorPos", "(J[D[D)V", false);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A client setup that uses each input path once, the way a 26.2 mod would. */
    private static byte[] clientSetup() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, "test/mod/Keys", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "setup",
                "(Lcom/mojang/blaze3d/platform/Window;" + CATEGORY + ")V", null, null);
        mv.visitCode();
        mv.visitTypeInsn(NEW, KEY_MAPPING);
        mv.visitInsn(DUP);
        mv.visitLdcInsn("key.mod.open");
        mv.visitFieldInsn(GETSTATIC, TYPE, "KEYSYM", "L" + TYPE + ";");
        mv.visitIntInsn(BIPUSH, 82);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKESPECIAL, KEY_MAPPING, "<init>",
                "(Ljava/lang/String;L" + TYPE + ";I" + CATEGORY + ")V", false);
        mv.visitInsn(POP);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitIntInsn(SIPUSH, 340);
        mv.visitMethodInsn(INVOKESTATIC, CONSTANTS, "isKeyDown",
                "(Lcom/mojang/blaze3d/platform/Window;I)Z", false);
        mv.visitInsn(POP);
        mv.visitFieldInsn(GETSTATIC, TYPE, "KEYSYM", "L" + TYPE + ";");
        mv.visitIntInsn(BIPUSH, 82);
        mv.visitMethodInsn(INVOKEVIRTUAL, TYPE, "getOrCreate", "(I)L" + CONSTANTS + "$Key;", false);
        mv.visitInsn(POP);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A method that runs {@code new KeyMapping(...)} with the given constructor. */
    private static byte[] constructs(String desc, java.util.function.Consumer<MethodVisitor> args) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, "test/mod/Keys", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "setup", "()V", null, null);
        mv.visitCode();
        mv.visitTypeInsn(NEW, KEY_MAPPING);
        mv.visitInsn(DUP);
        args.accept(mv);
        mv.visitMethodInsn(INVOKESPECIAL, KEY_MAPPING, "<init>", desc, false);
        mv.visitInsn(POP);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static List<String> trace(byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        List<String> out = new ArrayList<>();
        for (MethodNode m : cn.methods) {
            for (AbstractInsnNode insn : m.instructions) {
                if (insn instanceof MethodInsnNode c) out.add("call " + c.owner + "." + c.name);
                if (insn instanceof FieldInsnNode f) out.add("field " + f.owner + "." + f.name);
            }
        }
        return out;
    }
}
