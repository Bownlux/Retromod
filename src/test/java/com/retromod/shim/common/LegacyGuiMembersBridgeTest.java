/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Old window, screen click, list, option range and tooltip calls reach what replaced them, only where the host lacks them. */
class LegacyGuiMembersBridgeTest {

    private static final String ROOT = LegacyGuiMembersBridge.ROOT;
    private static final Path LIBRARIES = Path.of(System.getProperty("user.home", ""),
            "Library/Application Support/PrismLauncher/libraries");
    private String savedVersion;

    @AfterEach
    void reset() {
        RetromodTransformer.getInstance().clearRedirectsForTesting();
        if (savedVersion != null) RetromodVersion.TARGET_MC_VERSION = savedVersion;
    }

    @Test
    @DisplayName("offline for 26.3 every old GUI call reaches its rebuilt form")
    void offline263BridgesEveryCall() {
        List<String> refs = transformOldCalls("26.3", name -> null);

        assertTrue(refs.contains(LegacyGuiMembersBridge.WINDOW + ".setGuiScale(I)V"), refs.toString());
        assertTrue(refs.contains(LegacyGuiMembersBridge.WINDOW_CALLS + ".getFramerateLimit(L" + LegacyGuiMembersBridge.WINDOW + ";)I"), refs.toString());
        assertTrue(refs.contains(LegacyGuiMembersBridge.WINDOW_CALLS + ".setFramerateLimit(L" + LegacyGuiMembersBridge.WINDOW + ";I)V"), refs.toString());
        assertTrue(refs.contains(LegacyGuiMembersBridge.WINDOW_CALLS + ".updateVsync(L" + LegacyGuiMembersBridge.WINDOW + ";Z)V"), refs.toString());
        assertTrue(refs.contains(LegacyGuiMembersBridge.MINECRAFT + ".resizeGui()V"), refs.toString());
        assertTrue(refs.contains(LegacyGuiMembersBridge.CLIENT_FLAGS + ".useShaderTransparency()Z"), refs.toString());
        assertTrue(refs.contains(LegacyGuiMembersBridge.SCREEN_MEMBERS + ".handleComponentClicked(L" + LegacyGuiMembersBridge.SCREEN
                + ";Lnet/minecraft/network/chat/Style;)Z"), refs.toString());
        assertTrue(refs.contains(LegacyGuiMembersBridge.SELECTION_LIST + ".getY()I"), refs.toString());
        assertTrue(refs.contains(LegacyGuiMembersBridge.SCREEN_MEMBERS + ".wrapScreenError" + LegacyGuiMembersBridge.WRAP_ERROR), refs.toString());
        assertEquals(3, refs.stream().filter(r -> r.startsWith(LegacyGuiMembersBridge.RANGES + ".xmap(")).count(),
                "the range base and both concrete ranges reach the helper: " + refs);
        assertTrue(refs.contains(LegacyGuiMembersBridge.EXTRACT_CALLS + ".extractTooltipBackground" + LegacyGuiMembersBridge.OLD_BACKGROUND), refs.toString());
        assertTrue(refs.stream().anyMatch(r -> r.startsWith(LegacyGuiMembersBridge.EXTRACT_CALLS + ".getHeight(")), refs.toString());
        assertTrue(refs.stream().anyMatch(r -> r.startsWith(LegacyGuiMembersBridge.EXTRACT_CALLS + ".extractImage(")), refs.toString());
        assertTrue(refs.contains(LegacyGuiMembersBridge.EXTRACT_CALLS + ".extractSlotHighlight" + LegacyGuiMembersBridge.SLOT_HIGHLIGHT), refs.toString());
        assertTrue(refs.stream().anyMatch(r -> r.startsWith(LegacyGuiMembersBridge.BOOLEAN_OPTIONS + ".touchscreen(")), refs.toString());
        assertTrue(RetromodTransformer.getInstance().getSyntheticClasses().containsKey(LegacyGuiMembersBridge.SCREEN_MEMBERS),
                "the rebuilt click handler is embedded");
    }

    @Test
    @DisplayName("on 26.x the rebuilt image button answers a 1.20.1 subclass's super.updateTooltip(), which the host's tooltip holder replaced")
    void imageButtonKeepsUpdateTooltip() {
        savedVersion = RetromodVersion.TARGET_MC_VERSION;
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        LegacyGuiMembersBridge.registerImageButtons(t, null);
        ClassNode rebuilt = new ClassNode();
        new ClassReader(t.getSyntheticClasses().get(LegacyGuiMembersBridge.LEGACY_IMAGE_BUTTON)).accept(rebuilt, 0);
        assertTrue(rebuilt.methods.stream().anyMatch(m -> m.name.equals("updateTooltip") && m.desc.equals("()V")
                && (m.access & Opcodes.ACC_PUBLIC) != 0), "NoChatReports' button calls it as super");
    }

    @Test
    @DisplayName("offline for 26.1 vsync and shader transparency stay, since 26.1 still has both")
    void offline261KeepsVsyncAndTransparency() {
        List<String> refs = transformOldCalls("26.1", name -> null);

        assertTrue(refs.contains(LegacyGuiMembersBridge.WINDOW + ".updateVsync(Z)V"), refs.toString());
        assertTrue(refs.contains(LegacyGuiMembersBridge.MINECRAFT + ".useShaderTransparency()Z"), refs.toString());
        assertTrue(refs.contains(LegacyGuiMembersBridge.MINECRAFT + ".resizeGui()V"), refs.toString());
        assertTrue(refs.contains(LegacyGuiMembersBridge.OPTIONS + ".touchscreen" + LegacyGuiMembersBridge.OPTION), refs.toString());
    }

    @ParameterizedTest(name = "{0}")
    @DisplayName("on each real host only the members it lacks are bridged")
    @CsvSource({
            "1.21.11, net/neoforged/minecraft-client-patched/21.11.42/minecraft-client-patched-21.11.42.jar, false, false, false",
            "26.1, com/mojang/minecraft/26.1.2/minecraft-26.1.2-client.jar, false, true, true",
            "26.2, com/mojang/minecraft/26.2/minecraft-26.2-client.jar, true, true, true",
            "26.3, com/mojang/minecraft/26.3/minecraft-26.3-client.jar, true, true, true",
            "26.3, net/neoforged/minecraft-client-patched/26.3.0.43-beta/minecraft-client-patched-26.3.0.43-beta.jar, true, true, true",
            "26.2, net/minecraftforge/forge/26.2-65.0.0/forge-26.2-65.0.0-client.jar, true, true, true"})
    void realHostsGateEachMember(String version, String jar, boolean noVsync, boolean resized, boolean extractTooltips) throws Exception {
        Path host = LIBRARIES.resolve(jar);
        assumeTrue(Files.isRegularFile(host), "host jar not installed: " + jar);
        List<String> refs;
        try (ZipFile zip = new ZipFile(host.toFile())) {
            refs = transformOldCalls(version, name -> read(zip, name));
        }

        // Every host from 1.21.11 lacks these.
        assertTrue(refs.contains(LegacyGuiMembersBridge.WINDOW + ".setGuiScale(I)V"), refs.toString());
        assertTrue(refs.stream().anyMatch(r -> r.startsWith(LegacyGuiMembersBridge.WINDOW_CALLS + ".getFramerateLimit(")), refs.toString());
        assertTrue(refs.stream().anyMatch(r -> r.startsWith(LegacyGuiMembersBridge.SCREEN_MEMBERS + ".handleComponentClicked(")), refs.toString());
        assertTrue(refs.contains(LegacyGuiMembersBridge.SELECTION_LIST + ".getY()I"), refs.toString());
        assertTrue(refs.stream().anyMatch(r -> r.startsWith(LegacyGuiMembersBridge.RANGES + ".xmap(")), refs.toString());
        // The rest follow what each host removed.
        assertEquals(noVsync, refs.stream().anyMatch(r -> r.startsWith(LegacyGuiMembersBridge.WINDOW_CALLS + ".updateVsync(")), refs.toString());
        assertEquals(noVsync, refs.contains(LegacyGuiMembersBridge.CLIENT_FLAGS + ".useShaderTransparency()Z"), refs.toString());
        assertEquals(noVsync, refs.stream().anyMatch(r -> r.startsWith(LegacyGuiMembersBridge.BOOLEAN_OPTIONS + ".touchscreen(")), refs.toString());
        assertEquals(resized, refs.contains(LegacyGuiMembersBridge.MINECRAFT + ".resizeGui()V"), refs.toString());
        assertEquals(extractTooltips, refs.stream().anyMatch(r -> r.startsWith(LegacyGuiMembersBridge.EXTRACT_CALLS + ".")), refs.toString());
    }

    private List<String> transformOldCalls(String version, Function<String, ClassNode> host) {
        savedVersion = RetromodVersion.TARGET_MC_VERSION;
        RetromodVersion.TARGET_MC_VERSION = version;
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        LegacyGuiMembersBridge.register(t, host);
        return refs(t.transformClass(oldCalls(), "test/gui/Old"));
    }

    /** One call of each old member, the way a 1.20.1 or 1.21.1 mod made it after the 26.1 renames. */
    private static byte[] oldCalls() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/gui/Old", null, "java/lang/Object", null);
        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", "()V", null, null);
        mv.visitCode();
        String window = LegacyGuiMembersBridge.WINDOW;
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitInsn(Opcodes.DCONST_1);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, window, "setGuiScale", "(D)V", false);
        call(mv, Opcodes.INVOKEVIRTUAL, window, "getFramerateLimit", "()I", 1, 0);
        call(mv, Opcodes.INVOKEVIRTUAL, window, "setFramerateLimit", "(I)V", 1, 1);
        call(mv, Opcodes.INVOKEVIRTUAL, window, "updateVsync", "(Z)V", 1, 1);
        call(mv, Opcodes.INVOKEVIRTUAL, LegacyGuiMembersBridge.MINECRAFT, "resizeDisplay", "()V", 1, 0);
        call(mv, Opcodes.INVOKESTATIC, LegacyGuiMembersBridge.MINECRAFT, "useShaderTransparency", "()Z", 0, 0);
        call(mv, Opcodes.INVOKEVIRTUAL, LegacyGuiMembersBridge.SCREEN, "handleComponentClicked", LegacyGuiMembersBridge.STYLE_CLICK, 2, 0);
        call(mv, Opcodes.INVOKEVIRTUAL, LegacyGuiMembersBridge.SELECTION_LIST, "getTop", "()I", 1, 0);
        call(mv, Opcodes.INVOKESTATIC, LegacyGuiMembersBridge.SCREEN, "wrapScreenError", LegacyGuiMembersBridge.WRAP_ERROR, 3, 0);
        call(mv, Opcodes.INVOKEINTERFACE, LegacyGuiMembersBridge.RANGE_BASE, "xmap", LegacyGuiMembersBridge.OLD_XMAP, 3, 0);
        call(mv, Opcodes.INVOKEVIRTUAL, "net/minecraft/client/OptionInstance$IntRange", "xmap", LegacyGuiMembersBridge.OLD_XMAP, 3, 0);
        call(mv, Opcodes.INVOKEVIRTUAL, "net/minecraft/client/OptionInstance$ClampingLazyMaxIntRange", "xmap",
                LegacyGuiMembersBridge.OLD_XMAP, 3, 0);
        call(mv, Opcodes.INVOKESTATIC, LegacyGuiMembersBridge.TOOLTIP_UTIL, "extractTooltipBackground", LegacyGuiMembersBridge.OLD_BACKGROUND, 1, 5);
        call(mv, Opcodes.INVOKEINTERFACE, LegacyGuiMembersBridge.TOOLTIP_COMPONENT, "getHeight", "()I", 1, 0);
        call(mv, Opcodes.INVOKESTATIC, LegacyGuiMembersBridge.CONTAINER_SCREEN, "extractSlotHighlight", LegacyGuiMembersBridge.SLOT_HIGHLIGHT, 1, 3);
        call(mv, Opcodes.INVOKEVIRTUAL, LegacyGuiMembersBridge.OPTIONS, "touchscreen", LegacyGuiMembersBridge.OPTION, 1, 0);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, LegacyGuiMembersBridge.TOOLTIP_COMPONENT, "extractImage",
                LegacyGuiMembersBridge.OLD_IMAGE, true);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(8, 0);
        mv.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    /** Pushes {@code references} nulls and {@code ints} zeros, calls, and pops a non-void result. */
    private static void call(MethodVisitor mv, int opcode, String owner, String name, String desc, int references, int ints) {
        for (int i = 0; i < references; i++) mv.visitInsn(Opcodes.ACONST_NULL);
        for (int i = 0; i < ints; i++) mv.visitInsn(Opcodes.ICONST_0);
        mv.visitMethodInsn(opcode, owner, name, desc, opcode == Opcodes.INVOKEINTERFACE);
        if (!desc.endsWith(")V")) mv.visitInsn(Opcodes.POP);
    }

    private static ClassNode read(ZipFile zip, String name) {
        try {
            var entry = zip.getEntry(name + ".class");
            if (entry == null) return null;
            ClassNode node = new ClassNode();
            try (var in = zip.getInputStream(entry)) {
                new ClassReader(in).accept(node, ClassReader.SKIP_CODE);
            }
            return node;
        } catch (java.io.IOException unreadable) {
            return null;
        }
    }

    private static List<String> refs(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        List<String> refs = new ArrayList<>();
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call) refs.add(call.owner + "." + call.name + call.desc);
            }
        }
        return refs;
    }
}
