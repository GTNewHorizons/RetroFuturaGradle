package com.gtnewhorizons.retrofuturagradle.mcp;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;

public abstract class MergeSidedJarsTask extends DefaultTask {
    @InputFile
    public abstract RegularFileProperty getMergeConfigFile();

    @InputFile
    public abstract RegularFileProperty getClientJar();

    @InputFile
    public abstract RegularFileProperty getServerJar();

    @Input
    public abstract Property<String> getMcVersion();

    @OutputFile
    public abstract RegularFileProperty getMergedJar();

    @TaskAction
    void mergeJars() {
        // TODO
    }
}
