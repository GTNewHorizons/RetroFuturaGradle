package com.gtnewhorizons.retrofuturagradle.javac;

import com.google.auto.service.AutoService;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.*;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import java.lang.annotation.Annotation;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@AutoService(Processor.class)
@SupportedAnnotationTypes("*")
public class SideOnlyPlugin extends AbstractProcessor {

    static final String SIDE_ONLY_1_7 = "cpw.mods.fml.relauncher.SideOnly";
    static final String SIDE_ONLY_1_12 = "net.minecraftforge.fml.relauncher.SideOnly";

    Types typeUtils;
    Elements elementUtils;
    Messager messager;
    Class<? extends Annotation> sideOnlyClass;

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        typeUtils = processingEnv.getTypeUtils();
        elementUtils = processingEnv.getElementUtils();
        messager = processingEnv.getMessager();
    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latest();
    }

    private Class<? extends Annotation> getSideOnlyClass() {
        if (sideOnlyClass == null) {
            try {
                sideOnlyClass = (Class<? extends Annotation>) Class.forName(SIDE_ONLY_1_7);
            } catch (ClassNotFoundException e1) {
                try {
                    sideOnlyClass = (Class<? extends Annotation>) Class.forName(SIDE_ONLY_1_12);
                } catch (ClassNotFoundException e2) {
                    throw new RuntimeException("Could not initialize SideOnly scanner, SideOnly annotation not found!");
                }
            }
        }
        return sideOnlyClass;
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        for (Element element : roundEnv.getRootElements()) {
            if (element.getKind() == ElementKind.METHOD) {
                ExecutableElement methodElement = (ExecutableElement) element;
                if (!doesMethodHaveAnnotation(methodElement)) {
                    // This method does not have @SideOnly, so make sure super methods do not have it either
                    if (doesOverriddenMethodHaveAnnotation(methodElement)) {
                        messager.printMessage(
                                Diagnostic.Kind.WARNING,
                                "Method '" + getDetail(methodElement) + "' overrides a super method with @SideOnly and does not include the annotation."
                        );
                    }
                }
            }
        }
        return true;
    }

    private boolean doesMethodHaveAnnotation(ExecutableElement methodElement) {
        return methodElement.getAnnotation(getSideOnlyClass()) != null;
    }

    private boolean doesOverriddenMethodHaveAnnotation(ExecutableElement element) {
        Element enclElement = element.getEnclosingElement();
        if (enclElement instanceof TypeElement) {
            TypeElement typeElement = (TypeElement) enclElement; // will be a class

            // check super class methods
            while (typeElement.getSuperclass().getKind() != TypeKind.NONE) {
                TypeElement superClassElement = (TypeElement) typeUtils.asElement(typeElement.getSuperclass());
                for (Element enclosedElement : superClassElement.getEnclosedElements()) {
                    if (enclosedElement.getKind() == ElementKind.METHOD
                            && elementUtils.overrides(element, (ExecutableElement) enclosedElement, typeElement)) {
                        if (doesMethodHaveAnnotation((ExecutableElement) enclosedElement)) {
                            return true;
                        }
                    }
                }
                typeElement = superClassElement;
            }

            // check interface methods
            for (TypeMirror mirror : typeElement.getInterfaces()) {
                TypeElement mirrorElement = (TypeElement) typeUtils.asElement(mirror);
                for (Element enclosedElement : mirrorElement.getEnclosedElements()) {
                    if (enclosedElement.getKind() == ElementKind.METHOD
                            && elementUtils.overrides(element, (ExecutableElement) enclosedElement, typeElement)) {
                        if (doesMethodHaveAnnotation((ExecutableElement) enclosedElement)) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    String getDetail(Element e) {
        if (e.getKind().isField()) {
            return e.getSimpleName().toString();
        } else {
            // method or constructor
            ExecutableElement ee = (ExecutableElement) e;
            String ret;
            ret = desc(ee.getReturnType());
            List<? extends TypeMirror> parameterTypes = ((ExecutableType) ee.asType()).getParameterTypes();
            String params = parameterTypes.stream()
                    .map(this::desc)
                    .collect(Collectors.joining());
            return ee.getSimpleName().toString() + "(" + params + ")" + ret;
        }
    }

    String desc(TypeMirror tm) {
        switch (tm.getKind()) {
            case BOOLEAN:
                return "Z";
            case BYTE:
                return "B";
            case SHORT:
                return "S";
            case CHAR:
                return "C";
            case INT:
                return "I";
            case LONG:
                return "J";
            case FLOAT:
                return "F";
            case DOUBLE:
                return "D";
            case VOID:
                return "V";
            case DECLARED:
                String s = ((TypeElement) ((DeclaredType)tm).asElement()).getQualifiedName().toString();
                s = s.replace('.', '/');
                return "L" + s + ";";
            case ARRAY:
                return "[" + desc(((ArrayType) tm).getComponentType());
            default: return tm.getKind().toString();
        }
    }
}
