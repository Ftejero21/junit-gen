package com.junitgen.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.junitgen.seguridad.PreferenciasSeguras;
import com.junitgen.seguridad.RutaSegura;
import com.junitgen.util.Json;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Ruta de salida por defecto por proyecto, persistente entre sesiones en
 * {@code <instalación>/rutas-por-defecto.json}: {@code {"version":1,"rutas":{<proyecto>:<salida>}}}.
 * El contenido es entrada no confiable: quien lo use debe revalidar la ruta con RutaSegura.validarSalida.
 */
public final class RutasPorDefecto {

    public static final int VERSION = 1;

    private final PreferenciasSeguras preferencias;

    public RutasPorDefecto(PreferenciasSeguras preferencias) {
        this.preferencias = preferencias;
    }

    /** true si hay dónde guardar (se ejecuta desde el JAR instalado). */
    public boolean disponible() {
        return preferencias != null;
    }

    /** Ruta guardada (sin validar) para el proyecto, o null. */
    public synchronized Path de(Path proyectoReal) {
        if (preferencias == null || proyectoReal == null) {
            return null;
        }
        JsonNode v = leer().path("rutas").get(clave(proyectoReal));
        if (v == null || !v.isTextual() || v.asText().isBlank()) {
            return null;
        }
        try {
            return RutaSegura.ruta(v.asText());
        } catch (RuntimeException e) {
            System.err.println("Ruta por defecto guardada no válida para " + proyectoReal + ": " + e.getMessage());
            return null;
        }
    }

    /** Guarda (relee antes de escribir para no perder otras entradas). Lanza si no se puede. */
    public synchronized void guardar(Path proyectoReal, Path salidaReal) {
        if (preferencias == null) {
            throw new IllegalStateException("junit-gen no se ejecuta desde su JAR instalado: no hay dónde guardar la ruta por defecto.");
        }
        ObjectNode raiz = leer();
        raiz.put("version", VERSION);
        JsonNode rutas = raiz.get("rutas");
        ObjectNode r = rutas instanceof ObjectNode o ? o : raiz.putObject("rutas");
        r.put(clave(proyectoReal), salidaReal.toString());
        preferencias.escribir(Json.bonito(raiz) + "\n", proyectoReal);
    }

    static String clave(Path proyectoReal) {
        String s = proyectoReal.toString();
        return RutaSegura.WINDOWS ? s.toLowerCase(Locale.ROOT) : s;
    }

    private ObjectNode leer() {
        String texto = preferencias.leer();
        if (texto != null && !texto.isBlank()) {
            try {
                JsonNode n = Json.MAPPER.readTree(texto);
                if (n != null && n.isObject() && n.path("version").asInt(-1) == VERSION && n.path("rutas").isObject()) {
                    return (ObjectNode) n;
                }
                System.err.println("Preferencias ignoradas: " + preferencias.archivo() + " no tiene el formato esperado.");
            } catch (IOException e) {
                System.err.println("Preferencias ignoradas: " + preferencias.archivo() + " está corrupto: " + e.getMessage());
            }
        }
        ObjectNode nuevo = Json.objeto();
        nuevo.put("version", VERSION);
        nuevo.putObject("rutas");
        return nuevo;
    }
}
