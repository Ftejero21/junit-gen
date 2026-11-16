package com.junitgen.verificacion;

import com.junitgen.config.CargadorConfig;
import com.junitgen.escaneo.ModuloEscaneado;
import com.junitgen.escaneo.ProyectoEscaneado;
import com.junitgen.proyecto.Modulo;
import com.junitgen.seguridad.EscritorSeguro;
import com.junitgen.seguridad.ProcesoSeguro;
import com.junitgen.util.Json;
import com.junitgen.util.Textos;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Preparación de un módulo para verificar tests: JDK, Maven, classpath de dependencias (junit-gen.yml, pom
 * espejo de Maven o Gradle) y clases main del proyecto (las compiladas del proyecto, en solo lectura, o las
 * compiladas por junit-gen). Se hace una vez por módulo y se cachea en memoria y en
 * {@code <salida>/.junit-gen-tmp/<módulo>/prep/prep.json}, invalidándose por huella (archivos de build,
 * junit-gen.yml, JDK y, si el proyecto no está compilado, las fuentes main). Los fallos recientes se cachean
 * {@link #CADUCIDAD_FALLO} para no repetir Maven. Se llama siempre con el bloqueo de ejecución de la salida.
 */
public final class PreparadorModulos {

    /** Carpeta de la preparación dentro de {@code <tmp>/<módulo>}. */
    public static final String CARPETA_PREP = "prep";
    /** Archivo con la preparación persistida. */
    public static final String ARCHIVO_PREP = "prep.json";
    /** Tiempo durante el que se recuerda un fallo de preparación con la misma huella. */
    public static final Duration CADUCIDAD_FALLO = Duration.ofMinutes(5);
    private static final int LONGITUD_HUELLA = 64;

    /** Preparación lista para compilar y ejecutar tests del módulo. */
    public record Preparacion(LocalizadorJdk.Seleccion jdk, Path mvn, List<Path> deps, List<Path> clasesProyecto, String origenCp,
                              List<String> avisos, String huella, boolean reutilizada) {
    }

    private record Fallo(String huella, String mensaje, Instant instante) {
    }

    /** Forma persistida en prep.json. */
    static final class Guardada {
        public String huella;
        public String origenCp;
        public String mvn;
        public List<String> deps = new ArrayList<>();
        public List<String> clasesProyecto = new ArrayList<>();
        public List<String> avisos = new ArrayList<>();
    }

    private static final Map<String, LocalizadorJdk.Seleccion> JDKS = new ConcurrentHashMap<>();
    private static final Map<String, Preparacion> PREPARACIONES = new ConcurrentHashMap<>();
    private static final Map<String, Fallo> FALLOS = new ConcurrentHashMap<>();

    private PreparadorModulos() {
    }

    /** Carpeta {@code <tmp>/<módulo>} del módulo. */
    static Path dirModulo(EscritorSeguro esc, ModuloEscaneado me) {
        return esc.temporal().resolve(ContextoVerificacion.clave(me));
    }

    /** Clases de test compiladas del proyecto (se añaden al classpath si existen). */
    static Path testClassesProyecto(ModuloEscaneado me) {
        return me.modulo.build == Modulo.TipoBuild.MAVEN ? me.modulo.dir.resolve("target").resolve("test-classes")
                : me.modulo.dir.resolve("build").resolve("classes").resolve("java").resolve("test");
    }

    private static Path compiladas(ModuloEscaneado me) {
        return me.modulo.build == Modulo.TipoBuild.MAVEN ? me.modulo.dir.resolve("target").resolve("classes")
                : me.modulo.dir.resolve("build").resolve("classes").resolve("java").resolve("main");
    }

    /**
     * Devuelve la preparación del módulo: la de memoria o la de disco si la huella coincide y todas sus rutas
     * existen; si no, la prepara de nuevo (borrando {@code <tmp>/<módulo>} entero). Un fallo reciente con la
     * misma huella se relanza sin repetir Maven/Gradle.
     */
    public static Preparacion obtener(ProyectoEscaneado p, EscritorSeguro esc, ModuloEscaneado me) {
        String clave = esc.salida().toString().toLowerCase(Locale.ROOT) + "|" + ContextoVerificacion.clave(me);
        String claveJdk = clave + "|" + Objects.toString(me.versiones.java) + "|" + Objects.toString(me.config.jdk);
        LocalizadorJdk.Seleccion jdk = JDKS.get(claveJdk);
        if (jdk == null) {
            jdk = LocalizadorJdk.elegir(me.versiones.java, me.config);
            JDKS.put(claveJdk, jdk);
        }
        boolean proyectoCompilado = CompiladorTemporal.tieneClases(compiladas(me));
        String huella = huella(p, me, jdk, proyectoCompilado);
        List<String> avisosFrescos = new ArrayList<>();
        if (jdk.aviso() != null) {
            avisosFrescos.add(jdk.aviso());
        }
        if (proyectoCompilado) {
            avisoFuentesRecientes(me, avisosFrescos);
        }
        Preparacion enMemoria = PREPARACIONES.get(clave);
        if (enMemoria != null && enMemoria.huella().equals(huella) && rutasExisten(enMemoria.mvn(), enMemoria.deps(),
                enMemoria.clasesProyecto())) {
            return conAvisos(enMemoria, jdk, avisosFrescos, true);
        }
        Fallo f = FALLOS.get(clave);
        if (f != null && f.huella().equals(huella) && Duration.between(f.instante(), Instant.now()).compareTo(CADUCIDAD_FALLO) < 0) {
            throw new IllegalStateException(f.mensaje());
        }
        Path dirPrep = dirModulo(esc, me).resolve(CARPETA_PREP);
        Preparacion deDisco = leerDisco(dirPrep.resolve(ARCHIVO_PREP), huella, jdk);
        if (deDisco != null) {
            PREPARACIONES.put(clave, deDisco);
            FALLOS.remove(clave);
            return conAvisos(deDisco, jdk, avisosFrescos, true);
        }
        try {
            Preparacion nueva = preparar(p, esc, me, jdk, huella, proyectoCompilado, dirPrep);
            PREPARACIONES.put(clave, nueva);
            FALLOS.remove(clave);
            return conAvisos(nueva, jdk, avisosFrescos, false);
        } catch (RuntimeException e) {
            PREPARACIONES.remove(clave);
            FALLOS.put(clave, new Fallo(huella, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(), Instant.now()));
            throw e;
        }
    }

    /**
     * Preparación vigente del módulo (memoria o {@code prep.json} con la huella actual) sin preparar nada: no
     * lanza Maven/Gradle ni registra fallos. null si no hay ninguna vigente o algo falla.
     */
    public static Preparacion vigente(ProyectoEscaneado p, Path salida, ModuloEscaneado me) {
        if (p == null || salida == null || me == null) {
            return null;
        }
        try {
            Path real;
            try {
                real = salida.toRealPath();
            } catch (IOException e) {
                real = salida.toAbsolutePath().normalize();
            }
            String clave = real.toString().toLowerCase(Locale.ROOT) + "|" + ContextoVerificacion.clave(me);
            String claveJdk = clave + "|" + Objects.toString(me.versiones.java) + "|" + Objects.toString(me.config.jdk);
            LocalizadorJdk.Seleccion jdk = JDKS.get(claveJdk);
            if (jdk == null) {
                jdk = LocalizadorJdk.elegir(me.versiones.java, me.config);
                JDKS.put(claveJdk, jdk);
            }
            String huella = huella(p, me, jdk, CompiladorTemporal.tieneClases(compiladas(me)));
            Preparacion enMemoria = PREPARACIONES.get(clave);
            if (enMemoria != null && enMemoria.huella().equals(huella) && rutasExisten(enMemoria.mvn(), enMemoria.deps(),
                    enMemoria.clasesProyecto())) {
                return enMemoria;
            }
            Path archivo = real.resolve(EscritorSeguro.CARPETA_TMP).resolve(ContextoVerificacion.clave(me)).resolve(CARPETA_PREP)
                    .resolve(ARCHIVO_PREP);
            return leerDisco(archivo, huella, jdk);
        } catch (RuntimeException e) {
            System.err.println("junit-gen: no se pudo consultar la preparación de " + ContextoVerificacion.clave(me) + ": " + e.getMessage());
            return null;
        }
    }

    /** Classpath de compilación de tests: clases del proyecto, dependencias y test-classes del proyecto si existen. */
    public static List<Path> classpathCompilacion(Preparacion prep, ModuloEscaneado me) {
        List<Path> cp = new ArrayList<>(prep.clasesProyecto());
        cp.addAll(prep.deps());
        Path testClases = testClassesProyecto(me);
        if (Files.isDirectory(testClases)) {
            cp.add(testClases);
        }
        return cp;
    }

    private static Preparacion conAvisos(Preparacion base, LocalizadorJdk.Seleccion jdk, List<String> frescos, boolean reutilizada) {
        LinkedHashSet<String> avisos = new LinkedHashSet<>(frescos);
        avisos.addAll(base.avisos());
        return new Preparacion(jdk, base.mvn(), base.deps(), base.clasesProyecto(), base.origenCp(), List.copyOf(avisos), base.huella(),
                reutilizada);
    }

    private static void avisoFuentesRecientes(ModuloEscaneado me, List<String> avisos) {
        Path compiladas = compiladas(me);
        long fuentes = 0;
        for (Path src : me.modulo.fuentesMain) {
            fuentes = Math.max(fuentes, CompiladorTemporal.ultimaModificacion(src, ".java"));
        }
        if (fuentes > CompiladorTemporal.ultimaModificacion(compiladas, ".class")) {
            avisos.add("Hay fuentes más recientes que " + compiladas + ": recompila el proyecto tú mismo si los resultados no cuadran.");
        }
    }

    private static Preparacion preparar(ProyectoEscaneado proyecto, EscritorSeguro esc, ModuloEscaneado me, LocalizadorJdk.Seleccion jdk,
                                        String huella, boolean proyectoCompilado, Path dirPrep) {
        esc.borrarTemporal(dirModulo(esc, me));
        esc.carpetaTemporal(dirPrep);
        ContextoVerificacion ctx = new ContextoVerificacion();
        ctx.proyecto = proyecto;
        ctx.modulo = me;
        ctx.escritor = esc;
        ctx.proceso = new ProcesoSeguro(esc);
        ctx.tmp = dirPrep;
        ctx.jdk = jdk;
        List<String> avisos = new ArrayList<>();
        Path mvn = LocalizadorMaven.localizar(me.config, me.modulo.dir, proyecto.raiz);
        List<Path> deps;
        String origenCp;
        if (!me.config.classpath.isEmpty()) {
            deps = new ArrayList<>();
            for (String c : me.config.classpath) {
                deps.add(Path.of(c));
            }
            origenCp = "junit-gen.yml";
        } else if (me.modulo.build == Modulo.TipoBuild.MAVEN) {
            if (mvn == null) {
                throw new IllegalStateException("No se encontró Maven. Instálalo, define MAVEN_HOME o indica 'classpath' en junit-gen.yml.");
            }
            deps = ResolutorClasspathMaven.resolver(ctx, mvn);
            origenCp = "Maven (pom espejo) con " + mvn;
        } else {
            deps = ResolutorClasspathGradle.resolver(ctx);
            origenCp = "Gradle";
        }
        List<Path> clasesProyecto = new ArrayList<>();
        if (proyectoCompilado) {
            clasesProyecto.add(compiladas(me));
            if (me.modulo.build == Modulo.TipoBuild.GRADLE) {
                Path res = me.modulo.dir.resolve("build").resolve("resources").resolve("main");
                if (Files.isDirectory(res)) {
                    clasesProyecto.add(res);
                }
            }
        } else {
            clasesProyecto.add(CompiladorTemporal.compilarMain(ctx, new ArrayList<>(deps)));
            clasesProyecto.addAll(me.modulo.recursosMain);
            avisos.add("El módulo " + me.modulo.nombre() + " no estaba compilado: se compiló en " + ctx.tmp.resolve("main-classes"));
        }
        avisos.addAll(ctx.avisos);
        Guardada g = new Guardada();
        g.huella = huella;
        g.origenCp = origenCp;
        g.mvn = mvn == null ? null : mvn.toString();
        deps.forEach(d -> g.deps.add(d.toString()));
        clasesProyecto.forEach(c -> g.clasesProyecto.add(c.toString()));
        g.avisos.addAll(avisos);
        esc.escribir(dirPrep.resolve(ARCHIVO_PREP), Json.bonito(g) + "\n");
        return new Preparacion(jdk, mvn, List.copyOf(deps), List.copyOf(clasesProyecto), origenCp, List.copyOf(avisos), huella, false);
    }

    /** Preparación de prep.json si existe, su huella coincide y todas sus rutas existen; null si no (corrupto → se ignora). */
    private static Preparacion leerDisco(Path archivo, String huella, LocalizadorJdk.Seleccion jdk) {
        if (!Files.isRegularFile(archivo, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        Guardada g;
        try {
            g = Json.MAPPER.readValue(Files.readString(archivo, StandardCharsets.UTF_8), Guardada.class);
        } catch (IOException | RuntimeException e) {
            System.err.println("junit-gen: se ignora " + archivo + " (no válido): " + e.getMessage());
            return null;
        }
        if (g == null || !huella.equals(g.huella) || g.origenCp == null) {
            return null;
        }
        try {
            Path mvn = g.mvn == null ? null : Path.of(g.mvn);
            List<Path> deps = new ArrayList<>();
            for (String d : nonNull(g.deps)) {
                deps.add(Path.of(d));
            }
            List<Path> clases = new ArrayList<>();
            for (String c : nonNull(g.clasesProyecto)) {
                clases.add(Path.of(c));
            }
            if (clases.isEmpty() || !rutasExisten(mvn, deps, clases)) {
                return null;
            }
            return new Preparacion(jdk, mvn, List.copyOf(deps), List.copyOf(clases), g.origenCp, List.copyOf(nonNull(g.avisos)),
                    huella, true);
        } catch (RuntimeException e) {
            System.err.println("junit-gen: se ignora " + archivo + " (rutas no válidas): " + e.getMessage());
            return null;
        }
    }

    private static List<String> nonNull(List<String> l) {
        if (l == null) {
            return List.of();
        }
        List<String> r = new ArrayList<>();
        for (String s : l) {
            if (s != null) {
                r.add(s);
            }
        }
        return r;
    }

    private static boolean rutasExisten(Path mvn, List<Path> deps, List<Path> clases) {
        if (mvn != null && !Files.exists(mvn)) {
            return false;
        }
        for (Path d : deps) {
            if (!Files.exists(d)) {
                return false;
            }
        }
        for (Path c : clases) {
            if (!Files.exists(c)) {
                return false;
            }
        }
        return true;
    }

    /** Huella SHA-256 de las líneas ordenadas que invalidan la preparación. */
    static String huella(ProyectoEscaneado p, ModuloEscaneado me, LocalizadorJdk.Seleccion jdk, boolean proyectoCompilado) {
        List<String> lineas = new ArrayList<>();
        if (me.modulo.build == Modulo.TipoBuild.MAVEN) {
            if (p.localizacion != null) {
                for (Path pom : p.localizacion.poms) {
                    lineas.add(lineaArchivo("pom", pom, pom));
                }
            }
        } else {
            for (Path base : new LinkedHashSet<>(List.of(me.modulo.dir, p.raiz))) {
                for (String n : List.of("build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts", "gradle.properties")) {
                    lineas.add(lineaArchivo("gradle", base.resolve(n), base.resolve(n)));
                }
                Path toml = base.resolve("gradle").resolve("libs.versions.toml");
                lineas.add(lineaArchivo("gradle", toml, toml));
            }
        }
        lineas.add(lineaArchivo("yml", p.raiz.resolve(CargadorConfig.ARCHIVO), p.raiz.resolve(CargadorConfig.ARCHIVO)));
        lineas.add(lineaArchivo("yml", me.modulo.dir.resolve(CargadorConfig.ARCHIVO), me.modulo.dir.resolve(CargadorConfig.ARCHIVO)));
        lineas.add("jdk|" + jdk.jdk().home() + "|" + jdk.release());
        if (proyectoCompilado) {
            lineas.add("modo|P");
        } else {
            lineas.add("modo|T");
            for (Path src : me.modulo.fuentesMain) {
                for (Path f : CompiladorTemporal.fuentesJava(src)) {
                    Path rel;
                    try {
                        rel = me.modulo.dir.relativize(f);
                    } catch (IllegalArgumentException e) {
                        rel = f;
                    }
                    lineas.add(lineaArchivo("java", rel, f));
                }
            }
        }
        Collections.sort(lineas);
        return Textos.sha256Hex(String.join("\n", lineas), LONGITUD_HUELLA);
    }

    private static String lineaArchivo(String tipo, Path nombre, Path archivo) {
        String base = tipo + "|" + nombre.toString().replace('\\', '/');
        try {
            if (!Files.isRegularFile(archivo)) {
                return base + "|-";
            }
            return base + "|" + Files.size(archivo) + "|" + Files.getLastModifiedTime(archivo).toMillis();
        } catch (IOException e) {
            return base + "|?";
        }
    }
}
