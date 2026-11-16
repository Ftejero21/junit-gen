package com.junitgen.spec;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Método helper "private static" que construye un objeto. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"nombre", "tipo", "parametros", "pasos", "devuelve"})
public class FixtureSpec {
    public String nombre;
    public String tipo;
    public List<ParametroSpec> parametros = new ArrayList<>();
    public List<String> pasos = new ArrayList<>();
    public String devuelve;
}
