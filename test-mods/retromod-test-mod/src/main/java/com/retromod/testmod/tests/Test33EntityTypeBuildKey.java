/*
 * Retromod Test Mod
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.tests;

import com.retromod.testmod.Test;
import com.retromod.testmod.TestResult;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.MarkerEntity;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;

/**
 * 1.21.2 builds an entity type's translation key and loot table from the key passed to
 * {@code EntityType.Builder.build}, where 1.20.1 took them from the registered id and used the
 * String only for the datafixer. Built from a bare name, an old mod's mob showed
 * {@code entity.minecraft.<name>} and dropped nothing. One type is registered straight under a
 * literal id with a bare name, the other through a helper whose build name is not its id.
 */
public final class Test33EntityTypeBuildKey implements Test {

    private static final String MOD_ID = "retromod_test_mod";

    @Override
    public String description() {
        return "entity types built with an old name take the id they are registered under";
    }

    @Override
    public TestResult run() {
        EntityType<MarkerEntity> direct = Registry.register(Registries.ENTITY_TYPE,
                new Identifier(MOD_ID, "key_direct"),
                EntityType.Builder.create(MarkerEntity::new, SpawnGroup.MISC).build("key_direct"));
        EntityType<MarkerEntity> renamed = register("key_renamed",
                EntityType.Builder.create(MarkerEntity::new, SpawnGroup.MISC));

        String directKey = direct.getTranslationKey();
        if (!directKey.equals("entity." + MOD_ID + ".key_direct")) {
            return TestResult.fail("the bare name was built under another id: " + directKey);
        }
        String renamedKey = renamed.getTranslationKey();
        return renamedKey.equals("entity." + MOD_ID + ".key_renamed")
                ? TestResult.success()
                : TestResult.fail("the type was built under its old name, not its id: " + renamedKey);
    }

    /** The common helper shape: the id is built from a parameter, the old name differs from it. */
    private static <T extends Entity> EntityType<T> register(String name, EntityType.Builder<T> builder) {
        return Registry.register(Registries.ENTITY_TYPE, new Identifier(MOD_ID, name),
                builder.build("old_" + name));
    }
}
