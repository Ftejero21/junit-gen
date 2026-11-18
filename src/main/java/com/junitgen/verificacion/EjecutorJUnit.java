package com.junitgen.verificacion;

import com.junitgen.proyecto.ResolutorBom;
import com.junitgen.seguridad.ProcesoSeguro;
import com.junitgen.seguridad.SeguridadException;
import com.junitgen.util.Textos;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ejecuta los tests generados con el ConsoleLauncher de JUnit Platform (standalone de la caché Maven
 * o descargado con dependency:get) o con JUnitCore para JUnit &lt; 4.12. cwd y java.io.tmpdir dentro
 * del temporal.
 */
public final class EjecutorJUnit {

    private static final Pattern PLATAFORMA = Pattern.compile("junit-platform-(?:engine|commons|launcher)-([\\d.]+(?:-[\\w.]+)?)\\.jar$");
    private static final Pattern JUNIT4 = Pattern.compile("junit-(4\\.[\\d.]+)\\.jar$");
    private static final Pattern FALLO_JUNITCORE = Pattern.compile("^\\d+\\) ([\\w$]+)\\(([\\w.$]+)\\)$");

    public record Ejecucion(List<ResultadoTest> resultados, boolean xml, String salida, String plataforma) {
    }

    private EjecutorJUnit() {
    }

    public static String versionPlataforma(List<Path> cp) {
        for (Path p : cp) {
            Matcher m = PLATAFORMA.matcher(p.getFileName() == null ? "" : p.getFileName().toString());
            if (m.find()) {
                return m.group(1);
            }
        }
        return null;
    }

    public static String versionJunit4(List<Path> cp) {
        for (Path p : cp) {
            Matcher m = JUNIT4.matcher(p.getFileName() == null ? "" : p.getFileName().toString());
            if (m.find()) {
                return m.group(1);
            }
        }
        return null;
    }

    public static Ejecucion ejecutar(ContextoVerificacion ctx, List<String> clases, List<Path> cp, Path mvn) {
        return ejecutar(ctx, clases, cp, mvn, ProcesoSeguro.TIMEOUT_TESTS);
    }

    /** Como {@link #ejecutar(ContextoVerificacion, List, List, Path)} con un tiempo máximo propio. */
    public static Ejecucion ejecutar(ContextoVerificacion ctx, List<String> clases, List<Path> cp, Path mvn, Duration timeout) {
        Duration limite = timeout == null ? ProcesoSeguro.TIMEOUT_TESTS : timeout;
        String plataforma = versionPlataforma(cp);
        String junit4 = versionJunit4(cp);
        Path reports = ctx.escritor.carpetaTemporal(ctx.tmp.resolve("reports"));
        Path javaTmp = ctx.escritor.carpetaTemporal(ctx.tmp.resolve("java-tmp"));
        Path cwd = ctx.tmp.resolve("run-tests");
        if (plataforma == null && junit4 != null && (!Textos.versionAlMenos(junit4, "4.12") || ctx.jdk.jdk().major() < 8)) {
            return junitCore(ctx, clases, cp, javaTmp, cwd, limite);
        }
        String versionStandalone = plataforma != null ? plataforma : "1.10.2";
        if (plataforma == null && junit4 == null) {
            throw new SeguridadException("No hay JUnit en el classpath del proyecto.");
        }
        Path standalone = standalone(ctx, versionStandalone, mvn);
        List<Path> cpFinal = new ArrayList<>();
        cpFinal.add(standalone);
        cpFinal.addAll(cp);
        List<String> args = new ArrayList<>();
        boolean conExecute = Textos.versionAlMenos(versionStandalone, "1.10");
        List<String> launcher = new ArrayList<>();
        launcher.add("org.junit.platform.console.ConsoleLauncher");
        if (conExecute) {
            launcher.add("execute");
        }
        for (String c : clases) {
            launcher.add("--select-class");
            launcher.add(c);
        }
        launcher.add("--reports-dir");
        launcher.add(CompiladorTemporal.cita(reports));
        launcher.add("--details");
        launcher.add("none");
        launcher.add("--disable-banner");
        ProcesoSeguro.Resultado r;
        if (ctx.jdk.jdk().major() >= 9) {
            List<String> lineas = new ArrayList<>();
            lineas.add("\"-Djava.io.tmpdir=" + javaTmp.toAbsolutePath().toString().replace('\\', '/') + "\"");
            lineas.add("-cp");
            lineas.add(CompiladorTemporal.classpath(cpFinal));
            lineas.addAll(launcher);
            Path argfile = ctx.tmp.resolve("java-tests.args");
            ctx.escritor.escribir(argfile, String.join("\n", lineas) + "\n");
            args.add("@" + argfile);
        } else {
            Path pathing = pathingJar(ctx, cpFinal);
            args.add("-Djava.io.tmpdir=" + javaTmp);
            args.add("-cp");
            args.add(pathing.toString());
            for (String l : launcher) {
                args.add(l.startsWith("\"") ? l.substring(1, l.length() - 1) : l);
            }
        }
        r = ctx.proceso.java(ctx.jdk.jdk().home(), args, cwd, ctx.log("tests.log"), limite);
        if (r.timeout()) {
            throw new SeguridadException("Los tests superaron el tiempo máximo (" + Math.max(1, limite.toMinutes()) + " min). Log: " + r.log());
        }
        try {
            List<ResultadoTest> res = LectorResultadosXml.leer(reports);
            return new Ejecucion(res, !res.isEmpty(), r.salida(), versionStandalone);
        } catch (IOException e) {
            return new Ejecucion(List.of(), false, r.salida() + "\n(no se pudo leer el XML: " + e.getMessage() + ")", versionStandalone);
        }
    }

    private static Path standalone(ContextoVerificacion ctx, String version, Path mvn) {
        ResolutorBom rb = new ResolutorBom(null);
        Path jar = rb.rutaEnRepo("org.junit.platform", "junit-platform-console-standalone", version, "jar");
        if (jar != null && Files.isRegularFile(jar)) {
            return jar;
        }
        if (mvn == null) {
            throw new SeguridadException("Falta junit-platform-console-standalone " + version + " en la caché Maven y no hay Maven para descargarlo.");
        }
        // La descarga a la caché compartida de Maven es única aunque haya varias verificaciones a la vez.
        synchronized (DESCARGA_STANDALONE) {
            if (Files.isRegularFile(jar)) {
                return jar;
            }
            Map<String, String> props = new LinkedHashMap<>();
            props.put("artifact", "org.junit.platform:junit-platform-console-standalone:" + version);
            props.put("transitive", "false");
            ProcesoSeguro.Resultado r = ctx.proceso.maven(mvn, ProcesoSeguro.GoalMaven.GET, null, props, null, ctx.javaHerramienta,
                    ctx.tmp.resolve("run-maven"), ctx.log("maven-get-standalone.log"));
            if (!r.correcto() || !Files.isRegularFile(jar)) {
                throw new SeguridadException("No se pudo obtener junit-platform-console-standalone " + version + " (log: " + r.log() + ").");
            }
            return jar;
        }
    }

    private static final Object DESCARGA_STANDALONE = new Object();

    /** JDK 8 no admite @argfile en java: jar con Class-Path en el manifiesto, dentro del temporal. */
    private static Path pathingJar(ContextoVerificacion ctx, List<Path> cp) {
        Manifest mf = new Manifest();
        mf.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        StringBuilder sb = new StringBuilder();
        for (Path p : cp) {
            String uri = p.toAbsolutePath().toUri().toString();
            if (Files.isDirectory(p) && !uri.endsWith("/")) {
                uri = uri + "/";
            }
            sb.append(uri).append(' ');
        }
        mf.getMainAttributes().put(Attributes.Name.CLASS_PATH, sb.toString().trim());
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (JarOutputStream jos = new JarOutputStream(bos, mf)) {
            jos.flush();
        } catch (IOException e) {
            throw new SeguridadException("No se pudo crear el pathing-jar: " + e.getMessage(), e);
        }
        Path jar = ctx.tmp.resolve("classpath-pathing.jar");
        ctx.escritor.escribir(jar, bos.toByteArray());
        return jar;
    }

    private static Ejecucion junitCore(ContextoVerificacion ctx, List<String> clases, List<Path> cp, Path javaTmp, Path cwd,
                                       Duration limite) {
        List<String> args = new ArrayList<>();
        Path pathing = pathingJar(ctx, cp);
        args.add("-Djava.io.tmpdir=" + javaTmp);
        args.add("-cp");
        args.add(pathing.toString());
        args.add("org.junit.runner.JUnitCore");
        args.addAll(clases);
        ProcesoSeguro.Resultado r = ctx.proceso.java(ctx.jdk.jdk().home(), args, cwd, ctx.log("tests-junitcore.log"), limite);
        List<ResultadoTest> res = new ArrayList<>();
        String[] l = r.salida().split("\\R");
        for (int i = 0; i < l.length; i++) {
            Matcher m = FALLO_JUNITCORE.matcher(l[i].trim());
            if (m.find()) {
                ResultadoTest t = new ResultadoTest();
                t.metodoTest = m.group(1);
                t.clase = m.group(2);
                StringBuilder traza = new StringBuilder();
                for (int j = i + 1; j < l.length && j < i + 16 && !FALLO_JUNITCORE.matcher(l[j].trim()).find(); j++) {
                    traza.append(l[j]).append('\n');
                }
                String primera = i + 1 < l.length ? l[i + 1].trim() : "";
                t.tipoError = primera.contains(":") ? primera.substring(0, primera.indexOf(':')) : primera;
                t.mensaje = primera;
                t.traza = ClasificadorFallos.traza(traza.toString());
                t.causa = ClasificadorFallos.causa(traza.toString());
                t.estado = t.tipoError.contains("AssertionError") || t.tipoError.contains("ComparisonFailure")
                        ? ClasificadorFallos.FALLO_ASERCION : ClasificadorFallos.ERROR_MONTAJE;
                if (ClasificadorFallos.ERROR_MONTAJE.equals(t.estado)) {
                    t.pista = ClasificadorFallos.pista(t.tipoError, t.mensaje, t.traza);
                }
                res.add(t);
            }
        }
        return new Ejecucion(res, false, r.salida(), "JUnitCore");
    }
}
