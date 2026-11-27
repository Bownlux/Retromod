// Compile-only stub of a 1.21.11 to 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.network.chat;

public interface ClickEvent {
    record RunCommand(String command) implements ClickEvent {}
    record ShowDialog(Object dialog) implements ClickEvent {}
    record Custom(Object id, Object payload) implements ClickEvent {}
    record ChangePage(int page) implements ClickEvent {}
}
