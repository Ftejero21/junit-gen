package com.junitgen.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.junitgen.seguridad.RutaSegura;

import java.nio.file.Path;

/** cambiar_ruta_salida: cambia la carpeta de salida de la sesión o la ruta por defecto del proyecto (no escanea). */
public class HerramientaCambiarRuta implements Herramienta {

    @Override
    public String nombre() {
        return "cambiar_ruta_salida";
    }

    @Override
    public String titulo() {
        return "Cambiar la ruta de salida";
    }

    @Override
    public String descripcion() {
        return "Cambia dónde se exportan los tests (/generartest ruta). Sin 'carpetaSalida' pregunta al usuario; después llama "
                + "con 'carpetaSalida' (y guardarRutaPorDefecto=true si la establece como ruta por defecto). Nunca escribe en el "
                + "proyecto. Sigue accionRequerida.";
    }

    @Override
    public ObjectNode esquema() {
        ObjectNode s = Herramienta.esquemaBase();
        Herramienta.propiedad(s, "ruta", "string", "Raíz del workspace; por defecto, la de la sesión.", false);
        Herramienta.propiedad(s, "carpetaSalida", "string", "Nueva carpeta de salida elegida por el usuario (fuera del proyecto).", false);
        Herramienta.propiedad(s, "guardarRutaPorDefecto", "boolean", "El usuario la estableció como ruta por defecto.", false);
        return s;
    }

    @Override
    public boolean soloLectura() {
        return false;
    }

    @Override
    public String aviso(JsonNode args, SesionMcp sesion) {
        return RutasSesion.aviso(args, sesion, "ruta", "carpetaSalida", "(carpeta abierta en el IDE)");
    }

    @Override
    public Resultado ejecutar(JsonNode args, SesionMcp sesion) {
        Path proyecto = RutaSegura.validarProyecto(RutasSesion.proyecto(args, sesion, "ruta", true));
        String texto = RutasSesion.cambiarSalida(proyecto, Herramienta.ruta(args, "carpetaSalida"),
                Herramienta.bool(args, "guardarRutaPorDefecto"), sesion);
        return new Resultado(texto, false);
    }
}
