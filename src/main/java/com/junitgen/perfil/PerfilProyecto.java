package com.junitgen.perfil;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.junitgen.spec.ConstruccionSpec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Perfil del proyecto ({@code perfil-proyecto.json} en la carpeta de salida): montaje de cada servicio, cómo crear
 * objetos de prueba de los tipos de datos, stubs típicos, huellas de los fuentes y recetas aprendidas. Nunca guarda
 * cuerpos de métodos ni valores por defecto de {@code @Value}. Las rutas guardadas son solo cadenas para comparar.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"esquema", "generador", "motor", "proyecto", "creado", "actualizado", "entorno", "huellas", "servicios",
        "tipos", "stubs", "recetas"})
public class PerfilProyecto {

    public static final int ESQUEMA = 1;

    public int esquema = ESQUEMA;
    public String generador;
    public String motor;
    public Identidad proyecto;
    public String creado;
    public String actualizado;
    /** rel del módulo → resumen de versiones. */
    public Map<String, String> entorno = new LinkedHashMap<>();
    /** Ruta relativa con '/' → SHA-256 (16 hex) del contenido. */
    public Map<String, String> huellas = new LinkedHashMap<>();
    public Map<String, ServicioPerfil> servicios = new LinkedHashMap<>();
    public Map<String, TipoPerfil> tipos = new LinkedHashMap<>();
    public List<StubPerfil> stubs;
    public Recetas recetas = new Recetas();

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"ruta", "nombre", "modulos"})
    public static class Identidad {
        public String ruta;
        public String nombre;
        public List<String> modulos = new ArrayList<>();
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"modulo", "archivo", "fuentes", "montaje", "dependencias"})
    public static class ServicioPerfil {
        public String modulo;
        public String archivo;
        public List<String> fuentes = new ArrayList<>();
        public MontajePerfil montaje;
        public List<DependenciaPerfil> dependencias = new ArrayList<>();
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"modo", "via", "constructor", "constructorConLogica", "postConstruct", "limpiezaTearDown", "mockStatic",
            "mockConstruction"})
    public static class MontajePerfil {
        /** EXPLICITO | INJECT_MOCKS | SIN_MOCKS. */
        public String modo;
        /** constructor | campos | setters | mixto | sin_dependencias. */
        public String via;
        public String constructor;
        public Boolean constructorConLogica;
        public List<String> postConstruct;
        public List<String> limpiezaTearDown;
        public Boolean mockStatic;
        public Boolean mockConstruction;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"nombre", "tipo", "tipoFqn", "obtencion", "mock", "profundo", "requiereValor", "tieneValorPorDefecto",
            "real", "contenedor", "heredadoDe", "nota"})
    public static class DependenciaPerfil {
        public String nombre;
        public String tipo;
        public String tipoFqn;
        public String obtencion;
        public boolean mock;
        public boolean profundo;
        public boolean requiereValor;
        public boolean tieneValorPorDefecto;
        public String real;
        public String contenedor;
        public String heredadoDe;
        public String nota;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"modulo", "archivo", "fuentes", "clase", "modo", "crear", "setters", "campos", "entidad"})
    public static class TipoPerfil {
        public String modulo;
        public String archivo;
        public List<String> fuentes = new ArrayList<>();
        /** CLASE | RECORD | ENUM. */
        public String clase;
        public String modo;
        public String crear;
        public List<String> setters;
        public List<String> campos;
        public Boolean entidad;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"tipo", "metodo", "aridad", "retorno", "devuelve", "nota", "usos"})
    public static class StubPerfil {
        public String tipo;
        public String metodo;
        public int aridad;
        public String retorno;
        public String devuelve;
        public String nota;
        public int usos;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"siguienteId", "aprendidas", "enPrueba", "fallosMontaje"})
    public static class Recetas {
        public int siguienteId = 1;
        public List<Receta> aprendidas = new ArrayList<>();
        /** FQN del servicio → ids de recetas aplicadas pendientes de confirmar en la verificación. */
        public Map<String, List<String>> enPrueba = new LinkedHashMap<>();
        /** FQN del servicio → montaje y casos con error de montaje/compilación pendientes de corregir. */
        public Map<String, FalloMontaje> fallosMontaje = new LinkedHashMap<>();
    }

    /** Receta aprendida; solo se rellena el campo propio de su {@code forma}. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"id", "forma", "servicio", "tipo", "construccion", "expresion", "metodo", "quitar", "anadir", "sintoma",
            "aprendida", "aciertos", "fallos", "activa", "excluidaEn"})
    public static class Receta {
        public static final String CONSTRUCCION = "construccion";
        public static final String ARGUMENTO_TIPO = "argumento_tipo";
        public static final String PROFUNDO_TIPO = "profundo_tipo";
        public static final String STUBS_CASO = "stubs_caso";

        public String id;
        /** construccion | argumento_tipo | profundo_tipo | stubs_caso. */
        public String forma;
        /** FQN del servicio de origen. */
        public String servicio;
        /** FQN (o nombre simple) del tipo de dependencia (recetas de tipo). */
        public String tipo;
        public ConstruccionSpec construccion;
        public String expresion;
        public String metodo;
        public List<String> quitar;
        public List<String> anadir;
        public String sintoma;
        public String aprendida;
        public int aciertos;
        public int fallos;
        public boolean activa = true;
        public List<String> excluidaEn = new ArrayList<>();
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"construccion", "casos"})
    public static class FalloMontaje {
        public ConstruccionSpec construccion;
        public Map<String, CasoFallido> casos = new LinkedHashMap<>();
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"metodo", "tipoError", "pista", "stubs"})
    public static class CasoFallido {
        public String metodo;
        public String tipoError;
        public String pista;
        public List<String> stubs = new ArrayList<>();
    }
}
