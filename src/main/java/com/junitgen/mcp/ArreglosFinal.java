package com.junitgen.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.junitgen.escaneo.PaqueteTrabajo;
import com.junitgen.generacion.AlmacenSpec;
import com.junitgen.generacion.Tanda;
import com.junitgen.spec.CasoSpec;
import com.junitgen.spec.ServicioSpec;
import com.junitgen.spec.Spec;
import com.junitgen.util.Json;
import com.junitgen.util.LimitesRespuesta;
import com.junitgen.util.Textos;
import com.junitgen.verificacion.ClasificadorFallos;
import com.junitgen.verificacion.ResultadoTest;
import com.junitgen.verificacion.ResultadoVerificacion;
import com.junitgen.verificacion.VerificadorTests;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fase de arreglo de la tanda ({@code arreglos: alFinal}): durante la generación los casos que fallan se apuntan en
 * {@code tanda.json} sin frenar nada; con el plan completo junit-gen aplica primero los arreglos mecánicos
 * ({@link AutoArreglos}) y después entrega los casos agrupados por servicio (error, pistas, spec y código de sus métodos)
 * para corregirlos en una llamada a {@code arreglar_caso}. Solo orquesta: la generación, la verificación, el apartado y
 * los veredictos son los existentes. Nunca sujeta monitores durante la E/S.
 */
final class ArreglosFinal {

    /** Tamaños máximos (bytes UTF-8) del error, la pista y el motivo guardados. */
    static final int MAX_ERROR = 300;
    static final int MAX_PISTA = 500;
    static final int MAX_MOTIVO = 300;
    /** Cambios guardados por caso para el informe. */
    static final int MAX_CAMBIOS = 8;
    /** Entregas o rechazos seguidos del mismo caso sin corrección aplicada antes de terminarlo solo. */
    static final int MAX_SIN_CORREGIR = 4;
    /** Presupuesto mínimo (bytes) del código del método en la carga. */
    static final int MIN_CODIGO = 1500;
    /** Margen (bytes) que se deja al calcular el presupuesto del código. */
    static final int MARGEN_CARGA = 200;
    /** Claves del paquete de trabajo que se conservan en {@code codigo}. */
    static final List<String> CLAVES_CODIGO = List.of("metodos", "metodosInternos", "dependencias", "stubsTipicos", "tiposAuxiliares",
            "mocks", "helpersExternos");
    static final List<String> ORDEN_RECORTE = List.of("codigo.stubsTipicos", "codigo.dependencias", "codigo.tiposAuxiliares",
            "codigo.helpersExternos", "codigo.metodosInternos");
    /** Casos de un mismo servicio entregados a la vez. */
    static final int MAX_GRUPO = 6;
    /** Bytes reservados para el código al llenar un grupo. */
    static final int RESERVA_CODIGO = 2200;
    /** Margen (bytes) sobre la cabecera de la respuesta al calcular el presupuesto de la carga. */
    static final int MARGEN_CABECERA = 100;
    /** Rondas de corrección automática (sin IA) por caso. */
    static final int MAX_AUTO_INTENTOS = 2;
    /** Tamaño máximo (bytes UTF-8) del origen de la excepción guardado. */
    static final int MAX_ORIGEN = 300;
    /** Avisos de calidad guardados por caso. */
    static final int MAX_AVISOS_CALIDAD = 4;
    /** Longitud mínima (caracteres) de un motivo que justifica un test menos estricto. */
    static final int MIN_JUSTIFICACION = 60;
    /** Identificadores reales del código que debe citar ese motivo. */
    static final int MIN_IDENTIFICADORES = 2;
    /** Frases de las correcciones automáticas mostradas por caso en la carga. */
    static final int MAX_AUTO_CARGA = 2;
    /** Textos del spec que no se entregan en la fase de arreglo (se conserva queComprueba). */
    static final List<String> CAMPOS_TEXTO_SPEC = List.of("descripcion", "queHaceOriginal", "escenario");
    static final String REGLAS_ARREGLO = "`codigo` y `pistas` traen lo necesario: no llames a detalle_servicio. No rebajes el test (no "
            + "cambies igual por contiene/noNulo, no quites comprobaciones ni cambies el escenario): corrige la preparación (datos, "
            + "preparacion, stubs, estaticos) para que se cumpla lo que comprobaba. `motivo` citando el código (método, campo o "
            + "condición) es obligatorio en fallos de aserción y si algo queda menos estricto. No escribas al usuario.";
    static final String PREFIJO_AUTO = "Corrección automática de junit-gen: ";
    /** Identificador de Java (3 caracteres o más) citado en un motivo. */
    private static final Pattern IDENTIFICADOR = Pattern.compile("[A-Za-z_$][\\w$]{2,}");
    /** Palabras que no cuentan como identificadores del código. */
    private static final Set<String> NO_IDENTIFICADORES = Set.of("abstract", "assert", "boolean", "break", "byte", "case", "catch",
            "char", "class", "const", "continue", "default", "double", "else", "enum", "extends", "final", "finally", "float", "for",
            "goto", "implements", "import", "instanceof", "int", "interface", "long", "native", "package", "private", "protected",
            "public", "short", "static", "strictfp", "super", "switch", "synchronized", "throw", "throws", "transient", "try",
            "void", "volatile", "while", "var", "record", "yield", "String", "Integer", "Long", "List", "Map", "Set", "Optional",
            "Object", "null", "true", "false", "this", "new", "return");
    private static final List<String> CLAVES_IDENTIFICADORES = List.of("metodos", "metodosInternos", "helpersExternos");
    private static final List<String> CAMPOS_IDENTIFICADORES = List.of("cuerpo", "firma", "metodo", "clave");
    static final String COMO_VER_CODIGO = "detalle_servicio {clase, metodos: [método]} devuelve el código completo.";
    static final String MOTIVO_SIN_CORRECCION = "No se recibió ninguna corrección válida para este caso.";
    static final String MOTIVO_LIMITE = "No se pudo arreglar en 3 intentos.";
    /** Correcciones aplicadas con error común tras las que, si la clase sigue sin compilar, se aparta el servicio entero. */
    static final int MAX_INTENTOS_COMUN = 2;
    /** Rechazos seguidos de la corrección común tras los que se aparta el servicio entero. */
    static final int MAX_RECHAZOS_COMUN = 2;
    /** Inicio del motivo de los casos apartados porque su clase de test no compila por un error común. */
    static final String MOTIVO_COMUN = "La clase de test no compila por un error común (fuera de los casos): ";
    private static final String SIN_DETALLE = "sin detalle";
    private static final String CLAVE_REVERIFICAR = "reverificar|";

    private ArreglosFinal() {
    }

    /** Presupuesto (bytes) del cuerpo de la carga: lo que deja {@link LimitesRespuesta#maxRespuesta()} tras la cabecera. */
    static int presupuesto() {
        return Math.max(LimitesRespuesta.PRESUPUESTO_CUERPO,
                LimitesRespuesta.maxRespuesta() - LimitesRespuesta.MAX_CABECERA - MARGEN_CABECERA);
    }

    /** true si los fallos se arreglan al final y el plan está terminado (o se autorizó ejecutar lo generado). */
    static boolean enFase(SesionMcp sesion, Path proyecto) {
        if (!GuiaTanda.arreglosAlFinal(sesion, proyecto)) {
            return false;
        }
        SesionMcp.ProgresoPlan pp = sesion.progresoPlan(proyecto);
        return pp != null && (pp.restantes() == 0 || sesion.ejecucionParcialAutorizada());
    }

    /** {total, pendientes, arreglados, posiblesBugs, apartados (apartados + dudosos)} de los casos apuntados (solo memoria). */
    static int[] cuenta(SesionMcp sesion, Path proyecto) {
        int[] r = new int[5];
        for (Tanda.ArregloTanda a : sesion.arreglos(proyecto)) {
            r[0]++;
            String e = a.estado == null ? "" : a.estado;
            switch (e) {
                case Tanda.ARREGLO_PENDIENTE -> r[1]++;
                case Tanda.ARREGLO_ARREGLADO -> r[2]++;
                case Tanda.ARREGLO_POSIBLE_BUG -> r[3]++;
                case Tanda.ARREGLO_APARTADO, Tanda.ARREGLO_DUDOSO -> r[4]++;
                default -> {
                }
            }
        }
        return r;
    }

    // ------------------------------------------------------------- reconciliación

    /**
     * Reconcilia (de forma idempotente) los casos apuntados con spec.json y los estados de sesión del servicio
     * {@code fqn} (null = todos): crea o actualiza los que fallan, marca arreglados los que ya pasan tras corregirlos y
     * recoge las decisiones ya tomadas (apartado, posible bug, dudoso). Spec ilegible = sin información (no cambia nada).
     * Al final termina los pendientes que agotaron sus intentos.
     */
    static void sincronizar(SesionMcp sesion, Path proyecto, Path salida, String fqn) {
        if (proyecto == null || salida == null) {
            return;
        }
        Spec spec;
        try {
            spec = AlmacenSpec.leer(salida);
        } catch (RuntimeException e) {
            return;
        }
        if (spec == null) {
            return;
        }
        VerificacionesSesion ver = sesion.verificaciones();
        Map<String, String> servicios = sesion.serviciosDeCasos(salida);
        Map<String, String> estados = sesion.estadosDeSesion(salida);
        for (Map.Entry<String, String> en : servicios.entrySet()) {
            String id = en.getKey();
            String srv = en.getValue();
            if (id == null || srv == null || (fqn != null && !fqn.equals(srv))) {
                continue;
            }
            String est = estados.get(id);
            if (est == null) {
                continue;
            }
            AlmacenSpec.Ubicacion u = AlmacenSpec.buscar(spec, id);
            CasoSpec c = u == null ? null : u.caso();
            ResultadoTest t = ver.ultimo(salida, id);
            if (t != null && !est.equals(t.estado)) {
                t = null;
            }
            Datos d = new Datos(est, c == null ? null : c.estado, c == null ? null : c.motivoIa, srv,
                    claseTest(u, t), c == null ? null : c.metodo,
                    t == null ? null : t.metodoTest,
                    t == null ? null : LimitesRespuesta.recortar(unaLinea(t.mensaje), MAX_ERROR),
                    t == null ? null : LimitesRespuesta.recortar(unaLinea(t.pista), MAX_PISTA),
                    t != null && t.errorComun, t != null,
                    t == null ? null : LimitesRespuesta.recortar(unaLinea(origen(srv, t)), MAX_ORIGEN));
            sesion.actualizarArreglo(proyecto, id, a -> aplicar(a, d));
        }
        if (!servicios.isEmpty()) {
            for (Tanda.ArregloTanda a : sesion.arreglos(proyecto)) {
                if ((fqn == null || fqn.equals(a.servicio)) && !servicios.containsKey(a.caso)) {
                    sesion.actualizarArreglo(proyecto, a.caso, x -> null);
                }
            }
        }
        for (Tanda.ArregloTanda a : sesion.arreglos(proyecto)) {
            if ((fqn == null || fqn.equals(a.servicio)) && Tanda.ARREGLO_PENDIENTE.equals(a.estado)
                    && a.intentos >= VerificadorTests.MAX_INTENTOS) {
                terminar(sesion, proyecto, salida, a, MOTIVO_LIMITE);
            }
        }
    }

    /** Datos (ya calculados fuera del monitor de la tanda) de un caso para {@link #aplicar}. */
    private record Datos(String est, String estadoSpec, String motivoIa, String servicio, String claseTest, String metodo,
                         String metodoTest, String error, String pista, boolean comun, boolean conResultado, String origen) {
    }

    /** Origen de la excepción en el código del servicio (nunca lanza; null si no hay). */
    private static String origen(String srv, ResultadoTest t) {
        try {
            return ClasificadorFallos.origen(srv, t.tipoError, t.traza, t.causa);
        } catch (RuntimeException e) {
            System.err.println("junit-gen: no se pudo calcular el origen del fallo del caso " + t.caso + ": " + e.getClass().getSimpleName());
            return null;
        }
    }

    /** Mutador puro (solo memoria) del registro de un caso según su último estado. */
    private static Tanda.ArregloTanda aplicar(Tanda.ArregloTanda a, Datos d) {
        String est = d.est();
        if (ClasificadorFallos.PASA.equals(est)) {
            if (a != null && (Tanda.ARREGLO_PENDIENTE.equals(a.estado) || Tanda.ARREGLO_APARTADO.equals(a.estado))) {
                if (a.intentos > 0 || a.autoIntentos > 0) {
                    a.estado = Tanda.ARREGLO_ARREGLADO;
                    a.automatico = a.intentos == 0;
                    return a;
                }
                return null;
            }
            return a;
        }
        if (!ClasificadorFallos.NO_COMPILA.equals(est) && !ClasificadorFallos.ERROR_MONTAJE.equals(est)
                && !ClasificadorFallos.FALLO_ASERCION.equals(est)) {
            return a;
        }
        if (a != null && (Tanda.ARREGLO_POSIBLE_BUG.equals(a.estado) || Tanda.ARREGLO_DUDOSO.equals(a.estado)
                || Tanda.ARREGLO_APARTADO.equals(a.estado))) {
            return a;
        }
        String es = d.estadoSpec();
        boolean decidido = HerramientaEjecutar.ESTADO_SPEC_MONTAJE_FALLIDO.equals(es) || VerificadorTests.ESTADO_POSIBLE_BUG.equals(es)
                || VerificadorTests.ESTADO_DUDOSO.equals(es);
        if (a == null && decidido) {
            return null;
        }
        if (a != null && HerramientaEjecutar.ESTADO_SPEC_MONTAJE_FALLIDO.equals(es)) {
            a.estado = Tanda.ARREGLO_APARTADO;
            if (a.motivo == null || a.motivo.isBlank()) {
                int n = a.intentos > 0 ? a.intentos : VerificadorTests.MAX_INTENTOS;
                a.motivo = "No se pudo arreglar tras " + n + (n == 1 ? " intento" : " intentos") + "; queda comentado en su test.";
            }
            return a;
        }
        if (a != null && VerificadorTests.ESTADO_POSIBLE_BUG.equals(es)) {
            a.estado = Tanda.ARREGLO_POSIBLE_BUG;
            a.motivo = recortarMotivo(d.motivoIa());
            return a;
        }
        if (a != null && VerificadorTests.ESTADO_DUDOSO.equals(es)) {
            a.estado = Tanda.ARREGLO_DUDOSO;
            a.motivo = recortarMotivo(d.motivoIa());
            return a;
        }
        Tanda.ArregloTanda r = a == null ? new Tanda.ArregloTanda() : a;
        r.estado = Tanda.ARREGLO_PENDIENTE;
        r.tipo = est;
        r.servicio = d.servicio();
        if (d.claseTest() != null) {
            r.claseTest = d.claseTest();
        }
        if (d.metodo() != null) {
            r.metodo = d.metodo();
        }
        if (d.conResultado()) {
            if (d.metodoTest() != null) {
                r.metodoTest = d.metodoTest();
            }
            r.error = d.error();
            r.pista = d.pista();
            r.origen = d.origen();
            r.comun = d.comun();
            if (r.errorInicial == null) {
                r.errorInicial = d.error();
            }
        }
        return r;
    }

    private static String claseTest(AlmacenSpec.Ubicacion u, ResultadoTest t) {
        if (u != null && u.servicio() != null && u.servicio().claseTest != null && !u.servicio().claseTest.isBlank()) {
            return u.servicio().claseTest;
        }
        return t == null || t.clase == null ? null : Textos.nombreSimple(t.clase);
    }

    /**
     * Termina un caso que no se pudo arreglar (nunca lanza): un fallo de aserción queda dudoso (veredicto registrado; el
     * test no se toca); el resto se aparta (comentado en su test, nunca borrado) con el mecanismo existente.
     */
    static void terminar(SesionMcp sesion, Path proyecto, Path salida, Tanda.ArregloTanda a, String motivo) {
        if (a == null || a.caso == null) {
            return;
        }
        String m = LimitesRespuesta.recortar(motivo == null ? MOTIVO_LIMITE : motivo, MAX_MOTIVO);
        try {
            Tanda.ArregloTanda vigente = sesion.arreglo(proyecto, a.caso);
            if (vigente == null || !Tanda.ARREGLO_PENDIENTE.equals(vigente.estado)) {
                return;
            }
            if (ClasificadorFallos.FALLO_ASERCION.equals(a.tipo)) {
                dudoso(sesion, proyecto, salida, a, m);
                return;
            }
            if (a.comun || vigente.comun) {
                apartarServicio(sesion, proyecto, salida, a.servicio, a.error != null ? a.error : vigente.error);
                return;
            }
            VerificacionesSesion ver = sesion.verificaciones();
            ResultadoTest t = ver.ultimo(salida, a.caso);
            if (t == null) {
                t = new ResultadoTest();
                t.caso = a.caso;
                t.estado = a.tipo;
                t.mensaje = a.error;
                t.errorComun = a.comun;
            }
            Map<String, ResultadoTest> casos = new LinkedHashMap<>();
            casos.put(a.caso, t);
            try {
                sesion.fachada().apartarCasos(proyecto, salida, casos);
            } catch (RuntimeException e) {
                System.err.println("junit-gen: no se pudo apartar el caso " + a.caso + ": " + e.getClass().getSimpleName() + ": "
                        + e.getMessage());
            }
            ver.quitarReintentables(salida, a.servicio, List.of(a.caso));
            sesion.actualizarArreglo(proyecto, a.caso, x -> {
                if (x == null) {
                    return null;
                }
                x.estado = Tanda.ARREGLO_APARTADO;
                x.motivo = m;
                return x;
            });
        } catch (RuntimeException e) {
            System.err.println("junit-gen: no se pudo terminar el caso " + a.caso + ": " + e.getClass().getSimpleName() + ": "
                    + e.getMessage());
        }
    }

    /** Deja un fallo de aserción como dudoso (veredicto registrado; el test no se toca). Nunca lanza hacia fuera. */
    private static void dudoso(SesionMcp sesion, Path proyecto, Path salida, Tanda.ArregloTanda a, String m) {
        try {
            sesion.fachada().registrarAnalisisIa(salida, List.of(new VerificadorTests.AnalisisIa(a.caso,
                    VerificadorTests.VEREDICTO_DUDOSO, m)));
        } catch (IllegalArgumentException | IllegalStateException e) {
            System.err.println("junit-gen: no se pudo dejar como dudoso el caso " + a.caso + ": " + e.getClass().getSimpleName()
                    + ": " + e.getMessage());
        }
        sesion.actualizarArreglo(proyecto, a.caso, x -> {
            if (x == null) {
                return null;
            }
            x.estado = Tanda.ARREGLO_DUDOSO;
            x.motivo = m;
            return x;
        });
    }

    /**
     * Aparta todos los casos pendientes del servicio {@code fqn} porque su clase de test no compila por un error común
     * (nunca lanza; idempotente): los fallos de aserción quedan dudosos; el resto se aparta (estado en spec.json, sin
     * tocar el .java) con el motivo {@link #MOTIVO_COMUN} + el error técnico recortado.
     */
    static void apartarServicio(SesionMcp sesion, Path proyecto, Path salida, String fqn, String error) {
        if (sesion == null || proyecto == null || salida == null || fqn == null) {
            return;
        }
        try {
            String detalle = error == null ? "" : unaLinea(error);
            if (detalle.startsWith(VerificadorTests.PREFIJO_ERROR_COMUN)) {
                int k = detalle.indexOf("): ");
                detalle = k < 0 ? detalle.substring(VerificadorTests.PREFIJO_ERROR_COMUN.length()) : detalle.substring(k + "): ".length());
            }
            detalle = detalle.trim();
            String motivo = LimitesRespuesta.recortar(MOTIVO_COMUN + (detalle.isEmpty() ? SIN_DETALLE : detalle), MAX_MOTIVO);
            Map<String, ResultadoTest> casos = new LinkedHashMap<>();
            for (Tanda.ArregloTanda a : sesion.arreglosPendientes(proyecto)) {
                if (!fqn.equals(a.servicio)) {
                    continue;
                }
                if (ClasificadorFallos.FALLO_ASERCION.equals(a.tipo)) {
                    dudoso(sesion, proyecto, salida, a, motivo);
                    continue;
                }
                ResultadoTest t = new ResultadoTest();
                t.caso = a.caso;
                t.estado = a.tipo;
                t.mensaje = a.error;
                t.metodoTest = a.metodoTest;
                t.errorComun = true;
                casos.put(a.caso, t);
            }
            if (casos.isEmpty()) {
                return;
            }
            try {
                sesion.fachada().apartarCasos(proyecto, salida, casos);
            } catch (RuntimeException e) {
                System.err.println("junit-gen: no se pudieron apartar los casos de " + fqn + ": " + e.getClass().getSimpleName() + ": "
                        + e.getMessage());
            }
            List<String> ids = new ArrayList<>(casos.keySet());
            sesion.verificaciones().quitarReintentables(salida, fqn, ids);
            for (String id : ids) {
                sesion.actualizarArreglo(proyecto, id, x -> {
                    if (x == null || !Tanda.ARREGLO_PENDIENTE.equals(x.estado)) {
                        return x;
                    }
                    x.estado = Tanda.ARREGLO_APARTADO;
                    x.motivo = motivo;
                    x.comun = true;
                    return x;
                });
            }
        } catch (RuntimeException e) {
            System.err.println("junit-gen: no se pudo apartar el servicio " + fqn + ": " + e.getClass().getSimpleName() + ": "
                    + e.getMessage());
        }
    }

    /**
     * Tras verificar el servicio con correcciones contadas: sin casos pendientes con error común pone a cero sus
     * {@code intentosComun}; si alguno llega a {@link #MAX_INTENTOS_COMUN}, aparta el servicio entero.
     */
    static void comprobarComun(SesionMcp sesion, Path proyecto, Path salida, String fqn, Collection<String> contados) {
        if (contados == null || contados.isEmpty() || fqn == null) {
            return;
        }
        List<Tanda.ArregloTanda> comunes = new ArrayList<>();
        for (Tanda.ArregloTanda a : sesion.arreglosPendientes(proyecto)) {
            if (a.comun && fqn.equals(a.servicio)) {
                comunes.add(a);
            }
        }
        if (comunes.isEmpty()) {
            for (Tanda.ArregloTanda a : sesion.arreglos(proyecto)) {
                if (fqn.equals(a.servicio) && a.intentosComun > 0) {
                    sesion.actualizarArreglo(proyecto, a.caso, x -> {
                        if (x != null) {
                            x.intentosComun = 0;
                        }
                        return x;
                    });
                }
            }
            return;
        }
        int max = 0;
        for (Tanda.ArregloTanda a : comunes) {
            max = Math.max(max, a.intentosComun);
        }
        if (max >= MAX_INTENTOS_COMUN) {
            apartarServicio(sesion, proyecto, salida, fqn, comunes.get(0).error);
        }
    }

    /**
     * Corrección aplicada a {@code ids} del servicio: suma un intento a cada caso pendiente y encola la verificación del
     * servicio (solo esos casos cuentan como intento de montaje si vuelven a fallar).
     */
    static void aplicada(SesionMcp sesion, Path proyecto, Path salida, String fqn, Collection<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        for (String id : ids) {
            sesion.actualizarArreglo(proyecto, id, a -> {
                if (a != null && Tanda.ARREGLO_PENDIENTE.equals(a.estado)) {
                    a.intentos++;
                    if (a.comun) {
                        a.intentosComun++;
                    }
                }
                return a;
            });
        }
        sesion.verificaciones().encolarArreglo(proyecto, salida, fqn, ids);
    }

    // ------------------------------------------------------------- corrección común transaccional

    /** Casos mostrados como ejemplo y recorte del error de cada uno en el motivo de una corrección común deshecha. */
    private static final int MAX_ERROR_EJEMPLO = 160;

    /**
     * Motivo por el que una corrección de la parte común debe deshacerse tras verificar, o null si se mantiene: la clase
     * ya no compila fuera de los casos, o casos que pasaban antes (y no son los corregidos) ya no pasan.
     */
    static String rompeComun(VerificacionesSesion.PuntoComun p, ResultadoVerificacion rv, Set<String> propios) {
        if (p == null || rv == null || rv.resultados == null) {
            return null;
        }
        for (ResultadoTest t : rv.resultados) {
            if (t != null && t.errorComun && (t.caso == null || propios == null || propios.contains(t.caso))) {
                String detalle = unaLinea(t.mensaje == null ? "" : t.mensaje);
                if (detalle.startsWith(VerificadorTests.PREFIJO_ERROR_COMUN)) {
                    int k = detalle.indexOf("): ");
                    detalle = k < 0 ? detalle.substring(VerificadorTests.PREFIJO_ERROR_COMUN.length())
                            : detalle.substring(k + "): ".length());
                }
                String pista = t.pista == null || t.pista.isBlank() ? "" : " " + unaLinea(t.pista);
                String texto = (detalle.trim() + pista).trim();
                return LimitesRespuesta.recortar("la clase de test dejó de compilar fuera de los casos: "
                        + (texto.isEmpty() ? SIN_DETALLE : texto), MAX_ERROR);
            }
        }
        int rotos = 0;
        String ejemplo = null;
        for (ResultadoTest t : rv.resultados) {
            if (t == null || t.caso == null || (propios != null && !propios.contains(t.caso))
                    || p.idsCorreccion().contains(t.caso)) {
                continue;
            }
            if (ClasificadorFallos.PASA.equals(p.estadosPrevios().get(t.caso)) && !ClasificadorFallos.PASA.equals(t.estado)) {
                rotos++;
                if (ejemplo == null) {
                    String nombre = t.metodoTest != null && !t.metodoTest.isBlank() ? t.metodoTest : t.caso;
                    ejemplo = nombre + ": " + LimitesRespuesta.recortar(unaLinea(t.mensaje == null ? SIN_DETALLE : t.mensaje),
                            MAX_ERROR_EJEMPLO);
                }
            }
        }
        if (rotos == 0) {
            return null;
        }
        return LimitesRespuesta.recortar("rompía " + rotos + (rotos == 1 ? " caso que pasaba" : " casos que pasaban")
                + ", p. ej. " + ejemplo, MAX_ERROR);
    }

    /**
     * Deshace una corrección de la parte común (nunca lanza): restaura el archivo de test y la entrada del servicio en
     * spec.json, vuelve a registrar los estados previos, deja el motivo en los casos corregidos y guarda la tanda.
     * Devuelve true si quedó deshecha (el llamador reverifica el servicio).
     */
    static boolean deshacerComun(SesionMcp sesion, Path proyecto, Path salida, String fqn, VerificacionesSesion.PuntoComun p,
                                 String motivo) {
        if (sesion == null || proyecto == null || salida == null || fqn == null || p == null || p.archivoTest() == null
                || p.textoPrevio() == null || p.servicioPrevio() == null) {
            return false;
        }
        try {
            com.junitgen.seguridad.EscritorSeguro esc = new com.junitgen.seguridad.EscritorSeguro(proyecto, salida);
            Boolean restaurado = com.junitgen.seguridad.BloqueosSalida.conEscritura(esc.salida(), () -> {
                Spec spec = AlmacenSpec.leer(esc.salida());
                if (spec == null || spec.servicios == null) {
                    return Boolean.FALSE;
                }
                ServicioSpec previo;
                try {
                    previo = Json.MAPPER.treeToValue(p.servicioPrevio(), ServicioSpec.class);
                } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                    System.err.println("junit-gen: no se pudo leer la entrada guardada de " + fqn + ": " + e.getClass().getSimpleName());
                    return Boolean.FALSE;
                }
                int pos = -1;
                for (int i = 0; i < spec.servicios.size(); i++) {
                    ServicioSpec s = spec.servicios.get(i);
                    if (s != null && fqn.equals(s.clase) && Objects.equals(s.modulo, previo.modulo)) {
                        pos = i;
                        break;
                    }
                }
                if (pos < 0) {
                    return Boolean.FALSE;
                }
                spec.servicios.set(pos, previo);
                Map<Path, String> plan = new LinkedHashMap<>();
                plan.put(p.archivoTest(), p.textoPrevio());
                plan.put(AlmacenSpec.ruta(esc.salida()), AlmacenSpec.serializar(spec));
                esc.escribirTodo(plan);
                return Boolean.TRUE;
            });
            if (!Boolean.TRUE.equals(restaurado)) {
                return false;
            }
            sesion.registrarEstados(salida, p.estadosPrevios());
            String frase = LimitesRespuesta.recortar("Tu cambio de `servicio` (parte común) se ha deshecho: " + unaLinea(motivo)
                    + ". No lo repitas: cambia solo este caso (datos/preparacion; un campo sin setter se fija por reflexión sobre la "
                    + "instancia) o usa en `servicio` métodos que existan.", MAX_ERROR + MAX_PISTA);
            String cambio = LimitesRespuesta.recortar("Corrección de la parte común deshecha: " + unaLinea(motivo), MAX_ERROR);
            for (String id : p.idsCorreccion()) {
                sesion.actualizarArreglo(proyecto, id, a -> {
                    if (a == null) {
                        return null;
                    }
                    a.correccionDeshecha = frase;
                    List<String> todos = new ArrayList<>(a.cambios == null ? List.of() : a.cambios);
                    if (!todos.contains(cambio)) {
                        todos.add(cambio);
                    }
                    while (todos.size() > MAX_CAMBIOS) {
                        todos.remove(0);
                    }
                    a.cambios = todos;
                    return a;
                });
            }
            sesion.guardarTanda(proyecto, salida, Boolean.FALSE);
            return true;
        } catch (RuntimeException e) {
            System.err.println("junit-gen: no se pudo deshacer la corrección común de " + fqn + ": " + e.getClass().getSimpleName());
            return false;
        }
    }

    // ------------------------------------------------------------- respuestas

    /**
     * Respuesta de la fase de arreglo: aplica antes los arreglos automáticos del primer servicio con pendientes (y espera su
     * verificación); después, el grupo de casos pendientes de ese servicio (terminando antes los que se entregaron
     * demasiadas veces sin corrección). Sin pendientes: null si {@code anterior} es null; si no, la orden de cerrar con
     * ejecutar_tests.
     */
    static ObjectNode respuesta(SesionMcp sesion, Path proyecto, Path salida, ObjectNode anterior) {
        int limite = 4 * sesion.arreglos(proyecto).size() + 4;
        for (int vuelta = 0; vuelta < limite; vuelta++) {
            List<Tanda.ArregloTanda> pendientes = sesion.arreglosPendientes(proyecto);
            if (pendientes.isEmpty()) {
                break;
            }
            Tanda.ArregloTanda a = pendientes.get(0);
            // Salida reanudada con error común apuntado y sin resultado fresco: se re-verifica el servicio (repara imports) una vez.
            if (a.comun && sesion.verificaciones().ultimo(salida, a.caso) == null
                    && sesion.contarSinCorregir(CLAVE_REVERIFICAR + a.servicio, 0) == 1) {
                sesion.verificaciones().encolarArreglo(proyecto, salida, a.servicio, List.of());
                sesion.verificaciones().esperarInactiva(salida, HerramientaEjecutar.restoEspera(sesion));
                continue;
            }
            if (AutoArreglos.intentar(sesion, proyecto, salida, a.servicio)) {
                if (!sesion.verificaciones().esperarInactiva(salida, HerramientaEjecutar.restoEspera(sesion))) {
                    ObjectNode r = Json.objeto();
                    r.put("accionRequerida", HerramientaEjecutar.ACCION_PENDIENTE_IA + "junit-gen está aplicando y probando "
                            + "correcciones automáticas: llama ahora a " + BloquesTanda.llamadaEjecutar(sesion) + ".");
                    r.put("verificando", true);
                    if (anterior != null) {
                        r.set("resultadoAnterior", anterior);
                    }
                    r.set("fase", fase(sesion, proyecto));
                    LimitesRespuesta.acotarJson(r, presupuesto(), List.of(), "El detalle está en informe.md.");
                    return r;
                }
                continue;
            }
            List<Tanda.ArregloTanda> g = grupo(sesion, proyecto, salida, pendientes);
            boolean terminado = false;
            for (Tanda.ArregloTanda x : g) {
                if (sesion.contarSinCorregir(x.caso, x.intentos) > MAX_SIN_CORREGIR) {
                    terminar(sesion, proyecto, salida, x, MOTIVO_SIN_CORRECCION);
                    terminado = true;
                }
            }
            if (terminado) {
                continue;
            }
            return carga(sesion, proyecto, salida, g, anterior);
        }
        if (anterior == null) {
            return null;
        }
        Integer bloque = sesion.bloqueLlamada();
        if (bloque != null && sesion.ambito(proyecto) != null) {
            // Dentro de un bloque no hay respuestaFinal: el bloque queda terminado.
            return BloquesTanda.nodoTerminado(sesion, proyecto, salida, bloque, anterior);
        }
        ObjectNode r = Json.objeto();
        r.put("accionRequerida", HerramientaEjecutar.ACCION_PENDIENTE_IA + "No quedan casos por arreglar: llama ahora a "
                + "ejecutar_tests {} para cerrar la tanda y recibir respuestaFinal.");
        r.set("resultadoAnterior", anterior);
        r.set("fase", fase(sesion, proyecto));
        LimitesRespuesta.acotarJson(r, presupuesto(), List.of(), "El detalle está en informe.md.");
        return r;
    }

    /** {nombre, caso, de, pendientes, arreglados, posiblesBugs, apartados} de la fase de arreglo. */
    private static ObjectNode fase(SesionMcp sesion, Path proyecto) {
        int[] cu = cuenta(sesion, proyecto);
        ObjectNode f = Json.objeto();
        f.put("nombre", Tanda.FASE_ARREGLO);
        f.put("caso", Math.min(cu[0], cu[0] - cu[1] + 1));
        f.put("de", cu[0]);
        f.put("pendientes", cu[1]);
        f.put("arreglados", cu[2]);
        f.put("posiblesBugs", cu[3]);
        f.put("apartados", cu[4]);
        int automaticos = 0;
        for (Tanda.ArregloTanda a : sesion.arreglos(proyecto)) {
            if (Tanda.ARREGLO_ARREGLADO.equals(a.estado) && a.automatico) {
                automaticos++;
            }
        }
        if (automaticos > 0) {
            f.put("automaticos", automaticos);
        }
        return f;
    }

    /** Escribe una vez la sección «Arreglos al final» de informe.md (si hay casos apuntados y aún no está escrita). */
    static void informar(SesionMcp sesion, Path proyecto, Path salida) {
        if (!sesion.reservarInformeArreglos(proyecto)) {
            return;
        }
        try {
            List<VerificadorTests.ArregloInforme> filas = new ArrayList<>();
            for (Tanda.ArregloTanda a : sesion.arreglos(proyecto)) {
                List<String> cambios = new ArrayList<>();
                if (a.autoCambios != null) {
                    for (String c : a.autoCambios) {
                        if (c != null) {
                            cambios.add(PREFIJO_AUTO + c);
                        }
                    }
                }
                if (a.cambios != null) {
                    for (String c : a.cambios) {
                        if (c != null) {
                            cambios.add(c);
                        }
                    }
                }
                filas.add(new VerificadorTests.ArregloInforme(a.servicio, a.metodoTest, a.caso, a.tipo,
                        a.errorInicial != null ? a.errorInicial : a.error, List.copyOf(cambios),
                        a.intentos, a.estado, a.motivo, a.comun && Tanda.ARREGLO_APARTADO.equals(a.estado), a.automatico,
                        a.menosEstricto, a.avisosCalidad == null ? List.of() : List.copyOf(a.avisosCalidad)));
            }
            sesion.fachada().anotarArreglos(proyecto, salida, filas, sesion.importsReparados(proyecto));
        } catch (RuntimeException e) {
            sesion.liberarInformeArreglos(proyecto);
            System.err.println("junit-gen: no se pudo anotar «Arreglos al final» en informe.md: " + e.getClass().getSimpleName() + ": "
                    + e.getMessage());
        }
    }

    /**
     * Grupo de casos que se entregan a la vez: el primer pendiente y, si no es común, los demás no comunes de su servicio
     * (primero los del mismo método), hasta {@link #MAX_GRUPO} y mientras quepan con {@link #RESERVA_CODIGO} libres para
     * el código. El primero entra siempre.
     */
    static List<Tanda.ArregloTanda> grupo(SesionMcp sesion, Path proyecto, Path salida, List<Tanda.ArregloTanda> pendientes) {
        Tanda.ArregloTanda primero = pendientes.get(0);
        if (primero.comun || primero.servicio == null) {
            return List.of(primero);
        }
        List<Tanda.ArregloTanda> mismos = new ArrayList<>();
        List<Tanda.ArregloTanda> otros = new ArrayList<>();
        for (Tanda.ArregloTanda p : pendientes) {
            if (p.comun || !primero.servicio.equals(p.servicio)) {
                continue;
            }
            (Objects.equals(p.metodo, primero.metodo) ? mismos : otros).add(p);
        }
        List<Tanda.ArregloTanda> candidatos = new ArrayList<>(mismos);
        candidatos.addAll(otros);
        if (candidatos.size() <= 1) {
            return List.of(primero);
        }
        Spec spec = leerSpec(salida, primero.caso);
        int n = Math.min(MAX_GRUPO, candidatos.size());
        ObjectNode raiz = Json.objeto();
        raiz.put("accionRequerida", HerramientaEjecutar.ACCION_PENDIENTE_IA + instruccionGrupo(sesion, proyecto, primero, n));
        raiz.set("fase", fase(sesion, proyecto));
        raiz.put("clase", primero.servicio);
        ponerSiHay(raiz, "claseTest", primero.claseTest);
        int usados = LimitesRespuesta.bytes(Json.compacto(raiz));
        int limite = presupuesto() - RESERVA_CODIGO;
        List<Tanda.ArregloTanda> r = new ArrayList<>();
        for (Tanda.ArregloTanda a : candidatos) {
            if (r.size() >= MAX_GRUPO) {
                break;
            }
            CasoSpec c = casoDe(spec, a.caso);
            ObjectNode nodo = nodoCaso(a, c, null, false);
            if (c != null) {
                nodo.set("spec", specEntrega(c));
            }
            int tam = LimitesRespuesta.bytes(Json.compacto(nodo));
            if (c != null && c.estaticos != null && !c.estaticos.isEmpty()) {
                tam += PistasArreglo.MAX_PISTA;
            }
            if (!r.isEmpty() && usados + tam > limite) {
                break;
            }
            usados += tam;
            r.add(a);
        }
        return r;
    }

    /**
     * Carga autosuficiente de un caso: accionRequerida con la llamada exacta, resultadoAnterior, fase, caso {error, pista,
     * pistas}, spec actual del caso, parte común (si el error está ahí) y código del método probado, acotada al presupuesto.
     */
    static ObjectNode carga(SesionMcp sesion, Path proyecto, Path salida, Tanda.ArregloTanda a, ObjectNode anterior) {
        return carga(sesion, proyecto, salida, List.of(a), anterior);
    }

    /**
     * Carga autosuficiente de un grupo de casos del mismo servicio (con uno solo, la forma de un caso): accionRequerida con
     * la llamada exacta, resultadoAnterior, fase, casos {error, pista, pistas, auto, spec} y el código de sus métodos,
     * acotada al presupuesto. Si el código no cabe entero y hay varios métodos, se entrega solo el método del primero.
     */
    static ObjectNode carga(SesionMcp sesion, Path proyecto, Path salida, List<Tanda.ArregloTanda> grupo, ObjectNode anterior) {
        Carga c = armar(sesion, proyecto, salida, grupo, anterior);
        if (c.incompleta() && grupo.size() > 1) {
            String metodo = grupo.get(0).metodo;
            List<Tanda.ArregloTanda> mismos = new ArrayList<>();
            Set<String> metodos = new LinkedHashSet<>();
            for (Tanda.ArregloTanda a : grupo) {
                metodos.add(Objects.toString(a.metodo, ""));
                if (Objects.equals(a.metodo, metodo)) {
                    mismos.add(a);
                }
            }
            if (metodos.size() > 1) {
                c = armar(sesion, proyecto, salida, mismos, anterior);
            }
        }
        return c.r();
    }

    /** Carga armada y si su código quedó incompleto. */
    private record Carga(ObjectNode r, boolean incompleta) {
    }

    private static Carga armar(SesionMcp sesion, Path proyecto, Path salida, List<Tanda.ArregloTanda> grupo, ObjectNode anterior) {
        Tanda.ArregloTanda a = grupo.get(0);
        boolean varios = grupo.size() > 1;
        String id = a.caso;
        String simple = Textos.nombreSimple(a.servicio);
        Spec spec = leerSpec(salida, id);
        ServicioSpec sv = null;
        AlmacenSpec.Ubicacion u0 = spec == null ? null : AlmacenSpec.buscar(spec, id);
        if (u0 != null) {
            sv = u0.servicio();
        }
        String modulo = sv == null ? null : sv.modulo;
        Set<String> metodos = new LinkedHashSet<>();
        for (Tanda.ArregloTanda x : grupo) {
            if (x.metodo != null && !x.metodo.isBlank()) {
                metodos.add(x.metodo);
            }
        }
        // 1. Paquete de análisis (solo para las pistas).
        ObjectNode d0 = null;
        if (a.servicio != null && !metodos.isEmpty()) {
            try {
                PaqueteTrabajo.Opciones op = opcionesPaquete(metodos, modulo, LimitesRespuesta.TOPE_PRESUPUESTO_PAQUETE, true);
                d0 = HerramientaDetalle.paquete(sesion, proyecto, null, a.servicio, op);
            } catch (RuntimeException e) {
                System.err.println("junit-gen: no se pudo analizar el código del caso " + id + " para las pistas: "
                        + e.getClass().getSimpleName() + ": " + e.getMessage());
                d0 = null;
            }
        }
        // 2. Nodos de los casos.
        int[] cu = cuenta(sesion, proyecto);
        int total = cu[0];
        int k = Math.max(1, total - cu[1] + 1);
        ObjectNode r = Json.objeto();
        if (varios) {
            int j = Math.min(total, k + grupo.size() - 1);
            r.put("accionRequerida", HerramientaEjecutar.ACCION_PENDIENTE_IA + "Fase de arreglo: casos " + k + "–" + j + " de " + total
                    + " — " + instruccionGrupo(sesion, proyecto, a, grupo.size()));
        } else {
            int intento = Math.min(a.intentos + 1, VerificadorTests.MAX_INTENTOS);
            String metodoTest = a.metodoTest != null && !a.metodoTest.isBlank() ? a.metodoTest : a.caso;
            r.put("accionRequerida", HerramientaEjecutar.ACCION_PENDIENTE_IA + "Fase de arreglo: caso " + k + " de " + total + " — "
                    + GuiaTanda.posicion(sesion, proyecto, a.servicio) + " `" + simple + "`, test `" + metodoTest + "` (caso `" + id
                    + "`, intento " + intento + " de " + VerificadorTests.MAX_INTENTOS + "). " + instruccionCaso(a, simple));
        }
        if (anterior != null) {
            r.set("resultadoAnterior", anterior);
        }
        r.set("fase", fase(sesion, proyecto));
        if (varios) {
            r.put("clase", a.servicio);
            ponerSiHay(r, "claseTest", a.claseTest);
            ArrayNode casos = r.putArray("casos");
            for (Tanda.ArregloTanda x : grupo) {
                CasoSpec c = casoDe(spec, x.caso);
                ObjectNode nodo = nodoCaso(x, c, d0, false);
                if (c != null) {
                    nodo.set("spec", specEntrega(c));
                }
                casos.add(nodo);
            }
        } else {
            CasoSpec c = u0 == null ? null : u0.caso();
            r.set("caso", nodoCaso(a, c, d0, true));
            if (c != null) {
                r.set("spec", specEntrega(c));
            }
            if (a.comun && sv != null) {
                r.set("servicio", parteComun(sv));
            }
        }
        // 3. Código de los métodos del grupo.
        boolean incompleta = false;
        String llamada = "detalle_servicio {clase: \"" + a.servicio + "\", metodos: [\"" + (a.metodo == null ? "…" : a.metodo) + "\"]}";
        if (metodos.isEmpty() || a.servicio == null) {
            r.put("codigoNoDisponible", llamada);
        } else {
            try {
                int usados = LimitesRespuesta.bytes(Json.compacto(r));
                PaqueteTrabajo.Opciones op = opcionesPaquete(metodos, modulo,
                        Math.max(MIN_CODIGO, presupuesto() - usados - MARGEN_CARGA), a.comun);
                ObjectNode d = HerramientaDetalle.paquete(sesion, proyecto, null, a.servicio, op);
                ObjectNode codigo = Json.objeto();
                List<String> claves = new ArrayList<>(CLAVES_CODIGO);
                if (a.comun) {
                    claves.add("montaje");
                }
                for (String c : claves) {
                    JsonNode v = d.get(c);
                    if (v != null && !v.isNull()) {
                        codigo.set(c, v);
                    }
                }
                JsonNode sig = d.path("paginacion").get("siguiente");
                JsonNode omitido = d.get("omitido");
                incompleta = (sig != null && !sig.isNull()) || (omitido != null && !omitido.isNull() && !omitido.isEmpty());
                if (sig != null && !sig.isNull()) {
                    codigo.put("masCodigo", "detalle_servicio con " + Json.compacto(sig));
                }
                r.set("codigo", codigo);
            } catch (RuntimeException e) {
                System.err.println("junit-gen: no se pudo leer el código del caso " + id + ": " + e.getClass().getSimpleName() + ": "
                        + e.getMessage());
                r.remove("codigo");
                r.put("codigoNoDisponible", llamada);
            }
        }
        LimitesRespuesta.acotarJson(r, presupuesto(), ORDEN_RECORTE, Set.of(), Set.of("spec", "caso", "casos", "fase", "servicio"),
                COMO_VER_CODIGO);
        return new Carga(r, incompleta);
    }

    /** Opciones del paquete de trabajo de los métodos (sin plantilla ni stubs típicos; montaje solo si se pide). */
    private static PaqueteTrabajo.Opciones opcionesPaquete(Collection<String> metodos, String modulo, int presupuesto, boolean montaje) {
        PaqueteTrabajo.Opciones op = new PaqueteTrabajo.Opciones();
        op.metodos = new ArrayList<>(metodos);
        op.modulo = modulo;
        op.pagina = 1;
        op.conPlantilla = false;
        op.conMontaje = montaje;
        op.sinStubsTipicos = true;
        op.presupuesto = presupuesto;
        return op;
    }

    /** Instrucción de un caso según su tipo de fallo (sin la cabecera). */
    private static String instruccionCaso(Tanda.ArregloTanda a, String simple) {
        String id = a.caso;
        if (a.comun) {
            return "El error está en la parte común del test de `" + simple + "` (construcción, fixtures o imports), no en este "
                    + "caso (ver caso.error y caso.pista). Corrige servicio de abajo y llama ahora a arreglar_caso {caso: \"" + id
                    + "\", servicio: {imports, construccion, fixtures}} (añade spec solo si también cambia el caso). Arregla a la vez "
                    + "todos los casos de `" + simple + "`. No escribas al usuario. Si tras " + MAX_INTENTOS_COMUN + " correcciones la clase "
                    + "sigue sin compilar, junit-gen apartará el servicio entero y seguirá con el siguiente.";
        }
        if (ClasificadorFallos.FALLO_ASERCION.equals(a.tipo)) {
            return "El test se ejecuta pero el resultado no es el esperado (ver caso.error y caso.pistas). Decide con un motivo que "
                    + "cite el código: (a) el test estaba mal planteado → arreglar_caso {caso: \"" + id + "\", motivo: \"…\", spec: "
                    + "{…}}; (b) el código hace algo distinto de lo que la función debe hacer (posible bug) → registrar_analisis_ia "
                    + "{analisis: [{caso: \"" + id + "\", veredicto: \"bug_servicio\", motivo: \"…\"}]}. Prohibido poner como "
                    + "esperado el valor obtenido sin justificarlo con el código. " + REGLAS_ARREGLO;
        }
        return "El test no compila o falla al prepararse (ver caso.error, caso.pista y caso.pistas). Corrige el spec y llama ahora a "
                + "arreglar_caso {caso: \"" + id + "\", spec: {…solo los campos que cambian…}}. " + REGLAS_ARREGLO;
    }

    /** Instrucción de un grupo de {@code n} casos (tras «casos K–J de M — »). */
    private static String instruccionGrupo(SesionMcp sesion, Path proyecto, Tanda.ArregloTanda a, int n) {
        return GuiaTanda.posicion(sesion, proyecto, a.servicio) + " `" + Textos.nombreSimple(a.servicio) + "`. Corrige los " + n
                + " casos de `casos` en UNA sola llamada: arreglar_caso {casos: [{caso: \"" + a.caso + "\", spec: {…solo los campos "
                + "que cambian…}, motivo: \"…\"}, …]}; junit-gen recompila la clase una vez y devuelve el resultado de cada uno. Si "
                + "un fallo de aserción es un posible bug del código, regístralo con registrar_analisis_ia {analisis: [{caso, "
                + "veredicto: \"bug_servicio\", motivo}]} y envía el resto con arreglar_caso. " + REGLAS_ARREGLO;
    }

    /**
     * Nodo de un caso en la carga: {id, [servicio, claseTest], metodoTest, metodo, tipo, intento, error, pista, pistas, auto,
     * comun}.
     */
    private static ObjectNode nodoCaso(Tanda.ArregloTanda a, CasoSpec c, JsonNode paquete, boolean conServicio) {
        ObjectNode caso = Json.objeto();
        caso.put("id", a.caso);
        if (conServicio) {
            ponerSiHay(caso, "servicio", a.servicio);
            ponerSiHay(caso, "claseTest", a.claseTest);
        }
        ponerSiHay(caso, "metodoTest", a.metodoTest);
        ponerSiHay(caso, "metodo", a.metodo);
        ponerSiHay(caso, "tipo", a.tipo);
        caso.put("intento", Math.min(a.intentos + 1, VerificadorTests.MAX_INTENTOS));
        ponerSiHay(caso, "error", a.error);
        ponerSiHay(caso, "pista", a.pista);
        ponerSiHay(caso, "correccionDeshecha", a.correccionDeshecha);
        List<String> pistas = PistasArreglo.de(a, c, paquete);
        if (!pistas.isEmpty()) {
            ArrayNode p = caso.putArray("pistas");
            pistas.forEach(p::add);
        }
        if (a.autoCambios != null && !a.autoCambios.isEmpty()) {
            ArrayNode au = caso.putArray("auto");
            for (String f : a.autoCambios) {
                if (au.size() >= MAX_AUTO_CARGA) {
                    break;
                }
                au.add(f);
            }
        }
        if (a.comun) {
            caso.put("comun", true);
        }
        return caso;
    }

    /** Spec del caso que se entrega: limpio y sin los textos de {@link #CAMPOS_TEXTO_SPEC}. */
    private static ObjectNode specEntrega(CasoSpec c) {
        ObjectNode s = HerramientaEstadoVerificacion.specLimpio(c);
        CAMPOS_TEXTO_SPEC.forEach(s::remove);
        return s;
    }

    /** spec.json de la salida, o null si no se puede leer (con traza). */
    private static Spec leerSpec(Path salida, String caso) {
        try {
            return AlmacenSpec.leer(salida);
        } catch (RuntimeException e) {
            System.err.println("junit-gen: spec.json ilegible al preparar el caso " + caso + ": " + e.getClass().getSimpleName());
            return null;
        }
    }

    private static CasoSpec casoDe(Spec spec, String caso) {
        AlmacenSpec.Ubicacion u = spec == null ? null : AlmacenSpec.buscar(spec, caso);
        return u == null ? null : u.caso();
    }

    /**
     * Identificadores del código del método probado (cuerpos, firmas y claves del método, sus privados llamados y helpers
     * externos), sin palabras reservadas ni tipos comunes; siempre incluye el método. Nunca lanza.
     */
    static Set<String> identificadoresCodigo(SesionMcp sesion, Path proyecto, Tanda.ArregloTanda a, String modulo) {
        if (a == null || a.metodo == null || a.metodo.isBlank()) {
            return Set.of();
        }
        if (a.servicio == null) {
            return Set.of(a.metodo);
        }
        try {
            PaqueteTrabajo.Opciones op = opcionesPaquete(List.of(a.metodo), modulo, LimitesRespuesta.TOPE_PRESUPUESTO_PAQUETE, false);
            ObjectNode d = HerramientaDetalle.paquete(sesion, proyecto, null, a.servicio, op);
            Set<String> r = new LinkedHashSet<>();
            r.add(a.metodo);
            for (String k : CLAVES_IDENTIFICADORES) {
                for (JsonNode n : d.path(k)) {
                    for (String campo : CAMPOS_IDENTIFICADORES) {
                        JsonNode v = n.get(campo);
                        if (v == null || !v.isTextual()) {
                            continue;
                        }
                        Matcher m = IDENTIFICADOR.matcher(v.asText());
                        while (m.find()) {
                            if (!NO_IDENTIFICADORES.contains(m.group())) {
                                r.add(m.group());
                            }
                        }
                    }
                }
            }
            return r;
        } catch (RuntimeException e) {
            System.err.println("junit-gen: no se pudieron leer los identificadores del código de " + a.caso + ": "
                    + e.getClass().getSimpleName() + ": " + e.getMessage());
            return Set.of(a.metodo);
        }
    }

    /**
     * true si {@code motivo} justifica con el código un test menos estricto: al menos {@link #MIN_JUSTIFICACION} caracteres y
     * cita como palabra completa al menos {@code min(MIN_IDENTIFICADORES, ids.size())} identificadores distintos de {@code ids}.
     */
    static boolean justificacionValida(String motivo, Set<String> ids) {
        if (motivo == null || motivo.strip().length() < MIN_JUSTIFICACION) {
            return false;
        }
        Set<String> conjunto = ids == null ? Set.of() : ids;
        int necesarios = Math.min(MIN_IDENTIFICADORES, conjunto.size());
        int citados = 0;
        for (String id : conjunto) {
            if (id == null || id.isEmpty()) {
                continue;
            }
            Pattern p = Pattern.compile("(?<![\\w$])" + Pattern.quote(id) + "(?![\\w$])");
            if (p.matcher(motivo).find()) {
                citados++;
                if (citados >= necesarios) {
                    return true;
                }
            }
        }
        return citados >= necesarios;
    }

    /** {imports, construccion, fixtures} del servicio guardado en spec.json. */
    static ObjectNode parteComun(ServicioSpec sv) {
        ObjectNode n = Json.MAPPER.valueToTree(sv);
        ObjectNode r = Json.objeto();
        for (String k : List.of("imports", "construccion", "fixtures")) {
            JsonNode v = n.get(k);
            if (v != null && !v.isNull()) {
                r.set(k, v);
            }
        }
        return r;
    }

    /** Ids pendientes con error común del mismo servicio (para contar la corrección común en todos ellos). */
    static Set<String> pendientesComunes(SesionMcp sesion, Path proyecto, String fqn) {
        Set<String> r = new LinkedHashSet<>();
        for (Tanda.ArregloTanda a : sesion.arreglosPendientes(proyecto)) {
            if (a.comun && fqn != null && fqn.equals(a.servicio)) {
                r.add(a.caso);
            }
        }
        return r;
    }

    private static void ponerSiHay(ObjectNode n, String k, String v) {
        if (v != null && !v.isBlank()) {
            n.put(k, v);
        }
    }

    private static String recortarMotivo(String motivo) {
        return motivo == null || motivo.isBlank() ? null : LimitesRespuesta.recortar(unaLinea(motivo), MAX_MOTIVO);
    }

    private static String unaLinea(String s) {
        return s == null ? null : s.replaceAll("\\s*\\R\\s*", " ").trim();
    }
}
