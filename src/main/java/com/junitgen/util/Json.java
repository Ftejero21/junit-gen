package com.junitgen.util;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** ObjectMapper compartido y utilidades JSON. */
public final class Json {

    public static final ObjectMapper MAPPER = crear();

    private Json() {
    }

    private static ObjectMapper crear() {
        ObjectMapper m = new ObjectMapper();
        m.getFactory().setStreamReadConstraints(StreamReadConstraints.builder()
                .maxNestingDepth(200).maxStringLength(5_000_000).maxNumberLength(100).build());
        m.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
        m.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);
        m.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        return m;
    }

    public static ObjectNode objeto() {
        return MAPPER.createObjectNode();
    }

    public static ArrayNode lista() {
        return MAPPER.createArrayNode();
    }

    public static String compacto(Object o) {
        try {
            return MAPPER.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("No se pudo serializar a JSON: " + e.getMessage(), e);
        }
    }

    public static String bonito(Object o) {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("No se pudo serializar a JSON: " + e.getMessage(), e);
        }
    }

    public static JsonNode leer(String texto) {
        try {
            return MAPPER.readTree(texto);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("JSON no válido: " + e.getOriginalMessage()
                    + (e.getLocation() != null ? " (línea " + e.getLocation().getLineNr() + ", columna "
                    + e.getLocation().getColumnNr() + ")" : ""), e);
        }
    }

    public static String texto(JsonNode n, String campo) {
        if (n == null) {
            return null;
        }
        JsonNode v = n.get(campo);
        return v == null || v.isNull() ? null : v.asText();
    }
}
