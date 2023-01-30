package com.gtnewhorizons.retrofuturagradle.modutils;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;

import net.md_5.specialsource.Jar;
import net.md_5.specialsource.JarMapping;
import net.md_5.specialsource.JarRemapper;
import net.md_5.specialsource.RemapperProcessor;
import net.md_5.specialsource.provider.JarProvider;
import net.md_5.specialsource.provider.JointProvider;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.gradle.api.artifacts.transform.CacheableTransform;
import org.gradle.api.artifacts.transform.InputArtifact;
import org.gradle.api.artifacts.transform.InputArtifactDependencies;
import org.gradle.api.artifacts.transform.TransformAction;
import org.gradle.api.artifacts.transform.TransformOutputs;
import org.gradle.api.artifacts.transform.TransformParameters;
import org.gradle.api.artifacts.transform.VariantTransformConfigurationException;
import org.gradle.api.file.FileCollection;
import org.gradle.api.file.FileSystemLocation;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.internal.file.FileOperations;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.CompileClasspath;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;

@CacheableTransform
public abstract class DependencyDeobfuscationTransform
        implements TransformAction<DependencyDeobfuscationTransform.Parameters> {

    interface Parameters extends TransformParameters {

        @InputFile
        @PathSensitive(PathSensitivity.NONE)
        public abstract RegularFileProperty getSrgFile();

        @InputFile
        @PathSensitive(PathSensitivity.NONE)
        public abstract RegularFileProperty getFieldsCsv();

        @InputFile
        @PathSensitive(PathSensitivity.NONE)
        public abstract RegularFileProperty getMethodsCsv();

        @InputFile
        @PathSensitive(PathSensitivity.NONE)
        public abstract RegularFileProperty getMinecraftJar();
    }

    @Inject
    protected abstract FileOperations getFileOps();

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

    private void runTransform(TransformOutputs outputs) throws IOException {
        final File inputLocation = getInputArtifact().get().getAsFile();
        final File outputDir = inputLocation.getParentFile();
        final String tempCopyName = StringUtils.removeEnd(inputLocation.getName(), ".jar") + "-rfg_tmp_copy.jar";
        final File tempFile = new File(outputDir, tempCopyName);
        final String outFileName = StringUtils.removeEnd(inputLocation.getName(), ".jar") + "-deobf.jar";
        final File outFile = new File(outputDir, outFileName);
        FileUtils.copyFile(inputLocation, tempFile);
        final FileCollection dependencies = getDependencies();
        final FileOperations fileOps = getFileOps();
        final Parameters parameters = getParameters();

        final File srgFile = parameters.getSrgFile().get().getAsFile();
        final File fieldsCsv = parameters.getFieldsCsv().get().getAsFile();
        final File methodsCsv = parameters.getMethodsCsv().get().getAsFile();
        final File mcJar = parameters.getMinecraftJar().get().getAsFile();

        final JarMapping mapping = new JarMapping();
        mapping.loadMappings(srgFile);
        final Map<String, String> renames = new HashMap<>();
        for (File f : new File[] { fieldsCsv, methodsCsv }) {
            if (f == null) {
                continue;
            }
            FileUtils.lineIterator(f).forEachRemaining(line -> {
                String[] parts = line.split(",");
                if (!"searge".equals(parts[0])) {
                    renames.put(parts[0], parts[1]);
                }
            });
        }

        RemapperProcessor srgProcessor = new RemapperProcessor(null, mapping, null);
        JarRemapper remapper = new JarRemapper(srgProcessor, mapping, null);

        try (final Jar mc = Jar.init(tempFile); final Jar input = Jar.init(tempFile)) {
            final List<Jar> depJars = new ArrayList<>();
            JointProvider inheritanceProviders = new JointProvider();
            inheritanceProviders.add(new JarProvider(mc));
            inheritanceProviders.add(new JarProvider(input));
            for (File dep : dependencies) {
                if (dep.isFile() && dep.getName().endsWith(".jar")) {
                    Jar depJar = Jar.init(dep);
                    inheritanceProviders.add(new JarProvider(depJar));
                    depJars.add(depJar);
                }
            }
            mapping.setFallbackInheritanceProvider(inheritanceProviders);
            remapper.remapJar(input, outFile);
            depJars.forEach(jar -> {
                try {
                    jar.close();
                } catch (Exception e) {
                    // We don't really care about file closing errors
                    e.printStackTrace();
                }
            });
        }

        FileUtils.deleteQuietly(tempFile);
        outputs.file(outFile);
    }
}
