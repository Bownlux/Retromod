// Compile-only stub of a 26.1 to 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.client.resources.model;

import net.minecraft.resources.Identifier;

public record EquipmentClientInfo() {
    // The host lists more layer types; Retromod's armor hooks act on the two humanoid ones.
    public enum LayerType { HUMANOID, HUMANOID_LEGGINGS }

    public record Layer(Identifier textureId) {}
}
