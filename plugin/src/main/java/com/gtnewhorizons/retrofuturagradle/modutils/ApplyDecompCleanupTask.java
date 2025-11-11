package com.gtnewhorizons.retrofuturagradle.modutils;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import javax.inject.Inject;

import org.apache.commons.collections4.iterators.IteratorIterable;
import org.apache.commons.io.FileUtils;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.specs.Specs;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.TaskAction;

import com.gtnewhorizons.retrofuturagradle.fgpatchers.GLConstantFixer;
import com.gtnewhorizons.retrofuturagradle.fgpatchers.McpCleanupFg12;

public abstract class ApplyDecompCleanupTask extends DefaultTask {

    @InputFiles
    public abstract ConfigurableFileCollection getSourceDirectories();

    @Inject
    public ApplyDecompCleanupTask() {
        this.getOutputs().upToDateWhen(Specs.SATISFIES_NONE);
    }

    @TaskAction
    public void cleanup() throws IOException {
        final GLConstantFixer glFixer = new GLConstantFixer();
        for (File dir : getSourceDirectories().getFiles()) {
            if (dir.exists()) {
                getLogger().lifecycle("Running cleanup on {}", dir.getPath());
                for (File javaFile : new IteratorIterable<>(
                        FileUtils.iterateFiles(dir, new String[] { "java" }, true))) {
                    if (javaFile.isFile()) {
                        final String source = FileUtils.readFileToString(javaFile, StandardCharsets.UTF_8);
                        String patched = McpCleanupFg12.cleanup(source, false);
                        patched = glFixer.fixOGL(patched);
                        if (!patched.equals(source)) {
                            getLogger().info("Patched {}", javaFile);
                            FileUtils.writeStringToFile(javaFile, patched, StandardCharsets.UTF_8);
                        }
                    }
                }
            }
        }
    }

}
