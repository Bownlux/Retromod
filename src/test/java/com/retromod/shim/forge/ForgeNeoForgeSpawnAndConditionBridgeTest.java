/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Forge 1.20.1 APIs that Scorched Guns and Cracker's Wither Storm touch in their constructors or
 * entity classes on a NeoForge 1.21.1 host.
 */
class ForgeNeoForgeSpawnAndConditionBridgeTest {

    private static final String ENTITY = "com/example/BulletEntity";
    private static final String OLD_BUFFER = "(Lnet/minecraft/network/FriendlyByteBuf;)V";
    private static final String NEW_BUFFER = "(Lnet/minecraft/network/RegistryFriendlyByteBuf;)V";

    @AfterEach
    void reset() {
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    void spawnDataEntityGetsForwardersForTheRegistryBuffer() {
        ClassNode result = read(LegacySpawnDataAdapter.apply(
                spawnDataEntity(LegacySpawnDataAdapter.NEOFORGE_INTERFACE), "1.21.1"));

        for (String name : new String[]{"writeSpawnData", "readSpawnData"}) {
            MethodNode forwarder = method(result, name, NEW_BUFFER);
            assertNotNull(forwarder, name + " must exist with the NeoForge buffer type");
            MethodInsnNode call = (MethodInsnNode) forwarder.instructions.get(2);
            assertEquals(Opcodes.INVOKEVIRTUAL, call.getOpcode(),
                    "a virtual call keeps subclass overrides of the old method working");
            assertEquals(OLD_BUFFER, call.desc);
        }
    }

    @Test
    void entitiesWithoutTheSpawnDataInterfaceAreLeftAlone() {
        byte[] original = spawnDataEntity("java/io/Serializable");
        assertSame(original, LegacySpawnDataAdapter.apply(original, "1.21.1"));
    }

    @Test
    void spawnDataEntitiesOnHostsBefore1205AreLeftAlone() {
        byte[] original = spawnDataEntity(LegacySpawnDataAdapter.NEOFORGE_INTERFACE);
        assertSame(original, LegacySpawnDataAdapter.apply(original, "1.20.4"),
                "NeoForge 1.20.4 still declares the interface with FriendlyByteBuf");
    }

    @Test
    void sameShapeMovesRenameTheSpawnEventAndSpawnDataInterface() {
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        ForgeNeoForgeSameShapeMoves.register(transformer);

        Map<String, String> redirects = transformer.getClassRedirects();
        assertEquals("net/neoforged/neoforge/event/entity/RegisterSpawnPlacementsEvent",
                redirects.get("net/minecraftforge/event/entity/SpawnPlacementRegisterEvent"));
        assertEquals("net/neoforged/neoforge/common/command/IEntitySelectorType",
                redirects.get("net/minecraftforge/common/command/IEntitySelectorType"));
        assertEquals(LegacySpawnDataAdapter.NEOFORGE_INTERFACE,
                redirects.get(LegacySpawnDataAdapter.FORGE_INTERFACE));
    }

    @Test
    void craftingHelperRegisterReturnsTheSerializerSoTheConstructorContinues() throws Exception {
        Map<String, byte[]> classes = ForgeRecipeConditionSynthetics.generateAll();
        ClassLoader loader = new ClassLoader(getClass().getClassLoader()) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] bytes = classes.get(name.replace('.', '/'));
                if (bytes == null) throw new ClassNotFoundException(name);
                return defineClass(name, bytes, 0, bytes.length);
            }
        };
        Class<?> serializerType = loader.loadClass(
                ForgeRecipeConditionSynthetics.SERIALIZER.replace('/', '.'));
        Object serializer = Proxy.newProxyInstance(loader, new Class<?>[]{serializerType},
                (proxy, method, args) -> null);
        Method register = loader.loadClass(ForgeRecipeConditionSynthetics.CRAFTING_HELPER.replace('/', '.'))
                .getMethod("register", serializerType);

        assertSame(serializer, register.invoke(null, serializer),
                "mods chain on the returned serializer, so it must come back unchanged");
    }

    private static byte[] spawnDataEntity(String iface) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, ENTITY, null, "java/lang/Object", new String[]{iface});
        for (String name : new String[]{"writeSpawnData", "readSpawnData"}) {
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, name, OLD_BUFFER, null, null);
            mv.visitCode();
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static MethodNode method(ClassNode node, String name, String desc) {
        return node.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(desc))
                .findFirst().orElse(null);
    }
}
