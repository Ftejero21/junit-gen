package com.junitgen.spec;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Un caso de prueba. Los campos huella, huellaEntrada, estado, intentosMontaje, veredictoIa, motivoIa y
 * autorizacionCambio los añade la herramienta (los del spec de entrada se ignoran o sobrescriben).
 * autorizacionCambio permite reemplazar una sola vez un caso protegido (ver {@link #ESTADOS_PROTEGIDOS}).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"id", "metodo", "parametros", "escenario", "descripcion", "queHaceOriginal", "queComprueba", "datos", "preparacion", "stubs",
        "estaticos", "construcciones", "argumentos", "esperado", "aserciones", "verificaciones",
        "huella", "huellaEntrada", "estado", "intentosMontaje", "veredictoIa", "motivoIa", "autorizacionCambio"})
public class CasoSpec {
    /** El usuario eligió «Ajustar lo esperado» (pregunta de aserciones con `fallosAsercion: preguntar` o resolver_fallo confirmado). */
    public static final String AUTORIZACION_AJUSTAR = "ajustar";
    /** Veredicto error_ia de la revisión de la IA (registrar_analisis_ia) dentro del límite de intentos. */
    public static final String AUTORIZACION_ERROR_IA = "error_ia";
    public static final String ESTADO_FALLO_ASERCION = "fallo_asercion";
    /** Estados que exigen decisión del usuario (o veredicto error_ia) para reemplazar o eliminar el caso. */
    public static final Set<String> ESTADOS_PROTEGIDOS = Set.of(ESTADO_FALLO_ASERCION, "posible_bug", "dudoso");

    public String id;
    public String metodo;
    public List<String> parametros;
    public String escenario;
    public String descripcion;
    /** Qué hace la función original (1 frase; comentario del test). Obligatorio en casos nuevos. */
    public String queHaceOriginal;
    /** Qué comprueba este test (1 frase; comentario del test). Obligatorio en casos nuevos. */
    public String queComprueba;
    public List<DatoSpec> datos = new ArrayList<>();
    public List<String> preparacion = new ArrayList<>();
    public List<StubSpec> stubs = new ArrayList<>();
    public List<EstaticoSpec> estaticos = new ArrayList<>();
    public List<ConstruccionMockSpec> construcciones = new ArrayList<>();
    public List<String> argumentos = new ArrayList<>();
    public EsperadoSpec esperado;
    public List<AsercionSpec> aserciones = new ArrayList<>();
    public List<VerificacionSpec> verificaciones = new ArrayList<>();
    public String huella;
    public String huellaEntrada;
    public String estado;
    public Integer intentosMontaje;
    /** Último veredicto de la revisión de la IA (registrar_analisis_ia: error_ia | bug_servicio | dudoso). */
    public String veredictoIa;
    /** Motivo del último veredicto de la revisión de la IA (saneado, lo añade la herramienta). */
    public String motivoIa;
    /** Autorización de un único reemplazo de un caso protegido (ajustar | error_ia); se consume al reemplazar. */
    public String autorizacionCambio;

    /** El caso está en un estado protegido (fallo de aserción, posible bug o dudoso). */
    @JsonIgnore
    public boolean protegido() {
        return estado != null && ESTADOS_PROTEGIDOS.contains(estado);
    }
}
