package com.junitgen.escaneo;

import com.junitgen.config.CargadorConfig;
import com.junitgen.proyecto.LocalizadorModulos;
import com.junitgen.proyecto.Modulo;
import com.junitgen.util.Textos;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.TreeSet;

/**
 * Huella de lo que lee el escaneo: archivos de build y de configuración de la raíz y de cada módulo y todos los
 * {@code .java} de main y de test. Si no cambia, el escaneo en memoria sigue siendo válido. Solo lee.
 */
public final class HuellaEscaneo {

    /** Caracteres hexadecimales del resumen de cada archivo. */
    static final int LONGITUD_ARCHIVO = 16;
    /** Caracteres hexadecimales de la huella total. */
    static final int LONGITUD_TOTAL = 32;
    /** Aporte de un archivo que no se puede leer. */
    private static final String ILEGIBLE = "?";
    private static final char SEPARADOR = '\0';
    private static final List<String> AUXILIARES = List.of("settings.gradle", "settings.gradle.kts", "gradle.properties",
            CargadorConfig.ARCHIVO);
    private static final List<String> BUILD_RAIZ = List.of("pom.xml", "build.gradle", "build.gradle.kts");

    private HuellaEscaneo() {
    }

    /** Huella (32 hex) del proyecto de {@code raiz}; null ante cualquier duda (entonces se reescanea siempre). */
    public static String calcular(Path raiz) {
        try {
            LocalizadorModulos.Resultado loc = LocalizadorModulos.localizar(raiz);
            TreeSet<Path> archivos = new TreeSet<>();
            anadirSiExisten(archivos, raiz, AUXILIARES);
            anadirSiExisten(archivos, raiz, BUILD_RAIZ);
            archivos.addAll(loc.poms);
            for (Modulo m : loc.modulos) {
                if (m.archivoBuild != null) {
                    archivos.add(m.archivoBuild);
                }
                anadirSiExisten(archivos, m.dir, AUXILIARES);
                for (Path d : m.fuentesMain) {
                    archivos.addAll(IndiceTipos.listarJava(d));
                }
                for (Path d : m.fuentesTest) {
                    archivos.addAll(IndiceTipos.listarJava(d));
                }
            }
            MessageDigest total = MessageDigest.getInstance("SHA-256");
            for (Path f : archivos) {
                String rel = raiz.relativize(f).toString().replace('\\', '/');
                total.update((rel + SEPARADOR + resumen(f) + "\n").getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(total.digest()).substring(0, LONGITUD_TOTAL);
        } catch (RuntimeException | NoSuchAlgorithmException e) {
            return null;
        }
    }

    private static void anadirSiExisten(TreeSet<Path> destino, Path dir, List<String> nombres) {
        for (String n : nombres) {
            Path f = dir.resolve(n);
            if (Files.isRegularFile(f)) {
                destino.add(f);
            }
        }
    }

    private static String resumen(Path f) {
        try {
            return Textos.sha256Hex(Files.readAllBytes(f), LONGITUD_ARCHIVO);
        } catch (IOException | RuntimeException e) {
            return ILEGIBLE;
        }
    }
}
