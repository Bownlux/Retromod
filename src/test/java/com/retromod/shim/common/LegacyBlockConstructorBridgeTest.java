/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * #283: a 1.20.1 mod's blocks against the constructors 1.20.3 reshaped. Scorched Guns builds its
 * ores with {@code new DropExperienceBlock(properties)} and its acid cauldron with
 * {@code super(properties, Map.of())}, and both stopped its block registry. Each case runs the
 * rewritten call against stand-ins for the host classes.
 */
class LegacyBlockConstructorBridgeTest {

    private static final String PROPERTIES = "net/minecraft/world/level/block/state/BlockBehaviour$Properties";
    private static final String INT_PROVIDER = "net/minecraft/util/valueproviders/IntProvider";
    private static final String CONSTANT_INT = "net/minecraft/util/valueproviders/ConstantInt";
    private static final String INTERACTION = "net/minecraft/core/cauldron/CauldronInteraction";
    private static final String INTERACTION_MAP = INTERACTION + "$InteractionMap";
    private static final String DISPATCHER = INTERACTION + "$Dispatcher";
    private static final String ITEM = "net/minecraft/world/item/Item";
    private static final String DISPATCHERS = "net/minecraft/core/cauldron/CauldronInteractions";
    private static final String L_PROPERTIES = "L" + PROPERTIES + ";";
    private static final String DROP = "net/minecraft/world/level/block/DropExperienceBlock";
    private static final String CLUSTER = "net/minecraft/world/level/block/AmethystClusterBlock";
    private static final String CAULDRON = "net/minecraft/world/level/block/AbstractCauldronBlock";
    private static final String BUTTON = "net/minecraft/world/level/block/ButtonBlock";
    private static final String SIGN = "net/minecraft/world/level/block/StandingSignBlock";
    private static final String SET_TYPE = "net/minecraft/world/level/block/state/properties/BlockSetType";
    private static final String WOOD_TYPE = "net/minecraft/world/level/block/state/properties/WoodType";

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private byte[] transformedCauldron;

    @AfterEach
    void reset() {
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("an old DropExperienceBlock drops no experience, or the provider it named")
    void dropExperienceBlockTakesTheModernArgumentOrder() throws Exception {
        HostStubs host = register(host1211());

        Object plain = run(host, "plainOre");
        Object rich = run(host, "richOre");

        Object[] plainArgs = args(plain);
        assertEquals(0, field(plainArgs[0], "value"), "the one-argument form passed ConstantInt.of(0)");
        assertEquals(PROPERTIES.replace('/', '.'), plainArgs[1].getClass().getName());
        Object[] richArgs = args(rich);
        assertEquals(7, field(richArgs[0], "value"), "the mod's own provider comes first now");
        assertEquals(PROPERTIES.replace('/', '.'), richArgs[1].getClass().getName());
    }

    @Test
    @DisplayName("#372: a 1.20.1 button and sign put their type ahead of the properties, and the button drops its arrow flag")
    void typeFirstConstructors() throws Exception {
        HostStubs host = register(host1211());

        Object[] button = args(run(host, "button"));
        Object[] sign = args(run(host, "sign"));

        assertEquals(SET_TYPE.replace('/', '.'), button[0].getClass().getName());
        assertEquals(30, button[1]);
        assertEquals(PROPERTIES.replace('/', '.'), button[2].getClass().getName());
        assertEquals(WOOD_TYPE.replace('/', '.'), sign[0].getClass().getName());
        assertEquals(PROPERTIES.replace('/', '.'), sign[1].getClass().getName());
    }

    @Test
    @DisplayName("an old AmethystClusterBlock's int sizes arrive as floats before the properties")
    void amethystClusterSizesWiden() throws Exception {
        HostStubs host = register(host1211());

        Object[] args = args(run(host, "cluster"));

        assertEquals(List.of(3.0f, 4.0f), List.of(args[0], args[1]));
        assertEquals(PROPERTIES.replace('/', '.'), args[2].getClass().getName());
    }

    @Test
    @DisplayName("a cauldron's plain map becomes a named InteractionMap on 1.20.3 to 1.21.11")
    void cauldronMapBecomesAnInteractionMap() throws Exception {
        HostStubs host = register(host1211());

        Object[] args = args(run(host, "cauldron"));

        assertEquals(INTERACTION_MAP.replace('/', '.'), args[1].getClass().getName());
        Object[] interactionMap = args(args[1]);
        assertTrue(((String) interactionMap[0]).startsWith("retromod_legacy_cauldron_"));
        assertEquals(Map.of("water_bucket", "fill"), interactionMap[1],
                "the host's own map holds the mod's entries");
    }

    @Test
    @DisplayName("an entry the mod puts in CauldronInteraction.EMPTY reaches the host's table")
    void vanillaTablePutReachesTheInteractionMap() throws Exception {
        HostStubs host = register(host1211());

        host.method("test/ModCauldronInteraction", "register").invoke(null, "acid_bucket", "fill");

        Object empty = host.load(INTERACTION).getField("EMPTY").get(null);
        assertEquals(Map.of("acid_bucket", "fill"), args(empty)[1],
                "1.20.3 made EMPTY an InteractionMap; the mod's put lands in its map");
    }

    @Test
    @DisplayName("a cauldron's plain map fills a Dispatcher on 26.1 and later")
    void cauldronMapFillsADispatcher() throws Exception {
        Map<String, byte[]> classes = baseHost();
        classes.put(CAULDRON, argsHolder(CAULDRON, "java/lang/Object",
                "(" + L_PROPERTIES + "L" + DISPATCHER + ";)V"));
        classes.put(INTERACTION, HostStubs.iface(INTERACTION));
        classes.put(DISPATCHER, dispatcher());
        classes.put("test/Fill", HostStubs.plainClass("test/Fill", "java/lang/Object", INTERACTION));
        classes.put(DISPATCHERS, dispatchers());
        HostStubs host = register(classes);

        Object item = host.load(ITEM).getConstructor().newInstance();
        Object fill = host.load("test/Fill").getConstructor().newInstance();
        Object dispatcher = host.method(LegacyBlockConstructorBridge.HELPER, "interactions")
                .invoke(null, Map.of(item, fill));

        assertEquals(DISPATCHER.replace('/', '.'), dispatcher.getClass().getName());
        assertEquals(Map.of(item, fill), dispatcher.getClass().getField("items").get(dispatcher),
                "each entry goes through the package-private put");
        assertEquals("(" + L_PROPERTIES + "L" + DISPATCHER + ";)V", superCallDesc(transformedCauldron),
                "the subclass's super call takes the Dispatcher");

        host.method("test/ModCauldronInteraction", "register").invoke(null, item, fill);
        Object empty = host.load(DISPATCHERS).getField("EMPTY").get(null);
        assertEquals(Map.of(item, fill), empty.getClass().getField("items").get(empty),
                "26.1 moved EMPTY to CauldronInteractions; the mod's put reaches that dispatcher");
    }

    @Test
    @DisplayName("on 26.x a 1.20.1 Map read and a 1.21 InteractionMap read of EMPTY each keep their target")
    void bothOldReadsOfATableCoexist() {
        Map<String, byte[]> classes = baseHost();
        classes.put(CAULDRON, argsHolder(CAULDRON, "java/lang/Object",
                "(" + L_PROPERTIES + "L" + DISPATCHER + ";)V"));
        LegacyBlockConstructorBridge.register(transformer, reader(classes));
        // The 26.1 shim then registers its own redirect for the same field, typed InteractionMap.
        new LegacyCauldronInteractionShim().registerRedirects(transformer);

        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, "test/Tables", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "read", "()V", null, null);
        mv.visitCode();
        mv.visitFieldInsn(GETSTATIC, INTERACTION, "EMPTY", "Ljava/util/Map;");
        mv.visitInsn(POP);
        mv.visitFieldInsn(GETSTATIC, INTERACTION, "EMPTY", "L" + INTERACTION_MAP + ";");
        mv.visitInsn(POP);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        ClassNode node = new ClassNode();
        new ClassReader(transformer.transformClass(cw.toByteArray(), "test/Tables.class")).accept(node, 0);

        List<String> reads = new java.util.ArrayList<>();
        for (AbstractInsnNode insn : node.methods.get(0).instructions) {
            if (insn instanceof MethodInsnNode call) reads.add(call.owner + "." + call.name);
            if (insn instanceof org.objectweb.asm.tree.FieldInsnNode field) reads.add(field.owner + "." + field.name);
        }
        assertEquals(List.of(LegacyBlockConstructorBridge.HELPER + ".empty", DISPATCHERS + ".EMPTY"), reads);
    }

    @Test
    @DisplayName("a host that still has the old constructors gets no rewrite")
    void oldHostIsLeftAlone() {
        Map<String, byte[]> classes = new HashMap<>();
        classes.put(DROP, argsHolder(DROP, "java/lang/Object", "(" + L_PROPERTIES + ")V"));
        classes.put(CAULDRON, argsHolder(CAULDRON, "java/lang/Object", "(" + L_PROPERTIES + "Ljava/util/Map;)V"));

        assertEquals(0, LegacyBlockConstructorBridge.register(transformer, reader(classes)));
    }

    // --- the mod under test -------------------------------------------------------------------

    /** Registers the bridge against these host classes and loads them with the transformed mod. */
    private HostStubs register(Map<String, byte[]> classes) {
        assertTrue(LegacyBlockConstructorBridge.register(transformer, reader(classes)) > 0);
        HostStubs host = new HostStubs();
        classes.values().forEach(host::add);
        host.add(transformer.transformClass(modBlocks(), "test/ModBlocks.class"));
        transformedCauldron = transformer.transformClass(acidCauldron(), "test/AcidCauldron.class");
        host.add(transformedCauldron);
        host.add(transformer.transformClass(cauldronRegistration(), "test/ModCauldronInteraction.class"));
        host.add(resource(LegacyBlockConstructorBridge.HELPER));
        return host;
    }

    /** {@code test/ModBlocks}: one static factory per old constructor call. */
    private static byte[] modBlocks() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, "test/ModBlocks", null, "java/lang/Object", null);
        String drop = DROP;
        factory(cw, "plainOre", drop, "(" + L_PROPERTIES + ")V", mv -> properties(mv));
        factory(cw, "richOre", drop, "(" + L_PROPERTIES + "L" + INT_PROVIDER + ";)V", mv -> {
            properties(mv);
            mv.visitIntInsn(BIPUSH, 7);
            mv.visitMethodInsn(INVOKESTATIC, CONSTANT_INT, "of", "(I)L" + CONSTANT_INT + ";", false);
        });
        factory(cw, "cluster", "net/minecraft/world/level/block/AmethystClusterBlock", "(II" + L_PROPERTIES + ")V",
                mv -> {
                    mv.visitInsn(ICONST_3);
                    mv.visitInsn(ICONST_4);
                    properties(mv);
                });
        factory(cw, "cauldron", "test/AcidCauldron", "(" + L_PROPERTIES + ")V", mv -> properties(mv));
        // Fossils and Archaeology's 1.20.1 buttons and signs.
        factory(cw, "button", BUTTON, "(" + L_PROPERTIES + "L" + SET_TYPE + ";IZ)V", mv -> {
            properties(mv);
            instance(mv, SET_TYPE);
            mv.visitIntInsn(BIPUSH, 30);
            mv.visitInsn(ICONST_1);
        });
        factory(cw, "sign", SIGN, "(" + L_PROPERTIES + "L" + WOOD_TYPE + ";)V", mv -> {
            properties(mv);
            instance(mv, WOOD_TYPE);
        });
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code static void register(Object item, Object interaction) { CauldronInteraction.EMPTY.put(item, interaction); }} */
    private static byte[] cauldronRegistration() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, "test/ModCauldronInteraction", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "register",
                "(Ljava/lang/Object;Ljava/lang/Object;)V", null, null);
        mv.visitCode();
        mv.visitFieldInsn(GETSTATIC, INTERACTION, "EMPTY", "Ljava/util/Map;");
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEINTERFACE, "java/util/Map", "put",
                "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true);
        mv.visitInsn(POP);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code class AcidCauldron extends AbstractCauldronBlock}, calling {@code super(p, map)}. */
    private static byte[] acidCauldron() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        String cauldron = "net/minecraft/world/level/block/AbstractCauldronBlock";
        cw.visit(V17, ACC_PUBLIC, "test/AcidCauldron", null, cauldron, null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "<init>", "(" + L_PROPERTIES + ")V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitLdcInsn("water_bucket");
        mv.visitLdcInsn("fill");
        mv.visitMethodInsn(INVOKESTATIC, "java/util/Map", "of",
                "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/util/Map;", true);
        mv.visitMethodInsn(INVOKESPECIAL, cauldron, "<init>", "(" + L_PROPERTIES + "Ljava/util/Map;)V", false);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void factory(ClassWriter cw, String name, String owner, String ctor,
            java.util.function.Consumer<MethodVisitor> arguments) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, name, "()Ljava/lang/Object;", null, null);
        mv.visitCode();
        mv.visitTypeInsn(NEW, owner);
        mv.visitInsn(DUP);
        arguments.accept(mv);
        mv.visitMethodInsn(INVOKESPECIAL, owner, "<init>", ctor, false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static void instance(MethodVisitor mv, String type) {
        mv.visitTypeInsn(NEW, type);
        mv.visitInsn(DUP);
        mv.visitMethodInsn(INVOKESPECIAL, type, "<init>", "()V", false);
    }

    private static void properties(MethodVisitor mv) {
        mv.visitTypeInsn(NEW, PROPERTIES);
        mv.visitInsn(DUP);
        mv.visitMethodInsn(INVOKESPECIAL, PROPERTIES, "<init>", "()V", false);
    }

    // --- host stand-ins -----------------------------------------------------------------------

    /** The 1.21.1 shapes: the modern constructors and a named InteractionMap. */
    private static Map<String, byte[]> host1211() {
        Map<String, byte[]> classes = baseHost();
        classes.put(CAULDRON, argsHolder(CAULDRON, "java/lang/Object",
                "(" + L_PROPERTIES + "L" + INTERACTION_MAP + ";)V"));
        classes.put(INTERACTION_MAP, argsHolder(INTERACTION_MAP, "java/lang/Object",
                "(Ljava/lang/String;Ljava/util/Map;)V", "map", 1));
        classes.put(INTERACTION, interactionWithNamedMaps());
        return classes;
    }

    private static Map<String, byte[]> baseHost() {
        Map<String, byte[]> classes = new HashMap<>();
        classes.put(PROPERTIES, HostStubs.plainClass(PROPERTIES, "java/lang/Object"));
        classes.put(INT_PROVIDER, HostStubs.plainClass(INT_PROVIDER, "java/lang/Object"));
        classes.put(CONSTANT_INT, constantInt());
        classes.put(DROP, argsHolder(DROP, "java/lang/Object", "(L" + INT_PROVIDER + ";" + L_PROPERTIES + ")V"));
        classes.put(CLUSTER, argsHolder(CLUSTER, "java/lang/Object", "(FF" + L_PROPERTIES + ")V"));
        classes.put(SET_TYPE, HostStubs.plainClass(SET_TYPE, "java/lang/Object"));
        classes.put(WOOD_TYPE, HostStubs.plainClass(WOOD_TYPE, "java/lang/Object"));
        classes.put(BUTTON, argsHolder(BUTTON, "java/lang/Object", "(L" + SET_TYPE + ";I" + L_PROPERTIES + ")V"));
        classes.put(SIGN, argsHolder(SIGN, "java/lang/Object", "(L" + WOOD_TYPE + ";" + L_PROPERTIES + ")V"));
        return classes;
    }

    private static Function<String, ClassNode> reader(Map<String, byte[]> classes) {
        return name -> {
            byte[] bytes = classes.get(name);
            if (bytes == null) return null;
            ClassNode node = new ClassNode();
            new ClassReader(bytes).accept(node, ClassReader.SKIP_CODE);
            return node;
        };
    }

    private static String superCallDesc(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        for (AbstractInsnNode insn : node.methods.get(0).instructions) {
            if (insn instanceof MethodInsnNode call && call.name.equals("<init>")) {
                return call.desc;
            }
        }
        throw new AssertionError("no super call");
    }

    private static Object run(HostStubs host, String factory) throws Exception {
        return host.method("test/ModBlocks", factory).invoke(null);
    }

    private static Object[] args(Object holder) throws Exception {
        return (Object[]) holder.getClass().getField("args").get(holder);
    }

    private static Object field(Object owner, String name) throws Exception {
        return owner.getClass().getField(name).get(owner);
    }

    private static byte[] resource(String internalName) {
        try (InputStream in = LegacyBlockConstructorBridgeTest.class.getClassLoader()
                .getResourceAsStream(internalName + ".class")) {
            return in.readAllBytes();
        } catch (Exception e) {
            throw new AssertionError("missing " + internalName, e);
        }
    }

    /** A class whose one public constructor keeps its boxed arguments in {@code args}. */
    private static byte[] argsHolder(String name, String superName, String ctor) {
        return argsHolder(name, superName, ctor, null, -1);
    }

    /** As above, plus a getter returning one argument, as a record accessor does. */
    private static byte[] argsHolder(String name, String superName, String ctor, String getter, int index) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, name, null, superName, null);
        cw.visitField(ACC_PUBLIC, "args", "[Ljava/lang/Object;", null, null).visitEnd();
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "<init>", ctor, null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESPECIAL, superName, "<init>", "()V", false);
        Type[] params = Type.getArgumentTypes(ctor);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitLdcInsn(params.length);
        mv.visitTypeInsn(ANEWARRAY, "java/lang/Object");
        int local = 1;
        for (int i = 0; i < params.length; i++) {
            mv.visitInsn(DUP);
            mv.visitLdcInsn(i);
            mv.visitVarInsn(params[i].getOpcode(ILOAD), local);
            box(mv, params[i]);
            mv.visitInsn(AASTORE);
            local += params[i].getSize();
        }
        mv.visitFieldInsn(PUTFIELD, name, "args", "[Ljava/lang/Object;");
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        if (getter != null) {
            Type type = params[index];
            MethodVisitor get = cw.visitMethod(ACC_PUBLIC, getter, "()" + type.getDescriptor(), null, null);
            get.visitCode();
            get.visitVarInsn(ALOAD, 0);
            get.visitFieldInsn(GETFIELD, name, "args", "[Ljava/lang/Object;");
            get.visitLdcInsn(index);
            get.visitInsn(AALOAD);
            get.visitTypeInsn(CHECKCAST, type.getInternalName());
            get.visitInsn(ARETURN);
            get.visitMaxs(0, 0);
            get.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void box(MethodVisitor mv, Type type) {
        switch (type.getSort()) {
            case Type.INT -> mv.visitMethodInsn(INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false);
            case Type.FLOAT -> mv.visitMethodInsn(INVOKESTATIC, "java/lang/Float", "valueOf", "(F)Ljava/lang/Float;", false);
            default -> { }
        }
    }

    /** {@code ConstantInt extends IntProvider}, with {@code of(int)} and a public {@code value}. */
    private static byte[] constantInt() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, CONSTANT_INT, null, INT_PROVIDER, null);
        cw.visitField(ACC_PUBLIC, "value", "I", null, null).visitEnd();
        HostStubs.defaultConstructor(cw, INT_PROVIDER);
        MethodVisitor of = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "of", "(I)L" + CONSTANT_INT + ";", null, null);
        of.visitCode();
        of.visitTypeInsn(NEW, CONSTANT_INT);
        of.visitInsn(DUP);
        of.visitMethodInsn(INVOKESPECIAL, CONSTANT_INT, "<init>", "()V", false);
        of.visitInsn(DUP);
        of.visitVarInsn(ILOAD, 0);
        of.visitFieldInsn(PUTFIELD, CONSTANT_INT, "value", "I");
        of.visitInsn(ARETURN);
        of.visitMaxs(0, 0);
        of.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** 1.20.3 to 1.21.11: {@code CauldronInteraction.newInteractionMap(name)}, and an {@code EMPTY} table. */
    private static byte[] interactionWithNamedMaps() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_INTERFACE | ACC_ABSTRACT, INTERACTION, null, "java/lang/Object", null);
        cw.visitField(ACC_PUBLIC | ACC_STATIC | ACC_FINAL, "EMPTY", "L" + INTERACTION_MAP + ";", null, null).visitEnd();
        MethodVisitor clinit = cw.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.visitCode();
        clinit.visitLdcInsn("empty");
        clinit.visitMethodInsn(INVOKESTATIC, INTERACTION, "newInteractionMap",
                "(Ljava/lang/String;)L" + INTERACTION_MAP + ";", true);
        clinit.visitFieldInsn(PUTSTATIC, INTERACTION, "EMPTY", "L" + INTERACTION_MAP + ";");
        clinit.visitInsn(RETURN);
        clinit.visitMaxs(0, 0);
        clinit.visitEnd();
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "newInteractionMap",
                "(Ljava/lang/String;)L" + INTERACTION_MAP + ";", null, null);
        mv.visitCode();
        mv.visitTypeInsn(NEW, INTERACTION_MAP);
        mv.visitInsn(DUP);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitTypeInsn(NEW, "java/util/HashMap");
        mv.visitInsn(DUP);
        mv.visitMethodInsn(INVOKESPECIAL, "java/util/HashMap", "<init>", "()V", false);
        mv.visitMethodInsn(INVOKESPECIAL, INTERACTION_MAP, "<init>", "(Ljava/lang/String;Ljava/util/Map;)V", false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** 26.1 and later: {@code CauldronInteractions.EMPTY}, a dispatcher. */
    private static byte[] dispatchers() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, DISPATCHERS, null, "java/lang/Object", null);
        cw.visitField(ACC_PUBLIC | ACC_STATIC | ACC_FINAL, "EMPTY", "L" + DISPATCHER + ";", null, null).visitEnd();
        MethodVisitor clinit = cw.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.visitCode();
        clinit.visitTypeInsn(NEW, DISPATCHER);
        clinit.visitInsn(DUP);
        clinit.visitMethodInsn(INVOKESPECIAL, DISPATCHER, "<init>", "()V", false);
        clinit.visitFieldInsn(PUTSTATIC, DISPATCHERS, "EMPTY", "L" + DISPATCHER + ";");
        clinit.visitInsn(RETURN);
        clinit.visitMaxs(0, 0);
        clinit.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** 26.1 and later: a {@code Dispatcher} with a package-private {@code put(Item, CauldronInteraction)}. */
    private static byte[] dispatcher() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, DISPATCHER, null, "java/lang/Object", null);
        cw.visitField(ACC_PUBLIC | ACC_FINAL, "items", "Ljava/util/Map;", null, null).visitEnd();
        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitVarInsn(ALOAD, 0);
        init.visitTypeInsn(NEW, "java/util/HashMap");
        init.visitInsn(DUP);
        init.visitMethodInsn(INVOKESPECIAL, "java/util/HashMap", "<init>", "()V", false);
        init.visitFieldInsn(PUTFIELD, DISPATCHER, "items", "Ljava/util/Map;");
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        MethodVisitor put = cw.visitMethod(0, "put", "(L" + ITEM + ";L" + INTERACTION + ";)V", null, null);
        put.visitCode();
        put.visitVarInsn(ALOAD, 0);
        put.visitFieldInsn(GETFIELD, DISPATCHER, "items", "Ljava/util/Map;");
        put.visitVarInsn(ALOAD, 1);
        put.visitVarInsn(ALOAD, 2);
        put.visitMethodInsn(INVOKEINTERFACE, "java/util/Map", "put",
                "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true);
        put.visitInsn(POP);
        put.visitInsn(RETURN);
        put.visitMaxs(0, 0);
        put.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
