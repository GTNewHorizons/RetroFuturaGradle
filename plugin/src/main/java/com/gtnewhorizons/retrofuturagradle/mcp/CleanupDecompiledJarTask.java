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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.inject.Inject;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.tuple.MutablePair;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.logging.LogLevel;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.Optional;
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
import com.gtnewhorizons.retrofuturagradle.fgpatchers.McpCleanupFg12;
import com.gtnewhorizons.retrofuturagradle.fgpatchers.McpCleanupFg23;
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

    @Input
    public abstract Property<Integer> getMinorMcVersion();

    @InputDirectory
    @Optional
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract DirectoryProperty getPatchesInjectDir();

    @Override
    public void hashInputs(MessageDigest digest) {
        HashUtils.addPropertyToHash(digest, getPatches());
        HashUtils.addPropertyToHash(digest, getAstyleConfig());
        HashUtils.addPropertyToHash(digest, getMinorMcVersion());
        HashUtils.addPropertyToHash(digest, getPatchesInjectDir());
    }

    private File taskTempDir;

    @Inject
    public CleanupDecompiledJarTask() {
        getMinorMcVersion().convention(7);
    }

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

        final int mcMinor = getMinorMcVersion().get();
        if (mcMinor > 8) {
            getLogger().lifecycle("Fixup stage 4 - injecting package-info");
            final long pre4Ms = System.currentTimeMillis();
            final File injectedPIs = injectPackageInfos();
            final long post4Ms = System.currentTimeMillis();
            getLogger().lifecycle("  Stage 4 took " + (post4Ms - pre4Ms) + " ms");
        }

        getLogger().lifecycle("Saving the fixed-up jar");
        Utilities.saveMemoryJar(loadedResources, loadedSources, getOutputJar().get().getAsFile(), false);
    }

    private File loadAndApplyFfPatches(File decompiled) throws IOException {
        Utilities.loadMemoryJar(decompiled, loadedResources, loadedSources);
        final int mcMinor = getMinorMcVersion().get();

        loadedSources = loadedSources.entrySet().parallelStream().map(entry -> {
            try {
                final String patched;
                if (mcMinor <= 8) {
                    patched = FFPatcher.processFile(entry.getKey(), entry.getValue(), true);
                } else {
                    patched = com.gtnewhorizons.retrofuturagradle.mcp.fg23.FFPatcher.processFile(entry.getValue());
                }
                return MutablePair.of(entry.getKey(), patched);
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

    private static final ThreadLocal<ASFormatter> formattersFG12 = new ThreadLocal<>();
    private static final ThreadLocal<com.gtnewhorizons.retrofuturagradle.fg23shadow.com.github.abrarsyed.jastyle.ASFormatter> formattersFG23 = new ThreadLocal<>();

    private File applyMcpCleanup() throws IOException {
        final File astyleOptions = getAstyleConfig().get().getAsFile();
        final int mcMinor = getMinorMcVersion().get();

        final GLConstantFixer glFixer = new GLConstantFixer();

        loadedSources = loadedSources.entrySet().parallelStream().map(entry -> {
            try {
                final String filePath = entry.getKey();
                String text = entry.getValue();
                ASFormatter formatterFG12 = formattersFG12.get();
                com.gtnewhorizons.retrofuturagradle.fg23shadow.com.github.abrarsyed.jastyle.ASFormatter formatterFG23 = formattersFG23
                        .get();
                if (mcMinor <= 8) {
                    if (formatterFG12 == null) {
                        formatterFG12 = new ASFormatter();
                        OptParser parser = new OptParser(formatterFG12);
                        parser.parseOptionFile(astyleOptions);
                        formattersFG12.set(formatterFG12);
                    }
                } else {
                    if (formatterFG23 == null) {
                        formatterFG23 = new com.gtnewhorizons.retrofuturagradle.fg23shadow.com.github.abrarsyed.jastyle.ASFormatter();
                        formatterFG23.setUseProperInnerClassIndenting(false);
                        com.gtnewhorizons.retrofuturagradle.fg23shadow.com.github.abrarsyed.jastyle.OptParser parser = new com.gtnewhorizons.retrofuturagradle.fg23shadow.com.github.abrarsyed.jastyle.OptParser(
                                formatterFG23);
                        parser.parseOptionFile(astyleOptions);
                        formattersFG23.set(formatterFG23);
                    }
                }

                if (mcMinor <= 8) {
                    text = McpCleanupFg12.stripComments(text);
                    text = McpCleanupFg12.fixImports(text);
                    text = McpCleanupFg12.cleanup(text);
                } else {
                    text = McpCleanupFg23.stripComments(text);
                    text = McpCleanupFg23.fixImports(text);
                    text = McpCleanupFg23.cleanup(text);
                }

                text = glFixer.fixOGL(text);

                try (Reader reader = new StringReader(text); StringWriter writer = new StringWriter()) {
                    if (mcMinor <= 8) {
                        formatterFG12.format(reader, writer);
                    } else {
                        formatterFG23.format(reader, writer);
                    }
                    text = writer.toString();
                }

                if (mcMinor <= 8) {
                    text = BEFORE_RULE.matcher(text).replaceAll("$1");
                    text = AFTER_RULE.matcher(text).replaceAll("$1");
                    text = FmlCleanup.renameClass(text);
                }

                if (mcMinor > 8 && !text.endsWith(System.lineSeparator())) {
                    text += System.lineSeparator();
                }

                return MutablePair.of(filePath, text);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }).collect(Collectors.toConcurrentMap(MutablePair::getLeft, MutablePair::getRight));

        return Utilities.saveMemoryJar(loadedResources, loadedSources, new File(taskTempDir, "mcpcleanup.jar"), true);
    }

    private File injectPackageInfos() throws IOException {
        final Set<String> seenPackages = new HashSet<>();
        for (String key : loadedSources.keySet()) {
            if (key.startsWith("net/minecraft/")) {
                seenPackages.add(key.substring(0, key.lastIndexOf('/')));
            }
        }

        final File injectDir = getPatchesInjectDir().getAsFile().getOrNull();
        if (injectDir != null) {
            final File pkgInfo = new File(injectDir, "package-info-template.java");
            if (pkgInfo.isFile()) {
                final String template = FileUtils.readFileToString(pkgInfo, StandardCharsets.UTF_8);
                for (String pkg : seenPackages) {
                    final String info = template.replace("{PACKAGE}", pkg.replace('/', '.'));
                    loadedSources.put(pkg + "/package-info.java", info);
                }
                getLogger().lifecycle("  Injected {} package-infos", seenPackages.size());
            }
            final File common = new File(injectDir, "common/");
            if (common.isDirectory()) {
                String root = common.getAbsolutePath().replace('\\', '/');
                if (!root.endsWith("/")) root += '/';

                for (File commonFile : this.getProject().fileTree(common)) {
                    String absPath = commonFile.getAbsolutePath().replace('\\', '/');
                    String relPath = absPath.substring(root.length());
                    final String contents = FileUtils.readFileToString(commonFile, StandardCharsets.UTF_8);
                    loadedSources.put(relPath, contents);
                }
            }
        }

        return Utilities.saveMemoryJar(loadedResources, loadedSources, new File(taskTempDir, "pkginject.jar"), true);
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
