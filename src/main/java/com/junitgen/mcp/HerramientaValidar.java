package com.junitgen.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.junitgen.seguridad.RutaSegura;
import com.junitgen.spec.LectorSpec;
import com.junitgen.spec.ResultadoValidacion;
import com.junitgen.util.Json;
import com.junitgen.util.LimitesRespuesta;
import com.junitgen.util.Recursos;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/** validar_spec: errores claros y duplicados (3 niveles). No crea archivos. */
public class HerramientaValidar implements Herramienta {

    @Override
    public String nombre() {
        return "validar_spec";
    }

    @Override
    public String titulo() {
        return "Validar spec";
    }

    @Override
    public String descripcion() {
        return "Valida el spec contra el proyecto (estructura, tipos, versiones y duplicados) sin escribir nada. Opcional: "
                + "generar_tests valida igual.";
    }

    @Override
    public ObjectNode esquema() {
        ObjectNode s = Herramienta.esquemaBase();
        ObjectNode props = (ObjectNode) s.get("properties");
        ObjectNode spec = props.putObject("spec");
        spec.put("type", "object");
        spec.put("description", "El mismo spec que generar_tests.");
        Herramienta.propiedad(s, "rutaSpec", "string", "Alternativa: archivo con el spec.", false);
        Herramienta.propiedad(s, "ruta", "string", "Ruta del proyecto (por defecto, la de la sesión).", false);
        Herramienta.propiedad(s, "carpetaSalida", "string", "Carpeta de salida; por defecto, la de la sesión.", false);
        return s;
    }

    @Override
    public boolean soloLectura() {
        return true;
    }

    @Override
    public String aviso(JsonNode args, SesionMcp sesion) {
        return RutasSesion.aviso(args, sesion, "ruta", null, "(proyecto del spec)");
    }

    @Override
    public Resultado ejecutar(JsonNode args, SesionMcp sesion) {
        JsonNode spec = args == null ? null : args.get("spec");
        Path rutaSpec = Herramienta.ruta(args, "rutaSpec");
        Path ruta = Herramienta.ruta(args, "ruta");
        Path salida = Herramienta.ruta(args, "carpetaSalida");
        LectorSpec.Lectura lectura = sesion.fachada().leerSpec(spec, rutaSpec);
        if (lectura.ok()) {
            // Proyecto conocido (argumento, spec, último escaneo o carpeta del IDE) y salida recordada; nunca pregunta.
            ruta = RutaSegura.validarProyecto(RutasSesion.proyecto(args, sesion, "ruta", false));
            if (salida == null) {
                salida = sesion.salidaDe(ruta);
            }
        }
        ResultadoValidacion r = sesion.fachada().validar(spec, rutaSpec, ruta, salida, sesion.todas(ruta), sesion.selecciones(ruta));
        return new Resultado(Json.compacto(vistaCompacta(r)), false);
    }

    static final List<String> ORDEN_RECORTE = List.of("avisos", "duplicados", "errores");
    static final String COMO_VER_RESTO = "Corrige los errores mostrados y vuelve a llamar a validar_spec: aparecerán los siguientes.";

    /** valido + totales {errores, avisos, duplicados} + listas, acotado al presupuesto. */
    static ObjectNode vistaCompacta(ResultadoValidacion r) {
        ObjectNode arbol = Json.MAPPER.valueToTree(r);
        ObjectNode v = Json.objeto();
        v.set("valido", arbol.remove("valido"));
        ObjectNode totales = v.putObject("totales");
        totales.put("errores", r.errores.size());
        totales.put("avisos", r.avisos.size());
        totales.put("duplicados", r.duplicados.size());
        v.setAll(arbol);
        LimitesRespuesta.acotarJson(v, LimitesRespuesta.PRESUPUESTO_CUERPO, ORDEN_RECORTE, Set.of("errores"), COMO_VER_RESTO);
        return v;
    }
}
