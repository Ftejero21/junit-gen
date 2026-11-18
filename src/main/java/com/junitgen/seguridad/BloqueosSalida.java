package com.junitgen.seguridad;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;

/**
 * Bloqueos por carpeta de salida (dentro del proceso). El de escritura protege las secuencias
 * leer-modificar-escribir de spec.json, informe.md y tanda.json; el de ejecución protege la compilación
 * y la ejecución de tests sobre la misma salida y tiene dos modos: exclusivo ({@link #conEjecucion}: una sola
 * ejecución, nada en paralelo) y compartido ({@link #conEjecucionCompartida}: varias verificaciones de servicio a la
 * vez, que comparten en lectura la preparación del módulo). Orden obligatorio: ejecución (mutex heredado → lectura o
 * escritura) → cerrojo propio del servicio → escritura (nunca al revés), y el de escritura no se mantiene durante
 * javac, java ni Maven. Nunca se toma la ejecución exclusiva dentro de la compartida (no hay promoción de lectura a
 * escritura: interbloqueo). La clave es la ruta real de la salida en minúsculas.
 */
public final class BloqueosSalida {

    private static final ConcurrentHashMap<String, ReentrantLock> ESCRITURA = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, ReentrantLock> EJECUCION = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, ReentrantReadWriteLock> EJECUCION_RW = new ConcurrentHashMap<>();

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

    private static ReentrantReadWriteLock ejecucionRw(Path salidaReal) {
        return EJECUCION_RW.computeIfAbsent(clave(salidaReal), k -> new ReentrantReadWriteLock(true));
    }

    /**
     * Ejecuta {@code accion} con la ejecución de la salida en modo exclusivo: toma el bloqueo de ejecución (reentrante) y
     * después el de escritura del cerrojo lectura/escritura; no convive con ninguna ejecución compartida.
     */
    public static <T> T conEjecucion(Path salidaReal, Supplier<T> accion) {
        ReentrantLock mutex = ejecucion(salidaReal);
        mutex.lock();
        try {
            ReentrantReadWriteLock.WriteLock w = ejecucionRw(salidaReal).writeLock();
            w.lock();
            try {
                return accion.get();
            } finally {
                w.unlock();
            }
        } finally {
            mutex.unlock();
        }
    }

    /**
     * Ejecuta {@code accion} con la ejecución de la salida en modo compartido (lectura): varias a la vez, pero nunca con
     * una exclusiva. Dentro no se puede llamar a {@link #conEjecucion} para la misma salida.
     */
    public static <T> T conEjecucionCompartida(Path salidaReal, Supplier<T> accion) {
        ReentrantReadWriteLock.ReadLock r = ejecucionRw(salidaReal).readLock();
        r.lock();
        try {
            return accion.get();
        } finally {
            r.unlock();
        }
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
