package com.junitgen.spec;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Stub estático con mockStatic. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EstaticoSpec {
    public String clase;
    public String llamada;
    public String devuelve;
    public String lanza;
}
