package com.junitgen.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.junitgen.Fachada;
import com.junitgen.escaneo.SeleccionFunciones;
import com.junitgen.generacion.AlmacenSpec;
import com.junitgen.generacion.AlmacenTanda;
import com.junitgen.generacion.RutasSalida;
import com.junitgen.generacion.Tanda;
import com.junitgen.seguridad.BloqueosSalida;
import com.junitgen.seguridad.EscritorSeguro;
import com.junitgen.seguridad.RutaSegura;
import com.junitgen.spec.CasoSpec;
import com.junitgen.spec.SeleccionSpec;
import com.junitgen.spec.Spec;
import com.junitgen.util.Json;
import com.junitgen.util.LimitesRespuesta;
import com.junitgen.verificacion.ClasificadorFallos;
import com.junitgen.verificacion.VerificadorTests;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.UnaryOperator;

/**
 * Estado de la sesión MCP: capabilities del cliente, versión del protocolo, fachada (con la caché
 * del último escaneo por ruta) y peticiones servidor→cliente pendientes (ids srv-N).
 */
public class SesionMcp {

    private final TransporteStdio transporte;
    private final Fachada fachada;
    private final Map<String, CompletableFuture<JsonNode>> pendientes = new ConcurrentHashMap<>();
    private final Set<String> cancelados = ConcurrentHashMap.newKeySet();
    private final AtomicLong contador = new AtomicLong();
    private volatile JsonNode capacidadesCliente;
    private volatile String protocolo;
    private volatile boolean cerrada;
    private volatile List<Raices.Raiz> raices;
    private final AtomicLong generacionRaices = new AtomicLong();
    private final Map<String, Path> salidas = new ConcurrentHashMap<>();
    private final Set<String> salidaPreguntada = ConcurrentHashMap.newKeySet();
    private final EntornoSesion entorno;
    private final RutasPorDefecto rutasPorDefecto;
    private volatile String decisionVerificacion;
    private volatile boolean verificacionPreguntada;
    /** La pregunta [Probar tests] [Descartar] quedó sin responder (caducó o no hubo tiempo). */
    private volatile boolean verificacionSinRespuesta;
    /** Id de la petición tools/call (u otra) en curso en el worker, o null. */
    private volatile String idLlamada;
    /** System.nanoTime() del momento en que el lector recibió la petición en curso. */
    private volatile long inicioLlamadaNanos;
    /** Futuro de la petición servidor→cliente que el worker está esperando, o null. */
    private volatile CompletableFuture<JsonNode> esperaEnCurso;
    private final Map<String, Set<String>> clasesGeneradas = new ConcurrentHashMap<>();
    private final Map<String, Integer> casosGenerados = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> modulosTrabajo = new ConcurrentHashMap<>();
    private final Map<String, Plan> planes = new ConcurrentHashMap<>();
    private final Set<String> asercionesPreguntadas = ConcurrentHashMap.newKeySet();
    private final List<String> notas = new ArrayList<>();
    private final Map<String, EstadoSalida> estadosSalida = new ConcurrentHashMap<>();
    private final VerificacionesSesion verificaciones;
    private volatile boolean ejecucionParcialAutorizada;
    private boolean cwdCalculado;
    private Path cwdValido;
    private boolean escritorioCalculado;
    private Path escritorio;
    /** Rechazos seguidos de la puerta de plan (seleccionar_funciones / detalle_servicio de otro servicio). */
    private final AtomicInteger rechazosPuertaPlan = new AtomicInteger();
    /** System.nanoTime() del fin de la última llamada a herramienta (medición de la tanda); 0 si aún no hubo ninguna. */
    private final AtomicLong finLlamadaAnterior = new AtomicLong();

    /** Llamadas válidas seguidas a generar_tests sin casos nuevos tras las que se cierra el servicio en curso. */
    public static final int MAX_SIN_PROGRESO = 2;
    /** Specs rechazados seguidos de un servicio tras los que se cierra. */
    public static final int MAX_RECHAZOS_SPEC = 4;
    /** Rechazos seguidos de la puerta de plan tras los que se abre (nunca bucle infinito). */
    public static final int MAX_RECHAZOS_PUERTA = 3;

    /** Un método del plan: servicio (FQN), nombre del método, casos previstos y si ya tenía tests. */
    public record MetodoPlan(String fqn, String metodo, int casos, boolean conTests) {
    }

    /**
     * Progreso del plan activo: casos (total, generados, restantes), métodos, pendientes por método, casos
     * escritos (ids generados, sin contar duplicados) y servicios completos / pendientes.
     */
    public record ProgresoPlan(int total, int generados, int restantes, int servicios, int metodosTotal, int metodosCompletos,
                               List<String> pendientes, int casosEscritos, List<String> serviciosCompletos,
                               List<String> serviciosPendientes) {
    }

    /** Estado de un método del plan, en el orden del plan. */
    public record EstadoMetodo(String fqn, String metodo, int previstos, int acreditados) {
        public int faltan() {
            return Math.max(0, previstos - acreditados);
        }
    }

    /** Casos generados en la sesión para una carpeta de salida y su último estado de ejecución. */
    private static final class EstadoSalida {
        /** id → FQN del servicio, en orden de inserción. */
        final Map<String, String> casos = new LinkedHashMap<>();
        final Set<String> duplicados = new LinkedHashSet<>();
        final Set<String> eliminados = new LinkedHashSet<>();
        /** id → último estado de ejecución (pasa, fallo_asercion…). */
        final Map<String, String> estados = new HashMap<>();
        /** Páginas de resultados compactos de la última ejecución (para paginaResultados). */
        List<List<Map<String, Object>>> paginasResultados;
        /** Casos en fallo_asercion cuya pregunta quedó sin responder (decisión pendiente del usuario). */
        final Set<String> asercionesSinRespuesta = new LinkedHashSet<>();
    }

    /** Cómo terminó la espera de una petición servidor→cliente. */
    public enum FinEspera { RESPONDIDA, CADUCADA, ABORTADA, FALLIDA }

    /** Resultado de {@link #solicitarYEsperar}: el fin de la espera y, si se respondió, el mensaje del cliente. */
    public record RespuestaCliente(FinEspera fin, JsonNode mensaje) {
    }

    /** Petición servidor→cliente registrada: id srv-N y futuro de su respuesta. */
    private record Solicitud(String id, CompletableFuture<JsonNode> futuro) {
    }

    /** Seguimiento de un método del plan (las sobrecargas se fusionan por nombre). */
    private static final class MetodoSeguido {
        final String fqn;
        final String metodo;
        int previstos;
        boolean conTests;
        final Set<String> generados = new LinkedHashSet<>();
        final Set<String> duplicados = new LinkedHashSet<>();
        /** Ids descartados porque ya estaban generados en la salida: cuentan siempre como atendidos. */
        final Set<String> enSalida = new LinkedHashSet<>();
        /** Casos previstos dados por atendidos por la salvaguarda anti-bucle (servicio cerrado por atasco). */
        int renunciados;

        MetodoSeguido(String fqn, String metodo, int previstos, boolean conTests) {
            this.fqn = fqn;
            this.metodo = metodo;
            this.previstos = previstos;
            this.conTests = conTests;
        }

        int acreditados() {
            return Math.min(previstos, generados.size() + enSalida.size() + (conTests ? duplicados.size() : 0) + renunciados);
        }
    }

    /** Plan de generación: métodos (clave fqn#metodo) con sus casos previstos y los ids generados o descartados. */
    private static final class Plan {
        final Map<String, MetodoSeguido> metodos = new LinkedHashMap<>();
        /** FQN de los servicios del plan (aunque luego se queden sin métodos). */
        final Set<String> fqns = new LinkedHashSet<>();
        /** FQN → llamadas válidas seguidas a generar_tests sin casos nuevos (no se persiste). */
        final Map<String, Integer> sinProgreso = new HashMap<>();
        /** FQN → specs rechazados seguidos (no se persiste). */
        final Map<String, Integer> rechazos = new HashMap<>();
        /** FQN → motivo, de los servicios cerrados por la salvaguarda anti-bucle (en orden de cierre). */
        final Map<String, String> cerrados = new LinkedHashMap<>();
    }

    public SesionMcp(TransporteStdio transporte, Fachada fachada) {
        this(transporte, fachada, EntornoSesion.delSistema());
    }

    public SesionMcp(TransporteStdio transporte, Fachada fachada, EntornoSesion entorno) {
        this.transporte = transporte;
        this.fachada = fachada;
        this.entorno = entorno;
        this.rutasPorDefecto = new RutasPorDefecto(entorno.preferencias());
        this.verificaciones = new VerificacionesSesion(this);
    }

    public Fachada fachada() {
        return fachada;
    }

    /** Verificaciones en segundo plano de la sesión (cola por carpeta de salida). */
    VerificacionesSesion verificaciones() {
        return verificaciones;
    }

    public EntornoSesion entorno() {
        return entorno;
    }

    public RutasPorDefecto rutasPorDefecto() {
        return rutasPorDefecto;
    }

    // ------------------------------------------------------------- entorno memoizado

    /** Directorio de trabajo del proceso si es un proyecto válido (calculado una vez por sesión), o null. */
    public synchronized Path directorioTrabajoValido() {
        if (!cwdCalculado) {
            cwdValido = RutasSesion.cwdValido(entorno);
            cwdCalculado = true;
        }
        return cwdValido;
    }

    /** Carpeta del Escritorio (detectada una vez por sesión), o null. */
    public synchronized Path escritorio() {
        if (!escritorioCalculado) {
            try {
                escritorio = entorno.escritorio().get();
            } catch (RuntimeException e) {
                System.err.println("No se pudo localizar el Escritorio: " + e.getMessage());
                escritorio = null;
            }
            escritorioCalculado = true;
        }
        return escritorio;
    }

    // ------------------------------------------------------------- notas para el resultado

    /** Nota visible que se insertará tras la línea de aviso del resultado de la herramienta en curso. */
    public void anotar(String nota) {
        synchronized (notas) {
            if (nota != null && !nota.isBlank() && !notas.contains(nota)) {
                notas.add(nota);
            }
        }
    }

    public List<String> consumirNotas() {
        synchronized (notas) {
            List<String> r = List.copyOf(notas);
            notas.clear();
            return r;
        }
    }

    /** Bytes UTF-8 de las notas pendientes (con su salto de línea), para reservar presupuesto. */
    public int bytesNotasPendientes() {
        synchronized (notas) {
            int b = 0;
            for (String n : notas) {
                b += LimitesRespuesta.bytes(n) + LimitesRespuesta.bytes("\nNota: ");
            }
            return b;
        }
    }

    // ------------------------------------------------------------- selección de funciones por proyecto

    /** clave(proyecto) → FQN → selección de la sesión (origen ia o usuario). */
    private final Map<String, Map<String, SeleccionSpec>> seleccionesPorProyecto = new ConcurrentHashMap<>();

    /** Guarda la selección de funciones del servicio para el resto de la sesión (ia) o la petición (usuario). */
    public void fijarSeleccion(Path proyecto, String fqn, SeleccionSpec s) {
        if (proyecto == null || fqn == null || s == null) {
            return;
        }
        try {
            seleccionesPorProyecto.computeIfAbsent(clave(RutaSegura.normalizar(proyecto)), k -> new ConcurrentHashMap<>()).put(fqn, s);
        } catch (RuntimeException e) {
            System.err.println("No se pudo recordar la selección de funciones: " + e.getMessage());
        }
    }

    /** Copia inmutable FQN → selección de la sesión del proyecto; nunca null (proyecto null → vacío). */
    public Map<String, SeleccionSpec> selecciones(Path proyecto) {
        if (proyecto == null) {
            return Map.of();
        }
        try {
            Map<String, SeleccionSpec> m = seleccionesPorProyecto.get(clave(RutaSegura.normalizar(proyecto)));
            return m == null ? Map.of() : Map.copyOf(m);
        } catch (RuntimeException e) {
            System.err.println("No se pudieron leer las selecciones de funciones: " + e.getMessage());
            return Map.of();
        }
    }

    /** Olvida las selecciones de origen usuario del proyecto (valen solo para la petición en curso). */
    public void olvidarSeleccionesUsuario(Path proyecto) {
        if (proyecto == null) {
            return;
        }
        try {
            Map<String, SeleccionSpec> m = seleccionesPorProyecto.get(clave(RutaSegura.normalizar(proyecto)));
            if (m != null) {
                m.values().removeIf(x -> SeleccionFunciones.ORIGEN_USUARIO.equals(x.origen));
            }
        } catch (RuntimeException e) {
            System.err.println("No se pudieron olvidar las selecciones del usuario: " + e.getMessage());
        }
    }

    /** true si el servicio aún no tiene selección efectiva (ni `todas`, ni sesión, ni ia en el spec.json de la salida). */
    public boolean necesitaSeleccion(Path proyecto, Path salida, String fqn) {
        if (fqn == null || todas(proyecto)) {
            return false;
        }
        return SeleccionFunciones.guardada(selecciones(proyecto), AlmacenSpec.selecciones(salida), fqn) == null;
    }

    // ------------------------------------------------------------- modo `todas` por proyecto

    private final Map<String, Boolean> todasPorProyecto = new ConcurrentHashMap<>();

    /** Fija el modo `todas` (100 % de las funciones) del proyecto para el resto de la sesión. */
    public void fijarTodas(Path proyecto, boolean todas) {
        if (proyecto == null) {
            return;
        }
        try {
            todasPorProyecto.put(clave(RutaSegura.normalizar(proyecto)), todas);
        } catch (RuntimeException e) {
            System.err.println("No se pudo recordar el modo `todas`: " + e.getMessage());
        }
    }

    /** true si el usuario pidió `todas` en el último escaneo del proyecto; false si null o desconocido. */
    public boolean todas(Path proyecto) {
        if (proyecto == null) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(todasPorProyecto.get(clave(RutaSegura.normalizar(proyecto))));
        } catch (RuntimeException e) {
            return false;
        }
    }

    // ------------------------------------------------------------- verificación (una decisión por sesión)

    /** "probar", "descartar" o null si aún no se decidió. */
    public String decisionVerificacion() {
        return decisionVerificacion;
    }

    public void fijarDecisionVerificacion(String decision) {
        this.decisionVerificacion = decision;
        if (decision != null) {
            this.verificacionSinRespuesta = false;
        }
    }

    public boolean verificacionPreguntada() {
        return verificacionPreguntada;
    }

    public void marcarVerificacionPreguntada() {
        this.verificacionPreguntada = true;
    }

    /** La pregunta [Probar tests] [Descartar] quedó sin responder: la decisión sigue pendiente del usuario. */
    public void marcarVerificacionSinRespuesta() {
        this.verificacionSinRespuesta = true;
    }

    public void olvidarVerificacionSinRespuesta() {
        this.verificacionSinRespuesta = false;
    }

    /**
     * true si [Probar tests] [Descartar] quedó sin responder y aún no hay decisión de sesión (solo con
     * {@code verificacion: preguntar} en junit-gen.yml).
     */
    public boolean verificacionSinRespuesta() {
        return verificacionSinRespuesta && decisionVerificacion == null;
    }

    // ------------------------------------------------------------- aserciones sin respuesta

    /** Los casos quedan con la pregunta de aserciones sin responder (siguen en fallo_asercion, sin decidir). */
    public void marcarAsercionesSinRespuesta(Path salidaReal, Collection<String> ids) {
        if (salidaReal == null || ids == null) {
            return;
        }
        EstadoSalida e = estadosSalida.computeIfAbsent(clave(salidaReal), x -> new EstadoSalida());
        synchronized (e) {
            for (String id : ids) {
                if (id != null && !id.isBlank()) {
                    e.asercionesSinRespuesta.add(id);
                }
            }
        }
    }

    /** Los casos ya tienen respuesta (o decisión) del usuario: salen del conjunto «sin respuesta». */
    public void resolverAsercionesSinRespuesta(Path salidaReal, Collection<String> ids) {
        EstadoSalida e = salidaReal == null || ids == null ? null : estadosSalida.get(clave(salidaReal));
        if (e == null) {
            return;
        }
        synchronized (e) {
            e.asercionesSinRespuesta.removeAll(ids);
        }
    }

    /** Número de casos de la sesión en la salida con la pregunta de aserciones sin responder. */
    public int asercionesSinRespuesta(Path salidaReal) {
        EstadoSalida e = salidaReal == null ? null : estadosSalida.get(clave(salidaReal));
        if (e == null) {
            return 0;
        }
        synchronized (e) {
            int n = 0;
            for (String id : e.asercionesSinRespuesta) {
                if (e.casos.containsKey(id)) {
                    n++;
                }
            }
            return n;
        }
    }

    // ------------------------------------------------------------- llamada en curso

    /** El worker empieza a procesar la petición {@code id}, recibida por el lector en {@code inicioNanos}. */
    public void iniciarLlamada(String id, long inicioNanos) {
        this.inicioLlamadaNanos = inicioNanos;
        this.esperaEnCurso = null;
        this.idLlamada = id;
    }

    public void terminarLlamada() {
        this.idLlamada = null;
        this.esperaEnCurso = null;
    }

    /** Tiempo transcurrido desde que se recibió la petición en curso (incluida la cola); ZERO si no hay. */
    public Duration transcurridoLlamada() {
        if (idLlamada == null) {
            return Duration.ZERO;
        }
        long t = System.nanoTime() - inicioLlamadaNanos;
        return t <= 0 ? Duration.ZERO : Duration.ofNanos(t);
    }

    /** true si el cliente canceló (notifications/cancelled) la petición en curso. */
    public boolean llamadaCancelada() {
        String id = idLlamada;
        return id != null && cancelados.contains(id);
    }

    /**
     * Registra en la salida las clases de test y los casos generados en la sesión (id → FQN del servicio),
     * los descartados por duplicado y los eliminados. Un caso regenerado pierde su estado de ejecución
     * (pasa a «sin ejecutar») y sale de duplicados y eliminados.
     */
    public void registrarGenerados(Path salidaReal, Collection<String> clases, Map<String, String> casos,
                                   Collection<String> duplicados, Collection<String> eliminados) {
        String k = clave(salidaReal);
        if (clases != null && !clases.isEmpty()) {
            clasesGeneradas.computeIfAbsent(k, x -> ConcurrentHashMap.newKeySet()).addAll(clases);
        }
        casosGenerados.merge(k, casos == null ? 0 : casos.size(), Integer::sum);
        EstadoSalida e = estadosSalida.computeIfAbsent(k, x -> new EstadoSalida());
        synchronized (e) {
            if (casos != null) {
                casos.forEach((id, fqn) -> {
                    if (id != null && !id.isBlank()) {
                        e.casos.put(id, fqn);
                        e.estados.remove(id);
                        e.duplicados.remove(id);
                        e.eliminados.remove(id);
                        e.asercionesSinRespuesta.remove(id);
                    }
                });
            }
            if (duplicados != null) {
                for (String id : duplicados) {
                    if (id != null && !id.isBlank() && !e.casos.containsKey(id)) {
                        e.duplicados.add(id);
                    }
                }
            }
            if (eliminados != null) {
                eliminados.forEach(id -> olvidar(e, id));
            }
        }
    }

    private static void olvidar(EstadoSalida e, String id) {
        if (id == null || id.isBlank()) {
            return;
        }
        e.casos.remove(id);
        e.estados.remove(id);
        e.duplicados.remove(id);
        e.asercionesSinRespuesta.remove(id);
        e.eliminados.add(id);
    }

    /** El caso se eliminó (resolver_fallo o decisión «Eliminar casos»): pasa a eliminados. */
    public void olvidarCaso(Path salidaReal, String id) {
        if (salidaReal == null || id == null) {
            return;
        }
        EstadoSalida e = estadosSalida.computeIfAbsent(clave(salidaReal), x -> new EstadoSalida());
        synchronized (e) {
            olvidar(e, id);
        }
    }

    /** Ids de los casos generados en esta sesión en la salida (orden de inserción); vacío si no hay. */
    public List<String> casosDeSesion(Path salidaReal) {
        EstadoSalida e = salidaReal == null ? null : estadosSalida.get(clave(salidaReal));
        if (e == null) {
            return List.of();
        }
        synchronized (e) {
            return List.copyOf(e.casos.keySet());
        }
    }

    /** Ids de los casos de la sesión en la salida generados para ese servicio (FQN), en orden de inserción. */
    public List<String> casosDeServicio(Path salidaReal, String fqn) {
        EstadoSalida e = salidaReal == null || fqn == null ? null : estadosSalida.get(clave(salidaReal));
        if (e == null) {
            return List.of();
        }
        synchronized (e) {
            List<String> r = new ArrayList<>();
            e.casos.forEach((id, f) -> {
                if (fqn.equals(f)) {
                    r.add(id);
                }
            });
            return r;
        }
    }

    /** Copia id → último estado de ejecución de los casos de la sesión en la salida (solo los que tienen estado). */
    public Map<String, String> estadosDeSesion(Path salidaReal) {
        EstadoSalida e = salidaReal == null ? null : estadosSalida.get(clave(salidaReal));
        if (e == null) {
            return Map.of();
        }
        synchronized (e) {
            Map<String, String> r = new LinkedHashMap<>();
            for (String id : e.casos.keySet()) {
                String estado = e.estados.get(id);
                if (estado != null) {
                    r.put(id, estado);
                }
            }
            return r;
        }
    }

    /** Copia id → FQN del servicio de los casos de la sesión en la salida, en orden de inserción. */
    public Map<String, String> serviciosDeCasos(Path salidaReal) {
        EstadoSalida e = salidaReal == null ? null : estadosSalida.get(clave(salidaReal));
        if (e == null) {
            return Map.of();
        }
        synchronized (e) {
            return new LinkedHashMap<>(e.casos);
        }
    }

    /** Los casos vuelven a «sin ejecutar» (reejecución forzada): pierden su último estado de ejecución. */
    public void olvidarEstados(Path salidaReal, Collection<String> ids) {
        EstadoSalida e = salidaReal == null || ids == null ? null : estadosSalida.get(clave(salidaReal));
        if (e == null) {
            return;
        }
        synchronized (e) {
            for (String id : ids) {
                if (id != null) {
                    e.estados.remove(id);
                }
            }
        }
    }

    /** Último estado de ejecución de los casos de la sesión (los ids ajenos se ignoran). */
    public void registrarEstados(Path salidaReal, Map<String, String> estados) {
        EstadoSalida e = salidaReal == null ? null : estadosSalida.get(clave(salidaReal));
        if (e == null || estados == null) {
            return;
        }
        synchronized (e) {
            estados.forEach((id, estado) -> {
                if (id != null && estado != null && e.casos.containsKey(id)) {
                    e.estados.put(id, estado);
                    if (!ClasificadorFallos.FALLO_ASERCION.equals(estado)) {
                        e.asercionesSinRespuesta.remove(id);
                    }
                }
            });
        }
    }

    /** Guarda las páginas de resultados compactos de la última ejecución en la salida (para paginaResultados). */
    public void guardarPaginasResultados(Path salidaReal, List<List<Map<String, Object>>> paginas) {
        EstadoSalida e = estadosSalida.computeIfAbsent(clave(salidaReal), x -> new EstadoSalida());
        synchronized (e) {
            e.paginasResultados = List.copyOf(paginas);
        }
    }

    /** Páginas de resultados de la última ejecución de la sesión en la salida, o null si no se ha ejecutado. */
    public List<List<Map<String, Object>>> paginasResultados(Path salidaReal) {
        EstadoSalida e = salidaReal == null ? null : estadosSalida.get(clave(salidaReal));
        if (e == null) {
            return null;
        }
        synchronized (e) {
            return e.paginasResultados;
        }
    }

    public boolean ejecucionParcialAutorizada() {
        return ejecucionParcialAutorizada;
    }

    /** El usuario pidió ejecutar con el plan incompleto: vale para el resto de la sesión. */
    public void autorizarEjecucionParcial() {
        this.ejecucionParcialAutorizada = true;
    }

    /** Máximo de servicios listados en resumenSesion.plan. */
    static final int MAX_SERVICIOS_RESUMEN = 15;
    static final String INSTRUCCION_RESUMEN = "Tu mensaje final al usuario es EXACTAMENTE el markdown de `respuestaFinal` de la "
            + "última respuesta de junit-gen que lo traiga, tal cual: sin añadir, quitar ni cambiar nada. Estas cifras son solo de control.";

    /** Servicios (nombre simple, distintos, en orden) con al menos un caso generado en la sesión en la salida. */
    public List<String> serviciosDeSesion(Path salidaReal) {
        EstadoSalida e = salidaReal == null ? null : estadosSalida.get(clave(salidaReal));
        if (e == null) {
            return List.of();
        }
        Set<String> r = new LinkedHashSet<>();
        synchronized (e) {
            for (String fqn : e.casos.values()) {
                if (fqn != null && !fqn.isBlank()) {
                    r.add(simple(fqn));
                }
            }
        }
        return new ArrayList<>(r);
    }

    /**
     * Markdown final exacto ({@code respuestaFinal}) con las cifras de la sesión: la IA lo muestra tal cual.
     * Sin plan, completados = servicios de la sesión y pendientes = ninguno.
     */
    public String respuestaFinal(Path proyecto, Path salidaReal) {
        Map<String, Object> rs = resumenSesion(proyecto, salidaReal);
        RespuestaFinal.Cifras c = new RespuestaFinal.Cifras(entero(rs, "casosGenerados"), entero(rs, "ejecutados"),
                entero(rs, "pasan"), entero(rs, "fallosAsercion"), entero(rs, "erroresMontaje"), entero(rs, "noCompilan"),
                entero(rs, "descartadosPorDuplicado"));
        List<String> sesion = serviciosDeSesion(salidaReal);
        ProgresoPlan p = progresoPlan(proyecto);
        List<String> completados = null;
        List<RespuestaFinal.Pendiente> pendientes = null;
        List<RespuestaFinal.Pendiente> incompletos = new ArrayList<>();
        if (p != null) {
            Set<String> simplesCerrados = new LinkedHashSet<>();
            for (String fqn : cerradosPorSalvaguarda(proyecto).keySet()) {
                simplesCerrados.add(simple(fqn));
                incompletos.add(new RespuestaFinal.Pendiente(simple(fqn), renunciados(proyecto, fqn) + " casos sin generar"));
            }
            completados = new ArrayList<>(p.serviciosCompletos());
            completados.removeAll(simplesCerrados);
            pendientes = new ArrayList<>();
            for (String s : p.serviciosPendientes()) {
                int i = s.indexOf(": ");
                pendientes.add(i < 0 ? new RespuestaFinal.Pendiente(s, null)
                        : new RespuestaFinal.Pendiente(s.substring(0, i), s.substring(i + 2)));
            }
        }
        RespuestaFinal.Arreglos arreglos = null;
        List<Tanda.ArregloTanda> registros = arreglos(proyecto);
        if (!registros.isEmpty()) {
            int arreglados = 0;
            int bugs = 0;
            int apartados = 0;
            int automaticos = 0;
            int menosEstrictos = 0;
            for (Tanda.ArregloTanda a : registros) {
                if (a.menosEstricto) {
                    menosEstrictos++;
                }
                if (Tanda.ARREGLO_ARREGLADO.equals(a.estado)) {
                    arreglados++;
                    if (a.automatico) {
                        automaticos++;
                    }
                } else if (Tanda.ARREGLO_POSIBLE_BUG.equals(a.estado)) {
                    bugs++;
                } else if (Tanda.ARREGLO_APARTADO.equals(a.estado) || Tanda.ARREGLO_DUDOSO.equals(a.estado)) {
                    apartados++;
                }
            }
            arreglos = new RespuestaFinal.Arreglos(arreglados, bugs, apartados, automaticos, menosEstrictos);
        }
        return RespuestaFinal.armar(c, sesion, completados, pendientes, incompletos, arreglos, salidaReal,
                new RespuestaFinal.SinResponder(verificacionSinRespuesta(), asercionesSinRespuesta(salidaReal)));
    }

    private static int entero(Map<String, Object> m, String k) {
        return m.get(k) instanceof Number n ? n.intValue() : 0;
    }

    /**
     * Cifras de la sesión calculadas por junit-gen, con claves fijas y en este orden: carpeta, casosGenerados,
     * ejecutados, pasan, fallosAsercion, erroresMontaje, noCompilan, desconocidos, sinEjecutar,
     * descartadosPorDuplicado, eliminados, plan (si hay plan activo) e instruccion.
     */
    public Map<String, Object> resumenSesion(Path proyecto, Path salidaReal) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("carpeta", salidaReal == null ? "(sin carpeta de salida en esta sesión)" : salidaReal.toString());
        int generados = 0;
        int ejecutados = 0;
        int pasan = 0;
        int asercion = 0;
        int montaje = 0;
        int noCompila = 0;
        int desconocidos = 0;
        int duplicados = 0;
        int eliminados = 0;
        EstadoSalida e = salidaReal == null ? null : estadosSalida.get(clave(salidaReal));
        if (e != null) {
            synchronized (e) {
                generados = e.casos.size();
                for (String id : e.casos.keySet()) {
                    String estado = e.estados.get(id);
                    if (estado == null) {
                        continue;
                    }
                    ejecutados++;
                    switch (estado) {
                        case ClasificadorFallos.PASA -> pasan++;
                        case ClasificadorFallos.FALLO_ASERCION -> asercion++;
                        case ClasificadorFallos.ERROR_MONTAJE -> montaje++;
                        case ClasificadorFallos.NO_COMPILA -> noCompila++;
                        default -> desconocidos++;
                    }
                }
                duplicados = e.duplicados.size();
                eliminados = e.eliminados.size();
            }
        }
        r.put("casosGenerados", generados);
        r.put("ejecutados", ejecutados);
        r.put("pasan", pasan);
        r.put("fallosAsercion", asercion);
        r.put("erroresMontaje", montaje);
        r.put("noCompilan", noCompila);
        r.put("desconocidos", desconocidos);
        r.put("sinEjecutar", generados - ejecutados);
        r.put("descartadosPorDuplicado", duplicados);
        r.put("eliminados", eliminados);
        ProgresoPlan p = progresoPlan(proyecto);
        if (p != null) {
            Map<String, Object> plan = new LinkedHashMap<>();
            plan.put("serviciosTotal", p.servicios());
            plan.put("serviciosCompletados", LimitesRespuesta.listaAcotada(p.serviciosCompletos(), MAX_SERVICIOS_RESUMEN));
            plan.put("serviciosPendientes", LimitesRespuesta.listaAcotada(p.serviciosPendientes(), MAX_SERVICIOS_RESUMEN));
            plan.put("casosTotal", p.total());
            plan.put("casosGenerados", p.generados());
            plan.put("casosRestantes", p.restantes());
            Map<String, String> cerrados = cerradosPorSalvaguarda(proyecto);
            if (!cerrados.isEmpty()) {
                List<String> incompletos = new ArrayList<>();
                cerrados.keySet().forEach(fqn -> incompletos.add(simple(fqn)));
                plan.put("serviciosIncompletos", LimitesRespuesta.listaAcotada(incompletos, MAX_SERVICIOS_RESUMEN));
            }
            r.put("plan", plan);
        }
        r.put("instruccion", INSTRUCCION_RESUMEN);
        return r;
    }

    /** Clases de test generadas en la sesión para la salida (orden de inserción no garantizado). */
    public Set<String> clasesGeneradas(Path salidaReal) {
        Set<String> s = salidaReal == null ? null : clasesGeneradas.get(clave(salidaReal));
        return s == null ? Set.of() : new LinkedHashSet<>(s);
    }

    public int casosGenerados(Path salidaReal) {
        return salidaReal == null ? 0 : casosGenerados.getOrDefault(clave(salidaReal), 0);
    }

    // ------------------------------------------------------------- módulos de trabajo por proyecto

    public void recordarModulos(Path proyectoReal, Collection<String> modulosRel) {
        modulosTrabajo.put(clave(proyectoReal), new LinkedHashSet<>(modulosRel));
    }

    /** Módulos (rel) deducidos de los servicios pedidos para el proyecto, o vacío. */
    public Set<String> modulosTrabajo(Path proyecto) {
        if (proyecto == null) {
            return Set.of();
        }
        try {
            Set<String> s = modulosTrabajo.get(clave(RutaSegura.normalizar(proyecto)));
            return s == null ? Set.of() : s;
        } catch (RuntimeException e) {
            return Set.of();
        }
    }

    // ------------------------------------------------------------- plan de generación por proyecto

    /** Clave de seguimiento de un método del plan. */
    public static String clavePlan(String fqn, String metodo) {
        return fqn + "#" + metodo;
    }

    /**
     * Fija el plan del proyecto (métodos con sus casos previstos) y reinicia el seguimiento. Las sobrecargas
     * se fusionan: casos sumados y conTests solo si todas lo tienen.
     */
    public void fijarPlan(Path proyectoReal, List<MetodoPlan> metodos) {
        Plan p = new Plan();
        for (MetodoPlan m : metodos) {
            String k = clavePlan(m.fqn(), m.metodo());
            MetodoSeguido ms = p.metodos.get(k);
            if (ms == null) {
                p.metodos.put(k, new MetodoSeguido(m.fqn(), m.metodo(), m.casos(), m.conTests()));
            } else {
                ms.previstos += m.casos();
                ms.conTests = ms.conTests && m.conTests();
            }
            p.fqns.add(m.fqn());
        }
        planes.put(clave(proyectoReal), p);
    }

    /** true si hay plan activo y el servicio forma parte de él. */
    public boolean enPlan(Path proyecto, String fqn) {
        Plan p = plan(proyecto);
        if (p == null || fqn == null) {
            return false;
        }
        synchronized (p) {
            return p.fqns.contains(fqn);
        }
    }

    /**
     * Sustituye los métodos de un servicio en el plan activo (tras seleccionar_funciones); sin plan no hace nada.
     * Los nuevos ocupan la posición de la primera entrada del servicio (o el final) y las sobrecargas se fusionan
     * como en {@link #fijarPlan}. Una clave que ya existía conserva sus ids generados y duplicados, con
     * previstos = casos nuevos + ya acreditables. Las entradas antiguas no reelegidas se conservan solo si tienen
     * ids generados (quedan completas: previstos = acreditados); el resto se elimina.
     */
    public void reemplazarPlanServicio(Path proyecto, String fqn, List<MetodoPlan> nuevos) {
        Plan p = plan(proyecto);
        if (p == null || fqn == null) {
            return;
        }
        synchronized (p) {
            Map<String, MetodoSeguido> fusion = new LinkedHashMap<>();
            for (MetodoPlan m : nuevos) {
                if (!fqn.equals(m.fqn())) {
                    continue;
                }
                String k = clavePlan(m.fqn(), m.metodo());
                MetodoSeguido ms = fusion.get(k);
                if (ms == null) {
                    fusion.put(k, new MetodoSeguido(m.fqn(), m.metodo(), m.casos(), m.conTests()));
                } else {
                    ms.previstos += m.casos();
                    ms.conTests = ms.conTests && m.conTests();
                }
            }
            Map<String, MetodoSeguido> nuevosSeguidos = new LinkedHashMap<>();
            fusion.forEach((k, n) -> {
                MetodoSeguido viejo = p.metodos.get(k);
                if (viejo != null) {
                    viejo.renunciados = 0;
                    viejo.previstos = n.previstos + viejo.generados.size() + viejo.enSalida.size()
                            + (viejo.conTests ? viejo.duplicados.size() : 0);
                    nuevosSeguidos.put(k, viejo);
                } else {
                    nuevosSeguidos.put(k, n);
                }
            });
            Map<String, MetodoSeguido> r = new LinkedHashMap<>();
            boolean insertados = false;
            for (Map.Entry<String, MetodoSeguido> e : p.metodos.entrySet()) {
                MetodoSeguido ms = e.getValue();
                if (!fqn.equals(ms.fqn)) {
                    r.put(e.getKey(), ms);
                    continue;
                }
                if (!insertados) {
                    r.putAll(nuevosSeguidos);
                    insertados = true;
                }
                if (nuevosSeguidos.containsKey(e.getKey())) {
                    continue;
                }
                if (!ms.generados.isEmpty()) {
                    ms.renunciados = 0;
                    ms.previstos = ms.acreditados();
                    r.put(e.getKey(), ms);
                }
            }
            if (!insertados) {
                r.putAll(nuevosSeguidos);
            }
            p.metodos.clear();
            p.metodos.putAll(r);
            p.fqns.add(fqn);
            // reelegir reabre el servicio: sin cierre por salvaguarda ni contadores
            p.cerrados.remove(fqn);
            p.sinProgreso.remove(fqn);
            p.rechazos.remove(fqn);
        }
    }

    /** FQN → Σ casos acreditados de sus métodos en el plan activo (vacío sin plan). */
    public Map<String, Integer> acreditadosPorServicio(Path proyecto) {
        Plan p = plan(proyecto);
        if (p == null) {
            return Map.of();
        }
        synchronized (p) {
            Map<String, Integer> r = new LinkedHashMap<>();
            for (MetodoSeguido ms : p.metodos.values()) {
                r.merge(ms.fqn, ms.acreditados(), Integer::sum);
            }
            return r;
        }
    }

    /**
     * Salvaguarda anti-bucle: anota un intento de generar_tests sobre los servicios dados (solo los del plan con casos
     * pendientes). Spec rechazado → suma un rechazo y, al llegar a {@link #MAX_RECHAZOS_SPEC}, cierra el servicio. Spec
     * válido → reinicia los rechazos; si el servicio acreditó casos nuevos respecto a {@code antes} reinicia también
     * los intentos sin progreso; si no, los suma y, al llegar a {@link #MAX_SIN_PROGRESO}, lo cierra. Cerrar = dar por
     * atendidos (renunciados) los casos que faltaban y anotar el motivo. Devuelve los FQN cerrados en esta llamada.
     */
    public List<String> registrarIntento(Path proyecto, Collection<String> fqns, Map<String, Integer> antes,
                                         boolean specRechazado) {
        Plan p = plan(proyecto);
        if (p == null || fqns == null || fqns.isEmpty()) {
            return List.of();
        }
        List<String> cerradosAhora = new ArrayList<>();
        synchronized (p) {
            for (String fqn : new LinkedHashSet<>(fqns)) {
                if (fqn == null || !p.fqns.contains(fqn)) {
                    continue;
                }
                int faltan = 0;
                int acreditados = 0;
                for (MetodoSeguido ms : p.metodos.values()) {
                    if (fqn.equals(ms.fqn)) {
                        int a = ms.acreditados();
                        acreditados += a;
                        faltan += Math.max(0, ms.previstos - a);
                    }
                }
                if (faltan == 0) {
                    continue;
                }
                if (specRechazado) {
                    int n = p.rechazos.merge(fqn, 1, Integer::sum);
                    if (n >= MAX_RECHAZOS_SPEC) {
                        cerrarServicio(p, fqn, MAX_RECHAZOS_SPEC + " specs rechazados seguidos: quedan " + faltan
                                + " casos sin generar");
                        cerradosAhora.add(fqn);
                    }
                    continue;
                }
                p.rechazos.remove(fqn);
                int previo = antes == null ? 0 : antes.getOrDefault(fqn, 0);
                if (acreditados > previo) {
                    p.sinProgreso.remove(fqn);
                    continue;
                }
                int n = p.sinProgreso.merge(fqn, 1, Integer::sum);
                if (n >= MAX_SIN_PROGRESO) {
                    cerrarServicio(p, fqn, MAX_SIN_PROGRESO + " llamadas seguidas a generar_tests sin casos nuevos: se dan "
                            + "por atendidos los " + faltan + " casos que faltaban");
                    cerradosAhora.add(fqn);
                }
            }
        }
        return cerradosAhora;
    }

    /** Cierra el servicio por la salvaguarda anti-bucle (con el monitor del plan tomado). */
    private static void cerrarServicio(Plan p, String fqn, String motivo) {
        for (MetodoSeguido ms : p.metodos.values()) {
            if (fqn.equals(ms.fqn)) {
                ms.renunciados += Math.max(0, ms.previstos - ms.acreditados());
            }
        }
        p.cerrados.put(fqn, motivo);
        p.sinProgreso.remove(fqn);
        p.rechazos.remove(fqn);
    }

    /** Copia FQN → motivo de los servicios cerrados por la salvaguarda anti-bucle (vacío sin plan). */
    public Map<String, String> cerradosPorSalvaguarda(Path proyecto) {
        Plan p = plan(proyecto);
        if (p == null) {
            return Map.of();
        }
        synchronized (p) {
            return new LinkedHashMap<>(p.cerrados);
        }
    }

    /** Casos del servicio dados por atendidos por la salvaguarda anti-bucle (0 sin plan). */
    public int renunciados(Path proyecto, String fqn) {
        Plan p = plan(proyecto);
        if (p == null || fqn == null) {
            return 0;
        }
        synchronized (p) {
            int n = 0;
            for (MetodoSeguido ms : p.metodos.values()) {
                if (fqn.equals(ms.fqn)) {
                    n += ms.renunciados;
                }
            }
            return n;
        }
    }

    /** Suma un rechazo seguido de la puerta de plan y devuelve el total. */
    int contarRechazoPuertaPlan() {
        return rechazosPuertaPlan.incrementAndGet();
    }

    /** Reinicia los rechazos seguidos de la puerta de plan. */
    void reiniciarRechazosPuertaPlan() {
        rechazosPuertaPlan.set(0);
    }

    public void quitarPlan(Path proyectoReal) {
        planes.remove(clave(proyectoReal));
    }

    private Plan plan(Path proyecto) {
        if (proyecto == null) {
            return null;
        }
        try {
            return planes.get(clave(RutaSegura.normalizar(proyecto)));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Registra en el plan los ids generados (o reemplazados) y los descartados por duplicado, cada uno con
     * su clave {@link #clavePlan}; quita los eliminados. Los ids de métodos fuera del plan se ignoran.
     */
    public void registrarCasosPlan(Path proyecto, Map<String, String> generados, Map<String, String> duplicados,
                                   Collection<String> eliminados) {
        Plan p = plan(proyecto);
        if (p == null) {
            return;
        }
        synchronized (p) {
            if (generados != null) {
                generados.forEach((id, k) -> {
                    MetodoSeguido ms = id == null || id.isBlank() || k == null ? null : p.metodos.get(k);
                    if (ms != null) {
                        ms.duplicados.remove(id);
                        ms.enSalida.remove(id);
                        ms.generados.add(id);
                    }
                });
            }
            if (duplicados != null) {
                duplicados.forEach((id, k) -> {
                    MetodoSeguido ms = id == null || id.isBlank() || k == null ? null : p.metodos.get(k);
                    if (ms != null && !ms.generados.contains(id) && !ms.enSalida.contains(id)) {
                        ms.duplicados.add(id);
                    }
                });
            }
            if (eliminados != null) {
                for (MetodoSeguido ms : p.metodos.values()) {
                    ms.generados.removeAll(eliminados);
                    ms.duplicados.removeAll(eliminados);
                    ms.enSalida.removeAll(eliminados);
                }
            }
        }
    }

    /**
     * Registra en el plan los ids descartados por duplicado de nivel {@link com.junitgen.duplicados.Duplicado#NIVEL_SALIDA}
     * (ya generados en la carpeta de salida), cada uno con su clave {@link #clavePlan}: cuentan siempre como
     * atendidos, aunque el método no tuviera tests. Los ids ya generados o de métodos fuera del plan se ignoran.
     */
    public void registrarDuplicadosEnSalida(Path proyecto, Map<String, String> idAClave) {
        Plan p = plan(proyecto);
        if (p == null || idAClave == null) {
            return;
        }
        synchronized (p) {
            idAClave.forEach((id, k) -> {
                MetodoSeguido ms = id == null || id.isBlank() || k == null ? null : p.metodos.get(k);
                if (ms != null && !ms.generados.contains(id)) {
                    ms.duplicados.remove(id);
                    ms.enSalida.add(id);
                }
            });
        }
    }

    /**
     * Progreso del plan activo del proyecto, o null si no hay plan. generados = Σ min(previstos, ids
     * generados + (conTests ? ids descartados por duplicado : 0)). Un servicio está completo si todos sus
     * métodos tienen acreditados ≥ previstos.
     */
    public ProgresoPlan progresoPlan(Path proyecto) {
        Plan p = plan(proyecto);
        if (p == null) {
            return null;
        }
        synchronized (p) {
            int total = 0;
            int generados = 0;
            int completos = 0;
            int escritos = 0;
            // fqn → [casos que faltan, métodos con pendientes]
            Map<String, int[]> porServicio = new LinkedHashMap<>();
            List<String> pendientes = new ArrayList<>();
            for (MetodoSeguido ms : p.metodos.values()) {
                int acreditados = ms.acreditados();
                total += ms.previstos;
                generados += acreditados;
                escritos += ms.generados.size();
                int[] s = porServicio.computeIfAbsent(ms.fqn, k -> new int[2]);
                if (acreditados >= ms.previstos) {
                    completos++;
                } else {
                    s[0] += ms.previstos - acreditados;
                    s[1]++;
                    pendientes.add(simple(ms.fqn) + "." + ms.metodo + ": faltan " + (ms.previstos - acreditados));
                }
            }
            List<String> serviciosCompletos = new ArrayList<>();
            List<String> serviciosPendientes = new ArrayList<>();
            porServicio.forEach((fqn, s) -> {
                if (s[1] == 0) {
                    serviciosCompletos.add(simple(fqn));
                } else {
                    serviciosPendientes.add(simple(fqn) + ": faltan " + s[0] + (s[0] == 1 ? " caso en " : " casos en ") + s[1]
                            + (s[1] == 1 ? " método" : " métodos"));
                }
            });
            return new ProgresoPlan(total, generados, Math.max(0, total - generados), porServicio.size(), p.metodos.size(),
                    completos, pendientes, escritos, serviciosCompletos, serviciosPendientes);
        }
    }

    /** Estado de cada método del plan activo (en el orden del plan), o vacío si no hay plan. */
    public List<EstadoMetodo> estadoPlan(Path proyecto) {
        Plan p = plan(proyecto);
        if (p == null) {
            return List.of();
        }
        synchronized (p) {
            List<EstadoMetodo> r = new ArrayList<>();
            for (MetodoSeguido ms : p.metodos.values()) {
                r.add(new EstadoMetodo(ms.fqn, ms.metodo, ms.previstos, ms.acreditados()));
            }
            return r;
        }
    }

    private static String simple(String fqn) {
        return fqn == null ? "" : fqn.substring(fqn.lastIndexOf('.') + 1);
    }

    // ------------------------------------------------------------- tanda persistente (tanda.json)

    /** clave(proyecto) → tanda en memoria (se vuelca a tanda.json de la salida con {@link #guardarTanda}). */
    private final Map<String, Tanda> tandas = new ConcurrentHashMap<>();

    /** Fija la tanda en memoria del proyecto (null la olvida). */
    public void iniciarTanda(Path proyecto, Tanda t) {
        if (proyecto == null) {
            return;
        }
        try {
            String k = clave(RutaSegura.normalizar(proyecto));
            if (t == null) {
                tandas.remove(k);
            } else {
                tandas.put(k, t);
            }
        } catch (RuntimeException e) {
            System.err.println("junit-gen: no se pudo recordar la tanda: " + e.getMessage());
        }
    }

    /** Tanda en memoria del proyecto, o null. */
    public Tanda tanda(Path proyecto) {
        if (proyecto == null) {
            return null;
        }
        try {
            return tandas.get(clave(RutaSegura.normalizar(proyecto)));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Como {@link #guardarTanda(Path, Path, Boolean)} sin cambiar la marca de tanda cerrada. */
    public void guardarTanda(Path proyecto, Path salidaReal) {
        guardarTanda(proyecto, salidaReal, null);
    }

    /**
     * Vuelca el plan activo del proyecto a tanda.json de la salida (orden, previstos, conTests, ids generados y
     * duplicados por método), con el estado de cada servicio, si está completo, sus intentos de montaje y el índice
     * del primer servicio no completo. Sin plan no hace nada; sin tanda en memoria la crea. {@code cerrada} null
     * conserva la marca. Se escribe bajo el bloqueo de escritura de la salida; un fallo va a stderr y no rompe la llamada.
     */
    public void guardarTanda(Path proyecto, Path salidaReal, Boolean cerrada) {
        if (proyecto == null || salidaReal == null) {
            return;
        }
        try {
            Path proyectoReal = RutaSegura.normalizar(proyecto);
            Plan p = plan(proyectoReal);
            if (p == null) {
                return;
            }
            Tanda t = tandas.computeIfAbsent(clave(proyectoReal), x -> nuevaTanda(proyectoReal));
            BloqueosSalida.conEscritura(salidaReal, () -> {
                String texto = volcarTanda(t, p, salidaReal, cerrada);
                new EscritorSeguro(proyectoReal, salidaReal).escribir(AlmacenTanda.ruta(salidaReal), texto);
                return null;
            });
        } catch (RuntimeException e) {
            System.err.println("junit-gen: no se pudo guardar " + RutasSalida.TANDA + ": " + e.getMessage());
        }
    }

    private static Tanda nuevaTanda(Path proyectoReal) {
        Tanda t = new Tanda();
        t.proyecto = proyectoReal.toString();
        t.creada = Instant.now().toString();
        return t;
    }

    /** Copia de un método del plan para volcarlo sin mantener el monitor del plan. */
    private record MetodoVolcado(String metodo, int previstos, boolean conTests, List<String> generados,
                                 List<String> duplicados, List<String> enSalida, boolean completo, int renunciados) {
    }

    /** Actualiza la tanda con el plan y los estados actuales y devuelve su JSON (con el bloqueo de escritura tomado). */
    private String volcarTanda(Tanda t, Plan p, Path salidaReal, Boolean cerrada) {
        Map<String, List<MetodoVolcado>> porServicio = new LinkedHashMap<>();
        Map<String, String> cerrados;
        synchronized (p) {
            cerrados = new LinkedHashMap<>(p.cerrados);
            for (String fqn : p.fqns) {
                porServicio.put(fqn, new ArrayList<>());
            }
            for (MetodoSeguido ms : p.metodos.values()) {
                porServicio.computeIfAbsent(ms.fqn, k -> new ArrayList<>()).add(new MetodoVolcado(ms.metodo, ms.previstos,
                        ms.conTests, List.copyOf(ms.generados), List.copyOf(ms.duplicados), List.copyOf(ms.enSalida),
                        ms.acreditados() >= ms.previstos, ms.renunciados));
            }
        }
        Spec spec;
        try {
            spec = AlmacenSpec.leer(salidaReal);
        } catch (RuntimeException e) {
            System.err.println("junit-gen: tanda.json sin estados del spec: " + e.getMessage());
            spec = null;
        }
        Map<String, String> estados = estadosDeSesion(salidaReal);
        Map<String, String> casos = serviciosDeCasos(salidaReal);
        Set<String> corregir = new LinkedHashSet<>();
        for (VerificacionesSesion.Correccion c : verificaciones.correcciones(salidaReal)) {
            corregir.add(c.fqn());
        }
        List<Tanda.ServicioTanda> servicios = new ArrayList<>();
        synchronized (t) {
            Map<String, String> modulos = new HashMap<>();
            for (Tanda.ServicioTanda s : t.servicios) {
                if (s != null && s.clase != null && s.modulo != null) {
                    modulos.put(s.clase, s.modulo);
                }
            }
            for (Map.Entry<String, List<MetodoVolcado>> e : porServicio.entrySet()) {
                String fqn = e.getKey();
                Tanda.ServicioTanda s = new Tanda.ServicioTanda();
                s.clase = fqn;
                s.modulo = modulos.get(fqn);
                s.cierre = cerrados.get(fqn);
                Set<String> ids = new LinkedHashSet<>();
                boolean completo = true;
                for (MetodoVolcado m : e.getValue()) {
                    Tanda.MetodoTanda mt = new Tanda.MetodoTanda();
                    mt.metodo = m.metodo();
                    mt.previstos = m.previstos();
                    mt.conTests = m.conTests();
                    mt.generados = new ArrayList<>(m.generados());
                    mt.duplicados = new ArrayList<>(m.duplicados());
                    mt.enSalida = new ArrayList<>(m.enSalida());
                    mt.renunciados = m.renunciados();
                    s.metodos.add(mt);
                    ids.addAll(m.generados());
                    completo &= m.completo();
                }
                casos.forEach((id, f) -> {
                    if (fqn.equals(f)) {
                        ids.add(id);
                    }
                });
                s.completo = completo;
                estadoServicio(s, ids, spec, estados, corregir.contains(fqn), verificaciones.ocupado(salidaReal, fqn));
                servicios.add(s);
            }
            t.servicios = servicios;
            int indice = servicios.size();
            for (int i = 0; i < servicios.size(); i++) {
                if (!servicios.get(i).completo) {
                    indice = i;
                    break;
                }
            }
            t.indice = indice;
            if (t.arreglos == null) {
                t.arreglos = new ArrayList<>();
            }
            if (indice < servicios.size()) {
                t.fase = Tanda.FASE_GENERACION;
            } else if (t.arreglos.stream().anyMatch(a -> a != null && Tanda.ARREGLO_PENDIENTE.equals(a.estado))) {
                t.fase = Tanda.FASE_ARREGLO;
            } else {
                t.fase = Tanda.FASE_CIERRE;
            }
            if (cerrada != null) {
                t.cerrada = cerrada;
            }
            t.actualizada = Instant.now().toString();
            return AlmacenTanda.serializar(t);
        }
    }

    /**
     * Estado del servicio, por prioridad: sin casos → pendiente; en cola o en curso → verificando; algún caso de montaje
     * reintentable → con_fallos_montaje; algún montaje_fallido → montaje_fallido; algún fallo_asercion pendiente →
     * con_fallos_asercion; casos sin estado → generado; resto → ok. También fija los intentos de montaje.
     */
    private static void estadoServicio(Tanda.ServicioTanda s, Set<String> ids, Spec spec, Map<String, String> estados,
                                       boolean reintentable, boolean ocupado) {
        boolean fallido = false;
        boolean asercion = false;
        boolean sinEstado = false;
        int intentos = 0;
        for (String id : ids) {
            AlmacenSpec.Ubicacion u = spec == null ? null : AlmacenSpec.buscar(spec, id);
            CasoSpec c = u == null ? null : u.caso();
            String est = estados.get(id);
            if (c == null) {
                sinEstado |= est == null && spec == null;
                continue;
            }
            int n = c.intentosMontaje == null ? 0 : c.intentosMontaje;
            intentos = Math.max(intentos, n);
            if (HerramientaEjecutar.ESTADO_SPEC_MONTAJE_FALLIDO.equals(c.estado)) {
                fallido = true;
            }
            if (CasoSpec.ESTADO_FALLO_ASERCION.equals(c.estado) && c.autorizacionCambio == null) {
                asercion = true;
            }
            if ((ClasificadorFallos.ERROR_MONTAJE.equals(est) || ClasificadorFallos.NO_COMPILA.equals(est))
                    && HerramientaEjecutar.ESTADO_SPEC_GENERADO.equals(c.estado) && n >= 1 && n < VerificadorTests.MAX_INTENTOS) {
                reintentable = true;
            }
            if (est == null && HerramientaEjecutar.ESTADO_SPEC_GENERADO.equals(c.estado)) {
                sinEstado = true;
            }
        }
        s.intentos = intentos;
        if (ids.isEmpty()) {
            s.estado = Tanda.PENDIENTE;
        } else if (ocupado) {
            s.estado = Tanda.VERIFICANDO;
        } else if (reintentable) {
            s.estado = Tanda.CON_FALLOS_MONTAJE;
        } else if (fallido) {
            s.estado = Tanda.MONTAJE_FALLIDO;
        } else if (asercion) {
            s.estado = Tanda.CON_FALLOS_ASERCION;
        } else if (sinEstado) {
            s.estado = Tanda.GENERADO;
        } else {
            s.estado = Tanda.OK;
        }
    }

    // ------------------------------------------------------------- arreglos al final (tanda.json)

    /** caso#intentos → entregas o rechazos seguidos sin corrección aplicada (anti-bucle; no se persiste). */
    private final Map<String, Integer> sinCorregir = new ConcurrentHashMap<>();

    /** Copias de los casos apuntados para arreglar al final, en orden; sin tanda → vacía. */
    public List<Tanda.ArregloTanda> arreglos(Path proyecto) {
        Tanda t = tanda(proyecto);
        if (t == null) {
            return List.of();
        }
        List<Tanda.ArregloTanda> r = new ArrayList<>();
        synchronized (t) {
            if (t.arreglos != null) {
                for (Tanda.ArregloTanda a : t.arreglos) {
                    if (a != null) {
                        r.add(a.copia());
                    }
                }
            }
        }
        return r;
    }

    /** Copias de los casos pendientes de arreglo, ordenados (de forma estable) por la posición de su servicio en el plan. */
    public List<Tanda.ArregloTanda> arreglosPendientes(Path proyecto) {
        List<Tanda.ArregloTanda> r = new ArrayList<>();
        for (Tanda.ArregloTanda a : arreglos(proyecto)) {
            if (Tanda.ARREGLO_PENDIENTE.equals(a.estado)) {
                r.add(a);
            }
        }
        if (r.size() < 2) {
            return r;
        }
        Map<String, Integer> posicion = new HashMap<>();
        Plan p = null;
        try {
            p = plan(RutaSegura.normalizar(proyecto));
        } catch (RuntimeException e) {
            p = null;
        }
        if (p != null) {
            synchronized (p) {
                int i = 0;
                for (String fqn : p.fqns) {
                    posicion.putIfAbsent(fqn, i++);
                }
            }
        }
        r.sort(Comparator.comparingInt(a -> posicion.getOrDefault(a.servicio, Integer.MAX_VALUE)));
        return r;
    }

    /** Copia del caso apuntado {@code caso}, o null. */
    public Tanda.ArregloTanda arreglo(Path proyecto, String caso) {
        if (caso == null) {
            return null;
        }
        for (Tanda.ArregloTanda a : arreglos(proyecto)) {
            if (caso.equals(a.caso)) {
                return a;
            }
        }
        return null;
    }

    /**
     * Aplica {@code cambio} (puro, en memoria: sin E/S ni llamadas a la fachada) a una copia del registro del caso (o a
     * null si no existe): null lo quita y uno nuevo se añade. Sin tanda y con plan la crea; sin plan no hace nada. Marca
     * la sección «Arreglos al final» del informe como no escrita.
     */
    public void actualizarArreglo(Path proyecto, String caso, UnaryOperator<Tanda.ArregloTanda> cambio) {
        if (proyecto == null || caso == null || cambio == null) {
            return;
        }
        Path proyectoReal;
        try {
            proyectoReal = RutaSegura.normalizar(proyecto);
        } catch (RuntimeException e) {
            return;
        }
        Tanda t = tanda(proyectoReal);
        if (t == null) {
            if (plan(proyectoReal) == null) {
                return;
            }
            t = tandas.computeIfAbsent(clave(proyectoReal), x -> nuevaTanda(proyectoReal));
        }
        synchronized (t) {
            if (t.arreglos == null) {
                t.arreglos = new ArrayList<>();
            }
            int i = -1;
            for (int k = 0; k < t.arreglos.size(); k++) {
                Tanda.ArregloTanda a = t.arreglos.get(k);
                if (a != null && caso.equals(a.caso)) {
                    i = k;
                    break;
                }
            }
            Tanda.ArregloTanda actual = i < 0 ? null : t.arreglos.get(i).copia();
            Tanda.ArregloTanda nuevo = cambio.apply(actual);
            if (nuevo == null) {
                if (i >= 0) {
                    t.arreglos.remove(i);
                }
            } else {
                nuevo.caso = caso;
                if (i >= 0) {
                    t.arreglos.set(i, nuevo);
                } else {
                    t.arreglos.add(nuevo);
                }
            }
            t.arreglosInformados = false;
        }
    }

    /** Máximo de frases de imports corregidos automáticamente que se guardan en la tanda. */
    static final int MAX_IMPORTS_REPARADOS = 60;

    /**
     * Apunta en la tanda las frases de imports corregidos automáticamente (sin duplicados, tope
     * {@link #MAX_IMPORTS_REPARADOS}) y marca «Arreglos al final» como no escrita. Sin tanda no hace nada.
     */
    public void anotarImportsReparados(Path proyecto, Collection<String> textos) {
        if (proyecto == null || textos == null || textos.isEmpty()) {
            return;
        }
        Tanda t = tanda(proyecto);
        if (t == null) {
            return;
        }
        synchronized (t) {
            if (t.importsReparados == null) {
                t.importsReparados = new ArrayList<>();
            }
            boolean cambia = false;
            for (String x : textos) {
                if (x != null && !x.isBlank() && !t.importsReparados.contains(x) && t.importsReparados.size() < MAX_IMPORTS_REPARADOS) {
                    t.importsReparados.add(x);
                    cambia = true;
                }
            }
            if (cambia) {
                t.arreglosInformados = false;
            }
        }
    }

    /** Copia de las frases de imports corregidos automáticamente durante la tanda; sin tanda → vacía. */
    public List<String> importsReparados(Path proyecto) {
        Tanda t = tanda(proyecto);
        if (t == null) {
            return List.of();
        }
        synchronized (t) {
            return t.importsReparados == null ? List.of() : List.copyOf(t.importsReparados);
        }
    }

    /**
     * Reserva la escritura de «Arreglos al final»: false si no hay registros ni imports corregidos o ya está escrita;
     * si no, la marca.
     */
    public boolean reservarInformeArreglos(Path proyecto) {
        Tanda t = tanda(proyecto);
        if (t == null) {
            return false;
        }
        synchronized (t) {
            boolean sinArreglos = t.arreglos == null || t.arreglos.isEmpty();
            boolean sinImports = t.importsReparados == null || t.importsReparados.isEmpty();
            if ((sinArreglos && sinImports) || t.arreglosInformados) {
                return false;
            }
            t.arreglosInformados = true;
            return true;
        }
    }

    /** Anota el fin de la llamada en curso ({@code ahoraNanos}) y devuelve el de la anterior (0 si no había). */
    long marcarFinLlamada(long ahoraNanos) {
        return finLlamadaAnterior.getAndSet(ahoraNanos);
    }

    /** Deshace {@link #reservarInformeArreglos(Path)} cuando la escritura falla. */
    public void liberarInformeArreglos(Path proyecto) {
        Tanda t = tanda(proyecto);
        if (t == null) {
            return;
        }
        synchronized (t) {
            t.arreglosInformados = false;
        }
    }

    /** Suma una entrega o rechazo del caso sin corrección aplicada (con esos intentos) y devuelve el total. */
    int contarSinCorregir(String caso, int intentos) {
        return sinCorregir.merge(caso + "#" + intentos, 1, Integer::sum);
    }

    /** Reconstruye el plan del proyecto (métodos, previstos, conTests, ids generados y duplicados) tal cual desde la tanda. */
    public void restaurarPlan(Path proyectoReal, Tanda t) {
        if (proyectoReal == null || t == null || t.servicios == null) {
            return;
        }
        Plan p = new Plan();
        for (Tanda.ServicioTanda s : t.servicios) {
            if (s == null || s.clase == null || s.clase.isBlank()) {
                continue;
            }
            p.fqns.add(s.clase);
            if (s.cierre != null) {
                p.cerrados.put(s.clase, s.cierre);
            }
            if (s.metodos == null) {
                continue;
            }
            for (Tanda.MetodoTanda m : s.metodos) {
                if (m == null || m.metodo == null || m.metodo.isBlank()) {
                    continue;
                }
                MetodoSeguido ms = new MetodoSeguido(s.clase, m.metodo, Math.max(0, m.previstos), m.conTests);
                ms.renunciados = Math.max(0, m.renunciados);
                if (m.generados != null) {
                    m.generados.stream().filter(id -> id != null && !id.isBlank()).forEach(ms.generados::add);
                }
                if (m.duplicados != null) {
                    m.duplicados.stream().filter(id -> id != null && !id.isBlank() && !ms.generados.contains(id))
                            .forEach(ms.duplicados::add);
                }
                if (m.enSalida != null) {
                    m.enSalida.stream().filter(id -> id != null && !id.isBlank() && !ms.generados.contains(id))
                            .forEach(id -> {
                                ms.duplicados.remove(id);
                                ms.enSalida.add(id);
                            });
                }
                p.metodos.put(clavePlan(s.clase, m.metodo), ms);
            }
        }
        planes.put(clave(RutaSegura.normalizar(proyectoReal)), p);
    }

    // ------------------------------------------------------------- pregunta de aserciones (fallosAsercion: preguntar)

    /** Marca el caso como ya preguntado al usuario en esta sesión (para la salida dada). */
    public void marcarAsercionPreguntada(Path salidaReal, String caso) {
        if (salidaReal != null && caso != null) {
            asercionesPreguntadas.add(clave(salidaReal) + "|" + caso);
        }
    }

    public boolean asercionPreguntada(Path salidaReal, String caso) {
        return salidaReal != null && caso != null && asercionesPreguntadas.contains(clave(salidaReal) + "|" + caso);
    }

    public void inicializar(String protocolo, JsonNode capacidades) {
        this.protocolo = protocolo;
        this.capacidadesCliente = capacidades;
    }

    public String protocolo() {
        return protocolo;
    }

    public boolean soportaElicitacion() {
        return capacidadesCliente != null && capacidadesCliente.has("elicitation") && !capacidadesCliente.get("elicitation").isNull();
    }

    public boolean soportaRaices() {
        return capacidadesCliente != null && capacidadesCliente.has("roots") && !capacidadesCliente.get("roots").isNull();
    }

    // ------------------------------------------------------------- raíces (MCP roots)

    /** Raíces en caché, o null si hay que pedirlas al cliente. */
    public List<Raices.Raiz> raicesCache() {
        return raices;
    }

    public long generacionRaices() {
        return generacionRaices.get();
    }

    /** Guarda las raíces si no se invalidaron mientras se pedían. */
    public synchronized void guardarRaices(List<Raices.Raiz> lista, long generacion) {
        if (generacionRaices.get() == generacion) {
            raices = List.copyOf(lista);
        }
    }

    /** notifications/roots/list_changed: la próxima consulta vuelve a pedir roots/list. */
    public synchronized void invalidarRaices() {
        generacionRaices.incrementAndGet();
        raices = null;
    }

    // ------------------------------------------------------------- carpeta de salida por proyecto

    private static String clave(Path proyectoReal) {
        return proyectoReal.toString().toLowerCase(Locale.ROOT);
    }

    /** Recuerda una salida ya validada con RutaSegura.validarSalida para el proyecto (ruta real). */
    public void recordarSalida(Path proyectoReal, Path salidaReal) {
        salidas.put(clave(proyectoReal), salidaReal);
    }

    /** Salida recordada para el proyecto, o null. */
    public Path salidaDe(Path proyecto) {
        if (proyecto == null) {
            return null;
        }
        try {
            return salidas.get(clave(RutaSegura.normalizar(proyecto)));
        } catch (RuntimeException e) {
            return null;
        }
    }

    public void marcarSalidaPreguntada(Path proyectoReal) {
        salidaPreguntada.add(clave(proyectoReal));
    }

    public boolean salidaPreguntada(Path proyectoReal) {
        return salidaPreguntada.contains(clave(proyectoReal));
    }

    public void enviar(JsonNode mensaje) throws IOException {
        transporte.enviar(mensaje);
    }

    /** Envía una petición servidor→cliente y devuelve el futuro de su respuesta. */
    public CompletableFuture<JsonNode> solicitar(String metodo, JsonNode params) throws IOException {
        if (cerrada) {
            CompletableFuture<JsonNode> f = new CompletableFuture<>();
            f.completeExceptionally(new IOException("La sesión MCP está cerrada."));
            return f;
        }
        return registrarYEnviar(metodo, params).futuro();
    }

    /** Registra la petición servidor→cliente (id srv-N), la envía y devuelve id y futuro. */
    private Solicitud registrarYEnviar(String metodo, JsonNode params) throws IOException {
        String id = "srv-" + contador.incrementAndGet();
        CompletableFuture<JsonNode> f = new CompletableFuture<>();
        pendientes.put(id, f);
        try {
            transporte.enviar(JsonRpc.peticion(id, metodo, params));
        } catch (IOException e) {
            pendientes.remove(id);
            throw e;
        }
        f.whenComplete((r, ex) -> pendientes.remove(id));
        return new Solicitud(id, f);
    }

    /**
     * Envía una petición servidor→cliente y espera su respuesta como máximo {@code espera}. Solo el worker
     * espera; el hilo lector la completa ({@link #completar}) o la aborta ({@link #cancelar} de la llamada en
     * curso). Al caducar o abortarse se retira el id pendiente (una respuesta tardía se ignora) y se avisa al
     * cliente con notifications/cancelled. Nunca equivale a una respuesta del usuario.
     */
    public RespuestaCliente solicitarYEsperar(String metodo, JsonNode params, Duration espera) {
        if (cerrada) {
            return new RespuestaCliente(FinEspera.FALLIDA, null);
        }
        Solicitud s;
        try {
            s = registrarYEnviar(metodo, params);
        } catch (IOException e) {
            System.err.println("No se pudo enviar " + metodo + ": " + e.getMessage());
            return new RespuestaCliente(FinEspera.FALLIDA, null);
        }
        CompletableFuture<JsonNode> f = s.futuro();
        long ms = espera == null ? 0 : Math.max(0, espera.toMillis());
        FinEspera fin;
        try {
            esperaEnCurso = f;
            if (llamadaCancelada()) {
                f.cancel(false);
            }
            return new RespuestaCliente(FinEspera.RESPONDIDA, f.get(ms, TimeUnit.MILLISECONDS));
        } catch (TimeoutException e) {
            if (!f.cancel(false)) {
                // Ya se completó justo en el límite: se aprovecha la respuesta (o se clasifica el fin real).
                if (f.isCancelled()) {
                    fin = FinEspera.ABORTADA;
                } else if (f.isCompletedExceptionally()) {
                    return new RespuestaCliente(FinEspera.FALLIDA, null);
                } else {
                    return new RespuestaCliente(FinEspera.RESPONDIDA, f.getNow(null));
                }
            } else {
                fin = FinEspera.CADUCADA;
            }
        } catch (CancellationException e) {
            fin = FinEspera.ABORTADA;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            f.cancel(false);
            pendientes.remove(s.id());
            return new RespuestaCliente(FinEspera.FALLIDA, null);
        } catch (ExecutionException e) {
            System.err.println("Petición " + metodo + " " + s.id() + " fallida: " + e.getMessage());
            return new RespuestaCliente(FinEspera.FALLIDA, null);
        } finally {
            esperaEnCurso = null;
        }
        pendientes.remove(s.id());
        System.err.println("Petición " + metodo + " " + s.id() + (fin == FinEspera.CADUCADA
                ? " sin respuesta en " + (ms / 1000) + " s: se retira." : " abortada: la llamada en curso se canceló."));
        ObjectNode aviso = Json.objeto();
        aviso.put("requestId", s.id());
        aviso.put("reason", "Sin respuesta del usuario");
        try {
            transporte.enviar(JsonRpc.notificacion("notifications/cancelled", aviso));
        } catch (IOException e) {
            System.err.println("No se pudo enviar notifications/cancelled para " + s.id() + ": " + e.getMessage());
        }
        return new RespuestaCliente(fin, null);
    }

    /** Completa la petición servidor→cliente correspondiente a una respuesta recibida. */
    public boolean completar(JsonNode respuesta) {
        JsonNode id = respuesta.get("id");
        if (id == null) {
            return false;
        }
        CompletableFuture<JsonNode> f = pendientes.remove(id.asText());
        if (f == null) {
            return false;
        }
        return f.complete(respuesta);
    }

    public void cancelar(String id) {
        cancelados.add(id);
        CompletableFuture<JsonNode> espera = esperaEnCurso;
        if (id != null && id.equals(idLlamada) && espera != null) {
            espera.cancel(false);
        }
    }

    public boolean cancelado(String id) {
        return cancelados.contains(id);
    }

    public void cerrar() {
        cerrada = true;
        verificaciones.cerrar();
        for (CompletableFuture<JsonNode> f : pendientes.values()) {
            f.completeExceptionally(new IOException("Fin de la entrada estándar: la sesión MCP se cierra."));
        }
        pendientes.clear();
    }
}
