package com.junitgen.verificacion;

import com.junitgen.config.CargadorConfig;
import com.junitgen.config.ConfigProyecto;
import com.junitgen.duplicados.ExtractorHuellasTests;
import com.junitgen.escaneo.InfoClase;
import com.junitgen.escaneo.ModuloEscaneado;
import com.junitgen.escaneo.ProyectoEscaneado;
import com.junitgen.generacion.AlmacenSpec;
import com.junitgen.generacion.CatalogoClases;
import com.junitgen.generacion.CopiaAmpliada;
import com.junitgen.perfil.RecetasAprendidas;
import com.junitgen.generacion.EscritorInforme;
import com.junitgen.generacion.FusionadorArchivo;
import com.junitgen.generacion.ResolutorImports;
import com.junitgen.generacion.RutasSalida;
import com.junitgen.generacion.TextosInforme;
import com.junitgen.seguridad.BloqueosSalida;
import com.junitgen.seguridad.EscritorSeguro;
import com.junitgen.seguridad.GuardaIntegridad;
import com.junitgen.seguridad.ProcesoSeguro;
import com.junitgen.seguridad.RutaSegura;
import com.junitgen.spec.CasoSpec;
import com.junitgen.spec.Spec;
import com.junitgen.util.LimitesRespuesta;
import com.junitgen.util.Textos;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fase 5: compila y ejecuta los tests generados en {@code <salida>/.junit-gen-tmp} con el JDK del
 * proyecto, sin escribir nada en el proyecto (que se vigila con {@link GuardaIntegridad}).
 */
public final class VerificadorTests {

    private static final Pattern JAR_VERSION = Pattern.compile("^(junit-jupiter-api|junit|mockito-core|assertj-core)-(\\d[\\w.\\-]*)\\.jar$");

    private record ArchivoGenerado(Path archivo, String fqn, List<ExtractorHuellasTests.Marcador> marcadores, String texto) {
    }

    /** Título de la sección de informe.md de una verificación por lote. */
    static final String TITULO_VERIFICACION = "Verificación: prueba de los tests";
    /** Acción cuando los tests cambiaron mientras se verificaban (resultado descartado). */
    public static final String ACCION_DESCARTADA = "Los tests cambiaron durante la verificación: se repetirá.";
    /** Subcarpeta de trabajo de la verificación por lote dentro de {@code <tmp>/<módulo>}. */
    static final String CARPETA_LOTE = "lote";
    /** Subcarpeta de trabajo de la verificación por servicio dentro de {@code <tmp>/<módulo>}. */
    static final String CARPETA_SERVICIO = "srv";
    private static final int LONGITUD_HASH_SERVICIO = 8;

    public static final String ESTADO_POSIBLE_BUG = "posible_bug";
    public static final String ESTADO_DUDOSO = "dudoso";
    public static final String VEREDICTO_ERROR_IA = "error_ia";
    public static final String VEREDICTO_BUG = "bug_servicio";
    public static final String VEREDICTO_DUDOSO = "dudoso";
    public static final List<String> VEREDICTOS = List.of(VEREDICTO_ERROR_IA, VEREDICTO_BUG, VEREDICTO_DUDOSO);
    /** Máximo de entradas por llamada a registrar_analisis_ia. */
    public static final int MAX_ANALISIS = 100;
    /** Longitud máxima del motivo (texto de la IA). */
    public static final int MAX_MOTIVO = 500;
    /** Intentos máximos por caso (contador compartido intentosMontaje). */
    public static final int MAX_INTENTOS = 3;
    /** Rondas máximas de reparación automática de imports y recompilación. */
    static final int MAX_RONDAS_IMPORTS = 3;
    /** Inicio del mensaje de los casos que no compilan por un error común (fuera de los casos). */
    public static final String PREFIJO_ERROR_COMUN = "La clase no compila por un error fuera de los casos";

    /** Veredicto de la IA sobre un caso con fallo de aserción («Revisar con IA»). */
    public record AnalisisIa(String caso, String veredicto, String motivo) {
    }

    private VerificadorTests() {
    }

    public static ResultadoVerificacion verificar(ProyectoEscaneado proyecto, EscritorSeguro esc, String modulo, List<String> clases) {
        return verificar(proyecto, esc, modulo, clases, null);
    }

    /**
     * Como {@link #verificar(ProyectoEscaneado, EscritorSeguro, String, List)} limitando los resultados a
     * los ids de {@code casos} (null o vacío = todos): se omiten los archivos sin ninguno de esos casos y
     * las clases que los contienen se compilan y ejecutan enteras.
     */
    public static ResultadoVerificacion verificar(ProyectoEscaneado proyecto, EscritorSeguro esc, String modulo, List<String> clases,
                                                  Collection<String> casos) {
        return BloqueosSalida.conEjecucion(esc.salida(), () -> verificarBloqueado(proyecto, esc, modulo, clases, casos));
    }

    private static ResultadoVerificacion verificarBloqueado(ProyectoEscaneado proyecto, EscritorSeguro esc, String modulo,
                                                            List<String> clases, Collection<String> casos) {
        ResultadoVerificacion rv = new ResultadoVerificacion();
        List<ModuloEscaneado> modulos = new ArrayList<>();
        if (modulo != null && !modulo.isBlank()) {
            ModuloEscaneado m = proyecto.modulo(modulo);
            if (m == null) {
                throw new IllegalArgumentException("No existe el módulo '" + modulo + "'.");
            }
            modulos.add(m);
        } else {
            for (ModuloEscaneado m : proyecto.modulos) {
                if (!RutasSalida.archivosTestModulo(esc.salida(), m).isEmpty()) {
                    modulos.add(m);
                }
            }
        }
        if (modulos.isEmpty()) {
            rv.resumen = "No hay tests generados en " + esc.salida();
            rv.accionRequerida = "Genera primero los tests con generar_tests.";
            return rv;
        }
        List<String> jdks = new ArrayList<>();
        List<String> alertas = new ArrayList<>();
        StringBuilder informe = new StringBuilder();
        List<ArchivoGenerado> todos = new ArrayList<>();
        for (ModuloEscaneado me : modulos) {
            verificarModulo(proyecto, esc, me, clases, casos, rv, jdks, alertas, informe, todos);
        }
        cerrar(esc, rv, todos, jdks, alertas, informe, TITULO_VERIFICACION, proyecto);
        return rv;
    }

    /**
     * Verifica solo los tests generados de un servicio ({@code casos} = sus ids de caso): compila únicamente
     * el archivo de test que los contiene y ejecuta solo esa clase, reutilizando la preparación cacheada del
     * módulo ({@link PreparadorModulos}). Se ejecuta entero con el bloqueo de ejecución de la salida.
     */
    public static ResultadoVerificacion verificarServicio(ProyectoEscaneado proyecto, EscritorSeguro esc, String fqnServicio,
                                                         Collection<String> casos) {
        return verificarServicio(proyecto, esc, fqnServicio, casos, null);
    }

    /**
     * Como {@link #verificarServicio(ProyectoEscaneado, EscritorSeguro, String, Collection)}; solo los casos de
     * {@code contarIntentos} suman un intento de montaje si fallan (null = todos, como siempre).
     */
    public static ResultadoVerificacion verificarServicio(ProyectoEscaneado proyecto, EscritorSeguro esc, String fqnServicio,
                                                         Collection<String> casos, Collection<String> contarIntentos) {
        Path salida = esc.salida();
        if (ParalelismoVerificacion.efectivo(esc.proyecto(), salida) <= 1) {
            return BloqueosSalida.conEjecucion(salida,
                    () -> verificarServicioBloqueado(proyecto, esc, fqnServicio, casos, contarIntentos));
        }
        // Varias verificaciones a la vez: ejecución compartida. Nunca se toma la exclusiva dentro de la compartida.
        Intento intento = new Intento();
        intento.compartido = true;
        for (int vuelta = 0; vuelta < MAX_VUELTAS_COMPARTIDAS; vuelta++) {
            try {
                ResultadoVerificacion rv = BloqueosSalida.conEjecucionCompartida(salida,
                        () -> verificarServicioBloqueado(proyecto, esc, fqnServicio, casos, contarIntentos, intento));
                if (rv != null) {
                    return rv;
                }
                break;
            } catch (PreparadorModulos.PreparacionPendiente pendiente) {
                // Fuera de la compartida: un solo hilo prepara (el resto espera en la exclusiva y luego la encuentra hecha).
                try {
                    BloqueosSalida.conEjecucion(salida, () -> PreparadorModulos.obtener(proyecto, esc, pendiente.modulo()));
                } catch (RuntimeException e) {
                    // Queda cacheada: la verificación la convertirá en avisos como siempre.
                    System.err.println("junit-gen: no se pudo preparar el módulo " + pendiente.modulo().modulo.nombre() + ": "
                            + e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage()));
                }
            }
        }
        Intento exclusivo = new Intento();
        exclusivo.importsPrevios = intento.importsPrevios;
        exclusivo.sospechosos = intento.sospechosos;
        return BloqueosSalida.conEjecucion(salida,
                () -> verificarServicioBloqueado(proyecto, esc, fqnServicio, casos, contarIntentos, exclusivo));
    }

    /** Vueltas máximas en ejecución compartida antes de pasar a la exclusiva. */
    private static final int MAX_VUELTAS_COMPARTIDAS = 2;
    /** Cerrojos por carpeta de trabajo de un servicio: el mismo servicio no se verifica dos veces a la vez. */
    private static final Map<String, ReentrantLock> CERROJOS_SERVICIO = new ConcurrentHashMap<>();
    private static final String AVISO_SOLAPE_INTEGRIDAD =
            " (había otras verificaciones en paralelo: el cambio puede deberse a cualquiera de ellas)";
    /** Fragmentos (en minúsculas) del error que hacen sospechar de un choque de recursos entre pruebas en paralelo. */
    private static final List<String> PISTAS_CHOQUE = List.of("bindexception", "address already in use", "filesystemexception",
            "accessdeniedexception", "filealreadyexistsexception", "overlappingfilelockexception", "being used by another process",
            "utilizado por otro proceso", "already in use", "database is locked", "locked by another process",
            "superaron el tiempo máximo");

    /**
     * Estado de un intento de verificar un servicio: si va con la ejecución compartida, y lo que arrastra de un intento
     * compartido con solape cuyo resultado se descartó para repetirlo en solitario.
     */
    private static final class Intento {
        boolean compartido;
        List<String> importsPrevios;
        Map<String, String> sospechosos;
    }

    /** true si el resultado es un error de montaje o desconocido cuyo texto apunta a un choque de recursos. */
    static boolean sospechosoDeChoque(ResultadoTest t) {
        if (t == null || !(ClasificadorFallos.ERROR_MONTAJE.equals(t.estado) || ClasificadorFallos.DESCONOCIDO.equals(t.estado))) {
            return false;
        }
        String texto = (Objects.toString(t.tipoError, "") + " " + Objects.toString(t.mensaje, "") + " " + Objects.toString(t.causa, ""))
                .toLowerCase(Locale.ROOT);
        for (String p : PISTAS_CHOQUE) {
            if (texto.contains(p)) {
                return true;
            }
        }
        return false;
    }

    private static ResultadoVerificacion verificarServicioBloqueado(ProyectoEscaneado proyecto, EscritorSeguro esc, String fqnServicio,
                                                                    Collection<String> casos) {
        return verificarServicioBloqueado(proyecto, esc, fqnServicio, casos, null);
    }

    private static ResultadoVerificacion verificarServicioBloqueado(ProyectoEscaneado proyecto, EscritorSeguro esc, String fqnServicio,
                                                                    Collection<String> casos, Collection<String> contarIntentos) {
        return verificarServicioBloqueado(proyecto, esc, fqnServicio, casos, contarIntentos, null);
    }

    /**
     * Verifica un servicio. Con {@code intento} compartido devuelve null (sin escribir nada) si hubo solape con otras
     * verificaciones y algún resultado es sospechoso de choque de recursos: el llamante lo repite en exclusiva.
     */
    private static ResultadoVerificacion verificarServicioBloqueado(ProyectoEscaneado proyecto, EscritorSeguro esc, String fqnServicio,
                                                                    Collection<String> casos, Collection<String> contarIntentos,
                                                                    Intento intento) {
        boolean compartido = intento != null && intento.compartido;
        ResultadoVerificacion rv = new ResultadoVerificacion();
        String simple = Textos.nombreSimple(fqnServicio);
        if (casos == null || casos.isEmpty()) {
            rv.resumen = "No hay casos de " + simple + " que verificar.";
            rv.accionRequerida = "Genera primero los tests con generar_tests.";
            return rv;
        }
        ModuloEscaneado modulo = null;
        List<ArchivoGenerado> generados = List.of();
        for (ModuloEscaneado me : proyecto.modulos) {
            List<ArchivoGenerado> g = BloqueosSalida.conEscritura(esc.salida(), () -> generados(esc.salida(), me, null, casos));
            if (!g.isEmpty()) {
                modulo = me;
                generados = g;
                break;
            }
        }
        if (modulo == null) {
            rv.resumen = "No hay tests generados de " + simple + " en " + esc.salida();
            rv.accionRequerida = "Genera primero los tests con generar_tests.";
            return rv;
        }
        List<String> jdks = new ArrayList<>();
        List<String> alertas = new ArrayList<>();
        StringBuilder informe = new StringBuilder();
        informe.append("- Módulo ").append(TextosInforme.codigo(modulo.modulo.nombre(), 200)).append('\n');
        Path dirTrabajo = PreparadorModulos.dirModulo(esc, modulo).resolve(CARPETA_SERVICIO)
                .resolve(Textos.aIdentificador(simple) + "-" + Textos.sha256Hex(fqnServicio, LONGITUD_HASH_SERVICIO));
        if (!compartido) {
            ejecutarEnModulo(proyecto, esc, modulo, generados, dirTrabajo, rv, jdks, alertas, informe, false);
            if (intento != null) {
                aplicarRepeticionEnSolitario(esc, rv, intento, simple);
            }
            cerrar(esc, rv, generados, jdks, alertas, informe, "Verificación: prueba de los tests de `" + simple + "`", proyecto,
                    contarIntentos);
            return rv;
        }
        // Compartido: el mismo servicio nunca se verifica dos veces a la vez (cerrojo por carpeta de trabajo, hasta tras cerrar).
        ReentrantLock cerrojoServicio = CERROJOS_SERVICIO.computeIfAbsent(dirTrabajo.toAbsolutePath().normalize().toString()
                .toLowerCase(Locale.ROOT), k -> new ReentrantLock());
        cerrojoServicio.lock();
        try {
            // Tras esperar el cerrojo, los tests pueden haber cambiado: se vuelven a leer.
            ModuloEscaneado m = modulo;
            List<ArchivoGenerado> actuales = BloqueosSalida.conEscritura(esc.salida(), () -> generados(esc.salida(), m, null, casos));
            if (!actuales.isEmpty()) {
                generados = actuales;
            }
            boolean solape;
            ParalelismoVerificacion.Ventana ventana = ParalelismoVerificacion.abrirVentana(esc.salida());
            try {
                ejecutarEnModulo(proyecto, esc, modulo, generados, dirTrabajo, rv, jdks, alertas, informe, true);
            } finally {
                solape = ventana.cerrar();
            }
            if (solape) {
                alertas.replaceAll(a -> a + AVISO_SOLAPE_INTEGRIDAD);
                Map<String, String> sospechosos = new LinkedHashMap<>();
                for (ResultadoTest t : rv.resultados) {
                    if (t.caso != null && sospechosoDeChoque(t)) {
                        sospechosos.put(t.caso, t.estado);
                    }
                }
                if (!sospechosos.isEmpty() && ParalelismoVerificacion.repeticionPermitida(esc.salida())) {
                    intento.sospechosos = sospechosos;
                    intento.importsPrevios = new ArrayList<>(rv.importsReparados);
                    return null;
                }
            }
            cerrar(esc, rv, generados, jdks, alertas, informe, "Verificación: prueba de los tests de `" + simple + "`", proyecto,
                    contarIntentos);
            return rv;
        } finally {
            cerrojoServicio.unlock();
        }
    }

    /**
     * Segundo intento (en exclusiva) tras un resultado sospechoso con solape: si algún caso sospechoso ya no lo es, hubo un
     * choque de recursos (esa salida pasa a verificar de una en una, con aviso); si no, solo se anota la repetición.
     * En ambos casos recupera los imports reparados en el intento descartado. Antes de cerrar, para que el aviso salga en
     * informe.md.
     */
    private static void aplicarRepeticionEnSolitario(EscritorSeguro esc, ResultadoVerificacion rv, Intento intento, String simple) {
        if (intento.importsPrevios != null) {
            for (String f : intento.importsPrevios) {
                if (!rv.importsReparados.contains(f)) {
                    rv.importsReparados.add(f);
                }
            }
        }
        if (intento.sospechosos == null || intento.sospechosos.isEmpty()) {
            return;
        }
        boolean choque = false;
        for (String caso : intento.sospechosos.keySet()) {
            for (ResultadoTest t : rv.resultados) {
                if (caso.equals(t.caso) && !sospechosoDeChoque(t)) {
                    choque = true;
                    break;
                }
            }
        }
        if (choque) {
            ParalelismoVerificacion.registrarChoque(esc.salida());
            rv.avisos.add("Posible choque de recursos entre pruebas en paralelo: `" + simple + "` se repitió en solitario y desde ahora "
                    + "las pruebas de esta carpeta van de una en una. Para fijarlo, pon `verificadores: 1` en junit-gen.yml.");
        } else {
            ParalelismoVerificacion.contarRepeticionSinChoque(esc.salida());
        }
    }

    /**
     * Cierre común: JDK, alertas y resumen; después, con el bloqueo de escritura, relee los archivos verificados y,
     * si alguno cambió durante la verificación, descarta el resultado sin escribir; si no, actualiza spec.json e
     * informe.md.
     */
    private static void cerrar(EscritorSeguro esc, ResultadoVerificacion rv, List<ArchivoGenerado> generados, List<String> jdks,
                               List<String> alertas, StringBuilder informe, String titulo, ProyectoEscaneado proyecto) {
        cerrar(esc, rv, generados, jdks, alertas, informe, titulo, proyecto, null);
    }

    /** Como el cierre común; solo los casos de {@code contarIntentos} suman intento de montaje (null = todos). */
    private static void cerrar(EscritorSeguro esc, ResultadoVerificacion rv, List<ArchivoGenerado> generados, List<String> jdks,
                               List<String> alertas, StringBuilder informe, String titulo, ProyectoEscaneado proyecto,
                               Collection<String> contarIntentos) {
        rv.jdkUsado = String.join("; ", jdks);
        if (!alertas.isEmpty()) {
            rv.alertaIntegridad = alertas;
        }
        Map<String, Long> cuenta = new LinkedHashMap<>();
        for (ResultadoTest t : rv.resultados) {
            cuenta.merge(t.estado, 1L, Long::sum);
        }
        rv.resumen = rv.resultados.size() + " tests: " + cuenta;
        BloqueosSalida.conEscritura(esc.salida(), () -> {
            if (cambiaronDuranteVerificacion(generados)) {
                rv.descartada = true;
                rv.accionRequerida = ACCION_DESCARTADA;
                return null;
            }
            actualizarSpecEInforme(esc, rv, informe, titulo, proyecto, contarIntentos);
            return null;
        });
    }

    /** true si algún archivo verificado ya no tiene el texto capturado al empezar (o no se puede leer). */
    private static boolean cambiaronDuranteVerificacion(List<ArchivoGenerado> generados) {
        for (ArchivoGenerado g : generados) {
            String actual;
            try {
                actual = Files.readString(g.archivo(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                System.err.println("junit-gen: no se pudo releer " + g.archivo() + " tras la verificación: " + e.getMessage());
                return true;
            }
            if (!actual.equals(g.texto())) {
                return true;
            }
        }
        return false;
    }

    private static void verificarModulo(ProyectoEscaneado proyecto, EscritorSeguro esc, ModuloEscaneado me, List<String> clases,
                                        Collection<String> casos, ResultadoVerificacion rv, List<String> jdks, List<String> alertas,
                                        StringBuilder informe, List<ArchivoGenerado> todos) {
        List<ArchivoGenerado> generados = BloqueosSalida.conEscritura(esc.salida(), () -> generados(esc.salida(), me, clases, casos));
        informe.append("- Módulo ").append(TextosInforme.codigo(me.modulo.nombre(), 200)).append('\n');
        if (generados.isEmpty()) {
            rv.avisos.add("No hay tests generados para el módulo " + me.modulo.nombre() + (clases == null || clases.isEmpty() ? "" : " con las clases " + clases)
                    + (casos == null || casos.isEmpty() ? "" : " con los casos " + LimitesRespuesta.listaCorta(casos, 10)));
            informe.append("  - No hay tests creados para este módulo.\n");
            return;
        }
        ejecutarEnModulo(proyecto, esc, me, generados, PreparadorModulos.dirModulo(esc, me).resolve(CARPETA_LOTE), rv, jdks, alertas,
                informe);
        // Después de ejecutar: la reparación de imports sustituye entradas de la lista en sitio.
        todos.addAll(generados);
    }

    /**
     * Compila y ejecuta {@code generados} del módulo en {@code dirTrabajo} (se borra y se crea) con la preparación
     * cacheada del módulo, con instantáneas de {@link GuardaIntegridad} antes y después.
     */
    private static void ejecutarEnModulo(ProyectoEscaneado proyecto, EscritorSeguro esc, ModuloEscaneado me, List<ArchivoGenerado> generados,
                                         Path dirTrabajo, ResultadoVerificacion rv, List<String> jdks, List<String> alertas,
                                         StringBuilder informe) {
        ejecutarEnModulo(proyecto, esc, me, generados, dirTrabajo, rv, jdks, alertas, informe, false);
    }

    /**
     * Como {@link #ejecutarEnModulo(ProyectoEscaneado, EscritorSeguro, ModuloEscaneado, List, Path, ResultadoVerificacion, List,
     * List, StringBuilder)}; con {@code compartida} (ejecución compartida de la salida) nunca prepara el módulo: si hay que
     * prepararlo lanza {@link PreparadorModulos.PreparacionPendiente}.
     */
    private static void ejecutarEnModulo(ProyectoEscaneado proyecto, EscritorSeguro esc, ModuloEscaneado me, List<ArchivoGenerado> generados,
                                         Path dirTrabajo, ResultadoVerificacion rv, List<String> jdks, List<String> alertas,
                                         StringBuilder informe, boolean compartida) {
        Map<String, GuardaIntegridad.Entrada> antes = GuardaIntegridad.instantanea(me.modulo.dir, false);
        String origenCp = "";
        try {
            PreparadorModulos.Preparacion prep = compartida ? PreparadorModulos.obtenerSinPreparar(proyecto, esc, me)
                    : PreparadorModulos.obtener(proyecto, esc, me);
            if (prep == null) {
                throw new PreparadorModulos.PreparacionPendiente(me);
            }
            origenCp = prep.origenCp();
            jdks.add(me.modulo.nombre() + ": " + prep.jdk().jdk().home() + " (" + prep.jdk().jdk().version() + ")"
                    + (prep.jdk().release() != null ? " --release " + prep.jdk().release() : ""));
            for (String a : prep.avisos()) {
                if (!rv.avisos.contains(a)) {
                    rv.avisos.add(a);
                }
            }
            ContextoVerificacion ctx = new ContextoVerificacion();
            ctx.proyecto = proyecto;
            ctx.modulo = me;
            ctx.escritor = esc;
            ctx.proceso = new ProcesoSeguro(esc);
            ctx.jdk = prep.jdk();
            ctx.tmp = dirTrabajo;
            esc.borrarTemporal(ctx.tmp);
            esc.carpetaTemporal(ctx.tmp);
            Path mvn = prep.mvn();
            List<Path> deps = prep.deps();
            versionesReales(deps, me, rv);
            List<Path> cpCompilacion = PreparadorModulos.classpathCompilacion(prep, me);
            List<Path> archivos = new ArrayList<>();
            generados.forEach(g -> archivos.add(g.archivo()));
            CompiladorTemporal.ResultadoTests ct = CompiladorTemporal.compilarTests(ctx, archivos, cpCompilacion);
            // Red de seguridad sin IA: imports rotos o que faltan; hasta MAX_RONDAS_IMPORTS recompilaciones, sin gastar intentosMontaje.
            CatalogoClases catalogo = null;
            boolean catalogoCreado = false;
            Map<Path, Set<String>> excluidosPorArchivo = new HashMap<>();
            for (int ronda = 1; ronda <= MAX_RONDAS_IMPORTS && !ct.errores().isEmpty(); ronda++) {
                if (!catalogoCreado) {
                    catalogoCreado = true;
                    try {
                        catalogo = CatalogoClases.de(prep.jdk().jdk().home(), cpCompilacion, me.indice);
                    } catch (RuntimeException e) {
                        System.err.println("junit-gen: sin catálogo de clases para reparar imports: " + e.getMessage());
                        catalogo = null;
                    }
                }
                ReparacionImports rep = repararImports(esc, me, generados, ct, catalogo, excluidosPorArchivo);
                for (String a : rep.errores()) {
                    if (!rv.avisos.contains(a)) {
                        rv.avisos.add(a);
                    }
                }
                if (rep.anadidos().isEmpty()) {
                    break;
                }
                for (String a : rep.anadidos()) {
                    if (!rv.avisos.contains(a)) {
                        rv.avisos.add(a);
                    }
                }
                for (String f : rep.frases()) {
                    if (!rv.importsReparados.contains(f)) {
                        rv.importsReparados.add(f);
                    }
                }
                ct = CompiladorTemporal.compilarTests(ctx, archivos, cpCompilacion, ronda == 1 ? "-reintento" : "-reintento" + ronda);
            }
            // Aislamiento por caso: los casos que no compilan se excluyen de una copia temporal y el resto se ejecuta.
            Map<Path, Aislamiento> aislamientos = new LinkedHashMap<>();
            List<Path> cpAislado = new ArrayList<>(cpCompilacion);
            cpAislado.add(ct.clases());
            for (int i = 0; i < generados.size(); i++) {
                ArchivoGenerado g = generados.get(i);
                List<CompiladorTemporal.Diagnostico> diags = ct.errores().get(g.archivo());
                if (diags != null) {
                    aislamientos.put(g.archivo(), aislarCasos(ctx, g, diags, cpAislado, i));
                }
            }
            Map<String, ResultadoTest> ejecutados = new LinkedHashMap<>();
            EjecutorJUnit.Ejecucion ej = null;
            List<String> fqns = new ArrayList<>();
            List<Path> cpEjecucion = new ArrayList<>();
            cpEjecucion.add(ct.clases());
            for (ArchivoGenerado g : generados) {
                Aislamiento a = aislamientos.get(g.archivo());
                if (a == null) {
                    if (ct.compilados().contains(g.archivo())) {
                        fqns.add(g.fqn());
                    }
                } else if (a.compilado() && g.marcadores().stream().anyMatch(mk -> !a.excluidos().containsKey(mk.id()))) {
                    fqns.add(g.fqn());
                    cpEjecucion.add(a.clases());
                }
            }
            cpEjecucion.addAll(cpCompilacion);
            if (!fqns.isEmpty()) {
                ej = EjecutorJUnit.ejecutar(ctx, fqns, cpEjecucion, mvn);
                for (ResultadoTest t : ej.resultados()) {
                    ejecutados.put(t.clase + "#" + t.metodoTest, t);
                }
                // Copias ampliadas: los tests originales del usuario se ejecutan con la clase pero no son casos.
                for (ArchivoGenerado g : generados) {
                    if (fqns.contains(g.fqn()) && CopiaAmpliada.es(g.texto())) {
                        VerificadorMezcla.revisarOriginales(ctx, cpCompilacion, mvn, g.fqn(), g.texto(), ej, rv, informe);
                    }
                }
            }
            try (PistasCompilacion pistas = new PistasCompilacion(me.indice, cpCompilacion, catalogo)) {
                for (ArchivoGenerado g : generados) {
                    Aislamiento a = aislamientos.get(g.archivo());
                    for (ExtractorHuellasTests.Marcador mk : g.marcadores()) {
                        ResultadoTest t;
                        List<CompiladorTemporal.Diagnostico> propios = a == null ? null : a.excluidos().get(mk.id());
                        if (propios != null) {
                            t = new ResultadoTest();
                            t.estado = ClasificadorFallos.NO_COMPILA;
                            t.tipoError = "compilación";
                            t.mensaje = lineasDiagnostico(propios);
                            t.pista = pistas.pista(propios.get(0), g.texto());
                            t.errorComun = false;
                        } else if (a != null && !a.compilado()) {
                            t = new ResultadoTest();
                            t.estado = ClasificadorFallos.NO_COMPILA;
                            t.tipoError = "compilación";
                            t.mensaje = PREFIJO_ERROR_COMUN + " (cabecera, imports, campos o montaje común: "
                                    + "revisa `construccion`/`fixtures`/`imports` del servicio, no este caso): " + lineasDiagnostico(a.comunes());
                            t.pista = a.comunes().isEmpty() ? null : pistas.pista(a.comunes().get(0), g.texto());
                            t.errorComun = true;
                        } else {
                            ResultadoTest e = ejecutados.get(g.fqn() + "#" + mk.metodoTest());
                            if (e != null) {
                                t = e;
                            } else {
                                t = new ResultadoTest();
                                if (ej != null && "JUnitCore".equals(ej.plataforma())) {
                                    t.estado = ClasificadorFallos.PASA;
                                } else {
                                    t.estado = ClasificadorFallos.DESCONOCIDO;
                                    t.mensaje = "No aparece en el informe XML de JUnit. Salida: "
                                            + Textos.recortar(ej == null ? "" : ResolutorClasspathMaven.ultimas(ej.salida(), 15), 2000);
                                }
                            }
                        }
                        t.caso = mk.id();
                        t.clase = g.fqn();
                        t.metodoTest = mk.metodoTest();
                        rv.resultados.add(t);
                    }
                    if (a != null && !a.excluidos().isEmpty()) {
                        String aviso = "`" + Textos.nombreSimple(g.fqn()) + "`: " + a.excluidos().size() + " caso(s) no compilan ("
                                + LimitesRespuesta.listaCorta(a.excluidos().keySet(), 5) + ") y se han excluido de esta prueba; "
                                + (a.compilado() ? "el resto se ha compilado y ejecutado sin ellos."
                                : "el resto no compila por un error fuera de los casos.");
                        if (!rv.avisos.contains(aviso)) {
                            rv.avisos.add(aviso);
                        }
                    }
                }
            }
            for (String a : ctx.avisos) {
                if (!rv.avisos.contains(a)) {
                    rv.avisos.add(a);
                }
            }
        } catch (PreparadorModulos.PreparacionPendiente e) {
            throw e;
        } catch (RuntimeException e) {
            rv.avisos.add("Verificación del módulo " + me.modulo.nombre() + " interrumpida: " + e.getMessage());
            String msg = TextosInforme.codigo(e.getMessage(), 500);
            informe.append("  - La prueba se interrumpió: ").append(msg.isEmpty() ? TextosInforme.SIN_DATO : msg).append('\n');
            for (ArchivoGenerado g : generados) {
                for (ExtractorHuellasTests.Marcador mk : g.marcadores()) {
                    ResultadoTest t = new ResultadoTest();
                    t.caso = mk.id();
                    t.clase = g.fqn();
                    t.metodoTest = mk.metodoTest();
                    t.estado = ClasificadorFallos.DESCONOCIDO;
                    t.mensaje = e.getMessage();
                    rv.resultados.add(t);
                }
            }
        } finally {
            Map<String, GuardaIntegridad.Entrada> despues = GuardaIntegridad.instantanea(me.modulo.dir, false);
            List<String> dif = GuardaIntegridad.comparar(antes, despues);
            for (String d : dif) {
                alertas.add(me.modulo.dir + ": " + d);
            }
        }
        informe.append("  - Java usado: ").append(jdks.isEmpty() ? TextosInforme.SIN_DATO : TextosInforme.texto(jdks.get(jdks.size() - 1), 500))
                .append('\n');
        informe.append("  - Librerías obtenidas con: ").append(TextosInforme.texto(origenCp, 500)).append('\n');
    }

    /** Rondas máximas de recompilación al aislar casos que no compilan (javac informa la sintaxis en una pasada previa). */
    static final int MAX_RONDAS_AISLAMIENTO = 4;
    private static final int MAX_DIAGNOSTICOS_MENSAJE = 3;

    /**
     * Resultado de aislar los casos de un archivo que no compila: {@code compilado} = la copia sin los
     * {@code excluidos} compila en {@code clases}; {@code comunes} = diagnósticos fuera de cualquier caso.
     */
    private record Aislamiento(boolean compilado, Path clases, Map<String, List<CompiladorTemporal.Diagnostico>> excluidos,
                               List<CompiladorTemporal.Diagnostico> comunes) {
    }

    /**
     * Atribuye los errores de compilación de {@code g} a sus casos por rango de líneas y recompila una copia
     * temporal con las líneas de los casos culpables en blanco (mismo número de líneas), hasta
     * {@link #MAX_RONDAS_AISLAMIENTO} rondas. Si queda algún error fuera de los casos, es común.
     */
    private static Aislamiento aislarCasos(ContextoVerificacion ctx, ArchivoGenerado g, List<CompiladorTemporal.Diagnostico> diags,
                                           List<Path> cp, int indiceArchivo) {
        Map<String, List<CompiladorTemporal.Diagnostico>> excluidos = new LinkedHashMap<>();
        List<CompiladorTemporal.Diagnostico> actuales = delArchivo(diags, g.archivo());
        try {
            List<ExtractorHuellasTests.Marcador> todos = ExtractorHuellasTests.marcadores(g.texto()).stream()
                    .filter(m -> m.lineaFin() > 0 && !m.apartado()).toList();
            String[] lineas = g.texto().split("\\R", -1);
            Path base = ctx.tmp.resolve("aislado-casos").resolve("a" + indiceArchivo);
            for (int ronda = 1; ronda <= MAX_RONDAS_AISLAMIENTO; ronda++) {
                Map<String, List<CompiladorTemporal.Diagnostico>> nuevos = atribuir(actuales, todos, excluidos);
                if (nuevos.isEmpty()) {
                    return new Aislamiento(false, null, excluidos, fueraDeCasos(actuales, todos));
                }
                excluidos.putAll(nuevos);
                String[] copia = lineas.clone();
                for (ExtractorHuellasTests.Marcador m : todos) {
                    if (excluidos.containsKey(m.id())) {
                        for (int l = m.lineaInicio(); l <= m.lineaFin() && l - 1 < copia.length; l++) {
                            copia[l - 1] = "";
                        }
                    }
                }
                Path dir = base.resolve("r" + ronda);
                Path src = ctx.escritor.carpetaTemporal(dir.resolve("src"));
                Path archivo = src.resolve(nombreArchivo(g.archivo()));
                ctx.escritor.escribir(archivo, String.join("\n", copia));
                CompiladorTemporal.ResultadoArchivo ra = CompiladorTemporal.compilarArchivo(ctx, archivo, cp, dir.resolve("clases"),
                        "javac-aislado-casos-" + indiceArchivo + "-" + ronda);
                if (ra.correcto()) {
                    return new Aislamiento(true, dir.resolve("clases"), excluidos, List.of());
                }
                actuales = ra.diagnosticos();
            }
            // Rondas agotadas: los casos con diagnóstico propio son culpables; el resto, error común.
            excluidos.putAll(atribuir(actuales, todos, excluidos));
            List<CompiladorTemporal.Diagnostico> fuera = fueraDeCasos(actuales, todos);
            return new Aislamiento(false, null, excluidos, fuera.isEmpty() ? actuales : fuera);
        } catch (RuntimeException e) {
            System.err.println("junit-gen: no se pudieron aislar los casos de " + nombreArchivo(g.archivo()) + ": " + e.getMessage());
            return new Aislamiento(false, null, new LinkedHashMap<>(), actuales);
        }
    }

    /** Diagnósticos del archivo (por nombre); si ninguno coincide, todos. */
    private static List<CompiladorTemporal.Diagnostico> delArchivo(List<CompiladorTemporal.Diagnostico> diags, Path archivo) {
        String nombre = nombreArchivo(archivo);
        List<CompiladorTemporal.Diagnostico> r = new ArrayList<>();
        for (CompiladorTemporal.Diagnostico d : diags) {
            if (d.archivo() == null || d.linea() <= 0 || nombre.equals(nombreArchivo(d.archivo()))) {
                r.add(d);
            }
        }
        return r.isEmpty() ? diags : r;
    }

    /** Diagnósticos por id de los casos aún no excluidos cuyo rango contiene su línea. */
    private static Map<String, List<CompiladorTemporal.Diagnostico>> atribuir(List<CompiladorTemporal.Diagnostico> diags,
                                                                             List<ExtractorHuellasTests.Marcador> todos,
                                                                             Map<String, List<CompiladorTemporal.Diagnostico>> excluidos) {
        Map<String, List<CompiladorTemporal.Diagnostico>> r = new LinkedHashMap<>();
        for (CompiladorTemporal.Diagnostico d : diags) {
            ExtractorHuellasTests.Marcador m = casoDeLinea(todos, d.linea());
            if (m != null && !excluidos.containsKey(m.id())) {
                r.computeIfAbsent(m.id(), k -> new ArrayList<>()).add(d);
            }
        }
        return r;
    }

    private static List<CompiladorTemporal.Diagnostico> fueraDeCasos(List<CompiladorTemporal.Diagnostico> diags,
                                                                    List<ExtractorHuellasTests.Marcador> todos) {
        List<CompiladorTemporal.Diagnostico> r = new ArrayList<>();
        for (CompiladorTemporal.Diagnostico d : diags) {
            if (casoDeLinea(todos, d.linea()) == null) {
                r.add(d);
            }
        }
        return r;
    }

    private static ExtractorHuellasTests.Marcador casoDeLinea(List<ExtractorHuellasTests.Marcador> todos, int linea) {
        for (ExtractorHuellasTests.Marcador m : todos) {
            if (linea >= m.lineaInicio() && linea <= m.lineaFin()) {
                return m;
            }
        }
        return null;
    }

    /** «línea N: …» de hasta 3 diagnósticos, unidos por « | ». */
    private static String lineasDiagnostico(List<CompiladorTemporal.Diagnostico> diags) {
        List<String> r = new ArrayList<>();
        for (CompiladorTemporal.Diagnostico d : diags) {
            if (r.size() >= MAX_DIAGNOSTICOS_MENSAJE) {
                break;
            }
            r.add("línea " + d.linea() + ": " + d.mensaje());
        }
        return String.join(" | ", r);
    }

    /**
     * Avisos de imports corregidos (disparan la recompilación), de reparaciones que fallaron (no la disparan) y
     * frases «`ClaseTest`: cambio» para informe.md.
     */
    private record ReparacionImports(List<String> anadidos, List<String> errores, List<String> frases) {
    }

    /**
     * Con el bloqueo de escritura (se libera antes de recompilar): en los tests que no compilan sustituye o quita
     * los imports con error y añade los de las clases que javac no encontró y que se resuelven sin ambigüedad
     * (ver {@link ReparadorImports}). Solo toca archivos que siguen teniendo el texto capturado y sus
     * marcadores; actualiza {@code generados} en sitio. {@code excluidosPorArchivo} acumula entre rondas los
     * FQN que ya han fallado.
     */
    private static ReparacionImports repararImports(EscritorSeguro esc, ModuloEscaneado me, List<ArchivoGenerado> generados,
                                                    CompiladorTemporal.ResultadoTests ct, CatalogoClases catalogo,
                                                    Map<Path, Set<String>> excluidosPorArchivo) {
        return BloqueosSalida.conEscritura(esc.salida(), () -> {
            List<String> anadidos = new ArrayList<>();
            List<String> errores = new ArrayList<>();
            List<String> frases = new ArrayList<>();
            Map<Path, Set<String>> faltan = CompiladorTemporal.clasesNoEncontradas(ct.salida());
            if (faltan.isEmpty() && catalogo == null) {
                return new ReparacionImports(anadidos, errores, frases);
            }
            Spec spec = leerSpecOpcional(esc);
            for (int i = 0; i < generados.size(); i++) {
                ArchivoGenerado g = generados.get(i);
                List<CompiladorTemporal.Diagnostico> diags = ct.errores().get(g.archivo());
                if (diags == null) {
                    continue;
                }
                Set<String> clases = new LinkedHashSet<>();
                for (Map.Entry<Path, Set<String>> e : faltan.entrySet()) {
                    if (mismaRuta(e.getKey(), g.archivo())) {
                        clases.addAll(e.getValue());
                    }
                }
                if (clases.isEmpty() && catalogo == null) {
                    continue;
                }
                try {
                    Set<String> excluidos = excluidosPorArchivo.computeIfAbsent(g.archivo(), k -> new LinkedHashSet<>());
                    String aviso = repararArchivo(esc, me, spec, generados, i, clases, diags, catalogo, excluidos, frases);
                    if (aviso != null) {
                        anadidos.add(aviso);
                    }
                } catch (RuntimeException e) {
                    errores.add("No se pudieron corregir los imports de `" + nombreArchivo(g.archivo()) + "`: " + e.getMessage());
                }
            }
            return new ReparacionImports(anadidos, errores, frases);
        });
    }

    /** Repara un archivo; devuelve el aviso de los imports corregidos o null si no se ha tocado. */
    private static String repararArchivo(EscritorSeguro esc, ModuloEscaneado me, Spec spec, List<ArchivoGenerado> generados, int i,
                                         Set<String> clases, List<CompiladorTemporal.Diagnostico> diags, CatalogoClases catalogo,
                                         Set<String> excluidos, List<String> frases) {
        ArchivoGenerado g = generados.get(i);
        String actual;
        try {
            actual = Files.readString(g.archivo(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("junit-gen: no se pudo releer " + g.archivo() + " para corregir imports: " + e.getMessage());
            return null;
        }
        if (!actual.equals(g.texto()) || !FusionadorArchivo.tieneMarcadores(actual)) {
            return null;
        }
        String paquete = RutasSalida.paqueteDeclarado(actual);
        AlmacenSpec.Ubicacion u = spec == null || g.marcadores().isEmpty() ? null : AlmacenSpec.buscar(spec, g.marcadores().get(0).id());
        InfoClase servicio = null;
        if (u != null && u.servicio() != null && u.servicio().clase != null) {
            String clase = u.servicio().clase.trim();
            String simple = Textos.nombreSimple(clase);
            servicio = me.indice.get(paquete.isEmpty() ? simple : paquete + "." + simple);
            if (servicio == null && clase.contains(".")) {
                servicio = me.indice.get(clase);
            }
        }
        List<String> importsSpec = u == null || u.servicio() == null || u.servicio().imports == null ? List.of() : u.servicio().imports;
        ResolutorImports ri = servicio == null ? null : new ResolutorImports(me.indice, servicio, importsSpec);
        ReparadorImports.Resultado res = ReparadorImports.reparar(actual, diags, clases, paquete, catalogo, ri, importsSpec, me.indice,
                excluidos);
        if (res.cambios().isEmpty() || res.texto().equals(actual)) {
            return null;
        }
        String nuevo = res.texto();
        esc.escribir(g.archivo(), nuevo);
        Set<String> ids = new HashSet<>();
        g.marcadores().forEach(m -> ids.add(m.id()));
        List<ExtractorHuellasTests.Marcador> marcadores = ExtractorHuellasTests.marcadores(nuevo).stream()
                .filter(m -> ids.contains(m.id()) && !m.apartado()).toList();
        generados.set(i, new ArchivoGenerado(g.archivo(), g.fqn(), marcadores, nuevo));
        String claseTest = Textos.nombreSimple(g.fqn());
        for (String c : res.cambios()) {
            frases.add("`" + claseTest + "`: " + c);
        }
        return "Imports de `" + nombreArchivo(g.archivo()) + "` corregidos automáticamente: " + String.join("; ", res.cambios())
                + "; se ha recompilado.";
    }

    private static Spec leerSpecOpcional(EscritorSeguro esc) {
        try {
            return AlmacenSpec.leer(esc.salida());
        } catch (IllegalStateException e) {
            return null;
        }
    }

    private static boolean mismaRuta(Path a, Path b) {
        try {
            return a.toAbsolutePath().normalize().equals(b.toAbsolutePath().normalize());
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static String nombreArchivo(Path p) {
        return p.getFileName() == null ? p.toString() : p.getFileName().toString();
    }

    private static void versionesReales(List<Path> deps, ModuloEscaneado me, ResultadoVerificacion rv) {
        for (Path p : deps) {
            if (p.getFileName() == null) {
                continue;
            }
            Matcher m = JAR_VERSION.matcher(p.getFileName().toString());
            if (m.find()) {
                rv.versionesReales.put(m.group(1), m.group(2));
            }
        }
        comparar(rv, "junit-jupiter-api", me.versiones.versionJunit5, "JUnit 5");
        comparar(rv, "mockito-core", me.versiones.versionMockito, "Mockito");
        comparar(rv, "assertj-core", me.versiones.versionAssertj, "AssertJ");
        comparar(rv, "junit", me.versiones.versionJunit4, "JUnit 4");
    }

    private static void comparar(ResultadoVerificacion rv, String jar, String estatica, String nombre) {
        String real = rv.versionesReales.get(jar);
        if (real != null && estatica != null && !real.equals(estatica)) {
            rv.avisos.add(nombre + ": el escaneo estático indicó " + estatica + " pero el classpath real usa " + real + ".");
        }
    }

    private static List<ArchivoGenerado> generados(Path salida, ModuloEscaneado me, List<String> clases, Collection<String> casos) {
        List<ArchivoGenerado> r = new ArrayList<>();
        for (Path f : RutasSalida.archivosTestModulo(salida, me)) {
            String texto;
            try {
                texto = Files.readString(f, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IllegalStateException("No se pudieron leer los tests generados en " + f + ": " + e.getMessage(), e);
            }
            if (!texto.contains("junit-gen:montaje=")) {
                continue;
            }
            String nombre = f.getFileName().toString();
            String simple = nombre.substring(0, nombre.length() - ".java".length());
            String paquete = RutasSalida.paqueteDeclarado(texto);
            String fqn = paquete.isEmpty() ? simple : paquete + "." + simple;
            if (clases != null && !clases.isEmpty() && clases.stream().noneMatch(c -> c.equals(fqn) || c.equals(simple)
                    || c.equals(simple.replaceAll("(Generado)?Test$", "")) || fqn.endsWith("." + c + "Test"))) {
                continue;
            }
            List<ExtractorHuellasTests.Marcador> leidos = ExtractorHuellasTests.marcadores(texto);
            // Los casos apartados (bloque comentado) no se vuelven a verificar.
            List<ExtractorHuellasTests.Marcador> marcadores = leidos.stream().filter(m -> !m.apartado()).toList();
            if (marcadores.size() < leidos.size() && marcadores.isEmpty()) {
                continue;
            }
            if (casos != null && !casos.isEmpty()) {
                marcadores = marcadores.stream().filter(m -> casos.contains(m.id())).toList();
                if (marcadores.isEmpty()) {
                    continue;
                }
            }
            r.add(new ArchivoGenerado(f, fqn, marcadores, texto));
        }
        return r;
    }

    static void actualizarSpecEInforme(EscritorSeguro esc, ResultadoVerificacion rv, StringBuilder informe) {
        actualizarSpecEInforme(esc, rv, informe, TITULO_VERIFICACION);
    }

    /** Como {@link #actualizarSpecEInforme(EscritorSeguro, ResultadoVerificacion, StringBuilder)} con el título de sección dado. */
    static void actualizarSpecEInforme(EscritorSeguro esc, ResultadoVerificacion rv, StringBuilder informe, String titulo) {
        actualizarSpecEInforme(esc, rv, informe, titulo, null);
    }

    /**
     * Como {@link #actualizarSpecEInforme(EscritorSeguro, ResultadoVerificacion, StringBuilder, String)}; con
     * {@code proyecto} ≠ null aprende recetas de montaje en perfil-proyecto.json (se escribe con el resto).
     */
    static void actualizarSpecEInforme(EscritorSeguro esc, ResultadoVerificacion rv, StringBuilder informe, String titulo,
                                       ProyectoEscaneado proyecto) {
        actualizarSpecEInforme(esc, rv, informe, titulo, proyecto, null);
    }

    /**
     * Como {@link #actualizarSpecEInforme(EscritorSeguro, ResultadoVerificacion, StringBuilder, String, ProyectoEscaneado)};
     * solo los casos de {@code contarIntentos} suman un intento de montaje si fallan (null = todos).
     */
    static void actualizarSpecEInforme(EscritorSeguro esc, ResultadoVerificacion rv, StringBuilder informe, String titulo,
                                       ProyectoEscaneado proyecto, Collection<String> contarIntentos) {
        Spec spec = null;
        try {
            spec = AlmacenSpec.leer(esc.salida());
        } catch (IllegalStateException e) {
            rv.avisos.add(e.getMessage());
        }
        Map.Entry<Path, String> perfil = RecetasAprendidas.registrar(esc, proyecto, spec, rv.resultados);
        List<String> montaje = new ArrayList<>();
        List<String> fallidos = new ArrayList<>();
        List<String> aserciones = new ArrayList<>();
        List<String> revisionIa = new ArrayList<>();
        List<String> ajustarAutorizados = new ArrayList<>();
        int maxIntento = 0;
        List<String> corregidos = new ArrayList<>();
        boolean corregidosPorIa = true;
        Map<String, ResultadoTest> apartar = new LinkedHashMap<>();
        for (ResultadoTest t : rv.resultados) {
            AlmacenSpec.Ubicacion u = spec == null ? null : AlmacenSpec.buscar(spec, t.caso);
            CasoSpec c = u == null ? null : u.caso();
            switch (t.estado) {
                case ClasificadorFallos.PASA -> {
                    if (c != null && !"pasa".equals(c.estado) && c.intentosMontaje != null && c.intentosMontaje > 0) {
                        corregidos.add(t.caso);
                        corregidosPorIa &= VEREDICTO_ERROR_IA.equals(c.veredictoIa);
                    }
                    if (c != null) {
                        c.estado = "pasa";
                        c.autorizacionCambio = null;
                    }
                }
                case ClasificadorFallos.FALLO_ASERCION -> {
                    if (c == null) {
                        aserciones.add(t.caso);
                    } else if (ESTADO_POSIBLE_BUG.equals(c.estado) || ESTADO_DUDOSO.equals(c.estado)) {
                        // Decisión ya tomada (bug o veredicto de la IA): se conserva y no queda pendiente.
                    } else if (CasoSpec.AUTORIZACION_AJUSTAR.equals(c.autorizacionCambio)) {
                        // El usuario ya eligió «Ajustar lo esperado» y aún no se ha reemplazado: no se vuelve a preguntar.
                        c.estado = ClasificadorFallos.FALLO_ASERCION;
                        ajustarAutorizados.add(t.caso);
                    } else if (VEREDICTO_ERROR_IA.equals(c.veredictoIa)) {
                        c.estado = ClasificadorFallos.FALLO_ASERCION;
                        revisionIa.add(t.caso);
                    } else {
                        c.estado = ClasificadorFallos.FALLO_ASERCION;
                        aserciones.add(t.caso);
                    }
                }
                case ClasificadorFallos.ERROR_MONTAJE, ClasificadorFallos.NO_COMPILA -> {
                    if (c != null && c.protegido()) {
                        // No se ha reemplazado (sigue protegido): conserva estado e intentos y sigue pendiente de decisión.
                        if (ClasificadorFallos.FALLO_ASERCION.equals(c.estado) && c.autorizacionCambio == null
                                && !VEREDICTO_ERROR_IA.equals(c.veredictoIa)) {
                            aserciones.add(t.caso);
                        }
                        continue;
                    }
                    boolean cuenta = contarIntentos == null || contarIntentos.contains(t.caso);
                    int intentos = 1;
                    if (c != null) {
                        intentos = (c.intentosMontaje == null ? 0 : c.intentosMontaje) + (cuenta ? 1 : 0);
                        c.intentosMontaje = intentos;
                        c.estado = intentos >= MAX_INTENTOS ? "montaje_fallido" : "generado";
                    }
                    if (intentos >= MAX_INTENTOS) {
                        fallidos.add(t.caso);
                        apartar.put(t.caso, t);
                    } else {
                        montaje.add(t.caso);
                        maxIntento = Math.max(maxIntento, intentos);
                    }
                }
                default -> {
                }
            }
        }
        rv.intentoMontaje = maxIntento;
        rv.asercionesPendientes.addAll(aserciones);
        rv.montajeReintentables.addAll(montaje);
        rv.revisionIaPendiente.addAll(revisionIa);
        boolean auto = CargadorConfig.modoFallosAsercion(esc.proyecto()) == ConfigProyecto.ModoFallosAsercion.REVISAR_IA;
        rv.accionRequerida = textoAcciones(montaje, maxIntento, fallidos, revisionIa, ajustarAutorizados, aserciones,
                rv.contar(ClasificadorFallos.DESCONOCIDO) > 0, esc.temporal(), rv.todoPasa(), auto);
        Map<Path, String> plan = new LinkedHashMap<>();
        if (spec != null) {
            plan.put(AlmacenSpec.ruta(esc.salida()), AlmacenSpec.serializar(spec));
        }
        if (perfil != null) {
            plan.put(perfil.getKey(), perfil.getValue());
        }
        List<String> comentados = comentarApartados(esc, apartar, plan);
        if (!comentados.isEmpty()) {
            String aviso = comentados.size() + " caso(s) apartados automáticamente tras " + MAX_INTENTOS + " intentos ("
                    + LimitesRespuesta.listaCorta(comentados, 10) + "): quedan comentados en su test, no se han borrado.";
            if (!rv.avisos.contains(aviso)) {
                rv.avisos.add(aviso);
            }
        }
        Path rutaInforme = esc.salida().resolve(RutasSalida.INFORME);
        List<String> rutas = new ArrayList<>();
        plan.keySet().forEach(p -> rutas.add("`" + p + "`"));
        rutas.add("`" + rutaInforme + "`");
        rutas.add("temporales en `" + esc.temporal() + "`");
        String cuerpo = InformeVerificacion.cuerpoVerificacion(new InformeVerificacion.DatosVerificacion(rv, spec, montaje, fallidos,
                revisionIa, ajustarAutorizados, aserciones, maxIntento, corregidos, !corregidos.isEmpty() && corregidosPorIa,
                informe.toString(), String.join(", ", rutas), auto));
        plan.put(rutaInforme, EscritorInforme.anadir(esc.salida(), esc.aviso(), EscritorInforme.seccionTexto(titulo, cuerpo)));
        esc.escribirTodo(plan);
        rv.informe = rutaInforme.toString();
    }

    /** Texto de {@code accionRequerida} de una verificación según la clasificación de sus casos (modo «preguntar»). */
    public static String textoAcciones(List<String> montaje, int maxIntento, List<String> fallidos, List<String> revisionIa,
                                       List<String> ajustarAutorizados, List<String> aserciones, boolean hayDesconocidos, Path temporal,
                                       boolean todoPasa) {
        return textoAcciones(montaje, maxIntento, fallidos, revisionIa, ajustarAutorizados, aserciones, hayDesconocidos, temporal,
                todoPasa, false);
    }

    /**
     * Texto de {@code accionRequerida} de una verificación según la clasificación de sus casos; con
     * {@code revisionAutomatica} los fallos de aserción los revisa la IA con el código, sin preguntar al usuario.
     */
    public static String textoAcciones(List<String> montaje, int maxIntento, List<String> fallidos, List<String> revisionIa,
                                       List<String> ajustarAutorizados, List<String> aserciones, boolean hayDesconocidos, Path temporal,
                                       boolean todoPasa, boolean revisionAutomatica) {
        List<String> acciones = new ArrayList<>();
        if (!montaje.isEmpty()) {
            acciones.add("Errores de montaje o compilación en " + montaje + ": corrige el spec (nunca el .java), llama a generar_tests con "
                    + "reemplazarCasos=[esos ids] y después a ejecutar_tests (intento " + maxIntento + " de 3).");
        }
        if (!fallidos.isEmpty()) {
            acciones.add("Los casos " + fallidos + " quedaron apartados (montaje_fallido: comentados en su test y anotados en informe.md): "
                    + "no los corrijas más ni preguntes al usuario.");
        }
        if (!revisionIa.isEmpty()) {
            acciones.add("Los casos " + revisionIa + " corregidos tras la revisión de la IA (veredicto error_ia) vuelven a fallar por aserción: "
                    + "repite el análisis y regístralo con registrar_analisis_ia; no preguntes al usuario.");
        }
        if (!ajustarAutorizados.isEmpty()) {
            acciones.add("Los casos " + ajustarAutorizados + " tienen autorizado «Ajustar lo esperado»: corrige el esperado en el spec y "
                    + "llama a generar_tests con reemplazarCasos=" + ajustarAutorizados + ".");
        }
        if (!aserciones.isEmpty() && revisionAutomatica) {
            acciones.add("Fallos de aserción en " + aserciones + ": no preguntes al usuario; revísalos tú (estado_verificacion → "
                    + "detalle_servicio con metodos → registrar_analisis_ia con veredicto y motivo basado en el código). No los "
                    + "incluyas en reemplazarCasos antes de registrar error_ia: junit-gen lo rechaza.");
        } else if (!aserciones.isEmpty()) {
            acciones.add("Fallos de aserción en " + aserciones + ": puede ser un bug real; no ajustes el esperado ni decidas por el usuario; "
                    + "no los incluyas en reemplazarCasos: junit-gen lo rechaza.");
        }
        if (hayDesconocidos) {
            acciones.add("Hay resultados desconocidos: revisa los avisos y el log en " + temporal + ".");
        }
        if (acciones.isEmpty() && todoPasa) {
            acciones.add("Todos los tests pasan. Revísalos y cópialos manualmente a src/test/java del proyecto cuando te convenzan.");
        }
        return String.join(" ", acciones);
    }

    /**
     * resolver_fallo: bug → posible_bug; ajustar (con confirmación) → autoriza un único reemplazo
     * (autorizacionCambio=ajustar) e instrucción; eliminar (con confirmación) → quita el bloque.
     */
    public static Map<String, Object> resolverFallo(EscritorSeguro esc, String caso, String decision, boolean confirmado) {
        Map<String, Object> r = new LinkedHashMap<>();
        if (caso == null || !Textos.ID_CASO.matcher(caso).matches()) {
            throw new IllegalArgumentException("Id de caso no válido: " + caso);
        }
        Spec spec = AlmacenSpec.leer(esc.salida());
        if (spec == null) {
            throw new IllegalArgumentException("No hay spec.json en " + esc.salida());
        }
        AlmacenSpec.Ubicacion u = AlmacenSpec.buscar(spec, caso);
        if (u == null) {
            throw new IllegalArgumentException("El caso " + caso + " no está en spec.json.");
        }
        Map<Path, String> plan = new LinkedHashMap<>();
        String texto;
        switch (decision) {
            case "bug" -> {
                u.caso().estado = ESTADO_POSIBLE_BUG;
                u.caso().autorizacionCambio = null;
                texto = InformeVerificacion.textoDecision(decision, caso, null);
                r.put("estado", "posible_bug");
                r.put("instruccion", "El test se mantiene y queda marcado como posible bug en informe.md. No modifiques el proyecto.");
            }
            case "ajustar" -> {
                if (!confirmado) {
                    throw new IllegalArgumentException("Ajustar lo esperado exige la decisión explícita del usuario "
                            + "(confirmadoPorUsuario=true / --confirmar), solo tras su respuesta.");
                }
                u.caso().autorizacionCambio = CasoSpec.AUTORIZACION_AJUSTAR;
                texto = InformeVerificacion.textoDecision(decision, caso, null);
                r.put("estado", u.caso().estado);
                r.put("instruccion", "Corrige 'esperado' (y las aserciones) del caso " + caso + " en el spec y llama a generar_tests con "
                        + "reemplazarCasos=[\"" + caso + "\"]; después ejecuta los tests de nuevo.");
            }
            case "eliminar" -> {
                if (!confirmado) {
                    throw new IllegalArgumentException("Eliminar un caso exige confirmación explícita del usuario (confirmadoPorUsuario=true / --confirmar).");
                }
                Path archivo = buscarArchivoConCaso(esc.salida(), caso);
                if (archivo != null) {
                    String original;
                    try {
                        original = Files.readString(archivo, StandardCharsets.UTF_8);
                    } catch (IOException e) {
                        throw new IllegalStateException("No se puede leer " + archivo + ": " + e.getMessage(), e);
                    }
                    String hash = FusionadorArchivo.hashMontaje(original);
                    FusionadorArchivo.Resultado fr = FusionadorArchivo.fusionar(original, hash, List.of(), List.of(),
                            java.util.Set.of(), java.util.Set.of(), java.util.Set.of(), java.util.Set.of(caso));
                    if (fr.motivoNoTocado != null) {
                        throw new IllegalStateException("No se puede eliminar el caso de " + archivo + ": " + fr.motivoNoTocado);
                    }
                    if (fr.modificado) {
                        plan.put(archivo, fr.texto);
                    }
                    r.put("archivo", archivo.toString());
                }
                u.caso().estado = "eliminado";
                texto = InformeVerificacion.textoDecision(decision, caso,
                        archivo != null ? TextosInforme.rutaRelativa(esc.salida().toString(), archivo.toString()) : null);
                r.put("estado", "eliminado");
            }
            default -> throw new IllegalArgumentException("Decisión no válida: " + decision + " (bug | ajustar | eliminar).");
        }
        plan.put(AlmacenSpec.ruta(esc.salida()), AlmacenSpec.serializar(spec));
        Path rutaInforme = esc.salida().resolve(RutasSalida.INFORME);
        List<String> rutas = new ArrayList<>();
        plan.keySet().forEach(p -> rutas.add("`" + p + "`"));
        rutas.add("`" + rutaInforme + "`");
        plan.put(rutaInforme, EscritorInforme.anadir(esc.salida(), esc.aviso(),
                EscritorInforme.seccionTexto("Decisión sobre un test que falla", texto +"\n\nArchivos actualizados: " + String.join(", ", rutas) + "\n")));
        esc.escribirTodo(plan);
        r.put("caso", caso);
        r.put("decision", decision);
        r.put("informe", rutaInforme.toString());
        return r;
    }

    /**
     * registrar_analisis_ia: valida todo antes de escribir (ids, veredicto cerrado, motivo no vacío,
     * máximo {@link #MAX_ANALISIS} entradas sin repetir, solo casos en fallo_asercion) y aplica:
     * bug_servicio → posible_bug; dudoso → dudoso; error_ia → sigue en fallo_asercion y entra en
     * {@code corregir} (intentosMontaje+1, autorizacionCambio=error_ia: un único reemplazo) salvo que ya tenga
 * {@link #MAX_INTENTOS} intentos (→ dudoso).
     * Escribe spec.json y la sección «Revisión de la IA» de informe.md en una sola escritura.
     */
    public static Map<String, Object> registrarAnalisisIa(EscritorSeguro esc, List<AnalisisIa> lista) {
        if (lista == null || lista.isEmpty()) {
            throw new IllegalArgumentException("'analisis' no puede estar vacío: indica al menos un caso con su veredicto y motivo.");
        }
        if (lista.size() > MAX_ANALISIS) {
            throw new IllegalArgumentException("'analisis' admite como máximo " + MAX_ANALISIS + " entradas por llamada.");
        }
        Spec spec = AlmacenSpec.leer(esc.salida());
        if (spec == null) {
            throw new IllegalArgumentException("No hay spec.json en " + esc.salida());
        }
        Set<String> vistos = new HashSet<>();
        List<AnalisisIa> validados = new ArrayList<>();
        List<CasoSpec> casos = new ArrayList<>();
        for (AnalisisIa a : lista) {
            if (a == null || a.caso() == null || !Textos.ID_CASO.matcher(a.caso()).matches()) {
                throw new IllegalArgumentException("Id de caso no válido: " + (a == null ? null : a.caso()));
            }
            if (!vistos.add(a.caso())) {
                throw new IllegalArgumentException("El caso " + a.caso() + " está repetido en 'analisis'.");
            }
            if (a.veredicto() == null || !VEREDICTOS.contains(a.veredicto())) {
                throw new IllegalArgumentException("Veredicto no válido para " + a.caso() + ": " + a.veredicto() + " (" + String.join(" | ", VEREDICTOS) + ").");
            }
            String motivo = a.motivo() == null ? "" : a.motivo().strip();
            if (motivo.isEmpty()) {
                throw new IllegalArgumentException("Falta el motivo del veredicto del caso " + a.caso() + ".");
            }
            motivo = EscritorInforme.celda(Textos.recortar(motivo, MAX_MOTIVO));
            AlmacenSpec.Ubicacion u = AlmacenSpec.buscar(spec, a.caso());
            if (u == null) {
                throw new IllegalArgumentException("El caso " + a.caso() + " no está en spec.json.");
            }
            if (!ClasificadorFallos.FALLO_ASERCION.equals(u.caso().estado)) {
                throw new IllegalArgumentException("El caso " + a.caso() + " no tiene un fallo de aserción pendiente (estado: "
                        + u.caso().estado + "): solo se registran análisis de casos en fallo_asercion.");
            }
            validados.add(new AnalisisIa(a.caso(), a.veredicto(), motivo));
            casos.add(u.caso());
        }
        List<Map<String, Object>> resultados = new ArrayList<>();
        List<String> corregir = new ArrayList<>();
        List<InformeVerificacion.FilaRevision> filas = new ArrayList<>();
        for (int i = 0; i < validados.size(); i++) {
            AnalisisIa a = validados.get(i);
            CasoSpec c = casos.get(i);
            int intentos = c.intentosMontaje == null ? 0 : c.intentosMontaje;
            c.veredictoIa = a.veredicto();
            c.motivoIa = a.motivo();
            c.autorizacionCambio = null;
            String accion;
            String queSeHace;
            switch (a.veredicto()) {
                case VEREDICTO_BUG -> {
                    c.estado = ESTADO_POSIBLE_BUG;
                    accion = "Se mantiene el test: posible bug del servicio (posible_bug). Informa al usuario; no modifiques el proyecto.";
                    queSeHace = InformeVerificacion.queSeHaceBug();
                }
                case VEREDICTO_DUDOSO -> {
                    c.estado = ESTADO_DUDOSO;
                    accion = "Se mantiene el test como dudoso. Informa al usuario.";
                    queSeHace = InformeVerificacion.queSeHaceDudoso();
                }
                default -> {
                    if (intentos >= MAX_INTENTOS) {
                        c.estado = ESTADO_DUDOSO;
                        accion = "Límite de " + MAX_INTENTOS + " intentos alcanzado: se mantiene el test como dudoso. Informa al usuario.";
                        queSeHace = InformeVerificacion.queSeHaceLimite();
                    } else {
                        intentos++;
                        c.intentosMontaje = intentos;
                        c.autorizacionCambio = CasoSpec.AUTORIZACION_ERROR_IA;
                        corregir.add(a.caso());
                        accion = "Error de la IA: corrige el spec del caso y regenera (intento " + intentos + " de " + MAX_INTENTOS + ").";
                        queSeHace = InformeVerificacion.queSeHaceCorregir(intentos);
                    }
                }
            }
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("caso", a.caso());
            r.put("veredicto", a.veredicto());
            r.put("estado", c.estado);
            r.put("accion", accion);
            r.put("intento", intentos);
            resultados.add(r);
            filas.add(new InformeVerificacion.FilaRevision(a.caso(), a.veredicto(), a.motivo(), queSeHace));
        }
        Map<Path, String> plan = new LinkedHashMap<>();
        plan.put(AlmacenSpec.ruta(esc.salida()), AlmacenSpec.serializar(spec));
        Path rutaInforme = esc.salida().resolve(RutasSalida.INFORME);
        String archivosActualizados = "`" + AlmacenSpec.ruta(esc.salida()) + "`, `" + rutaInforme + "`";
        plan.put(rutaInforme, EscritorInforme.anadir(esc.salida(), esc.aviso(),
                EscritorInforme.seccionTexto("Revisión de la IA", InformeVerificacion.cuerpoRevisionIa(filas, archivosActualizados))));
        esc.escribirTodo(plan);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("resultados", resultados);
        r.put("corregir", corregir);
        r.put("instruccion", corregir.isEmpty()
                ? "No hay casos que corregir. Tu mensaje final es EXACTAMENTE respuestaFinal, tal cual; no muestres la tabla «Revisión de la IA» (queda en informe.md)."
                : "Para " + corregir + ": corrige el spec (esperado y aserciones; nunca el .java ni el proyecto), llama a generar_tests con "
                + "reemplazarCasos=" + corregir + " y después a ejecutar_tests con casos=" + corregir + ". No preguntes al usuario; "
                + "al final muestra EXACTAMENTE respuestaFinal, tal cual; no muestres la tabla «Revisión de la IA» (queda en informe.md).");
        r.put("informe", rutaInforme.toString());
        return r;
    }

    /** Título de la sección de informe.md de los casos apartados automáticamente. */
    static final String TITULO_APARTADOS = "Tests apartados automáticamente";
    private static final int MAX_DETALLE_APARTADO = 300;

    /**
     * Comenta en su archivo de test (sin borrar nada) el bloque de cada caso de {@code casos} cuyo fallo es
     * propio (resultado no null y sin {@code errorComun}); usa el texto ya planificado o el del disco y deja
     * el nuevo en {@code plan}. Devuelve los ids comentados.
     */
    private static List<String> comentarApartados(EscritorSeguro esc, Map<String, ResultadoTest> casos, Map<Path, String> plan) {
        return comentarApartados(esc, casos, plan, new LinkedHashMap<>());
    }

    private static List<String> comentarApartados(EscritorSeguro esc, Map<String, ResultadoTest> casos, Map<Path, String> plan,
                                                  Map<String, Path> archivos) {
        List<String> comentados = new ArrayList<>();
        for (Map.Entry<String, ResultadoTest> e : casos.entrySet()) {
            String id = e.getKey();
            ResultadoTest t = e.getValue();
            if (id == null || t == null || t.errorComun) {
                continue;
            }
            try {
                Path archivo = buscarArchivoConCaso(esc.salida(), id);
                if (archivo == null) {
                    continue;
                }
                String texto = plan.get(archivo);
                if (texto == null) {
                    texto = Files.readString(archivo, StandardCharsets.UTF_8);
                }
                String motivo = ClasificadorFallos.ERROR_MONTAJE.equals(t.estado) ? ClasificadorFallos.ERROR_MONTAJE
                        : ClasificadorFallos.NO_COMPILA;
                String nuevo = FusionadorArchivo.apartar(texto, id, motivo);
                if (!nuevo.equals(texto)) {
                    plan.put(archivo, nuevo);
                    comentados.add(id);
                    archivos.put(id, archivo);
                }
            } catch (IOException | RuntimeException ex) {
                System.err.println("junit-gen: no se pudo apartar el caso " + id + ": " + ex.getMessage());
            }
        }
        return comentados;
    }

    /**
     * Aparta casos que no compilan o no montan y que la IA no ha conseguido arreglar (se llama con el
     * bloqueo de escritura): en spec.json quedan {@code montaje_fallido} con intentosMontaje ≥
     * {@link #MAX_INTENTOS} (los protegidos no se tocan); su bloque queda comentado en el test si el fallo es
     * del propio caso (nunca se borra) y se anota la sección «Tests apartados automáticamente» en informe.md.
     * Devuelve {@code {apartados, comentados, informe}}.
     */
    public static Map<String, Object> apartarCasos(EscritorSeguro esc, Map<String, ResultadoTest> casos) {
        Map<String, Object> r = new LinkedHashMap<>();
        Spec spec = AlmacenSpec.leer(esc.salida());
        List<String> apartados = new ArrayList<>();
        Map<String, ResultadoTest> aComentar = new LinkedHashMap<>();
        for (Map.Entry<String, ResultadoTest> e : casos.entrySet()) {
            String id = e.getKey();
            if (id == null || !Textos.ID_CASO.matcher(id).matches()) {
                continue;
            }
            AlmacenSpec.Ubicacion u = spec == null ? null : AlmacenSpec.buscar(spec, id);
            CasoSpec c = u == null ? null : u.caso();
            if (c != null && c.protegido()) {
                continue;
            }
            if (c != null) {
                c.estado = "montaje_fallido";
                c.intentosMontaje = Math.max(c.intentosMontaje == null ? 0 : c.intentosMontaje, MAX_INTENTOS);
            }
            apartados.add(id);
            aComentar.put(id, e.getValue());
        }
        Path rutaInforme = esc.salida().resolve(RutasSalida.INFORME);
        List<String> comentados = new ArrayList<>();
        if (!apartados.isEmpty()) {
            Map<Path, String> plan = new LinkedHashMap<>();
            if (spec != null) {
                plan.put(AlmacenSpec.ruta(esc.salida()), AlmacenSpec.serializar(spec));
            }
            Map<String, Path> archivos = new LinkedHashMap<>();
            comentados.addAll(comentarApartados(esc, aComentar, plan, archivos));
            StringBuilder cuerpo = new StringBuilder();
            cuerpo.append("En resumen: ").append(apartados.size()).append(apartados.size() == 1
                    ? " test no se ha podido arreglar y se ha apartado para no frenar el resto."
                    : " tests no se han podido arreglar y se han apartado para no frenar el resto.").append("\n\n");
            for (String id : apartados) {
                ResultadoTest t = aComentar.get(id);
                String metodo = t == null || t.metodoTest == null || t.metodoTest.isBlank() ? null : t.metodoTest;
                String detalle = t == null ? "" : TextosInforme.codigo(t.mensaje, MAX_DETALLE_APARTADO);
                cuerpo.append("- ").append(metodo == null ? "" : TextosInforme.codigo(metodo, 200) + " ")
                        .append("(caso ").append(TextosInforme.codigo(id, 100)).append("): ")
                        .append(detalle.isEmpty() ? TextosInforme.SIN_DATO : detalle).append(". ");
                Path archivo = archivos.get(id);
                if (archivo != null) {
                    cuerpo.append("Queda comentado dentro de `")
                            .append(TextosInforme.rutaRelativa(esc.salida().toString(), archivo.toString()).replace('`', '\''))
                            .append("` (no se ha borrado).");
                } else if (t != null && t.errorComun) {
                    cuerpo.append("Sigue sin comentar: el error está en la parte común de la clase y el archivo no compila hasta corregirla.");
                } else {
                    cuerpo.append("Sigue sin comentar: no se ha encontrado su bloque en los tests generados.");
                }
                cuerpo.append('\n');
            }
            cuerpo.append("\nPara recuperarlos: corrige el caso y vuelve a generarlo, o quita a mano las marcas `//` del bloque.\n");
            List<String> rutas = new ArrayList<>();
            plan.keySet().forEach(p -> rutas.add("`" + p + "`"));
            rutas.add("`" + rutaInforme + "`");
            cuerpo.append("\nArchivos actualizados: ").append(String.join(", ", rutas)).append('\n');
            plan.put(rutaInforme, EscritorInforme.anadir(esc.salida(), esc.aviso(),
                    EscritorInforme.seccionTexto(TITULO_APARTADOS, cuerpo.toString())));
            esc.escribirTodo(plan);
        }
        r.put("apartados", apartados);
        r.put("comentados", comentados);
        r.put("informe", rutaInforme.toString());
        return r;
    }

    /** Fila de la sección «Arreglos al final» de informe.md (un caso apuntado durante la tanda). */
    public record ArregloInforme(String servicio, String metodoTest, String caso, String tipo, String error, List<String> cambios,
                                 int intentos, String estado, String motivo, boolean errorComun, boolean automatico,
                                 boolean menosEstricto, List<String> avisosCalidad) {

        /** Fila sin corrección automática ni avisos de calidad. */
        public ArregloInforme(String servicio, String metodoTest, String caso, String tipo, String error, List<String> cambios,
                              int intentos, String estado, String motivo, boolean errorComun) {
            this(servicio, metodoTest, caso, tipo, error, cambios, intentos, estado, motivo, errorComun, false, false, List.of());
        }
    }

    /** Título de la sección de informe.md con los casos arreglados al final de la tanda. */
    public static final String TITULO_ARREGLOS = "Arreglos al final";

    /**
     * Añade a informe.md la sección «Arreglos al final» (se llama con el bloqueo de escritura). Lista vacía → no
     * escribe. Devuelve la ruta del informe.
     */
    public static String anotarArreglos(EscritorSeguro esc, List<ArregloInforme> filas) {
        return anotarArreglos(esc, filas, List.of());
    }

    /**
     * Como {@link #anotarArreglos(EscritorSeguro, List)}, con los imports corregidos automáticamente durante la
     * tanda; solo deja de escribir si ambas listas están vacías.
     */
    public static String anotarArreglos(EscritorSeguro esc, List<ArregloInforme> filas, List<String> importsReparados) {
        Path rutaInforme = esc.salida().resolve(RutasSalida.INFORME);
        List<ArregloInforme> f = filas == null ? List.of() : filas;
        List<String> imports = importsReparados == null ? List.of() : importsReparados;
        if (f.isEmpty() && imports.isEmpty()) {
            return rutaInforme.toString();
        }
        String cuerpo = InformeVerificacion.cuerpoArreglos(f, imports)
                + "\nArchivos actualizados: `" + rutaInforme + "`\n";
        Map<Path, String> plan = new LinkedHashMap<>();
        plan.put(rutaInforme, EscritorInforme.anadir(esc.salida(), esc.aviso(), EscritorInforme.seccionTexto(TITULO_ARREGLOS, cuerpo)));
        esc.escribirTodo(plan);
        return rutaInforme.toString();
    }

    /** Archivo .java de la salida que contiene el caso (solo lectura), o null si no existe. */
    public static Path archivoDeCaso(Path salida, String caso) {
        if (salida == null || caso == null || !Files.isDirectory(salida)) {
            return null;
        }
        return buscarArchivoConCaso(salida, caso);
    }

    private static Path buscarArchivoConCaso(Path salida, String caso) {
        Path[] encontrado = {null};
        Pattern p = Pattern.compile("junit-gen:caso-inicio\\s+id=" + Pattern.quote(caso) + "(\\s|$)", Pattern.MULTILINE);
        try {
            Files.walkFileTree(salida, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes attrs) {
                    if (d.getFileName() != null && d.getFileName().toString().equals(EscritorSeguro.CARPETA_TMP)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return !d.equals(salida) && RutaSegura.esEnlace(d) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path f, BasicFileAttributes attrs) throws IOException {
                    if (f.toString().endsWith(".java") && p.matcher(Files.readString(f, StandardCharsets.UTF_8)).find()) {
                        encontrado[0] = f;
                        return FileVisitResult.TERMINATE;
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new IllegalStateException("No se pudo buscar el caso en " + salida + ": " + e.getMessage(), e);
        }
        return encontrado[0];
    }
}
