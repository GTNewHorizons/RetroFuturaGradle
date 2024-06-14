package com.gtnewhorizons.retrofuturagradle.mcp;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.gradle.api.DefaultTask;
import org.gradle.api.JavaVersion;
import org.gradle.api.Project;
import org.gradle.api.UnknownTaskException;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.ModuleDependency;
import org.gradle.api.artifacts.ResolvedArtifact;
import org.gradle.api.artifacts.ResolvedDependency;
import org.gradle.api.artifacts.dsl.DependencyHandler;
import org.gradle.api.attributes.Attribute;
import org.gradle.api.attributes.AttributeContainer;
import org.gradle.api.attributes.Bundling;
import org.gradle.api.attributes.Category;
import org.gradle.api.attributes.LibraryElements;
import org.gradle.api.attributes.Usage;
import org.gradle.api.attributes.java.TargetJvmVersion;
import org.gradle.api.component.AdhocComponentWithVariants;
import org.gradle.api.component.ConfigurationVariantDetails;
import org.gradle.api.component.SoftwareComponent;
import org.gradle.api.file.ArchiveOperations;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.FileCollection;
import org.gradle.api.file.FileSystemOperations;
import org.gradle.api.file.ProjectLayout;
import org.gradle.api.file.RegularFile;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Provider;
import org.gradle.api.provider.ProviderFactory;
import org.gradle.api.tasks.Copy;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.compile.JavaCompile;
import org.gradle.api.tasks.scala.ScalaCompile;
import org.gradle.jvm.tasks.Jar;
import org.gradle.language.base.plugins.LifecycleBasePlugin;

import com.google.common.collect.ImmutableMap;
import com.gtnewhorizons.retrofuturagradle.BuildConfig;
import com.gtnewhorizons.retrofuturagradle.Constants;
import com.gtnewhorizons.retrofuturagradle.MinecraftExtension;
import com.gtnewhorizons.retrofuturagradle.ObfuscationAttribute;
import com.gtnewhorizons.retrofuturagradle.minecraft.MinecraftTasks;
import com.gtnewhorizons.retrofuturagradle.minecraft.RunMinecraftTask;
import com.gtnewhorizons.retrofuturagradle.util.Distribution;
import com.gtnewhorizons.retrofuturagradle.util.IJarOutputTask;
import com.gtnewhorizons.retrofuturagradle.util.IJarTransformTask;
import com.gtnewhorizons.retrofuturagradle.util.JarChain;
import com.gtnewhorizons.retrofuturagradle.util.Utilities;

/**
 * Tasks reproducing the MCP/FML/Forge toolchain for deobfuscation
 */
public class MCPTasks extends SharedMCPTasks<MinecraftExtension> {

    private final Configuration forgeUniversalConfiguration;

    private final JarChain decompiledMcChain;
    private final File mergedVanillaJarLocation;
    private final TaskProvider<MergeSidedJarsTask> taskMergeVanillaSidedJars;
    /**
     * Merged C+S jar remapped to SRG names
     */
    private final File srgMergedJarLocation;

    private final TaskProvider<ExtractDependencyATsTask> taskExtractDependencyATs;

    private final TaskProvider<DeobfuscateTask> taskDeobfuscateMergedJarToSrg;
    private final ConfigurableFileCollection deobfuscationATs;

    private final TaskProvider<DecompileTask> taskDecompileSrgJar;
    private final TaskProvider<CleanupDecompiledJarTask> taskCleanupDecompSrgJar;
    private final File decompiledSrgLocation;

    private final TaskProvider<PatchSourcesTask> taskPatchDecompiledJar;
    private final File patchedSourcesLocation;

    private final TaskProvider<RemapSourceJarTask> taskRemapDecompiledJar;
    private final File remappedSourcesLocation;

    private final TaskProvider<Copy> taskDecompressDecompiledSources;
    private final File decompressedSourcesLocation;
    private final Configuration patchedConfiguration;
    private final SourceSet patchedMcSources;
    private final TaskProvider<JavaCompile> taskBuildPatchedMc;
    private final File packagedMcLocation;
    private final TaskProvider<Jar> taskPackagePatchedMc;
    private final File launcherSourcesLocation;
    private final TaskProvider<CreateLauncherFiles> taskCreateLauncherFiles;
    private final SourceSet launcherSources;
    private final File packagedMcLauncherLocation;
    private final TaskProvider<Jar> taskPackageMcLauncher;
    private final TaskProvider<RunMinecraftTask> taskRunClient;
    private final TaskProvider<RunMinecraftTask> taskRunServer;

    private final File binaryPatchedMcLocation;
    private final TaskProvider<BinaryPatchJarTask> taskInstallBinaryPatchedVersion;
    private final File srgBinaryPatchedMcLocation;
    private final TaskProvider<DeobfuscateTask> taskSrgifyBinaryPatchedVersion;
    private final TaskProvider<RunMinecraftTask> taskRunObfClient;
    private final TaskProvider<RunMinecraftTask> taskRunObfServer;
    private final Configuration obfRuntimeClasspathConfiguration;
    private final Configuration reobfJarConfiguration;
    private final TaskProvider<InjectTagsTask> taskInjectTags;
    private final SourceSet injectedSourceSet;
    private final File injectedSourcesLocation;
    public static final String PATCHED_MINECRAFT_CONFIGURATION_NAME = "patchedMinecraft";

    @SuppressWarnings({ "rawtypes", "unchecked" })
    public MCPTasks(Project project, MinecraftExtension mcExt, MinecraftTasks mcTasks) {
        super(project, mcExt, mcTasks);

        forgeUniversalConfiguration = project.getConfigurations().create("forgeUniversal");
        forgeUniversalConfiguration.setCanBeConsumed(false);

        // Extract specific factories instead of using `project` to avoid serializing Project in the configuration
        // cache.
        final ObjectFactory objects = project.getObjects();
        final FileSystemOperations fso = mcExt.getFileSystemOperations();
        final ProviderFactory providers = mcExt.getProviderFactory();
        final ArchiveOperations archives = mcExt.getArchiveOperations();
        final ProjectLayout layout = mcExt.getProjectLayout();
        final Provider<RfgCacheService> rfgCacheService = RfgCacheService.lazyAccess(project.getGradle());
        // TODO: Make all users of this into providers
        final File buildDir = layout.getBuildDirectory().get().getAsFile();

        project.afterEvaluate(this::afterEvaluate);

        deobfuscationATs = project.getObjects().fileCollection();

        decompiledMcChain = new JarChain();

        this.patchedConfiguration = project.getConfigurations().create(PATCHED_MINECRAFT_CONFIGURATION_NAME);
        this.patchedConfiguration.extendsFrom(mcTasks.getVanillaMcConfiguration());
        this.patchedConfiguration.setDescription("Dependencies needed to run modded minecraft");
        this.patchedConfiguration.setCanBeConsumed(false);

        mergedVanillaJarLocation = FileUtils.getFile(buildDir, RFG_DIR, "vanilla_merged_minecraft.jar");
        taskMergeVanillaSidedJars = project.getTasks()
                .register("mergeVanillaSidedJars", MergeSidedJarsTask.class, task -> {
                    task.setGroup(TASK_GROUP_INTERNAL);
                    task.dependsOn(mcTasks.getTaskDownloadVanillaJars());
                    final File mergedVanillaJar = mergedVanillaJarLocation;
                    task.onlyIf(t -> !mergedVanillaJar.exists());
                    task.getClientJar().set(mcTasks.getVanillaClientLocation());
                    task.getServerJar().set(mcTasks.getVanillaServerLocation());
                    task.getOutputJar().set(mergedVanillaJar);
                    task.getMergeConfigFile().set(
                            mcExt.getMinorMcVersion()
                                    .flatMap(ver -> (ver <= 8) ? userdevFile("conf/mcp_merge.cfg") : null));
                    task.getMergeConfig()
                            .set(mcExt.getMinorMcVersion().map(ver -> (ver <= 8) ? null : Constants.FG23_MERGE_CONFIG));
                    task.getMcVersion().set(mcExt.getMcVersion());
                });
        decompiledMcChain.addTask(taskMergeVanillaSidedJars);

        final File extractedDependencyAts = FileUtils.getFile(buildDir, RFG_DIR, "dependency_at.cfg");
        taskExtractDependencyATs = project.getTasks()
                .register("extractDependencyATs", ExtractDependencyATsTask.class, task -> {
                    task.setGroup(TASK_GROUP_INTERNAL);
                    task.onlyIf(
                            "useDependencyAccessTransformers set in minecraft{}",
                            _t -> mcExt.getUseDependencyAccessTransformers().get());
                    task.getDependencies().from(
                            mcExt.getUseDependencyAccessTransformers().map(
                                    use -> use ? mcExt.getDependenciesForAccessTransformerScan() : project.files()));
                    task.getOutputFile().set(extractedDependencyAts);
                });
        final Provider<FileCollection> extractedDependencyATs = mcExt.getUseDependencyAccessTransformers().map(
                use -> use ? layout.files(taskExtractDependencyATs.flatMap(ExtractDependencyATsTask::getOutputFile))
                        : layout.files());

        srgMergedJarLocation = FileUtils.getFile(buildDir, RFG_DIR, "srg_merged_minecraft.jar");
        taskDeobfuscateMergedJarToSrg = project.getTasks()
                .register("deobfuscateMergedJarToSrg", DeobfuscateTask.class, task -> {
                    task.setGroup(TASK_GROUP_INTERNAL);
                    task.dependsOn(taskMergeVanillaSidedJars, taskGenerateForgeSrgMappings, taskExtractDependencyATs);
                    task.getSrgFile().set(taskGenerateForgeSrgMappings.flatMap(GenSrgMappingsTask::getNotchToSrg));
                    task.getExceptorJson().set(
                            mcExt.getMinorMcVersion().flatMap(
                                    ver -> (ver <= 8) ? userdevFile("conf/exceptor.json") : mcpFile("exceptor.json")));
                    task.getExceptorCfg().set(taskGenerateForgeSrgMappings.flatMap(GenSrgMappingsTask::getSrgExc));
                    task.getInputJar().set(taskMergeVanillaSidedJars.flatMap(IJarOutputTask::getOutputJar));
                    task.getOutputJar().set(srgMergedJarLocation);
                    // No fields or methods CSV - passing them in causes ATs to not successfully apply
                    task.getIsApplyingMarkers().set(true);
                    // Configured in afterEvaluate()
                    task.getAccessTransformerFiles().setFrom(deobfuscationATs, extractedDependencyATs);
                    task.getMinorMcVersion().set(mcExt.getMinorMcVersion());
                });
        decompiledMcChain.addTask(taskDeobfuscateMergedJarToSrg);

        decompiledSrgLocation = FileUtils.getFile(buildDir, RFG_DIR, "srg_merged_minecraft-sources.jar");
        final File rawDecompiledSrgLocation = FileUtils
                .getFile(buildDir, RFG_DIR, "srg_merged_minecraft-sources-rawff.jar");
        taskDecompileSrgJar = project.getTasks().register("decompileSrgJar", DecompileTask.class, task -> {
            task.setGroup(TASK_GROUP_INTERNAL);
            task.dependsOn(taskDeobfuscateMergedJarToSrg, taskDownloadFernflower);
            task.getInputJar().set(taskDeobfuscateMergedJarToSrg.flatMap(IJarOutputTask::getOutputJar));
            task.getOutputJar().set(rawDecompiledSrgLocation);
            task.getCacheDir().set(Utilities.getCacheDir(project, "fernflower-cache"));
            task.getFernflower()
                    .set(layout.file(mcExt.getMinorMcVersion().map(mcVer -> (mcVer <= 8) ? fernflowerLocation : null)));
            task.getMinorMcVersion().set(mcExt.getMinorMcVersion());
            task.getClasspath().from(patchedConfiguration.plus(mcTasks.getLwjgl2Configuration()));
            task.getJava8Launcher().set(mcExt.getToolchainLauncher(project, 8));
            task.getJava17Launcher().set(mcExt.getToolchainLauncher(project, 17));
            task.getCacheService().set(rfgCacheService);
            task.usesService(rfgCacheService);
        });
        decompiledMcChain.addTask(taskDecompileSrgJar);
        taskCleanupDecompSrgJar = project.getTasks()
                .register("cleanupDecompSrgJar", CleanupDecompiledJarTask.class, task -> {
                    task.setGroup(TASK_GROUP_INTERNAL);
                    task.dependsOn(taskDecompileSrgJar);
                    task.getInputJar().set(taskDecompileSrgJar.flatMap(IJarOutputTask::getOutputJar));
                    task.getOutputJar().set(decompiledSrgLocation);
                    task.getPatches().set(
                            mcExt.getMinorMcVersion().flatMap(
                                    mcVer -> (mcVer <= 8) ? userdevDir("conf/minecraft_ff")
                                            : mcpDir("patches/minecraft_merged_ff/")));
                    task.getAstyleConfig().set(
                            mcExt.getMinorMcVersion().flatMap(
                                    mcVer -> (mcVer <= 8) ? userdevFile("conf/astyle.cfg") : mcpFile("astyle.cfg")));
                    task.getMinorMcVersion().set(mcExt.getMinorMcVersion());
                    task.getPatchesInjectDir().set(
                            mcExt.getMinorMcVersion()
                                    .flatMap(mcVer -> (mcVer <= 8) ? null : mcpDir("patches/inject/")));
                });
        decompiledMcChain.addTask(taskCleanupDecompSrgJar);

        patchedSourcesLocation = FileUtils.getFile(buildDir, RFG_DIR, "srg_patched_minecraft-sources.jar");
        taskPatchDecompiledJar = project.getTasks().register("patchDecompiledJar", PatchSourcesTask.class, task -> {
            task.setGroup(TASK_GROUP_INTERNAL);
            task.dependsOn(taskCleanupDecompSrgJar);
            task.getInputJar().set(taskCleanupDecompSrgJar.flatMap(IJarOutputTask::getOutputJar));
            task.getOutputJar().set(patchedSourcesLocation);
            task.getMaxFuzziness().set(1);
            task.getPathComponentsToStrip().set(mcExt.getMinorMcVersion().map(mcVer -> (mcVer <= 8) ? 3 : 1));
        });
        decompiledMcChain.addTask(taskPatchDecompiledJar);

        remappedSourcesLocation = FileUtils.getFile(buildDir, RFG_DIR, "mcp_patched_minecraft-sources.jar");
        taskRemapDecompiledJar = project.getTasks().register("remapDecompiledJar", RemapSourceJarTask.class, task -> {
            task.setGroup(TASK_GROUP_INTERNAL);
            task.dependsOn(taskPatchDecompiledJar);
            task.getBinaryJar().set(taskDecompileSrgJar.flatMap(IJarTransformTask::getInputJar));
            task.getInputJar().set(taskPatchDecompiledJar.flatMap(IJarOutputTask::getOutputJar));
            task.getOutputJar().set(remappedSourcesLocation);
            task.getFieldCsv().set(taskGenerateForgeSrgMappings.flatMap(GenSrgMappingsTask::getFieldsCsv));
            task.getMethodCsv().set(taskGenerateForgeSrgMappings.flatMap(GenSrgMappingsTask::getMethodsCsv));
            task.getParamCsv().set(mcpFile("params.csv"));
            task.getExtraParamsCsvs().setFrom(mcExt.getExtraParamsCsvs());
            task.getGenericFieldsCsvName().set(mcExt.getInjectMissingGenerics().flatMap(enabled -> {
                if (enabled) {
                    return mcExt.getMcVersion().map(mcv -> "genericFields-" + mcv + ".csv");
                } else {
                    return providers.provider(() -> null);
                }
            }));
            task.getAddJavadocs().set(true);
        });
        decompiledMcChain.addTask(taskRemapDecompiledJar);
        decompiledMcChain.finish();

        decompressedSourcesLocation = FileUtils.getFile(buildDir, RFG_DIR, "minecraft-src");
        taskDecompressDecompiledSources = project.getTasks()
                .register("decompressDecompiledSources", Copy.class, task -> {
                    task.setGroup(TASK_GROUP_INTERNAL);
                    task.dependsOn(taskRemapDecompiledJar);
                    task.from(
                            archives.zipTree(taskRemapDecompiledJar.flatMap(IJarOutputTask::getOutputJar)),
                            subset -> { subset.include("**/*.java"); });
                    task.from(
                            archives.zipTree(taskRemapDecompiledJar.flatMap(IJarOutputTask::getOutputJar)),
                            subset -> { subset.exclude("**/*.java"); });
                    task.eachFile(
                            fcd -> {
                                fcd.setRelativePath(
                                        fcd.getRelativePath()
                                                .prepend(fcd.getName().endsWith(".java") ? "java" : "resources"));
                            });
                    task.into(decompressedSourcesLocation);
                });

        final SourceSetContainer sourceSets = project.getExtensions().getByType(SourceSetContainer.class);
        final JavaPluginExtension javaExt = project.getExtensions().getByType(JavaPluginExtension.class);

        patchedMcSources = sourceSets.create(SOURCE_SET_PATCHED_MC, sourceSet -> {
            sourceSet.setCompileClasspath(patchedConfiguration.plus(mcTasks.getLwjgl2Configuration()));
            sourceSet.setRuntimeClasspath(patchedConfiguration);
            sourceSet.java(
                    java -> java.setSrcDirs(
                            objects.fileCollection().from(new File(decompressedSourcesLocation, "java"))
                                    .builtBy(taskDecompressDecompiledSources)));
            sourceSet.resources(
                    java -> java.setSrcDirs(
                            objects.fileCollection().from(new File(decompressedSourcesLocation, "resources"))
                                    .builtBy(taskDecompressDecompiledSources)));
        });

        final SourceSet mainSet = sourceSets.getByName(SourceSet.MAIN_SOURCE_SET_NAME);
        final SourceSet testSet = sourceSets.getByName(SourceSet.TEST_SOURCE_SET_NAME);
        final Configuration cfgApi = project.getConfigurations().getByName("api");
        final Configuration cfgCompileOnlyApi = project.getConfigurations().getByName("compileOnlyApi");
        final SourceSet apiSet = javaExt.getSourceSets().create("api", set -> {
            set.setCompileClasspath(
                    set.getCompileClasspath().plus(patchedConfiguration).plus(patchedMcSources.getOutput())
                            .plus(mcTasks.getLwjgl2Configuration()));
            set.setRuntimeClasspath(
                    set.getRuntimeClasspath().plus(patchedConfiguration).plus(mcTasks.getLwjgl2Configuration()));
        });
        mainSet.setCompileClasspath(mainSet.getCompileClasspath().plus(apiSet.getOutput()));
        mainSet.setRuntimeClasspath(mainSet.getRuntimeClasspath().plus(apiSet.getOutput()));
        testSet.setCompileClasspath(testSet.getCompileClasspath().plus(apiSet.getOutput()));
        testSet.setRuntimeClasspath(testSet.getRuntimeClasspath().plus(apiSet.getOutput()));
        final Configuration apiCompileCfg = project.getConfigurations()
                .getByName(apiSet.getCompileClasspathConfigurationName());
        final Configuration apiRuntimeCfg = project.getConfigurations()
                .getByName(apiSet.getRuntimeClasspathConfigurationName());
        apiCompileCfg.extendsFrom(cfgApi).extendsFrom(cfgCompileOnlyApi);
        apiRuntimeCfg.extendsFrom(cfgApi).extendsFrom(cfgCompileOnlyApi);

        taskBuildPatchedMc = project.getTasks()
                .named(patchedMcSources.getCompileJavaTaskName(), JavaCompile.class, task -> {
                    task.setGroup(TASK_GROUP_INTERNAL);
                    task.dependsOn(taskDecompressDecompiledSources);
                    configureMcJavaCompilation(project, task);
                });
        project.getTasks().named(patchedMcSources.getProcessResourcesTaskName())
                .configure(task -> task.dependsOn(taskDecompressDecompiledSources));

        packagedMcLocation = FileUtils.getFile(buildDir, RFG_DIR, "recompiled_minecraft.jar");
        taskPackagePatchedMc = project.getTasks().register("packagePatchedMc", Jar.class, task -> {
            task.setGroup(TASK_GROUP_INTERNAL);
            task.dependsOn(taskBuildPatchedMc, taskDecompressDecompiledSources, patchedMcSources.getClassesTaskName());
            task.getArchiveVersion().set(mcExt.getMcVersion());
            task.getArchiveBaseName().set(StringUtils.removeEnd(packagedMcLocation.getName(), ".jar"));
            task.getDestinationDirectory().set(packagedMcLocation.getParentFile());
            task.from(patchedMcSources.getOutput());
        });

        launcherSourcesLocation = FileUtils.getFile(buildDir, RFG_DIR, "launcher-src");
        taskCreateLauncherFiles = project.getTasks()
                .register("createMcLauncherFiles", CreateLauncherFiles.class, task -> {
                    task.setGroup(TASK_GROUP_INTERNAL);
                    task.dependsOn(mcTasks.getTaskExtractNatives(mcExt.getMainLwjglVersion()));
                    task.getOutputDir().set(launcherSourcesLocation);

                    task.addResources("fg[0-9]+/", mcExt.getMinorMcVersion().map(mcMinor -> {
                        if (mcMinor <= 8) {
                            return Arrays.asList(
                                    "fg12/GradleStart.java",
                                    "fg12/GradleStartServer.java",
                                    "fg12/net/minecraftforge/gradle/GradleStartCommon.java",
                                    "fg12/net/minecraftforge/gradle/OldPropertyMapSerializer.java",
                                    "fg12/net/minecraftforge/gradle/tweakers/CoremodTweaker.java",
                                    "fg12/net/minecraftforge/gradle/tweakers/AccessTransformerTweaker.java");
                        } else {
                            return Arrays.asList(
                                    "fg23/GradleStart.java",
                                    "fg23/GradleStartServer.java",
                                    "fg23/net/minecraftforge/gradle/GradleStartCommon.java",
                                    "fg23/net/minecraftforge/gradle/GradleForgeHacks.java",
                                    "fg23/net/minecraftforge/gradle/tweakers/CoremodTweaker.java",
                                    "fg23/net/minecraftforge/gradle/tweakers/AccessTransformerTweaker.java");
                        }
                    }));

                    MapProperty<String, String> replacements = task.getReplacementTokens();
                    replacements.put("@@MCVERSION@@", mcExt.getMcVersion());
                    replacements.put("@@ASSETINDEX@@", mcExt.getMcVersion());
                    replacements.put("@@ASSETSDIR@@", mcTasks.getVanillaAssetsLocation().getPath());
                    replacements.put("@@NATIVESDIR@@", mcTasks.getLwjgl2NativesDirectory().getPath());
                    replacements.put("@@SRGDIR@@", getSrgLocation().map(d -> d.getAsFile().getPath()));
                    replacements.put(
                            "@@SRG_NOTCH_SRG@@",
                            taskGenerateForgeSrgMappings.flatMap(GenSrgMappingsTask::getNotchToSrg)
                                    .map(RegularFile::getAsFile).map(File::getPath));
                    replacements.put(
                            "@@SRG_NOTCH_MCP@@",
                            taskGenerateForgeSrgMappings.flatMap(GenSrgMappingsTask::getNotchToMcp)
                                    .map(RegularFile::getAsFile).map(File::getPath));
                    replacements.put(
                            "@@SRG_SRG_MCP@@",
                            taskGenerateForgeSrgMappings.flatMap(GenSrgMappingsTask::getSrgToMcp)
                                    .map(RegularFile::getAsFile).map(File::getPath));
                    replacements.put(
                            "@@SRG_MCP_SRG@@",
                            taskGenerateForgeSrgMappings.flatMap(GenSrgMappingsTask::getMcpToSrg)
                                    .map(RegularFile::getAsFile).map(File::getPath));
                    replacements.put(
                            "@@SRG_MCP_NOTCH@@",
                            taskGenerateForgeSrgMappings.flatMap(GenSrgMappingsTask::getMcpToNotch)
                                    .map(RegularFile::getAsFile).map(File::getPath));
                    replacements.put(
                            "@@CSVDIR@@",
                            taskGenerateForgeSrgMappings.flatMap(GenSrgMappingsTask::getFieldsCsv)
                                    .map(f -> f.getAsFile().getParentFile().getPath()));
                    replacements.putAll(mcExt.getMinorMcVersion().map(mcMinor -> {
                        if (mcMinor <= 8) {
                            return ImmutableMap.of(
                                    "@@BOUNCERCLIENT@@",
                                    "net.minecraft.launchwrapper.Launch",
                                    "@@BOUNCERSERVER@@",
                                    "net.minecraft.launchwrapper.Launch",
                                    "@@CLIENTTWEAKER@@",
                                    "cpw.mods.fml.common.launcher.FMLTweaker",
                                    "@@SERVERTWEAKER@@",
                                    "cpw.mods.fml.common.launcher.FMLServerTweaker");
                        } else {
                            return ImmutableMap.of(
                                    "@@BOUNCERCLIENT@@",
                                    "net.minecraft.launchwrapper.Launch",
                                    "@@BOUNCERSERVER@@",
                                    "net.minecraft.launchwrapper.Launch",
                                    "@@TWEAKERCLIENT@@",
                                    "net.minecraftforge.fml.common.launcher.FMLTweaker",
                                    "@@TWEAKERSERVER@@",
                                    "net.minecraftforge.fml.common.launcher.FMLServerTweaker",
                                    "//@@EXTRALINES@@",
                                    "net.minecraftforge.gradle.GradleForgeHacks.searchCoremods(this);",
                                    "// @@EXTRALINES@@",
                                    "net.minecraftforge.gradle.GradleForgeHacks.searchCoremods(this);");
                        }
                    }));
                });

        launcherSources = sourceSets.create(SOURCE_SET_LAUNCHER, sourceSet -> {
            sourceSet.setCompileClasspath(patchedConfiguration);
            sourceSet.setRuntimeClasspath(patchedConfiguration);
            sourceSet.getJava().setSrcDirs(
                    objects.fileCollection().from(launcherSourcesLocation).builtBy(taskCreateLauncherFiles));
        });
        javaExt.getSourceSets().add(launcherSources);
        project.getTasks().named("compileMcLauncherJava", JavaCompile.class, task -> {
            task.setGroup(TASK_GROUP_INTERNAL);
            task.dependsOn(taskCreateLauncherFiles);
            configureMcJavaCompilation(project, task);
        });

        packagedMcLauncherLocation = FileUtils.getFile(buildDir, RFG_DIR, "mclauncher.jar");
        final TaskProvider<?> launcherSourcesClasses = project.getTasks().named(launcherSources.getClassesTaskName());
        taskPackageMcLauncher = project.getTasks().register("packageMcLauncher", Jar.class, task -> {
            task.setGroup(TASK_GROUP_INTERNAL);
            task.dependsOn(taskCreateLauncherFiles, launcherSources.getClassesTaskName());
            task.getArchiveVersion().set(mcExt.getMcVersion());
            task.getArchiveBaseName().set(StringUtils.removeEnd(packagedMcLauncherLocation.getName(), ".jar"));
            task.getDestinationDirectory().set(packagedMcLauncherLocation.getParentFile());

            task.from(launcherSources.getOutput());
            task.from(launcherSourcesClasses);
        });

        injectedSourcesLocation = FileUtils.getFile(buildDir, "generated", "sources", "injectTags");
        taskInjectTags = project.getTasks().register("injectTags", InjectTagsTask.class, task -> {
            task.setGroup(TASK_GROUP_INTERNAL);
            task.getOutputDir().set(injectedSourcesLocation);
            task.getTags().set(mcExt.getInjectedTags());
        });
        injectedSourceSet = sourceSets.create(
                "injectedTags",
                set -> {
                    set.getJava()
                            .setSrcDirs(objects.fileCollection().from(injectedSourcesLocation).builtBy(taskInjectTags));
                });
        project.getTasks().named(injectedSourceSet.getCompileJavaTaskName())
                .configure(task -> task.dependsOn(taskInjectTags));
        final FileCollection mcCp = launcherSources.getOutput().plus(patchedMcSources.getOutput());
        final FileCollection extraCp = mcCp.plus(injectedSourceSet.getOutput());
        mainSet.setCompileClasspath(mainSet.getCompileClasspath().plus(extraCp));
        mainSet.setRuntimeClasspath(mainSet.getRuntimeClasspath().plus(extraCp));
        testSet.setCompileClasspath(testSet.getCompileClasspath().plus(extraCp));
        testSet.setRuntimeClasspath(testSet.getRuntimeClasspath().plus(extraCp));
        project.getTasks().named("jar", Jar.class)
                .configure(task -> task.from(injectedSourceSet.getOutput().getAsFileTree()));

        // A dummy source set to satisfy IntelliJ native launch configurations
        final SourceSet ideMainSet = sourceSets.create("ideVirtualMain", sourceSet -> {
            project.getConfigurations().named(sourceSet.getImplementationConfigurationName()).configure(ideConfig -> {
                ideConfig.extendsFrom(patchedConfiguration);
                ideConfig.extendsFrom(mcTasks.getLwjgl2Configuration());
                ideConfig.extendsFrom(
                        project.getConfigurations().getByName(JavaPlugin.RUNTIME_CLASSPATH_CONFIGURATION_NAME));
            });

            final ConfigurableFileCollection classpath = objects.fileCollection();
            classpath.from(patchedMcSources.getOutput());
            classpath.from(launcherSources.getOutput());
            classpath.from(mainSet.getOutput());
            classpath.from(apiSet.getOutput());

            sourceSet.setCompileClasspath(classpath.plus(sourceSet.getCompileClasspath()));
            sourceSet.setRuntimeClasspath(classpath.plus(sourceSet.getRuntimeClasspath()));
        });
        project.getTasks().configureEach(task -> {
            if (task.getName().toLowerCase(Locale.ROOT).contains("idevirtualmain")) {
                task.setEnabled(false);
                task.dependsOn(taskPackagePatchedMc, taskPackageMcLauncher);
            }
        });

        taskRunClient = project.getTasks().register("runClient", RunMinecraftTask.class, Distribution.CLIENT);
        final TaskProvider<?> taskJar = project.getTasks().named("jar");
        final Configuration runtimeClasspathCfg = project.getConfigurations()
                .getByName(JavaPlugin.RUNTIME_CLASSPATH_CONFIGURATION_NAME);
        final Configuration compileClasspathCfg = project.getConfigurations()
                .getByName(JavaPlugin.COMPILE_CLASSPATH_CONFIGURATION_NAME);
        taskRunClient.configure(task -> {
            task.setup(project);
            task.setGroup(TASK_GROUP_USER);
            task.setDescription("Runs the deobfuscated client with your mod");
            task.dependsOn(
                    launcherSources.getClassesTaskName(),
                    mcTasks.getTaskDownloadVanillaAssets(),
                    taskPackagePatchedMc,
                    "jar");

            task.getUsername().set(mcExt.getUsername());
            task.getUserUUID().set(mcExt.getUserUUID());
            task.classpath(taskPackageMcLauncher);
            task.classpath(taskPackagePatchedMc);
            task.classpath(patchedConfiguration);
            task.classpath(taskJar);
            task.classpath(runtimeClasspathCfg);
            task.getMainClass().set("GradleStart");
        });

        taskRunServer = project.getTasks().register("runServer", RunMinecraftTask.class, Distribution.DEDICATED_SERVER);
        taskRunServer.configure(task -> {
            task.setup(project);
            task.setGroup(TASK_GROUP_USER);
            task.setDescription("Runs the deobfuscated server with your mod");
            task.dependsOn(launcherSources.getClassesTaskName(), taskPackagePatchedMc, "classes");

            task.getUsername().set(mcExt.getUsername());
            task.getUserUUID().set(mcExt.getUserUUID());
            task.classpath(taskPackageMcLauncher);
            task.classpath(taskPackagePatchedMc);
            task.classpath(patchedConfiguration);
            task.classpath(taskJar);
            task.classpath(runtimeClasspathCfg);
            task.getMainClass().set("GradleStartServer");
        });

        // The default jar is deobfuscated, specify the correct classifier for it
        project.getTasks().named("jar", Jar.class).configure(task -> { task.getArchiveClassifier().set("dev"); });
        project.getConfigurations().configureEach(cfg -> {
            // Use MCP by default for every configuration
            cfg.getAttributes()
                    .attribute(ObfuscationAttribute.OBFUSCATION_ATTRIBUTE, ObfuscationAttribute.getMcp(objects));
        });

        // Add a reobfuscation task rule
        project.getTasks().addRule("Pattern: reobf<JarTaskName>: Reobfuscate a modded jar", taskName -> {
            if (!taskName.startsWith("reobf")) {
                return;
            }
            final String subjectTaskName = StringUtils.uncapitalize(StringUtils.removeStart(taskName, "reobf"));
            final TaskProvider<Jar> subjectTask;
            try {
                subjectTask = project.getTasks().named(subjectTaskName, Jar.class);
            } catch (UnknownTaskException ute) {
                project.getLogger()
                        .warn("Couldn't find a Jar task named " + subjectTaskName + " for automatic reobfuscation");
                return;
            }
            project.getTasks().register(taskName, ReobfuscatedJar.class, task -> {
                task.setGroup(TASK_GROUP_USER);
                task.setDescription("Reobfuscate the output of the `" + subjectTaskName + "` task to SRG mappings");
                task.dependsOn(taskGenerateForgeSrgMappings, taskPackagePatchedMc, subjectTask);

                task.setInputJarFromTask(subjectTask);
                task.getMcVersion().set(mcExt.getMcVersion());
                task.getSrg().set(taskGenerateForgeSrgMappings.flatMap(GenSrgMappingsTask::getMcpToSrg));
                task.getFieldCsv().set(taskGenerateForgeSrgMappings.flatMap(GenSrgMappingsTask::getFieldsCsv));
                task.getMethodCsv().set(taskGenerateForgeSrgMappings.flatMap(GenSrgMappingsTask::getMethodsCsv));
                task.getExceptorCfg().set(taskGenerateForgeSrgMappings.flatMap(GenSrgMappingsTask::getSrgExc));
                task.getRecompMcJar().set(taskPackagePatchedMc.flatMap(Jar::getArchiveFile));
                task.getReferenceClasspath().from(runtimeClasspathCfg);
                final ConfigurableFileCollection refCp = task.getReferenceClasspath();
                refCp.from(taskPackageMcLauncher);
                refCp.from(taskPackagePatchedMc);
                refCp.from(patchedConfiguration);
                refCp.from(runtimeClasspathCfg);
                refCp.from(compileClasspathCfg);
            });
        });

        // Initialize a reobf task for the default jar
        final TaskProvider<ReobfuscatedJar> taskReobfJar = project.getTasks().named("reobfJar", ReobfuscatedJar.class);
        if (project.getTasks().getNames().contains(LifecycleBasePlugin.ASSEMBLE_TASK_NAME)) {
            project.getTasks().named(LifecycleBasePlugin.ASSEMBLE_TASK_NAME)
                    .configure(task -> task.dependsOn(taskReobfJar));
        }
        reobfJarConfiguration = project.getConfigurations().create("reobfJarConfiguration");
        {
            // Based on org.gradle.api.plugins.internal.JvmPluginsHelper.configureDocumentationVariantWithArtifact
            reobfJarConfiguration.setVisible(true);
            reobfJarConfiguration.setCanBeConsumed(false);
            reobfJarConfiguration.setCanBeResolved(true);
            reobfJarConfiguration.setDescription("Reobfuscated jar");

            project.afterEvaluate(p -> {
                final DependencyHandler deps = p.getDependencies();
                final Configuration parentUnresolved = p.getConfigurations()
                        .getByName(JavaPlugin.RUNTIME_ELEMENTS_CONFIGURATION_NAME);
                final Configuration parentResolved = p.getConfigurations().create("reobfJarInputConfiguration", cfg -> {
                    cfg.setCanBeConsumed(false);
                    cfg.setCanBeResolved(true);
                    cfg.getDependencies().addAll(parentUnresolved.getAllDependencies());
                });
                final AttributeContainer parentAttrs = parentResolved.getAttributes();
                parentAttrs.attribute(ObfuscationAttribute.OBFUSCATION_ATTRIBUTE, ObfuscationAttribute.getMcp(objects));
                parentAttrs.attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.class, Usage.JAVA_RUNTIME));
                parentAttrs.attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.class, Category.LIBRARY));
                parentAttrs.attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.class, Bundling.EXTERNAL));

                reobfJarConfiguration.withDependencies(depset -> {
                    parentResolved.resolve();
                    final Set<String> excludedGroups = mcExt.getGroupsToExcludeFromAutoReobfMapping().get();
                    final HashSet<ResolvedDependency> visited = new HashSet<>();
                    final Deque<ResolvedDependency> toVisit = new ArrayDeque<>(64);
                    toVisit.addAll(parentResolved.getResolvedConfiguration().getFirstLevelModuleDependencies());
                    while (!toVisit.isEmpty()) {
                        final ResolvedDependency dep = toVisit.removeFirst();
                        if (!visited.add(dep) || dep.getModuleGroup() == null || dep.getModuleGroup().isEmpty()) {
                            continue;
                        }
                        if (excludedGroups.contains(dep.getModuleGroup())) {
                            continue;
                        }
                        toVisit.addAll(dep.getChildren());

                        ModuleDependency mDep = (ModuleDependency) deps.create(
                                ImmutableMap.of(
                                        "group",
                                        dep.getModuleGroup(),
                                        "name",
                                        dep.getModuleName(),
                                        "version",
                                        dep.getModuleVersion()));
                        // The artifacts only exist for dependencies with a classifier (eg :dev)
                        mDep.getArtifacts().clear();
                        for (ResolvedArtifact artifact : dep.getModuleArtifacts()) {
                            String classifier = artifact.getClassifier();
                            if ("dev".equalsIgnoreCase(classifier) || "deobf".equalsIgnoreCase(classifier)) {
                                classifier = "";
                            }
                            if ("api".equalsIgnoreCase(classifier)) {
                                continue;
                            }
                            final String resultingClassifier = classifier;
                            mDep.artifact(da -> {
                                da.setName(dep.getModuleName());
                                da.setType(artifact.getType());
                                da.setClassifier(resultingClassifier);
                                da.setExtension(artifact.getExtension());
                            });
                        }
                        mDep.setTransitive(false);
                        depset.add(mDep);
                    }
                });
            });
            final AttributeContainer attributes = reobfJarConfiguration.getAttributes();
            attributes.attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.class, Usage.JAVA_RUNTIME));
            attributes.attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.class, Category.LIBRARY));
            attributes.attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.class, Bundling.EXTERNAL));
            project.afterEvaluate(
                    p -> {
                        attributes.attribute(
                                TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE,
                                mcExt.getJavaCompatibilityVersion().get());
                    });
            attributes.attribute(
                    LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE,
                    objects.named(LibraryElements.class, LibraryElements.JAR));
            attributes.attribute(ObfuscationAttribute.OBFUSCATION_ATTRIBUTE, ObfuscationAttribute.getSrg(objects));
            project.getArtifacts().add(reobfJarConfiguration.getName(), taskReobfJar);

            final Configuration reobfElements = project.getConfigurations().create("reobfElements");
            reobfElements.setCanBeResolved(false);
            reobfElements.setCanBeConsumed(true);
            reobfElements.getOutgoing().artifact(taskReobfJar);
            attributes.keySet().forEach(
                    attr -> reobfElements.getAttributes().attribute((Attribute) attr, attributes.getAttribute(attr)));

            SoftwareComponent javaComponent = project.getComponents().getByName("java");
            if (javaComponent instanceof AdhocComponentWithVariants) {
                AdhocComponentWithVariants java = (AdhocComponentWithVariants) javaComponent;
                java.addVariantsFromConfiguration(reobfElements, ConfigurationVariantDetails::mapToOptional);
            }
        }

        binaryPatchedMcLocation = FileUtils.getFile(buildDir, RFG_DIR, "binpatchedmc.jar");
        taskInstallBinaryPatchedVersion = project.getTasks()
                .register("installBinaryPatchedVersion", BinaryPatchJarTask.class, task -> {
                    task.setGroup(TASK_GROUP_INTERNAL);
                    task.dependsOn(taskMergeVanillaSidedJars);
                    task.setDescription("currently unused");
                    task.getInputJar().set(taskMergeVanillaSidedJars.flatMap(IJarOutputTask::getOutputJar));
                    task.getOutputJar().set(binaryPatchedMcLocation);
                    task.getPatchesLzma().set(userdevFile("devbinpatches.pack.lzma"));
                    task.getExtraClassesJar().set(userdevFile("binaries.jar"));
                    task.getExtraResourcesTree().from(userdevDir("src/main/resources"));
                });

        srgBinaryPatchedMcLocation = FileUtils.getFile(buildDir, RFG_DIR, "srg_binpatchedmc.jar");
        taskSrgifyBinaryPatchedVersion = project.getTasks()
                .register("srgifyBinpatchedJar", DeobfuscateTask.class, task -> {
                    task.setGroup(TASK_GROUP_INTERNAL);
                    task.dependsOn(taskInstallBinaryPatchedVersion, taskGenerateForgeSrgMappings);
                    task.setDescription("currently unused");
                    task.getSrgFile().set(taskGenerateForgeSrgMappings.flatMap(GenSrgMappingsTask::getNotchToSrg));
                    task.getExceptorJson().set(userdevFile("conf/exceptor.json"));
                    task.getExceptorCfg().set(taskGenerateForgeSrgMappings.flatMap(GenSrgMappingsTask::getSrgExc));
                    task.getInputJar().set(taskInstallBinaryPatchedVersion.flatMap(IJarOutputTask::getOutputJar));
                    task.getOutputJar().set(srgBinaryPatchedMcLocation);
                    task.getFieldCsv().set(taskGenerateForgeSrgMappings.flatMap(GenSrgMappingsTask::getFieldsCsv));
                    task.getMethodCsv().set(taskGenerateForgeSrgMappings.flatMap(GenSrgMappingsTask::getMethodsCsv));
                    task.getIsApplyingMarkers().set(true);
                    // Configured in afterEvaluate()
                    task.getAccessTransformerFiles().setFrom(deobfuscationATs, extractedDependencyATs);
                    task.getMinorMcVersion().set(mcExt.getMinorMcVersion());
                });

        obfRuntimeClasspathConfiguration = project.getConfigurations().create("obfuscatedRuntimeClasspath");
        obfRuntimeClasspathConfiguration.setCanBeConsumed(false);
        obfRuntimeClasspathConfiguration.extendsFrom(reobfJarConfiguration);
        obfRuntimeClasspathConfiguration.getAttributes().attribute(
                ObfuscationAttribute.OBFUSCATION_ATTRIBUTE,
                ObfuscationAttribute.getSrg(project.getObjects()));

        final File obfRunFolder = new File(mcTasks.getRunDirectory(), "obfuscated/");
        final TaskProvider<Copy> taskPrepareObfMods = project.getTasks()
                .register("prepareObfModsFolder", Copy.class, task -> {
                    task.setGroup(TASK_GROUP_INTERNAL);
                    task.dependsOn(taskPackageMcLauncher, taskPackagePatchedMc, taskReobfJar);

                    task.from(taskReobfJar);
                    task.from(obfRuntimeClasspathConfiguration);

                    task.into(new File(obfRunFolder, "mods/"));

                    task.doFirst(t -> {
                        final File obfModsFolder = task.getDestinationDir();
                        final File[] children = obfModsFolder.listFiles();
                        if (children != null) {
                            for (final File child : children) {
                                FileUtils.deleteQuietly(child);
                            }
                        }
                    });
                });

        taskRunObfClient = project.getTasks().register("runObfClient", RunMinecraftTask.class, Distribution.CLIENT);
        taskRunObfClient.configure(task -> {
            task.setup(project);
            task.setGroup(TASK_GROUP_USER);
            task.setDescription("Runs the Forge obfuscated client with your mod");
            task.dependsOn(
                    mcTasks.getTaskDownloadVanillaJars(),
                    mcTasks.getTaskDownloadVanillaAssets(),
                    taskReobfJar,
                    taskPrepareObfMods);

            task.setWorkingDir(obfRunFolder);
            task.systemProperty("retrofuturagradle.reobfDev", true);
            task.classpath(forgeUniversalConfiguration);
            task.classpath(mcTasks.getVanillaClientLocation());
            task.classpath(patchedConfiguration);
            task.getMainClass().set("net.minecraft.launchwrapper.Launch");
            task.getTweakClasses().add(
                    mcExt.getMinorMcVersion().map(
                            v -> (v <= 7) ? "cpw.mods.fml.common.launcher.FMLTweaker"
                                    : "net.minecraftforge.fml.common.launcher.FMLTweaker"));
        });

        taskRunObfServer = project.getTasks()
                .register("runObfServer", RunMinecraftTask.class, Distribution.DEDICATED_SERVER);
        taskRunObfServer.configure(task -> {
            task.setup(project);
            task.setGroup(TASK_GROUP_USER);
            task.setDescription("Runs the Forge obfuscated server with your mod");
            task.dependsOn(mcTasks.getTaskDownloadVanillaJars(), taskReobfJar, taskPrepareObfMods);

            task.setWorkingDir(obfRunFolder);
            task.systemProperty("retrofuturagradle.reobfDev", true);
            task.classpath(forgeUniversalConfiguration);
            task.classpath(mcTasks.getVanillaServerLocation());
            task.classpath(patchedConfiguration);
            task.getMainClass().set(
                    mcExt.getMinorMcVersion().map(
                            v -> (v <= 7) ? "cpw.mods.fml.relauncher.ServerLaunchWrapper"
                                    : "net.minecraftforge.fml.relauncher.ServerLaunchWrapper"));
            task.getTweakClasses().set(Collections.emptyList());
        });

        // Mostly for compat with FG
        project.getTasks().register("setupCIWorkspace", DefaultTask.class, task -> {
            task.setGroup(TASK_GROUP_USER);
            task.setDescription("Prepares everything for mod building on a CI server");
            task.dependsOn(taskPackagePatchedMc, taskPackageMcLauncher);
        });
        project.getTasks().register("setupDecompWorkspace", DefaultTask.class, task -> {
            task.setGroup(TASK_GROUP_USER);
            task.setDescription("Prepares everything for mod building in a dev environment");
            task.dependsOn(taskPackagePatchedMc, taskPackageMcLauncher, mcTasks.getTaskDownloadVanillaAssets());
        });
    }

    public void configureMcJavaCompilation(Project project, JavaCompile task) {
        task.getModularity().getInferModulePath().set(false);
        task.getOptions().setEncoding("UTF-8");
        task.getOptions().setFork(true);
        task.getOptions().setWarnings(false);
        task.setSourceCompatibility(JavaVersion.VERSION_1_8.toString());
        task.setTargetCompatibility(JavaVersion.VERSION_1_8.toString());
        task.getJavaCompiler().set(mcExt.getToolchainCompiler(project));
    }

    private void afterEvaluate(Project project) {
        final DependencyHandler deps = project.getDependencies();

        // At afterEvaluate minecraft version should be already set and stable
        final int mcMinor = mcExt.getMinorMcVersion().get();

        if (mcExt.getSkipSlowTasks().get()) {
            taskDeobfuscateMergedJarToSrg
                    .configure(t -> t.onlyIf("skipping slow task", p -> !t.getOutputJar().get().getAsFile().exists()));
            taskDecompileSrgJar
                    .configure(t -> t.onlyIf("skipping slow task", p -> !t.getOutputJar().get().getAsFile().exists()));
            taskPatchDecompiledJar
                    .configure(t -> t.onlyIf("skipping slow task", p -> !t.getOutputJar().get().getAsFile().exists()));
        }

        // Workaround https://github.com/gradle/gradle/issues/10861 to avoid publishing these dependencies
        for (String configName : new String[] { JavaPlugin.COMPILE_CLASSPATH_CONFIGURATION_NAME,
                JavaPlugin.RUNTIME_CLASSPATH_CONFIGURATION_NAME, JavaPlugin.TEST_COMPILE_CLASSPATH_CONFIGURATION_NAME,
                JavaPlugin.TEST_RUNTIME_CLASSPATH_CONFIGURATION_NAME }) {
            project.getConfigurations().getByName(configName).extendsFrom(this.patchedConfiguration);
        }
        for (String configName : new String[] { JavaPlugin.COMPILE_CLASSPATH_CONFIGURATION_NAME,
                JavaPlugin.TEST_COMPILE_CLASSPATH_CONFIGURATION_NAME }) {
            project.getConfigurations().getByName(configName)
                    .extendsFrom(mcTasks.getLwjglConfiguration(mcExt.getMainLwjglVersion()).get());
        }

        if (mcExt.getTagReplacementFiles().isPresent() && !mcExt.getTagReplacementFiles().get().isEmpty()) {
            final File replacementPropFile = new File(injectedSourcesLocation.getParentFile(), "injectTags.resources");
            taskInjectTags.configure(task -> {
                task.getOutputs().file(replacementPropFile);
                task.doLast("Generate tag injection resource file", t -> {
                    final Properties props = new Properties();
                    int i = 0;
                    for (String pattern : mcExt.getTagReplacementFiles().get()) {
                        props.setProperty("files." + i, pattern);
                    }
                    for (Map.Entry<String, Object> value : task.getTags().get().entrySet()) {
                        props.setProperty("replacements." + value.getKey(), value.getValue().toString());
                    }
                    try (FileOutputStream fos = new FileOutputStream(replacementPropFile);
                            BufferedOutputStream bos = new BufferedOutputStream(fos)) {
                        props.store(bos, "");
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
            });
            // Inject a javac & scalac plugin
            final JavaPluginExtension javaExt = project.getExtensions().getByType(JavaPluginExtension.class);
            final Configuration rfgJavacCfg = project.getConfigurations().create("rfgJavacPlugin", cfg -> {
                cfg.setCanBeConsumed(false);
                cfg.setCanBeResolved(true);
            });
            final Dependency rfgJavacPlugin = project.getDependencies()
                    .add(rfgJavacCfg.getName(), "com.gtnewhorizons:rfg-javac-plugin:" + BuildConfig.PLUGIN_VERSION);
            project.getConfigurations().getByName("annotationProcessor").extendsFrom(rfgJavacCfg);
            final URI replacementsUri = replacementPropFile.toURI();
            project.getTasks().named("compileJava", JavaCompile.class).configure(task -> {
                task.getOptions().getCompilerArgs()
                        .add("-Xplugin:RetrofuturagradleTokenReplacement " + replacementsUri.toASCIIString());
                task.getOptions().setFork(true);
                task.getInputs().files(project.files(replacementPropFile).builtBy(taskInjectTags));
                if (javaExt.getToolchain().getLanguageVersion().get().asInt() > 8) {
                    final List<String> jargs = Arrays.asList(
                            "--add-exports",
                            "jdk.compiler/com.sun.tools.javac.model=ALL-UNNAMED",
                            "--add-exports",
                            "jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
                            "--add-exports",
                            "jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED",
                            "--add-exports",
                            "jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED",
                            "--add-exports",
                            "jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED",
                            "--add-opens",
                            "jdk.compiler/com.sun.tools.javac.main=ALL-UNNAMED",
                            "--add-opens",
                            "jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED");
                    task.getOptions().getForkOptions().getJvmArgs().addAll(jargs);
                }
            });
            if (project.getPluginManager().hasPlugin("scala")) {
                // Configure the Scala task lazily to avoid failure if it doesn't exist
                final ObjectFactory objects = mcExt.getObjectFactory();
                project.getTasks().withType(ScalaCompile.class).configureEach(task -> {
                    if (!task.getName().equals("compileScala")) {
                        return;
                    }
                    task.getInputs().files(objects.fileCollection().from(replacementPropFile).builtBy(taskInjectTags));
                    task.setScalaCompilerPlugins(task.getScalaCompilerPlugins().plus(rfgJavacCfg));
                    if (task.getScalaCompileOptions().getAdditionalParameters() == null) {
                        task.getScalaCompileOptions().setAdditionalParameters(new ArrayList<>());
                    }
                    final String scParam = "-P:RetrofuturagradleScalaTokenReplacement:"
                            + replacementsUri.toASCIIString();
                    // It can be an immutable list
                    final List<String> scArgs = new ArrayList<>(
                            task.getScalaCompileOptions().getAdditionalParameters());
                    scArgs.add(scParam);
                    task.getScalaCompileOptions().setAdditionalParameters(scArgs);
                    task.getOptions().setFork(true);
                });
            }
        }

        if (mcExt.getUsesFml().get()) {
            if (mcMinor <= 8) {
                deobfuscationATs.from(userdevFile(Constants.PATH_USERDEV_FML_ACCESS_TRANFORMER));
            }

            if (mcMinor <= 8 || mcExt.getUsesForge().get()) {
                deps.addProvider(
                        forgeUniversalConfiguration.getName(),
                        mcExt.getForgeVersion()
                                .map(forgeVer -> String.format("net.minecraftforge:forge:%s:universal", forgeVer)));

                taskPatchDecompiledJar.configure(task -> {
                    if (mcMinor <= 8) {
                        task.getPatches().from(userdevFile("fmlpatches.zip"));
                    }
                    task.getInjectionDirectories().from(userdevDir("src/main/java"));
                    task.getInjectionDirectories().from(userdevDir("src/main/resources"));
                });
            }

            final String mcVer = mcExt.getMcVersion().get();
            final String PATCHED_MC_CFG = patchedConfiguration.getName();
            // LaunchWrapper brings in its own lwjgl version which we want to override
            ((ModuleDependency) deps.add(PATCHED_MC_CFG, "net.minecraft:launchwrapper:1.12")).setTransitive(false);
            deps.add(PATCHED_MC_CFG, "com.google.code.findbugs:jsr305:1.3.9");
            // Forge brings in an incomplete set of Scala libraries, we use transitive(false) to control this.
            ((ModuleDependency) deps.add(PATCHED_MC_CFG, "com.typesafe.akka:akka-actor_2.11:2.3.3"))
                    .setTransitive(false);
            ((ModuleDependency) deps.add(PATCHED_MC_CFG, "com.typesafe:config:1.2.1")).setTransitive(false);
            ((ModuleDependency) deps.add(PATCHED_MC_CFG, "org.scala-lang:scala-actors-migration_2.11:1.1.0"))
                    .setTransitive(false);
            ((ModuleDependency) deps.add(PATCHED_MC_CFG, "org.scala-lang:scala-compiler:2.11.5")).setTransitive(false);
            ((ModuleDependency) deps
                    .add(PATCHED_MC_CFG, "org.scala-lang.plugins:scala-continuations-library_2.11:1.0.2"))
                            .setTransitive(false);
            ((ModuleDependency) deps
                    .add(PATCHED_MC_CFG, "org.scala-lang.plugins:scala-continuations-plugin_2.11.1:1.0.2"))
                            .setTransitive(false);
            ((ModuleDependency) deps.add(PATCHED_MC_CFG, "org.scala-lang:scala-library:2.11.5")).setTransitive(false);
            ((ModuleDependency) deps.add(PATCHED_MC_CFG, "org.scala-lang:scala-parser-combinators_2.11:1.0.1"))
                    .setTransitive(false);
            ((ModuleDependency) deps.add(PATCHED_MC_CFG, "org.scala-lang:scala-reflect:2.11.5")).setTransitive(false);
            ((ModuleDependency) deps.add(PATCHED_MC_CFG, "org.scala-lang:scala-swing_2.11:1.0.1")).setTransitive(false);
            ((ModuleDependency) deps.add(PATCHED_MC_CFG, "org.scala-lang:scala-xml_2.11:1.0.2")).setTransitive(false);
            deps.add(PATCHED_MC_CFG, "lzma:lzma:0.0.1");
            switch (mcVer) {
                case "1.7.10" -> {
                    deps.add(PATCHED_MC_CFG, "org.ow2.asm:asm-debug-all:5.0.3");
                }
                case "1.12.2" -> {
                    deps.add(PATCHED_MC_CFG, "org.ow2.asm:asm-debug-all:5.2");
                    deps.add(PATCHED_MC_CFG, "org.jline:jline:3.5.1");
                    deps.add(PATCHED_MC_CFG, "lzma:lzma:0.0.1");
                    deps.add(PATCHED_MC_CFG, "java3d:vecmath:1.5.2");
                    deps.add(PATCHED_MC_CFG, "net.sf.trove4j:trove4j:3.0.3");
                    deps.add(PATCHED_MC_CFG, "org.apache.maven:maven-artifact:3.5.3");
                }
                default -> throw new UnsupportedOperationException("Unsupported MC version " + mcVer);
            }

            if (mcExt.getUsesForge().get()) {
                deobfuscationATs.from(userdevDir(Constants.PATH_USERDEV_FORGE_ACCESS_TRANFORMER));

                taskPatchDecompiledJar.configure(task -> {
                    if (mcMinor <= 8) {
                        task.getPatches().from(userdevFile("forgepatches.zip"));
                    } else {
                        task.getPatches().from(userdevFile("patches.zip"));
                    }
                });
            }
        }
    }

    public TaskProvider<GenSrgMappingsTask> getTaskGenerateForgeSrgMappings() {
        return taskGenerateForgeSrgMappings;
    }

    public TaskProvider<MergeSidedJarsTask> getTaskMergeVanillaSidedJars() {
        return taskMergeVanillaSidedJars;
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

    public TaskProvider<PatchSourcesTask> getTaskPatchDecompiledJar() {
        return taskPatchDecompiledJar;
    }

    public TaskProvider<RemapSourceJarTask> getTaskRemapDecompiledJar() {
        return taskRemapDecompiledJar;
    }

    public TaskProvider<Copy> getTaskDecompressDecompiledSources() {
        return taskDecompressDecompiledSources;
    }

    public Configuration getPatchedConfiguration() {
        return patchedConfiguration;
    }

    public SourceSet getPatchedMcSources() {
        return patchedMcSources;
    }

    public TaskProvider<JavaCompile> getTaskBuildPatchedMc() {
        return taskBuildPatchedMc;
    }

    public TaskProvider<Jar> getTaskPackagePatchedMc() {
        return taskPackagePatchedMc;
    }

    public Configuration getForgeUniversalConfiguration() {
        return forgeUniversalConfiguration;
    }

    public TaskProvider<CreateLauncherFiles> getTaskCreateLauncherFiles() {
        return taskCreateLauncherFiles;
    }

    public SourceSet getLauncherSources() {
        return launcherSources;
    }

    public TaskProvider<Jar> getTaskPackageMcLauncher() {
        return taskPackageMcLauncher;
    }

    public TaskProvider<RunMinecraftTask> getTaskRunClient() {
        return taskRunClient;
    }

    public TaskProvider<RunMinecraftTask> getTaskRunServer() {
        return taskRunServer;
    }

    public TaskProvider<BinaryPatchJarTask> getTaskInstallBinaryPatchedVersion() {
        return taskInstallBinaryPatchedVersion;
    }

    public TaskProvider<DeobfuscateTask> getTaskSrgifyBinaryPatchedVersion() {
        return taskSrgifyBinaryPatchedVersion;
    }

    public TaskProvider<RunMinecraftTask> getTaskRunObfClient() {
        return taskRunObfClient;
    }

    public TaskProvider<RunMinecraftTask> getTaskRunObfServer() {
        return taskRunObfServer;
    }

    public Configuration getObfRuntimeClasspathConfiguration() {
        return obfRuntimeClasspathConfiguration;
    }

    public Configuration getReobfJarConfiguration() {
        return reobfJarConfiguration;
    }
}
