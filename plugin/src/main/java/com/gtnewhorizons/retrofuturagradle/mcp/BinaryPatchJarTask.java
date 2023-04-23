package com.gtnewhorizons.retrofuturagradle.mcp;

import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;
import java.util.regex.Pattern;
import java.util.zip.Adler32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import javax.inject.Inject;

import org.apache.commons.collections4.iterators.EnumerationIterator;
import org.apache.commons.collections4.iterators.IteratorIterable;
import org.apache.commons.compress.java.util.jar.Pack200;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileTree;
import org.gradle.api.file.FileTree;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.internal.file.FileOperations;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

import com.google.common.collect.ImmutableMap;
import com.gtnewhorizons.retrofuturagradle.fg12shadow.com.nothome.delta.GDiffPatcher;
import com.gtnewhorizons.retrofuturagradle.util.HashUtils;
import com.gtnewhorizons.retrofuturagradle.util.IJarTransformTask;

import lzma.sdk.lzma.Decoder;
import lzma.streams.LzmaInputStream;

@CacheableTask
public abstract class BinaryPatchJarTask extends DefaultTask implements IJarTransformTask {

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getPatchesLzma();

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getExtraClassesJar();

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileTree getExtraResourcesTree();

    @Inject
    protected abstract FileOperations getFileOperations();

    @Override
    public void hashInputs(MessageDigest digest) {
        HashUtils.addPropertyToHash(digest, getPatchesLzma());
        HashUtils.addPropertyToHash(digest, getExtraClassesJar());
        HashUtils.addPropertyToHash(digest, getExtraResourcesTree());
    }

    @TaskAction
    public void patchJar() throws IOException {
        final Map<String, ClassPatch> patches = loadPatches(getPatchesLzma().get().getAsFile());
        final GDiffPatcher patcher = new GDiffPatcher();

        final File inputJar = getInputJar().get().getAsFile();
        final File extraClassesJar = getExtraClassesJar().get().getAsFile();
        final File outputJar = getOutputJar().get().getAsFile();
        FileUtils.deleteQuietly(outputJar);

        final Set<String> processed = new HashSet<>();

        final Adler32 hasher = new Adler32();
        try (final ZipFile inZip = new ZipFile(inputJar);
                final FileOutputStream fos = FileUtils.openOutputStream(outputJar);
                final BufferedOutputStream bos = new BufferedOutputStream(fos);
                final ZipOutputStream out = new ZipOutputStream(bos)) {
            // Apply patches
            for (ZipEntry e : new IteratorIterable<>(new EnumerationIterator<>(inZip.entries()))) {
                if (e.getName().contains("META-INF")) {
                    continue;
                }
                if (e.isDirectory()) {
                    out.putNextEntry(e);
                } else {
                    final ZipEntry newEntry = new ZipEntry(e.getName());
                    e.setTime(newEntry.getTime());
                    out.putNextEntry(newEntry);

                    final byte[] data = IOUtils.toByteArray(inZip.getInputStream(e));
                    ClassPatch patch = patches.get(e.getName().replace('\\', '/'));

                    if (patch != null) {
                        hasher.reset();
                        hasher.update(data, 0, data.length);
                        final int hash = (int) hasher.getValue();
                        if (hash != patch.inputChecksum) {
                            throw new RuntimeException(
                                    String.format(
                                            "Mismatched checksum for class %s: expected %d, got %d",
                                            e.getName(),
                                            patch.inputChecksum,
                                            hash));
                        }
                        patcher.patch(data, patch.patch);
                    }
                    out.write(data);
                    out.closeEntry();
                }
                processed.add(e.getName());
            }
            // Copy extra classes
            {
                final FileTree tree = getFileOperations().zipTree(getExtraClassesJar().getAsFile().get());
                tree.visit(fvd -> {
                    if (fvd.isDirectory()) {
                        return;
                    }
                    final String name = fvd.getRelativePath().toString().replace('\\', '/');
                    if (processed.contains(name)) {
                        return;
                    }
                    ZipEntry newEntry = new ZipEntry(name);
                    newEntry.setTime(fvd.getLastModified());
                    try {
                        out.putNextEntry(newEntry);
                        out.write(IOUtils.toByteArray(fvd.open()));
                        out.closeEntry();
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                    processed.add(name);
                });
            }
            // Copy resources
            getExtraResourcesTree().visit(fvd -> {
                if (fvd.isDirectory()) {
                    return;
                }
                try {
                    final String name = fvd.getRelativePath().toString().replace('\\', '/');
                    if (!processed.contains(name)) {
                        final ZipEntry newEntry = new ZipEntry(name);
                        newEntry.setTime(fvd.getLastModified());
                        out.putNextEntry(newEntry);
                        IOUtils.copy(fvd.open(), out);
                        processed.add(name);
                        out.closeEntry();
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
            out.flush();
            bos.flush();
        }
    }

    private static Map<String, ClassPatch> loadPatches(File patchesLzmaFile) throws IOException {
        final byte[] patchesJarBytes;
        final byte[] decompressedPatchesLzma;
        try (LzmaInputStream decompressed = new LzmaInputStream(
                FileUtils.openInputStream(patchesLzmaFile),
                new Decoder())) {
            decompressedPatchesLzma = IOUtils.toByteArray(decompressed);
        }
        try (ByteArrayInputStream decompressed = new ByteArrayInputStream(decompressedPatchesLzma);
                ByteArrayOutputStream jarBytes = new ByteArrayOutputStream();
                JarOutputStream jarOut = new JarOutputStream(jarBytes);) {
            Pack200.newUnpacker().unpack(decompressed, jarOut);
            jarOut.flush();
            patchesJarBytes = jarBytes.toByteArray();
        }

        final Pattern patchNamePattern = Pattern.compile("binpatch/merged/.*.binpatch");
        final ImmutableMap.Builder<String, ClassPatch> mapBuilder = new ImmutableMap.Builder<>();
        try (JarInputStream jis = new JarInputStream(new ByteArrayInputStream(patchesJarBytes))) {
            JarEntry entry;
            while ((entry = jis.getNextJarEntry()) != null) {
                if (patchNamePattern.matcher(entry.getName()).matches()) {
                    final byte[] patchBytes = IOUtils.toByteArray(jis);
                    final ClassPatch patch = readPatch(entry, patchBytes);
                    final String patchedFile = patch.sourceClassName.replace('.', '/') + ".class";
                    mapBuilder.put(patchedFile, patch);
                } else {
                    jis.closeEntry();
                }
            }
        }
        return mapBuilder.buildOrThrow();
    }

    private static ClassPatch readPatch(JarEntry entry, byte[] rawPatchBytes) throws IOException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(rawPatchBytes))) {
            final String name = input.readUTF();
            final String sourceClassName = input.readUTF();
            final String targetClassName = input.readUTF();
            final boolean checksumExists = input.readBoolean();
            final int inputChecksum;
            if (checksumExists) {
                inputChecksum = input.readInt();
            } else {
                inputChecksum = 0;
            }
            final int patchLength = input.readInt();
            final byte[] patchBytes = new byte[patchLength];
            input.readFully(patchBytes);

            return new ClassPatch(name, sourceClassName, targetClassName, checksumExists, inputChecksum, patchBytes);
        }
    }

    private static class ClassPatch {

        public final String name;
        public final String sourceClassName;
        public final String targetClassName;
        public final boolean existsAtTarget;
        public final byte[] patch;
        public final int inputChecksum;

        public ClassPatch(String name, String sourceClassName, String targetClassName, boolean existsAtTarget,
                int inputChecksum, byte[] patch) {
            this.name = name;
            this.sourceClassName = sourceClassName;
            this.targetClassName = targetClassName;
            this.existsAtTarget = existsAtTarget;
            this.inputChecksum = inputChecksum;
            this.patch = patch;
        }

        @Override
        public String toString() {
            return String.format(
                    "%s : %s => %s (%b) size %d",
                    name,
                    sourceClassName,
                    targetClassName,
                    existsAtTarget,
                    patch.length);
        }
    }
}
