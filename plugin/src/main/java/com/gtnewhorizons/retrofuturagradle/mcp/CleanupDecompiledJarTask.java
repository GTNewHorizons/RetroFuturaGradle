package com.gtnewhorizons.retrofuturagradle.mcp;

import com.cloudbees.diff.PatchException;
import com.github.abrarsyed.jastyle.ASFormatter;
import com.github.abrarsyed.jastyle.OptParser;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.apache.commons.io.FileUtils;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.logging.LogLevel;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

public abstract class CleanupDecompiledJarTask extends DefaultTask {

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getInputJar();

    @OutputFile
    public abstract RegularFileProperty getOutputJar();

    private Map<String, byte[]> loadedResources = new ConcurrentHashMap<>();
    private Map<String, String> loadedSources = new ConcurrentHashMap<>();

    @InputDirectory
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract DirectoryProperty getPatches();

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getAstyleConfig();

    private File taskTempDir;

    @TaskAction
    public void doCleanup() throws IOException {
        {
            try {
                System.err.printf(
                        "Debug in; tid=%d:%s%n",
                        Thread.currentThread().getId(), Thread.currentThread().getName());
                System.in.read();
            } catch (Throwable e) {
                throw new RuntimeException(e);
            }
        }
        taskTempDir = getTemporaryDir();
        loadedResources.clear();
        loadedSources.clear();

        getLogger().lifecycle("Fixup stage 1 - applying FF patches");
        final long pre1Ms = System.currentTimeMillis();
        final File ffPatched = loadAndApplyFfPatches(getInputJar().get().getAsFile());
        final long post1Ms = System.currentTimeMillis();
        getLogger().lifecycle("  Stage 1 took " + (post1Ms - pre1Ms) + " ms");

        getLogger().lifecycle("Fixup stage 2 - applying MCP patches");
        final long pre2Ms = System.currentTimeMillis();
        final File mcpPatched = applyMcpPatches();
        final long post2Ms = System.currentTimeMillis();
        getLogger().lifecycle("  Stage 2 took " + (post2Ms - pre2Ms) + " ms");

        getLogger().lifecycle("Fixup stage 3 - applying MCP cleanup");
        final long pre3Ms = System.currentTimeMillis();
        final File mcpCleaned = applyMcpCleanup();
        final long post3Ms = System.currentTimeMillis();
        getLogger().lifecycle("  Stage 3 took " + (post3Ms - pre3Ms) + " ms");

        getLogger().lifecycle("Saving the fixed-up jar");
        FileUtils.copyFile(mcpCleaned, getOutputJar().get().getAsFile());
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
