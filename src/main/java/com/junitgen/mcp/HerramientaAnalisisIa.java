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
        return "Paso normal para todo fallo de aserción, sin preguntar al usuario (no uses ask_user): tras releer el código con "
                + "detalle_servicio {clase, metodos}, registra en UNA llamada el veredicto de cada caso (error_ia: el test estaba "
                + "mal planteado; bug_servicio: el código contradice lo que la función debe hacer; dudoso: solo si no puedes "
                + "decidir) con un motivo que cite el código (condición, cálculo o línea que produce el valor). bug_servicio → "
                + "posible_bug y dudoso → dudoso (el test se mantiene y el motivo va a informe.md); error_ia → devuelve 'corregir': "
                + "corrige esperado/aserciones del spec según el código y llama a generar_tests con reemplazarCasos (se vuelven a "
                + "probar solos; máximo " + VerificadorTests.MAX_INTENTOS + " intentos por caso; después queda dudoso). error_ia "
                + "autoriza un único reemplazo del caso en generar_tests. Prohibido poner como esperado el valor obtenido sin "
                + "justificarlo con el código. Solo admite casos en fallo_asercion. Devuelve respuestaFinal solo si no queda nada "
                + "pendiente; si no, accionRequerida «NO HAS TERMINADO: …» con el siguiente paso. Escribe spec.json y la sección "
                + "«Revisión de la IA» de informe.md; nunca toca el proyecto.";
    }

    @Override
    public ObjectNode esquema() {
        ObjectNode s = Herramienta.esquemaBase();
        ObjectNode props = (ObjectNode) s.get("properties");
        ObjectNode analisis = props.putObject("analisis");
        analisis.put("type", "array");
        analisis.put("description", "Veredictos, uno por caso (máximo " + VerificadorTests.MAX_ANALISIS + ", sin repetir casos).");
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
        ip.putObject("motivo").put("type", "string").put("description", "Motivo breve del veredicto (máximo "
                + VerificadorTests.MAX_MOTIVO + " caracteres).");
        item.putArray("required").add("caso").add("veredicto").add("motivo");
        item.put("additionalProperties", false);
        ((ArrayNode) s.get("required")).add("analisis");
        Herramienta.propiedad(s, "carpetaSalida", "string", "Carpeta de salida (por defecto, la elegida en la sesión).", false);
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
