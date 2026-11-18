package com.junitgen.generacion;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Estado persistente de una tanda de generación ({@code tanda.json} en la carpeta de salida): lista ordenada de
 * servicios con sus métodos (previstos, ids generados y descartados por duplicado), índice del primer servicio no
 * completo y estado por servicio. Permite retomar la tanda con {@code continuar} en otro chat o proceso.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Tanda {

    public static final int VERSION = 1;

    public static final String PENDIENTE = "pendiente";
    public static final String GENERADO = "generado";
    public static final String VERIFICANDO = "verificando";
    public static final String OK = "ok";
    public static final String CON_FALLOS_MONTAJE = "con_fallos_montaje";
    public static final String CON_FALLOS_ASERCION = "con_fallos_asercion";
    public static final String MONTAJE_FALLIDO = "montaje_fallido";

    /** Fases de la tanda: generando servicios, arreglando los casos apuntados o lista para cerrar. */
    public static final String FASE_GENERACION = "generacion";
    public static final String FASE_ARREGLO = "arreglo";
    public static final String FASE_CIERRE = "cierre";

    /** Estados de un caso apuntado para arreglar al final. */
    public static final String ARREGLO_PENDIENTE = "pendiente";
    public static final String ARREGLO_ARREGLADO = "arreglado";
    public static final String ARREGLO_POSIBLE_BUG = "posible_bug";
    public static final String ARREGLO_DUDOSO = "dudoso";
    public static final String ARREGLO_APARTADO = "apartado";

    public int version = VERSION;
    /** Ruta real del proyecto. */
    public String proyecto;
    public String creada;
    public String actualizada;
    /** alfabetico | prioridad. */
    public String orden;
    public Boolean todas;
    public Integer numServicios;
    /** Posición (desde 0) del primer servicio no completo; = número de servicios si todos están completos. */
    public int indice;
    public boolean cerrada;
    public List<ServicioTanda> servicios = new ArrayList<>();
    /** generacion | arreglo | cierre (se recalcula al guardar). */
    public String fase = FASE_GENERACION;
    /** Casos que fallaron durante la tanda, apuntados para arreglarlos al final. */
    public List<ArregloTanda> arreglos = new ArrayList<>();
    /** true si la sección «Arreglos al final» ya está escrita en informe.md con los registros actuales. */
    public boolean arreglosInformados;
    /** Frases de los imports corregidos automáticamente durante la tanda (para «Arreglos al final»). */
    public List<String> importsReparados = new ArrayList<>();
    /** Medición por servicio (FQN, o «(tanda)» para lo que no es de ningún servicio): tiempo y llamadas a herramientas. */
    public Map<String, Medicion> mediciones = new LinkedHashMap<>();
    /** true si la sección «Medición de la tanda» ya está escrita en informe.md. */
    public boolean medicionInformada;
    /** Bloques de trabajo en paralelo (clientes con subagentes: Claude Code y GitHub Copilot); null = tanda secuencial. */
    public List<BloqueTanda> bloques;

    /** Estados de un bloque de trabajo en paralelo. */
    public static final String BLOQUE_PENDIENTE = "pendiente";
    public static final String BLOQUE_EN_CURSO = "en_curso";
    public static final String BLOQUE_TERMINADO = "terminado";

    /** Bloque de servicios que trabaja un único trabajador (subagente o el propio agente principal). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class BloqueTanda {
        public int id;
        /** FQN de los servicios del bloque, en el orden del plan. */
        public List<String> servicios = new ArrayList<>();
        /** pendiente | en_curso | terminado. */
        public String estado;
        /** Instante (ISO-8601) en que un trabajador tomó el bloque por primera vez. */
        public String tomado;
        /** Instante (ISO-8601) en que el bloque quedó terminado. */
        public String terminado;
    }

    /** Tiempo y llamadas atribuidos a un servicio de la tanda. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Medicion {
        /** Primera llamada atribuida (ISO-8601). */
        public String inicio;
        /** Última llamada atribuida (ISO-8601). */
        public String fin;
        /** Milisegundos atribuidos (tiempo de la IA entre llamadas + ejecución de la herramienta). */
        public long ms;
        public int llamadas;
        /** Llamadas devueltas con error. */
        public int rechazadas;
        /** Bytes de las respuestas. */
        public long bytes;
        /** Llamadas por herramienta durante la generación del servicio. */
        public Map<String, Integer> porHerramienta = new LinkedHashMap<>();
        /** Llamadas por herramienta durante la fase de arreglo del servicio. */
        public Map<String, Integer> enArreglo = new LinkedHashMap<>();
        /** Milisegundos atribuidos a la fase de arreglo del servicio. */
        public long msArreglo;
    }

    /** Caso que falló durante la tanda y se arregla al final (arreglos: alFinal). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ArregloTanda {
        /** Id del caso. */
        public String caso;
        /** FQN del servicio. */
        public String servicio;
        public String claseTest;
        public String metodoTest;
        /** Método probado del servicio. */
        public String metodo;
        /** no_compila | error_montaje | fallo_asercion. */
        public String tipo;
        /** Primer error registrado (para el informe). */
        public String errorInicial;
        /** Último error conocido. */
        public String error;
        public String pista;
        /** true si el error está en la parte común de la clase de test. */
        public boolean comun;
        /** Correcciones aplicadas. */
        public int intentos;
        /** Correcciones aplicadas mientras el error era común (fuera de los casos). */
        public int intentosComun;
        public String estado = ARREGLO_PENDIENTE;
        public String motivo;
        public List<String> cambios = new ArrayList<>();
        /** Rondas de corrección automática (sin IA) aplicadas por junit-gen. */
        public int autoIntentos;
        /** Frases de las correcciones automáticas aplicadas. */
        public List<String> autoCambios = new ArrayList<>();
        /** true si quedó arreglado solo con correcciones automáticas (sin intentos de la IA). */
        public boolean automatico;
        /** Origen de la excepción en el código (clase.método, línea, causa), o null. */
        public String origen;
        /** true si la corrección aceptada dejó el test menos estricto que el original. */
        public boolean menosEstricto;
        /** Avisos de calidad de las correcciones aceptadas. */
        public List<String> avisosCalidad = new ArrayList<>();
        /** Motivo por el que se deshizo el último cambio de la parte común (servicio) hecho al corregir este caso. */
        public String correccionDeshecha;

        /** Copia independiente del registro. */
        public ArregloTanda copia() {
            ArregloTanda c = new ArregloTanda();
            c.caso = caso;
            c.servicio = servicio;
            c.claseTest = claseTest;
            c.metodoTest = metodoTest;
            c.metodo = metodo;
            c.tipo = tipo;
            c.errorInicial = errorInicial;
            c.error = error;
            c.pista = pista;
            c.comun = comun;
            c.intentos = intentos;
            c.intentosComun = intentosComun;
            c.estado = estado;
            c.motivo = motivo;
            c.cambios = cambios == null ? new ArrayList<>() : new ArrayList<>(cambios);
            c.autoIntentos = autoIntentos;
            c.autoCambios = autoCambios == null ? new ArrayList<>() : new ArrayList<>(autoCambios);
            c.automatico = automatico;
            c.origen = origen;
            c.menosEstricto = menosEstricto;
            c.avisosCalidad = avisosCalidad == null ? new ArrayList<>() : new ArrayList<>(avisosCalidad);
            c.correccionDeshecha = correccionDeshecha;
            return c;
        }
    }

    /** Servicio de la tanda, en el orden del plan. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ServicioTanda {
        /** FQN del servicio. */
        public String clase;
        /** rel del módulo (puede faltar). */
        public String modulo;
        public String estado;
        public boolean completo;
        /** Máximo de intentos de montaje de sus casos. */
        public int intentos;
        /** Motivo del cierre por la salvaguarda anti-bucle (servicio atascado), o null si no se cerró así. */
        public String cierre;
        public List<MetodoTanda> metodos = new ArrayList<>();
    }

    /** Método del plan de un servicio (las sobrecargas van fusionadas por nombre). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class MetodoTanda {
        public String metodo;
        public int previstos;
        public boolean conTests;
        public List<String> generados = new ArrayList<>();
        public List<String> duplicados = new ArrayList<>();
        /** Ids descartados porque ya estaban generados en la salida (cuentan siempre como atendidos). */
        public List<String> enSalida = new ArrayList<>();
        /** Casos previstos dados por atendidos por la salvaguarda anti-bucle (servicio atascado y cerrado). */
        public int renunciados;
    }

    /** Número de servicios completos (todos sus métodos acreditados). */
    public int completos() {
        int n = 0;
        for (ServicioTanda s : servicios) {
            if (s != null && s.completo) {
                n++;
            }
        }
        return n;
    }

    /** true si todos los servicios están completos. */
    public boolean todoCompleto() {
        return completos() == servicios.size();
    }
}
