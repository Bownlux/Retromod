/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.embedded;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/** Runtime half of the 1.12.2 bridge, exercised without a loader on the classpath. */
class LegacyForgeEventsTest {

    /** A 1.12 pre-init event stand-in: built with the mod id, as the bridge does. */
    public static class PreInit {
        final String modid;

        public PreInit(String modid) {
            this.modid = modid;
        }
    }

    @LegacyHandlers({
            "L|i|preInit|(Lcom/retromod/shim/forge/embedded/LegacyForgeEventsTest$PreInit;)V||0",
            // A handler whose event class is gone: it must not stop the lifecycle handler above.
            "E|s|onMissing|(Lnet/minecraftforge/fml/client/event/ConfigChangedEvent;)V||0"
    })
    public static class LegacyMod {
        final List<String> seen = new ArrayList<>();

        public void preInit(PreInit event) {
            seen.add("pre:" + event.modid);
        }
    }

    @Test
    void lifecycleHandlersRunFromTheRecordedTable() {
        LegacyMod mod = new LegacyMod();

        LegacyForgeEvents.fireLifecycle(mod, "oldmod", PreInit.class);

        assertEquals(List.of("pre:oldmod"), mod.seen,
                "pre-init must run even when the class names a removed event elsewhere");
    }

    @Test
    void preInitWaitsForTheFirstRegistryEventAndRunsOnce() {
        LegacyMod mod = new LegacyMod();
        LegacyForgeEvents.registryDispatcherInstalled = true;
        try {
            assertTrue(LegacyForgeEvents.deferToFirstRegistryEvent(mod, "oldmod", PreInit.class));
            assertEquals(List.of(), mod.seen,
                    "pre-init must not run at construction, when the host registries are frozen");

            LegacyForgeEvents.dispatchRegistry(new Object());
            LegacyForgeEvents.dispatchRegistry(new Object());

            assertEquals(List.of("pre:oldmod"), mod.seen,
                    "pre-init runs once, at the start of the first registry event");
        } finally {
            LegacyForgeEvents.registryDispatcherInstalled = false;
        }
    }

    @Test
    void preInitIsNotDeferredWithoutARegistryDispatcher() {
        assertFalse(LegacyForgeEvents.deferToFirstRegistryEvent(new LegacyMod(), "oldmod", PreInit.class),
                "without a host registry event the caller must run pre-init another way");
    }

    /** The host's modifier operations, in the order 1.12 numbered them. */
    public enum Operation { ADD_VALUE, ADD_MULTIPLIED_BASE, ADD_MULTIPLIED_TOTAL }

    /** A host effect with Forge 1.20.1's {@code addAttributeModifier(attribute, uuid, amount, op)}. */
    public static class Effect {
        final List<Object> modifiers = new ArrayList<>();

        public Effect addAttributeModifier(Object attribute, String uuid, double amount, Operation op) {
            modifiers.add(List.of(attribute, uuid, amount, op));
            return this;
        }
    }

    @Test
    void potionAttributeModifiersReachTheHostEffect() {
        Effect effect = new Effect();

        Object result = LegacyForgeEvents.effectAttribute(effect, "speed",
                "7BB42B36-75AC-448D-9BE2-B318E42D6898", 0.06, 2);

        assertSame(effect, result, "the 1.12 call returned the potion for chaining");
        assertEquals(List.of(List.of("speed", "7BB42B36-75AC-448D-9BE2-B318E42D6898", 0.06,
                        Operation.ADD_MULTIPLIED_TOTAL)), effect.modifiers,
                "operation 2 (multiply total) must keep its meaning");
    }

    /** A host {@code StateDefinition.Builder}: {@code add(Property...)} returns the builder. */
    public static class Builder {
        final List<Object> added = new ArrayList<>();

        public Builder add(CharSequence... properties) {
            added.addAll(List.of(properties));
            return this;
        }
    }

    @Test
    void stateContainerPropertiesGoToTheOpenBuilder() {
        Builder builder = new Builder();

        Object previous = LegacyForgeEvents.beginLegacyStates(builder);
        assertNull(LegacyForgeEvents.addLegacyStates(new Object(), new Object[]{"powered", "facing"}),
                "the host builds the definition itself");
        LegacyForgeEvents.endLegacyStates(previous);

        assertEquals(List.of("powered", "facing"), builder.added);
        assertNull(LegacyForgeEvents.addLegacyStates(new Object(), new Object[]{"late"}),
                "a container built outside block construction has no builder to fill");
        assertEquals(2, builder.added.size());
    }

    @Test
    void propertiesWithoutABlockIdPassThroughOnOlderHosts() {
        Object properties = new Object();
        assertSame(properties, LegacyForgeEvents.withProvisionalBlockId(properties),
                "hosts before 1.21.2 have no setId and keep the properties as built");
    }

    /** The field table entries a transformed {@code @Mod} class carries. */
    @LegacyHandlers({
            "I|s|instance|Lcom/retromod/shim/forge/embedded/LegacyForgeEventsTest$ModWithInstance;|withinstance|0",
            "I|s|other|Lcom/retromod/shim/forge/embedded/LegacyForgeEventsTest$ModWithInstance;|othermod|0"
    })
    public static class ModWithInstance {
        static ModWithInstance instance;
        static ModWithInstance other;
    }

    @Test
    void modInstanceFieldIsFilledOnlyForItsOwnModId() {
        ModWithInstance mod = new ModWithInstance();

        LegacyForgeEvents.injectModInstances(ModWithInstance.class, mod, "withinstance");

        assertSame(mod, ModWithInstance.instance, "@Mod.Instance handlers would NPE without this");
        assertNull(ModWithInstance.other, "@Mod.Instance(\"othermod\") names another mod's instance");
    }

    @Test
    void subscribingAClassWithAMissingEventTypeDoesNotThrow() {
        assertDoesNotThrow(() -> LegacyForgeEvents.subscribe(LegacyMod.class));
    }

    @Test
    void registryNamesRoundTripThroughTheSideTable() {
        LegacyForgeEvents.modId = "oldmod";
        Object block = new Object();

        assertSame(block, LegacyForgeEvents.setRegistryName(block, "Ruby_Block"),
                "setRegistryName returns its receiver, as in 1.12");
        assertEquals("oldmod:ruby_block", String.valueOf(LegacyForgeEvents.getRegistryName(block)),
                "an unqualified 1.12 name takes the mod id and is lower-cased");
    }

    @Test
    void registerOnTheBridgedRegistryReachesTheRegistrar() {
        List<Object> registered = new ArrayList<>();
        Consumer<Object> registrar = registered::add;
        Object a = new Object();
        Object b = new Object();

        LegacyForgeEvents.registryRegister(registrar, a);
        LegacyForgeEvents.registryRegisterAll(registrar, new Object[]{b});

        assertEquals(List.of(a, b), registered);
    }

    @Test
    void registryKeyTextGivesTheRegistryPath() {
        assertEquals("block", LegacyForgeEvents.registryPathOfKey("ResourceKey[minecraft:root / minecraft:block]"));
        assertEquals("othermod:widget",
                LegacyForgeEvents.registryPathOfKey("ResourceKey[minecraft:root / othermod:widget]"));
    }
}
