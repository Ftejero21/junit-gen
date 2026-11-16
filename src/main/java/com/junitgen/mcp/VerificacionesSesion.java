package com.junitgen.mcp;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.junitgen.util.LimitesRespuesta;
import com.junitgen.util.Textos;
import com.junitgen.verificacion.ClasificadorFallos;
import com.junitgen.verificacion.ResultadoTest;
import com.junitgen.verificacion.ResultadoVerificacion;
import com.junitgen.verificacion.VerificadorTests;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Verificaciones en segundo plano de la sesión MCP: un único hilo daemon ({@code junit-gen-verificador}) prueba
 * servicio a servicio los tests recién generados mientras la IA sigue con el siguiente. El estado por carpeta de
 * salida (cola, servicio en curso, últimos resultados, casos de montaje a corregir, avisos y alertas) está protegido
 * por un monitor propio con {@code wait/notifyAll}. El hilo verificador nunca pregunta al usuario, no mide la
 * llamada en curso ni envía mensajes al cliente: solo deja resultados que las herramientas leen.
 */
final class VerificacionesSesion {

    /** Reintentos de un servicio cuyo resultado se descartó porque sus tests cambiaron durante la verificación. */
    static final int MAX_REINTENTOS_DESCARTE = 3;
    /** Intervalo máximo entre comprobaciones de cancelación mientras se espera a que la cola quede inactiva. */
    static final long SONDEO_MS = 500;
    /** Espera acotada al cerrar el servidor. */
    static final Duration ESPERA_CIERRE = Duration.ofSeconds(30);
    /** Tamaño máximo de la nota de cabecera. */
    static final int MAX_NOTA = 300;
    /** Ids mostrados en la nota y en la puerta. */
    static final int MAX_IDS_NOTA = 3;
    static final int MAX_IDS_PUERTA = 10;
    /** Avisos y alertas acumulados por salida como máximo. */
    static final int MAX_AVISOS = 30;
    /** Espera máxima de la puerta cuando el servicio que bloquea se está re-verificando (datos viejos). */
    static final Duration ESPERA_PUERTA = Duration.ofSeconds(30);
    /** Intento desde el que la puerta aparta los casos rotos en vez de rechazar. */
    static final int INTENTO_APARTAR = 2;
    /** Líneas de error (agrupadas) en el rechazo de la puerta de montaje. */
    static final int MAX_ERRORES_PUERTA = 5;
    /** Tamaño máximo de cada pista en el rechazo de la puerta. */
    static final int MAX_PISTA_PUERTA = 400;
    /** Rechazos de la puerta de revisión por servicio antes de dejar pasar. */
    static final int MAX_RECHAZOS_REVISION = 2;
    /** Tamaño máximo del rechazo de la puerta. */
    static final int MAX_TEXTO_PUERTA = 4500;

    /** Progreso de las verificaciones de una salida. */
    record Progreso(int verificados, String enCurso, int enCola, int total) {
    }

    /** Casos de montaje o compilación de un servicio que la IA debe corregir (intento k de 3). */
    record Correccion(String fqn, int intento, List<String> ids) {
    }

    /** Casos con fallo de aserción de un servicio que la IA debe revisar con el código (modo revisarIa). */
    record Revision(String fqn, List<String> ids) {
    }

    /**
     * Punto de restauración de una corrección de la parte común (servicio) hecha con arreglar_caso: archivo de test y
     * entrada del servicio en spec.json antes del cambio, casos corregidos y estados previos de los casos del servicio.
     */
    record PuntoComun(Path archivoTest, String textoPrevio, ObjectNode servicioPrevio, Set<String> idsCorreccion,
                      Map<String, String> estadosPrevios) {
    }

    /** Estado de las verificaciones de una carpeta de salida (protegido por {@link #monitor}). */
    private static final class EstadoVerificaciones {
        /** FQN → punto de restauración de la corrección común pendiente de verificar. */
        final Map<String, PuntoComun> puntosComun = new HashMap<>();
        final Path salida;
        final LinkedHashSet<String> cola = new LinkedHashSet<>();
        final Map<String, Path> proyectos = new HashMap<>();
        String enCurso;
        final Map<String, Integer> reintentos = new HashMap<>();
        /** Servicios cuya última verificación terminó (con resultado o con error) desde su última generación. */
        final Set<String> intentados = new LinkedHashSet<>();
        final Map<String, List<String>> reintentables = new LinkedHashMap<>();
        /**
         * Pista en memoria (solo para nota y puertas) de los casos con fallo de aserción por revisar de cada servicio; la
         * verdad está en spec.json y se reconcilia (solo quitando) con {@link #retenerRevisiones}.
         */
        final Map<String, List<String>> revisables = new LinkedHashMap<>();
        final Map<String, Integer> intentoMontaje = new HashMap<>();
        final Map<String, ResultadoTest> ultimos = new HashMap<>();
        /** Rechazos de la puerta por servicio bloqueante desde su último resultado o generación. */
        final Map<String, Integer> rechazosPuerta = new HashMap<>();
        final List<String> avisos = new ArrayList<>();
        final List<String> alertas = new ArrayList<>();
        /** Servicios con alerta de integridad aún no anunciada en una nota. */
        final Set<String> alertasSinNota = new LinkedHashSet<>();
        String jdkUsado;
        /** true si los fallos de esta salida se arreglan al final de la tanda (sin nota ni puerta de montaje/revisión). */
        boolean alFinal;
        /** FQN → ids corregidos en la fase de arreglo cuyo fallo cuenta como intento en la próxima verificación. */
        final Map<String, Set<String>> contar = new HashMap<>();

        EstadoVerificaciones(Path salida) {
            this.salida = salida;
        }

        boolean inactiva() {
            return cola.isEmpty() && enCurso == null;
        }

        /** Sin ámbito (null), como {@link #inactiva()}; con ámbito, ningún servicio del ámbito está en cola ni en curso. */
        boolean inactiva(Set<String> ambito) {
            if (ambito == null) {
                return inactiva();
            }
            for (String fqn : cola) {
                if (ambito.contains(fqn)) {
                    return false;
                }
            }
            return enCurso == null || !ambito.contains(enCurso);
        }
    }

    private final SesionMcp sesion;
    private final Object monitor = new Object();
    private final Map<String, EstadoVerificaciones> estados = new LinkedHashMap<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "junit-gen-verificador");
        t.setDaemon(true);
        return t;
    });
    private volatile boolean cerrado;

    VerificacionesSesion(SesionMcp sesion) {
        this.sesion = sesion;
    }

    private static String clave(Path salidaReal) {
        return salidaReal.toString().toLowerCase(Locale.ROOT);
    }

    private EstadoVerificaciones estado(Path salida) {
        return salida == null ? null : estados.get(clave(salida));
    }

    // ------------------------------------------------------------- cola

    /**
     * Encola la verificación de esos servicios (tests recién generados o reejecución forzada): no duplica los que ya
     * están en cola; si un servicio está en curso, se vuelve a encolar para después.
     */
    void encolar(Path proyecto, Path salida, Collection<String> fqns) {
        encolar(proyecto, salida, fqns, true);
    }

    /** Como {@link #encolar} pero omite los servicios en cola, en curso o ya verificados desde su última generación. */
    void encolarPendientes(Path proyecto, Path salida, Collection<String> fqns) {
        encolar(proyecto, salida, fqns, false);
    }

    private void encolar(Path proyecto, Path salida, Collection<String> fqns, boolean forzar) {
        if (proyecto == null || salida == null || fqns == null || fqns.isEmpty() || cerrado) {
            return;
        }
        boolean alFinal = GuiaTanda.arreglosAlFinal(sesion, proyecto);
        String k = clave(salida);
        synchronized (monitor) {
            EstadoVerificaciones e = estados.computeIfAbsent(k, x -> new EstadoVerificaciones(salida));
            e.alFinal = alFinal;
            for (String fqn : fqns) {
                if (fqn == null || fqn.isBlank()) {
                    continue;
                }
                if (!forzar && (e.cola.contains(fqn) || fqn.equals(e.enCurso) || e.intentados.contains(fqn))) {
                    continue;
                }
                e.intentados.remove(fqn);
                e.reintentos.remove(fqn);
                e.rechazosPuerta.remove(fqn);
                e.proyectos.put(fqn, proyecto);
                if (e.cola.add(fqn)) {
                    enviar(k);
                }
            }
            monitor.notifyAll();
        }
    }

    /**
     * Fase de arreglo: encola (forzado) la verificación del servicio tras aplicar una corrección; los fallos de
     * {@code ids} contarán como intento en esa verificación.
     */
    void encolarArreglo(Path proyecto, Path salida, String fqn, Collection<String> ids) {
        if (proyecto == null || salida == null || fqn == null || fqn.isBlank() || cerrado) {
            return;
        }
        boolean alFinal = GuiaTanda.arreglosAlFinal(sesion, proyecto);
        String k = clave(salida);
        synchronized (monitor) {
            EstadoVerificaciones e = estados.computeIfAbsent(k, x -> new EstadoVerificaciones(salida));
            e.alFinal = alFinal;
            if (ids != null) {
                Set<String> c = e.contar.computeIfAbsent(fqn, x -> new LinkedHashSet<>());
                for (String id : ids) {
                    if (id != null) {
                        c.add(id);
                    }
                }
            }
            e.intentados.remove(fqn);
            e.reintentos.remove(fqn);
            e.rechazosPuerta.remove(fqn);
            e.proyectos.put(fqn, proyecto);
            if (e.cola.add(fqn)) {
                enviar(k);
            }
            monitor.notifyAll();
        }
    }

    /** Fija el punto de restauración de la corrección común de {@code fqn}, que se evaluará en su próxima verificación. */
    void fijarPuntoComun(Path salida, String fqn, PuntoComun p) {
        if (salida == null || fqn == null || fqn.isBlank() || p == null || cerrado) {
            return;
        }
        synchronized (monitor) {
            EstadoVerificaciones e = estados.computeIfAbsent(clave(salida), x -> new EstadoVerificaciones(salida));
            e.puntosComun.put(fqn, p);
        }
    }

    /** Lanza un trabajo que procesará el siguiente servicio de la cola de la salida (con el monitor tomado). */
    private void enviar(String k) {
        try {
            executor.execute(() -> procesar(k));
        } catch (RejectedExecutionException ex) {
            System.err.println("junit-gen: verificación en segundo plano no encolada (servidor cerrándose): " + ex.getMessage());
            EstadoVerificaciones e = estados.get(k);
            if (e != null) {
                e.cola.clear();
            }
        }
    }

    /** Trabajo del hilo verificador: prueba el primer servicio de la cola y deja su resultado. */
    private void procesar(String k) {
        EstadoVerificaciones e;
        String fqn;
        Path proyecto;
        boolean alFinal;
        Set<String> contar;
        synchronized (monitor) {
            e = estados.get(k);
            if (e == null || e.cola.isEmpty()) {
                return;
            }
            Iterator<String> it = e.cola.iterator();
            fqn = it.next();
            it.remove();
            e.enCurso = fqn;
            proyecto = e.proyectos.get(fqn);
            alFinal = e.alFinal;
            contar = alFinal ? new LinkedHashSet<>(e.contar.getOrDefault(fqn, Set.of())) : null;
        }
        Path salida = e.salida;
        try {
            if (cerrado || Thread.currentThread().isInterrupted()) {
                return;
            }
            List<String> ids = sesion.casosDeServicio(salida, fqn);
            if (ids.isEmpty()) {
                synchronized (monitor) {
                    e.intentados.add(fqn);
                }
                return;
            }
            ResultadoVerificacion rv = sesion.fachada().verificarServicio(proyecto, salida, fqn, ids, contar);
            if (cerrado || Thread.currentThread().isInterrupted()) {
                return;
            }
            if (rv.descartada) {
                synchronized (monitor) {
                    int n = e.reintentos.merge(fqn, 1, Integer::sum);
                    if (n <= MAX_REINTENTOS_DESCARTE) {
                        if (e.cola.add(fqn)) {
                            enviar(k);
                        }
                    } else {
                        e.reintentos.remove(fqn);
                        e.intentados.add(fqn);
                        anadirAviso(e.avisos, "Verificación de " + Textos.nombreSimple(fqn) + " descartada " + MAX_REINTENTOS_DESCARTE
                                + " veces porque sus tests cambiaban durante la prueba: llama a ejecutar_tests al terminar de generar.");
                    }
                }
                return;
            }
            Set<String> propios = new LinkedHashSet<>(ids);
            Map<String, String> estadosCaso = new LinkedHashMap<>();
            for (ResultadoTest t : rv.resultados) {
                if (t.caso != null && propios.contains(t.caso) && t.estado != null) {
                    estadosCaso.put(t.caso, t.estado);
                }
            }
            PuntoComun punto;
            synchronized (monitor) {
                punto = e.puntosComun.remove(fqn);
            }
            if (alFinal && punto != null && proyecto != null) {
                String motivoComun = ArreglosFinal.rompeComun(punto, rv, propios);
                if (motivoComun != null && ArreglosFinal.deshacerComun(sesion, proyecto, salida, fqn, punto, motivoComun)) {
                    synchronized (monitor) {
                        e.contar.remove(fqn);
                        e.intentados.remove(fqn);
                        e.reintentos.remove(fqn);
                        if (e.cola.add(fqn)) {
                            enviar(k);
                        }
                    }
                    return;
                }
            }
            sesion.registrarEstados(salida, estadosCaso);
            boolean auto = HerramientaEjecutar.revisionAutomatica(proyecto);
            synchronized (monitor) {
                e.reintentos.remove(fqn);
                e.intentados.add(fqn);
                e.rechazosPuerta.remove(fqn);
                if (contar != null && !contar.isEmpty()) {
                    Set<String> pendientesContar = e.contar.get(fqn);
                    if (pendientesContar != null) {
                        pendientesContar.removeAll(contar);
                        if (pendientesContar.isEmpty()) {
                            e.contar.remove(fqn);
                        }
                    }
                }
                for (ResultadoTest t : rv.resultados) {
                    if (t.caso != null && propios.contains(t.caso)) {
                        e.ultimos.put(t.caso, t);
                    }
                }
                List<String> reintentables = new ArrayList<>();
                for (String id : rv.montajeReintentables) {
                    if (propios.contains(id)) {
                        reintentables.add(id);
                    }
                }
                e.reintentables.put(fqn, List.copyOf(reintentables));
                Set<String> revisables = new LinkedHashSet<>();
                if (auto) {
                    for (String id : rv.asercionesPendientes) {
                        if (propios.contains(id)) {
                            revisables.add(id);
                        }
                    }
                    for (String id : rv.revisionIaPendiente) {
                        if (propios.contains(id)) {
                            revisables.add(id);
                        }
                    }
                }
                e.revisables.put(fqn, List.copyOf(revisables));
                e.intentoMontaje.put(fqn, rv.intentoMontaje);
                for (String a : rv.avisos) {
                    anadirAviso(e.avisos, a);
                }
                if (rv.alertaIntegridad != null && !rv.alertaIntegridad.isEmpty()) {
                    for (String a : rv.alertaIntegridad) {
                        anadirAviso(e.alertas, a);
                    }
                    e.alertasSinNota.add(fqn);
                }
                if (rv.jdkUsado != null && !rv.jdkUsado.isBlank()) {
                    e.jdkUsado = rv.jdkUsado;
                }
            }
            if (alFinal) {
                // Fase de arreglo: apunta (o actualiza) los casos fallidos en tanda.json; nunca con el monitor tomado.
                try {
                    sesion.anotarImportsReparados(proyecto, rv.importsReparados);
                    ArreglosFinal.sincronizar(sesion, proyecto, salida, fqn);
                    ArreglosFinal.comprobarComun(sesion, proyecto, salida, fqn, contar);
                } catch (RuntimeException ex) {
                    System.err.println("junit-gen: no se pudieron apuntar los fallos de " + fqn + " para arreglar al final: "
                            + ex.getClass().getSimpleName() + ": " + ex.getMessage());
                    synchronized (monitor) {
                        anadirAviso(e.avisos, "No se pudieron apuntar los fallos de " + Textos.nombreSimple(fqn) + " para arreglarlos al "
                                + "final: " + LimitesRespuesta.recortar(String.valueOf(ex.getMessage()), HerramientaEjecutar.MAX_MENSAJE));
                    }
                }
            }
        } catch (RuntimeException ex) {
            System.err.println("junit-gen: verificación en segundo plano de " + fqn + " interrumpida: " + ex);
            ex.printStackTrace(System.err);
            synchronized (monitor) {
                e.intentados.add(fqn);
                anadirAviso(e.avisos, "Verificación de " + Textos.nombreSimple(fqn) + " interrumpida: "
                        + LimitesRespuesta.recortar(String.valueOf(ex.getMessage()), HerramientaEjecutar.MAX_MENSAJE));
            }
        } finally {
            synchronized (monitor) {
                if (fqn.equals(e.enCurso)) {
                    e.enCurso = null;
                }
                monitor.notifyAll();
            }
            if (!cerrado && proyecto != null) {
                // Estado persistente de la tanda (tanda.json): guardarTanda nunca lanza (los fallos van a stderr).
                sesion.guardarTanda(proyecto, salida);
            }
        }
    }

    private static void anadirAviso(List<String> lista, String aviso) {
        if (aviso != null && !aviso.isBlank() && !lista.contains(aviso) && lista.size() < MAX_AVISOS) {
            lista.add(aviso);
        }
    }

    // ------------------------------------------------------------- consultas

    /**
     * true si la salida no tiene nada en cola ni en curso; en una llamada con bloque, si ningún servicio del bloque está en
     * cola ni en curso.
     */
    boolean inactiva(Path salida) {
        Set<String> ambito = sesion.ambitoLlamada();
        synchronized (monitor) {
            EstadoVerificaciones e = estado(salida);
            return e == null || e.inactiva(ambito);
        }
    }

    /** true si el servicio está en cola o en curso en la salida. */
    boolean ocupado(Path salida, String fqn) {
        synchronized (monitor) {
            EstadoVerificaciones e = estado(salida);
            return e != null && fqn != null && (e.cola.contains(fqn) || fqn.equals(e.enCurso));
        }
    }

    /**
     * Espera como máximo {@code max} a que la salida quede inactiva, comprobando la cancelación de la llamada en
     * curso al menos cada {@link #SONDEO_MS} ms. Devuelve true si quedó inactiva.
     */
    boolean esperarInactiva(Path salida, Duration max) {
        long limite = System.nanoTime() + Math.max(0, max == null ? 0 : max.toNanos());
        // El ámbito se calcula antes de tomar el monitor (toma el monitor de la tanda).
        Set<String> ambito = sesion.ambitoLlamada();
        // Mientras espera, la llamada suelta el cerrojo de sesión para que otros bloques sigan; nunca se readquiere con
        // el monitor tomado (el cerrojo se recupera al salir del bloque sincronizado).
        return sesion.sinCerrojo(() -> {
            synchronized (monitor) {
                while (true) {
                    EstadoVerificaciones e = estado(salida);
                    if (e == null || e.inactiva(ambito)) {
                        return true;
                    }
                    long resto = TimeUnit.NANOSECONDS.toMillis(limite - System.nanoTime());
                    if (resto <= 0 || sesion.llamadaCancelada() || cerrado) {
                        return false;
                    }
                    try {
                        monitor.wait(Math.min(SONDEO_MS, resto));
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
            }
        });
    }

    /** Progreso: servicios con casos en la sesión, verificados (todos sus casos con estado), en curso y en cola. */
    Progreso progreso(Path salida) {
        Map<String, String> servicios = sesion.serviciosDeCasos(salida);
        Map<String, String> estadosCaso = sesion.estadosDeSesion(salida);
        Map<String, Boolean> completos = new LinkedHashMap<>();
        servicios.forEach((id, fqn) -> {
            if (fqn != null) {
                completos.merge(fqn, estadosCaso.containsKey(id), Boolean::logicalAnd);
            }
        });
        int verificados = (int) completos.values().stream().filter(Boolean::booleanValue).count();
        synchronized (monitor) {
            EstadoVerificaciones e = estado(salida);
            String enCurso = e == null || e.enCurso == null ? null : Textos.nombreSimple(e.enCurso);
            int enCola = e == null ? 0 : e.cola.size();
            return new Progreso(verificados, enCurso, enCola, completos.size());
        }
    }

    /**
     * Correcciones de montaje pendientes: por servicio verificado, los ids reintentables cuyo estado de sesión sigue
     * siendo error_montaje o no_compila (los regenerados pierden estado y salen solos).
     */
    List<Correccion> correcciones(Path salida) {
        Map<String, List<String>> reintentables;
        Map<String, Integer> intentos;
        synchronized (monitor) {
            EstadoVerificaciones e = estado(salida);
            if (e == null || e.reintentables.isEmpty()) {
                return List.of();
            }
            reintentables = new LinkedHashMap<>(e.reintentables);
            intentos = new HashMap<>(e.intentoMontaje);
        }
        Map<String, String> estadosCaso = sesion.estadosDeSesion(salida);
        List<Correccion> r = new ArrayList<>();
        reintentables.forEach((fqn, ids) -> {
            List<String> pendientes = new ArrayList<>();
            for (String id : ids) {
                String est = estadosCaso.get(id);
                if (ClasificadorFallos.ERROR_MONTAJE.equals(est) || ClasificadorFallos.NO_COMPILA.equals(est)) {
                    pendientes.add(id);
                }
            }
            if (!pendientes.isEmpty()) {
                r.add(new Correccion(fqn, Math.max(1, intentos.getOrDefault(fqn, 1)), List.copyOf(pendientes)));
            }
        });
        return r;
    }

    /**
     * Revisiones de fallos de aserción pendientes (modo revisarIa): por servicio verificado, los ids revisables cuyo
     * estado de sesión sigue siendo fallo_asercion (los regenerados pierden estado y salen solos).
     */
    List<Revision> revisiones(Path salida) {
        Map<String, List<String>> revisables;
        synchronized (monitor) {
            EstadoVerificaciones e = estado(salida);
            if (e == null || e.revisables.isEmpty()) {
                return List.of();
            }
            revisables = new LinkedHashMap<>(e.revisables);
        }
        Map<String, String> estadosCaso = sesion.estadosDeSesion(salida);
        List<Revision> r = new ArrayList<>();
        revisables.forEach((fqn, ids) -> {
            List<String> pendientes = new ArrayList<>();
            for (String id : ids) {
                if (ClasificadorFallos.FALLO_ASERCION.equals(estadosCaso.get(id))) {
                    pendientes.add(id);
                }
            }
            if (!pendientes.isEmpty()) {
                r.add(new Revision(fqn, List.copyOf(pendientes)));
            }
        });
        return r;
    }

    /**
     * Reconciliación de la pista de revisiones con spec.json: deja en cada servicio solo los ids presentes en
     * {@code idsPendientes} (nunca añade).
     */
    void retenerRevisiones(Path salida, Collection<String> idsPendientes) {
        if (salida == null || idsPendientes == null) {
            return;
        }
        Set<String> pendientes = new LinkedHashSet<>(idsPendientes);
        synchronized (monitor) {
            EstadoVerificaciones e = estado(salida);
            if (e == null) {
                return;
            }
            Iterator<Map.Entry<String, List<String>>> it = e.revisables.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, List<String>> en = it.next();
                List<String> quedan = new ArrayList<>();
                for (String id : en.getValue()) {
                    if (pendientes.contains(id)) {
                        quedan.add(id);
                    }
                }
                if (quedan.isEmpty()) {
                    it.remove();
                } else {
                    en.setValue(List.copyOf(quedan));
                }
            }
        }
    }

    /** true si la salida tiene correcciones de montaje o revisiones de fallos de aserción pendientes. */
    boolean pendientes(Path salida) {
        return salida != null && (!correcciones(salida).isEmpty() || !revisiones(salida).isEmpty());
    }

    /**
     * true si hay que corregir antes de seguir (puerta de montaje/revisión): la salida tiene pendientes y sus fallos no
     * se arreglan al final de la tanda.
     */
    boolean puertaActiva(Path salida) {
        boolean alFinal;
        synchronized (monitor) {
            EstadoVerificaciones e = estado(salida);
            if (e == null) {
                return false;
            }
            alFinal = e.alFinal;
        }
        return !alFinal && pendientes(salida);
    }

    /** true si los fallos de la salida se arreglan al final de la tanda. */
    private boolean alFinal(Path salida) {
        synchronized (monitor) {
            EstadoVerificaciones e = estado(salida);
            return e != null && e.alFinal;
        }
    }

    /** Quita de las correcciones de montaje en memoria esos casos del servicio (ya apartados). */
    void quitarReintentables(Path salida, String fqn, Collection<String> ids) {
        if (salida == null || fqn == null || ids == null) {
            return;
        }
        synchronized (monitor) {
            EstadoVerificaciones e = estado(salida);
            if (e == null) {
                return;
            }
            List<String> actuales = e.reintentables.get(fqn);
            if (actuales != null) {
                List<String> quedan = new ArrayList<>(actuales);
                quedan.removeAll(ids);
                e.reintentables.put(fqn, List.copyOf(quedan));
            }
            e.rechazosPuerta.remove(fqn);
        }
    }

    /** Olvida todas las correcciones y revisiones pendientes en memoria de la salida (red de seguridad del cierre). */
    void olvidarCorrecciones(Path salida) {
        synchronized (monitor) {
            EstadoVerificaciones e = estado(salida);
            if (e == null) {
                return;
            }
            e.reintentables.clear();
            e.revisables.clear();
            e.rechazosPuerta.clear();
        }
    }

    /**
     * Nota de cabecera (≤ {@link #MAX_NOTA} bytes) con la primera corrección de montaje pendiente (o, si no hay
     * ninguna, la primera revisión de fallos de aserción) y, una sola vez, las alertas de integridad nuevas; null si
     * no hay nada que anunciar.
     */
    String nota() {
        List<Path> salidas;
        synchronized (monitor) {
            salidas = new ArrayList<>();
            for (EstadoVerificaciones e : estados.values()) {
                if (!e.alFinal) {
                    salidas.add(e.salida);
                }
            }
        }
        StringBuilder sb = new StringBuilder();
        for (Path salida : salidas) {
            List<Correccion> cs = correcciones(salida);
            if (!cs.isEmpty()) {
                Correccion c = cs.get(0);
                sb.append("Verificación en segundo plano: `").append(Textos.nombreSimple(c.fqn())).append("` tiene ")
                        .append(c.ids().size()).append(c.ids().size() == 1 ? " caso que no compila o falla" : " casos que no compilan o fallan")
                        .append(" al montarse (intento ").append(c.intento()).append(" de ").append(VerificadorTests.MAX_INTENTOS)
                        .append("). Corrígelos ahora: estado_verificacion {} trae errores y pistas.");
                break;
            }
        }
        if (sb.length() == 0) {
            for (Path salida : salidas) {
                List<Revision> rs = revisiones(salida);
                if (!rs.isEmpty()) {
                    Revision r = rs.get(0);
                    sb.append("Verificación en segundo plano: `").append(Textos.nombreSimple(r.fqn())).append("` tiene ")
                            .append(r.ids().size()).append(r.ids().size() == 1 ? " fallo de aserción" : " fallos de aserción")
                            .append(" sin revisar. Revísalos ahora sin preguntar al usuario: estado_verificacion {}.");
                    break;
                }
            }
        }
        synchronized (monitor) {
            for (EstadoVerificaciones e : estados.values()) {
                if (!e.alertasSinNota.isEmpty()) {
                    String fqn = e.alertasSinNota.iterator().next();
                    e.alertasSinNota.clear();
                    sb.append(sb.length() == 0 ? "" : " ").append("Alerta de integridad durante la verificación de `")
                            .append(Textos.nombreSimple(fqn)).append("` (ver informe.md).");
                    break;
                }
            }
        }
        return sb.length() == 0 ? null : LimitesRespuesta.recortar(sb.toString(), MAX_NOTA);
    }

    /**
     * Puerta de montaje y de revisión: null si no hay correcciones ni revisiones pendientes o si el objetivo es uno de
     * los servicios a corregir o revisar; si no, el texto de rechazo para no empezar otro servicio (montaje primero).
     * Nunca rechaza con datos viejos: si el servicio que bloquea está en cola o en curso espera hasta
     * {@link #ESPERA_PUERTA} y, si sigue ocupado, deja pasar. Con intento ≥ {@link #INTENTO_APARTAR} o al segundo
     * intento de avanzar sin regenerar, aparta los casos rotos (junit-gen los comenta) y deja pasar; las revisiones
     * dejan pasar tras {@link #MAX_RECHAZOS_REVISION} rechazos (sin cambiar estados).
     */
    String bloqueo(Path salida, String fqnObjetivo) {
        if (alFinal(salida)) {
            return null;
        }
        boolean esperado = false;
        for (int vuelta = 0; vuelta < 50; vuelta++) {
            List<Correccion> cs = correcciones(salida);
            List<Revision> rs = revisiones(salida);
            if (cs.isEmpty() && rs.isEmpty()) {
                return null;
            }
            for (Correccion c : cs) {
                if (c.fqn().equals(fqnObjetivo)) {
                    return null;
                }
            }
            for (Revision r : rs) {
                if (r.fqn().equals(fqnObjetivo)) {
                    return null;
                }
            }
            String bloqueante = cs.isEmpty() ? rs.get(0).fqn() : cs.get(0).fqn();
            if (ocupado(salida, bloqueante)) {
                if (esperado) {
                    return null;
                }
                esperado = true;
                esperarInactiva(salida, ESPERA_PUERTA);
                continue;
            }
            if (!cs.isEmpty()) {
                Correccion c = cs.get(0);
                Map<String, ResultadoTest> resultados = null;
                synchronized (monitor) {
                    EstadoVerificaciones e = estado(salida);
                    int rechazos = e == null ? 0 : e.rechazosPuerta.getOrDefault(c.fqn(), 0);
                    if (e != null && c.intento() < INTENTO_APARTAR && rechazos < 1) {
                        e.rechazosPuerta.put(c.fqn(), rechazos + 1);
                        resultados = new LinkedHashMap<>();
                        for (String id : c.ids()) {
                            resultados.put(id, e.ultimos.get(id));
                        }
                    }
                }
                if (resultados == null) {
                    apartar(salida, c);
                    continue;
                }
                return textoRechazoMontaje(fqnObjetivo, c, resultados);
            }
            Revision r = rs.get(0);
            synchronized (monitor) {
                EstadoVerificaciones e = estado(salida);
                int rechazos = e == null ? 0 : e.rechazosPuerta.getOrDefault(r.fqn(), 0);
                if (e == null || rechazos >= MAX_RECHAZOS_REVISION) {
                    if (e != null) {
                        e.rechazosPuerta.remove(r.fqn());
                    }
                    return null;
                }
                e.rechazosPuerta.put(r.fqn(), rechazos + 1);
            }
            String idsR = LimitesRespuesta.listaCorta(r.ids(), MAX_IDS_PUERTA);
            return "No empieces `" + Textos.nombreSimple(fqnObjetivo) + "` todavía: `" + Textos.nombreSimple(r.fqn()) + "` tiene "
                    + r.ids().size() + (r.ids().size() == 1 ? " fallo de aserción" : " fallos de aserción") + " sin revisar ("
                    + idsR + "). No preguntes al usuario: 1) estado_verificacion {} 2) detalle_servicio {clase: \"" + r.fqn()
                    + "\", metodos: [...]} y decide por caso 3) registrar_analisis_ia (y, para error_ia, generar_tests con "
                    + "reemplazarCasos). Después repite esta llamada. No escribas al usuario.";
        }
        return null;
    }

    /** Rechazo de la puerta de montaje con los errores, pistas e ids para corregir en la misma respuesta. */
    private static String textoRechazoMontaje(String fqnObjetivo, Correccion c, Map<String, ResultadoTest> resultados) {
        int n = c.ids().size();
        List<String> idsJson = new ArrayList<>();
        for (String id : c.ids()) {
            if (idsJson.size() >= MAX_IDS_PUERTA) {
                break;
            }
            idsJson.add("\"" + id + "\"");
        }
        StringBuilder sb = new StringBuilder();
        sb.append("No empieces `").append(Textos.nombreSimple(fqnObjetivo)).append("` todavía: `").append(Textos.nombreSimple(c.fqn()))
                .append("` tiene ").append(n).append(n == 1 ? " caso que no compila o falla" : " casos que no compilan o fallan")
                .append(" al montarse (intento ").append(c.intento()).append(" de ").append(VerificadorTests.MAX_INTENTOS)
                .append("). Corrige SOLO esos casos en el spec (nunca el .java) y llama a generar_tests {spec, reemplazarCasos: [")
                .append(String.join(", ", idsJson)).append("]}; se vuelven a probar solos. Después repite esta llamada. No escribas al "
                        + "usuario ni te detengas: si no consigues arreglarlos, repite esta llamada y junit-gen los apartará solo.");
        // Agrupa los casos con el mismo error (un error común sale una vez).
        Map<String, List<String>> grupos = new LinkedHashMap<>();
        Map<String, ResultadoTest> muestra = new LinkedHashMap<>();
        for (String id : c.ids()) {
            ResultadoTest t = resultados.get(id);
            String clave = t == null ? "sin-resultado" : "r|" + t.estado + "|" + t.mensaje + "|" + t.pista;
            grupos.computeIfAbsent(clave, k -> new ArrayList<>()).add(id);
            muestra.putIfAbsent(clave, t);
        }
        sb.append("\nErrores:");
        int lineas = 0;
        for (Map.Entry<String, List<String>> g : grupos.entrySet()) {
            if (lineas >= MAX_ERRORES_PUERTA) {
                sb.append("\n… y ").append(grupos.size() - lineas).append(" más: estado_verificacion {}");
                break;
            }
            ResultadoTest t = muestra.get(g.getKey());
            List<String> ids = g.getValue();
            sb.append("\n- ").append(LimitesRespuesta.listaCorta(ids, MAX_IDS_PUERTA));
            if (t == null) {
                sb.append(": sin resultado en memoria; estado_verificacion {} trae el detalle.");
            } else {
                if (ids.size() == 1 && t.metodoTest != null && !t.metodoTest.isBlank()) {
                    sb.append(" (").append(t.metodoTest).append(')');
                }
                sb.append(" [").append(t.estado).append("]: ")
                        .append(LimitesRespuesta.recortar(unaLinea(t.mensaje), HerramientaEjecutar.MAX_MENSAJE)).append('.');
                if (t.pista != null && !t.pista.isBlank()) {
                    sb.append(" Pista: ").append(LimitesRespuesta.recortar(unaLinea(t.pista), MAX_PISTA_PUERTA));
                }
            }
            lineas++;
        }
        return LimitesRespuesta.recortar(sb.toString(), MAX_TEXTO_PUERTA);
    }

    private static String unaLinea(String s) {
        return s == null ? "" : s.replaceAll("\\s*\\R\\s*", " ").trim();
    }

    /**
     * Aparta los casos de la corrección (no se pudieron arreglar): junit-gen los marca montaje_fallido, comenta su
     * bloque y lo anota en informe.md. Aunque falle la escritura, se quitan de memoria para no bloquear la tanda.
     * No sujeta el monitor durante la E/S ni durante la llamada a la fachada.
     */
    private void apartar(Path salida, Correccion c) {
        Path proyecto;
        Path salidaReal;
        Map<String, ResultadoTest> casos = new LinkedHashMap<>();
        synchronized (monitor) {
            EstadoVerificaciones e = estado(salida);
            if (e == null) {
                return;
            }
            proyecto = e.proyectos.get(c.fqn());
            salidaReal = e.salida;
            for (String id : c.ids()) {
                casos.put(id, e.ultimos.get(id));
            }
        }
        String error = null;
        try {
            if (proyecto == null) {
                error = "proyecto desconocido";
            } else {
                sesion.fachada().apartarCasos(proyecto, salidaReal, casos);
            }
        } catch (RuntimeException ex) {
            error = String.valueOf(ex.getMessage());
            System.err.println("junit-gen: no se pudieron apartar los casos de " + c.fqn() + ": " + ex);
        }
        synchronized (monitor) {
            EstadoVerificaciones e = estado(salida);
            if (e != null) {
                List<String> actuales = e.reintentables.get(c.fqn());
                if (actuales != null) {
                    List<String> quedan = new ArrayList<>(actuales);
                    quedan.removeAll(c.ids());
                    e.reintentables.put(c.fqn(), List.copyOf(quedan));
                }
                e.rechazosPuerta.remove(c.fqn());
                if (error != null) {
                    anadirAviso(e.avisos, "No se pudieron apartar en disco los casos " + LimitesRespuesta.listaCorta(c.ids(), MAX_IDS_PUERTA)
                            + " de " + Textos.nombreSimple(c.fqn()) + " (" + LimitesRespuesta.recortar(error, HerramientaEjecutar.MAX_MENSAJE)
                            + "): se dejan pasar igualmente; revísalos en informe.md.");
                }
            }
        }
        int n = c.ids().size();
        sesion.anotar("junit-gen ha apartado " + n + (n == 1 ? " caso" : " casos") + " de `" + Textos.nombreSimple(c.fqn())
                + "` que no se pudieron arreglar (quedan comentados en su test y anotados en informe.md). No los corrijas: sigue con el plan.");
        if (proyecto != null) {
            sesion.guardarTanda(proyecto, salidaReal);
        }
    }

    /** Último resultado verificado en segundo plano del caso, o null. */
    ResultadoTest ultimo(Path salida, String caso) {
        synchronized (monitor) {
            EstadoVerificaciones e = estado(salida);
            return e == null || caso == null ? null : e.ultimos.get(caso);
        }
    }

    /** Avisos acumulados por las verificaciones de la salida (copia). */
    List<String> avisos(Path salida) {
        synchronized (monitor) {
            EstadoVerificaciones e = estado(salida);
            return e == null ? List.of() : List.copyOf(e.avisos);
        }
    }

    /** Alertas de integridad acumuladas por las verificaciones de la salida (copia). */
    List<String> alertas(Path salida) {
        synchronized (monitor) {
            EstadoVerificaciones e = estado(salida);
            return e == null ? List.of() : List.copyOf(e.alertas);
        }
    }

    /** JDK usado en la última verificación de la salida, o null. */
    String jdkUsado(Path salida) {
        synchronized (monitor) {
            EstadoVerificaciones e = estado(salida);
            return e == null ? null : e.jdkUsado;
        }
    }

    /** Cancela las verificaciones (la interrupción mata el proceso hijo) y espera de forma acotada. */
    void cerrar() {
        cerrado = true;
        synchronized (monitor) {
            for (EstadoVerificaciones e : estados.values()) {
                e.cola.clear();
            }
            monitor.notifyAll();
        }
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(ESPERA_CIERRE.toMillis(), TimeUnit.MILLISECONDS)) {
                System.err.println("junit-gen: el verificador en segundo plano no terminó en " + ESPERA_CIERRE.toSeconds() + " s.");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            System.err.println("junit-gen: interrumpida la espera del verificador en segundo plano.");
        }
    }
}
