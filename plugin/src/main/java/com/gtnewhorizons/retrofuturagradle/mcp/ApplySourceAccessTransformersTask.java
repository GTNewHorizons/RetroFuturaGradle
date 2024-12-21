package com.gtnewhorizons.retrofuturagradle.mcp;

import static com.gtnewhorizons.retrofuturagradle.Constants.JST_TOOL_ARTIFACT;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import org.apache.commons.io.FileUtils;
import org.gradle.api.DefaultTask;
import org.gradle.api.Project;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.logging.Logger;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.jvm.toolchain.JavaLauncher;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.gtnewhorizons.retrofuturagradle.util.HashUtils;
import com.gtnewhorizons.retrofuturagradle.util.IJarTransformTask;
import com.gtnewhorizons.retrofuturagradle.util.MessageDigestConsumer;

@CacheableTask
public abstract class ApplySourceAccessTransformersTask extends DefaultTask implements IJarTransformTask {

    @InputFiles
    @PathSensitive(PathSensitivity.NONE)
    public abstract ConfigurableFileCollection getAccessTransformerFiles();

    @Override
    public MessageDigestConsumer hashInputs() {
        return HashUtils.addPropertyToHash(getAccessTransformerFiles());
    }

    @InputFiles
    @Classpath
    public abstract ConfigurableFileCollection getCompileClasspath();

    @Internal
    public abstract Property<JavaLauncher> getJava17Launcher();

    @TaskAction
    public void applyForgeAccessTransformers() throws IOException {

        if (getAccessTransformerFiles().isEmpty()) {
            FileUtils.copyFile(getInputJar().get().getAsFile(), getOutputJar().get().getAsFile());
            return;
        }

        final Project project = getProject();
        final Logger logger = getLogger();
        File toolExecutable = resolveTool(getProject(), JST_TOOL_ARTIFACT);
        final List<String> jvmArgs = ImmutableList.of("-Xmx4g");
        final List<String> programArgs = new ArrayList<>();

        programArgs.add("--enable-accesstransformers");
        final Set<File> atFiles = new ImmutableSet.Builder<File>().addAll(getAccessTransformerFiles()).build();
        for (File atFile : atFiles) {
            File patched = patchInvalidAccessTransformer(atFile);
            logger.lifecycle("Applying access transformer: " + patched);
            programArgs.add("--access-transformer");
            programArgs.add(patched.getAbsolutePath());
        }

        logger.lifecycle("Using JST: " + toolExecutable);
        File inputJar = getInputJar().get().getAsFile();
        File outputJar = getOutputJar().get().getAsFile();

        programArgs.add(inputJar.getAbsolutePath());
        programArgs.add(outputJar.getAbsolutePath());

        logger.lifecycle("Program Args: " + programArgs);

        project.javaexec(exec -> {
            exec.classpath(toolExecutable);
            try {
                exec.setStandardOutput(
                        FileUtils.openOutputStream(
                                FileUtils.getFile(project.getBuildDir(), MCPTasks.RFG_DIR, "jst_log.log")));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            exec.setMinHeapSize("4G");
            exec.setMaxHeapSize("4G");
            exec.setJvmArgs(jvmArgs);
            exec.setArgs(programArgs);
            exec.setExecutable(getJava17Launcher().get().getExecutablePath().toString());

        }).assertNormalExitValue();

    }

    private File patchInvalidAccessTransformer(File atFile) {
        // Fix known invalid AT files shipped by Forge, specifically forge_at.cfg
        if (!atFile.getName().equals("forge_at.cfg")) {
            return atFile;
        }

        final File patched = new File(atFile.getParentFile(), atFile.getName() + ".patched");
        final String regex = ";\\)$";
        final String replacement = ";\\)V";
        try (BufferedReader reader = new BufferedReader(new FileReader(atFile));
                BufferedWriter writer = new BufferedWriter(new FileWriter(patched))) {
            String line;
            while ((line = reader.readLine()) != null) {
                writer.write(line.replaceAll(regex, replacement));
                writer.newLine();
            }

        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return patched;
    }

    // Stuff borrowed from NeoGradle to get it to work -- refactor as needed

    public static File resolveTool(final Project project, final String tool) {
        return resolveTool(
                () -> project.getConfigurations().detachedConfiguration(project.getDependencies().create(tool))
                        .getFiles().iterator().next());
    }

    private static <T> T resolveTool(final Supplier<T> searcher) {
        // Return the resolved artifact
        return searcher.get();
    }
}
