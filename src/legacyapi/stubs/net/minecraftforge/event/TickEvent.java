// Compile-only stub of a Forge 26.2 host type for src/legacyapi; never packaged.
package net.minecraftforge.event;

import java.util.function.BooleanSupplier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraftforge.fml.LogicalSide;

public interface TickEvent {
    interface ClientTickEvent extends TickEvent {
        final class Pre implements ClientTickEvent {
        }

        final class Post implements ClientTickEvent {
        }
    }

    interface ServerTickEvent extends TickEvent {
        BooleanSupplier haveTimeSupplier();
        default boolean haveTime() { throw new UnsupportedOperationException(); }
        MinecraftServer server();

        final class Pre implements ServerTickEvent {
            public BooleanSupplier haveTimeSupplier() { throw new UnsupportedOperationException(); }
            public MinecraftServer server() { throw new UnsupportedOperationException(); }
        }

        final class Post implements ServerTickEvent {
            public BooleanSupplier haveTimeSupplier() { throw new UnsupportedOperationException(); }
            public MinecraftServer server() { throw new UnsupportedOperationException(); }
        }
    }

    interface LevelTickEvent extends TickEvent {
        LogicalSide side();
        Level level();
        BooleanSupplier haveTimeSupplier();
        default boolean haveTime() { throw new UnsupportedOperationException(); }

        final class Pre implements LevelTickEvent {
            public LogicalSide side() { throw new UnsupportedOperationException(); }
            public Level level() { throw new UnsupportedOperationException(); }
            public BooleanSupplier haveTimeSupplier() { throw new UnsupportedOperationException(); }
        }

        final class Post implements LevelTickEvent {
            public LogicalSide side() { throw new UnsupportedOperationException(); }
            public Level level() { throw new UnsupportedOperationException(); }
            public BooleanSupplier haveTimeSupplier() { throw new UnsupportedOperationException(); }
        }
    }

    interface PlayerTickEvent extends TickEvent {
        LogicalSide side();

        final class Pre implements PlayerTickEvent {
            public LogicalSide side() { throw new UnsupportedOperationException(); }
            public Player player() { throw new UnsupportedOperationException(); }
        }

        final class Post implements PlayerTickEvent {
            public LogicalSide side() { throw new UnsupportedOperationException(); }
            public Player player() { throw new UnsupportedOperationException(); }
        }
    }

    interface RenderTickEvent extends TickEvent {
        final class Pre implements RenderTickEvent {
        }

        final class Post implements RenderTickEvent {
        }
    }
}
