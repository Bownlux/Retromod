// Compile-only stub of a 26.1 to 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.network.chat;

import net.minecraft.world.item.ItemStackTemplate;

public interface HoverEvent {
    Action action();

    enum Action { SHOW_TEXT, SHOW_ITEM, SHOW_ENTITY }

    // Records on the host.
    final class ShowText implements HoverEvent {
        public Component value() { throw new UnsupportedOperationException(); }
        public Action action() { throw new UnsupportedOperationException(); }
    }

    final class ShowItem implements HoverEvent {
        public ItemStackTemplate item() { throw new UnsupportedOperationException(); }
        public Action action() { throw new UnsupportedOperationException(); }
    }

    final class ShowEntity implements HoverEvent {
        public EntityTooltipInfo entity() { throw new UnsupportedOperationException(); }
        public Action action() { throw new UnsupportedOperationException(); }
    }

    final class EntityTooltipInfo {
    }
}
