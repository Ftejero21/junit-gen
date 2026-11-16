package com.junitgen.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Configuración opcional leída de {@code junit-gen.yml} (raíz del proyecto y/o módulo; el módulo
 * prevalece).
 */
public class ConfigProyecto {

    public List<String> incluirClases = new ArrayList<>();
    public List<String> incluirPaquetes = new ArrayList<>();
    public List<String> excluirClases = new ArrayList<>();
    public List<String> excluirPaquetes = new ArrayList<>();
    /** assertj | junit | auto (por defecto auto). */
    public String aserciones = "auto";
    public String jdk;
    public String mavenHome;
    public String mavenSettings;
    public List<String> classpath = new ArrayList<>();
    public Integer umbralPuntuacion;
    /** Porcentaje (1..100) de funciones no triviales que se prueban, por puntuación; por defecto 60. */
    public Integer porcentajeFunciones;
    /** Modo de verificación de los tests generados; null = sin configurar (se usa {@link ModoVerificacion#SIEMPRE}). */
    public ModoVerificacion verificacion;
    /** Orden de los servicios del plan; null = sin configurar (se usa {@link OrdenServicios#ALFABETICO}). */
    public OrdenServicios orden;
    /** Qué hacer con los fallos de aserción; null = sin configurar (se usa {@link ModoFallosAsercion#REVISAR_IA}). */
    public ModoFallosAsercion fallosAsercion;
    /** Cuándo se arreglan los casos que fallan en una tanda; null = sin configurar (se usa {@link ModoArreglos#AL_FINAL}). */
    public ModoArreglos arreglos;
    public List<String> origenes = new ArrayList<>();
    public List<String> avisos = new ArrayList<>();

    /** Si los tests generados se prueban siempre, se pregunta o nunca (clave {@code verificacion}). */
    public enum ModoVerificacion {
        SIEMPRE, PREGUNTAR, NUNCA;

        /** Valor de {@code siempre|preguntar|nunca} (sin distinguir mayúsculas); null si no es válido. */
        public static ModoVerificacion de(String valor) {
            if (valor == null) {
                return null;
            }
            return switch (valor.trim().toLowerCase(Locale.ROOT)) {
                case "siempre" -> SIEMPRE;
                case "preguntar" -> PREGUNTAR;
                case "nunca" -> NUNCA;
                default -> null;
            };
        }
    }

    /** Orden de los servicios al elegir los N primeros (clave {@code orden}). */
    public enum OrdenServicios {
        ALFABETICO, PRIORIDAD;

        /** Valor de {@code alfabetico|prioridad} (sin distinguir mayúsculas); null si no es válido. */
        public static OrdenServicios de(String valor) {
            if (valor == null) {
                return null;
            }
            return switch (valor.trim().toLowerCase(Locale.ROOT)) {
                case "alfabetico", "alfabético" -> ALFABETICO;
                case "prioridad" -> PRIORIDAD;
                default -> null;
            };
        }
    }

    /**
     * Qué se hace con los tests que fallan por aserción (clave {@code fallosAsercion}): la IA los revisa
     * sola con el código ({@code revisarIa}) o se pregunta al usuario al cerrar ({@code preguntar}).
     */
    public enum ModoFallosAsercion {
        REVISAR_IA, PREGUNTAR;

        /** Valor de {@code revisarIa|preguntar} (sin distinguir mayúsculas); null si no es válido. */
        public static ModoFallosAsercion de(String valor) {
            if (valor == null) {
                return null;
            }
            return switch (valor.trim().toLowerCase(Locale.ROOT)) {
                case "revisaria", "revisar_ia", "revisar-ia" -> REVISAR_IA;
                case "preguntar" -> PREGUNTAR;
                default -> null;
            };
        }
    }

    /**
     * Cuándo se arreglan los casos que fallan durante una tanda (clave {@code arreglos}): al terminar de
     * generar todos los servicios ({@code alFinal}) o servicio a servicio ({@code inmediato}).
     */
    public enum ModoArreglos {
        AL_FINAL, INMEDIATO;

        /** Valor de {@code alFinal|inmediato} (sin distinguir mayúsculas); null si no es válido. */
        public static ModoArreglos de(String valor) {
            if (valor == null) {
                return null;
            }
            return switch (valor.trim().toLowerCase(Locale.ROOT)) {
                case "alfinal", "al_final", "al-final", "final" -> AL_FINAL;
                case "inmediato" -> INMEDIATO;
                default -> null;
            };
        }
    }

    /** Modo efectivo de los arreglos: {@link ModoArreglos#AL_FINAL} si no está configurado. */
    public ModoArreglos modoArreglos() {
        return arreglos == null ? ModoArreglos.AL_FINAL : arreglos;
    }

    /** Modo efectivo de los fallos de aserción: {@link ModoFallosAsercion#REVISAR_IA} si no está configurado. */
    public ModoFallosAsercion modoFallosAsercion() {
        return fallosAsercion == null ? ModoFallosAsercion.REVISAR_IA : fallosAsercion;
    }

    /** Orden efectivo de los servicios: {@link OrdenServicios#ALFABETICO} si no está configurado. */
    public OrdenServicios ordenServicios() {
        return orden == null ? OrdenServicios.ALFABETICO : orden;
    }

    /** Modo de verificación efectivo: {@link ModoVerificacion#SIEMPRE} si no está configurado. */
    public ModoVerificacion modoVerificacion() {
        return verificacion == null ? ModoVerificacion.SIEMPRE : verificacion;
    }

    public int umbral() {
        return umbralPuntuacion == null ? 40 : umbralPuntuacion;
    }

    /** Porcentaje de funciones seleccionadas (1..100); 60 si no está configurado. */
    public int porcentaje() {
        return porcentajeFunciones == null ? 60 : porcentajeFunciones;
    }

    /** Combina: los valores de {@code otra} (módulo) prevalecen sobre los de esta (raíz). */
    public ConfigProyecto combinar(ConfigProyecto otra) {
        if (otra == null) {
            return this;
        }
        ConfigProyecto r = new ConfigProyecto();
        r.incluirClases = !otra.incluirClases.isEmpty() ? otra.incluirClases : incluirClases;
        r.incluirPaquetes = !otra.incluirPaquetes.isEmpty() ? otra.incluirPaquetes : incluirPaquetes;
        r.excluirClases = !otra.excluirClases.isEmpty() ? otra.excluirClases : excluirClases;
        r.excluirPaquetes = !otra.excluirPaquetes.isEmpty() ? otra.excluirPaquetes : excluirPaquetes;
        r.aserciones = otra.aserciones != null && !"auto".equals(otra.aserciones) ? otra.aserciones : aserciones;
        r.jdk = otra.jdk != null ? otra.jdk : jdk;
        r.mavenHome = otra.mavenHome != null ? otra.mavenHome : mavenHome;
        r.mavenSettings = otra.mavenSettings != null ? otra.mavenSettings : mavenSettings;
        r.classpath = !otra.classpath.isEmpty() ? otra.classpath : classpath;
        r.umbralPuntuacion = otra.umbralPuntuacion != null ? otra.umbralPuntuacion : umbralPuntuacion;
        r.porcentajeFunciones = otra.porcentajeFunciones != null ? otra.porcentajeFunciones : porcentajeFunciones;
        r.verificacion = otra.verificacion != null ? otra.verificacion : verificacion;
        r.orden = otra.orden != null ? otra.orden : orden;
        r.fallosAsercion = otra.fallosAsercion != null ? otra.fallosAsercion : fallosAsercion;
        r.arreglos = otra.arreglos != null ? otra.arreglos : arreglos;
        r.origenes = new ArrayList<>(origenes);
        r.origenes.addAll(otra.origenes);
        r.avisos = new ArrayList<>(avisos);
        r.avisos.addAll(otra.avisos);
        return r;
    }
}
