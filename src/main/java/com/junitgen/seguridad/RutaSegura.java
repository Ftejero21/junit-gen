package com.junitgen.seguridad;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;

/**
 * Normalización y validación de rutas. Resuelve "..", enlaces simbólicos, junctions y nombres 8.3
 * (mediante {@code toRealPath()} del ancestro existente más profundo).
 */
public final class RutaSegura {

    public static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    private RutaSegura() {
    }

    public static Path ruta(String texto) {
        if (texto == null || texto.isBlank()) {
            throw new SeguridadException("Ruta vacía.");
        }
        String limpio = texto.trim();
        if (limpio.length() > 1 && limpio.startsWith("\"") && limpio.endsWith("\"")) {
            limpio = limpio.substring(1, limpio.length() - 1);
        }
        try {
            return Paths.get(limpio);
        } catch (InvalidPathException e) {
            throw new SeguridadException("Ruta no válida: " + texto + " (" + e.getReason() + ")", e);
        }
    }

    /**
     * Ruta absoluta, normalizada y con el ancestro existente más profundo resuelto con
     * {@code toRealPath()}; los segmentos que aún no existen se añaden tal cual.
     */
    public static Path normalizar(Path ruta) {
        if (ruta == null) {
            throw new SeguridadException("Ruta nula.");
        }
        Path abs = ruta.toAbsolutePath().normalize();
        Deque<String> pendientes = new ArrayDeque<>();
        Path existente = abs;
        while (existente != null && !Files.exists(existente)) {
            if (Files.exists(existente, LinkOption.NOFOLLOW_LINKS)) {
                throw new SeguridadException("La ruta " + existente
                        + " es un enlace roto; indica una carpeta real.");
            }
            Path nombre = existente.getFileName();
            if (nombre != null) {
                pendientes.push(nombre.toString());
            }
            existente = existente.getParent();
        }
        if (existente == null) {
            throw new SeguridadException("No existe ningún ancestro de la ruta " + abs + ".");
        }
        Path real;
        try {
            real = existente.toRealPath();
        } catch (IOException e) {
            throw new SeguridadException("No se puede resolver la ruta real de " + existente + ": " + e.getMessage(), e);
        }
        while (!pendientes.isEmpty()) {
            real = real.resolve(pendientes.pop());
        }
        return real.normalize();
    }

    /** true si {@code hijo} es igual a {@code padre} o está dentro (comparación sin mayúsculas en Windows). */
    public static boolean dentroOIgual(Path hijo, Path padre) {
        String h = clave(hijo);
        String p = clave(padre);
        if (h.equals(p)) {
            return true;
        }
        String sep = hijo.getFileSystem().getSeparator();
        String prefijo = p.endsWith(sep) ? p : p + sep;
        return h.startsWith(prefijo);
    }

    public static boolean iguales(Path a, Path b) {
        return clave(a).equals(clave(b));
    }

    private static String clave(Path p) {
        String s = p.toAbsolutePath().normalize().toString();
        if (WINDOWS) {
            s = s.toLowerCase(Locale.ROOT);
        }
        String sep = p.getFileSystem().getSeparator();
        while (s.endsWith(sep) && s.length() > 3) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    /** true si la ruta es un enlace simbólico o una junction/punto de montaje (no se siguen). */
    public static boolean esEnlace(Path p) {
        try {
            BasicFileAttributes a = Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return a.isSymbolicLink() || (a.isDirectory() && a.isOther());
        } catch (IOException e) {
            return false;
        }
    }

    /** Valida la ruta del proyecto: debe existir y ser una carpeta. Devuelve su ruta real. */
    public static Path validarProyecto(Path proyecto) {
        Path real = normalizar(proyecto);
        if (!Files.isDirectory(real)) {
            throw new SeguridadException("La ruta del proyecto no existe o no es una carpeta: " + proyecto);
        }
        return real;
    }

    /**
     * Valida la carpeta de salida respecto al proyecto. Rechaza: misma carpeta, salida dentro del
     * proyecto, proyecto dentro de la salida, segmentos que son archivos o accesos directos (.lnk).
     */
    public static Path validarSalida(Path proyectoReal, Path salida) {
        if (salida == null) {
            throw new SeguridadException("Falta la carpeta de salida.");
        }
        Path abs = salida.toAbsolutePath().normalize();
        comprobarSegmentos(abs);
        Path real = normalizar(abs);
        comprobarSegmentos(real);
        if (proyectoReal != null) {
            if (iguales(real, proyectoReal)) {
                throw new SeguridadException("La carpeta de salida (" + real
                        + ") es la misma que la del proyecto. Elige una carpeta fuera del proyecto.");
            }
            if (dentroOIgual(real, proyectoReal)) {
                throw new SeguridadException("La carpeta de salida (" + real + ") está dentro del proyecto ("
                        + proyectoReal + "). El proyecto es de solo lectura: elige una carpeta fuera de él.");
            }
            if (dentroOIgual(proyectoReal, real)) {
                throw new SeguridadException("La carpeta de salida (" + real + ") contiene al proyecto ("
                        + proyectoReal + "). Elige una carpeta separada que no lo contenga.");
            }
        }
        return real;
    }

    private static void comprobarSegmentos(Path abs) {
        Path actual = abs.getRoot();
        if (actual == null) {
            return;
        }
        for (Path seg : abs) {
            actual = actual.resolve(seg);
            String nombre = seg.toString();
            if (Files.exists(actual, LinkOption.NOFOLLOW_LINKS)) {
                if (!Files.isDirectory(actual)) {
                    if (nombre.toLowerCase(Locale.ROOT).endsWith(".lnk")) {
                        throw new SeguridadException("La ruta " + actual
                                + " es un acceso directo de Windows (.lnk): indica la carpeta real.");
                    }
                    throw new SeguridadException("La ruta " + actual + " es un archivo, no una carpeta.");
                }
            } else {
                Path parent = actual.getParent();
                if (parent != null && Files.isRegularFile(parent.resolve(nombre + ".lnk"))) {
                    throw new SeguridadException("La ruta " + actual
                            + " parece un acceso directo de Windows (" + nombre + ".lnk): indica la carpeta real.");
                }
                return;
            }
        }
    }
}
