package com.gtnewhorizons.retrofuturagradle.mcp;

import java.io.File;
import java.io.IOException;
import java.net.URLClassLoader;
import java.util.Set;

import net.md_5.specialsource.JarMapping;
import net.md_5.specialsource.JarRemapper;
import net.md_5.specialsource.provider.ClassLoaderProvider;
import net.md_5.specialsource.provider.JarProvider;
import net.md_5.specialsource.provider.JointProvider;

import org.apache.commons.io.FileUtils;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.internal.file.copy.CopyAction;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.jvm.tasks.Jar;

import com.gtnewhorizons.retrofuturagradle.Constants;
import com.gtnewhorizons.retrofuturagradle.fgpatchers.ReobfExceptor;
import com.gtnewhorizons.retrofuturagradle.util.Utilities;

@CacheableTask
public abstract class ReobfuscatedJar extends Jar {

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getInputJar();

    @Input
    public abstract Property<String> getMcVersion();

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getSrg();

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getFieldCsv();

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getMethodCsv();

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getExceptorCfg();

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getRecompMcJar();

    @Input
    public abstract ListProperty<String> getExtraSrgEntries();

    @InputFiles
    @PathSensitive(PathSensitivity.NONE)
    public abstract ConfigurableFileCollection getExtraSrgFiles();

    @InputFiles
    @Classpath
    public abstract ConfigurableFileCollection getReferenceClasspath();

    /**
     * Sets the inputJar property to the output of the given Jar task, and copies all jar attributes (base name,
     * appendix, version, extension) except the classifier as default values for the output jar properties.
     */
    public void setInputJarFromTask(TaskProvider<Jar> task) {
        this.getInputJar().set(task.flatMap(Jar::getArchiveFile));
        this.getDestinationDirectory().convention(task.flatMap(Jar::getDestinationDirectory));
        this.getArchiveBaseName().convention(task.flatMap(Jar::getArchiveBaseName));
        this.getArchiveAppendix().convention(task.flatMap(Jar::getArchiveAppendix));
        this.getArchiveVersion().convention(task.flatMap(Jar::getArchiveVersion));
        this.getArchiveExtension().convention(task.flatMap(Jar::getArchiveExtension));
    }

    @Override
    protected void copy() {
        try {
            final File tmpDir = getTemporaryDir();
            final File tmpObfedJar = new File(tmpDir, "working.jar");
            final File recompJar = new File(tmpDir, "recomp.jar");
            final File tmpInjectedJar = new File(tmpDir, "inject.jar");
            FileUtils.copyFile(getInputJar().get().getAsFile(), tmpObfedJar);
            FileUtils.copyFile(getRecompMcJar().get().getAsFile(), recompJar);

            final File srg = File.createTempFile("reobf-default", ".srg", tmpDir);
            final File extraSrg = File.createTempFile("reobf-extra", ".srg", tmpDir);
            final ReobfExceptor exc = new ReobfExceptor();
            exc.deobfJar = tmpObfedJar;
            exc.toReobfJar = recompJar;
            exc.excConfig = getExceptorCfg().get().getAsFile();
            exc.fieldCSV = getFieldCsv().get().getAsFile();
            exc.methodCSV = getMethodCsv().get().getAsFile();
            exc.doFirstThings();

            exc.buildSrg(getSrg().get().getAsFile(), srg);
            FileUtils.writeLines(extraSrg, getExtraSrgEntries().get());

            final JarMapping mapping = new JarMapping();
            mapping.loadMappings(srg);
            mapping.loadMappings(extraSrg);
            for (File file : getExtraSrgFiles()) {
                mapping.loadMappings(file);
            }
            final JarRemapper remapper = new JarRemapper(null, mapping);

            try (net.md_5.specialsource.Jar inputJar = net.md_5.specialsource.Jar.init(tmpObfedJar)) {
                JointProvider inheritanceProviders = new JointProvider();
                inheritanceProviders.add(new JarProvider(inputJar));
                Set<File> cpFiles = getReferenceClasspath().getFiles();
                if (!cpFiles.isEmpty()) {
                    inheritanceProviders
                            .add(new ClassLoaderProvider(new URLClassLoader(Utilities.filesToURLArray(cpFiles))));
                }
                mapping.setFallbackInheritanceProvider(inheritanceProviders);

                remapper.remapJar(inputJar, tmpInjectedJar);
            }

            final File outputJar = super.getArchiveFile().get().getAsFile();
            FileUtils.copyFile(tmpInjectedJar, outputJar);

            if (!Constants.DEBUG_NO_TMP_CLEANUP) {
                FileUtils.deleteQuietly(tmpInjectedJar);
                FileUtils.deleteQuietly(tmpObfedJar);
                FileUtils.deleteQuietly(srg);
                FileUtils.deleteQuietly(extraSrg);
                FileUtils.deleteQuietly(recompJar);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    protected CopyAction createCopyAction() {
        // Make sure the default copy action doesn't run
        return null;
    }
}
