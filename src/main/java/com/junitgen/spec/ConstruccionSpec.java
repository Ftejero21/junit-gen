package com.junitgen.spec;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Montaje del servicio: valores o instancias reales ({@code argumentos}), stubs previos a construir
 * ({@code stubs}), mocks con deep stubs ({@code profundos}) e invocación de {@code @PostConstruct}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"argumentos", "stubs", "profundos", "postConstruct"})
public class ConstruccionSpec {
    public Map<String, String> argumentos = new LinkedHashMap<>();
    /** Stubs lenient que se ejecutan en setUp antes de construir el servicio. */
    public List<StubSpec> stubs;
    /** Nombres de mocks que se declaran con RETURNS_DEEP_STUBS. */
    public List<String> profundos;
    /** true para invocar los métodos @PostConstruct tras montar. */
    public Boolean postConstruct;
}
