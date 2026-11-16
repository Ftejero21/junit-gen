package com.junitgen.spec;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Verificación de interacción con un mock. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"mock", "llamada", "veces", "sinInteracciones"})
public class VerificacionSpec {
    public String mock;
    public String llamada;
    public Integer veces;
    public Boolean sinInteracciones;
}
