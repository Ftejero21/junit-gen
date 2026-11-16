package com.junitgen.spec;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.junitgen.util.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Lee el spec (objeto JSON, cadena JSON o archivo) con límite de tamaño y errores claros. */
public final class LectorSpec {

    public static final int MAX_BYTES = 2 * 1024 * 1024;

    public record Lectura(Spec spec, List<ErrorValidacion> errores) {
        public boolean ok() {
            return spec != null && errores.isEmpty();
        }
    }

    private LectorSpec() {
    }

    public static Lectura leerArchivo(Path archivo) {
        List<ErrorValidacion> errores = new ArrayList<>();
        try {
            if (!Files.isRegularFile(archivo)) {
                errores.add(ErrorValidacion.error("$", "SPEC_NO_EXISTE", "No existe el archivo de spec " + archivo, null));
                return new Lectura(null, errores);
            }
            if (Files.size(archivo) > MAX_BYTES) {
                errores.add(ErrorValidacion.error("$", "SPEC_DEMASIADO_GRANDE", "El spec supera 2 MB.",
                        "Divide el trabajo: un servicio por spec."));
                return new Lectura(null, errores);
            }
            String texto = Files.readString(archivo, StandardCharsets.UTF_8);
            return leerTexto(texto);
        } catch (IOException e) {
            errores.add(ErrorValidacion.error("$", "SPEC_ILEGIBLE", "No se pudo leer " + archivo + ": " + e.getMessage(), null));
            return new Lectura(null, errores);
        }
    }

    public static Lectura leerTexto(String texto) {
        List<ErrorValidacion> errores = new ArrayList<>();
        if (texto == null || texto.isBlank()) {
            errores.add(ErrorValidacion.error("$", "SPEC_VACIO", "El spec está vacío.", "Envía el JSON del spec."));
            return new Lectura(null, errores);
        }
        String t = texto.startsWith("﻿") ? texto.substring(1) : texto;
        if (t.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            errores.add(ErrorValidacion.error("$", "SPEC_DEMASIADO_GRANDE", "El spec supera 2 MB.", "Un servicio por spec."));
            return new Lectura(null, errores);
        }
        JsonNode n;
        try {
            n = Json.MAPPER.readTree(t);
        } catch (JsonProcessingException e) {
            errores.add(ErrorValidacion.error("$", "JSON_INVALIDO", "JSON no válido: " + e.getOriginalMessage()
                    + (e.getLocation() != null ? " (línea " + e.getLocation().getLineNr() + ", columna "
                    + e.getLocation().getColumnNr() + ")" : ""), "Devuelve únicamente el objeto JSON del spec."));
            return new Lectura(null, errores);
        }
        return leer(n);
    }

    public static Lectura leer(JsonNode n) {
        List<ErrorValidacion> errores = new ArrayList<>();
        if (n == null || n.isNull() || n.isMissingNode()) {
            errores.add(ErrorValidacion.error("$", "SPEC_VACIO", "Falta el spec.", null));
            return new Lectura(null, errores);
        }
        if (n.isTextual()) {
            return leerTexto(n.asText());
        }
        if (!n.isObject()) {
            errores.add(ErrorValidacion.error("$", "SPEC_NO_OBJETO", "El spec debe ser un objeto JSON.", null));
            return new Lectura(null, errores);
        }
        if (n.toString().length() > MAX_BYTES) {
            errores.add(ErrorValidacion.error("$", "SPEC_DEMASIADO_GRANDE", "El spec supera 2 MB.", "Un servicio por spec."));
            return new Lectura(null, errores);
        }
        try {
            Spec s = Json.MAPPER.treeToValue(n, Spec.class);
            return new Lectura(s, errores);
        } catch (UnrecognizedPropertyException e) {
            errores.add(ErrorValidacion.error(ruta(e), "CAMPO_DESCONOCIDO", "Campo desconocido '" + e.getPropertyName() + "'.",
                    "Campos válidos aquí: " + e.getKnownPropertyIds()));
        } catch (JsonMappingException e) {
            errores.add(ErrorValidacion.error(ruta(e), "TIPO_INCORRECTO", "Valor con tipo incorrecto: " + e.getOriginalMessage(),
                    "Revisa el esquema del spec (cadenas para expresiones Java, listas para datos/stubs/casos)."));
        } catch (JsonProcessingException e) {
            errores.add(ErrorValidacion.error("$", "JSON_INVALIDO", e.getOriginalMessage(), null));
        }
        return new Lectura(null, errores);
    }

    private static String ruta(JsonMappingException e) {
        StringBuilder sb = new StringBuilder("$");
        for (JsonMappingException.Reference r : e.getPath()) {
            if (r.getFieldName() != null) {
                sb.append('.').append(r.getFieldName());
            } else if (r.getIndex() >= 0) {
                sb.append('[').append(r.getIndex()).append(']');
            }
        }
        return sb.toString();
    }
}
