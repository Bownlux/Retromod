// Compile-only stub of a Forge 26.2 and 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.client.resources.model.sprite;

import com.google.gson.JsonObject;

public class TextureSlots {
    public static final TextureSlots EMPTY = null;
    private TextureSlots() {}
    public Material getMaterial(String name) { throw new UnsupportedOperationException(); }
    public static Data parseTextureMap(JsonObject json) { throw new UnsupportedOperationException(); }

    public static class Resolver {
        public Resolver() {}
        public Resolver addLast(Data data) { throw new UnsupportedOperationException(); }
        public TextureSlots resolve(net.minecraft.client.resources.model.ModelDebugName name) { throw new UnsupportedOperationException(); }
    }

    // A record on the host.
    public static final class Data {
        public static final Data EMPTY = null;
        private Data() {}
    }
}
