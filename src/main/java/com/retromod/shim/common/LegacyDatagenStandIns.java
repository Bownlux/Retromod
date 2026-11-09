/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.objectweb.asm.Opcodes.*;

/** Gives removed data-generation classes empty stand-ins, so the classes that name them still load. */
public final class LegacyDatagenStandIns {

    static final String NEOFORGE_TABLE = "/retromod/neoforge-removed-datagen.tsv";
    static final String VANILLA_TABLE = "/retromod/vanilla-removed-datagen.tsv";
    /** Each stand-in keeps its old name under this package; the embedder moves it into the mod's own. */
    static final String ROOT = "com/retromod/generated/datagen/";

    /** One table row: what the class was, what it extended and implemented, and the first host without it. */
    record Row(String kind, String name, String superName, List<String> interfaces, String removedIn) {}

    private LegacyDatagenStandIns() {}

    /** NeoForge 1.21.1's model generators and ExistingFileHelper, which NeoForge 21.4 removed. */
    public static void registerNeoForge(RetromodTransformer t) {
        register(t, NEOFORGE_TABLE);
    }

    /** Vanilla's tag provider classes that 26.2 and earlier releases removed, for every loader, as the target lacks them. */
    public static void registerVanilla(RetromodTransformer t) {
        register(t, VANILLA_TABLE);
    }

    /** Registers a stand-in and a redirect for every class in a table this host lacks. */
    static void register(RetromodTransformer t, String table) {
        // Data generation runs only when a mod is built, so the stand-ins carry no members. A class
        // that runs in the game can still name these types, and the JVM loads a type to check an
        // assignment: Registrate passes a model builder where a model file is expected.
        List<Row> rows = rows(table);
        Set<String> replaced = new HashSet<>();
        for (Row row : rows) {
            // A host that still ships the class keeps it. Offline the host cannot be read, so the
            // version it was removed in decides.
            boolean removedByVersion = row.removedIn() == null
                    || RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, row.removedIn()) >= 0;
            if (removedByVersion && ClassResourceInspector.read(row.name()) == null) replaced.add(row.name());
        }
        for (Row row : rows) {
            if (!replaced.contains(row.name())) continue;
            String standIn = ROOT + row.name();
            if (!t.getSyntheticClasses().containsKey(standIn)) {
                t.registerSyntheticClass(standIn, generate(row, replaced));
            }
            t.registerClassRedirect(row.name(), standIn);
        }
    }

    /** An empty class of the row's kind, extending and implementing the stand-ins of its old supertypes. */
    static byte[] generate(Row row, Set<String> replaced) {
        boolean isInterface = row.kind().equals("interface");
        String superName = isInterface ? "java/lang/Object" : standInOf(row.superName(), replaced);
        String[] interfaces = row.interfaces().stream().map(name -> standInOf(name, replaced)).toArray(String[]::new);
        int access = isInterface ? ACC_PUBLIC | ACC_INTERFACE | ACC_ABSTRACT
                : ACC_PUBLIC | ACC_SUPER | (row.kind().equals("abstract") ? ACC_ABSTRACT : 0);
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, access, ROOT + row.name(), null, superName, interfaces.length == 0 ? null : interfaces);
        if (!isInterface) {
            // A no-argument constructor, so the stand-in is a complete class; subclasses only link to it.
            MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
            init.visitCode();
            init.visitVarInsn(ALOAD, 0);
            init.visitMethodInsn(INVOKESPECIAL, superName, "<init>", "()V", false);
            init.visitInsn(RETURN);
            init.visitMaxs(0, 0);
            init.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static String standInOf(String name, Set<String> replaced) {
        return replaced.contains(name) ? ROOT + name : name;
    }

    static List<Row> rows(String table) {
        List<Row> rows = new ArrayList<>();
        try (InputStream in = LegacyDatagenStandIns.class.getResourceAsStream(table)) {
            if (in == null) return rows;
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] columns = line.split("\t");
                if (columns.length != 4 && columns.length != 5) continue;
                List<String> interfaces = columns[3].equals("-") ? List.of() : List.of(columns[3].split(","));
                rows.add(new Row(columns[0], columns[1], columns[2], interfaces, columns.length == 5 ? columns[4] : null));
            }
        } catch (IOException unreadable) {
            return List.of();
        }
        return rows;
    }
}
