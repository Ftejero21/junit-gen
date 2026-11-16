package com.junitgen.util;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Recursos incluidos en el JAR: esquema del spec (fuente única), ejemplo y prompts. */
public final class Recursos {

    private Recursos() {
    }

    public static String texto(String nombre) {
        try (InputStream in = Recursos.class.getResourceAsStream("/" + nombre)) {
            if (in == null) {
                throw new IllegalStateException("Falta el recurso " + nombre + " en el JAR.");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("No se pudo leer el recurso " + nombre + ": " + e.getMessage(), e);
        }
    }

    public static String esquemaSpecTexto() {
        return texto("spec.schema.json").trim();
    }

    /** Esquema del spec sin $schema/$id, apto para inputSchema de MCP. */
    public static ObjectNode esquemaSpec() {
        ObjectNode n = (ObjectNode) Json.leer(esquemaSpecTexto());
        n.remove("$schema");
        n.remove("$id");
        return n;
    }

    public static String ejemploSpec() {
        return texto("spec-ejemplo.json").trim();
    }

    private static String interpolar(String plantilla) {
        return plantilla.replace("{{ESQUEMA_SPEC}}", esquemaSpecTexto()).replace("{{EJEMPLO_SPEC}}", ejemploSpec());
    }

    /**
     * Prompt del flujo MCP. La ruta del proyecto, la carpeta de salida y los servicios son opcionales
     * (null o en blanco): sin ruta, el agente pasa la raíz del workspace que conoce; sin salida, junit-gen
     * pregunta dónde exportar; con servicios, se pasan en {@code servicios} para deducir el módulo.
     */
    public static String promptMcp(String rutaProyecto, String carpetaSalida, String servicios) {
        return promptMcp(rutaProyecto, carpetaSalida, servicios, false);
    }

    /**
     * Como {@link #promptMcp(String, String, String)}; con {@code todas} (o la palabra «todas» entre los
     * servicios) añade «`todas` = true» a los argumentos del escaneo: 100 % de las funciones.
     */
    public static String promptMcp(String rutaProyecto, String carpetaSalida, String servicios, boolean todas) {
        return promptMcp(rutaProyecto, carpetaSalida, servicios, todas, null);
    }

    /**
     * Como {@link #promptMcp(String, String, String, boolean)}; con {@code funciones} (nombres separados por comas,
     * nombrados por el usuario para un servicio) añade «`funciones` = [..]» a los argumentos del escaneo: se prueban
     * exactamente esas y la IA no llama a seleccionar_funciones.
     */
    public static String promptMcp(String rutaProyecto, String carpetaSalida, String servicios, boolean todas, String funciones) {
        return promptMcp(rutaProyecto, carpetaSalida, servicios, todas, funciones, false);
    }

    /**
     * Como {@link #promptMcp(String, String, String, boolean, String)}; con {@code continuar} (o la palabra «continuar»
     * entre los servicios) los argumentos del escaneo son «`continuar` = true» (sin servicios, todas ni funciones):
     * junit-gen retoma la tanda guardada en la carpeta de salida por el siguiente servicio.
     */
    public static String promptMcp(String rutaProyecto, String carpetaSalida, String servicios, boolean todas, String funciones,
                                   boolean continuar) {
        if (!continuar && servicios != null) {
            for (String x : servicios.split("[,;\\s]+")) {
                if (x.replace("`", "").replace("\"", "").replace("'", "").trim().equalsIgnoreCase("continuar")) {
                    continuar = true;
                    break;
                }
            }
        }
        if (continuar) {
            servicios = null;
            todas = false;
            funciones = null;
        }
        boolean hayRuta = rutaProyecto != null && !rutaProyecto.isBlank();
        boolean haySalida = carpetaSalida != null && !carpetaSalida.isBlank();
        List<String> lista = new ArrayList<>();
        if (servicios != null) {
            for (String x : servicios.split("[,;\\s]+")) {
                String n = x.replace("`", "").replace("\"", "").replace("'", "").trim();
                if (n.equalsIgnoreCase("todas")) {
                    todas = true;
                    continue;
                }
                if (!n.isEmpty() && !lista.contains(n)) {
                    lista.add(n);
                }
            }
        }
        String s = continuar ? "los pendientes de la tanda guardada (tanda.json)"
                : lista.isEmpty() ? "los que el usuario confirme tras el escaneo" : String.join(", ", lista);
        String proyecto = hayRuta ? "`" + rutaProyecto + "`"
                : "la raíz del workspace/carpeta de trabajo que ya conoces (pásala en `ruta` sin preguntar al usuario; nunca un "
                + "módulo ni una subcarpeta)";
        String salida = haySalida ? "`" + carpetaSalida + "`"
                : "la ruta por defecto guardada (junit-gen la usa sin preguntar y lo indica en una nota) o la que elija el usuario "
                + "cuando junit-gen pregunte «¿Dónde exportamos los tests?» (no pases `carpetaSalida`)";
        List<String> partes = new ArrayList<>();
        partes.add(hayRuta ? "`ruta` = `" + rutaProyecto + "`"
                : "`ruta` = la raíz del workspace que ya conoces, sin preguntar al usuario (si no conoces ninguna, omítela: junit-gen "
                + "usa la carpeta abierta en el IDE o el directorio de trabajo del servidor)");
        if (!lista.isEmpty()) {
            List<String> comillas = new ArrayList<>();
            lista.forEach(x -> comillas.add("\"" + x + "\""));
            partes.add("`servicios` = [" + String.join(", ", comillas) + "] (junit-gen deduce el módulo: no lo preguntes)");
        }
        if (todas) {
            partes.add("`todas` = true (el usuario pidió probar el 100 % de las funciones)");
        }
        List<String> fs = new ArrayList<>();
        if (funciones != null && !todas) {
            for (String x : funciones.split("[,;\\s]+")) {
                String n = x.replace("`", "").replace("\"", "").replace("'", "").trim();
                if (!n.isEmpty() && !fs.contains("\"" + n + "\"")) {
                    fs.add("\"" + n + "\"");
                }
            }
        }
        if (!fs.isEmpty()) {
            partes.add("`funciones` = [" + String.join(", ", fs) + "] (las nombró el usuario: no llames a seleccionar_funciones)");
        }
        if (continuar) {
            partes.add("`continuar` = true (el usuario escribió `continuar`: retoma la tanda guardada por el siguiente servicio; "
                    + "no pases numServicios, servicios, funciones ni todas)");
        }
        partes.add(haySalida ? "`carpetaSalida` = `" + carpetaSalida + "`"
                : "sin `carpetaSalida` (junit-gen usa la ruta por defecto guardada o preguntará dónde exportar)");
        String argumentos = "con " + String.join(", ", partes);
        return interpolar(texto("prompt-mcp.md")).replace("{{argumentosEscaneo}}", argumentos)
                .replace("{{proyecto}}", proyecto).replace("{{salida}}", salida).replace("{{servicios}}", s);
    }

    public static String promptSinMcp() {
        return interpolar(texto("prompt-sin-mcp.md"));
    }
}
