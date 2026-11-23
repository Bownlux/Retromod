/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The rebuilt state builder skips only the property the vanilla base already added, run against a small stand-in builder. */
class LegacyStateDefinitionsRuntimeTest {

    private static final Path COMPILED = Path.of("target", "legacyapi-classes");
    private static final String HELPER = "com/retromod/legacyapi/minecraft/world/level/block/state/LegacyStateDefinitions";

    @Test
    @DisplayName("The base's own WATERLOGGED is skipped, while a mod's own BooleanProperty named waterlogged still throws")
    void onlyTheSameInstanceIsSkipped(@TempDir Path dir) throws Exception {
        ClassLoader game = game(dir);
        Class<?> builderType = game.loadClass("net.minecraft.world.level.block.state.StateDefinition$Builder");
        Class<?> propertyType = game.loadClass("net.minecraft.world.level.block.state.properties.Property");
        Class<?> booleanType = game.loadClass("net.minecraft.world.level.block.state.properties.BooleanProperty");
        Method create = booleanType.getMethod("create", String.class);
        Object waterlogged = create.invoke(null, "waterlogged");
        Object builder = builderType.getConstructor().newInstance();
        Method hostAdd = builderType.getMethod("add", propertyType.arrayType());
        hostAdd.invoke(builder, properties(propertyType, waterlogged));

        Method add = game.loadClass(HELPER.replace('/', '.')).getMethod("add", builderType, propertyType.arrayType());
        assertSame(builder, add.invoke(null, builder, properties(propertyType, waterlogged)),
                "the property the base class already added is accepted again");

        Object own = create.invoke(null, "waterlogged");
        InvocationTargetException thrown = assertThrows(InvocationTargetException.class,
                () -> add.invoke(null, builder, properties(propertyType, own)));
        assertInstanceOf(IllegalArgumentException.class, thrown.getCause(), "a different property of the same name still fails");
    }

    private static Object properties(Class<?> propertyType, Object... values) {
        Object array = Array.newInstance(propertyType, values.length);
        for (int i = 0; i < values.length; i++) Array.set(array, i, values[i]);
        return array;
    }

    /** Stand-ins for the host builder and properties, with the rebuilt helper as the mod would carry it. */
    private static ClassLoader game(Path dir) throws Exception {
        Map<String, byte[]> classes = new HashMap<>(BridgeHarness.compile(dir, Map.of(
                "net/minecraft/world/level/block/state/properties/Property.java", """
                        package net.minecraft.world.level.block.state.properties;
                        public abstract class Property<T extends Comparable<T>> {
                            private final String name;
                            protected Property(String name) { this.name = name; }
                            public String getName() { return name; }
                            public abstract String getName(T value);
                        }""",
                "net/minecraft/world/level/block/state/properties/BooleanProperty.java", """
                        package net.minecraft.world.level.block.state.properties;
                        public final class BooleanProperty extends Property<Boolean> {
                            private BooleanProperty(String name) { super(name); }
                            public static BooleanProperty create(String name) { return new BooleanProperty(name); }
                            public String getName(Boolean value) { return value.toString(); }
                        }""",
                "net/minecraft/world/level/block/state/StateDefinition.java", """
                        package net.minecraft.world.level.block.state;
                        import java.util.HashMap;
                        import java.util.Map;
                        import net.minecraft.world.level.block.state.properties.Property;
                        public class StateDefinition<O, S> {
                            public static class Builder<O, S> {
                                private final Object owner = "block";
                                private final Map<String, Property<?>> properties = new HashMap<>();
                                public Builder() {}
                                public Builder<O, S> add(Property<?>... added) {
                                    for (Property<?> property : added) {
                                        if (properties.containsKey(property.getName())) {
                                            throw new IllegalArgumentException(owner + " has duplicate property: " + property.getName());
                                        }
                                        properties.put(property.getName(), property);
                                    }
                                    return this;
                                }
                            }
                        }""")));
        classes.put(HELPER, Files.readAllBytes(COMPILED.resolve(HELPER + ".class")));
        return new ClassLoader(ClassLoader.getPlatformClassLoader()) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] bytes = classes.get(name.replace('.', '/'));
                if (bytes == null) throw new ClassNotFoundException(name);
                return defineClass(name, bytes, 0, bytes.length);
            }
        };
    }
}
