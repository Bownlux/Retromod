/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.neoforge.client.model;

import com.retromod.legacyapi.forge.client.model.LegacyModelLocations;
import com.retromod.shim.common.embedded.LegacyLog;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.event.ModelEvent;

/** NeoForge 1.21.1's {@code ModifyBakingResult.getModels()} on NeoForge 26.x, a live view of the bake's block state models. */
public final class LegacyNeoForgeModelMaps {

    private LegacyNeoForgeModelMaps() {}

    /**
     * The old model map, keyed by block state location. An old model is the host's block state model here, so a put
     * replaces the bake's model for every state the key names. Item models are no block state models and are not in it.
     */
    public static Map<Identifier, BlockStateModel> getModels(ModelEvent.ModifyBakingResult event) {
        return new LiveModels(event.getBakingResult().blockStateModels());
    }

    private static final class LiveModels extends AbstractMap<Identifier, BlockStateModel> {
        private final Map<BlockState, BlockStateModel> blocks;

        LiveModels(Map<BlockState, BlockStateModel> blocks) {
            this.blocks = blocks;
        }

        @Override
        public BlockStateModel get(Object key) {
            List<BlockState> states = states(key);
            return states.isEmpty() ? null : blocks.get(states.get(0));
        }

        @Override
        public boolean containsKey(Object key) {
            return get(key) != null;
        }

        @Override
        public BlockStateModel put(Identifier key, BlockStateModel model) {
            List<BlockState> states = states(key);
            if (states.isEmpty() || model == null) {
                LegacyLog.warnOnce("neoforge model put " + key, "An old mod put a model under " + key + ", which names no block"
                        + " state, so Retromod skipped it. Item models cannot be replaced through the old model map on 26.x.");
                return null;
            }
            BlockStateModel previous = blocks.get(states.get(0));
            try {
                for (BlockState state : states) blocks.put(state, model);
            } catch (UnsupportedOperationException readOnly) {
                LegacyLog.warnOnce("neoforge model put read-only", "Minecraft's baked model map could not be changed, so"
                        + " Retromod skipped the old model put under " + key + ".");
            }
            return previous;
        }

        @Override
        public BlockStateModel remove(Object key) {
            LegacyLog.warnOnce("neoforge model remove", "An old mod removed the model " + key + "; 26.x needs a model for"
                    + " every block state, so Retromod kept it.");
            return null;
        }

        @Override
        public int size() {
            return blocks.size();
        }

        @Override
        public Set<Entry<Identifier, BlockStateModel>> entrySet() {
            return new AbstractSet<>() {
                @Override
                public Iterator<Entry<Identifier, BlockStateModel>> iterator() {
                    Iterator<BlockState> states = new ArrayList<>(blocks.keySet()).iterator();
                    return new Iterator<>() {
                        @Override
                        public boolean hasNext() {
                            return states.hasNext();
                        }

                        @Override
                        public Entry<Identifier, BlockStateModel> next() {
                            BlockState state = states.next();
                            Identifier key = LegacyModelLocations.forState(state);
                            return new SimpleEntry<>(key, blocks.get(state)) {
                                @Override
                                public BlockStateModel setValue(BlockStateModel value) {
                                    return put(key, value);
                                }
                            };
                        }
                    };
                }

                @Override
                public int size() {
                    return blocks.size();
                }
            };
        }

        // A full variant names one state; a shorter one, such as "", every state it matches.
        private static List<BlockState> states(Object key) {
            if (!(key instanceof Identifier location) || !LegacyModelLocations.hasVariant(location)) return List.of();
            Block block = LegacyModelLocations.blockOf(LegacyModelLocations.id(location));
            String variant = LegacyModelLocations.getVariant(location);
            if (block == null || LegacyModelLocations.stateOf(block, variant) == null) return List.of();
            List<BlockState> states = new ArrayList<>();
            for (BlockState candidate : block.getStateDefinition().getPossibleStates()) {
                if (LegacyModelLocations.matches(candidate, variant)) states.add(candidate);
            }
            return states;
        }
    }
}
