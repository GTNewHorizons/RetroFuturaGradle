package com.gtnewhorizons.retrofuturagradle.mcp;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;

import net.md_5.specialsource.Jar;
import net.md_5.specialsource.JarMapping;
import net.md_5.specialsource.JarRemapper;
import net.md_5.specialsource.repo.ClassRepo;
import net.md_5.specialsource.util.Pair2;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

/** Caches member names for one jar, after all mappings have been loaded, and buffers the remapped jar. */
final class CachingJarRemapper extends JarRemapper {

    private static final String CLASS_SUFFIX = ".class";
    private static final int OUTPUT_BUFFER_SIZE = 64 * 1024;

    private final Map<MemberKey, String> memberNames = new HashMap<>();

    CachingJarRemapper(JarMapping mapping) {
        super(null, mapping);
    }

    /**
     * The same as {@link JarRemapper#remapJar(Jar, File)} with buffered output. SpecialSource has this on master, this
     * can go once a release with it (1.11.7 ?) is out.
     */
    @Override
    public void remapJar(Jar jar, File target) throws IOException {
        if (target.getParentFile() != null) {
            target.getParentFile().mkdirs();
        }
        final ClassRepo repo = new MemberRepo(jar);
        try (JarOutputStream out = new JarOutputStream(
                new BufferedOutputStream(new FileOutputStream(target), OUTPUT_BUFFER_SIZE))) {
            for (final String name : jar.getEntryNames()) {
                final Pair2<ZipEntry, InputStream> source = jar.getEntry(name);
                try (InputStream in = source.second) {
                    final JarEntry entry;
                    final byte[] data;
                    if (name.endsWith(CLASS_SUFFIX)) {
                        data = remapClassFile(in, repo);
                        entry = new JarEntry(
                                map(name.substring(0, name.length() - CLASS_SUFFIX.length())) + CLASS_SUFFIX);
                    } else if (name.endsWith(".DSA") || name.endsWith(".SF")) {
                        continue;
                    } else {
                        data = in.readAllBytes();
                        entry = new JarEntry(name);
                    }
                    entry.setTime(source.first.getTime());
                    out.putNextEntry(entry);
                    out.write(data);
                }
            }
        }
    }

    @Override
    public String mapFieldName(String owner, String name, String desc, int access) {
        return mapMember(false, owner, name, desc, access);
    }

    @Override
    public String mapMethodName(String owner, String name, String desc, int access) {
        return mapMember(true, owner, name, desc, access);
    }

    private String mapMember(boolean method, String owner, String name, String desc, int access) {
        final MemberKey key = new MemberKey(method, owner, name, desc, access);
        final String cached = memberNames.get(key);
        if (cached != null) {
            return cached;
        }
        final String mapped = method ? super.mapMethodName(owner, name, desc, access)
                : super.mapFieldName(owner, name, desc, access);
        memberNames.put(key, mapped);
        return mapped;
    }

    private record MemberKey(boolean method, String owner, String name, String desc, int access) {}

    /**
     * Keeps the members of every class in the jar. SpecialSource only looks up their names and access here, while its
     * JarRepo parses method code and has a cache too small for large mods, which reads classes over and over.
     */
    private static final class MemberRepo implements ClassRepo {

        private static final int SKIP_ALL = ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES;

        private final Jar jar;
        private final Map<String, Optional<ClassNode>> classes = new HashMap<>();

        private MemberRepo(Jar jar) {
            this.jar = jar;
        }

        @Override
        public ClassNode findClass(String internalName) {
            return classes.computeIfAbsent(internalName, this::read).orElse(null);
        }

        private Optional<ClassNode> read(String internalName) {
            try (InputStream in = jar.getClass(internalName)) {
                if (in == null) {
                    return Optional.empty();
                }
                final ClassNode node = new ClassNode();
                new ClassReader(in).accept(node, SKIP_ALL);
                return Optional.of(node);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
