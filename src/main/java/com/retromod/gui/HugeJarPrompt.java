/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.gui;

import com.retromod.core.ArchiveAdmission;
import com.retromod.util.ArchiveCheck;
import com.retromod.util.McI18n;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Asks on the title screen whether to translate a held mod jar: one that unpacks to more than
 * 25 GB, or one over 1 GB whose archive check did not pass, with a zip-bomb warning.
 */
public final class HugeJarPrompt {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-HugeJar");

    // The jars asked about this session, so a title screen that opens again asks about the next one.
    private static final Set<Path> ASKED = ConcurrentHashMap.newKeySet();

    // The held jars come from the admission; tests supply their own.
    private static volatile Supplier<List<ArchiveAdmission.Decision>> held = ArchiveAdmission::heldThisLaunch;

    private HugeJarPrompt() {}

    /**
     * Asks about the next held jar over {@code titleScreen}. Returns true when the question
     * opened, so the restart prompt waits until the title screen opens again.
     */
    public static boolean maybeShow(Object titleScreen) {
        ArchiveAdmission.Decision next = nextToAsk();
        if (next == null) return false;
        ASKED.add(next.jar());
        try {
            boolean shown = InGameScreenFactory.showChoice(titleScreen,
                    McI18n.translatable(titleKey(next)),
                    McI18n.translatable(messageKey(next), messageArguments(next)),
                    McI18n.translatable("retromod.huge_jar.allow"),
                    McI18n.translatable("retromod.huge_jar.skip"),
                    () -> allow(titleScreen, next),
                    () -> InGameScreenFactory.showScreen(titleScreen));
            if (!shown) LOGGER.warn(next.message());
            return shown;
        } catch (RuntimeException | LinkageError e) {
            LOGGER.warn("Retromod could not ask about {}: {}. {}", next.name(), e.toString(), next.message());
            return false;
        }
    }

    /** The next held jar not asked about yet, or null. */
    static ArchiveAdmission.Decision nextToAsk() {
        for (ArchiveAdmission.Decision decision : held.get()) {
            if (decision.verdict() == ArchiveAdmission.Verdict.HOLD && !ASKED.contains(decision.jar())) {
                return decision;
            }
        }
        return null;
    }

    /** The title: a failed check gets its own, so the warning shows before the text is read. */
    static String titleKey(ArchiveAdmission.Decision decision) {
        return decision.unverified() ? "retromod.huge_jar.unverified_title" : "retromod.huge_jar.title";
    }

    /** The question: a failed check warns that the jar may be a zip bomb. */
    static String messageKey(ArchiveAdmission.Decision decision) {
        return decision.unverified() ? "retromod.huge_jar.unverified" : "retromod.huge_jar.message";
    }

    /**
     * The values of the message's slots: name, unpacked size, file size, entry count, and folder.
     * The warning leaves out the entry count, since a failed check is no count to trust.
     */
    static Object[] messageArguments(ArchiveAdmission.Decision decision) {
        Path folder = decision.jar().getParent();
        String folderName = folder == null || folder.getFileName() == null ? "."
                : folder.getFileName() + "/";
        String unpacked = ArchiveCheck.humanBytes(decision.unpackedBytes());
        String file = ArchiveCheck.humanBytes(decision.fileBytes());
        if (decision.unverified()) return new Object[]{decision.name(), unpacked, file, folderName};
        int entries = decision.check() == null ? 0 : decision.check().entries();
        return new Object[]{decision.name(), unpacked, file,
                String.format(java.util.Locale.ROOT, "%,d", entries), folderName};
    }

    /** Records the approval and says when the jar is translated. */
    static void allow(Object titleScreen, ArchiveAdmission.Decision decision) {
        String key;
        try {
            ArchiveAdmission.approve(decision);
            key = "retromod.huge_jar.approved";
        } catch (IOException | RuntimeException e) {
            // A jar whose check failed is allowed only from its own list.
            String list = decision.unverified() ? ArchiveAdmission.APPROVED_UNVERIFIED_KEY : ArchiveAdmission.APPROVED_KEY;
            LOGGER.warn("Retromod could not save the approval of {} to {}: {}. Add \"{}\" to {} by hand.",
                    decision.name(), ArchiveAdmission.configPath(), e.getMessage(), decision.sha256(), list);
            key = "retromod.huge_jar.not_saved";
        }
        boolean shown = InGameScreenFactory.showAlertText(titleScreen,
                McI18n.translatable(titleKey(decision)),
                McI18n.translatable(key, decision.name()));
        if (!shown) InGameScreenFactory.showScreen(titleScreen);
    }

    static void useHeldForTesting(Supplier<List<ArchiveAdmission.Decision>> source) {
        held = source;
    }

    static void resetForTesting() {
        held = ArchiveAdmission::heldThisLaunch;
        ASKED.clear();
    }
}
