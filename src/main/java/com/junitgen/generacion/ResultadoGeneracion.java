package com.junitgen.generacion;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.junitgen.duplicados.Duplicado;
import com.junitgen.spec.ErrorValidacion;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Resultado de generar_tests. */
@JsonPropertyOrder({"resumenSesion", "respuestaFinal", "archivosCreados", "archivosAmpliados", "casosGenerados", "descartados", "noReescritos",
        "requiereRefactor", "informe", "spec", "plan", "verificacion"})
public class ResultadoGeneracion {

    /** Cifras de la sesión calculadas por junit-gen (solo MCP). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public Map<String, Object> resumenSesion;
    /** Markdown final exacto que la IA muestra tal cual (solo MCP y solo si no habrá verificación). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public String respuestaFinal;
    public final List<String> archivosCreados = new ArrayList<>();
    public final List<String> archivosAmpliados = new ArrayList<>();
    public int casosGenerados;
    public final List<Duplicado> descartados = new ArrayList<>();
    public final List<String> noReescritos = new ArrayList<>();
    public final List<Map<String, Object>> requiereRefactor = new ArrayList<>();
    public String informe;
    public String spec;
    /** Progreso del plan de la sesión {total, generados, restantes (casos), servicios, metodos {total, completos}, pendientes, instruccion}; null sin plan. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public Map<String, Object> plan;
    public Map<String, String> verificacion = new LinkedHashMap<>();
    public final List<Map<String, String>> generados = new ArrayList<>();
    public final List<String> omitidos = new ArrayList<>();
    public final List<String> reemplazados = new ArrayList<>();
    public final List<String> eliminados = new ArrayList<>();
    public final List<String> noTocados = new ArrayList<>();
    public final List<String> perfiles = new ArrayList<>();
    public final List<ErrorValidacion> avisos = new ArrayList<>();
    public final List<ErrorValidacion> errores = new ArrayList<>();
    /** FQN del servicio → ids de los casos del archivo cuyo montaje se ha reescrito (se vuelven a probar). */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public final Map<String, List<String>> remontados = new LinkedHashMap<>();
    /**
     * Un bloque markdown por servicio con la selección de funciones (para informe.md): origen, elegidas y
     * omitidas, cada una con su motivo y origen. Solo contiene los servicios cuya selección es nueva o cambió
     * respecto a la última informada en esa carpeta de salida.
     */
    @JsonIgnore
    public final List<String> funcionesOmitidas = new ArrayList<>();
    /**
     * Un bloque markdown por caso reemplazado (solo para informe.md): origen o motivo del reemplazo y el
     * antes → después de su spec.
     */
    @JsonIgnore
    public final List<String> cambiosReemplazados = new ArrayList<>();
    /** Archivo creado o ampliado (relativo a la salida, con {@code /}) → ruta destino en el proyecto (para informe.md). */
    @JsonIgnore
    public final Map<String, String> destinosCopia = new LinkedHashMap<>();
    /** Id de caso → frase «qué comprueba» (solo para informe.md). */
    @JsonIgnore
    public final Map<String, String> queComprueba = new LinkedHashMap<>();
    /** Cambios aplicados a la parte común de un test existente (imports sustituidos, fixtures reescritas; arreglar_caso). */
    @JsonIgnore
    public final List<String> cambiosComunes = new ArrayList<>();
    /** Copias ampliadas que necesitan una comprobación (compilar y ejecutar) antes de escribirse; con alguna, no se escribe nada. */
    @JsonIgnore
    public final List<CopiaAmpliada.Pendiente> mezclasPendientes = new ArrayList<>();
    /** Una línea markdown por servicio con test existente en el proyecto (copia ampliada o motivo por el que no se mezcló). */
    @JsonIgnore
    public final List<String> mezclas = new ArrayList<>();
}
