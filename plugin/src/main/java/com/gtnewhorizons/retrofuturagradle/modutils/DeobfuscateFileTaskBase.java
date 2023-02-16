package com.gtnewhorizons.retrofuturagradle.modutils;

import java.io.File;

import javax.inject.Inject;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.internal.file.FileOperations;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.options.Option;

import com.gtnewhorizons.retrofuturagradle.mcp.RemapSourceJarTask;
import com.gtnewhorizons.retrofuturagradle.util.Utilities;

/**
 * A base class for file deobfuscation utilities
 */
public abstract class DeobfuscateFileTaskBase extends DefaultTask {

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getFieldsCsv();

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getMethodsCsv();

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    @Optional
    public abstract RegularFileProperty getParamsCsv();

    @InputFiles
    @PathSensitive(PathSensitivity.NONE)
    @Optional
    public abstract ConfigurableFileCollection getExtraParamsCsvs();

    @Input
    @Optional
    public abstract Property<String> getGenericFieldsCsvName();

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getMinecraftJar();

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    @Option(option = "input", description = "The file to deobfuscate")
    public abstract RegularFileProperty getInputFile();

    @OutputFile
    @Option(option = "output", description = "Where to save the deobfuscated version")
    public abstract RegularFileProperty getOutputFile();

    @Inject
    protected abstract FileOperations getFileOperations();

    @Inject
    protected abstract ObjectFactory getObjectFactory();

    @Inject
    public DeobfuscateFileTaskBase() {
        getOutputFile().convention(getInputFile().map(rf -> {
            File f = rf.getAsFile();
            String path = f.getPath();
            int lastDot = path.lastIndexOf('.');
            if (lastDot != -1) {
                path = path.substring(0, lastDot + 1) + "deobf" + path.substring(lastDot);
            } else {
                path += ".deobf";
            }
            return getObjectFactory().fileProperty().fileValue(new File(path)).get();
        }));
    }

    public void configureFromDeobfMcTask(RemapSourceJarTask mcTask) {
        getFieldsCsv().set(mcTask.getFieldCsv());
        getMethodsCsv().set(mcTask.getMethodCsv());
        getParamsCsv().set(mcTask.getParamCsv());
        getExtraParamsCsvs().setFrom(mcTask.getExtraParamsCsvs());
        getGenericFieldsCsvName().set(mcTask.getGenericFieldsCsvName());
        getMinecraftJar().set(mcTask.getInputJar());
    }

    @Internal
    public Utilities.MappingsSet getMappings() {
        return Utilities.loadMappingCsvs(
                getMethodsCsv().getAsFile().get(),
                getFieldsCsv().getAsFile().get(),
                getParamsCsv().getAsFile().getOrNull(),
                getExtraParamsCsvs().getFiles(),
                getGenericFieldsCsvName().getOrNull());
    }
}
