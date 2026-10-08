/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.fabric;

import com.retromod.shim.fabric.embedded.LegacyAttributeKeys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Porting Lib adds its attributes to {@code createLivingAttributes()} and registers them in its
 * initializer. When another mod builds the entity defaults first, the defaults hold direct
 * holders and every later lookup by registered holder misses, which crashed the server on the
 * first mob tick once Porting Lib's {@code LivingEntityMixin} applied. These cases cover the
 * repair that moves such an entry to the registered holder on the first lookup.
 */
class Pre1_20_5AttributeKeyRepairTest {

    private static final String STUBS = "com/retromod/shim/fabric/attrstub/";
    private static final Map<String, String> HOST_NAMES =
            Map.of(STUBS + "AttributeSupplier", "net/minecraft/class_5132");

    @Test
    @DisplayName("A default added before registration is found by the registered holder")
    void defaultAddedBeforeRegistrationIsFound() throws Exception {
        World world = new World();
        Object stepHeight = new Object();
        Object template = "step height defaults";
        Object entity = world.entity(Map.of(world.direct(stepHeight), template));
        Object registered = world.reference(stepHeight);
        assertNull(world.lookup(entity, registered), "the stale key is what the mod's lookup misses");

        world.repairEntity(entity, registered);

        assertSame(template, world.lookup(entity, registered));
        assertNull(world.lookup(entity, world.direct(stepHeight)),
                "the entry moves to the registered holder, so instances save with the attribute's id");
    }

    @Test
    @DisplayName("Defaults that already use the registered holder are left as they are")
    void registeredDefaultsAreLeftAlone() throws Exception {
        World world = new World();
        Object armor = new Object();
        Object registered = world.reference(armor);
        Object entity = world.entity(Map.of(registered, "armor defaults"));
        Object before = world.instancesOf(entity);

        world.repairEntity(entity, registered);

        assertSame(before, world.instancesOf(entity), "a lookup that already succeeds must not copy the map");
    }

    @Test
    @DisplayName("An entry for a different attribute is never moved")
    void otherAttributesAreNotMoved() throws Exception {
        World world = new World();
        Object gravity = new Object();
        Object entity = world.entity(Map.of(world.direct(gravity), "gravity defaults"));
        Object unrelated = world.reference(new Object());

        world.repairEntity(entity, unrelated);

        assertNull(world.lookup(entity, unrelated));
        assertEquals("gravity defaults", world.lookup(entity, world.direct(gravity)));
    }

    @Test
    @DisplayName("Attribute lookups repair the defaults first, attribute builders do not")
    void lookupAdaptersCallTheRepair() {
        ClassNode helper = new ClassNode();
        new ClassReader(Pre1_20_5RegistryHolderBridge.generateHelper(
                Pre1_20_5RegistryHolderBridge.OVERLOADS, List.of())).accept(helper, 0);

        assertEquals("repairEntity", repairCalledBy(helper, "class_1309$method_5996"),
                "getAttribute(Attribute) is how Porting Lib reads its own attributes");
        assertEquals("repairMap", repairCalledBy(helper, "class_5131$method_26842"));
        assertNull(repairCalledBy(helper, "class_5132$class_5133$method_26867"),
                "building defaults is what creates the entry; there is nothing to repair yet");
    }

    // ---- fixtures ------------------------------------------------------------------------

    /** Builds stand-in entities inside a loader where the supplier carries its host name. */
    private static final class World {
        private final HostRenamingLoader loader =
                new HostRenamingLoader(STUBS, HOST_NAMES, LegacyAttributeKeys.class);

        Object direct(Object attribute) throws Exception {
            return create("DirectHolder", Object.class, attribute);
        }

        Object reference(Object attribute) throws Exception {
            return create("ReferenceHolder", Object.class, attribute);
        }

        Object entity(Map<Object, Object> defaults) throws Exception {
            Object supplier = loader.loadClass("net.minecraft.class_5132")
                    .getConstructor(Map.class).newInstance(new LinkedHashMap<>(defaults));
            Class<?> supplierType = supplier.getClass();
            Object map = create("AttributeMap", supplierType, supplier);
            return create("LivingEntity", map.getClass(), map);
        }

        Object lookup(Object entity, Object holder) throws Exception {
            Object map = entity.getClass().getMethod("method_6127").invoke(entity);
            return map.getClass().getMethod("getInstance", Object.class).invoke(map, holder);
        }

        Object instancesOf(Object entity) throws Exception {
            Object map = entity.getClass().getMethod("method_6127").invoke(entity);
            java.lang.reflect.Field supplierField = map.getClass().getDeclaredField("supplier");
            supplierField.setAccessible(true);
            Object supplier = supplierField.get(map);
            java.lang.reflect.Field instances = supplier.getClass().getDeclaredField("instances");
            instances.setAccessible(true);
            return instances.get(supplier);
        }

        void repairEntity(Object entity, Object holder) throws Exception {
            loader.loadClass(LegacyAttributeKeys.class.getName())
                    .getMethod("repairEntity", Object.class, Object.class).invoke(null, entity, holder);
        }

        private Object create(String simpleName, Class<?> parameter, Object argument) throws Exception {
            String name = (STUBS + simpleName).replace('/', '.');
            return loader.loadClass(name).getConstructor(parameter).newInstance(argument);
        }
    }

    private static String repairCalledBy(ClassNode helper, String adapterName) {
        MethodNode adapter = helper.methods.stream().filter(m -> m.name.equals(adapterName)).findFirst()
                .orElseThrow(() -> new AssertionError("no adapter " + adapterName));
        List<MethodInsnNode> calls = new ArrayList<>();
        for (AbstractInsnNode instruction : adapter.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && call.owner.equals(Pre1_20_5RegistryHolderBridge.ATTRIBUTE_KEYS)) {
                calls.add(call);
            }
        }
        return calls.isEmpty() ? null : calls.get(0).name;
    }
}
