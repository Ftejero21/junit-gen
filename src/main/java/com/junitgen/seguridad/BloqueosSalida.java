package com.junitgen.seguridad;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Bloqueos por carpeta de salida (dentro del proceso). El de escritura protege las secuencias
 * leer-modificar-escribir de spec.json, informe.md y tanda.json; el de ejecución serializa la compilación
 * y la ejecución de tests sobre la misma salida. Orden obligatorio: ejecución → escritura (nunca al revés),
 * y el de escritura no se mantiene durante javac, java ni Maven. La clave es la ruta real de la salida en
 * minúsculas.
 */
public final class BloqueosSalida {

    private static final ConcurrentHashMap<String, ReentrantLock> ESCRITURA = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, ReentrantLock> EJECUCION = new ConcurrentHashMap<>();

    private BloqueosSalida() {
    }

    private static String clave(Path salidaReal) {
        Objects.requireNonNull(salidaReal, "salida");
        return salidaReal.toAbsolutePath().normalize().toString().toLowerCase(Locale.ROOT);
    }

    /** Bloqueo de escritura de la salida (reentrante). */
    public static ReentrantLock escritura(Path salidaReal) {
        return ESCRITURA.computeIfAbsent(clave(salidaReal), k -> new ReentrantLock());
    }

    /** Bloqueo de ejecución (compilar y ejecutar tests) de la salida (reentrante). */
    public static ReentrantLock ejecucion(Path salidaReal) {
        return EJECUCION.computeIfAbsent(clave(salidaReal), k -> new ReentrantLock());
    }

    /** Ejecuta {@code accion} con el bloqueo de escritura de la salida tomado. */
    public static <T> T conEscritura(Path salidaReal, Supplier<T> accion) {
        return con(escritura(salidaReal), accion);
    }

    /** Ejecuta {@code accion} con el bloqueo de ejecución de la salida tomado. */
    public static <T> T conEjecucion(Path salidaReal, Supplier<T> accion) {
        return con(ejecucion(salidaReal), accion);
    }

    private static <T> T con(ReentrantLock lock, Supplier<T> accion) {
        lock.lock();
        try {
            return accion.get();
        } finally {
            lock.unlock();
        }
    }
}
