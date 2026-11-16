package com.junitgen.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.junitgen.util.Json;
import com.junitgen.util.Recursos;

/** Prompt predefinido GenerarTest con el flujo completo (todos los argumentos son opcionales). */
public final class PromptFlujo {

    public static final String NOMBRE = "GenerarTest";

    private PromptFlujo() {
    }

    public static ObjectNode listar() {
        ObjectNode r = Json.objeto();
        ArrayNode ps = r.putArray("prompts");
        ObjectNode p = ps.addObject();
        p.put("name", NOMBRE);
        p.put("title", "Generar Test");
        p.put("description", "Flujo completo: escanear en solo lectura, seguir el plan de N servicios (sus funciones que eliges tú "
                + "por importancia de negocio con `seleccionar_funciones`, dentro del cupo de junit-gen (60 % de las no triviales por "
                + "defecto; con `todas`, el 100 %; con `funciones=…`, las que nombre el usuario)), escribir "
                + "el spec, validar, generar en la carpeta de salida, probar los tests en segundo plano servicio a servicio (siempre, "
                + "sin preguntar, en una carpeta temporal y sin tocar el proyecto), cerrar con ejecutar_tests y resumir; con "
                + "`continuar`, retomar la tanda guardada (tanda.json) por el siguiente servicio. No hace falta preguntar rutas: el agente pasa la raíz del workspace que conoce, junit-gen deduce el módulo "
                + "de los servicios nombrados, usa la ruta por defecto guardada sin preguntar (o pregunta una vez dónde exportar: "
                + "Escritorio, ruta por defecto u «Otra ruta…») y calcula el plan con 'numServicios' (N = número de servicios) o "
                + "con los servicios nombrados.");
        ArrayNode args = p.putArray("arguments");
        arg(args, "rutaProyecto", "Raíz del proyecto/workspace (opcional; por defecto, la raíz del workspace que conoce el agente, la "
                + "carpeta abierta en el IDE o el directorio de trabajo del servidor).", false);
        arg(args, "carpetaSalida", "Carpeta donde se crearán los tests (opcional; si se omite, junit-gen usa la ruta por defecto "
                + "guardada o la pregunta una vez).", false);
        arg(args, "servicios", "Servicios a tratar, separados por comas (opcional; si se indican, junit-gen deduce el módulo; si no, "
                + "se eligen tras el escaneo).", false);
        arg(args, "todas", "Escribe `todas` (o true) solo si quieres probar el 100 % de las funciones, incluidas las triviales "
                + "(opcional; por defecto eliges tú las funciones con seleccionar_funciones dentro del cupo).", false);
        arg(args, "funciones", "Funciones concretas de UN servicio, separadas por comas (opcional; solo si el usuario las nombró: "
                + "se prueban exactamente esas y no se llama a seleccionar_funciones).", false);
        arg(args, "continuar", "Escribe `continuar` (o true) para retomar la tanda guardada en la carpeta de salida por el "
                + "siguiente servicio, p. ej. en un chat nuevo con el contexto agotado (opcional; no se combina con servicios, todas "
                + "ni funciones).", false);
        return r;
    }

    private static void arg(ArrayNode args, String nombre, String descripcion, boolean obligatorio) {
        ObjectNode a = args.addObject();
        a.put("name", nombre);
        a.put("description", descripcion);
        a.put("required", obligatorio);
    }

    public static ObjectNode obtener(JsonNode params) {
        String nombre = params == null ? null : params.path("name").asText(null);
        if (!NOMBRE.equals(nombre)) {
            throw new JsonRpc.ErrorRpc(JsonRpc.INVALID_PARAMS, "Prompt desconocido: " + nombre);
        }
        JsonNode a = params.path("arguments");
        String ruta = argumento(a, "rutaProyecto");
        String salida = argumento(a, "carpetaSalida");
        String servicios = argumento(a, "servicios");
        String todasArg = argumento(a, "todas");
        boolean todas = todasArg != null && (todasArg.equalsIgnoreCase("todas") || todasArg.equalsIgnoreCase("true")
                || todasArg.equalsIgnoreCase("sí") || todasArg.equalsIgnoreCase("si"));
        ObjectNode r = Json.objeto();
        r.put("description", ruta == null ? "Flujo de junit-gen" : "Flujo de junit-gen para " + ruta);
        ArrayNode msgs = r.putArray("messages");
        ObjectNode m = msgs.addObject();
        m.put("role", "user");
        ObjectNode c = m.putObject("content");
        c.put("type", "text");
        String continuarArg = argumento(a, "continuar");
        boolean continuar = continuarArg != null && (continuarArg.equalsIgnoreCase("continuar")
                || continuarArg.equalsIgnoreCase("true") || continuarArg.equalsIgnoreCase("sí") || continuarArg.equalsIgnoreCase("si"));
        c.put("text", Recursos.promptMcp(ruta, salida, servicios, todas, argumento(a, "funciones"), continuar));
        return r;
    }

    /** Valor de texto del argumento; ausente, nulo o en blanco → null. */
    private static String argumento(JsonNode a, String nombre) {
        JsonNode v = a.get(nombre);
        if (v == null || v.isNull() || !v.isValueNode()) {
            return null;
        }
        String s = v.asText();
        return s.isBlank() ? null : s.trim();
    }
}
