package com.junitgen.escaneo;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.ThrowStmt;
import com.github.javaparser.ast.stmt.TryStmt;
import com.junitgen.generacion.EscritorInforme;
import com.junitgen.generacion.TextosInforme;
import com.junitgen.spec.ErrorValidacion;
import com.junitgen.spec.SeleccionSpec;
import com.junitgen.util.LimitesRespuesta;
import com.junitgen.util.Textos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Selección de las funciones de un servicio que merecen tests. junit-gen prepara las candidatas con datos
 * objetivos (puntuación transitiva, escritura, transacción, excepciones, privados usados, tests existentes) y
 * fija el cupo ({@code porcentajeFunciones} % de las no triviales, por defecto 60 %); la IA elige dentro del
 * cupo por importancia de negocio ({@code seleccionar_funciones}) y junit-gen valida la elección
 * ({@link #validarEleccion}). Orígenes: {@code ia}, {@code usuario} (funciones nombradas por el usuario),
 * {@code todas} (100 % de las públicas, incluidas triviales) y {@code java} (sin elección: top % por
 * puntuación, determinista).
 */
public final class SeleccionFunciones {

    public static final String ORIGEN_IA = "ia";
    public static final String ORIGEN_USUARIO = "usuario";
    public static final String ORIGEN_JAVA = "java";
    public static final String ORIGEN_TODAS = "todas";
    /** Niveles de llamadas internas que suman a la puntuación de una función. */
    static final int MAX_NIVELES = 3;
    /** Longitud máxima de un motivo. */
    public static final int MAX_MOTIVO = 120;
    /** Entradas máximas en {@code funciones} y en {@code omitidas}. */
    public static final int MAX_ENTRADAS = 200;
    private static final int MAX_LANZA = 3;
    private static final int MAX_PRIVADOS = 4;
    private static final int MAX_TRIVIALES_PIE = 10;

    private static final Pattern EFECTO =
            Pattern.compile("^(save|delete|remove|update|insert|persist|merge|flush|send|publish|create).*");
    private static final Pattern ESCRITURA =
            Pattern.compile("^(save|delete|remove|update|insert|persist|merge|flush).*");

    /** Datos objetivos de una función (transitivos sobre los métodos internos que usa). */
    public record Datos(int ramas, boolean escribeDatos, boolean transaccional, List<String> privados,
            List<String> excepciones, int tests, String trivial) {
    }

    /** Función pública del servicio con su puntuación, máximo de casos y si entra en la selección. */
    public record Funcion(InfoMetodo metodo, int puntuacion, int maxCasos, boolean seleccionada,
            String motivoOmision, String motivoSeleccion, Datos datos) {

        public Funcion(InfoMetodo metodo, int puntuacion, int maxCasos, boolean seleccionada, String motivoOmision) {
            this(metodo, puntuacion, maxCasos, seleccionada, motivoOmision, null,
                    new Datos(metodo.ramas(), false, false, List.of(), List.of(), 0, null));
        }
    }

    /** Resultado de validar una elección: la selección normalizada (claves) o los errores. */
    public record Eleccion(SeleccionSpec normalizada, List<ErrorValidacion> errores) {
        public boolean valida() {
            return errores.isEmpty();
        }
    }

    private final InfoClase clase;
    private final List<Funcion> funciones;
    private final Map<InfoMetodo, Funcion> porMetodo = new IdentityHashMap<>();
    private final Map<InfoMetodo, String> origenFila = new IdentityHashMap<>();
    private final int porcentaje;
    private final boolean todas;
    private final String origen;

    private SeleccionFunciones(InfoClase clase, List<Funcion> funciones, int porcentaje, boolean todas, String origen) {
        this.clase = clase;
        this.funciones = funciones;
        this.porcentaje = porcentaje;
        this.todas = todas;
        this.origen = origen;
        for (Funcion f : funciones) {
            porMetodo.put(f.metodo(), f);
        }
    }

    public static SeleccionFunciones de(ModuloEscaneado me, InfoClase c, boolean todas) {
        return de(me, c, todas, null);
    }

    /**
     * Selección efectiva: con {@code todas}, el 100 %; con {@code guardada} de origen ia/usuario y al menos un
     * nombre resoluble, esas funciones; si no, la selección automática de junit-gen (origen java).
     */
    public static SeleccionFunciones de(ModuloEscaneado me, InfoClase c, boolean todas, SeleccionSpec guardada) {
        Analisis an = analizar(me, c);
        List<InfoMetodo> universo = an.universo;
        Set<InfoMetodo> elegidas = Collections.newSetFromMap(new IdentityHashMap<>());
        Map<InfoMetodo, String> motivosSel = new IdentityHashMap<>();
        Map<InfoMetodo, String> motivosOmi = new IdentityHashMap<>();
        String origen;
        if (todas) {
            origen = ORIGEN_TODAS;
            elegidas.addAll(universo);
        } else if (guardada != null && (ORIGEN_IA.equals(guardada.origen) || ORIGEN_USUARIO.equals(guardada.origen))
                && resolverEntradas(universo, guardada.funciones, elegidas, motivosSel)) {
            origen = guardada.origen;
            if (guardada.omitidas != null) {
                Set<InfoMetodo> omi = Collections.newSetFromMap(new IdentityHashMap<>());
                resolverEntradas(universo, guardada.omitidas, omi, motivosOmi);
                omi.removeAll(elegidas);
                motivosOmi.keySet().retainAll(omi);
            }
        } else {
            origen = ORIGEN_JAVA;
            List<InfoMetodo> candidatas = new ArrayList<>();
            for (InfoMetodo m : universo) {
                if (an.datos.get(m).trivial() == null) {
                    candidatas.add(m);
                }
            }
            candidatas.sort(Comparator.comparingInt((InfoMetodo m) -> -an.puntos.get(m)).thenComparing(InfoMetodo::clave));
            int k = cupo(candidatas.size(), an.pct);
            elegidas.addAll(candidatas.subList(0, Math.min(k, candidatas.size())));
        }
        List<Funcion> fs = new ArrayList<>();
        Map<InfoMetodo, String> filas = new IdentityHashMap<>();
        for (InfoMetodo m : universo) {
            int p = an.puntos.get(m);
            Datos d = an.datos.get(m);
            boolean sel = elegidas.contains(m);
            String motivoSel = null;
            String motivo = null;
            String fila = origen;
            if (sel) {
                motivoSel = switch (origen) {
                    case ORIGEN_TODAS -> "modo todas";
                    case ORIGEN_JAVA -> "puntuación " + p + " (top " + an.pct + " %)";
                    default -> {
                        String mo = motivosSel.get(m);
                        yield mo != null && !mo.isBlank() ? mo
                                : ORIGEN_USUARIO.equals(origen) ? "pedida por el usuario" : "elegida por la IA";
                    }
                };
            } else if (d.trivial() != null) {
                motivo = "trivial: " + d.trivial();
                fila = ORIGEN_JAVA;
            } else if (ORIGEN_JAVA.equals(origen)) {
                motivo = "fuera del porcentaje (puntuación " + p + "; top " + an.pct + " %)";
            } else {
                String mo = motivosOmi.get(m);
                if (mo != null && !mo.isBlank()) {
                    motivo = mo;
                } else {
                    motivo = ORIGEN_USUARIO.equals(origen) ? "no elegida por el usuario" : "no elegida por la IA";
                    fila = ORIGEN_JAVA;
                }
            }
            filas.put(m, fila);
            fs.add(new Funcion(m, p, maxCasosPorPuntuacion(p), sel, motivo, motivoSel, d));
        }
        SeleccionFunciones r = new SeleccionFunciones(c, fs, an.pct, todas, origen);
        r.origenFila.putAll(filas);
        return r;
    }

    /** Selección efectiva guardada: la de la sesión; si no, la del spec.json (origen ia); si no, null. */
    public static SeleccionSpec guardada(Map<String, SeleccionSpec> sesion, Map<String, SeleccionSpec> delSpecJson, String fqn) {
        if (fqn == null) {
            return null;
        }
        SeleccionSpec s = sesion == null ? null : sesion.get(fqn);
        if (s != null) {
            return s;
        }
        return delSpecJson == null ? null : delSpecJson.get(fqn);
    }

    // ------------------------------------------------------------- análisis

    private record Analisis(List<InfoMetodo> universo, Map<InfoMetodo, Integer> puntos, Map<InfoMetodo, Datos> datos,
            int pct) {
    }

    private static Analisis analizar(ModuloEscaneado me, InfoClase c) {
        int pct = me == null || me.config == null ? 60 : Math.max(1, Math.min(100, me.config.porcentaje()));
        Set<String> deps = new HashSet<>();
        EmparejadorTests.Emparejamiento emp = null;
        if (me != null) {
            for (Dependencia d : me.analisis(c).dependencias) {
                if (d.nombre != null) {
                    deps.add(d.nombre);
                }
            }
            emp = me.emparejamiento(c);
        }
        Set<String> nombresInternos = new LinkedHashSet<>();
        for (InfoMetodo x : c.metodos) {
            if (!x.publico) {
                nombresInternos.add(x.nombre);
            }
        }
        Map<InfoMetodo, Integer> propia = new IdentityHashMap<>();
        for (InfoMetodo x : c.metodos) {
            propia.put(x, puntuacion(x, deps));
        }
        Map<InfoMetodo, List<InfoMetodo>> alcance = new IdentityHashMap<>();
        Set<String> internosConLogica = new HashSet<>();
        for (InfoMetodo x : c.metodos) {
            List<InfoMetodo> al = alcanzables(c, x, nombresInternos);
            alcance.put(x, al);
            if (!x.publico && transitiva(x, al, propia) > 0) {
                internosConLogica.add(x.nombre);
            }
        }
        List<InfoMetodo> universo = c.metodosPublicos();
        Map<InfoMetodo, Integer> puntos = new IdentityHashMap<>();
        Map<InfoMetodo, Datos> datos = new IdentityHashMap<>();
        for (InfoMetodo m : universo) {
            List<InfoMetodo> al = alcance.get(m);
            puntos.put(m, transitiva(m, al, propia));
            int ramas = m.ramas();
            boolean escribe = escribe(m, deps);
            Set<String> privados = new LinkedHashSet<>();
            Set<String> excepciones = new LinkedHashSet<>();
            excepciones(m, excepciones);
            for (InfoMetodo q : al) {
                ramas += q.ramas();
                escribe |= escribe(q, deps);
                privados.add(q.nombre);
                excepciones(q, excepciones);
            }
            String trivial = m.abstracto ? "sin cuerpo" : m.motivoTrivial(internosConLogica);
            int tests = emp == null ? 0 : emp.casosDe(m.nombre, m.aridad()).size();
            datos.put(m, new Datos(ramas, escribe, m.anotaciones.contains("Transactional"), List.copyOf(privados),
                    List.copyOf(excepciones), tests, trivial));
        }
        return new Analisis(universo, puntos, datos, pct);
    }

    private static int transitiva(InfoMetodo m, List<InfoMetodo> alcanzables, Map<InfoMetodo, Integer> propia) {
        long t = propia.getOrDefault(m, 0);
        for (InfoMetodo q : alcanzables) {
            t += propia.getOrDefault(q, 0);
        }
        return (int) Math.min(Integer.MAX_VALUE, t);
    }

    /**
     * Métodos no públicos de la clase llamados (sin scope o con {@code this}) desde {@code m}, por nombre (todas
     * las sobrecargas), hasta {@link #MAX_NIVELES} niveles; sin {@code m} y sin repetir (por identidad).
     */
    static List<InfoMetodo> alcanzables(InfoClase c, InfoMetodo m, Set<String> nombresInternos) {
        Set<InfoMetodo> vistos = Collections.newSetFromMap(new IdentityHashMap<>());
        vistos.add(m);
        List<InfoMetodo> r = new ArrayList<>();
        List<InfoMetodo> nivel = List.of(m);
        for (int n = 0; n < MAX_NIVELES && !nivel.isEmpty(); n++) {
            List<InfoMetodo> siguiente = new ArrayList<>();
            for (InfoMetodo x : nivel) {
                List<String> nombres = new ArrayList<>();
                DetalleServicio.llamadas(x, nombresInternos, nombres);
                for (String nom : new LinkedHashSet<>(nombres)) {
                    for (InfoMetodo q : c.metodos) {
                        if (!q.publico && q.nombre.equals(nom) && vistos.add(q)) {
                            r.add(q);
                            siguiente.add(q);
                        }
                    }
                }
            }
            nivel = siguiente;
        }
        return r;
    }

    private static boolean escribe(InfoMetodo m, Set<String> deps) {
        if (m.decl.getBody().isEmpty()) {
            return false;
        }
        for (MethodCallExpr mc : m.decl.getBody().get().findAll(MethodCallExpr.class)) {
            if (llamadaADependencia(mc, deps) && ESCRITURA.matcher(mc.getNameAsString().toLowerCase(Locale.ROOT)).matches()) {
                return true;
            }
        }
        return false;
    }

    private static void excepciones(InfoMetodo m, Set<String> destino) {
        m.decl.getBody().ifPresent(b -> b.findAll(ThrowStmt.class).forEach(t -> {
            if (t.getExpression() instanceof ObjectCreationExpr oc) {
                destino.add(oc.getType().getNameAsString());
            }
        }));
    }

    static boolean llamadaADependencia(MethodCallExpr mc, Set<String> deps) {
        if (mc.getScope().isEmpty()) {
            return false;
        }
        var s = mc.getScope().get();
        return (s instanceof NameExpr n && deps.contains(n.getNameAsString()))
                || (s instanceof FieldAccessExpr f && f.getScope() instanceof ThisExpr && deps.contains(f.getNameAsString()));
    }

    /** Máximo de casos por complejidad: ≤3 → 2; 4..7 → 3; 8..13 → 4; ≥14 → 5. */
    static int maxCasosPorPuntuacion(int p) {
        if (p <= 3) {
            return 2;
        }
        if (p <= 7) {
            return 3;
        }
        if (p <= 13) {
            return 4;
        }
        return 5;
    }

    /**
     * Puntuación propia = 2·ramas + 2·try + catch + 2·min(llamadas a dependencias, 6) + 3·min(con efecto, 3)
     * + 3 si @Transactional + min(aritmética/lambdas/referencias/new, 5) + min(sentencias/5, 4). La puntuación
     * de una función pública suma además la propia de los métodos internos que usa (hasta 3 niveles).
     */
    static int puntuacion(InfoMetodo m, Set<String> deps) {
        if (m.decl.getBody().isEmpty()) {
            return 0;
        }
        Node b = m.decl.getBody().get();
        int r = m.ramas();
        int t = b.findAll(TryStmt.class).size();
        int cc = b.findAll(CatchClause.class).size();
        int d = 0;
        int e = 0;
        for (MethodCallExpr mc : b.findAll(MethodCallExpr.class)) {
            if (llamadaADependencia(mc, deps)) {
                d++;
                if (EFECTO.matcher(mc.getNameAsString().toLowerCase(Locale.ROOT)).matches()) {
                    e++;
                }
            }
        }
        int tx = m.anotaciones.contains("Transactional") ? 3 : 0;
        long k = b.findAll(BinaryExpr.class).stream().filter(x -> switch (x.getOperator()) {
            case PLUS, MINUS, MULTIPLY, DIVIDE, REMAINDER -> true;
            default -> false;
        }).count() + b.findAll(LambdaExpr.class).size() + b.findAll(MethodReferenceExpr.class).size()
                + b.findAll(ObjectCreationExpr.class).size();
        long sentencias = b.findAll(Statement.class).stream().filter(x -> !(x instanceof BlockStmt)).count();
        long total = 2L * r + 2L * t + cc + 2L * Math.min(d, 6) + 3L * Math.min(e, 3) + tx + Math.min(k, 5)
                + Math.min(sentencias / 5, 4);
        return (int) Math.min(Integer.MAX_VALUE, total);
    }

    static int cupo(int noTriviales, int pct) {
        return noTriviales == 0 ? 0 : Math.max(1, (int) Math.ceil(noTriviales * pct / 100.0));
    }

    static int minimo(int cupo) {
        return cupo == 0 ? 0 : Math.max(1, (int) Math.ceil(cupo / 2.0));
    }

    // ------------------------------------------------------------- resolución y validación

    /** Nombre normalizado: sin espacios ni backticks. */
    private static String normalizar(String n) {
        return n == null ? "" : n.replaceAll("[\\s`]", "");
    }

    /** Métodos del universo que corresponden al nombre (con «(» por clave; sin él, todas las sobrecargas). */
    static List<InfoMetodo> resolver(List<InfoMetodo> universo, String nombre) {
        String n = normalizar(nombre);
        List<InfoMetodo> r = new ArrayList<>();
        if (n.isEmpty()) {
            return r;
        }
        boolean porClave = n.contains("(");
        for (InfoMetodo m : universo) {
            if ((porClave ? m.clave() : m.nombre).equals(n)) {
                r.add(m);
            }
        }
        if (r.isEmpty()) {
            for (InfoMetodo m : universo) {
                if ((porClave ? m.clave() : m.nombre).equalsIgnoreCase(n)) {
                    r.add(m);
                }
            }
        }
        return r;
    }

    /** Resuelve las entradas en {@code destino} con su motivo saneado; true si resolvió alguna. */
    private static boolean resolverEntradas(List<InfoMetodo> universo, List<SeleccionSpec.Entrada> entradas,
                                            Set<InfoMetodo> destino, Map<InfoMetodo, String> motivos) {
        boolean alguna = false;
        if (entradas == null) {
            return false;
        }
        for (SeleccionSpec.Entrada en : entradas) {
            if (en == null) {
                continue;
            }
            for (InfoMetodo m : resolver(universo, en.nombre)) {
                alguna = true;
                if (destino.add(m)) {
                    String mo = Textos.sanearComentario(en.motivo, MAX_MOTIVO);
                    if (mo != null && !mo.isBlank()) {
                        motivos.put(m, mo);
                    }
                }
            }
        }
        return alguna;
    }

    public static Eleccion validarEleccion(ModuloEscaneado me, InfoClase c, SeleccionSpec propuesta) {
        return validarEleccion(me, c, propuesta, "seleccion");
    }

    /**
     * Valida una elección de origen {@code ia} (por defecto) o {@code usuario} contra las funciones públicas del
     * servicio. {@code ruta} es la ruta JSON de los errores.
     */
    public static Eleccion validarEleccion(ModuloEscaneado me, InfoClase c, SeleccionSpec propuesta, String ruta) {
        List<ErrorValidacion> errores = new ArrayList<>();
        String origen = propuesta == null || propuesta.origen == null ? ORIGEN_IA : propuesta.origen;
        boolean usuario = ORIGEN_USUARIO.equals(origen);
        List<SeleccionSpec.Entrada> fs = propuesta == null || propuesta.funciones == null ? List.of() : propuesta.funciones;
        List<SeleccionSpec.Entrada> os = propuesta == null || propuesta.omitidas == null ? List.of() : propuesta.omitidas;
        if (!ORIGEN_IA.equals(origen) && !usuario) {
            errores.add(ErrorValidacion.error(ruta + ".origen", "SELECCION_INVALIDA",
                    "Origen de selección no admitido: " + origen + ".", "Usa origen \"ia\" (o \"usuario\" si las nombró el usuario)."));
            return new Eleccion(null, errores);
        }
        if (fs.size() > MAX_ENTRADAS || os.size() > MAX_ENTRADAS) {
            errores.add(ErrorValidacion.error(ruta, "SELECCION_INVALIDA",
                    "Demasiadas entradas: máximo " + MAX_ENTRADAS + " en `funciones` y " + MAX_ENTRADAS + " en `omitidas`.",
                    "Envía solo las funciones del servicio, una vez cada una."));
            return new Eleccion(null, errores);
        }
        if (fs.isEmpty()) {
            errores.add(ErrorValidacion.error(ruta + ".funciones", "SELECCION_VACIA", "`funciones` está vacío.",
                    "Indica al menos una función: funciones:[{nombre, motivo}]."));
            return new Eleccion(null, errores);
        }
        Analisis an = analizar(me, c);
        List<InfoMetodo> universo = an.universo;
        List<String> claves = new ArrayList<>();
        universo.forEach(m -> claves.add(m.clave()));
        int n = 0;
        for (InfoMetodo m : universo) {
            if (an.datos.get(m).trivial() == null) {
                n++;
            }
        }
        int cupo = cupo(n, an.pct);
        int minimo = minimo(cupo);
        if (!usuario && n == 0) {
            errores.add(ErrorValidacion.error(ruta, "SIN_FUNCIONES",
                    "`" + c.fqn + "` solo tiene funciones triviales: no hay nada que elegir.",
                    "Solo se prueban con `todas` o si el usuario las nombra."));
            return new Eleccion(null, errores);
        }
        Set<InfoMetodo> elegidas = Collections.newSetFromMap(new IdentityHashMap<>());
        List<InfoMetodo> orden = new ArrayList<>();
        Map<InfoMetodo, String> motivos = new IdentityHashMap<>();
        Set<String> desconocidas = new LinkedHashSet<>();
        Set<String> triviales = new LinkedHashSet<>();
        for (int i = 0; i < fs.size(); i++) {
            SeleccionSpec.Entrada en = fs.get(i);
            String nombre = en == null ? null : en.nombre;
            List<InfoMetodo> ms = resolver(universo, nombre);
            if (ms.isEmpty()) {
                String nn = nombre == null ? "(sin nombre)" : nombre;
                if (desconocidas.add(nn)) {
                    errores.add(ErrorValidacion.error(ruta + ".funciones[" + i + "]", "FUNCION_DESCONOCIDA",
                            "`" + Textos.sanearComentario(nn, MAX_MOTIVO) + "` no es una función pública de `" + c.fqn + "`.",
                            "Funciones válidas: " + LimitesRespuesta.listaCorta(claves, 30)));
                }
                continue;
            }
            String mo = Textos.sanearComentario(en.motivo, MAX_MOTIVO);
            if (!usuario && (mo == null || mo.isBlank())) {
                errores.add(ErrorValidacion.error(ruta + ".funciones[" + i + "].motivo", "FALTA_MOTIVO",
                        "Falta el motivo de `" + ms.get(0).nombre + "`.", "Añade un motivo de una frase: {nombre, motivo}."));
            }
            if (usuario && (mo == null || mo.isBlank())) {
                mo = "pedida por el usuario";
            }
            for (InfoMetodo m : ms) {
                String t = an.datos.get(m).trivial();
                if (!usuario && t != null) {
                    if (triviales.add(m.clave())) {
                        errores.add(ErrorValidacion.error(ruta + ".funciones[" + i + "]", "FUNCION_TRIVIAL",
                                "`" + m.clave() + "` es trivial (" + t + ") y no se puede elegir.",
                                "Quítala: las triviales solo se prueban con `todas` o si el usuario las nombra."));
                    }
                    continue;
                }
                if (elegidas.add(m)) {
                    orden.add(m);
                    if (mo != null && !mo.isBlank()) {
                        motivos.put(m, mo);
                    }
                }
            }
        }
        Map<InfoMetodo, String> omitidas = new LinkedHashMap<>();
        for (SeleccionSpec.Entrada en : os) {
            if (en == null) {
                continue;
            }
            String mo = Textos.sanearComentario(en.motivo, MAX_MOTIVO);
            for (InfoMetodo m : resolver(universo, en.nombre)) {
                if (!elegidas.contains(m) && !omitidas.containsKey(m) && mo != null && !mo.isBlank()) {
                    omitidas.put(m, mo);
                }
            }
        }
        if (!usuario) {
            int e = elegidas.size();
            if (e > cupo) {
                errores.add(ErrorValidacion.error(ruta + ".funciones", "EXCESO_SELECCION",
                        "Has elegido " + e + "; el cupo es " + cupo + " (" + an.pct + " % de " + n + "). Quita " + (e - cupo) + ".",
                        "Deja como máximo " + cupo + " funciones (las sobrecargas cuentan por separado)."));
            } else if (e < minimo) {
                errores.add(ErrorValidacion.error(ruta + ".funciones", "SELECCION_INSUFICIENTE",
                        "Has elegido " + e + "; el mínimo es " + minimo + " (cupo " + cupo + ").",
                        "Elige entre " + minimo + " y " + cupo + " funciones."));
            }
            List<String> sinMotivo = new ArrayList<>();
            for (InfoMetodo m : universo) {
                Datos d = an.datos.get(m);
                if (d.trivial() == null && d.escribeDatos() && !elegidas.contains(m) && !omitidas.containsKey(m)) {
                    sinMotivo.add(m.clave());
                }
            }
            if (!sinMotivo.isEmpty()) {
                errores.add(ErrorValidacion.error(ruta + ".omitidas", "FALTA_MOTIVO_OMISION",
                        "Funciones que escriben datos fuera de la selección sin motivo: " + LimitesRespuesta.listaCorta(sinMotivo, 30) + ".",
                        "Inclúyelas en `funciones` o añade {nombre, motivo} en `omitidas`."));
            }
        }
        if (!errores.isEmpty()) {
            return new Eleccion(null, errores);
        }
        SeleccionSpec s = new SeleccionSpec();
        s.origen = origen;
        for (InfoMetodo m : orden) {
            s.funciones.add(new SeleccionSpec.Entrada(m.clave(), motivos.get(m)));
        }
        if (!omitidas.isEmpty()) {
            s.omitidas = new ArrayList<>();
            omitidas.forEach((m, mo) -> s.omitidas.add(new SeleccionSpec.Entrada(m.clave(), mo)));
        }
        return new Eleccion(s, errores);
    }

    // ------------------------------------------------------------- accesores

    public List<Funcion> funciones() {
        return funciones;
    }

    /** Métodos seleccionados, en orden de fuente. */
    public List<InfoMetodo> seleccionados() {
        List<InfoMetodo> r = new ArrayList<>();
        for (Funcion f : funciones) {
            if (f.seleccionada()) {
                r.add(f.metodo());
            }
        }
        return r;
    }

    /** Funciones públicas omitidas (triviales o no elegidas), en orden de fuente. */
    public List<Funcion> omitidas() {
        List<Funcion> r = new ArrayList<>();
        for (Funcion f : funciones) {
            if (!f.seleccionada()) {
                r.add(f);
            }
        }
        return r;
    }

    /** Función del universo correspondiente al método, o {@code null} si no es pública. */
    public Funcion de(InfoMetodo m) {
        return porMetodo.get(m);
    }

    /** true si hay alguna función seleccionada con ese nombre. */
    public boolean seleccionado(String nombre) {
        for (Funcion f : funciones) {
            if (f.seleccionada() && f.metodo().nombre.equals(nombre)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Máximo de casos para un nombre de método (suma de sus sobrecargas seleccionadas); 0 si ninguna está
     * seleccionada, salvo con {@code todas}, que admite 2 para métodos fuera del universo público.
     */
    public int maxCasos(String nombre) {
        int s = 0;
        for (Funcion f : funciones) {
            if (f.seleccionada() && f.metodo().nombre.equals(nombre)) {
                s += f.maxCasos();
            }
        }
        if (s == 0 && todas) {
            return 2;
        }
        return s;
    }

    public int porcentaje() {
        return porcentaje;
    }

    public boolean todas() {
        return todas;
    }

    /** Origen de la selección: ia, usuario, java o todas. */
    public String origen() {
        return origen;
    }

    /** Número de funciones públicas no triviales (N). */
    public int noTriviales() {
        return (int) funciones.stream().filter(f -> f.datos().trivial() == null).count();
    }

    /** Cupo de la IA: {@code max(1, ceil(N·pct/100))}; 0 sin no triviales. */
    public int cupo() {
        return cupo(noTriviales(), porcentaje);
    }

    /** Mínimo de funciones a elegir: {@code max(1, ceil(cupo/2))}; 0 con cupo 0. */
    public int minimo() {
        return minimo(cupo());
    }

    public long numTriviales() {
        return funciones.stream().filter(f -> !f.seleccionada() && f.datos().trivial() != null).count();
    }

    public long numFueraPorcentaje() {
        return funciones.stream().filter(f -> !f.seleccionada() && f.datos().trivial() == null).count();
    }

    /** Selección usada, para el spec.json: claves con motivo (una entrada por sobrecarga). */
    public SeleccionSpec aSpec() {
        SeleccionSpec s = new SeleccionSpec();
        s.origen = origen;
        List<SeleccionSpec.Entrada> omi = new ArrayList<>();
        for (Funcion f : funciones) {
            if (f.seleccionada()) {
                s.funciones.add(new SeleccionSpec.Entrada(f.metodo().clave(), f.motivoSeleccion()));
            } else {
                omi.add(new SeleccionSpec.Entrada(f.metodo().clave(), f.motivoOmision()));
            }
        }
        s.omitidas = omi.isEmpty() ? null : omi;
        return s;
    }

    // ------------------------------------------------------------- candidatas (render)

    public String cabeceraCandidatas() {
        int n = noTriviales();
        int cupo = cupo();
        return "## Funciones candidatas de `" + clase.fqn + "`\n\n"
                + "Elige por importancia de negocio entre " + minimo() + " y " + cupo + " funciones (cupo = " + porcentaje
                + " % de " + n + " no triviales) y llama a seleccionar_funciones {clase, funciones:[{nombre, motivo}], "
                + "omitidas:[{nombre, motivo}]}. Toda función con `escribe` que dejes fuera necesita motivo en `omitidas`. "
                + "`p` es una puntuación orientativa (complejidad propia + privados que usa). Al elegir recibirás el paquete "
                + "de trabajo (cuerpos, métodos internos, tipos y montaje) solo de las elegidas.\n\n";
    }

    /** Una línea por función no trivial, por puntuación desc y clave. */
    public List<String> lineasCandidatas() {
        List<Funcion> nt = new ArrayList<>();
        for (Funcion f : funciones) {
            if (f.datos().trivial() == null) {
                nt.add(f);
            }
        }
        nt.sort(Comparator.comparingInt((Funcion f) -> -f.puntuacion()).thenComparing(f -> f.metodo().clave()));
        List<String> r = new ArrayList<>();
        for (Funcion f : nt) {
            Datos d = f.datos();
            StringBuilder sb = new StringBuilder("- `").append(f.metodo().clave()).append("` → ")
                    .append(f.metodo().tipoRetorno).append(" | p=").append(f.puntuacion());
            if (d.escribeDatos()) {
                sb.append(" | escribe");
            }
            if (d.transaccional()) {
                sb.append(" | tx");
            }
            if (!d.excepciones().isEmpty()) {
                sb.append(" | lanza: ").append(acotada(d.excepciones(), MAX_LANZA));
            }
            if (!d.privados().isEmpty()) {
                sb.append(" | privados: ").append(acotada(d.privados(), MAX_PRIVADOS));
            }
            if (d.tests() > 0) {
                sb.append(" | tests: ").append(d.tests());
            }
            r.add(sb.append('\n').toString());
        }
        return r;
    }

    private static String acotada(List<String> l, int max) {
        if (l.size() <= max) {
            return String.join(", ", l);
        }
        return String.join(", ", l.subList(0, max)) + " +" + (l.size() - max);
    }

    /** Triviales excluidas (máx. 10 + «+N»); vacío si no hay. */
    public String pieCandidatas() {
        List<String> t = new ArrayList<>();
        for (Funcion f : funciones) {
            if (f.datos().trivial() != null) {
                t.add("`" + f.metodo().clave() + "` (" + f.datos().trivial() + ")");
            }
        }
        if (t.isEmpty()) {
            return "";
        }
        return "\nTriviales excluidas (" + t.size() + "): " + acotada(t, MAX_TRIVIALES_PIE) + ".\n";
    }

    // ------------------------------------------------------------- informe

    /** Bloque markdown del informe en lenguaje llano: frase de resumen + tabla de funciones con si se prueban y por qué. */
    public String resumenInforme() {
        StringBuilder sb = new StringBuilder("**`").append(Textos.nombreSimple(clase.fqn)).append("`**: ");
        if (todas) {
            sb.append("se prueban todas sus funciones (").append(funciones.size()).append(").");
        } else {
            String quien = switch (origen) {
                case ORIGEN_IA -> "Las ha elegido la IA";
                case ORIGEN_USUARIO -> "Las ha elegido el usuario";
                case ORIGEN_JAVA -> "Las ha elegido junit-gen automáticamente";
                default -> "Origen de la elección: " + origen;
            };
            sb.append("se prueban ").append(seleccionados().size()).append(" de sus ").append(funciones.size()).append(" funciones. ")
                    .append(quien).append(". El límite era ").append(cupo()).append(" (el ").append(porcentaje).append(" % de las ")
                    .append(noTriviales()).append(" funciones con lógica que probar).");
        }
        boolean otroOrigen = false;
        for (Funcion f : funciones) {
            if (!origen.equals(origenFila.getOrDefault(f.metodo(), origen))) {
                otroOrigen = true;
                break;
            }
        }
        sb.append("\n\n| Función | ¿Se prueba? | Por qué |");
        sb.append(otroOrigen ? " Quién lo decidió |\n|---|---|---|---|\n" : "\n|---|---|---|\n");
        for (Funcion f : funciones) {
            sb.append("| `").append(EscritorInforme.celda(f.metodo().clave())).append("` | ")
                    .append(f.seleccionada() ? "Sí" : "No").append(" | ")
                    .append(EscritorInforme.celda(f.seleccionada() ? f.motivoSeleccion() : f.motivoOmision())).append(" |");
            if (otroOrigen) {
                sb.append(' ').append(EscritorInforme.celda(TextosInforme.origenSeleccion(origenFila.getOrDefault(f.metodo(), origen))))
                        .append(" |");
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /** Compatibilidad: igual que {@link #resumenInforme()}. */
    public String resumenOmitidas() {
        return resumenInforme();
    }
}
