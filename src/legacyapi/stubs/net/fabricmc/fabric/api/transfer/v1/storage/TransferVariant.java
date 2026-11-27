// Compile-only stub of a Fabric API 26.1 to 26.3 host type for src/legacyapi; never packaged.
package net.fabricmc.fabric.api.transfer.v1.storage;

import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponentPatch;

public interface TransferVariant<O> {
    DataComponentPatch getComponentsPatch();
    DataComponentMap getComponents();
}
