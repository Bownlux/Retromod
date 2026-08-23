/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.archive;

import com.retromod.core.RetromodVersion;
import com.retromod.util.ZipSecurity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.jar.*;

/**
 * Downloads, caches, and extracts old Fabric/NeoForge API JARs for embedding into legacy mods.
 */
public class ApiArchiveManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("retromod-archive");

    private static final Path ARCHIVE_DIR = Path.of("config/retromod/api-archive");
    private static final long MAX_DOWNLOAD_SIZE = 256L * 1024 * 1024;
    private static final int MAX_ARCHIVE_ENTRIES = 100_000;

    private static final String FABRIC_MAVEN = "https://maven.fabricmc.net/";
    private static final String NEOFORGE_MAVEN = "https://maven.neoforged.net/releases/";
    private static final String FORGE_MAVEN = "https://maven.minecraftforge.net/";
    private static final String MAVEN_CENTRAL = "https://repo1.maven.org/maven2/";

    private static final Map<String, String> FABRIC_API_VERSIONS = new LinkedHashMap<>() {{
        put("1.21", "0.100.0+1.21");
        put("1.21.1", "0.102.0+1.21.1");
        put("1.21.2", "0.106.0+1.21.2");
        put("1.21.3", "0.107.0+1.21.3");
        put("1.21.4", "0.110.0+1.21.4");
        put("1.21.5", "0.115.0+1.21.5");
        put("1.21.6", "0.120.0+1.21.6");
        put("1.21.7", "0.125.0+1.21.7");
        put("1.21.8", "0.130.0+1.21.8");
        put("1.21.9", "0.134.0+1.21.9");
        put("1.21.10", "0.138.0+1.21.10");
        put("1.21.11", "0.141.0+1.21.11");
    }};

    private static final Map<String, String> NEOFORGE_VERSIONS = new LinkedHashMap<>() {{
        put("1.21", "21.0.0-beta");
        put("1.21.1", "21.1.0");
        put("1.21.3", "21.3.0");
        put("1.21.4", "21.4.0");
        put("1.21.5", "21.5.0");
        put("1.21.6", "21.6.0");
        put("1.21.7", "21.7.0");
        put("1.21.8", "21.8.0");
        put("1.21.9", "21.9.0");
        put("1.21.10", "21.10.0");
        put("1.21.11", "21.11.0");
    }};

    private final Map<String, Map<String, byte[]>> archiveCache = new ConcurrentHashMap<>();

    private final ExecutorService downloadExecutor = Executors.newFixedThreadPool(2);

    public ApiArchiveManager() {
        try {
            ensureArchiveDirectory();
        } catch (IOException e) {
            LOGGER.error("Could not create archive directory", e);
        }
    }
    
    /**
     * Returns a class from an archived API version, loading the archive from disk if not cached.
     */
    public byte[] getArchivedClass(String loaderType, String mcVersion, String className) {
        try {
            String archiveKey = archiveKey(loaderType, mcVersion);
            Map<String, byte[]> archive = archiveCache.get(archiveKey);
            if (archive != null && archive.containsKey(className)) {
                return archive.get(className);
            }

            loadArchive(loaderType, mcVersion);
            archive = archiveCache.get(archiveKey);
            if (archive != null) {
                return archive.get(className);
            }
        } catch (Exception e) {
            LOGGER.error("Failed to load archive for {} {}", loaderType, mcVersion, e);
        }
        
        return null;
    }
    
    /**
     * Loads an archive from disk into the memory cache. Never downloads: if the archive isn't already
     * present it throws, pointing at {@link #downloadArchiveWithUserConsent}.
     */
    public void loadArchive(String loaderType, String mcVersion) throws IOException {
        String archiveKey = archiveKey(loaderType, mcVersion);

        if (archiveCache.containsKey(archiveKey)) {
            return;
        }

        Path archivePath = getArchivePath(loaderType, mcVersion);

        if (!Files.isRegularFile(archivePath, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("API archive not present locally for "
                + loaderType + " " + mcVersion + " at " + archivePath + ". "
                + "Retromod does not auto-download archives - see "
                + "ApiArchiveManager.downloadArchiveWithUserConsent for the "
                + "explicit-consent download path, or manually place a JAR "
                + "at the path above.");
        }

        Map<String, byte[]> classes = extractClasses(archivePath);
        archiveCache.put(archiveKey, classes);

        LOGGER.info("Loaded archive {} with {} classes", archiveKey, classes.size());
    }

    /**
     * Downloads an archive only after the consent gate passes. The supplier is invoked after the URL is
     * resolved and logged, so the user sees the destination before consenting. This is the only path
     * that initiates an outbound HTTP request.
     *
     * @return true if the download succeeded; false if the user declined, the file already existed, or
     *         the download failed.
     */
    public boolean downloadArchiveWithUserConsent(String loaderType, String mcVersion,
                                                    java.util.function.BooleanSupplier consentSupplier)
            throws IOException {
        Path archivePath = getArchivePath(loaderType, mcVersion);
        if (Files.exists(archivePath)) {
            return false;
        }

        String url = getDownloadUrl(loaderType, mcVersion);
        LOGGER.info("Awaiting user consent to download: {} (for {} {})",
                url, loaderType, mcVersion);

        if (!consentSupplier.getAsBoolean()) {
            LOGGER.info("User declined download of {}", url);
            return false;
        }

        downloadArchive(loaderType, mcVersion, archivePath);
        return Files.exists(archivePath);
    }

    /**
     * Low-level HTTP download. Only call through {@link #downloadArchiveWithUserConsent}, which holds
     * the consent gate.
     */
    private void downloadArchive(String loaderType, String mcVersion, Path targetPath)
            throws IOException {

        String url = getDownloadUrl(loaderType, mcVersion);

        LOGGER.info("Downloading archive: {}", url);

        HttpURLConnection conn = null;
        Path temporary = null;
        try {
            validateArchiveTarget(targetPath);
            URL downloadUrl = URI.create(url).toURL();
            conn = (HttpURLConnection) downloadUrl.openConnection();
            configureConnection(conn);

            if (conn.getResponseCode() != 200) {
                throw new IOException("Failed to download: HTTP " + conn.getResponseCode());
            }

            long declaredSize = conn.getContentLengthLong();
            if (declaredSize > MAX_DOWNLOAD_SIZE) {
                throw new IOException("Download exceeds " + MAX_DOWNLOAD_SIZE + " bytes: " + url);
            }

            temporary = Files.createTempFile(targetPath.getParent(),
                    "retromod-api-", ".download");
            try (InputStream in = conn.getInputStream();
                 OutputStream out = new BufferedOutputStream(Files.newOutputStream(temporary))) {
                long total = copyDownloadBounded(in, out, MAX_DOWNLOAD_SIZE);
                LOGGER.info("Downloaded {} bytes to {}", total, targetPath.getFileName());
            }

            validateJar(temporary);
            moveReplacingAtomically(temporary, targetPath);
            temporary = null;

        } catch (Exception e) {
            throw new IOException("Download failed: " + url, e);
        } finally {
            if (conn != null) conn.disconnect();
            if (temporary != null) Files.deleteIfExists(temporary);
        }
    }

    static void configureConnection(HttpURLConnection connection) {
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("User-Agent",
                "Retromod/" + RetromodVersion.RETROMOD_VERSION);
        connection.setConnectTimeout(30000);
        connection.setReadTimeout(60000);
    }

    private String getDownloadUrl(String loaderType, String mcVersion) {
        String safeLoader = validateLoaderType(loaderType);
        String safeVersion = validateVersion(mcVersion);
        return switch (safeLoader) {
            case "fabric" -> getFabricApiUrl(safeVersion);
            case "neoforge" -> getNeoForgeUrl(safeVersion);
            case "forge" -> getForgeUrl(safeVersion);
            default -> throw new IllegalStateException("validated loader is unsupported: " + safeLoader);
        };
    }
    
    private String getFabricApiUrl(String mcVersion) {
        String apiVersion = FABRIC_API_VERSIONS.get(mcVersion);
        if (apiVersion == null) {
            throw new IllegalArgumentException("No Fabric API version known for MC " + mcVersion);
        }

        return FABRIC_MAVEN +
               "net/fabricmc/fabric-api/fabric-api/" + apiVersion + 
               "/fabric-api-" + apiVersion + ".jar";
    }
    
    private String getNeoForgeUrl(String mcVersion) {
        String nfVersion = NEOFORGE_VERSIONS.get(mcVersion);
        if (nfVersion == null) {
            throw new IllegalArgumentException("No NeoForge version known for MC " + mcVersion);
        }

        return NEOFORGE_MAVEN +
               "net/neoforged/neoforge/" + nfVersion + 
               "/neoforge-" + nfVersion + ".jar";
    }
    
    private String getForgeUrl(String mcVersion) {
        throw new UnsupportedOperationException(
            "Legacy Forge not supported for 1.21+. Use NeoForge instead.");
    }

    private Path getArchivePath(String loaderType, String mcVersion) throws IOException {
        Path archiveDir = ensureArchiveDirectory();
        Path archivePath = resolveArchivePath(archiveDir, loaderType, mcVersion);
        validateArchiveTarget(archivePath);
        return archivePath;
    }

    private Map<String, byte[]> extractClasses(Path jarPath) throws IOException {
        return extractClasses(jarPath, ZipSecurity.DEFAULT_MAX_ENTRY_SIZE,
                ZipSecurity.DEFAULT_MAX_TOTAL_SIZE);
    }

    static Map<String, byte[]> extractClasses(Path jarPath, long maxClassBytes,
                                               long maxTotalBytes) throws IOException {
        return extractClasses(jarPath, maxClassBytes, maxTotalBytes, MAX_ARCHIVE_ENTRIES);
    }

    static Map<String, byte[]> extractClasses(Path jarPath, long maxClassBytes,
                                               long maxTotalBytes, int maxEntries)
            throws IOException {
        if (maxClassBytes <= 0 || maxTotalBytes <= 0 || maxEntries <= 0) {
            throw new IllegalArgumentException("archive extraction limits must be positive");
        }
        ZipSecurity.validateNotSymlink(jarPath);
        Map<String, byte[]> classes = new HashMap<>();
        Set<String> entryNames = new HashSet<>();
        long total = 0;

        try (JarFile jar = new JarFile(jarPath.toFile())) {
            if (jar.size() > maxEntries) {
                throw new IOException("API archive contains more than " + maxEntries
                    + " entries: " + jarPath.getFileName());
            }
            Enumeration<JarEntry> entries = jar.entries();

            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String entryName = ZipSecurity.safeEntryName(entry.getName());
                String canonicalName = ZipSecurity.canonicalEntryName(entryName);
                if (!entryNames.add(canonicalName)) {
                    throw new IOException("API archive contains a duplicate normalized entry: "
                            + entryName);
                }

                if (!entry.isDirectory() && canonicalName.endsWith(".class")) {
                    try (InputStream is = jar.getInputStream(entry)) {
                        byte[] classBytes = ZipSecurity.safeReadAllBytes(is, maxClassBytes);
                        if (classBytes.length > maxTotalBytes - total) {
                            throw new IOException("API archive exceeds expanded class limit of "
                                    + maxTotalBytes + " bytes at " + canonicalName);
                        }
                        total += classBytes.length;
                        String className = canonicalName.substring(0, canonicalName.length() - 6);
                        if (classes.putIfAbsent(className, classBytes) != null) {
                            throw new IOException("API archive contains duplicate class: " + className);
                        }
                    }
                }
            }
        }

        return classes;
    }

    static String validateLoaderType(String loaderType) {
        if (loaderType == null) throw new IllegalArgumentException("loader type is required");
        String normalized = loaderType.toLowerCase(Locale.ROOT);
        if (!normalized.equals("fabric") && !normalized.equals("neoforge")
                && !normalized.equals("forge")) {
            throw new IllegalArgumentException("Unknown loader type: " + loaderType);
        }
        return normalized;
    }

    static String validateVersion(String mcVersion) {
        if (mcVersion == null || mcVersion.isBlank()) {
            throw new IllegalArgumentException("Minecraft version is required");
        }
        if (mcVersion.length() > 128
                || !mcVersion.matches("[A-Za-z0-9][A-Za-z0-9._+-]*")) {
            throw new IllegalArgumentException("Unsafe Minecraft version component: " + mcVersion);
        }
        return mcVersion;
    }

    static Path resolveArchivePath(Path archiveDir, String loaderType, String mcVersion)
            throws IOException {
        String loader = validateLoaderType(loaderType);
        String version = validateVersion(mcVersion);
        Path normalizedDir = archiveDir.toAbsolutePath().normalize();
        Path resolved = normalizedDir.resolve(loader + "-" + version + ".jar").normalize();
        if (!normalizedDir.equals(resolved.getParent())) {
            throw new IOException("API archive path escapes the archive directory");
        }
        return resolved;
    }

    private static String archiveKey(String loaderType, String mcVersion) {
        return validateLoaderType(loaderType) + "-" + validateVersion(mcVersion);
    }

    private static synchronized Path ensureArchiveDirectory() throws IOException {
        Path archiveDir = ARCHIVE_DIR.toAbsolutePath().normalize();
        validateNoSymlinkComponents(archiveDir);
        Files.createDirectories(archiveDir);
        validateNoSymlinkComponents(archiveDir);
        if (!Files.isDirectory(archiveDir, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("API archive path is not a directory: " + archiveDir);
        }
        return archiveDir;
    }

    private static void validateArchiveTarget(Path targetPath) throws IOException {
        validateArchiveTarget(ARCHIVE_DIR.toAbsolutePath().normalize(), targetPath);
    }

    static void validateArchiveTarget(Path archiveDirectory, Path targetPath) throws IOException {
        Path archiveDir = archiveDirectory.toAbsolutePath().normalize();
        Path normalizedTarget = targetPath.toAbsolutePath().normalize();
        if (!archiveDir.equals(normalizedTarget.getParent())) {
            throw new IOException("API archive target escapes the archive directory: " + targetPath);
        }
        validateNoSymlinkComponents(archiveDir);
        if (Files.isSymbolicLink(normalizedTarget)) {
            throw new IOException("Security: symlink detected at API archive target: "
                    + normalizedTarget);
        }
        ZipSecurity.validateNotSymlink(normalizedTarget);
    }

    private static void validateNoSymlinkComponents(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        Path current = absolute.getRoot();
        for (Path component : absolute) {
            current = current == null ? component : current.resolve(component);
            if (Files.isSymbolicLink(current)) {
                throw new IOException("Security: symlink detected in API archive path: " + current);
            }
        }
    }

    static long copyDownloadBounded(InputStream input, OutputStream output, long maxBytes)
            throws IOException {
        if (maxBytes <= 0) throw new IllegalArgumentException("download limit must be positive");
        byte[] buffer = new byte[8192];
        long total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (read > maxBytes - total) {
                throw new IOException("API archive download exceeds " + maxBytes + " bytes");
            }
            output.write(buffer, 0, read);
            total += read;
        }
        return total;
    }

    static void validateJar(Path jarPath) throws IOException {
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            if (jar.size() > MAX_ARCHIVE_ENTRIES) {
                throw new IOException("API archive contains more than "
                    + MAX_ARCHIVE_ENTRIES + " entries");
            }
            Set<String> entryNames = new HashSet<>();
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                String entryName = ZipSecurity.safeEntryName(entries.nextElement().getName());
                String canonicalName = ZipSecurity.canonicalEntryName(entryName);
                if (!entryNames.add(canonicalName)) {
                    throw new IOException("API archive contains a duplicate normalized entry: "
                            + entryName);
                }
            }
        }
    }

    private static void moveReplacingAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Pre-downloads archives for all known versions, but only if the consent gate passes. The supplier
     * is consulted once before any download starts; on denial the future completes immediately with no
     * network activity.
     */
    public CompletableFuture<Void> preloadAllArchives(java.util.function.BooleanSupplier consentSupplier) {
        if (!consentSupplier.getAsBoolean()) {
            LOGGER.info("User declined preload of all API archives");
            return CompletableFuture.completedFuture(null);
        }

        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (String mcVersion : FABRIC_API_VERSIONS.keySet()) {
            futures.add(CompletableFuture.runAsync(() -> {
                try {
                    Path path = getArchivePath("fabric", mcVersion);
                    if (!Files.exists(path)) {
                        downloadArchive("fabric", mcVersion, path);
                    }
                } catch (Exception e) {
                    LOGGER.warn("Failed to preload Fabric API for {}", mcVersion, e);
                }
            }, downloadExecutor));
        }

        for (String mcVersion : NEOFORGE_VERSIONS.keySet()) {
            futures.add(CompletableFuture.runAsync(() -> {
                try {
                    Path path = getArchivePath("neoforge", mcVersion);
                    if (!Files.exists(path)) {
                        downloadArchive("neoforge", mcVersion, path);
                    }
                } catch (Exception e) {
                    LOGGER.warn("Failed to preload NeoForge for {}", mcVersion, e);
                }
            }, downloadExecutor));
        }

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
    }
    
    /**
     * Returns all classes in an API whose name starts with the given package prefix.
     */
    public List<String> findClasses(String loaderType, String mcVersion, String packagePattern) {
        String archiveKey = archiveKey(loaderType, mcVersion);
        Map<String, byte[]> archive = archiveCache.get(archiveKey);

        if (archive == null) {
            try {
                loadArchive(loaderType, mcVersion);
                archive = archiveCache.get(archiveKey);
            } catch (Exception e) {
                return Collections.emptyList();
            }
        }

        if (archive == null) return Collections.emptyList();

        String pattern = packagePattern.replace('.', '/');
        List<String> matches = new ArrayList<>();

        for (String className : archive.keySet()) {
            if (className.startsWith(pattern)) {
                matches.add(className);
            }
        }
        
        return matches;
    }
    
    /**
     * Compares two API versions, returning the removed, added, and modified classes.
     */
    public ApiDiff compareVersions(String loaderType, String oldVersion, String newVersion)
            throws IOException {

        loadArchive(loaderType, oldVersion);
        loadArchive(loaderType, newVersion);

        Map<String, byte[]> oldClasses = archiveCache.get(archiveKey(loaderType, oldVersion));
        Map<String, byte[]> newClasses = archiveCache.get(archiveKey(loaderType, newVersion));

        Set<String> removed = new HashSet<>(oldClasses.keySet());
        removed.removeAll(newClasses.keySet());

        Set<String> added = new HashSet<>(newClasses.keySet());
        added.removeAll(oldClasses.keySet());

        Set<String> modified = new HashSet<>();
        for (String className : oldClasses.keySet()) {
            if (newClasses.containsKey(className)) {
                byte[] oldBytes = oldClasses.get(className);
                byte[] newBytes = newClasses.get(className);
                if (!Arrays.equals(oldBytes, newBytes)) {
                    modified.add(className);
                }
            }
        }
        
        return new ApiDiff(oldVersion, newVersion, removed, added, modified);
    }

    public void shutdown() {
        downloadExecutor.shutdown();
        try {
            downloadExecutor.awaitTermination(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            downloadExecutor.shutdownNow();
        }
    }

    public void clearCache() {
        archiveCache.clear();
    }

    /** Class count per loaded archive. */
    public Map<String, Integer> getCacheStats() {
        Map<String, Integer> stats = new HashMap<>();
        for (var entry : archiveCache.entrySet()) {
            stats.put(entry.getKey(), entry.getValue().size());
        }
        return stats;
    }

    public record ApiDiff(
        String oldVersion,
        String newVersion,
        Set<String> removedClasses,
        Set<String> addedClasses,
        Set<String> modifiedClasses
    ) {
        public boolean hasChanges() {
            return !removedClasses.isEmpty() || !addedClasses.isEmpty() || !modifiedClasses.isEmpty();
        }
        
        public int totalChanges() {
            return removedClasses.size() + addedClasses.size() + modifiedClasses.size();
        }
    }
}
