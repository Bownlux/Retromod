/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.mixin;

import com.retromod.core.RetromodTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the mixin blocklist that turns fatal mixin handlers inert (#28).
 */
class MixinBlocklistTest {

    @AfterEach
    void reset() {
        MixinBlocklist.resetForTesting();
    }

    private static final String FIXTURE_TARGET = "com/example/Target";
    private static final String FIXTURE_INTERFACE = "com/example/AddedInterface";

    /** A minimal @Mixin class with a blocked handler, an injector, members, and a plain helper. */
    private static byte[] mixinClass(String internalName) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object",
                new String[]{FIXTURE_INTERFACE});
        AnnotationVisitor mixin = cw.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false);
        AnnotationVisitor targets = mixin.visitArray("value");
        targets.visit(null, Type.getObjectType(FIXTURE_TARGET));
        targets.visitEnd();
        mixin.visitEnd();

        // @Shadow field and @Accessor method: members, not handlers, so a handler strip keeps them
        FieldVisitor shadow = cw.visitField(Opcodes.ACC_PRIVATE, "shadowedValue", "I", null, null);
        shadow.visitAnnotation("Lorg/spongepowered/asm/mixin/Shadow;", false).visitEnd();
        shadow.visitEnd();
        MethodVisitor accessor = cw.visitMethod(Opcodes.ACC_PUBLIC, "getShadowedValue", "()I", null, null);
        accessor.visitAnnotation("Lorg/spongepowered/asm/mixin/gen/Accessor;", false).visitEnd();
        accessor.visitCode();
        accessor.visitInsn(Opcodes.ICONST_0);
        accessor.visitInsn(Opcodes.IRETURN);
        accessor.visitMaxs(1, 1);
        accessor.visitEnd();

        MethodVisitor ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(1, 1);
        ctor.visitEnd();

        // blocked MixinExtras @WrapOperation handler
        MethodVisitor h = cw.visitMethod(Opcodes.ACC_PRIVATE,
                "deeperdarker$decrementStackOnServer", "()V", null, null);
        h.visitAnnotation("Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;", false).visitEnd();
        h.visitCode();
        h.visitInsn(Opcodes.RETURN);
        h.visitMaxs(0, 1);
        h.visitEnd();

        // standard @Inject handler, used by the whole-class case
        MethodVisitor inj = cw.visitMethod(Opcodes.ACC_PRIVATE, "onSomething", "()V", null, null);
        inj.visitAnnotation("Lorg/spongepowered/asm/mixin/injection/Inject;", false).visitEnd();
        inj.visitCode();
        inj.visitInsn(Opcodes.RETURN);
        inj.visitMaxs(0, 1);
        inj.visitEnd();

        // plain helper with no injector annotation, must survive
        MethodVisitor keep = cw.visitMethod(Opcodes.ACC_PRIVATE, "plainHelper", "()V", null, null);
        keep.visitCode();
        keep.visitInsn(Opcodes.RETURN);
        keep.visitMaxs(0, 1);
        keep.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    private static Set<String> methodNames(byte[] bytes) {
        Set<String> names = new HashSet<>();
        for (MethodNode m : classNode(bytes).methods) names.add(m.name);
        return names;
    }

    private static ClassNode classNode(byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        return cn;
    }

    @Test
    @DisplayName("#28 correction: stale PaintingItemMixin retired; real HangingEntityItemMixin/PaintingMixin handlers listed")
    void loadsBundledDeeperDarkerEntry() {
        MixinBlocklist.resetForTesting(); // fresh load of the bundled resource
        // The fictional PaintingItemMixin/deeperdarker$decrementStackOnServer entry (absent from the
        // 1.4.1 jar) is retired - it was a silent no-op that hid the mod's real drift.
        assertNull(MixinBlocklist.methodsToStrip("com/kyanite/deeperdarker/mixin/PaintingItemMixin"),
                "the fictional PaintingItemMixin entry must be retired");
        // HangingEntityItemMixin.appendHoverText (param split + deleted-API body) is stripped.
        Set<String> hei = MixinBlocklist.methodsToStrip("com/kyanite/deeperdarker/mixin/HangingEntityItemMixin");
        assertNotNull(hei, "HangingEntityItemMixin must be blocklisted");
        assertTrue(hei.contains("appendHoverText"), "the unrepairable appendHoverText handler must be listed");
        // PaintingMixin.dropItem (ServerLevel drift) is stripped; getPickResult applies natively.
        Set<String> pm = MixinBlocklist.methodsToStrip("com/kyanite/deeperdarker/mixin/PaintingMixin");
        assertNotNull(pm, "PaintingMixin must be blocklisted");
        assertTrue(pm.contains("dropItem"), "the drifted dropItem handler must be listed");
        assertFalse(pm.contains("getPickResult"), "getPickResult applies natively - must NOT be stripped");
    }

    @Test
    @DisplayName("Surgical: only the named handler is stripped, everything else stays")
    void stripsNamedHandlerOnly() {
        MixinBlocklist.setForTesting(Map.of(
                "test/FooMixin", Set.of("deeperdarker$decrementStackOnServer")));
        var t = new MixinCompatibilityTransformer(RetromodTransformer.getInstance());

        Set<String> names = methodNames(t.transformMixinClass(mixinClass("test/FooMixin")));
        assertFalse(names.contains("deeperdarker$decrementStackOnServer"), "blocked handler removed");
        assertTrue(names.contains("onSomething"), "other injector kept (not named)");
        assertTrue(names.contains("plainHelper"), "plain helper kept");
        assertTrue(names.contains("<init>"), "constructor kept");
    }

    @Test
    @DisplayName("No methods: every injector dropped, accessors, shadows, helpers and target kept")
    void wholeClassStripsInjectorsKeepsHelpers() {
        MixinBlocklist.setForTesting(Map.of("test/BarMixin", Set.of())); // empty means all injectors
        var t = new MixinCompatibilityTransformer(RetromodTransformer.getInstance());

        byte[] out = t.transformMixinClass(mixinClass("test/BarMixin"));
        Set<String> names = methodNames(out);
        assertFalse(names.contains("deeperdarker$decrementStackOnServer"), "MixinExtras handler stripped");
        assertFalse(names.contains("onSomething"), "@Inject handler stripped");
        assertTrue(names.contains("plainHelper"), "plain helper kept");
        assertTrue(names.contains("<init>"), "constructor kept");

        // docs/troubleshooting.md promises these stay; only "strip": "class" disables the whole mixin.
        ClassNode node = classNode(out);
        assertTrue(names.contains("getShadowedValue"), "@Accessor method kept without \"strip\": \"class\"");
        assertTrue(node.fields.stream().anyMatch(f -> f.name.equals("shadowedValue")), "@Shadow field kept");
        assertTrue(node.interfaces.contains(FIXTURE_INTERFACE), "the interface the mixin adds is kept");
        assertTrue(hasMixinValueTargets(out), "the original @Mixin target is kept");
        assertTrue(mixinStringTargets(out).isEmpty(),
                "no retromod/stripped placeholder target without \"strip\": \"class\"");
    }

    @Test
    @DisplayName("Troubleshooting page shows the whole-mixin switch and the version range keys")
    void troubleshootingDocumentsWholeMixinSwitch() throws IOException {
        String page = Files.readString(Path.of("docs/troubleshooting.md"));

        // MixinBlocklist reads these keys; leaving out "methods" does not disable a whole mixin.
        for (String key : List.of("\"strip\": \"class\"", "\"targetAtLeast\"", "\"targetAtMost\"")) {
            assertTrue(page.contains(key),
                    "docs/troubleshooting.md should show " + key + " in the mixin blocklist example");
        }
    }

    @Test
    @DisplayName("End-to-end (#28): the bundled entry strips PaintingMixin.dropItem, keeps getPickResult")
    void bundledEntryStripsRealMixin() {
        MixinBlocklist.resetForTesting(); // use the bundled list, not a test injection
        var t = new MixinCompatibilityTransformer(RetromodTransformer.getInstance());

        Set<String> names = methodNames(t.stripBlocklistedHandlers(mixinClassWithMethods(
                "com/kyanite/deeperdarker/mixin/PaintingMixin", "dropItem", "getPickResult")));
        assertFalse(names.contains("dropItem"),
                "the drifted dropItem @Inject must be stripped from the real mixin name");
        assertTrue(names.contains("getPickResult"),
                "getPickResult applies natively after the Painting class-move and must survive");
    }

    /** A minimal @Mixin class with two named handler methods plus a ctor. */
    private static byte[] mixinClassWithMethods(String internalName, String... methodNames) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        cw.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false).visitEnd();
        MethodVisitor ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(1, 1);
        ctor.visitEnd();
        for (String name : methodNames) {
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PRIVATE, name, "()V", null, null);
            mv.visitAnnotation("Lorg/spongepowered/asm/mixin/injection/Inject;", false).visitEnd();
            mv.visitCode();
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(0, 1);
            mv.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    @Test
    @DisplayName("#50: PhantomMixin is RETIRED from the bundled blocklist (repaired by the owner-alias redirect)")
    void phantomMixinRetiredFromBlocklist() {
        MixinBlocklist.resetForTesting();
        assertNull(MixinBlocklist.methodsToStrip("dev/lukebemish/revampedphantoms/mixin/PhantomMixin"),
                "PhantomMixin is no longer blocklisted - the FlyingMob->Mob owner-alias redirect repairs it");
    }

    @Test
    @DisplayName("#50: the strip pass leaves the retired PhantomMixin's handlers intact")
    void phantomMixinPassesStripUntouched() {
        MixinBlocklist.resetForTesting(); // use the bundled list
        var t = new MixinCompatibilityTransformer(RetromodTransformer.getInstance());
        Set<String> names = methodNames(t.stripBlocklistedHandlers(mixinClassWithMethods(
                "dev/lukebemish/revampedphantoms/mixin/PhantomMixin",
                "revamped_phantoms$getDefaultDimensions", "revamped_phantoms$registerGoals")));
        assertTrue(names.contains("revamped_phantoms$getDefaultDimensions"),
                "the dimension handler survives - PhantomMixin is retired; the owner-alias redirect repairs it");
        assertTrue(names.contains("revamped_phantoms$registerGoals"),
                "the goals handler survives too");
    }

    @Test
    @DisplayName("A mixin not on the blocklist is left untouched")
    void nonBlockedClassUntouched() {
        MixinBlocklist.setForTesting(Map.of("other/Thing", Set.of("x")));
        var t = new MixinCompatibilityTransformer(RetromodTransformer.getInstance());

        Set<String> names = methodNames(t.transformMixinClass(mixinClass("test/FooMixin")));
        assertTrue(names.contains("deeperdarker$decrementStackOnServer"),
                "non-blocked mixin keeps all handlers");
        assertTrue(names.contains("onSomething"));
    }


    /** A @Mixin(value = {targetInternal.class}) class, for neutralization tests. */
    private static byte[] mixinClassWithTarget(String internalName, String targetInternal) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        org.objectweb.asm.AnnotationVisitor av =
                cw.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false);
        org.objectweb.asm.AnnotationVisitor arr = av.visitArray("value");
        arr.visit(null, org.objectweb.asm.Type.getObjectType(targetInternal));
        arr.visitEnd();
        av.visitEnd();

        MethodVisitor ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(1, 1);
        ctor.visitEnd();
        // @Inject handler must not remain reachable once neutralized
        MethodVisitor inj = cw.visitMethod(Opcodes.ACC_PRIVATE, "onRenderLevel", "()V", null, null);
        inj.visitAnnotation("Lorg/spongepowered/asm/mixin/injection/Inject;", false).visitEnd();
        inj.visitCode();
        inj.visitInsn(Opcodes.RETURN);
        inj.visitMaxs(0, 1);
        inj.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** The @Mixin annotation's string {@code targets} list. */
    private static java.util.List<String> mixinStringTargets(byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        java.util.List<org.objectweb.asm.tree.AnnotationNode> anns =
                cn.invisibleAnnotations != null ? cn.invisibleAnnotations
                        : (cn.visibleAnnotations != null ? cn.visibleAnnotations : java.util.List.of());
        for (var a : anns) {
            if (!"Lorg/spongepowered/asm/mixin/Mixin;".equals(a.desc) || a.values == null) continue;
            for (int i = 0; i < a.values.size(); i += 2) {
                if ("targets".equals(a.values.get(i)) && a.values.get(i + 1) instanceof java.util.List<?> l) {
                    java.util.List<String> out = new java.util.ArrayList<>();
                    for (Object o : l) out.add(String.valueOf(o));
                    return out;
                }
            }
        }
        return java.util.List.of();
    }

    /** Whether the @Mixin annotation still declares any Class[] {@code value} targets. */
    private static boolean hasMixinValueTargets(byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        java.util.List<org.objectweb.asm.tree.AnnotationNode> anns =
                cn.invisibleAnnotations != null ? cn.invisibleAnnotations
                        : (cn.visibleAnnotations != null ? cn.visibleAnnotations : java.util.List.of());
        for (var a : anns) {
            if (!"Lorg/spongepowered/asm/mixin/Mixin;".equals(a.desc) || a.values == null) continue;
            for (int i = 0; i < a.values.size(); i += 2) {
                if ("value".equals(a.values.get(i)) && a.values.get(i + 1) instanceof java.util.List<?> l
                        && !l.isEmpty()) {
                    return true;
                }
            }
        }
        return false;
    }

    @Test
    @DisplayName("Whole-class strip repoints @Mixin target to a non-existent placeholder (Fabric path)")
    void wholeClassStripNeutralizesMixinFabric() {
        MixinBlocklist.setForTesting(
                Map.of("test/InterfaceMixin", Set.of()),
                Set.of("test/InterfaceMixin")); // full strip
        var t = new MixinCompatibilityTransformer(RetromodTransformer.getInstance());

        byte[] out = t.transformMixinClass(
                mixinClassWithTarget("test/InterfaceMixin", "net/minecraft/SomeTarget"));
        assertTrue(mixinStringTargets(out).contains("retromod/stripped/InterfaceMixin"),
                "neutralized mixin must point at the placeholder so the framework skips it");
        assertFalse(hasMixinValueTargets(out),
                "the original Class[] value target must be cleared");
    }

    @Test
    @DisplayName("Whole-class strip also works on the NeoForge/Forge path (stripBlocklistedHandlers)")
    void wholeClassStripNeutralizesMixinNeoForge() {
        MixinBlocklist.setForTesting(
                Map.of("test/InterfaceMixin", Set.of()),
                Set.of("test/InterfaceMixin"));
        var t = new MixinCompatibilityTransformer(RetromodTransformer.getInstance());

        byte[] out = t.stripBlocklistedHandlers(
                mixinClassWithTarget("test/InterfaceMixin", "net/minecraft/SomeTarget"));
        assertTrue(mixinStringTargets(out).contains("retromod/stripped/InterfaceMixin"),
                "NeoForge path must neutralize too - #68 True Darkness is a NeoForge mod");
    }

    @Test
    @DisplayName("Bundled blocklist marks True Darkness render mixins as full-class strips (#68)")
    void bundledDarknessEntriesAreFullStrip() {
        MixinBlocklist.resetForTesting();
        assertTrue(MixinBlocklist.isFullStrip("grondag/darkness/mixin/MixinLightTexture"),
                "MixinLightTexture (interface-adder) must be a whole-class strip");
        assertTrue(MixinBlocklist.isFullStrip("grondag/darkness/mixin/MixinGameRenderer"),
                "MixinGameRenderer (the ClassCastException consumer) must be a whole-class strip");
    }

    @Test
    @DisplayName("1.3.0: the repairable #48/#69/#50 entries are RETIRED; unrepairable strips remain")
    void repairableEntriesRetired() {
        MixinBlocklist.resetForTesting();
        // #69 SweepAttackMixin: repaired by the @At signature-drift rewrite (doHurtTarget INVOKE
        // injection point); every other anchor verified present in the 26.2 jar. No longer stripped.
        assertFalse(MixinBlocklist.isFullStrip("dev/lukebemish/revampedphantoms/mixin/SweepAttackMixin"),
                "SweepAttackMixin is repaired by the @At drift rewrite, not stripped");
        assertNull(MixinBlocklist.methodsToStrip("dev/lukebemish/revampedphantoms/mixin/SweepAttackMixin"),
                "no handler strip for SweepAttackMixin either");
        // #48 Darker Depths PlayerMixin: repaired by the ValueIO adapter (repair-or-strip). Retired.
        assertNull(MixinBlocklist.methodsToStrip("com/naterbobber/darkerdepths/mixin/PlayerMixin"),
                "PlayerMixin save-data handlers are ValueIO-adapted now, not blocklist-stripped");
        // #50 PhantomMixin: RETIRED. Phantom STILL declares getDefaultDimensions(Pose) on 26.1/26.2
        // (its body calls Mob.getDefaultDimensions, the former super.getDefaultDimensions), so the
        // @ModifyExpressionValue's only break was the @At INVOKE target still naming the deleted
        // FlyingMob. The FlyingMob->Mob owner-alias redirect (in the 1.21.11->26.1 shims) repairs it,
        // so the phantom-size tweak is live again, not stripped.
        assertNull(MixinBlocklist.methodsToStrip("dev/lukebemish/revampedphantoms/mixin/PhantomMixin"),
                "PhantomMixin is repaired by the FlyingMob->Mob owner-alias redirect, not stripped");
        assertFalse(MixinBlocklist.isFullStrip("dev/lukebemish/revampedphantoms/mixin/PhantomMixin"),
                "PhantomMixin is fully retired from the blocklist");
    }
}
