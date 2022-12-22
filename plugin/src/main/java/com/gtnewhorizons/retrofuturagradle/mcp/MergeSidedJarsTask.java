package com.gtnewhorizons.retrofuturagradle.mcp;

import com.google.common.collect.ImmutableList;
import com.gtnewhorizons.retrofuturagradle.util.Utilities;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.apache.commons.collections4.MultiValuedMap;
import org.apache.commons.collections4.iterators.EnumerationIterator;
import org.apache.commons.collections4.iterators.IteratorIterable;
import org.apache.commons.collections4.multimap.ArrayListValuedHashMap;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.LineIterator;
import org.apache.commons.lang3.tuple.Pair;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

public abstract class MergeSidedJarsTask extends DefaultTask {
    @InputFile
    public abstract RegularFileProperty getMergeConfigFile();

    @InputFile
    public abstract RegularFileProperty getClientJar();

    @InputFile
    public abstract RegularFileProperty getServerJar();

    @Input
    public abstract Property<String> getMcVersion();

    @OutputFile
    public abstract RegularFileProperty getMergedJar();

    @TaskAction
    void mergeJars() throws IOException {
        final MergeConfig config = new MergeConfig(getMergeConfigFile().get().getAsFile());
        try (final ZipFile clientJar = new ZipFile(getClientJar().get().getAsFile());
                final ZipFile serverJar = new ZipFile(getServerJar().get().getAsFile());
                final FileOutputStream outFOS =
                        new FileOutputStream(getMergedJar().get().getAsFile());
                final BufferedOutputStream outBOS = new BufferedOutputStream(outFOS);
                final ZipOutputStream outJar = new ZipOutputStream(outBOS)) {
            final Set<String> resources = new HashSet<>();
            final Map<String, ZipEntry> clientClasses = new HashMap<>();
            final Map<String, ZipEntry> serverClasses = new HashMap<>();
            final Set<String> processedClasses = new HashSet<>();

            // Find classes and merge resources
            for (Pair<Map<String, ZipEntry>, ZipFile> pair :
                    ImmutableList.of(Pair.of(clientClasses, clientJar), Pair.of(serverClasses, serverJar))) {
                final ZipFile jar = pair.getRight();
                final Map<String, ZipEntry> classes = pair.getLeft();
                for (ZipEntry entry : new IteratorIterable<>(new EnumerationIterator<>(jar.entries()))) {
                    final String entryName = entry.getName();
                    if (entry.isDirectory()
                            || "META-INF/MANIFEST.MF".equals(entryName)
                            || config.dontProcess.stream().anyMatch(entryName::startsWith)) {
                        continue;
                    }
                    final boolean isResource = !entryName.endsWith(".class") || entryName.startsWith(".");
                    if (isResource) {
                        if (!resources.contains(entryName)) {
                            final ZipEntry newEntry = new ZipEntry(entryName);
                            outJar.putNextEntry(newEntry);
                            outJar.write(Utilities.readZipEntry(jar, entry));
                            resources.add(entryName);
                        }
                    } else {
                        classes.put(entryName.replaceFirst("\\.class$", ""), entry);
                    }
                }
            }

            // Process Client classes
            for (Map.Entry<String, ZipEntry> entry : clientClasses.entrySet()) {
                final String className = entry.getKey(); // e.g. java/lang/Math
                final ZipEntry clientEntry = entry.getValue();
                final ZipEntry serverEntry = serverClasses.get(className);
                if (serverEntry == null) {
                    copySidedClass(config, clientJar, clientEntry, outJar, true);
                    processedClasses.add(className);
                } else {
                    serverClasses.remove(className);
                    byte[] clientData = Utilities.readZipEntry(clientJar, clientEntry);
                    byte[] serverData = Utilities.readZipEntry(serverJar, serverEntry);
                    byte[] mergedData = mergeClasses(clientData, serverData, className);
                    ZipEntry mergedEntry = new ZipEntry(clientEntry.getName());
                    outJar.putNextEntry(mergedEntry);
                    outJar.write(mergedData);
                    processedClasses.add(className);
                }
            }
            // Process remaining server classes
            for (Map.Entry<String, ZipEntry> entry : serverClasses.entrySet()) {
                copySidedClass(config, serverJar, entry.getValue(), outJar, false);
            }
            // Add the Side&SideOnly classes to the jar
            for (Class<?> klass : ImmutableList.of(Side.class, SideOnly.class)) {
                final String entityName = klass.getName().replace('.', '/');
                final String zipPath = entityName + ".class";
                if (!processedClasses.contains(entityName)) {
                    ZipEntry newEntry = new ZipEntry(zipPath);
                    outJar.putNextEntry(newEntry);
                    outJar.write(Utilities.getClassBytes(klass));
                }
            }
        }
    }

    private static class MergeConfig {
        public MergeConfig(File f) throws IOException {
            final HashSet<String> copyToServer = new HashSet<>();
            final HashSet<String> copyToClient = new HashSet<>();
            final HashSet<String> dontAnnotate = new HashSet<>();
            final HashSet<String> dontProcess = new HashSet<>();
            try (final FileInputStream fis = new FileInputStream(f);
                    final BufferedInputStream bis = new BufferedInputStream(fis);
                    final LineIterator lines = IOUtils.lineIterator(bis, StandardCharsets.UTF_8)) {
                while (lines.hasNext()) {
                    final String line = lines.nextLine().split("#", 2)[0].trim();
                    final char cmd = line.charAt(0);
                    final String instruction = line.substring(1);
                    switch (cmd) {
                        case '!':
                            dontAnnotate.add(instruction);
                            break;
                        case '<':
                            copyToClient.add(line);
                            break;
                        case '>':
                            copyToServer.add(line);
                            break;
                        case '^':
                            dontProcess.add(line);
                            break;
                    }
                }
            }
            this.copyToServer = Collections.unmodifiableSet(copyToServer);
            this.copyToClient = Collections.unmodifiableSet(copyToClient);
            this.dontAnnotate = Collections.unmodifiableSet(dontAnnotate);
            this.dontProcess = Collections.unmodifiableSet(dontProcess);
        }

        final Set<String> copyToServer;
        final Set<String> copyToClient;
        final Set<String> dontAnnotate;
        final Set<String> dontProcess;
    }

    private void copySidedClass(
            MergeConfig config, ZipFile inputJar, ZipEntry entry, ZipOutputStream outputJar, boolean isClientOnly)
            throws IOException {
        ClassNode classNode = Utilities.parseClassBytes(Utilities.readZipEntry(inputJar, entry), entry.getName());

        // Annotate with @SideOnly(Side.SIDE)
        if (!config.dontAnnotate.contains(classNode.name)) {
            if (classNode.visibleAnnotations == null) {
                classNode.visibleAnnotations = new ArrayList<>(1);
            }
            classNode.visibleAnnotations.add(makeSideAnnotation(isClientOnly));
        }

        byte[] annotatedClass = Utilities.emitClassBytes(classNode, ClassWriter.COMPUTE_MAXS);
        if (outputJar != null) {
            ZipEntry newEntry = new ZipEntry(entry.getName());
            outputJar.putNextEntry(newEntry);
            outputJar.write(annotatedClass);
        }
    }

    private byte[] mergeClasses(byte[] clientData, byte[] serverData, String debugName) {
        ClassNode clientClass = Utilities.parseClassBytes(clientData, debugName);
        ClassNode serverClass = Utilities.parseClassBytes(serverData, debugName);
        // clientClass is also the output class
        MultiValuedMap<String, FieldOrMethod> sidedEntries =
                new ArrayListValuedHashMap<>(clientClass.methods.size() + clientClass.fields.size(), 2);
        clientClass.methods.stream()
                .map(e -> new FieldOrMethod(Side.CLIENT, e))
                .forEach(e -> sidedEntries.put(e.getKey(), e));
        clientClass.fields.stream()
                .map(e -> new FieldOrMethod(Side.CLIENT, e))
                .forEach(e -> sidedEntries.put(e.getKey(), e));
        serverClass.methods.stream()
                .map(e -> new FieldOrMethod(Side.SERVER, e))
                .forEach(e -> sidedEntries.put(e.getKey(), e));
        serverClass.fields.stream()
                .map(e -> new FieldOrMethod(Side.SERVER, e))
                .forEach(e -> sidedEntries.put(e.getKey(), e));
        for (String key : sidedEntries.keySet()) {
            Collection<FieldOrMethod> foms = sidedEntries.get(key);
            assert !foms.isEmpty();
            // If sided
            if (foms.size() == 1) {
                FieldOrMethod value = foms.iterator().next();
                value.processSidedness(clientClass);
            }
        }
        return Utilities.emitClassBytes(clientClass, ClassWriter.COMPUTE_MAXS);
    }

    private static AnnotationNode makeSideAnnotation(boolean isClientOnly) {
        AnnotationNode an = new AnnotationNode(Type.getDescriptor(SideOnly.class));
        an.values = new ArrayList<>(2);
        an.values.add("value");
        Side side = isClientOnly ? Side.CLIENT : Side.SERVER;
        an.values.add(new String[] {Type.getDescriptor(side.getClass()), side.toString()});
        return an;
    }

    private static class FieldOrMethod {
        public final Side side;
        private final FieldNode field;
        private final MethodNode method;

        public FieldOrMethod(Side side, FieldNode field) {
            this.side = side;
            this.field = field;
            this.method = null;
        }

        public FieldOrMethod(Side side, MethodNode method) {
            this.side = side;
            this.field = null;
            this.method = method;
        }

        public String getKey() {
            if (this.field != null) {
                return "field " + this.field.name;
            } else {
                assert this.method != null;
                return "method " + this.method.name + "!" + this.method.desc;
            }
        }

        public void processSidedness(ClassNode targetNode) {
            final List<AnnotationNode> annotations;
            if (this.field != null) {
                if (this.field.visibleAnnotations == null) {
                    this.field.visibleAnnotations = new ArrayList<>(1);
                }
                annotations = this.field.visibleAnnotations;
            } else {
                assert this.method != null;
                if (this.method.visibleAnnotations == null) {
                    this.method.visibleAnnotations = new ArrayList<>(1);
                }
                annotations = this.method.visibleAnnotations;
            }
            annotations.add(makeSideAnnotation(side == Side.CLIENT));
            if (side == Side.SERVER) {
                if (this.field != null) {
                    targetNode.fields.add(this.field);
                } else {
                    targetNode.methods.add(this.method);
                }
            }
        }
    }
}
