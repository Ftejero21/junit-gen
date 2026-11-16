package com.junitgen.proyecto;

import com.junitgen.seguridad.RutaSegura;

import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Localiza los módulos Maven/Gradle bajo una raíz (profundidad máxima 6, sin seguir enlaces,
 * ignorando carpetas ocultas y de build/dependencias).
 */
public final class LocalizadorModulos {

    public static final int PROFUNDIDAD_MAX = 6;
    public static final Set<String> IGNORADAS = Set.of("node_modules", ".pnpm-store", "target", "build", "out",
            "dist", "bin", "venv", "__pycache__", "src");

    public static class Resultado {
        public final List<Modulo> modulos = new ArrayList<>();
        /** pom.xml sin fuentes (agregadores/parents). */
        public final List<Path> agregadores = new ArrayList<>();
        /** Todos los pom.xml encontrados (para el pom espejo). */
        public final List<Path> poms = new ArrayList<>();
        public final List<String> avisos = new ArrayList<>();
    }

    private LocalizadorModulos() {
    }

    public static Resultado localizar(Path raiz) {
        Resultado r = new Resultado();
        Set<Path> dirsBuild = new TreeSet<>();
        try {
            Files.walkFileTree(raiz, EnumSet.noneOf(FileVisitOption.class), PROFUNDIDAD_MAX + 1, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (dir.equals(raiz)) {
                        return FileVisitResult.CONTINUE;
                    }
                    String n = dir.getFileName().toString();
                    if (n.startsWith(".") || IGNORADAS.contains(n) || RutaSegura.esEnlace(dir)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String n = file.getFileName().toString();
                    if (n.equals("pom.xml")) {
                        r.poms.add(file);
                        dirsBuild.add(file.getParent());
                    } else if (n.equals("build.gradle") || n.equals("build.gradle.kts")) {
                        dirsBuild.add(file.getParent());
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    r.avisos.add("No se pudo leer " + file + ": " + exc.getMessage());
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            r.avisos.add("Error recorriendo " + raiz + ": " + e.getMessage());
        }
        for (Path dir : dirsBuild) {
            Modulo m = crearModulo(raiz, dir, r);
            if (m != null) {
                r.modulos.add(m);
            }
        }
        r.modulos.sort(Comparator.comparing(m -> m.rel));
        return r;
    }

    private static Modulo crearModulo(Path raiz, Path dir, Resultado r) {
        String rel = raiz.relativize(dir).toString().replace('\\', '/');
        Path pomXml = dir.resolve("pom.xml");
        if (Files.isRegularFile(pomXml)) {
            Pom pom = null;
            try {
                pom = LectorPom.leer(pomXml);
            } catch (IOException e) {
                r.avisos.add("pom.xml ilegible (" + pomXml + "): " + e.getMessage());
            }
            String src = pom != null && pom.sourceDirectory != null ? limpiarBasedir(pom.sourceDirectory) : "src/main/java";
            String test = pom != null && pom.testSourceDirectory != null ? limpiarBasedir(pom.testSourceDirectory) : "src/test/java";
            Path main = dir.resolve(src).normalize();
            if (!Files.isDirectory(main) || !RutaSegura.dentroOIgual(main, dir)) {
                r.agregadores.add(pomXml);
                return null;
            }
            Modulo m = new Modulo(dir, rel, Modulo.TipoBuild.MAVEN, pomXml);
            m.pom = pom;
            m.fuentesMain.add(main);
            Path t = dir.resolve(test).normalize();
            if (Files.isDirectory(t) && RutaSegura.dentroOIgual(t, dir)) {
                m.fuentesTest.add(t);
            }
            Path res = dir.resolve("src/main/resources");
            if (Files.isDirectory(res)) {
                m.recursosMain.add(res);
            }
            return m;
        }
        Path gradle = Files.isRegularFile(dir.resolve("build.gradle")) ? dir.resolve("build.gradle")
                : dir.resolve("build.gradle.kts");
        Path main = dir.resolve("src/main/java");
        if (!Files.isDirectory(main)) {
            return null;
        }
        Modulo m = new Modulo(dir, rel, Modulo.TipoBuild.GRADLE, gradle);
        m.fuentesMain.add(main);
        Path t = dir.resolve("src/test/java");
        if (Files.isDirectory(t)) {
            m.fuentesTest.add(t);
        }
        Path res = dir.resolve("src/main/resources");
        if (Files.isDirectory(res)) {
            m.recursosMain.add(res);
        }
        return m;
    }

    private static String limpiarBasedir(String s) {
        return s.replace("${project.basedir}/", "").replace("${basedir}/", "")
                .replace("${project.basedir}\\", "").replace("${basedir}\\", "").trim();
    }
}
