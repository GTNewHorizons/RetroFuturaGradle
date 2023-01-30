package com.gtnewhorizons.retrofuturagradle.mcp;

import java.io.File;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.FileUtils;
import org.gradle.api.DefaultTask;
import org.gradle.api.Project;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.jvm.toolchain.JavaLanguageVersion;
import org.gradle.jvm.toolchain.JavaToolchainService;
import org.gradle.jvm.toolchain.JvmVendorSpec;
import org.gradle.work.DisableCachingByDefault;

import com.gtnewhorizons.retrofuturagradle.Constants;
import com.gtnewhorizons.retrofuturagradle.MinecraftExtension;
import com.gtnewhorizons.retrofuturagradle.util.HashUtils;
import com.gtnewhorizons.retrofuturagradle.util.IJarTransformTask;

@DisableCachingByDefault(because = "Uses an internal caching mechanism")
public abstract class DecompileTask extends DefaultTask implements IJarTransformTask {

    @OutputDirectory
    public abstract DirectoryProperty getCacheDir();

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getFernflower();

    @Override
    public void hashInputs(MessageDigest digest) {
        HashUtils.addPropertyToHash(digest, getFernflower());
    }

    @TaskAction
    public void decompileAndCleanup() throws IOException {
        final File taskTempDir = getTemporaryDir();

        final DigestUtils digests = new DigestUtils(DigestUtils.getSha256Digest());
        final String fernflowerChecksum = digests.digestAsHex(getFernflower().get().getAsFile());
        final String inputFileChecksum = digests.digestAsHex(getInputJar().get().getAsFile());
        final File cachedOutputFile = new File(
                getCacheDir().get().getAsFile(),
                fernflowerChecksum + "-" + inputFileChecksum + ".jar");
        if (cachedOutputFile.exists()) {
            getLogger().lifecycle("Using cached decompiled jar from " + cachedOutputFile.getPath());
            FileUtils.copyFile(cachedOutputFile, getOutputJar().get().getAsFile());
            return;
        } else {
            getLogger().lifecycle(
                    "Didn't find cached decompiled jar, decompiling and saving to " + cachedOutputFile.getPath());
        }

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
            JavaToolchainService jts = project.getExtensions().findByType(JavaToolchainService.class);
            final String javaExe = jts.launcherFor(toolchain -> {
                toolchain.getLanguageVersion().set(JavaLanguageVersion.of(17));
                toolchain.getVendor().set(JvmVendorSpec.ADOPTIUM);
            }).get().getExecutablePath().getAsFile().getAbsolutePath();
            exec.executable(javaExe);
        }).assertNormalExitValue();
        FileUtils.delete(ffinpcopy);

        FileUtils.forceMkdirParent(cachedOutputFile);
        FileUtils.copyFile(ffoutfile, cachedOutputFile);
        FileUtils.copyFile(ffoutfile, getOutputJar().get().getAsFile());

        final long postDecompileMs = System.currentTimeMillis();
        getLogger().lifecycle("  Decompiling took " + (postDecompileMs - preDecompileMs) + " ms");

        if (!Constants.DEBUG_NO_TMP_CLEANUP) {
            FileUtils.deleteQuietly(ffoutfile);
        }
    }
}
