package com.junitgen.verificacion;

import com.junitgen.config.CargadorConfig;

import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Número de verificaciones de servicio simultáneas por carpeta de salida y estado de la mitigación de choques de
 * recursos entre ellas. Se configura con la clave {@code verificadores} (1..8) de {@code junit-gen.yml} y la variable
 * {@link #ENV} (1..8); con ambas, la menor; sin ninguna, {@link #porDefecto()}. Si una salida sufre un choque
 * confirmado ({@link #registrarChoque}), todas sus verificaciones pasan a ir de una en una durante la sesión.
 * El estado vive en memoria y su clave es la ruta de la salida normalizada en minúsculas.
 */
public final class ParalelismoVerificacion {

    /** Variable de entorno con el número de verificadores (1..8). */
    public static final String ENV = "JUNITGEN_VERIFICADORES";
    public static final int MIN = 1;
    public static final int MAX = 8;
    /** Tope del valor automático. */
    public static final int MAX_AUTOMATICO = 4;
    /** Repeticiones en solitario sin confirmar un choque a partir de las cuales ya no se repite más. */
    public static final int MAX_REPETICIONES_SIN_CHOQUE = 3;

    private static final ConcurrentHashMap<String, Boolean> CHOQUES = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, AtomicInteger> SIN_CHOQUE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Actividad> ACTIVIDAD = new ConcurrentHashMap<>();
    private static final AtomicBoolean AVISO_ENV = new AtomicBoolean();

    private ParalelismoVerificacion() {
    }

    /** Actividad de las ventanas de una salida (contadores protegidos por el propio objeto). */
    private static final class Actividad {
        int activas;
        long inicios;
    }

    /** Ventana de una verificación en curso; {@link #cerrar()} dice si hubo solape con otras de la misma salida. */
    public static final class Ventana {
        private final Actividad actividad;
        private final boolean solapeAlInicio;
        private final long inicioPropio;
        private boolean cerrada;

        private Ventana(Actividad actividad) {
            this.actividad = actividad;
            synchronized (actividad) {
                this.solapeAlInicio = actividad.activas > 0;
                actividad.activas++;
                actividad.inicios++;
                this.inicioPropio = actividad.inicios;
            }
        }

        /** Cierra la ventana (idempotente en el conteo); true si otra verificación estaba activa al empezar o empezó durante esta. */
        public boolean cerrar() {
            synchronized (actividad) {
                boolean solape = solapeAlInicio || actividad.inicios != inicioPropio;
                if (!cerrada) {
                    cerrada = true;
                    actividad.activas--;
                }
                return solape;
            }
        }
    }

    private static String clave(Path salida) {
        return salida.toAbsolutePath().normalize().toString().toLowerCase(Locale.ROOT);
    }

    /** {@code min(4, max(1, procesadores / 2))}. */
    public static int porDefecto() {
        return Math.min(MAX_AUTOMATICO, Math.max(1, Runtime.getRuntime().availableProcessors() / 2));
    }

    /** Valor de {@link #ENV} en [1, 8]; null si falta o no es válido (con aviso a stderr una sola vez). */
    private static Integer desdeEntorno() {
        String v;
        try {
            v = System.getenv(ENV);
        } catch (SecurityException e) {
            return null;
        }
        if (v == null || v.isBlank()) {
            return null;
        }
        try {
            int n = Integer.parseInt(v.trim());
            if (n >= MIN && n <= MAX) {
                return n;
            }
        } catch (NumberFormatException e) {
            // se avisa abajo
        }
        if (AVISO_ENV.compareAndSet(false, true)) {
            System.err.println("junit-gen: " + ENV + " debe ser un entero entre " + MIN + " y " + MAX + "; se ignora.");
        }
        return null;
    }

    /**
     * Verificadores configurados: yml de la raíz y variable (la menor si están las dos; si solo hay uno, ese; si no hay
     * ninguno, {@link #porDefecto()}). Con {@code raiz} null, solo la variable.
     */
    public static int configurado(Path raiz) {
        Integer yml = null;
        try {
            yml = CargadorConfig.verificadores(raiz);
        } catch (RuntimeException e) {
            System.err.println("junit-gen: no se pudo leer 'verificadores' de junit-gen.yml: " + e.getClass().getSimpleName());
        }
        Integer env = desdeEntorno();
        if (yml != null && env != null) {
            return Math.min(yml, env);
        }
        if (yml != null) {
            return yml;
        }
        if (env != null) {
            return env;
        }
        return porDefecto();
    }

    /** 1 si la salida tuvo un choque confirmado; si no, {@link #configurado(Path)}. */
    public static int efectivo(Path raiz, Path salida) {
        if (salida != null && huboChoque(salida)) {
            return 1;
        }
        return configurado(raiz);
    }

    /** Marca la salida como afectada por un choque de recursos: desde ahora, de una en una. */
    public static void registrarChoque(Path salida) {
        if (salida != null) {
            CHOQUES.put(clave(salida), Boolean.TRUE);
        }
    }

    public static boolean huboChoque(Path salida) {
        return salida != null && CHOQUES.containsKey(clave(salida));
    }

    /** true si aún se puede repetir en solitario un resultado sospechoso de esta salida. */
    public static boolean repeticionPermitida(Path salida) {
        if (salida == null || huboChoque(salida)) {
            return false;
        }
        AtomicInteger c = SIN_CHOQUE.get(clave(salida));
        return c == null || c.get() < MAX_REPETICIONES_SIN_CHOQUE;
    }

    /** Anota una repetición en solitario que no confirmó ningún choque. */
    public static void contarRepeticionSinChoque(Path salida) {
        if (salida != null) {
            SIN_CHOQUE.computeIfAbsent(clave(salida), k -> new AtomicInteger()).incrementAndGet();
        }
    }

    /** Abre la ventana de una verificación de la salida (cerrarla siempre, en un {@code finally}). */
    public static Ventana abrirVentana(Path salida) {
        return new Ventana(ACTIVIDAD.computeIfAbsent(clave(salida), k -> new Actividad()));
    }
}
