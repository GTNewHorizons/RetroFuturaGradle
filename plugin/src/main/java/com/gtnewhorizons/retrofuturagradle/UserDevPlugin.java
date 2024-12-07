/*
 * This Java source file was generated by the Gradle 'init' task.
 */
package com.gtnewhorizons.retrofuturagradle;

import org.gradle.api.JavaVersion;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.plugins.JavaLibraryPlugin;
import org.gradle.util.GradleVersion;

import com.gtnewhorizons.retrofuturagradle.mcp.MCPTasks;
import com.gtnewhorizons.retrofuturagradle.mcp.RfgCacheService;
import com.gtnewhorizons.retrofuturagradle.minecraft.MinecraftTasks;
import com.gtnewhorizons.retrofuturagradle.modutils.ModUtils;

/**
 * A plugin for modding 1.7.10 Minecraft
 */
public class UserDevPlugin implements Plugin<Project> {

    public void apply(Project project) {
        project.getPluginManager().apply(JavaLibraryPlugin.class);

        if (GradleVersion.current().compareTo(GradleVersion.version("7.6")) < 0) {
            throw new IllegalStateException("Using RetroFuturaGradle requires at least Gradle 7.6.");
        }

        if (!JavaVersion.current().isCompatibleWith(JavaVersion.VERSION_21)) {
            project.getLogger().error(
                    "[DEPRECATION NOTICE] Support for running Gradle using Java versions older than 21 with the RetroFuturaGradle plugin is scheduled to be removed, please upgrade your local and CI workflow Java version to 21 or newer. "
                            + "This does NOT mean that mod code has to use newer Java, the Gradle process itself will require Java 21 but the compiler and mod code can keep on using Java 8.");
        }

        RfgCacheService.register(project.getGradle());

        // Register the obfuscation status attribute
        ObfuscationAttribute.configureProject(project);

        // Register the `minecraft {...}` block
        final MinecraftExtension mcExt = project.getExtensions().create("minecraft", MinecraftExtension.class, project);

        final MinecraftTasks mcTasks = new MinecraftTasks(project, mcExt);
        project.getExtensions().add("minecraftTasks", mcTasks);
        final MCPTasks mcpTasks = new MCPTasks(project, mcExt, mcTasks);
        project.getExtensions().add("mcpTasks", mcpTasks);
        final ModUtils modUtils = new ModUtils(project, mcExt, mcTasks, mcpTasks);
        project.getExtensions().add("modUtils", modUtils);
    }
}
