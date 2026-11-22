/*
 * Retromod Test Mod
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.tests;

import com.retromod.testmod.Test;
import com.retromod.testmod.TestResult;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;

/** A mod's own KeyBinding subclass whose super(...) passes the old String category (#425). */
public final class Test89KeyBindingSubclass implements Test {

    @Override
    public String description() {
        return "KeyBinding subclass with a String category";
    }

    @Override
    public TestResult run() {
        // 1.21.9 replaced the String category with a Category record, so an unrepaired super(...)
        // throws NoSuchMethodError here, as Shifty Hotbar's InvokableKeyBinding did.
        try {
            OwnKeyBinding typed = new OwnKeyBinding("key.retromod_test.test89_typed", InputUtil.Type.KEYSYM, 75);
            OwnKeyBinding plain = new OwnKeyBinding("key.retromod_test.test89_plain", 76);
            if (!"key.retromod_test.test89_typed".equals(typed.getTranslationKey())) {
                return TestResult.fail("the typed subclass kept " + typed.getTranslationKey());
            }
            if (!"key.retromod_test.test89_plain".equals(plain.getTranslationKey())) {
                return TestResult.fail("the plain subclass kept " + plain.getTranslationKey());
            }
            return TestResult.success();
        } catch (Throwable t) {
            return TestResult.fail(t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    /** Calls both old String-category super constructors. */
    private static final class OwnKeyBinding extends KeyBinding {
        OwnKeyBinding(String name, InputUtil.Type type, int code) {
            super(name, type, code, "key.categories.misc");
        }

        OwnKeyBinding(String name, int code) {
            super(name, code, "key.categories.misc");
        }
    }
}
