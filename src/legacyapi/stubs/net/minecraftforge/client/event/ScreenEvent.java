// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package net.minecraftforge.client.event;

import net.minecraft.client.input.KeyEvent;

public interface ScreenEvent {
    interface KeyInput extends ScreenEvent {
        KeyEvent getInfo();
    }
}
