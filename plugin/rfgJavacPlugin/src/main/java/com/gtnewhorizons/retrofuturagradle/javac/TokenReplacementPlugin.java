package com.gtnewhorizons.retrofuturagradle.javac;

import com.google.auto.service.AutoService;
import com.gtnewhorizons.retrofuturagradle.TokenReplacement;
import com.sun.source.util.JavacTask;
import com.sun.source.util.Plugin;
import com.sun.source.util.TaskEvent;
import com.sun.source.util.TaskListener;
import com.sun.tools.javac.api.BasicJavacTask;
import com.sun.tools.javac.main.JavaCompiler;
import com.sun.tools.javac.parser.JavacParser;
import com.sun.tools.javac.parser.ParserFactory;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.Log;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.description.modifier.Visibility;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;
import net.bytebuddy.dynamic.scaffold.subclass.ConstructorStrategy;
import net.bytebuddy.implementation.MethodDelegation;
import net.bytebuddy.implementation.SuperMethodCall;
import net.bytebuddy.implementation.bind.annotation.FieldValue;
import net.bytebuddy.implementation.bind.annotation.SuperMethod;
import net.bytebuddy.implementation.bind.annotation.This;
import net.bytebuddy.matcher.ElementMatchers;

/**
 * Replaces tokens in the source file with other values, used to mimic ForgeGradle's replacement process.
 * Run with: javac -cp rfgplugins.jar "-Xplugin:RetrofuturagradleTokenReplacement file:/home/developer/mod/replacements.properties" src/Main.java
 */
@AutoService(Plugin.class)
public class TokenReplacementPlugin implements Plugin, TaskListener {

    public static final String TOKEN_REPLACEMENT_PLUGIN_FIELD = "tokenReplacementPlugin";
    private Log log;
    public final TokenReplacement replacer = new TokenReplacement();

    @Override
    public String getName() {
        return "RetrofuturagradleTokenReplacement";
    }

    @Override
    public void init(JavacTask rawTask, String... args) {
        BasicJavacTask task = (BasicJavacTask) rawTask;
        Context ctx = task.getContext();

        log = Log.instance(ctx);
        if (args.length >= 1) {
            if (args.length > 1) {
                throw new RuntimeException("RFG Token replacement javac plugin only takes one argument");
            }
            replacer.loadConfig(args[0]);
        }

        // Intercept calls to parse source files to modify their contents with the token replacements as needed
        try {
            final Field compilerKeyField = JavaCompiler.class.getDeclaredField("compilerKey");
            compilerKeyField.setAccessible(true);
            final Context.Key<JavaCompiler> compilerKey = (Context.Key<JavaCompiler>) compilerKeyField.get(null);
            final JavaCompiler compiler = ctx.get(compilerKey);
            final ByteBuddy buddy = new ByteBuddy();
            final List<Field> parserFactoryFields = Arrays.stream(
                            compiler.getClass().getDeclaredFields())
                    .filter(df -> df.getType().isAssignableFrom(ParserFactory.class))
                    .collect(Collectors.toList());
            if (parserFactoryFields.isEmpty()) {
                throw new IllegalStateException("[rfg] No ParserFactory found");
            } else if (parserFactoryFields.size() > 1) {
                log.printRawLines("[rfg] Warning: multiple ParserFactory fields found");
            }
            parserFactoryFields.forEach(f -> f.setAccessible(true));
            final ParserFactory originalFactory =
                    (ParserFactory) parserFactoryFields.get(0).get(compiler);
            final Field pfKeyField = originalFactory.getClass().getDeclaredField("parserFactoryKey");
            pfKeyField.setAccessible(true);
            Context.Key<ParserFactory> pfKey = (Context.Key<ParserFactory>) pfKeyField.get(null);

            Class<? extends ParserFactory> wrapperFactoryClass = buddy.subclass(
                            originalFactory.getClass(), ConstructorStrategy.Default.NO_CONSTRUCTORS)
                    .name("rfg.ParserFactoryTokenReplacingWrapper")
                    .defineConstructor(Visibility.PUBLIC)
                    .withParameter(Context.class, "context", 0)
                    .intercept(SuperMethodCall.INSTANCE)
                    .defineField(TOKEN_REPLACEMENT_PLUGIN_FIELD, this.getClass(), Visibility.PUBLIC)
                    .method(ElementMatchers.named("newParser"))
                    .intercept(MethodDelegation.to(ParserFactoryInterceptor.class))
                    .make()
                    .load(getClass().getClassLoader(), ClassLoadingStrategy.Default.WRAPPER)
                    .getLoaded();
            // Remove the old factory
            try {
                ctx.put(pfKey, (ParserFactory) null);
            } catch (AssertionError ignored) {
            }
            Constructor<? extends ParserFactory> constructor = wrapperFactoryClass.getConstructor(Context.class);
            constructor.setAccessible(true);
            ParserFactory wrapperFactory = constructor.newInstance(ctx);
            wrapperFactoryClass.getField(TOKEN_REPLACEMENT_PLUGIN_FIELD).set(wrapperFactory, this);
            for (Field f : parserFactoryFields) {
                f.set(compiler, wrapperFactory);
            }
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
        task.addTaskListener(this);
    }

    public File lastFile;

    @Override
    public void started(TaskEvent ev) {
        if (replacer.isEmpty()) {
            return;
        }
        if (ev.getKind() != TaskEvent.Kind.PARSE) {
            return;
        }
        // ev.getSourceFile() is the input file
        // ev.getCompilationUnit() is null
        // invoked just before creating a parser from the factory
        try {
            final File srcFile = new File(ev.getSourceFile().getName()).getCanonicalFile();
            if (!replacer.shouldReplaceInFile(srcFile)) {
                return;
            }
            lastFile = srcFile;
        } catch (IOException exc) {
            throw new RuntimeException(exc);
        }
    }

    @Override
    public void finished(TaskEvent ev) {
        // no-op
    }

    public static class ParserFactoryInterceptor {
        public static JavacParser newParser(
                CharSequence input,
                boolean keepDocComments,
                boolean keepEndPos,
                boolean keepLineMap,
                boolean parseModuleInfo,
                @SuperMethod(fallbackToDefault = false) Method superCall,
                @This Object self,
                @FieldValue(TOKEN_REPLACEMENT_PLUGIN_FIELD) TokenReplacementPlugin plugin)
                throws ReflectiveOperationException {
            input = transformInput(input, plugin);
            return (JavacParser)
                    superCall.invoke(self, input, keepDocComments, keepEndPos, keepLineMap, parseModuleInfo);
        }

        public static JavacParser newParser(
                CharSequence input,
                boolean keepDocComments,
                boolean keepEndPos,
                boolean keepLineMap,
                @SuperMethod(fallbackToDefault = false) Method superCall,
                @This Object self,
                @FieldValue(TOKEN_REPLACEMENT_PLUGIN_FIELD) TokenReplacementPlugin plugin)
                throws ReflectiveOperationException {
            input = transformInput(input, plugin);
            return (JavacParser) superCall.invoke(self, input, keepDocComments, keepEndPos, keepLineMap);
        }

        public static CharSequence transformInput(CharSequence input, TokenReplacementPlugin plugin) {
            if (plugin.lastFile != null) {
                final File lastFile = plugin.lastFile;
                plugin.lastFile = null;
                // replace
                input = plugin.replacer.replaceIfNeeded(
                        input,
                        totalReplaced -> plugin.log.printRawLines(
                                Log.WriterKind.NOTICE,
                                String.format("RFG: Replaced %d tokens in %s", totalReplaced, lastFile.getPath())));
            }
            return input;
        }
    }
}
