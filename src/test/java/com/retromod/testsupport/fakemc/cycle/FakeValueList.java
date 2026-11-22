/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testsupport.fakemc.cycle;

import java.util.List;

/** Stands in for {@code CycleButton.ValueListSupplier}. */
public interface FakeValueList<T> {
    List<T> getDefaultList();
}
