package com.junitgen.generacion;

import com.junitgen.util.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** tanda.json de la carpeta de salida: lectura y serialización (la escritura va por EscritorSeguro). Solo lee. */
public final class AlmacenTanda {

    private AlmacenTanda() {
    }

    public static Path ruta(Path salida) {
        return salida.resolve(RutasSalida.TANDA);
    }

    /** Lee la tanda guardada; null si no existe. Si está corrupta lanza IllegalStateException con un mensaje claro. */
    public static Tanda leer(Path salida) {
        Path p = ruta(salida);
        if (!Files.isRegularFile(p)) {
            return null;
        }
        Tanda t;
        try {
            t = Json.MAPPER.readValue(Files.readString(p, StandardCharsets.UTF_8), Tanda.class);
        } catch (IOException | RuntimeException e) {
            throw corrupta(salida, e.getMessage(), e);
        }
        if (t == null || t.servicios == null) {
            throw corrupta(salida, "vacío o sin servicios", null);
        }
        return t;
    }

    private static IllegalStateException corrupta(Path salida, String motivo, Exception causa) {
        return new IllegalStateException("El " + RutasSalida.TANDA + " de " + salida + " está corrupto y no se puede retomar ("
                + motivo + "). Usa /generartest N para empezar una tanda nueva.", causa);
    }

    public static String serializar(Tanda t) {
        return Json.bonito(t);
    }
}
