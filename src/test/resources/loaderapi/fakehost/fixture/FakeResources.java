package fixture;

import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.StringReader;
import java.util.HashMap;
import java.util.Map;

/** Resource files by id, such as {@code oldmod:models/item/gem.json}. */
public final class FakeResources implements ResourceManager {
    public static final FakeResources INSTANCE = new FakeResources();
    public static final Map<String, String> FILES = new HashMap<>();

    @Override
    public BufferedReader openAsReader(Identifier id) throws FileNotFoundException {
        String text = FILES.get(id.toString());
        if (text == null) throw new FileNotFoundException(id.toString());
        return new BufferedReader(new StringReader(text));
    }
}
