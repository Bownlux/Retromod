/*
 * Retromod Test Mod
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.tests;

import com.retromod.testmod.Test;
import com.retromod.testmod.TestResult;
import com.retromod.testmod.tests.crossclass.CrossClassBlocks;
import com.retromod.testmod.tests.crossclass.CrossClassItems;
import com.retromod.testmod.tests.crossclass.CrossClassRegistrar;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

/**
 * Longwings 0.9.5 on Fabric 26.3: blocks built into static fields of one class and registered by
 * name from another died with {@code Block id not set} when their class initialized. Registering
 * here builds both fields; reaching the checks at all means each was built with an id, and the
 * block's translation key shows it was the block's own id rather than the item's.
 */
public final class Test32CrossClassRegistryId implements Test {

    @Override
    public String description() {
        return "blocks and items built in one class and registered by another get their ids";
    }

    @Override
    public TestResult run() {
        try {
            CrossClassRegistrar.register();
        } catch (ExceptionInInitializerError e) {
            return TestResult.fail("a field's class failed to initialize: " + e.getCause());
        }
        Identifier block = Registries.BLOCK.getId(CrossClassBlocks.CROSS_CLASS_BLOCK);
        Identifier item = Registries.ITEM.getId(CrossClassItems.CROSS_CLASS_ITEM);
        if (!new Identifier("retromod_test_mod", "cross_class_block").equals(block)) {
            return TestResult.fail("the block registered as " + block);
        }
        if (!new Identifier("retromod_test_mod", "cross_class_item").equals(item)) {
            return TestResult.fail("the item registered as " + item);
        }
        String key = CrossClassBlocks.CROSS_CLASS_BLOCK.getTranslationKey();
        return key.equals("block.retromod_test_mod.cross_class_block")
                ? TestResult.success()
                : TestResult.fail("the block was built under another id: " + key);
    }
}
