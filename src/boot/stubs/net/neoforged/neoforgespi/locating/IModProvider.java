package net.neoforged.neoforgespi.locating;

import java.nio.file.Path;
import java.util.Map;
import java.util.function.Consumer;

/** Compile-only stub of NeoForge 20.2 to 20.4's IModProvider, which the loader supplies at runtime. */
public interface IModProvider {
    // Only the methods the bootstrap implements.
    String name();

    void scanFile(IModFile modFile, Consumer<Path> pathConsumer);

    void initArguments(Map<String, ?> arguments);

    boolean isValid(IModFile modFile);
}
