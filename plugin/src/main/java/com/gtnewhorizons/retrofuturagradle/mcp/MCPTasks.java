package com.gtnewhorizons.retrofuturagradle.mcp;

import com.google.common.collect.ImmutableMap;
import com.gtnewhorizons.retrofuturagradle.Constants;
import com.gtnewhorizons.retrofuturagradle.MinecraftExtension;
import com.gtnewhorizons.retrofuturagradle.minecraft.MinecraftTasks;
import com.gtnewhorizons.retrofuturagradle.util.ExtractZipsTask;
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
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.tasks.TaskProvider;

/**
 * Tasks reproducing the MCP/FML/Forge toolchain for deobfuscation
 */
public class MCPTasks {
    private static final String TASK_GROUP_INTERNAL = "Internal MCP";
    private static final String TASK_GROUP_USER = "MCP";
    public static final String MCP_DIR = "mcp";

    private final Project project;
    private final MinecraftExtension mcExt;
    private final MinecraftTasks mcTasks;

    private final Configuration mcpMappingDataConfiguration;
    private final Configuration forgeUserdevConfiguration;

    private final File fernflowerLocation;
    private final TaskProvider<Download> taskDownloadFernflower;

    private final File mcpDataLocation;
    private final TaskProvider<ExtractZipsTask> taskExtractMcpData;
    private final File forgeUserdevLocation;
    private final TaskProvider<ExtractZipsTask> taskExtractForgeUserdev;
    private final File forgeSrgLocation;
    private final TaskProvider<GenSrgMappingsTask> taskGenerateForgeSrgMappings;
    private final File mergedVanillaJarLocation;
    private final TaskProvider<MergeSidedJarsTask> taskMergeVanillaSidedJars;
    /**
     * Merged C+S jar remapped to SRG names
     */
    private final File srgMergedJarLocation;

    private final TaskProvider<DeobfuscateTask> taskDeobfuscateMergedJarToSrg;
    private final ConfigurableFileCollection deobfuscationATs;

    private final TaskProvider<DecompileTask> taskDecompileSrgJar;
    private final File decompiledSrgLocation;

    private final TaskProvider<PatchSourcesTask> taskPatchDecompiledJar;
    private final File patchedSourcesLocation;

    public MCPTasks(Project project, MinecraftExtension mcExt, MinecraftTasks mcTasks) {
        this.project = project;
        this.mcExt = mcExt;
        this.mcTasks = mcTasks;

        project.afterEvaluate(p -> this.afterEvaluate());

        mcpMappingDataConfiguration = project.getConfigurations().create("mcpMappingData");
        forgeUserdevConfiguration = project.getConfigurations().create("fmlUserdev");
        deobfuscationATs = project.getObjects().fileCollection();

        final File fernflowerDownloadLocation = Utilities.getCacheDir(project, "mcp", "fernflower-fixed.zip");
        fernflowerLocation = Utilities.getCacheDir(project, "mcp", "fernflower.jar");
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

        mcpDataLocation = FileUtils.getFile(project.getBuildDir(), MCP_DIR, "data");
        taskExtractMcpData = project.getTasks().register("extractMcpData", ExtractZipsTask.class, task -> {
            task.setGroup(TASK_GROUP_INTERNAL);
            task.getJars().setFrom(getMcpMappingDataConfiguration());
            task.getOutputDir().set(mcpDataLocation);
        });

        forgeUserdevLocation = FileUtils.getFile(project.getBuildDir(), MCP_DIR, "userdev");
        taskExtractForgeUserdev = project.getTasks().register("extractForgeUserdev", ExtractZipsTask.class, task -> {
            task.setGroup(TASK_GROUP_INTERNAL);
            task.getJars().setFrom(getForgeUserdevConfiguration());
            task.getOutputDir().set(forgeUserdevLocation);
        });

        forgeSrgLocation = FileUtils.getFile(project.getBuildDir(), MCP_DIR, "forge_srg");
        taskGenerateForgeSrgMappings = project.getTasks()
                .register("generateForgeSrgMappings", GenSrgMappingsTask.class, task -> {
                    task.setGroup(TASK_GROUP_INTERNAL);
                    task.dependsOn(taskExtractMcpData, taskExtractForgeUserdev);
                    // inputs
                    task.getInputSrg().set(FileUtils.getFile(forgeUserdevLocation, "conf", "packaged.srg"));
                    task.getInputExc().set(FileUtils.getFile(forgeUserdevLocation, "conf", "packaged.exc"));
                    task.getMethodsCsv().set(FileUtils.getFile(mcpDataLocation, "methods.csv"));
                    task.getFieldsCsv().set(FileUtils.getFile(mcpDataLocation, "fields.csv"));
                    // outputs
                    task.getNotchToSrg().set(FileUtils.getFile(forgeSrgLocation, "notch-srg.srg"));
                    task.getNotchToMcp().set(FileUtils.getFile(forgeSrgLocation, "notch-mcp.srg"));
                    task.getSrgToMcp().set(FileUtils.getFile(forgeSrgLocation, "srg-mcp.srg"));
                    task.getMcpToSrg().set(FileUtils.getFile(forgeSrgLocation, "mcp-srg.srg"));
                    task.getMcpToNotch().set(FileUtils.getFile(forgeSrgLocation, "mcp-notch.srg"));
                    task.getSrgExc().set(FileUtils.getFile(forgeSrgLocation, "srg.exc"));
                    task.getMcpExc().set(FileUtils.getFile(forgeSrgLocation, "mcp.exc"));
                });

        mergedVanillaJarLocation = FileUtils.getFile(project.getBuildDir(), MCP_DIR, "vanilla_merged_minecraft.jar");
        taskMergeVanillaSidedJars = project.getTasks()
                .register("mergeVanillaSidedJars", MergeSidedJarsTask.class, task -> {
                    task.setGroup(TASK_GROUP_INTERNAL);
                    task.dependsOn(taskExtractForgeUserdev, mcTasks.getTaskDownloadVanillaJars());
                    task.getClientJar().set(mcTasks.getVanillaClientLocation());
                    task.getServerJar().set(mcTasks.getVanillaServerLocation());
                    task.getMergedJar().set(mergedVanillaJarLocation);
                    task.getMergeConfigFile().set(FileUtils.getFile(forgeUserdevLocation, "conf", "mcp_merge.cfg"));
                    task.getMcVersion().set(mcExt.getMcVersion());
                });

        srgMergedJarLocation = FileUtils.getFile(project.getBuildDir(), MCP_DIR, "srg_merged_minecraft.jar");
        taskDeobfuscateMergedJarToSrg = project.getTasks()
                .register("deobfuscateMergedJarToSrg", DeobfuscateTask.class, task -> {
                    task.setGroup(TASK_GROUP_INTERNAL);
                    task.dependsOn(taskMergeVanillaSidedJars, taskGenerateForgeSrgMappings);
                    task.getSrgFile().set(taskGenerateForgeSrgMappings.flatMap(GenSrgMappingsTask::getNotchToSrg));
                    task.getExceptorJson().set(taskExtractForgeUserdev.flatMap(t -> t.getOutputDir()
                            .file("conf/exceptor.json")));
                    task.getExceptorCfg().set(taskGenerateForgeSrgMappings.flatMap(GenSrgMappingsTask::getSrgExc));
                    task.getInputJar().set(taskMergeVanillaSidedJars.flatMap(MergeSidedJarsTask::getMergedJar));
                    task.getOutputJar().set(srgMergedJarLocation);
                    // TODO: figure out why deobfBinJar uses these but deobfuscateJar doesn't
                    // task.getFieldCsv().set(FileUtils.getFile(mcpDataLocation, "fields.csv"));
                    // task.getMethodCsv().set(FileUtils.getFile(mcpDataLocation, "methods.csv"));
                    task.getIsApplyingMarkers().set(true);
                    // Configured in afterEvaluate()
                    task.getAccessTransformerFiles().setFrom(deobfuscationATs);
                });

        decompiledSrgLocation = FileUtils.getFile(project.getBuildDir(), MCP_DIR, "srg_merged_minecraft-sources.jar");
        taskDecompileSrgJar = project.getTasks().register("decompileSrgJar", DecompileTask.class, task -> {
            task.setGroup(TASK_GROUP_INTERNAL);
            task.dependsOn(taskDeobfuscateMergedJarToSrg, taskDownloadFernflower);
            task.getInputJar().set(taskDeobfuscateMergedJarToSrg.flatMap(DeobfuscateTask::getOutputJar));
            task.getOutputJar().set(decompiledSrgLocation);
            task.onlyIf(t -> !decompiledSrgLocation.exists());
            task.getFernflower().set(fernflowerLocation);
            task.getPatches()
                    .set(taskExtractForgeUserdev.flatMap(t -> t.getOutputDir().dir("conf/minecraft_ff")));
            task.getAstyleConfig()
                    .set(taskExtractForgeUserdev.flatMap(t -> t.getOutputDir().file("conf/astyle.cfg")));
        });

        patchedSourcesLocation = FileUtils.getFile(project.getBuildDir(), MCP_DIR, "srg_patched_minecraft-sources.jar");
        taskPatchDecompiledJar = project.getTasks().register("patchDecompiledJar", PatchSourcesTask.class, task -> {
            task.setGroup(TASK_GROUP_INTERNAL);
            task.dependsOn(taskDecompileSrgJar);
            task.getInputJar().set(taskDecompileSrgJar.flatMap(DecompileTask::getOutputJar));
            task.getOutputJar().set(decompiledSrgLocation);
        });
    }

    private void afterEvaluate() {
        project.getDependencies()
                .add(
                        mcpMappingDataConfiguration.getName(),
                        ImmutableMap.of(
                                "group",
                                "de.oceanlabs.mcp",
                                "name",
                                "mcp_" + mcExt.getMcpMappingChannel().get(),
                                "version",
                                mcExt.getMcpMappingVersion().get() + "-"
                                        + mcExt.getMcVersion().get(),
                                "ext",
                                "zip"));

        project.getDependencies()
                .add(
                        forgeUserdevConfiguration.getName(),
                        "net.minecraftforge:forge:1.7.10-10.13.4.1614-1.7.10:userdev");
        if (mcExt.getUsesFml().get()) {
            deobfuscationATs.builtBy(taskExtractForgeUserdev);
            deobfuscationATs.from(taskExtractForgeUserdev.flatMap(
                    t -> t.getOutputDir().file(Constants.PATH_USERDEV_FML_ACCESS_TRANFORMER)));

            taskPatchDecompiledJar.configure(task -> {
                task.getPatches().builtBy(taskExtractForgeUserdev);
                task.getInjectionDirectories().builtBy(taskExtractForgeUserdev);
                task.getPatches().from(taskExtractForgeUserdev.flatMap(t -> t.getOutputDir()
                        .file("fmlpatches.zip")));
                task.getInjectionDirectories().from(taskExtractForgeUserdev.flatMap(t -> t.getOutputDir()
                        .dir("src/main/java")));
                task.getInjectionDirectories().from(taskExtractForgeUserdev.flatMap(t -> t.getOutputDir()
                        .dir("src/main/resources")));
            });

            if (mcExt.getUsesForge().get()) {
                deobfuscationATs.from(taskExtractForgeUserdev.flatMap(
                        t -> t.getOutputDir().file(Constants.PATH_USERDEV_FORGE_ACCESS_TRANFORMER)));

                taskPatchDecompiledJar.configure(task -> {
                    task.getPatches().from(taskExtractForgeUserdev.flatMap(t -> t.getOutputDir()
                            .file("forgepatches.zip")));
                });
            }
        }
    }

    public Configuration getMcpMappingDataConfiguration() {
        return mcpMappingDataConfiguration;
    }

    public Configuration getForgeUserdevConfiguration() {
        return forgeUserdevConfiguration;
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

    public TaskProvider<ExtractZipsTask> getTaskExtractMcpData() {
        return taskExtractMcpData;
    }

    public File getForgeUserdevLocation() {
        return forgeUserdevLocation;
    }

    public TaskProvider<ExtractZipsTask> getTaskExtractForgeUserdev() {
        return taskExtractForgeUserdev;
    }

    public File getForgeSrgLocation() {
        return forgeSrgLocation;
    }

    public TaskProvider<GenSrgMappingsTask> getTaskGenerateForgeSrgMappings() {
        return taskGenerateForgeSrgMappings;
    }

    public File getMergedVanillaJarLocation() {
        return mergedVanillaJarLocation;
    }

    public TaskProvider<MergeSidedJarsTask> getTaskMergeVanillaSidedJars() {
        return taskMergeVanillaSidedJars;
    }

    public File getSrgMergedJarLocation() {
        return srgMergedJarLocation;
    }

    public TaskProvider<DeobfuscateTask> getTaskDeobfuscateMergedJarToSrg() {
        return taskDeobfuscateMergedJarToSrg;
    }

    public ConfigurableFileCollection getDeobfuscationATs() {
        return deobfuscationATs;
    }

    public TaskProvider<DecompileTask> getTaskDecompileSrgJar() {
        return taskDecompileSrgJar;
    }

    public File getDecompiledSrgLocation() {
        return decompiledSrgLocation;
    }

    public TaskProvider<PatchSourcesTask> getTaskPatchDecompiledJar() {
        return taskPatchDecompiledJar;
    }

    public File getPatchedSourcesLocation() {
        return patchedSourcesLocation;
    }
}
