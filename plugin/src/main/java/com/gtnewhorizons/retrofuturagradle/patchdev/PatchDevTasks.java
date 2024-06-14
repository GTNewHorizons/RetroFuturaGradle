package com.gtnewhorizons.retrofuturagradle.patchdev;

import java.io.File;
import java.util.Arrays;
import java.util.List;

import org.apache.commons.io.FileUtils;
import org.gradle.api.Project;
import org.gradle.api.artifacts.ConfigurationContainer;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.Copy;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.TaskProvider;

import com.gtnewhorizons.retrofuturagradle.RfgPatchdevExtension;
import com.gtnewhorizons.retrofuturagradle.mcp.CleanupDecompiledJarTask;
import com.gtnewhorizons.retrofuturagradle.mcp.DecompileTask;
import com.gtnewhorizons.retrofuturagradle.mcp.DeobfuscateTask;
import com.gtnewhorizons.retrofuturagradle.mcp.GenSrgMappingsTask;
import com.gtnewhorizons.retrofuturagradle.mcp.MergeSidedJarsTask;
import com.gtnewhorizons.retrofuturagradle.mcp.PatchSourcesTask;
import com.gtnewhorizons.retrofuturagradle.mcp.RemapSourceJarTask;
import com.gtnewhorizons.retrofuturagradle.mcp.RfgCacheService;
import com.gtnewhorizons.retrofuturagradle.mcp.SharedMCPTasks;
import com.gtnewhorizons.retrofuturagradle.minecraft.MinecraftTasks;
import com.gtnewhorizons.retrofuturagradle.util.IJarOutputTask;
import com.gtnewhorizons.retrofuturagradle.util.Utilities;

public class PatchDevTasks extends SharedMCPTasks<RfgPatchdevExtension> {

    public PatchDevTasks(Project project, RfgPatchdevExtension mcExt, MinecraftTasks mcTasks) {
        super(project, mcExt, mcTasks);

        project.afterEvaluate(this::afterEvaluate);
        final Provider<RfgCacheService> rfgCacheService = RfgCacheService.lazyAccess(project.getGradle());

        final File mergedVanillaJarLocation = FileUtils
                .getFile(project.getBuildDir(), RFG_DIR, "vanilla_merged_minecraft.jar");
        final TaskProvider<MergeSidedJarsTask> taskMergeVanillaSidedJars = project.getTasks()
                .register("mergeVanillaSidedJars", MergeSidedJarsTask.class, task -> {
                    task.setGroup(TASK_GROUP_INTERNAL);
                    task.dependsOn(mcTasks.getTaskDownloadVanillaJars());
                    task.onlyIf(t -> !mergedVanillaJarLocation.exists());
                    task.getClientJar().set(mcTasks.getVanillaClientLocation());
                    task.getServerJar().set(mcTasks.getVanillaServerLocation());
                    task.getOutputJar().set(mergedVanillaJarLocation);
                    task.getMergeConfigFile().set(project.file("mcp_merge.cfg"));
                    task.getMcVersion().set(mcExt.getMcVersion());
                });

        final File srgMergedJarLocation = FileUtils.getFile(project.getBuildDir(), RFG_DIR, "srg_merged_minecraft.jar");
        final TaskProvider<DeobfuscateTask> taskDeobfuscateMergedJarToSrg = project.getTasks()
                .register("deobfuscateMergedJarToSrg", DeobfuscateTask.class, task -> {
                    task.setGroup(TASK_GROUP_INTERNAL);
                    task.dependsOn(taskMergeVanillaSidedJars, taskGenerateForgeSrgMappings);
                    task.getSrgFile().set(taskGenerateForgeSrgMappings.flatMap(GenSrgMappingsTask::getNotchToSrg));
                    task.getExceptorJson().set(userdevFile("conf/exceptor.json"));
                    task.getExceptorCfg().set(taskGenerateForgeSrgMappings.flatMap(GenSrgMappingsTask::getSrgExc));
                    task.getInputJar().set(taskMergeVanillaSidedJars.flatMap(IJarOutputTask::getOutputJar));
                    task.getOutputJar().set(srgMergedJarLocation);
                    // No fields or methods CSV - passing them in causes ATs to not successfully apply
                    task.getIsApplyingMarkers().set(true);
                    // Configured in afterEvaluate()
                    task.getAccessTransformerFiles().setFrom(mcExt.getAccessTransformers());
                });

        final File decompiledSrgLocation = FileUtils
                .getFile(project.getBuildDir(), RFG_DIR, "srg_merged_minecraft-sources.jar");
        final File rawDecompiledSrgLocation = FileUtils
                .getFile(project.getBuildDir(), RFG_DIR, "srg_merged_minecraft-sources-rawff.jar");
        final TaskProvider<DecompileTask> taskDecompileSrgJar = project.getTasks()
                .register("decompileSrgJar", DecompileTask.class, task -> {
                    task.setGroup(TASK_GROUP_INTERNAL);
                    task.dependsOn(taskDeobfuscateMergedJarToSrg, taskDownloadFernflower);
                    task.getInputJar().set(taskDeobfuscateMergedJarToSrg.flatMap(DeobfuscateTask::getOutputJar));
                    task.getOutputJar().set(rawDecompiledSrgLocation);
                    task.getCacheDir().set(Utilities.getCacheDir(project, "fernflower-cache"));
                    task.getFernflower().set(fernflowerLocation);
                    task.getCacheService().set(rfgCacheService);
                    task.usesService(rfgCacheService);
                });
        final TaskProvider<CleanupDecompiledJarTask> taskCleanupDecompSrgJar = project.getTasks()
                .register("cleanupDecompSrgJar", CleanupDecompiledJarTask.class, task -> {
                    task.setGroup(TASK_GROUP_INTERNAL);
                    task.dependsOn(taskDecompileSrgJar);
                    task.getInputJar().set(taskDecompileSrgJar.flatMap(DecompileTask::getOutputJar));
                    task.getOutputJar().set(decompiledSrgLocation);
                    task.getPatches().set(userdevDir("conf/minecraft_ff"));
                    task.getAstyleConfig().set(userdevFile("conf/astyle.cfg"));
                });

        final File patchedSourcesLocation = FileUtils
                .getFile(project.getBuildDir(), RFG_DIR, "srg_patched_minecraft-sources.jar");
        final TaskProvider<PatchSourcesTask> taskPatchDecompiledJar = project.getTasks()
                .register("patchDecompiledJar", PatchSourcesTask.class, task -> {
                    task.setGroup(TASK_GROUP_INTERNAL);
                    task.dependsOn(taskCleanupDecompSrgJar);
                    task.getInputJar().set(taskCleanupDecompSrgJar.flatMap(CleanupDecompiledJarTask::getOutputJar));
                    task.getOutputJar().set(patchedSourcesLocation);
                    // BS: Patches added by the buildscript
                    task.getMaxFuzziness().set(2);
                });

        final File remappedUnpatchedSourcesLocation = FileUtils
                .getFile(project.getBuildDir(), RFG_DIR, "mcp_unpatched_minecraft-sources.jar");
        final TaskProvider<RemapSourceJarTask> taskRemapDecompiledJar = project.getTasks()
                .register("remapDecompiledJar", RemapSourceJarTask.class, task -> {
                    task.setGroup(TASK_GROUP_INTERNAL);
                    task.dependsOn(taskCleanupDecompSrgJar);
                    task.getInputJar().set(taskCleanupDecompSrgJar.flatMap(CleanupDecompiledJarTask::getOutputJar));
                    task.getOutputJar().set(remappedUnpatchedSourcesLocation);
                    task.getFieldCsv().set(taskGenerateForgeSrgMappings.flatMap(GenSrgMappingsTask::getFieldsCsv));
                    task.getMethodCsv().set(taskGenerateForgeSrgMappings.flatMap(GenSrgMappingsTask::getMethodsCsv));
                    // No params - incompatible with the old patches
                    task.getAddJavadocs().set(false);
                });

        final File remappedPatchedSourcesLocation = FileUtils
                .getFile(project.getBuildDir(), RFG_DIR, "mcp_patched_minecraft-sources.jar");
        final TaskProvider<RemapSourceJarTask> taskRemapPatchedJar = project.getTasks()
                .register("remapPatchedJar", RemapSourceJarTask.class, task -> {
                    task.setGroup(TASK_GROUP_INTERNAL);
                    task.dependsOn(taskPatchDecompiledJar);
                    task.getInputJar().set(taskPatchDecompiledJar.flatMap(PatchSourcesTask::getOutputJar));
                    task.getOutputJar().set(remappedPatchedSourcesLocation);
                    task.getFieldCsv().set(taskGenerateForgeSrgMappings.flatMap(GenSrgMappingsTask::getFieldsCsv));
                    task.getMethodCsv().set(taskGenerateForgeSrgMappings.flatMap(GenSrgMappingsTask::getMethodsCsv));
                    // No params - incompatible with the old patches
                    task.getAddJavadocs().set(false);
                });

        final JavaPluginExtension javaExt = project.getExtensions().getByType(JavaPluginExtension.class);
        final ConfigurationContainer configurations = project.getConfigurations();

        final File cleanSrcDir = FileUtils.getFile(project.getProjectDir(), "src", "clean");
        final SourceSet cleanSources = javaExt.getSourceSets().create("clean", (SourceSet sources) -> {
            sources.getResources().srcDir(new File(cleanSrcDir, "resources"));
            sources.getJava().srcDir(new File(cleanSrcDir, "java"));
            configurations.getByName(sources.getCompileClasspathConfigurationName())
                    .extendsFrom(configurations.getByName("compileClasspath"));
            configurations.getByName(sources.getRuntimeClasspathConfigurationName())
                    .extendsFrom(configurations.getByName("runtimeClasspath"));
        });
        final File patchedSrcDir = FileUtils.getFile(project.getProjectDir(), "src", "patched");
        final SourceSet patchedSources = javaExt.getSourceSets().create("patched", (SourceSet sources) -> {
            sources.getResources().srcDir(new File(patchedSrcDir, "resources"));
            sources.getJava().srcDir(new File(patchedSrcDir, "java"));
            configurations.getByName(sources.getCompileClasspathConfigurationName())
                    .extendsFrom(configurations.getByName("compileClasspath"));
            configurations.getByName(sources.getRuntimeClasspathConfigurationName())
                    .extendsFrom(configurations.getByName("runtimeClasspath"));
        });

        final List<String> javaFilePatterns = Arrays.asList("**.java", "**/*.java");

        final TaskProvider<Copy> extractCleanSources = project.getTasks()
                .register("extractCleanSources", Copy.class, task -> {
                    task.setGroup(TASK_GROUP_INTERNAL);
                    task.dependsOn(taskRemapDecompiledJar);
                    task.setIncludeEmptyDirs(false);
                    task.from(project.zipTree(taskRemapDecompiledJar.flatMap(RemapSourceJarTask::getOutputJar)));
                    task.into(cleanSrcDir);

                    task.filesMatching(
                            javaFilePatterns,
                            fcd -> { fcd.setRelativePath(fcd.getRelativePath().prepend("java")); });
                    task.filesNotMatching(
                            javaFilePatterns,
                            fcd -> { fcd.setRelativePath(fcd.getRelativePath().prepend("resources")); });
                });

        final TaskProvider<Copy> extractPatchedSources = project.getTasks()
                .register("extractPatchedSources", Copy.class, task -> {
                    task.setGroup(TASK_GROUP_INTERNAL);
                    task.dependsOn(taskRemapPatchedJar);
                    task.setIncludeEmptyDirs(false);
                    task.from(project.zipTree(taskRemapPatchedJar.flatMap(RemapSourceJarTask::getOutputJar)));
                    task.into(patchedSrcDir);
                    task.onlyIf(ignored -> !patchedSrcDir.exists());

                    task.filesMatching(
                            javaFilePatterns,
                            fcd -> { fcd.setRelativePath(fcd.getRelativePath().prepend("java")); });
                    task.filesNotMatching(
                            javaFilePatterns,
                            fcd -> { fcd.setRelativePath(fcd.getRelativePath().prepend("resources")); });
                });
    }

    protected void afterEvaluate(Project _p) {}
}
