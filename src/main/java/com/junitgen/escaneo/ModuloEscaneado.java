package com.junitgen.escaneo;

import com.junitgen.config.ConfigProyecto;
import com.junitgen.generacion.RutasSalida;
import com.junitgen.proyecto.Modulo;
import com.junitgen.proyecto.VersionesProyecto;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Resultado del escaneo de un módulo. */
public class ModuloEscaneado {

    public Modulo modulo;
    public IndiceTipos indice;
    public VersionesProyecto versiones;
    public ConfigProyecto config;
    public RegistroBeans beans;
    public DetectorServicios.Resultado deteccion;
    public final Map<String, AnalisisServicio> analisis = new LinkedHashMap<>();
    public Map<String, EmparejadorTests.Emparejamiento> emparejamientos = new LinkedHashMap<>();
    public AnalizadorEstiloTests.EstiloTests estilo;
    public final List<String> avisos = new ArrayList<>();
    /** Caché de huellas de tests existentes por servicio (nivel 1 de duplicados). */
    public final Map<String, Object> cacheHuellas = new LinkedHashMap<>();
    /** Paquetes de los tipos main por carpeta de salida (en minúsculas); se calcula bajo demanda. */
    private Map<String, Set<String>> paquetesPorCarpeta;

    /**
     * Paquetes de los tipos main del módulo (sin interfaces ni anotaciones) cuya carpeta de salida coincide,
     * sin distinguir mayúsculas, con {@code carpetaBase}.
     */
    public synchronized Set<String> paquetesDeCarpeta(String carpetaBase) {
        if (indice == null) {
            return Set.of();
        }
        if (paquetesPorCarpeta == null) {
            Map<String, Set<String>> m = new HashMap<>();
            for (InfoClase c : indice.clasesMain()) {
                if (c.tipo == InfoClase.Tipo.INTERFAZ || c.tipo == InfoClase.Tipo.ANOTACION) {
                    continue;
                }
                m.computeIfAbsent(RutasSalida.carpetaBase(c.simple).toLowerCase(Locale.ROOT), k -> new LinkedHashSet<>())
                        .add(c.paquete);
            }
            paquetesPorCarpeta = m;
        }
        Set<String> r = paquetesPorCarpeta.get(carpetaBase.toLowerCase(Locale.ROOT));
        return r == null ? Set.of() : r;
    }

    public DetectorServicios.Candidato candidato(String fqn) {
        for (DetectorServicios.Candidato c : deteccion.servicios) {
            if (c.clase.fqn.equals(fqn)) {
                return c;
            }
        }
        for (DetectorServicios.Candidato c : deteccion.dudosos) {
            if (c.clase.fqn.equals(fqn)) {
                return c;
            }
        }
        return null;
    }

    /** Análisis de dependencias (se calcula bajo demanda para clases que no son candidatas). */
    public synchronized AnalisisServicio analisis(InfoClase c) {
        return analisis.computeIfAbsent(c.fqn, k -> AnalizadorDependencias.analizar(c, indice, versiones));
    }

    public synchronized EmparejadorTests.Emparejamiento emparejamiento(InfoClase c) {
        EmparejadorTests.Emparejamiento e = emparejamientos.get(c.fqn);
        if (e == null) {
            e = EmparejadorTests.emparejar(List.of(c), indice).get(c.fqn);
            emparejamientos.put(c.fqn, e);
        }
        return e;
    }
}
