package com.gtnewhorizons.retrofuturagradle.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.opencsv.CSVParser;
import com.opencsv.CSVParserBuilder;
import com.opencsv.CSVReader;
import com.opencsv.CSVReaderBuilder;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.gradle.api.Project;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;

public final class Utilities {
    public static final Gson GSON;

    static {
        GsonBuilder builder = new GsonBuilder();
        builder.enableComplexMapKeySerialization();
        builder.setPrettyPrinting();
        GSON = builder.create();
    }

    public static File getCacheRoot(Project project) {
        return FileUtils.getFile(project.getGradle().getGradleUserHomeDir(), "caches", "retro_futura_gradle");
    }

    public static File getCacheDir(Project project, String... paths) {
        return FileUtils.getFile(getCacheRoot(project), paths);
    }

    public static CSVReader createCsvReader(File file) throws IOException {
        final CSVParser csvParser = new CSVParserBuilder()
                .withEscapeChar(CSVParser.NULL_CHARACTER)
                .withStrictQuotes(false)
                .build();
        return new CSVReaderBuilder(Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8))
                .withSkipLines(1)
                .withCSVParser(csvParser)
                .build();
    }

    public static byte[] readZipEntry(ZipFile jar, ZipEntry entry) throws IOException {
        try (InputStream zis = jar.getInputStream(entry)) {
            return IOUtils.toByteArray(zis);
        }
    }

    public static byte[] getClassBytes(Class<?> klass) {
        final String resourcePath = String.format("/%s.class", klass.getName().replace('.', '/'));
        try (InputStream cis = klass.getResourceAsStream(resourcePath)) {
            return IOUtils.toByteArray(cis);
        } catch (IOException exc) {
            throw new RuntimeException(
                    "IO Exception caught when trying to get the class bytes for " + klass.getName(), exc);
        }
    }

    public static ClassNode parseClassBytes(byte[] bytes, String debugName) {
        try {
            ClassReader reader = new ClassReader(bytes);
            ClassNode classNode = new ClassNode();
            reader.accept(classNode, 0);
            return classNode;
        } catch (Exception e) {
            throw new RuntimeException("Couldn't parse bytes of class " + debugName, e);
        }
    }

    public static byte[] emitClassBytes(ClassNode node, int writerFlags) {
        ClassWriter writer = new ClassWriter(writerFlags);
        node.accept(writer);
        return writer.toByteArray();
    }

    /**
     * Load a JAR file into in-memory hashmaps
     * @param jar The JAR to load
     * @param loadedResources The map to populate with non-java file contents
     * @param loadedSources The map to populate with java file contents
     * @throws IOException Forwarded IO errors from the JAR reading process
     */
    public static void loadMemoryJar(File jar, Map<String, byte[]> loadedResources, Map<String, String> loadedSources)
            throws IOException {
        try (final FileInputStream fis = new FileInputStream(jar);
                final BufferedInputStream bis = new BufferedInputStream(fis);
                final ZipInputStream zis = new ZipInputStream(bis)) {
            ZipEntry entry = null;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.getName().contains("META-INF")) {
                    continue;
                }
                if (entry.isDirectory() || !entry.getName().endsWith(".java")) {
                    loadedResources.put(entry.getName(), IOUtils.toByteArray(zis));
                } else {
                    final String src = IOUtils.toString(zis, StandardCharsets.UTF_8);
                    loadedSources.put(entry.getName(), src);
                }
            }
        }
    }

    public static File saveMemoryJar(
            Map<String, byte[]> loadedResources, Map<String, String> loadedSources, File target) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(target);
                BufferedOutputStream bos = new BufferedOutputStream(fos);
                ZipOutputStream zos = new ZipOutputStream(bos)) {
            for (Map.Entry<String, byte[]> resource : loadedResources.entrySet()) {
                zos.putNextEntry(new ZipEntry(resource.getKey()));
                zos.write(resource.getValue());
                zos.closeEntry();
            }
            for (Map.Entry<String, String> srcFile : loadedSources.entrySet()) {
                zos.putNextEntry(new ZipEntry(srcFile.getKey()));
                zos.write(srcFile.getValue().getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }
        return target;
    }
}
