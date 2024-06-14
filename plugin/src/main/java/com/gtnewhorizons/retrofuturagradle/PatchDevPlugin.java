package com.gtnewhorizons.retrofuturagradle;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.plugins.JavaLibraryPlugin;
import org.gradle.util.GradleVersion;

import com.gtnewhorizons.retrofuturagradle.mcp.RfgCacheService;
import com.gtnewhorizons.retrofuturagradle.minecraft.MinecraftTasks;
import com.gtnewhorizons.retrofuturagradle.patchdev.PatchDevTasks;

/**
 * A plugin for building patch-based mods for 1.7.10 Minecraft
 */
public class PatchDevPlugin implements Plugin<Project> {

    public void apply(Project project) {
        project.getPluginManager().apply(JavaLibraryPlugin.class);

        if (GradleVersion.current().compareTo(GradleVersion.version("7.6")) < 0) {
            throw new IllegalStateException("Using RetroFuturaGradle requires at least Gradle 7.6.");
        }

        RfgCacheService.register(project.getGradle());

        // Register the obfuscation status attribute
        ObfuscationAttribute.configureProject(project);

        // Register the `minecraft {...}` block
        final RfgPatchdevExtension pdExt = project.getExtensions()
                .create("rfgPatchDev", RfgPatchdevExtension.class, project);

        final MinecraftTasks mcTasks = new MinecraftTasks(project, pdExt);
        project.getExtensions().add("minecraftTasks", mcTasks);
        final PatchDevTasks mcpTasks = new PatchDevTasks(project, pdExt, mcTasks);
        project.getExtensions().add("patchDevTasks", mcpTasks);
    }
}
