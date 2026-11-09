// Compile-only stub of a Forge 26.2 and 26.3 host type for src/legacyapi; never packaged.
package net.minecraftforge.client.model.geometry;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import net.minecraft.client.resources.model.geometry.UnbakedGeometry;

public interface IGeometryLoader {
    UnbakedGeometry read(JsonObject json, JsonDeserializationContext context) throws JsonParseException;
}
