package com.gtnewhorizons.retrofuturagradle.mcp;

import com.google.common.collect.ImmutableMap;
import com.gtnewhorizons.retrofuturagradle.Constants;
import com.gtnewhorizons.retrofuturagradle.MinecraftExtension;
import com.gtnewhorizons.retrofuturagradle.minecraft.MinecraftTasks;
import com.gtnewhorizons.retrofuturagradle.util.ExtractZipsTask;
import com.gtnewhorizons.retrofuturagradle.util.Utilities;
import de.undercouch.gradle.tasks.download.Download;
import java.io.File;
import org.apache.commons.io.FileUtils;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.tasks.TaskProvider;

/**
 * Tasks reproducing the MCP toolchain for deobfuscation
 */
public class MCPTasks {
    private static final String TASK_GROUP_INTERNAL = "Internal MCP";
    private static final String TASK_GROUP_USER = "MCP";
    private static final String MCP_DIR = "mcp";

    private final Project project;
    private final MinecraftExtension mcExt;
    private final MinecraftTasks mcTasks;

    private final Configuration mcpMappingDataConfiguration;

    private final File fernflowerLocation;
    private final TaskProvider<Download> taskDownloadFernflower;

    private final File mcpDataLocation;
    private final TaskProvider<ExtractZipsTask> taskExtractMcpData;

    public MCPTasks(Project project, MinecraftExtension mcExt, MinecraftTasks mcTasks) {
        this.project = project;
        this.mcExt = mcExt;
        this.mcTasks = mcTasks;

        project.afterEvaluate(p -> this.afterEvaluate());

        mcpMappingDataConfiguration = project.getConfigurations().create("mcpMappingData");

        fernflowerLocation = Utilities.getCacheDir(project, "mcp", "fernflower-fixed.jar");
        taskDownloadFernflower = project.getTasks().register("downloadFernflower", Download.class, task -> {
            task.setGroup(TASK_GROUP_INTERNAL);
            task.src(Constants.URL_FERNFLOWER);
            task.onlyIf(t -> !fernflowerLocation.exists());
            task.overwrite(false);
            task.onlyIfModified(true);
            task.useETag(true);
            task.dest(fernflowerLocation);
        });

        mcpDataLocation = FileUtils.getFile(project.getBuildDir(), MCP_DIR, "data");
        taskExtractMcpData = project.getTasks().register("extractMcpData", ExtractZipsTask.class, task -> {
            task.setGroup(TASK_GROUP_INTERNAL);
            task.getJars().setFrom(getMcpMappingDataConfiguration());
            task.getOutputDir().set(mcpDataLocation);
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

    public TaskProvider<ExtractZipsTask> getTaskExtractMcpData() {
        return taskExtractMcpData;
    }
}
