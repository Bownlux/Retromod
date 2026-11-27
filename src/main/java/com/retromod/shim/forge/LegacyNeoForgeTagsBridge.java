/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.util.McReflect;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.ClassNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.objectweb.asm.Opcodes.*;

/** Points Forge 1.20.1 {@code Tags} constants at NeoForge's {@code c:} constants, or keeps their {@code forge:} id. */
public final class LegacyNeoForgeTagsBridge {

    static final String FORGE_TAGS = "net/minecraftforge/common/Tags$";
    static final String NEO_TAGS = "net/neoforged/neoforge/common/Tags$";
    static final String ITEM_TAGS = "net/minecraft/tags/ItemTags";
    static final String HELPER = "com/retromod/generated/LegacyNeoForgeTags";
    static final String TAG_KEY = "Lnet/minecraft/tags/TagKey;";
    static final String TABLE = "/retromod/forge-tags-neoforge.tsv";
    /** NeoForge moved its tags to {@code c:} in 1.20.5; the table was generated against 26.3. */
    static final String FIRST_HOST = "1.20.5";

    /** The registry each nested class's tags belong to. */
    private static final Map<String, String> REGISTRIES = Map.of("Items", "ITEM", "Blocks", "BLOCK",
            "Biomes", "BIOME", "Fluids", "FLUID", "EntityTypes", "ENTITY_TYPE");

    /** One table row, as {@code forge-tags-neoforge.tsv} describes it. */
    record Row(String group, String field, String kind, String target, String forgePath, String hostSince) {}

    static final List<Row> ROWS = load();

    private LegacyNeoForgeTagsBridge() {}

    public static void register(RetromodTransformer t) {
        if (!McReflect.isNeoForge() || RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, FIRST_HOST) < 0) return;
        register(t, ClassResourceInspector::read);
    }

    static void register(RetromodTransformer t, Function<String, ClassNode> host) {
        List<Row> kept = new ArrayList<>();
        for (Row row : ROWS) {
            String owner = targetOwner(row);
            // Each constant gets its own redirect, so the old class needs no rename that a later field could miss.
            if (owner != null && hostHas(host, owner, row)) {
                t.registerFieldRedirect(FORGE_TAGS + row.group(), row.field(), TAG_KEY, owner, row.target(), TAG_KEY);
            } else {
                kept.add(row);
            }
        }
        if (kept.isEmpty()) return;
        t.registerSyntheticClass(HELPER, generateHelper(kept, idClass(host)));
        for (Row row : kept) {
            t.registerFieldRedirect(FORGE_TAGS + row.group(), row.field(), TAG_KEY, HELPER, getterName(row), "()" + TAG_KEY);
        }
    }

    private static String targetOwner(Row row) {
        return switch (row.kind()) {
            case "same", "field" -> NEO_TAGS + row.group();
            case "vanilla" -> ITEM_TAGS;
            default -> null;
        };
    }

    /** Offline without host jars the table answers: its own host (26.3), or the row's first host when it names one. */
    private static boolean hostHas(Function<String, ClassNode> host, String owner, Row row) {
        ClassNode node = host.apply(owner);
        if (node != null) return node.fields.stream().anyMatch(f -> f.name.equals(row.target()));
        return row.hostSince() == null
                || RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, row.hostSince()) >= 0;
    }

    /** 1.21.11 renamed ResourceLocation to Identifier, and the getter names the one its host has. */
    static String idClass(Function<String, ClassNode> host) {
        if (host.apply("net/minecraft/resources/Identifier") != null) return "net/minecraft/resources/Identifier";
        if (host.apply("net/minecraft/resources/ResourceLocation") != null) return "net/minecraft/resources/ResourceLocation";
        return RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, "1.21.11") >= 0
                ? "net/minecraft/resources/Identifier" : "net/minecraft/resources/ResourceLocation";
    }

    static String getterName(Row row) {
        return row.group() + "_" + row.field();
    }

    /** One getter per kept constant: {@code TagKey.create(Registries.X, forge:path)}. */
    static byte[] generateHelper(List<Row> kept, String idClass) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, HELPER, null, "java/lang/Object", null);
        for (Row row : kept) {
            MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, getterName(row), "()" + TAG_KEY, null, null);
            mv.visitCode();
            // TagKey.create interns its keys, so every read returns the same key, as the old constant did.
            mv.visitFieldInsn(GETSTATIC, "net/minecraft/core/registries/Registries", REGISTRIES.get(row.group()),
                    "Lnet/minecraft/resources/ResourceKey;");
            mv.visitLdcInsn("forge");
            mv.visitLdcInsn(row.forgePath());
            mv.visitMethodInsn(INVOKESTATIC, idClass, "fromNamespaceAndPath",
                    "(Ljava/lang/String;Ljava/lang/String;)L" + idClass + ";", false);
            mv.visitMethodInsn(INVOKESTATIC, "net/minecraft/tags/TagKey", "create",
                    "(Lnet/minecraft/resources/ResourceKey;L" + idClass + ";)" + TAG_KEY, false);
            mv.visitInsn(ARETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static List<Row> load() {
        List<Row> rows = new ArrayList<>();
        try (InputStream in = LegacyNeoForgeTagsBridge.class.getResourceAsStream(TABLE)) {
            if (in == null) throw new IllegalStateException(TABLE + " is missing from Retromod's jar");
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            for (String line; (line = reader.readLine()) != null; ) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] p = line.split("\t");
                rows.add(new Row(p[0], p[1], p[2], p[3], p[4], p.length > 5 ? p[5] : null));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return List.copyOf(rows);
    }
}
