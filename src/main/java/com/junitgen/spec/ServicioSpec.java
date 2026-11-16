package com.junitgen.spec;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Casos de un servicio. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"clase", "modulo", "claseTest", "seleccion", "seleccionInformada", "imports", "construccion", "fixtures", "casos"})
public class ServicioSpec {
    public String clase;
    /** Opcional: módulo del servicio (ruta relativa o nombre); solo hace falta si la misma clase está en varios módulos. */
    public String modulo;
    public String claseTest;
    /** Funciones elegidas (solo sin MCP como entrada; junit-gen escribe aquí la selección usada). */
    public SeleccionSpec seleccion;
    /** Huella de la última selección escrita en informe.md; la añade la herramienta, el valor de entrada se ignora. */
    public String seleccionInformada;
    public List<String> imports = new ArrayList<>();
    public ConstruccionSpec construccion;
    public List<FixtureSpec> fixtures = new ArrayList<>();
    public List<CasoSpec> casos = new ArrayList<>();
}
