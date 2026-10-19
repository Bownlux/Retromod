package net.minecraft.network;

import io.netty.channel.ChannelFutureListener;
import java.util.function.Supplier;

/** On 26.x this is a class whose factories build netty listeners. */
public class PacketSendListener {
    public PacketSendListener() {}

    public static ChannelFutureListener thenRun(Runnable action) {
        return future -> action.run();
    }

    public static ChannelFutureListener exceptionallySend(Supplier<?> packet) {
        return future -> { };
    }
}
