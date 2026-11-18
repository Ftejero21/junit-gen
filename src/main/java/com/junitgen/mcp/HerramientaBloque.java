package com.junitgen.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.junitgen.escaneo.PaqueteTrabajo;
import com.junitgen.generacion.Tanda;
import com.junitgen.util.Json;
import com.junitgen.util.LimitesRespuesta;
import com.junitgen.util.Recursos;
import com.junitgen.util.Textos;

import java.nio.file.Path;

/**
 * tomar_bloque: el trabajador de un bloque de la tanda en paralelo recibe (con {@code instrucciones}) las reglas de su trabajo y
 * el paquete de trabajo del siguiente servicio pendiente de su bloque. Solo lee: el estado del bloque se guarda en tanda.json con
 * la siguiente herramienta que guarde la tanda. Es idempotente: repetirla devuelve el servicio en curso.
 */
public class HerramientaBloque implements Herramienta {

    /** Margen de la respuesta que se deja libre al añadir el paquete de trabajo. */
    static final int MARGEN_PAQUETE = 200;
    static final String RECURSO_INSTRUCCIONES = "trabajador-bloque.md";

    @Override
    public String nombre() {
        return "tomar_bloque";
    }

    @Override
    public String titulo() {
        return "Tomar un bloque de la tanda";
    }

    @Override
    public String descripcion() {
        return "Solo en la tanda en paralelo: el trabajador de un bloque la llama al empezar (instrucciones: true) o si pierde el "
                + "hilo. Devuelve su bloque, el servicio en curso y su paquete de trabajo (candidatas, cuerpos, plantillaSpec). "
                + "Después: generar_tests por servicio, ejecutar_tests {bloque}, arreglar_caso y «BLOQUE K TERMINADO». Idempotente. "
                + "Si responde SIN_SESION_COMPARTIDA, responde solo eso. Sigue accionRequerida.";
    }

    @Override
    public ObjectNode esquema() {
        ObjectNode s = Herramienta.esquemaBase();
        Herramienta.propiedad(s, "bloque", "integer", "Id del bloque que te asignó el agente principal.", true);
        ((ObjectNode) s.get("properties").get("bloque")).put("minimum", 1);
        Herramienta.propiedad(s, "instrucciones", "boolean", "true solo la primera vez: añade las reglas del trabajador.", false);
        return s;
    }

    @Override
    public boolean soloLectura() {
        return true;
    }

    @Override
    public String aviso(JsonNode args, SesionMcp sesion) {
        return HerramientaDetalle.avisoDetalle(args, sesion);
    }

    @Override
    public Resultado ejecutar(JsonNode args, SesionMcp sesion) {
        Integer id = BloquesTanda.bloqueArg(args);
        if (id == null) {
            throw new IllegalArgumentException("Falta el argumento obligatorio 'bloque'.");
        }
        Path proyecto = sesion.fachada().ultimaRuta();
        Tanda t = proyecto == null ? null : sesion.tanda(proyecto);
        if (!BloquesTanda.activo(t)) {
            return new Resultado(BloquesTanda.SIN_SESION_COMPARTIDA + ": este proceso de junit-gen no tiene ninguna tanda en bloques "
                    + "(tu sesión MCP no es la del agente principal). No sigas: responde solo «" + BloquesTanda.SIN_SESION_COMPARTIDA
                    + "» y termina; el agente principal hará el bloque él mismo.", true);
        }
        Tanda.BloqueTanda bloque = BloquesTanda.de(t, id);
        if (bloque == null) {
            return new Resultado(BloquesTanda.errorBloqueInexistente(t, id), true);
        }
        sesion.fijarBloqueLlamada(id);
        Path salida = sesion.salidaDe(proyecto);
        if (Tanda.BLOQUE_TERMINADO.equals(bloque.estado)) {
            return new Resultado(Json.compacto(BloquesTanda.nodoTerminado(sesion, proyecto, salida, id, null)), false);
        }
        BloquesTanda.marcar(sesion, proyecto, id, Tanda.BLOQUE_EN_CURSO);
        // Cola de trabajo: si el bloque no tiene nada por generar, toma el siguiente servicio pendiente; se vuelve a leer.
        BloquesTanda.reclamar(sesion, proyecto, id);
        Tanda.BloqueTanda actualizado = BloquesTanda.de(t, id);
        if (actualizado != null) {
            bloque = actualizado;
        }
        if (Tanda.BLOQUE_TERMINADO.equals(bloque.estado)) {
            return new Resultado(Json.compacto(BloquesTanda.nodoTerminado(sesion, proyecto, salida, id, null)), false);
        }
        String actual = HerramientaGenerar.siguienteServicio(sesion, proyecto);
        ObjectNode r = Json.objeto();
        ObjectNode b = r.putObject("bloque");
        b.put("id", id);
        ArrayNode servicios = b.putArray("servicios");
        bloque.servicios.forEach(fqn -> servicios.add(Textos.nombreSimple(fqn)));
        int enCola = BloquesTanda.enCola(sesion, proyecto);
        if (enCola > 0) {
            b.put("enCola", enCola);
        }
        if (actual == null) {
            b.put("generado", true);
        } else {
            b.put("servicioEnCurso", Textos.nombreSimple(actual));
        }
        if (Herramienta.bool(args, "instrucciones")) {
            r.put("instrucciones", Recursos.texto(RECURSO_INSTRUCCIONES));
        }
        if (actual != null) {
            // Repetir tomar_bloque vuelve a enviar todos los tipos auxiliares del servicio en curso.
            sesion.olvidarTiposEnviados(proyecto, id);
            adjuntarPaquete(args, sesion, proyecto, salida, actual, r);
        }
        return new Resultado(Json.compacto(r), false);
    }

    /** Añade {@code paquete} si cabe (con las instrucciones puede no caber con el límite por defecto: se repite sin ellas). */
    private static void adjuntarPaquete(JsonNode args, SesionMcp sesion, Path proyecto, Path salida, String fqn, ObjectNode r) {
        int presupuesto = Math.min(HerramientaDetalle.presupuestoPaquete(args, sesion), LimitesRespuesta.maxRespuesta()
                - LimitesRespuesta.MAX_CABECERA - LimitesRespuesta.bytes(Json.compacto(r)) - MARGEN_PAQUETE);
        if (presupuesto < PaqueteTrabajo.MIN_PAGINA) {
            return;
        }
        try {
            r.set("paquete", BloquesTanda.paquete(sesion, proyecto, salida, fqn, presupuesto, true));
        } catch (RuntimeException e) {
            System.err.println("tomar_bloque: no se pudo adjuntar el paquete de trabajo del servicio: " + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage()));
        }
    }
}
