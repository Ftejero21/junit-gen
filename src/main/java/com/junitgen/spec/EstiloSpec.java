package com.junitgen.spec;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Estilo de los tests generados (deducido de los tests de ejemplo). */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"patronNombre", "displayName", "comentariosGwt", "aserciones", "visibilidadClase", "nombreCampoSut"})
public class EstiloSpec {
    public String patronNombre;
    public Boolean displayName;
    public Boolean comentariosGwt;
    /** junit | assertj | auto */
    public String aserciones;
    /** package | public */
    public String visibilidadClase;
    public String nombreCampoSut;
}
