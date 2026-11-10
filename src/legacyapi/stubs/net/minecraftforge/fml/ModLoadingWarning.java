// Compile-only stub of a Forge 26.2 host type for src/legacyapi; never packaged.
package net.minecraftforge.fml;

import java.util.List;
import net.minecraftforge.forgespi.language.IModInfo;

// A record on the host; rebuilt code only reads it.
public final class ModLoadingWarning {
    private ModLoadingWarning() {}

    public String formatToString() { throw new UnsupportedOperationException(); }

    public IModInfo modInfo() { throw new UnsupportedOperationException(); }

    public String i18nMessage() { throw new UnsupportedOperationException(); }

    public List<Object> context() { throw new UnsupportedOperationException(); }
}
