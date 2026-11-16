package com.junitgen.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.junitgen.util.Json;

/** Construcción de mensajes JSON-RPC 2.0. */
public final class JsonRpc {

    public static final int PARSE_ERROR = -32700;
    public static final int INVALID_REQUEST = -32600;
    public static final int METHOD_NOT_FOUND = -32601;
    public static final int INVALID_PARAMS = -32602;
    public static final int INTERNAL_ERROR = -32603;

    private JsonRpc() {
    }

    public static ObjectNode resultado(JsonNode id, JsonNode resultado) {
        ObjectNode r = Json.objeto();
        r.put("jsonrpc", "2.0");
        r.set("id", id);
        r.set("result", resultado);
        return r;
    }

    public static ObjectNode error(JsonNode id, int codigo, String mensaje) {
        ObjectNode r = Json.objeto();
        r.put("jsonrpc", "2.0");
        r.set("id", id == null ? Json.MAPPER.nullNode() : id);
        ObjectNode e = r.putObject("error");
        e.put("code", codigo);
        e.put("message", mensaje);
        return r;
    }

    public static ObjectNode peticion(String id, String metodo, JsonNode params) {
        ObjectNode r = Json.objeto();
        r.put("jsonrpc", "2.0");
        r.put("id", id);
        r.put("method", metodo);
        if (params != null) {
            r.set("params", params);
        }
        return r;
    }

    /** Notificación JSON-RPC 2.0 (sin id). */
    public static ObjectNode notificacion(String metodo, JsonNode params) {
        ObjectNode r = Json.objeto();
        r.put("jsonrpc", "2.0");
        r.put("method", metodo);
        if (params != null) {
            r.set("params", params);
        }
        return r;
    }

    public static class ErrorRpc extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public final int codigo;

        public ErrorRpc(int codigo, String mensaje) {
            super(mensaje);
            this.codigo = codigo;
        }
    }
}
