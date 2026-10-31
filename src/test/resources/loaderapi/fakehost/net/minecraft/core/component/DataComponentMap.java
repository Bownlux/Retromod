package net.minecraft.core.component;

import java.util.LinkedHashMap;
import java.util.Map;

public interface DataComponentMap {
    final class Builder {
        public final Map<DataComponentType<?>, Object> components = new LinkedHashMap<>();

        // As on 26.x, setting null removes the component.
        public <T> Builder set(DataComponentType<T> type, T value) {
            if (value == null) components.remove(type);
            else components.put(type, value);
            return this;
        }
    }
}
