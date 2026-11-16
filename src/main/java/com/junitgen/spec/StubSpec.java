package com.junitgen.spec;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Stub de una dependencia: devuelve | devuelveSecuencia | lanza | responde. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"mock", "llamada", "devuelve", "devuelveSecuencia", "lanza", "responde", "forma"})
public class StubSpec {
    public String mock;
    public String llamada;
    public String devuelve;
    public List<String> devuelveSecuencia;
    public String lanza;
    public String responde;
    /** auto | when | do */
    public String forma;
}
