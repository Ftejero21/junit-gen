package com.junitgen.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.junitgen.seguridad.SeguridadException;
import com.junitgen.util.Json;
import com.junitgen.util.LimitesRespuesta;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Registro de herramientas MCP y ejecución con el aviso de solo lectura como primera línea. */
public class RegistroHerramientas {

    private final Map<String, Herramienta> herramientas = new LinkedHashMap<>();

    public RegistroHerramientas() {
        registrar(new HerramientaEscanear());
        registrar(new HerramientaSeleccionar());
        registrar(new HerramientaDetalle());
        registrar(new HerramientaValidar());
        registrar(new HerramientaGenerar());
        registrar(new HerramientaArreglarCaso());
        registrar(new HerramientaEjecutar());
        registrar(new HerramientaEstadoVerificacion());
        registrar(new HerramientaResolverFallo());
        registrar(new HerramientaAnalisisIa());
        registrar(new HerramientaCambiarRuta());
    }

    private void registrar(Herramienta h) {
        herramientas.put(h.nombre(), h);
    }

    public Herramienta get(String nombre) {
        return herramientas.get(nombre);
    }

    public ObjectNode listar() {
        ObjectNode r = Json.objeto();
        ArrayNode tools = r.putArray("tools");
        for (Herramienta h : herramientas.values()) {
            ObjectNode t = tools.addObject();
            t.put("name", h.nombre());
            t.put("title", h.titulo());
            t.put("description", h.descripcion());
            t.set("inputSchema", h.esquema());
            ObjectNode a = t.putObject("annotations");
            a.put("title", h.titulo());
            if (h.soloLectura()) {
                a.put("readOnlyHint", true);
            } else {
                a.put("readOnlyHint", false);
                a.put("destructiveHint", false);
            }
            a.put("openWorldHint", false);
        }
        return r;
    }

    /** Ejecuta la herramienta; los fallos se devuelven como resultado con isError=true. */
    public ObjectNode llamar(Herramienta h, JsonNode args, SesionMcp sesion) {
        sesion.consumirNotas();
        if (!HerramientaEstadoVerificacion.NOMBRE.equals(h.nombre()) && !"ejecutar_tests".equals(h.nombre())) {
            // Nota de cabecera de las verificaciones en segundo plano (antes de ejecutar: detalle_servicio la descuenta).
            try {
                String nota = sesion.verificaciones().nota();
                if (nota != null) {
                    sesion.anotar(nota);
                }
            } catch (RuntimeException e) {
                System.err.println("junit-gen: no se pudo calcular la nota de verificación: " + e);
            }
        }
        long inicio = System.nanoTime();
        MedicionTanda.Cubo cubo = null;
        try {
            cubo = MedicionTanda.cubo(sesion);
        } catch (RuntimeException e) {
            System.err.println("junit-gen: no se pudo calcular el servicio de la medición: " + e.getClass().getSimpleName());
        }
        String texto;
        boolean error;
        try {
            Herramienta.Resultado r = h.ejecutar(args, sesion);
            texto = r.texto();
            error = r.error();
        } catch (SeguridadException e) {
            texto = "Rechazo de seguridad: " + e.getMessage();
            error = true;
        } catch (IllegalArgumentException | IllegalStateException e) {
            texto = "Error: " + e.getMessage();
            error = true;
        } catch (Exception e) {
            System.err.println("Error inesperado en " + h.nombre() + ": " + e);
            e.printStackTrace(System.err);
            texto = "Error inesperado en " + h.nombre() + ": " + e.getClass().getSimpleName() + ": " + e.getMessage();
            error = true;
        }
        // El aviso se calcula después de ejecutar para reflejar el proyecto y la salida ya resueltos.
        String aviso;
        try {
            aviso = h.aviso(args, sesion);
        } catch (RuntimeException e) {
            aviso = "Modo solo lectura sobre el proyecto indicado. Esta operación no crea archivos.";
        }
        // Línea «no pares» de la tanda (única para todas las herramientas); un fallo la omite y nunca rompe la respuesta.
        String accion;
        try {
            accion = GuiaTanda.cabecera(sesion, h.nombre(), args, error, texto);
        } catch (RuntimeException e) {
            System.err.println("junit-gen: no se pudo calcular la línea de acción de la tanda: " + e.getClass().getSimpleName()
                    + ": " + e.getMessage());
            accion = null;
        }
        ObjectNode res = Json.objeto();
        ArrayNode content = res.putArray("content");
        ObjectNode c = content.addObject();
        c.put("type", "text");
        String compuesto = componer(h.nombre(), accion, aviso, sesion.consumirNotas(), texto);
        c.put("text", compuesto);
        res.put("isError", error);
        if (cubo != null) {
            try {
                MedicionTanda.registrar(sesion, cubo, h.nombre(), error, LimitesRespuesta.bytes(compuesto), inicio);
            } catch (RuntimeException e) {
                System.err.println("junit-gen: no se pudo registrar la medición: " + e.getClass().getSimpleName());
            }
        }
        return res;
    }

    /**
     * Aviso + notas (recortadas para que la cabecera no supere {@link LimitesRespuesta#MAX_CABECERA}) + cuerpo.
     * Red de seguridad: si el texto supera {@link LimitesRespuesta#MAX_RESPUESTA}, recorta el cuerpo y lo
     * declara; se registra en stderr (sin contenido) porque es señal de un bug de presupuesto.
     */
    static String componer(String herramienta, String aviso, List<String> notas, String texto) {
        return componer(herramienta, null, aviso, notas, texto);
    }

    /**
     * Como {@link #componer(String, String, List, String)} con la línea de acción de la tanda ({@code accion}, null si no
     * hay) como primera línea: cuenta dentro de {@link LimitesRespuesta#MAX_CABECERA} (las notas se recortan a lo que
     * quede) y la red de seguridad nunca la recorta.
     */
    static String componer(String herramienta, String accion, String aviso, List<String> notas, String texto) {
        StringBuilder cab = new StringBuilder();
        if (accion != null) {
            cab.append(accion);
            if (aviso != null && !aviso.isEmpty()) {
                cab.append('\n');
            }
        }
        cab.append(aviso == null ? "" : aviso);
        for (String nota : notas) {
            String linea = "\nNota: " + nota;
            int libre = LimitesRespuesta.MAX_CABECERA - LimitesRespuesta.bytes(cab.toString());
            if (libre <= LimitesRespuesta.bytes("\nNota: ") + LimitesRespuesta.bytes(LimitesRespuesta.MARCA_RECORTE)) {
                break;
            }
            cab.append(LimitesRespuesta.recortar(linea, libre));
        }
        String cuerpo = texto == null ? "" : texto;
        String completo = cab + "\n\n" + cuerpo;
        int tam = LimitesRespuesta.bytes(completo);
        int max = LimitesRespuesta.maxRespuesta();
        if (tam <= max) {
            return completo;
        }
        System.err.println("junit-gen: la respuesta de " + herramienta + " ocupaba " + tam + " bytes; se recorta a "
                + max + " (red de seguridad: revisar el presupuesto de la herramienta).");
        String sufijo = "\n" + LimitesRespuesta.avisoRed();
        int libre = max - LimitesRespuesta.bytes(cab + "\n\n") - LimitesRespuesta.bytes(sufijo);
        String r = cab + "\n\n" + LimitesRespuesta.recortar(cuerpo, Math.max(0, libre)) + sufijo;
        if (LimitesRespuesta.bytes(r) > max) {
            r = LimitesRespuesta.recortar(r, max - LimitesRespuesta.bytes(sufijo)) + sufijo;
        }
        return r;
    }
}
