// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.server.level;

import com.mojang.authlib.GameProfile;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.stats.Stat;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.player.Player;

public class ServerPlayer extends Player {
    public ServerGamePacketListenerImpl connection;

    public ServerPlayer(MinecraftServer server, ServerLevel level, GameProfile profile, ClientInformation information) {}

    public ServerLevel level() { throw new UnsupportedOperationException(); }
    public void tick() { throw new UnsupportedOperationException(); }
    public void die(DamageSource source) { throw new UnsupportedOperationException(); }
    public boolean canHarmPlayer(Player other) { throw new UnsupportedOperationException(); }
    public boolean isInvulnerableTo(ServerLevel level, DamageSource source) { throw new UnsupportedOperationException(); }
    public void awardStat(Stat<?> stat, int amount) { throw new UnsupportedOperationException(); }
    public void sendSystemMessage(Component message, boolean overlay) { throw new UnsupportedOperationException(); }
    public void updateOptions(ClientInformation information) { throw new UnsupportedOperationException(); }
}
