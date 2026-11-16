package com.junitgen.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.junitgen.util.Json;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Preguntas al usuario con botones mediante elicitation/create (solo si el cliente lo declara). */
public final class Elicitacion {

    /** Propiedad de sistema con el límite total (segundos, 60..600) de las preguntas de una llamada. */
    public static final String PROPIEDAD_TIMEOUT = "junitgen.elicitacion.timeoutSegundos";
    static final int TIMEOUT_POR_DEFECTO = 150;
    /**
     * Límite total por llamada para las preguntas (por defecto 150 s; propiedad {@link #PROPIEDAD_TIMEOUT}).
     * Debe ser inferior al timeout de petición del cliente (~180 s en Copilot / rmcp): se descuenta el tiempo
     * ya consumido por la llamada en curso, incluida la cola del worker.
     */
    public static final Duration TIMEOUT = Duration.ofSeconds(leerTimeout());
    /** Por debajo de este tiempo restante no se muestra la pregunta ({@link Estado#SIN_TIEMPO}). */
    public static final Duration ESPERA_MINIMA = Duration.ofSeconds(45);
    public static final String DECLINE = "decline";
    public static final String CANCEL = "cancel";
    /** Acción de {@link RespuestaSalida} cuando la pregunta caducó sin respuesta (no es aceptar, decline ni cancel). */
    public static final String SIN_RESPUESTA = "sin_respuesta";
    public static final String PREGUNTA_SALIDA = "¿Dónde exportamos los tests?";
    public static final String CAMPO_CARPETA = "carpeta";
    /** Única propiedad de la segunda pantalla (solo tras elegir {@link #OTRA_RUTA}). */
    public static final String CAMPO_RUTA = "ruta";
    /** Opción del enum de la carpeta que lleva a la pantalla de texto (con el carácter «…», U+2026). */
    public static final String OTRA_RUTA = "Otra ruta\u2026";
    public static final String GUARDAR_DEFECTO = "Establecer como ruta por defecto";
    public static final String SOLO_ESTA_VEZ = "Solo esta vez";
    /** Primera versión del protocolo que admite 'default' en los esquemas de elicitation. */
    static final String PROTOCOLO_DEFAULT = "2025-11-25";

    private Elicitacion() {
    }

    private static int leerTimeout() {
        String v = System.getProperty(PROPIEDAD_TIMEOUT);
        if (v == null || v.isBlank()) {
            return TIMEOUT_POR_DEFECTO;
        }
        try {
            int s = Integer.parseInt(v.trim());
            if (s >= 60 && s <= 600) {
                return s;
            }
        } catch (NumberFormatException e) {
            System.err.println("Valor no numérico en " + PROPIEDAD_TIMEOUT + ": " + e.getMessage());
        }
        System.err.println(PROPIEDAD_TIMEOUT + " debe ser un entero entre 60 y 600: se usan " + TIMEOUT_POR_DEFECTO + " s.");
        return TIMEOUT_POR_DEFECTO;
    }

    /**
     * Estado de una pregunta: opción elegida, decline, cancel, sin respuesta (caducó o se canceló la llamada),
     * sin tiempo (no se mostró porque quedaba menos de {@link #ESPERA_MINIMA}) o no disponible (sin soporte,
     * error o respuesta no válida). Sin respuesta y sin tiempo nunca equivalen a aceptar ni a rechazar.
     */
    public enum Estado { ELEGIDA, DECLINE, CANCEL, SIN_RESPUESTA, SIN_TIEMPO, NO_DISPONIBLE }

    /** Respuesta de {@link #consultar}: estado y opción (solo con {@link Estado#ELEGIDA}). */
    public record Respuesta(Estado estado, String opcion) {
    }

    /** Resultado interno de {@link #solicitar}: estado y 'result' del cliente (solo si respondió). */
    private record Res(Estado estado, JsonNode result) {
    }

    /**
     * Devuelve la opción elegida, {@link #DECLINE}, {@link #CANCEL}, o null si no hay soporte, se agota
     * el tiempo o hay un error (el agente debe preguntar en el chat).
     */
    public static String preguntar(SesionMcp sesion, String mensaje, List<String> opciones, Duration timeout) {
        Respuesta r = consultar(sesion, mensaje, opciones, timeout);
        if (r.estado() == Estado.ELEGIDA) {
            return r.opcion();
        }
        if (r.estado() == Estado.DECLINE) {
            return DECLINE;
        }
        if (r.estado() == Estado.CANCEL) {
            return CANCEL;
        }
        return null;
    }

    /** Pregunta con botones (enum {@code accion}) y devuelve su estado; ver {@link Estado}. */
    public static Respuesta consultar(SesionMcp sesion, String mensaje, List<String> opciones, Duration timeout) {
        if (!sesion.soportaElicitacion()) {
            return new Respuesta(Estado.NO_DISPONIBLE, null);
        }
        ObjectNode params = Json.objeto();
        params.put("message", mensaje);
        ObjectNode schema = params.putObject("requestedSchema");
        schema.put("type", "object");
        ObjectNode accion = schema.putObject("properties").putObject("accion");
        accion.put("type", "string");
        accion.put("title", "Acción");
        ArrayNode en = accion.putArray("enum");
        opciones.forEach(en::add);
        schema.putArray("required").add("accion");
        Res res = solicitar(sesion, params, timeout);
        if (res.estado() != Estado.ELEGIDA) {
            return new Respuesta(res.estado(), null);
        }
        String action = res.result().path("action").asText("");
        if ("accept".equals(action)) {
            String elegido = res.result().path("content").path("accion").asText(null);
            return elegido != null && opciones.contains(elegido) ? new Respuesta(Estado.ELEGIDA, elegido)
                    : new Respuesta(Estado.NO_DISPONIBLE, null);
        }
        if (DECLINE.equals(action)) {
            return new Respuesta(Estado.DECLINE, null);
        }
        if (CANCEL.equals(action)) {
            return new Respuesta(Estado.CANCEL, null);
        }
        return new Respuesta(Estado.NO_DISPONIBLE, null);
    }

    /**
     * Origen de la respuesta de {@link #preguntarSalida} / {@link #preguntarRuta}: una opción del enum,
     * texto escrito por el usuario, o la opción {@link #OTRA_RUTA} (hay que pedir la ruta en otra pantalla).
     */
    public enum Origen { PREDEFINIDA, TEXTO_LIBRE, OTRA }

    /** Respuesta a {@link #preguntarSalida} / {@link #preguntarRuta}: acción del usuario, ruta (null si no hay) y su origen. */
    public record RespuestaSalida(String accion, String ruta, Origen origen) {
    }

    /**
     * Primera pantalla de la carpeta de salida: una única propiedad {@code carpeta} (enum de etiquetas +
     * {@link #OTRA_RUTA}). Las etiquetas nunca se interpretan: la ruta sale del mapa etiqueta→ruta. Una
     * etiqueta conocida da su ruta (PREDEFINIDA); «Otra ruta…» (comparación tolerante) devuelve ruta null y
     * origen OTRA; otro texto no vacío se trata como ruta escrita (TEXTO_LIBRE). Si la pregunta caduca sin
     * respuesta devuelve acción {@link #SIN_RESPUESTA} sin ruta; devuelve null si no hay soporte, no queda
     * tiempo para preguntar o hay un error. Con decline/cancel o respuesta vacía, la ruta es null.
     */
    public static RespuestaSalida preguntarSalida(SesionMcp sesion, String mensaje, LinkedHashMap<String, String> etiquetaARuta,
                                                  String etiquetaPreseleccionada, Duration timeout) {
        if (!sesion.soportaElicitacion()) {
            return null;
        }
        Map<String, String> opciones = etiquetaARuta == null ? Map.of() : etiquetaARuta;
        ObjectNode params = Json.objeto();
        params.put("message", mensaje);
        ObjectNode schema = params.putObject("requestedSchema");
        schema.put("type", "object");
        ObjectNode carpeta = schema.putObject("properties").putObject(CAMPO_CARPETA);
        carpeta.put("type", "string");
        carpeta.put("title", PREGUNTA_SALIDA);
        ArrayNode en = carpeta.putArray("enum");
        opciones.keySet().forEach(en::add);
        en.add(OTRA_RUTA);
        String protocolo = sesion.protocolo();
        if (etiquetaPreseleccionada != null && opciones.containsKey(etiquetaPreseleccionada)
                && protocolo != null && protocolo.compareTo(PROTOCOLO_DEFAULT) >= 0) {
            carpeta.put("default", etiquetaPreseleccionada);
        }
        schema.putArray("required").add(CAMPO_CARPETA);
        Res rs = solicitar(sesion, params, timeout);
        if (rs.estado() == Estado.SIN_RESPUESTA) {
            return new RespuestaSalida(SIN_RESPUESTA, null, null);
        }
        if (rs.estado() != Estado.ELEGIDA) {
            return null;
        }
        JsonNode res = rs.result();
        String action = res.path("action").asText("");
        if (!"accept".equals(action)) {
            return new RespuestaSalida(action, null, null);
        }
        String elegido = valor(res.path("content"), CAMPO_CARPETA);
        if (elegido == null) {
            return new RespuestaSalida(action, null, null);
        }
        String predefinida = opciones.get(elegido);
        if (predefinida != null) {
            return new RespuestaSalida(action, predefinida, Origen.PREDEFINIDA);
        }
        if (esOtraRuta(elegido)) {
            return new RespuestaSalida(action, null, Origen.OTRA);
        }
        return new RespuestaSalida(action, elegido, Origen.TEXTO_LIBRE);
    }

    /**
     * Segunda pantalla (solo tras «Otra ruta…» o sin opciones predefinidas): una única propiedad de texto
     * {@code ruta}. Si caduca sin respuesta devuelve acción {@link #SIN_RESPUESTA} sin ruta; devuelve null si
     * no hay soporte, no queda tiempo para preguntar o hay un error; si no, la acción y el texto escrito
     * (null si está vacío o no se aceptó).
     */
    public static RespuestaSalida preguntarRuta(SesionMcp sesion, String mensaje, Duration timeout) {
        if (!sesion.soportaElicitacion()) {
            return null;
        }
        ObjectNode params = Json.objeto();
        params.put("message", mensaje);
        ObjectNode schema = params.putObject("requestedSchema");
        schema.put("type", "object");
        ObjectNode ruta = schema.putObject("properties").putObject(CAMPO_RUTA);
        ruta.put("type", "string");
        ruta.put("title", "Ruta completa de la carpeta de salida");
        ruta.put("description", "Ruta completa de una carpeta fuera del proyecto.");
        schema.putArray("required").add(CAMPO_RUTA);
        Res rs = solicitar(sesion, params, timeout);
        if (rs.estado() == Estado.SIN_RESPUESTA) {
            return new RespuestaSalida(SIN_RESPUESTA, null, null);
        }
        if (rs.estado() != Estado.ELEGIDA) {
            return null;
        }
        JsonNode res = rs.result();
        String action = res.path("action").asText("");
        String texto = "accept".equals(action) ? valor(res.path("content"), CAMPO_RUTA) : null;
        return new RespuestaSalida(action, texto, Origen.TEXTO_LIBRE);
    }

    /** «Otra ruta…» sin distinguir mayúsculas ni espacios, con «…», «...» o sin puntos suspensivos. */
    static boolean esOtraRuta(String texto) {
        if (texto == null) {
            return false;
        }
        String t = texto.trim().toLowerCase(Locale.ROOT);
        if (t.endsWith("\u2026")) {
            t = t.substring(0, t.length() - 1);
        } else if (t.endsWith("...")) {
            t = t.substring(0, t.length() - 3);
        }
        return t.trim().equals("otra ruta");
    }

    private static String valor(JsonNode content, String campo) {
        JsonNode v = content.get(campo);
        if (v == null || v.isNull() || !v.isValueNode()) {
            return null;
        }
        String s = v.asText();
        return s.isBlank() ? null : s.trim();
    }

    /**
     * Envía elicitation/create y espera como mucho min(timeout, lo que queda del límite {@link #TIMEOUT} de la
     * llamada en curso). Estado ELEGIDA = el cliente respondió con 'result' (se devuelve); SIN_RESPUESTA si la
     * llamada se canceló o la pregunta caducó/se abortó; SIN_TIEMPO si quedaba menos de {@link #ESPERA_MINIMA}
     * (no se envía); NO_DISPONIBLE ante error del cliente, respuesta sin 'result' o fallo de la sesión.
     */
    private static Res solicitar(SesionMcp sesion, ObjectNode params, Duration timeout) {
        if (sesion.llamadaCancelada()) {
            return new Res(Estado.SIN_RESPUESTA, null);
        }
        Duration restante = TIMEOUT.minus(sesion.transcurridoLlamada());
        if (restante.compareTo(ESPERA_MINIMA) < 0) {
            System.err.println("elicitation/create no enviada: quedan " + Math.max(0, restante.toSeconds())
                    + " s del límite de " + TIMEOUT.toSeconds() + " s de la llamada.");
            return new Res(Estado.SIN_TIEMPO, null);
        }
        Duration espera = timeout == null || timeout.compareTo(restante) > 0 ? restante : timeout;
        SesionMcp.RespuestaCliente r = sesion.solicitarYEsperar("elicitation/create", params, espera);
        if (r.fin() == SesionMcp.FinEspera.RESPONDIDA) {
            JsonNode m = r.mensaje();
            if (m == null || m.has("error")) {
                System.err.println("Elicitation rechazada por el cliente: " + (m == null ? "sin mensaje" : m.path("error").path("code").asText("?")));
                return new Res(Estado.NO_DISPONIBLE, null);
            }
            JsonNode result = m.get("result");
            if (result == null || result.isNull()) {
                return new Res(Estado.NO_DISPONIBLE, null);
            }
            return new Res(Estado.ELEGIDA, result);
        }
        if (r.fin() == SesionMcp.FinEspera.CADUCADA || r.fin() == SesionMcp.FinEspera.ABORTADA) {
            return new Res(Estado.SIN_RESPUESTA, null);
        }
        return new Res(Estado.NO_DISPONIBLE, null);
    }
}
