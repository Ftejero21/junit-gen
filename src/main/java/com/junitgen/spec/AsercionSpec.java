package com.junitgen.spec;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Aserción adicional: igual, noIgual, nulo, noNulo, verdadero, falso, mismo, tamano, vacio, contiene. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"real", "tipo", "esperado"})
public class AsercionSpec {
    public String real;
    public String tipo;
    public String esperado;
}
