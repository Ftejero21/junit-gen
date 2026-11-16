package com.junitgen.duplicados;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** Caso descartado por duplicado. Nivel 1: tests del proyecto; 2: dentro del spec; 3: generado antes. */
@JsonPropertyOrder({"caso", "servicio", "nivel", "motivo", "coincideCon"})
public class Duplicado {

    /** Nivel de duplicado: el caso ya está generado en la carpeta de salida (otro id). */
    public static final int NIVEL_SALIDA = 3;

    public String caso;
    public String servicio;
    public int nivel;
    public String motivo;
    public String coincideCon;

    public Duplicado() {
    }

    public Duplicado(String caso, String servicio, int nivel, String motivo, String coincideCon) {
        this.caso = caso;
        this.servicio = servicio;
        this.nivel = nivel;
        this.motivo = motivo;
        this.coincideCon = coincideCon;
    }
}
