package com.junitgen.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.junitgen.seguridad.RutaSegura;
import com.junitgen.util.Json;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Herramienta MCP. El primer renglón de cada resultado es el aviso de solo lectura. */
public interface Herramienta {

    record Resultado(String texto, boolean error) {
    }

    String nombre();

    String titulo();

    String descripcion();

    ObjectNode esquema();

    boolean soloLectura();

    /** Aviso de la primera línea (se calcula tras ejecutar, sin preguntar nada al cliente). */
    String aviso(JsonNode args, SesionMcp sesion);

    Resultado ejecutar(JsonNode args, SesionMcp sesion) throws Exception;

    // ------------------------------------------------------------- utilidades de argumentos

    static String texto(JsonNode args, String campo) {
        JsonNode v = args == null ? null : args.get(campo);
        if (v == null || v.isNull()) {
            return null;
        }
        String s = v.isTextual() ? v.asText() : v.toString();
        return s.isBlank() ? null : s;
    }

    static String obligatorio(JsonNode args, String campo) {
        String s = texto(args, campo);
        if (s == null) {
            throw new IllegalArgumentException("Falta el argumento obligatorio '" + campo + "'.");
        }
        return s;
    }

    static Path ruta(JsonNode args, String campo) {
        String s = texto(args, campo);
        return s == null ? null : RutaSegura.ruta(s);
    }

    static boolean bool(JsonNode args, String campo) {
        JsonNode v = args == null ? null : args.get(campo);
        return v != null && (v.asBoolean(false) || "true".equalsIgnoreCase(v.asText()));
    }

    static int entero(JsonNode args, String campo, int defecto) {
        JsonNode v = args == null ? null : args.get(campo);
        if (v == null || v.isNull()) {
            return defecto;
        }
        return v.isNumber() ? v.asInt() : Integer.parseInt(v.asText().trim());
    }

    static List<String> lista(JsonNode args, String campo) {
        List<String> r = new ArrayList<>();
        JsonNode v = args == null ? null : args.get(campo);
        if (v == null || v.isNull()) {
            return r;
        }
        if (v.isArray()) {
            v.forEach(x -> r.add(x.asText()));
        } else {
            for (String s : v.asText().split(",")) {
                if (!s.isBlank()) {
                    r.add(s.trim());
                }
            }
        }
        return r;
    }

    static ObjectNode esquemaBase() {
        ObjectNode s = Json.objeto();
        s.put("type", "object");
        s.putObject("properties");
        s.putArray("required");
        return s;
    }

    static void propiedad(ObjectNode esquema, String nombre, String tipo, String descripcion, boolean obligatoria) {
        ObjectNode p = ((ObjectNode) esquema.get("properties")).putObject(nombre);
        p.put("type", tipo);
        p.put("description", descripcion);
        if (tipo.equals("array")) {
            p.putObject("items").put("type", "string");
        }
        if (obligatoria) {
            ((ArrayNode) esquema.get("required")).add(nombre);
        }
    }
}
