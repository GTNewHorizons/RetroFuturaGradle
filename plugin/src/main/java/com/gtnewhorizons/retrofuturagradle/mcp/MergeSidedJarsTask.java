package com.gtnewhorizons.retrofuturagradle.mcp;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
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
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import com.google.common.collect.ImmutableList;
import com.gtnewhorizons.retrofuturagradle.util.HashUtils;
import com.gtnewhorizons.retrofuturagradle.util.IJarOutputTask;
import com.gtnewhorizons.retrofuturagradle.util.Utilities;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

@CacheableTask
public abstract class MergeSidedJarsTask extends DefaultTask implements IJarOutputTask {

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getMergeConfigFile();

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getClientJar();

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getServerJar();

    @Input
    public abstract Property<String> getMcVersion();

    @Override
    public void hashInputs(MessageDigest digest) {
        HashUtils.addPropertyToHash(digest, getMergeConfigFile());
        HashUtils.addPropertyToHash(digest, getClientJar());
        HashUtils.addPropertyToHash(digest, getServerJar());
        HashUtils.addPropertyToHash(digest, getMcVersion());
    }

    @TaskAction
    void mergeJars() throws IOException {
        final MergeConfig config = new MergeConfig(getMergeConfigFile().get().getAsFile());
        try (final ZipFile clientJar = new ZipFile(getClientJar().get().getAsFile());
                final ZipFile serverJar = new ZipFile(getServerJar().get().getAsFile());
                final FileOutputStream outFOS = new FileOutputStream(getOutputJar().get().getAsFile());
                final BufferedOutputStream outBOS = new BufferedOutputStream(outFOS);
                final ZipOutputStream outJar = new ZipOutputStream(outBOS)) {
            final Set<String> resources = new HashSet<>();
            final Map<String, ZipEntry> clientClasses = new HashMap<>();
            final Map<String, ZipEntry> serverClasses = new HashMap<>();
            final Set<String> processedClasses = new HashSet<>();

            // Find classes and merge resources
            for (Pair<Map<String, ZipEntry>, ZipFile> pair : ImmutableList
                    .of(Pair.of(clientClasses, clientJar), Pair.of(serverClasses, serverJar))) {
                final ZipFile jar = pair.getRight();
                final Map<String, ZipEntry> classes = pair.getLeft();
                for (ZipEntry entry : new IteratorIterable<>(new EnumerationIterator<>(jar.entries()))) {
                    final String entryName = entry.getName();
                    if (entry.isDirectory() || "META-INF/MANIFEST.MF".equals(entryName)
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
                    if (line.isEmpty()) {
                        continue;
                    }
                    final char cmd = line.charAt(0);
                    final String instruction = line.substring(1);
                    switch (cmd) {
                        case '!':
                            dontAnnotate.add(instruction);
                            break;
                        case '<':
                            copyToClient.add(instruction);
                            break;
                        case '>':
                            copyToServer.add(instruction);
                            break;
                        case '^':
                            dontProcess.add(instruction);
                            break;
                        default:
                            throw new RuntimeException("Invalid mergeconfig instruction: " + instruction);
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

    private void copySidedClass(MergeConfig config, ZipFile inputJar, ZipEntry entry, ZipOutputStream outputJar,
            boolean isClientOnly) throws IOException {
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
        // maintain insertion order via a LinkedHashSet for reliable method ordering
        LinkedHashSet<String> entryKeys = new LinkedHashSet<>(clientClass.methods.size() + clientClass.fields.size());
        MultiValuedMap<String, FieldOrMethod> sidedEntries = new ArrayListValuedHashMap<>(
                clientClass.methods.size() + clientClass.fields.size(),
                2);

        // Process and add fields in the same order as ForgeGradle
        {
            for (int cPos = 0, sPos = 0; cPos < clientClass.fields.size(); cPos++, sPos++) {
                final FieldNode cNode = clientClass.fields.get(cPos);
                final FieldOrMethod cFom = new FieldOrMethod(Side.CLIENT, cNode);
                if (sPos < serverClass.fields.size()) {
                    final FieldNode sNode = serverClass.fields.get(sPos);
                    final FieldOrMethod sFom = new FieldOrMethod(Side.SERVER, sNode);
                    entryKeys.add(sFom.getKey());
                    sidedEntries.put(sFom.getKey(), sFom);
                    if (!cNode.name.equals(sNode.name)) {
                        final boolean foundServerField = serverClass.fields.stream().skip(sPos + 1)
                                .anyMatch(sf -> sf.name.equals(cNode.name));
                        if (foundServerField) {
                            final boolean foundClientField = clientClass.fields.stream().skip(cPos + 1)
                                    .anyMatch(cf -> cf.name.equals(sNode.name));
                            if (!foundClientField) {
                                clientClass.fields.add(cPos, sNode);
                            }
                        } else {
                            serverClass.fields.add(sPos, cNode);
                        }
                    }
                } else {
                    serverClass.fields.add(sPos, cNode);
                }
                entryKeys.add(cFom.getKey());
                sidedEntries.put(cFom.getKey(), cFom);
            }
        }

        // Process methods in the same order as ForgeGradle
        {
            int cPos = 0, sPos = 0;
            final int cLen = clientClass.methods.size(), sLen = serverClass.methods.size();
            String serverName = "", clientName = "", lastName = "";
            while (cPos < cLen || sPos < sLen) {
                for (; sPos < sLen; sPos++) {
                    final MethodNode sNode = serverClass.methods.get(sPos);
                    serverName = sNode.name;
                    if (serverName.equals(lastName) || cPos == cLen) {
                        final FieldOrMethod fom = new FieldOrMethod(Side.SERVER, sNode);
                        entryKeys.add(fom.getKey());
                        sidedEntries.put(fom.getKey(), fom);
                    } else {
                        break;
                    }
                }
                for (; cPos < cLen; cPos++) {
                    final MethodNode cNode = clientClass.methods.get(cPos);
                    lastName = clientName;
                    clientName = cNode.name;
                    if (clientName.equals(lastName) || sPos == sLen) {
                        final FieldOrMethod fom = new FieldOrMethod(Side.CLIENT, cNode);
                        entryKeys.add(fom.getKey());
                        sidedEntries.put(fom.getKey(), fom);
                    } else {
                        break;
                    }
                }
            }
        }

        clientClass.methods.clear();

        for (String key : entryKeys) {
            Collection<FieldOrMethod> foms = sidedEntries.get(key);
            assert !foms.isEmpty();
            FieldOrMethod fom = foms.iterator().next();
            if (fom.isMethod()) {
                fom.addToClass(clientClass);
            }
            // If sided
            if (foms.size() == 1) {
                fom.addSideOnlyAnnotation();
            }
        }
        return Utilities.emitClassBytes(clientClass, ClassWriter.COMPUTE_MAXS);
    }

    private static AnnotationNode makeSideAnnotation(boolean isClientOnly) {
        AnnotationNode an = new AnnotationNode(Type.getDescriptor(SideOnly.class));
        an.values = new ArrayList<>(2);
        an.values.add("value");
        Side side = isClientOnly ? Side.CLIENT : Side.SERVER;
        an.values.add(new String[] { Type.getDescriptor(side.getClass()), side.toString() });
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

        public boolean isMethod() {
            return this.method != null;
        }

        public String getKey() {
            if (this.field != null) {
                return "field " + this.field.name;
            } else {
                assert this.method != null;
                return "method " + this.method.name + "!" + this.method.desc;
            }
        }

        public void addToClass(ClassNode targetNode) {
            if (this.field != null) {
                targetNode.fields.add(this.field);
            } else {
                assert this.method != null;
                targetNode.methods.add(this.method);
            }
        }

        public void addSideOnlyAnnotation() {
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
        }
    }
}
