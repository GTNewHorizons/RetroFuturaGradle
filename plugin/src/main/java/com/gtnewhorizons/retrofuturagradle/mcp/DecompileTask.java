package com.gtnewhorizons.retrofuturagradle.mcp;

import com.cloudbees.diff.PatchException;
import com.github.abrarsyed.jastyle.ASFormatter;
import com.github.abrarsyed.jastyle.OptParser;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import com.gtnewhorizons.retrofuturagradle.MinecraftExtension;
import com.gtnewhorizons.retrofuturagradle.fgpatchers.FFPatcher;
import com.gtnewhorizons.retrofuturagradle.fgpatchers.FmlCleanup;
import com.gtnewhorizons.retrofuturagradle.fgpatchers.GLConstantFixer;
import com.gtnewhorizons.retrofuturagradle.fgpatchers.McpCleanup;
import com.gtnewhorizons.retrofuturagradle.util.Utilities;
import com.gtnewhorizons.retrofuturagradle.util.patching.ContextualPatch;
import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.apache.commons.io.FileUtils;
import org.gradle.api.DefaultTask;
import org.gradle.api.Project;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.logging.LogLevel;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;
import org.gradle.jvm.toolchain.JavaLanguageVersion;
import org.gradle.jvm.toolchain.JavaToolchainService;
import org.gradle.jvm.toolchain.JvmVendorSpec;

public abstract class DecompileTask extends DefaultTask {
    @InputFile
    public abstract RegularFileProperty getInputJar();

    @OutputFile
    public abstract RegularFileProperty getOutputJar();

    @InputFile
    public abstract RegularFileProperty getFernflower();

    @InputDirectory
    public abstract DirectoryProperty getPatches();

    @InputFile
    public abstract RegularFileProperty getAstyleConfig();

    private File taskTempDir;

    private Map<String, byte[]> loadedResources = new HashMap<>();
    private Map<String, String> loadedSources = new HashMap<>();

    @TaskAction
    public void decompileAndCleanup() throws IOException {
        taskTempDir = getTemporaryDir();
        loadedResources.clear();
        loadedSources.clear();

        getLogger().lifecycle("Decompiling the srg jar with fernflower");
        final File decompiled = decompile();

        getLogger().lifecycle("Fixup stage 1 - applying FF patches");
        final File ffPatched = loadAndApplyFfPatches(decompiled);

        getLogger().lifecycle("Fixup stage 2 - applying MCP patches");
        final File mcpPatched = applyMcpPatches();

        getLogger().lifecycle("Fixup stage 3 - applying MCP cleanup");
        final File mcpCleaned = applyMcpCleanup();

        getLogger().lifecycle("Saving the fixed-up jar");
        FileUtils.copyFile(mcpCleaned, getOutputJar().get().getAsFile());
    }

    private File decompile() throws IOException {
        Project project = getProject();
        final File ffoutdir = new File(taskTempDir, "ff-out");
        ffoutdir.mkdirs();
        final File ffinpcopy = new File(taskTempDir, "mc.jar");
        final File ffoutfile = new File(ffoutdir, "mc.jar");
        // Skip the lengthy fernflower decompilation if already done before
        if (false && ffoutfile.isFile()) {
            return ffoutfile;
        }
        FileUtils.copyFile(getInputJar().get().getAsFile(), ffinpcopy);
        project.javaexec(exec -> {
                    exec.classpath(getFernflower().get());
                    MinecraftExtension mcExt = project.getExtensions().findByType(MinecraftExtension.class);
                    List<String> args = new ArrayList<>(Objects.requireNonNull(mcExt)
                            .getFernflowerArguments()
                            .get());
                    args.add(ffinpcopy.getAbsolutePath());
                    args.add(ffoutdir.getAbsolutePath());
                    exec.args(args);
                    exec.setWorkingDir(getFernflower().get().getAsFile().getParentFile());
                    try {
                        exec.setStandardOutput(FileUtils.openOutputStream(
                                FileUtils.getFile(project.getBuildDir(), MCPTasks.MCP_DIR, "fernflower_log.log")));
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                    exec.setMaxHeapSize("512M");
                    JavaToolchainService jts = project.getExtensions().findByType(JavaToolchainService.class);
                    final String javaExe = jts.launcherFor(toolchain -> {
                                toolchain.getLanguageVersion().set(JavaLanguageVersion.of(17));
                                toolchain.getVendor().set(JvmVendorSpec.ADOPTIUM);
                            })
                            .get()
                            .getExecutablePath()
                            .getAsFile()
                            .getAbsolutePath();
                    exec.executable(javaExe);
                })
                .assertNormalExitValue();
        FileUtils.delete(ffinpcopy);
        return ffoutfile;
    }

    private File loadAndApplyFfPatches(File decompiled) throws IOException {
        Utilities.loadMemoryJar(decompiled, loadedResources, loadedSources);
        for (Map.Entry<String, String> entry : loadedSources.entrySet()) {
            final String patchedSrc = FFPatcher.processFile(entry.getKey(), entry.getValue(), true);
            entry.setValue(patchedSrc);
        }
        return Utilities.saveMemoryJar(loadedResources, loadedSources, new File(taskTempDir, "ffpatcher.jar"));
    }

    private File applyMcpPatches() throws IOException {
        Multimap<String, File> patches = ArrayListMultimap.create();
        Set<File> patchDir = getPatches()
                .get()
                .getAsFileTree()
                .filter(f -> f.getName().contains(".patch"))
                .getFiles();
        for (File patchFile : patchDir) {
            String base = patchFile.getName();
            final int extLoc = base.lastIndexOf(".patch");
            base = base.substring(0, extLoc + ".patch".length());
            patches.put(base, patchFile);
        }

        for (String key : patches.keySet()) {
            // Apply first non-failing patch
            final Collection<File> patchFiles = patches.get(key);
            ContextualPatch patch = null;
            for (File patchFile : patchFiles) {
                patch = ContextualPatch.create(
                        FileUtils.readFileToString(patchFile, StandardCharsets.UTF_8),
                        new Utilities.InMemoryJarContextProvider(loadedSources, 1));
                final List<ContextualPatch.PatchReport> errors;
                try {
                    errors = patch.patch(true);
                } catch (PatchException pe) {
                    throw new RuntimeException(pe);
                }

                if (errors.stream().allMatch(e -> e.getStatus().isSuccess())) {
                    break;
                }
            }
            final List<ContextualPatch.PatchReport> errors;
            try {
                errors = patch.patch(false);
            } catch (PatchException pe) {
                throw new RuntimeException(pe);
            }
            printPatchErrors(errors);
        }

        return Utilities.saveMemoryJar(loadedResources, loadedSources, new File(taskTempDir, "mcppatched.jar"));
    }

    private static final Pattern BEFORE_RULE =
            Pattern.compile("(?m)((case|default).+(?:\\r\\n|\\r|\\n))(?:\\r\\n|\\r|\\n)");
    private static final Pattern AFTER_RULE =
            Pattern.compile("(?m)(?:\\r\\n|\\r|\\n)((?:\\r\\n|\\r|\\n)[ \\t]+(case|default))");

    private File applyMcpCleanup() throws IOException {
        ASFormatter formatter = new ASFormatter();
        OptParser parser = new OptParser(formatter);
        parser.parseOptionFile(getAstyleConfig().get().getAsFile());

        final GLConstantFixer glFixer = new GLConstantFixer();
        final List<String> files = new ArrayList<>(loadedSources.keySet());
        Collections.sort(files);

        for (String filePath : files) {
            String text = loadedSources.get(filePath);

            getLogger().debug("Processing file: " + filePath);

            getLogger().debug("processing comments");
            text = McpCleanup.stripComments(text);

            getLogger().debug("fixing imports comments");
            text = McpCleanup.fixImports(text);

            getLogger().debug("various other cleanup");
            text = McpCleanup.cleanup(text);

            getLogger().debug("fixing OGL constants");
            text = glFixer.fixOGL(text);

            getLogger().debug("formatting source");
            try (Reader reader = new StringReader(text);
                    StringWriter writer = new StringWriter()) {
                formatter.format(reader, writer);
                text = writer.toString();
            }

            getLogger().debug("applying FML transformations");
            text = BEFORE_RULE.matcher(text).replaceAll("$1");
            text = AFTER_RULE.matcher(text).replaceAll("$1");
            text = FmlCleanup.renameClass(text);

            loadedSources.put(filePath, text);
        }
        return Utilities.saveMemoryJar(loadedResources, loadedSources, new File(taskTempDir, "mcpcleanup.jar"));
    }

    private void printPatchErrors(List<ContextualPatch.PatchReport> errors) throws IOException {
        boolean fuzzed = false;
        for (ContextualPatch.PatchReport report : errors) {
            if (!report.getStatus().isSuccess()) {
                getLogger().log(LogLevel.ERROR, "Patching failed: " + report.getTarget(), report.getFailure());

                for (ContextualPatch.HunkReport hunk : report.getHunks()) {
                    if (!hunk.getStatus().isSuccess()) {
                        getLogger().error("Hunk " + hunk.getHunkID() + " failed!");
                    }
                }

                throw new RuntimeException(report.getFailure());
            } else if (report.getStatus() == ContextualPatch.PatchStatus.Fuzzed) // catch fuzzed patches
            {
                getLogger().log(LogLevel.INFO, "Patching fuzzed: " + report.getTarget(), report.getFailure());
                fuzzed = true;

                for (ContextualPatch.HunkReport hunk : report.getHunks()) {
                    if (!hunk.getStatus().isSuccess()) {
                        getLogger().info("Hunk " + hunk.getHunkID() + " fuzzed " + hunk.getFuzz() + "!");
                    }
                }
            } else {
                getLogger().debug("Patch succeeded: " + report.getTarget());
            }
        }
        if (fuzzed) {
            getLogger().lifecycle("Patches Fuzzed!");
        }
    }
}
