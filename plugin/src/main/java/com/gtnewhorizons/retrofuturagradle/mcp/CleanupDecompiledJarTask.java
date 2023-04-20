package com.gtnewhorizons.retrofuturagradle.mcp;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.tuple.MutablePair;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.logging.LogLevel;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

import com.cloudbees.diff.PatchException;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import com.gtnewhorizons.retrofuturagradle.fg12shadow.com.github.abrarsyed.jastyle.ASFormatter;
import com.gtnewhorizons.retrofuturagradle.fg12shadow.com.github.abrarsyed.jastyle.OptParser;
import com.gtnewhorizons.retrofuturagradle.fgpatchers.FFPatcher;
import com.gtnewhorizons.retrofuturagradle.fgpatchers.FmlCleanup;
import com.gtnewhorizons.retrofuturagradle.fgpatchers.GLConstantFixer;
import com.gtnewhorizons.retrofuturagradle.fgpatchers.McpCleanup;
import com.gtnewhorizons.retrofuturagradle.util.HashUtils;
import com.gtnewhorizons.retrofuturagradle.util.IJarTransformTask;
import com.gtnewhorizons.retrofuturagradle.util.Utilities;
import com.gtnewhorizons.retrofuturagradle.util.patching.ContextualPatch;

public abstract class CleanupDecompiledJarTask extends DefaultTask implements IJarTransformTask {

    private Map<String, byte[]> loadedResources = new HashMap<>();
    private Map<String, String> loadedSources = new HashMap<>();

    @InputDirectory
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract DirectoryProperty getPatches();

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getAstyleConfig();

    @Override
    public void hashInputs(MessageDigest digest) {
        HashUtils.addPropertyToHash(digest, getPatches());
        HashUtils.addPropertyToHash(digest, getAstyleConfig());
    }

    private File taskTempDir;

    @TaskAction
    public void doCleanup() throws IOException {
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
        Utilities.saveMemoryJar(loadedResources, loadedSources, getOutputJar().get().getAsFile(), false);
    }

    private File loadAndApplyFfPatches(File decompiled) throws IOException {
        Utilities.loadMemoryJar(decompiled, loadedResources, loadedSources);

        loadedSources = loadedSources.entrySet().parallelStream().map(entry -> {
            try {
                return MutablePair.of(entry.getKey(), FFPatcher.processFile(entry.getKey(), entry.getValue(), true));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }).collect(Collectors.toConcurrentMap(MutablePair::getLeft, MutablePair::getRight));

        return Utilities.saveMemoryJar(loadedResources, loadedSources, new File(taskTempDir, "ffpatcher.jar"), true);
    }

    private File applyMcpPatches() throws IOException {
        Multimap<String, File> patches = ArrayListMultimap.create();
        Set<File> patchDir = getPatches().get().getAsFileTree().filter(f -> f.getName().contains(".patch")).getFiles();
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
                patch.setAccessC14N(true);
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

        return Utilities.saveMemoryJar(loadedResources, loadedSources, new File(taskTempDir, "mcppatched.jar"), true);
    }

    private static final Pattern BEFORE_RULE = Pattern
            .compile("(?m)((case|default).+(?:\\r\\n|\\r|\\n))(?:\\r\\n|\\r|\\n)");
    private static final Pattern AFTER_RULE = Pattern
            .compile("(?m)(?:\\r\\n|\\r|\\n)((?:\\r\\n|\\r|\\n)[ \\t]+(case|default))");

    private static final ThreadLocal<ASFormatter> formatters = new ThreadLocal<>();

    private File applyMcpCleanup() throws IOException {
        final File astyleOptions = getAstyleConfig().get().getAsFile();

        final GLConstantFixer glFixer = new GLConstantFixer();

        loadedSources = loadedSources.entrySet().parallelStream().map(entry -> {
            try {
                final String filePath = entry.getKey();
                String text = entry.getValue();
                ASFormatter formatter = formatters.get();

                if (formatter == null) {
                    formatter = new ASFormatter();
                    OptParser parser = new OptParser(formatter);
                    parser.parseOptionFile(astyleOptions);
                    formatters.set(formatter);
                }

                text = McpCleanup.stripComments(text);

                text = McpCleanup.fixImports(text);

                text = McpCleanup.cleanup(text);

                text = glFixer.fixOGL(text);

                try (Reader reader = new StringReader(text); StringWriter writer = new StringWriter()) {
                    formatter.format(reader, writer);
                    text = writer.toString();
                }

                text = BEFORE_RULE.matcher(text).replaceAll("$1");
                text = AFTER_RULE.matcher(text).replaceAll("$1");
                text = FmlCleanup.renameClass(text);

                return MutablePair.of(filePath, text);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }).collect(Collectors.toConcurrentMap(MutablePair::getLeft, MutablePair::getRight));

        return Utilities.saveMemoryJar(loadedResources, loadedSources, new File(taskTempDir, "mcpcleanup.jar"), true);
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
