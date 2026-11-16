package com.junitgen.spec;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** mockConstruction de un tipo creado con new dentro del servicio. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ConstruccionMockSpec {
    public String clase;
    public List<StubSpec> stubs = new ArrayList<>();
}
