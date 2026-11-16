package com.junitgen.spec;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.ArrayList;
import java.util.List;

/**
 * Funciones elegidas de un servicio: {@code origen} ({@code ia}, {@code usuario}, {@code java} o
 * {@code todas}), las elegidas y las omitidas, cada una con su motivo. Con MCP la fija seleccionar_funciones;
 * sin MCP llega en el spec. junit-gen escribe siempre la selección usada; solo se recarga la de origen ia.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"origen", "funciones", "omitidas"})
public class SeleccionSpec {

    public String origen;
    public List<Entrada> funciones = new ArrayList<>();
    public List<Entrada> omitidas;

    /** Función por nombre (todas sus sobrecargas) o por clave {@code nombre(Tipos)}, con su motivo. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"nombre", "motivo"})
    public static class Entrada {
        public String nombre;
        public String motivo;

        public Entrada() {
        }

        public Entrada(String nombre, String motivo) {
            this.nombre = nombre;
            this.motivo = motivo;
        }
    }
}
