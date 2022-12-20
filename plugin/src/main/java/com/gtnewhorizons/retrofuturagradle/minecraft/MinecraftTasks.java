package com.gtnewhorizons.retrofuturagradle.minecraft;

import com.gtnewhorizons.retrofuturagradle.Constants;
import com.gtnewhorizons.retrofuturagradle.MinecraftExtension;
import de.undercouch.gradle.tasks.download.Download;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.FileUtils;
import org.gradle.api.Project;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.TaskProvider;

/**
 * Registers vanilla Minecraft-related gradle tasks
 */
public final class MinecraftTasks {
    private final MinecraftExtension mcExt;

    private final File allVersionsManifestLocation;
    private final TaskProvider<Download> taskDownloadLauncherAllVersionsManifest;

    public abstract static class LauncherManifestDownloadTask extends Download {
        @Internal
        public abstract Property<LauncherManifest> getManifest();
    }

    private final File versionManifestLocation;
    private final TaskProvider<LauncherManifestDownloadTask> taskDownloadLauncherVersionManifest;

    public abstract static class AssetManifestDownloadTask extends Download {
        @Internal
        public abstract Property<AssetManifest> getManifest();
    }

    private final File assetManifestLocation;
    private final TaskProvider<AssetManifestDownloadTask> taskDownloadAssetManifest;

    public MinecraftTasks(Project project, MinecraftExtension mcExt) {
        this.mcExt = mcExt;
        allVersionsManifestLocation = new File(project.getBuildDir(), "all_versions_manifest.json");
        taskDownloadLauncherAllVersionsManifest = project.getTasks()
                .register("downloadLauncherAllVersionsManifest", Download.class, task -> {
                    task.src(Constants.URL_LAUNCHER_VERSION_MANIFEST);
                    task.onlyIfModified(true);
                    task.useETag(true);
                    task.dest(allVersionsManifestLocation);
                });

        versionManifestLocation = new File(project.getBuildDir(), "mc_version_manifest.json");
        taskDownloadLauncherVersionManifest = project.getTasks()
                .register("downloadLauncherVersionManifest", LauncherManifestDownloadTask.class, task -> {
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
                        task.getManifest().set(new LauncherManifest(versionManifestJson));
                    });
                });

        assetManifestLocation = new File(project.getBuildDir(), "assets.json");
        taskDownloadAssetManifest = project.getTasks()
                .register("downloadAssetManifest", AssetManifestDownloadTask.class, task -> {
                    task.dependsOn(taskDownloadLauncherVersionManifest);
                    task.src(project.getProviders().provider(() -> {
                        final LauncherManifest manifest = ((LauncherManifestDownloadTask)
                                        project.getTasks().getByPath(taskDownloadLauncherVersionManifest.getName()))
                                .getManifest()
                                .get();
                        return manifest.getAssetIndexUrl();
                    }));
                    task.overwrite(false);
                    task.onlyIfModified(true);
                    task.useETag(true);
                    task.dest(assetManifestLocation);
                    task.doLast("parseAssetManifestJson", (_t) -> {
                        final LauncherManifest manifest = ((LauncherManifestDownloadTask)
                                        project.getTasks().getByPath(taskDownloadLauncherVersionManifest.getName()))
                                .getManifest()
                                .get();
                        final byte[] assetManifestJsonRaw;
                        try {
                            assetManifestJsonRaw = FileUtils.readFileToByteArray(assetManifestLocation);
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                        final String realSha1 =
                                new DigestUtils(DigestUtils.getSha1Digest()).digestAsHex(assetManifestJsonRaw);
                        if (!realSha1.equals(manifest.getAssetIndexSha1())) {
                            throw new RuntimeException("Mismached assets index sha1 sums: " + realSha1 + " != "
                                    + manifest.getAssetIndexSha1());
                        }
                        final String assetManifestJson = new String(assetManifestJsonRaw, StandardCharsets.UTF_8);
                        task.getManifest().set(new AssetManifest(assetManifestJson));
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

    public TaskProvider<LauncherManifestDownloadTask> getTaskDownloadLauncherVersionManifest() {
        return taskDownloadLauncherVersionManifest;
    }
}
