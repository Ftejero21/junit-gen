package com.junitgen.generacion;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.List;

/**
 * Campaña del modo «todos los servicios» ({@code oleadas.json} en la carpeta de salida): servicios ordenados y
 * repartidos en oleadas de tamaño fijo, cada una de las cuales se trabaja como una tanda normal. Permite retomar la
 * campaña con {@code continuar} en otro chat o proceso.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Oleadas {

    public static final int VERSION = 1;

    public static final String SERVICIO_PENDIENTE = "pendiente";
    public static final String SERVICIO_HECHO = "hecho";
    public static final String SERVICIO_OMITIDO = "omitido";

    public static final String OLEADA_PENDIENTE = "pendiente";
    public static final String OLEADA_EN_CURSO = "en_curso";
    public static final String OLEADA_CERRADA = "cerrada";

    public int version = VERSION;
    /** Ruta real del proyecto. */
    public String proyecto;
    public String creada;
    public String actualizada;
    /** true si la campaña se creó con `todas` (100 % de las funciones). */
    public Boolean todas;
    public String modulo;
    /** Límite de servicios pedido (`todos máx N`); null = todos. */
    public Integer maximo;
    /** Servicios por oleada. */
    public int tamOleada;
    /** true si todas las oleadas están cerradas y el informe global ya se generó. */
    public boolean cerrada;
    /** true si la sección global de informe.md ya está escrita. */
    public boolean informada;
    public List<ServicioOleada> servicios = new ArrayList<>();
    public List<ResumenOleada> oleadas = new ArrayList<>();

    /** Servicio de la campaña, con la oleada que lo trabaja. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ServicioOleada {
        /** FQN del servicio. */
        public String clase;
        /** rel del módulo (puede faltar). */
        public String modulo;
        /** Oleada (desde 1). */
        public int oleada;
        /** pendiente | hecho | omitido. */
        public String estado = SERVICIO_PENDIENTE;
        /** true si se cerró sin completar todos sus casos. */
        public Boolean incompleto;
        /** Motivo de la omisión. */
        public String motivo;
    }

    /** Estado y cifras de una oleada. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ResumenOleada {
        public int numero;
        public int servicios;
        /** pendiente | en_curso | cerrada. */
        public String estado = OLEADA_PENDIENTE;
        public String inicio;
        public String fin;
        public long ms;
        public int casosGenerados;
        public int pasan;
        public int fallosAsercion;
        public int montajeFallido;
        public int sinVerificar;
        public int duplicados;
        public int arreglados;
        public int posiblesBugs;
        public int apartados;
        public int serviciosIncompletos;
    }

    /** Primera oleada no cerrada, o null si todas lo están. */
    public ResumenOleada actual() {
        synchronized (this) {
            for (ResumenOleada o : oleadas) {
                if (o != null && !OLEADA_CERRADA.equals(o.estado)) {
                    return o;
                }
            }
            return null;
        }
    }

    /** Oleada por número (desde 1), o null. */
    public ResumenOleada oleada(int numero) {
        synchronized (this) {
            for (ResumenOleada o : oleadas) {
                if (o != null && o.numero == numero) {
                    return o;
                }
            }
            return null;
        }
    }

    /** Servicios ya dados por hechos. */
    public int hechos() {
        synchronized (this) {
            return (int) servicios.stream().filter(s -> s != null && SERVICIO_HECHO.equals(s.estado)).count();
        }
    }

    /** Servicios de la campaña (incluidos los omitidos). */
    public int total() {
        synchronized (this) {
            return servicios.size();
        }
    }
}
