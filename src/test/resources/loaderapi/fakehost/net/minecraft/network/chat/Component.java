package net.minecraft.network.chat;

public interface Component {
    static Component literal(String text) {
        return new Component() {
            @Override public String toString() { return text; }
        };
    }
}
