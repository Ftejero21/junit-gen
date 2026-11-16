package com.junitgen.escaneo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Resultado del análisis de dependencias y montaje de un servicio. */
public class AnalisisServicio {

    public InfoClase clase;
    public final List<Dependencia> dependencias = new ArrayList<>();
    public Dependencia.ModoMontaje modo;
    public InfoClase.InfoConstructor constructor;
    public boolean constructorSinArgs;
    /** clave del método (nombre(tipos)) → motivos por los que requiere refactor. */
    public final Map<String, List<String>> refactor = new LinkedHashMap<>();
    public boolean permiteMockStatic;
    public boolean permiteMockConstruction;
    /** true si el constructor elegido usa sus dependencias o calcula campos derivados. */
    public boolean constructorConLogica;
    /** Sentencias del constructor que no son asignaciones simples (compactadas); null si es trivial. */
    public String cuerpoConstructor;
    /** Métodos {@code @PostConstruct} sin parámetros y no estáticos. */
    public final List<String> postConstruct = new ArrayList<>();
    /** Sentencias de limpieza para tearDown (holders estáticos que referencia el servicio) y su import. */
    public final List<Limpieza> limpiezas = new ArrayList<>();

    public record Limpieza(String sentencia, String importa) {
    }

    public Dependencia dependencia(String nombre) {
        for (Dependencia d : dependencias) {
            if (d.nombre.equals(nombre)) {
                return d;
            }
        }
        return null;
    }

    public List<Dependencia> mocks() {
        return dependencias.stream().filter(Dependencia::esMockDeclarado).collect(Collectors.toList());
    }

    public boolean requiereRefactor() {
        return !refactor.isEmpty();
    }
}
