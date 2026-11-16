package com.junitgen.seguridad;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Punto único de escritura de junit-gen. Está ligado a la ruta real del proyecto (solo lectura) y a
 * la ruta real de la carpeta de salida (único lugar de escritura). Ninguna otra clase escribe, crea
 * carpetas ni borra archivos.
 */
public final class EscritorSeguro {

    public static final String CARPETA_TMP = ".junit-gen-tmp";

    private final Path proyectoReal;
    private final Path salidaReal;
    private final List<Path> escritos = new ArrayList<>();

    public EscritorSeguro(Path proyecto, Path salida) {
        this.proyectoReal = RutaSegura.validarProyecto(proyecto);
        this.salidaReal = RutaSegura.validarSalida(this.proyectoReal, salida);
    }

    public Path proyecto() {
        return proyectoReal;
    }

    public Path salida() {
        return salidaReal;
    }

    public Path temporal() {
        return salidaReal.resolve(CARPETA_TMP);
    }

    /** Archivos escritos por este escritor (rutas absolutas), en orden. */
    public synchronized List<Path> escritos() {
        return new ArrayList<>(escritos);
    }

    public static String aviso(Path proyecto, Path salida) {
        if (salida == null) {
            return "Modo solo lectura sobre " + proyecto + ". Esta operación no crea archivos.";
        }
        return "Modo solo lectura sobre " + proyecto + ". Los archivos se crearán únicamente en " + salida;
    }

    public String aviso() {
        return aviso(proyectoReal, salidaReal);
    }

    /** Valida que el destino (normalizado) esté dentro de la salida y fuera del proyecto. */
    public Path comprobarDestino(Path destino) {
        Path base = destino.isAbsolute() ? destino : salidaReal.resolve(destino);
        Path norm = RutaSegura.normalizar(base);
        if (!RutaSegura.dentroOIgual(norm, salidaReal)) {
            throw new SeguridadException("Escritura rechazada: " + norm + " está fuera de la carpeta de salida "
                    + salidaReal + ". No se ha escrito nada.");
        }
        if (RutaSegura.dentroOIgual(norm, proyectoReal)) {
            throw new SeguridadException("Escritura rechazada: " + norm + " está dentro del proyecto "
                    + proyectoReal + " (solo lectura). No se ha escrito nada.");
        }
        return norm;
    }

    private void recomprobarPadre(Path destino) {
        Path padre = destino.getParent();
        if (padre == null) {
            throw new SeguridadException("Destino sin carpeta padre: " + destino);
        }
        Path realPadre;
        try {
            realPadre = padre.toRealPath();
        } catch (IOException e) {
            throw new SeguridadException("No se puede resolver la carpeta " + padre + ": " + e.getMessage(), e);
        }
        if (!RutaSegura.dentroOIgual(realPadre, salidaReal) || RutaSegura.dentroOIgual(realPadre, proyectoReal)) {
            throw new SeguridadException("Escritura abortada: la carpeta real " + realPadre
                    + " no está dentro de la salida " + salidaReal + " o está dentro del proyecto.");
        }
        if (Files.exists(destino, LinkOption.NOFOLLOW_LINKS) && RutaSegura.esEnlace(destino)) {
            throw new SeguridadException("Escritura abortada: " + destino + " es un enlace; no se sigue.");
        }
        if (Files.isDirectory(destino, LinkOption.NOFOLLOW_LINKS)) {
            throw new SeguridadException("Escritura abortada: " + destino + " es una carpeta.");
        }
    }

    /** Crea la carpeta de salida (si no existe) y las carpetas indicadas, segmento a segmento. */
    public synchronized Path crearDirectorios(Path dir) {
        Path norm = comprobarDestino(dir);
        crearSalidaSiFalta();
        Path actual = salidaReal;
        Path relativa = salidaReal.relativize(norm);
        for (Path seg : relativa) {
            if (seg.toString().isEmpty()) {
                continue;
            }
            Path siguiente = actual.resolve(seg);
            if (Files.exists(siguiente, LinkOption.NOFOLLOW_LINKS)) {
                if (RutaSegura.esEnlace(siguiente)) {
                    Path real = RutaSegura.normalizar(siguiente);
                    if (!RutaSegura.dentroOIgual(real, salidaReal) || RutaSegura.dentroOIgual(real, proyectoReal)) {
                        throw new SeguridadException("La carpeta " + siguiente + " es un enlace que apunta fuera de la salida ("
                                + real + "). No se escribe nada.");
                    }
                }
                if (!Files.isDirectory(siguiente)) {
                    throw new SeguridadException("La ruta " + siguiente + " existe y no es una carpeta.");
                }
            } else {
                recomprobarPadreDirectorio(actual);
                try {
                    Files.createDirectory(siguiente);
                } catch (IOException e) {
                    throw new SeguridadException("No se puede crear la carpeta " + siguiente + ": " + e.getMessage(), e);
                }
            }
            actual = siguiente;
        }
        return norm;
    }

    private void recomprobarPadreDirectorio(Path padre) {
        Path real;
        try {
            real = padre.toRealPath();
        } catch (IOException e) {
            throw new SeguridadException("No se puede resolver la carpeta " + padre + ": " + e.getMessage(), e);
        }
        if (!RutaSegura.dentroOIgual(real, salidaReal) || RutaSegura.dentroOIgual(real, proyectoReal)) {
            throw new SeguridadException("Creación de carpeta abortada: " + real + " está fuera de la salida.");
        }
    }

    private void crearSalidaSiFalta() {
        if (Files.isDirectory(salidaReal)) {
            return;
        }
        // Se revalida antes de crear: los ancestros existentes ya se resolvieron con toRealPath().
        Path revalidada = RutaSegura.validarSalida(proyectoReal, salidaReal);
        if (!RutaSegura.iguales(revalidada, salidaReal)) {
            throw new SeguridadException("La carpeta de salida ha cambiado durante la operación: " + revalidada);
        }
        try {
            Files.createDirectories(salidaReal);
        } catch (IOException e) {
            throw new SeguridadException("No se puede crear la carpeta de salida " + salidaReal + ": " + e.getMessage(), e);
        }
    }

    public void escribir(Path destino, String texto) {
        escribir(destino, texto.getBytes(StandardCharsets.UTF_8));
    }

    public synchronized void escribir(Path destino, byte[] datos) {
        Path norm = comprobarDestino(destino);
        crearDirectorios(norm.getParent());
        recomprobarPadre(norm);
        try {
            Files.write(norm, datos, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
        } catch (IOException e) {
            throw new SeguridadException("No se puede escribir " + norm + ": " + e.getMessage(), e);
        }
        if (!escritos.contains(norm)) {
            escritos.add(norm);
        }
    }

    /**
     * Escritura en dos fases: primero se validan todas las rutas; si alguna falla no se escribe nada.
     * Después se escribe cada archivo recomprobando la carpeta padre real.
     */
    public synchronized void escribirTodo(Map<Path, String> plan) {
        Map<Path, byte[]> validado = new LinkedHashMap<>();
        for (Map.Entry<Path, String> e : plan.entrySet()) {
            Path norm = comprobarDestino(e.getKey());
            comprobarAncestros(norm);
            validado.put(norm, e.getValue().getBytes(StandardCharsets.UTF_8));
        }
        for (Map.Entry<Path, byte[]> e : validado.entrySet()) {
            escribir(e.getKey(), e.getValue());
        }
    }

    private void comprobarAncestros(Path norm) {
        Path actual = salidaReal;
        Path relativa = salidaReal.relativize(norm.getParent() == null ? norm : norm.getParent());
        for (Path seg : relativa) {
            if (seg.toString().isEmpty()) {
                continue;
            }
            actual = actual.resolve(seg);
            if (!Files.exists(actual, LinkOption.NOFOLLOW_LINKS)) {
                return;
            }
            if (!Files.isDirectory(actual)) {
                throw new SeguridadException("La ruta " + actual + " existe y no es una carpeta. No se ha escrito nada.");
            }
            Path real = RutaSegura.normalizar(actual);
            if (!RutaSegura.dentroOIgual(real, salidaReal) || RutaSegura.dentroOIgual(real, proyectoReal)) {
                throw new SeguridadException("La carpeta " + actual + " apunta fuera de la salida (" + real
                        + "). No se ha escrito nada.");
            }
        }
    }

    /** Prepara (valida y crea su carpeta) un archivo de log dentro del temporal; lo usa ProcesoSeguro. */
    synchronized Path prepararArchivoTemporal(Path destino) {
        Path norm = comprobarDestino(destino);
        if (!RutaSegura.dentroOIgual(norm, temporal())) {
            throw new SeguridadException("Los archivos de proceso deben estar en " + temporal() + ": " + norm);
        }
        crearDirectorios(norm.getParent());
        recomprobarPadre(norm);
        return norm;
    }

    /** Valida que una carpeta esté dentro del temporal y la crea. */
    public synchronized Path carpetaTemporal(Path dir) {
        Path norm = comprobarDestino(dir);
        if (!RutaSegura.dentroOIgual(norm, temporal())) {
            throw new SeguridadException("La carpeta " + norm + " no está dentro de " + temporal());
        }
        return crearDirectorios(norm);
    }

    /**
     * Único borrado permitido: contenido bajo {@code <salida>/.junit-gen-tmp}. Los enlaces (symlinks y
     * junctions) se borran como enlace, sin descender a su destino.
     */
    public synchronized void borrarTemporal(Path dir) {
        Path tmp = temporal();
        Path norm = RutaSegura.normalizar(dir.isAbsolute() ? dir : tmp.resolve(dir));
        if (!RutaSegura.dentroOIgual(norm, tmp)) {
            throw new SeguridadException("Borrado rechazado: " + norm + " no está dentro de " + tmp);
        }
        if (RutaSegura.dentroOIgual(norm, proyectoReal)) {
            throw new SeguridadException("Borrado rechazado: " + norm + " está dentro del proyecto.");
        }
        Path inicio = dir.isAbsolute() ? dir.toAbsolutePath().normalize() : tmp.resolve(dir).normalize();
        if (!Files.exists(inicio, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (RutaSegura.esEnlace(inicio)) {
            borrarUno(inicio);
            return;
        }
        final Path tmpReal;
        try {
            tmpReal = Files.exists(tmp) ? tmp.toRealPath() : tmp;
        } catch (IOException e) {
            throw new SeguridadException("No se puede resolver " + tmp + ": " + e.getMessage(), e);
        }
        try {
            Files.walkFileTree(inicio, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes attrs) throws IOException {
                    if (!d.equals(inicio) && RutaSegura.esEnlace(d)) {
                        borrarUno(d);
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    Path real = d.toRealPath();
                    if (!RutaSegura.dentroOIgual(real, tmpReal) || RutaSegura.dentroOIgual(real, proyectoReal)) {
                        throw new SeguridadException("Borrado abortado: " + d + " resuelve a " + real
                                + ", fuera del temporal.");
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path f, BasicFileAttributes attrs) throws IOException {
                    Path padreReal = f.getParent().toRealPath();
                    if (!RutaSegura.dentroOIgual(padreReal, tmpReal)) {
                        throw new SeguridadException("Borrado abortado: " + f + " fuera del temporal.");
                    }
                    borrarUno(f);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
                    if (exc != null) {
                        throw exc;
                    }
                    borrarUno(d);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new SeguridadException("No se pudo limpiar el temporal " + inicio + ": " + e.getMessage(), e);
        }
    }

    private void borrarUno(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException e) {
            throw new SeguridadException("No se pudo borrar " + p + ": " + e.getMessage(), e);
        }
    }
}
