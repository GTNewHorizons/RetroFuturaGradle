package com.gtnewhorizons.retrofuturagradle.modutils;

import java.io.*;
import java.nio.file.Path;
import java.util.*;
import java.util.jar.*;
import java.util.stream.Collectors;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.gradle.api.artifacts.transform.CacheableTransform;
import org.gradle.api.artifacts.transform.InputArtifact;
import org.gradle.api.artifacts.transform.InputArtifactDependencies;
import org.gradle.api.artifacts.transform.TransformAction;
import org.gradle.api.artifacts.transform.TransformOutputs;
import org.gradle.api.artifacts.transform.TransformParameters;
import org.gradle.api.artifacts.transform.VariantTransformConfigurationException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.FileCollection;
import org.gradle.api.file.FileSystemLocation;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.provider.Provider;
import org.gradle.api.provider.SetProperty;
import org.gradle.api.tasks.CompileClasspath;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;

import com.google.common.io.Files;
import com.gtnewhorizons.retrofuturagradle.util.Utilities;

@CacheableTransform
public abstract class DependencyDeobfuscationTransform
        implements TransformAction<DependencyDeobfuscationTransform.Parameters> {

    interface Parameters extends TransformParameters {

        @InputFile
        @PathSensitive(PathSensitivity.NONE)
        public abstract RegularFileProperty getFieldsCsv();

        @InputFile
        @PathSensitive(PathSensitivity.NONE)
        public abstract RegularFileProperty getMethodsCsv();

        @InputFile
        @PathSensitive(PathSensitivity.RELATIVE)
        public abstract ConfigurableFileCollection getFilesToDeobf();

        @Input
        public abstract SetProperty<String> getModulesToDeobf();
    }

    @InputArtifact
    @PathSensitive(PathSensitivity.NONE)
    public abstract Provider<FileSystemLocation> getInputArtifact();

    @InputArtifactDependencies
    @CompileClasspath
    public abstract FileCollection getDependencies();

    @Override
    public void transform(TransformOutputs outputs) {
        try {
            runTransform(outputs);
        } catch (Exception e) {
            throw new VariantTransformConfigurationException(
                    "Error encountered when deobfuscating " + getInputArtifact().get().getAsFile(),
                    e);
        }
    }

    private static boolean mustRunDeobf(File inputFile, Set<File> filesToDeobf, Set<String> modulesToDeobf) {
        boolean mustRunDeobf = filesToDeobf.stream().anyMatch(inputFile::equals);

        if (!mustRunDeobf) {
            final Path inputPath = inputFile.toPath();
            final String moduleSpec = Utilities.getModuleSpecFromCachePath(inputPath);
            if (moduleSpec == null) {
                for (final String foundSpec : modulesToDeobf) {
                    final List<String> parts = Arrays.stream(foundSpec.split(":")).filter(StringUtils::isNotBlank)
                            .collect(Collectors.toList());
                    final String foundJar = StringUtils.join(parts, "-") + ".jar";
                    if (inputPath.endsWith(foundJar)) {
                        mustRunDeobf = true;
                    }
                }
            } else {
                mustRunDeobf = modulesToDeobf.contains(moduleSpec);
            }
        }

        return mustRunDeobf;
    }

    private void runTransform(TransformOutputs outputs) throws IOException {
        final FileCollection dependencies = getDependencies();
        final Parameters parameters = getParameters();
        final File inputLocation = getInputArtifact().get().getAsFile();

        final Set<File> filesToDeobf = parameters.getFilesToDeobf().getFiles();
        final Set<String> modulesToDeobf = parameters.getModulesToDeobf().getOrElse(Collections.emptySet());

        final boolean runDeobf = mustRunDeobf(inputLocation, filesToDeobf, modulesToDeobf);

        if (!runDeobf) {
            outputs.file(inputLocation);
            return;
        }

        Logger logger = Logging.getLogger(DependencyDeobfuscationTransform.class);
        final Deque<DeobfJob> deobfQueue = new ArrayDeque<>();
        deobfQueue.add(
                new DeobfJob(
                        StringUtils.removeEnd(inputLocation.getName(), ".jar"),
                        inputLocation,
                        inputLocation.toString(),
                        false));

        DeobfJob job;
        while ((job = deobfQueue.poll()) != null) {
            final String outFileName = job.artifactName + "-deobf.jar";
            final String tempCopyName = job.artifactName + "-rfg_tmp_copy.jar";
            final File outFile = outputs.file(outFileName);
            final File outputDir = outFile.getParentFile();
            final File outFileTemp = new File(outputDir, tempCopyName);
            logger.info("Deobfuscating {} to {}", job.sourceName, outFile);

            final File fieldsCsv = parameters.getFieldsCsv().get().getAsFile();
            final File methodsCsv = parameters.getMethodsCsv().get().getAsFile();

            final Utilities.MappingsSet mappings = Utilities.loadMappingCsvs(methodsCsv, fieldsCsv, null, null, null);
            final Map<String, String> combined = mappings.getCombinedMappings();

            if (outFile.isFile()) {
                FileUtils.delete(outFile);
            }

            try (final JarFile ijar = new JarFile(job.sourceFile, false)) {
                final Manifest mf = ijar.getManifest();
                Map<String, String> containedDeps = Collections.emptyMap(); // Maps dep path to dep name
                if (mf != null) {
                    transformManifest(mf);
                    final Attributes attrs = mf.getMainAttributes();

                    final String containedDepsAttr = attrs.getValue("ContainedDeps");
                    if (containedDepsAttr != null) {
                        containedDeps = new HashMap<>();
                        for (final String depName : containedDepsAttr.split(" ")) {
                            if (!depName.endsWith(".jar")) {
                                logger.warn("Skipping non-jar embedded dependency: {} -> {}", job.sourceFile, depName);
                                continue;
                            }
                            String depPath = depName;
                            if (ijar.getJarEntry(depPath) == null) {
                                depPath = "META-INF/libraries/" + depPath;
                                if (ijar.getJarEntry(depPath) == null) {
                                    logger.warn(
                                            "Skipping missing embedded dependency: {} -> {}",
                                            job.sourceFile,
                                            depName);
                                    continue;
                                }
                            }
                            logger.info("Found embedded dependency: {} -> {}", job.sourceFile, depName);
                            containedDeps.put(depPath, StringUtils.removeEnd(depName, ".jar"));
                        }
                    }
                }

                try (final OutputStream os = FileUtils.openOutputStream(outFileTemp, false);
                        final BufferedOutputStream bos = new BufferedOutputStream(os);
                        final JarOutputStream jos = mf != null ? new JarOutputStream(bos, mf)
                                : new JarOutputStream(bos)) {
                    final Enumeration<JarEntry> iter = ijar.entries();
                    while (iter.hasMoreElements()) {
                        final JarEntry entry = iter.nextElement();
                        if (StringUtils.endsWith(entry.getName(), "META-INF/MANIFEST.MF")
                                || StringUtils.endsWithIgnoreCase(entry.getName(), ".dsa")
                                || StringUtils.endsWithIgnoreCase(entry.getName(), ".rsa")
                                || StringUtils.endsWithIgnoreCase(entry.getName(), ".sf")
                                || StringUtils.containsIgnoreCase(entry.getName(), "meta-inf/sig-")) {
                            continue;
                        }
                        try (final InputStream is = ijar.getInputStream(entry);
                                final BufferedInputStream bis = new BufferedInputStream(is)) {
                            final String depName = containedDeps.get(entry.getName());
                            if (depName != null) {
                                File tempFile = File.createTempFile(depName, "-rfg_tmp_copy.jar", outputDir);
                                tempFile.deleteOnExit();
                                try (final OutputStream tos = FileUtils.openOutputStream(tempFile);
                                        final BufferedOutputStream btos = new BufferedOutputStream(tos)) {
                                    IOUtils.copy(bis, btos);
                                }
                                deobfQueue.add(
                                        new DeobfJob(depName, tempFile, job.sourceName + "!" + entry.getName(), true));
                                continue;
                            }

                            jos.putNextEntry(new JarEntry(entry.getName()));
                            if (StringUtils.endsWithIgnoreCase(entry.getName(), ".class")) {
                                byte[] data = IOUtils.toByteArray(bis);
                                IOUtils.write(Utilities.simpleRemapClass(data, combined), jos);
                            } else {
                                IOUtils.copy(bis, jos);
                            }
                            jos.closeEntry();
                        }
                    }
                }
            }

            Files.move(outFileTemp, outFile);
            if (job.deleteSource) {
                FileUtils.delete(job.sourceFile);
            }
        }
    }

    private static void transformManifest(Manifest mf) {
        final List<String> entriesToRemove = new ArrayList<>();
        for (Map.Entry<String, Attributes> mfEntry : mf.getEntries().entrySet()) {
            final Attributes attrs = mfEntry.getValue();
            final Object[] keys = attrs.keySet().toArray(new Object[0]);
            for (Object key : keys) {
                if (StringUtils.endsWith(key.toString(), "-Digest")) {
                    attrs.remove(key);
                }
            }
            if (attrs.size() == 0
                    || (attrs.size() == 1 && attrs.keySet().iterator().next().toString().equals("Name"))) {
                attrs.clear();
                entriesToRemove.add(mfEntry.getKey());
            }
        }
        entriesToRemove.forEach(mf.getEntries()::remove);
    }

    private static class DeobfJob {

        final String artifactName;
        final File sourceFile;
        final String sourceName;
        final boolean deleteSource;

        DeobfJob(String artifactName, File sourceFile, String sourceName, boolean deleteSource) {
            this.artifactName = artifactName;
            this.sourceFile = sourceFile;
            this.sourceName = sourceName;
            this.deleteSource = deleteSource;
        }
    }
}
