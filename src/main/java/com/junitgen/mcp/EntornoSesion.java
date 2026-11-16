package com.junitgen.mcp;

import com.junitgen.Main;
import com.junitgen.seguridad.DetectorEscritorio;
import com.junitgen.seguridad.PreferenciasSeguras;

import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.Locale;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Entorno del proceso que usa la sesión MCP: directorio de trabajo, carpeta del usuario, carpeta de
 * instalación (la del JAR), detección del Escritorio y preferencias persistentes. Inyectable en tests.
 *
 * @param preferencias null si no se ejecuta desde un JAR (persistencia desactivada)
 */
public record EntornoSesion(Path directorioTrabajo, Path home, Path instalacion, Supplier<Path> escritorio,
                            PreferenciasSeguras preferencias, Function<String, String> env) {

    public static final String ARCHIVO_RUTAS = "rutas-por-defecto.json";

    public EntornoSesion {
        if (escritorio == null) {
            escritorio = () -> null;
        }
        if (env == null) {
            env = k -> null;
        }
    }

    /** Propiedad del sistema con la carpeta de datos (preferencias); alternativa: {@link #ENV_DATOS}. */
    public static final String PROP_DATOS = "junitgen.datos";
    /** Variable de entorno con la carpeta de datos, usada si no se indica {@link #PROP_DATOS}. */
    public static final String ENV_DATOS = "JUNITGEN_DATOS";

    /**
     * Entorno real: user.dir, user.home, carpeta del JAR y Escritorio detectado en el sistema. Las
     * preferencias ({@value #ARCHIVO_RUTAS}) se guardan en la carpeta de datos indicada con
     * {@code -Djunitgen.datos} o {@code JUNITGEN_DATOS} (ruta absoluta de una carpeta existente, por ejemplo
     * la carpeta de datos del plugin de Claude Code); si no se indica o no es válida, junto al JAR como
     * siempre. El campo {@code instalacion} sigue siendo la carpeta del JAR.
     */
    public static EntornoSesion delSistema() {
        Path instalacion = carpetaInstalacion();
        Path datos = carpetaDatos();
        Path carpetaPreferencias = datos != null ? datos : instalacion;
        return new EntornoSesion(ruta(System.getProperty("user.dir")), ruta(System.getProperty("user.home")), instalacion,
                DetectorEscritorio::detectar,
                carpetaPreferencias == null ? null : new PreferenciasSeguras(carpetaPreferencias, ARCHIVO_RUTAS),
                System::getenv);
    }

    /**
     * Carpeta de datos indicada por {@link #PROP_DATOS} o, en su defecto, {@link #ENV_DATOS}; null si no se
     * indica, no es absoluta (un valor sin sustituir como {@code ${CLAUDE_PLUGIN_DATA}} es relativo y se
     * ignora) o no es una carpeta existente.
     */
    private static Path carpetaDatos() {
        String valor = System.getProperty(PROP_DATOS);
        if (valor == null || valor.isBlank()) {
            valor = System.getenv(ENV_DATOS);
        }
        if (valor == null || valor.isBlank()) {
            return null;
        }
        try {
            Path p = Path.of(valor.trim());
            if (!p.isAbsolute() || !Files.isDirectory(p)) {
                return null;
            }
            return p.toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            System.err.println("Carpeta de datos no válida (" + valor + "): " + e.getMessage());
            return null;
        }
    }

    /** Entorno de prueba con todo inyectado (instalación null = sin persistencia). */
    public static EntornoSesion para(Path directorioTrabajo, Path home, Path instalacion, Path escritorio,
                                     Function<String, String> env) {
        return new EntornoSesion(directorioTrabajo, home, instalacion, () -> escritorio,
                instalacion == null ? null : new PreferenciasSeguras(instalacion, ARCHIVO_RUTAS), env);
    }

    private static Path ruta(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return Path.of(s);
        } catch (InvalidPathException e) {
            System.err.println("Ruta del sistema no válida (" + s + "): " + e.getMessage());
            return null;
        }
    }

    /** Carpeta que contiene el JAR en ejecución, o null si no se ejecuta desde un .jar. */
    static Path carpetaInstalacion() {
        try {
            CodeSource cs = Main.class.getProtectionDomain().getCodeSource();
            if (cs == null || cs.getLocation() == null) {
                return null;
            }
            Path jar = Path.of(cs.getLocation().toURI()).toAbsolutePath().normalize();
            Path nombre = jar.getFileName();
            if (nombre == null || !nombre.toString().toLowerCase(Locale.ROOT).endsWith(".jar") || !Files.isRegularFile(jar)) {
                return null;
            }
            return jar.getParent();
        } catch (URISyntaxException | RuntimeException e) {
            System.err.println("No se pudo determinar la carpeta de instalación: " + e.getMessage());
            return null;
        }
    }
}
