package com.gtnewhorizons.retrofuturagradle.mcp;

import static org.objectweb.asm.Opcodes.ACC_FINAL;
import static org.objectweb.asm.Opcodes.ACC_PRIVATE;
import static org.objectweb.asm.Opcodes.ACC_PROTECTED;
import static org.objectweb.asm.Opcodes.ACC_PUBLIC;

import com.google.common.collect.ImmutableSet;
import com.gtnewhorizons.retrofuturagradle.json.MCInjectorStruct;
import com.gtnewhorizons.retrofuturagradle.util.RenamedAccessMap;
import com.gtnewhorizons.retrofuturagradle.util.Utilities;
import com.gtnewhorizons.specialsource174.Jar;
import com.gtnewhorizons.specialsource174.JarMapping;
import com.gtnewhorizons.specialsource174.JarRemapper;
import com.gtnewhorizons.specialsource174.RemapperProcessor;
import com.gtnewhorizons.specialsource174.provider.JarProvider;
import com.gtnewhorizons.specialsource174.provider.JointProvider;
import de.oceanlabs.mcp.mcinjector.MCInjectorImpl;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import javax.inject.Inject;
import org.apache.commons.collections4.iterators.EnumerationIterator;
import org.apache.commons.collections4.iterators.IteratorIterable;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.LineIterator;
import org.gradle.api.DefaultTask;
import org.gradle.api.Project;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

@CacheableTask
public abstract class DeobfuscateTask extends DefaultTask {
    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getInputJar();

    @InputFiles
    @PathSensitive(PathSensitivity.NONE)
    public abstract ConfigurableFileCollection getAccessTransformerFiles();

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getSrgFile();

    @InputFile
    @Optional
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getFieldCsv();

    @InputFile
    @Optional
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getMethodCsv();

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getExceptorCfg();

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getExceptorJson();

    @Input
    public abstract Property<Boolean> getIsApplyingMarkers();

    @Input
    @Optional
    public abstract Property<Boolean> getIsStrippingSynthetics();

    @OutputFile
    public abstract RegularFileProperty getOutputJar();

    private File taskTempDir;

    @Inject
    public DeobfuscateTask(Project proj) {
        getIsStrippingSynthetics().convention(false);
        getIsApplyingMarkers().convention(false);
    }

    @TaskAction
    void processJar() throws IOException {
        final File taskTempDir = getTemporaryDir();
        this.taskTempDir = taskTempDir;
        final File deobfedJar = new File(taskTempDir, "deobf.jar");
        final File exceptedJar = new File(taskTempDir, "excepted.jar");

        getLogger().lifecycle("Applying SpecialSource");
        final Set<File> atFiles = new ImmutableSet.Builder<File>()
                .addAll(getAccessTransformerFiles())
                .build();
        applySpecialSource(deobfedJar, atFiles);

        getLogger().lifecycle("Applying Exceptor");
        applyExceptor(deobfedJar, exceptedJar, new File(taskTempDir, "deobf.log"), atFiles);

        final boolean isStrippingSynths = getIsStrippingSynthetics().get();
        getLogger()
                .lifecycle("Cleaning up generated debuginfo{}", isStrippingSynths ? " and stripping synthetics" : "");
        cleanupJar(exceptedJar, getOutputJar().get().getAsFile(), isStrippingSynths);
    }

    private void applySpecialSource(File tempDeobfJar, Set<File> atFiles) throws IOException {
        final File originalInputFile = getInputJar().get().getAsFile();
        // Work on a copy to make sure the original jar doesn't get modified
        final File inputFile = new File(taskTempDir, "input.jar");
        FileUtils.copyFile(originalInputFile, inputFile);
        final JarMapping mapping = new JarMapping();
        mapping.loadMappings(getSrgFile().get().getAsFile());
        final Map<String, String> renames = new HashMap<>();
        for (File f : new File[] {
            getFieldCsv().getAsFile().getOrNull(), getMethodCsv().getAsFile().getOrNull()
        }) {
            if (f == null) {
                continue;
            }
            FileUtils.lineIterator(f).forEachRemaining(line -> {
                String[] parts = line.split(",");
                if (!"searge".equals(parts[0])) {
                    renames.put(parts[0], parts[1]);
                }
            });
        }

        // Load access transformers
        getLogger().lifecycle("Loading {} AccessTransformers", atFiles.size());
        RenamedAccessMap accessMap = new RenamedAccessMap(renames);
        for (File atFile : atFiles) {
            getLogger().info("{}", atFile.getPath());
            accessMap.loadAccessTransformer(atFile);
        }
        getLogger().lifecycle("Renamed {} AT entries", accessMap.getRenameCount());

        RemapperProcessor srgProcessor = new RemapperProcessor(null, mapping, null);
        RemapperProcessor atProcessor = new RemapperProcessor(null, null, accessMap);
        JarRemapper remapper = new JarRemapper(srgProcessor, mapping, atProcessor);

        final Jar input = Jar.init(inputFile);
        JointProvider inheritanceProviders = new JointProvider();
        inheritanceProviders.add(new JarProvider(input));
        mapping.setFallbackInheritanceProvider(inheritanceProviders);
        remapper.remapJar(input, tempDeobfJar);
    }

    private void applyExceptor(File deobfJar, File tempExceptorJar, File logFile, Set<File> atFiles)
            throws IOException {
        String json = null;
        if (getExceptorJson().isPresent()) {
            File exceptorJsonFile = getExceptorJson().get().getAsFile();
            final Map<String, MCInjectorStruct> struct = MCInjectorStruct.loadMCIJson(exceptorJsonFile);
            // TODO: Make this more readable, it's currently mostly a copy directly from FG
            for (File atFile : atFiles) {
                try (final FileInputStream fis = new FileInputStream(atFile);
                        final BufferedInputStream bis = new BufferedInputStream(fis);
                        final LineIterator lines = IOUtils.lineIterator(bis, StandardCharsets.UTF_8)) {
                    while (lines.hasNext()) {
                        // eg. "a.foo/bar ()desc # comment"
                        String line = lines.nextLine();
                        int commentIdx = line.indexOf('#');
                        if (commentIdx != -1) {
                            line = line.substring(0, commentIdx);
                        }
                        line = line.trim().replace('.', '/');
                        // now it's "a/foo/bar ()desc"
                        if (line.isEmpty()) continue;
                        String[] s = line.split(" ");
                        if (s.length == 2 && s[1].indexOf('$') > 0) {
                            String parent = s[1].substring(0, s[1].indexOf('$'));
                            for (MCInjectorStruct cls : new MCInjectorStruct[] {struct.get(parent), struct.get(s[1])}) {
                                if (cls != null && cls.innerClasses != null) {
                                    for (MCInjectorStruct.InnerClass inner : cls.innerClasses) {
                                        if (inner.inner_class.equals(s[1])) {
                                            int access = fixAccess(inner.getAccess(), s[0]);
                                            inner.access = (access == 0 ? null : Integer.toHexString(access));
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            File tmpJsonFile = new File(taskTempDir, "transformed.json");
            json = tmpJsonFile.getCanonicalPath();
            FileUtils.write(tmpJsonFile, Utilities.GSON.toJson(struct), StandardCharsets.UTF_8);
        }

        MCInjectorImpl.process(
                deobfJar.getCanonicalPath(),
                tempExceptorJar.getCanonicalPath(),
                getExceptorCfg().get().getAsFile().getCanonicalPath(),
                logFile.getCanonicalPath(),
                null,
                0,
                json,
                getIsApplyingMarkers().get(),
                true);
    }

    private void cleanupJar(File inputJar, File outputJar, boolean stripSynthetics) throws IOException {
        try (final ZipFile inZip = new ZipFile(inputJar);
                final FileOutputStream fos = new FileOutputStream(outputJar);
                final BufferedOutputStream bos = new BufferedOutputStream(fos);
                final ZipOutputStream out = new ZipOutputStream(bos)) {
            for (ZipEntry entry : new IteratorIterable<>(new EnumerationIterator<>(inZip.entries()))) {
                if (entry.getName().contains("META-INF")) continue;

                if (entry.isDirectory()) {
                    out.putNextEntry(entry);
                } else {
                    ZipEntry n = new ZipEntry(entry.getName());
                    n.setTime(entry.getTime());
                    out.putNextEntry(n);

                    byte[] entryContents = Utilities.readZipEntry(inZip, entry);

                    // correct source name
                    if (entry.getName().endsWith(".class")) {
                        final ClassNode node = Utilities.parseClassBytes(entryContents, entry.getName());
                        // Other asm-based class cleanup can be done here
                        if (stripSynthetics) {
                            stripClassSynthetics(node);
                        }
                        entryContents = Utilities.emitClassBytes(node, 0);
                    }

                    out.write(entryContents);
                }
            }
        }
    }

    private void stripClassSynthetics(ClassNode node) {
        if ((node.access & Opcodes.ACC_ENUM) == 0
                && !node.superName.equals("java/lang/Enum")
                && (node.access & Opcodes.ACC_SYNTHETIC) == 0) {
            // ^^ is for ignoring enums.

            for (FieldNode f : node.fields) {
                f.access = f.access & (0xffffffff - Opcodes.ACC_SYNTHETIC);
            }

            for (MethodNode m : node.methods) {
                m.access = m.access & (0xffffffff - Opcodes.ACC_SYNTHETIC);
            }
        }
    }

    private int fixAccess(int access, String target) {
        final int PROT_LEVEL_MASK = ACC_PUBLIC | ACC_PRIVATE | ACC_PROTECTED;
        int ret = access & ~PROT_LEVEL_MASK;
        int t = 0;

        if (target.startsWith("public")) t = ACC_PUBLIC;
        else if (target.startsWith("private")) t = ACC_PRIVATE;
        else if (target.startsWith("protected")) t = ACC_PROTECTED;

        switch (access & PROT_LEVEL_MASK) {
            case ACC_PRIVATE:
                ret |= t;
                break;
            case 0:
                ret |= (t != ACC_PRIVATE ? t : 0);
                break;
            case ACC_PROTECTED:
                ret |= (t != ACC_PRIVATE && t != 0 ? t : ACC_PROTECTED);
                break;
            case ACC_PUBLIC:
                ret |= ACC_PUBLIC;
                break;
        }

        if (target.endsWith("-f")) ret &= ~ACC_FINAL;
        else if (target.endsWith("+f")) ret |= ACC_FINAL;
        return ret;
    }
}
