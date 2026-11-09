// Compile-only stub of a Forge 26.3 host type for src/legacyapi; never packaged.
package net.minecraftforge.client.event;

import net.minecraft.client.input.KeyEvent;

public interface InputEvent {
    // A record on the host.
    final class Key implements InputEvent {
        public KeyEvent getInfo() { throw new UnsupportedOperationException(); }
    }
}
