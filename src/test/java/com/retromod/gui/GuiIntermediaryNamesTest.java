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
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Retromod's screens find Minecraft's GUI by its intermediary names on Fabric before 26.1. */
class GuiIntermediaryNamesTest {

    private static final Path TABLE = Path.of("src/main/resources/intermediary-to-mojang.tsv");
    private static final Pattern INTERMEDIARY =
            Pattern.compile("net\\.minecraft\\.class_\\d+(\\$class_\\d+)*|method_\\d+|field_\\d+");

    // The ids each class needs, so a lookup site that lost its intermediary name fails here.
    private static final Map<String, List<String>> REQUIRED = new LinkedHashMap<>();
    static {
        REQUIRED.put("TitleScreenButtonInjector", List.of("net.minecraft.class_442",
                "net.minecraft.class_437", "net.minecraft.class_2561", "net.minecraft.class_4185",
                "net.minecraft.class_4185$class_4241", "field_22789", "method_37063",
                "method_43470", "method_46430", "method_46434", "method_46431"));
        REQUIRED.put("MainScreenFactory", List.of("net.minecraft.class_437",
                "net.minecraft.class_2561", "net.minecraft.class_4185",
                "net.minecraft.class_4185$class_4241", "net.minecraft.class_310", "field_22789",
                "field_22790", "method_37063", "method_46430", "method_46434", "method_46431",
                "method_1551"));
        REQUIRED.put("ConfigScreenFactory", List.of("net.minecraft.class_437",
                "net.minecraft.class_2561", "net.minecraft.class_4185",
                "net.minecraft.class_4185$class_4241", "field_22789", "field_22790",
                "method_37063", "method_46430", "method_46434", "method_46431"));
        REQUIRED.put("InGameScreenFactory", List.of("net.minecraft.class_437",
                "net.minecraft.class_2561", "net.minecraft.class_410", "net.minecraft.class_403",
                "net.minecraft.class_310", "net.minecraft.class_4185", "method_43470"));
        REQUIRED.put("McScreens", List.of("net.minecraft.class_310", "net.minecraft.class_437",
                "method_1551", "method_1507"));
        REQUIRED.put("RestartPrompt", List.of("net.minecraft.class_310", "method_1551",
                "method_1592"));
        REQUIRED.put("ScreenClassGenerator", List.of("net.minecraft.class_437",
                "net.minecraft.class_2561", "method_25426", "method_25419", "method_29638"));
    }
    private static final String MC_I18N = "com/retromod/util/McI18n";
    private static final List<String> MC_I18N_IDS = List.of("net.minecraft.class_2561",
            "method_43470", "method_43471", "method_43469");
    private static final String MC_OPENER = "com/retromod/util/McOpener";
    private static final List<String> MC_OPENER_IDS = List.of("net.minecraft.class_156",
            "method_668", "method_673");

    @Test
    void everyIntermediaryIdNamesTheMemberBeforeIt() throws IOException {
        Map<String, Set<String>> table = loadTable();
        for (String owner : owners()) {
            for (String[] pair : idsWithPreviousName(owner)) {
                String id = pair[0];
                String expected = pair[1];
                Set<String> mapped = table.get(id.replace('.', '/'));
                assertNotNull(mapped, owner + " uses " + id + ", which the bundled table lacks");
                // A literal and translatable(String) swap shares a descriptor, so names must match.
                assertTrue(expected != null && mapped.contains(expected.replace('.', '/')),
                        owner + " pairs " + id + " (" + mapped + ") with '" + expected + "'");
            }
        }
    }

    @Test
    void everyLookupSiteCarriesItsIntermediaryNames() throws IOException {
        for (Map.Entry<String, List<String>> required : REQUIRED.entrySet()) {
            String owner = "com/retromod/gui/" + required.getKey();
            Set<String> ids = ids(owner);
            for (String id : required.getValue()) {
                assertTrue(ids.contains(id), owner + " has no " + id + " for intermediary hosts");
            }
        }
        Set<String> i18n = ids(MC_I18N);
        for (String id : MC_I18N_IDS) {
            assertTrue(i18n.contains(id), MC_I18N + " has no " + id + " for intermediary hosts");
        }
        Set<String> opener = ids(MC_OPENER);
        for (String id : MC_OPENER_IDS) {
            assertTrue(opener.contains(id), MC_OPENER + " has no " + id + " for intermediary hosts");
        }
    }

    private static List<String> owners() {
        List<String> owners = new ArrayList<>();
        for (String name : REQUIRED.keySet()) owners.add("com/retromod/gui/" + name);
        owners.add(MC_I18N);
        owners.add(MC_OPENER);
        return owners;
    }

    private static Set<String> ids(String internalName) throws IOException {
        Set<String> ids = new HashSet<>();
        for (String[] pair : idsWithPreviousName(internalName)) ids.add(pair[0]);
        return ids;
    }

    /** Each intermediary constant in the class, with the string constant pushed just before it. */
    private static List<String[]> idsWithPreviousName(String internalName) throws IOException {
        ClassNode node = new ClassNode();
        try (InputStream in = GuiIntermediaryNamesTest.class.getClassLoader()
                .getResourceAsStream(internalName + ".class")) {
            assertNotNull(in, internalName + ".class is not on the test classpath");
            new ClassReader(in).accept(node, ClassReader.SKIP_DEBUG);
        }
        List<String[]> ids = new ArrayList<>();
        for (MethodNode method : node.methods) {
            String previous = null;
            for (AbstractInsnNode instruction : method.instructions) {
                if (!(instruction instanceof LdcInsnNode ldc) || !(ldc.cst instanceof String text)) {
                    continue;
                }
                if (INTERMEDIARY.matcher(text).matches()) ids.add(new String[]{text, previous});
                previous = text;
            }
        }
        return ids;
    }

    /** The bundled table, from intermediary id to every name it maps to. */
    private static Map<String, Set<String>> loadTable() throws IOException {
        Map<String, Set<String>> table = new HashMap<>();
        for (String line : Files.readAllLines(TABLE)) {
            String[] columns = line.split("\t");
            if (line.startsWith("#") || columns.length < 3) continue;
            table.computeIfAbsent(columns[1], key -> new HashSet<>()).add(columns[2]);
        }
        return table;
    }
}
