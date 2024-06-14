package com.gtnewhorizons.retrofuturagradle.mcp;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

import javax.inject.Inject;

import org.apache.commons.compress.archivers.ArchiveStreamFactory;
import org.apache.commons.io.IOUtils;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.invocation.Gradle;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.provider.Provider;
import org.gradle.api.services.BuildService;
import org.gradle.api.services.BuildServiceParameters;
import org.jetbrains.annotations.Nullable;

import com.gtnewhorizons.retrofuturagradle.Constants;
import com.gtnewhorizons.retrofuturagradle.util.Utilities;

/**
 * A shared build service that can fetch and provide cached forge, mapping, etc. data for various MC versions.
 */
public abstract class RfgCacheService implements BuildService<RfgCacheService.Parameters>, Serializable {

    public interface Parameters extends BuildServiceParameters {

        DirectoryProperty getGradleCacheDirectory();
    }

    /**
     * The name you can use in {@link org.gradle.api.services.ServiceReference} to obtain an instance of this service.
     */
    public static final String NAME = "rfgCacheService";

    private transient FileChannel cacheLockFile = null;

    /**
     * Acquires a lock on the RFG cache directory.
     * 
     * @param shared Whether the lock can be shared with other shared locks (for reading only), if false it must be
     *               exclusive (for writing).
     * @return The lock acquired
     */
    public FileLock lockCache(boolean shared) {
        try {
            final FileChannel channel;
            Path lockFile = null;
            synchronized (this) {
                if (cacheLockFile == null) {
                    final Path cacheDir = getRfgCachePath();
                    if (!Files.isDirectory(cacheDir)) {
                        Files.createDirectories(cacheDir);
                    }
                    lockFile = cacheDir.resolve("rfg.lock");
                    cacheLockFile = FileChannel.open(
                            lockFile,
                            StandardOpenOption.CREATE,
                            StandardOpenOption.WRITE,
                            StandardOpenOption.READ);
                }
                channel = cacheLockFile;
            }
            FileLock lock = null;
            int waitCount = 0;
            int waitTime = 50;
            while (lock == null) {
                try {
                    lock = channel.lock(0, Long.MAX_VALUE, shared);
                } catch (OverlappingFileLockException ofle) {
                    waitCount++;
                    if (waitCount == 5) {
                        LOGGER.warn("Waiting for the RFG cache lock at {} to get released...", lockFile);
                    }
                    try {
                        Thread.sleep(waitTime);
                        if (waitTime < 1000) {
                            waitTime *= 2;
                        }
                    } catch (InterruptedException ignored) {}
                }
            }
            return lock;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private File getGradleCacheDirectory() {
        return getParameters().getGradleCacheDirectory().getAsFile().get();
    }

    public static void register(Gradle gradle) {
        gradle.getSharedServices().registerIfAbsent(
                NAME,
                RfgCacheService.class,
                spec -> { spec.getParameters().getGradleCacheDirectory().set(Utilities.getRawCacheRoot(gradle)); });
    }

    public static RfgCacheService access(Gradle gradle) {
        return (RfgCacheService) gradle.getSharedServices().getRegistrations().named(NAME).get().getService().get();
    }

    @SuppressWarnings("unchecked")
    public static Provider<RfgCacheService> lazyAccess(Gradle gradle) {
        return (Provider<RfgCacheService>) gradle.getSharedServices().getRegistrations().named(NAME).get().getService();
    }

    public Path getRfgCachePath() {
        return getGradleCacheDirectory().toPath().resolve(Utilities.RFG_CACHE_SUBDIRECTORY);
    }

    public Path getFgCachePath() {
        return getGradleCacheDirectory().toPath().resolve("minecraft");
    }

    @SuppressWarnings("unused") // used by Gradle
    @Inject
    public RfgCacheService() {}

    private static final Logger LOGGER = Logging.getLogger("RFG Cache");

    private Path accessDownloadableZipData(final Path targetPath, final List<URI> downloadUris,
            @Nullable final Consumer<Path> prepareFolder) {
        try {
            try (final FileLock ignored = lockCache(true)) {
                if (Files.isDirectory(targetPath)) {
                    return targetPath;
                }
            }
            // Path doesn't exist, upgrade to a write lock and fetch it
            try (final FileLock ignored = lockCache(false)) {
                // Could have been created in between calls
                if (Files.isDirectory(targetPath)) {
                    return targetPath;
                }

                LOGGER.lifecycle("Downloading data into the RFG cache from {}...", downloadUris);

                final Path parentPath = targetPath.getParent();
                if (!Files.exists(parentPath)) {
                    Files.createDirectories(parentPath);
                }

                final Path tempDestination = Files.createTempDirectory(parentPath, "rfg-dl-");
                final Path tempZip = tempDestination.resolve("_rfg-dl-zip.zip");

                for (final URI downloadUri : downloadUris) {
                    IOUtils.copy(downloadUri.toURL(), tempZip.toFile());
                    Utilities.decompressArchive(ArchiveStreamFactory.ZIP, tempZip, tempDestination);
                    Files.delete(tempZip);
                }

                if (prepareFolder != null) {
                    prepareFolder.accept(tempDestination);
                }

                Files.move(tempDestination, targetPath);
                assert Files.isDirectory(targetPath);
                return targetPath;
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * @param forgeVersion The version of forge to get userdev for.
     * @return The root of the extracted userdev archive.
     */
    public Path accessForgeUserdev(final String forgeVersion) {
        final URI downloadUrl = URI.create(
                Constants.URL_FORGE_MAVEN + "/net/minecraftforge/forge/"
                        + forgeVersion
                        + "/forge-"
                        + forgeVersion
                        + "-userdev.jar");
        final Path userdevRoot = getFgCachePath().resolve("net").resolve("minecraftforge").resolve("forge")
                .resolve(forgeVersion).resolve("unpacked");
        return accessDownloadableZipData(userdevRoot, Collections.singletonList(downloadUrl), userdevExtractRoot -> {
            final Path root = userdevExtractRoot;
            final Path srcZip = root.resolve("sources.zip");
            final Path resZip = root.resolve("resources.zip");
            final Path srcMain = root.resolve("src").resolve("main");
            final Path srcMainJava = srcMain.resolve("java");
            final Path srcMainRes = srcMain.resolve("resources");
            try {
                if (Files.exists(srcZip)) {
                    Files.createDirectories(srcMainJava);
                    Utilities.decompressArchive(ArchiveStreamFactory.ZIP, srcZip, srcMainJava);
                }
                if (Files.exists(resZip)) {
                    Files.createDirectories(srcMainRes);
                    Utilities.decompressArchive(ArchiveStreamFactory.ZIP, resZip, srcMainRes);
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * @param mcVersion  the MC version, e.g. 1.7.10 or 1.12.2
     * @param channel    stable or nightly
     * @param mcpVersion the mapping version, e.g. 42
     * @return The root of the extracted MCP archive.
     */
    public Path accessMcpMappings(final String mcVersion, final String channel, final String mcpVersion) {
        // https://maven.minecraftforge.net/de/oceanlabs/mcp/mcp_stable/12-1.7.10/mcp_stable-12-1.7.10.zip
        // https://maven.minecraftforge.net/de/oceanlabs/mcp/mcp/1.7.10/mcp-1.7.10-srg.zip
        final String mcpMcVerComponent = switch (mcVersion) {
            case "1.12.2" -> "1.12";
            case "1.10.2" -> "1.10";
            default -> mcVersion;
        };
        final URI mainUrl = URI.create(
                Constants.URL_FORGE_MAVEN + "/de/oceanlabs/mcp/mcp_"
                        + channel
                        + "/"
                        + mcpVersion
                        + "-"
                        + mcpMcVerComponent
                        + "/mcp_"
                        + channel
                        + "-"
                        + mcpVersion
                        + "-"
                        + mcpMcVerComponent
                        + ".zip");
        final URI srgUrl = URI.create(
                Constants.URL_FORGE_MAVEN + "/de/oceanlabs/mcp/mcp/" + mcVersion + "/mcp-" + mcVersion + "-srg.zip");
        final List<URI> urls = Arrays.asList(srgUrl, mainUrl);
        final Path mcpRoot = getFgCachePath().resolve("de").resolve("oceanlabs").resolve("mcp")
                .resolve("mcp_" + channel).resolve(mcpVersion);
        return accessDownloadableZipData(mcpRoot, urls, null);
    }
}
