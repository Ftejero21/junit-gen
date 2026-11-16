package com.junitgen.spec;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Proyecto y módulo al que se refiere el spec. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ProyectoSpec {
    public String ruta;
    public String modulo;
}
