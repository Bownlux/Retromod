/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.mixin;

import com.retromod.mixin.runtime.AreaEffectCloudEffectsBridge;
import com.retromod.mixin.runtime.AttributeSupplierBuilderBridge;
import com.google.common.collect.ImmutableMap;
import com.retromod.mixin.runtime.SynchedEntityDataBridge;
import net.minecraft.world.item.alchemy.PotionContents;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReadWriteLock;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Accessors for fields that 1.20.5 removed fail their whole mixin with
 * {@code InvalidAccessorException}. Cracker's Wither Storm reaches {@code AreaEffectCloud.effects},
 * {@code SynchedEntityData.itemsById} and {@code lock}, and {@code ZombieVillager.tradeOffers}.
 * These tests pin both halves of the repair: the dead accessor leaves the mixin, and its callers
 * reach a bridge that still sees the data.
 */
class MixinAccessorFieldMigrationTest {

    private static final String CLOUD_MIXIN = "com/example/mixin/CloudAccessor";
    private static final String CLOUD = "net/minecraft/world/entity/AreaEffectCloud";
    private static final String CALLER = "com/example/FireballHandler";
    private static final String GETTER = "witherstormmod$getEffects";

    @Test
    void removesTheDeadAccessorOnHostsWithoutTheField() {
        ClassNode mixin = read(accessorMixin(CLOUD, "effects"));

        List<MixinAccessorFieldMigration.FieldMigration> matched =
                MixinAccessorFieldMigration.removeMigratedAccessors(mixin, "1.21.1");

        assertEquals(1, matched.size(), "the AreaEffectCloud.effects rule should match");
        assertTrue(mixin.methods.stream().noneMatch(m -> m.name.equals(GETTER)),
                "Mixin would still look for the removed field while the accessor remains");
        assertTrue(mixin.methods.stream().anyMatch(m -> m.name.equals("getRadius")),
                "an accessor for a field that still exists must be kept");
    }

    @Test
    void keepsTheAccessorOnHostsThatStillHaveTheField() {
        ClassNode mixin = read(accessorMixin(CLOUD, "effects"));
        assertTrue(MixinAccessorFieldMigration.removeMigratedAccessors(mixin, "1.20.4").isEmpty());
        assertTrue(mixin.methods.stream().anyMatch(m -> m.name.equals(GETTER)),
                "the field still exists before 1.20.5, so the accessor works as written");
    }

    @Test
    void ignoresAnAccessorOfTheSameNameOnAnotherTarget() {
        ClassNode mixin = read(accessorMixin("com/example/OtherCloud", "effects"));
        assertTrue(MixinAccessorFieldMigration.removeMigratedAccessors(mixin, "1.21.1").isEmpty(),
                "rules are keyed by target class, not by field name alone");
    }

    @Test
    void pointsCallersAtTheBridge() {
        Map<String, byte[]> jar = Map.of(CLOUD_MIXIN, accessorMixin(CLOUD, "effects"));
        List<String> registered = new ArrayList<>();

        byte[] rewritten = MixinAccessorFieldMigration.rewriteCallSites(
                caller(), "1.21.1", jar::get, registered::add);

        MethodNode handler = read(rewritten).methods.stream()
                .filter(m -> m.name.equals("onHit")).findFirst().orElseThrow();
        MethodInsnNode call = calls(handler).get(0);
        assertEquals(Opcodes.INVOKESTATIC, call.getOpcode(), "the interface call must become static");
        assertEquals("com/retromod/mixin/runtime/AreaEffectCloudEffectsBridge", call.owner);
        assertEquals("(Ljava/lang/Object;)Ljava/lang/Object;", call.desc);
        assertTrue(call.getNext() instanceof TypeInsnNode cast
                        && cast.getOpcode() == Opcodes.CHECKCAST && cast.desc.equals("java/util/List"),
                "the Object result must be cast back to the accessor's type for the caller");
        assertTrue(registered.contains("com/retromod/mixin/runtime/AreaEffectCloudEffectsBridge$WriteThroughEffects"),
                "the embedder needs the bridge's nested list class too, got " + registered);
    }

    @Test
    void leavesCallersAloneOnOldHosts() {
        Map<String, byte[]> jar = Map.of(CLOUD_MIXIN, accessorMixin(CLOUD, "effects"));
        byte[] original = caller();
        assertSame(original, MixinAccessorFieldMigration.rewriteCallSites(
                original, "1.20.4", jar::get, name -> fail("nothing should be registered")));
    }

    @Test
    void clearingTheBridgedListWritesEmptyEffectsBackToTheCloud() {
        FakeCloud cloud = new FakeCloud(new PotionContents(
                Optional.of("harming"), Optional.of(0xFF0000), List.of("instant_damage")));

        @SuppressWarnings("unchecked")
        List<Object> effects = (List<Object>) AreaEffectCloudEffectsBridge.effects(cloud);
        assertEquals(List.of("instant_damage"), effects);
        effects.clear();
        effects.add("wither");

        assertEquals(List.of("wither"), cloud.potionContents.customEffects(),
                "old code that cleared and refilled the list must change what the cloud applies");
        assertEquals(Optional.of("harming"), cloud.potionContents.potion(),
                "the potion component must be copied unchanged");
        assertEquals(2, cloud.setterCalls, "each change goes through setPotionContents");
    }

    @Test
    void entityDataLockPairsLockAndUnlockAcrossCalls() {
        ReadWriteLock first = (ReadWriteLock) SynchedEntityDataBridge.lock(new Object());
        ReadWriteLock second = (ReadWriteLock) SynchedEntityDataBridge.lock(new Object());
        first.readLock().lock();
        assertDoesNotThrow(() -> second.readLock().unlock(),
                "old code locks and unlocks through two accessor calls, so both must return one lock");
    }

    @Test
    void bridgesTheAttributeBuilderMapAccessorUnderEveryName() {
        String intermediaryBuilder = "net/minecraft/class_5132$class_5133";
        for (String[] accessor : new String[][] {
                {"net/minecraft/world/entity/ai/attributes/AttributeSupplier$Builder", "builder"},
                {intermediaryBuilder, "builder"},
                {intermediaryBuilder, "instances"}}) {
            ClassNode mixin = read(mapAccessorMixin(accessor[0], accessor[1]));
            List<MixinAccessorFieldMigration.FieldMigration> matched =
                    MixinAccessorFieldMigration.removeMigratedAccessors(mixin, "1.21.1");
            assertEquals(1, matched.size(), accessor[0] + "." + accessor[1] + " should match one rule");
            assertTrue(matched.get(0).bridgeClasses().contains(
                            "com/retromod/mixin/runtime/AttributeSupplierBuilderBridge$BuilderView"),
                    "the embedder needs the bridge's nested map class too");
            assertTrue(mixin.methods.stream().noneMatch(m -> m.name.equals("getAttributeMap")));
        }
        ClassNode oldHost = read(mapAccessorMixin(intermediaryBuilder, "instances"));
        assertTrue(MixinAccessorFieldMigration.removeMigratedAccessors(oldHost, "1.20.4").isEmpty(),
                "before 1.20.5 the field is still a Map, so the accessor works as written");
    }

    @Test
    void attributeBuilderViewWritesIntoTheGuavaBuilder() {
        FakeAttributeBuilder attributes = new FakeAttributeBuilder();

        @SuppressWarnings("unchecked")
        Map<Object, Object> view = (Map<Object, Object>) AttributeSupplierBuilderBridge.builder(attributes);
        view.put("speed", 0.1);
        view.put("speed", 0.3);
        view.put("armor", 2.0);

        assertEquals(Map.of("speed", 0.3, "armor", 2.0), view,
                "the view reads what the game would build, keeping the last value of a repeated key");
        assertEquals(Map.of("speed", 0.3, "armor", 2.0), attributes.builder.buildKeepingLast(),
                "an old mod's additions must reach the builder the game builds from");
    }

    @Test
    void bridgesAClassMixinAccessorForTheRemovedItemModifiersField() {
        ClassNode mixin = read(itemModifiersClassMixin("net/minecraft/class_1738"));

        List<MixinAccessorFieldMigration.FieldMigration> matched =
                MixinAccessorFieldMigration.bridgeClassMixinAccessors(mixin, "1.21.1");

        assertEquals(1, matched.size(), "getter and setter share the ArmorItem.defaultModifiers rule");
        MethodNode getter = mixin.methods.stream().filter(m -> m.name.equals("kjs$getAttributeMap"))
                .findFirst().orElseThrow();
        MethodNode setter = mixin.methods.stream().filter(m -> m.name.equals("kjs$setAttributeMap"))
                .findFirst().orElseThrow();
        assertEquals(0, getter.access & Opcodes.ACC_ABSTRACT,
                "a class mixin is merged into its target, so the accessor can carry a body");
        assertNull(getter.visibleAnnotations == null ? null : getter.visibleAnnotations.stream()
                .filter(a -> a.desc.endsWith("/Accessor;")).findFirst().orElse(null),
                "Mixin must stop looking for the removed field");
        assertEquals("defaultModifiers", calls(getter).get(0).name);
        assertEquals("setDefaultModifiers", calls(setter).get(0).name);
        assertEquals("com/retromod/mixin/runtime/ItemComponentsBridge", calls(setter).get(0).owner);
    }

    @Test
    void boxesAPrimitiveItemFieldForItsComponentBridge() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "com/example/mixin/ItemMixin",
                null, "java/lang/Object", null);
        AnnotationVisitor annotation = cw.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false);
        AnnotationVisitor targets = annotation.visitArray("value");
        targets.visit(null, Type.getObjectType("net/minecraft/class_1792"));
        targets.visitEnd();
        annotation.visitEnd();
        MethodVisitor setter = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "kjs$setMaxStackSize",
                "(I)V", null, null);
        AnnotationVisitor accessor = setter.visitAnnotation("Lorg/spongepowered/asm/mixin/gen/Accessor;", true);
        accessor.visit("value", "maxStackSize");
        accessor.visitEnd();
        setter.visitEnd();
        cw.visitEnd();
        ClassNode mixin = read(cw.toByteArray());

        assertEquals(1, MixinAccessorFieldMigration.bridgeClassMixinAccessors(mixin, "1.21.1").size());

        List<MethodInsnNode> body = calls(mixin.methods.get(0));
        assertEquals("java/lang/Integer", body.get(0).owner, "the int is boxed for the Object-typed bridge");
        assertEquals("setMaxStackSize", body.get(1).name);
    }

    @Test
    void leavesClassMixinAccessorsAloneOnHostsThatStillHaveTheField() {
        ClassNode mixin = read(itemModifiersClassMixin("net/minecraft/class_1829"));

        assertTrue(MixinAccessorFieldMigration.bridgeClassMixinAccessors(mixin, "1.20.1").isEmpty());
        assertTrue(mixin.methods.stream().allMatch(m -> (m.access & Opcodes.ACC_ABSTRACT) != 0
                        || m.name.equals("<init>")),
                "before 1.20.5 the Multimap field exists, so the accessors work as written");
    }

    /**
     * {@code @Mixin(target) abstract class ToolItemMixin implements ModifiableItemKJS} with an
     * {@code @Accessor("defaultModifiers")} getter and a {@code @Mutable} setter, as KubeJS 2001 ships.
     */
    private static byte[] itemModifiersClassMixin(String target) {
        String multimap = "Lcom/google/common/collect/Multimap;";
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "com/example/mixin/ToolItemMixin",
                null, "java/lang/Object", new String[] {"com/example/ModifiableItem"});
        AnnotationVisitor mixin = cw.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false);
        AnnotationVisitor targets = mixin.visitArray("value");
        targets.visit(null, Type.getObjectType(target));
        targets.visitEnd();
        mixin.visitEnd();
        accessor(cw, "kjs$getAttributeMap", "()" + multimap, "defaultModifiers");
        MethodVisitor setter = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "kjs$setAttributeMap",
                "(" + multimap + ")V", null, null);
        AnnotationVisitor accessor = setter.visitAnnotation("Lorg/spongepowered/asm/mixin/gen/Accessor;", true);
        accessor.visit("value", "defaultModifiers");
        accessor.visitEnd();
        setter.visitAnnotation("Lorg/spongepowered/asm/mixin/Mutable;", true).visitEnd();
        setter.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Stands in for AttributeSupplier.Builder since 1.20.5: a private Guava builder field. */
    static final class FakeAttributeBuilder {
        private final ImmutableMap.Builder<Object, Object> builder = ImmutableMap.builder();
    }

    /** {@code @Mixin(target) interface BuilderAccessor { @Accessor(field) Map getAttributeMap(); }} */
    private static byte[] mapAccessorMixin(String target, String field) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE,
                "com/example/mixin/BuilderAccessor", null, "java/lang/Object", null);
        AnnotationVisitor mixin = cw.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false);
        AnnotationVisitor targets = mixin.visitArray("value");
        targets.visit(null, Type.getObjectType(target));
        targets.visitEnd();
        mixin.visitEnd();
        accessor(cw, "getAttributeMap", "()Ljava/util/Map;", field);
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Stands in for AreaEffectCloud: a private PotionContents field and its public setter. */
    static final class FakeCloud {
        private PotionContents potionContents;
        int setterCalls;

        FakeCloud(PotionContents contents) {
            this.potionContents = contents;
        }

        public void setPotionContents(PotionContents contents) {
            this.potionContents = contents;
            setterCalls++;
        }
    }

    /** {@code @Mixin(target) interface CloudAccessor { @Accessor(field) List getEffects(); @Accessor float getRadius(); }} */
    private static byte[] accessorMixin(String target, String field) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE,
                CLOUD_MIXIN, null, "java/lang/Object", null);
        AnnotationVisitor mixin = cw.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false);
        AnnotationVisitor targets = mixin.visitArray("value");
        targets.visit(null, Type.getObjectType(target));
        targets.visitEnd();
        mixin.visitEnd();
        accessor(cw, GETTER, "()Ljava/util/List;", field);
        accessor(cw, "getRadius", "()F", null);
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void accessor(ClassWriter cw, String name, String desc, String field) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, name, desc, null, null);
        AnnotationVisitor accessor = mv.visitAnnotation("Lorg/spongepowered/asm/mixin/gen/Accessor;", true);
        if (field != null) accessor.visit("value", field);
        accessor.visitEnd();
        mv.visitEnd();
    }

    /** {@code void onHit(AreaEffectCloud cloud) { ((CloudAccessor) cloud).getEffects().clear(); }} */
    private static byte[] caller() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, CALLER, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "onHit", "(L" + CLOUD + ";)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitTypeInsn(Opcodes.CHECKCAST, CLOUD_MIXIN);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, CLOUD_MIXIN, GETTER, "()Ljava/util/List;", true);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/List", "clear", "()V", true);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static List<MethodInsnNode> calls(MethodNode method) {
        List<MethodInsnNode> calls = new ArrayList<>();
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call) calls.add(call);
        }
        return calls;
    }
}
