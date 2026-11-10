/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.mixin;

import com.retromod.core.FuzzyMethodResolver;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.mapping.IntermediaryToMojangMapper;
import com.retromod.shim.common.LegacyMemberRenames;
import com.retromod.shim.common.RemovedBaseToParentBridge;
import com.retromod.shim.fabric.Fabric_1_21_1_to_1_21_2;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** Mixins from Forge 1.20.1 mods in the known-good set that Mixin rejected on a Forge 26.2 client. */
class OldForgeModMixinRepairTest {

    private static final String MIXIN = "Lorg/spongepowered/asm/mixin/Mixin;";
    private static final String SHADOW = "Lorg/spongepowered/asm/mixin/Shadow;";
    private static final String UNIQUE = "Lorg/spongepowered/asm/mixin/Unique;";
    private static final String ACCESSOR = "Lorg/spongepowered/asm/mixin/gen/Accessor;";
    private static final String INJECT = "Lorg/spongepowered/asm/mixin/injection/Inject;";
    private static final String AT = "Lorg/spongepowered/asm/mixin/injection/At;";
    private static final String CI = "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;";
    private static final String CIR = "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;";

    private static final String DISPENSER = "net/minecraft/world/level/block/DispenserBlock";
    private static final String STACK = "Lnet/minecraft/world/item/ItemStack;";
    private static final String BEHAVIOR = "Lnet/minecraft/core/dispenser/DispenseItemBehavior;";
    private static final String BUILT_IN_REGISTRIES = "net/minecraft/core/registries/BuiltInRegistries";
    private static final String MAPPED_REGISTRY = "net/minecraft/core/MappedRegistry";
    private static final String KEY = "Lnet/minecraft/resources/ResourceKey;";
    private static final String LIFECYCLE = "Lcom/mojang/serialization/Lifecycle;";
    private static final String BOOTSTRAP = "Lnet/minecraft/core/registries/BuiltInRegistries$RegistryBootstrap;";
    private static final String REGISTRY = "Lnet/minecraft/core/Registry;";
    private static final String LIFECYCLE_REGISTER = "(" + KEY + LIFECYCLE + BOOTSTRAP + ")" + REGISTRY;
    private static final String INTERNAL_REGISTER =
            "(" + KEY + "Lnet/minecraft/core/WritableRegistry;" + BOOTSTRAP + ")Lnet/minecraft/core/WritableRegistry;";
    private static final String INVENTORY_SCREEN = "net/minecraft/client/gui/screens/inventory/InventoryScreen";
    private static final String EFFECTS_SCREEN =
            "net/minecraft/client/gui/screens/inventory/EffectRenderingInventoryScreen";
    private static final String CONTAINER_SCREEN =
            "net/minecraft/client/gui/screens/inventory/AbstractContainerScreen";
    private static final String SCREEN_CTOR = "(Lnet/minecraft/world/inventory/AbstractContainerMenu;"
            + "Lnet/minecraft/world/entity/player/Inventory;Lnet/minecraft/network/chat/Component;)V";
    private static final String SMITHING = "net/minecraft/world/item/crafting/SmithingTransformRecipe";
    private static final String INGREDIENT = "Lnet/minecraft/world/item/crafting/Ingredient;";
    private static final String PATCHOULI_SMITHING = "vazkii/patchouli/mixin/AccessorSmithingTransformRecipe";
    private static final String GLITCHCORE_PLAYER = "glitchcore/forge/mixin/MixinServerPlayer";
    private static final String TELEPORTER_HANDLER = "(Lnet/minecraft/server/level/ServerLevel;"
            + "Lnet/minecraftforge/common/util/ITeleporter;" + CIR + ")V";
    private static final String KEYBOARD = "net/minecraft/client/KeyboardHandler";
    private static final String KEY_DISPATCH = "net/minecraft/util/KeyDispatchDataCodec";
    private static final String MAP_CODEC = "Lcom/mojang/serialization/MapCodec;";
    private static final String RULE = "terrablender/worldgen/surface/NamespacedSurfaceRuleSource";
    private static final String RULE_SOURCE = "net/minecraft/world/level/levelgen/SurfaceRules$RuleSource";
    private static final String SURFACE_RULE = "net/minecraft/world/level/levelgen/SurfaceRules$SurfaceRule";
    private static final String CONTEXT = "net/minecraft/world/level/levelgen/SurfaceRules$Context";
    private static final String OLD_ID = "net/minecraft/resources/ResourceLocation";
    private static final String NEW_ID = "net/minecraft/resources/Identifier";

    @TempDir
    Path tempDir;

    private String savedVersion;
    private FuzzyMethodResolver host;

    @BeforeEach
    void indexHost() throws IOException {
        savedVersion = RetromodVersion.TARGET_MC_VERSION;
        RetromodVersion.TARGET_MC_VERSION = "26.2";
        // The 26.2 members these mixins meet, nothing more.
        Path jar = tempDir.resolve("host.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            put(out, hostClass(DISPENSER, writer -> {
                method(writer, Opcodes.ACC_PROTECTED, "getDispenseMethod",
                        "(Lnet/minecraft/world/level/Level;" + STACK + ")" + BEHAVIOR);
                method(writer, Opcodes.ACC_PROTECTED, "dispenseFrom", "(Lnet/minecraft/server/level/ServerLevel;"
                        + "Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;)V");
            }));
            put(out, hostClass(BUILT_IN_REGISTRIES, writer -> {
                method(writer, Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "registerSimple",
                        "(" + KEY + BOOTSTRAP + ")" + REGISTRY);
                method(writer, Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "internalRegister", INTERNAL_REGISTER);
            }));
            put(out, hostClass(MAPPED_REGISTRY,
                    writer -> method(writer, Opcodes.ACC_PUBLIC, "<init>", "(" + KEY + LIFECYCLE + "Z)V")));
            put(out, hostClass(KEYBOARD, writer -> writer.visitField(Opcodes.ACC_PRIVATE, "usedDebugKeyAsModifier",
                    "Z", null, null).visitEnd()));
        }
        host = new FuzzyMethodResolver();
        host.indexJar(jar);
        assertTrue(host.isIndexed(), "the synthetic host jar must be indexed");
    }

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedVersion;
        RetromodTransformer.getInstance().clearRedirectsForTesting();
        MixinBlocklist.resetForTesting();
    }

    @Test
    @DisplayName("Farmer's Delight: a shadow nothing calls no longer fails the dispenser mixin")
    void unusedShadowOfAChangedMethodIsDropped() {
        byte[] mixin = mixin("CuttingBoardDispenserMixin", DISPENSER, Opcodes.ACC_ABSTRACT, writer -> {
            writer.visitMethod(Opcodes.ACC_PROTECTED | Opcodes.ACC_ABSTRACT, "getDispenseMethod",
                    "(" + STACK + ")" + BEHAVIOR, null, null).visitAnnotation(SHADOW, true).visitEnd();
            MethodVisitor handler = inject(writer, "onDispense", "(" + CI + ")V", "dispenseFrom", "HEAD");
            handler.visitInsn(Opcodes.RETURN);
            handler.visitMaxs(0, 2);
            handler.visitEnd();
        });

        ClassNode repaired = read(drift().apply(mixin));

        assertNull(method(repaired, "getDispenseMethod", "(" + STACK + ")" + BEHAVIOR),
                "26.2 only has getDispenseMethod(Level, ItemStack), and the mixin never calls the old one");
        assertNotNull(method(repaired, "onDispense", "(" + CI + ")V"), "the handler stays");
    }

    @Test
    @DisplayName("A shadow the mixin calls, or one in a mixin that adds interfaces, is kept")
    void calledOrInterfaceShadowIsKept() {
        byte[] called = mixin("CallsShadowMixin", DISPENSER, Opcodes.ACC_ABSTRACT, writer -> {
            writer.visitMethod(Opcodes.ACC_PROTECTED | Opcodes.ACC_ABSTRACT, "getDispenseMethod",
                    "(" + STACK + ")" + BEHAVIOR, null, null).visitAnnotation(SHADOW, true).visitEnd();
            MethodVisitor handler = inject(writer, "onDispense", "(" + CI + ")V", "dispenseFrom", "HEAD");
            handler.visitVarInsn(Opcodes.ALOAD, 0);
            handler.visitInsn(Opcodes.ACONST_NULL);
            handler.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "test/mixin/CallsShadowMixin", "getDispenseMethod",
                    "(" + STACK + ")" + BEHAVIOR, false);
            handler.visitInsn(Opcodes.POP);
            handler.visitInsn(Opcodes.RETURN);
            handler.visitMaxs(2, 2);
            handler.visitEnd();
        });
        assertArrayEquals(called, drift().apply(called), "a called shadow needs a real repair, not removal");

        byte[] withInterface = mixin("AddsInterfaceMixin", DISPENSER, Opcodes.ACC_ABSTRACT, writer ->
                writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "getDispenseMethod",
                        "(" + STACK + ")" + BEHAVIOR, null, null).visitAnnotation(SHADOW, true).visitEnd(),
                "test/DispenserAccess");
        assertArrayEquals(withInterface, drift().apply(withInterface),
                "the shadow may implement the added interface for callers outside the mixin");
    }

    @Test
    @DisplayName("A mixin with a nested class keeps shadows it never names, since Mixin moves the nested class too")
    void shadowsOfAMixinWithANestedClassAreKept() {
        byte[] nested = mixin("NestedMixin", DISPENSER, Opcodes.ACC_ABSTRACT, writer -> {
            writer.visitNestMember("test/mixin/NestedMixin$1");
            writer.visitInnerClass("test/mixin/NestedMixin$1", null, null, 0);
            writer.visitMethod(Opcodes.ACC_PROTECTED | Opcodes.ACC_ABSTRACT, "getDispenseMethod",
                    "(" + STACK + ")" + BEHAVIOR, null, null).visitAnnotation(SHADOW, true).visitEnd();
            writer.visitField(Opcodes.ACC_PRIVATE, "removedField", "I", null, null)
                    .visitAnnotation(SHADOW, true).visitEnd();
        });

        ClassNode kept = read(drift().apply(nested));

        assertNotNull(method(kept, "getDispenseMethod", "(" + STACK + ")" + BEHAVIOR),
                "the nested class may call the shadow, and Mixin points that call at the target");
        assertTrue(kept.fields.stream().anyMatch(field -> field.name.equals("removedField")),
                "the same holds for a shadow field");
    }

    @Test
    @DisplayName("TerraBlender: the lifecycle overload of registerSimple becomes a mixin-owned copy")
    void lifecycleRegisterSimpleShadowIsBridged() {
        byte[] mixin = terraBlenderMixin();

        ClassNode repaired = read(drift().apply(mixin));

        MethodNode bridge = method(repaired, "registerSimple", LIFECYCLE_REGISTER);
        assertFalse(hasAnnotation(bridge, SHADOW), "26.2 has no registerSimple(key, lifecycle, bootstrap)");
        assertTrue(hasAnnotation(bridge, UNIQUE));
        assertEquals(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
                bridge.access & (Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_PUBLIC));
        assertTrue(calls(bridge, MAPPED_REGISTRY, "<init>", "(" + KEY + LIFECYCLE + "Z)V"),
                "the registry keeps the lifecycle the mod asked for");
        assertTrue(calls(bridge, repaired.name, "internalRegister", INTERNAL_REGISTER),
                "it registers through internalRegister, never through the kept overload its own injection hooks");
        assertFalse(calls(bridge, repaired.name, "registerSimple", "(" + KEY + BOOTSTRAP + ")" + REGISTRY));
        MethodNode internal = method(repaired, "internalRegister", INTERNAL_REGISTER);
        assertTrue(hasAnnotation(internal, SHADOW), "the private host method is reached through a shadow");
    }

    @Test
    @DisplayName("A host that still has the lifecycle overload keeps TerraBlender's shadow")
    void lifecycleRegisterSimpleStaysWhereTheHostHasIt() throws IOException {
        Path jar = tempDir.resolve("old-host.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            put(out, hostClass(BUILT_IN_REGISTRIES, writer -> {
                method(writer, Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "registerSimple", LIFECYCLE_REGISTER);
                method(writer, Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "internalRegister", INTERNAL_REGISTER);
            }));
            put(out, hostClass(MAPPED_REGISTRY,
                    writer -> method(writer, Opcodes.ACC_PUBLIC, "<init>", "(" + KEY + LIFECYCLE + "Z)V")));
        }
        FuzzyMethodResolver oldHost = new FuzzyMethodResolver();
        oldHost.indexJar(jar);
        byte[] mixin = terraBlenderMixin();

        assertArrayEquals(mixin, new MixinHostDriftRepair(oldHost, Map.of(), Map.of()).apply(mixin));
    }

    @Test
    @DisplayName("TerraBlender: the repair declines when its lambda still calls a member this host lacks")
    void lifecycleRegisterSimpleDeclinesWhenTheLambdaCannotLink() throws IOException {
        MixinHostDriftRepair repair = new MixinHostDriftRepair(codecHost(), Map.of(), Map.of());

        // Without the KeyDispatchDataCodec bridge the lambda would fail in Bootstrap, so Mixin keeps skipping it.
        byte[] unbridged = terraBlenderMixinWithLambda("()Lcom/mojang/serialization/Codec;");
        assertArrayEquals(unbridged, repair.apply(unbridged));

        ClassNode bridged = read(repair.apply(terraBlenderMixinWithLambda("()Lcom/mojang/serialization/MapCodec;")));
        assertTrue(hasAnnotation(method(bridged, "registerSimple", LIFECYCLE_REGISTER), UNIQUE),
                "once the lambda links, the repair applies");
    }

    @Test
    @DisplayName("TerraBlender on 26.3: the repair declines when a rule class its lambda loads names a removed class")
    void lifecycleRegisterSimpleDeclinesWhenALoadedModClassNamesARemovedClass() throws IOException {
        byte[] mixin = terraBlenderMixinWith(ruleCodecRead());
        Map<String, byte[]> jar = Map.of(RULE, ruleSourceClass());

        // 26.3 replaced SurfaceRules$SurfaceRule, which the rule class names, so loading it would fail in Bootstrap.
        MixinHostDriftRepair without = new MixinHostDriftRepair(codecHost(), Map.of(), Map.of(), jar::get);
        assertArrayEquals(mixin, without.apply(mixin));

        MixinHostDriftRepair with = new MixinHostDriftRepair(codecHost(SURFACE_RULE, CONTEXT), Map.of(), Map.of(),
                jar::get);
        assertTrue(hasAnnotation(method(read(with.apply(mixin)), "registerSimple", LIFECYCLE_REGISTER), UNIQUE),
                "a host with every class the rule class names gets the repair");

        // A rule class Retromod cannot read could name anything, so it declines too.
        MixinHostDriftRepair unread = new MixinHostDriftRepair(codecHost(SURFACE_RULE, CONTEXT), Map.of(), Map.of(),
                name -> null);
        assertArrayEquals(mixin, unread.apply(mixin));
    }

    @Test
    @DisplayName("TerraBlender: a class move the host takes counts as present, a Minecraft class it lacks as missing")
    void lifecycleRegisterSimpleChecksTheClassesItsCodeNames() throws IOException {
        InsnList idClass = new InsnList();
        idClass.add(new LdcInsnNode(Type.getObjectType(OLD_ID)));
        idClass.add(new InsnNode(Opcodes.ARETURN));
        byte[] mixin = terraBlenderMixinWith(idClass);

        assertArrayEquals(mixin, new MixinHostDriftRepair(codecHost(), Map.of(), Map.of()).apply(mixin),
                "the host has no ResourceLocation");
        MixinHostDriftRepair kept = new MixinHostDriftRepair(codecHost(OLD_ID), Map.of(), Map.of());
        assertTrue(hasAnnotation(method(read(kept.apply(mixin)), "registerSimple", LIFECYCLE_REGISTER), UNIQUE));
        MixinHostDriftRepair moved = new MixinHostDriftRepair(codecHost(NEW_ID), Map.of(), Map.of(OLD_ID, NEW_ID));
        assertTrue(hasAnnotation(method(read(moved.apply(mixin)), "registerSimple", LIFECYCLE_REGISTER), UNIQUE));
    }

    @Test
    @DisplayName("TerraBlender: a method reference to a member the host lacks is checked like a call")
    void lifecycleRegisterSimpleChecksMethodReferences() throws IOException {
        byte[] oldReference = terraBlenderMixinWith(supplierOf("()Lcom/mojang/serialization/Codec;"));
        byte[] newReference = terraBlenderMixinWith(supplierOf("()Lcom/mojang/serialization/MapCodec;"));
        MixinHostDriftRepair repair = new MixinHostDriftRepair(codecHost(), Map.of(), Map.of());

        assertArrayEquals(oldReference, repair.apply(oldReference));
        assertTrue(hasAnnotation(method(read(repair.apply(newReference)), "registerSimple", LIFECYCLE_REGISTER),
                UNIQUE));
    }

    @Test
    @DisplayName("Without a host index the Bootstrap repair never runs")
    void lifecycleRegisterSimpleNeedsAnIndex() {
        byte[] mixin = terraBlenderMixin();

        assertArrayEquals(mixin, new MixinHostDriftRepair(new FuzzyMethodResolver(), Map.of(), Map.of()).apply(mixin));
        assertArrayEquals(mixin, new MixinHostDriftRepair(null, Map.of(), Map.of()).apply(mixin));
    }

    @Test
    @DisplayName("Patchouli: an inventory mixin rebased onto a generated legacy base moves to its parent")
    void mixinLeavesTheGeneratedLegacyBase() {
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        RemovedBaseToParentBridge.register(transformer);
        byte[] remapped = transformer.transformClass(patchouliInventoryMixin(), "test/MixinInventoryScreen");
        assertEquals("com/retromod/generated/LegacyEffectRenderingInventoryScreen", read(remapped).superName,
                "the class pass alone puts the mixin on the generated base, which Mixin rejects");

        ClassNode repaired = read(new MixinCompatibilityTransformer(transformer).applyLegacyMemberBridges(remapped));

        assertEquals(CONTAINER_SCREEN, repaired.superName,
                "InventoryScreen's hierarchy has AbstractContainerScreen, never the mod's generated base");
        MethodNode constructor = method(repaired, "<init>", null);
        assertTrue(calls(constructor, CONTAINER_SCREEN, "<init>", SCREEN_CTOR),
                "Mixin finds the super call it drops by the superclass name");
        assertFalse(repaired.signature.contains("LegacyEffectRenderingInventoryScreen"));
    }

    @Test
    @DisplayName("Patchouli: Mojang-named smithing accessors are retyped to the Optional fields")
    void smithingAccessorsFollowTheOptionalFields() {
        ClassNode accessor = read(patchouliSmithingAccessor());

        assertTrue(MixinLegacyMemberBridge.apply(accessor));

        // Its refmap still maps "template" to the template field, and Mixin takes only the name.
        for (String[] pair : new String[][]{{"getTemplate", "template"}, {"getAddition", "addition"}}) {
            assertNull(method(accessor, pair[0], "()" + INGREDIENT), "Mixin finds no Ingredient field of this name");
            MethodNode optional = method(accessor, "retromod$" + pair[0] + "Optional", "()Ljava/util/Optional;");
            assertTrue(hasAnnotation(optional, ACCESSOR));
            assertEquals(pair[1], accessorValue(optional),
                    "the accessor names the field, which is also its refmap key");
        }
        assertNotNull(method(accessor, "getBase", "()" + INGREDIENT), "the base ingredient did not change");
    }

    @Test
    @DisplayName("Patchouli: Forge and NeoForge calls to the old accessor unwrap the Optional")
    void smithingAccessorCallersUnwrap() {
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        new com.retromod.shim.forge.Forge_1_21_4_to_1_21_5().registerRedirects(transformer);

        MethodNode call = method(read(transformer.transformClass(patchouliPageCall(), "test/PageSmithing")),
                "template", null);

        assertTrue(calls(call, PATCHOULI_SMITHING, "retromod$getTemplateOptional", "()Ljava/util/Optional;"));
        assertTrue(calls(call, "java/util/Optional", "orElseThrow", "()Ljava/lang/Object;"));
        assertTrue(castsToIngredient(call), "the page still receives an Ingredient");

        transformer.clearRedirectsForTesting();
        new com.retromod.shim.neoforge.NeoForge_1_21_4_to_1_21_5().registerRedirects(transformer);
        MethodNode neoCall = method(read(transformer.transformClass(patchouliPageCall(), "test/PageSmithing")),
                "template", null);
        assertTrue(calls(neoCall, PATCHOULI_SMITHING, "retromod$getTemplateOptional", "()Ljava/util/Optional;"));
    }

    @Test
    @DisplayName("Patchouli: a Fabric call on 26.2 unwraps the Optional after the intermediary remap")
    void fabricSmithingAccessorCallersUnwrapOn26x() {
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        new Fabric_1_21_1_to_1_21_2().registerRedirects(transformer);
        IntermediaryToMojangMapper.applyTo(transformer);

        MethodNode call = method(read(transformer.transformClass(
                patchouliPageCall("Lnet/minecraft/class_1856;"), "test/PageSmithing")), "template", null);

        assertTrue(calls(call, PATCHOULI_SMITHING, "retromod$getTemplateOptional", "()Ljava/util/Optional;"),
                "the Mixin repair renames the accessor on 26.x, so its Fabric callers must follow");
        assertTrue(calls(call, "java/util/Optional", "orElseThrow", "()Ljava/lang/Object;"));
        assertTrue(castsToIngredient(call), "the page still receives an Ingredient");
    }

    @Test
    @DisplayName("Another mod's smithing accessor keeps its shape, because only Patchouli's callers are unwrapped")
    void otherSmithingAccessorIsLeftAlone() {
        ClassNode accessor = read(smithingAccessor("test/OtherSmithingAccessor"));

        assertFalse(MixinLegacyMemberBridge.apply(accessor));
        assertNotNull(method(accessor, "getTemplate", "()" + INGREDIENT));
    }

    @Test
    @DisplayName("GlitchCore: the handler for Forge's removed changeDimension(ServerLevel, ITeleporter) is stripped")
    void teleporterHandlerIsStripped() {
        MixinBlocklist.resetForTesting();
        byte[] mixin = glitchCorePlayerMixin(TELEPORTER_HANDLER);

        ClassNode stripped = read(new MixinCompatibilityTransformer(RetromodTransformer.getInstance())
                .transformMixinClass(mixin));

        assertNull(method(stripped, "onChangeDimension", TELEPORTER_HANDLER),
                "Mixin cannot read a handler whose parameter type the host lacks, and rejects the whole mixin");
        assertNotNull(method(stripped, "<init>", null), "the rest of the mixin stays");
    }

    @Test
    @DisplayName("GlitchCore 2.x's same-named handler for DimensionTransition, and old hosts, keep theirs")
    void teleporterEntryMatchesOnlyTheOldShapeOnNewHosts() {
        MixinBlocklist.resetForTesting();
        String newerHandler = "(Lnet/minecraft/world/level/portal/DimensionTransition;" + CIR + ")V";
        MixinCompatibilityTransformer mixins = new MixinCompatibilityTransformer(RetromodTransformer.getInstance());
        assertNotNull(method(read(mixins.transformMixinClass(glitchCorePlayerMixin(newerHandler))),
                "onChangeDimension", newerHandler));

        RetromodVersion.TARGET_MC_VERSION = "1.20.1";
        MixinBlocklist.resetForTesting();
        assertNotNull(method(read(mixins.transformMixinClass(glitchCorePlayerMixin(TELEPORTER_HANDLER))),
                "onChangeDimension", TELEPORTER_HANDLER), "Forge 1.20.1 still has the method the handler hooks");
    }

    @Test
    @DisplayName("GlitchCore: the handledDebugKey shadow follows its 1.21.11 rename")
    void debugKeyShadowFollowsTheRename() {
        ClassNode mixin = new ClassNode();
        new ClassReader(mixin("MixinKeyboardHandler", KEYBOARD, 0, writer -> {})).accept(mixin, 0);
        FieldNode shadow = new FieldNode(Opcodes.ACC_PRIVATE, "handledDebugKey", "Z", null, null);
        shadow.visibleAnnotations = new ArrayList<>(List.of(new AnnotationNode(SHADOW)));
        mixin.fields.add(shadow);
        MethodNode handler = new MethodNode(Opcodes.ACC_PUBLIC, "onKeyInput", "()V", null, null);
        handler.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        handler.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        handler.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, mixin.name, "handledDebugKey", "Z"));
        handler.instructions.add(new FieldInsnNode(Opcodes.PUTFIELD, mixin.name, "handledDebugKey", "Z"));
        handler.instructions.add(new InsnNode(Opcodes.RETURN));
        mixin.methods.add(handler);

        assertTrue(MixinShadowRenames.apply(mixin, rename -> true));

        assertEquals("usedDebugKeyAsModifier", shadow.name, "26.2 has no handledDebugKey field");
        assertTrue(Arrays.stream(handler.instructions.toArray()).filter(FieldInsnNode.class::isInstance)
                .map(FieldInsnNode.class::cast).allMatch(access -> access.name.equals("usedDebugKeyAsModifier")),
                "the mixin's own read and write follow the shadow");
    }

    @Test
    @DisplayName("Offline, the --mc-jar index proves the rename the game proves from its classes")
    void debugKeyRenameIsProvenFromTheIndex() {
        LegacyMemberRenames.Rename debugKey = LegacyMemberRenames.RENAMES.stream()
                .filter(rename -> rename.owner().equals(KEYBOARD)).findFirst().orElseThrow();

        assertFalse(LegacyMemberRenames.hostRenamed(debugKey), "the test classpath has no Minecraft classes");
        assertTrue(LegacyMemberRenames.hostRenamed(debugKey, host),
                "the CLI and AOT translate offline and must rename the shadow as the game does");
        assertFalse(LegacyMemberRenames.hostRenamed(debugKey, null), "no index proves nothing");
    }

    @Test
    @DisplayName("Balm: only the renderSlot invoker leaves the screen accessor, so the other accessors bind")
    void renderSlotInvokerIsStrippedAndTheRestStays() {
        MixinBlocklist.resetForTesting();
        MixinCompatibilityTransformer mixins = new MixinCompatibilityTransformer(RetromodTransformer.getInstance());

        ClassNode accessor = read(mixins.transformMixinClass(balmScreenAccessor()));

        assertNull(method(accessor, "callRenderSlot", null),
                "Mixin locates every accessor first, so one unbindable invoker left the screen with none");
        assertNotNull(method(accessor, "getLeftPos", "()I"), "Waystones and Sophisticated Core call this one");

        RetromodVersion.TARGET_MC_VERSION = "1.21.10";
        MixinBlocklist.resetForTesting();
        assertNotNull(method(read(mixins.transformMixinClass(balmScreenAccessor())), "callRenderSlot", null),
                "before 1.21.11 renderSlot(GuiGraphics, Slot) still exists");
    }

    // ---- Old-shape mixins ----------------------------------------------------------------------

    private static byte[] balmScreenAccessor() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE,
                "net/blay09/mods/balm/mixin/AbstractContainerScreenAccessor", null, "java/lang/Object", null);
        mixinAnnotation(writer, CONTAINER_SCREEN);
        MethodVisitor leftPos = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "getLeftPos", "()I",
                null, null);
        leftPos.visitAnnotation(ACCESSOR, true).visitEnd();
        leftPos.visitEnd();
        MethodVisitor renderSlot = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "callRenderSlot",
                "(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/world/inventory/Slot;)V", null, null);
        renderSlot.visitAnnotation("Lorg/spongepowered/asm/mixin/gen/Invoker;", true).visitEnd();
        renderSlot.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] terraBlenderMixin() {
        return mixin("MixinBuiltInRegistries", BUILT_IN_REGISTRIES, 0, writer -> {
            MethodVisitor shadow = writer.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "registerSimple",
                    LIFECYCLE_REGISTER, null, null);
            shadow.visitAnnotation(SHADOW, true).visitEnd();
            shadow.visitCode();
            shadow.visitInsn(Opcodes.ACONST_NULL);
            shadow.visitInsn(Opcodes.ARETURN);
            shadow.visitMaxs(1, 3);
            shadow.visitEnd();
            // Like TerraBlender's own handler: hooks the kept overload and calls the removed one.
            MethodVisitor handler = inject(writer, "registerSimple", "(" + KEY + BOOTSTRAP + CIR + ")V",
                    "registerSimple(" + KEY + BOOTSTRAP + ")" + REGISTRY, "HEAD", Opcodes.ACC_STATIC);
            handler.visitVarInsn(Opcodes.ALOAD, 2);
            handler.visitVarInsn(Opcodes.ALOAD, 0);
            handler.visitInsn(Opcodes.ACONST_NULL);
            handler.visitVarInsn(Opcodes.ALOAD, 1);
            handler.visitMethodInsn(Opcodes.INVOKESTATIC, "test/mixin/MixinBuiltInRegistries", "registerSimple",
                    LIFECYCLE_REGISTER, false);
            handler.visitMethodInsn(Opcodes.INVOKEVIRTUAL, CIR.substring(1, CIR.length() - 1), "setReturnValue",
                    "(Ljava/lang/Object;)V", false);
            handler.visitInsn(Opcodes.RETURN);
            handler.visitMaxs(4, 3);
            handler.visitEnd();
        });
    }

    /** The same mixin with TerraBlender's registration lambda, reading the rule codec through {@code accessorDesc}. */
    private static byte[] terraBlenderMixinWithLambda(String accessorDesc) {
        ClassNode node = read(terraBlenderMixin());
        MethodNode lambda = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                "lambda$registerSimple$0", "(" + REGISTRY + ")Ljava/lang/Object;", null, null);
        lambda.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        lambda.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, KEY_DISPATCH));
        lambda.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, KEY_DISPATCH, "codec", accessorDesc, false));
        lambda.instructions.add(new InsnNode(Opcodes.ARETURN));
        lambda.maxStack = 1;
        lambda.maxLocals = 1;
        node.methods.add(lambda);
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }

    /**
     * The 26.2 host the registerSimple repair needs, with KeyDispatchDataCodec as 1.20.5 and newer
     * have it: a record whose codec() returns a MapCodec. {@code extra} classes are added empty.
     */
    private FuzzyMethodResolver codecHost(String... extra) throws IOException {
        Path jar = Files.createTempFile(tempDir, "codec-host", ".jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            put(out, hostClass(BUILT_IN_REGISTRIES, writer -> {
                method(writer, Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "registerSimple",
                        "(" + KEY + BOOTSTRAP + ")" + REGISTRY);
                method(writer, Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "internalRegister", INTERNAL_REGISTER);
            }));
            put(out, hostClass(MAPPED_REGISTRY,
                    writer -> method(writer, Opcodes.ACC_PUBLIC, "<init>", "(" + KEY + LIFECYCLE + "Z)V")));
            put(out, hostClass(KEY_DISPATCH, "java/lang/Record",
                    writer -> method(writer, Opcodes.ACC_PUBLIC, "codec", "()" + MAP_CODEC)));
            // An interface, as on the real host: the rule class implements it, and the check compares kinds.
            put(out, hostInterface(RULE_SOURCE));
            for (String name : extra) put(out, hostClass(name, writer -> { }));
        }
        FuzzyMethodResolver resolver = new FuzzyMethodResolver();
        resolver.indexJar(jar);
        return resolver;
    }

    /** TerraBlender's mixin with a lambda made of {@code body}. */
    private static byte[] terraBlenderMixinWith(InsnList body) {
        ClassNode node = read(terraBlenderMixin());
        MethodNode lambda = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                "lambda$registerSimple$0", "(" + REGISTRY + ")Ljava/lang/Object;", null, null);
        lambda.instructions.add(body);
        lambda.maxStack = 2;
        lambda.maxLocals = 1;
        node.methods.add(lambda);
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }

    /** {@code return NamespacedSurfaceRuleSource.CODEC.codec();}, the map codec TerraBlender registers. */
    private static InsnList ruleCodecRead() {
        InsnList body = new InsnList();
        body.add(new FieldInsnNode(Opcodes.GETSTATIC, RULE, "CODEC", "L" + KEY_DISPATCH + ";"));
        body.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, KEY_DISPATCH, "codec", "()" + MAP_CODEC, false));
        body.add(new InsnNode(Opcodes.ARETURN));
        return body;
    }

    /** {@code return ((KeyDispatchDataCodec) null)::codec;} with the accessor descriptor {@code desc}. */
    private static InsnList supplierOf(String desc) {
        InsnList body = new InsnList();
        body.add(new InsnNode(Opcodes.ACONST_NULL));
        body.add(new TypeInsnNode(Opcodes.CHECKCAST, KEY_DISPATCH));
        Handle metafactory = new Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory",
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
                        + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)"
                        + "Ljava/lang/invoke/CallSite;", false);
        body.add(new InvokeDynamicInsnNode("get", "(L" + KEY_DISPATCH + ";)Ljava/util/function/Supplier;", metafactory,
                Type.getMethodType("()Ljava/lang/Object;"),
                new Handle(Opcodes.H_INVOKEVIRTUAL, KEY_DISPATCH, "codec", desc, false), Type.getMethodType(desc)));
        body.add(new InsnNode(Opcodes.ARETURN));
        return body;
    }

    /** TerraBlender's rule source as its jar holds it: a record of a rule source whose rules are 26.2's SurfaceRule. */
    private static byte[] ruleSourceClass() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, RULE, null, "java/lang/Record",
                new String[]{RULE_SOURCE});
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, "CODEC", "L" + KEY_DISPATCH + ";",
                null, null).visitEnd();
        method(writer, Opcodes.ACC_PUBLIC, "apply", "(L" + CONTEXT + ";)L" + SURFACE_RULE + ";");
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] patchouliInventoryMixin() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "test/MixinInventoryScreen",
                "L" + EFFECTS_SCREEN + "<Lnet/minecraft/world/inventory/InventoryMenu;>;", EFFECTS_SCREEN, null);
        mixinAnnotation(writer, INVENTORY_SCREEN);
        String ctor = "(Lnet/minecraft/world/inventory/InventoryMenu;Lnet/minecraft/world/entity/player/Inventory;"
                + "Lnet/minecraft/network/chat/Component;)V";
        MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", ctor, null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitVarInsn(Opcodes.ALOAD, 1);
        constructor.visitVarInsn(Opcodes.ALOAD, 2);
        constructor.visitVarInsn(Opcodes.ALOAD, 3);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, EFFECTS_SCREEN, "<init>", SCREEN_CTOR, false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(4, 4);
        constructor.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] patchouliSmithingAccessor() {
        return smithingAccessor(PATCHOULI_SMITHING);
    }

    private static byte[] smithingAccessor(String name) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE,
                name, null, "java/lang/Object", null);
        mixinAnnotation(writer, SMITHING);
        for (String getter : List.of("getTemplate", "getBase", "getAddition")) {
            MethodVisitor accessor = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, getter,
                    "()" + INGREDIENT, null, null);
            accessor.visitAnnotation(ACCESSOR, true).visitEnd();
            accessor.visitEnd();
        }
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] patchouliPageCall() {
        return patchouliPageCall(INGREDIENT);
    }

    /** Patchouli's smithing page reading the template, with the ingredient type in the given names. */
    private static byte[] patchouliPageCall(String ingredient) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/PageSmithing", null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "template",
                "(L" + PATCHOULI_SMITHING + ";)" + ingredient, null, null);
        method.visitCode();
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitMethodInsn(Opcodes.INVOKEINTERFACE, PATCHOULI_SMITHING, "getTemplate", "()" + ingredient, true);
        method.visitInsn(Opcodes.ARETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] glitchCorePlayerMixin(String handlerDesc) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, GLITCHCORE_PLAYER, null,
                "java/lang/Object", null);
        mixinAnnotation(writer, "net/minecraft/server/level/ServerPlayer");
        MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(1, 1);
        constructor.visitEnd();
        MethodVisitor handler = inject(writer, "onChangeDimension", handlerDesc, "changeDimension", "TAIL");
        handler.visitInsn(Opcodes.RETURN);
        handler.visitMaxs(0, Type.getArgumentsAndReturnSizes(handlerDesc) >> 2);
        handler.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    // ---- Helpers -----------------------------------------------------------------------------

    private MixinHostDriftRepair drift() {
        return new MixinHostDriftRepair(host, Map.of(), Map.of());
    }

    private static void put(JarOutputStream out, byte[] classBytes) throws IOException {
        out.putNextEntry(new JarEntry(new ClassReader(classBytes).getClassName() + ".class"));
        out.write(classBytes);
        out.closeEntry();
    }

    private static byte[] hostClass(String name, Consumer<ClassWriter> members) {
        return hostClass(name, "java/lang/Object", members);
    }

    private static byte[] hostInterface(String name) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE, name, null,
                "java/lang/Object", null);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] hostClass(String name, String superName, Consumer<ClassWriter> members) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, superName, null);
        members.accept(writer);
        writer.visitEnd();
        return writer.toByteArray();
    }

    /** A host method with a trivial body, which is all the index reads. */
    private static void method(ClassWriter writer, int access, String name, String desc) {
        MethodVisitor body = writer.visitMethod(access, name, desc, null, null);
        body.visitCode();
        body.visitInsn(Opcodes.ACONST_NULL);
        body.visitInsn(Opcodes.ATHROW);
        body.visitMaxs(1, Type.getArgumentsAndReturnSizes(desc) >> 2);
        body.visitEnd();
    }

    private static byte[] mixin(String simpleName, String target, int extraAccess, Consumer<ClassWriter> members,
            String... interfaces) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | extraAccess, "test/mixin/" + simpleName, null,
                "java/lang/Object", interfaces.length == 0 ? null : interfaces);
        mixinAnnotation(writer, target);
        members.accept(writer);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void mixinAnnotation(ClassWriter writer, String target) {
        AnnotationVisitor mixin = writer.visitAnnotation(MIXIN, false);
        AnnotationVisitor value = mixin.visitArray("value");
        value.visit(null, Type.getObjectType(target));
        value.visitEnd();
        mixin.visitEnd();
    }

    private static MethodVisitor inject(ClassWriter writer, String name, String desc, String selector, String point) {
        return inject(writer, name, desc, selector, point, 0);
    }

    private static MethodVisitor inject(ClassWriter writer, String name, String desc, String selector, String point,
            int extraAccess) {
        MethodVisitor handler = writer.visitMethod(Opcodes.ACC_PRIVATE | extraAccess, name, desc, null, null);
        AnnotationVisitor inject = handler.visitAnnotation(INJECT, true);
        AnnotationVisitor methods = inject.visitArray("method");
        methods.visit(null, selector);
        methods.visitEnd();
        AnnotationVisitor ats = inject.visitArray("at");
        AnnotationVisitor at = ats.visitAnnotation(null, AT);
        at.visit("value", point);
        at.visitEnd();
        ats.visitEnd();
        inject.visit("require", 0);
        inject.visitEnd();
        handler.visitCode();
        return handler;
    }

    private static ClassNode read(byte[] classBytes) {
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, 0);
        return node;
    }

    /** The method with this name and, when given, this descriptor. */
    private static MethodNode method(ClassNode node, String name, String desc) {
        return node.methods.stream()
                .filter(m -> m.name.equals(name) && (desc == null || m.desc.equals(desc)))
                .findFirst().orElse(null);
    }

    private static boolean calls(MethodNode method, String owner, String name, String desc) {
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call && call.owner.equals(owner) && call.name.equals(name)
                    && call.desc.equals(desc)) {
                return true;
            }
        }
        return false;
    }

    private static boolean castsToIngredient(MethodNode method) {
        return Arrays.stream(method.instructions.toArray()).anyMatch(insn -> insn instanceof TypeInsnNode cast
                && cast.getOpcode() == Opcodes.CHECKCAST
                && cast.desc.equals("net/minecraft/world/item/crafting/Ingredient"));
    }

    private static boolean hasAnnotation(MethodNode method, String desc) {
        List<AnnotationNode> all = new ArrayList<>();
        if (method.visibleAnnotations != null) all.addAll(method.visibleAnnotations);
        if (method.invisibleAnnotations != null) all.addAll(method.invisibleAnnotations);
        return all.stream().anyMatch(annotation -> desc.equals(annotation.desc));
    }

    private static Object accessorValue(MethodNode method) {
        List<AnnotationNode> all = new ArrayList<>();
        if (method.visibleAnnotations != null) all.addAll(method.visibleAnnotations);
        if (method.invisibleAnnotations != null) all.addAll(method.invisibleAnnotations);
        AnnotationNode accessor = all.stream().filter(a -> ACCESSOR.equals(a.desc)).findFirst().orElseThrow();
        for (int i = 0; i + 1 < accessor.values.size(); i += 2) {
            if ("value".equals(accessor.values.get(i))) return accessor.values.get(i + 1);
        }
        return null;
    }
}
