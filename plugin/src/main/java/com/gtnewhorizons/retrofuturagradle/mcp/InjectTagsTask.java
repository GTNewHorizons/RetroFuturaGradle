package com.gtnewhorizons.retrofuturagradle.mcp;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

@CacheableTask
public abstract class InjectTagsTask extends DefaultTask {
    @Input
    public abstract MapProperty<String, Object> getTags();

    @InputFiles
    @PathSensitive(PathSensitivity.ABSOLUTE)
    public abstract ConfigurableFileCollection getSourceReplacementFiles();

    @Input
    public abstract Property<String> getOutputClassName();

    @OutputDirectory
    public abstract DirectoryProperty getOutputDir();

    public InjectTagsTask() {
        onlyIf(t -> !getTags().get().isEmpty());
    }

    @TaskAction
    public void injectTags() {
        //
    }
}
