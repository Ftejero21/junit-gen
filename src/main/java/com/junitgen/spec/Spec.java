package com.junitgen.spec;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Spec JSON v1: lo único que produce la IA. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"version", "proyecto", "estilo", "servicios"})
public class Spec {
    public Integer version;
    public ProyectoSpec proyecto;
    public EstiloSpec estilo;
    public List<ServicioSpec> servicios = new ArrayList<>();
}
