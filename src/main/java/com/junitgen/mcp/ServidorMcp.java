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

/**
 * Servidor MCP estándar por stdio (JSON-RPC 2.0, un mensaje por línea). El hilo lector despacha las
 * peticiones a un worker único y completa las respuestas a peticiones servidor→cliente.
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
        return "REGLA: durante una tanda cada respuesta tuya es una llamada a una herramienta de junit-gen; no escribas mensajes "
                + "de progreso ni resúmenes (un mensaje sin llamada termina el turno). Toda respuesta con trabajo pendiente empieza "
                + "por «accionRequerida: NO HAS TERMINADO (servicio k de N): llama ahora a …»: haz exactamente esa llamada; solo "
                + "escribes al usuario al recibir respuestaFinal. Si pierdes el hilo, escanear_proyecto {continuar: true}. "
                + "junit-gen genera tests unitarios JUnit a partir de un spec JSON. Flujo obligatorio, en orden y sin pedir "
                + "confirmación: 1) escanear_proyecto una vez (con `servicios` si el usuario los nombró; un número N es "
                + "`numServicios`; `todas`=true solo si el usuario escribió `todas`; `funciones` solo si el usuario nombró funciones "
                + "de UN servicio; si escribió una frase libre, pásala tal cual en `peticion` (sin numServicios, servicios, "
                + "funciones, todas ni continuar): junit-gen la interpreta): devuelve un resumen con el plan (provisional hasta elegir funciones) y el siguiente paso. 2) Para "
                + "CADA servicio del plan: elige TÚ por importancia de negocio (dentro del cupo, con motivo) de «Funciones "
                + "candidatas» (en el escaneo o en siguienteServicio.candidatas; si no vienen, seleccionar_funciones {clase}) y llama "
                + "a seleccionar_funciones {clase, funciones, omitidas} hasta que no devuelva errores: su respuesta ya trae el "
                + "paquete de trabajo del servicio; detalle_servicio solo si paginacion.siguiente no es null (con `todas`, "
                + "`funciones` o selección guardada: detalle_servicio {clase, paquete: 1}) → spec con los casos de casosPlan (solo funciones listadas, máx. "
                + "maxCasos, cada caso con queHaceOriginal y queComprueba) → generar_tests, que valida el spec (si devuelve errores, "
                + "corrige y repite; validar_spec es opcional; sigue la primera línea accionRequerida). generar_tests prueba esos tests en segundo plano y "
                + "responde enseguida: no esperes, no sondees ni llames a ejecutar_tests entre servicios; sigue con el siguiente. "
                + "No arregles nada durante la tanda: los casos que fallen se apuntan solos («N fallos apuntados para arreglar al "
                + "final») y se arreglan cuando estén generados todos los servicios (solo con `arreglos: inmediato` en junit-gen.yml "
                + "se corrigen al llegar la nota «Verificación en segundo plano…»). Nunca preguntes al usuario (ni con ask_user) qué "
                + "hacer con un test que falla, ni pongas como "
                + "esperado el valor obtenido sin justificarlo con el código. 3) Con plan.restantes = 0, ejecutar_tests sin "
                + "argumentos (repítela mientras devuelva verificando=true): si hay fallos apuntados abre la fase de arreglo: junit-gen "
                + "corrige solo lo mecánico y cada respuesta trae los casos fallidos del siguiente servicio (con su error, pistas, "
                + "spec y código); corrígelos en una llamada a arreglar_caso {casos: [{caso, spec, motivo}]} (fallo de aserción: con "
                + "motivo que cite el código si el test estaba mal planteado, o registrar_analisis_ia con bug_servicio si es un bug "
                + "del código; no rebajes el test). junit-gen recompila la clase una vez y prueba los casos al momento (máximo 3 "
                + "intentos; después los aparta solo). Sin casos pendientes devuelve respuestaFinal. junit-gen rechaza cerrar si en la "
                + "sesión no se ha generado nada, si el plan está incompleto (parcial=true solo si el usuario lo pidió) o si "
                + "carpetaTests no es la carpeta de la sesión. 4) Respuesta final: EXACTAMENTE el markdown de respuestaFinal de la "
                + "última respuesta de junit-gen que lo traiga, tal cual, sin añadir, quitar ni cambiar nada. Si una respuesta "
                + "empieza por accionRequerida «NO HAS TERMINADO: …» no escribas el mensaje final: haz ese paso. "
                + "Prohibido durante el flujo: terminal o lectura de archivos (proyecto, carpetas de salida, temporales) y carpetas "
                + "de salida de sesiones anteriores; si falta información, pídela a junit-gen (paginacion.siguiente, seccion, tipos, "
                + "paginaResultados). Regla de solo lectura: el proyecto analizado nunca se modifica; junit-gen solo escribe en la "
                + "carpeta de salida (y, si el usuario lo pide, guarda su ruta por defecto en rutas-por-defecto.json junto al JAR). "
                + "El agente no edita el proyecto, no hace operaciones git sobre él y no escribe .java de test: solo el spec; copiar "
                + "los tests al proyecto es un paso manual del usuario. Pasa en 'ruta' la raíz del workspace que ya conoces (nunca "
                + "un módulo). Sin 'carpetaSalida', junit-gen pregunta una sola vez «¿Dónde exportamos los tests?» y la recuerda. "
                + "junit-gen NO pregunta si probar los tests: se prueban siempre en segundo plano (compila y ejecuta en una carpeta "
                + "temporal de la salida, sin tocar el proyecto); no lo preguntes tú tampoco. Solo si "
                + "verificacion.decision=descartar (junit-gen.yml con `verificacion: nunca`, o el usuario pidió no ejecutarlos) no "
                + "se prueba nada: no llames a ejecutar_tests y muestra respuestaFinal. Las preguntas de junit-gen (carpeta de "
                + "salida y, solo con `verificacion: preguntar` o `fallosAsercion: preguntar` en junit-gen.yml, la de probar los "
                + "tests o la de fallos de aserción) caducan (~150 s): si una queda sin responder (lo indica accionRequerida, o "
                + "verificacion.decision=pendiente_usuario con `verificacion: preguntar`) NO decidas por el usuario; muestra respuestaFinal (con pendiente_usuario, llama antes a ejecutar_tests sin argumentos "
                + "para repetirla), y nunca digas que no se generaron tests si resumenSesion.casosGenerados > 0. "
                + "El progreso de la tanda se guarda en tanda.json de la carpeta de salida: si el usuario escribe `continuar` o si pierdes "
                + "el hilo (p. ej. en un chat nuevo con el contexto agotado, o contexto resumido), llama a escanear_proyecto {continuar: true} (sin numServicios, "
                + "servicios, funciones ni todas) y sigue su «Siguiente paso»: retoma por el siguiente servicio sin repetir nada. "
                + "Usa el prompt GenerarTest.";
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
            // Cierra la sesión: también cancela las verificaciones en segundo plano (espera acotada) antes del worker.
            sesion.cerrar();
            worker.shutdown();
            try {
                if (!worker.awaitTermination(15, TimeUnit.MINUTES)) {
                    worker.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                worker.shutdownNow();
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
        worker.submit(() -> {
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
        }
        // notifications/initialized y el resto: sin acción.
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
