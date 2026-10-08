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
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** Load-time stand-ins for 1.12.2 FML services touched before content registration. */
class Forge1122ServiceStubsTest {

    @AfterEach
    void reset() {
        RetromodTransformer.getInstance().clearRedirectsForTesting();
        Forge1122LifecycleSynthetics.resetForTesting();
        Forge1122EventBridge.resetForTesting();
    }

    /** A 1.12 pre-init that opens a channel and registers one message, as most 1.12 mods do. */
    private static byte[] preInitWithChannel() {
        String registry = "net/minecraftforge/fml/common/network/NetworkRegistry";
        String channel = "net/minecraftforge/fml/common/network/simpleimpl/SimpleNetworkWrapper";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "test/Packets", null, "java/lang/Object", null);
        var mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "init", "()V", null, null);
        mv.visitCode();
        mv.visitFieldInsn(GETSTATIC, registry, "INSTANCE", "L" + registry + ";");
        mv.visitLdcInsn("oldmod");
        mv.visitMethodInsn(INVOKEVIRTUAL, registry, "newSimpleChannel",
                "(Ljava/lang/String;)L" + channel + ";", false);
        mv.visitLdcInsn(Type.getObjectType("test/Handler"));
        mv.visitLdcInsn(Type.getObjectType("test/Message"));
        mv.visitInsn(ICONST_0);
        mv.visitInsn(ACONST_NULL);
        mv.visitMethodInsn(INVOKEVIRTUAL, channel, "registerMessage",
                "(Ljava/lang/Class;Ljava/lang/Class;ILnet/minecraftforge/fml/relauncher/Side;)V", false);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static Class<?> standIn(String internalName) throws ClassNotFoundException {
        return Class.forName(internalName.replace('/', '.'));
    }

    private static boolean declares(Class<?> type, String name, String desc) {
        return Arrays.stream(type.getDeclaredMethods())
                .anyMatch(m -> m.getName().equals(name) && Type.getMethodDescriptor(m).equals(desc));
    }

    @Test
    void channelCallsLinkAgainstTheCompiledStandIns() throws Exception {
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        Forge1122LifecycleSynthetics.register(t);
        Forge1122EventBridge.register(t);
        Forge1122ServiceStubs.register(t);

        byte[] remapped = t.transformClass(preInitWithChannel(), "test/Packets.class");
        ClassNode cn = new ClassNode();
        new ClassReader(Forge1122EventBridge.rewriteLegacyCalls(remapped)).accept(cn, 0);
        MethodNode init = cn.methods.stream().filter(m -> m.name.equals("init")).findFirst().orElseThrow();

        int checked = 0;
        for (AbstractInsnNode in : init.instructions.toArray()) {
            if (!(in instanceof MethodInsnNode mi)) continue;
            assertTrue(Forge1122ServiceStubs.ERASED_OWNERS.contains(mi.owner),
                    "every channel call must land on a stand-in: " + mi.owner);
            assertTrue(declares(standIn(mi.owner), mi.name, mi.desc),
                    mi.owner + " must declare " + mi.name + mi.desc + " or the call fails to link");
            checked++;
        }
        assertEquals(2, checked);
    }

    /** The usual 1.12 config read: build, load, get(category, key, default, comment).getInt(). */
    private static byte[] preInitWithConfig() {
        String config = "net/minecraftforge/common/config/Configuration";
        String property = "net/minecraftforge/common/config/Property";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "test/Config", null, "java/lang/Object", null);
        var mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "load", "(Ljava/io/File;)Ljava/lang/String;", null, null);
        mv.visitCode();
        mv.visitTypeInsn(NEW, config);
        mv.visitInsn(DUP);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESPECIAL, config, "<init>", "(Ljava/io/File;)V", false);
        mv.visitVarInsn(ASTORE, 1);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEVIRTUAL, config, "load", "()V", false);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitLdcInsn("general");
        mv.visitLdcInsn("radius");
        mv.visitIntInsn(BIPUSH, 16);
        mv.visitLdcInsn("search radius");
        mv.visitMethodInsn(INVOKEVIRTUAL, config, "get",
                "(Ljava/lang/String;Ljava/lang/String;ILjava/lang/String;)L" + property + ";", false);
        mv.visitMethodInsn(INVOKEVIRTUAL, property, "getInt", "()I", false);
        mv.visitInsn(POP);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitLdcInsn("name");
        mv.visitLdcInsn("general");
        mv.visitLdcInsn("compass");
        mv.visitLdcInsn("display name");
        mv.visitMethodInsn(INVOKEVIRTUAL, config, "getString",
                "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;", false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    @Test
    void configReadsLinkAgainstTheCompiledStandIns() throws Exception {
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        Forge1122LifecycleSynthetics.register(t);
        Forge1122EventBridge.register(t);
        Forge1122ServiceStubs.register(t);

        byte[] remapped = t.transformClass(preInitWithConfig(), "test/Config.class");
        ClassNode cn = new ClassNode();
        new ClassReader(Forge1122EventBridge.rewriteLegacyCalls(remapped)).accept(cn, 0);
        MethodNode load = cn.methods.stream().filter(m -> m.name.equals("load")).findFirst().orElseThrow();

        for (AbstractInsnNode in : load.instructions.toArray()) {
            if (!(in instanceof MethodInsnNode mi)) continue;
            Class<?> owner = standIn(mi.owner);
            boolean linked = mi.name.equals("<init>")
                    ? Arrays.stream(owner.getConstructors())
                            .anyMatch(c -> Type.getConstructorDescriptor(c).equals(mi.desc))
                    : declares(owner, mi.name, mi.desc);
            assertTrue(linked, mi.owner + " must declare " + mi.name + mi.desc);
        }
    }

    @Test
    void configPropertyReturnsTheModDefault() {
        var config = new com.retromod.shim.forge.embedded.LegacyConfiguration(null);

        assertEquals(16, config.get("general", "radius", 16, "search radius").getInt());
        assertEquals(List.of("a", "b"), Arrays.asList((String[]) config.get("general", "list",
                (Object) new String[]{"a", "b"}, "entries").getStringList()));
        assertEquals("compass", config.getString("name", "general", "compass", "display name"));
    }

    @Test
    void oreRegistrationLinksAgainstThePolyfill() throws Exception {
        MethodNode m = new MethodNode(ACC_STATIC, "x", "(Lnet/minecraft/world/item/Item;)V", null, null);
        m.visitLdcInsn("blockElevator");
        m.visitVarInsn(ALOAD, 0);
        m.visitMethodInsn(INVOKESTATIC, Forge1122ServiceStubs.ORE_DICTIONARY, "registerOre",
                "(Ljava/lang/String;Lnet/minecraft/world/item/Item;)V", false);
        m.visitInsn(RETURN);
        MethodInsnNode call = (MethodInsnNode) m.instructions.get(2);

        assertTrue(Forge1122ServiceStubs.rewriteCall(m, call));
        assertTrue(declares(standIn(call.owner), call.name, call.desc),
                "registerOre(String, Item) must reach the polyfill's (String, Object) or the "
                        + "registry handler that calls it fails, as in ElevatorMod");
    }

    @Test
    void lootTableRegistrationKeepsTheName() {
        String rl = "Lnet/minecraft/resources/ResourceLocation;";
        MethodNode m = new MethodNode(ACC_STATIC, "x", "(" + rl + ")" + rl, null, null);
        m.visitVarInsn(ALOAD, 0);
        m.visitMethodInsn(INVOKESTATIC, Forge1122ServiceStubs.LOOT_TABLE_LIST, "func_186375_a",
                "(" + rl + ")" + rl, false);
        m.visitInsn(ARETURN);
        MethodInsnNode call = (MethodInsnNode) m.instructions.get(1);

        assertTrue(Forge1122ServiceStubs.rewriteCall(m, call));
        assertEquals(List.of(ALOAD, ARETURN), Arrays.stream(m.instructions.toArray())
                .map(AbstractInsnNode::getOpcode).toList(), "register(name) returned its argument");
    }

    @Test
    void fmlLogFormatsLikeTheOldHelpers() throws Exception {
        byte[] bytes = Forge1122ServiceStubs.fmlLogBytes();
        Class<?> log = new ClassLoader(getClass().getClassLoader()) {
            Class<?> define() {
                return defineClass(Forge1122ServiceStubs.FML_LOG.replace('/', '.'), bytes, 0, bytes.length);
            }
        }.define();

        Method info = log.getMethod("info", String.class, Object[].class);
        assertDoesNotThrow(() -> info.invoke(null, "loaded %s items", new Object[]{3}));
        assertNotNull(log.getMethod("getLogger").invoke(null), "FMLLog.log must be initialized");
    }

    /** MCreator 1.12 element discovery: {@code event.getAsmData().getAll(tag)} then each class name. */
    private static byte[] elementDiscovery() {
        String table = "net/minecraftforge/fml/common/discovery/ASMDataTable";
        String data = table + "$ASMData";
        String event = "net/minecraftforge/fml/common/event/FMLPreInitializationEvent";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "test/Elements", null, "java/lang/Object", null);
        var mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "preInit", "(L" + event + ";)Ljava/lang/String;",
                null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEVIRTUAL, event, "getAsmData", "()L" + table + ";", false);
        mv.visitLdcInsn("test.Elements$Tag");
        mv.visitMethodInsn(INVOKEVIRTUAL, table, "getAll", "(Ljava/lang/String;)Ljava/util/Set;", false);
        mv.visitMethodInsn(INVOKEINTERFACE, "java/util/Set", "iterator", "()Ljava/util/Iterator;", true);
        mv.visitMethodInsn(INVOKEINTERFACE, "java/util/Iterator", "next", "()Ljava/lang/Object;", true);
        mv.visitTypeInsn(CHECKCAST, data);
        mv.visitMethodInsn(INVOKEVIRTUAL, data, "getClassName", "()Ljava/lang/String;", false);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEVIRTUAL, event, "getModMetadata",
                "()Lnet/minecraftforge/fml/common/ModMetadata;", false);
        mv.visitInsn(ICONST_0);
        mv.visitFieldInsn(PUTFIELD, "net/minecraftforge/fml/common/ModMetadata", "autogenerated", "Z");
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    @Test
    void preInitAnnotationTableAndMetadataLinkAgainstTheStandIns() throws Exception {
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        Forge1122LifecycleSynthetics.register(t);
        Forge1122EventBridge.register(t);
        Forge1122ServiceStubs.register(t);

        byte[] remapped = t.transformClass(elementDiscovery(), "test/Elements.class");
        ClassNode cn = new ClassNode();
        new ClassReader(Forge1122EventBridge.rewriteLegacyCalls(remapped)).accept(cn, 0);
        MethodNode preInit = cn.methods.stream().filter(m -> m.name.equals("preInit")).findFirst().orElseThrow();

        ClassNode event = new ClassNode();
        new ClassReader(t.getSyntheticClasses().get(Forge1122LifecycleSynthetics.PRE_EVENT)).accept(event, 0);
        int checked = 0;
        for (AbstractInsnNode in : preInit.instructions.toArray()) {
            if (!(in instanceof MethodInsnNode mi) || mi.owner.startsWith("java/")) continue;
            boolean linked = mi.owner.equals(Forge1122LifecycleSynthetics.PRE_EVENT)
                    ? event.methods.stream().anyMatch(m -> m.name.equals(mi.name) && m.desc.equals(mi.desc))
                    : declares(standIn(mi.owner), mi.name, mi.desc);
            assertTrue(linked, mi.owner + " must declare " + mi.name + mi.desc + " or the call fails to link");
            checked++;
        }
        assertEquals(4, checked);
        assertEquals(1, standIn(Forge1122ServiceStubs.MOD_METADATA).getField("autogenerated")
                .getModifiers() & java.lang.reflect.Modifier.PUBLIC, "mods write the metadata fields directly");
    }
}
