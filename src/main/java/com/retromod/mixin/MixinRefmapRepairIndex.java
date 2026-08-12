/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.mixin;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Immutable archive-scoped facts that connect Mixin source selectors to repaired refmap targets.
 *
 * <p>A Fabric annotation can retain its Yarn source name while its refmap value names the
 * intermediary target. The class transformer cannot infer that relationship from either input
 * alone. This index carries only relationships read directly from the same archive's refmap and
 * proven against the exact host method index. Conflicting entries are discarded.
 */
public final class MixinRefmapRepairIndex {

    private static final MixinRefmapRepairIndex EMPTY =
            new MixinRefmapRepairIndex(Map.of(), Set.of());

    /** One uniquely proven parameter-addition repair. */
    public record Repair(String targetOwner, String oldTargetDescriptor,
            String newTargetDescriptor, int targetAccess,
            List<MixinHandlerResignature.ParamInsert> insertions) {
        public Repair {
            insertions = List.copyOf(insertions);
        }
    }

    private record Key(String mixinClass, String sourceSelector) {
        private Key {
            mixinClass = normalizeMixinClass(mixinClass);
        }
    }

    private final Map<Key, Repair> repairs;
    private final Set<Key> ambiguous;

    private MixinRefmapRepairIndex(Map<Key, Repair> repairs, Set<Key> ambiguous) {
        this.repairs = Map.copyOf(repairs);
        this.ambiguous = Set.copyOf(ambiguous);
    }

    public static MixinRefmapRepairIndex empty() {
        return EMPTY;
    }

    public static Builder builder() {
        return new Builder();
    }

    public boolean isEmpty() {
        return repairs.isEmpty();
    }

    /** Finds an exact source-selector relationship for one Mixin class. */
    public Optional<Repair> find(String mixinClass, String sourceSelector) {
        if (mixinClass == null || sourceSelector == null) return Optional.empty();
        return Optional.ofNullable(repairs.get(new Key(mixinClass, sourceSelector)));
    }

    /** Returns an immutable union. Any disagreement makes that selector unavailable. */
    public MixinRefmapRepairIndex merge(MixinRefmapRepairIndex other) {
        if (other == null || other == EMPTY) return this;
        if (this == EMPTY) return other;
        Builder builder = new Builder();
        builder.merge(this);
        builder.merge(other);
        return builder.build();
    }

    private static String normalizeMixinClass(String name) {
        return name == null ? "" : name.replace('.', '/');
    }

    /** Mutable collector used only while reading an archive's refmaps. */
    public static final class Builder {
        private final Map<Key, Repair> repairs = new HashMap<>();
        private final Set<Key> ambiguous = new HashSet<>();

        public Builder put(String mixinClass, String sourceSelector, Repair repair) {
            if (mixinClass == null || sourceSelector == null || repair == null) return this;
            Key key = new Key(mixinClass, sourceSelector);
            if (ambiguous.contains(key)) return this;
            Repair previous = repairs.putIfAbsent(key, repair);
            if (previous != null && !previous.equals(repair)) {
                repairs.remove(key);
                ambiguous.add(key);
            }
            return this;
        }

        public Builder merge(MixinRefmapRepairIndex index) {
            if (index == null) return this;
            for (Key key : index.ambiguous) {
                repairs.remove(key);
                ambiguous.add(key);
            }
            for (Map.Entry<Key, Repair> entry : index.repairs.entrySet()) {
                put(entry.getKey().mixinClass(), entry.getKey().sourceSelector(), entry.getValue());
            }
            return this;
        }

        public MixinRefmapRepairIndex build() {
            if (repairs.isEmpty() && ambiguous.isEmpty()) return EMPTY;
            return new MixinRefmapRepairIndex(repairs, ambiguous);
        }
    }
}
