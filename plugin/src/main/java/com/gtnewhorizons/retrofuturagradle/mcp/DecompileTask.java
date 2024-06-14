package com.gtnewhorizons.retrofuturagradle.mcp;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.channels.FileLock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import javax.inject.Inject;

import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.FileUtils;
import org.gradle.api.DefaultTask;
import org.gradle.api.Project;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.CompileClasspath;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.jvm.toolchain.JavaLauncher;
import org.gradle.process.JavaForkOptions;
import org.gradle.work.DisableCachingByDefault;
import org.gradle.workers.WorkAction;
import org.gradle.workers.WorkParameters;
import org.gradle.workers.WorkQueue;
import org.gradle.workers.WorkerExecutor;

import com.gtnewhorizons.retrofuturagradle.Constants;
import com.gtnewhorizons.retrofuturagradle.MinecraftExtension;
import com.gtnewhorizons.retrofuturagradle.fg23shadow.org.jetbrains.java.decompiler.main.DecompilerContext;
import com.gtnewhorizons.retrofuturagradle.fg23shadow.org.jetbrains.java.decompiler.main.decompiler.BaseDecompiler;
import com.gtnewhorizons.retrofuturagradle.fg23shadow.org.jetbrains.java.decompiler.main.decompiler.PrintStreamLogger;
import com.gtnewhorizons.retrofuturagradle.fg23shadow.org.jetbrains.java.decompiler.main.extern.IFernflowerPreferences;
import com.gtnewhorizons.retrofuturagradle.mcp.fg23.AdvancedJadRenamer;
import com.gtnewhorizons.retrofuturagradle.mcp.fg23.ArtifactSaver;
import com.gtnewhorizons.retrofuturagradle.mcp.fg23.ByteCodeProvider;
import com.gtnewhorizons.retrofuturagradle.util.HashUtils;
import com.gtnewhorizons.retrofuturagradle.util.IJarTransformTask;
import com.gtnewhorizons.retrofuturagradle.util.MessageDigestConsumer;

@DisableCachingByDefault(because = "Uses an internal caching mechanism")
public abstract class DecompileTask extends DefaultTask implements IJarTransformTask {

    @OutputDirectory
    public abstract DirectoryProperty getCacheDir();

    @InputFile
    @Optional
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getFernflower();

    @Input
    public abstract Property<Integer> getMinorMcVersion();

    @InputFiles
    @CompileClasspath
    public abstract ConfigurableFileCollection getClasspath();

    @Internal
    public abstract Property<JavaLauncher> getJava8Launcher();

    @Internal
    public abstract Property<JavaLauncher> getJava17Launcher();

    @Inject
    public DecompileTask() {
        getMinorMcVersion().convention(7);
    }

    @Override
    public MessageDigestConsumer hashInputs() {
        return HashUtils.addPropertyToHash(getFernflower());
    }

    @Inject
    abstract public WorkerExecutor getWorkerExecutor();

    @Internal
    public abstract Property<RfgCacheService> getCacheService();

    @TaskAction
    public void decompileAndCleanup() throws IOException {
        final File taskTempDir = getTemporaryDir();
        final int minorMcVer = getMinorMcVersion().get();

        final DigestUtils digests = new DigestUtils(DigestUtils.getSha256Digest());
        final String fernflowerChecksum = (minorMcVer <= 8) ? digests.digestAsHex(getFernflower().get().getAsFile())
                : "1.0.342";
        final String inputFileChecksum = digests.digestAsHex(getInputJar().get().getAsFile());
        final File cachedOutputFile = new File(
                getCacheDir().get().getAsFile(),
                fernflowerChecksum + "-" + inputFileChecksum + ".jar");
        try (final FileLock ignored = getCacheService().get().lockCache(true)) {
            if (cachedOutputFile.exists()) {
                getLogger().lifecycle("Using cached decompiled jar from " + cachedOutputFile.getPath());
                FileUtils.copyFile(cachedOutputFile, getOutputJar().get().getAsFile());
                return;
            } else {
                getLogger().lifecycle(
                        "Didn't find cached decompiled jar, decompiling and saving to " + cachedOutputFile.getPath());
            }
        }

        getLogger().lifecycle("Decompiling the srg jar with fernflower");
        final long preDecompileMs = System.currentTimeMillis();

        Project project = getProject();
        final File ffoutdir = new File(taskTempDir, "ff-out");
        ffoutdir.mkdirs();
        final File ffinpcopy = new File(taskTempDir, "mc.jar");
        final File ffoutfile = new File(ffoutdir, "mc.jar");
        FileUtils.copyFile(getInputJar().get().getAsFile(), ffinpcopy);
        if (minorMcVer <= 8) {
            decompileFg12(project, ffoutdir, ffinpcopy);
        } else {
            decompileFg23(project, ffoutdir, ffinpcopy);
        }
        FileUtils.delete(ffinpcopy);

        try (final FileLock ignored = getCacheService().get().lockCache(false)) {
            FileUtils.forceMkdirParent(cachedOutputFile);
            FileUtils.copyFile(ffoutfile, cachedOutputFile);
        }
        FileUtils.copyFile(ffoutfile, getOutputJar().get().getAsFile());

        final long postDecompileMs = System.currentTimeMillis();
        getLogger().lifecycle("  Decompiling took " + (postDecompileMs - preDecompileMs) + " ms");

        if (!Constants.DEBUG_NO_TMP_CLEANUP) {
            FileUtils.deleteQuietly(ffoutfile);
        }
    }

    private void decompileFg12(Project project, File ffoutdir, File ffinpcopy) {
        project.javaexec(exec -> {
            exec.classpath(getFernflower().get());
            MinecraftExtension mcExt = project.getExtensions().findByType(MinecraftExtension.class);
            List<String> args = new ArrayList<>(Objects.requireNonNull(mcExt).getFernflowerArguments().get());
            args.add(ffinpcopy.getAbsolutePath());
            args.add(ffoutdir.getAbsolutePath());
            exec.args(args);
            exec.setWorkingDir(getFernflower().get().getAsFile().getParentFile());
            try {
                exec.setStandardOutput(
                        FileUtils.openOutputStream(
                                FileUtils.getFile(project.getBuildDir(), MCPTasks.RFG_DIR, "fernflower_log.log")));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            exec.setMinHeapSize("768M");
            exec.setMaxHeapSize("768M");
            final String javaExe = getJava17Launcher().get().getExecutablePath().getAsFile().getAbsolutePath();
            exec.executable(javaExe);
        }).assertNormalExitValue();
    }

    private void decompileFg23(Project project, File ffoutdir, File ffinpcopy) {
        final File tempDir = getTemporaryDir();
        final WorkQueue queue = getWorkerExecutor().processIsolation(pws -> {
            final JavaForkOptions fork = pws.getForkOptions();
            fork.setMinHeapSize("3072M");
            fork.setMaxHeapSize("3072M");
            final String javaExe = getJava8Launcher().get().getExecutablePath().getAsFile().getAbsolutePath();
            // We can't use Java 17 so at least use some tuning options that are the defaults in newer versions
            fork.jvmArgs("-XX:+UnlockExperimentalVMOptions", "-XX:+UseG1GC", "-XX:+AggressiveOpts");
            fork.executable(javaExe);
        });
        queue.submit(Fg23DecompTask.class, args -> {
            // setup args
            args.getTempDir().set(tempDir);
            args.getLogFile().set(FileUtils.getFile(project.getBuildDir(), MCPTasks.RFG_DIR, "fernflower_log.log"));
            args.getInputJar().set(ffinpcopy);
            args.getOutputDir().set(ffoutdir);
            args.getClasspath().setFrom(this.getClasspath());
        });
        queue.await();
    }

    public interface Fg23DecompArgs extends WorkParameters {

        DirectoryProperty getTempDir();

        RegularFileProperty getLogFile();

        RegularFileProperty getInputJar();

        RegularFileProperty getOutputDir();

        ConfigurableFileCollection getClasspath();
    }

    public static abstract class Fg23DecompTask implements WorkAction<Fg23DecompArgs> {

        @Override
        public void execute() {
            try {
                Fg23DecompArgs settings = getParameters();

                Map<String, Object> mapOptions = new HashMap<>();
                // "-din=1", "-rbr=1", "-dgs=1", "-asc=1", "-rsy=1", "-iec=1", "-jvn=1", "-log=TRACE", "-cfg",
                // "{libraries}", "{input}", "{output}"
                mapOptions.put(IFernflowerPreferences.DECOMPILE_INNER, "1");
                mapOptions.put(IFernflowerPreferences.DECOMPILE_GENERIC_SIGNATURES, "1");
                mapOptions.put(IFernflowerPreferences.ASCII_STRING_CHARACTERS, "1");
                mapOptions.put(IFernflowerPreferences.INCLUDE_ENTIRE_CLASSPATH, "1");
                mapOptions.put(IFernflowerPreferences.REMOVE_SYNTHETIC, "1");
                mapOptions.put(IFernflowerPreferences.REMOVE_BRIDGE, "1");
                mapOptions.put(IFernflowerPreferences.LITERALS_AS_IS, "0");
                mapOptions.put(IFernflowerPreferences.UNIT_TEST_MODE, "0");
                mapOptions.put(IFernflowerPreferences.MAX_PROCESSING_METHOD, "0");
                mapOptions.put(DecompilerContext.RENAMER_FACTORY, AdvancedJadRenamer.Factory.class.getName());

                // FernFlowerSettings settings = new FernFlowerSettings(tempDir, in, tempJar,
                // Constants.getTaskLogFile(getProject(), getName() + ".log"), classpath.getFiles(), mapOptions);

                PrintStreamLogger logger = new PrintStreamLogger(
                        new PrintStream(settings.getLogFile().getAsFile().get()));
                BaseDecompiler decompiler = new BaseDecompiler(
                        new ByteCodeProvider(),
                        new ArtifactSaver(settings.getOutputDir().getAsFile().get()),
                        mapOptions,
                        logger);

                decompiler.addSpace(settings.getInputJar().getAsFile().get(), true);
                for (File library : settings.getClasspath()) {
                    decompiler.addSpace(library, false);
                }

                decompiler.decompileContext();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
