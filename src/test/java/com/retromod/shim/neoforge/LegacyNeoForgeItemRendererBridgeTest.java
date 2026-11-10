/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.neoforge;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.shim.common.LegacyItemRendererBridge;
import com.retromod.util.McReflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static com.retromod.shim.neoforge.LegacyNeoForgeItemRendererBridge.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** On NeoForge 26.1 and newer, an old item renderer extends the rebuilt base and getCustomRenderer calls reach the mod's own method. */
class LegacyNeoForgeItemRendererBridgeTest {

    private static final String OLD_RENDERER = LegacyItemRendererBridge.OLD_RENDERER;
    private static final String RENDERER = LegacyItemRendererBridge.RENDERER;
    private static final String GETTER_DESC = LegacyItemRendererBridge.OLD_GETTER_DESC;
    private static final String RENDER_PREFIX = "(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;"
            + "Lcom/mojang/blaze3d/vertex/PoseStack;L";
    private static final String PROPERTIES = "mod/GemRenderProperties";

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;
    private final boolean savedForge = McReflect.isForceForge();
    private final boolean savedNeoForge = McReflect.isForceNeoForge();

    @BeforeEach
    void neoForgeTarget() {
        McReflect.setForceForge(false);
        McReflect.setForceNeoForge(true);
        transformer.clearRedirectsForTesting();
    }

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        McReflect.setForceForge(savedForge);
        McReflect.setForceNeoForge(savedNeoForge);
        transformer.setModSourceVersion(null);
        transformer.clearRedirectsForTesting();
    }

    @ParameterizedTest(name = "NeoForge {0}")
    @ValueSource(strings = {"26.1", "26.2", "26.3"})
    @DisplayName("The NeoForge 26.1 shim registers the bridge, with the host's own buffer source on 26.1 and Retromod's stand-in after")
    void neoForgeHostsFrom261TakeTheBridge(String host) {
        RetromodVersion.TARGET_MC_VERSION = host;
        new NeoForge_1_21_11_to_26_1().registerRedirects(transformer);

        assertEquals(RENDERER, transformer.getClassRedirects().get(OLD_RENDERER));
        for (String rebuilt : List.of(RENDERER, LegacyItemRendererBridge.CALLS, LegacyItemRendererBridge.MODEL, INSTALLER,
                INSTALLER.replace("LegacyItemRenderers", "LegacyItemModelTypes"))) {
            assertTrue(transformer.getSyntheticClasses().containsKey(rebuilt), rebuilt + " is registered for embedding");
        }
        String buffers = host.equals("26.1") ? HOST_BUFFERS : "com/retromod/shim/common/embedded/MultiBufferSource";
        assertTrue(node(transformer.getSyntheticClasses().get(RENDERER)).methods.stream()
                        .anyMatch(m -> m.name.equals("renderByItem") && m.desc.startsWith(RENDER_PREFIX + buffers + ";")),
                "the rebuilt base's renderByItem takes " + buffers + ", as old renderers there do");
    }

    @Test
    @DisplayName("Forge, Fabric, NeoForge 1.21.11, and NeoForge hosts with the old base, the old getter or the 1.21.x bake take nothing")
    void otherHostsAreRefused() {
        RetromodVersion.TARGET_MC_VERSION = "26.2";
        McReflect.setForceNeoForge(false);
        McReflect.setForceForge(true);
        LegacyNeoForgeItemRendererBridge.register(transformer);
        assertNull(transformer.getClassRedirects().get(OLD_RENDERER), "a Forge target takes the Forge bridge instead");
        McReflect.setForceForge(false);
        LegacyNeoForgeItemRendererBridge.register(transformer);
        assertNull(transformer.getClassRedirects().get(OLD_RENDERER), "a Fabric or Quilt run takes no NeoForge bridge");

        McReflect.setForceNeoForge(true);
        RetromodVersion.TARGET_MC_VERSION = "1.21.11";
        LegacyNeoForgeItemRendererBridge.register(transformer);
        assertNull(transformer.getClassRedirects().get(OLD_RENDERER), "NeoForge 1.21.11 bakes item models another way");

        RetromodVersion.TARGET_MC_VERSION = "26.2";
        assertFalse(hostTakesBridge(host(Map.of(ANCHOR, plain()))), "no item model event");
        assertFalse(hostTakesBridge(host(Map.of(ANCHOR, plain(), REGISTER_ITEM_MODELS, plain(), NEO_EXTENSIONS, plain(),
                OLD_RENDERER, plain()))), "a host that still has the old base");
        assertFalse(hostTakesBridge(host(Map.of(ANCHOR, plain(), REGISTER_ITEM_MODELS, plain(),
                NEO_EXTENSIONS, withMethod("getCustomRenderer", GETTER_DESC)))), "a host whose extensions still have the getter");
        assertFalse(hostTakesBridge(host(Map.of(ANCHOR, plain(), REGISTER_ITEM_MODELS, plain(), NEO_EXTENSIONS, plain(),
                UNBAKED, withMethod("bake", "(Lnet/minecraft/client/renderer/item/ItemModel$BakingContext;)"
                        + "Lnet/minecraft/client/renderer/item/ItemModel;")))), "NeoForge 1.21.11's bake without a transformation");
        assertTrue(hostTakesBridge(host(Map.of(ANCHOR, plain(), REGISTER_ITEM_MODELS, plain(), NEO_EXTENSIONS, plain(),
                UNBAKED, withMethod("bake", BAKE_DESC)))), "NeoForge 26.2's own shape");
        assertTrue(hostTakesBridge(host(Map.of(ANCHOR, plain(), REGISTER_ITEM_MODELS, plain(), NEO_EXTENSIONS, plain()))),
                "a dedicated server reads no client class, so the version decides");
        assertTrue(hostKeepsBuffers(host(Map.of(ANCHOR, plain(), UNBAKED, plain(), HOST_BUFFERS, plain()))), "NeoForge 26.1");
        assertFalse(hostKeepsBuffers(host(Map.of(ANCHOR, plain(), UNBAKED, plain()))), "NeoForge 26.2");
    }

    @Test
    @DisplayName("A NeoForge 1.21.1 mod's and a Forge 1.20.1 mod's getter calls reach the helper; a mod built for 1.21.4 keeps its call")
    void getterCallsFromBothLoaders() {
        RetromodVersion.TARGET_MC_VERSION = "26.2";
        LegacyNeoForgeItemRendererBridge.register(transformer);
        String helper = LegacyItemRendererBridge.CALLS + ".getCustomRenderer" + LegacyItemRendererBridge.HELPER_DESC;
        for (String owner : List.of(NEO_EXTENSIONS, FORGE_EXTENSIONS)) {
            transformer.setModSourceVersion(owner.equals(NEO_EXTENSIONS) ? "1.21.1" : "1.20.1");
            ClassNode out = node(transformer.transformClass(properties(owner), PROPERTIES + ".class"));
            assertEquals("()L" + RENDERER + ";", method(out, "getCustomRenderer").desc, owner);
            assertEquals(List.of(helper), calls(method(out, "drawWith")), "a call on " + owner);
        }
        transformer.setModSourceVersion("1.21.4");
        ClassNode kept = node(transformer.transformClass(properties(NEO_EXTENSIONS), PROPERTIES + ".class"));
        assertEquals(List.of(NEO_EXTENSIONS + ".getCustomRenderer" + GETTER_DESC), calls(method(kept, "drawWith")));
    }

    @Test
    @DisplayName("A jar's @Mod constructors run NeoForge's install only when the jar names the old base")
    void installOnlyWhereTheOldBaseIsNamed() {
        assertNotNull(plan(Map.of(PROPERTIES, properties(NEO_EXTENSIONS)), Map.of("mod/GemMod", "gemmod")));
        assertNull(plan(Map.of("mod/Other", plainClass("mod/Other")), Map.of("mod/GemMod", "gemmod")));
    }

    // An IClientItemExtensions with its own getCustomRenderer and a call through the host interface.
    private static byte[] properties(String owner) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, PROPERTIES, null, "java/lang/Object", new String[]{owner});
        MethodVisitor getter = cw.visitMethod(ACC_PUBLIC, "getCustomRenderer", GETTER_DESC, null, null);
        getter.visitCode();
        getter.visitInsn(ACONST_NULL);
        getter.visitInsn(ARETURN);
        getter.visitMaxs(0, 0);
        getter.visitEnd();
        MethodVisitor draw = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "drawWith", "(L" + owner + ";)L" + OLD_RENDERER + ";", null, null);
        draw.visitCode();
        draw.visitVarInsn(ALOAD, 0);
        draw.visitMethodInsn(INVOKEINTERFACE, owner, "getCustomRenderer", GETTER_DESC, true);
        draw.visitInsn(ARETURN);
        draw.visitMaxs(0, 0);
        draw.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] plainClass(String name) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, name, null, "java/lang/Object", null);
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static Function<String, ClassNode> host(Map<String, ClassNode> classes) {
        return classes::get;
    }

    private static ClassNode plain() {
        return new ClassNode();
    }

    private static ClassNode withMethod(String name, String desc) {
        ClassNode node = new ClassNode();
        node.methods.add(new MethodNode(ACC_PUBLIC | ACC_ABSTRACT, name, desc, null, null));
        return node;
    }

    private static ClassNode node(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static MethodNode method(ClassNode node, String name) {
        return node.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow(() -> new AssertionError(name));
    }

    private static List<String> calls(MethodNode method) {
        List<String> calls = new ArrayList<>();
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call) calls.add(call.owner + "." + call.name + call.desc);
        }
        return calls;
    }
}
