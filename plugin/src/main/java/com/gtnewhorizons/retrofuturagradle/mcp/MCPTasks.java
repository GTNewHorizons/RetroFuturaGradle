package com.gtnewhorizons.retrofuturagradle.mcp;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import com.gtnewhorizons.retrofuturagradle.Constants;
import com.gtnewhorizons.retrofuturagradle.MinecraftExtension;
import com.gtnewhorizons.retrofuturagradle.minecraft.MinecraftTasks;
import com.gtnewhorizons.retrofuturagradle.util.Utilities;
import de.undercouch.gradle.tasks.download.Download;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.input.CloseShieldInputStream;
import org.apache.commons.lang3.StringUtils;
import org.gradle.api.JavaVersion;
import org.gradle.api.Project;
import org.gradle.api.UnknownTaskException;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ModuleDependency;
import org.gradle.api.artifacts.dsl.DependencyHandler;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.Directory;
import org.gradle.api.file.FileCollection;
import org.gradle.api.file.RegularFile;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Provider;
import org.gradle.api.provider.ProviderFactory;
import org.gradle.api.specs.Specs;
import org.gradle.api.tasks.Copy;
import org.gradle.api.tasks.JavaExec;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.compile.JavaCompile;
import org.gradle.jvm.tasks.Jar;

/**
 * Tasks reproducing the MCP/FML/Forge toolchain for deobfuscation
 */
public class MCPTasks {
    private static final String TASK_GROUP_INTERNAL = "Internal Modded Minecraft";
    private static final String TASK_GROUP_USER = "Modded Minecraft";
    public static final String RFG_DIR = "rfg";
    public static final String SOURCE_SET_PATCHED_MC = "patchedMc";
    public static final String SOURCE_SET_LAUNCHER = "mcLauncher";

    private final Project project;
    private final MinecraftExtension mcExt;
    private final MinecraftTasks mcTasks;

    private final Configuration mcpMappingDataConfiguration;
    private final Configuration forgeUserdevConfiguration;

    private final File fernflowerLocation;
    private final TaskProvider<Download> taskDownloadFernflower;

    private final File mcpDataLocation;
    private final TaskProvider<Copy> taskExtractMcpData;
    private final File forgeUserdevLocation;
    private final TaskProvider<Copy> taskExtractForgeUserdev;
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

    private final TaskProvider<RemapSourceJarTask> taskRemapDecompiledJar;
    private final File remappedSourcesLocation;

    private final TaskProvider<Copy> taskDecompressDecompiledSources;
    private final File decompressedSourcesLocation;
    private final Configuration patchedConfiguration;
    private final SourceSet patchedMcSources;
    private final File compiledMcLocation;
    private final TaskProvider<JavaCompile> taskBuildPatchedMc;
    private final File packagedMcLocation;
    private final TaskProvider<Jar> taskPackagePatchedMc;
    private final File launcherSourcesLocation;
    private final File launcherCompiledLocation;
    private final TaskProvider<CreateLauncherFiles> taskCreateLauncherFiles;
    private final SourceSet launcherSources;
    private final File packagedMcLauncherLocation;
    private final TaskProvider<Jar> taskPackageMcLauncher;
    private final TaskProvider<JavaExec> taskRunClient;
    private final TaskProvider<JavaExec> taskRunServer;

    private final File binaryPatchedMcLocation;
    private final TaskProvider<BinaryPatchJarTask> taskInstallBinaryPatchedVersion;

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

    public MCPTasks(Project project, MinecraftExtension mcExt, MinecraftTasks mcTasks) {
        this.project = project;
        this.mcExt = mcExt;
        this.mcTasks = mcTasks;

        project.afterEvaluate(p -> this.afterEvaluate());

        mcpMappingDataConfiguration = project.getConfigurations().create("mcpMappingData");
        forgeUserdevConfiguration = project.getConfigurations().create("forgeUserdev");
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

        mcpDataLocation = FileUtils.getFile(project.getBuildDir(), RFG_DIR, "data");
        taskExtractMcpData = project.getTasks().register("extractMcpData", Copy.class, task -> {
            task.setGroup(TASK_GROUP_INTERNAL);
            task.from(project.provider(() -> project.zipTree(getMcpMappingDataConfiguration()
                    .fileCollection(Specs.SATISFIES_ALL)
                    .getSingleFile())));
            task.into(mcpDataLocation);
        });

        forgeUserdevLocation = FileUtils.getFile(project.getBuildDir(), RFG_DIR, "userdev");
        taskExtractForgeUserdev = project.getTasks().register("extractForgeUserdev", Copy.class, task -> {
            task.setGroup(TASK_GROUP_INTERNAL);
            task.from(project.provider(() -> project.zipTree(getForgeUserdevConfiguration()
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
                    task.getMethodsCsv().set(mcpFile("methods.csv"));
                    task.getFieldsCsv().set(mcpFile("fields.csv"));
                    // outputs
                    task.getNotchToSrg().set(FileUtils.getFile(forgeSrgLocation, "notch-srg.srg"));
                    task.getNotchToMcp().set(FileUtils.getFile(forgeSrgLocation, "notch-mcp.srg"));
                    task.getSrgToMcp().set(FileUtils.getFile(forgeSrgLocation, "srg-mcp.srg"));
                    task.getMcpToSrg().set(FileUtils.getFile(forgeSrgLocation, "mcp-srg.srg"));
                    task.getMcpToNotch().set(FileUtils.getFile(forgeSrgLocation, "mcp-notch.srg"));
                    task.getSrgExc().set(FileUtils.getFile(forgeSrgLocation, "srg.exc"));
                    task.getMcpExc().set(FileUtils.getFile(forgeSrgLocation, "mcp.exc"));
                });

        mergedVanillaJarLocation = FileUtils.getFile(project.getBuildDir(), RFG_DIR, "vanilla_merged_minecraft.jar");
        taskMergeVanillaSidedJars = project.getTasks()
                .register("mergeVanillaSidedJars", MergeSidedJarsTask.class, task -> {
                    task.setGroup(TASK_GROUP_INTERNAL);
                    task.dependsOn(taskExtractForgeUserdev, mcTasks.getTaskDownloadVanillaJars());
                    task.onlyIf(t -> !mergedVanillaJarLocation.exists());
                    task.getClientJar().set(mcTasks.getVanillaClientLocation());
                    task.getServerJar().set(mcTasks.getVanillaServerLocation());
                    task.getMergedJar().set(mergedVanillaJarLocation);
                    task.getMergeConfigFile().set(FileUtils.getFile(forgeUserdevLocation, "conf", "mcp_merge.cfg"));
                    task.getMcVersion().set(mcExt.getMcVersion());
                });

        srgMergedJarLocation = FileUtils.getFile(project.getBuildDir(), RFG_DIR, "srg_merged_minecraft.jar");
        taskDeobfuscateMergedJarToSrg = project.getTasks()
                .register("deobfuscateMergedJarToSrg", DeobfuscateTask.class, task -> {
                    task.setGroup(TASK_GROUP_INTERNAL);
                    task.dependsOn(taskMergeVanillaSidedJars, taskGenerateForgeSrgMappings);
                    task.getSrgFile().set(taskGenerateForgeSrgMappings.flatMap(GenSrgMappingsTask::getNotchToSrg));
                    task.getExceptorJson().set(userdevFile("conf/exceptor.json"));
                    task.getExceptorCfg().set(taskGenerateForgeSrgMappings.flatMap(GenSrgMappingsTask::getSrgExc));
                    task.getInputJar().set(taskMergeVanillaSidedJars.flatMap(MergeSidedJarsTask::getMergedJar));
                    task.getOutputJar().set(srgMergedJarLocation);
                    // TODO: figure out why deobfBinJar uses these but deobfuscateJar doesn't
                    // Passing them in causes ATs to not successfully apply
                    /*
                    task.getFieldCsv().set(FileUtils.getFile(mcpDataLocation, "fields.csv"));
                    task.getMethodCsv().set(FileUtils.getFile(mcpDataLocation, "methods.csv"));
                    */
                    task.getIsApplyingMarkers().set(true);
                    // Configured in afterEvaluate()
                    task.getAccessTransformerFiles().setFrom(deobfuscationATs);
                });

        decompiledSrgLocation = FileUtils.getFile(project.getBuildDir(), RFG_DIR, "srg_merged_minecraft-sources.jar");
        taskDecompileSrgJar = project.getTasks().register("decompileSrgJar", DecompileTask.class, task -> {
            task.setGroup(TASK_GROUP_INTERNAL);
            task.dependsOn(taskDeobfuscateMergedJarToSrg, taskDownloadFernflower);
            task.getInputJar().set(taskDeobfuscateMergedJarToSrg.flatMap(DeobfuscateTask::getOutputJar));
            task.getOutputJar().set(decompiledSrgLocation);
            task.getFernflower().set(fernflowerLocation);
            task.getPatches().set(userdevDir("conf/minecraft_ff"));
            task.getAstyleConfig().set(userdevFile("conf/astyle.cfg"));
        });

        patchedSourcesLocation = FileUtils.getFile(project.getBuildDir(), RFG_DIR, "srg_patched_minecraft-sources.jar");
        taskPatchDecompiledJar = project.getTasks().register("patchDecompiledJar", PatchSourcesTask.class, task -> {
            task.setGroup(TASK_GROUP_INTERNAL);
            task.dependsOn(taskDecompileSrgJar);
            task.getInputJar().set(taskDecompileSrgJar.flatMap(DecompileTask::getOutputJar));
            task.getOutputJar().set(patchedSourcesLocation);
            task.getMaxFuzziness().set(1);
        });

        remappedSourcesLocation =
                FileUtils.getFile(project.getBuildDir(), RFG_DIR, "mcp_patched_minecraft-sources.jar");
        taskRemapDecompiledJar = project.getTasks().register("remapDecompiledJar", RemapSourceJarTask.class, task -> {
            task.setGroup(TASK_GROUP_INTERNAL);
            task.dependsOn(taskPatchDecompiledJar);
            task.getInputJar().set(taskPatchDecompiledJar.flatMap(PatchSourcesTask::getOutputJar));
            task.getOutputJar().set(remappedSourcesLocation);
            task.getFieldCsv().set(FileUtils.getFile(mcpDataLocation, "fields.csv"));
            task.getMethodCsv().set(FileUtils.getFile(mcpDataLocation, "methods.csv"));
            task.getParamCsv().set(FileUtils.getFile(mcpDataLocation, "params.csv"));
            task.getAddJavadocs().set(true);
        });

        decompressedSourcesLocation = FileUtils.getFile(project.getBuildDir(), RFG_DIR, "minecraft-src");
        taskDecompressDecompiledSources = project.getTasks()
                .register("decompressDecompiledSources", Copy.class, task -> {
                    task.setGroup(TASK_GROUP_INTERNAL);
                    task.dependsOn(taskRemapDecompiledJar);
                    task.from(
                            project.zipTree(taskRemapDecompiledJar.flatMap(RemapSourceJarTask::getOutputJar)),
                            subset -> {
                                subset.include("**/*.java");
                            });
                    task.from(
                            project.zipTree(taskRemapDecompiledJar.flatMap(RemapSourceJarTask::getOutputJar)),
                            subset -> {
                                subset.exclude("**/*.java");
                            });
                    task.eachFile(fcd -> {
                        fcd.setRelativePath(
                                fcd.getRelativePath().prepend(fcd.getName().endsWith(".java") ? "java" : "resources"));
                    });
                    task.into(decompressedSourcesLocation);
                });

        this.patchedConfiguration = project.getConfigurations().create("patchedMinecraft");
        this.patchedConfiguration.extendsFrom(mcTasks.getVanillaMcConfiguration());
        project.getConfigurations()
                .getByName(JavaPlugin.IMPLEMENTATION_CONFIGURATION_NAME)
                .extendsFrom(this.patchedConfiguration);

        final SourceSetContainer sourceSets = project.getExtensions().getByType(SourceSetContainer.class);
        final JavaPluginExtension javaExt = project.getExtensions().getByType(JavaPluginExtension.class);

        patchedMcSources = sourceSets.create(SOURCE_SET_PATCHED_MC, sourceSet -> {
            sourceSet.setCompileClasspath(patchedConfiguration);
            sourceSet.setRuntimeClasspath(patchedConfiguration);
            sourceSet.java(java -> java.setSrcDirs(project.files(new File(decompressedSourcesLocation, "java"))
                    .builtBy(taskDecompressDecompiledSources)));
            sourceSet.resources(
                    java -> java.setSrcDirs(project.files(new File(decompressedSourcesLocation, "resources"))
                            .builtBy(taskDecompressDecompiledSources)));
        });
        javaExt.getSourceSets().add(patchedMcSources);

        compiledMcLocation = patchedMcSources.getOutput().getClassesDirs().getSingleFile();
        taskBuildPatchedMc = project.getTasks()
                .named(patchedMcSources.getCompileJavaTaskName(), JavaCompile.class, task -> {
                    task.setGroup(TASK_GROUP_INTERNAL);
                    task.dependsOn(taskDecompressDecompiledSources);
                    configureMcJavaCompilation(task);
                });
        project.getTasks()
                .named(patchedMcSources.getProcessResourcesTaskName())
                .configure(task -> task.dependsOn(taskDecompressDecompiledSources));

        packagedMcLocation = FileUtils.getFile(project.getBuildDir(), RFG_DIR, "recompiled_minecraft.jar");
        taskPackagePatchedMc = project.getTasks().register("packagePatchedMc", Jar.class, task -> {
            task.setGroup(TASK_GROUP_INTERNAL);
            task.dependsOn(taskBuildPatchedMc, taskDecompressDecompiledSources, patchedMcSources.getClassesTaskName());
            task.getArchiveBaseName().set(StringUtils.removeEnd(packagedMcLocation.getName(), ".jar"));
            task.getDestinationDirectory().set(packagedMcLocation.getParentFile());
            task.from(patchedMcSources.getOutput());
        });

        launcherSourcesLocation = FileUtils.getFile(project.getBuildDir(), RFG_DIR, "launcher-src");
        taskCreateLauncherFiles = project.getTasks()
                .register("createMcLauncherFiles", CreateLauncherFiles.class, task -> {
                    task.setGroup(TASK_GROUP_INTERNAL);
                    task.dependsOn(
                            taskExtractMcpData,
                            taskExtractForgeUserdev,
                            mcTasks.getTaskDownloadVanillaAssets(),
                            mcTasks.getTaskExtractNatives());
                    task.getOutputDir().set(launcherSourcesLocation);
                    final ProviderFactory providers = project.getProviders();
                    task.addResource(providers, "GradleStart.java");
                    task.addResource(providers, "GradleStartServer.java");
                    task.addResource(providers, "net/minecraftforge/gradle/GradleStartCommon.java");
                    task.addResource(providers, "net/minecraftforge/gradle/OldPropertyMapSerializer.java");
                    task.addResource(providers, "net/minecraftforge/gradle/tweakers/CoremodTweaker.java");
                    task.addResource(providers, "net/minecraftforge/gradle/tweakers/AccessTransformerTweaker.java");

                    MapProperty<String, String> replacements = task.getReplacementTokens();
                    replacements.put("@@MCVERSION@@", mcExt.getMcVersion());
                    replacements.put("@@ASSETINDEX@@", mcExt.getMcVersion());
                    replacements.put(
                            "@@ASSETSDIR@@", mcTasks.getVanillaAssetsLocation().getPath());
                    replacements.put(
                            "@@NATIVESDIR@@", mcTasks.getNativesDirectory().getPath());
                    replacements.put("@@SRGDIR@@", forgeSrgLocation.getPath());
                    replacements.put(
                            "@@SRG_NOTCH_SRG@@",
                            taskGenerateForgeSrgMappings
                                    .flatMap(GenSrgMappingsTask::getNotchToSrg)
                                    .map(RegularFile::getAsFile)
                                    .map(File::getPath));
                    replacements.put(
                            "@@SRG_NOTCH_MCP@@",
                            taskGenerateForgeSrgMappings
                                    .flatMap(GenSrgMappingsTask::getNotchToMcp)
                                    .map(RegularFile::getAsFile)
                                    .map(File::getPath));
                    replacements.put(
                            "@@SRG_SRG_MCP@@",
                            taskGenerateForgeSrgMappings
                                    .flatMap(GenSrgMappingsTask::getSrgToMcp)
                                    .map(RegularFile::getAsFile)
                                    .map(File::getPath));
                    replacements.put(
                            "@@SRG_MCP_SRG@@",
                            taskGenerateForgeSrgMappings
                                    .flatMap(GenSrgMappingsTask::getMcpToSrg)
                                    .map(RegularFile::getAsFile)
                                    .map(File::getPath));
                    replacements.put(
                            "@@SRG_MCP_NOTCH@@",
                            taskGenerateForgeSrgMappings
                                    .flatMap(GenSrgMappingsTask::getMcpToNotch)
                                    .map(RegularFile::getAsFile)
                                    .map(File::getPath));
                    replacements.put("@@CSVDIR@@", mcpDataLocation.getPath());
                    replacements.put("@@CLIENTTWEAKER@@", "cpw.mods.fml.common.launcher.FMLTweaker");
                    replacements.put("@@SERVERTWEAKER@@", "cpw.mods.fml.common.launcher.FMLServerTweaker");
                    replacements.put("@@BOUNCERCLIENT@@", "net.minecraft.launchwrapper.Launch");
                    replacements.put("@@BOUNCERSERVER@@", "net.minecraft.launchwrapper.Launch");
                });

        launcherSources = sourceSets.create(SOURCE_SET_LAUNCHER, sourceSet -> {
            sourceSet.setCompileClasspath(patchedConfiguration);
            sourceSet.setRuntimeClasspath(patchedConfiguration);
            sourceSet.java(java ->
                    java.setSrcDirs(project.files(launcherSourcesLocation).builtBy(taskCreateLauncherFiles)));
        });
        launcherCompiledLocation = launcherSources.getOutput().getClassesDirs().getSingleFile();
        javaExt.getSourceSets().add(launcherSources);
        project.getTasks().named("compileMcLauncherJava", JavaCompile.class, task -> {
            task.setGroup(TASK_GROUP_INTERNAL);
            task.dependsOn(taskCreateLauncherFiles);
            configureMcJavaCompilation(task);
        });

        packagedMcLauncherLocation = FileUtils.getFile(project.getBuildDir(), RFG_DIR, "mclauncher.jar");
        taskPackageMcLauncher = project.getTasks().register("packageMcLauncher", Jar.class, task -> {
            task.setGroup(TASK_GROUP_INTERNAL);
            task.dependsOn(taskCreateLauncherFiles, launcherSources.getClassesTaskName());
            task.getArchiveBaseName().set(StringUtils.removeEnd(packagedMcLauncherLocation.getName(), ".jar"));
            task.getDestinationDirectory().set(packagedMcLauncherLocation.getParentFile());

            task.from(launcherSources.getOutput());
            task.from(project.getTasks().named(launcherSources.getClassesTaskName()));
        });

        taskRunClient = project.getTasks().register("runClient", JavaExec.class, task -> {
            task.setGroup(TASK_GROUP_USER);
            task.setDescription("Runs the deobfuscated client with your mod");
            task.dependsOn(launcherSources.getClassesTaskName(), taskPackagePatchedMc, "classes");

            task.doFirst(p -> {
                final FileCollection classpath = task.getClasspath();
                final StringBuilder classpathStr = new StringBuilder();
                for (File f : classpath) {
                    classpathStr.append(f.getPath());
                    classpathStr.append(' ');
                }
                p.getLogger()
                        .lifecycle(
                                "Starting the client with args: {}\nClasspath: {}",
                                StringUtils.join(task.getAllJvmArgs(), " "),
                                classpathStr);
                try {
                    FileUtils.forceMkdir(mcTasks.getRunDirectory());
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });

            task.classpath(project.getTasks().named("jar"));
            task.classpath(project.getConfigurations().getByName(JavaPlugin.RUNTIME_CLASSPATH_CONFIGURATION_NAME));
            task.classpath(taskPackageMcLauncher);
            task.classpath(taskPackagePatchedMc);
            task.classpath(patchedConfiguration);
            task.workingDir(mcTasks.getRunDirectory());
            task.setEnableAssertions(true);
            task.setStandardInput(System.in);
            task.setStandardOutput(System.out);
            task.setErrorOutput(System.err);
            task.getMainClass().set("GradleStart");
            String libraryPath = mcTasks.getNativesDirectory().getAbsolutePath()
                    + File.pathSeparator
                    + System.getProperty("java.library.path");
            task.jvmArgs(
                    "-Djava.library.path=" + libraryPath,
                    "-Xmx6G",
                    "-Xms1G",
                    "-Dfml.ignoreInvalidMinecraftCertificates=true");
            task.args(
                    "--username",
                    "Developer",
                    "--version",
                    mcExt.getMcVersion().get(),
                    "--gameDir",
                    mcTasks.getRunDirectory(),
                    "--assetsDir",
                    mcTasks.getVanillaAssetsLocation(),
                    "--assetIndex",
                    mcExt.getMcVersion().get(),
                    "--uuid",
                    UUID.nameUUIDFromBytes(new byte[] {'d', 'e', 'v'}),
                    "--userProperties",
                    "{}",
                    "--accessToken",
                    "0");
            task.getJavaLauncher().set(mcExt.getToolchainLauncher());
        });

        taskRunServer = project.getTasks().register("runServer", JavaExec.class, task -> {
            task.setGroup(TASK_GROUP_USER);
            task.setDescription("Runs the deobfuscated server with your mod");
            task.dependsOn(launcherSources.getClassesTaskName(), taskPackagePatchedMc, "classes");

            task.doFirst(p -> {
                final FileCollection classpath = task.getClasspath();
                final StringBuilder classpathStr = new StringBuilder();
                for (File f : classpath) {
                    classpathStr.append(f.getPath());
                    classpathStr.append(' ');
                }
                p.getLogger()
                        .lifecycle(
                                "Starting the server with args: {}\nClasspath: {}",
                                StringUtils.join(task.getAllJvmArgs(), " "),
                                classpathStr);
                try {
                    FileUtils.forceMkdir(mcTasks.getRunDirectory());
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                final File eula = new File(mcTasks.getRunDirectory(), "eula.txt");
                if (!eula.exists()) {
                    p.getLogger()
                            .warn(
                                    "Do you accept the minecraft EULA? Say 'y' if you accept the terms at https://account.mojang.com/documents/minecraft_eula");
                    final String userInput;
                    try (InputStreamReader isr = new InputStreamReader(CloseShieldInputStream.wrap(System.in));
                            BufferedReader reader = new BufferedReader(isr)) {
                        userInput = Strings.nullToEmpty(reader.readLine()).trim();
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                    if (userInput.startsWith("y") || userInput.startsWith("Y")) {
                        try {
                            FileUtils.write(eula, "eula=true", StandardCharsets.UTF_8);
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    } else {
                        p.getLogger().error("EULA not accepted!");
                        throw new RuntimeException("Minecraft EULA not accepted.");
                    }
                }
            });

            task.classpath(project.getTasks().named("jar"));
            task.classpath(project.getConfigurations().getByName(JavaPlugin.RUNTIME_CLASSPATH_CONFIGURATION_NAME));
            task.classpath(taskPackageMcLauncher);
            task.classpath(taskPackagePatchedMc);
            task.classpath(patchedConfiguration);
            task.workingDir(mcTasks.getRunDirectory());
            task.setEnableAssertions(true);
            task.setStandardInput(System.in);
            task.setStandardOutput(System.out);
            task.setErrorOutput(System.err);
            task.getMainClass().set("GradleStartServer");
            task.jvmArgs("-Xmx4G", "-Xms1G", "-Dfml.ignoreInvalidMinecraftCertificates=true");
            task.args("nogui");
            task.getJavaLauncher().set(mcExt.getToolchainLauncher());
        });

        // The default jar is deobfuscated, specify the correct classifier for it
        project.getTasks().named("jar", Jar.class).configure(task -> {
            task.getArchiveClassifier().set("dev");
        });

        // Add a reobfuscation task rule
        project.getTasks().addRule("Reobfuscate a modded jar", taskName -> {
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
                task.dependsOn(
                        taskExtractMcpData,
                        taskExtractForgeUserdev,
                        taskGenerateForgeSrgMappings,
                        taskPackagePatchedMc,
                        subjectTask);

                task.setInputJarFromTask(subjectTask);
                task.getMcVersion().set(mcExt.getMcVersion());
                task.getSrg().set(taskGenerateForgeSrgMappings.flatMap(GenSrgMappingsTask::getMcpToSrg));
                task.getFieldCsv().set(mcpFile("fields.csv"));
                task.getMethodCsv().set(mcpFile("methods.csv"));
                task.getExceptorCfg().set(taskGenerateForgeSrgMappings.flatMap(GenSrgMappingsTask::getSrgExc));
                task.getRecompMcJar().set(taskPackageMcLauncher.flatMap(Jar::getArchiveFile));
                task.getReferenceClasspath()
                        .from(project.getConfigurations()
                                .getByName(JavaPlugin.RUNTIME_CLASSPATH_CONFIGURATION_NAME)
                                .fileCollection(Specs.SATISFIES_ALL));
            });
        });

        // Initialize a reobf task for the default jar
        project.getTasks().named("reobfJar", ReobfuscatedJar.class);

        binaryPatchedMcLocation = FileUtils.getFile(project.getBuildDir(), RFG_DIR, "binpatchedmc.jar");
        taskInstallBinaryPatchedVersion = project.getTasks()
                .register("installBinaryPatchedVersion", BinaryPatchJarTask.class, task -> {
                    task.setGroup(TASK_GROUP_INTERNAL);
                    task.dependsOn(taskExtractForgeUserdev, taskMergeVanillaSidedJars);
                    task.getInputJar().set(taskMergeVanillaSidedJars.flatMap(MergeSidedJarsTask::getMergedJar));
                    task.getOutputJar().set(binaryPatchedMcLocation);
                    task.getPatchesLzma().set(userdevFile("devbinpatches.pack.lzma"));
                    task.getExtraClassesJar().set(userdevFile("binaries.jar"));
                    task.getExtraResourcesTree().from(userdevDir("src/main/resources"));
                });
    }

    public void configureMcJavaCompilation(JavaCompile task) {
        task.getModularity().getInferModulePath().set(false);
        task.getOptions().setEncoding("UTF-8");
        task.getOptions().setFork(true);
        task.getOptions().setWarnings(false);
        task.setSourceCompatibility(JavaVersion.VERSION_1_8.toString());
        task.setTargetCompatibility(JavaVersion.VERSION_1_8.toString());
        task.getJavaCompiler().set(mcExt.getToolchainCompiler());
    }

    private void afterEvaluate() {
        final DependencyHandler deps = project.getDependencies();

        deps.add(
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

        if (mcExt.getSkipSlowTasks().get()) {
            taskDeobfuscateMergedJarToSrg.configure(t -> t.onlyIf(
                    "skipping slow task",
                    p -> !t.getOutputJar().get().getAsFile().exists()));
            taskDecompileSrgJar.configure(t -> t.onlyIf(
                    "skipping slow task",
                    p -> !t.getOutputJar().get().getAsFile().exists()));
            taskPatchDecompiledJar.configure(t -> t.onlyIf(
                    "skipping slow task",
                    p -> !t.getOutputJar().get().getAsFile().exists()));
        }

        deps.add(forgeUserdevConfiguration.getName(), "net.minecraftforge:forge:1.7.10-10.13.4.1614-1.7.10:userdev");
        if (mcExt.getUsesFml().get()) {
            deobfuscationATs.builtBy(taskExtractForgeUserdev);
            deobfuscationATs.from(userdevFile(Constants.PATH_USERDEV_FML_ACCESS_TRANFORMER));

            taskPatchDecompiledJar.configure(task -> {
                task.getPatches().builtBy(taskExtractForgeUserdev);
                task.getInjectionDirectories().builtBy(taskExtractForgeUserdev);
                task.getPatches().from(userdevFile("fmlpatches.zip"));
                task.getInjectionDirectories().from(userdevDir("src/main/java"));
                task.getInjectionDirectories().from(userdevDir("src/main/resources"));
            });

            final String PATCHED_MC_CFG = patchedConfiguration.getName();
            // LaunchWrapper brings in its own lwjgl version which we want to override
            ((ModuleDependency) deps.add(PATCHED_MC_CFG, "net.minecraft:launchwrapper:1.12")).setTransitive(false);
            deps.add(PATCHED_MC_CFG, "com.google.code.findbugs:jsr305:1.3.9");
            deps.add(PATCHED_MC_CFG, "org.ow2.asm:asm-debug-all:5.0.3");
            deps.add(PATCHED_MC_CFG, "com.typesafe.akka:akka-actor_2.11:2.3.3");
            deps.add(PATCHED_MC_CFG, "com.typesafe:config:1.2.1");
            deps.add(PATCHED_MC_CFG, "org.scala-lang:scala-actors-migration_2.11:1.1.0");
            deps.add(PATCHED_MC_CFG, "org.scala-lang:scala-compiler:2.11.1");
            deps.add(PATCHED_MC_CFG, "org.scala-lang.plugins:scala-continuations-library_2.11:1.0.2");
            deps.add(PATCHED_MC_CFG, "org.scala-lang.plugins:scala-continuations-plugin_2.11.1:1.0.2");
            deps.add(PATCHED_MC_CFG, "org.scala-lang:scala-library:2.11.1");
            deps.add(PATCHED_MC_CFG, "org.scala-lang:scala-parser-combinators_2.11:1.0.1");
            deps.add(PATCHED_MC_CFG, "org.scala-lang:scala-reflect:2.11.1");
            deps.add(PATCHED_MC_CFG, "org.scala-lang:scala-swing_2.11:1.0.1");
            deps.add(PATCHED_MC_CFG, "org.scala-lang:scala-xml_2.11:1.0.2");
            deps.add(PATCHED_MC_CFG, "lzma:lzma:0.0.1");

            if (mcExt.getUsesForge().get()) {
                deobfuscationATs.from(userdevDir(Constants.PATH_USERDEV_FORGE_ACCESS_TRANFORMER));

                taskPatchDecompiledJar.configure(task -> {
                    task.getPatches().from(userdevDir("forgepatches.zip"));
                });

                final SourceSetContainer sourceSets = project.getExtensions().getByType(SourceSetContainer.class);
                final SourceSet mainSet = sourceSets.getByName(SourceSet.MAIN_SOURCE_SET_NAME);
                final ConfigurableFileCollection mcCp = project.getObjects().fileCollection();
                mcCp.from(launcherSources.getOutput());
                mcCp.from(patchedMcSources.getOutput());
                mainSet.setCompileClasspath(mainSet.getCompileClasspath().plus(mcCp));
                mainSet.setRuntimeClasspath(mainSet.getRuntimeClasspath().plus(mcCp));
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

    public TaskProvider<Copy> getTaskExtractMcpData() {
        return taskExtractMcpData;
    }

    public File getForgeUserdevLocation() {
        return forgeUserdevLocation;
    }

    public TaskProvider<Copy> getTaskExtractForgeUserdev() {
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

    public TaskProvider<RemapSourceJarTask> getTaskRemapDecompiledJar() {
        return taskRemapDecompiledJar;
    }

    public File getRemappedSourcesLocation() {
        return remappedSourcesLocation;
    }

    public TaskProvider<Copy> getTaskDecompressDecompiledSources() {
        return taskDecompressDecompiledSources;
    }

    public File getDecompressedSourcesLocation() {
        return decompressedSourcesLocation;
    }

    public Configuration getPatchedConfiguration() {
        return patchedConfiguration;
    }

    public SourceSet getPatchedMcSources() {
        return patchedMcSources;
    }

    public File getCompiledMcLocation() {
        return compiledMcLocation;
    }

    public TaskProvider<JavaCompile> getTaskBuildPatchedMc() {
        return taskBuildPatchedMc;
    }

    public File getPackagedMcLocation() {
        return packagedMcLocation;
    }

    public TaskProvider<Jar> getTaskPackagePatchedMc() {
        return taskPackagePatchedMc;
    }
}
