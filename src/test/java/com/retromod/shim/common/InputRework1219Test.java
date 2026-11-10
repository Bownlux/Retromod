/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.core.VersionShim;
import com.retromod.shim.ShimRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** Old GUI input calls and overrides reach the 1.21.9 event API on every host that has it, and only there. */
class InputRework1219Test {

    private static final String KEY_EVENT = "net/minecraft/client/input/KeyEvent";
    private static final String CHAR_EVENT = "net/minecraft/client/input/CharacterEvent";
    private static final String MOUSE_EVENT = "net/minecraft/client/input/MouseButtonEvent";
    private static final String MOUSE_INFO = "net/minecraft/client/input/MouseButtonInfo";
    private static final String SCREEN = "net/minecraft/client/gui/screens/Screen";
    private static final String WIDGET = "net/minecraft/client/gui/components/AbstractWidget";
    private static final String EDIT_BOX = "net/minecraft/client/gui/components/EditBox";
    private static final String LISTENER = "net/minecraft/client/gui/components/events/GuiEventListener";
    private static final String INPUT = "com/mojang/blaze3d/platform/InputConstants";
    private static final String KEY_RESULT = "Lcom/mojang/blaze3d/platform/InputConstants$Key;";
    private static final String GUI_CALLS = "test/mod/GuiCalls";
    private static final String OLD_SCREEN = "test/mod/OldScreen";
    private static final String OWN_WIDGET = "test/mod/OwnWidget";

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
    }

    // Call sites on vanilla GUI owners.

    @ParameterizedTest(name = "{0}")
    @CsvSource({"1.21.9", "1.21.10", "1.21.11", "26.1", "26.2", "26.3"})
    void oldGuiCallsRunAgainstTheHostEventApi(String host) throws Exception {
        RetromodVersion.TARGET_MC_VERSION = host;
        boolean codepointOnly = RetromodVersion.compareMcVersions(host, "26.1") >= 0;
        String scanName = RetromodVersion.compareMcVersions(host, "26.3") >= 0 ? "keycode" : "scancode";
        ClassLoader loader = loader(LegacyInputEventCallAdapter.apply(guiCalls()), codepointOnly, scanName);
        Class<?> calls = loader.loadClass(dotted(GUI_CALLS));
        Class<?> editBox = loader.loadClass(dotted(EDIT_BOX));
        Class<?> listener = loader.loadClass(dotted(LISTENER));
        Object box = editBox.getConstructor().newInstance();

        // EditBox.keyPressed(III)Z gets all three values through a KeyEvent.
        assertEquals(true, calls.getMethod("typeKey", editBox, int.class, int.class, int.class)
                .invoke(null, box, 65, 30, 2));
        assertEquals(List.of(65, 30, 2), ints(editBox, "lastKey", "lastScan", "lastMods"));

        // EditBox.charTyped(CI)Z keeps the modifier mask where the host event still has one.
        assertEquals(true, calls.getMethod("typeChar", editBox, char.class, int.class)
                .invoke(null, box, 'é', 4));
        assertEquals(List.of(0xe9, codepointOnly ? -1 : 4), ints(editBox, "lastChar", "lastCharMods"));

        // GuiEventListener.mouseDragged(DDIDD)Z keeps the drag deltas after the event.
        assertEquals(true, calls.getMethod("drag", listener, double.class, double.class, int.class,
                double.class, double.class).invoke(null, box, 3.5, 4.5, 1, 0.25, -0.5));
        assertEquals(List.of(3.5, 4.5, 0.25, -0.5),
                doubles(editBox, "lastX", "lastY", "lastDragX", "lastDragY"));
        assertEquals(List.of(1), ints(editBox, "lastButton"));

        // InputConstants.getKey(II) looks the key up through a KeyEvent with no modifiers.
        calls.getMethod("lookup", int.class, int.class).invoke(null, 81, 16);
        Class<?> input = loader.loadClass(dotted(INPUT));
        assertEquals(List.of(81, 16, 0), ints(input, "lastKey", "lastScan", "lastMods"));
    }

    @Test
    void mouseAndInterfaceCallsKeepTheirOpcodeAndOwner() {
        RetromodVersion.TARGET_MC_VERSION = "1.21.9";
        Map<String, MethodInsnNode> out = firstCalls(LegacyInputEventCallAdapter.apply(listenerCalls()));

        MethodInsnNode release = out.get("release");
        assertEquals(INVOKEVIRTUAL, release.getOpcode());
        assertEquals(SCREEN, release.owner);
        assertEquals("(L" + MOUSE_EVENT + ";)Z", release.desc, "Screen.mouseReleased(DDI)Z is gone from 1.21.9");

        MethodInsnNode click = out.get("superClick");
        assertEquals(INVOKESPECIAL, click.getOpcode(), "a super call must stay a super call");
        assertEquals(WIDGET, click.owner);
        assertEquals("(L" + MOUSE_EVENT + ";Z)Z", click.desc);

        MethodInsnNode keyUp = out.get("keyUp");
        assertEquals(INVOKEINTERFACE, keyUp.getOpcode());
        assertTrue(keyUp.itf, "an interface call must stay an interface call");
        assertEquals("(L" + KEY_EVENT + ";)Z", keyUp.desc);

        MethodInsnNode own = out.get("own");
        assertEquals(OWN_WIDGET, own.owner);
        assertEquals("(III)Z", own.desc, "a mod's own keyPressed(III)Z still exists and must stay");
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"1.21.1", "1.21.5", "1.21.8"})
    void hostsBefore1219KeepEveryOldCall(String host) {
        RetromodVersion.TARGET_MC_VERSION = host;
        byte[] calls = guiCalls();
        assertSame(calls, LegacyInputEventCallAdapter.apply(calls), host + " still has the primitive forms");
        byte[] listeners = listenerCalls();
        assertSame(listeners, LegacyInputEventCallAdapter.apply(listeners));
    }

    // Overrides on a mod's own screen.

    @ParameterizedTest(name = "{0}")
    @CsvSource({"1.21.9,scancode", "1.21.11,scancode", "26.2,scancode", "26.3,keycode"})
    void oldScreenOverrideAnswersTheHostKeyEvent(String host, String scanName) throws Exception {
        RetromodVersion.TARGET_MC_VERSION = host;
        byte[] screen = LegacyGuiOverrideAdapter.apply(oldScreen());
        ClassLoader loader = loader(screen, true, scanName);
        Class<?> keyEvent = loader.loadClass(dotted(KEY_EVENT));
        Class<?> screenClass = loader.loadClass(dotted(OLD_SCREEN));
        Object instance = screenClass.getConstructor().newInstance();

        // The host calls keyPressed(KeyEvent); the bridge hands its values to the old override.
        Object event = keyEvent.getConstructor(int.class, int.class, int.class).newInstance(256, 1, 8);
        assertEquals(true, screenClass.getMethod("keyPressed", keyEvent).invoke(instance, event));
        assertEquals(List.of(256, 1, 8), ints(screenClass, "lastKey", "lastScan", "lastMods"));
        assertTrue(hasMethod(screen, "mouseClicked", "(L" + MOUSE_EVENT + ";Z)Z"));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"1.21.8", "1.21.1"})
    void oldScreenOverrideStaysAloneBefore1219(String host) {
        RetromodVersion.TARGET_MC_VERSION = host;
        byte[] screen = oldScreen();
        assertSame(screen, LegacyGuiOverrideAdapter.apply(screen));
    }

    @ParameterizedTest(name = "{0} renames render: {1}")
    @CsvSource({"1.21.9,false", "1.21.11,false", "26.1,true", "26.3,true"})
    void renderRenameStillWaitsFor261(String host, boolean renamed) {
        RetromodVersion.TARGET_MC_VERSION = host;
        byte[] screen = LegacyGuiOverrideAdapter.apply(oldScreen());
        String render = "(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V";
        assertEquals(renamed, hasMethod(screen, "extractRenderState", render), host);
        assertEquals(!renamed, hasMethod(screen, "render", render), host);
    }

    // Redirects the version shims register.

    @ParameterizedTest(name = "{0} on {1}")
    @CsvSource({"forge,1.21.8,false", "forge,1.21.9,true", "forge,1.21.11,true", "forge,26.3,true",
            "neoforge,1.21.8,false", "neoforge,1.21.9,true", "neoforge,1.21.10,true", "neoforge,26.3,true"})
    void modifierChecksAndWindowKeyPollFollowTheHost(String loader, String host, boolean bridged) {
        loadShimsFor(loader, host);
        List<String> out = trace(transformer.transformClass(keyPolls(), "test/mod/Polls"));

        for (String check : new String[]{"hasShiftDown", "hasControlDown", "hasAltDown"}) {
            assertEquals(!bridged, out.contains("static " + SCREEN + "." + check), host + ": " + out);
            assertEquals(bridged, out.contains("virtual net/minecraft/client/Minecraft." + check),
                    host + ": " + out);
        }
        assertEquals(bridged, out.contains("static net/minecraft/client/Minecraft.getInstance"), out.toString());
        assertEquals(!bridged, out.contains("static " + INPUT + ".isKeyDown"), host + ": " + out);
        assertEquals(bridged, out.contains("static com/retromod/polyfill/minecraft/LegacyGuiCalls.isKeyDown"),
                host + ": " + out);
    }

    @ParameterizedTest(name = "{0} on {1}")
    @CsvSource({"forge,1.21.1,false,false", "forge,1.21.2,true,false", "forge,1.21.4,true,false",
            "forge,1.21.5,true,true", "forge,26.3,true,true", "neoforge,1.21.1,false,false",
            "neoforge,1.21.2,true,false", "neoforge,1.21.5,true,true", "neoforge,26.3,true,true"})
    void interactionChecksFollowTheVersionThatRemovedThem(String loader, String host,
            boolean swing, boolean infinite) {
        loadShimsFor(loader, host);
        List<String> out = trace(transformer.transformClass(interactionCalls(), "test/mod/Interact"));
        String helper = "static " + LegacyClientInteractionSynthetic.INTERNAL + ".";

        assertEquals(swing, out.contains(helper + "shouldSwing"), host + ": " + out);
        assertEquals(!swing, out.contains("virtual net/minecraft/world/InteractionResult.shouldSwing"),
                out.toString());
        assertEquals(infinite, out.contains(helper + "hasInfiniteItems"), host + ": " + out);
        assertEquals(!infinite, out.contains(
                "virtual net/minecraft/client/multiplayer/MultiPlayerGameMode.hasInfiniteItems"), out.toString());
        // displayClientMessage changed in 26.1, so 1.21.x hosts keep it.
        boolean message = RetromodVersion.compareMcVersions(host, "26.1") >= 0;
        assertEquals(message, out.contains(helper + "displayClientMessage"), host + ": " + out);
    }

    @Test
    void interactionHelperLoadsWithoutClientOnlyClasses() throws Exception {
        // A dedicated server has no LocalPlayer, and shouldSwing is reached from common code.
        byte[] helper = LegacyClientInteractionSynthetic.generate();
        Map<String, byte[]> definitions = Map.of(dotted(LegacyClientInteractionSynthetic.INTERNAL), helper);
        ClassLoader loader = new BytesLoader(definitions);
        assertDoesNotThrow(() -> Class.forName(dotted(LegacyClientInteractionSynthetic.INTERNAL), true, loader),
                "verifying the helper must not need any Minecraft class");
    }

    // Fixtures: a mod's calls and screen, and the host classes they run against.

    private static byte[] guiCalls() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, GUI_CALLS, null, "java/lang/Object", null);
        staticMethod(cw, "typeKey", "(L" + EDIT_BOX + ";III)Z", mv -> {
            loadArgs(mv, "(L" + EDIT_BOX + ";III)Z", true);
            mv.visitMethodInsn(INVOKEVIRTUAL, EDIT_BOX, "keyPressed", "(III)Z", false);
            mv.visitInsn(IRETURN);
        });
        staticMethod(cw, "typeChar", "(L" + EDIT_BOX + ";CI)Z", mv -> {
            loadArgs(mv, "(L" + EDIT_BOX + ";CI)Z", true);
            mv.visitMethodInsn(INVOKEVIRTUAL, EDIT_BOX, "charTyped", "(CI)Z", false);
            mv.visitInsn(IRETURN);
        });
        staticMethod(cw, "drag", "(L" + LISTENER + ";DDIDD)Z", mv -> {
            loadArgs(mv, "(L" + LISTENER + ";DDIDD)Z", true);
            mv.visitMethodInsn(INVOKEINTERFACE, LISTENER, "mouseDragged", "(DDIDD)Z", true);
            mv.visitInsn(IRETURN);
        });
        staticMethod(cw, "lookup", "(II)Ljava/lang/Object;", mv -> {
            loadArgs(mv, "(II)V", true);
            mv.visitMethodInsn(INVOKESTATIC, INPUT, "getKey", "(II)" + KEY_RESULT, false);
            mv.visitInsn(ARETURN);
        });
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] listenerCalls() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, "test/mod/Listeners", null, WIDGET, null);
        staticMethod(cw, "release", "(L" + SCREEN + ";DDI)Z", mv -> {
            loadArgs(mv, "(L" + SCREEN + ";DDI)Z", true);
            mv.visitMethodInsn(INVOKEVIRTUAL, SCREEN, "mouseReleased", "(DDI)Z", false);
            mv.visitInsn(IRETURN);
        });
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "superClick", "(DDI)Z", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        loadArgs(mv, "(DDI)Z", false);
        mv.visitMethodInsn(INVOKESPECIAL, WIDGET, "mouseClicked", "(DDI)Z", false);
        mv.visitInsn(IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        staticMethod(cw, "keyUp", "(L" + LISTENER + ";III)Z", m -> {
            loadArgs(m, "(L" + LISTENER + ";III)Z", true);
            m.visitMethodInsn(INVOKEINTERFACE, LISTENER, "keyReleased", "(III)Z", true);
            m.visitInsn(IRETURN);
        });
        staticMethod(cw, "own", "(L" + OWN_WIDGET + ";III)Z", m -> {
            loadArgs(m, "(L" + OWN_WIDGET + ";III)Z", true);
            m.visitMethodInsn(INVOKEVIRTUAL, OWN_WIDGET, "keyPressed", "(III)Z", false);
            m.visitInsn(IRETURN);
        });
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A 1.21.8 screen that overrides the primitive key and mouse methods and an extract-named render. */
    private static byte[] oldScreen() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, OLD_SCREEN, null, SCREEN, null);
        for (String name : new String[]{"lastKey", "lastScan", "lastMods"}) {
            cw.visitField(ACC_PUBLIC | ACC_STATIC, name, "I", null, null).visitEnd();
        }
        constructor(cw, SCREEN);
        MethodVisitor key = cw.visitMethod(ACC_PUBLIC, "keyPressed", "(III)Z", null, null);
        key.visitCode();
        String[] fields = {"lastKey", "lastScan", "lastMods"};
        for (int i = 0; i < 3; i++) {
            key.visitVarInsn(ILOAD, i + 1);
            key.visitFieldInsn(PUTSTATIC, OLD_SCREEN, fields[i], "I");
        }
        key.visitInsn(ICONST_1);
        key.visitInsn(IRETURN);
        key.visitMaxs(0, 0);
        key.visitEnd();
        returnsTrue(cw, "mouseClicked", "(DDI)Z");
        MethodVisitor render = cw.visitMethod(ACC_PUBLIC, "render",
                "(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V", null, null);
        render.visitCode();
        render.visitInsn(RETURN);
        render.visitMaxs(0, 0);
        render.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] keyPolls() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, "test/mod/Polls", null, "java/lang/Object", null);
        staticMethod(cw, "poll", "(JI)V", mv -> {
            for (String check : new String[]{"hasShiftDown", "hasControlDown", "hasAltDown"}) {
                mv.visitMethodInsn(INVOKESTATIC, SCREEN, check, "()Z", false);
                mv.visitInsn(POP);
            }
            mv.visitVarInsn(LLOAD, 0);
            mv.visitVarInsn(ILOAD, 2);
            mv.visitMethodInsn(INVOKESTATIC, INPUT, "isKeyDown", "(JI)Z", false);
            mv.visitInsn(POP);
            mv.visitInsn(RETURN);
        });
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] interactionCalls() {
        String result = "net/minecraft/world/InteractionResult";
        String gameMode = "net/minecraft/client/multiplayer/MultiPlayerGameMode";
        String player = "net/minecraft/world/entity/player/Player";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, "test/mod/Interact", null, "java/lang/Object", null);
        staticMethod(cw, "use", "(L" + result + ";L" + gameMode + ";L" + player
                + ";Lnet/minecraft/network/chat/Component;)V", mv -> {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKEVIRTUAL, result, "shouldSwing", "()Z", false);
            mv.visitInsn(POP);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitMethodInsn(INVOKEVIRTUAL, gameMode, "hasInfiniteItems", "()Z", false);
            mv.visitInsn(POP);
            mv.visitVarInsn(ALOAD, 2);
            mv.visitVarInsn(ALOAD, 3);
            mv.visitInsn(ICONST_1);
            mv.visitMethodInsn(INVOKEVIRTUAL, player, "displayClientMessage",
                    "(Lnet/minecraft/network/chat/Component;Z)V", false);
            mv.visitInsn(RETURN);
        });
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** The host classes the rewritten calls link against, in the shape the host version has. */
    private static ClassLoader loader(byte[] modClass, boolean codepointOnly, String scanName) {
        Map<String, byte[]> definitions = new HashMap<>();
        definitions.put(dotted(new ClassReader(modClass).getClassName()), modClass);
        definitions.put(dotted(KEY_EVENT), intRecord(KEY_EVENT, "key", scanName, "modifiers"));
        definitions.put(dotted(CHAR_EVENT), codepointOnly
                ? intRecord(CHAR_EVENT, "codepoint") : intRecord(CHAR_EVENT, "codepoint", "modifiers"));
        definitions.put(dotted(MOUSE_INFO), intRecord(MOUSE_INFO, "button", "modifiers"));
        definitions.put(dotted(MOUSE_EVENT), mouseEvent());
        definitions.put(dotted(LISTENER), listener());
        definitions.put(dotted(EDIT_BOX), editBox(codepointOnly, scanName));
        definitions.put(dotted(INPUT), inputConstants(scanName));
        definitions.put(dotted(SCREEN), plainClass(SCREEN));
        // Reflection resolves every public signature, the old screen's render included.
        definitions.put("net.minecraft.client.gui.GuiGraphicsExtractor",
                plainClass("net/minecraft/client/gui/GuiGraphicsExtractor"));
        return new BytesLoader(definitions);
    }

    /** A final class with int components, a canonical constructor, and one accessor per component. */
    private static byte[] intRecord(String name, String... components) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL, name, null, "java/lang/Object", null);
        StringBuilder desc = new StringBuilder("(");
        for (String component : components) {
            cw.visitField(ACC_PRIVATE | ACC_FINAL, component, "I", null, null).visitEnd();
            desc.append('I');
        }
        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", desc + ")V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        for (int i = 0; i < components.length; i++) {
            init.visitVarInsn(ALOAD, 0);
            init.visitVarInsn(ILOAD, i + 1);
            init.visitFieldInsn(PUTFIELD, name, components[i], "I");
        }
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        for (String component : components) {
            MethodVisitor get = cw.visitMethod(ACC_PUBLIC, component, "()I", null, null);
            get.visitCode();
            get.visitVarInsn(ALOAD, 0);
            get.visitFieldInsn(GETFIELD, name, component, "I");
            get.visitInsn(IRETURN);
            get.visitMaxs(0, 0);
            get.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] mouseEvent() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL, MOUSE_EVENT, null, "java/lang/Object", null);
        cw.visitField(ACC_PRIVATE | ACC_FINAL, "x", "D", null, null).visitEnd();
        cw.visitField(ACC_PRIVATE | ACC_FINAL, "y", "D", null, null).visitEnd();
        cw.visitField(ACC_PRIVATE | ACC_FINAL, "info", "L" + MOUSE_INFO + ";", null, null).visitEnd();
        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "(DDL" + MOUSE_INFO + ";)V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitVarInsn(ALOAD, 0);
        init.visitVarInsn(DLOAD, 1);
        init.visitFieldInsn(PUTFIELD, MOUSE_EVENT, "x", "D");
        init.visitVarInsn(ALOAD, 0);
        init.visitVarInsn(DLOAD, 3);
        init.visitFieldInsn(PUTFIELD, MOUSE_EVENT, "y", "D");
        init.visitVarInsn(ALOAD, 0);
        init.visitVarInsn(ALOAD, 5);
        init.visitFieldInsn(PUTFIELD, MOUSE_EVENT, "info", "L" + MOUSE_INFO + ";");
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        for (String axis : new String[]{"x", "y"}) {
            MethodVisitor get = cw.visitMethod(ACC_PUBLIC, axis, "()D", null, null);
            get.visitCode();
            get.visitVarInsn(ALOAD, 0);
            get.visitFieldInsn(GETFIELD, MOUSE_EVENT, axis, "D");
            get.visitInsn(DRETURN);
            get.visitMaxs(0, 0);
            get.visitEnd();
        }
        MethodVisitor button = cw.visitMethod(ACC_PUBLIC, "button", "()I", null, null);
        button.visitCode();
        button.visitVarInsn(ALOAD, 0);
        button.visitFieldInsn(GETFIELD, MOUSE_EVENT, "info", "L" + MOUSE_INFO + ";");
        button.visitMethodInsn(INVOKEVIRTUAL, MOUSE_INFO, "button", "()I", false);
        button.visitInsn(IRETURN);
        button.visitMaxs(0, 0);
        button.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** GuiEventListener whose default mouseDragged records into EditBox. */
    private static byte[] listener() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_INTERFACE | ACC_ABSTRACT, LISTENER, null, "java/lang/Object", null);
        MethodVisitor drag = cw.visitMethod(ACC_PUBLIC, "mouseDragged",
                "(L" + MOUSE_EVENT + ";DD)Z", null, null);
        drag.visitCode();
        for (String[] read : new String[][]{{"x", "lastX"}, {"y", "lastY"}}) {
            drag.visitVarInsn(ALOAD, 1);
            drag.visitMethodInsn(INVOKEVIRTUAL, MOUSE_EVENT, read[0], "()D", false);
            drag.visitFieldInsn(PUTSTATIC, EDIT_BOX, read[1], "D");
        }
        drag.visitVarInsn(ALOAD, 1);
        drag.visitMethodInsn(INVOKEVIRTUAL, MOUSE_EVENT, "button", "()I", false);
        drag.visitFieldInsn(PUTSTATIC, EDIT_BOX, "lastButton", "I");
        drag.visitVarInsn(DLOAD, 2);
        drag.visitFieldInsn(PUTSTATIC, EDIT_BOX, "lastDragX", "D");
        drag.visitVarInsn(DLOAD, 4);
        drag.visitFieldInsn(PUTSTATIC, EDIT_BOX, "lastDragY", "D");
        drag.visitInsn(ICONST_1);
        drag.visitInsn(IRETURN);
        drag.visitMaxs(0, 0);
        drag.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** EditBox with the event-taking key and character methods, recording what they receive. */
    private static byte[] editBox(boolean codepointOnly, String scanName) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, EDIT_BOX, null, "java/lang/Object", new String[]{LISTENER});
        for (String name : new String[]{"lastKey", "lastScan", "lastMods", "lastChar", "lastButton"}) {
            cw.visitField(ACC_PUBLIC | ACC_STATIC, name, "I", null, null).visitEnd();
        }
        for (String name : new String[]{"lastX", "lastY", "lastDragX", "lastDragY"}) {
            cw.visitField(ACC_PUBLIC | ACC_STATIC, name, "D", null, null).visitEnd();
        }
        // lastCharMods starts at -1, so a host event without modifiers leaves it visibly untouched.
        cw.visitField(ACC_PUBLIC | ACC_STATIC, "lastCharMods", "I", null, null).visitEnd();
        MethodVisitor clinit = cw.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.visitCode();
        clinit.visitInsn(ICONST_M1);
        clinit.visitFieldInsn(PUTSTATIC, EDIT_BOX, "lastCharMods", "I");
        clinit.visitInsn(RETURN);
        clinit.visitMaxs(0, 0);
        clinit.visitEnd();
        constructor(cw, "java/lang/Object");
        recordInts(cw, EDIT_BOX, "keyPressed", KEY_EVENT, new String[]{"key", scanName, "modifiers"},
                new String[]{"lastKey", "lastScan", "lastMods"}, true);
        recordInts(cw, EDIT_BOX, "charTyped", CHAR_EVENT,
                codepointOnly ? new String[]{"codepoint"} : new String[]{"codepoint", "modifiers"},
                codepointOnly ? new String[]{"lastChar"} : new String[]{"lastChar", "lastCharMods"}, true);
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] inputConstants(String scanName) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, INPUT, null, "java/lang/Object", null);
        for (String name : new String[]{"lastKey", "lastScan", "lastMods"}) {
            cw.visitField(ACC_PUBLIC | ACC_STATIC, name, "I", null, null).visitEnd();
        }
        recordInts(cw, INPUT, "getKey", KEY_EVENT, new String[]{"key", scanName, "modifiers"},
                new String[]{"lastKey", "lastScan", "lastMods"}, false);
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** One method that copies the event's int accessors into static fields. */
    private static void recordInts(ClassWriter cw, String owner, String name, String event,
            String[] accessors, String[] fields, boolean instance) {
        String desc = "(L" + event + ";)" + (instance ? "Z" : KEY_RESULT);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | (instance ? 0 : ACC_STATIC), name, desc, null, null);
        mv.visitCode();
        int eventSlot = instance ? 1 : 0;
        for (int i = 0; i < accessors.length; i++) {
            mv.visitVarInsn(ALOAD, eventSlot);
            mv.visitMethodInsn(INVOKEVIRTUAL, event, accessors[i], "()I", false);
            mv.visitFieldInsn(PUTSTATIC, owner, fields[i], "I");
        }
        if (instance) {
            mv.visitInsn(ICONST_1);
            mv.visitInsn(IRETURN);
        } else {
            mv.visitInsn(ACONST_NULL);
            mv.visitInsn(ARETURN);
        }
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static byte[] plainClass(String name) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, name, null, "java/lang/Object", null);
        constructor(cw, "java/lang/Object");
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void constructor(ClassWriter cw, String superName) {
        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, superName, "<init>", "()V", false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
    }

    private static void returnsTrue(ClassWriter cw, String name, String desc) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, name, desc, null, null);
        mv.visitCode();
        mv.visitInsn(ICONST_1);
        mv.visitInsn(IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static void staticMethod(ClassWriter cw, String name, String desc, Consumer<MethodVisitor> body) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, name, desc, null, null);
        mv.visitCode();
        body.accept(mv);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /** Loads each argument of {@code desc} in order, from slot 0 for a static method or 1 otherwise. */
    private static void loadArgs(MethodVisitor mv, String desc, boolean isStatic) {
        int slot = isStatic ? 0 : 1;
        for (org.objectweb.asm.Type arg : org.objectweb.asm.Type.getArgumentTypes(desc)) {
            mv.visitVarInsn(arg.getOpcode(ILOAD), slot);
            slot += arg.getSize();
        }
    }

    // Reading results.

    private static Map<String, MethodInsnNode> firstCalls(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        Map<String, MethodInsnNode> calls = new HashMap<>();
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call && !"<init>".equals(call.name)) {
                    calls.putIfAbsent(method.name, call);
                }
            }
        }
        return calls;
    }

    private static boolean hasMethod(byte[] bytes, String name, String desc) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node.methods.stream().anyMatch(m -> m.name.equals(name) && m.desc.equals(desc));
    }

    private static List<String> trace(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        List<String> out = new ArrayList<>();
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call) {
                    String kind = call.getOpcode() == INVOKESTATIC ? "static " : "virtual ";
                    out.add(kind + call.owner + "." + call.name);
                }
            }
        }
        return out;
    }

    private static List<Integer> ints(Class<?> owner, String... fields) throws Exception {
        List<Integer> values = new ArrayList<>();
        for (String field : fields) values.add(owner.getField(field).getInt(null));
        return values;
    }

    private static List<Double> doubles(Class<?> owner, String... fields) throws Exception {
        List<Double> values = new ArrayList<>();
        for (String field : fields) values.add(owner.getField(field).getDouble(null));
        return values;
    }

    private static String dotted(String internalName) {
        return internalName.replace('/', '.');
    }

    /** Registers what a host of this version registers, through the entry points' loader filter and gate. */
    private void loadShimsFor(String loader, String host) {
        RetromodVersion.TARGET_MC_VERSION = host;
        // RetromodNeoForge also takes Forge shims, for Forge mods it runs.
        List<String> loaderTypes = loader.equals("forge")
                ? List.of("forge", "common") : List.of("neoforge", "forge", "common");
        for (VersionShim shim : ServiceLoader.load(VersionShim.class)) {
            if (!loaderTypes.contains(shim.getModLoaderType())) continue;
            if (ShimRegistry.isAvailableOnHost(shim, host)) shim.registerRedirects(transformer);
        }
    }

    private static final class BytesLoader extends ClassLoader {
        private final Map<String, byte[]> definitions;

        BytesLoader(Map<String, byte[]> definitions) {
            super(InputRework1219Test.class.getClassLoader());
            this.definitions = definitions;
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            byte[] bytes = definitions.get(name);
            if (bytes == null) throw new ClassNotFoundException(name);
            return defineClass(name, bytes, 0, bytes.length);
        }
    }
}
