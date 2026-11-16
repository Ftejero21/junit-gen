package com.junitgen.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.junitgen.Fachada;
import com.junitgen.seguridad.RutaSegura;
import com.junitgen.util.Json;
import com.junitgen.util.LimitesRespuesta;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** resolver_fallo: aplica la decisión del usuario sobre un fallo de aserción. */
public class HerramientaResolverFallo implements Herramienta {

    @Override
    public String nombre() {
        return "resolver_fallo";
    }

    @Override
    public String titulo() {
        return "Resolver un fallo de aserción";
    }

    @Override
    public String descripcion() {
        return "Aplica la decisión del usuario sobre un caso con fallo de aserción, solo tras su respuesta explícita: 'bug' lo "
                + "mantiene como posible bug; 'ajustar' autoriza reemplazarlo una vez con generar_tests; 'eliminar' lo quita (ambos "
                + "exigen confirmadoPorUsuario). Por defecto usa registrar_analisis_ia. Sigue accionRequerida.";
    }

    @Override
    public ObjectNode esquema() {
        ObjectNode s = Herramienta.esquemaBase();
        Herramienta.propiedad(s, "carpetaSalida", "string", "Carpeta de salida; por defecto, la de la sesión.", false);
        Herramienta.propiedad(s, "caso", "string", "Id del caso.", true);
        Herramienta.propiedad(s, "decision", "string", "bug | ajustar | eliminar", true);
        ((ObjectNode) s.get("properties").get("decision")).putArray("enum").add("bug").add("ajustar").add("eliminar");
        Herramienta.propiedad(s, "confirmadoPorUsuario", "boolean", "Obligatoria para ajustar y eliminar; solo tras respuesta explícita del usuario.", false);
        return s;
    }

    @Override
    public boolean soloLectura() {
        return false;
    }

    @Override
    public String aviso(JsonNode args, SesionMcp sesion) {
        Path salida = salidaConocida(args, sesion);
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
        Path salida = salidaConocida(args, sesion);
        if (salida == null) {
            throw new IllegalArgumentException("Falta 'carpetaSalida' y no hay ninguna carpeta de salida elegida en esta sesión: "
                    + "indica la carpeta de salida donde se generaron los tests.");
        }
        String caso = Herramienta.obligatorio(args, "caso");
        Map<String, Object> r = sesion.fachada().resolverFallo(salida, caso, Herramienta.obligatorio(args, "decision"),
                Herramienta.bool(args, "confirmadoPorUsuario"));
        if ("eliminado".equals(r.get("estado"))) {
            sesion.olvidarCaso(RutaSegura.normalizar(salida), caso);
        }
        Path proyecto = sesion.fachada().ultimaRuta();
        Path salidaReal = RutaSegura.normalizar(salida);
        sesion.resolverAsercionesSinRespuesta(salidaReal, List.of(caso));
        sesion.guardarTanda(proyecto, salidaReal);
        HerramientaEjecutar.sincronizarRevisiones(sesion, salidaReal);
        if (GuiaTanda.arreglosAlFinal(sesion, proyecto)) {
            ArreglosFinal.sincronizar(sesion, proyecto, salidaReal, null);
        }
        ObjectNode res = HerramientaEjecutar.conCierre(sesion, proyecto, salidaReal, Json.MAPPER.valueToTree(r));
        if (res.has("respuestaFinal")) {
            HerramientaAnalisisIa.cerrarTandaSiProcede(sesion, proyecto, salidaReal);
        }
        LimitesRespuesta.acotarJson(res, LimitesRespuesta.PRESUPUESTO_CUERPO, List.of(), "El detalle completo está en informe.md.");
        return new Resultado(Json.compacto(res), false);
    }

    /** carpetaSalida → la recordada para el último proyecto escaneado → null. */
    static Path salidaConocida(JsonNode args, SesionMcp sesion) {
        Path salida = Herramienta.ruta(args, "carpetaSalida");
        return salida != null ? salida : sesion.salidaDe(sesion.fachada().ultimaRuta());
    }
}
