package com.gtnewhorizons.retrofuturagradle.modutils;

import java.io.File;

import org.gradle.api.tasks.TaskAction;

import com.gtnewhorizons.retrofuturagradle.util.Utilities;

public abstract class DeobfuscateProfilerData extends DeobfuscateFileTaskBase {

    @TaskAction
    public void doDeobf() {
        final File inputFile = getInputFile().getAsFile().get();
        final File outputFile = getOutputFile().getAsFile().get();
        final Utilities.MappingsSet mappings = getMappings();

    }
}
