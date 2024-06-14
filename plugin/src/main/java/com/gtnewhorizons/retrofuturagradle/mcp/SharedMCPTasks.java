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
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.apache.commons.io.IOUtils;
import org.gradle.api.Project;
import org.gradle.api.UnknownTaskException;
import org.gradle.api.file.ArchiveOperations;
import org.gradle.api.file.Directory;
import org.gradle.api.file.FileSystemOperations;
import org.gradle.api.file.ProjectLayout;
import org.gradle.api.file.RegularFile;
import org.gradle.api.provider.Provider;
import org.gradle.api.provider.ProviderFactory;
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

    protected final Provider<Directory> forgeSrgLocation;
    protected final TaskProvider<GenSrgMappingsTask> taskGenerateForgeSrgMappings;

    protected final File fernflowerLocation;
    protected final TaskProvider<Download> taskDownloadFernflower;

    protected final Provider<Directory> mcpExtractRoot;
    protected final Provider<Directory> userdevExtractRoot;

    public SharedMCPTasks(Project project, McExtType mcExt, MinecraftTasks mcTasks) {
        this.mcExt = mcExt;
        this.mcTasks = mcTasks;

        // Extract specific factories instead of using `project` to avoid serializing Project in the configuration
        // cache.
        final FileSystemOperations fso = mcExt.getFileSystemOperations();
        final ProviderFactory providers = mcExt.getProviderFactory();
        final ArchiveOperations archives = mcExt.getArchiveOperations();
        final ProjectLayout layout = mcExt.getProjectLayout();

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

        final Provider<RfgCacheService> rfgCache = RfgCacheService.lazyAccess(project.getGradle());
        mcpExtractRoot = layout.dir(
                rfgCache.map(
                        c -> c.accessMcpMappings(
                                mcExt.getMcVersion().get(),
                                mcExt.getMcpMappingChannel().get(),
                                mcExt.getMcpMappingVersion().get()).toFile()));
        userdevExtractRoot = layout
                .dir(rfgCache.zip(mcExt.getForgeVersion(), (c, fv) -> c.accessForgeUserdev(fv).toFile()));

        forgeSrgLocation = mcExt.getUseForgeEmbeddedMappings().flatMap(
                useForge -> useForge ? userdevExtractRoot.map(root -> root.dir("srgs"))
                        : mcpExtractRoot.map(root -> root.dir("rfg_srgs")));
        taskGenerateForgeSrgMappings = project.getTasks()
                .register("generateForgeSrgMappings", GenSrgMappingsTask.class, task -> {
                    task.setGroup(TASK_GROUP_INTERNAL);
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
                    task.getCacheService().set(RfgCacheService.lazyAccess(project.getGradle()));
                    task.usesService(RfgCacheService.lazyAccess(project.getGradle()));
                });

        // Set up dependencies across the cache-writing tasks to suppress Gradle errors about this.
        // The tasks are idempotent, but there's no way to tell this to the validation layer.
        project.afterEvaluate(p -> {
            final Project rootProject = p.getRootProject();
            final Set<String> tasksToOrder = new HashSet<>();
            tasksToOrder.add(taskDownloadFernflower.getName());
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
        return mcExt.getProjectLayout().file(mcpExtractRoot.map(d -> new File(d.getAsFile(), path)));
    }

    public Provider<Directory> mcpDir(String path) {
        return mcExt.getProjectLayout().dir(mcpExtractRoot.map(d -> new File(d.getAsFile(), path)));
    }

    public Provider<RegularFile> userdevFile(String path) {
        return mcExt.getProjectLayout().file(userdevExtractRoot.map(d -> new File(d.getAsFile(), path)));
    }

    public Provider<Directory> userdevDir(String path) {
        return mcExt.getProjectLayout().dir(userdevExtractRoot.map(d -> new File(d.getAsFile(), path)));
    }

    public Provider<RegularFile> srgFile(String path) {
        return forgeSrgLocation.map(d -> d.file(path));
    }

    public Provider<Directory> getSrgLocation() {
        return forgeSrgLocation;
    }

    public File getFernflowerLocation() {
        return fernflowerLocation;
    }

    public TaskProvider<Download> getTaskDownloadFernflower() {
        return taskDownloadFernflower;
    }
}
