/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.FuzzyMethodResolver;
import com.retromod.core.MinecraftVersionedApiShim;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.SyntheticEmbedder;
import com.retromod.shim.common.embedded.LegacyRemovedRegistrySupport;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.ClassNode;

import java.util.ArrayList;
import java.util.List;

import static org.objectweb.asm.Opcodes.*;

/** Hands code built before a built-in registry's removal an empty one, so a class that lists every registry still loads. */
public class LegacyRemovedRegistryShim implements MinecraftVersionedApiShim {

    static final String BUILT_IN = "net/minecraft/core/registries/BuiltInRegistries";
    static final String KEYS = "net/minecraft/core/registries/Registries";
    static final String SUPPORT = "com/retromod/shim/common/embedded/LegacyRemovedRegistrySupport";
    static final String GETTERS = "com/retromod/generated/LegacyRemovedRegistries";
    private static final String GETTER = "()Ljava/lang/Object;";

    /**
     * Each built-in registry a host dropped outright, with its id. The schedule, armor material, instrument and
     * variant registries were gone by 1.21.11, most of them to data packs; 26.3 dropped the pot pattern and loot
     * number provider registries. A registry that was renamed instead, such as 26.3's feature types, is not here.
     */
    static final String[][] REMOVED = {
            {"SCHEDULE", "schedule"}, {"ARMOR_MATERIAL", "armor_material"}, {"INSTRUMENT", "instrument"},
            {"CAT_VARIANT", "cat_variant"}, {"FROG_VARIANT", "frog_variant"}, {"DECORATED_POT_PATTERN", "decorated_pot_pattern"},
            {"LOOT_NUMBER_PROVIDER_TYPE", "loot_number_provider_type"}};

    @Override
    public String getShimName() {
        return "Legacy Removed Registries";
    }

    // Each field waits for the host to show it is gone, so the shim may apply from the first host
    // after 1.21.1, where every one was still present.
    @Override
    public String getSourceVersion() {
        return "1.21.1";
    }

    @Override
    public String getTargetVersion() {
        return "1.21.2";
    }

    @Override
    public String getModLoaderType() {
        return "common";
    }

    @Override
    public void registerRedirects(RetromodTransformer transformer) {
        FuzzyMethodResolver index = transformer.getFuzzyResolver();
        List<String[]> registries = new ArrayList<>();
        List<String[]> keys = new ArrayList<>();
        for (String[] removed : REMOVED) {
            if (hostRemoved(BUILT_IN, removed[0], index)) registries.add(removed);
            if (hostRemoved(KEYS, removed[0], index)) keys.add(removed);
        }
        if (registries.isEmpty() && keys.isEmpty()) return;
        SyntheticEmbedder.registerClassResource(transformer, SUPPORT, LegacyRemovedRegistrySupport.class);
        transformer.registerSyntheticClass(GETTERS, generateGetters(registries, keys));
        // Bookshelf builds a codec helper for every built-in registry in one static block; one missing
        // field failed the whole class, and every codec of the mod with it. A stand-in yields to a real
        // bridge for the same field, such as the rebuilt armor material registry on 26.x.
        for (String[] removed : registries) {
            transformer.registerStandInStaticFieldBridge(BUILT_IN, removed[0], GETTERS, removed[0], GETTER);
        }
        for (String[] removed : keys) {
            transformer.registerStandInStaticFieldBridge(KEYS, removed[0], GETTERS, removed[0] + "_KEY", GETTER);
        }
    }

    @Override
    public String[] getShimClasses() {
        return new String[0];
    }

    /** Whether the host has {@code owner} without the static field {@code name}; offline, the indexed jar answers. */
    static boolean hostRemoved(String owner, String name, FuzzyMethodResolver index) {
        ClassNode node = ClassResourceInspector.read(owner);
        if (node != null) return node.fields.stream().noneMatch(field -> field.name.equals(name));
        // A host that cannot be read keeps the field, as the member renames do.
        if (index == null || !index.isIndexed() || !index.hasClass(owner)) return false;
        return !index.hasFieldName(owner, name);
    }

    /** One getter per removed field, each handing the support class the registry's id. */
    static byte[] generateGetters(List<String[]> registries, List<String[]> keys) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, GETTERS, null, "java/lang/Object", null);
        for (String[] removed : registries) getter(cw, removed[0], "registry", removed[1]);
        for (String[] removed : keys) getter(cw, removed[0] + "_KEY", "registryKey", removed[1]);
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void getter(ClassWriter cw, String name, String supportMethod, String path) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, name, GETTER, null, null);
        mv.visitCode();
        mv.visitLdcInsn(path);
        mv.visitMethodInsn(INVOKESTATIC, SUPPORT, supportMethod, "(Ljava/lang/String;)Ljava/lang/Object;", false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }
}
