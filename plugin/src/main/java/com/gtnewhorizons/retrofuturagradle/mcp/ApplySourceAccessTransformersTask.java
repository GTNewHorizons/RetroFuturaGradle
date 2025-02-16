package com.gtnewhorizons.retrofuturagradle.mcp;

import static com.gtnewhorizons.retrofuturagradle.Constants.JST_TOOL_ARTIFACT;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import javax.inject.Inject;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.output.TeeOutputStream;
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
import org.gradle.process.ExecOperations;

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
    public abstract Property<JavaLauncher> getJavaLauncher();

    @Inject
    protected abstract ExecOperations getExecOperations();

    @TaskAction
    public void applyForgeAccessTransformers() throws IOException {

        if (getAccessTransformerFiles().isEmpty()) {
            Files.copy(
                    getInputJar().get().getAsFile().toPath(),
                    getOutputJar().get().getAsFile().toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
            return;
        }

        final Project project = getProject();
        final Logger logger = getLogger();
        final File toolExecutable = resolveTool(getProject(), JST_TOOL_ARTIFACT);
        final List<String> programArgs = new ArrayList<>();

        programArgs.add("--enable-accesstransformers");
        final Set<File> atFiles = new ImmutableSet.Builder<File>().addAll(getAccessTransformerFiles()).build();
        final List<String> loggedAtFiles = new ArrayList<>(atFiles.size());
        for (File atFile : atFiles) {
            final File patched = patchInvalidAccessTransformer(atFile);
            loggedAtFiles.add(patched.toString());
            programArgs.add("--access-transformer");
            programArgs.add(patched.getAbsolutePath());
        }
        logger.lifecycle("Applying access transformers: {}", loggedAtFiles);

        logger.info("Using JST: {}", toolExecutable);
        final File inputJar = getInputJar().get().getAsFile();
        final File outputJar = getOutputJar().get().getAsFile();

        final String libraryPaths = getCompileClasspath().getFiles().stream().map(File::getAbsolutePath)
                .collect(Collectors.joining(System.lineSeparator()));
        final File librariesFile = project.getResources().getText().fromString(libraryPaths)
                .asFile(StandardCharsets.UTF_8.name());
        programArgs.add("--libraries-list=" + librariesFile.getAbsolutePath());

        programArgs.add(inputJar.getAbsolutePath());
        programArgs.add(outputJar.getAbsolutePath());

        logger.info("Program Args: {}", programArgs);

        getExecOperations().javaexec(exec -> {
            exec.classpath(toolExecutable);
            try {
                final OutputStream logFileStream = FileUtils.openOutputStream(
                        FileUtils.getFile(
                                project.getLayout().getBuildDirectory().get().getAsFile(),
                                MCPTasks.RFG_DIR,
                                "jst_log_" + getName() + ".log"));
                final OutputStream logStream = new TeeOutputStream(logFileStream, System.out);
                exec.setStandardOutput(logStream);
                exec.setErrorOutput(logStream);
                logFileStream
                        .write(String.format("%s %s%n", toolExecutable, programArgs).getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            exec.setMinHeapSize("512M");
            exec.setMaxHeapSize("2G");
            exec.setArgs(programArgs);
            exec.setExecutable(getJavaLauncher().get().getExecutablePath().toString());
        }).assertNormalExitValue();

    }

    private File patchInvalidAccessTransformer(File atFile) {
        // Fix known invalid AT files shipped by Forge, specifically forge_at.cfg
        if (!atFile.getName().equals("forge_at.cfg")) {
            return atFile;
        }

        final File patched = new File(atFile.getParentFile(), atFile.getName() + ".patched");
        try {
            final StringBuilder writer = new StringBuilder((int) Math.max(1024, atFile.length()));
            for (final String line : Files.readAllLines(atFile.toPath(), StandardCharsets.UTF_8)) {
                writer.append(line);
                if (line.endsWith(";)")) {
                    writer.append('V');
                }
                writer.append('\n');
            }
            Files.write(patched.toPath(), writer.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return patched;
    }

    // Stuff borrowed from NeoGradle to get it to work -- refactor as needed

    public static File resolveTool(final Project project, final String tool) {
        return project.getConfigurations().detachedConfiguration(project.getDependencies().create(tool)).getFiles()
                .iterator().next();
    }
}
