/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.retromod.shim.common.LegacyLootBuilderBridge.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tiny Dragons Reforged, a 1.21.11 Fabric mod, on 26.3 (#336): it adds its eggs to vanilla chests
 * from the Fabric loot table event with {@code LootItem.lootTableItem(egg).setWeight(n)},
 * {@code EmptyLootItem.emptyItem()} and {@code NestedLootTable.lootTableReference(key)}. 26.3
 * rebuilt those builders, so the event handler failed with {@code NoClassDefFoundError} on
 * {@code LootPoolSingletonContainer$Builder} and {@code NoSuchMethodError} on the nested table.
 */
class LegacyLootBuilderBridgeTest {

    private static final String MOD_CLASS = "test/mod/ModLoot";
    private static final String EGG_CLASS = "test/mod/EggLoot";
    private static final String ITEM_LIKE = "net/minecraft/world/level/ItemLike";
    private static final String LOOT_ITEM = ENTRIES + "LootItem";
    private static final String OLD_FACTORY = "(L" + RESOURCE_KEY + ";)L" + SINGLETON_BUILDER + ";";

    private RetromodTransformer transformer;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        register(transformer);
    }

    @AfterEach
    void tearDown() {
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("a nested table key is looked up in the loot event's registries on 26.3")
    void nestedTableComesFromTheEventRegistries() throws Exception {
        ClassLoader host = hostWith(transformed());
        Class<?> mod = host.loadClass(MOD_CLASS.replace('/', '.'));
        Object key = host.loadClass("net.minecraft.resources.ResourceKey")
                .getConstructor(String.class).newInstance("tinydragons:chests/eggs_tiny_dragons");
        Object registries = host.loadClass("test.host.Registries").getConstructor().newInstance();

        Object builder = mod.getMethod("modifyLootTable", key.getClass(), Object.class, Object.class,
                host.loadClass("net.minecraft.core.HolderLookup$Provider")).invoke(null, key, null, null, registries);

        assertEquals("reference(tinydragons:chests/eggs_tiny_dragons)", builder.toString(),
                "the nested entry must hold the reference the registries gave for the key");
    }

    @Test
    @DisplayName("a method with no registries keeps its nested table call unchanged")
    void withoutRegistriesTheCallStays() {
        List<String> calls = calls(transformed(), "withoutRegistries");
        assertTrue(calls.contains(NESTED_TABLE + ".lootTableReference" + OLD_REFERENCE), calls.toString());
        assertTrue(calls.stream().noneMatch(call -> call.startsWith(HELPER)), calls.toString());
    }

    @Test
    @DisplayName("lootTableItem and setWeight name 26.3's UniformContainerBase.Builder")
    void entryBuilderMovesToItsSuccessor() {
        List<String> calls = calls(transformedEgg(), "egg");
        String builder = "L" + UNIFORM_BUILDER + ";";
        assertEquals(List.of(LOOT_ITEM + ".lootTableItem(L" + ITEM_LIKE + ";)" + builder,
                UNIFORM_BUILDER + ".setWeight(I)" + builder), calls.subList(0, 2));
    }

    @Test
    @DisplayName("apply and when keep the old builders and cast the interface default's result back")
    void applyAndWhenUseTheInterfaceDefaults() {
        List<String> calls = calls(transformedEgg(), "egg");
        assertEquals(List.of(
                UNIFORM_BUILDER + ".apply(L" + FUNCTION_BUILDER + ";)L" + FUNCTION_USER + ";",
                UNIFORM_BUILDER + ".when(L" + CONDITION_BUILDER + ";)L" + CONDITION_USER + ";",
                POOL_BUILDER + ".when(L" + CONDITION_BUILDER + ";)L" + CONDITION_USER + ";"),
                calls.subList(2, 5));
        assertTrue(casts(transformedEgg(), "egg").containsAll(List.of(UNIFORM_BUILDER, ENTRY_BUILDER, POOL_BUILDER)),
                "the caller's next call still names the builder, so the result is cast back");
    }

    @Test
    @DisplayName("the generated helper passes the stack verifier")
    void helperVerifies() throws Exception {
        ClassNode node = new ClassNode();
        new ClassReader(generateHelper()).accept(node, 0);
        for (MethodNode method : node.methods) new Analyzer<>(new BasicVerifier()).analyze(node.name, method);
    }

    @Test
    @DisplayName("only one registries parameter counts, and its slot follows wide parameters")
    void findsTheRegistriesSlot() {
        assertEquals(3, registriesParameter(method(Opcodes.ACC_STATIC,
                "(JLjava/lang/Object;L" + LOOKUP_PROVIDER + ";)V")));
        assertEquals(2, registriesParameter(method(0, "(Ljava/lang/Object;L" + GETTER_PROVIDER + ";)V")));
        assertTrue(registriesParameter(method(Opcodes.ACC_STATIC,
                "(L" + LOOKUP_PROVIDER + ";L" + LOOKUP_PROVIDER + ";)V")) < 0,
                "two lookups leave it unknown which holds the loot tables");
        assertTrue(registriesParameter(method(Opcodes.ACC_STATIC, "(Ljava/lang/Object;)V")) < 0);
    }

    private static MethodNode method(int access, String desc) {
        return new MethodNode(access, "m", desc, null, null);
    }

    private byte[] transformed() {
        return transformer.transformClass(modLoot(), MOD_CLASS);
    }

    private byte[] transformedEgg() {
        return transformer.transformClass(eggLoot(), EGG_CLASS);
    }

    /**
     * The nested table code of a 1.21.11 mod, in Mojang names:
     * <pre>
     * static Object modifyLootTable(ResourceKey key, Object table, Object source, HolderLookup.Provider registries) {
     *     return NestedLootTable.lootTableReference(key); }
     * static Object withoutRegistries(ResourceKey key) { return NestedLootTable.lootTableReference(key); }
     * </pre>
     * The fourth parameter of the first method is what the Fabric loot table event passes.
     */
    private static byte[] modLoot() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, MOD_CLASS, null, "java/lang/Object", null);

        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "modifyLootTable",
                "(L" + RESOURCE_KEY + ";Ljava/lang/Object;Ljava/lang/Object;L" + LOOKUP_PROVIDER + ";)Ljava/lang/Object;",
                null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, NESTED_TABLE, "lootTableReference", OLD_FACTORY, false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "withoutRegistries",
                "(L" + RESOURCE_KEY + ";)Ljava/lang/Object;", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, NESTED_TABLE, "lootTableReference", OLD_FACTORY, false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * The chest loot code of a 1.21.11 mod, in Mojang names:
     * <pre>
     * static Object egg(ItemLike egg, LootItemFunction.Builder function, LootItemCondition.Builder condition,
     *         LootPool.Builder pool) {
     *     LootItem.lootTableItem(egg).setWeight(3).apply(function).when(condition);
     *     return pool.when(condition); }
     * </pre>
     */
    private static byte[] eggLoot() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, EGG_CLASS, null, "java/lang/Object", null);
        String singleton = "L" + SINGLETON_BUILDER + ";";
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "egg",
                "(L" + ITEM_LIKE + ";L" + FUNCTION_BUILDER + ";L" + CONDITION_BUILDER + ";L" + POOL_BUILDER
                        + ";)Ljava/lang/Object;", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, LOOT_ITEM, "lootTableItem",
                "(L" + ITEM_LIKE + ";)" + singleton, false);
        mv.visitInsn(Opcodes.ICONST_3);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, SINGLETON_BUILDER, "setWeight", "(I)" + singleton, false);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, SINGLETON_BUILDER, "apply",
                "(L" + FUNCTION_BUILDER + ";)" + singleton, false);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, SINGLETON_BUILDER, "when",
                "(L" + CONDITION_BUILDER + ";)L" + ENTRY_BUILDER + ";", false);
        mv.visitInsn(Opcodes.POP);
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, POOL_BUILDER, "when",
                "(L" + CONDITION_BUILDER + ";)L" + POOL_BUILDER + ";", false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Every call in one method, as owner.name+descriptor, in order. */
    private static List<String> calls(byte[] bytes, String methodName) {
        List<String> calls = new ArrayList<>();
        visit(bytes, methodName, new MethodVisitor(Opcodes.ASM9) {
            @Override
            public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
                calls.add(owner + "." + name + desc);
            }
        });
        return calls;
    }

    private static List<String> casts(byte[] bytes, String methodName) {
        List<String> casts = new ArrayList<>();
        visit(bytes, methodName, new MethodVisitor(Opcodes.ASM9) {
            @Override
            public void visitTypeInsn(int opcode, String type) {
                if (opcode == Opcodes.CHECKCAST) casts.add(type);
            }
        });
        return casts;
    }

    private static void visit(byte[] bytes, String methodName, MethodVisitor visitor) {
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String signature,
                    String[] exceptions) {
                return name.equals(methodName) ? visitor : null;
            }
        }, 0);
    }

    /** The transformed mod class, the helper, and compiled 26.3 stand-ins, isolated. */
    private ClassLoader hostWith(byte[] modClass) throws Exception {
        Map<String, byte[]> classes = new HashMap<>(compile263StandIns());
        classes.put(MOD_CLASS.replace('/', '.'), modClass);
        transformer.getSyntheticClasses().forEach((name, bytes) -> classes.put(name.replace('/', '.'), bytes));
        return new ClassLoader(null) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] bytes = classes.get(name);
                if (bytes == null) return ClassLoader.getPlatformClassLoader().loadClass(name);
                return defineClass(name, bytes, 0, bytes.length);
            }
        };
    }

    /** The 26.3 shapes the helper calls, checked against the 26.3 client jar with javap. */
    private Map<String, byte[]> compile263StandIns() throws Exception {
        Map<String, String> sources = new HashMap<>();
        sources.put("net/minecraft/resources/ResourceKey.java", """
                package net.minecraft.resources;
                public record ResourceKey<T>(String id) {}""");
        sources.put("net/minecraft/core/Holder.java", """
                package net.minecraft.core;
                import net.minecraft.resources.ResourceKey;
                public interface Holder<T> {
                    record Reference<T>(ResourceKey<T> key) implements Holder<T> {}
                }""");
        sources.put("net/minecraft/core/HolderGetter.java", """
                package net.minecraft.core;
                import net.minecraft.resources.ResourceKey;
                public interface HolderGetter<T> {
                    interface Provider {
                        <T> Holder.Reference<T> getOrThrow(ResourceKey<T> key);
                    }
                }""");
        sources.put("net/minecraft/core/HolderLookup.java", """
                package net.minecraft.core;
                public interface HolderLookup<T> extends HolderGetter<T> {
                    interface Provider extends HolderGetter.Provider {}
                }""");
        sources.put("net/minecraft/world/level/storage/loot/entries/UniformContainerBase.java", """
                package net.minecraft.world.level.storage.loot.entries;
                public abstract class UniformContainerBase {
                    public static class Builder<T> {
                        private final Object table;
                        Builder(Object table) { this.table = table; }
                        @Override public String toString() {
                            return "reference(" + ((net.minecraft.core.Holder.Reference<?>) table).key().id() + ")";
                        }
                    }
                }""");
        sources.put("net/minecraft/world/level/storage/loot/entries/NestedLootTable.java", """
                package net.minecraft.world.level.storage.loot.entries;
                import net.minecraft.core.Holder;
                public class NestedLootTable {
                    public static UniformContainerBase.Builder<?> lootTableReference(Holder<?> table) {
                        return new UniformContainerBase.Builder<>(table);
                    }
                }""");
        sources.put("test/host/Registries.java", """
                package test.host;
                import net.minecraft.core.Holder;
                import net.minecraft.core.HolderLookup;
                import net.minecraft.resources.ResourceKey;
                public class Registries implements HolderLookup.Provider {
                    public Registries() {}
                    @Override public <T> Holder.Reference<T> getOrThrow(ResourceKey<T> key) {
                        return new Holder.Reference<>(key);
                    }
                }""");

        Path src = tempDir.resolve("src");
        Path out = tempDir.resolve("out");
        List<String> args = new ArrayList<>(List.of("-d", out.toString(), "--release", "17"));
        for (Map.Entry<String, String> source : sources.entrySet()) {
            Path file = src.resolve(source.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, source.getValue());
            args.add(file.toString());
        }
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "the tests need a JDK to compile the 26.3 stand-ins");
        assertEquals(0, compiler.run(null, null, null, args.toArray(new String[0])),
                "the 26.3 stand-ins must compile");

        Map<String, byte[]> classes = new HashMap<>();
        try (var files = Files.walk(out)) {
            for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".class"))::iterator) {
                String name = out.relativize(file).toString().replace(java.io.File.separatorChar, '.');
                classes.put(name.substring(0, name.length() - ".class".length()), Files.readAllBytes(file));
            }
        }
        return classes;
    }
}
