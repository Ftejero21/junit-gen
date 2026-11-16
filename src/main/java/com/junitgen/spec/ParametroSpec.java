package com.junitgen.spec;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Parámetro de una fixture. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ParametroSpec {
    public String tipo;
    public String nombre;
}
