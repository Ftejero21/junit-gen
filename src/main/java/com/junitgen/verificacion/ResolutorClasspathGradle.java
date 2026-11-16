package com.junitgen.verificacion;

import com.junitgen.proyecto.LectorGradle;
import com.junitgen.seguridad.ProcesoSeguro;
import com.junitgen.seguridad.RutaSegura;
import com.junitgen.seguridad.SeguridadException;
import com.junitgen.util.Textos;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Classpath por Gradle (nunca gradlew): solo si no hay buildSrc ni includeBuild. Usa gradle del
 * PATH o de ~/.gradle/wrapper/dists, con --project-cache-dir e init script en el temporal.
 */
public final class ResolutorClasspathGradle {

    private static final String INIT = """
            allprojects {
                tasks.register('junitGenClasspath') {
                    doLast {
                        def ss = project.extensions.findByName('sourceSets')
                        if (ss != null && ss.findByName('test') != null) {
                            println 'JUNITGEN_CP=' + ss.test.runtimeClasspath.files.join(File.pathSeparator)
                        }
                    }
                }
            }
            """;

    private ResolutorClasspathGradle() {
    }

    public static List<Path> resolver(ContextoVerificacion ctx) {
        Path dir = ctx.modulo.modulo.dir;
        LectorGradle.InfoGradle info = LectorGradle.leer(dir, ctx.proyecto.raiz);
        if (info.usaBuildSrc || info.usaIncludeBuild) {
            throw new SeguridadException("El proyecto Gradle usa buildSrc o includeBuild: obtener el classpath compilaría código del build."
                    + " Indica la lista 'classpath' en junit-gen.yml.");
        }
        Path gradle = localizarGradle();
        if (gradle == null) {
            throw new SeguridadException("No se encontró Gradle (ni en PATH ni en ~/.gradle/wrapper/dists). Indica 'classpath' en junit-gen.yml.");
        }
        Path init = ctx.tmp.resolve("init.gradle");
        ctx.escritor.escribir(init, INIT);
        Path cache = ctx.escritor.carpetaTemporal(ctx.tmp.resolve("gradle-cache"));
        ProcesoSeguro.Resultado r = ctx.proceso.gradle(gradle, dir, init, cache, ctx.javaHerramienta, ctx.tmp.resolve("run-gradle"),
                ctx.log("gradle-classpath.log"));
        if (!r.correcto()) {
            throw new SeguridadException("Gradle no pudo obtener el classpath. Log: " + r.log()
                    + ". Indica 'classpath' en junit-gen.yml o compila el proyecto tú mismo.");
        }
        List<Path> cp = new ArrayList<>();
        for (String linea : r.salida().split("\\R")) {
            if (linea.startsWith("JUNITGEN_CP=")) {
                for (String e : linea.substring("JUNITGEN_CP=".length()).split(java.util.regex.Pattern.quote(File.pathSeparator))) {
                    if (!e.isBlank()) {
                        cp.add(Paths.get(e.trim()));
                    }
                }
                break;
            }
        }
        if (cp.isEmpty()) {
            throw new SeguridadException("Gradle no devolvió el classpath de test. Indica 'classpath' en junit-gen.yml.");
        }
        return cp;
    }

    private static Path localizarGradle() {
        String nombre = RutaSegura.WINDOWS ? "gradle.bat" : "gradle";
        String path = System.getenv("PATH");
        if (path != null) {
            for (String d : path.split(File.pathSeparator)) {
                try {
                    Path p = Paths.get(d.trim()).resolve(nombre);
                    if (!d.isBlank() && Files.isRegularFile(p)) {
                        return p;
                    }
                } catch (RuntimeException e) {
                    System.err.println("Aviso: entrada de PATH ignorada: " + d);
                }
            }
        }
        Path dists = Paths.get(System.getProperty("user.home"), ".gradle", "wrapper", "dists");
        Path mejor = null;
        String mejorV = null;
        if (Files.isDirectory(dists)) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(dists, "gradle-*")) {
                for (Path d : ds) {
                    try (DirectoryStream<Path> hs = Files.newDirectoryStream(d)) {
                        for (Path h : hs) {
                            try (DirectoryStream<Path> gs = Files.newDirectoryStream(h, "gradle-*")) {
                                for (Path g : gs) {
                                    Path bin = g.resolve("bin").resolve(nombre);
                                    String v = g.getFileName().toString().replace("gradle-", "");
                                    if (Files.isRegularFile(bin) && (mejorV == null || Textos.compararVersiones(v, mejorV) > 0)) {
                                        mejor = bin;
                                        mejorV = v;
                                    }
                                }
                            } catch (IOException e) {
                                System.err.println("Aviso: " + e.getMessage());
                            }
                        }
                    }
                }
            } catch (IOException e) {
                System.err.println("Aviso: no se pudo listar " + dists + ": " + e.getMessage());
            }
        }
        return mejor;
    }
}
