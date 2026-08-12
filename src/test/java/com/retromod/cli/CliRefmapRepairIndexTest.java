/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.cli;

import com.retromod.core.FuzzyMethodResolver;
import com.retromod.core.RetromodTransformer;
import com.retromod.testsupport.RefmapRepairFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CliRefmapRepairIndexTest {

    @TempDir
    Path tempDir;

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private Field resolverField;
    private Object savedResolver;

    @BeforeEach
    void installExactTargetIndex() throws Exception {
        resolverField = RetromodTransformer.class.getDeclaredField("fuzzyResolver");
        resolverField.setAccessible(true);
        savedResolver = resolverField.get(transformer);
        FuzzyMethodResolver resolver = new FuzzyMethodResolver();
        resolver.indexJar(RefmapRepairFixture.writeTargetJar(tempDir.resolve("target.jar")));
        assertTrue(resolver.isIndexed());
        resolverField.set(transformer, resolver);
    }

    @AfterEach
    void restoreTransformer() throws Exception {
        resolverField.set(transformer, savedResolver);
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("CLI pre-scans a nested refmap before repairing an earlier class")
    void nestedRefmapRetypesHandlerBeforeStreamingClasses() throws Exception {
        byte[] transformed = RetromodCli.transformNestedJar(
                RefmapRepairFixture.archive(true), 1);

        assertEquals(RefmapRepairFixture.NEW_HANDLER,
                RefmapRepairFixture.handlerDescriptor(transformed));
        assertTrue(RefmapRepairFixture.refmap(transformed)
                        .contains(RefmapRepairFixture.NEW_TARGET_SELECTOR),
                "the class and emitted refmap must use the same proven target descriptor");
    }

    @Test
    @DisplayName("CLI does not leak one nested archive's refmap plan into the next archive")
    void nestedRefmapPlanIsArchiveScoped() throws Exception {
        byte[] withRefmap = RetromodCli.transformNestedJar(
                RefmapRepairFixture.archive(true), 1);
        byte[] withoutRefmap = RetromodCli.transformNestedJar(
                RefmapRepairFixture.archive(false), 1);

        assertEquals(RefmapRepairFixture.NEW_HANDLER,
                RefmapRepairFixture.handlerDescriptor(withRefmap));
        assertEquals(RefmapRepairFixture.OLD_HANDLER,
                RefmapRepairFixture.handlerDescriptor(withoutRefmap),
                "an archive without the relationship must not inherit another archive's repair");
    }
}
