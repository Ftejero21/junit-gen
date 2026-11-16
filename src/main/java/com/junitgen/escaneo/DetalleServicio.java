package com.junitgen.escaneo;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.junitgen.generacion.AlmacenSpec;
import com.junitgen.spec.CasoSpec;
import com.junitgen.spec.CompatibilidadJava;
import com.junitgen.spec.SeleccionSpec;
import com.junitgen.spec.ServicioSpec;
import com.junitgen.spec.Spec;
import com.junitgen.util.Json;
import com.junitgen.util.LimitesRespuesta;
import com.junitgen.util.Textos;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Detalle completo de un servicio para la IA: métodos con firma y cuerpo, dependencias y cómo se
 * obtienen, tipos auxiliares a un salto, casos existentes (del proyecto y, con salida, los ya generados
 * en su spec.json) y una plantilla de spec pre-rellenada.
 */
public final class DetalleServicio {

    public static final int MAX_CARACTERES = 60_000;
    private static final String ELIMINADO = "eliminado";

    private DetalleServicio() {
    }

    /** «id: escenario/descripción» por nombre de método de los casos no eliminados de la clase; ausente o corrupto → vacío. */
    static Map<String, List<String>> casosEnSalida(Path salida, String fqn) {
        return casosEnSalida(leerSpec(salida), fqn);
    }

    /** spec.json de la salida; null si no hay salida o spec; corrupto → null + línea en stderr. */
    static Spec leerSpec(Path salida) {
        if (salida == null) {
            return null;
        }
        try {
            return AlmacenSpec.leer(salida);
        } catch (RuntimeException e) {
            System.err.println("Se ignora el spec.json de la salida en detalle_servicio: " + e.getMessage());
            return null;
        }
    }

    static Map<String, List<String>> casosEnSalida(Spec spec, String fqn) {
        Map<String, List<String>> r = new LinkedHashMap<>();
        if (spec == null || spec.servicios == null) {
            return r;
        }
        for (ServicioSpec sv : spec.servicios) {
            if (sv == null || !fqn.equals(sv.clase) || sv.casos == null) {
                continue;
            }
            for (CasoSpec cs : sv.casos) {
                if (cs == null || cs.id == null || cs.metodo == null || ELIMINADO.equals(cs.estado)) {
                    continue;
                }
                String texto = cs.escenario != null && !cs.escenario.isBlank() ? cs.escenario
                        : cs.descripcion != null ? cs.descripcion : "";
                r.computeIfAbsent(cs.metodo, k -> new ArrayList<>()).add(cs.id + (texto.isBlank() ? "" : ": " + texto));
            }
        }
        return r;
    }

    public static ObjectNode detalle(ProyectoEscaneado p, ModuloEscaneado me, InfoClase c, List<String> metodosPedidos) {
        return detalle(p, me, c, metodosPedidos, null);
    }

    /**
     * Como {@link #detalle(ProyectoEscaneado, ModuloEscaneado, InfoClase, List)}; con {@code salida} (nullable)
     * añade por método {@code casosGeneradosEnSalida} («id: escenario» de los casos no eliminados del spec.json).
     */
    public static ObjectNode detalle(ProyectoEscaneado p, ModuloEscaneado me, InfoClase c, List<String> metodosPedidos, Path salida) {
        return detalle(p, me, c, metodosPedidos, salida, false);
    }

    /**
     * Como {@link #detalle(ProyectoEscaneado, ModuloEscaneado, InfoClase, List, Path)}; sin filtro de métodos
     * recorre solo las funciones seleccionadas ({@link SeleccionFunciones}; con {@code todas}, el 100 %), añade
     * {@code maxCasos} por método y {@code seleccion} con los conteos.
     */
    public static ObjectNode detalle(ProyectoEscaneado p, ModuloEscaneado me, InfoClase c, List<String> metodosPedidos, Path salida,
                                     boolean todas) {
        Spec previo = leerSpec(salida);
        SeleccionFunciones sel = SeleccionFunciones.de(me, c, todas,
                todas ? null : SeleccionFunciones.guardada(Map.of(), AlmacenSpec.selecciones(previo), c.fqn));
        Map<String, List<String>> generados = casosEnSalida(previo, c.fqn);
        AnalisisServicio a = me.analisis(c);
        EmparejadorTests.Emparejamiento e = me.emparejamiento(c);
        DetectorServicios.Candidato cand = me.candidato(c.fqn);
        ObjectNode r = Json.objeto();
        r.put("clase", c.fqn);
        r.put("modulo", me.modulo.nombre());
        r.put("archivo", c.archivo.toString());
        ArrayNode anns = r.putArray("anotaciones");
        c.anotaciones.forEach(anns::add);
        if (cand != null) {
            r.put("puntuacion", cand.puntuacion);
            r.put("motivo", cand.motivo);
        } else {
            r.put("motivo", "La clase no está entre los servicios detectados; se analiza igualmente.");
        }
        ObjectNode montaje = r.putObject("montaje");
        montaje.put("modo", a.modo.toString());
        montaje.put("constructor", a.constructor == null ? "(por defecto, sin parámetros)" : a.constructor.firma());
        montaje.put("explicacion", switch (a.modo) {
            case SIN_MOCKS -> "Sin dependencias: el test crea el servicio con new.";
            case INJECT_MOCKS -> "Mockito crea el servicio con @InjectMocks a partir de los @Mock.";
            case EXPLICITO -> "El test crea el servicio en setUp (constructor, setters y reflexión). Los valores no mockeables o "
                    + "las instancias reales van en construccion.argumentos; los stubs que necesite el constructor, en construccion.stubs.";
        });
        montajeConstructor(montaje, a, 0);
        montaje.put("mockStaticDisponible", a.permiteMockStatic);
        montaje.put("mockConstructionDisponible", a.permiteMockConstruction);
        ArrayNode deps = r.putArray("dependencias");
        for (Dependencia d : a.dependencias) {
            ObjectNode o = deps.addObject();
            o.put("nombre", d.nombre);
            o.put("tipo", d.tipo);
            if (d.tipoFqn != null) {
                o.put("tipoFqn", d.tipoFqn);
            }
            o.put("obtencion", d.obtencion.toString());
            o.put("mockeable", d.mockeable);
            o.put("esMockEnElTest", d.esMockDeclarado());
            if (d.requiereValor) {
                o.put("requiereValor", true);
            }
            if (d.profundo) {
                o.put("profundo", true);
            }
            novedadesDependencia(o, d);
            o.put("nota", d.nota);
        }
        Set<String> pedidos = metodosPedidos == null ? Set.of() : new LinkedHashSet<>(metodosPedidos);
        ArrayNode metodos = r.putArray("metodos");
        Set<String> internos = new LinkedHashSet<>();
        Set<String> nombresMetodos = new LinkedHashSet<>();
        c.metodos.forEach(m -> nombresMetodos.add(m.nombre));
        List<InfoMetodo> publicos = c.metodosPublicos();
        Map<String, LlamadasDependencias.Stub> stubsCli = new LinkedHashMap<>();
        for (InfoMetodo m : pedidos.isEmpty() ? sel.seleccionados() : publicos) {
            if (!pedidos.isEmpty() && !pedidos.contains(m.nombre) && !pedidos.contains(m.clave())) {
                continue;
            }
            ObjectNode o = metodos.addObject();
            o.put("firma", m.firma());
            o.put("clave", m.clave());
            ArrayNode ps = o.putArray("parametros");
            m.tiposParametros().forEach(ps::add);
            if (!m.anotaciones.isEmpty()) {
                ArrayNode ma = o.putArray("anotaciones");
                m.anotaciones.forEach(ma::add);
            }
            o.put("cuerpo", m.cuerpo());
            List<String> llamaCli = llama(me, c, m);
            if (!llamaCli.isEmpty()) {
                ArrayNode ll = o.putArray("llama");
                llamaCli.forEach(ll::add);
            }
            stubsCli.putAll(stubsDeMetodo(me, c, a, m));
            List<EmparejadorTests.CasoExistente> casos = e.casosDe(m.nombre, m.aridad());
            o.put("estado", m.esAccessor() ? "accessor" : casos.isEmpty() ? "sin tests" : "con tests");
            seleccionMetodo(o, m, sel);
            ArrayNode ce = o.putArray("casosExistentes");
            for (EmparejadorTests.CasoExistente x : casos) {
                ce.add(x.test + "." + x.metodoTest);
            }
            ArrayNode cg = o.putArray("casosGeneradosEnSalida");
            generados.getOrDefault(m.nombre, List.of()).forEach(cg::add);
            List<String> ref = a.refactor.get(m.clave());
            o.put("requiereRefactor", ref != null);
            if (ref != null) {
                ArrayNode rr = o.putArray("motivosRefactor");
                ref.forEach(rr::add);
            }
            m.decl.getBody().ifPresent(b -> b.findAll(MethodCallExpr.class).forEach(mc -> {
                if ((mc.getScope().isEmpty() || mc.getScope().get() instanceof ThisExpr) && nombresMetodos.contains(mc.getNameAsString())) {
                    internos.add(mc.getNameAsString());
                }
            }));
        }
        // Métodos internos invocados (transitivo, no públicos).
        ArrayNode ints = r.putArray("metodosInternos");
        List<String> pendientes = new ArrayList<>(internos);
        Set<String> vistos = new LinkedHashSet<>();
        while (!pendientes.isEmpty()) {
            String n = pendientes.remove(0);
            if (!vistos.add(n)) {
                continue;
            }
            for (InfoMetodo m : c.metodos) {
                if (m.nombre.equals(n) && !m.publico) {
                    ObjectNode o = ints.addObject();
                    o.put("firma", m.firma());
                    o.put("cuerpo", m.cuerpo());
                    m.decl.getBody().ifPresent(b -> b.findAll(MethodCallExpr.class).forEach(mc -> {
                        if ((mc.getScope().isEmpty() || mc.getScope().get() instanceof ThisExpr) && nombresMetodos.contains(mc.getNameAsString())) {
                            pendientes.add(mc.getNameAsString());
                        }
                    }));
                }
            }
        }
        if (!stubsCli.isEmpty()) {
            r.set("stubsTipicos", nodoStubs(stubsCli.values(), Integer.MAX_VALUE));
        }
        // Tipos auxiliares a un salto.
        r.set("tiposAuxiliares", tiposAuxiliares(c, me.indice, a, publicos, java(me)));
        ObjectNode vers = r.putObject("versiones");
        vers.put("resumen", me.versiones.resumen());
        vers.set("detalle", Json.MAPPER.valueToTree(me.versiones));
        ArrayNode rest = r.putArray("restriccionesJava");
        CompatibilidadJava.restricciones(me.versiones.javaEfectivo()).forEach(rest::add);
        if (me.versiones.junitElegido == 4 && !Textos.versionAlMenos(me.versiones.versionJunit4, "4.13")) {
            rest.add("JUnit 4 < 4.13: no hay assertThrows; la herramienta genera try/catch + fail para las excepciones.");
        }
        if (!a.permiteMockStatic) {
            rest.add("mockStatic no disponible: no uses 'estaticos'.");
        }
        if (!a.permiteMockConstruction) {
            rest.add("mockConstruction no disponible: no uses 'construcciones'.");
        }
        ArrayNode tests = r.putArray("testsEmparejados");
        for (EmparejadorTests.TestEmparejado t : e.tests) {
            tests.add(t.test.fqn + " (" + t.confianza + ", " + t.via + ")");
        }
        r.set("seleccion", nodoSeleccion(sel));
        r.set("plantillaSpec", plantilla(p, me, c, a, sel.seleccionados(), e));
        return truncar(r);
    }

    /** {@code maxCasos} del método y, si no está seleccionado, {@code seleccionada:false} + {@code motivo}. */
    private static void seleccionMetodo(ObjectNode o, InfoMetodo m, SeleccionFunciones sel) {
        SeleccionFunciones.Funcion f = sel.de(m);
        boolean seleccionada = f != null ? f.seleccionada() : sel.todas();
        o.put("maxCasos", f != null && f.seleccionada() ? f.maxCasos() : sel.maxCasos(m.nombre));
        if (!seleccionada) {
            o.put("seleccionada", false);
            o.put("motivo", f != null ? f.motivoOmision() : "no es una función pública de instancia: solo se prueba con `todas`");
        }
    }

    static ObjectNode nodoSeleccion(SeleccionFunciones sel) {
        ObjectNode s = Json.objeto();
        s.put("origen", sel.origen());
        s.put("porcentaje", sel.porcentaje());
        s.put("todas", sel.todas());
        s.put("seleccionadas", sel.seleccionados().size());
        s.put("omitidas", sel.omitidas().size());
        s.put("nota", !sel.todas() && SeleccionFunciones.ORIGEN_JAVA.equals(sel.origen())
                ? "selección automática de junit-gen por puntuación: no se llamó a seleccionar_funciones (paso obligatorio)"
                : "junit-gen rechaza casos sobre funciones no listadas");
        return s;
    }

    private static ArrayNode tiposAuxiliares(InfoClase c, IndiceTipos idx, AnalisisServicio a, List<InfoMetodo> publicos, int java) {
        Map<String, InfoClase> tipos = new LinkedHashMap<>();
        List<String> nombres = new ArrayList<>();
        for (Dependencia d : a.dependencias) {
            nombres.add(d.tipo);
        }
        for (InfoMetodo m : publicos) {
            nombres.add(m.tipoRetorno);
            m.tiposParametros().forEach(nombres::add);
            m.decl.getBody().ifPresent(b -> {
                b.findAll(ObjectCreationExpr.class).forEach(oc -> nombres.add(oc.getType().getNameAsString()));
                b.findAll(ClassOrInterfaceType.class).forEach(t -> nombres.add(t.getNameAsString()));
            });
            m.decl.getType().findAll(ClassOrInterfaceType.class).forEach(t -> nombres.add(t.getNameAsString()));
            m.decl.getParameters().forEach(pr -> pr.getType().findAll(ClassOrInterfaceType.class)
                    .forEach(t -> nombres.add(t.getNameAsString())));
        }
        for (String n : nombres) {
            if (tipos.size() >= 40) {
                break;
            }
            for (String parte : Textos.quitarGenericos(n).split("[^\\w$.]")) {
                InfoClase t = tipoProyecto(idx, parte, c);
                if (t != null && t != c && !t.esTest) {
                    tipos.putIfAbsent(t.fqn, t);
                }
            }
        }
        ArrayNode r = Json.lista();
        for (InfoClase t : tipos.values()) {
            ObjectNode o = r.addObject();
            o.put("fqn", t.fqn);
            o.put("tipo", t.tipo.toString());
            fabrica(o, t, idx, java);
            ArrayNode an = o.putArray("anotaciones");
            t.anotaciones.forEach(an::add);
            ArrayNode campos = o.putArray("campos");
            for (InfoClase.InfoCampo f : t.campos) {
                campos.add((f.estatico() ? "static " : "") + (f.esFinal() ? "final " : "") + f.tipo() + " " + f.nombre());
            }
            ArrayNode ks = o.putArray("constructores");
            for (InfoClase.InfoConstructor k : t.constructores) {
                ks.add(k.firma());
            }
            ArrayNode ms = o.putArray("metodos");
            for (InfoMetodo m : t.metodos) {
                if (m.publico || t.tipo == InfoClase.Tipo.INTERFAZ) {
                    ms.add((m.estatico ? "static " : "") + m.firma());
                }
            }
            if (!t.constantesEnum.isEmpty()) {
                ArrayNode ce = o.putArray("constantesEnum");
                t.constantesEnum.forEach(ce::add);
            }
            List<String> lombok = t.notasLombok();
            if (!lombok.isEmpty()) {
                ArrayNode nl = o.putArray("notasLombok");
                lombok.forEach(nl::add);
            }
            if (!t.extiende.isEmpty()) {
                o.put("extiende", String.join(", ", t.extiende));
            }
        }
        return r;
    }

    static ObjectNode plantilla(ProyectoEscaneado p, ModuloEscaneado me, InfoClase c, AnalisisServicio a,
                                        List<InfoMetodo> publicos, EmparejadorTests.Emparejamiento e) {
        ObjectNode s = Json.objeto();
        s.put("version", 1);
        ObjectNode pr = s.putObject("proyecto");
        pr.put("ruta", p.raiz.toString());
        pr.put("modulo", me.modulo.rel.isEmpty() ? null : me.modulo.rel);
        s.set("estilo", Json.MAPPER.valueToTree(me.estilo.sugerencia));
        ArrayNode servs = s.putArray("servicios");
        ObjectNode sv = servs.addObject();
        sv.put("clase", c.fqn);
        sv.putNull("claseTest");
        sv.putArray("imports");
        ObjectNode argumentos = Json.objeto();
        for (Dependencia d : a.dependencias) {
            if (d.requiereValor) {
                argumentos.put(d.nombre, "<literal Java de tipo " + d.tipo + ">");
            }
        }
        if (!argumentos.isEmpty()) {
            sv.putObject("construccion").set("argumentos", argumentos);
        }
        sv.putArray("fixtures");
        ArrayNode casos = sv.putArray("casos");
        for (InfoMetodo m : publicos) {
            if (m.esAccessor() || !e.casosDe(m.nombre, m.aridad()).isEmpty() || a.refactor.containsKey(m.clave())) {
                continue;
            }
            ObjectNode cs = casos.addObject();
            cs.put("id", (m.nombre.toLowerCase().replaceAll("[^a-z0-9]", "") + "-escenario").replaceAll("^-", "caso-"));
            cs.put("metodo", m.nombre);
            ArrayNode ps = cs.putArray("parametros");
            m.tiposParametros().forEach(ps::add);
            cs.put("escenario", "<escenarioEnCamelCase>");
            cs.put("descripcion", "<qué comprueba>");
            cs.put("queHaceOriginal", "<qué hace la función original, 1 frase>");
            cs.put("queComprueba", "<qué comprueba este test, 1 frase>");
            cs.putArray("datos");
            cs.putArray("preparacion");
            cs.putArray("stubs");
            cs.putArray("argumentos");
            ObjectNode esp = cs.putObject("esperado");
            esp.put("tipo", m.esVoid() ? "sinRetorno" : "valor");
            cs.putArray("aserciones");
            cs.putArray("verificaciones");
            break;
        }
        return s;
    }

    /** Caracteres reservados para la nota de truncado. */
    private static final int RESERVA_NOTA = 600;

    private static boolean excede(ObjectNode r) {
        return Json.compacto(r).length() > MAX_CARACTERES - RESERVA_NOTA;
    }

    private static ObjectNode truncar(ObjectNode r) {
        if (Json.compacto(r).length() <= MAX_CARACTERES) {
            return r;
        }
        List<String> notas = new ArrayList<>();
        ArrayNode ints = (ArrayNode) r.get("metodosInternos");
        ArrayNode aux = (ArrayNode) r.get("tiposAuxiliares");
        ArrayNode ms = (ArrayNode) r.get("metodos");
        for (var n : ints) {
            if (n.hasNonNull("cuerpo")) {
                ((ObjectNode) n).put("cuerpo", Textos.recortar(n.get("cuerpo").asText(), 400));
            }
        }
        notas.add("cuerpos de métodos internos recortados a 400 caracteres");
        if (excede(r)) {
            for (var n : aux) {
                ((ObjectNode) n).remove("metodos");
            }
            notas.add("métodos de tipos auxiliares omitidos");
        }
        if (excede(r)) {
            for (var n : ints) {
                ((ObjectNode) n).remove("cuerpo");
            }
            notas.add("métodos internos solo con su firma");
        }
        if (excede(r)) {
            for (var n : r.get("dependencias")) {
                if (n.hasNonNull("nota")) {
                    ((ObjectNode) n).put("nota", Textos.recortar(n.get("nota").asText(), MAX_NOTA_DEPENDENCIA));
                }
            }
            notas.add("notas de dependencias recortadas a " + MAX_NOTA_DEPENDENCIA + " caracteres");
        }
        if (excede(r)) {
            for (var n : ms) {
                if (n.hasNonNull("cuerpo")) {
                    ((ObjectNode) n).put("cuerpo", Textos.recortar(n.get("cuerpo").asText(), 1500));
                }
            }
            notas.add("cuerpos de métodos públicos recortados a 1500 caracteres");
        }
        if (excede(r)) {
            int antes = aux.size();
            while (!aux.isEmpty() && excede(r)) {
                aux.remove(aux.size() - 1);
            }
            notas.add("tipos auxiliares truncados (" + aux.size() + " de " + antes + ")");
        }
        if (excede(r)) {
            int antes = ms.size();
            while (ms.size() > 1 && excede(r)) {
                ms.remove(ms.size() - 1);
            }
            notas.add("lista de métodos truncada (" + ms.size() + " de " + antes + ")");
        }
        if (excede(r)) {
            int antes = ints.size();
            while (!ints.isEmpty() && excede(r)) {
                ints.remove(ints.size() - 1);
            }
            notas.add("métodos internos truncados (" + ints.size() + " de " + antes + ")");
        }
        r.put("nota", "Detalle truncado (" + String.join("; ", notas)
                + "). Pide los métodos concretos con el parámetro 'metodos' para verlos completos.");
        return r;
    }

    // ------------------------------------------------------------- detalle paginado (MCP)

    /** Opciones del detalle paginado por presupuesto (MCP). Las sobrecargas sin opciones no cambian (CLI). */
    public static class Opciones {
        /** Limitar a estos métodos (nombre o clave); también no públicos y accessors si se nombran. */
        public List<String> metodos = new ArrayList<>();
        /** Cursor (1-based) sobre la lista estable de métodos. */
        public int desde = 1;
        /** Solo estos tipos auxiliares completos (FQN o nombre simple). */
        public List<String> tipos = new ArrayList<>();
        /** Parte del cuerpo del método {@code desde} (para cuerpos mayores que MAX_CUERPO_PARTE); null = normal. */
        public Integer parteCuerpo;
        public int presupuesto = LimitesRespuesta.PRESUPUESTO_CUERPO;
        /** fqn#metodo → {previstos, faltan} del plan activo; vacío sin plan. */
        public Map<String, int[]> casosPlan = new LinkedHashMap<>();
        /** Módulo pedido por el usuario (se repite en paginacion.siguiente). */
        public String modulo;
        /** Modo {@code todas}: el 100 % de las funciones públicas en lugar de la selección. */
        public boolean todas;
        /** FQN → selección de la sesión (ia/usuario); vacío = solo la del spec.json o la automática. */
        public Map<String, SeleccionSpec> selecciones = new LinkedHashMap<>();
        /** Recetas aprendidas del perfil para la cabecera (líneas ya formateadas); vacío = sin recetas. */
        public List<String> recetas = new ArrayList<>();
    }

    static final int MAX_CASOS_LISTA = 10;
    static final int MAX_CASO_BYTES = 100;
    static final int MAX_NOTA_DEPENDENCIA = 160;
    static final int MAX_TESTS_EMPAREJADOS = 2;
    static final int MAX_CAMPOS_TIPO = 12;
    static final int MAX_CONSTRUCTORES_TIPO = 3;
    static final int MAX_ENUM_TIPO = 12;
    static final int MAX_METODOS_RESTANTES = 30;
    static final int MAX_LLAMA = 10;
    static final int MAX_STUBS_TIPICOS = 12;
    static final int MAX_SETTERS = 6;
    static final int MAX_RECETAS = 6;
    static final int MAX_RECETA_BYTES = 160;
    static final String AMPLIAR = "tipo auxiliar completo: detalle_servicio {clase, tipos:[fqn]}; método interno completo: "
            + "detalle_servicio {clase, metodos:[nombre]}; `crear`/`setters` = cómo instanciar el tipo; `<X>` en `stubsTipicos` "
            + "= instancia de X";

    static int java(ModuloEscaneado me) {
        return me.versiones == null ? 8 : me.versiones.javaEfectivo();
    }

    /** {@code dep.metodo} de las llamadas a dependencias mock de {@code m} (y sus internos), en orden y sin repetir. */
    private static List<String> llama(ModuloEscaneado me, InfoClase c, InfoMetodo m) {
        Set<String> r = new LinkedHashSet<>();
        for (LlamadasDependencias.Llamada l : LlamadasDependencias.de(me, c, m)) {
            r.add(l.dependencia() + "." + l.metodo());
        }
        return new ArrayList<>(r);
    }

    /** Stubs típicos de las llamadas de {@code m} con retorno conocido (clave {@code dep.metodo/aridad}), en orden. */
    static Map<String, LlamadasDependencias.Stub> stubsDeMetodo(ModuloEscaneado me, InfoClase c, AnalisisServicio a,
                                                                         InfoMetodo m) {
        Map<String, LlamadasDependencias.Stub> r = new LinkedHashMap<>();
        int java = java(me);
        for (LlamadasDependencias.Llamada l : LlamadasDependencias.de(me, c, m)) {
            String k = l.dependencia() + "." + l.metodo() + "/" + l.aridad();
            Dependencia d = a.dependencia(l.dependencia());
            if (d == null || r.containsKey(k)) {
                continue;
            }
            LlamadasDependencias.Stub st = LlamadasDependencias.stub(d, l, c, me.indice, java);
            if (st != null) {
                r.put(k, st);
            }
        }
        return r;
    }

    static ArrayNode nodoStubs(Collection<LlamadasDependencias.Stub> stubs, int max) {
        ArrayNode r = Json.lista();
        for (LlamadasDependencias.Stub st : stubs) {
            if (r.size() >= max) {
                break;
            }
            ObjectNode o = r.addObject();
            o.put("llamada", st.llamada());
            o.put("retorno", st.retorno());
            if (st.devuelve() != null) {
                o.put("devuelve", st.devuelve());
            }
            if (st.nota() != null) {
                o.put("nota", st.nota());
            }
        }
        return r;
    }

    /** Campos de fábrica del tipo (modo, crear, setters); devuelve el modo o null si no hay fábrica. */
    static String fabrica(ObjectNode o, InfoClase t, IndiceTipos idx, int java) {
        FabricaObjetos.Fabrica f = FabricaObjetos.de(t, idx, java);
        if (f == null) {
            return null;
        }
        o.put("modo", f.modo());
        if (f.crear() != null) {
            o.put("crear", f.crear());
        }
        if (!f.setters().isEmpty()) {
            o.set("setters", listaCorta(f.setters(), MAX_SETTERS, 0));
        }
        return f.modo();
    }

    /**
     * Lista estable de métodos del detalle: con filtro, los que coincidan (en orden de fuente); sin filtro, las
     * funciones seleccionadas ({@link SeleccionFunciones}, en orden de fuente).
     */
    static List<InfoMetodo> listaMetodos(SeleccionFunciones sel, InfoClase c, List<String> metodosPedidos) {
        List<InfoMetodo> r = new ArrayList<>();
        if (metodosPedidos != null && !metodosPedidos.isEmpty()) {
            Set<String> pedidos = new LinkedHashSet<>(metodosPedidos);
            for (InfoMetodo m : c.metodos) {
                if (pedidos.contains(m.nombre) || pedidos.contains(m.clave())) {
                    r.add(m);
                }
            }
            return r;
        }
        return sel.seleccionados();
    }

    /** Nombres de los métodos internos (no públicos) invocados por {@code m}, de forma transitiva y en orden. */
    private static Set<String> internosDe(InfoClase c, InfoMetodo m) {
        Set<String> nombresMetodos = new LinkedHashSet<>();
        c.metodos.forEach(x -> nombresMetodos.add(x.nombre));
        Set<String> privados = new LinkedHashSet<>();
        c.metodos.stream().filter(x -> !x.publico).forEach(x -> privados.add(x.nombre));
        List<String> pendientes = new ArrayList<>();
        llamadas(m, nombresMetodos, pendientes);
        Set<String> vistos = new LinkedHashSet<>();
        Set<String> r = new LinkedHashSet<>();
        while (!pendientes.isEmpty()) {
            String n = pendientes.remove(0);
            if (!vistos.add(n)) {
                continue;
            }
            if (privados.contains(n)) {
                r.add(n);
            }
            for (InfoMetodo x : c.metodos) {
                if (x.nombre.equals(n) && !x.publico) {
                    llamadas(x, nombresMetodos, pendientes);
                }
            }
        }
        return r;
    }

    static void llamadas(InfoMetodo m, Set<String> nombresMetodos, List<String> destino) {
        m.decl.getBody().ifPresent(b -> b.findAll(MethodCallExpr.class).forEach(mc -> {
            if ((mc.getScope().isEmpty() || mc.getScope().get() instanceof ThisExpr) && nombresMetodos.contains(mc.getNameAsString())) {
                destino.add(mc.getNameAsString());
            }
        }));
    }

    /** Tipos del proyecto referenciados por los nombres dados (resueltos desde la clase), en orden. */
    static Map<String, InfoClase> resolverTipos(InfoClase c, IndiceTipos idx, List<String> nombres) {
        Map<String, InfoClase> tipos = new LinkedHashMap<>();
        for (String n : nombres) {
            if (n == null) {
                continue;
            }
            for (String parte : Textos.quitarGenericos(n).split("[^\\w$.]")) {
                if (parte.isBlank()) {
                    continue;
                }
                InfoClase t = tipoProyecto(idx, parte, c);
                if (t != null && t != c && !t.esTest) {
                    tipos.putIfAbsent(t.fqn, t);
                }
            }
        }
        return tipos;
    }

    /** Tipo del proyecto (de primer nivel o anidado no privado) escrito en {@code c}; null si no lo es. */
    static InfoClase tipoProyecto(IndiceTipos idx, String nombre, InfoClase c) {
        InfoClase t = idx.resolverClase(nombre, c);
        if (t == null) {
            t = idx.resolverAnidada(nombre, c);
            if (t != null && t.decl.isPrivate()) {
                return null;
            }
        }
        return t;
    }

    static Map<String, InfoClase> tiposDeMetodo(InfoClase c, IndiceTipos idx, InfoMetodo m) {
        List<String> nombres = new ArrayList<>();
        nombres.add(m.tipoRetorno);
        nombres.addAll(m.tiposParametros());
        m.decl.getBody().ifPresent(b -> {
            b.findAll(ObjectCreationExpr.class).forEach(oc -> nombres.add(oc.getType().getNameAsString()));
            b.findAll(ClassOrInterfaceType.class).forEach(t -> nombres.add(t.getNameAsString()));
        });
        m.decl.getType().findAll(ClassOrInterfaceType.class).forEach(t -> nombres.add(t.getNameAsString()));
        m.decl.getParameters().forEach(pr -> pr.getType().findAll(ClassOrInterfaceType.class).forEach(t -> nombres.add(t.getNameAsString())));
        return resolverTipos(c, idx, nombres);
    }

    static Map<String, InfoClase> tiposDeDependencias(InfoClase c, IndiceTipos idx, AnalisisServicio a) {
        List<String> nombres = new ArrayList<>();
        a.dependencias.forEach(d -> nombres.add(d.tipo));
        return resolverTipos(c, idx, nombres);
    }

    /** Lista de cadenas acotada a {@code max} entradas de como máximo {@code maxBytes} bytes, + «+N más». */
    static ArrayNode listaCorta(List<String> l, int max, int maxBytes) {
        ArrayNode r = Json.lista();
        for (int i = 0; i < Math.min(max, l.size()); i++) {
            r.add(maxBytes > 0 ? LimitesRespuesta.recortar(l.get(i), maxBytes) : l.get(i));
        }
        if (l.size() > max) {
            r.add("+" + (l.size() - max) + " más");
        }
        return r;
    }

    static ObjectNode nodoMetodo(ModuloEscaneado me, InfoClase c, InfoMetodo m, EmparejadorTests.Emparejamiento e,
                                         Map<String, List<String>> generados, AnalisisServicio a, Map<String, int[]> casosPlan,
                                         SeleccionFunciones sel) {
        ObjectNode o = Json.objeto();
        o.put("firma", m.firma());
        o.put("clave", m.clave());
        ArrayNode ps = o.putArray("parametros");
        m.tiposParametros().forEach(ps::add);
        if (!m.anotaciones.isEmpty()) {
            ArrayNode ma = o.putArray("anotaciones");
            m.anotaciones.forEach(ma::add);
        }
        o.put("cuerpo", m.cuerpoCompacto());
        List<String> llama = llama(me, c, m);
        if (!llama.isEmpty()) {
            o.set("llama", listaCorta(llama, MAX_LLAMA, 0));
        }
        List<EmparejadorTests.CasoExistente> casos = e.casosDe(m.nombre, m.aridad());
        o.put("estado", m.esAccessor() ? "accessor" : casos.isEmpty() ? "sin tests" : "con tests");
        seleccionMetodo(o, m, sel);
        List<String> ce = new ArrayList<>();
        casos.forEach(x -> ce.add(x.test + "." + x.metodoTest));
        if (!ce.isEmpty()) {
            o.set("casosExistentes", listaCorta(ce, MAX_CASOS_LISTA, MAX_CASO_BYTES));
        }
        List<String> gen = generados.getOrDefault(m.nombre, List.of());
        if (!gen.isEmpty()) {
            o.set("casosGeneradosEnSalida", listaCorta(gen, MAX_CASOS_LISTA, MAX_CASO_BYTES));
        }
        List<String> ref = a.refactor.get(m.clave());
        if (ref != null) {
            o.put("requiereRefactor", true);
            ArrayNode rr = o.putArray("motivosRefactor");
            ref.forEach(rr::add);
        }
        int[] plan = casosPlan == null ? null : casosPlan.get(c.fqn + "#" + m.nombre);
        if (plan != null) {
            ObjectNode cp = o.putObject("casosPlan");
            cp.put("previstos", plan[0]);
            cp.put("faltan", plan[1]);
        }
        return o;
    }

    private static List<ObjectNode> nodosInternos(InfoClase c, Collection<String> nombres, boolean soloFirma) {
        List<ObjectNode> r = new ArrayList<>();
        for (String n : nombres) {
            for (InfoMetodo m : c.metodos) {
                if (m.nombre.equals(n) && !m.publico) {
                    ObjectNode o = Json.objeto();
                    o.put("firma", m.firma());
                    if (!soloFirma) {
                        String cuerpo = m.cuerpoCompacto();
                        if (LimitesRespuesta.bytes(cuerpo) > LimitesRespuesta.MAX_CUERPO_INTERNO) {
                            o.put("cuerpo", LimitesRespuesta.recortar(cuerpo, LimitesRespuesta.MAX_CUERPO_INTERNO));
                            o.put("cuerpoRecortado", true);
                        } else {
                            o.put("cuerpo", cuerpo);
                        }
                    }
                    r.add(o);
                }
            }
        }
        return r;
    }

    /** Tipo auxiliar resumido: campos (máx 12), constructores (máx 3), enum (máx 12), Lombok, extiende y nº de métodos. */
    static ObjectNode tipoResumido(InfoClase t, IndiceTipos idx, int java) {
        ObjectNode o = Json.objeto();
        o.put("fqn", t.fqn);
        o.put("tipo", t.tipo.toString());
        String modo = fabrica(o, t, idx, java);
        boolean sinConstructores = modo != null && (modo.equals(FabricaObjetos.RECORD) || modo.equals(FabricaObjetos.BUILDER)
                || modo.equals(FabricaObjetos.SETTERS) || modo.equals(FabricaObjetos.ENUM));
        boolean sinLombok = sinConstructores || FabricaObjetos.CONSTRUCTOR.equals(modo);
        List<String> campos = new ArrayList<>();
        for (InfoClase.InfoCampo f : t.campos) {
            campos.add((f.estatico() ? "static " : "") + (f.esFinal() ? "final " : "") + f.tipo() + " " + f.nombre());
        }
        if (!campos.isEmpty()) {
            o.set("campos", listaCorta(campos, MAX_CAMPOS_TIPO, 0));
        }
        List<String> ks = new ArrayList<>();
        t.constructores.forEach(k -> ks.add(k.firma()));
        if (!ks.isEmpty() && !sinConstructores) {
            o.set("constructores", listaCorta(ks, MAX_CONSTRUCTORES_TIPO, 0));
        }
        if (!t.constantesEnum.isEmpty()) {
            o.set("constantesEnum", listaCorta(new ArrayList<>(t.constantesEnum), MAX_ENUM_TIPO, 0));
        }
        List<String> lombok = sinLombok ? List.of() : t.notasLombok();
        if (!lombok.isEmpty()) {
            ArrayNode nl = o.putArray("notasLombok");
            lombok.forEach(nl::add);
        }
        if (!t.extiende.isEmpty()) {
            o.put("extiende", String.join(", ", t.extiende));
        }
        o.put("numMetodos", (int) t.metodos.stream().filter(m -> m.publico || t.tipo == InfoClase.Tipo.INTERFAZ).count());
        return o;
    }

    /** Tipo auxiliar completo (como el detalle de la CLI). */
    private static ObjectNode tipoCompleto(InfoClase t, IndiceTipos idx, int java) {
        ObjectNode o = Json.objeto();
        o.put("fqn", t.fqn);
        o.put("tipo", t.tipo.toString());
        fabrica(o, t, idx, java);
        ArrayNode an = o.putArray("anotaciones");
        t.anotaciones.forEach(an::add);
        ArrayNode campos = o.putArray("campos");
        for (InfoClase.InfoCampo f : t.campos) {
            campos.add((f.estatico() ? "static " : "") + (f.esFinal() ? "final " : "") + f.tipo() + " " + f.nombre());
        }
        ArrayNode ks = o.putArray("constructores");
        t.constructores.forEach(k -> ks.add(k.firma()));
        ArrayNode ms = o.putArray("metodos");
        for (InfoMetodo m : t.metodos) {
            if (m.publico || t.tipo == InfoClase.Tipo.INTERFAZ) {
                ms.add((m.estatico ? "static " : "") + m.firma());
            }
        }
        if (!t.constantesEnum.isEmpty()) {
            ArrayNode ce = o.putArray("constantesEnum");
            t.constantesEnum.forEach(ce::add);
        }
        List<String> lombok = t.notasLombok();
        if (!lombok.isEmpty()) {
            ArrayNode nl = o.putArray("notasLombok");
            lombok.forEach(nl::add);
        }
        if (!t.extiende.isEmpty()) {
            o.put("extiende", String.join(", ", t.extiende));
        }
        return o;
    }

    /** Cabecera del detalle (solo con desde = 1). {@code ligera}: sin notas en mockeables y testsEmparejados en 2. */
    private static void cabecera(ObjectNode r, ProyectoEscaneado p, ModuloEscaneado me, InfoClase c, AnalisisServicio a,
                                 EmparejadorTests.Emparejamiento e, int nivel, SeleccionFunciones sel, Opciones op) {
        ArrayNode anns = r.putArray("anotaciones");
        c.anotaciones.forEach(anns::add);
        ObjectNode montaje = r.putObject("montaje");
        montaje.put("modo", a.modo.toString());
        String constructor = a.constructor == null ? "(por defecto, sin parámetros)" : a.constructor.firma();
        montaje.put("constructor", nivel >= 3 ? LimitesRespuesta.recortar(constructor, MAX_NOTA_DEPENDENCIA) : constructor);
        montajeConstructor(montaje, a, nivel);
        montaje.put("mockStaticDisponible", a.permiteMockStatic);
        montaje.put("mockConstructionDisponible", a.permiteMockConstruction);
        if (nivel >= 4) {
            r.set("dependencias", dependenciasCortas(a));
        } else {
            ArrayNode deps = r.putArray("dependencias");
            for (Dependencia d : a.dependencias) {
                ObjectNode o = deps.addObject();
                o.put("nombre", d.nombre);
                o.put("tipo", d.tipo);
                o.put("obtencion", d.obtencion.toString());
                o.put("esMockEnElTest", d.esMockDeclarado());
                if (d.requiereValor) {
                    o.put("requiereValor", true);
                }
                if (d.profundo) {
                    o.put("profundo", true);
                }
                novedadesDependencia(o, d);
                if (d.nota != null && (!d.mockeable || d.notaVisible)) {
                    o.put("nota", LimitesRespuesta.recortar(d.nota, MAX_NOTA_DEPENDENCIA));
                }
            }
        }
        if (op != null && op.recetas != null && !op.recetas.isEmpty()) {
            ArrayNode rc = r.putArray("recetas");
            for (String x : op.recetas) {
                if (rc.size() >= MAX_RECETAS) {
                    break;
                }
                if (x != null && !x.isBlank()) {
                    rc.add(LimitesRespuesta.recortar(x, MAX_RECETA_BYTES));
                }
            }
        }
        r.putObject("versiones").put("resumen", me.versiones.resumen());
        ArrayNode rest = r.putArray("restriccionesJava");
        CompatibilidadJava.restricciones(me.versiones.javaEfectivo()).forEach(rest::add);
        if (me.versiones.junitElegido == 4 && !Textos.versionAlMenos(me.versiones.versionJunit4, "4.13")) {
            rest.add("JUnit 4 < 4.13: no hay assertThrows; la herramienta genera try/catch + fail para las excepciones.");
        }
        if (!a.permiteMockStatic) {
            rest.add("mockStatic no disponible: no uses 'estaticos'.");
        }
        if (!a.permiteMockConstruction) {
            rest.add("mockConstruction no disponible: no uses 'construcciones'.");
        }
        List<String> tests = new ArrayList<>();
        e.tests.forEach(t -> tests.add(t.test.fqn + " (" + t.confianza + ", " + t.via + ")"));
        r.set("testsEmparejados", listaCorta(tests, MAX_TESTS_EMPAREJADOS, 0));
        if (tests.size() > MAX_TESTS_EMPAREJADOS) {
            r.put("testsEmparejadosTotal", tests.size());
        }
        r.set("seleccion", nodoSeleccion(sel));
        r.put("cuerpos", "compactados: sin comentarios ni logs");
        r.set("plantillaSpec", plantilla(p, me, c, a, sel.seleccionados(), e));
    }

    private static ArrayNode dependenciasCortas(AnalisisServicio a) {
        ArrayNode deps = Json.lista();
        for (Dependencia d : a.dependencias) {
            deps.add(d.nombre + ": " + d.tipo + " (" + d.obtencion + ")" + (d.profundo ? " profundo" : ""));
        }
        return deps;
    }

    static final String NOTA_MONTAJE = "constructor con lógica: mocks profundos y campos derivados ya montados; si falla usa "
            + "construccion.stubs/argumentos/profundos; @PostConstruct solo con construccion.postConstruct=true";

    /**
     * Campos de montaje que solo aparecen si aplican: constructorConLogica, cuerpoConstructor (recortado con
     * nivel ≥ 3, omitido con nivel ≥ 4), postConstruct y nota.
     */
    static void montajeConstructor(ObjectNode montaje, AnalisisServicio a, int nivel) {
        if (a.constructorConLogica) {
            montaje.put("constructorConLogica", true);
            if (a.cuerpoConstructor != null && nivel < 4) {
                montaje.put("cuerpoConstructor", nivel >= 3
                        ? LimitesRespuesta.recortar(a.cuerpoConstructor, MAX_NOTA_DEPENDENCIA) : a.cuerpoConstructor);
            }
        }
        if (!a.postConstruct.isEmpty()) {
            ArrayNode pc = montaje.putArray("postConstruct");
            a.postConstruct.forEach(pc::add);
        }
        if (a.constructorConLogica || !a.postConstruct.isEmpty()) {
            montaje.put("nota", NOTA_MONTAJE);
        }
        if (!a.limpiezas.isEmpty()) {
            ArrayNode lz = montaje.putArray("limpiezaTearDown");
            a.limpiezas.forEach(x -> lz.add(x.sentencia()));
        }
    }

    /** Campos de las novedades de montaje, solo si aplican: valor por defecto y contenedor (las recetas y la herencia van en la nota). */
    static void novedadesDependencia(ObjectNode o, Dependencia d) {
        if (d.valorPorDefecto != null) {
            o.put("valorPorDefecto", d.valorPorDefecto);
        }
        if (d.contenedor != null) {
            o.put("contenedor", d.contenedor);
        }
    }

    /**
     * Detalle paginado para MCP, siempre dentro de {@code op.presupuesto}: cabecera solo con desde = 1,
     * métodos desde L[desde] mientras quepan (mínimo 1), internos y tipos auxiliares (resumidos) nuevos de
     * esos métodos, cuerpos largos por partes y {@code paginacion.siguiente} con los argumentos exactos de la
     * próxima llamada. Con {@code op.tipos}, solo esos tipos auxiliares completos.
     */
    public static ObjectNode detalle(ProyectoEscaneado p, ModuloEscaneado me, InfoClase c, Path salida, Opciones op) {
        if (op.tipos != null && !op.tipos.isEmpty()) {
            return detalleTipos(me, c, op);
        }
        if (op.desde < 1) {
            throw new IllegalArgumentException("'desde' debe ser un entero ≥ 1.");
        }
        if (op.parteCuerpo != null && op.parteCuerpo < 1) {
            throw new IllegalArgumentException("'parteCuerpo' debe ser un entero ≥ 1.");
        }
        Spec previo = leerSpec(salida);
        Map<String, List<String>> generados = casosEnSalida(previo, c.fqn);
        AnalisisServicio a = me.analisis(c);
        EmparejadorTests.Emparejamiento e = me.emparejamiento(c);
        SeleccionFunciones sel = SeleccionFunciones.de(me, c, op.todas, op.todas ? null
                : SeleccionFunciones.guardada(op.selecciones == null ? Map.of() : op.selecciones,
                        AlmacenSpec.selecciones(previo), c.fqn));
        List<InfoMetodo> lista = listaMetodos(sel, c, op.metodos);
        int total = lista.size();
        int inicio = op.desde - 1;
        // {desde:1, parteCuerpo:1}: métodos desde el primero sin cabecera (la cabecera ya se envió sola).
        boolean continuacion = inicio == 0 && op.parteCuerpo != null && op.parteCuerpo == 1;
        boolean conCabecera = inicio == 0 && !continuacion;
        // Internos y tipos ya enviados en posiciones anteriores a 'desde' (la lista es estable).
        Set<String> internosEnviados = new LinkedHashSet<>();
        Set<String> tiposEnviados = new LinkedHashSet<>();
        Set<String> stubsEnviados = new LinkedHashSet<>();
        if (!conCabecera) {
            tiposEnviados.addAll(tiposDeDependencias(c, me.indice, a).keySet());
        }
        for (int i = 0; i < Math.min(inicio, total); i++) {
            internosEnviados.addAll(internosDe(c, lista.get(i)));
            tiposEnviados.addAll(tiposDeMetodo(c, me.indice, lista.get(i)).keySet());
            stubsEnviados.addAll(stubsDeMetodo(me, c, a, lista.get(i)).keySet());
        }
        if (op.parteCuerpo != null && op.parteCuerpo > 1) {
            return parteDeCuerpo(me, c, lista, op, e, generados, a, sel);
        }
        if (inicio >= total) {
            ObjectNode r = inicio == 0 ? base(me, c) : soloClase(c);
            if (inicio == 0) {
                cabecera(r, p, me, c, a, e, 0, sel, op);
            }
            r.putArray("metodos");
            ObjectNode pg = r.putObject("paginacion");
            pg.put("totalMetodos", total);
            pg.put("desde", op.desde);
            pg.put("hasta", Math.min(op.desde - 1, total));
            pg.put("restantes", 0);
            pg.putNull("siguiente");
            String vacia = op.metodos != null && !op.metodos.isEmpty() ? "Ningún método coincide con 'metodos'."
                    : op.todas ? "La clase no tiene funciones públicas: nada que generar."
                    : "Ninguna función seleccionada (solo triviales): nada que generar; con `todas`, si el usuario lo pide, se incluyen.";
            pg.put("instruccion", total == 0 ? vacia : "No hay más métodos (total " + total + "): ya los has recibido todos.");
            return r;
        }
        Map<String, InfoClase> tiposDeps = conCabecera ? tiposDeDependencias(c, me.indice, a) : Map.of();
        ObjectNode mejor = null;
        int maxNivel = conCabecera ? 2 : 4;
        for (int nivel = 0; nivel <= maxNivel && mejor == null; nivel++) {
            mejor = empaquetar(p, me, c, a, e, generados, lista, op, inicio, internosEnviados, tiposEnviados, stubsEnviados, tiposDeps,
                    nivel, sel, conCabecera);
        }
        if (mejor == null && conCabecera) {
            return soloCabecera(p, me, c, a, e, lista, op, sel);
        }
        if (mejor == null) {
            mejor = empaquetar(p, me, c, a, e, generados, lista, op, inicio, internosEnviados, tiposEnviados, stubsEnviados, tiposDeps,
                    -1, sel, conCabecera);
            LimitesRespuesta.acotarJson(mejor, op.presupuesto, List.of("stubsTipicos", "tiposAuxiliaresOmitidos", "testsEmparejados",
                    "restriccionesJava", "metodosInternos", "paginacion.metodosRestantes", "montaje.cuerpoConstructor", "montaje.nota",
                    "montaje.constructor", "anotaciones", "recetas", "dependencias"), Set.of(), Set.of("metodos"),
                    "Pide lo recortado con 'tipos' o 'metodos' (ver 'ampliar').");
        }
        return mejor;
    }

    /**
     * Página 1 solo con la cabecera (el primer método no cabe con ella): los métodos empiezan en
     * {@code siguiente = {desde:1, parteCuerpo:1}}. Prueba los niveles 0, 3 y 4; si ninguno cabe, nivel 4 acotado.
     */
    private static ObjectNode soloCabecera(ProyectoEscaneado p, ModuloEscaneado me, InfoClase c, AnalisisServicio a,
                                           EmparejadorTests.Emparejamiento e, List<InfoMetodo> lista, Opciones op, SeleccionFunciones sel) {
        Map<String, Object> sig = siguiente(c, op, 1, 1);
        ObjectNode r = null;
        for (int nivel : new int[]{0, 3, 4}) {
            r = armar(p, me, c, a, e, lista, op, 0, 0, List.of(), List.of(), Map.of(), Map.of(), sig, nivel, sel, true);
            if (LimitesRespuesta.bytes(Json.compacto(r)) <= op.presupuesto) {
                return r;
            }
        }
        LimitesRespuesta.acotarJson(r, op.presupuesto, List.of("stubsTipicos", "tiposAuxiliaresOmitidos", "testsEmparejados",
                "restriccionesJava", "metodosInternos", "paginacion.metodosRestantes", "montaje.cuerpoConstructor", "montaje.nota",
                "montaje.constructor", "anotaciones", "recetas", "dependencias"), Set.of(), Set.of("metodos"),
                "Pide lo recortado con 'tipos' o 'metodos' (ver 'ampliar').");
        return r;
    }

    private static ObjectNode base(ModuloEscaneado me, InfoClase c) {
        ObjectNode r = Json.objeto();
        r.put("clase", c.fqn);
        r.put("modulo", me.modulo.nombre());
        r.put("archivo", c.archivo.toString());
        return r;
    }

    /** Base de las páginas con {@code desde > 1}: solo la clase (la cabecera ya se envió). */
    private static ObjectNode soloClase(InfoClase c) {
        ObjectNode r = Json.objeto();
        r.put("clase", c.fqn);
        return r;
    }

    /**
     * Empaquetado voraz con un nivel de degradación (0 normal; 1 tipos → solo FQN; 2 internos → solo firmas;
     * 3 cabecera ligera; 4 dependencias como cadenas). Devuelve null si ni siquiera el primer método cabe
     * (salvo nivel −1: devuelve el primer método igualmente, el llamador lo acota).
     */
    private static ObjectNode empaquetar(ProyectoEscaneado p, ModuloEscaneado me, InfoClase c, AnalisisServicio a,
                                         EmparejadorTests.Emparejamiento e, Map<String, List<String>> generados, List<InfoMetodo> lista,
                                         Opciones op, int inicio, Set<String> internosPrevios, Set<String> tiposPrevios,
                                         Set<String> stubsPrevios, Map<String, InfoClase> tiposDeps, int nivel,
                                         SeleccionFunciones sel, boolean conCabecera) {
        int n = nivel < 0 ? 4 : nivel;
        boolean forzar = nivel < 0;
        Set<String> internos = new LinkedHashSet<>(internosPrevios);
        Set<String> tiposVistos = new LinkedHashSet<>(tiposPrevios);
        Set<String> stubsVistos = new LinkedHashSet<>(stubsPrevios);
        Map<String, LlamadasDependencias.Stub> stubsNuevos = new LinkedHashMap<>();
        List<ObjectNode> metodos = new ArrayList<>();
        List<String> internosNuevos = new ArrayList<>();
        Map<String, InfoClase> tiposNuevos = new LinkedHashMap<>();
        // Los tipos de las dependencias ya se describen en 'dependencias': solo se marcan como vistos.
        tiposVistos.addAll(tiposDeps.keySet());
        ObjectNode mejor = null;
        for (int i = inicio; i < lista.size(); i++) {
            InfoMetodo m = lista.get(i);
            String cuerpo = m.cuerpoCompacto();
            boolean largo = esCuerpoLargo(cuerpo);
            if (largo && !metodos.isEmpty()) {
                break;
            }
            ObjectNode nodo = nodoMetodo(me, c, m, e, generados, a, op.casosPlan, sel);
            Map<String, Object> siguiente = null;
            if (largo) {
                List<String> partes = partesCuerpo(cuerpo);
                nodo.put("cuerpo", partes.get(0));
                nodo.put("cuerpoParte", 1);
                nodo.put("cuerpoPartes", partes.size());
                siguiente = siguiente(c, op, i + 1, 2);
            }
            List<String> intCand = new ArrayList<>(internosNuevos);
            Set<String> intVistos = new LinkedHashSet<>(internos);
            for (String x : internosDe(c, m)) {
                if (intVistos.add(x)) {
                    intCand.add(x);
                }
            }
            Map<String, InfoClase> tiposCand = new LinkedHashMap<>(tiposNuevos);
            Set<String> tiposVistosCand = new LinkedHashSet<>(tiposVistos);
            for (Map.Entry<String, InfoClase> t : tiposDeMetodo(c, me.indice, m).entrySet()) {
                if (tiposVistosCand.add(t.getKey())) {
                    tiposCand.put(t.getKey(), t.getValue());
                }
            }
            Map<String, LlamadasDependencias.Stub> stubsCand = new LinkedHashMap<>(stubsNuevos);
            Set<String> stubsVistosCand = new LinkedHashSet<>(stubsVistos);
            for (Map.Entry<String, LlamadasDependencias.Stub> st : stubsDeMetodo(me, c, a, m).entrySet()) {
                if (stubsVistosCand.add(st.getKey())) {
                    stubsCand.put(st.getKey(), st.getValue());
                }
            }
            List<ObjectNode> metCand = new ArrayList<>(metodos);
            metCand.add(nodo);
            int hasta = i + 1;
            if (siguiente == null && hasta < lista.size()) {
                siguiente = siguiente(c, op, hasta + 1, null);
            }
            ObjectNode r = armar(p, me, c, a, e, lista, op, inicio, hasta, metCand, intCand, tiposCand, stubsCand, siguiente, n, sel,
                    conCabecera);
            boolean cabe = LimitesRespuesta.bytes(Json.compacto(r)) <= op.presupuesto;
            if (!cabe && !(forzar && metodos.isEmpty())) {
                break;
            }
            mejor = r;
            metodos = metCand;
            internosNuevos = intCand;
            internos = intVistos;
            tiposNuevos = tiposCand;
            tiposVistos = tiposVistosCand;
            stubsNuevos = stubsCand;
            stubsVistos = stubsVistosCand;
            if (largo || forzar) {
                break;
            }
        }
        return mejor;
    }

    /** Un cuerpo se sirve por partes si su tamaño JSON-escapado supera MAX_CUERPO_PARTE. */
    static boolean esCuerpoLargo(String cuerpo) {
        return LimitesRespuesta.bytesJson(cuerpo) > LimitesRespuesta.MAX_CUERPO_PARTE;
    }

    /** Partes del cuerpo (las mismas en empaquetar y en parteDeCuerpo). */
    static List<String> partesCuerpo(String cuerpo) {
        return LimitesRespuesta.partirJson(cuerpo, LimitesRespuesta.MAX_CUERPO_PARTE);
    }

    private static Map<String, Object> siguiente(InfoClase c, Opciones op, int desde, Integer parte) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("clase", c.fqn);
        if (op.modulo != null) {
            s.put("modulo", op.modulo);
        }
        if (op.metodos != null && !op.metodos.isEmpty()) {
            s.put("metodos", op.metodos);
        }
        s.put("desde", desde);
        if (parte != null) {
            s.put("parteCuerpo", parte);
        }
        return s;
    }

    private static ObjectNode armar(ProyectoEscaneado p, ModuloEscaneado me, InfoClase c, AnalisisServicio a,
                                    EmparejadorTests.Emparejamiento e, List<InfoMetodo> lista, Opciones op, int inicio, int hasta,
                                    List<ObjectNode> metodos, List<String> internos, Map<String, InfoClase> tipos,
                                    Map<String, LlamadasDependencias.Stub> stubs, Map<String, Object> siguiente, int nivel,
                                    SeleccionFunciones sel, boolean conCabecera) {
        ObjectNode r;
        if (conCabecera) {
            r = base(me, c);
            cabecera(r, p, me, c, a, e, nivel, sel, op);
        } else {
            r = soloClase(c);
        }
        ArrayNode ms = r.putArray("metodos");
        metodos.forEach(m -> ms.add(m.deepCopy()));
        ArrayNode ints = r.putArray("metodosInternos");
        nodosInternos(c, internos, nivel >= 2).forEach(ints::add);
        if (nivel >= 1) {
            ArrayNode om = r.putArray("tiposAuxiliaresOmitidos");
            tipos.keySet().forEach(om::add);
        } else {
            ArrayNode ta = r.putArray("tiposAuxiliares");
            int java = java(me);
            tipos.values().forEach(t -> ta.add(tipoResumido(t, me.indice, java)));
        }
        if (nivel < 2 && stubs != null && !stubs.isEmpty()) {
            r.set("stubsTipicos", nodoStubs(stubs.values(), MAX_STUBS_TIPICOS));
        }
        if (conCabecera) {
            r.put("ampliar", AMPLIAR);
        }
        boolean conRestantes = conCabecera && (op.metodos == null || op.metodos.isEmpty());
        r.set("paginacion", paginacion(lista, op, hasta, siguiente, conRestantes));
        return r;
    }

    private static ObjectNode paginacion(List<InfoMetodo> lista, Opciones op, int hasta, Map<String, Object> siguiente,
                                         boolean conRestantes) {
        ObjectNode pg = Json.objeto();
        pg.put("totalMetodos", lista.size());
        pg.put("desde", op.desde);
        pg.put("hasta", hasta);
        pg.put("restantes", lista.size() - hasta);
        if (conRestantes) {
            List<String> nombres = new ArrayList<>();
            for (int i = hasta; i < lista.size(); i++) {
                nombres.add(lista.get(i).nombre);
            }
            pg.set("metodosRestantes", listaCorta(nombres, MAX_METODOS_RESTANTES, 0));
        }
        if (siguiente == null) {
            pg.putNull("siguiente");
            pg.put("instruccion", "Último tramo del servicio.");
        } else {
            pg.set("siguiente", Json.MAPPER.valueToTree(siguiente));
            pg.put("instruccion", hasta == 0
                    ? "Cabecera completa; los métodos empiezan en paginacion.siguiente (quedan " + (lista.size() - hasta) + ")."
                    : siguiente.containsKey("parteCuerpo")
                    ? "El cuerpo continúa: llama con paginacion.siguiente."
                    : "Quedan " + (lista.size() - hasta) + ": llama con paginacion.siguiente.");
        }
        return pg;
    }

    /** parteCuerpo > 1: solo el método L[desde] con esa parte del cuerpo (sin cabecera). */
    private static ObjectNode parteDeCuerpo(ModuloEscaneado me, InfoClase c, List<InfoMetodo> lista, Opciones op,
                                            EmparejadorTests.Emparejamiento e, Map<String, List<String>> generados, AnalisisServicio a,
                                            SeleccionFunciones sel) {
        int i = op.desde - 1;
        if (i >= lista.size()) {
            throw new IllegalArgumentException("'desde' (" + op.desde + ") supera el número de métodos (" + lista.size() + ").");
        }
        InfoMetodo m = lista.get(i);
        List<String> partes = partesCuerpo(m.cuerpoCompacto());
        int k = op.parteCuerpo;
        if (k > partes.size()) {
            throw new IllegalArgumentException("El cuerpo de " + m.nombre + " tiene " + partes.size() + " partes; 'parteCuerpo' = " + k + ".");
        }
        ObjectNode r = soloClase(c);
        ObjectNode nodo = nodoMetodo(me, c, m, e, generados, a, op.casosPlan, sel);
        nodo.put("cuerpo", partes.get(k - 1));
        nodo.put("cuerpoParte", k);
        nodo.put("cuerpoPartes", partes.size());
        r.putArray("metodos").add(nodo);
        Map<String, Object> sig = k < partes.size() ? siguiente(c, op, op.desde, k + 1)
                : op.desde < lista.size() ? siguiente(c, op, op.desde + 1, null) : null;
        r.set("paginacion", paginacion(lista, op, op.desde, sig, false));
        LimitesRespuesta.acotarJson(r, op.presupuesto, List.of("metodos.0.casosExistentes", "metodos.0.casosGeneradosEnSalida"),
                Set.of(), Set.of("metodos"), AMPLIAR);
        return r;
    }

    /** Solo los tipos auxiliares pedidos, completos, paginados por presupuesto (siguiente = los tipos restantes). */
    private static ObjectNode detalleTipos(ModuloEscaneado me, InfoClase c, Opciones op) {
        ObjectNode r = Json.objeto();
        r.put("clase", c.fqn);
        ArrayNode ta = r.putArray("tiposAuxiliares");
        List<String> noEncontrados = new ArrayList<>();
        List<String> restantes = new ArrayList<>();
        for (int i = 0; i < op.tipos.size(); i++) {
            String nombre = op.tipos.get(i);
            InfoClase t = me.indice.get(nombre);
            if (t == null) {
                t = me.indice.anidada(nombre);
                if (t != null && t.decl.isPrivate()) {
                    t = null;
                }
            }
            if (t == null) {
                t = tipoProyecto(me.indice, nombre, c);
            }
            if (t == null) {
                noEncontrados.add(nombre);
                continue;
            }
            ObjectNode nodo = tipoCompleto(t, me.indice, java(me));
            ta.add(nodo);
            // tiposRestantes y siguiente.tipos repiten la lista de los que quedan: se reservan ambos.
            int reserva = 2 * LimitesRespuesta.bytes(Json.compacto(op.tipos.subList(i + 1, op.tipos.size()))) + 300;
            if (ta.size() > 1 && LimitesRespuesta.bytes(Json.compacto(r)) + reserva > op.presupuesto) {
                ta.remove(ta.size() - 1);
                restantes.addAll(op.tipos.subList(i, op.tipos.size()));
                break;
            }
        }
        if (!noEncontrados.isEmpty()) {
            ArrayNode ne = r.putArray("tiposNoEncontrados");
            noEncontrados.forEach(ne::add);
        }
        ArrayNode tr = r.putArray("tiposRestantes");
        restantes.forEach(tr::add);
        if (restantes.isEmpty()) {
            r.putNull("siguiente");
        } else {
            ObjectNode s = r.putObject("siguiente");
            s.put("clase", c.fqn);
            if (op.modulo != null) {
                s.put("modulo", op.modulo);
            }
            ArrayNode st = s.putArray("tipos");
            restantes.forEach(st::add);
        }
        LimitesRespuesta.acotarJson(r, op.presupuesto, List.of("tiposAuxiliares.0.metodos", "tiposAuxiliares.0.campos",
                "tiposAuxiliares.0.constantesEnum", "tiposAuxiliares.0.constructores"), Set.of(), Set.of("siguiente", "tiposRestantes"),
                "El tipo es demasiado grande para una respuesta: lo omitido no se puede pedir por partes; consulta solo lo necesario.");
        return r;
    }
}
