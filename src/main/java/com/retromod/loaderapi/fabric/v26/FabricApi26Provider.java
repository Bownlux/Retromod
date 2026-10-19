/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.loaderapi.fabric.v26;

import com.retromod.core.RetromodVersion;
import com.retromod.loaderapi.LoaderApiContext;
import com.retromod.loaderapi.LoaderApiProvider;
import com.retromod.loaderapi.LoaderApiRegistration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Old Fabric API (1.16.5 to 1.21.11) on Fabric and Quilt hosts for Minecraft 26.1 and newer. */
public final class FabricApi26Provider implements LoaderApiProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-LoaderApi");

    public static final String ID = "fabric-26x";

    /** The newest host this provider has rows for; newer hosts get these rows and one warning. */
    public static final String NEWEST_KNOWN_HOST = "26.3";

    private static volatile boolean newerHostWarned;

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String rowsVersion() {
        return FabricApi26Rows.bundled().rowsVersion();
    }

    @Override
    public boolean appliesTo(LoaderApiContext context) {
        return "fabric".equals(context.loader()) && context.hostVersion() != null
                && RetromodVersion.isUnobfuscatedTarget(context.hostVersion())
                && RetromodVersion.compareMcVersions(context.hostVersion(), "26.1") >= 0;
    }

    @Override
    public void register(LoaderApiRegistration registration, LoaderApiContext context) {
        String host = context.hostVersion();
        // A host past the newest known one keeps the newest rows, which is usually right.
        if (RetromodVersion.mcVersionExceeds(host, NEWEST_KNOWN_HOST) && !newerHostWarned) {
            newerHostWarned = true;
            LOGGER.warn("Minecraft {} is newer than the loader API runtime knows ({}). It uses the "
                    + "{} rows, and old Fabric API calls may fail where {} changed.",
                    host, NEWEST_KNOWN_HOST, NEWEST_KNOWN_HOST, host);
        }
        String rowHost = RetromodVersion.mcVersionExceeds(host, NEWEST_KNOWN_HOST)
                ? NEWEST_KNOWN_HOST : host;
        // Shared types first: the table's call rows are keyed on the names they map to.
        NetworkingRows.register(registration);
        RenderRows.register(registration);
        ScreenRows.register(registration);
        FabricApi26Rows.register(FabricApi26Rows.bundled(), registration, rowHost);
    }
}
