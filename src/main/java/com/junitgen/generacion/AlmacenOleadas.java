package com.junitgen.generacion;

import com.junitgen.util.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** oleadas.json de la carpeta de salida: lectura y serialización (la escritura va por EscritorSeguro). Solo lee. */
public final class AlmacenOleadas {

    private AlmacenOleadas() {
    }

    public static Path ruta(Path salida) {
        return salida.resolve(RutasSalida.OLEADAS);
    }

    /** Lee la campaña guardada; null si no existe. Si está corrupta lanza IllegalStateException con un mensaje claro. */
    public static Oleadas leer(Path salida) {
        Path p = ruta(salida);
        if (!Files.isRegularFile(p)) {
            return null;
        }
        Oleadas o;
        try {
            o = Json.MAPPER.readValue(Files.readString(p, StandardCharsets.UTF_8), Oleadas.class);
        } catch (IOException | RuntimeException e) {
            throw corrupta(salida, e.getMessage(), e);
        }
        if (o == null || o.servicios == null || o.oleadas == null) {
            throw corrupta(salida, "vacío o sin servicios", null);
        }
        return o;
    }

    private static IllegalStateException corrupta(Path salida, String motivo, Exception causa) {
        return new IllegalStateException("El " + RutasSalida.OLEADAS + " de " + salida + " está corrupto y no se puede retomar ("
                + motivo + "). Usa /generartest todos para empezar una campaña nueva.", causa);
    }

    public static String serializar(Oleadas o) {
        return Json.bonito(o);
    }
}
