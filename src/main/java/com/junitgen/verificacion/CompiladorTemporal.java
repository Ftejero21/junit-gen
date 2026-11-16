package com.junitgen.verificacion;

import com.junitgen.seguridad.ProcesoSeguro;
import com.junitgen.seguridad.RutaSegura;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Compila en el temporal (nunca en el proyecto): fuentes del proyecto si no hay clases compiladas, y
 * los tests generados con -proc:none. Siempre con argfiles (classpath &gt; 32 000 caracteres).
 */
public final class CompiladorTemporal {

    private static final Pattern DIAGNOSTICO = Pattern.compile("^(.+\\.java):(\\d+): (?:error|warning|advertencia): (.*)$");
    private static final Pattern SIMBOLO_CLASE = Pattern.compile(
            "^\\s*(?:symbol|s\\S{1,3}mbolo)\\s*:\\s+(?:class|clase)\\s+([A-Za-z_$][\\w$]*)\\s*$");

    private static final Pattern FIN_DIAGNOSTICOS = Pattern.compile("^\\d+ (?:error|errors|warning|warnings)$");
    private static final int MAX_DETALLE = 8;

    /**
     * Un diagnóstico de javac. {@code detalle}: líneas que javac añade tras el código (symbol, location,
     * required, found, reason, candidatos), sin la línea de código ni el «^».
     */
    public record Diagnostico(Path archivo, int linea, String mensaje, List<String> detalle) {
        public Diagnostico {
            detalle = detalle == null ? List.of() : List.copyOf(detalle);
        }

        public Diagnostico(Path archivo, int linea, String mensaje) {
            this(archivo, linea, mensaje, List.of());
        }
    }

    /** Resultado de compilar un único archivo de test. Diagnósticos solo si no es correcto. */
    public record ResultadoArchivo(boolean correcto, List<Diagnostico> diagnosticos, String salida) {
    }

    public record ResultadoTests(Path clases, List<Path> compilados, Map<Path, List<Diagnostico>> errores, String salida) {
    }

    private CompiladorTemporal() {
    }

    static String cita(Path p) {
        return "\"" + p.toAbsolutePath().toString().replace('\\', '/') + "\"";
    }

    static String classpath(List<Path> cp) {
        List<String> r = new ArrayList<>();
        for (Path p : cp) {
            r.add(p.toAbsolutePath().toString().replace('\\', '/'));
        }
        return "\"" + String.join(java.io.File.pathSeparator, r) + "\"";
    }

    private static List<String> opcionesVersion(ContextoVerificacion ctx) {
        List<String> r = new ArrayList<>();
        if (ctx.jdk.release() != null) {
            int rel = ctx.jdk.release();
            if (ctx.jdk.jdk().major() >= 9) {
                r.add("--release");
                r.add(String.valueOf(rel));
            } else {
                r.add("-source");
                r.add(rel <= 8 ? "1." + rel : String.valueOf(rel));
                r.add("-target");
                r.add(rel <= 8 ? "1." + rel : String.valueOf(rel));
            }
        }
        return r;
    }

    /** ¿Hay algún .class en la carpeta? */
    public static boolean tieneClases(Path dir) {
        if (!Files.isDirectory(dir)) {
            return false;
        }
        try (var s = Files.walk(dir, 30)) {
            return s.anyMatch(p -> p.toString().endsWith(".class"));
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    public static long ultimaModificacion(Path dir, String extension) {
        long[] max = {0};
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes attrs) {
                    return !d.equals(dir) && RutaSegura.esEnlace(d) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path f, BasicFileAttributes attrs) {
                    if (f.toString().endsWith(extension)) {
                        max[0] = Math.max(max[0], attrs.lastModifiedTime().toMillis());
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            return 0;
        }
        return max[0];
    }

    static List<Path> fuentesJava(Path dir) {
        List<Path> r = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return r;
        }
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes attrs) {
                    return !d.equals(dir) && RutaSegura.esEnlace(d) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path f, BasicFileAttributes attrs) {
                    if (attrs.isRegularFile() && f.toString().endsWith(".java")) {
                        r.add(f);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new IllegalStateException("No se pudo listar " + dir + ": " + e.getMessage(), e);
        }
        return r;
    }

    /** Compila src/main/java del módulo en {@code <tmp>/main-classes}. Devuelve la carpeta o lanza error. */
    public static Path compilarMain(ContextoVerificacion ctx, List<Path> classpath) {
        Path destino = ctx.escritor.carpetaTemporal(ctx.tmp.resolve("main-classes"));
        Path gen = ctx.escritor.carpetaTemporal(ctx.tmp.resolve("gen"));
        List<String> lineas = new ArrayList<>();
        lineas.add("-d");
        lineas.add(cita(destino));
        lineas.add("-s");
        lineas.add(cita(gen));
        lineas.add("-encoding");
        lineas.add("UTF-8");
        lineas.add("-nowarn");
        lineas.add("-g");
        if (ctx.jdk.jdk().major() >= 23) {
            lineas.add("-proc:full");
        }
        lineas.addAll(opcionesVersion(ctx));
        if (!classpath.isEmpty()) {
            lineas.add("-cp");
            lineas.add(classpath(classpath));
        }
        int n = 0;
        for (Path src : ctx.modulo.modulo.fuentesMain) {
            for (Path f : fuentesJava(src)) {
                lineas.add(cita(f));
                n++;
            }
        }
        if (n == 0) {
            throw new IllegalStateException("El módulo no tiene fuentes en src/main/java.");
        }
        Path argfile = ctx.tmp.resolve("javac-main.args");
        ctx.escritor.escribir(argfile, String.join("\n", lineas) + "\n");
        ProcesoSeguro.Resultado r = ctx.proceso.javac(ctx.jdk.jdk().home(), List.of("@" + argfile), ctx.tmp.resolve("run-javac"),
                ctx.log("javac-main.log"));
        if (!r.correcto()) {
            throw new IllegalStateException("No se pudieron compilar las fuentes del proyecto en el temporal (" + r.log()
                    + "). Compila el proyecto tú mismo (sus clases en target/classes se usarán solo en lectura) y repite. "
                    + ResolutorClasspathMaven.ultimas(r.salida(), 10));
        }
        return destino;
    }

    public static ResultadoTests compilarTests(ContextoVerificacion ctx, List<Path> tests, List<Path> classpath) {
        return compilarTests(ctx, tests, classpath, "");
    }

    /**
     * Como {@link #compilarTests(ContextoVerificacion, List, List)}, con {@code sufijo} en los nombres de
     * log y argfile (p. ej. "-reintento", para conservar los del primer intento). Parte de un
     * {@code test-classes} vacío.
     */
    public static ResultadoTests compilarTests(ContextoVerificacion ctx, List<Path> tests, List<Path> classpath, String sufijo) {
        ctx.escritor.borrarTemporal(ctx.tmp.resolve("test-classes"));
        Path destino = ctx.escritor.carpetaTemporal(ctx.tmp.resolve("test-classes"));
        ProcesoSeguro.Resultado r = javacTests(ctx, tests, classpath, destino, "javac-tests" + sufijo);
        Map<Path, List<Diagnostico>> errores = new LinkedHashMap<>();
        if (r.correcto()) {
            return new ResultadoTests(destino, new ArrayList<>(tests), errores, r.salida());
        }
        // Aislar: compilar archivo a archivo para saber cuáles fallan.
        List<Path> buenos = new ArrayList<>();
        int i = 0;
        for (Path t : tests) {
            Path aislado = ctx.escritor.carpetaTemporal(ctx.tmp.resolve("aislado").resolve("t" + i));
            ProcesoSeguro.Resultado ri = javacTests(ctx, List.of(t), classpath, aislado, "javac-aislado-" + i + sufijo);
            i++;
            if (ri.correcto()) {
                buenos.add(t);
            } else {
                errores.put(t, diagnosticos(ri.salida(), t));
            }
        }
        if (!buenos.isEmpty()) {
            ctx.escritor.borrarTemporal(destino);
            destino = ctx.escritor.carpetaTemporal(ctx.tmp.resolve("test-classes"));
            ProcesoSeguro.Resultado r2 = javacTests(ctx, buenos, classpath, destino, "javac-tests-buenos" + sufijo);
            if (!r2.correcto()) {
                for (Path b : buenos) {
                    errores.put(b, diagnosticos(r2.salida(), b));
                }
                buenos.clear();
            }
        }
        return new ResultadoTests(destino, buenos, errores, r.salida());
    }

    /**
     * Compila un único archivo de test en {@code destino} (carpeta temporal bajo {@code ctx.tmp}), con
     * {@code nombre} para argfile y log.
     */
    static ResultadoArchivo compilarArchivo(ContextoVerificacion ctx, Path archivo, List<Path> classpath, Path destino, String nombre) {
        Path d = ctx.escritor.carpetaTemporal(destino);
        ProcesoSeguro.Resultado r = javacTests(ctx, List.of(archivo), classpath, d, nombre);
        if (r.correcto()) {
            return new ResultadoArchivo(true, List.of(), r.salida());
        }
        return new ResultadoArchivo(false, diagnosticos(r.salida() == null ? "" : r.salida(), archivo), r.salida());
    }

    private static ProcesoSeguro.Resultado javacTests(ContextoVerificacion ctx, List<Path> tests, List<Path> classpath, Path destino,
                                                      String nombre) {
        List<String> lineas = new ArrayList<>();
        lineas.add("-proc:none");
        lineas.add("-d");
        lineas.add(cita(destino));
        lineas.add("-encoding");
        lineas.add("UTF-8");
        lineas.add("-nowarn");
        lineas.add("-g");
        lineas.addAll(opcionesVersion(ctx));
        lineas.add("-cp");
        lineas.add(classpath(classpath));
        for (Path t : tests) {
            lineas.add(cita(t));
        }
        Path argfile = ctx.tmp.resolve(nombre + ".args");
        ctx.escritor.escribir(argfile, String.join("\n", lineas) + "\n");
        return ctx.proceso.javac(ctx.jdk.jdk().home(), List.of("@" + argfile), ctx.tmp.resolve("run-javac"), ctx.log(nombre + ".log"));
    }

    /**
     * Clases que javac no encontró, por archivo: líneas «symbol: class X» (o «símbolo: clase X», con la
     * í posiblemente mal codificada) tras cada diagnóstico.
     */
    static Map<Path, Set<String>> clasesNoEncontradas(String salida) {
        Map<Path, Set<String>> r = new LinkedHashMap<>();
        if (salida == null) {
            return r;
        }
        Path actual = null;
        for (String linea : salida.split("\\R")) {
            Matcher md = DIAGNOSTICO.matcher(linea);
            if (md.find()) {
                try {
                    actual = Path.of(md.group(1).trim());
                } catch (RuntimeException e) {
                    actual = null;
                }
                continue;
            }
            Matcher ms = SIMBOLO_CLASE.matcher(linea);
            if (actual != null && ms.find()) {
                r.computeIfAbsent(actual, k -> new LinkedHashSet<>()).add(ms.group(1));
            }
        }
        return r;
    }

    static List<Diagnostico> diagnosticos(String salida, Path archivo) {
        List<Diagnostico> r = new ArrayList<>();
        String[] lineas = salida.split("\\R");
        for (int i = 0; i < lineas.length; i++) {
            Matcher m = DIAGNOSTICO.matcher(lineas[i]);
            if (m.find() && lineas[i].contains(": error: ")) {
                StringBuilder msg = new StringBuilder(m.group(3));
                if (i + 1 < lineas.length && !DIAGNOSTICO.matcher(lineas[i + 1]).find()) {
                    msg.append(" → ").append(lineas[i + 1].trim());
                }
                List<String> detalle = new ArrayList<>();
                boolean codigo = true;
                for (int j = i + 1; j < lineas.length; j++) {
                    String l = lineas[j];
                    if (DIAGNOSTICO.matcher(l).find() || FIN_DIAGNOSTICOS.matcher(l.trim()).matches()) {
                        break;
                    }
                    if (codigo) {
                        codigo = false;
                        continue;
                    }
                    String t = l.trim();
                    if (t.isEmpty() || t.matches("\\^+")) {
                        continue;
                    }
                    if (detalle.size() < MAX_DETALLE) {
                        detalle.add(t);
                    }
                }
                Path f;
                try {
                    f = Path.of(m.group(1).trim());
                } catch (RuntimeException e) {
                    f = archivo;
                }
                r.add(new Diagnostico(f, Integer.parseInt(m.group(2)), msg.toString(), detalle));
            }
        }
        if (r.isEmpty()) {
            r.add(new Diagnostico(archivo, 0, ResolutorClasspathMaven.ultimas(salida, 10)));
        }
        return r;
    }
}
