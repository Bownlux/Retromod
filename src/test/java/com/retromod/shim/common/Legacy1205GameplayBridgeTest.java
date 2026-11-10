/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.shim.common.Legacy1205GameplayBridge.Shape;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;

import static com.retromod.shim.common.Legacy1205GameplayBridge.*;
import static org.junit.jupiter.api.Assertions.*;

/** A 1.20.1 mod's arrows, effects, death loot and item calls against the shapes 1.20.5 to 1.21.2 gave them. */
class Legacy1205GameplayBridgeTest {

    private static final String L_STACK = "L" + ITEM_STACK + ";";
    private static final String L_LIVING = "L" + LIVING + ";";
    private static final String L_MAP = "L" + ATTRIBUTE_MAP + ";";
    private static final String OLD_ATTRIBUTES = "(" + L_LIVING + L_MAP + "I)V";
    private static final BiPredicate<String, String> DIRECT_PARENTS = (child, ancestor) -> false;

    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;

    @AfterEach
    void reset() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    // ---- call sites ------------------------------------------------------------------------------

    @Test
    void oldItemAndEffectCallsReachTheirReplacements() {
        RetromodTransformer transformer = registered("1.21.1", Shape.AT_1_21_1);

        List<String> calls = calls(node(transformer.transformClass(itemCaller(), "test/Calls.class")), "run");

        String helper = HELPER;
        assertTrue(calls.contains(ITEM_STACK + ".isSameItemSameComponents(" + L_STACK + L_STACK + ")Z"), calls.toString());
        assertTrue(calls.contains(helper + ".updateMobEffectPacket(IL" + EFFECT_INSTANCE + ";)L" + EFFECT_PACKET + ";"),
                "the old effect packet must be built with the blend flag: " + calls);
        assertTrue(calls.contains(helper + ".setHoverName(" + L_STACK + "L" + COMPONENT + ";)" + L_STACK), calls.toString());
        assertTrue(calls.contains(helper + ".blockBox(L" + BLOCK_POS + ";L" + BLOCK_POS + ";)L" + AABB + ";"), calls.toString());
        assertTrue(calls.contains(helper + ".readBlockPos(L" + COMPOUND_TAG + ";)L" + BLOCK_POS + ";"),
                "a 1.21.1 host reads the old position compound through the helper: " + calls);
        assertTrue(calls.contains(STRING_UTIL + ".isAllowedChatCharacter(C)Z"), calls.toString());
        assertTrue(calls.contains(ARROW_MEMBERS + ".setKnockback(Ljava/lang/Object;I)V"), calls.toString());
    }

    @Test
    void positionTagsAreLeftToTheRebuiltMembersFrom1215() {
        RetromodTransformer transformer = registered("26.3", Shape.AT_26_3);

        List<String> calls = calls(node(transformer.transformClass(itemCaller(), "test/Calls.class")), "run");

        assertFalse(calls.stream().anyMatch(call -> call.startsWith(HELPER + ".readBlockPos")),
                "26.3 already answers the old position compound through the 1.21.5 shims: " + calls);
        assertTrue(calls.contains(STRING_UTIL + ".isAllowedChatCharacter(I)Z"),
                "26.x takes a code point, which the char already is on the stack: " + calls);
    }

    @Test
    void oldArrowConstructorsTakeAPickupStackAndTheFiringWeapon() {
        RetromodTransformer transformer = registered("1.21.1", Shape.AT_1_21_1);

        ClassNode node = node(transformer.transformClass(oldArrow(ARROW_1205), "test/OldArrow.class"));

        String twoStacks = L_STACK + L_STACK + ")V";
        List<String> ctorCalls = new ArrayList<>();
        for (MethodNode method : node.methods) {
            if (method.name.equals("<init>")) ctorCalls.addAll(calls(method));
        }
        assertTrue(ctorCalls.contains(ARROW_1205 + ".<init>(L" + ENTITY_TYPE + ";DDDL" + LEVEL + ";" + twoStacks), ctorCalls.toString());
        assertTrue(ctorCalls.contains(ARROW_1205 + ".<init>(L" + ENTITY_TYPE + ";" + L_LIVING + "L" + LEVEL + ";" + twoStacks),
                ctorCalls.toString());
        assertTrue(readsArrowItem(node), "the pickup stack is a plain arrow");
        assertTrue(ctorCalls.contains(ARROW_MEMBERS + ".weapon()" + L_STACK), "the weapon is the bow an old arrow item fires from");
    }

    @Test
    void generatedHelperIsStructurallyValidForBothShapes() throws Exception {
        for (Shape shape : List.of(Shape.AT_1_21_1, Shape.AT_26_3)) {
            ClassNode node = node(Legacy1205GameplayBridge.generateHelper(shape));
            for (MethodNode method : node.methods) new Analyzer<>(new BasicVerifier()).analyze(node.name, method);
        }
    }

    // ---- overrides, run against generated host classes --------------------------------------------

    @Test
    void anOldEffectRunsItsAttributeOverridesWithTheEntity() throws Exception {
        byte[] effect = Legacy1205OverrideAdapter.apply(oldEffect(), DIRECT_PARENTS, name -> null, Shape.AT_1_21_1);
        HostClasses host = HostClasses.effects(false);
        Class<?> effectClass = host.define("test/OldEffect", effect);
        Object instance = effectClass.getConstructor().newInstance();
        Object entity = host.newEntity();
        Object attributes = entity.getClass().getMethod("getAttributes").invoke(entity);

        // The host adds the effect before any hook has named the entity, then calls the effect's hook.
        host.call(instance, "addAttributeModifiers", attributes, 2);
        assertEquals(List.of("host add 2"), host.drain(), "an unknown owner gets the host's attributes at once");
        host.call(instance, "onEffectAdded", entity, 2);
        assertEquals(List.of("host added", "old add 2", "host add 2"), host.drain(),
                "the hook runs the waiting old addition with the entity");
        // Later additions and the removal know the entity, and the removal gets the amplifier it was added with.
        host.call(instance, "removeAttributeModifiers", attributes);
        assertEquals(List.of("old remove 2", "host remove"), host.drain());
    }

    @Test
    void anOldEffectTicksThroughTheHostForms() throws Exception {
        for (boolean levelForm : new boolean[]{false, true}) {
            Shape shape = levelForm ? Shape.AT_26_3 : Shape.AT_1_21_1;
            byte[] effect = Legacy1205OverrideAdapter.apply(oldEffect(), DIRECT_PARENTS, name -> null, shape);
            HostClasses host = HostClasses.effects(levelForm);
            Class<?> effectClass = host.define("test/OldEffect", effect);
            Object instance = effectClass.getConstructor().newInstance();
            Object entity = host.newEntity();

            assertEquals(true, host.call(instance, "shouldApplyEffectTickThisTick", 40, 1),
                    "the old tick check answers the renamed one");
            Object kept = levelForm ? host.call(instance, "applyEffectTick", host.newLevel(), entity, 1)
                    : host.call(instance, "applyEffectTick", entity, 1);
            assertEquals(true, kept, "an old void tick keeps the effect");
            assertEquals(List.of("old tick 1", "host tick"), host.drain(),
                    "the old tick runs, and its super call reaches the host's tick" + (levelForm ? " with the level" : ""));
            // No hook has run, as for an effect loaded with a saved world, yet the tick named the entity and amplifier.
            host.call(instance, "removeAttributeModifiers", entity.getClass().getMethod("getAttributes").invoke(entity));
            assertEquals(List.of("old remove 1", "host remove"), host.drain(), "the old removal runs after a reload");
        }
    }

    @Test
    void anOldArrowGetsAVerifiedDefaultPickupAndKnockback() throws Exception {
        RetromodTransformer transformer = registered("1.21.1", Shape.AT_1_21_1);
        byte[] arrow = transformer.transformClass(oldArrow(ARROW_1205), "test/OldArrow.class");
        byte[] adapted = Legacy1205OverrideAdapter.apply(arrow, DIRECT_PARENTS, name -> null, Shape.AT_1_21_1);

        ClassNode node = node(adapted);
        assertNotNull(method(node, "getDefaultPickupItem"), "the parent's abstract pickup default is answered");
        assertEquals(List.of("java/lang/RuntimeException", "java/lang/LinkageError"),
                method(node, "getDefaultPickupItem").tryCatchBlocks.stream().map(block -> block.type).toList(),
                "a pickup method that fails or does not link while the parent builds falls back to a plain arrow");
        assertNotNull(method(node, "doKnockback"), "the knockback a mod sets is applied on a hit");
        // Defining the class against a host hierarchy runs the JVM's verifier over the added methods and frames.
        HostClasses host = HostClasses.arrows();
        Class<?> defined = host.define("test/OldArrow", adapted);
        assertDoesNotThrow(() -> Class.forName(defined.getName(), true, defined.getClassLoader()));
    }

    @Test
    void anOldDeathLootOverrideRunsFromTheHostForm() {
        byte[] adapted = Legacy1205OverrideAdapter.apply(oldMob(), (child, ancestor) -> ancestor.equals(LIVING),
                name -> null, Shape.AT_1_21_1);

        ClassNode node = node(adapted);
        String hostForm = "(L" + SERVER_LEVEL + ";L" + DAMAGE_SOURCE + ";Z)V";
        MethodNode bridge = node.methods.stream().filter(m -> m.name.equals("dropCustomDeathLoot") && m.desc.equals(hostForm))
                .findFirst().orElseThrow(() -> new AssertionError("no host-form death loot override"));
        assertTrue(calls(bridge).contains(ENCHANTMENT_SUPPORT + ".lootingLevel(Ljava/lang/Object;)I"), calls(bridge).toString());
        List<String> oldCalls = calls(node.methods.stream()
                .filter(m -> m.name.equals("dropCustomDeathLoot") && m.desc.startsWith("(L" + DAMAGE_SOURCE)).findFirst().orElseThrow());
        assertTrue(oldCalls.contains("net/minecraft/world/entity/monster/Monster.dropCustomDeathLoot" + hostForm),
                "the old super call reaches the host's form: " + oldCalls);
    }

    @Test
    void aModParentThatDeclaresTheOldFormKeepsTheSuperCall() {
        Map<String, byte[]> jar = new HashMap<>();
        jar.put("test/OldEffect", oldEffect());
        byte[] child = effectSubclass();

        byte[] adapted = Legacy1205OverrideAdapter.apply(child, (c, a) -> true, jar::get, Shape.AT_1_21_1);

        List<String> calls = calls(node(adapted), "addAttributeModifiers");
        assertTrue(calls.contains("test/OldEffect.addAttributeModifiers" + OLD_ATTRIBUTES),
                "the mod parent still answers the old form: " + calls);
    }

    @Test
    void theAdapterIsIdempotent() {
        byte[] once = Legacy1205OverrideAdapter.apply(oldEffect(), DIRECT_PARENTS, name -> null, Shape.AT_1_21_1);
        byte[] twice = Legacy1205OverrideAdapter.apply(once, DIRECT_PARENTS, name -> null, Shape.AT_1_21_1);
        assertSame(once, twice, "a second pass finds nothing left to change");
    }

    @Test
    void worldDataThatSavesItsOwnFileGetsTheProviderForm() {
        RetromodVersion.TARGET_MC_VERSION = "1.21.1";
        String savedData = "net/minecraft/world/level/saveddata/SavedData";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, "test/ChestData", null, savedData, null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "save", "(Ljava/io/File;)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, savedData, "save", "(Ljava/io/File;)V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();

        ClassNode node = node(LegacyRegistryNbtBridge.apply(cw.toByteArray(), (child, ancestor) -> ancestor.equals(savedData),
                name -> null));

        String withProvider = "(Ljava/io/File;Lnet/minecraft/core/HolderLookup$Provider;)V";
        MethodNode bridge = node.methods.stream().filter(m -> m.name.equals("save") && m.desc.equals(withProvider))
                .findFirst().orElseThrow(() -> new AssertionError("the host's file save never reaches the old one"));
        assertTrue((bridge.access & Opcodes.ACC_PUBLIC) != 0, "it overrides a public method");
        assertTrue(calls(bridge).contains("test/ChestData.save(Ljava/io/File;)V"), calls(bridge).toString());
        List<String> oldCalls = calls(node.methods.stream()
                .filter(m -> m.name.equals("save") && m.desc.equals("(Ljava/io/File;)V")).findFirst().orElseThrow());
        assertTrue(oldCalls.contains(savedData + ".save" + withProvider), "the old super call takes the registries: " + oldCalls);
    }

    @Test
    void aDirectWorldDataSaveReachesTheDataOnEveryHost() {
        String savedData = "net/minecraft/world/level/saveddata/SavedData";
        for (String host : new String[]{"1.21.1", "26.3"}) {
            RetromodVersion.TARGET_MC_VERSION = host;
            RetromodTransformer transformer = RetromodTransformer.getInstance();
            transformer.clearRedirectsForTesting();
            if (host.equals("1.21.1")) {
                LegacyRegistryNbtBridge.registerRedirects(transformer, false);
                LegacyRegistryNbtBridge.registerSavedDataRedirects(transformer);
            } else {
                LegacyRegistryNbtBridge.registerValueIoRedirects(transformer, false);
            }
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, "test/DataCopy", null, "java/lang/Object", null);
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run",
                    "(L" + savedData + ";)L" + COMPOUND_TAG + ";", null, null);
            mv.visitCode();
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitTypeInsn(Opcodes.NEW, COMPOUND_TAG);
            mv.visitInsn(Opcodes.DUP);
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, COMPOUND_TAG, "<init>", "()V", false);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, savedData, "save", "(L" + COMPOUND_TAG + ";)L" + COMPOUND_TAG + ";", false);
            mv.visitInsn(Opcodes.ARETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
            cw.visitEnd();

            List<String> calls = calls(node(transformer.transformClass(cw.toByteArray(), "test/DataCopy.class")), "run");

            String expected = host.equals("1.21.1")
                    ? LegacyRegistryNbtBridge.HELPER + ".dataSave(L" + savedData + ";L" + COMPOUND_TAG + ";)L" + COMPOUND_TAG + ";"
                    : LegacyRegistryNbtBridge.MEMBERS + ".dataSave(Ljava/lang/Object;L" + COMPOUND_TAG + ";)L" + COMPOUND_TAG + ";";
            assertTrue(calls.contains(expected), host + ": " + calls);
        }
    }

    @Test
    void aJarThatReadsALevelTheOldWayIsAnOldEnchantmentJar() {
        String enchantment = "net/minecraft/world/item/enchantment/Enchantment";
        ClassWriter old = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        old.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, "test/Fortune", null, "java/lang/Object", null);
        MethodVisitor mv = old.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "level", "(L" + enchantment + ";" + L_STACK + ")I", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "net/minecraft/world/item/enchantment/EnchantmentHelper",
                "getItemEnchantmentLevel", "(L" + enchantment + ";" + L_STACK + ")I", false);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        old.visitEnd();
        // A 1.21 helper that only takes the record keeps it.
        ClassWriter modern = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        modern.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, "test/Curses", null, "java/lang/Object", null);
        mv = modern.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "getEnchantmentLevel", "(L" + enchantment + ";)I", null, null);
        mv.visitCode();
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        modern.visitEnd();

        assertTrue(LegacyEnchantmentBridge.usesOldEnchantment(old.toByteArray()));
        assertFalse(LegacyEnchantmentBridge.usesOldEnchantment(modern.toByteArray()));
        // A Forge 1.20.1 jar is read before its SRG names are remapped.
        assertTrue(LegacyEnchantmentBridge.usesOldEnchantment(
                renameCalls(old.toByteArray(), "getItemEnchantmentLevel", "m_44843_")));
    }

    @Test
    void anOldArrowItemFiresWithTheBowAsItsWeapon() throws Exception {
        byte[] adapted = Legacy1205OverrideAdapter.apply(oldArrowItem(), DIRECT_PARENTS, name -> null, Shape.AT_1_21_1);
        HostClasses host = HostClasses.arrows();
        host.withArrowItem();
        Class<?> item = host.define("test/OldArrowItem", adapted);
        Object instance = item.getConstructor().newInstance();
        Object bow = host.loadClass(ITEM_STACK.replace('/', '.')).getConstructor().newInstance();

        Method hostForm = item.getMethod("createArrow", host.loadClass(LEVEL.replace('/', '.')),
                host.loadClass(ITEM_STACK.replace('/', '.')), host.loadClass(LIVING.replace('/', '.')),
                host.loadClass(ITEM_STACK.replace('/', '.')));
        hostForm.invoke(instance, null, null, null, bow);

        assertSame(bow, item.getField("seen").get(null), "the old createArrow ran with the bow as the current weapon");
        assertNull(host.loadClass(ARROW_MEMBERS.replace('/', '.')).getMethod("weapon").invoke(null),
                "the weapon is cleared once the old method returns");
    }

    @Test
    void mixinsThatNameTheHostEnchantmentKeepIt() {
        String enchantment = "net/minecraft/world/item/enchantment/Enchantment";
        byte[] intoEnchantment = mixin("test/EnchantmentMixin", enchantment, "getSlotItems", "(" + L_LIVING + ")Ljava/util/Map;");
        byte[] throughEnchantment = mixin("test/HelperMixin", "net/minecraft/world/item/enchantment/EnchantmentHelper",
                "level", "(L" + enchantment + ";" + L_STACK + "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;)V");
        ClassWriter plain = new ClassWriter(0);
        plain.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/Reads", null, "java/lang/Object", null);
        plain.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "sharpness", "L" + enchantment + ";", null, null).visitEnd();
        plain.visitEnd();

        assertSame(intoEnchantment, LegacyEnchantmentBridge.rename(intoEnchantment), "a mixin into Enchantment keeps its target");
        assertSame(throughEnchantment, LegacyEnchantmentBridge.rename(throughEnchantment),
                "a handler that takes an Enchantment keeps the host's type");
        assertTrue(new String(LegacyEnchantmentBridge.rename(plain.toByteArray()), java.nio.charset.StandardCharsets.ISO_8859_1)
                .contains(LegacyEnchantmentBridge.LEGACY), "other classes of the jar still read the stand-in");
    }

    // ---- fixtures --------------------------------------------------------------------------------

    /** A mixin class into {@code target} with one empty handler of {@code desc}. */
    private static byte[] mixin(String name, String target, String handler, String desc) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, name, null, "java/lang/Object", null);
        var annotation = cw.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false);
        var targets = annotation.visitArray("value");
        targets.visit(null, org.objectweb.asm.Type.getObjectType(target));
        targets.visitEnd();
        annotation.visitEnd();
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PRIVATE, handler, desc, null, null);
        mv.visitCode();
        if (desc.endsWith(")V")) {
            mv.visitInsn(Opcodes.RETURN);
        } else {
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitInsn(Opcodes.ARETURN);
        }
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A 1.20.1 arrow item whose old createArrow records the weapon current while it runs. */
    private static byte[] oldArrowItem() {
        String arrowItem = "net/minecraft/world/item/ArrowItem";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, "test/OldArrowItem", null, arrowItem, null);
        cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "seen", "Ljava/lang/Object;", null, null).visitEnd();
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, arrowItem, "<init>", "()V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "createArrow", "(L" + LEVEL + ";" + L_STACK + L_LIVING + ")L" + ARROW_1205 + ";",
                null, null);
        mv.visitCode();
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, ARROW_MEMBERS, "weapon", "()" + L_STACK, false);
        mv.visitFieldInsn(Opcodes.PUTSTATIC, "test/OldArrowItem", "seen", "Ljava/lang/Object;");
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** The class with every call named {@code from} renamed to {@code to}. */
    private static byte[] renameCalls(byte[] bytes, String from, String to) {
        ClassNode node = node(bytes);
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call && call.name.equals(from)) call.name = to;
            }
        }
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    private RetromodTransformer registered(String host, Shape shape) {
        RetromodVersion.TARGET_MC_VERSION = host;
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        Legacy1205GameplayBridge.registerRedirects(transformer, shape);
        return transformer;
    }

    /** {@code static void run(...)} making the old calls an item, effect and arrow helper of 1.20.1 made. */
    private static byte[] itemCaller() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, "test/Calls", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run",
                "(" + L_STACK + L_STACK + "L" + EFFECT_INSTANCE + ";L" + BLOCK_POS + ";L" + COMPOUND_TAG + ";L" + ARROW_1205 + ";)V",
                null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, ITEM_STACK, "isSameItemSameTags", "(" + L_STACK + L_STACK + ")Z", false);
        mv.visitInsn(Opcodes.POP);
        mv.visitTypeInsn(Opcodes.NEW, EFFECT_PACKET);
        mv.visitInsn(Opcodes.DUP);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, EFFECT_PACKET, "<init>", "(IL" + EFFECT_INSTANCE + ";)V", false);
        mv.visitInsn(Opcodes.POP);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ITEM_STACK, "setHoverName", "(L" + COMPONENT + ";)" + L_STACK, false);
        mv.visitInsn(Opcodes.POP);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitLdcInsn("display");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ITEM_STACK, "removeTagKey", "(Ljava/lang/String;)V", false);
        mv.visitTypeInsn(Opcodes.NEW, AABB);
        mv.visitInsn(Opcodes.DUP);
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, AABB, "<init>", "(L" + BLOCK_POS + ";L" + BLOCK_POS + ";)V", false);
        mv.visitInsn(Opcodes.POP);
        mv.visitVarInsn(Opcodes.ALOAD, 4);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, NBT_UTILS, "readBlockPos", "(L" + COMPOUND_TAG + ";)L" + BLOCK_POS + ";", false);
        mv.visitInsn(Opcodes.POP);
        mv.visitIntInsn(Opcodes.BIPUSH, 'a');
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, SHARED_CONSTANTS, "isAllowedChatCharacter", "(C)Z", false);
        mv.visitInsn(Opcodes.POP);
        mv.visitVarInsn(Opcodes.ALOAD, 5);
        mv.visitInsn(Opcodes.ICONST_2);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ARROW_1205, "setKnockback", "(I)V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A 1.20.1 arrow: the two old constructors and the old abstract pickup method. */
    private static byte[] oldArrow(String arrow) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, "test/OldArrow", null, arrow, null);
        String at = "(L" + ENTITY_TYPE + ";DDDL" + LEVEL + ";)V";
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", at, null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.DLOAD, 2);
        mv.visitVarInsn(Opcodes.DLOAD, 4);
        mv.visitVarInsn(Opcodes.DLOAD, 6);
        mv.visitVarInsn(Opcodes.ALOAD, 8);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, arrow, "<init>", at, false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        String from = "(L" + ENTITY_TYPE + ";" + L_LIVING + "L" + LEVEL + ";)V";
        mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", from, null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, arrow, "<init>", from, false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        mv = cw.visitMethod(Opcodes.ACC_PROTECTED, "getPickupItem", "()" + L_STACK, null, null);
        mv.visitCode();
        mv.visitFieldInsn(Opcodes.GETSTATIC, ITEM_STACK, "EMPTY", L_STACK);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A 1.20.1 effect with tick, attribute and tick-check overrides that log and call their parents. */
    private static byte[] oldEffect() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, "test/OldEffect", null, MOB_EFFECT, null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, MOB_EFFECT, "<init>", "()V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        for (String name : new String[]{"addAttributeModifiers", "removeAttributeModifiers"}) {
            mv = cw.visitMethod(Opcodes.ACC_PUBLIC, name, OLD_ATTRIBUTES, null, null);
            mv.visitCode();
            logWithAmplifier(mv, name.startsWith("add") ? "old add " : "old remove ", 3);
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitVarInsn(Opcodes.ALOAD, 2);
            mv.visitVarInsn(Opcodes.ILOAD, 3);
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, MOB_EFFECT, name, OLD_ATTRIBUTES, false);
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
        mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "isDurationEffectTick", "(II)Z", null, null);
        mv.visitCode();
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "applyEffectTick", "(" + L_LIVING + "I)V", null, null);
        mv.visitCode();
        logWithAmplifier(mv, "old tick ", 2);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ILOAD, 2);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, MOB_EFFECT, "applyEffectTick", "(" + L_LIVING + "I)V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A mod effect built on {@code test/OldEffect}, whose own old addition calls its mod parent's. */
    private static byte[] effectSubclass() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, "test/StrongerEffect", null, "test/OldEffect", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "addAttributeModifiers", OLD_ATTRIBUTES, null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitVarInsn(Opcodes.ILOAD, 3);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "test/OldEffect", "addAttributeModifiers", OLD_ATTRIBUTES, false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A 1.20.1 monster whose death loot override calls its parent's. */
    private static byte[] oldMob() {
        String monster = "net/minecraft/world/entity/monster/Monster";
        String old = "(L" + DAMAGE_SOURCE + ";IZ)V";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, "test/OldMob", null, monster, null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PROTECTED, "dropCustomDeathLoot", old, null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ILOAD, 2);
        mv.visitVarInsn(Opcodes.ILOAD, 3);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, monster, "dropCustomDeathLoot", old, false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        // The adapter only bridges subclasses of LivingEntity, which a monster is.
        return cw.toByteArray();
    }

    /** Appends {@code prefix + amplifier} to the host's call log, reading the amplifier from {@code slot}. */
    private static void logWithAmplifier(MethodVisitor mv, String prefix, int slot) {
        mv.visitFieldInsn(Opcodes.GETSTATIC, MOB_EFFECT, "calls", "Ljava/util/List;");
        mv.visitLdcInsn(prefix);
        mv.visitVarInsn(Opcodes.ILOAD, slot);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/String", "valueOf", "(I)Ljava/lang/String;", false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/List", "add", "(Ljava/lang/Object;)Z", true);
        mv.visitInsn(Opcodes.POP);
    }

    private static boolean readsArrowItem(ClassNode node) {
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof FieldInsnNode field && field.owner.equals(ITEMS) && field.name.equals("ARROW")) return true;
            }
        }
        return false;
    }

    private static List<String> calls(ClassNode node, String methodName) {
        List<String> calls = new ArrayList<>();
        for (MethodNode method : node.methods) {
            if (method.name.equals(methodName)) calls.addAll(calls(method));
        }
        return calls;
    }

    private static List<String> calls(MethodNode method) {
        List<String> calls = new ArrayList<>();
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call) calls.add(call.owner + "." + call.name + call.desc);
        }
        return calls;
    }

    private static MethodNode method(ClassNode node, String name) {
        return node.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElse(null);
    }

    private static ClassNode node(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    /** Small stand-ins for the host classes a bridged class runs against, defined in a class loader of their own. */
    static final class HostClasses extends ClassLoader {

        private final Map<String, byte[]> classes = new HashMap<>();

        private HostClasses() {
            super(Legacy1205GameplayBridgeTest.class.getClassLoader());
        }

        /** The effect hierarchy, with the 1.21.1 tick or the 26.3 tick that takes the server level. */
        static HostClasses effects(boolean levelForm) {
            HostClasses host = new HostClasses();
            host.plain(ATTRIBUTE_MAP, "java/lang/Object");
            host.plain(LEVEL, "java/lang/Object");
            host.plain(SERVER_LEVEL, LEVEL);
            host.classes.put(ENTITY, entityOnServer());
            host.classes.put(LIVING, host.living());
            host.classes.put(MOB_EFFECT, host.mobEffect(levelForm));
            return host;
        }

        /** The arrow hierarchy: the abstract arrow asks its subclass for a default pickup stack. */
        static HostClasses arrows() {
            HostClasses host = new HostClasses();
            for (String name : new String[]{ENTITY_TYPE, LEVEL, ITEM_STACK, DAMAGE_SOURCE, ENTITY}) host.plain(name, "java/lang/Object");
            // The pickup stack is built from an item, which the verifier checks is an ItemLike.
            ClassWriter itemLike = new ClassWriter(0);
            itemLike.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT, ITEM_LIKE, null,
                    "java/lang/Object", null);
            itemLike.visitEnd();
            host.classes.put(ITEM_LIKE, itemLike.toByteArray());
            ClassWriter item = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            item.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, ITEM, null, "java/lang/Object", new String[]{ITEM_LIKE});
            constructor(item, "java/lang/Object", "()V");
            item.visitEnd();
            host.classes.put(ITEM, item.toByteArray());
            host.classes.put(LIVING, host.living());
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, ARROW_1205, null, ENTITY, null);
            constructor(cw, ENTITY, "()V");
            abstractMethod(cw, "getDefaultPickupItem", "()" + L_STACK);
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PROTECTED, "doKnockback", "(" + L_LIVING + "L" + DAMAGE_SOURCE + ";)V", null, null);
            mv.visitCode();
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
            cw.visitEnd();
            host.classes.put(ARROW_1205, cw.toByteArray());
            return host;
        }

        /** Adds ArrowItem, a stack that is never empty, and the rebuilt arrow members, so the weapon they keep is this host's stack. */
        void withArrowItem() throws Exception {
            plain("net/minecraft/world/item/ArrowItem", "java/lang/Object");
            ClassWriter stack = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            stack.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, ITEM_STACK, null, "java/lang/Object", null);
            constructor(stack, "java/lang/Object", "()V");
            MethodVisitor empty = stack.visitMethod(Opcodes.ACC_PUBLIC, "isEmpty", "()Z", null, null);
            empty.visitCode();
            empty.visitInsn(Opcodes.ICONST_0);
            empty.visitInsn(Opcodes.IRETURN);
            empty.visitMaxs(0, 0);
            empty.visitEnd();
            stack.visitEnd();
            classes.put(ITEM_STACK, stack.toByteArray());
            try (java.io.InputStream in = Legacy1205GameplayBridgeTest.class.getClassLoader()
                    .getResourceAsStream(ARROW_MEMBERS + ".class")) {
                classes.put(ARROW_MEMBERS, in.readAllBytes());
            }
        }

        Object newEntity() throws Exception {
            return loadClass(LIVING.replace('/', '.')).getConstructor().newInstance();
        }

        Object newLevel() throws Exception {
            return loadClass(SERVER_LEVEL.replace('/', '.')).getConstructor().newInstance();
        }

        Class<?> define(String name, byte[] bytes) throws ClassNotFoundException {
            classes.put(name, bytes);
            return loadClass(name.replace('/', '.'));
        }

        /** Calls the public method {@code name} whose parameters take {@code args}, as the host would: its form returns a value. */
        Object call(Object target, String name, Object... args) throws Exception {
            java.lang.reflect.Method chosen = null;
            for (java.lang.reflect.Method method : target.getClass().getMethods()) {
                if (!method.getName().equals(name) || method.getParameterCount() != args.length) continue;
                Class<?>[] types = method.getParameterTypes();
                boolean fits = true;
                for (int i = 0; i < args.length; i++) {
                    fits &= types[i].isPrimitive() ? args[i] instanceof Integer && types[i] == int.class
                            : types[i].isInstance(args[i]);
                }
                // An old void form and the host's boolean form can take the same arguments; the host calls its own.
                if (fits && (chosen == null || chosen.getReturnType() == void.class)) chosen = method;
            }
            if (chosen == null) throw new AssertionError("no " + name + " on " + target.getClass().getName() + " for " + List.of(args));
            return chosen.invoke(target, args);
        }

        @SuppressWarnings("unchecked")
        List<String> drain() throws Exception {
            List<String> calls = (List<String>) loadClass(MOB_EFFECT.replace('/', '.')).getField("calls").get(null);
            List<String> copy = List.copyOf(calls);
            calls.clear();
            return copy;
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            byte[] bytes = classes.get(name.replace('.', '/'));
            if (bytes == null) throw new ClassNotFoundException(name);
            return defineClass(name, bytes, 0, bytes.length);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            // Host and test classes come from here first, so the parent never answers a Minecraft name.
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null && classes.containsKey(name.replace('.', '/'))) loaded = findClass(name);
                if (loaded == null) loaded = super.loadClass(name, false);
                if (resolve) resolveClass(loaded);
                return loaded;
            }
        }

        private void plain(String name, String parent) {
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, parent, null);
            constructor(cw, parent, "()V");
            cw.visitEnd();
            classes.put(name, cw.toByteArray());
        }

        /** An entity whose level is a server level. */
        private static byte[] entityOnServer() {
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, ENTITY, null, "java/lang/Object", null);
            constructor(cw, "java/lang/Object", "()V");
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "level", "()L" + LEVEL + ";", null, null);
            mv.visitCode();
            mv.visitTypeInsn(Opcodes.NEW, SERVER_LEVEL);
            mv.visitInsn(Opcodes.DUP);
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, SERVER_LEVEL, "<init>", "()V", false);
            mv.visitInsn(Opcodes.ARETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
            cw.visitEnd();
            return cw.toByteArray();
        }

        /** A living entity that owns one attribute map. */
        private byte[] living() {
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, LIVING, null, ENTITY, null);
            cw.visitField(Opcodes.ACC_PRIVATE, "attributes", L_MAP, null, null).visitEnd();
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
            mv.visitCode();
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, ENTITY, "<init>", "()V", false);
            if (classes.containsKey(ATTRIBUTE_MAP)) {
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitTypeInsn(Opcodes.NEW, ATTRIBUTE_MAP);
                mv.visitInsn(Opcodes.DUP);
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, ATTRIBUTE_MAP, "<init>", "()V", false);
                mv.visitFieldInsn(Opcodes.PUTFIELD, LIVING, "attributes", L_MAP);
            }
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
            mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "getAttributes", "()" + L_MAP, null, null);
            mv.visitCode();
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitFieldInsn(Opcodes.GETFIELD, LIVING, "attributes", L_MAP);
            mv.visitInsn(Opcodes.ARETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
            cw.visitEnd();
            return cw.toByteArray();
        }

        /** MobEffect with the host forms, each of which logs what ran. */
        private byte[] mobEffect(boolean levelForm) {
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, MOB_EFFECT, null, "java/lang/Object", null);
            cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "calls", "Ljava/util/List;", null, null).visitEnd();
            MethodVisitor clinit = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
            clinit.visitCode();
            clinit.visitTypeInsn(Opcodes.NEW, "java/util/ArrayList");
            clinit.visitInsn(Opcodes.DUP);
            clinit.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/ArrayList", "<init>", "()V", false);
            clinit.visitFieldInsn(Opcodes.PUTSTATIC, MOB_EFFECT, "calls", "Ljava/util/List;");
            clinit.visitInsn(Opcodes.RETURN);
            clinit.visitMaxs(0, 0);
            clinit.visitEnd();
            constructor(cw, "java/lang/Object", "()V");
            logging(cw, "addAttributeModifiers", "(" + L_MAP + "I)V", "host add ", 2);
            logging(cw, "removeAttributeModifiers", "(" + L_MAP + ")V", "host remove", -1);
            logging(cw, "onEffectAdded", "(" + L_LIVING + "I)V", "host added", -1);
            logging(cw, "onEffectStarted", "(" + L_LIVING + "I)V", null, -1);
            logging(cw, "shouldApplyEffectTickThisTick", "(II)Z", null, -1);
            logging(cw, "applyEffectTick", levelForm ? "(L" + SERVER_LEVEL + ";" + L_LIVING + "I)Z" : "(" + L_LIVING + "I)Z",
                    "host tick", -1);
            cw.visitEnd();
            return cw.toByteArray();
        }

        /** A method that logs {@code message}, with the int in {@code amplifierSlot} when there is one, and returns false. */
        private static void logging(ClassWriter cw, String name, String desc, String message, int amplifierSlot) {
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, name, desc, null, null);
            mv.visitCode();
            if (message != null) {
                mv.visitFieldInsn(Opcodes.GETSTATIC, MOB_EFFECT, "calls", "Ljava/util/List;");
                mv.visitLdcInsn(message);
                if (amplifierSlot > 0) {
                    mv.visitVarInsn(Opcodes.ILOAD, amplifierSlot);
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/String", "valueOf", "(I)Ljava/lang/String;", false);
                    mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat",
                            "(Ljava/lang/String;)Ljava/lang/String;", false);
                }
                mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/List", "add", "(Ljava/lang/Object;)Z", true);
                mv.visitInsn(Opcodes.POP);
            }
            if (desc.endsWith("Z")) {
                mv.visitInsn(Opcodes.ICONST_0);
                mv.visitInsn(Opcodes.IRETURN);
            } else {
                mv.visitInsn(Opcodes.RETURN);
            }
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }

        private static void constructor(ClassWriter cw, String parent, String desc) {
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", desc, null, null);
            mv.visitCode();
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, parent, "<init>", "()V", false);
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }

        private static void abstractMethod(ClassWriter cw, String name, String desc) {
            cw.visitMethod(Opcodes.ACC_PROTECTED | Opcodes.ACC_ABSTRACT, name, desc, null, null).visitEnd();
        }
    }
}
