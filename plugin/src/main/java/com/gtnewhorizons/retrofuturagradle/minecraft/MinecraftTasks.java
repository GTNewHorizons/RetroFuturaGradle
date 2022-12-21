package com.gtnewhorizons.retrofuturagradle.minecraft;

import com.gtnewhorizons.retrofuturagradle.Constants;
import com.gtnewhorizons.retrofuturagradle.MinecraftExtension;
import com.gtnewhorizons.retrofuturagradle.util.Utilities;
import de.undercouch.gradle.tasks.download.Download;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.FileUtils;
import org.gradle.api.DefaultTask;
import org.gradle.api.Project;
import org.gradle.api.tasks.TaskProvider;

/**
 * Registers vanilla Minecraft-related gradle tasks
 */
public final class MinecraftTasks {
    public static final String MC_DOWNLOAD_PATH = "mc-vanilla";
    private final MinecraftExtension mcExt;

    private final File allVersionsManifestLocation;
    private final TaskProvider<Download> taskDownloadLauncherAllVersionsManifest;

    private final File versionManifestLocation;
    private final TaskProvider<Download> taskDownloadLauncherVersionManifest;

    private final File assetManifestLocation;
    private final TaskProvider<Download> taskDownloadAssetManifest;

    private final File vanillaClientLocation, vanillaServerLocation;
    private final TaskProvider<Download> taskDownloadVanillaJars;

    private final File vanillaAssetsLocation;
    private final TaskProvider<DownloadAssetsTask> taskDownloadVanillaAssets;

    private final TaskProvider<DefaultTask> taskCleanVanillaAssets;

    public MinecraftTasks(Project project, MinecraftExtension mcExt) {
        this.mcExt = mcExt;
        allVersionsManifestLocation =
                FileUtils.getFile(project.getBuildDir(), MC_DOWNLOAD_PATH, "all_versions_manifest.json");
        taskDownloadLauncherAllVersionsManifest = project.getTasks()
                .register("downloadLauncherAllVersionsManifest", Download.class, task -> {
                    task.src(Constants.URL_LAUNCHER_VERSION_MANIFEST);
                    task.onlyIf(t -> !allVersionsManifestLocation.exists());
                    task.overwrite(false);
                    task.onlyIfModified(true);
                    task.useETag(true);
                    task.dest(allVersionsManifestLocation);
                });

        versionManifestLocation =
                FileUtils.getFile(project.getBuildDir(), MC_DOWNLOAD_PATH, "mc_version_manifest.json");
        taskDownloadLauncherVersionManifest = project.getTasks()
                .register("downloadLauncherVersionManifest", Download.class, task -> {
                    task.dependsOn(taskDownloadLauncherAllVersionsManifest);
                    task.src(project.getProviders().provider(() -> {
                        final String mcVersion = mcExt.getMcVersion().get();
                        final String allVersionsManifestJson;
                        try {
                            allVersionsManifestJson =
                                    FileUtils.readFileToString(allVersionsManifestLocation, StandardCharsets.UTF_8);
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                        return LauncherManifest.getVersionManifestUrl(allVersionsManifestJson, mcVersion);
                    }));
                    task.onlyIf(t -> !versionManifestLocation.exists());
                    task.overwrite(false);
                    task.onlyIfModified(true);
                    task.useETag(true);
                    task.dest(versionManifestLocation);
                    task.doLast("parseLauncherManifestJson", (_t) -> {
                        final String versionManifestJson;
                        try {
                            versionManifestJson =
                                    FileUtils.readFileToString(versionManifestLocation, StandardCharsets.UTF_8);
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
                });

        assetManifestLocation = Utilities.getCacheDir(
                project, "assets", "indexes", mcExt.getMcVersion().get() + ".json");
        taskDownloadAssetManifest = project.getTasks().register("downloadAssetManifest", Download.class, task -> {
            task.dependsOn(taskDownloadLauncherVersionManifest);
            task.src(project.getProviders().provider(() -> {
                final LauncherManifest manifest = LauncherManifest.read(versionManifestLocation);
                return manifest.getAssetIndexUrl();
            }));
            task.onlyIf(t -> !assetManifestLocation.exists());
            task.overwrite(false);
            task.onlyIfModified(true);
            task.useETag(true);
            task.dest(assetManifestLocation);
            task.doLast("parseAssetManifestJson", (_t) -> {
                final LauncherManifest manifest = LauncherManifest.read(versionManifestLocation);
                final byte[] assetManifestJsonRaw;
                try {
                    assetManifestJsonRaw = FileUtils.readFileToByteArray(assetManifestLocation);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                final String realSha1 = new DigestUtils(DigestUtils.getSha1Digest()).digestAsHex(assetManifestJsonRaw);
                if (!realSha1.equals(manifest.getAssetIndexSha1())) {
                    throw new RuntimeException(
                            "Mismached assets index sha1 sums: " + realSha1 + " != " + manifest.getAssetIndexSha1());
                }
            });
        });

        vanillaClientLocation = Utilities.getCacheDir(
                project, MC_DOWNLOAD_PATH, mcExt.getMcVersion().get(), "client.jar");
        vanillaServerLocation = Utilities.getCacheDir(
                project, MC_DOWNLOAD_PATH, mcExt.getMcVersion().get(), "server.jar");
        taskDownloadVanillaJars = project.getTasks().register("downloadVanillaJars", Download.class, task -> {
            task.dependsOn(taskDownloadLauncherVersionManifest);
            task.doFirst((_t) -> {
                vanillaClientLocation.getParentFile().mkdirs();
            });
            task.src(project.getProviders().provider(() -> {
                final LauncherManifest manifest = LauncherManifest.read(versionManifestLocation);
                return new String[] {manifest.getClientUrl(), manifest.getServerUrl()};
            }));
            task.onlyIf(t -> !vanillaClientLocation.exists() || !vanillaServerLocation.exists());
            task.overwrite(false);
            task.onlyIfModified(true);
            task.useETag(true);
            task.dest(vanillaClientLocation.getParentFile());
            task.doLast("verifyVanillaJars", (_t) -> {
                final LauncherManifest manifest = LauncherManifest.read(versionManifestLocation);
                final String realClientSha1, realServerSha1;
                try {
                    realClientSha1 = new DigestUtils(DigestUtils.getSha1Digest()).digestAsHex(vanillaClientLocation);
                    realServerSha1 = new DigestUtils(DigestUtils.getSha1Digest()).digestAsHex(vanillaServerLocation);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                if (!realClientSha1.equals(manifest.getClientSha1())) {
                    throw new RuntimeException(
                            "Mismached client.jar sha1 sums: " + realClientSha1 + " != " + manifest.getClientSha1());
                }
                if (!realServerSha1.equals(manifest.getServerSha1())) {
                    throw new RuntimeException(
                            "Mismached server.jar sha1 sums: " + realServerSha1 + " != " + manifest.getServerSha1());
                }
            });
        });

        vanillaAssetsLocation = Utilities.getCacheDir(project, "assets");
        taskDownloadVanillaAssets = project.getTasks()
                .register("downloadVanillaAssets", DownloadAssetsTask.class, task -> {
                    task.dependsOn(taskDownloadAssetManifest);
                    task.getManifest().set(assetManifestLocation);
                    task.getObjectsDir().set(new File(vanillaAssetsLocation, "objects"));
                });

        taskCleanVanillaAssets = project.getTasks().register("cleanVanillaAssets", DefaultTask.class, task -> {
            task.doLast("cleanVanillaAssetFolders", (_t) -> {
                System.out.println("Cleaning asset folders at " + vanillaAssetsLocation.getAbsolutePath());
                try {
                    FileUtils.deleteDirectory(vanillaAssetsLocation);
                } catch (IOException e) {
                    System.out.println("Couldn't delete assets: " + e.toString());
                }
            });
        });
    }

    public File getAllVersionsManifestLocation() {
        return allVersionsManifestLocation;
    }

    public TaskProvider<Download> getTaskDownloadLauncherAllVersionsManifest() {
        return taskDownloadLauncherAllVersionsManifest;
    }

    public File getVersionManifestLocation() {
        return versionManifestLocation;
    }

    public TaskProvider<Download> getTaskDownloadLauncherVersionManifest() {
        return taskDownloadLauncherVersionManifest;
    }

    public File getAssetManifestLocation() {
        return assetManifestLocation;
    }

    public TaskProvider<Download> getTaskDownloadAssetManifest() {
        return taskDownloadAssetManifest;
    }

    public File getVanillaClientLocation() {
        return vanillaClientLocation;
    }

    public File getVanillaServerLocation() {
        return vanillaServerLocation;
    }

    public TaskProvider<Download> getTaskDownloadVanillaJars() {
        return taskDownloadVanillaJars;
    }

    public File getVanillaAssetsLocation() {
        return vanillaAssetsLocation;
    }

    public TaskProvider<DownloadAssetsTask> getTaskDownloadVanillaAssets() {
        return taskDownloadVanillaAssets;
    }

    public TaskProvider<DefaultTask> getTaskCleanVanillaAssets() {
        return taskCleanVanillaAssets;
    }
}
