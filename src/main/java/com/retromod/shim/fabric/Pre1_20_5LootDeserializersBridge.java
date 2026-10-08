/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.fabric;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.SyntheticEmbedder;
import com.retromod.shim.fabric.embedded.LegacyLootGson;
import com.retromod.util.HostLibraryNames;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Bridges the Gson loot serializers that 1.20.5 removed, for intermediary-namespace Fabric mods on
 * pre-26.1 hosts.
 *
 * <p>Before 1.20.5 a mod read loot pools and tables from its own data files with a Gson built by
 * {@code Deserializers.createLootTableSerializer()}, {@code createFunctionSerializer()} or
 * {@code createConditionSerializer()}. Minecraft 1.20.5 moved all loot types to codecs and
 * deleted {@code Deserializers}, so such a mod dies with {@code NoClassDefFoundError} as soon as
 * its loot code initializes, usually while the server loads data packs.
 *
 * <p>Each old call returns a Gson builder from {@link LegacyLootGson} whose adapters decode with
 * the modern loot codecs. JSON that only names built-in registry entries reads as before. A
 * reference into a data pack registry, such as an enchantment, fails for that element, because
 * the old API gave the mod no registry access to pass on.
 */
public final class Pre1_20_5LootDeserializersBridge {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod");

    static final String DESERIALIZERS = "net/minecraft/class_5270";
    static final String LOOT_POOL = "net/minecraft/class_55";
    static final String LOOT_POOL_CODEC = "field_45795";
    static final String FACTORY = "com/retromod/generated/LegacyLootDeserializers";
    static final String SUPPORT = "com/retromod/shim/fabric/embedded/LegacyLootGson";

    /** One removed {@code Deserializers} factory and the support method that replaces it. */
    record Factory(String name, String supportMethod) {}

    static final List<Factory> FACTORIES = List.of(
            new Factory("method_27860", "conditionBuilder"),
            new Factory("method_27861", "functionBuilder"),
            new Factory("method_27862", "lootTableBuilder"));

    private Pre1_20_5LootDeserializersBridge() {}

    /** Registers the bridge only when the host lost {@code Deserializers} and has the loot codecs. */
    public static void register(RetromodTransformer transformer) {
        if (ClassResourceInspector.exists(DESERIALIZERS) || !hasLootPoolCodec()) {
            LOGGER.debug("The host still has the Gson loot serializers, or lacks the loot codecs");
            return;
        }
        registerRedirects(transformer);
        LOGGER.debug("Added support for the pre-1.20.5 Gson loot serializers");
    }

    private static boolean hasLootPoolCodec() {
        ClassNode pool = ClassResourceInspector.read(LOOT_POOL);
        return pool != null && pool.fields.stream().anyMatch(field -> field.name.equals(LOOT_POOL_CODEC));
    }

    /** Unconditional registration for offline transforms and transform-shape tests. */
    public static void registerRedirects(RetromodTransformer transformer) {
        SyntheticEmbedder.registerClassResource(transformer, SUPPORT, LegacyLootGson.class);
        transformer.registerSyntheticClass(FACTORY, generateFactory());
        for (Factory factory : FACTORIES) {
            transformer.registerMethodRedirect(DESERIALIZERS, factory.name(), builderDesc(),
                    FACTORY, factory.name(), builderDesc());
        }
    }

    /** {@code ()GsonBuilder} with the game's Gson, which the release jar must not relocate. */
    static String builderDesc() {
        return "()L" + HostLibraryNames.GSON_BUILDER + ";";
    }

    /**
     * Static methods with the old descriptors. The support class returns {@code Object} because it
     * cannot name the game's Gson, so each method casts the builder back for the caller.
     */
    static byte[] generateFactory() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
                FACTORY, null, "java/lang/Object", null);
        for (Factory factory : FACTORIES) {
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                    factory.name(), builderDesc(), null, null);
            mv.visitCode();
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, SUPPORT, factory.supportMethod(),
                    "()Ljava/lang/Object;", false);
            mv.visitTypeInsn(Opcodes.CHECKCAST, HostLibraryNames.GSON_BUILDER);
            mv.visitInsn(Opcodes.ARETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }
}
