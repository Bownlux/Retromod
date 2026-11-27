/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** What a 1.20.1 mod builds while it constructs and registers, on hosts from 1.20.3 that changed it (#415). */
class Legacy1201RegistrationBridgesTest {

    private static final String SET_TYPE = "net/minecraft/world/level/block/state/properties/BlockSetType";
    private static final String TRUNK = "net/minecraft/world/level/levelgen/feature/trunkplacers/TrunkPlacerType";
    private static final String CODEC = "com/mojang/serialization/Codec";
    private static final String MAP_CODEC = "com/mojang/serialization/MapCodec";
    private static final String PLAYER_TRIGGER = "net/minecraft/advancements/critereon/PlayerTrigger";
    private static final String ID = "net/minecraft/resources/ResourceLocation";

    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;
    private RetromodTransformer transformer;

    @BeforeEach
    void setUp() {
        RetromodVersion.TARGET_MC_VERSION = "1.21.1";
        transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
    }

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("An item that implements the removed Vanishable gets an empty interface in its place")
    void vanishableBecomesAnEmptyInterface() {
        RemovedItemBaseBridge.registerRemovedIn(transformer, "1.20.5");
        String marker = transformer.getClassRedirects().get("net/minecraft/world/item/Vanishable");
        assertEquals("com/retromod/generated/LegacyVanishable", marker);
        ClassNode generated = node(transformer.getSyntheticClasses().get(marker));
        assertTrue((generated.access & Opcodes.ACC_INTERFACE) != 0);
        assertTrue(generated.methods.isEmpty(), "a marker has nothing to implement");

        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/mod/DarkBlade", null, "net/minecraft/world/item/Item",
                new String[]{"net/minecraft/world/item/Vanishable"});
        cw.visitEnd();
        ClassNode item = node(transformer.transformClass(cw.toByteArray(), "test/mod/DarkBlade.class"));
        assertEquals(List.of(marker), item.interfaces);
    }

    @Test
    @DisplayName("On a Fabric intermediary host the removed Vanishable takes the same empty interface")
    void intermediaryVanishableBecomesAnEmptyInterface() {
        RemovedItemBaseBridge.registerIntermediaryMarkers(transformer);
        assertEquals("com/retromod/generated/LegacyVanishable", transformer.getClassRedirects().get("net/minecraft/class_5150"));
        assertNotNull(transformer.getSyntheticClasses().get("com/retromod/generated/LegacyVanishable"));
    }

    @Test
    @DisplayName("A 1.20.1 BlockSetType goes through the 1.20.3 constructor, with settings that follow hand opening")
    void blockSetTypeUsesTheNewConstructor() {
        assertTrue(LegacyBlockSetTypeBridge.lacksOldSetTypeConstructor(withConstructors(SET_TYPE, LegacyBlockSetTypeBridge.SET_TYPE_CTOR)));
        assertFalse(LegacyBlockSetTypeBridge.lacksOldSetTypeConstructor(withConstructors(SET_TYPE,
                LegacyBlockSetTypeBridge.OLD_SET_TYPE_CTOR)), "1.20.1 and 1.20.2 keep the old record");
        LegacyBlockSetTypeBridge.registerSetTypeConstructor(transformer, withConstructors(SET_TYPE, LegacyBlockSetTypeBridge.SET_TYPE_CTOR));

        List<MethodInsnNode> calls = calls(transformer.transformClass(construct(SET_TYPE, LegacyBlockSetTypeBridge.OLD_SET_TYPE_CTOR),
                "test/mod/Sets.class"), "build");
        assertEquals(LegacyBlockSetTypeBridge.SET_TYPE_FACTORY, calls.get(0).owner);
        assertEquals(Opcodes.INVOKESTATIC, calls.get(0).getOpcode());
        List<MethodInsnNode> factory = calls(transformer.getSyntheticClasses().get(LegacyBlockSetTypeBridge.SET_TYPE_FACTORY), "create");
        assertEquals(LegacyBlockSetTypeBridge.SET_TYPE_CTOR, factory.get(0).desc, "the record's 1.20.3 canonical constructor");
    }

    @Test
    @DisplayName("A tree placer type built on a codec is built on that codec as a map codec")
    void placerTypeTakesAMapCodec() {
        LegacyRegistryMapCodecBridge.registerClasses(transformer);
        LegacyRegistryMapCodecBridge.registerWorldgenTypeConstructors(transformer,
                name -> name.equals(TRUNK) ? withConstructors(TRUNK, "(L" + MAP_CODEC + ";)V") : withConstructors(name, "(L" + CODEC + ";)V"));

        List<MethodInsnNode> calls = calls(transformer.transformClass(construct(TRUNK, "(L" + CODEC + ";)V"),
                "test/mod/Sets.class"), "build");
        assertEquals(LegacyRegistryMapCodecBridge.WORLDGEN_TYPE_FACTORY, calls.get(0).owner);
        assertEquals("(L" + CODEC + ";)L" + TRUNK + ";", calls.get(0).desc);
        List<MethodInsnNode> factory = calls(transformer.getSyntheticClasses().get(LegacyRegistryMapCodecBridge.WORLDGEN_TYPE_FACTORY), "create");
        assertEquals(List.of("of", "<init>"), factory.stream().map(c -> c.name).toList());
        assertEquals(1, node(transformer.getSyntheticClasses().get(LegacyRegistryMapCodecBridge.WORLDGEN_TYPE_FACTORY)).methods.size(),
                "a host that still takes a codec for the other types leaves them alone");
    }

    @Test
    @DisplayName("A PlayerTrigger built with its id is made through the helper that keeps the id for registration")
    void playerTriggerKeepsItsId() {
        RetromodVersion.TARGET_MC_VERSION = "1.21.1";
        LegacyCriterionTriggerBridge.register(transformer);
        List<MethodInsnNode> calls = calls(transformer.transformClass(construct(PLAYER_TRIGGER, "(L" + ID + ";)V"),
                "test/mod/Triggers.class"), "build");
        assertEquals("playerTrigger", calls.get(0).name);
        assertEquals("(L" + ID + ";)L" + PLAYER_TRIGGER + ";", calls.get(0).desc);
    }

    @Test
    @DisplayName("An MCreator procedure's four-argument addObjective goes to the 1.20.3 form with the command's defaults")
    void scoreboardObjectiveTakesTheNewForm() {
        ClassNode host = new ClassNode();
        host.methods.add(new MethodNode(Opcodes.ACC_PUBLIC, "addObjective", LegacyScoreboardBridge.NEW_ADD, null, null));
        assertTrue(LegacyScoreboardBridge.lacksOldAdd(host));
        LegacyScoreboardBridge.register(transformer, host);
        var target = transformer.getMethodRedirects().get(new RetromodTransformer.MethodKey(LegacyScoreboardBridge.SCOREBOARD,
                "addObjective", LegacyScoreboardBridge.OLD_ADD));
        assertNotNull(target);
        assertEquals(LegacyScoreboardBridge.HELPER, target.owner());
        List<MethodInsnNode> forwarded = calls(transformer.getSyntheticClasses().get(LegacyScoreboardBridge.HELPER), "addObjective");
        assertEquals(LegacyScoreboardBridge.NEW_ADD, forwarded.get(0).desc);
        host.methods.add(new MethodNode(Opcodes.ACC_PUBLIC, "addObjective", LegacyScoreboardBridge.OLD_ADD, null, null));
        assertFalse(LegacyScoreboardBridge.lacksOldAdd(host), "1.20.1 and 1.20.2 keep the old form");
    }

    private static ClassNode withConstructors(String name, String... descs) {
        ClassNode node = new ClassNode();
        node.name = name;
        for (String desc : descs) node.methods.add(new MethodNode(Opcodes.ACC_PUBLIC, "<init>", desc, null, null));
        return node;
    }

    /** {@code static Object build(args...)} that returns {@code new owner(args...)}, with each argument passed as null or zero. */
    private static byte[] construct(String owner, String ctorDesc) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/mod/Sets", null, "java/lang/Object", null);
        MethodVisitor m = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "build", "()Ljava/lang/Object;", null, null);
        m.visitCode();
        m.visitTypeInsn(Opcodes.NEW, owner);
        m.visitInsn(Opcodes.DUP);
        for (org.objectweb.asm.Type arg : org.objectweb.asm.Type.getArgumentTypes(ctorDesc)) {
            m.visitInsn(arg.getSort() == org.objectweb.asm.Type.OBJECT ? Opcodes.ACONST_NULL : Opcodes.ICONST_0);
        }
        m.visitMethodInsn(Opcodes.INVOKESPECIAL, owner, "<init>", ctorDesc, false);
        m.visitInsn(Opcodes.ARETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static List<MethodInsnNode> calls(byte[] bytes, String method) {
        List<MethodInsnNode> calls = new ArrayList<>();
        for (AbstractInsnNode insn : node(bytes).methods.stream().filter(m -> m.name.equals(method)).findFirst().orElseThrow().instructions) {
            if (insn instanceof MethodInsnNode call) calls.add(call);
        }
        return calls;
    }

    private static ClassNode node(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }
}
