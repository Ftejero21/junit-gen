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
        return "Cambia dónde se exportan los tests (/generartest ruta). Sin 'carpetaSalida' pregunta por elicitation en una "
                + "pantalla [Mantener la ruta por defecto: X] (si no hay, la del Escritorio) [" + Elicitacion.OTRA_RUTA + "]; solo "
                + "con «" + Elicitacion.OTRA_RUTA + "» (o sin opción que mantener) pide la ruta en una segunda pantalla con un único "
                + "campo; si el usuario escribe otra, pregunta [Solo esta vez] [Establecer como ruta por defecto]. Sin elicitation devuelve la instrucción para "
                + "preguntar en el chat; después llama de nuevo con 'carpetaSalida' y, si el usuario la establece, "
                + "guardarRutaPorDefecto=true. Nunca escribe en el proyecto ni lo escanea; la ruta se valida siempre fuera del "
                + "proyecto. Si el usuario no pidió nada más, informa del resultado y termina.";
    }

    @Override
    public ObjectNode esquema() {
        ObjectNode s = Herramienta.esquemaBase();
        Herramienta.propiedad(s, "ruta", "string", "Raíz del proyecto/workspace (por defecto, el de la sesión, la carpeta abierta "
                + "en el IDE o el directorio de trabajo del servidor).", false);
        Herramienta.propiedad(s, "carpetaSalida", "string", "Nueva carpeta de salida elegida por el usuario en el chat (fuera del "
                + "proyecto).", false);
        Herramienta.propiedad(s, "guardarRutaPorDefecto", "boolean", "Con carpetaSalida: el usuario eligió [Establecer como ruta por "
                + "defecto].", false);
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
