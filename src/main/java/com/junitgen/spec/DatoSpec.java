package com.junitgen.spec;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Variable local de entrada. mock=true genera mock(Tipo.class). */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"tipo", "nombre", "valor", "mock"})
public class DatoSpec {
    public String tipo;
    public String nombre;
    public String valor;
    public Boolean mock;
}
