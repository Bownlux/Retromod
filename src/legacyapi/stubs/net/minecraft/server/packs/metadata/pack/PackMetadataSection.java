// Compile-only stub of a 1.21.11 to 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.server.packs.metadata.pack;

import net.minecraft.network.chat.Component;
import net.minecraft.util.InclusiveRange;

// A record on the host.
public final class PackMetadataSection {
    public PackMetadataSection(Component description, InclusiveRange<PackFormat> supportedFormats) {
        throw new UnsupportedOperationException();
    }
    public Component description() { throw new UnsupportedOperationException(); }
    public InclusiveRange<PackFormat> supportedFormats() { throw new UnsupportedOperationException(); }
}
