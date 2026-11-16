package com.junitgen.escaneo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.junitgen.generacion.AlmacenSpec;
import com.junitgen.spec.CompatibilidadJava;
import com.junitgen.spec.SeleccionSpec;
import com.junitgen.spec.Spec;
import com.junitgen.util.Json;
import com.junitgen.util.LimitesRespuesta;
import com.junitgen.util.Textos;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Paquete de trabajo de un servicio para la IA: todo lo necesario para escribir el spec de las funciones elegidas
 * (firma y cuerpo compacto, métodos de la misma clase que llaman, stubs típicos, tipos del proyecto que usan, helpers
 * de otras clases del proyecto y montaje ya resuelto) en como máximo {@link #MAX_PAGINAS} respuestas. Se construye
 * con los mismos nodos que {@link DetalleServicio}; lo que no cabe se declara ({@code omitido}, {@code cuerpoRecortado},
 * {@code ampliar}) y nunca se pierde en silencio. La paginación es un cursor determinista de bloques ({@code desde}).
 */
public final class PaqueteTrabajo {

    public static final int MAX_PAGINAS = 2;
    /** Presupuesto mínimo de la página 2 para que merezca la pena ofrecerla. */
    public static final int MIN_PAGINA = 1500;
    /** Profundidad máxima de los métodos de la clase llamados desde las funciones elegidas. */
    public static final int MAX_PROFUNDIDAD = 3;
    public static final int MAX_PARTES_ELEGIDA = 3;
    public static final int MAX_PARTES_INTERNO = 2;
    public static final int MAX_HELPERS = 8;
    public static final int MAX_CUERPO_HELPER = 700;
    public static final int MAX_TIPOS = 12;
    public static final int MAX_CAMPOS_TIPO = 20;
    public static final int MAX_ANIDADOS = 4;
    public static final int MAX_CAMPOS_ANIDADO = 12;
    public static final int MAX_NOTA = 240;
    public static final int MAX_OMITIDOS = 10;
    /** Bytes reservados para el pie (omitido, ampliar, paginacion) y las claves de los arrays. */
    public static final int RESERVA_PIE = 500;
    /** Máximo de la firma del constructor en el montaje. */
    static final int MAX_CONSTRUCTOR = 300;
    /** Nivel de recorte de {@link DetalleServicio#montajeConstructor} (cuerpo del constructor recortado). */
    static final int NIVEL_MONTAJE = 3;
    static final String ANOTACION_PROPIEDADES = "ConfigurationProperties";
    static final String SIN_RESTRICCIONES = "Sin restricciones";
    static final String CONSTRUCTOR_POR_DEFECTO = "(por defecto, sin parámetros)";

    public static final String AMPLIAR = "Lo omitido o recortado no suele hacer falta. Solo si un caso lo necesita: método completo -> "
            + "detalle_servicio {clase, metodos:[nombre]}; tipo completo -> detalle_servicio {clase, tipos:[fqn]}.";

    static final String INSTRUCCION_PAGINA_2 = "Falta la página 2 del paquete: llama una vez a detalle_servicio con "
            + "paginacion.siguiente y después a generar_tests.";
    static final String INSTRUCCION_COMPLETO = "Paquete completo: escribe el spec y llama a generar_tests. No llames a detalle_servicio.";
    static final String INSTRUCCION_ELEGIDAS = "Quedan funciones elegidas sin enviar (omitido.metodos): llama a detalle_servicio con "
            + "paginacion.siguiente.";
    static final String INSTRUCCION_OMITIDO = "Paquete enviado; lo que no cupo está en omitido (ver ampliar): escribe el spec y llama "
            + "a generar_tests. No llames a detalle_servicio salvo que un caso concreto lo necesite.";

    static final String METODOS = "metodos";
    static final String INTERNOS = "metodosInternos";
    static final String STUBS = "stubsTipicos";
    static final String TIPOS = "tiposAuxiliares";
    static final String HELPERS = "helpersExternos";
    /** Claves de los bloques en el orden de prioridad de llenado. */
    static final List<String> CLAVES_BLOQUES = List.of(METODOS, INTERNOS, STUBS, TIPOS, HELPERS);

    static final String OM_METODOS = "metodos";
    static final String OM_INTERNOS = "internos";
    static final String OM_TIPOS = "tipos";
    static final String OM_HELPERS = "helpers";
    static final String OM_STUBS = "stubsTipicos";
    static final List<String> CLAVES_OMITIDO = List.of(OM_METODOS, OM_INTERNOS, OM_TIPOS, OM_HELPERS, OM_STUBS);

    /** Orden de recorte de la red final (de menos a más importante). */
    static final List<String> ORDEN_RECORTE = List.of(HELPERS, TIPOS, STUBS, "testsEmparejados", "restriccionesJava", "recetas",
            "montaje.cuerpoConstructor", INTERNOS, "dependencias", "mocks");

    private PaqueteTrabajo() {
    }

    /** Opciones del paquete de trabajo (MCP). */
    public static class Opciones {
        /** Limitar a estos métodos (nombre o clave); vacío = funciones seleccionadas. */
        public List<String> metodos = new ArrayList<>();
        /** Página del paquete (1 o 2). */
        public int pagina = 1;
        /** Cursor (1-based) de bloques de la página 2; 0 = sin cursor (se recalcula con el presupuesto nominal). */
        public int desde;
        /** Presupuesto de esta respuesta (bytes del JSON compacto). */
        public int presupuesto = LimitesRespuesta.PRESUPUESTO_CUERPO;
        /** false = el paquete va en una sola página (lo que no cabe se declara en omitido). */
        public boolean hayPagina2 = true;
        /** Recetas, versiones, restricciones, tests emparejados, selección y plantillaSpec. */
        public boolean conPlantilla = true;
        public boolean conMontaje = true;
        /** plantillaSpec con el esqueleto de caso (claseTest, imports, fixtures, casos). */
        public boolean plantillaCompleta;
        /** fqn#metodo → {previstos, faltan} del plan activo; vacío sin plan. */
        public Map<String, int[]> casosPlan = new LinkedHashMap<>();
        /** Modo {@code todas}: el 100 % de las funciones públicas en lugar de la selección. */
        public boolean todas;
        /** FQN → selección de la sesión (ia/usuario); vacío = solo la del spec.json o la automática. */
        public Map<String, SeleccionSpec> selecciones = new LinkedHashMap<>();
        /** Recetas aprendidas del perfil (líneas ya formateadas); vacío = sin recetas. */
        public List<String> recetas = new ArrayList<>();
        /** Módulo pedido por el usuario (se repite en paginacion.siguiente). */
        public String modulo;
        /** true = sin el bloque de stubs típicos. */
        public boolean sinStubsTipicos;
    }

    /** Bloque de contenido: clave de destino, nodo, categoría y nombre para {@code omitido}, y clave de la función elegida. */
    private record Bloque(String clave, JsonNode nodo, int tam, String omision, String nombre, String claveElegida) {
        static Bloque de(String clave, JsonNode nodo, String omision, String nombre, String claveElegida) {
            return new Bloque(clave, nodo, LimitesRespuesta.bytes(Json.compacto(nodo)), omision, nombre, claveElegida);
        }
    }

    /** Bloques ordenados y lo excluido siempre (por topes o cuerpos recortados), por categoría de {@code omitido}. */
    private record Contenido(List<Bloque> bloques, Map<String, Set<String>> excluidos) {
    }

    private record Interno(InfoMetodo metodo, int profundidad) {
    }

    /** Página {@code op.pagina} del paquete de trabajo de la clase, dentro de {@code op.presupuesto}. */
    public static ObjectNode pagina(ProyectoEscaneado p, ModuloEscaneado me, InfoClase c, Path salida, Opciones op) {
        if (op.pagina < 1 || op.pagina > MAX_PAGINAS) {
            throw new IllegalArgumentException("'paquete' debe ser 1 o 2.");
        }
        if (op.desde < 0) {
            throw new IllegalArgumentException("'desde' debe ser un entero ≥ 1.");
        }
        Spec previo = DetalleServicio.leerSpec(salida);
        Map<String, List<String>> generados = DetalleServicio.casosEnSalida(previo, c.fqn);
        AnalisisServicio a = me.analisis(c);
        EmparejadorTests.Emparejamiento e = me.emparejamiento(c);
        SeleccionFunciones sel = SeleccionFunciones.de(me, c, op.todas, op.todas ? null
                : SeleccionFunciones.guardada(op.selecciones == null ? Map.of() : op.selecciones,
                        AlmacenSpec.selecciones(previo), c.fqn));
        List<InfoMetodo> lista = DetalleServicio.listaMetodos(sel, c, op.metodos);
        if (lista.isEmpty()) {
            return vacia(p, me, c, a, e, sel, op);
        }
        Contenido k = contenido(me, c, a, e, generados, sel, lista, op);
        List<Bloque> bl = k.bloques();
        ObjectNode base;
        int inicio;
        if (op.pagina == 1) {
            base = base(p, me, c, a, e, sel, op);
            inicio = 0;
        } else {
            base = soloClase(c);
            inicio = op.desde > 0 ? op.desde - 1
                    : corte(base(p, me, c, a, e, sel, op), bl, 0, LimitesRespuesta.paginaPaqueteNominal());
        }
        inicio = Math.min(inicio, bl.size());
        int fin = corte(base, bl, inicio, op.presupuesto);
        ObjectNode r = armar(base, bl, inicio, fin, op.pagina == 1);
        boolean resto = fin < bl.size();
        ObjectNode pg = Json.objeto();
        pg.put("pagina", op.pagina);
        if (resto && op.pagina == 1 && op.hayPagina2) {
            pg.put("paginas", MAX_PAGINAS);
            ObjectNode s = siguienteBase(c, op);
            if (op.metodos != null && !op.metodos.isEmpty()) {
                ArrayNode ms = s.putArray("metodos");
                op.metodos.forEach(ms::add);
            }
            s.put("paquete", 2);
            s.put("desde", fin + 1);
            pg.set("siguiente", s);
            pg.put("instruccion", INSTRUCCION_PAGINA_2);
        } else {
            pg.put("paginas", op.pagina == 1 ? 1 : MAX_PAGINAS);
            List<Bloque> pendientes = bl.subList(fin, bl.size());
            boolean conOmitido = omitido(r, k.excluidos(), pendientes);
            Set<String> elegidas = new LinkedHashSet<>();
            for (Bloque b : pendientes) {
                if (b.claveElegida() != null) {
                    elegidas.add(b.claveElegida());
                }
            }
            if (!elegidas.isEmpty()) {
                ObjectNode s = siguienteBase(c, op);
                ArrayNode ms = s.putArray("metodos");
                elegidas.forEach(ms::add);
                pg.set("siguiente", s);
                pg.put("instruccion", INSTRUCCION_ELEGIDAS);
            } else {
                pg.putNull("siguiente");
                pg.put("instruccion", resto || conOmitido ? INSTRUCCION_OMITIDO : INSTRUCCION_COMPLETO);
            }
        }
        if (!r.has("ampliar") && conRecorte(bl, inicio, fin)) {
            r.put("ampliar", AMPLIAR);
        }
        r.set("paginacion", pg);
        LimitesRespuesta.acotarJson(r, op.presupuesto, ORDEN_RECORTE, Set.of(), Set.of(METODOS, "plantillaSpec"), AMPLIAR);
        return r;
    }

    /** Lista de métodos vacía: base, metodos vacío y la instrucción de detalle_servicio para total = 0. */
    private static ObjectNode vacia(ProyectoEscaneado p, ModuloEscaneado me, InfoClase c, AnalisisServicio a,
                                    EmparejadorTests.Emparejamiento e, SeleccionFunciones sel, Opciones op) {
        ObjectNode r = op.pagina == 1 ? base(p, me, c, a, e, sel, op) : soloClase(c);
        r.putArray(METODOS);
        ObjectNode pg = r.putObject("paginacion");
        pg.put("pagina", op.pagina);
        pg.put("paginas", 1);
        pg.putNull("siguiente");
        pg.put("instruccion", op.metodos != null && !op.metodos.isEmpty() ? "Ningún método coincide con 'metodos'."
                : op.todas ? "La clase no tiene funciones públicas: nada que generar."
                : "Ninguna función seleccionada (solo triviales): nada que generar; con `todas`, si el usuario lo pide, se incluyen.");
        LimitesRespuesta.acotarJson(r, op.presupuesto, ORDEN_RECORTE, Set.of(), Set.of(METODOS, "plantillaSpec"), AMPLIAR);
        return r;
    }

    private static ObjectNode soloClase(InfoClase c) {
        ObjectNode r = Json.objeto();
        r.put("clase", c.fqn);
        return r;
    }

    private static ObjectNode siguienteBase(InfoClase c, Opciones op) {
        ObjectNode s = Json.objeto();
        s.put("clase", c.fqn);
        if (op.modulo != null) {
            s.put("modulo", op.modulo);
        }
        return s;
    }

    /**
     * Índice (exclusivo) del último bloque que cabe desde {@code desde}: base + Σ(bloque + 1) ≤ presupuesto − RESERVA_PIE;
     * el primer bloque entra siempre.
     */
    private static int corte(ObjectNode base, List<Bloque> bl, int desde, int presupuesto) {
        int usados = LimitesRespuesta.bytes(Json.compacto(base));
        int limite = presupuesto - RESERVA_PIE;
        int i = desde;
        while (i < bl.size()) {
            int t = bl.get(i).tam() + 1;
            if (i > desde && usados + t > limite) {
                break;
            }
            usados += t;
            i++;
        }
        return i;
    }

    /** Base + bloques [inicio, fin) agrupados por clave en el orden de {@link #CLAVES_BLOQUES}. */
    private static ObjectNode armar(ObjectNode base, List<Bloque> bl, int inicio, int fin, boolean primera) {
        ObjectNode r = base.deepCopy();
        for (String clave : CLAVES_BLOQUES) {
            List<Bloque> deClave = new ArrayList<>();
            for (int i = inicio; i < fin; i++) {
                if (clave.equals(bl.get(i).clave())) {
                    deClave.add(bl.get(i));
                }
            }
            if (STUBS.equals(clave)) {
                if (!deClave.isEmpty()) {
                    r.set(STUBS, deClave.get(0).nodo().deepCopy());
                }
                continue;
            }
            if (deClave.isEmpty() && !(primera && METODOS.equals(clave))) {
                continue;
            }
            ArrayNode a = r.putArray(clave);
            deClave.forEach(b -> a.add(b.nodo().deepCopy()));
        }
        return r;
    }

    /** true si algún bloque de la página lleva un cuerpo recortado. */
    private static boolean conRecorte(List<Bloque> bl, int inicio, int fin) {
        for (int i = inicio; i < fin; i++) {
            if (bl.get(i).nodo().has("cuerpoRecortado")) {
                return true;
            }
        }
        return false;
    }

    /** Añade {@code omitido} (y {@code ampliar}) con lo excluido y los bloques no enviados; devuelve true si añadió algo. */
    private static boolean omitido(ObjectNode r, Map<String, Set<String>> excluidos, List<Bloque> pendientes) {
        Map<String, Set<String>> om = new LinkedHashMap<>();
        excluidos.forEach((k, v) -> om.computeIfAbsent(k, x -> new LinkedHashSet<>()).addAll(v));
        int stubs = 0;
        for (Bloque b : pendientes) {
            if (OM_STUBS.equals(b.omision())) {
                stubs += b.nodo().size();
                continue;
            }
            om.computeIfAbsent(b.omision(), x -> new LinkedHashSet<>()).add(b.nombre());
        }
        ObjectNode o = Json.objeto();
        for (String k : CLAVES_OMITIDO) {
            Set<String> v = om.get(k);
            if (v != null && !v.isEmpty()) {
                o.set(k, DetalleServicio.listaCorta(new ArrayList<>(v), MAX_OMITIDOS, 0));
            }
        }
        if (stubs > 0) {
            o.put(OM_STUBS, stubs);
        }
        if (o.isEmpty()) {
            return false;
        }
        r.set("omitido", o);
        r.put("ampliar", AMPLIAR);
        return true;
    }

    // ------------------------------------------------------------- base (cabecera compacta)

    private static ObjectNode base(ProyectoEscaneado p, ModuloEscaneado me, InfoClase c, AnalisisServicio a,
                                   EmparejadorTests.Emparejamiento e, SeleccionFunciones sel, Opciones op) {
        ObjectNode r = Json.objeto();
        r.put("clase", c.fqn);
        r.put("modulo", me.modulo.nombre());
        if (op.conMontaje) {
            ObjectNode m = r.putObject("montaje");
            m.put("modo", a.modo.toString());
            if (a.modo == Dependencia.ModoMontaje.EXPLICITO) {
                m.put("constructor", LimitesRespuesta.recortar(a.constructor == null ? CONSTRUCTOR_POR_DEFECTO : a.constructor.firma(),
                        MAX_CONSTRUCTOR));
            }
            DetalleServicio.montajeConstructor(m, a, NIVEL_MONTAJE);
            m.put("mockStaticDisponible", a.permiteMockStatic);
            m.put("mockConstructionDisponible", a.permiteMockConstruction);
        }
        ArrayNode mocks = Json.lista();
        ArrayNode deps = Json.lista();
        for (Dependencia d : a.dependencias) {
            boolean notaVisible = d.nota != null && (!d.mockeable || d.notaVisible);
            boolean especial = notaVisible || d.requiereValor || d.profundo || d.valorPorDefecto != null || d.contenedor != null;
            if (d.esMockDeclarado() && !especial) {
                mocks.add(d.nombre + ": " + d.tipo);
                continue;
            }
            ObjectNode o = deps.addObject();
            o.put("nombre", d.nombre);
            if (!Objects.equals(d.tipo, d.nombre)) {
                o.put("tipo", d.tipo);
            }
            o.put("obtencion", String.valueOf(d.obtencion));
            if (!d.esMockDeclarado()) {
                o.put("esMockEnElTest", false);
            }
            if (d.requiereValor) {
                o.put("requiereValor", true);
            }
            if (d.profundo) {
                o.put("profundo", true);
            }
            DetalleServicio.novedadesDependencia(o, d);
            if (notaVisible) {
                o.put("nota", LimitesRespuesta.recortar(d.nota, MAX_NOTA));
            }
        }
        if (!mocks.isEmpty()) {
            r.set("mocks", mocks);
        }
        if (!deps.isEmpty()) {
            r.set("dependencias", deps);
        }
        if (op.conPlantilla) {
            plantilla(r, p, me, c, a, e, sel, op);
        }
        return r;
    }

    /** recetas, versiones, restriccionesJava, testsEmparejados, seleccion y plantillaSpec (cada uno solo si aplica). */
    private static void plantilla(ObjectNode r, ProyectoEscaneado p, ModuloEscaneado me, InfoClase c, AnalisisServicio a,
                                  EmparejadorTests.Emparejamiento e, SeleccionFunciones sel, Opciones op) {
        if (op.recetas != null && !op.recetas.isEmpty()) {
            ArrayNode rc = Json.lista();
            for (String x : op.recetas) {
                if (rc.size() >= DetalleServicio.MAX_RECETAS) {
                    break;
                }
                if (x != null && !x.isBlank()) {
                    rc.add(LimitesRespuesta.recortar(x, DetalleServicio.MAX_RECETA_BYTES));
                }
            }
            if (!rc.isEmpty()) {
                r.set("recetas", rc);
            }
        }
        r.put("versiones", me.versiones.resumen());
        ArrayNode rest = Json.lista();
        for (String x : CompatibilidadJava.restricciones(me.versiones.javaEfectivo())) {
            if (x != null && !x.startsWith(SIN_RESTRICCIONES)) {
                rest.add(x);
            }
        }
        if (me.versiones.junitElegido == 4 && !Textos.versionAlMenos(me.versiones.versionJunit4, "4.13")) {
            rest.add("JUnit 4 < 4.13: no hay assertThrows; la herramienta genera try/catch + fail para las excepciones.");
        }
        if (!a.permiteMockStatic) {
            rest.add("mockStatic no disponible: no uses 'estaticos'.");
        }
        if (!a.permiteMockConstruction) {
            rest.add("mockConstruction no disponible: no uses 'construcciones'.");
        }
        if (!rest.isEmpty()) {
            r.set("restriccionesJava", rest);
        }
        List<String> tests = new ArrayList<>();
        e.tests.forEach(t -> tests.add(t.test.fqn + " (" + t.confianza + ", " + t.via + ")"));
        if (!tests.isEmpty()) {
            r.set("testsEmparejados", DetalleServicio.listaCorta(tests, DetalleServicio.MAX_TESTS_EMPAREJADOS, 0));
        }
        if (!sel.todas() && SeleccionFunciones.ORIGEN_JAVA.equals(sel.origen())) {
            JsonNode nota = DetalleServicio.nodoSeleccion(sel).get("nota");
            if (nota != null && !nota.isNull()) {
                r.put("seleccion", nota.asText());
            }
        }
        ObjectNode ps = DetalleServicio.plantilla(p, me, c, a, sel.seleccionados(), e);
        if (!op.plantillaCompleta) {
            JsonNode sv = ps.path("servicios").get(0);
            if (sv instanceof ObjectNode o) {
                o.remove(List.of("casos", "imports", "fixtures", "claseTest"));
            }
        }
        r.set("plantillaSpec", ps);
    }

    // ------------------------------------------------------------- bloques

    private static Contenido contenido(ModuloEscaneado me, InfoClase c, AnalisisServicio a, EmparejadorTests.Emparejamiento e,
                                       Map<String, List<String>> generados, SeleccionFunciones sel, List<InfoMetodo> lista,
                                       Opciones op) {
        List<Bloque> bl = new ArrayList<>();
        Map<String, Set<String>> excluidos = new LinkedHashMap<>();
        IndiceTipos idx = me.indice;
        int java = DetalleServicio.java(me);
        // 1. Funciones elegidas (por partes si el cuerpo es largo).
        for (InfoMetodo m : lista) {
            ObjectNode nodo = DetalleServicio.nodoMetodo(me, c, m, e, generados, a, op.casosPlan, sel);
            String cuerpo = m.cuerpoCompacto();
            if (!DetalleServicio.esCuerpoLargo(cuerpo)) {
                bl.add(Bloque.de(METODOS, nodo, OM_METODOS, m.clave(), m.clave()));
                continue;
            }
            List<String> partes = DetalleServicio.partesCuerpo(cuerpo);
            int n = Math.min(partes.size(), MAX_PARTES_ELEGIDA);
            boolean recortado = partes.size() > MAX_PARTES_ELEGIDA;
            for (int i = 0; i < n; i++) {
                ObjectNode o;
                if (i == 0) {
                    o = nodo;
                } else {
                    o = Json.objeto();
                    o.put("clave", m.clave());
                }
                o.put("cuerpo", partes.get(i));
                o.put("cuerpoParte", i + 1);
                o.put("cuerpoPartes", partes.size());
                if (recortado && i == n - 1) {
                    o.put("cuerpoRecortado", true);
                }
                bl.add(Bloque.de(METODOS, o, OM_METODOS, m.clave(), m.clave()));
            }
            if (recortado) {
                excluidos.computeIfAbsent(OM_METODOS, x -> new LinkedHashSet<>()).add(m.clave());
            }
        }
        // 2. Métodos de la clase llamados (profundidad 1, luego 2 y 3).
        List<Interno> internos = internos(c, lista);
        for (Interno in : internos) {
            InfoMetodo m = in.metodo();
            String cuerpo = m.cuerpoCompacto();
            if (in.profundidad() > 1) {
                ObjectNode o = Json.objeto();
                o.put("firma", m.firma());
                if (LimitesRespuesta.bytes(cuerpo) > LimitesRespuesta.MAX_CUERPO_INTERNO) {
                    o.put("cuerpo", LimitesRespuesta.recortar(cuerpo, LimitesRespuesta.MAX_CUERPO_INTERNO));
                    o.put("cuerpoRecortado", true);
                } else {
                    o.put("cuerpo", cuerpo);
                }
                bl.add(Bloque.de(INTERNOS, o, OM_INTERNOS, m.clave(), null));
                continue;
            }
            if (!DetalleServicio.esCuerpoLargo(cuerpo)) {
                ObjectNode o = Json.objeto();
                o.put("firma", m.firma());
                o.put("cuerpo", cuerpo);
                bl.add(Bloque.de(INTERNOS, o, OM_INTERNOS, m.clave(), null));
                continue;
            }
            List<String> partes = DetalleServicio.partesCuerpo(cuerpo);
            int n = Math.min(partes.size(), MAX_PARTES_INTERNO);
            boolean recortado = partes.size() > MAX_PARTES_INTERNO;
            for (int i = 0; i < n; i++) {
                ObjectNode o = Json.objeto();
                o.put("firma", m.firma());
                o.put("cuerpo", partes.get(i));
                o.put("cuerpoParte", i + 1);
                o.put("cuerpoPartes", partes.size());
                if (recortado && i == n - 1) {
                    o.put("cuerpoRecortado", true);
                }
                bl.add(Bloque.de(INTERNOS, o, OM_INTERNOS, m.clave(), null));
            }
            if (recortado) {
                excluidos.computeIfAbsent(OM_INTERNOS, x -> new LinkedHashSet<>()).add(m.clave());
            }
        }
        // 3. Stubs típicos de las funciones elegidas.
        Map<String, LlamadasDependencias.Stub> stubs = new LinkedHashMap<>();
        if (!op.sinStubsTipicos) {
            for (InfoMetodo m : lista) {
                DetalleServicio.stubsDeMetodo(me, c, a, m).forEach(stubs::putIfAbsent);
            }
        }
        if (!stubs.isEmpty()) {
            ArrayNode st = DetalleServicio.nodoStubs(stubs.values(), DetalleServicio.MAX_STUBS_TIPICOS);
            bl.add(Bloque.de(STUBS, st, OM_STUBS, STUBS, null));
        }
        // 4. Tipos del proyecto que usan.
        List<InfoMetodo> conTipos = new ArrayList<>(lista);
        for (Interno in : internos) {
            if (in.profundidad() == 1) {
                conTipos.add(in.metodo());
            }
        }
        Map<String, InfoClase> tipos = new LinkedHashMap<>();
        for (InfoMetodo m : conTipos) {
            DetalleServicio.tiposDeMetodo(c, idx, m).forEach(tipos::putIfAbsent);
        }
        for (Dependencia d : a.dependencias) {
            for (InfoClase t : DetalleServicio.resolverTipos(c, idx, List.of(d.tipo)).values()) {
                if (!d.esMockDeclarado() || d.realPorDefecto != null || t.tieneAnotacion(ANOTACION_PROPIEDADES)) {
                    tipos.putIfAbsent(t.fqn, t);
                }
            }
        }
        int nTipos = 0;
        for (InfoClase t : tipos.values()) {
            if (t == c || t.esTest || t.tipo == InfoClase.Tipo.INTERFAZ) {
                continue;
            }
            if (nTipos >= MAX_TIPOS) {
                excluidos.computeIfAbsent(OM_TIPOS, x -> new LinkedHashSet<>()).add(t.fqn);
                continue;
            }
            bl.add(Bloque.de(TIPOS, tipoPaquete(t, idx, java), OM_TIPOS, t.fqn, null));
            nTipos++;
        }
        // 5. Helpers de otras clases del proyecto (estáticos y heredados).
        List<InfoMetodo> conHelpers = new ArrayList<>(lista);
        internos.forEach(in -> conHelpers.add(in.metodo()));
        helpers(idx, c, conHelpers, bl, excluidos);
        return new Contenido(bl, excluidos);
    }

    /** Métodos de la misma clase llamados (sin scope o con this) desde las elegidas, en anchura, profundidad ≤ 3. */
    private static List<Interno> internos(InfoClase c, List<InfoMetodo> elegidas) {
        Set<String> vistos = new HashSet<>();
        elegidas.forEach(m -> vistos.add(m.clave()));
        List<Interno> r = new ArrayList<>();
        List<InfoMetodo> nivel = elegidas;
        for (int prof = 1; prof <= MAX_PROFUNDIDAD && !nivel.isEmpty(); prof++) {
            List<InfoMetodo> siguiente = new ArrayList<>();
            for (InfoMetodo m : nivel) {
                for (InfoMetodo x : llamadosInternos(c, m)) {
                    if (!vistos.add(x.clave()) || x.decl.getBody().isEmpty()) {
                        continue;
                    }
                    r.add(new Interno(x, prof));
                    siguiente.add(x);
                }
            }
            nivel = siguiente;
        }
        return r;
    }

    private static List<InfoMetodo> llamadosInternos(InfoClase c, InfoMetodo m) {
        List<InfoMetodo> r = new ArrayList<>();
        m.decl.getBody().ifPresent(b -> b.findAll(MethodCallExpr.class).forEach(mc -> {
            if (mc.getScope().isEmpty() || mc.getScope().get() instanceof ThisExpr) {
                for (InfoMetodo x : coincidentes(c.metodos, mc.getNameAsString(), mc.getArguments().size())) {
                    if (!r.contains(x)) {
                        r.add(x);
                    }
                }
            }
        }));
        return r;
    }

    /** Métodos de nombre {@code nombre} y aridad {@code aridad}; si ninguno coincide en aridad, todos los del nombre. */
    private static List<InfoMetodo> coincidentes(List<InfoMetodo> metodos, String nombre, int aridad) {
        List<InfoMetodo> porNombre = new ArrayList<>();
        List<InfoMetodo> porAridad = new ArrayList<>();
        for (InfoMetodo x : metodos) {
            if (x.nombre.equals(nombre)) {
                porNombre.add(x);
                if (x.aridad() == aridad) {
                    porAridad.add(x);
                }
            }
        }
        return porAridad.isEmpty() ? porNombre : porAridad;
    }

    /**
     * Helpers de otras clases del proyecto en los cuerpos dados: {@code Clase.metodo(...)} con scope en mayúscula que no
     * es variable local, parámetro ni campo y que resuelve a una clase del proyecto (no la propia ni de test), y llamadas
     * sin scope a métodos heredados de una superclase del proyecto. Máximo {@link #MAX_HELPERS}; el resto, a excluidos.
     */
    private static void helpers(IndiceTipos idx, InfoClase c, List<InfoMetodo> cuerpos, List<Bloque> bl,
                                Map<String, Set<String>> excluidos) {
        Set<String> campos = new HashSet<>();
        c.campos.forEach(f -> campos.add(f.nombre()));
        Set<String> propios = new HashSet<>();
        c.metodos.forEach(x -> propios.add(x.nombre));
        Set<String> vistos = new LinkedHashSet<>();
        int n = 0;
        for (InfoMetodo m : cuerpos) {
            if (m.decl.getBody().isEmpty()) {
                continue;
            }
            var cuerpo = m.decl.getBody().get();
            Set<String> locales = new HashSet<>();
            cuerpo.findAll(VariableDeclarator.class).forEach(vd -> locales.add(vd.getNameAsString()));
            cuerpo.findAll(Parameter.class).forEach(pr -> locales.add(pr.getNameAsString()));
            m.parametros.forEach(pr -> locales.add(pr.nombre()));
            for (MethodCallExpr mc : cuerpo.findAll(MethodCallExpr.class)) {
                String nombre = mc.getNameAsString();
                int aridad = mc.getArguments().size();
                InfoClase t = null;
                List<InfoMetodo> cands = List.of();
                if (mc.getScope().isPresent() && mc.getScope().get() instanceof NameExpr ne) {
                    String s = ne.getNameAsString();
                    if (s.isEmpty() || !Character.isUpperCase(s.charAt(0)) || locales.contains(s) || campos.contains(s)) {
                        continue;
                    }
                    t = idx.resolverClase(s, c);
                    if (t == null || t == c || t.esTest) {
                        continue;
                    }
                    cands = coincidentes(t.metodos, nombre, aridad);
                } else if (mc.getScope().isEmpty() && !propios.contains(nombre)) {
                    InfoClase actual = c;
                    for (int nivel = 0; nivel < MAX_PROFUNDIDAD && actual != null; nivel++) {
                        InfoClase sup = superclase(idx, actual);
                        if (sup == null || sup == c || sup.esTest) {
                            break;
                        }
                        List<InfoMetodo> x = coincidentes(sup.metodos, nombre, aridad);
                        if (!x.isEmpty()) {
                            t = sup;
                            cands = x;
                            break;
                        }
                        actual = sup;
                    }
                }
                if (t == null) {
                    continue;
                }
                for (InfoMetodo h : cands) {
                    if (!vistos.add(t.fqn + "#" + h.clave())) {
                        continue;
                    }
                    String etiqueta = t.simple + "." + h.nombre;
                    if (n >= MAX_HELPERS) {
                        excluidos.computeIfAbsent(OM_HELPERS, x -> new LinkedHashSet<>()).add(etiqueta);
                        continue;
                    }
                    bl.add(Bloque.de(HELPERS, nodoHelper(t, h), OM_HELPERS, etiqueta, null));
                    n++;
                }
            }
        }
    }

    /** Superclase del proyecto de {@code t} (no interfaz), o null. */
    private static InfoClase superclase(IndiceTipos idx, InfoClase t) {
        for (String s : t.extiende) {
            InfoClase sup = DetalleServicio.tipoProyecto(idx, Textos.quitarGenericos(s).trim(), t);
            if (sup != null && sup.tipo != InfoClase.Tipo.INTERFAZ) {
                return sup;
            }
        }
        return null;
    }

    private static ObjectNode nodoHelper(InfoClase t, InfoMetodo h) {
        ObjectNode o = Json.objeto();
        o.put("metodo", t.simple + "." + h.nombre);
        o.put("firma", (h.estatico ? "static " : "") + h.firma());
        if (h.decl.getBody().isPresent()) {
            String cuerpo = h.cuerpoCompacto();
            if (LimitesRespuesta.bytes(cuerpo) > MAX_CUERPO_HELPER) {
                o.put("cuerpo", LimitesRespuesta.recortar(cuerpo, MAX_CUERPO_HELPER));
                o.put("cuerpoRecortado", true);
            } else {
                o.put("cuerpo", cuerpo);
            }
        }
        return o;
    }

    /**
     * Tipo compacto del paquete: como {@link DetalleServicio#tipoResumido} con hasta {@link #MAX_CAMPOS_TIPO} campos, sin
     * {@code numMetodos} y con sus tipos miembro no privados de primer nivel ({@code anidados}).
     */
    static ObjectNode tipoPaquete(InfoClase t, IndiceTipos idx, int java) {
        ObjectNode o = DetalleServicio.tipoResumido(t, idx, java);
        o.remove("numMetodos");
        List<String> campos = campos(t);
        if (!campos.isEmpty()) {
            o.set("campos", DetalleServicio.listaCorta(campos, MAX_CAMPOS_TIPO, 0));
        }
        ArrayNode anidados = Json.lista();
        int total = 0;
        if (t.decl != null) {
            for (BodyDeclaration<?> bd : t.decl.getMembers()) {
                if (!(bd instanceof TypeDeclaration<?> td) || td.isPrivate()) {
                    continue;
                }
                InfoClase n = idx.anidada(t.fqn + "." + td.getNameAsString());
                if (n == null) {
                    continue;
                }
                total++;
                if (anidados.size() >= MAX_ANIDADOS) {
                    continue;
                }
                ObjectNode an = anidados.addObject();
                an.put("nombre", n.simple);
                List<String> cn = campos(n);
                if (!cn.isEmpty()) {
                    an.set("campos", DetalleServicio.listaCorta(cn, MAX_CAMPOS_ANIDADO, 0));
                }
                if (!n.constantesEnum.isEmpty()) {
                    an.set("constantesEnum", DetalleServicio.listaCorta(new ArrayList<>(n.constantesEnum), MAX_CAMPOS_ANIDADO, 0));
                }
            }
        }
        if (total > MAX_ANIDADOS) {
            anidados.add("+" + (total - MAX_ANIDADOS) + " más");
        }
        if (!anidados.isEmpty()) {
            o.set("anidados", anidados);
        }
        return o;
    }

    private static List<String> campos(InfoClase t) {
        List<String> r = new ArrayList<>();
        for (InfoClase.InfoCampo f : t.campos) {
            r.add((f.estatico() ? "static " : "") + (f.esFinal() ? "final " : "") + f.tipo() + " " + f.nombre());
        }
        return r;
    }
}
