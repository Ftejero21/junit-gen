package com.junitgen.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.junitgen.Fachada;
import com.junitgen.seguridad.RutaSegura;
import com.junitgen.util.Json;
import com.junitgen.util.LimitesRespuesta;
import com.junitgen.verificacion.VerificadorTests;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** registrar_analisis_ia: registra en lote los veredictos de la IA sobre casos con fallo de aserción (revisión sin preguntar al usuario). */
public class HerramientaAnalisisIa implements Herramienta {

    @Override
    public String nombre() {
        return "registrar_analisis_ia";
    }

    @Override
    public String titulo() {
        return "Registrar el análisis de la IA";
    }

    @Override
    public String descripcion() {
        return "Registra en UNA llamada, sin preguntar al usuario, el veredicto de cada caso con fallo de aserción tras releer el "
                + "código: error_ia (test mal planteado), bug_servicio (el código contradice lo que la función "
                + "debe hacer) o dudoso (si no puedes decidir), con motivo que cite el código. error_ia devuelve 'corregir': "
                + "corrige esperado/aserciones y llama a generar_tests con reemplazarCasos (máx. " + VerificadorTests.MAX_INTENTOS
                + " intentos). No pongas como esperado el valor obtenido sin justificarlo. Sigue accionRequerida.";
    }

    @Override
    public ObjectNode esquema() {
        ObjectNode s = Herramienta.esquemaBase();
        ObjectNode props = (ObjectNode) s.get("properties");
        ObjectNode analisis = props.putObject("analisis");
        analisis.put("type", "array");
        analisis.put("description", "Veredictos, uno por caso (máx. " + VerificadorTests.MAX_ANALISIS + ", sin repetir).");
        analisis.put("minItems", 1);
        analisis.put("maxItems", VerificadorTests.MAX_ANALISIS);
        ObjectNode item = analisis.putObject("items");
        item.put("type", "object");
        ObjectNode ip = item.putObject("properties");
        ip.putObject("caso").put("type", "string").put("description", "Id del caso con fallo de aserción.");
        ObjectNode veredicto = ip.putObject("veredicto");
        veredicto.put("type", "string");
        veredicto.put("description", "error_ia | bug_servicio | dudoso");
        ArrayNode en = veredicto.putArray("enum");
        VerificadorTests.VEREDICTOS.forEach(en::add);
        ip.putObject("motivo").put("type", "string").put("description", "Motivo breve que cita el código (máx. "
                + VerificadorTests.MAX_MOTIVO + " caracteres).");
        item.putArray("required").add("caso").add("veredicto").add("motivo");
        item.put("additionalProperties", false);
        ((ArrayNode) s.get("required")).add("analisis");
        Herramienta.propiedad(s, "carpetaSalida", "string", "Carpeta de salida; por defecto, la de la sesión.", false);
        return s;
    }

    @Override
    public boolean soloLectura() {
        return false;
    }

    @Override
    public String aviso(JsonNode args, SesionMcp sesion) {
        Path salida = HerramientaResolverFallo.salidaConocida(args, sesion);
        Path proyecto;
        try {
            proyecto = salida == null ? null : Fachada.proyectoDeSalida(salida);
        } catch (RuntimeException e) {
            proyecto = null;
        }
        return Fachada.aviso(proyecto == null ? Path.of("(proyecto de spec.json)") : proyecto, salida);
    }

    @Override
    public Resultado ejecutar(JsonNode args, SesionMcp sesion) {
        Path salida = HerramientaResolverFallo.salidaConocida(args, sesion);
        if (salida == null) {
            throw new IllegalArgumentException("Falta 'carpetaSalida' y no hay ninguna carpeta de salida elegida en esta sesión: "
                    + "indica la carpeta de salida donde se generaron los tests.");
        }
        Map<String, Object> r = sesion.fachada().registrarAnalisisIa(salida, leer(args));
        Path proyecto = sesion.fachada().ultimaRuta();
        Path salidaReal = RutaSegura.normalizar(salida);
        sesion.guardarTanda(proyecto, salidaReal);
        HerramientaEjecutar.sincronizarRevisiones(sesion, salidaReal);
        if (GuiaTanda.arreglosAlFinal(sesion, proyecto)) {
            ArreglosFinal.sincronizar(sesion, proyecto, salidaReal, null);
            if (ArreglosFinal.enFase(sesion, proyecto)) {
                // Fase de arreglo: devuelve el siguiente caso; el cierre (respuestaFinal) pasa siempre por ejecutar_tests.
                sesion.guardarTanda(proyecto, salidaReal);
                ObjectNode anterior = Json.objeto();
                anterior.set("resultados", Json.MAPPER.valueToTree(r.get("resultados")));
                return new Resultado(Json.compacto(ArreglosFinal.respuesta(sesion, proyecto, salidaReal, anterior)), false);
            }
        }
        ObjectNode arbol = Json.MAPPER.valueToTree(r);
        // La instrucción de VerificadorTests da por terminado el trabajo; el siguiente paso lo decide conCierre.
        arbol.remove("instruccion");
        ObjectNode res = HerramientaEjecutar.conCierre(sesion, proyecto, salidaReal, arbol);
        if (res.has("respuestaFinal")) {
            res.put("instruccion", NADA_PENDIENTE);
            cerrarTandaSiProcede(sesion, proyecto, salidaReal);
        }
        LimitesRespuesta.acotarJson(res, LimitesRespuesta.PRESUPUESTO_CUERPO, List.of(), "El detalle completo está en la sección "
                + "«Revisión de la IA» de informe.md.");
        return new Resultado(Json.compacto(res), false);
    }

    static final String NADA_PENDIENTE = "No queda nada pendiente: tu mensaje final es EXACTAMENTE respuestaFinal, tal cual; no "
            + "muestres la tabla «Revisión de la IA» (queda en informe.md).";

    /**
     * Sin nada pendiente tras {@link HerramientaEjecutar#conCierre}: si el plan está completo (o no hay plan) y no quedan
     * preguntas de aserciones sin responder, guarda la tanda como cerrada.
     */
    static void cerrarTandaSiProcede(SesionMcp sesion, Path proyecto, Path salidaReal) {
        SesionMcp.ProgresoPlan pp = sesion.progresoPlan(proyecto);
        if ((pp == null || pp.restantes() == 0) && sesion.asercionesSinRespuesta(salidaReal) == 0) {
            MedicionTanda.informar(sesion, proyecto, salidaReal);
            sesion.guardarTanda(proyecto, salidaReal, Boolean.TRUE);
        }
    }

    /** Lee 'analisis' (array de objetos, o cadena con el JSON) sin interpretar nada más; la validación es de VerificadorTests. */
    static List<VerificadorTests.AnalisisIa> leer(JsonNode args) {
        JsonNode a = args == null ? null : args.get("analisis");
        if (a != null && a.isTextual()) {
            a = Json.leer(a.asText());
        }
        if (a == null || !a.isArray() || a.isEmpty()) {
            throw new IllegalArgumentException("Falta 'analisis': lista de {caso, veredicto, motivo}.");
        }
        if (a.size() > VerificadorTests.MAX_ANALISIS) {
            throw new IllegalArgumentException("'analisis' admite como máximo " + VerificadorTests.MAX_ANALISIS + " entradas por llamada.");
        }
        List<VerificadorTests.AnalisisIa> r = new ArrayList<>();
        for (JsonNode x : a) {
            if (!x.isObject()) {
                throw new IllegalArgumentException("Cada entrada de 'analisis' debe ser un objeto {caso, veredicto, motivo}.");
            }
            r.add(new VerificadorTests.AnalisisIa(texto(x, "caso"), texto(x, "veredicto"), texto(x, "motivo")));
        }
        return r;
    }

    private static String texto(JsonNode x, String campo) {
        JsonNode v = x.get(campo);
        return v == null || v.isNull() || !v.isValueNode() ? null : v.asText();
    }
}
