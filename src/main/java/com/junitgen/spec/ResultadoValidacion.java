package com.junitgen.spec;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.junitgen.duplicados.Duplicado;
import com.junitgen.escaneo.AnalisisServicio;
import com.junitgen.escaneo.InfoClase;
import com.junitgen.escaneo.InfoMetodo;
import com.junitgen.escaneo.ModuloEscaneado;
import com.junitgen.escaneo.ProyectoEscaneado;
import com.junitgen.escaneo.SeleccionFunciones;
import com.junitgen.duplicados.Huella;

import com.junitgen.escaneo.Dependencia;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Resultado de validar un spec: errores, avisos, duplicados y el modelo resuelto para generar. */
@JsonPropertyOrder({"valido", "errores", "avisos", "duplicados"})
public class ResultadoValidacion {

    public boolean valido;
    public final List<ErrorValidacion> errores = new ArrayList<>();
    public final List<ErrorValidacion> avisos = new ArrayList<>();
    public final List<Duplicado> duplicados = new ArrayList<>();

    @JsonIgnore
    public Spec spec;
    @JsonIgnore
    public ProyectoEscaneado proyecto;
    @JsonIgnore
    public final List<ServicioValidado> servicios = new ArrayList<>();

    public void error(String ruta, String codigo, String mensaje, String sugerencia) {
        errores.add(ErrorValidacion.error(ruta, codigo, mensaje, sugerencia));
    }

    public void aviso(String ruta, String codigo, String mensaje, String sugerencia) {
        avisos.add(ErrorValidacion.aviso(ruta, codigo, mensaje, sugerencia));
    }

    public boolean esDuplicado(String casoId) {
        return duplicados.stream().anyMatch(d -> d.caso.equals(casoId));
    }

    /** Servicio del spec ya resuelto contra el escaneo. */
    public static class ServicioValidado {
        public ServicioSpec spec;
        public int indice;
        public ModuloEscaneado modulo;
        public InfoClase clase;
        public AnalisisServicio analisis;
        public EstiloSpec estilo;
        public String claseTest;
        /** Funciones seleccionadas del servicio (calculadas del código y la configuración). */
        public SeleccionFunciones seleccion;
        public final List<CasoValidado> casos = new ArrayList<>();
        /** nombre simple → FQN a importar ("" si no hace falta). */
        public final Map<String, String> imports = new LinkedHashMap<>();
        /** dependencia mock que pasa a instancia real → expresión (construccion.argumentos). */
        public final Map<String, String> reales = new LinkedHashMap<>();
        /** Mocks que se declaran con RETURNS_DEEP_STUBS. */
        public final Set<String> profundos = new LinkedHashSet<>();
        /** índice del stub de construccion.stubs → "when" | "do". */
        public final Map<Integer, String> formaStubMontaje = new LinkedHashMap<>();
        /** true si se invocan los @PostConstruct tras montar. */
        public boolean postConstruct;

        /** true si la dependencia se declara como mock en el test (no es real ni deriva de un real). */
        public boolean esMock(Dependencia d) {
            return d.esMockDeclarado() && !reales.containsKey(d.nombre) && Collections.disjoint(d.derivaDe, reales.keySet())
                    && !contenedorFijado(d);
        }

        /** true si es el elemento de una colección/proveedor cuyo valor da el spec en construccion.argumentos. */
        public boolean contenedorFijado(Dependencia d) {
            return d.contenedor != null && spec != null && spec.construccion != null && spec.construccion.argumentos != null
                    && spec.construccion.argumentos.containsKey(d.contenedor);
        }

        /** Dependencias que se declaran como mock en el test. */
        public List<Dependencia> mocks() {
            List<Dependencia> r = new ArrayList<>();
            for (Dependencia d : analisis.dependencias) {
                if (esMock(d)) {
                    r.add(d);
                }
            }
            return r;
        }
    }

    /** Caso del spec resuelto: método objetivo, formas de stub y huella. */
    public static class CasoValidado {
        public CasoSpec spec;
        public int indice;
        public InfoMetodo metodo;
        public Huella huella;
        /** índice del stub → "when" | "do". */
        public final Map<Integer, String> formaStub = new LinkedHashMap<>();
        /** índice de la construcción → (índice del stub → forma). */
        public final Map<Integer, Map<Integer, String>> formaStubConstruccion = new LinkedHashMap<>();
        /** Tipo para la variable resultado ("var"/"Object" si no se puede escribir). */
        public String tipoResultado;
    }
}
