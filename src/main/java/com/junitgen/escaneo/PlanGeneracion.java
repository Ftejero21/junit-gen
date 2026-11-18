package com.junitgen.escaneo;

import com.junitgen.config.ConfigProyecto;
import com.junitgen.generacion.AlmacenSpec;
import com.junitgen.spec.CasoSpec;
import com.junitgen.spec.SeleccionSpec;
import com.junitgen.spec.ServicioSpec;
import com.junitgen.spec.Spec;
import com.junitgen.util.LimitesRespuesta;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Plan determinista de N servicios (N = número de servicios, nunca de tests). Candidatos: los servicios
 * detectados en todos los módulos (o en {@code modulo}), o los servicios nombrados y resueltos. Se
 * descartan, con aviso, los que requieren refactor y los que no tienen funciones seleccionables (solo
 * triviales). De cada servicio se planifican sus funciones SELECCIONADAS ({@link SeleccionFunciones}: las
 * elegidas por la IA con seleccionar_funciones o por el usuario; sin elección, el top
 * {@code porcentajeFunciones} % por puntuación transitiva; con {@code todas}, el 100 %),
 * incluidas las que ya tienen tests (en el proyecto o en el spec.json de la salida): en esas solo
 * escenarios nuevos. Casos por método (ramas transitivas): {@code base = 1 + min(ramas, 2)}; sin tests → base; con tests →
 * {@code max(1, base − existentes)}, acotado por el máximo restante de la función ({@code maxCasos} menos
 * los casos ya en la salida, repartido entre sobrecargas); las funciones en su máximo no se planifican.
 * Orden de servicios ({@link ConfigProyecto.OrdenServicios}, clave {@code orden} de junit-gen.yml):
 * {@code alfabetico} (por defecto en MCP) = nombre simple de la clase sin distinguir mayúsculas → nombre simple
 * exacto → FQN → módulo; {@code prioridad} = métodos sin tests desc → suma de ramas desc → tests existentes del
 * servicio asc → modo de montaje asc → FQN → módulo (las sobrecargas sin orden usan prioridad). Se toman los N
 * primeros (sin N, todos los nombrados). Dentro de cada servicio: sin tests primero → ramas desc → clave del método.
 */
public final class PlanGeneracion {

    public static final int MIN_SERVICIOS = 1;
    public static final int MAX_SERVICIOS = 200;
    /** Ramas que suman casos como máximo: base = 1 + min(ramas, MAX_RAMAS_CASOS). */
    static final int MAX_RAMAS_CASOS = 2;
    private static final String ELIMINADO = "eliminado";

    /** Método planificado de un servicio. */
    public static final class Objetivo {
        public final ModuloEscaneado modulo;
        public final InfoClase servicio;
        public final InfoMetodo metodo;
        public final int ramas;
        /** Tests existentes del método: casos del proyecto + casos no eliminados del spec.json de la salida. */
        public final int existentes;
        public final boolean conTests;
        public final int casos;
        /** Puntuación de complejidad ({@link SeleccionFunciones}). */
        public final int puntuacion;
        /** Máximo de casos de la función por complejidad (2..5). */
        public final int maxCasos;

        Objetivo(ModuloEscaneado modulo, InfoClase servicio, InfoMetodo metodo, int existentes) {
            this(modulo, servicio, metodo, existentes, 0, SeleccionFunciones.maxCasosPorPuntuacion(0),
                    casos(metodo.ramas(), existentes));
        }

        Objetivo(ModuloEscaneado modulo, InfoClase servicio, InfoMetodo metodo, int existentes, int puntuacion,
                 int maxCasos, int casos) {
            this(modulo, servicio, metodo, metodo.ramas(), existentes, puntuacion, maxCasos, casos);
        }

        /** Con {@code ramas} explícitas (transitivas: las del método más las de los internos que usa). */
        Objetivo(ModuloEscaneado modulo, InfoClase servicio, InfoMetodo metodo, int ramas, int existentes, int puntuacion,
                 int maxCasos, int casos) {
            this.modulo = modulo;
            this.servicio = servicio;
            this.metodo = metodo;
            this.ramas = ramas;
            this.existentes = existentes;
            this.conTests = existentes > 0;
            this.casos = casos;
            this.puntuacion = puntuacion;
            this.maxCasos = maxCasos;
        }

        /** {@code base = 1 + min(ramas, 2)}; sin tests → base; con tests → max(1, base − existentes). */
        static int casos(int ramas, int existentes) {
            int base = 1 + Math.min(ramas, MAX_RAMAS_CASOS);
            return existentes <= 0 ? base : Math.max(1, base - existentes);
        }
    }

    /** Servicio planificado con sus funciones seleccionadas y las métricas de prioridad. */
    public static final class ServicioPlan {
        public final ModuloEscaneado modulo;
        public final InfoClase clase;
        public final List<Objetivo> objetivos;
        public final int sinTests;
        public final int ramas;
        public final int tests;
        public final Dependencia.ModoMontaje modo;
        /** Funciones públicas del servicio (universo de la selección). */
        public final int funcionesTotales;
        public final int omitidasTriviales;
        public final int omitidasPorcentaje;
        /** Origen de la selección de funciones: ia, usuario, java (pendiente de elegir) o todas. */
        public final String origenSeleccion;

        ServicioPlan(ModuloEscaneado modulo, InfoClase clase, List<Objetivo> objetivos, int tests, Dependencia.ModoMontaje modo) {
            this(modulo, clase, objetivos, tests, modo, objetivos.size(), 0, 0);
        }

        ServicioPlan(ModuloEscaneado modulo, InfoClase clase, List<Objetivo> objetivos, int tests, Dependencia.ModoMontaje modo,
                     int funcionesTotales, int omitidasTriviales, int omitidasPorcentaje) {
            this(modulo, clase, objetivos, tests, modo, funcionesTotales, omitidasTriviales, omitidasPorcentaje,
                    SeleccionFunciones.ORIGEN_JAVA);
        }

        ServicioPlan(ModuloEscaneado modulo, InfoClase clase, List<Objetivo> objetivos, int tests, Dependencia.ModoMontaje modo,
                     int funcionesTotales, int omitidasTriviales, int omitidasPorcentaje, String origenSeleccion) {
            this.origenSeleccion = origenSeleccion == null ? SeleccionFunciones.ORIGEN_JAVA : origenSeleccion;
            this.modulo = modulo;
            this.clase = clase;
            this.objetivos = objetivos;
            this.sinTests = (int) objetivos.stream().filter(o -> !o.conTests).count();
            this.ramas = objetivos.stream().mapToInt(o -> o.ramas).sum();
            this.tests = tests;
            this.modo = modo;
            this.funcionesTotales = funcionesTotales;
            this.omitidasTriviales = omitidasTriviales;
            this.omitidasPorcentaje = omitidasPorcentaje;
        }

        public int casos() {
            return objetivos.stream().mapToInt(o -> o.casos).sum();
        }

        public String motivo() {
            return sinTests + " de " + objetivos.size() + (objetivos.size() == 1 ? " método" : " métodos") + " sin tests; "
                    + ramas + (ramas == 1 ? " rama" : " ramas") + "; " + tests + (tests == 1 ? " test" : " tests") + "; montaje " + modo;
        }
    }

    private final Integer pedidos;
    private final List<ServicioPlan> servicios = new ArrayList<>();
    private final List<String> avisos = new ArrayList<>();
    private boolean todas;
    private boolean modoTodos;
    private int porcentaje = 60;
    private ConfigProyecto.OrdenServicios orden = ConfigProyecto.OrdenServicios.PRIORIDAD;

    private PlanGeneracion(Integer pedidos) {
        this.pedidos = pedidos;
    }

    /** true si el plan se calculó en modo {@code todas} (100 % de las funciones). */
    public boolean todas() {
        return todas;
    }

    /** Orden con el que se eligieron y ordenaron los servicios del plan. */
    public ConfigProyecto.OrdenServicios orden() {
        return orden;
    }

    /** Línea corta del pie que indica el orden de los servicios. */
    String lineaOrden() {
        if (modoTodos) {
            return "Orden: modo «todos» (primero los servicios sin tests existentes, después los de más lógica).";
        }
        return orden == ConfigProyecto.OrdenServicios.ALFABETICO
                ? "Orden: alfabético por nombre de clase."
                : "Orden: prioridad (junit-gen.yml).";
    }

    /** Pie con el resumen de la selección de funciones. */
    String pieSeleccion() {
        if (todas) {
            return "Modo `todas`: 100 % de las funciones.";
        }
        int total = servicios.stream().mapToInt(s -> s.funcionesTotales).sum();
        int triv = servicios.stream().mapToInt(s -> s.omitidasTriviales).sum();
        int fuera = servicios.stream().mapToInt(s -> s.omitidasPorcentaje).sum();
        int sel = total - triv - fuera;
        String r = "Funciones seleccionadas: " + sel + " de " + total + " (cupo " + porcentaje + " %; omitidas: " + triv
                + " triviales, " + fuera + " no elegidas; con `todas` se incluyen).";
        if (hayPendientes()) {
            r += "\nSelección provisional de junit-gen (por puntuación, top " + porcentaje
                    + " %): las cifras se recalculan al llamar a seleccionar_funciones por servicio.";
        }
        return r;
    }

    /** true si el servicio aún no tiene selección de la IA ni del usuario (y no es modo todas). */
    public boolean pendiente(ServicioPlan s) {
        return !todas && SeleccionFunciones.ORIGEN_JAVA.equals(s.origenSeleccion);
    }

    /** true si algún servicio del plan está pendiente de seleccionar funciones. */
    public boolean hayPendientes() {
        return servicios.stream().anyMatch(this::pendiente);
    }

    /** Texto de la columna «Selección» del plan. */
    String etiquetaSeleccion(ServicioPlan s) {
        if (todas || SeleccionFunciones.ORIGEN_TODAS.equals(s.origenSeleccion)) {
            return "todas";
        }
        return switch (s.origenSeleccion) {
            case SeleccionFunciones.ORIGEN_IA -> "IA (guardada)";
            case SeleccionFunciones.ORIGEN_USUARIO -> "usuario";
            default -> "pendiente";
        };
    }

    /** Número de servicios pedido (null si solo se nombraron servicios). */
    public Integer pedidos() {
        return pedidos;
    }

    public List<ServicioPlan> servicios() {
        return servicios;
    }

    /** Todos los métodos planificados, en el orden del plan. */
    public List<Objetivo> objetivos() {
        List<Objetivo> r = new ArrayList<>();
        servicios.forEach(s -> r.addAll(s.objetivos));
        return r;
    }

    public List<String> avisos() {
        return avisos;
    }

    /** Casos totales del plan. */
    public int total() {
        return servicios.stream().mapToInt(ServicioPlan::casos).sum();
    }

    public int totalMetodos() {
        return servicios.stream().mapToInt(s -> s.objetivos.size()).sum();
    }

    public Set<String> modulosRel() {
        Set<String> r = new LinkedHashSet<>();
        servicios.forEach(s -> r.add(s.modulo.modulo.rel));
        return r;
    }

    public Set<String> fqns() {
        Set<String> r = new LinkedHashSet<>();
        servicios.forEach(s -> r.add(s.clase.fqn));
        return r;
    }

    /**
     * Calcula el plan. {@code numServicios} (null solo con {@code fqnsLimitados}) = número de servicios;
     * {@code salida} (opcional) aporta los casos ya generados y el spec.json acumulado; {@code fqnsLimitados}
     * (null = sin límite) limita a esas clases (servicios pedidos y resueltos).
     */
    public static PlanGeneracion calcular(ProyectoEscaneado p, Path salida, Integer numServicios, String modulo,
                                          Collection<String> fqnsLimitados) {
        return calcular(p, salida, numServicios, modulo, fqnsLimitados, false);
    }

    /**
     * Como {@link #calcular(ProyectoEscaneado, Path, Integer, String, Collection)}; con {@code todas} se
     * planifican el 100 % de las funciones públicas (incluidas triviales) en lugar de la selección.
     */
    public static PlanGeneracion calcular(ProyectoEscaneado p, Path salida, Integer numServicios, String modulo,
                                          Collection<String> fqnsLimitados, boolean todas) {
        return calcular(p, salida, numServicios, modulo, fqnsLimitados, todas, Map.of());
    }

    /**
     * Como {@link #calcular(ProyectoEscaneado, Path, Integer, String, Collection, boolean)}; la selección de
     * cada servicio es la de la sesión ({@code seleccionesSesion}, FQN → selección ia/usuario), si no la de
     * origen ia del spec.json de la salida y, si no, la automática de junit-gen (origen java, «pendiente»).
     */
    public static PlanGeneracion calcular(ProyectoEscaneado p, Path salida, Integer numServicios, String modulo,
                                          Collection<String> fqnsLimitados, boolean todas,
                                          Map<String, SeleccionSpec> seleccionesSesion) {
        return calcular(p, salida, numServicios, modulo, fqnsLimitados, todas, seleccionesSesion,
                ConfigProyecto.OrdenServicios.PRIORIDAD);
    }

    /**
     * Como {@link #calcular(ProyectoEscaneado, Path, Integer, String, Collection, boolean, Map)} con el
     * {@code orden} de servicios indicado (null = alfabético): alfabético por nombre simple de la clase (sin
     * distinguir mayúsculas; desempate por nombre simple exacto, FQN y módulo) o por prioridad.
     */
    public static PlanGeneracion calcular(ProyectoEscaneado p, Path salida, Integer numServicios, String modulo,
                                          Collection<String> fqnsLimitados, boolean todas,
                                          Map<String, SeleccionSpec> seleccionesSesion,
                                          ConfigProyecto.OrdenServicios orden) {
        if (numServicios == null && (fqnsLimitados == null || fqnsLimitados.isEmpty())) {
            throw new IllegalArgumentException("Indica numServicios (" + MIN_SERVICIOS + ".." + MAX_SERVICIOS + ") o los servicios.");
        }
        if (numServicios != null && (numServicios < MIN_SERVICIOS || numServicios > MAX_SERVICIOS)) {
            throw new IllegalArgumentException("numServicios debe estar entre " + MIN_SERVICIOS + " y " + MAX_SERVICIOS + ".");
        }
        return calcularInterno(p, salida, numServicios, modulo, fqnsLimitados, todas, seleccionesSesion, orden, false);
    }

    /**
     * Plan del modo «todos los servicios»: todos los candidatos (o los {@code maximo} primeros) sin el tope
     * {@link #MAX_SERVICIOS}. Orden: servicios sin tests existentes primero, después los de más lógica (ramas).
     * Sin aviso «solo hay X» cuando hay menos candidatos que {@code maximo}.
     */
    public static PlanGeneracion calcularTodos(ProyectoEscaneado p, Path salida, String modulo, boolean todas,
                                               Map<String, SeleccionSpec> selecciones, Integer maximo) {
        if (maximo != null && maximo < MIN_SERVICIOS) {
            throw new IllegalArgumentException("El máximo de servicios debe ser al menos " + MIN_SERVICIOS + ".");
        }
        return calcularInterno(p, salida, maximo, modulo, null, todas, selecciones, ConfigProyecto.OrdenServicios.PRIORIDAD, true);
    }

    private static PlanGeneracion calcularInterno(ProyectoEscaneado p, Path salida, Integer numServicios, String modulo,
                                                  Collection<String> fqnsLimitados, boolean todas,
                                                  Map<String, SeleccionSpec> seleccionesSesion,
                                                  ConfigProyecto.OrdenServicios orden, boolean modoTodos) {
        PlanGeneracion plan = new PlanGeneracion(numServicios);
        plan.todas = todas;
        plan.modoTodos = modoTodos;
        plan.orden = orden == null ? ConfigProyecto.OrdenServicios.ALFABETICO : orden;
        Spec previo = leerSpec(salida, plan.avisos);
        Map<String, Map<String, Integer>> enSpec = metodosEnSpec(previo);
        Map<String, SeleccionSpec> delSpecJson = AlmacenSpec.selecciones(previo);
        List<ServicioPlan> candidatos = new ArrayList<>();
        List<String> refactor = new ArrayList<>();
        List<String> sinElegibles = new ArrayList<>();
        List<String> enMaximo = new ArrayList<>();
        boolean porcentajeFijado = false;
        for (ModuloEscaneado me : p.modulos) {
            if (modulo != null && !modulo.isBlank() && me != p.modulo(modulo)) {
                continue;
            }
            for (InfoClase c : clases(me, fqnsLimitados)) {
                AnalisisServicio a = me.analisis(c);
                if (a.requiereRefactor()) {
                    refactor.add(c.fqn);
                    continue;
                }
                EmparejadorTests.Emparejamiento e = me.emparejamiento(c);
                SeleccionFunciones sel = SeleccionFunciones.de(me, c, todas,
                        todas ? null : SeleccionFunciones.guardada(seleccionesSesion, delSpecJson, c.fqn));
                if (!porcentajeFijado) {
                    plan.porcentaje = sel.porcentaje();
                    porcentajeFijado = true;
                }
                if (sel.seleccionados().isEmpty()) {
                    sinElegibles.add(c.fqn);
                    continue;
                }
                List<Objetivo> objetivos = objetivosDe(me, c, e, enSpec.getOrDefault(c.fqn, Map.of()), sel);
                if (objetivos.isEmpty()) {
                    enMaximo.add(c.fqn);
                    continue;
                }
                int tests = e.casos.size() + (salida == null ? 0 : InformeEscaneo.generadosEnSalida(salida, me, c));
                candidatos.add(new ServicioPlan(me, c, objetivos, tests, a.modo == null ? Dependencia.ModoMontaje.EXPLICITO : a.modo,
                        sel.funciones().size(), (int) sel.numTriviales(), (int) sel.numFueraPorcentaje(), sel.origen()));
            }
        }
        candidatos.sort(modoTodos ? COMPARADOR_TODOS : comparador(plan.orden));
        int tomar = numServicios == null ? candidatos.size() : Math.min(numServicios, candidatos.size());
        plan.servicios.addAll(candidatos.subList(0, tomar));
        if (!refactor.isEmpty()) {
            plan.avisos.add("Descartados por requerir refactor: " + String.join(", ", refactor) + ".");
        }
        if (!sinElegibles.isEmpty()) {
            plan.avisos.add("Descartados por no tener funciones seleccionables (solo triviales; con `todas` se incluyen): "
                    + String.join(", ", sinElegibles) + ".");
        }
        if (!enMaximo.isEmpty()) {
            plan.avisos.add("Sin casos previstos porque sus funciones seleccionadas ya tienen el máximo de casos: "
                    + String.join(", ", enMaximo) + " (para ampliar: `funciones=…` o `todas`).");
        }
        if (candidatos.isEmpty()) {
            plan.avisos.add("No hay servicios con métodos elegibles: el plan está vacío.");
        } else if (!modoTodos && numServicios != null && candidatos.size() < numServicios) {
            plan.avisos.add("Solo hay " + candidatos.size() + " servicios disponibles de los " + numServicios + " pedidos.");
        }
        return plan;
    }

    /** Orden del modo «todos»: sin tests existentes primero → más ramas → más métodos sin tests → FQN → módulo. */
    private static final Comparator<ServicioPlan> COMPARADOR_TODOS = Comparator.<ServicioPlan, Boolean>comparing(s -> s.tests > 0)
            .thenComparingInt(s -> -s.ramas)
            .thenComparingInt(s -> -s.objetivos.size())
            .thenComparingInt(s -> -s.sinTests)
            .thenComparing(s -> s.clase.fqn)
            .thenComparing(s -> s.modulo.modulo.rel);

    /** Comparador de servicios para el orden indicado. */
    private static Comparator<ServicioPlan> comparador(ConfigProyecto.OrdenServicios orden) {
        if (orden == ConfigProyecto.OrdenServicios.PRIORIDAD) {
            return Comparator.<ServicioPlan>comparingInt(s -> -s.sinTests)
                    .thenComparingInt(s -> -s.ramas)
                    .thenComparingInt(s -> s.tests)
                    .thenComparingInt(s -> s.modo.ordinal())
                    .thenComparing(s -> s.clase.fqn)
                    .thenComparing(s -> s.modulo.modulo.rel);
        }
        return Comparator.<ServicioPlan, String>comparing(s -> s.clase.simple, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(s -> s.clase.simple)
                .thenComparing(s -> s.clase.fqn)
                .thenComparing(s -> s.modulo.modulo.rel);
    }

    /**
     * Métodos planificados de un servicio con la selección dada: casos por método con las ramas transitivas,
     * acotados por el máximo restante de cada función (menos los casos ya en el spec.json de {@code salida}).
     */
    public static List<Objetivo> objetivosDe(ModuloEscaneado me, InfoClase c, Path salida, SeleccionFunciones sel) {
        List<String> avisos = new ArrayList<>();
        Map<String, Integer> delSpec = metodosEnSpec(leerSpec(salida, avisos)).getOrDefault(c.fqn, Map.of());
        avisos.forEach(System.err::println);
        return objetivosDe(me, c, me.emparejamiento(c), delSpec, sel);
    }

    private static List<Objetivo> objetivosDe(ModuloEscaneado me, InfoClase c, EmparejadorTests.Emparejamiento e,
                                              Map<String, Integer> delSpec, SeleccionFunciones sel) {
        Map<String, Integer> restante = new HashMap<>();
        List<Objetivo> objetivos = new ArrayList<>();
        for (InfoMetodo m : sel.seleccionados()) {
            if (m.abstracto) {
                continue;
            }
            int enSalida = delSpec.getOrDefault(m.nombre, 0);
            int resto = restante.computeIfAbsent(m.nombre, k -> Math.max(0, sel.maxCasos(k) - enSalida));
            int existentes = e.casosDe(m.nombre, m.aridad()).size() + enSalida;
            SeleccionFunciones.Funcion f = sel.de(m);
            int ramas = f == null ? m.ramas() : f.datos().ramas();
            int casos = Math.min(Objetivo.casos(ramas, existentes), resto);
            if (casos <= 0) {
                continue;
            }
            restante.put(m.nombre, resto - casos);
            objetivos.add(new Objetivo(me, c, m, ramas, existentes, f == null ? 0 : f.puntuacion(),
                    f == null ? 2 : f.maxCasos(), casos));
        }
        objetivos.sort(Comparator.<Objetivo, Boolean>comparing(o -> o.conTests)
                .thenComparingInt(o -> -o.ramas)
                .thenComparing(o -> o.metodo.clave()));
        return objetivos;
    }

    private static List<InfoClase> clases(ModuloEscaneado me, Collection<String> fqnsLimitados) {
        List<InfoClase> r = new ArrayList<>();
        if (fqnsLimitados == null) {
            me.deteccion.servicios.forEach(c -> r.add(c.clase));
            return r;
        }
        for (String fqn : fqnsLimitados) {
            InfoClase c = me.indice.get(fqn);
            if (c != null && !c.esTest && c.tipo != InfoClase.Tipo.INTERFAZ) {
                r.add(c);
            }
        }
        return r;
    }

    /** spec.json de la salida; null si no hay salida o spec. Corrupto → aviso y null. */
    private static Spec leerSpec(Path salida, List<String> avisos) {
        if (salida == null) {
            return null;
        }
        try {
            return AlmacenSpec.leer(salida);
        } catch (RuntimeException e) {
            avisos.add("Se ignora el spec.json de la salida: " + e.getMessage());
            return null;
        }
    }

    /** Casos no eliminados del spec por FQN y nombre de método. */
    private static Map<String, Map<String, Integer>> metodosEnSpec(Spec spec) {
        Map<String, Map<String, Integer>> r = new HashMap<>();
        if (spec == null || spec.servicios == null) {
            return r;
        }
        for (ServicioSpec sv : spec.servicios) {
            if (sv == null || sv.clase == null || sv.casos == null) {
                continue;
            }
            for (CasoSpec c : sv.casos) {
                if (c != null && c.metodo != null && !ELIMINADO.equals(c.estado)) {
                    r.computeIfAbsent(sv.clase, k -> new HashMap<>()).merge(c.metodo, 1, Integer::sum);
                }
            }
        }
        return r;
    }

    private static String celda(String s) {
        return s == null ? "" : s.replace("|", "\\|").replace("\n", " ");
    }

    /**
     * Plan compacto para el resumen de escanear_proyecto: una fila por servicio (sin lista de métodos), las
     * que quepan en {@code maxBytes}, más «… y K servicios más», la línea del total y los avisos recortados.
     * El total y los avisos nunca se omiten; las filas se rellenan al final con el presupuesto restante.
     */
    public String markdownCompacto(int maxBytes) {
        StringBuilder avisosMd = new StringBuilder();
        for (String a : avisos) {
            avisosMd.append("\n> Aviso: ").append(LimitesRespuesta.recortar(a, LimitesRespuesta.MAX_AVISO_PLAN)).append('\n');
        }
        if (servicios.isEmpty()) {
            return "## Plan de generación\n\nPlan vacío: no hay servicios con métodos elegibles" + (pedidos != null ? " para los " + pedidos
                    + (pedidos == 1 ? " servicio pedido" : " servicios pedidos") : "") + ".\n" + avisosMd;
        }
        String cab = "## Plan de generación\n\n| # | Servicio | Módulo | Métodos | Casos | Sin tests | Selección |\n|---|---|---|---|---|---|---|\n";
        int n = servicios.size();
        int m = totalMetodos();
        int c = total();
        String pie = "\n**Total: " + n + (n == 1 ? " servicio, " : " servicios, ") + m + (m == 1 ? " método, " : " métodos, ") + c
                + (c == 1 ? " caso**" : " casos**") + (pedidos != null ? " (servicios pedidos: " + pedidos + ")" : "")
                + ". Los casos por método llegan en `casosPlan` de detalle_servicio.\n" + pieSeleccion() + "\n" + lineaOrden() + "\n" + avisosMd;
        String mas = "\n… y " + n + " servicios más: escanear_proyecto {seccion:\"plan\", pagina:2}\n";
        int libre = maxBytes - LimitesRespuesta.bytes(cab) - LimitesRespuesta.bytes(pie);
        StringBuilder filas = new StringBuilder();
        int usados = 0;
        int i = 0;
        for (ServicioPlan s : servicios) {
            String fila = "| " + (i + 1) + " | " + celda(s.clase.fqn) + " | " + celda(s.modulo.modulo.nombre()) + " | "
                    + s.objetivos.size() + " | " + s.casos() + " | " + s.sinTests + " | " + etiquetaSeleccion(s) + " |\n";
            int b = LimitesRespuesta.bytes(fila);
            boolean ultima = i == n - 1;
            if (usados + b + (ultima ? 0 : LimitesRespuesta.bytes(mas)) > libre) {
                break;
            }
            filas.append(fila);
            usados += b;
            i++;
        }
        StringBuilder sb = new StringBuilder(cab).append(filas);
        if (i < n) {
            sb.append("\n… y ").append(n - i).append(n - i == 1 ? " servicio más" : " servicios más")
                    .append(": escanear_proyecto {seccion:\"plan\", pagina:2}\n");
        }
        return sb.append(pie).toString();
    }

    public String markdown() {
        StringBuilder sb = new StringBuilder("## Plan de generación\n\n");
        if (servicios.isEmpty()) {
            sb.append("Plan vacío: no hay servicios con métodos elegibles").append(pedidos != null ? " para los " + pedidos
                    + (pedidos == 1 ? " servicio pedido" : " servicios pedidos") : "").append(".\n");
        } else {
            sb.append("| # | Servicio | Módulo | Métodos (casos) | Casos | Motivo |\n|---|---|---|---|---|---|\n");
            int i = 1;
            boolean hayConTests = false;
            for (ServicioPlan s : servicios) {
                List<String> metodos = new ArrayList<>();
                for (Objetivo o : s.objetivos) {
                    metodos.add(o.metodo.clave() + (o.conTests ? "*" : "") + " ×" + o.casos);
                    hayConTests |= o.conTests;
                }
                sb.append("| ").append(i++).append(" | ").append(celda(s.clase.fqn)).append(" | ")
                        .append(celda(s.modulo.modulo.nombre())).append(" | ").append(celda(String.join(", ", metodos))).append(" | ")
                        .append(s.casos()).append(" | ").append(celda(s.motivo())).append(" |\n");
            }
            if (hayConTests) {
                sb.append("\n\\* ya tiene tests: solo escenarios nuevos.\n");
            }
            int n = servicios.size();
            int m = totalMetodos();
            int c = total();
            sb.append("\n**Total: ").append(n).append(n == 1 ? " servicio, " : " servicios, ").append(m).append(m == 1 ? " método, " : " métodos, ")
                    .append(c).append(c == 1 ? " caso**" : " casos**");
            if (pedidos != null) {
                sb.append(" (servicios pedidos: ").append(pedidos).append(')');
            }
            sb.append(".\n").append(pieSeleccion()).append('\n').append(lineaOrden()).append('\n');
        }
        for (String a : avisos) {
            sb.append("\n> Aviso: ").append(a).append('\n');
        }
        return sb.toString();
    }
}
