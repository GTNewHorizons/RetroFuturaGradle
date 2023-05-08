package com.gtnewhorizons.retrofuturagradle.mcp;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import javax.inject.Inject;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.text.StringEscapeUtils;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.Directory;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.internal.file.FileOperations;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.TaskAction;

@CacheableTask
public abstract class InjectTagsTask extends DefaultTask {

    /**
     * A key-value map of tags to inject into the project either by way of token substitution (hacky, deprecated) or
     * generating a small Java file with the tag values.
     */
    @Input
    @Optional
    public abstract MapProperty<String, Object> getTags();

    /**
     * Class package and name (e.g. org.mymod.Tags) to fill with the provided tags.
     */
    @Input
    @Optional
    public abstract Property<String> getOutputClassName();

    @OutputDirectory
    public abstract DirectoryProperty getOutputDir();

    /**
     * Whether to remove stale files from the output directory. True by default.
     */
    @Input
    public abstract Property<Boolean> getCleanOutputDir();

    @Inject
    protected abstract FileOperations getFileOperations();

    @Inject
    public InjectTagsTask() {
        getCleanOutputDir().convention(true);
    }

    @TaskAction
    public void injectTags() throws IOException {
        final File outDir = getOutputDir().get().getAsFile();
        final Map<String, Object> replacements = getTags().get();
        final String outClass = StringUtils.stripToNull(getOutputClassName().getOrNull());
        if (outClass != null) {
            final int lastDot = outClass.lastIndexOf('.');
            final String outPackage = (lastDot >= 0) ? outClass.substring(0, lastDot) : null;
            final String outClassName = (lastDot >= 0) ? outClass.substring(lastDot + 1) : outClass;
            final String outPath = (outPackage == null ? "" : outPackage.replace('.', '/') + "/") + outClassName
                    + ".java";
            final Directory outputDir = getOutputDir().get();
            if (getCleanOutputDir().get() && outputDir.getAsFile().isDirectory()) {
                try {
                    FileUtils.deleteDirectory(outputDir.getAsFile());
                } catch (IOException e) {
                    getLogger().warn("Could not clean output directory {}", outputDir.getAsFile(), e);
                }
            }
            final File outFile = outputDir.file(outPath).getAsFile();
            FileUtils.forceMkdirParent(outFile);
            final StringBuilder outWriter = new StringBuilder();
            if (outPackage != null) {
                outWriter.append("package ");
                outWriter.append(outPackage);
                outWriter.append(";\n\n");
            }
            outWriter.append("/** Auto-generated tags from RetroFuturaGradle */\n");
            outWriter.append("public class ");
            outWriter.append(outClassName);
            outWriter.append(" {\n    private ");
            outWriter.append(outClassName);
            outWriter.append("() {}\n\n");
            for (Map.Entry<String, Object> entry : replacements.entrySet()) {
                final Object e = entry.getValue();
                final String eType, eJava;
                if (e instanceof Integer) {
                    eType = "int";
                    eJava = Integer.toString((Integer) e);
                } else {
                    eType = "String";
                    eJava = '"' + StringEscapeUtils.escapeJava(e.toString()) + '"';
                }
                outWriter.append("    /** Auto-generated tag from RetroFuturaGradle */\n    public static final ");
                outWriter.append(eType);
                outWriter.append(' ');
                outWriter.append(entry.getKey());
                outWriter.append(" = ");
                outWriter.append(eJava);
                outWriter.append(";\n");
            }
            outWriter.append("}\n");
            FileUtils.writeStringToFile(outFile, outWriter.toString(), StandardCharsets.UTF_8);
        }
    }
}
