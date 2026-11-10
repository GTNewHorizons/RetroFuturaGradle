package com.gtnewhorizons.retrofuturagradle.mcp;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.objectweb.asm.Opcodes.ACC_PRIVATE;
import static org.objectweb.asm.Opcodes.ACC_PUBLIC;
import static org.objectweb.asm.Opcodes.ACC_STATIC;
import static org.objectweb.asm.Opcodes.ALOAD;
import static org.objectweb.asm.Opcodes.INVOKESPECIAL;
import static org.objectweb.asm.Opcodes.RETURN;
import static org.objectweb.asm.Opcodes.V1_8;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

import net.md_5.specialsource.Jar;
import net.md_5.specialsource.JarMapping;
import net.md_5.specialsource.JarRemapper;
import net.md_5.specialsource.NodeType;
import net.md_5.specialsource.provider.JarProvider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;

class CachingJarRemapperTest {

    @Test
    void cachesInheritedMappingsAndUnmappedNames() {
        final CountingJarMapping mapping = new CountingJarMapping();
        mapping.methods.put("Parent/run ()V", "func_run");
        mapping.setFallbackInheritanceProvider(owner -> owner.equals("Child") ? List.of("Parent") : null);
        final CachingJarRemapper remapper = new CachingJarRemapper(mapping);

        assertEquals("func_run", remapper.mapMethodName("Child", "run", "()V", ACC_PUBLIC));
        assertTrue(mapping.lookups > 0);
        final int mappedLookups = mapping.lookups;
        assertEquals("func_run", remapper.mapMethodName("Child", "run", "()V", ACC_PUBLIC));
        assertEquals(mappedLookups, mapping.lookups);

        assertEquals("missing", remapper.mapMethodName("Child", "missing", "()V", ACC_PUBLIC));
        final int unmappedLookups = mapping.lookups;
        assertTrue(unmappedLookups > mappedLookups);
        assertEquals("missing", remapper.mapMethodName("Child", "missing", "()V", ACC_PUBLIC));
        assertEquals(unmappedLookups, mapping.lookups);
    }

    @Test
    void preservesOwnersDescriptorsAndAccessRules() {
        final JarMapping mapping = new JarMapping();
        mapping.methods.put("Parent/value ()I", "func_int");
        mapping.methods.put("Parent/value ()J", "func_long");
        mapping.methods.put("Other/value ()I", "func_other");
        mapping.fields.put("Parent/value/I", "field_int");
        mapping.fields.put("Parent/value/J", "field_long");
        mapping.setFallbackInheritanceProvider(owner -> owner.equals("Child") ? List.of("Parent") : null);
        final CachingJarRemapper remapper = new CachingJarRemapper(mapping);

        assertEquals("func_int", remapper.mapMethodName("Child", "value", "()I", ACC_PUBLIC));
        assertEquals("func_long", remapper.mapMethodName("Child", "value", "()J", ACC_PUBLIC));
        assertEquals("func_other", remapper.mapMethodName("Other", "value", "()I", ACC_PUBLIC));
        assertEquals("field_int", remapper.mapFieldName("Child", "value", "I", ACC_PUBLIC));
        assertEquals("field_long", remapper.mapFieldName("Child", "value", "J", ACC_PUBLIC));
        assertEquals("value", remapper.mapMethodName("Child", "value", "()I", ACC_PRIVATE));
        assertEquals("value", remapper.mapMethodName("Child", "value", "()I", ACC_STATIC));
        assertEquals("func_int", remapper.mapMethodName("Child", "value", "()I", -1));
    }

    @Test
    void keepsCachesSeparateBetweenRemaps() {
        final JarMapping first = new JarMapping();
        first.methods.put("Owner/run ()V", "func_first");
        final JarMapping second = new JarMapping();
        second.methods.put("Owner/run ()V", "func_second");

        assertEquals("func_first", new CachingJarRemapper(first).mapMethodName("Owner", "run", "()V", ACC_PUBLIC));
        assertEquals("func_second", new CachingJarRemapper(second).mapMethodName("Owner", "run", "()V", ACC_PUBLIC));
    }

    @Test
    void writesTheSameJarAsSpecialSource(@TempDir Path dir) throws IOException {
        final Path input = dir.resolve("input.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(input))) {
            writeEntry(out, "META-INF/", new byte[0]);
            writeEntry(out, "META-INF/MOD.SF", "signature".getBytes(StandardCharsets.UTF_8));
            writeEntry(out, "assets/", new byte[0]);
            writeEntry(out, "assets/mod.txt", "resource".getBytes(StandardCharsets.UTF_8));
            writeEntry(out, "Parent.class", classWithRun("Parent", "java/lang/Object"));
            writeEntry(out, "Child.class", classWithRun("Child", "Parent"));
        }

        final Path stock = dir.resolve("stock.jar");
        final Path cached = dir.resolve("cached.jar");
        final JarMapping stockMapping = jarMapping();
        final JarMapping cachedMapping = jarMapping();
        remap(new JarRemapper(null, stockMapping), stockMapping, input, stock);
        remap(new CachingJarRemapper(cachedMapping), cachedMapping, input, cached);

        assertArrayEquals(Files.readAllBytes(stock), Files.readAllBytes(cached));
        try (JarFile jar = new JarFile(cached.toFile())) {
            assertNotNull(jar.getEntry("a.class"));
            assertNotNull(jar.getEntry("assets/mod.txt"));
            assertFalse(jar.stream().anyMatch(entry -> entry.getName().endsWith(".SF")));
        }
    }

    private static JarMapping jarMapping() {
        final JarMapping mapping = new JarMapping();
        mapping.classes.put("Parent", "a");
        mapping.methods.put("Parent/run ()V", "func_run");
        return mapping;
    }

    private static void remap(JarRemapper remapper, JarMapping mapping, Path input, Path output) throws IOException {
        try (Jar jar = Jar.init(input.toFile())) {
            mapping.setFallbackInheritanceProvider(new JarProvider(jar));
            remapper.remapJar(jar, output.toFile());
        }
    }

    private static void writeEntry(JarOutputStream out, String name, byte[] data) throws IOException {
        final JarEntry entry = new JarEntry(name);
        entry.setTime(1_000_000_000_000L);
        out.putNextEntry(entry);
        out.write(data);
    }

    private static byte[] classWithRun(String name, String superName) {
        final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(V1_8, ACC_PUBLIC, name, null, superName, null);
        final MethodVisitor run = writer.visitMethod(ACC_PUBLIC, "run", "()V", null, null);
        run.visitCode();
        if (!superName.equals("java/lang/Object")) {
            run.visitVarInsn(ALOAD, 0);
            run.visitMethodInsn(INVOKESPECIAL, superName, "run", "()V", false);
        }
        run.visitInsn(RETURN);
        run.visitMaxs(0, 0);
        run.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static class CountingJarMapping extends JarMapping {

        private int lookups;

        @Override
        public String tryClimb(Map<String, String> map, NodeType type, String owner, String name, String desc,
                int access) {
            lookups++;
            return super.tryClimb(map, type, owner, name, desc, access);
        }
    }
}
