package com.junitgen.seguridad;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Instantánea (ruta relativa, tamaño, fecha de modificación y opcionalmente SHA-256) de todos los
 * archivos y carpetas de un árbol, para demostrar que el proyecto no ha cambiado.
 */
public final class GuardaIntegridad {

    public record Entrada(boolean directorio, long tamano, long mtime, String hash) {
    }

    private GuardaIntegridad() {
    }

    public static Map<String, Entrada> instantanea(Path raiz, boolean conHash) {
        Map<String, Entrada> mapa = new TreeMap<>();
        if (raiz == null || !Files.exists(raiz)) {
            return mapa;
        }
        try {
            Files.walkFileTree(raiz, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    BasicFileAttributes reales = reales(dir, attrs);
                    mapa.put(rel(raiz, dir), new Entrada(true, 0, reales.lastModifiedTime().toMillis(), null));
                    if (!dir.equals(raiz) && RutaSegura.esEnlace(dir)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    BasicFileAttributes reales = reales(file, attrs);
                    String hash = conHash && reales.isRegularFile() ? sha256(file) : null;
                    mapa.put(rel(raiz, file), new Entrada(false, reales.size(), reales.lastModifiedTime().toMillis(), hash));
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    mapa.put(rel(raiz, file), new Entrada(false, -1, -1, "ilegible:" + exc.getClass().getSimpleName()));
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new SeguridadException("No se pudo tomar la instantánea de " + raiz + ": " + e.getMessage(), e);
        }
        return mapa;
    }

    /**
     * Atributos leídos directamente del archivo: en Windows los que da el listado del directorio padre
     * son una copia en caché que NTFS actualiza de forma diferida (sobre todo la fecha de las carpetas).
     */
    private static BasicFileAttributes reales(Path p, BasicFileAttributes listado) {
        try {
            return Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException e) {
            return listado;
        }
    }

    private static String rel(Path raiz, Path p) {
        String r = raiz.relativize(p).toString().replace('\\', '/');
        return r.isEmpty() ? "." : r;
    }

    public static String sha256(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (IOException e) {
            return "ilegible:" + e.getMessage();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 no disponible", e);
        }
    }

    /** Lista de diferencias legibles; vacía si las instantáneas son idénticas. */
    public static List<String> comparar(Map<String, Entrada> antes, Map<String, Entrada> despues) {
        List<String> dif = new ArrayList<>();
        TreeSet<String> claves = new TreeSet<>(antes.keySet());
        claves.addAll(despues.keySet());
        for (String k : claves) {
            Entrada a = antes.get(k);
            Entrada d = despues.get(k);
            if (a == null) {
                dif.add("creado: " + k);
            } else if (d == null) {
                dif.add("eliminado: " + k);
            } else if (!a.equals(d)) {
                StringBuilder sb = new StringBuilder("modificado: ").append(k).append(" (");
                if (a.tamano() != d.tamano()) {
                    sb.append("tamaño ").append(a.tamano()).append("→").append(d.tamano()).append(' ');
                }
                if (a.mtime() != d.mtime()) {
                    sb.append("fecha ").append(a.mtime()).append("→").append(d.mtime()).append(' ');
                }
                if (a.hash() != null && !a.hash().equals(d.hash())) {
                    sb.append("contenido ");
                }
                dif.add(sb.toString().trim() + ")");
            }
        }
        return dif;
    }
}
