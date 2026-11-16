package com.junitgen.verificacion;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** Resultado de un test generado: pasa | fallo_asercion | error_montaje | no_compila | desconocido. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"caso", "clase", "metodoTest", "estado", "deEstaSesion", "tipoError", "mensaje", "pista", "traza"})
public class ResultadoTest {

    public String caso;
    public String clase;
    public String metodoTest;
    public String estado;
    /** true si el caso se generó en esta sesión; los de sesiones anteriores no cuentan en resumenSesion. */
    public Boolean deEstaSesion;
    public String tipoError;
    public String mensaje;
    /**
     * Pista de diagnóstico: para errores de montaje, ClasificadorFallos.pista; para no_compila, firmas
     * reales de constructores/métodos y detalle de javac (PistasCompilacion). null si no hay.
     */
    public String pista;
    public String traza;
    /** true = no compila por un error fuera de los casos (cabecera, imports, campos, montaje común). */
    @JsonIgnore
    public boolean errorComun;
    /** Última «Caused by» de la traza completa (con su primer marco), o null. */
    @JsonIgnore
    public String causa;
}
