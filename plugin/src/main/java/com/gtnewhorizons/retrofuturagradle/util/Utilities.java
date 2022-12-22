package com.gtnewhorizons.retrofuturagradle.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.opencsv.CSVParser;
import com.opencsv.CSVParserBuilder;
import com.opencsv.CSVReader;
import com.opencsv.CSVReaderBuilder;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
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
}
