// Compile-only stub of a 26.1 to 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.client.gui.screens.inventory;

import net.minecraft.client.gui.screens.Screen;

public abstract class AbstractContainerScreen<T> extends Screen {
    protected int leftPos;
    protected int topPos;
    private AbstractContainerScreen() { super(null); }
}
