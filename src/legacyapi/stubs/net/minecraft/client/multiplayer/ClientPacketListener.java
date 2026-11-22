// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.client.multiplayer;

import net.minecraft.core.RegistryAccess;

public class ClientPacketListener extends ClientCommonPacketListenerImpl {
    public RegistryAccess.Frozen registryAccess() { throw new UnsupportedOperationException(); }
    public void sendCommand(String command) { throw new UnsupportedOperationException(); }
    public com.mojang.brigadier.CommandDispatcher<ClientSuggestionProvider> getCommands() { throw new UnsupportedOperationException(); }
    public ClientSuggestionProvider getSuggestionsProvider() { throw new UnsupportedOperationException(); }
}
