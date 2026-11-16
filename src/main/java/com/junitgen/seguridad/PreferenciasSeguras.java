package com.junitgen.seguridad;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.regex.Pattern;

/**
 * Archivo de preferencias de junit-gen (nombre fijo) en una carpeta fuera del proyecto analizado,
 * normalmente la de instalación. Es la única escritura permitida fuera de la carpeta de salida: se
 * comprueba que la carpeta real no está dentro del proyecto y que el destino no es un enlace ni una
 * carpeta. La lectura está limitada a 256 KB.
 */
public final class PreferenciasSeguras {

    public static final int MAX_BYTES = 256 * 1024;
    private static final Pattern NOMBRE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,99}");

    private final Path carpetaBase;
    private final String nombreArchivo;

    public PreferenciasSeguras(Path carpetaBase, String nombreArchivo) {
        if (carpetaBase == null) {
            throw new SeguridadException("Falta la carpeta de las preferencias.");
        }
        if (nombreArchivo == null || !NOMBRE.matcher(nombreArchivo).matches() || nombreArchivo.contains("..")) {
            throw new SeguridadException("Nombre de archivo de preferencias no válido: " + nombreArchivo);
        }
        this.carpetaBase = carpetaBase.toAbsolutePath().normalize();
        this.nombreArchivo = nombreArchivo;
    }

    public Path archivo() {
        return carpetaBase.resolve(nombreArchivo);
    }

    /** Contenido del archivo, o null si no existe, no es un archivo normal, supera el límite o no se puede leer. */
    public String leer() {
        Path f = archivo();
        if (!Files.exists(f, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        if (RutaSegura.esEnlace(f) || !Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS)) {
            System.err.println("Preferencias ignoradas: " + f + " no es un archivo normal.");
            return null;
        }
        try (InputStream in = Files.newInputStream(f, LinkOption.NOFOLLOW_LINKS)) {
            byte[] b = in.readNBytes(MAX_BYTES + 1);
            if (b.length > MAX_BYTES) {
                System.err.println("Preferencias ignoradas: " + f + " supera " + (MAX_BYTES / 1024) + " KB.");
                return null;
            }
            return new String(b, StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("Preferencias ignoradas: no se pudo leer " + f + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Escribe el archivo. Lanza SeguridadException si la carpeta real está dentro del proyecto
     * analizado o el destino es un enlace o una carpeta, e IllegalStateException si falla la E/S.
     */
    public void escribir(String contenido, Path proyectoReal) {
        byte[] datos = (contenido == null ? "" : contenido).getBytes(StandardCharsets.UTF_8);
        if (datos.length > MAX_BYTES) {
            throw new SeguridadException("Preferencias demasiado grandes (" + datos.length + " bytes).");
        }
        Path base;
        try {
            base = carpetaBase.toRealPath();
        } catch (IOException e) {
            throw new SeguridadException("No existe la carpeta de preferencias " + carpetaBase + ": " + e.getMessage(), e);
        }
        if (!Files.isDirectory(base)) {
            throw new SeguridadException("La carpeta de preferencias " + base + " no es una carpeta.");
        }
        if (proyectoReal != null) {
            Path proyecto = RutaSegura.normalizar(proyectoReal);
            if (RutaSegura.dentroOIgual(base, proyecto)) {
                throw new SeguridadException("Escritura rechazada: la carpeta de preferencias " + base
                        + " está dentro del proyecto " + proyecto + " (solo lectura).");
            }
        }
        Path destino = base.resolve(nombreArchivo);
        if (Files.exists(destino, LinkOption.NOFOLLOW_LINKS)) {
            if (RutaSegura.esEnlace(destino) || Files.isSymbolicLink(destino)) {
                throw new SeguridadException("Escritura abortada: " + destino + " es un enlace; no se sigue.");
            }
            if (Files.isDirectory(destino, LinkOption.NOFOLLOW_LINKS)) {
                throw new SeguridadException("Escritura abortada: " + destino + " es una carpeta.");
            }
        }
        try {
            Files.write(destino, datos, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException e) {
            throw new IllegalStateException("No se pudo escribir " + destino + ": " + e.getMessage(), e);
        }
    }
}
