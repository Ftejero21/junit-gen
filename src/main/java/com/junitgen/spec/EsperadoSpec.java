package com.junitgen.spec;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Resultado esperado: valor | excepcion | nulo | noNulo | verdadero | falso | sinRetorno | aserciones. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"tipo", "valor", "excepcion", "mensaje", "mensajeContiene"})
public class EsperadoSpec {
    public String tipo;
    public String valor;
    public String excepcion;
    public String mensaje;
    public String mensajeContiene;
}
