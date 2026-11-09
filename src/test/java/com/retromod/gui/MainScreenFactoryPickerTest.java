/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.gui;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Choose Mods reaches the picker, and every message that names retromod-input/ shows its path. */
class MainScreenFactoryPickerTest {

    private static final Path FOLDER = Path.of("/games/minecraft/retromod-input");

    @Test
    void chooseModsConstructsTheScreenDirectly() throws Exception {
        ClassNode factory = classNode("com/retromod/gui/MainScreenFactory");
        boolean constructs = false;
        for (MethodNode method : factory.methods) {
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call) {
                    constructs |= call.owner.equals("com/retromod/gui/RetromodScreen")
                            && call.name.equals("<init>")
                            && call.desc.equals("(Ljava/lang/Object;Ljava/lang/Object;)V");
                    assertFalse(call.owner.equals("java/lang/Class")
                                    && call.name.equals("getConstructor"),
                            "a reflective constructor lookup hides a mismatch until a player clicks");
                }
                if (instruction instanceof LdcInsnNode constant) {
                    assertFalse("java.awt.headless".equals(constant.cst),
                            "Minecraft caches the headless answer, so writing it changes nothing");
                }
            }
        }
        assertTrue(constructs, "Choose Mods must build RetromodScreen(client, parentScreen)");
    }

    @Test
    void theFolderButtonOpensThroughTheSharedOpener() throws Exception {
        List<MethodInsnNode> calls = calls(classNode("com/retromod/gui/MainScreenFactory"));

        assertTrue(calls.stream().anyMatch(call -> call.owner.equals("com/retromod/util/McOpener")
                && call.name.equals("openFolder")), "the folder button must use McOpener");
        assertTrue(calls.stream().anyMatch(call -> call.owner.equals("com/retromod/util/McOpener")
                && call.name.equals("markClientRunning")),
                "opening the screen tells the opener that the client runs");
        assertTrue(calls.stream().noneMatch(call -> call.owner.startsWith("java/awt/")),
                "a launcher sandbox does not route AWT");
    }

    @Test
    void theFolderButtonAlwaysShowsThePath() {
        // Minecraft's openers report no success, so the path shows either way.
        String opened = MainScreenFactory.openedFolderMessage(FOLDER);
        String failed = MainScreenFactory.inputFolderMessage(false, FOLDER);

        assertTrue(opened.contains(FOLDER.toString()) && opened.contains("file manager"), opened);
        assertTrue(failed.contains("could not open") && failed.contains(FOLDER.toString()), failed);
        assertTrue(opened.contains("restart Minecraft") && failed.contains("restart Minecraft"),
                "the player has to restart after adding jars");
    }

    @Test
    void aPickerFailureNamesTheFolder() {
        String picker = MainScreenFactory.inputFolderMessage(true, FOLDER);

        assertTrue(picker.contains("file picker") && picker.contains(FOLDER.toString()), picker);
        assertTrue(picker.contains("restart Minecraft"), picker);
    }

    @Test
    void unreadableJarsAreNamedNextToTheFolder() {
        String one = MainScreenFactory.unreadableMessage(List.of("old mod.jar"), FOLDER);
        assertTrue(one.contains("old mod.jar") && one.contains(FOLDER.toString()), one);
        assertTrue(one.contains("restart Minecraft"), one);

        List<String> many = new ArrayList<>();
        for (int i = 1; i <= 7; i++) many.add("mod" + i + ".jar");
        String list = MainScreenFactory.unreadableMessage(many, FOLDER);
        assertTrue(list.contains("mod5.jar") && !list.contains("mod6.jar"),
                "a long list stays short enough for the message screen: " + list);
        assertTrue(list.contains("and 2 more"), list);
    }

    private static List<MethodInsnNode> calls(ClassNode node) {
        List<MethodInsnNode> calls = new ArrayList<>();
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call) calls.add(call);
            }
        }
        return calls;
    }

    private static ClassNode classNode(String internalName) throws Exception {
        try (InputStream in = MainScreenFactoryPickerTest.class.getClassLoader()
                .getResourceAsStream(internalName + ".class")) {
            ClassNode node = new ClassNode();
            new ClassReader(in).accept(node, ClassReader.SKIP_DEBUG);
            return node;
        }
    }
}
