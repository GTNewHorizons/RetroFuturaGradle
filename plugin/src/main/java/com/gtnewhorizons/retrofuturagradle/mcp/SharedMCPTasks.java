package com.gtnewhorizons.retrofuturagradle.mcp;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.gradle.api.Project;
import org.gradle.api.UnknownTaskException;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.file.ArchiveOperations;
import org.gradle.api.file.Directory;
import org.gradle.api.file.DuplicatesStrategy;
import org.gradle.api.file.FileSystemOperations;
import org.gradle.api.file.ProjectLayout;
import org.gradle.api.file.RegularFile;
import org.gradle.api.provider.Provider;
import org.gradle.api.provider.ProviderFactory;
import org.gradle.api.specs.Specs;
import org.gradle.api.tasks.Copy;
import org.gradle.api.tasks.TaskContainer;
import org.gradle.api.tasks.TaskProvider;

import com.gtnewhorizons.retrofuturagradle.Constants;
import com.gtnewhorizons.retrofuturagradle.IMinecraftyExtension;
import com.gtnewhorizons.retrofuturagradle.minecraft.MinecraftTasks;
import com.gtnewhorizons.retrofuturagradle.util.MkdirAction;
import com.gtnewhorizons.retrofuturagradle.util.Utilities;

import de.undercouch.gradle.tasks.download.Download;

public class SharedMCPTasks<McExtType extends IMinecraftyExtension> {

    protected static final String TASK_GROUP_INTERNAL = "Internal Modded Minecraft";
    protected static final String TASK_GROUP_USER = "Modded Minecraft";

    public static final String RFG_DIR = "rfg";
    public static final String SOURCE_SET_PATCHED_MC = "patchedMc";
    public static final String SOURCE_SET_LAUNCHER = "mcLauncher";

    protected final McExtType mcExt;
    protected final MinecraftTasks mcTasks;

    protected final Configuration mcpMappingDataConfiguration;
    protected final Configuration forgeUserdevConfiguration;

    protected final TaskProvider<Copy> taskExtractMcpData;

    protected final TaskProvider<Copy> taskExtractForgeUserdev;
    protected final Provider<Directory> forgeSrgLocation;
    protected final TaskProvider<GenSrgMappingsTask> taskGenerateForgeSrgMappings;

    protected final File fernflowerLocation;
    protected final TaskProvider<Download> taskDownloadFernflower;

    public SharedMCPTasks(Project project, McExtType mcExt, MinecraftTasks mcTasks) {
        this.mcExt = mcExt;
        this.mcTasks = mcTasks;

        // Extract specific factories instead of using `project` to avoid serializing Project in the configuration
        // cache.
        final FileSystemOperations fso = mcExt.getFileSystemOperations();
        final ProviderFactory providers = mcExt.getProviderFactory();
        final ArchiveOperations archives = mcExt.getArchiveOperations();
        final ProjectLayout layout = mcExt.getProjectLayout();

        mcpMappingDataConfiguration = project.getConfigurations().create("mcpMappingData");
        mcpMappingDataConfiguration.setCanBeConsumed(false);

        forgeUserdevConfiguration = project.getConfigurations().create("forgeUserdev");
        forgeUserdevConfiguration.setCanBeConsumed(false);

        final File fernflower1Location = Utilities.getCacheDir(project, "mcp", "fernflower.jar");
        final File fernflower1DownloadLocation = Utilities.getCacheDir(project, "mcp", "fernflower-fixed.zip");
        fernflowerLocation = fernflower1Location;
        taskDownloadFernflower = project.getTasks().register("downloadFernflower", Download.class, task -> {
            task.setGroup(TASK_GROUP_INTERNAL);
            task.src(Constants.URL_FERNFLOWER_1);
            final Provider<Integer> minorMcVersion = mcExt.getMinorMcVersion();
            task.onlyIf(t -> minorMcVersion.get() <= 8 && !fernflower1Location.exists());
            task.overwrite(false);
            task.onlyIfModified(true);
            task.useETag(true);
            task.dest(fernflower1DownloadLocation);
            task.doLast(_t -> {
                if (mcExt.getMinorMcVersion().get() > 8) {
                    return;
                }
                try (final FileInputStream fis = new FileInputStream(fernflower1DownloadLocation);
                        final ZipInputStream zis = new ZipInputStream(fis);
                        final FileOutputStream fos = new FileOutputStream(fernflower1Location)) {
                    ZipEntry entry;
                    while ((entry = zis.getNextEntry()) != null) {
                        if (entry.getName().toLowerCase(Locale.ROOT).endsWith("fernflower.jar")) {
                            IOUtils.copy(zis, fos);
                            break;
                        }
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
            task.getOutputs().file(fernflower1Location);
        });

        final File mcpRoot = Utilities.getRawCacheDir(project, "minecraft", "de", "oceanlabs", "mcp");
        final Provider<Directory> mcpExtractRoot = project.getLayout().dir(
                mcExt.getMcpMappingChannel().zip(
                        mcExt.getMcpMappingVersion(),
                        (chan, ver) -> FileUtils.getFile(mcpRoot, "mcp_" + chan, ver)));
        taskExtractMcpData = project.getTasks().register("extractMcpData", Copy.class, task -> {
            task.onlyIf(t -> {
                final File root = mcpExtractRoot.get().getAsFile();
                final boolean hasExtraFiles = mcExt.getMinorMcVersion().get() <= 8
                        || new File(root, "joined.exc").isFile();
                return !(root.isDirectory() && hasExtraFiles && new File(root, "methods.csv").isFile());
            });
            task.getOutputs().upToDateWhen(t -> {
                File root = mcpExtractRoot.get().getAsFile();
                final boolean hasExtraFiles = mcExt.getMinorMcVersion().get() <= 8
                        || new File(root, "joined.exc").isFile();
                return root.isDirectory() && hasExtraFiles && new File(root, "methods.csv").isFile();
            });
            task.setGroup(TASK_GROUP_INTERNAL);
            task.setDuplicatesStrategy(DuplicatesStrategy.FAIL);
            task.from(
                    providers.provider(
                            () -> mcpMappingDataConfiguration.fileCollection(Specs.SATISFIES_ALL).getFiles().stream()
                                    .map(archives::zipTree).collect(Collectors.toList())));
            task.into(mcpExtractRoot);
            task.doFirst(new MkdirAction(mcpExtractRoot));
        });

        final File userdevRoot = Utilities.getRawCacheDir(project, "minecraft", "net", "minecraftforge", "forge");
        final Provider<Directory> userdevRootProvider = project.getLayout()
                .dir(mcExt.getMcVersion().map(mcVer -> switch (mcVer) {
                case "1.7.10" -> FileUtils.getFile(userdevRoot, "1.7.10-10.13.4.1614-1.7.10");
                case "1.12.2" -> FileUtils.getFile(userdevRoot, mcExt.getForgeVersion().get());
                default -> throw new UnsupportedOperationException("Unsupported Minecraft version " + mcVer);
                }));
        final Provider<Directory> userdevExtractRoot = userdevRootProvider.map(root -> root.dir("unpacked"));
        taskExtractForgeUserdev = project.getTasks().register("extractForgeUserdev", Copy.class, task -> {
            task.setEnabled(false); // Enabled as needed in MCPTasks
            task.onlyIf(t -> {
                final File root = userdevExtractRoot.get().getAsFile();
                return !t.getInputs().getFiles().isEmpty()
                        && !(root.isDirectory() && new File(root, "dev.json").isFile());
            });
            task.getOutputs().upToDateWhen(t -> {
                final File root = userdevExtractRoot.get().getAsFile();
                return root.isDirectory() && new File(root, "dev.json").isFile();
            });
            task.setGroup(TASK_GROUP_INTERNAL);
            task.from(
                    providers.provider(
                            () -> forgeUserdevConfiguration.isEmpty() ? layout.files()
                                    : archives.zipTree(
                                            forgeUserdevConfiguration.fileCollection(Specs.SATISFIES_ALL)
                                                    .getSingleFile())));
            task.into(userdevExtractRoot);
            task.doFirst("mkdir", new MkdirAction(userdevExtractRoot));
            task.doLast("extractFg2DataIfNeeded", tsk -> {
                final File root = userdevExtractRoot.get().getAsFile();
                final File srcZip = new File(root, "sources.zip");
                final File resZip = new File(root, "resources.zip");
                final File srcMain = FileUtils.getFile(root, "src", "main");
                final File srcMainJava = FileUtils.getFile(srcMain, "java");
                final File srcMainRes = FileUtils.getFile(srcMain, "resources");
                if (srcZip.exists()) {
                    srcMainJava.mkdirs();
                    fso.copy(copy -> {
                        copy.from(archives.zipTree(srcZip));
                        copy.into(srcMainJava);
                    });
                }
                if (resZip.exists()) {
                    srcMainRes.mkdirs();
                    fso.copy(copy -> {
                        copy.from(archives.zipTree(resZip));
                        copy.into(srcMainRes);
                    });
                }
            });
        });

        forgeSrgLocation = mcExt.getUseForgeEmbeddedMappings().flatMap(
                useForge -> useForge ? userdevRootProvider.map(root -> root.dir("srgs"))
                        : mcpExtractRoot.map(root -> root.dir("rfg_srgs")));
        taskGenerateForgeSrgMappings = project.getTasks()
                .register("generateForgeSrgMappings", GenSrgMappingsTask.class, task -> {
                    task.setGroup(TASK_GROUP_INTERNAL);
                    task.dependsOn(taskExtractMcpData, taskExtractForgeUserdev);
                    final Provider<Directory> srgLocation = forgeSrgLocation; // configuration cache fix
                    task.onlyIf(t -> {
                        File root = srgLocation.get().getAsFile();
                        return !(root.isDirectory() && new File(root, "notch-srg.srg").isFile());
                    });
                    // inputs
                    Provider<Integer> mcVer = mcExt.getMinorMcVersion();
                    task.getInputSrg().set(
                            mcVer.flatMap(v -> (v <= 8) ? userdevFile("conf/packaged.srg") : mcpFile("joined.srg")));
                    task.getInputExc().set(
                            mcVer.flatMap(v -> (v <= 8) ? userdevFile("conf/packaged.exc") : mcpFile("joined.exc")));
                    task.getFieldsCsv().set(
                            mcExt.getUseForgeEmbeddedMappings().flatMap(
                                    useForge -> useForge.booleanValue() ? userdevFile("conf/fields.csv")
                                            : mcpFile("fields.csv")));
                    task.getMethodsCsv().set(
                            mcExt.getUseForgeEmbeddedMappings().flatMap(
                                    useForge -> useForge.booleanValue() ? userdevFile("conf/methods.csv")
                                            : mcpFile("methods.csv")));
                    // outputs
                    task.getNotchToSrg().set(srgFile("notch-srg.srg"));
                    task.getNotchToMcp().set(srgFile("notch-mcp.srg"));
                    task.getSrgToMcp().set(srgFile("srg-mcp.srg"));
                    task.getMcpToSrg().set(srgFile("mcp-srg.srg"));
                    task.getMcpToNotch().set(srgFile("mcp-notch.srg"));
                    task.getSrgExc().set(srgFile("srg.exc"));
                    task.getMcpExc().set(srgFile("mcp.exc"));
                    task.doFirst(new MkdirAction(forgeSrgLocation));
                });

        // Set up dependencies across the cache-writing tasks to suppress Gradle errors about this.
        // The tasks are idempotent, but there's no way to tell this to the validation layer.
        project.afterEvaluate(p -> {
            final Project rootProject = p.getRootProject();
            final Set<String> tasksToOrder = new HashSet<>();
            tasksToOrder.add(taskDownloadFernflower.getName());
            tasksToOrder.add(taskExtractMcpData.getName());
            tasksToOrder.add(taskExtractForgeUserdev.getName());
            tasksToOrder.add(taskGenerateForgeSrgMappings.getName());
            final HashMap<String, TreeMap<String, TaskProvider<?>>> foundTasks = new HashMap<>();
            for (final Project proj : rootProject.getAllprojects()) {
                final TaskContainer subprojTasks = proj.getTasks();
                for (String toFind : tasksToOrder) {
                    try {
                        TaskProvider<?> task = subprojTasks.named(toFind);
                        if (task != null) {
                            final String taskPath = proj.getPath() + ":" + task.getName();
                            foundTasks.computeIfAbsent(toFind, k -> new TreeMap<>()).put(taskPath, task);
                        }
                    } catch (UnknownTaskException e) {
                        // Ignore non-RFG subprojects
                    }
                }
            }
            for (TreeMap<String, TaskProvider<?>> allProjectTasks : foundTasks.values()) {
                Map.Entry<String, TaskProvider<?>> lastTask = null;
                for (Map.Entry<String, TaskProvider<?>> task : allProjectTasks.entrySet()) {
                    if (lastTask != null) {
                        final TaskProvider<?> dependency = lastTask.getValue();
                        task.getValue().configure(t -> { t.mustRunAfter(dependency); });
                        p.getLogger().debug("Added false dependency {} -> {}\n", lastTask.getKey(), task.getKey());
                    }
                    lastTask = task;
                }
            }
        });
    }

    public Provider<RegularFile> mcpFile(String path) {
        return mcExt.getProjectLayout()
                .file(taskExtractMcpData.map(Copy::getDestinationDir).map(d -> new File(d, path)));
    }

    public Provider<Directory> mcpDir(String path) {
        return mcExt.getProjectLayout()
                .dir(taskExtractMcpData.map(Copy::getDestinationDir).map(d -> new File(d, path)));
    }

    public Provider<RegularFile> userdevFile(String path) {
        return mcExt.getProjectLayout()
                .file(taskExtractForgeUserdev.map(Copy::getDestinationDir).map(d -> new File(d, path)));
    }

    public Provider<Directory> userdevDir(String path) {
        return mcExt.getProjectLayout()
                .dir(taskExtractForgeUserdev.map(Copy::getDestinationDir).map(d -> new File(d, path)));
    }

    public Provider<RegularFile> srgFile(String path) {
        return forgeSrgLocation.map(d -> d.file(path));
    }

    public Provider<Directory> getSrgLocation() {
        return forgeSrgLocation;
    }

    public Configuration getMcpMappingDataConfiguration() {
        return mcpMappingDataConfiguration;
    }

    public File getFernflowerLocation() {
        return fernflowerLocation;
    }

    public TaskProvider<Download> getTaskDownloadFernflower() {
        return taskDownloadFernflower;
    }

    public TaskProvider<Copy> getTaskExtractMcpData() {
        return taskExtractMcpData;
    }
}
