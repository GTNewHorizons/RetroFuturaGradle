package com.gtnewhorizons.retrofuturagradle.mcp;

import com.gtnewhorizons.retrofuturagradle.MinecraftExtension;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.apache.commons.io.FileUtils;
import org.gradle.api.DefaultTask;
import org.gradle.api.Project;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.jvm.toolchain.JavaLanguageVersion;
import org.gradle.jvm.toolchain.JavaToolchainService;
import org.gradle.jvm.toolchain.JvmVendorSpec;

@CacheableTask
public abstract class DecompileTask extends DefaultTask {
    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getInputJar();

    @OutputFile
    public abstract RegularFileProperty getOutputJar();

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getFernflower();

    @TaskAction
    public void decompileAndCleanup() throws IOException {
        final File taskTempDir = getTemporaryDir();

        getLogger().lifecycle("Decompiling the srg jar with fernflower");
        final long preDecompileMs = System.currentTimeMillis();

        Project project = getProject();
        final File ffoutdir = new File(taskTempDir, "ff-out");
        ffoutdir.mkdirs();
        final File ffinpcopy = new File(taskTempDir, "mc.jar");
        final File ffoutfile = new File(ffoutdir, "mc.jar");
        FileUtils.copyFile(getInputJar().get().getAsFile(), ffinpcopy);
        project.javaexec(exec -> {
                    exec.classpath(getFernflower().get());
                    MinecraftExtension mcExt = project.getExtensions().findByType(MinecraftExtension.class);
                    List<String> args = new ArrayList<>(Objects.requireNonNull(mcExt)
                            .getFernflowerArguments()
                            .get());
                    args.add(ffinpcopy.getAbsolutePath());
                    args.add(ffoutdir.getAbsolutePath());
                    exec.args(args);
                    exec.setWorkingDir(getFernflower().get().getAsFile().getParentFile());
                    try {
                        exec.setStandardOutput(FileUtils.openOutputStream(
                                FileUtils.getFile(project.getBuildDir(), MCPTasks.RFG_DIR, "fernflower_log.log")));
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                    exec.setMinHeapSize("768M");
                    exec.setMaxHeapSize("768M");
                    JavaToolchainService jts = project.getExtensions().findByType(JavaToolchainService.class);
                    final String javaExe = jts.launcherFor(toolchain -> {
                                toolchain.getLanguageVersion().set(JavaLanguageVersion.of(17));
                                toolchain.getVendor().set(JvmVendorSpec.ADOPTIUM);
                            })
                            .get()
                            .getExecutablePath()
                            .getAsFile()
                            .getAbsolutePath();
                    exec.executable(javaExe);
                })
                .assertNormalExitValue();
        FileUtils.delete(ffinpcopy);
        FileUtils.copyFile(ffoutfile, getOutputJar().get().getAsFile());

        final long postDecompileMs = System.currentTimeMillis();
        getLogger().lifecycle("  Decompiling took " + (postDecompileMs - preDecompileMs) + " ms");
    }
}
