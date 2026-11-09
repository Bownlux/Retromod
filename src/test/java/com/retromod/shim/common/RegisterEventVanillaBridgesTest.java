/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.util.CheckClassAdapter;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** The vanilla shapes Farmer's Delight's registration reached on Forge 26.2, bridged on every loader. */
class RegisterEventVanillaBridgesTest {

    private static final String PROPS = "Lnet/minecraft/world/level/block/state/BlockBehaviour$Properties;";
    private static final String SOUND = "Lnet/minecraft/sounds/SoundEvent;";
    private static final String WOOD = "Lnet/minecraft/world/level/block/state/properties/WoodType;";
    private static final String WOOD_TYPE = "net/minecraft/world/level/block/state/properties/WoodType";
    private static final String GATE = "net/minecraft/world/level/block/FenceGateBlock";
    private static final String SOUND_SHAPE = "(" + PROPS + SOUND + SOUND + ")V";

    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("Mth.color(r, g, b) packs floored channels with no alpha, as 1.20.1 did")
    void mthColorMatchesTheOldPacking() throws Exception {
        byte[] bytes = LegacyMthColorBridge.generate();
        new ClassReader(bytes).accept(new CheckClassAdapter(new ClassWriter(0), true), 0);
        Class<?> helper = new ClassLoader(getClass().getClassLoader()) {
            Class<?> define() {
                return defineClass(LegacyMthColorBridge.HELPER.replace('/', '.'), bytes, 0, bytes.length);
            }
        }.define();
        var color = helper.getMethod("color", float.class, float.class, float.class);

        assertEquals(0xFF7F00, color.invoke(null, 1.0f, 0.5f, 0.0f));
        assertEquals(old(0.2f, 0.4f, 0.6f), color.invoke(null, 0.2f, 0.4f, 0.6f));
        assertEquals(0, color.invoke(null, 0f, 0f, 0f));
    }

    @Test
    @DisplayName("a 1.21.2 or newer target gets the Mth.color redirect; a host that keeps it gets none")
    void mthColorFollowsTheHost() {
        RetromodVersion.TARGET_MC_VERSION = "26.2";
        assertTrue(LegacyMthColorBridge.hostLacksColor(null));
        RetromodVersion.TARGET_MC_VERSION = "1.21.1";
        assertFalse(LegacyMthColorBridge.hostLacksColor(null));
        ClassNode keeps = new ClassNode();
        keeps.methods.add(new MethodNode(0, "color", "(FFF)I", null, null));
        assertFalse(LegacyMthColorBridge.hostLacksColor(keeps));
        assertTrue(LegacyMthColorBridge.hostLacksColor(new ClassNode()));
    }

    @Test
    @DisplayName("a gate built from its sounds gets an oak wood type that plays them, open before close")
    void soundGateBuildsAWoodType() {
        String host = "(" + WOOD + PROPS + ")V";
        ClassNode gate = generated(Set.of(host), host);
        MethodNode sounds = constructor(gate, SOUND_SHAPE);

        MethodInsnNode wood = call(sounds, "retromod$soundWood");
        assertEquals("(" + SOUND + SOUND + ")" + WOOD, wood.desc);
        assertEquals(host, call(sounds, "<init>").desc, "the gate is built with the host's own constructor");
        MethodNode helper = gate.methods.stream().filter(m -> m.name.equals("retromod$soundWood")).findFirst().orElseThrow();
        MethodInsnNode record = call(helper, "<init>");
        assertEquals(WOOD_TYPE, record.owner);
        // WoodType lists the close sound before the open one, so the second sound goes first.
        int[] loads = Arrays.stream(helper.instructions.toArray())
                .filter(i -> i.getOpcode() == org.objectweb.asm.Opcodes.ALOAD)
                .mapToInt(i -> ((org.objectweb.asm.tree.VarInsnNode) i).var).toArray();
        assertArrayEquals(new int[] {1, 0}, loads);
    }

    @Test
    @DisplayName("a host that keeps the sound constructor, like NeoForge, gets it called straight through")
    void soundGatePassesThroughWhereTheHostHasIt() {
        String host = "(" + WOOD + PROPS + ")V";
        ClassNode gate = generated(Set.of(host, SOUND_SHAPE), host);
        MethodNode sounds = constructor(gate, SOUND_SHAPE);

        assertEquals(SOUND_SHAPE, call(sounds, "<init>").desc);
        assertTrue(gate.methods.stream().noneMatch(m -> m.name.equals("retromod$soundWood")),
                "no wood type is built when nothing needs it");
    }

    @Test
    @DisplayName("Forge 26.2's four-argument gate keeps the open sound ahead of the close sound")
    void sameTypedArgumentsKeepTheirOrder() {
        String forge = "(" + WOOD + PROPS + SOUND + SOUND + ")V";
        ClassNode gate = generated(Set.of(), forge);
        MethodNode sounds = constructor(gate, SOUND_SHAPE);

        int[] loads = Arrays.stream(sounds.instructions.toArray())
                .filter(i -> i.getOpcode() == org.objectweb.asm.Opcodes.ALOAD)
                .mapToInt(i -> ((org.objectweb.asm.tree.VarInsnNode) i).var).toArray();
        assertArrayEquals(new int[] {1, 2, 3}, Arrays.copyOfRange(loads, loads.length - 3, loads.length),
                "properties, then open, then close");
    }

    @Test
    @DisplayName("the random patch configuration and recipe interface are rebuilt only where the host lost them")
    void rebuiltClassesFollowTheTarget() {
        RetromodVersion.TARGET_MC_VERSION = "26.1";
        assertTrue(LegacyRandomPatchApi.hostRemovedRandomPatch());
        assertTrue(LegacyRecipeHolderApi.hostRenamedRecipeHolder());
        RetromodVersion.TARGET_MC_VERSION = "1.21.11";
        assertFalse(LegacyRandomPatchApi.hostRemovedRandomPatch());
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        assertFalse(LegacyRandomPatchApi.hostRemovedRandomPatch(), "26.3 has no configured features to implement");
        RetromodVersion.TARGET_MC_VERSION = "1.20.1";
        assertFalse(LegacyRecipeHolderApi.hostRenamedRecipeHolder());
    }

    @Test
    @DisplayName("the rebuilt classes register with their redirects")
    void rebuiltClassesRegister() {
        RetromodVersion.TARGET_MC_VERSION = "26.2";
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        LegacyRandomPatchApi.register(t);
        LegacyRecipeHolderApi.register(t);

        assertTrue(t.getSyntheticClasses().containsKey(LegacyRandomPatchApi.REBUILT));
        assertTrue(t.getSyntheticClasses().containsKey(LegacyRecipeHolderApi.REBUILT));
        assertEquals(LegacyRandomPatchApi.REBUILT, t.getClassRedirects().get(LegacyRandomPatchApi.OLD));
        assertEquals(LegacyRecipeHolderApi.REBUILT, t.getClassRedirects().get(LegacyRecipeHolderApi.OLD));
    }

    private static int old(float r, float g, float b) {
        return ((int) Math.floor(r * 255f) << 16) | ((int) Math.floor(g * 255f) << 8) | (int) Math.floor(b * 255f);
    }

    private static ClassNode generated(Set<String> hostShapes, String hostDesc) {
        byte[] bytes = LegacyBlockSetTypeBridge.generate("g/Gate", GATE, hostDesc, WOOD_TYPE, hostShapes,
                "(" + PROPS + ")V", "(" + PROPS + WOOD + ")V", "(" + WOOD + PROPS + ")V", SOUND_SHAPE);
        new ClassReader(bytes).accept(new CheckClassAdapter(new ClassWriter(0), false), 0);
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static MethodNode constructor(ClassNode node, String desc) {
        return node.methods.stream().filter(m -> m.name.equals("<init>") && m.desc.equals(desc))
                .findFirst().orElseThrow(() -> new AssertionError("no constructor " + desc));
    }

    private static MethodInsnNode call(MethodNode method, String name) {
        List<MethodInsnNode> calls = Arrays.stream(method.instructions.toArray())
                .filter(MethodInsnNode.class::isInstance).map(MethodInsnNode.class::cast)
                .filter(c -> c.name.equals(name)).toList();
        assertFalse(calls.isEmpty(), method.name + method.desc + " never calls " + name);
        return calls.get(0);
    }
}
