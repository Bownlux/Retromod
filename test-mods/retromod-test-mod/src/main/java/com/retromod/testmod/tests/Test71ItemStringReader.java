/*
 * Retromod Test Mod
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.tests;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.retromod.testmod.Test;
import com.retromod.testmod.TestResult;
import net.minecraft.command.argument.ItemStringReader;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;

/** Carry On and FancyMenu read item strings this way; 1.20.5 removed the reader's item call and its result. */
public class Test71ItemStringReader implements Test {

    @Override
    public String description() {
        return "ItemStringReader.item reads an item id and the NBT after it";
    }

    @Override
    public TestResult run() {
        try {
            ItemStringReader.ItemResult result = ItemStringReader.item(Registries.ITEM.getReadOnlyWrapper(),
                    new StringReader("minecraft:diamond{Damage:3}"));
            if (result.item().value() != Items.DIAMOND) return TestResult.fail("read " + result.item());
            return String.valueOf(result.nbt()).contains("Damage:3")
                    ? TestResult.success() : TestResult.fail("the NBT read as " + result.nbt());
        } catch (CommandSyntaxException e) {
            return TestResult.fail(e.getMessage());
        }
    }
}
