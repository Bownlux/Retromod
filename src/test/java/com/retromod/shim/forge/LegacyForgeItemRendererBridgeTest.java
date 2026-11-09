/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.util.McReflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static com.retromod.shim.common.LegacyItemRendererBridge.CALLS;
import static com.retromod.shim.common.LegacyItemRendererBridge.HELPER_DESC;
import static com.retromod.shim.common.LegacyItemRendererBridge.MODEL;
import static com.retromod.shim.common.LegacyItemRendererBridge.RENDERER;
import static com.retromod.shim.forge.LegacyForgeItemRendererBridge.*;
import static com.retromod.shim.forge.LegacyForgeRenderBridgeTest.host;
import static com.retromod.shim.forge.LegacyForgeRenderBridgeTest.node;
import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** An old item renderer extends a base rebuilt inside the mod, and getCustomRenderer calls reach the mod's own method, on Forge 26.2 and newer only. */
class LegacyForgeItemRendererBridgeTest {

    static final String GEM_RENDERER = "mod/GemRenderer";
    static final String GEM_PROPERTIES = "mod/GemRenderProperties";
    static final String DISPATCHER = "net/minecraft/client/renderer/blockentity/BlockEntityRenderDispatcher";
    static final String MODELS = "net/minecraft/client/model/geom/EntityModelSet";
    static final String OLD_SUPER_DESC = "(L" + DISPATCHER + ";L" + MODELS + ";)V";
    static final String OLD_BUFFERS = "net/minecraft/client/renderer/MultiBufferSource";
    static final String OLD_RENDER_DESC = "(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;"
            + "Lcom/mojang/blaze3d/vertex/PoseStack;L" + OLD_BUFFERS + ";II)V";

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;
    private final boolean savedForge = McReflect.isForceForge();
    private final boolean savedNeoForge = McReflect.isForceNeoForge();

    @BeforeEach
    void forgeTarget() {
        McReflect.setForceNeoForge(false);
        McReflect.setForceForge(true);
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

    @ParameterizedTest(name = "Forge {0}")
    @ValueSource(strings = {"26.2", "26.3"})
    @DisplayName("The Forge 26.2 shim registers the rebuilt item renderer, which carries on to Forge 26.3")
    void forgeHostsFrom262TakeTheBridge(String host) {
        RetromodVersion.TARGET_MC_VERSION = host;
        new Forge_26_1_to_26_2().registerRedirects(transformer);

        assertEquals(RENDERER, transformer.getClassRedirects().get(OLD_RENDERER));
        Set<String> synthetics = transformer.getSyntheticClasses().keySet();
        for (String rebuilt : List.of(RENDERER, CALLS, INSTALLER, MODEL, ROOT + "LegacyItemRendererTypes",
                com.retromod.shim.common.LegacyItemRendererBridge.ROOT + "LegacyItemGeometry",
                LegacyClientRenderBootstrapInjector.BOOTSTRAP)) {
            assertTrue(synthetics.contains(rebuilt), rebuilt + " is registered for embedding");
        }
    }

    @Test
    @DisplayName("NeoForge, other loaders, Forge 1.21.11 and 26.1, and a Forge host with the old base or getter take nothing")
    void otherHostsAreRefused() {
        RetromodVersion.TARGET_MC_VERSION = "26.2";
        McReflect.setForceForge(false);
        McReflect.setForceNeoForge(true);
        new Forge_26_1_to_26_2().registerRedirects(transformer);
        LegacyForgeItemRendererBridge.register(transformer);
        assertNull(transformer.getClassRedirects().get(OLD_RENDERER), "NeoForge keeps its own item extensions");

        McReflect.setForceNeoForge(false);
        LegacyForgeItemRendererBridge.register(transformer);
        assertNull(transformer.getClassRedirects().get(OLD_RENDERER), "a Fabric or Quilt run takes no Forge bridge");

        McReflect.setForceForge(true);
        for (String older : List.of("1.21.11", "26.1")) {
            RetromodVersion.TARGET_MC_VERSION = older;
            LegacyForgeItemRendererBridge.register(transformer);
            assertNull(transformer.getClassRedirects().get(OLD_RENDERER), "Forge " + older + " is not a checked host");
        }

        RetromodVersion.TARGET_MC_VERSION = "26.2";
        LegacyForgeItemRendererBridge.register(transformer, host(ForgeHostShape.ANCHOR, CLIENT_ITEM_EXTENSIONS, OLD_RENDERER));
        assertNull(transformer.getClassRedirects().get(OLD_RENDERER), "a host that still has the old base keeps it");
        LegacyForgeItemRendererBridge.register(transformer, name -> name.equals(CLIENT_ITEM_EXTENSIONS) ? extensionsWithGetter()
                : name.equals(ForgeHostShape.ANCHOR) ? new ClassNode() : null);
        assertNull(transformer.getClassRedirects().get(OLD_RENDERER), "a host whose extensions still have getCustomRenderer");
        LegacyForgeItemRendererBridge.register(transformer, host(ForgeHostShape.ANCHOR, CLIENT_ITEM_EXTENSIONS));
        assertEquals(RENDERER, transformer.getClassRedirects().get(OLD_RENDERER), "Forge 26.2's own shape");
    }

    @Test
    @DisplayName("A Forge 1.20.1 renderer extends the rebuilt base, whose old constructor and renderByItem it links to and overrides")
    void oldRendererTakesTheRebuiltBase() {
        register("1.20.1");
        ClassNode out = node(transformer.transformClass(gemRenderer(), GEM_RENDERER + ".class"));

        assertEquals(RENDERER, out.superName, "the renderer extends the rebuilt base");
        MethodInsnNode superCall = calls(method(out, "<init>")).stream().filter(call -> call.getOpcode() == INVOKESPECIAL)
                .findFirst().orElseThrow();
        assertEquals(RENDERER + ".<init>" + OLD_SUPER_DESC, superCall.owner + "." + superCall.name + superCall.desc,
                "the old super(dispatcher, models) call");
        ClassNode base = node(transformer.getSyntheticClasses().get(RENDERER));
        assertTrue(base.methods.stream().anyMatch(m -> m.name.equals("<init>") && m.desc.equals(OLD_SUPER_DESC)),
                "the rebuilt base has the constructor the old call names");
        String override = method(out, "renderByItem").desc;
        assertTrue(base.methods.stream().anyMatch(m -> m.name.equals("renderByItem") && m.desc.equals(override)),
                "the override keeps the base's descriptor, so Forge 1.20.1's virtual call reaches it: " + override);
        assertFalse(override.contains(OLD_BUFFERS), "MultiBufferSource is the stand-in in both");
    }

    @Test
    @DisplayName("getCustomRenderer calls on the host interface reach the reflective helper, and a super call the old default")
    void getterCallsReachTheHelper() {
        register("1.20.1");
        ClassNode out = node(transformer.transformClass(gemProperties(), GEM_PROPERTIES + ".class"));

        assertEquals("()L" + RENDERER + ";", method(out, GET_CUSTOM_RENDERER).desc, "the mod's own getter returns the rebuilt base");
        assertEquals(List.of(CALLS + ".getCustomRenderer" + HELPER_DESC + " static"), describe(calls(method(out, "drawWith"))));
        assertEquals(List.of(CALLS + ".superGetCustomRenderer" + HELPER_DESC + " static"), describe(calls(method(out, "fallback"))),
                "the interface default, not the override again");
        TypeInsnNode check = (TypeInsnNode) java.util.Arrays.stream(method(out, "isOurs").instructions.toArray())
                .filter(insn -> insn.getOpcode() == INSTANCEOF).findFirst().orElseThrow();
        assertEquals(RENDERER, check.desc, "an instanceof on the old base tests the rebuilt one");
    }

    @Test
    @DisplayName("A mod built for Forge 26.2 keeps the names, and its getter call is left alone")
    void modBuiltFor262KeepsNames() {
        register("26.2");
        ClassNode renderer = node(transformer.transformClass(gemRenderer(), GEM_RENDERER + ".class"));
        assertEquals(OLD_RENDERER, renderer.superName);
        ClassNode properties = node(transformer.transformClass(gemProperties(), GEM_PROPERTIES + ".class"));
        assertEquals(List.of(CLIENT_ITEM_EXTENSIONS + ".getCustomRenderer" + OLD_GETTER_DESC + " interface"),
                describe(calls(method(properties, "drawWith"))));
    }

    private void register(String source) {
        RetromodVersion.TARGET_MC_VERSION = "26.2";
        new Forge_26_1_to_26_2().registerRedirects(transformer);
        transformer.setModSourceVersion(source);
    }

    private static ClassNode extensionsWithGetter() {
        ClassNode node = new ClassNode();
        node.methods.add(new MethodNode(ACC_PUBLIC, GET_CUSTOM_RENDERER, OLD_GETTER_DESC, null, null));
        return node;
    }

    /** {@code class GemRenderer extends BlockEntityWithoutLevelRenderer}, as a Forge 1.20.1 mod writes one. */
    static byte[] gemRenderer() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, GEM_RENDERER, null, OLD_RENDERER, null);
        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitInsn(ACONST_NULL);
        init.visitInsn(ACONST_NULL);
        init.visitMethodInsn(INVOKESPECIAL, OLD_RENDERER, "<init>", OLD_SUPER_DESC, false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        MethodVisitor render = cw.visitMethod(ACC_PUBLIC, "renderByItem", OLD_RENDER_DESC, null, null);
        render.visitCode();
        render.visitInsn(RETURN);
        render.visitMaxs(0, 0);
        render.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** An IClientItemExtensions with its own getCustomRenderer, a call through the host interface, a super call and an instanceof. */
    static byte[] gemProperties() {
        return gemProperties(CLIENT_ITEM_EXTENSIONS);
    }

    /** The same render properties on {@code extensions}: Forge's IClientItemExtensions or NeoForge's. */
    static byte[] gemProperties(String extensions) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, GEM_PROPERTIES, null, "java/lang/Object", new String[]{extensions});
        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        MethodVisitor getter = cw.visitMethod(ACC_PUBLIC, GET_CUSTOM_RENDERER, OLD_GETTER_DESC, null, null);
        getter.visitCode();
        getter.visitTypeInsn(NEW, GEM_RENDERER);
        getter.visitInsn(DUP);
        getter.visitMethodInsn(INVOKESPECIAL, GEM_RENDERER, "<init>", "()V", false);
        getter.visitInsn(ARETURN);
        getter.visitMaxs(0, 0);
        getter.visitEnd();
        MethodVisitor draw = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "drawWith", "(L" + extensions + ";)L" + OLD_RENDERER + ";",
                null, null);
        draw.visitCode();
        draw.visitVarInsn(ALOAD, 0);
        draw.visitMethodInsn(INVOKEINTERFACE, extensions, GET_CUSTOM_RENDERER, OLD_GETTER_DESC, true);
        draw.visitInsn(ARETURN);
        draw.visitMaxs(0, 0);
        draw.visitEnd();
        MethodVisitor fallback = cw.visitMethod(ACC_PUBLIC, "fallback", OLD_GETTER_DESC, null, null);
        fallback.visitCode();
        fallback.visitVarInsn(ALOAD, 0);
        fallback.visitMethodInsn(INVOKESPECIAL, extensions, GET_CUSTOM_RENDERER, OLD_GETTER_DESC, true);
        fallback.visitInsn(ARETURN);
        fallback.visitMaxs(0, 0);
        fallback.visitEnd();
        MethodVisitor isOurs = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "isOurs", "(Ljava/lang/Object;)Z", null, null);
        isOurs.visitCode();
        isOurs.visitVarInsn(ALOAD, 0);
        isOurs.visitTypeInsn(INSTANCEOF, OLD_RENDERER);
        isOurs.visitInsn(IRETURN);
        isOurs.visitMaxs(0, 0);
        isOurs.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    static MethodNode method(ClassNode node, String name) {
        return node.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow(() -> new AssertionError(name));
    }

    static List<MethodInsnNode> calls(MethodNode method) {
        List<MethodInsnNode> calls = new ArrayList<>();
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call && !call.owner.startsWith("java/")) calls.add(call);
        }
        return calls;
    }

    private static List<String> describe(List<MethodInsnNode> calls) {
        List<String> described = new ArrayList<>();
        for (MethodInsnNode call : calls) {
            String kind = switch (call.getOpcode()) {
                case INVOKESTATIC -> "static";
                case INVOKEINTERFACE -> "interface";
                case INVOKESPECIAL -> "special";
                default -> "virtual";
            };
            described.add(call.owner + "." + call.name + call.desc + " " + kind);
        }
        return described;
    }
}
