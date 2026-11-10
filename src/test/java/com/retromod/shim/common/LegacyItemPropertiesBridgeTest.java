/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.mapping.IntermediaryToMojangMapper;
import com.retromod.shim.common.LegacyItemPropertiesFixtures.Loader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/** Old ItemProperties calls, lambdas and Fabric's names relink to the rebuilt classes, only for mods built before 1.21.4. */
class LegacyItemPropertiesBridgeTest {

    private static final String ITEM_PROPERTIES = LegacyItemPropertiesBridge.ITEM_PROPERTIES;
    private static final String FUNCTION = LegacyItemPropertiesBridge.FUNCTION;
    private static final String CLAMPED = LegacyItemPropertiesBridge.CLAMPED;
    private static final Function<String, ClassNode> NO_HOST = name -> null;

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;

    @TempDir
    Path dir;

    @BeforeEach
    void host261() {
        RetromodVersion.TARGET_MC_VERSION = "26.1";
        transformer.clearRedirectsForTesting();
    }

    @AfterEach
    void restore() {
        transformer.setModSourceVersion(null);
        transformer.clearRedirectsForTesting();
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
    }

    @Test
    @DisplayName("A 1.20.1 mod's register, registerGeneric and getProperty calls and its lambdas reach the rebuilt classes")
    void oldCallsReachTheRebuiltClasses() throws Exception {
        LegacyItemPropertiesBridge.register(transformer, NO_HOST);
        transformer.setModSourceVersion("1.20.1");

        ClassNode props = transform(Loader.FORGE_1201);

        assertEquals(List.of(ITEM_PROPERTIES + ".register(" + FUNCTION + ")", ITEM_PROPERTIES + ".register(" + FUNCTION + ")",
                ITEM_PROPERTIES + ".registerGeneric(" + FUNCTION + ")"), calls(method(props, "register")));
        assertEquals(List.of(FUNCTION, CLAMPED, FUNCTION), lambdas(method(props, "register")),
                "a clamped lambda keeps its clamp through the rebuilt default call");
        assertEquals(List.of(ITEM_PROPERTIES + ".getProperty()", FUNCTION + ".call()"), calls(method(props, "read")));
    }

    @Test
    @DisplayName("A mod built for 1.21.4 or later keeps the host's names")
    void newerModsKeepTheNames() throws Exception {
        LegacyItemPropertiesBridge.register(transformer, NO_HOST);
        transformer.setModSourceVersion("1.21.4");

        ClassNode props = transform(Loader.FORGE_1201);

        assertTrue(calls(method(props, "register")).stream().allMatch(call -> call.startsWith(LegacyItemPropertiesBridge.OLD_ITEM_PROPERTIES)),
                calls(method(props, "register")).toString());
    }

    @Test
    @DisplayName("A host that still has ItemProperties gets no redirect")
    void hostWithItemPropertiesIsLeftAlone() {
        LegacyItemPropertiesBridge.register(transformer, name -> name.equals(LegacyItemPropertiesBridge.OLD_ITEM_PROPERTIES)
                ? new ClassNode() : null);

        assertFalse(transformer.getClassRedirects().containsKey(LegacyItemPropertiesBridge.OLD_ITEM_PROPERTIES));
        assertFalse(transformer.getSyntheticClasses().containsKey(ITEM_PROPERTIES));
    }

    @Test
    @DisplayName("A host whose range properties still take a LivingEntity, as 1.21.4 to 1.21.8 did, gets no redirect")
    void livingEntityShapedHostIsLeftAlone() {
        ClassNode property = new ClassNode();
        property.methods.add(new MethodNode(org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_ABSTRACT, "get",
                "(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/client/multiplayer/ClientLevel;"
                        + "Lnet/minecraft/world/entity/LivingEntity;I)F", null, null));
        LegacyItemPropertiesBridge.register(transformer, name -> name.equals(LegacyItemPropertiesBridge.RANGE_PROPERTY) ? property : null);

        assertFalse(transformer.getClassRedirects().containsKey(LegacyItemPropertiesBridge.OLD_ITEM_PROPERTIES));
    }

    @Test
    @DisplayName("A Fabric 1.21.1 mod's class_1800 is the old function, not 1.21.4's range property, and Fabric API's registry relinks")
    void fabricNamesReachTheRebuiltClasses() throws Exception {
        IntermediaryToMojangMapper.applyTo(transformer);
        LegacyItemPropertiesBridge.register(transformer, NO_HOST);
        LegacyItemPropertiesBridge.registerFabric(transformer, NO_HOST);
        transformer.setModSourceVersion("1.21.1");

        ClassNode props = transform(Loader.FABRIC_1211);

        assertEquals(List.of(ITEM_PROPERTIES + ".register(" + CLAMPED + ")", ITEM_PROPERTIES + ".register(" + CLAMPED + ")",
                ITEM_PROPERTIES + ".registerGeneric(" + CLAMPED + ")"), calls(method(props, "register")),
                "class_5272.method_27879, Fabric API's register and class_5272.method_27881");
        assertEquals(List.of(CLAMPED, CLAMPED, CLAMPED), lambdas(method(props, "register")));
        assertEquals(List.of(ITEM_PROPERTIES + ".getProperty()", FUNCTION + ".call()"), calls(method(props, "read")),
                "class_5272.method_27878 and class_1800.call");
    }

    @Test
    @DisplayName("A Fabric mod built for 1.21.4 keeps class_1800 as the host's range property")
    void newerFabricModsKeepTheRangeProperty() throws Exception {
        IntermediaryToMojangMapper.applyTo(transformer);
        LegacyItemPropertiesBridge.registerFabric(transformer, NO_HOST);
        transformer.setModSourceVersion("1.21.4");

        ClassNode props = transform(Loader.FABRIC_1211);

        assertTrue(calls(method(props, "read")).contains(LegacyItemPropertiesBridge.RANGE_PROPERTY + ".call()"),
                calls(method(props, "read")).toString());
    }

    @Test
    @DisplayName("A mixin into ItemProperties, by Mojang or intermediary name, would land on the copy, so it is reported")
    void mixinIntoItemPropertiesIsReported() {
        IntermediaryToMojangMapper.applyTo(transformer);
        LegacyItemPropertiesBridge.register(transformer, NO_HOST);
        transformer.setModSourceVersion("1.20.1");

        assertEquals(LegacyItemPropertiesBridge.OLD_ITEM_PROPERTIES, LegacyItemPropertiesBridge.removedMixinTarget(
                List.of("net/minecraft/client/Minecraft", LegacyItemPropertiesBridge.OLD_ITEM_PROPERTIES),
                transformer::classRedirectForCurrentMod));
        assertEquals("net/minecraft/class_5272", LegacyItemPropertiesBridge.removedMixinTarget(
                List.of("net/minecraft/class_5272"), transformer::classRedirectForCurrentMod));
        assertNull(LegacyItemPropertiesBridge.removedMixinTarget(List.of("net/minecraft/client/Minecraft"),
                transformer::classRedirectForCurrentMod));
    }

    private ClassNode transform(Loader loader) throws Exception {
        Map<String, byte[]> classes = LegacyItemPropertiesFixtures.compile(dir, loader);
        byte[] bytes = transformer.transformClass(classes.get(LegacyItemPropertiesFixtures.PROPS),
                LegacyItemPropertiesFixtures.PROPS + ".class");
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    // Each call into the old API as owner.name(function type), the function type being the last argument, if any.
    private static List<String> calls(MethodNode method) {
        List<String> seen = new ArrayList<>();
        for (AbstractInsnNode insn : method.instructions) {
            if (!(insn instanceof MethodInsnNode call) || call.owner.startsWith("java/") || call.name.equals("getItem")) continue;
            Type[] arguments = Type.getArgumentTypes(call.desc);
            String last = arguments.length == 0 ? "" : arguments[arguments.length - 1].getInternalName();
            seen.add(call.owner + "." + call.name + "(" + (last.contains("Function") ? last : "") + ")");
        }
        return seen;
    }

    private static List<String> lambdas(MethodNode method) {
        List<String> seen = new ArrayList<>();
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof InvokeDynamicInsnNode indy) seen.add(Type.getReturnType(indy.desc).getInternalName());
        }
        return seen;
    }

    private static MethodNode method(ClassNode node, String name) {
        return node.methods.stream().filter(m -> m.name.equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError(node.name + " has no " + name));
    }
}
