package fixture;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;

/** What the test mods' handlers received, and buffers for them to send. */
public final class Recorder {
    public static final List<String> EVENTS = new ArrayList<>();

    private Recorder() {}

    public static FriendlyByteBuf buf(int value) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeInt(value);
        return buf;
    }

    public static void record(String what, ByteBuf buf) {
        EVENTS.add(what + " " + buf.readInt());
    }

    public static void note(String what) {
        EVENTS.add(what);
    }

    public static void noteInt(String what, int value) {
        EVENTS.add(what + " " + value);
    }
}
