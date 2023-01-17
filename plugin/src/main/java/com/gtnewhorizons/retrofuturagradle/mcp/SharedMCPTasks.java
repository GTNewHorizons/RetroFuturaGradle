package com.gtnewhorizons.retrofuturagradle.mcp;

import com.gtnewhorizons.retrofuturagradle.Constants;
import com.gtnewhorizons.retrofuturagradle.IMinecraftyExtension;
import com.gtnewhorizons.retrofuturagradle.minecraft.MinecraftTasks;
import com.gtnewhorizons.retrofuturagradle.util.Utilities;
import de.undercouch.gradle.tasks.download.Download;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.file.Directory;
import org.gradle.api.file.RegularFile;
import org.gradle.api.provider.Provider;
import org.gradle.api.specs.Specs;
import org.gradle.api.tasks.Copy;
import org.gradle.api.tasks.TaskProvider;

public class SharedMCPTasks<McExtType extends IMinecraftyExtension> {
    protected static final String TASK_GROUP_INTERNAL = "Internal Modded Minecraft";
    protected static final String TASK_GROUP_USER = "Modded Minecraft";

    public static final String RFG_DIR = "rfg";
    public static final String SOURCE_SET_PATCHED_MC = "patchedMc";
    public static final String SOURCE_SET_LAUNCHER = "mcLauncher";

    protected final Project project;
    protected final McExtType mcExt;
    protected final MinecraftTasks mcTasks;

    protected final Configuration mcpMappingDataConfiguration;
    protected final Configuration forgeUserdevConfiguration;

    protected final File mcpDataLocation;
    protected final TaskProvider<Copy> taskExtractMcpData;

    protected final File forgeUserdevLocation;
    protected final TaskProvider<Copy> taskExtractForgeUserdev;
    protected final File forgeSrgLocation;
    protected final TaskProvider<GenSrgMappingsTask> taskGenerateForgeSrgMappings;

    protected final File fernflowerLocation;
    protected final TaskProvider<Download> taskDownloadFernflower;

    public SharedMCPTasks(Project project, McExtType mcExt, MinecraftTasks mcTasks) {
        this.project = project;
        this.mcExt = mcExt;
        this.mcTasks = mcTasks;

        mcpMappingDataConfiguration = project.getConfigurations().create("mcpMappingData");
        mcpMappingDataConfiguration.setCanBeConsumed(false);

        forgeUserdevConfiguration = project.getConfigurations().create("forgeUserdev");
        forgeUserdevConfiguration.setCanBeConsumed(false);

        fernflowerLocation = Utilities.getCacheDir(project, "mcp", "fernflower.jar");
        final File fernflowerDownloadLocation = Utilities.getCacheDir(project, "mcp", "fernflower-fixed.zip");
        taskDownloadFernflower = project.getTasks().register("downloadFernflower", Download.class, task -> {
            task.setGroup(TASK_GROUP_INTERNAL);
            task.src(Constants.URL_FERNFLOWER);
            task.onlyIf(t -> !fernflowerLocation.exists());
            task.overwrite(false);
            task.onlyIfModified(true);
            task.useETag(true);
            task.dest(fernflowerDownloadLocation);
            task.doLast(_t -> {
                try (final FileInputStream fis = new FileInputStream(fernflowerDownloadLocation);
                        final ZipInputStream zis = new ZipInputStream(fis);
                        final FileOutputStream fos = new FileOutputStream(fernflowerLocation)) {
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
            task.getOutputs().file(fernflowerLocation);
        });

        mcpDataLocation = FileUtils.getFile(project.getBuildDir(), RFG_DIR, "data");
        taskExtractMcpData = project.getTasks().register("extractMcpData", Copy.class, task -> {
            task.setGroup(TASK_GROUP_INTERNAL);
            task.from(project.provider(() -> project.zipTree(mcpMappingDataConfiguration
                    .fileCollection(Specs.SATISFIES_ALL)
                    .getSingleFile())));
            task.into(mcpDataLocation);
        });

        forgeUserdevLocation = FileUtils.getFile(project.getBuildDir(), RFG_DIR, "userdev");
        taskExtractForgeUserdev = project.getTasks().register("extractForgeUserdev", Copy.class, task -> {
            task.setGroup(TASK_GROUP_INTERNAL);
            task.from(project.provider(() -> project.zipTree(forgeUserdevConfiguration
                    .fileCollection(Specs.SATISFIES_ALL)
                    .getSingleFile())));
            task.into(forgeUserdevLocation);
        });

        forgeSrgLocation = FileUtils.getFile(project.getBuildDir(), RFG_DIR, "forge_srg");
        taskGenerateForgeSrgMappings = project.getTasks()
                .register("generateForgeSrgMappings", GenSrgMappingsTask.class, task -> {
                    task.setGroup(TASK_GROUP_INTERNAL);
                    task.dependsOn(taskExtractMcpData, taskExtractForgeUserdev);
                    // inputs
                    task.getInputSrg().set(userdevFile("conf/packaged.srg"));
                    task.getInputExc().set(userdevFile("conf/packaged.exc"));
                    task.getFieldsCsv()
                            .set(mcExt.getUseForgeEmbeddedMappings()
                                    .flatMap(useForge -> useForge.booleanValue()
                                            ? userdevFile("conf/fields.csv")
                                            : mcpFile("fields.csv")));
                    task.getMethodsCsv()
                            .set(mcExt.getUseForgeEmbeddedMappings()
                                    .flatMap(useForge -> useForge.booleanValue()
                                            ? userdevFile("conf/methods.csv")
                                            : mcpFile("methods.csv")));
                    // outputs
                    task.getNotchToSrg().set(FileUtils.getFile(forgeSrgLocation, "notch-srg.srg"));
                    task.getNotchToMcp().set(FileUtils.getFile(forgeSrgLocation, "notch-mcp.srg"));
                    task.getSrgToMcp().set(FileUtils.getFile(forgeSrgLocation, "srg-mcp.srg"));
                    task.getMcpToSrg().set(FileUtils.getFile(forgeSrgLocation, "mcp-srg.srg"));
                    task.getMcpToNotch().set(FileUtils.getFile(forgeSrgLocation, "mcp-notch.srg"));
                    task.getSrgExc().set(FileUtils.getFile(forgeSrgLocation, "srg.exc"));
                    task.getMcpExc().set(FileUtils.getFile(forgeSrgLocation, "mcp.exc"));
                });
    }

    public Provider<RegularFile> mcpFile(String path) {
        return project.getLayout()
                .file(taskExtractMcpData.map(Copy::getDestinationDir).map(d -> new File(d, path)));
    }

    public Provider<RegularFile> userdevFile(String path) {
        return project.getLayout()
                .file(taskExtractForgeUserdev.map(Copy::getDestinationDir).map(d -> new File(d, path)));
    }

    public Provider<Directory> userdevDir(String path) {
        return project.getLayout()
                .dir(taskExtractForgeUserdev.map(Copy::getDestinationDir).map(d -> new File(d, path)));
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

    public File getMcpDataLocation() {
        return mcpDataLocation;
    }

    public TaskProvider<Copy> getTaskExtractMcpData() {
        return taskExtractMcpData;
    }
}
