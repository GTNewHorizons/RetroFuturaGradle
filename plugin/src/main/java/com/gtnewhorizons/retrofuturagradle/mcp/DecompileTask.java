package com.gtnewhorizons.retrofuturagradle.mcp;

import com.gtnewhorizons.retrofuturagradle.MinecraftExtension;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.gradle.api.DefaultTask;
import org.gradle.api.Project;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;

public abstract class DecompileTask extends DefaultTask {
    @InputFile
    abstract RegularFileProperty getInputJar();

    @OutputFile
    abstract RegularFileProperty getOutputJar();

    @InputFile
    abstract RegularFileProperty getFernflower();

    @InputDirectory
    abstract DirectoryProperty getPatches();

    @InputFile
    abstract RegularFileProperty getAstyleConfig();

    private File taskTempDir;

    @TaskAction
    public void decompileAndCleanup() throws IOException {
        taskTempDir = getTemporaryDir();

        getLogger().lifecycle("Decompiling the srg jar with fernflower");
        decompile();
    }

    private void decompile() throws IOException {
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
                                FileUtils.getFile(project.getBuildDir(), MCPTasks.MCP_DIR, "fernflower_log.log")));
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                    exec.setMaxHeapSize("512M");
                    final String javaExe = mcExt.getToolchainLauncher(project)
                            .get()
                            .getExecutablePath()
                            .getAsFile()
                            .getAbsolutePath();
                    exec.executable(javaExe);
                    System.err.printf("`%s` `%s`\n", javaExe, StringUtils.join(args, ";"));
                })
                .assertNormalExitValue();
        FileUtils.copyFile(ffoutfile, getOutputJar().getAsFile().get());
        FileUtils.delete(ffinpcopy);
        FileUtils.delete(ffoutfile);
    }
}
