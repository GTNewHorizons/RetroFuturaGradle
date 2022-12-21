package com.gtnewhorizons.retrofuturagradle.util;

import java.io.File;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.FileTree;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

@DisableCachingByDefault(because = "Not worth caching zip copies")
public abstract class ExtractZipsTask extends DefaultTask {
    @InputFiles
    public abstract ConfigurableFileCollection getJars();

    @OutputDirectory
    public abstract DirectoryProperty getOutputDir();

    @TaskAction
    public void extract() {
        if (!getOutputDir().get().getAsFile().exists()) {
            getOutputDir().get().getAsFile().mkdirs();
        }
        for (File file : getJars()) {
            FileTree tree = getProject().zipTree(file);
            getProject().copy(cp -> {
                cp.from(tree);
                cp.into(getOutputDir());
            });
        }
    }
}
