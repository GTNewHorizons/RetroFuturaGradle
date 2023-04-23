package com.gtnewhorizons.retrofuturagradle.mcp;

import static org.objectweb.asm.Opcodes.ACC_FINAL;
import static org.objectweb.asm.Opcodes.ACC_PRIVATE;
import static org.objectweb.asm.Opcodes.ACC_PROTECTED;
import static org.objectweb.asm.Opcodes.ACC_PUBLIC;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Comparator;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarFile;
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
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import com.google.common.collect.ImmutableSet;
import com.gtnewhorizons.retrofuturagradle.Constants;
import com.gtnewhorizons.retrofuturagradle.fg12shadow.de.oceanlabs.mcp.mcinjector.MCInjectorImpl;
import com.gtnewhorizons.retrofuturagradle.fg12shadow.net.md_5.specialsource.Jar;
import com.gtnewhorizons.retrofuturagradle.fg12shadow.net.md_5.specialsource.JarMapping;
import com.gtnewhorizons.retrofuturagradle.fg12shadow.net.md_5.specialsource.JarRemapper;
import com.gtnewhorizons.retrofuturagradle.fg12shadow.net.md_5.specialsource.RemapperProcessor;
import com.gtnewhorizons.retrofuturagradle.fg12shadow.net.md_5.specialsource.provider.JarProvider;
import com.gtnewhorizons.retrofuturagradle.fg12shadow.net.md_5.specialsource.provider.JointProvider;
import com.gtnewhorizons.retrofuturagradle.fg23shadow.de.oceanlabs.mcp.mcinjector.LVTNaming;
import com.gtnewhorizons.retrofuturagradle.json.MCInjectorStruct;
import com.gtnewhorizons.retrofuturagradle.util.HashUtils;
import com.gtnewhorizons.retrofuturagradle.util.IJarTransformTask;
import com.gtnewhorizons.retrofuturagradle.util.RenamedAccessMapFG12;
import com.gtnewhorizons.retrofuturagradle.util.RenamedAccessMapFG23;
import com.gtnewhorizons.retrofuturagradle.util.Utilities;

@CacheableTask
public abstract class DeobfuscateTask extends DefaultTask implements IJarTransformTask {

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

    @Input
    public abstract Property<Integer> getMinorMcVersion();

    @Override
    public void hashInputs(MessageDigest digest) {
        HashUtils.addPropertyToHash(digest, getAccessTransformerFiles());
        HashUtils.addPropertyToHash(digest, getSrgFile());
        HashUtils.addPropertyToHash(digest, getFieldCsv());
        HashUtils.addPropertyToHash(digest, getMethodCsv());
        HashUtils.addPropertyToHash(digest, getExceptorCfg());
        HashUtils.addPropertyToHash(digest, getExceptorJson());
        HashUtils.addPropertyToHash(digest, getIsApplyingMarkers());
        HashUtils.addPropertyToHash(digest, getIsStrippingSynthetics());
        HashUtils.addPropertyToHash(digest, getMinorMcVersion());
    }

    private File taskTempDir;

    @Inject
    public DeobfuscateTask(Project proj) {
        getIsStrippingSynthetics().convention(false);
        getIsApplyingMarkers().convention(false);
        getMinorMcVersion().convention(7);
    }

    @TaskAction
    void processJar() throws IOException {
        final File taskTempDir = getTemporaryDir();
        this.taskTempDir = taskTempDir;
        final File deobfedJar = new File(taskTempDir, "deobf.jar");
        final File exceptedJar = new File(taskTempDir, "excepted.jar");
        final int mcMinor = getMinorMcVersion().get();

        getLogger().lifecycle("Applying SpecialSource");
        final Set<File> atFiles = new ImmutableSet.Builder<File>().addAll(getAccessTransformerFiles()).build();
        if (mcMinor <= 8) {
            applySpecialSourceFG12(deobfedJar, atFiles);
        } else {
            applySpecialSourceFG23(deobfedJar, atFiles);
        }

        getLogger().lifecycle("Applying Exceptor");
        applyExceptor(deobfedJar, exceptedJar, new File(taskTempDir, "deobf.log"), atFiles, mcMinor);

        final boolean isStrippingSynths = getIsStrippingSynthetics().get();
        getLogger()
                .lifecycle("Cleaning up generated debuginfo{}", isStrippingSynths ? " and stripping synthetics" : "");
        cleanupJar(exceptedJar, getOutputJar().get().getAsFile(), isStrippingSynths);

        // Clean up temporary files
        if (!Constants.DEBUG_NO_TMP_CLEANUP) {
            FileUtils.deleteQuietly(deobfedJar);
            FileUtils.deleteQuietly(exceptedJar);
        }
    }

    private void applySpecialSourceFG12(File tempDeobfJar, Set<File> atFiles) throws IOException {
        final File originalInputFile = getInputJar().get().getAsFile();
        // Work on a copy to make sure the original jar doesn't get modified
        final File inputFile = new File(taskTempDir, "input.jar");
        FileUtils.copyFile(originalInputFile, inputFile);
        final JarMapping mapping = new JarMapping();
        mapping.loadMappings(getSrgFile().get().getAsFile());
        final Map<String, String> renames = new HashMap<>();
        for (File f : new File[] { getFieldCsv().getAsFile().getOrNull(), getMethodCsv().getAsFile().getOrNull() }) {
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
        RenamedAccessMapFG12 accessMap = new RenamedAccessMapFG12(renames);
        for (File atFile : atFiles) {
            getLogger().info("{}", atFile.getPath());
            accessMap.loadAccessTransformer(atFile);
        }
        getLogger().lifecycle("Renamed {} AT entries", accessMap.getRenameCount());

        final RemapperProcessor srgProcessor = new RemapperProcessor(null, mapping, null);
        final RemapperProcessor atProcessor = new RemapperProcessor(null, null, accessMap);
        final JarRemapper remapper = new JarRemapper(srgProcessor, mapping, atProcessor);

        final Jar input = Jar.init(inputFile);
        try {
            final JointProvider inheritanceProviders = new JointProvider();
            inheritanceProviders.add(new JarProvider(input));
            mapping.setFallbackInheritanceProvider(inheritanceProviders);
            remapper.remapJar(input, tempDeobfJar);
        } finally {
            try {
                // Close the jar file handle manually, because the old SpecialSource didn't have a close method
                final Field fFiles = Jar.class.getDeclaredField("jarFiles");
                fFiles.setAccessible(true);
                @SuppressWarnings("unchecked")
                final List<JarFile> files = (List<JarFile>) fFiles.get(input);
                if (files != null) {
                    for (JarFile file : files) {
                        file.close();
                    }
                }
            } catch (ReflectiveOperationException | IOException e) {
                throw new RuntimeException(e);
            }
        }

        // Clean up temporary files
        if (!Constants.DEBUG_NO_TMP_CLEANUP) {
            FileUtils.deleteQuietly(inputFile);
        }
    }

    private void applySpecialSourceFG23(File tempDeobfJar, Set<File> atFiles) throws IOException {
        final File originalInputFile = getInputJar().get().getAsFile();
        // Work on a copy to make sure the original jar doesn't get modified
        final File inputFile = new File(taskTempDir, "input.jar");
        FileUtils.copyFile(originalInputFile, inputFile);
        final com.gtnewhorizons.retrofuturagradle.fg23shadow.net.md_5.specialsource.JarMapping mapping = new com.gtnewhorizons.retrofuturagradle.fg23shadow.net.md_5.specialsource.JarMapping();
        mapping.loadMappings(getSrgFile().get().getAsFile());
        final Map<String, String> renames = new HashMap<>();
        for (File f : new File[] { getFieldCsv().getAsFile().getOrNull(), getMethodCsv().getAsFile().getOrNull() }) {
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
        RenamedAccessMapFG23 accessMap = new RenamedAccessMapFG23(renames);
        for (File atFile : atFiles) {
            getLogger().info("{}", atFile.getPath());
            accessMap.loadAccessTransformer(atFile);
        }
        getLogger().lifecycle("Renamed {} AT entries", accessMap.getRenameCount());

        final com.gtnewhorizons.retrofuturagradle.fg23shadow.net.md_5.specialsource.RemapperProcessor srgProcessor = new com.gtnewhorizons.retrofuturagradle.fg23shadow.net.md_5.specialsource.RemapperProcessor(
                null,
                mapping,
                null);
        final com.gtnewhorizons.retrofuturagradle.fg23shadow.net.md_5.specialsource.RemapperProcessor atProcessor = new com.gtnewhorizons.retrofuturagradle.fg23shadow.net.md_5.specialsource.RemapperProcessor(
                null,
                null,
                accessMap);
        final com.gtnewhorizons.retrofuturagradle.fg23shadow.net.md_5.specialsource.JarRemapper remapper = new com.gtnewhorizons.retrofuturagradle.fg23shadow.net.md_5.specialsource.JarRemapper(
                srgProcessor,
                mapping,
                atProcessor);

        try (final com.gtnewhorizons.retrofuturagradle.fg23shadow.net.md_5.specialsource.Jar input = com.gtnewhorizons.retrofuturagradle.fg23shadow.net.md_5.specialsource.Jar
                .init(inputFile)) {
            final com.gtnewhorizons.retrofuturagradle.fg23shadow.net.md_5.specialsource.provider.JointProvider inheritanceProviders = new com.gtnewhorizons.retrofuturagradle.fg23shadow.net.md_5.specialsource.provider.JointProvider();
            inheritanceProviders.add(
                    new com.gtnewhorizons.retrofuturagradle.fg23shadow.net.md_5.specialsource.provider.JarProvider(
                            input));
            mapping.setFallbackInheritanceProvider(inheritanceProviders);
            remapper.remapJar(input, tempDeobfJar);
        }

        // Clean up temporary files
        if (!Constants.DEBUG_NO_TMP_CLEANUP) {
            FileUtils.deleteQuietly(inputFile);
        }
    }

    private void applyExceptor(File deobfJar, File tempExceptorJar, File logFile, Set<File> atFiles, int mcMinor)
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
                            for (MCInjectorStruct cls : new MCInjectorStruct[] { struct.get(parent),
                                    struct.get(s[1]) }) {
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

            if (mcMinor > 8) {
                removeUnknownClasses(deobfJar, struct);
            }

            File tmpJsonFile = new File(taskTempDir, "transformed.json");
            json = tmpJsonFile.getCanonicalPath();
            FileUtils.write(tmpJsonFile, Utilities.GSON.toJson(struct), StandardCharsets.UTF_8);
        }

        // Silence MCI logs
        java.util.logging.Logger.getLogger("MCInjector").setLevel(java.util.logging.Level.WARNING);

        if (mcMinor <= 8) {
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
        } else {
            com.gtnewhorizons.retrofuturagradle.fg23shadow.de.oceanlabs.mcp.mcinjector.MCInjectorImpl.process(
                    deobfJar.getCanonicalPath(),
                    tempExceptorJar.getCanonicalPath(),
                    getExceptorCfg().get().getAsFile().getCanonicalPath(),
                    logFile.getCanonicalPath(),
                    null,
                    0,
                    json,
                    getIsApplyingMarkers().get(),
                    true,
                    LVTNaming.LVT);
        }
    }

    // FG2.3
    private void removeUnknownClasses(File inJar, Map<String, MCInjectorStruct> config) throws IOException {
        try (ZipFile zip = new ZipFile(inJar)) {
            Iterator<Map.Entry<String, MCInjectorStruct>> entries = config.entrySet().iterator();
            while (entries.hasNext()) {
                Map.Entry<String, MCInjectorStruct> entry = entries.next();
                String className = entry.getKey();

                // Verify the configuration contains only classes we actually have
                if (zip.getEntry(className + ".class") == null) {
                    entries.remove();
                    continue;
                }

                MCInjectorStruct struct = entry.getValue();

                // Verify the inner classes in the configuration actually exist in our deobfuscated JAR file
                if (struct.innerClasses != null) {
                    Iterator<MCInjectorStruct.InnerClass> innerClasses = struct.innerClasses.iterator();
                    while (innerClasses.hasNext()) {
                        MCInjectorStruct.InnerClass innerClass = innerClasses.next();
                        if (zip.getEntry(innerClass.inner_class + ".class") == null) {
                            innerClasses.remove();
                        }
                    }
                }
            }
        }
    }

    /**
     * Copied from Gradle ZipCopyAction internals. Note that setting the January 1st 1980 (or even worse, "0", as time)
     * won't work due to Java 8 doing some interesting time processing: It checks if this date is before January 1st
     * 1980 and if it is it starts setting some extra fields in the zip. Java 7 does not do that - but in the zip not
     * the milliseconds are saved but values for each of the date fields - but no time zone. And 1980 is the first year
     * which can be saved. If you use January 1st 1980 then it is treated as a special flag in Java 8. Moreover, only
     * even seconds can be stored in the zip file. Java 8 uses the upper half of some other long to store the remaining
     * millis while Java 7 doesn't do that. So make sure that your seconds are even. Moreover, parsing happens via `new
     * Date(millis)` in java.util.zip.ZipUtils#javaToDosTime() so we must use default timezone and locale. The date is
     * 1980 February 1st CET.
     */
    public static final long CONSTANT_TIME_FOR_ZIP_ENTRIES = new GregorianCalendar(1980, Calendar.FEBRUARY, 1, 0, 0, 0)
            .getTimeInMillis();

    private void cleanupJar(File inputJar, File outputJar, boolean stripSynthetics) throws IOException {
        try (final ZipFile inZip = new ZipFile(inputJar);
                final FileOutputStream fos = new FileOutputStream(outputJar);
                final BufferedOutputStream bos = new BufferedOutputStream(fos);
                final ZipOutputStream out = new ZipOutputStream(bos)) {
            final ArrayList<ZipEntry> inputEntries = new ArrayList<>(inZip.size());
            for (ZipEntry entry : new IteratorIterable<>(new EnumerationIterator<>(inZip.entries()))) {
                if (entry.getName().contains("META-INF")) continue;
                inputEntries.add(entry);
            }
            // Ensure reproducible jar output
            inputEntries.sort(Comparator.comparing(ZipEntry::getName));

            for (ZipEntry entry : inputEntries) {
                ZipEntry n = new ZipEntry(entry.getName());
                n.setTime(CONSTANT_TIME_FOR_ZIP_ENTRIES);
                out.putNextEntry(n);
                if (!entry.isDirectory()) {
                    byte[] entryContents = Utilities.readZipEntry(inZip, entry);

                    // correct source name
                    if (entry.getName().endsWith(".class") && stripSynthetics) {
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
        if ((node.access & Opcodes.ACC_ENUM) == 0 && !node.superName.equals("java/lang/Enum")
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
