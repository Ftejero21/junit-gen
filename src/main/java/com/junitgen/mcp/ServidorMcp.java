package com.junitgen.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.junitgen.Fachada;
import com.junitgen.Version;
import com.junitgen.util.Json;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Servidor MCP estándar por stdio (JSON-RPC 2.0, un mensaje por línea). El hilo lector despacha las peticiones
 * {@code tools/call} a un pool de hilos (cada llamada es atendida bajo el cerrojo de sesión de
 * {@link RegistroHerramientas#llamar}: una a la vez, por orden de llegada, en un único proceso y sesión; así varios
 * trabajadores en paralelo pueden esperar las pruebas a la vez) y el resto de peticiones a un worker único, y completa las
 * respuestas a peticiones servidor→cliente.
 */
public class ServidorMcp {

    public static final List<String> VERSIONES = List.of("2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05");
    public static final String VERSION_POR_DEFECTO = "2025-06-18";

    private final TransporteStdio transporte;
    private final SesionMcp sesion;
    private final RegistroHerramientas registro = new RegistroHerramientas();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "junit-gen-mcp-worker");
        t.setDaemon(true);
        return t;
    });
    private final AtomicInteger numeroLlamada = new AtomicInteger();
    /** Hilos de las llamadas a herramientas (un hilo por llamada en curso). */
    private final ExecutorService llamadas = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "junit-gen-mcp-llamada-" + numeroLlamada.incrementAndGet());
        t.setDaemon(true);
        return t;
    });

    public ServidorMcp(TransporteStdio transporte, Fachada fachada) {
        this(transporte, fachada, EntornoSesion.delSistema());
    }

    public ServidorMcp(TransporteStdio transporte, Fachada fachada, EntornoSesion entorno) {
        this.transporte = transporte;
        this.sesion = new SesionMcp(transporte, fachada, entorno);
    }

    public SesionMcp sesion() {
        return sesion;
    }

    public static String instrucciones() {
        return "REGLA: durante una tanda cada respuesta tuya es una llamada a una herramienta de junit-gen (o, en modo paralelo, "
                + "las llamadas a Agent que lanzan los subagentes de los bloques); un mensaje sin llamada termina el turno. Haz "
                + "exactamente lo que diga accionRequerida y no escribas al usuario hasta recibir respuestaFinal: entonces escribe "
                + "EXACTAMENTE ese markdown, tal cual. Si pierdes el hilo: escanear_proyecto {continuar: true}. junit-gen genera "
                + "tests JUnit a partir de un spec JSON. Empieza por escanear_proyecto, una vez: pasa en `peticion` la frase del "
                + "usuario tal cual y en `ruta` la raíz del workspace (nunca un módulo); su «Siguiente paso» indica el resto "
                + "(seleccionar_funciones, spec, generar_tests, ejecutar_tests, arreglar_caso). En cada caso del spec: "
                + "queHaceOriginal una vez por función y queComprueba en cada caso; junit-gen completa el resto. Modo paralelo "
                + "(Agent en Claude Code; task o runSubagent en Copilot): si el escaneo devuelve bloques, lanza un "
                + "subagente por bloque con el promptTrabajador y, al volver todos, llama a ejecutar_tests {} hasta "
                + "respuestaFinal. No arregles nada durante la tanda: los fallos se arreglan al final. No rebajes los tests ni "
                + "pongas como esperado el valor obtenido sin justificarlo con el código. Nunca preguntes al usuario qué hacer con "
                + "un test que falla. Prohibido: terminal y leer archivos. Regla de solo lectura: el proyecto nunca se modifica; junit-gen "
                + "solo escribe en la carpeta de salida. Usa el prompt GenerarTest.";
    }

    /** Bucle principal: lee hasta EOF, termina lo pendiente y sale. */
    public void ejecutar() throws IOException {
        try {
            String linea;
            while ((linea = transporte.leerLinea()) != null) {
                JsonNode msg;
                try {
                    msg = Json.MAPPER.readTree(linea);
                } catch (IOException e) {
                    sesion.enviar(JsonRpc.error(null, JsonRpc.PARSE_ERROR, "JSON no válido: " + e.getMessage()));
                    continue;
                }
                despachar(msg);
            }
        } finally {
            // Cierra la sesión: también cancela las verificaciones en segundo plano (espera acotada) antes de los hilos.
            sesion.cerrar();
            worker.shutdown();
            llamadas.shutdown();
            try {
                if (!worker.awaitTermination(15, TimeUnit.MINUTES)) {
                    worker.shutdownNow();
                }
                if (!llamadas.awaitTermination(15, TimeUnit.MINUTES)) {
                    llamadas.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                worker.shutdownNow();
                llamadas.shutdownNow();
            }
        }
    }

    private void despachar(JsonNode msg) throws IOException {
        if (msg.isArray()) {
            for (JsonNode m : msg) {
                despachar(m);
            }
            return;
        }
        if (!msg.isObject()) {
            sesion.enviar(JsonRpc.error(null, JsonRpc.INVALID_REQUEST, "Mensaje JSON-RPC no válido."));
            return;
        }
        JsonNode id = msg.get("id");
        String metodo = msg.has("method") ? msg.get("method").asText() : null;
        if (metodo == null) {
            if (id != null && (msg.has("result") || msg.has("error"))) {
                sesion.completar(msg);
            }
            return;
        }
        if (id == null || id.isNull()) {
            notificacion(metodo, msg.get("params"));
            return;
        }
        long recibido = System.nanoTime();
        // Las llamadas a herramientas van a su pool (la exclusión mutua la da el cerrojo de sesión); el resto, al worker único.
        ExecutorService destino = "tools/call".equals(metodo) ? llamadas : worker;
        destino.submit(() -> {
            sesion.iniciarLlamada(id.asText(), recibido);
            try {
                if (sesion.cancelado(id.asText())) {
                    return;
                }
                JsonNode r = procesar(metodo, msg.get("params"));
                if (!sesion.cancelado(id.asText())) {
                    sesion.enviar(JsonRpc.resultado(id, r));
                }
            } catch (JsonRpc.ErrorRpc e) {
                enviarSeguro(JsonRpc.error(id, e.codigo, e.getMessage()));
            } catch (RuntimeException e) {
                System.err.println("Error procesando " + metodo + ": " + e);
                e.printStackTrace(System.err);
                enviarSeguro(JsonRpc.error(id, JsonRpc.INTERNAL_ERROR, "Error interno: " + e.getMessage()));
            } catch (IOException e) {
                System.err.println("No se pudo enviar la respuesta: " + e.getMessage());
            } finally {
                sesion.terminarLlamada();
            }
        });
    }

    private void enviarSeguro(JsonNode m) {
        try {
            sesion.enviar(m);
        } catch (IOException e) {
            System.err.println("No se pudo enviar el error: " + e.getMessage());
        }
    }

    private void notificacion(String metodo, JsonNode params) {
        if ("notifications/cancelled".equals(metodo) && params != null && params.has("requestId")) {
            sesion.cancelar(params.get("requestId").asText());
        } else if ("notifications/roots/list_changed".equals(metodo)) {
            sesion.invalidarRaices();
        } else if ("notifications/initialized".equals(metodo)) {
            PreEscaneo.lanzar(sesion);
        }
        // El resto: sin acción.
    }

    JsonNode procesar(String metodo, JsonNode params) {
        switch (metodo) {
            case "initialize":
                return inicializar(params);
            case "ping":
                return Json.objeto();
            case "tools/list":
                return registro.listar();
            case "tools/call": {
                String nombre = params == null ? null : params.path("name").asText(null);
                Herramienta h = nombre == null ? null : registro.get(nombre);
                if (h == null) {
                    throw new JsonRpc.ErrorRpc(JsonRpc.INVALID_PARAMS, "Herramienta desconocida: " + nombre);
                }
                JsonNode args = params.get("arguments");
                if (args == null || args.isNull()) {
                    args = Json.objeto();
                }
                return registro.llamar(h, args, sesion);
            }
            case "prompts/list":
                return PromptFlujo.listar();
            case "prompts/get":
                return PromptFlujo.obtener(params);
            default:
                throw new JsonRpc.ErrorRpc(JsonRpc.METHOD_NOT_FOUND, "Método no soportado: " + metodo);
        }
    }

    private JsonNode inicializar(JsonNode params) {
        sesion.fijarCliente(params == null ? null : params.path("clientInfo").path("name").asText(null));
        String pedida = params == null ? null : params.path("protocolVersion").asText(null);
        String version = pedida != null && VERSIONES.contains(pedida) ? pedida : VERSION_POR_DEFECTO;
        sesion.inicializar(version, params == null ? null : params.get("capabilities"));
        ObjectNode r = Json.objeto();
        r.put("protocolVersion", version);
        ObjectNode caps = r.putObject("capabilities");
        caps.putObject("tools").put("listChanged", false);
        caps.putObject("prompts").put("listChanged", false);
        ObjectNode info = r.putObject("serverInfo");
        info.put("name", "junit-gen");
        info.put("version", Version.version());
        r.put("instructions", instrucciones());
        return r;
    }
}
