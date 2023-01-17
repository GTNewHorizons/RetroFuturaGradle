package com.gtnewhorizons.retrofuturagradle.minecraft;

import com.gtnewhorizons.retrofuturagradle.Constants;
import com.gtnewhorizons.retrofuturagradle.IMinecraftyExtension;
import com.gtnewhorizons.retrofuturagradle.util.Utilities;
import cpw.mods.fml.relauncher.Side;
import de.undercouch.gradle.tasks.download.Download;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.FileUtils;
import org.gradle.api.DefaultTask;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ExternalModuleDependency;
import org.gradle.api.artifacts.dsl.DependencyHandler;
import org.gradle.api.artifacts.dsl.RepositoryHandler;
import org.gradle.api.artifacts.repositories.MavenArtifactRepository;
import org.gradle.api.file.FileCollection;
import org.gradle.api.file.FileTree;
import org.gradle.api.tasks.Copy;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.internal.os.OperatingSystem;

/**
 * Registers vanilla Minecraft-related gradle tasks
 */
public final class MinecraftTasks {
    public static final String MC_DOWNLOAD_PATH = "mc-vanilla";
    private static final String TASK_GROUP_INTERNAL = "Internal Vanilla Minecraft";
    private static final String TASK_GROUP_USER = "Vanilla Minecraft";
    private final Project project;
    private final IMinecraftyExtension mcExt;

    private final File allVersionsManifestLocation;
    private final TaskProvider<Download> taskDownloadLauncherAllVersionsManifest;

    private final File versionManifestLocation;
    private final TaskProvider<Download> taskDownloadLauncherVersionManifest;

    private final File assetManifestLocation;
    private final TaskProvider<Download> taskDownloadAssetManifest;

    private final File vanillaClientLocation, vanillaServerLocation;
    private final TaskProvider<Download> taskDownloadVanillaJars;

    /**
     * The assets root (contains the indexes and objects directories)
     */
    private final File vanillaAssetsLocation;

    private final TaskProvider<DownloadAssetsTask> taskDownloadVanillaAssets;

    private final TaskProvider<DefaultTask> taskCleanVanillaAssets;

    private final TaskProvider<Copy> taskExtractNatives;
    private final TaskProvider<RunMinecraftTask> taskRunVanillaClient;
    private final TaskProvider<RunMinecraftTask> taskRunVanillaServer;

    private final File runDirectory;
    private final File nativesDirectory;
    private final Configuration vanillaMcConfiguration;
    // LWJGL to compile minecraft with
    private final Configuration lwjglCompileMcConfiguration;
    // LWJGL to run the mod with, used for hacking in lwjgl3 via asm transformers
    private final Configuration lwjglModConfiguration;

    public MinecraftTasks(Project project, IMinecraftyExtension mcExt) {
        this.project = project;
        this.mcExt = mcExt;
        allVersionsManifestLocation =
                FileUtils.getFile(project.getBuildDir(), MC_DOWNLOAD_PATH, "all_versions_manifest.json");
        taskDownloadLauncherAllVersionsManifest = project.getTasks()
                .register("downloadLauncherAllVersionsManifest", Download.class, task -> {
                    task.setGroup(TASK_GROUP_INTERNAL);
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
                    task.setGroup(TASK_GROUP_INTERNAL);
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
            task.setGroup(TASK_GROUP_INTERNAL);
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
            task.setGroup(TASK_GROUP_INTERNAL);
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
                    task.setGroup(TASK_GROUP_INTERNAL);
                    task.dependsOn(taskDownloadAssetManifest);
                    task.getManifest().set(assetManifestLocation);
                    task.getObjectsDir().set(new File(vanillaAssetsLocation, "objects"));
                });

        taskCleanVanillaAssets = project.getTasks().register("cleanVanillaAssets", DefaultTask.class, task -> {
            task.setDescription("Removes the cached game assets from your gradle cache");
            task.setGroup(TASK_GROUP_USER);
            task.doLast("cleanVanillaAssetFolders", (_t) -> {
                System.out.println("Cleaning asset folders at " + vanillaAssetsLocation.getAbsolutePath());
                try {
                    FileUtils.deleteDirectory(vanillaAssetsLocation);
                } catch (IOException e) {
                    System.out.println("Couldn't delete assets: " + e.toString());
                }
            });
        });

        runDirectory = new File(project.getProjectDir(), "run");
        nativesDirectory = new File(runDirectory, "natives");

        this.vanillaMcConfiguration = project.getConfigurations().create("vanilla_minecraft");
        this.vanillaMcConfiguration.setCanBeConsumed(false);
        this.lwjglCompileMcConfiguration = project.getConfigurations().create("lwjglMcCompileClasspath");
        this.lwjglModConfiguration = project.getConfigurations().create("lwjglModClasspath");
        applyMcDependencies();

        taskExtractNatives = project.getTasks().register("extractNatives", Copy.class, task -> {
            task.setGroup(TASK_GROUP_INTERNAL);
            task.from(project.provider(() -> {
                final OperatingSystem os = OperatingSystem.current();
                final String twitchNatives;
                final String lwjglNatives = (String) project.getExtensions().getByName("lwjglNatives");
                if (os.isWindows()) {
                    twitchNatives = "natives-windows-64";
                } else if (os.isMacOsX()) {
                    twitchNatives = "natives-osx";
                } else {
                    twitchNatives = "natives-linux"; // don't actually exist
                }
                final FileCollection lwjglZips = lwjglModConfiguration.filter(
                        f -> f.getName().contains("lwjgl") && f.getName().contains(lwjglNatives));
                final FileCollection twitchZips = getVanillaMcConfiguration()
                        .filter(f ->
                                f.getName().contains("twitch") && f.getName().contains(twitchNatives));
                final FileCollection zips = lwjglZips.plus(twitchZips);
                final ArrayList<FileTree> trees = new ArrayList<>();
                for (File zip : zips) {
                    trees.add(project.zipTree(zip));
                }
                return trees;
            }));
            task.exclude("META-INF/**", "META-INF");
            task.into(nativesDirectory);
        });

        taskRunVanillaClient = project.getTasks().register("runVanillaClient", RunMinecraftTask.class, Side.CLIENT);
        taskRunVanillaClient.configure(task -> {
            task.setup(project);
            task.setDescription("Runs the vanilla (unmodified) game client, use --debug-jvm for debugging");
            task.setGroup(TASK_GROUP_USER);
            task.dependsOn(taskDownloadVanillaJars, taskDownloadVanillaAssets);

            task.classpath(vanillaClientLocation);
            task.classpath(vanillaMcConfiguration);
            task.classpath(lwjglCompileMcConfiguration);
            task.getMainClass().set("net.minecraft.client.main.Main");
        });
        taskRunVanillaServer = project.getTasks().register("runVanillaServer", RunMinecraftTask.class, Side.SERVER);
        taskRunVanillaServer.configure(task -> {
            task.setup(project);
            task.setDescription("Runs the vanilla (unmodified) game server, use --debug-jvm for debugging");
            task.setGroup(TASK_GROUP_USER);
            task.dependsOn(taskDownloadVanillaJars);

            task.classpath(vanillaServerLocation);
            task.getMainClass().set("net.minecraft.server.MinecraftServer");
        });
    }

    private void applyMcDependencies() {
        RepositoryHandler repos = project.getRepositories();
        repos.maven(mvn -> {
            mvn.setName("mojang");
            mvn.setUrl(Constants.URL_MOJANG_LIBRARIES_MAVEN);
            mvn.mavenContent(content -> {
                content.includeGroup("com.ibm.icu");
                content.includeGroup("com.mojang");
                content.includeGroup("com.paulscode");
                content.includeGroup("org.lwjgl.lwjgl");
                content.includeGroup("tv.twitch");
                content.includeGroup("net.minecraft");
            });
        });
        repos.maven(mvn -> {
            mvn.setName("forge");
            mvn.setUrl(Constants.URL_FORGE_MAVEN);
            mvn.mavenContent(content -> {
                content.includeGroup("net.minecraftforge");
                content.includeGroup("de.oceanlabs.mcp");
                content.includeGroup("cpw.mods");
                content.includeGroup("org.scala-lang");
            });
            // Allow pom-less artifacts (e.g. MCP data zips)
            mvn.metadataSources(MavenArtifactRepository.MetadataSources::artifact);
        });
        repos.maven(mvn -> {
            mvn.setName("sponge");
            mvn.setUrl(Constants.URL_SPONGEPOWERED_MAVEN);
            mvn.mavenContent(content -> {
                content.includeGroup("lzma");
            });
            // Allow pom-less artifacts (e.g. MCP data zips)
            mvn.metadataSources(MavenArtifactRepository.MetadataSources::artifact);
        });
        repos.mavenCentral().mavenContent(content -> {
            content.excludeGroup("com.mojang");
            content.excludeGroup("net.minecraftforge");
            content.excludeGroup("de.oceanlabs.mcp");
            content.excludeGroup("cpw.mods");
        });

        final OperatingSystem os = OperatingSystem.current();
        final String lwjgl2Natives;
        if (os.isWindows()) {
            lwjgl2Natives = "natives-windows";
        } else if (os.isMacOsX()) {
            lwjgl2Natives = "natives-osx";
        } else {
            lwjgl2Natives = "natives-linux";
        }

        final String lwjgl3Natives;
        final String osArch = System.getProperty("os.arch");
        if (os.isMacOsX()) {
            lwjgl3Natives = (osArch.startsWith("aarch64")) ? "natives-macos-arm64" : "natives-macos";
        } else if (os.isWindows()) {
            if (osArch.contains("64")) {
                lwjgl3Natives = osArch.startsWith("aarch64") ? "natives-windows-arm64" : "natives-windows";
            } else {
                lwjgl3Natives = "natives-windows-x86";
            }
        } else if (os.isLinux() || os.isUnix()) {
            if (osArch.startsWith("arm") || osArch.startsWith("aarch64")) {
                lwjgl3Natives = (osArch.contains("64") || osArch.startsWith("armv8"))
                        ? "natives-linux-arm64"
                        : "natives-linux-arm32";
            } else {
                lwjgl3Natives = "natives-linux";
            }
        } else {
            throw new UnsupportedOperationException("Operating system not supported by lwjgl");
        }

        project.afterEvaluate(_p -> {
            if (mcExt.getApplyMcDependencies().get()) {
                final String lwjglVersion = mcExt.getLwjglVersion().get();
                final boolean lwjglIs2 = lwjglVersion.startsWith("2.");
                final String lwjgl2Version = lwjglIs2 ? lwjglVersion : "2.9.4-nightly-20150209";

                project.getExtensions().add("lwjglNatives", lwjglIs2 ? lwjgl2Natives : lwjgl3Natives);

                final String VANILLA_MC_CFG = vanillaMcConfiguration.getName();
                final DependencyHandler deps = project.getDependencies();
                deps.add(VANILLA_MC_CFG, "com.mojang:netty:1.8.8");
                deps.add(VANILLA_MC_CFG, "com.mojang:realms:1.3.5");
                deps.add(VANILLA_MC_CFG, "org.apache.commons:commons-compress:1.8.1");
                deps.add(VANILLA_MC_CFG, "org.apache.httpcomponents:httpclient:4.3.3");
                deps.add(VANILLA_MC_CFG, "commons-logging:commons-logging:1.1.3");
                deps.add(VANILLA_MC_CFG, "org.apache.httpcomponents:httpcore:4.3.2");
                deps.add(VANILLA_MC_CFG, "java3d:vecmath:1.3.1");
                deps.add(VANILLA_MC_CFG, "net.sf.trove4j:trove4j:3.0.3");
                deps.add(VANILLA_MC_CFG, "com.ibm.icu:icu4j-core-mojang:51.2");
                deps.add(VANILLA_MC_CFG, "net.sf.jopt-simple:jopt-simple:4.5");
                // paulscode libraries depend on lwjgl only
                ((ExternalModuleDependency) deps.add(VANILLA_MC_CFG, "com.paulscode:codecjorbis:20101023"))
                        .setTransitive(false);
                ((ExternalModuleDependency) deps.add(VANILLA_MC_CFG, "com.paulscode:codecwav:20101023"))
                        .setTransitive(false);
                ((ExternalModuleDependency) deps.add(VANILLA_MC_CFG, "com.paulscode:libraryjavasound:20101123"))
                        .setTransitive(false);
                ((ExternalModuleDependency) deps.add(VANILLA_MC_CFG, "com.paulscode:librarylwjglopenal:20100824"))
                        .setTransitive(false);
                ((ExternalModuleDependency) deps.add(VANILLA_MC_CFG, "com.paulscode:soundsystem:20120107"))
                        .setTransitive(false);
                deps.add(VANILLA_MC_CFG, "io.netty:netty-all:4.0.10.Final");
                deps.add(VANILLA_MC_CFG, "com.google.guava:guava:17.0");
                deps.add(VANILLA_MC_CFG, "org.apache.commons:commons-lang3:3.1");
                deps.add(VANILLA_MC_CFG, "commons-io:commons-io:2.4");
                deps.add(VANILLA_MC_CFG, "commons-codec:commons-codec:1.9");
                deps.add(VANILLA_MC_CFG, "net.java.jinput:jinput:2.0.5");
                deps.add(VANILLA_MC_CFG, "net.java.jutils:jutils:1.0.0");
                deps.add(VANILLA_MC_CFG, "com.google.code.gson:gson:2.2.4");
                deps.add(VANILLA_MC_CFG, "com.mojang:authlib:1.5.21");
                deps.add(VANILLA_MC_CFG, "org.apache.logging.log4j:log4j-api:2.0-beta9");
                deps.add(VANILLA_MC_CFG, "org.apache.logging.log4j:log4j-core:2.0-beta9");
                final String LWJGL_MC_CFG = lwjglCompileMcConfiguration.getName();
                deps.add(LWJGL_MC_CFG, "org.lwjgl.lwjgl:lwjgl:" + lwjgl2Version);
                deps.add(LWJGL_MC_CFG, "org.lwjgl.lwjgl:lwjgl_util:" + lwjgl2Version);
                deps.add(LWJGL_MC_CFG, "org.lwjgl.lwjgl:lwjgl-platform:" + lwjgl2Version + ":" + lwjgl2Natives);
                if (lwjglIs2) {
                    lwjglModConfiguration.extendsFrom(lwjglCompileMcConfiguration);
                } else {
                    final String LWJGL3_CFG = lwjglModConfiguration.getName();
                    deps.add(LWJGL3_CFG, deps.platform("org.lwjgl:lwjgl-bom:" + lwjglVersion));
                    deps.add(LWJGL3_CFG, "org.lwjgl:lwjgl:" + lwjglVersion);
                    deps.add(LWJGL3_CFG, "org.lwjgl:lwjgl-glfw:" + lwjglVersion);
                    deps.add(LWJGL3_CFG, "org.lwjgl:lwjgl-openal:" + lwjglVersion);
                    deps.add(LWJGL3_CFG, "org.lwjgl:lwjgl-opengl:" + lwjglVersion);
                    deps.add(LWJGL3_CFG, "org.lwjgl:lwjgl:" + lwjglVersion + ":" + lwjgl3Natives);
                    deps.add(LWJGL3_CFG, "org.lwjgl:lwjgl-glfw:" + lwjglVersion + ":" + lwjgl3Natives);
                    deps.add(LWJGL3_CFG, "org.lwjgl:lwjgl-openal:" + lwjglVersion + ":" + lwjgl3Natives);
                    deps.add(LWJGL3_CFG, "org.lwjgl:lwjgl-opengl:" + lwjglVersion + ":" + lwjgl3Natives);
                }
                deps.add(VANILLA_MC_CFG, "net.java.jinput:jinput-platform:2.0.5:" + lwjgl2Natives);
                deps.add(VANILLA_MC_CFG, "tv.twitch:twitch:5.16");
                if (os.isWindows()) {
                    deps.add(VANILLA_MC_CFG, "tv.twitch:twitch-platform:5.16:natives-windows-64");
                    deps.add(VANILLA_MC_CFG, "tv.twitch:twitch-external-platform:4.5:natives-windows-64");
                }
            }
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

    public File getRunDirectory() {
        return runDirectory;
    }

    public File getNativesDirectory() {
        return nativesDirectory;
    }

    public Configuration getVanillaMcConfiguration() {
        return vanillaMcConfiguration;
    }

    public TaskProvider<Copy> getTaskExtractNatives() {
        return taskExtractNatives;
    }

    public TaskProvider<RunMinecraftTask> getTaskRunVanillaClient() {
        return taskRunVanillaClient;
    }

    public TaskProvider<RunMinecraftTask> getTaskRunVanillaServer() {
        return taskRunVanillaServer;
    }

    public Configuration getLwjglCompileMcConfiguration() {
        return lwjglCompileMcConfiguration;
    }

    public Configuration getLwjglModConfiguration() {
        return lwjglModConfiguration;
    }
}
