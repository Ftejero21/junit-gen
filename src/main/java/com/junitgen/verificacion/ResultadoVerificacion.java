package com.junitgen.verificacion;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Resultado de ejecutar_tests. */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonPropertyOrder({"resumenSesion", "respuestaFinal", "jdkUsado", "resumen", "resultadosDeOtrasSesiones", "resultados", "accionRequerida", "asercionesPendientes", "montajeReintentables", "revisionIaPendiente", "alertaIntegridad", "avisos", "decisiones"})
public class ResultadoVerificacion {

    /** Cifras de la sesión calculadas por junit-gen (solo MCP). */
    public Map<String, Object> resumenSesion;
    /** Markdown final exacto que la IA muestra tal cual (solo MCP). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public String respuestaFinal;
    /** Resultados de casos de sesiones anteriores (no cuentan en resumenSesion). */
    public Integer resultadosDeOtrasSesiones;
    public String jdkUsado;
    public String resumen;
    public final List<ResultadoTest> resultados = new ArrayList<>();
    public String accionRequerida;
    public List<String> alertaIntegridad;
    public final List<String> avisos = new ArrayList<>();
    public final Map<String, String> versionesReales = new LinkedHashMap<>();
    public final List<Map<String, String>> decisiones = new ArrayList<>();
    /** Casos con fallo de aserción pendientes de decisión del usuario (no incluye los ya marcados posible_bug). */
    public final List<String> asercionesPendientes = new ArrayList<>();
    /** Casos con error de montaje o de compilación que aún admiten otro intento (menos de 3). */
    public final List<String> montajeReintentables = new ArrayList<>();
    /** Casos corregidos tras la revisión de la IA (veredicto error_ia) que vuelven a fallar: la IA repite el análisis. */
    public final List<String> revisionIaPendiente = new ArrayList<>();
    public String informe;
    /** true si los tests cambiaron durante la verificación: no se escribió spec ni informe y debe repetirse. */
    @JsonIgnore
    public boolean descartada;
    /** Mayor intento de montaje (1..2) de los casos reintentables de esta verificación; 0 si no hay. */
    @JsonIgnore
    public int intentoMontaje;
    /** Frases «`ClaseTest`: cambio» de los imports corregidos automáticamente en esta verificación (informe.md). */
    @JsonIgnore
    public final List<String> importsReparados = new ArrayList<>();

    public long contar(String estado) {
        return resultados.stream().filter(r -> estado.equals(r.estado)).count();
    }

    public boolean todoPasa() {
        return !resultados.isEmpty() && resultados.stream().allMatch(r -> ClasificadorFallos.PASA.equals(r.estado));
    }
}
