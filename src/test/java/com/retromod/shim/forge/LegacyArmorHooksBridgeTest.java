/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.shim.neoforge.LegacyNeoForgeClientApiBridgeTest;
import com.retromod.util.McReflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** Old armor hooks, ender mask checks and PlayerRenderer reach the Forge-family 26.x hosts, and old overrides get the new hooks. */
class LegacyArmorHooksBridgeTest {

    private static final String NEO = "net/neoforged/neoforge/";
    private static final String FORGE = "net/minecraftforge/";
    private static final String NEO_EXT = NEO + "client/extensions/common/IClientItemExtensions";
    private static final String FORGE_EXT = FORGE + "client/extensions/common/IClientItemExtensions";
    private static final String OLD_HUMANOID = LegacyArmorOverrideAdapter.OLD_HUMANOID;
    private static final String OLD_GENERIC = LegacyArmorOverrideAdapter.OLD_GENERIC;
    private static final String ENDERMAN = "Lnet/minecraft/world/entity/monster/Enderman;";

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;
    private final boolean savedForge = McReflect.isForceForge();
    private final boolean savedNeoForge = McReflect.isForceNeoForge();

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        McReflect.setForceForge(savedForge);
        McReflect.setForceNeoForge(savedNeoForge);
        transformer.setModSourceVersion(null);
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("On NeoForge 26.3 old extension, client hook and item calls go to the armor helpers, and super calls to their old defaults")
    void neoForgeCallsGoToHelpers() {
        registerNeoForge("26.3");
        String hooks = LegacyArmorHooksBridge.NEO_HOOKS;
        String self = "(L" + NEO_EXT + ";";
        assertEquals(hooks + ".getHumanoidArmorModel" + self + OLD_HUMANOID.substring(1), call(INVOKEINTERFACE, NEO_EXT, "getHumanoidArmorModel", OLD_HUMANOID));
        assertEquals(hooks + ".getGenericArmorModel" + self + OLD_GENERIC.substring(1), call(INVOKEINTERFACE, FORGE_EXT, "getGenericArmorModel", OLD_GENERIC));
        assertEquals(hooks + ".getHumanoidArmorModelSuper" + self + OLD_HUMANOID.substring(1), call(INVOKESPECIAL, NEO_EXT, "getHumanoidArmorModel", OLD_HUMANOID),
                "an override's super call answers the old default instead of running the override again");
        String layerTint = "(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/entity/LivingEntity;" + LegacyArmorHooksBridge.OLD_LAYER + "II)I";
        assertTrue(call(INVOKEINTERFACE, NEO_EXT, "getArmorLayerTintColor", layerTint).startsWith(hooks + ".getArmorLayerTintColor("));
        assertTrue(call(INVOKEINTERFACE, NEO_EXT, "applyForgeHandTransform", "(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/player/LocalPlayer;"
                + "Lnet/minecraft/world/entity/HumanoidArm;Lnet/minecraft/world/item/ItemStack;FFF)Z").startsWith(hooks + ".applyForgeHandTransform("));
        String texture = "(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/item/ItemStack;" + LegacyArmorHooksBridge.OLD_LAYER
                + "ZLnet/minecraft/world/entity/EquipmentSlot;)Lnet/minecraft/resources/Identifier;";
        assertTrue(call(INVOKESTATIC, NEO + "client/ClientHooks", "getArmorTexture", texture).startsWith(hooks + ".getArmorTexture("));
        assertEquals(LegacyArmorHooksBridge.TEXTURES + ".hooksTexture" + LegacyArmorHooksBridge.OLD_HOOKS_TEXTURE,
                call(INVOKESTATIC, FORGE + "client/ForgeHooksClient", "getArmorTexture", LegacyArmorHooksBridge.OLD_HOOKS_TEXTURE));
        assertEquals(LegacyArmorHooksBridge.TEXTURES + ".itemTexture(Ljava/lang/Object;" + LegacyArmorHooksBridge.OLD_ITEM_TEXTURE.substring(1),
                call(INVOKEVIRTUAL, "net/minecraft/world/item/Item", "getArmorTexture", LegacyArmorHooksBridge.OLD_ITEM_TEXTURE));
        assertEquals(LegacyArmorHooksBridge.TEXTURES + ".itemTextureSuper(Ljava/lang/Object;" + LegacyArmorHooksBridge.OLD_ITEM_TEXTURE.substring(1),
                call(INVOKESPECIAL, "com/retromod/generated/LegacyArmorItem", "getArmorTexture", LegacyArmorHooksBridge.OLD_ITEM_TEXTURE),
                "an armor item's super call names the generated armor base");
        assertTrue(transformer.getSyntheticClasses().containsKey(hooks), "the helper is embedded");
    }

    @Test
    @DisplayName("NeoForge 26.1 and 26.2 keep the hand transform that takes the local player")
    void earlierNeoForgeKeepsTheHandTransform() {
        registerNeoForge("26.2");
        String hand = "(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/player/LocalPlayer;Lnet/minecraft/world/entity/HumanoidArm;"
                + "Lnet/minecraft/world/item/ItemStack;FFF)Z";
        assertEquals(NEO_EXT + ".applyForgeHandTransform" + hand, call(INVOKEINTERFACE, NEO_EXT, "applyForgeHandTransform", hand));
    }

    @Test
    @DisplayName("On Forge 26.2 and 26.3 the old model hooks and ForgeHooksClient calls go to the Forge helper")
    void forgeCallsGoToHelpers() {
        for (String host : List.of("26.2", "26.3")) forgeCallsGoToHelpers(host);
    }

    private void forgeCallsGoToHelpers(String host) {
        registerForge(host);
        String hooks = LegacyArmorHooksBridge.FORGE_HOOKS;
        assertEquals(hooks + ".getHumanoidArmorModel(L" + FORGE_EXT + ";" + OLD_HUMANOID.substring(1),
                call(INVOKEINTERFACE, FORGE_EXT, "getHumanoidArmorModel", OLD_HUMANOID));
        assertEquals(hooks + ".getArmorModel" + LegacyArmorHooksBridge.OLD_MODEL_ARGS + LegacyArmorOverrideAdapter.MODEL,
                call(INVOKESTATIC, FORGE + "client/ForgeHooksClient", "getArmorModel", LegacyArmorHooksBridge.OLD_MODEL_ARGS + LegacyArmorOverrideAdapter.MODEL));
        assertEquals(LegacyArmorHooksBridge.TEXTURES + ".itemTextureSuper(Ljava/lang/Object;" + LegacyArmorHooksBridge.OLD_ITEM_TEXTURE.substring(1),
                call(INVOKESPECIAL, "net/minecraft/world/item/Item", "getArmorTexture", LegacyArmorHooksBridge.OLD_ITEM_TEXTURE));
    }

    @Test
    @DisplayName("Old isEnderMask calls ask NeoForge's gaze disguise and Forge's monster disguise, under either enderman name")
    void enderMaskCalls() {
        registerNeoForge("26.3");
        assertEquals("net/minecraft/world/item/ItemStack.isGazeDisguise(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/entity/LivingEntity;)Z",
                call(INVOKEVIRTUAL, "net/minecraft/world/item/ItemStack", "isEnderMask", "(Lnet/minecraft/world/entity/player/Player;" + ENDERMAN + ")Z"));
        registerForge("26.2");
        assertEquals("net/minecraft/world/item/ItemStack.isMonsterDisguise(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/entity/monster/Monster;)Z",
                call(INVOKEVIRTUAL, "net/minecraft/world/item/ItemStack", "isEnderMask",
                        "(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/entity/monster/EnderMan;)Z"));
        registerForge("26.3");
        assertEquals("net/minecraft/world/item/ItemStack.isMonsterDisguise(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/entity/monster/Monster;)Z",
                call(INVOKEVIRTUAL, "net/minecraft/world/item/ItemStack", "isEnderMask", "(Lnet/minecraft/world/entity/player/Player;" + ENDERMAN + ")Z"),
                "26.3 renamed EnderMan to Enderman");
        // The test's calling class extends Object, so only the name and descriptor of the super call are checked.
        assertTrue(call(INVOKESPECIAL, "net/minecraft/world/item/Item", "isEnderMask",
                        "(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/entity/player/Player;" + ENDERMAN + ")Z")
                .endsWith(".isMonsterDisguise(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/entity/player/Player;"
                        + "Lnet/minecraft/world/entity/monster/Monster;)Z"), "an override's super call asks the inherited disguise check");
    }

    @Test
    @DisplayName("A mixin on PlayerRenderer keeps its target and its PlayerRenderer types through the mixin pass and the class pass")
    void playerRendererMixinStaysOnItsTarget() {
        registerNeoForge("26.3");
        String renderer = LegacyAvatarRendererBridge.PLAYER_RENDERER;
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V17, ACC_PUBLIC | ACC_ABSTRACT, "mod/mixin/PlayerRendererMixin", null, "java/lang/Object", null);
        AnnotationVisitor mixin = cw.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false);
        AnnotationVisitor targets = mixin.visitArray("value");
        targets.visit(null, Type.getObjectType(renderer));
        targets.visitEnd();
        mixin.visitEnd();
        MethodVisitor handler = cw.visitMethod(ACC_PRIVATE, "onRenderHand", "(Lcom/mojang/blaze3d/vertex/PoseStack;L" + renderer
                + ";Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V", null, null);
        AnnotationVisitor inject = handler.visitAnnotation("Lorg/spongepowered/asm/mixin/injection/Inject;", true);
        AnnotationVisitor method = inject.visitArray("method");
        method.visit(null, "renderRightHand");
        method.visitEnd();
        inject.visitEnd();
        handler.visitCode();
        handler.visitInsn(RETURN);
        handler.visitMaxs(0, 4);
        handler.visitEnd();
        cw.visitEnd();
        byte[] bytes = cw.toByteArray();

        byte[] mixed = new com.retromod.mixin.MixinCompatibilityTransformer(transformer).transformMixinClass(bytes);
        ClassNode out = new ClassNode();
        try (var scope = transformer.pushJarClassBytesProvider(Map.of("mod/mixin/PlayerRendererMixin", mixed)::get)) {
            new ClassReader(transformer.transformClass(mixed, "mod/mixin/PlayerRendererMixin")).accept(out, 0);
        }
        AnnotationNode kept = out.invisibleAnnotations.stream().filter(a -> a.desc.equals("Lorg/spongepowered/asm/mixin/Mixin;")).findFirst().orElseThrow();
        assertEquals(List.of(Type.getObjectType(renderer)), kept.values.get(kept.values.indexOf("value") + 1),
                "the mixin keeps its old target, which the host lacks, so Mixin skips it as before");
        assertTrue(out.methods.stream().anyMatch(m -> m.name.equals("onRenderHand") && m.desc.contains("L" + renderer + ";")),
                "its handler keeps the old type, never AvatarRenderer");
    }

    @Test
    @DisplayName("PlayerRenderer becomes AvatarRenderer for old mods, its old arm draws and offset go to helpers, and a mixin on it stays inert")
    void playerRendererIsRenamed() {
        registerNeoForge("26.3");
        String draw = "(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/client/player/AbstractClientPlayer;)V";
        assertTrue(call(INVOKEVIRTUAL, LegacyAvatarRendererBridge.PLAYER_RENDERER, "renderRightHand", draw)
                .startsWith(LegacyAvatarRendererBridge.MEMBERS + ".renderRightHand(L" + LegacyAvatarRendererBridge.AVATAR_RENDERER + ";"));
        assertEquals(LegacyAvatarRendererBridge.AVATAR_RENDERER + ".addLayer(Lnet/minecraft/client/renderer/entity/layers/RenderLayer;)Z",
                call(INVOKEVIRTUAL, LegacyAvatarRendererBridge.PLAYER_RENDERER, "addLayer", "(Lnet/minecraft/client/renderer/entity/layers/RenderLayer;)Z"),
                "members AvatarRenderer still has stay as they are under the new name");
        assertEquals(NEO + "client/event/RenderPlayerEvent$Pre.getRenderer()L" + LegacyAvatarRendererBridge.AVATAR_RENDERER + ";",
                call(INVOKEVIRTUAL, NEO + "client/event/RenderPlayerEvent$Pre", "getRenderer", "()L" + LegacyAvatarRendererBridge.PLAYER_RENDERER + ";"),
                "the old renderer read reaches NeoForge's own, which returns the avatar renderer");
        assertTrue(transformer.isMojangClassRename(LegacyAvatarRendererBridge.PLAYER_RENDERER));
        transformer.setModSourceVersion("1.21.9");
        assertEquals(LegacyAvatarRendererBridge.PLAYER_RENDERER + ".addLayer(Lnet/minecraft/client/renderer/entity/layers/RenderLayer;)Z",
                call(INVOKEVIRTUAL, LegacyAvatarRendererBridge.PLAYER_RENDERER, "addLayer", "(Lnet/minecraft/client/renderer/entity/layers/RenderLayer;)Z"),
                "a mod built for 1.21.9 is left alone");
    }

    @Test
    @DisplayName("An old NeoForge extension gets the 26.x item-and-layer hooks next to its old ones; a mixin and the new hooks it has are left alone")
    void neoForgeExtensionGetsNewHooks() {
        byte[] ext = extension(NEO_EXT, List.of("getHumanoidArmorModel" + OLD_HUMANOID, "getGenericArmorModel" + OLD_GENERIC), false);
        Set<String> added = added(ext, adapt(ext, LegacyArmorOverrideAdapter.NEO_ARMOR));
        String type = "Lnet/minecraft/client/resources/model/EquipmentClientInfo$LayerType;";
        assertEquals(Set.of("getHumanoidArmorModel(Lnet/minecraft/world/item/ItemStack;" + type + "Lnet/minecraft/client/model/Model;)Lnet/minecraft/client/model/Model;",
                "getGenericArmorModel(Lnet/minecraft/world/item/ItemStack;" + type + "Lnet/minecraft/client/model/Model;)Lnet/minecraft/client/model/Model;",
                "getArmorTexture(Lnet/minecraft/world/item/ItemStack;" + type + "Lnet/minecraft/client/resources/model/EquipmentClientInfo$Layer;"
                        + "Lnet/minecraft/resources/Identifier;)Lnet/minecraft/resources/Identifier;"), added);
        byte[] mixin = extension(NEO_EXT, List.of("getHumanoidArmorModel" + OLD_HUMANOID), true);
        assertSame(mixin, adapt(mixin, LegacyArmorOverrideAdapter.NEO_ARMOR));
        byte[] tint = extension(NEO_EXT, List.of("getArmorLayerTintColor(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/entity/LivingEntity;"
                + "Lcom/retromod/embedded/mod/com/retromod/generated/LegacyArmorLayer;II)I"), false);
        assertEquals(Set.of("getArmorLayerTintColor(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/client/resources/model/EquipmentClientInfo$Layer;II)I"),
                added(tint, adapt(tint, LegacyArmorOverrideAdapter.NEO_ARMOR)),
                "a NeoForge 21.1 tint override with the mod's own copy of the old layer is found");
    }

    @Test
    @DisplayName("An old Forge extension gets Forge 26.2's render state model hooks")
    void forgeExtensionGetsStateHooks() {
        byte[] ext = extension(FORGE_EXT, List.of("getHumanoidArmorModel" + OLD_HUMANOID), false);
        assertEquals(Set.of("getHumanoidArmorModel(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lnet/minecraft/world/item/ItemStack;"
                        + "Lnet/minecraft/world/entity/EquipmentSlot;Lnet/minecraft/client/model/HumanoidModel;)Lnet/minecraft/client/model/HumanoidModel;"),
                added(ext, adapt(ext, LegacyArmorOverrideAdapter.FORGE_ARMOR)));
    }

    @Test
    @DisplayName("An item that overrides isEnderMask gets the host's disguise hook; one with no override or no item parent is left alone")
    void itemGetsDisguiseHook() {
        byte[] item = item("net/minecraft/world/item/Item", "isEnderMask(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/entity/player/Player;"
                + ENDERMAN + ")Z");
        assertEquals(Set.of("isGazeDisguise(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/entity/LivingEntity;)Z"),
                added(item, adapt(item, LegacyArmorOverrideAdapter.NEO_DISGUISE)));
        assertEquals(Set.of("isMonsterDisguise(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/entity/player/Player;"
                        + "Lnet/minecraft/world/entity/monster/Monster;)Z"), added(item, adapt(item, LegacyArmorOverrideAdapter.FORGE_DISGUISE)),
                "Forge 26.3's Enderman reaches Forge's monster disguise hook");
        byte[] helper = item("java/lang/Object", "isEnderMask(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/entity/player/Player;" + ENDERMAN + ")Z");
        assertSame(helper, adapt(helper, LegacyArmorOverrideAdapter.NEO_DISGUISE), "a platform helper with the same name is not an item");
    }

    @Test
    @DisplayName("An added hook falls back to a parent that implements the extension, else to the interface the class lists, else it is not added")
    void addedHooksNameTheirInheritedHook() {
        byte[] anonymous = extension(NEO_EXT, List.of("getHumanoidArmorModel" + OLD_HUMANOID), false);
        assertEquals(List.of(NEO_EXT + ".getHumanoidArmorModel"), inheritedCalls(adapt(anonymous, LegacyArmorOverrideAdapter.NEO_ARMOR), "getHumanoidArmorModel"));
        ClassWriter parent = new ClassWriter(0);
        parent.visit(V17, ACC_PUBLIC | ACC_SUPER, "mod/BaseProperties", null, "java/lang/Object", new String[]{NEO_EXT});
        parent.visitEnd();
        byte[] child = subclass("mod/BaseProperties", "getHumanoidArmorModel" + OLD_HUMANOID);
        byte[] adapted = LegacyArmorOverrideAdapter.apply(child, Set.of(LegacyArmorOverrideAdapter.NEO_ARMOR)::contains,
                Map.of("mod/BaseProperties", parent.toByteArray())::get);
        assertEquals(List.of("mod/BaseProperties.getHumanoidArmorModel"), inheritedCalls(adapted, "getHumanoidArmorModel"));

        // Reached only through the mod's own sub-interface: INVOKESPECIAL names that direct interface, never the plain base.
        ClassWriter plain = new ClassWriter(0);
        plain.visit(V17, ACC_PUBLIC | ACC_SUPER, "mod/PlainBase", null, "java/lang/Object", null);
        plain.visitEnd();
        ClassWriter own = new ClassWriter(0);
        own.visit(V17, ACC_PUBLIC | ACC_ABSTRACT | ACC_INTERFACE, "mod/IModArmorExtensions", null, "java/lang/Object", new String[]{NEO_EXT});
        own.visitEnd();
        Map<String, byte[]> shipped = Map.of("mod/PlainBase", plain.toByteArray(), "mod/IModArmorExtensions", own.toByteArray());
        byte[] viaOwn = subclass(V17, "mod/PlainBase", new String[]{"mod/IModArmorExtensions"}, "getHumanoidArmorModel" + OLD_HUMANOID);
        assertEquals(List.of("mod/IModArmorExtensions.getHumanoidArmorModel"), inheritedCalls(LegacyArmorOverrideAdapter.apply(viaOwn,
                Set.of(LegacyArmorOverrideAdapter.NEO_ARMOR)::contains, shipped::get), "getHumanoidArmorModel"));

        // A base no jar ships proves nothing, so the listed interface answers, and with none listed the hook is left out.
        byte[] unshipped = subclass(V17, "lib/LibraryBase", new String[]{NEO_EXT}, "getHumanoidArmorModel" + OLD_HUMANOID);
        assertEquals(List.of(NEO_EXT + ".getHumanoidArmorModel"), inheritedCalls(adapt(unshipped, LegacyArmorOverrideAdapter.NEO_ARMOR), "getHumanoidArmorModel"));
        byte[] unlisted = subclass(V17, "lib/LibraryBase", null, "getHumanoidArmorModel" + OLD_HUMANOID);
        assertSame(unlisted, adapt(unlisted, LegacyArmorOverrideAdapter.NEO_ARMOR));
        // A Java 7 class file cannot name an interface method with INVOKESPECIAL.
        byte[] java7 = subclass(V1_7, "java/lang/Object", new String[]{NEO_EXT}, "getHumanoidArmorModel" + OLD_HUMANOID);
        assertSame(java7, adapt(java7, LegacyArmorOverrideAdapter.NEO_ARMOR));
        byte[] unrelated = item("java/lang/Object", "getArmorTexture(Ljava/lang/String;)V");
        assertSame(unrelated, adapt(unrelated, LegacyArmorOverrideAdapter.NEO_ARMOR), "a class with no old hook shape is left as it was");
    }

    @Test
    @DisplayName("The NeoForge install goes into a jar that names the old armor texture hook")
    void installPlanCoversArmorTextures() {
        assertNotNull(com.retromod.shim.neoforge.LegacyNeoForgeClientApiBridgeTest.planForTest(
                Map.of("a", "getArmorTexture".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1))));
        assertNull(com.retromod.shim.neoforge.LegacyNeoForgeClientApiBridgeTest.planForTest(
                Map.of("a", "getArmorSlots".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1))));
    }

    // ---- helpers ----

    private void registerNeoForge(String host) {
        McReflect.setForceForge(false);
        McReflect.setForceNeoForge(true);
        RetromodVersion.TARGET_MC_VERSION = host;
        transformer.clearRedirectsForTesting();
        com.retromod.shim.neoforge.LegacyNeoForgeClientApiBridgeTest.registerForTest(transformer, name -> null);
        transformer.setModSourceVersion("1.20.1");
    }

    private void registerForge(String host) {
        McReflect.setForceNeoForge(false);
        McReflect.setForceForge(true);
        RetromodVersion.TARGET_MC_VERSION = host;
        transformer.clearRedirectsForTesting();
        Function<String, ClassNode> offline = name -> null;
        LegacyForgeClientApiBridge.register(transformer, offline);
        transformer.setModSourceVersion("1.20.1");
    }

    private static byte[] adapt(byte[] bytes, String flag) {
        return LegacyArmorOverrideAdapter.apply(bytes, Set.of(flag)::contains, name -> null);
    }

    // The owners of the INVOKESPECIAL calls in each added method named {@code name}.
    private static List<String> inheritedCalls(byte[] bytes, String name) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        List<String> owners = new java.util.ArrayList<>();
        for (MethodNode method : node.methods) {
            if (!method.name.equals(name) || (method.access & ACC_SYNTHETIC) == 0) continue;
            for (var insn : method.instructions) {
                if (insn instanceof org.objectweb.asm.tree.MethodInsnNode call && call.getOpcode() == INVOKESPECIAL) owners.add(call.owner + "." + call.name);
            }
        }
        return owners;
    }

    private static byte[] subclass(String superName, String method) {
        return subclass(V17, superName, null, method);
    }

    private static byte[] subclass(int version, String superName, String[] interfaces, String method) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(version, ACC_PUBLIC | ACC_SUPER, "mod/ChildProperties", null, superName, interfaces);
        declare(cw, method);
        cw.visitEnd();
        return cw.toByteArray();
    }

    private String call(int opcode, String owner, String name, String desc) {
        return LegacyNeoForgeClientApiBridgeTest.call(opcode, owner, name, desc, transformer);
    }

    /** A mod class implementing {@code iface} that declares each {@code name+desc} as returning its argument or zero. */
    private static byte[] extension(String iface, List<String> methods, boolean mixin) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "mod/ArmorProperties", null, "java/lang/Object", new String[]{iface});
        if (mixin) cw.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false).visitEnd();
        for (String method : methods) declare(cw, method);
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] item(String superName, String method) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "mod/MaskItem", null, superName, null);
        declare(cw, method);
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void declare(ClassWriter cw, String method) {
        int open = method.indexOf('(');
        String desc = method.substring(open);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, method.substring(0, open), desc, null, null);
        mv.visitCode();
        Type result = Type.getReturnType(desc);
        if (result.getSort() == Type.OBJECT) mv.visitInsn(ACONST_NULL);
        else mv.visitInsn(ICONST_0);
        mv.visitInsn(result.getOpcode(IRETURN));
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static Set<String> added(byte[] before, byte[] after) {
        Set<String> old = methods(before);
        return methods(after).stream().filter(m -> !old.contains(m)).collect(Collectors.toSet());
    }

    private static Set<String> methods(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node.methods.stream().map(m -> m.name + m.desc).collect(Collectors.toSet());
    }
}
