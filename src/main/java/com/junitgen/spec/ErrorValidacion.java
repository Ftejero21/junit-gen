package com.junitgen.spec;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** Error o aviso de validación del spec, con ruta JSON y sugerencia de corrección. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"nivel", "ruta", "codigo", "mensaje", "sugerencia"})
public class ErrorValidacion {

    public String nivel;
    public String ruta;
    public String codigo;
    public String mensaje;
    public String sugerencia;

    public ErrorValidacion() {
    }

    public ErrorValidacion(String nivel, String ruta, String codigo, String mensaje, String sugerencia) {
        this.nivel = nivel;
        this.ruta = ruta;
        this.codigo = codigo;
        this.mensaje = mensaje;
        this.sugerencia = sugerencia;
    }

    public static ErrorValidacion error(String ruta, String codigo, String mensaje, String sugerencia) {
        return new ErrorValidacion("error", ruta, codigo, mensaje, sugerencia);
    }

    public static ErrorValidacion aviso(String ruta, String codigo, String mensaje, String sugerencia) {
        return new ErrorValidacion("aviso", ruta, codigo, mensaje, sugerencia);
    }

    @Override
    public String toString() {
        return "[" + nivel + "] " + ruta + " (" + codigo + "): " + mensaje + (sugerencia != null ? " → " + sugerencia : "");
    }
}
