package com.junitgen.spec;

import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.EmptyStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.junitgen.duplicados.DetectorDuplicados;
import com.junitgen.duplicados.ExtractorHuellasTests;
import com.junitgen.duplicados.Huella;
import com.junitgen.duplicados.NormalizadorExpresiones;
import com.junitgen.escaneo.AnalisisServicio;
import com.junitgen.escaneo.Dependencia;
import com.junitgen.escaneo.IndiceTipos;
import com.junitgen.escaneo.InfoClase;
import com.junitgen.escaneo.InfoMetodo;
import com.junitgen.escaneo.ModuloEscaneado;
import com.junitgen.escaneo.ProyectoEscaneado;
import com.junitgen.escaneo.SeleccionFunciones;
import com.junitgen.generacion.AlmacenSpec;
import com.junitgen.generacion.CatalogoClases;
import com.junitgen.generacion.PerfilPlantilla;
import com.junitgen.generacion.ResolutorImports;
import com.junitgen.util.Textos;
import com.junitgen.verificacion.PreparadorModulos;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Fase 3: valida el spec contra el escaneo (estructura, compatibilidad con la versión del proyecto,
 * tipos resolubles) y detecta duplicados en 3 niveles. Toda expresión o sentencia se parsea con
 * JavaParser; nunca se usa texto libre para construir rutas.
 */
public final class ValidadorSpec {

    public static final int MAX_EXPRESION = 2000;
    /** Longitud máxima de un elemento de {@code preparacion} que se intenta separar en varias sentencias. */
    private static final int MAX_CADENA_PREPARACION = 4 * MAX_EXPRESION;
    public static final Set<String> TIPOS_ESPERADO = Set.of("valor", "excepcion", "nulo", "noNulo", "verdadero", "falso",
            "sinRetorno", "aserciones");
    public static final Set<String> TIPOS_ASERCION = Set.of("igual", "noIgual", "nulo", "noNulo", "verdadero", "falso",
            "mismo", "tamano", "vacio", "contiene");
    private static final Set<String> ASERCION_CON_ESPERADO = Set.of("igual", "noIgual", "mismo", "tamano", "contiene");
    private static final Pattern IMPORT = Pattern.compile("(static\\s+)?[A-Za-z_$][\\w$]*(\\.[A-Za-z_$][\\w$]*)*(\\.\\*)?");
    private static final Pattern PATRON_NOMBRE = Pattern.compile("[A-Za-z0-9_${}]*");
    private static final Pattern TIPO_EXCEPCION = Pattern.compile("[A-Z][\\w$]*(\\.[A-Z][\\w$]*)*");

    private final ResultadoValidacion res = new ResultadoValidacion();
    private final Path salida;
    private int java;
    private IndiceTipos idx;
    private Set<String> tiposUsados;
    private Set<String> variables;
    /** Máximo de stubs en construccion.stubs. */
    public static final int MAX_STUBS_MONTAJE = 30;
    /** Mocks con deep stubs del servicio que se valida (permiten llamadas encadenadas). */
    private Set<String> profundosActuales = Set.of();

    /** Modo {@code todas}: el 100 % de las funciones; sin él, solo las seleccionadas (ia, usuario o automática). */
    private final boolean todas;
    /** Ids no eliminados del spec.json de la salida (casos existentes). */
    private final Set<String> idsPrevios = new LinkedHashSet<>();
    /** FQN → nombre de método → ids no eliminados del spec.json de la salida. */
    private final Map<String, Map<String, List<String>>> previosPorMetodo = new java.util.HashMap<>();
    /** FQN → selección de la sesión MCP (ia/usuario); null = modo CLI (la selección llega en el spec). */
    private final Map<String, SeleccionSpec> seleccionesSesion;
    /** Catálogo de clases por módulo (por identidad), creado al validar el primer import o tipo del módulo. */
    private final Map<ModuloEscaneado, CatalogoClases> catalogos = new IdentityHashMap<>();
    /** FQN → selección tal cual está en el spec.json de la salida (cualquier origen). */
    private final Map<String, SeleccionSpec> seleccionesCrudas = new java.util.HashMap<>();
    /** FQN → selección de origen ia del spec.json de la salida. */
    private Map<String, SeleccionSpec> seleccionesIa = Map.of();

    private ValidadorSpec(Path salida, boolean todas, Map<String, SeleccionSpec> seleccionesSesion) {
        this.salida = salida;
        this.todas = todas;
        this.seleccionesSesion = seleccionesSesion;
        cargarPrevios();
    }

    public static ResultadoValidacion validar(Spec spec, ProyectoEscaneado proyecto, Path salida) {
        return validar(spec, proyecto, salida, false);
    }

    /**
     * Valida el spec. Sin {@code todas}, los casos nuevos solo pueden ir sobre funciones seleccionadas por
     * {@link SeleccionFunciones}; los casos nuevos deben traer {@code queHaceOriginal} y {@code queComprueba}; y
     * ninguna función puede superar su máximo de casos (contando los de la salida).
     */
    public static ResultadoValidacion validar(Spec spec, ProyectoEscaneado proyecto, Path salida, boolean todas) {
        return validar(spec, proyecto, salida, todas, null);
    }

    /**
     * Como {@link #validar(Spec, ProyectoEscaneado, Path, boolean)}. {@code seleccionesSesion} (FQN → selección
     * ia/usuario de la sesión MCP; null = modo CLI): con MCP la {@code seleccion} del spec se ignora y manda la de
     * la sesión o la ia del spec.json; sin MCP la {@code seleccion} del spec se valida (salvo que sea la misma ya
     * guardada). Sin ninguna, la selección automática de junit-gen.
     */
    public static ResultadoValidacion validar(Spec spec, ProyectoEscaneado proyecto, Path salida, boolean todas,
                                              Map<String, SeleccionSpec> seleccionesSesion) {
        ValidadorSpec v = new ValidadorSpec(salida, todas, seleccionesSesion);
        v.res.spec = spec;
        v.res.proyecto = proyecto;
        v.ejecutar(spec, proyecto);
        if (v.res.errores.isEmpty()) {
            DetectorDuplicados.detectar(v.res, salida);
            if (v.res.errores.isEmpty()) {
                v.comprobarMaximos();
            }
        }
        v.res.valido = v.res.errores.isEmpty();
        return v.res;
    }

    /** Casos no eliminados del spec.json de la salida; ausente o corrupto → vacío. */
    private void cargarPrevios() {
        if (salida == null) {
            return;
        }
        Spec previo;
        try {
            previo = AlmacenSpec.leer(salida);
        } catch (RuntimeException e) {
            System.err.println("Se ignora el spec.json de la salida al validar máximos: " + e.getMessage());
            return;
        }
        if (previo == null || previo.servicios == null) {
            return;
        }
        seleccionesIa = AlmacenSpec.selecciones(previo);
        for (ServicioSpec s : previo.servicios) {
            if (s != null && s.clase != null && s.seleccion != null) {
                seleccionesCrudas.put(s.clase, s.seleccion);
            }
            if (s == null || s.clase == null || s.casos == null) {
                continue;
            }
            for (CasoSpec c : s.casos) {
                if (c == null || c.id == null || "eliminado".equals(c.estado)) {
                    continue;
                }
                idsPrevios.add(c.id);
                if (c.metodo != null) {
                    previosPorMetodo.computeIfAbsent(s.clase, k -> new LinkedHashMap<>())
                            .computeIfAbsent(c.metodo, k -> new ArrayList<>()).add(c.id);
                }
            }
        }
    }

    /**
     * Máximo de casos por función: por servicio y nombre de método, ids = previos de la salida ∪ casos del spec
     * no duplicados; cada id nuevo que exceda el máximo es un error EXCESO_CASOS.
     */
    private void comprobarMaximos() {
        for (ResultadoValidacion.ServicioValidado sv : res.servicios) {
            if (sv.seleccion == null) {
                continue;
            }
            Set<String> idsDelSpec = new LinkedHashSet<>();
            for (ResultadoValidacion.CasoValidado cv : sv.casos) {
                if (cv.spec.id != null) {
                    idsDelSpec.add(cv.spec.id);
                }
            }
            Map<String, List<ResultadoValidacion.CasoValidado>> porNombre = new LinkedHashMap<>();
            for (ResultadoValidacion.CasoValidado cv : sv.casos) {
                if (cv.metodo != null && cv.spec.id != null && !res.esDuplicado(cv.spec.id)) {
                    porNombre.computeIfAbsent(cv.metodo.nombre, k -> new ArrayList<>()).add(cv);
                }
            }
            Map<String, List<String>> previos = previosPorMetodo.getOrDefault(sv.clase.fqn, Map.of());
            for (Map.Entry<String, List<ResultadoValidacion.CasoValidado>> en : porNombre.entrySet()) {
                String nombre = en.getKey();
                int max = sv.seleccion.maxCasos(nombre);
                List<String> ids = new ArrayList<>();
                for (String id : previos.getOrDefault(nombre, List.of())) {
                    if (!idsDelSpec.contains(id)) {
                        ids.add(id);
                    }
                }
                for (ResultadoValidacion.CasoValidado cv : en.getValue()) {
                    String id = cv.spec.id;
                    if (ids.contains(id)) {
                        continue;
                    }
                    if (!idsPrevios.contains(id) && ids.size() >= max) {
                        int punt = puntuacionDe(sv.seleccion, nombre);
                        res.error("$.servicios[" + sv.indice + "].casos[" + cv.indice + "]", "EXCESO_CASOS",
                                "\"" + nombre + "\" admite como máximo " + max + " casos (complejidad " + punt + "); ya hay: "
                                        + ids + ". Sobra el caso " + id + ".",
                                "Quita este caso; el máximo lo fija junit-gen.");
                        continue;
                    }
                    ids.add(id);
                }
            }
        }
        res.valido = res.errores.isEmpty();
    }

    private static int puntuacionDe(SeleccionFunciones sel, String nombre) {
        int p = 0;
        for (SeleccionFunciones.Funcion f : sel.funciones()) {
            if (f.metodo().nombre.equals(nombre)) {
                p = Math.max(p, f.puntuacion());
            }
        }
        return p;
    }

    private void ejecutar(Spec spec, ProyectoEscaneado proyecto) {
        if (spec.version == null || spec.version != 1) {
            res.error("$.version", "VERSION", "La versión del spec debe ser 1.", "Añade \"version\": 1.");
        }
        if (spec.servicios == null || spec.servicios.isEmpty()) {
            res.error("$.servicios", "SIN_SERVICIOS", "El spec no tiene servicios.", "Añade al menos un servicio con sus casos.");
            return;
        }
        String moduloSpec = spec.proyecto == null ? null : spec.proyecto.modulo;
        if (moduloSpec != null && moduloSpec.isBlank()) {
            moduloSpec = null;
        }
        if (moduloSpec != null && proyecto.modulo(moduloSpec) == null) {
            res.aviso("$.proyecto.modulo", "MODULO_DESCONOCIDO", "No existe el módulo '" + moduloSpec
                            + "': se ignora; cada servicio se busca en todos los módulos.",
                    "Módulos: " + proyecto.modulos.stream().map(m -> m.modulo.nombre()).toList());
            moduloSpec = null;
        }
        Set<String> ids = new LinkedHashSet<>();
        for (int i = 0; i < spec.servicios.size(); i++) {
            ServicioSpec s = spec.servicios.get(i);
            String ruta = "$.servicios[" + i + "]";
            if (s == null) {
                res.error(ruta, "NULO", "Servicio nulo.", null);
                continue;
            }
            validarServicio(s, i, ruta, proyecto, moduloSpec, spec.estilo, ids);
        }
    }

    private void validarServicio(ServicioSpec s, int i, String ruta, ProyectoEscaneado proyecto, String moduloSpec,
                                 EstiloSpec estiloSpec, Set<String> ids) {
        if (s.clase == null || s.clase.isBlank()) {
            res.error(ruta + ".clase", "FALTA_CLASE", "Falta la clase del servicio (FQN).", "Usa el FQN que devuelve escanear_proyecto.");
            return;
        }
        List<ProyectoEscaneado.Encontrada> cands = proyecto.resolverServicio(s.clase.trim(), s.modulo, moduloSpec);
        if (cands.isEmpty()) {
            res.error(ruta + ".clase", "CLASE_NO_ENCONTRADA", "No se encuentra la clase " + s.clase + " en el proyecto.",
                    "Usa el FQN exacto del escaneo (se ha buscado en todos los módulos).");
            return;
        }
        if (cands.size() > 1) {
            res.error(ruta + ".clase", "CLASE_AMBIGUA", "La clase " + s.clase + " es ambigua.",
                    "Usa el FQN e indica servicios[" + i + "].modulo. Candidatos: "
                            + cands.stream().map(c -> c.modulo().modulo.nombre() + ":" + c.clase().fqn).toList());
            return;
        }
        ModuloEscaneado me = cands.get(0).modulo();
        InfoClase clase = cands.get(0).clase();
        avisoModulo(s, ruta, proyecto, moduloSpec, me, clase);
        if ((clase.tipo != InfoClase.Tipo.CLASE && clase.tipo != InfoClase.Tipo.RECORD) || clase.abstracta) {
            res.error(ruta + ".clase", "CLASE_NO_INSTANCIABLE", clase.fqn + " no es una clase concreta.", null);
            return;
        }
        idx = me.indice;
        java = me.versiones.javaEfectivo();
        AnalisisServicio an = me.analisis(clase);
        ResultadoValidacion.ServicioValidado sv = new ResultadoValidacion.ServicioValidado();
        sv.spec = s;
        sv.indice = i;
        sv.modulo = me;
        sv.clase = clase;
        sv.analisis = an;
        sv.estilo = resolverEstilo(estiloSpec, me);
        sv.seleccion = seleccionDe(s, ruta, me, clase);
        tiposUsados = new LinkedHashSet<>();
        variables = new LinkedHashSet<>();

        if (me.versiones.junitElegido == 0) {
            res.error(ruta, "SIN_JUNIT", "El módulo " + me.modulo.nombre() + " no tiene JUnit: no se puede generar la plantilla.",
                    "Añade JUnit al proyecto (manualmente) o indica la versión en junit-gen.yml.");
        }
        profundosActuales = sv.profundos;
        // mock(X.class) en argumentos sobre dependencias que ya son @Mock: se ignora (camino estándar).
        Set<String> profundosDeArgumentos = normalizarArgumentosMock(s, ruta, an, me);
        // Construcción (claves de argumentos): dependencias mock que pasan a instancia real.
        Map<String, String> argumentos = s.construccion == null || s.construccion.argumentos == null ? Map.of()
                : s.construccion.argumentos;
        List<String> argumentosValidos = clavesArgumentos(argumentos, ruta, an, clase, sv);
        // Recetas: instancia real por defecto (Clock, MeterRegistry…) si el spec no da construccion.argumentos para ella.
        for (Dependencia d : an.dependencias) {
            if (d.realPorDefecto != null && d.esMockDeclarado() && !argumentos.containsKey(d.nombre)
                    && !sv.reales.containsKey(d.nombre) && !sv.contenedorFijado(d)) {
                sv.reales.put(d.nombre, d.realPorDefecto);
                for (String fqn : d.importsReal) {
                    sv.imports.put(fqn.substring(fqn.lastIndexOf('.') + 1), fqn);
                }
            }
        }
        List<Dependencia> mocks = sv.mocks();
        if (!me.versiones.mockito && !mocks.isEmpty()) {
            res.error(ruta, "SIN_MOCKITO", "El proyecto no tiene Mockito y " + clase.simple + " necesita mocks ("
                    + mocks.stream().map(d -> d.nombre).toList() + ").", "Sin Mockito solo se generan servicios sin dependencias.");
        }
        if (s.claseTest != null) {
            if (!Textos.esIdentificador(s.claseTest)) {
                res.error(ruta + ".claseTest", "CLASE_TEST_INVALIDA", "claseTest debe ser un identificador Java simple.",
                        "Ejemplo: " + clase.simple + "Test, o null para el nombre por defecto.");
            } else {
                sv.claseTest = s.claseTest;
                if (me.indice.get(prefijo(clase.paquete) + s.claseTest) != null) {
                    res.aviso(ruta + ".claseTest", "CLASE_TEST_EXISTE", "Ya existe " + s.claseTest
                            + " en el proyecto: al copiar el archivo lo sobrescribirías.", "Usa otro nombre o null.");
                }
            }
        }
        if (sv.claseTest == null) {
            String base = clase.simple + "Test";
            sv.claseTest = me.indice.get(prefijo(clase.paquete) + base) != null ? clase.simple + "GeneradoTest" : base;
        }
        if (s.imports != null) {
            if (s.imports.size() > 100) {
                res.error(ruta + ".imports", "DEMASIADOS_IMPORTS", "Máximo 100 imports.", null);
            }
            for (int k = 0; k < s.imports.size(); k++) {
                String imp = s.imports.get(k);
                if (imp == null || !IMPORT.matcher(imp.trim()).matches()) {
                    res.error(ruta + ".imports[" + k + "]", "IMPORT_INVALIDO", "Import no válido: " + imp,
                            "Solo el nombre cualificado, sin 'import' ni ';'. Ejemplo: java.util.List");
                }
            }
            validarImportsExistentes(s, i, ruta, proyecto, me);
        }
        for (Dependencia d : mocks) {
            variables.add(d.nombre);
        }
        variables.addAll(sv.reales.keySet());
        variables.add(sv.estilo.nombreCampoSut);
        // Construcción: valores o instancias reales para dependencias y campos.
        for (String clave : argumentosValidos) {
            expresion(argumentos.get(clave), ruta + ".construccion.argumentos." + clave, true);
        }
        validarMontaje(s.construccion, ruta + ".construccion", an, mocks, sv, profundosDeArgumentos);
        for (Dependencia d : an.dependencias) {
            if (d.requiereValor && !argumentos.containsKey(d.nombre)) {
                res.error(ruta + ".construccion.argumentos", "FALTA_VALOR_CONSTRUCTOR", "Falta el valor de '" + d.nombre
                        + "' (" + d.tipo + ") para el constructor de " + clase.simple + ".",
                        "Añade \"construccion\": {\"argumentos\": {\"" + d.nombre + "\": \"<literal Java>\"}}.");
            }
        }
        // Fixtures
        Set<String> fixtures = new LinkedHashSet<>();
        if (s.fixtures != null) {
            for (int f = 0; f < s.fixtures.size(); f++) {
                validarFixture(s.fixtures.get(f), ruta + ".fixtures[" + f + "]", fixtures);
            }
        }
        // Casos
        if (s.casos == null || s.casos.isEmpty()) {
            res.aviso(ruta + ".casos", "SIN_CASOS", "El servicio no tiene casos.", null);
        } else {
            for (int c = 0; c < s.casos.size(); c++) {
                CasoSpec caso = s.casos.get(c);
                String rc = ruta + ".casos[" + c + "]";
                if (caso == null) {
                    res.error(rc, "NULO", "Caso nulo.", null);
                    continue;
                }
                ResultadoValidacion.CasoValidado cv = validarCaso(caso, c, rc, sv, ids);
                if (cv != null) {
                    sv.casos.add(cv);
                }
            }
        }
        // Tipos usados por el servicio: dependencias declaradas como mock o como instancia real.
        for (Dependencia d : mocks) {
            tiposEnTexto(d.tipo);
        }
        for (String real : sv.reales.keySet()) {
            tiposEnTexto(an.dependencia(real).tipo);
        }
        // Los parámetros de tipo de la clase del servicio (p. ej. T en Repo<T>) no son tipos a importar.
        if (clase.decl instanceof com.github.javaparser.ast.body.ClassOrInterfaceDeclaration ci) {
            ci.getTypeParameters().forEach(tp -> tiposUsados.remove(tp.getNameAsString()));
        }
        // Tipos de dependencias heredadas: el FQN se resolvió en el contexto de la superclase.
        List<String> importsResolucion = s.imports;
        for (Dependencia d : an.dependencias) {
            if (d.heredadoDe != null && d.tipoFqn != null && d.tipoFqn.contains(".") && IMPORT.matcher(d.tipoFqn).matches()
                    && (mocks.contains(d) || sv.reales.containsKey(d.nombre))) {
                if (importsResolucion == s.imports) {
                    importsResolucion = s.imports == null ? new ArrayList<>() : new ArrayList<>(s.imports);
                }
                importsResolucion.add(d.tipoFqn);
            }
        }
        ResolutorImports ri = new ResolutorImports(me.indice, clase, importsResolucion);
        for (String t : new TreeSet<>(tiposUsados)) {
            String fqn = ri.resolver(t);
            if (fqn == null) {
                CatalogoClases cat = catalogo(proyecto, me);
                String c = cat != null && cat.conClasspath() ? cat.elegir(t, List.of(), Set.of(), null) : null;
                if (c != null) {
                    s.imports = s.imports == null ? new ArrayList<>() : new ArrayList<>(s.imports);
                    if (!s.imports.contains(c)) {
                        s.imports.add(c);
                    }
                    res.aviso(ruta + ".imports", "IMPORT_DEDUCIDO", "El tipo '" + t + "' se ha resuelto como `" + c
                            + "` (único candidato) y se ha añadido a imports.", null);
                    sv.imports.put(t, c);
                } else if (!clase.importsComodin.isEmpty()) {
                    res.aviso(ruta + ".imports", "TIPO_POR_COMODIN", "El tipo '" + t + "' se resolverá con los imports comodín del servicio "
                            + clase.importsComodin + ".", "Si no compila, añade su FQN a servicios[" + i + "].imports.");
                    for (String w : clase.importsComodin) {
                        sv.imports.put("*" + w, w + ".*");
                    }
                } else {
                    res.error(ruta + ".imports", "TIPO_NO_RESUELTO", "No se puede resolver el tipo '" + t + "'."
                                    + textoCandidatos(cat, t, " Candidatos: "),
                            "Añade su nombre cualificado a servicios[" + i + "].imports (p. ej. \"com.ejemplo." + t + "\").");
                }
            } else {
                sv.imports.put(t, fqn);
            }
        }
        res.servicios.add(sv);
    }

    private static String prefijo(String paquete) {
        return paquete.isEmpty() ? "" : paquete + ".";
    }

    /**
     * Catálogo de clases del módulo (perezoso, uno por módulo): con la preparación vigente de la salida, JDK +
     * classpath de verificación + índice; sin ella, JDK de ejecución + índice. Nunca prepara el módulo.
     */
    private CatalogoClases catalogo(ProyectoEscaneado proyecto, ModuloEscaneado me) {
        if (me == null) {
            return null;
        }
        return catalogos.computeIfAbsent(me, m -> {
            PreparadorModulos.Preparacion prep = salida == null ? null : PreparadorModulos.vigente(proyecto, salida, m);
            return prep != null
                    ? CatalogoClases.de(prep.jdk().jdk().home(), PreparadorModulos.classpathCompilacion(prep, m), m.indice)
                    : CatalogoClases.de(null, List.of(), m.indice);
        });
    }

    /** «<prefijo>`a` | `b`.» con los candidatos del nombre simple; "" si no hay catálogo o candidatos. */
    private static String textoCandidatos(CatalogoClases cat, String simple, String prefijo) {
        if (cat == null) {
            return "";
        }
        List<String> cands = cat.candidatos(simple);
        if (cands.isEmpty()) {
            return "";
        }
        List<String> r = new ArrayList<>();
        for (String c : cands.subList(0, Math.min(cands.size(), CatalogoClases.MAX_CANDIDATOS))) {
            r.add("`" + c + "`");
        }
        return prefijo + String.join(" | ", r) + ".";
    }

    private static boolean esJdk(String fqn) {
        return fqn.startsWith("java.") || fqn.startsWith("javax.");
    }

    /**
     * Comprueba que los imports con punto del servicio existen (JDK, classpath vigente o proyecto). Solo es
     * autoritativo para {@code java.*} o con classpath vigente; si no, no dice nada (la verificación lo repara al
     * compilar). Un import inexistente con candidato claro se corrige en el spec; si no, error. Quita duplicados.
     */
    private void validarImportsExistentes(ServicioSpec s, int i, String ruta, ProyectoEscaneado proyecto, ModuloEscaneado me) {
        if (s.imports == null || s.imports.isEmpty()) {
            return;
        }
        CatalogoClases cat = catalogo(proyecto, me);
        if (cat == null) {
            return;
        }
        s.imports = new ArrayList<>(s.imports);
        String sugerencia = "Escribe el nombre completo exacto en servicios[" + i + "].imports o quita ese import.";
        for (int k = 0; k < s.imports.size(); k++) {
            String imp = s.imports.get(k);
            if (imp == null || !IMPORT.matcher(imp.trim()).matches()) {
                continue;
            }
            String t = imp.trim();
            boolean estatico = t.startsWith("static") && t.length() > "static".length() && Character.isWhitespace(t.charAt("static".length()));
            String nombre = estatico ? t.substring("static".length()).trim() : t;
            boolean comodin = nombre.endsWith(".*");
            String base = comodin ? nombre.substring(0, nombre.length() - 2) : nombre;
            String clase = estatico && !comodin ? (base.lastIndexOf('.') < 0 ? "" : base.substring(0, base.lastIndexOf('.'))) : base;
            String miembro = estatico ? (comodin ? "*" : base.substring(base.lastIndexOf('.') + 1)) : null;
            if (clase.indexOf('.') < 0) {
                continue;
            }
            String rk = ruta + ".imports[" + k + "]";
            boolean verificable = (clase.startsWith("java.") && cat.existePaquete("java.lang")) || cat.conClasspath();
            if (comodin && !estatico) {
                if (verificable && !cat.existePaquete(clase)) {
                    res.error(rk, "IMPORT_NO_EXISTE", "El paquete `" + clase + "` no existe.", sugerencia);
                }
                continue;
            }
            if (cat.existe(clase) || !verificable) {
                continue;
            }
            String simple = clase.substring(clase.lastIndexOf('.') + 1);
            String elegido = cat.elegir(simple, List.of(), Set.of(), clase);
            if (elegido != null && !cat.conClasspath() && !esJdk(elegido)) {
                elegido = null;
            }
            if (elegido != null) {
                String nuevo = estatico ? "static " + elegido + "." + miembro : elegido;
                s.imports.set(k, nuevo);
                res.aviso(rk, "IMPORT_CORREGIDO", "El import `" + t + "` no existe: se ha sustituido por `" + nuevo + "`.",
                        "Usa ese nombre completo en adelante.");
            } else {
                String cands = textoCandidatos(cat, simple, "");
                res.error(rk, "IMPORT_NO_EXISTE", "El import `" + t + "` no existe" + (cands.isEmpty()
                        ? " y no hay ninguna clase `" + simple + "` en el JDK, las librerías ni el proyecto."
                        : ": hay varias clases `" + simple + "`: " + cands), sugerencia);
            }
        }
        s.imports = new ArrayList<>(new LinkedHashSet<>(s.imports));
    }

    private EstiloSpec resolverEstilo(EstiloSpec e, ModuloEscaneado me) {
        EstiloSpec base = me.estilo.sugerencia;
        EstiloSpec r = new EstiloSpec();
        r.patronNombre = e != null && e.patronNombre != null ? e.patronNombre : base.patronNombre;
        r.displayName = e != null && e.displayName != null ? e.displayName : base.displayName;
        r.comentariosGwt = e != null && e.comentariosGwt != null ? e.comentariosGwt : base.comentariosGwt;
        r.visibilidadClase = e != null && e.visibilidadClase != null ? e.visibilidadClase : base.visibilidadClase;
        r.nombreCampoSut = e != null && e.nombreCampoSut != null ? e.nombreCampoSut : base.nombreCampoSut;
        if (!PATRON_NOMBRE.matcher(r.patronNombre).matches()
                || r.patronNombre.replace("{metodo}", "").replace("{escenario}", "").contains("{")) {
            res.error("$.estilo.patronNombre", "PATRON_INVALIDO", "patronNombre solo admite letras, dígitos, '_' y {metodo}/{escenario}.",
                    "Ejemplo: {metodo}_{escenario}");
            r.patronNombre = "{metodo}_{escenario}";
        }
        if (!r.patronNombre.contains("{escenario}")) {
            res.aviso("$.estilo.patronNombre", "PATRON_SIN_ESCENARIO", "patronNombre no incluye {escenario}; los nombres pueden colisionar.", null);
        }
        if (!"package".equals(r.visibilidadClase) && !"public".equals(r.visibilidadClase)) {
            res.error("$.estilo.visibilidadClase", "VISIBILIDAD_INVALIDA", "visibilidadClase debe ser 'package' o 'public'.", null);
            r.visibilidadClase = "package";
        }
        if (!Textos.esIdentificador(r.nombreCampoSut)) {
            res.error("$.estilo.nombreCampoSut", "NOMBRE_SUT_INVALIDO", "nombreCampoSut debe ser un identificador Java.", "Ejemplo: service");
            r.nombreCampoSut = "service";
        }
        if (me.versiones.junitElegido != 5 && Boolean.TRUE.equals(r.displayName)) {
            res.aviso("$.estilo.displayName", "DISPLAYNAME_JUNIT4", "@DisplayName solo existe en JUnit 5; se ignora.", null);
            r.displayName = false;
        }
        String yml = me.config.aserciones;
        String pedido = e != null ? e.aserciones : null;
        if (pedido != null && !Set.of("junit", "assertj", "auto").contains(pedido)) {
            res.error("$.estilo.aserciones", "ASERCIONES_INVALIDAS", "aserciones debe ser junit, assertj o auto.", null);
            pedido = "auto";
        }
        String elegido;
        if (yml != null && !"auto".equals(yml)) {
            elegido = yml;
        } else if (pedido != null && !"auto".equals(pedido)) {
            elegido = pedido;
        } else if (me.estilo.totalTests > 0 && me.estilo.aserciones != null) {
            elegido = me.estilo.aserciones;
        } else {
            elegido = me.versiones.assertj ? "assertj" : "junit";
        }
        if ("assertj".equals(elegido) && !me.versiones.assertj) {
            res.aviso("$.estilo.aserciones", "SIN_ASSERTJ", "El proyecto no tiene AssertJ; se usan aserciones de JUnit.", null);
            elegido = "junit";
        }
        if ("assertj".equals(elegido) && java < 8) {
            elegido = "junit";
        }
        r.aserciones = elegido;
        return r;
    }

    private void validarFixture(FixtureSpec f, String ruta, Set<String> nombres) {
        if (f == null) {
            res.error(ruta, "NULO", "Fixture nula.", null);
            return;
        }
        if (!Textos.esIdentificador(f.nombre) || Set.of("setUp", "tearDown", "inyectar").contains(f.nombre)) {
            res.error(ruta + ".nombre", "NOMBRE_INVALIDO", "Nombre de fixture no válido: " + f.nombre, "Identificador Java, p. ej. usuario");
        } else if (!nombres.add(f.nombre)) {
            res.error(ruta + ".nombre", "FIXTURE_DUPLICADA", "Fixture repetida: " + f.nombre, null);
        }
        tipo(f.tipo, ruta + ".tipo");
        Set<String> previas = new LinkedHashSet<>(variables);
        if (f.parametros != null) {
            Set<String> ps = new LinkedHashSet<>();
            for (int p = 0; p < f.parametros.size(); p++) {
                ParametroSpec ps0 = f.parametros.get(p);
                if (ps0 == null) {
                    res.error(ruta + ".parametros[" + p + "]", "NULO", "Parámetro nulo.", null);
                    continue;
                }
                tipo(ps0.tipo, ruta + ".parametros[" + p + "].tipo");
                if (!Textos.esIdentificador(ps0.nombre) || !ps.add(ps0.nombre)) {
                    res.error(ruta + ".parametros[" + p + "].nombre", "NOMBRE_INVALIDO", "Nombre de parámetro no válido o repetido: " + ps0.nombre, null);
                }
                variables.add(ps0.nombre);
            }
        }
        if (f.pasos != null) {
            for (int p = 0; p < f.pasos.size(); p++) {
                sentencia(f.pasos.get(p), ruta + ".pasos[" + p + "]");
            }
        }
        if (f.devuelve == null || f.devuelve.isBlank()) {
            res.error(ruta + ".devuelve", "FALTA_DEVUELVE", "La fixture debe indicar la expresión que devuelve.", "Ejemplo: \"u\"");
        } else {
            expresion(f.devuelve, ruta + ".devuelve", true);
        }
        variables.clear();
        variables.addAll(previas);
    }

    /**
     * Selección efectiva del servicio: {@code todas}; con MCP, la de la sesión o la ia del spec.json (la del spec
     * entrante se ignora); sin MCP, la {@code seleccion} del spec (ia/usuario; validada salvo que coincida con la
     * guardada) o la ia del spec.json. Sin ninguna, la automática de junit-gen.
     */
    private SeleccionFunciones seleccionDe(ServicioSpec s, String ruta, ModuloEscaneado me, InfoClase clase) {
        if (todas) {
            return SeleccionFunciones.de(me, clase, true);
        }
        if (seleccionesSesion != null) {
            return SeleccionFunciones.de(me, clase, false,
                    SeleccionFunciones.guardada(seleccionesSesion, seleccionesIa, clase.fqn));
        }
        SeleccionSpec entrante = s.seleccion;
        if (entrante != null && (entrante.origen == null || SeleccionFunciones.ORIGEN_IA.equals(entrante.origen)
                || SeleccionFunciones.ORIGEN_USUARIO.equals(entrante.origen))) {
            SeleccionSpec propuesta = new SeleccionSpec();
            propuesta.origen = entrante.origen == null ? SeleccionFunciones.ORIGEN_IA : entrante.origen;
            propuesta.funciones = entrante.funciones == null ? new ArrayList<>() : entrante.funciones;
            propuesta.omitidas = entrante.omitidas;
            SeleccionSpec cruda = seleccionesCrudas.get(clase.fqn);
            if (cruda != null && propuesta.origen.equals(cruda.origen)
                    && nombres(propuesta.funciones).equals(nombres(cruda.funciones))) {
                return SeleccionFunciones.de(me, clase, false, propuesta);
            }
            SeleccionFunciones.Eleccion el = SeleccionFunciones.validarEleccion(me, clase, propuesta, ruta + ".seleccion");
            if (el.valida()) {
                return SeleccionFunciones.de(me, clase, false, el.normalizada());
            }
            for (ErrorValidacion e : el.errores()) {
                res.error(e.ruta, e.codigo, e.mensaje, e.sugerencia);
            }
        }
        return SeleccionFunciones.de(me, clase, false, seleccionesIa.get(clase.fqn));
    }

    private static Set<String> nombres(List<SeleccionSpec.Entrada> entradas) {
        Set<String> r = new java.util.HashSet<>();
        if (entradas != null) {
            for (SeleccionSpec.Entrada e : entradas) {
                if (e != null && e.nombre != null) {
                    r.add(e.nombre.replaceAll("[\\s`]", ""));
                }
            }
        }
        return r;
    }

    /**
     * Casos nuevos (id ausente del spec.json de la salida): la función debe estar seleccionada (o {@code todas})
     * y traer {@code queHaceOriginal} y {@code queComprueba}. Siempre: ambos campos se sanean y recortan a 160.
     */
    private void validarSeleccionYComentario(CasoSpec c, InfoMetodo m, String ruta, ResultadoValidacion.ServicioValidado sv) {
        boolean nuevo = c.id == null || !idsPrevios.contains(c.id);
        if (nuevo && sv.seleccion != null) {
            SeleccionFunciones.Funcion f = sv.seleccion.de(m);
            boolean seleccionada = f != null ? f.seleccionada() : sv.seleccion.todas();
            if (!seleccionada) {
                String motivo = f != null ? f.motivoOmision() : "no es una función pública de instancia; solo con `todas`";
                res.error(ruta + ".metodo", "FUNCION_NO_SELECCIONADA", "'" + m.clave()
                                + "' no está entre las funciones seleccionadas (origen " + sv.seleccion.origen() + ": " + motivo + ").",
                        "Usa solo las funciones seleccionadas: " + sv.seleccion.seleccionados().stream().map(InfoMetodo::clave).toList()
                                + ". Para cambiarlas: seleccionar_funciones (CLI: campo `seleccion`). Solo el usuario puede pedir `todas`.");
            }
        }
        if (nuevo && (c.queHaceOriginal == null || c.queHaceOriginal.isBlank() || c.queComprueba == null
                || c.queComprueba.isBlank())) {
            res.error(ruta, "FALTA_COMENTARIO", "Falta 'queHaceOriginal' o 'queComprueba' en el caso " + c.id + ".",
                    "Añade ambos: qué hace la función original y qué comprueba este test (1 frase cada uno, máx. 160 caracteres).");
        }
        c.queHaceOriginal = normalizarComentario(c.queHaceOriginal, ruta + ".queHaceOriginal");
        c.queComprueba = normalizarComentario(c.queComprueba, ruta + ".queComprueba");
    }

    private String normalizarComentario(String valor, String ruta) {
        if (valor == null) {
            return null;
        }
        String colapsado = Textos.sanearComentario(valor, Integer.MAX_VALUE);
        String san = Textos.sanearComentario(valor, 160);
        if (!san.equals(colapsado)) {
            res.aviso(ruta, "COMENTARIO_RECORTADO", "El comentario supera 160 caracteres y se ha recortado.", "Usa 1 frase corta.");
        }
        return san;
    }

    private ResultadoValidacion.CasoValidado validarCaso(CasoSpec c, int indice, String ruta,
                                                         ResultadoValidacion.ServicioValidado sv, Set<String> ids) {
        InfoClase clase = sv.clase;
        AnalisisServicio an = sv.analisis;
        ResultadoValidacion.CasoValidado cv = new ResultadoValidacion.CasoValidado();
        cv.spec = c;
        cv.indice = indice;
        if (c.id == null || !Textos.ID_CASO.matcher(c.id).matches()) {
            res.error(ruta + ".id", "ID_INVALIDO", "id no válido: " + c.id, "Minúsculas, dígitos, '-' o '_', máx. 64. Ej.: accept-usuario-distinto");
        } else if (!ids.add(c.id)) {
            res.error(ruta + ".id", "ID_DUPLICADO", "id repetido en el spec: " + c.id, "Cada caso necesita un id único.");
        }
        // Método objetivo
        List<InfoMetodo> mismos = new ArrayList<>();
        for (InfoMetodo m : clase.metodosNoPrivados()) {
            if (m.nombre.equals(c.metodo)) {
                mismos.add(m);
            }
        }
        if (c.metodo == null || mismos.isEmpty()) {
            res.error(ruta + ".metodo", "METODO_NO_EXISTE", "El método '" + c.metodo + "' no existe (o es privado) en " + clase.simple + ".",
                    "Métodos: " + clase.metodosPublicos().stream().map(InfoMetodo::clave).toList());
            return null;
        }
        if (c.parametros != null) {
            List<InfoMetodo> filtrados = new ArrayList<>();
            for (InfoMetodo m : mismos) {
                List<String> t = ExtractorHuellasTests.simples(m.tiposParametros());
                List<String> p = new ArrayList<>();
                for (String x : c.parametros) {
                    p.add(Textos.nombreSimple(x));
                }
                if (t.equals(p)) {
                    filtrados.add(m);
                }
            }
            mismos = filtrados;
            if (mismos.isEmpty()) {
                res.error(ruta + ".parametros", "SOBRECARGA_NO_EXISTE", "No hay una sobrecarga de " + c.metodo + " con parámetros " + c.parametros + ".",
                        "Sobrecargas: " + clase.metodosNoPrivados().stream().filter(m -> m.nombre.equals(c.metodo)).map(InfoMetodo::clave).toList());
                return null;
            }
        }
        if (mismos.size() > 1) {
            res.error(ruta + ".parametros", "SOBRECARGA_AMBIGUA", c.metodo + " tiene varias sobrecargas.",
                    "Indica \"parametros\" con los tipos: " + mismos.stream().map(InfoMetodo::clave).toList());
            return null;
        }
        InfoMetodo m = mismos.get(0);
        cv.metodo = m;
        validarSeleccionYComentario(c, m, ruta, sv);
        if (an.refactor.containsKey(m.clave())) {
            res.error(ruta + ".metodo", "REQUIERE_REFACTOR", m.clave() + " requiere refactor: " + an.refactor.get(m.clave()),
                    "No se pueden generar casos para este método con la versión de Mockito del proyecto.");
        }
        if (c.escenario == null || c.escenario.isBlank()) {
            res.error(ruta + ".escenario", "FALTA_ESCENARIO", "Falta el escenario.", "CamelCase corto, p. ej. lanzaSiNoExiste");
        } else if (c.escenario.length() > 120) {
            res.error(ruta + ".escenario", "ESCENARIO_LARGO", "El escenario supera 120 caracteres.", null);
        }
        if (c.descripcion != null) {
            String san = Textos.sanearDescripcion(c.descripcion);
            if (!san.equals(c.descripcion)) {
                res.aviso(ruta + ".descripcion", "DESCRIPCION_SANEADA", "La descripción se ha saneado (sin saltos de línea ni '*/', máx. 300).", null);
            }
        }
        Set<String> previas = new LinkedHashSet<>(variables);
        Set<String> mocksCaso = new LinkedHashSet<>();
        for (Dependencia d : sv.mocks()) {
            mocksCaso.add(d.nombre);
        }
        Set<String> reservados = new LinkedHashSet<>(List.of("resultado", "ex", "mocks", "construido", "contexto", "tipo",
                sv.estilo.nombreCampoSut));
        reservados.addAll(mocksCaso);
        reservados.addAll(sv.reales.keySet());
        Set<String> datosCaso = new LinkedHashSet<>();
        if (c.datos != null) {
            for (int k = 0; k < c.datos.size(); k++) {
                DatoSpec d = c.datos.get(k);
                String rd = ruta + ".datos[" + k + "]";
                if (d == null) {
                    res.error(rd, "NULO", "Dato nulo.", null);
                    continue;
                }
                tipo(d.tipo, rd + ".tipo");
                if (!Textos.esIdentificador(d.nombre) || reservados.contains(d.nombre) || !datosCaso.add(d.nombre)) {
                    res.error(rd + ".nombre", "NOMBRE_INVALIDO", "Nombre de dato no válido o reservado: " + d.nombre,
                            "Reservados: " + reservados);
                }
                if (Boolean.TRUE.equals(d.mock)) {
                    if (!sv.modulo.versiones.mockito) {
                        res.error(rd + ".mock", "SIN_MOCKITO", "mock:true requiere Mockito.", null);
                    }
                    if (d.valor != null) {
                        res.aviso(rd + ".valor", "VALOR_IGNORADO", "Con mock:true se ignora 'valor'.", null);
                    }
                    mocksCaso.add(d.nombre);
                } else if (d.valor == null || d.valor.isBlank()) {
                    res.error(rd + ".valor", "FALTA_VALOR", "Falta el valor del dato " + d.nombre + ".", "Expresión Java, p. ej. 50L o new Pedido()");
                } else {
                    expresion(d.valor, rd + ".valor", true);
                }
                if ("var".equals(d.tipo) && java < 10) {
                    res.error(rd + ".tipo", "VAR_NO_SOPORTADO", "'var' requiere Java 10 (proyecto: Java " + java + ").", "Usa el tipo explícito.");
                }
                variables.add(d.nombre);
            }
        }
        if (c.preparacion != null) {
            c.preparacion = separarPreparacion(c.preparacion, ruta);
            for (int k = 0; k < c.preparacion.size(); k++) {
                Statement st = sentencia(c.preparacion.get(k), ruta + ".preparacion[" + k + "]");
                if (st != null) {
                    st.findAll(VariableDeclarator.class).forEach(v -> variables.add(v.getNameAsString()));
                }
            }
        }
        // Stubs
        if (c.stubs != null) {
            for (int k = 0; k < c.stubs.size(); k++) {
                StubSpec st = c.stubs.get(k);
                String rs = ruta + ".stubs[" + k + "]";
                if (st == null) {
                    res.error(rs, "NULO", "Stub nulo.", null);
                    continue;
                }
                if (st.mock == null || !mocksCaso.contains(st.mock)) {
                    res.error(rs + ".mock", "MOCK_DESCONOCIDO", "'" + st.mock + "' no es un mock de este caso.",
                            "Mocks disponibles: " + mocksCaso);
                    continue;
                }
                String forma = validarStub(st, rs, st.mock, tipoMock(st.mock, an, c));
                if (forma != null) {
                    cv.formaStub.put(k, forma);
                }
                if (an.dependencia(st.mock) != null && !usaDependencia(clase, m, st.mock)) {
                    res.aviso(rs + ".mock", "STUB_POSIBLEMENTE_INNECESARIO", "El método " + m.nombre + " no parece usar '" + st.mock
                            + "'. Con Mockito estricto un stub no usado falla.", "Incluye solo stubs realmente invocados.");
                }
            }
        }
        boolean hayEstaticos = c.estaticos != null && !c.estaticos.isEmpty();
        if (hayEstaticos) {
            if (!an.permiteMockStatic || java < 8) {
                res.error(ruta + ".estaticos", "MOCKSTATIC_NO_DISPONIBLE", "mockStatic no está disponible (requiere Mockito ≥ 5, o ≥ 3.4 con mockito-inline, y Java 8).",
                        "Elimina 'estaticos' y deja la llamada real.");
            }
            for (int k = 0; k < c.estaticos.size(); k++) {
                EstaticoSpec e = c.estaticos.get(k);
                String re = ruta + ".estaticos[" + k + "]";
                if (e == null || e.clase == null || !TIPO_EXCEPCION.matcher(e.clase).matches()) {
                    res.error(re + ".clase", "CLASE_INVALIDA", "Clase no válida para mockStatic.", "Nombre simple de la clase, p. ej. LocalDate");
                    continue;
                }
                tiposEnTexto(e.clase);
                Expression ll = expresion(e.clase + "." + quitarPrefijo(e.llamada, e.clase), re + ".llamada", true);
                if (ll != null && !(ll instanceof MethodCallExpr)) {
                    res.error(re + ".llamada", "LLAMADA_INVALIDA", "La llamada estática debe ser una invocación de método.", "Ejemplo: now()");
                }
                if (e.devuelve != null && e.lanza != null) {
                    res.error(re, "STUB_AMBIGUO", "Indica devuelve o lanza, no ambos.", null);
                }
                if (e.devuelve != null) {
                    expresion(e.devuelve, re + ".devuelve", true);
                }
                if (e.lanza != null) {
                    expresion(e.lanza, re + ".lanza", true);
                }
            }
        }
        if (c.construcciones != null && !c.construcciones.isEmpty()) {
            if (!an.permiteMockConstruction || java < 8) {
                res.error(ruta + ".construcciones", "MOCKCONSTRUCTION_NO_DISPONIBLE",
                        "mockConstruction no está disponible (requiere Mockito ≥ 5, o ≥ 3.5 con mockito-inline, y Java 8).", null);
            }
            for (int k = 0; k < c.construcciones.size(); k++) {
                ConstruccionMockSpec cm = c.construcciones.get(k);
                String rc = ruta + ".construcciones[" + k + "]";
                if (cm == null || cm.clase == null || !TIPO_EXCEPCION.matcher(cm.clase).matches()) {
                    res.error(rc + ".clase", "CLASE_INVALIDA", "Clase no válida para mockConstruction.", null);
                    continue;
                }
                tiposEnTexto(cm.clase);
                Map<Integer, String> formas = new LinkedHashMap<>();
                if (cm.stubs != null) {
                    for (int q = 0; q < cm.stubs.size(); q++) {
                        StubSpec st = cm.stubs.get(q);
                        if (st == null) {
                            continue;
                        }
                        String fq = validarStub(st, rc + ".stubs[" + q + "]", "construido",
                                idx.resolverFqn(cm.clase, clase));
                        if (fq != null) {
                            formas.put(q, fq);
                        }
                    }
                }
                cv.formaStubConstruccion.put(k, formas);
            }
        }
        // Argumentos
        int aridad = m.aridad();
        boolean varargs = !m.parametros.isEmpty() && m.parametros.get(aridad - 1).tipo().endsWith("...");
        int n = c.argumentos == null ? 0 : c.argumentos.size();
        if (varargs ? n < aridad - 1 : n != aridad) {
            res.error(ruta + ".argumentos", "NUM_ARGUMENTOS", m.nombre + " espera " + aridad + " argumentos y hay " + n + ".",
                    "Firma: " + m.firma());
        }
        if (c.argumentos != null) {
            for (int k = 0; k < c.argumentos.size(); k++) {
                expresion(c.argumentos.get(k), ruta + ".argumentos[" + k + "]", true);
            }
        }
        // Esperado
        EsperadoSpec esp = c.esperado;
        if (esp == null || esp.tipo == null || !TIPOS_ESPERADO.contains(esp.tipo)) {
            res.error(ruta + ".esperado.tipo", "ESPERADO_INVALIDO", "esperado.tipo debe ser uno de " + TIPOS_ESPERADO + ".", null);
        } else {
            boolean esVoid = m.esVoid();
            if (esVoid && Set.of("valor", "nulo", "noNulo", "verdadero", "falso").contains(esp.tipo)) {
                res.error(ruta + ".esperado.tipo", "ESPERADO_EN_VOID", m.nombre + " es void: no puede tener esperado '" + esp.tipo + "'.",
                        "Usa sinRetorno, excepcion o aserciones (sobre el estado) más verificaciones.");
            }
            if (!esVoid && "sinRetorno".equals(esp.tipo)) {
                res.error(ruta + ".esperado.tipo", "SIN_RETORNO_EN_NO_VOID", m.nombre + " devuelve " + m.tipoRetorno + ".",
                        "Usa valor, nulo, noNulo, verdadero, falso o aserciones.");
            }
            switch (esp.tipo) {
                case "valor" -> {
                    if (esp.valor == null) {
                        res.error(ruta + ".esperado.valor", "FALTA_VALOR", "esperado.tipo=valor requiere 'valor'.", null);
                    } else {
                        expresion(esp.valor, ruta + ".esperado.valor", true);
                    }
                }
                case "excepcion" -> {
                    if (esp.excepcion == null || !TIPO_EXCEPCION.matcher(esp.excepcion.trim()).matches()) {
                        res.error(ruta + ".esperado.excepcion", "FALTA_EXCEPCION", "Indica el tipo de la excepción (nombre simple).",
                                "Ejemplo: IllegalArgumentException");
                    } else {
                        tiposEnTexto(esp.excepcion.trim());
                    }
                    if (esp.mensaje != null && esp.mensajeContiene != null) {
                        res.error(ruta + ".esperado", "MENSAJE_AMBIGUO", "Usa mensaje o mensajeContiene, no ambos.", null);
                    }
                }
                case "aserciones" -> {
                    if (c.aserciones == null || c.aserciones.isEmpty()) {
                        res.error(ruta + ".aserciones", "SIN_ASERCIONES", "esperado.tipo=aserciones requiere al menos una aserción.", null);
                    }
                }
                default -> {
                }
            }
        }
        if (!m.esVoid()) {
            variables.add("resultado");
        }
        if (c.aserciones != null) {
            for (int k = 0; k < c.aserciones.size(); k++) {
                AsercionSpec a = c.aserciones.get(k);
                String ra = ruta + ".aserciones[" + k + "]";
                if (a == null || a.tipo == null || !TIPOS_ASERCION.contains(a.tipo)) {
                    res.error(ra + ".tipo", "ASERCION_INVALIDA", "tipo de aserción no válido.", "Valores: " + TIPOS_ASERCION);
                    continue;
                }
                expresion(a.real, ra + ".real", true);
                if (ASERCION_CON_ESPERADO.contains(a.tipo)) {
                    expresion(a.esperado, ra + ".esperado", true);
                }
            }
        }
        if (c.verificaciones != null) {
            for (int k = 0; k < c.verificaciones.size(); k++) {
                VerificacionSpec v = c.verificaciones.get(k);
                String rv = ruta + ".verificaciones[" + k + "]";
                if (v == null) {
                    continue;
                }
                if (v.mock == null || !mocksCaso.contains(v.mock)) {
                    res.error(rv + ".mock", "MOCK_DESCONOCIDO", "'" + v.mock + "' no es un mock de este caso.", "Mocks: " + mocksCaso);
                    continue;
                }
                if (Boolean.TRUE.equals(v.sinInteracciones)) {
                    continue;
                }
                if (v.veces != null && v.veces < 0) {
                    res.error(rv + ".veces", "VECES_INVALIDO", "veces debe ser ≥ 0.", null);
                }
                llamadaSobreMock(v.mock, v.llamada, rv + ".llamada", sv.profundos.contains(v.mock));
            }
        }
        // Tipo de la variable resultado
        if (!m.esVoid()) {
            cv.tipoResultado = tipoResultado(m, clase, sv);
        }
        cv.huella = huella(c, m, sv, mocksCaso);
        variables.clear();
        variables.addAll(previas);
        return cv;
    }

    private String tipoResultado(InfoMetodo m, InfoClase clase, ResultadoValidacion.ServicioValidado sv) {
        List<String> parametrosTipo = new ArrayList<>();
        m.decl.getTypeParameters().forEach(tp -> parametrosTipo.add(tp.getNameAsString()));
        if (clase.decl instanceof com.github.javaparser.ast.body.ClassOrInterfaceDeclaration ci) {
            ci.getTypeParameters().forEach(tp -> parametrosTipo.add(tp.getNameAsString()));
        }
        boolean generico = false;
        for (String tp : parametrosTipo) {
            if (Pattern.compile("\\b" + Pattern.quote(tp) + "\\b").matcher(m.tipoRetorno).find()) {
                generico = true;
            }
        }
        if (generico) {
            return java >= 10 ? "var" : "Object";
        }
        ResolutorImports ri = new ResolutorImports(idx, clase, sv.spec.imports);
        ParseResult<Type> pt = IndiceTipos.parser().parseType(m.tipoRetorno);
        if (!pt.isSuccessful() || pt.getResult().isEmpty()) {
            return java >= 10 ? "var" : "Object";
        }
        for (String t : ResolutorImports.tiposUsados(pt.getResult().get(), Set.of())) {
            if (ri.resolver(t) == null) {
                res.aviso("$.servicios[" + sv.indice + "]", "TIPO_RETORNO_NO_RESUELTO", "No se resuelve el tipo de retorno "
                        + m.tipoRetorno + " de " + m.nombre + "; se usa " + (java >= 10 ? "var" : "Object") + ".", null);
                return java >= 10 ? "var" : "Object";
            }
        }
        tiposEnTexto(m.tipoRetorno);
        return pt.getResult().get().toString();
    }

    private Huella huella(CasoSpec c, InfoMetodo m, ResultadoValidacion.ServicioValidado sv, Set<String> mocksCaso) {
        NormalizadorExpresiones n = new NormalizadorExpresiones();
        for (Dependencia d : sv.mocks()) {
            n.mock(d.nombre, Textos.nombreSimple(d.tipo));
        }
        if (c.datos != null) {
            for (DatoSpec d : c.datos) {
                if (d == null || d.nombre == null) {
                    continue;
                }
                if (Boolean.TRUE.equals(d.mock)) {
                    n.mock(d.nombre, Textos.nombreSimple(d.tipo));
                } else if (d.valor != null) {
                    n.declararTexto(d.nombre, d.valor);
                }
            }
        }
        if (c.preparacion != null) {
            List<Statement> sts = new ArrayList<>();
            for (String p : c.preparacion) {
                Statement st = parsearSentencia(p);
                if (st != null) {
                    sts.add(st);
                }
            }
            ExtractorHuellasTests.aplicarSentencias(sts, n);
        }
        n.fijo("resultado", "RESULTADO");
        List<String> args = new ArrayList<>();
        if (c.argumentos != null) {
            for (String a : c.argumentos) {
                args.add(n.normalizar(a));
            }
        }
        List<String> stubs = new ArrayList<>();
        if (c.stubs != null) {
            for (StubSpec s : c.stubs) {
                if (s == null || s.mock == null || s.llamada == null) {
                    continue;
                }
                stubs.add(n.normalizar(s.mock + "." + s.llamada) + "->" + resultadoStub(s, n));
            }
        }
        if (c.estaticos != null) {
            for (EstaticoSpec e : c.estaticos) {
                if (e != null && e.clase != null) {
                    stubs.add("static:" + n.normalizar(e.clase + "." + quitarPrefijo(e.llamada, e.clase)) + "->"
                            + (e.lanza != null ? "!" + NormalizadorExpresiones.nombreExcepcion(e.lanza) : n.normalizar(e.devuelve)));
                }
            }
        }
        if (c.construcciones != null) {
            for (ConstruccionMockSpec cm : c.construcciones) {
                if (cm == null || cm.stubs == null) {
                    continue;
                }
                for (StubSpec s : cm.stubs) {
                    if (s != null && s.llamada != null) {
                        stubs.add("new:" + cm.clase + "." + n.normalizar(s.llamada) + "->" + resultadoStub(s, n));
                    }
                }
            }
        }
        String esperado = Huella.DESCONOCIDO;
        EsperadoSpec e = c.esperado;
        if (e != null && e.tipo != null) {
            switch (e.tipo) {
                case "valor" -> esperado = "valor:" + n.normalizar(e.valor);
                case "excepcion" -> esperado = "excepcion:" + NormalizadorExpresiones.nombreExcepcion(e.excepcion);
                case "aserciones" -> {
                    List<String> as = new ArrayList<>();
                    if (c.aserciones != null) {
                        for (AsercionSpec a : c.aserciones) {
                            if (a != null) {
                                as.add(a.tipo + "(" + n.normalizar(a.real) + "," + (a.esperado == null ? "" : n.normalizar(a.esperado)) + ")");
                            }
                        }
                    }
                    as.sort(null);
                    esperado = "aserciones:" + String.join(";", as);
                }
                default -> esperado = e.tipo;
            }
        }
        return Huella.calcular(sv.clase.fqn, m.nombre, ExtractorHuellasTests.simples(m.tiposParametros()), args, stubs, esperado);
    }

    private static String resultadoStub(StubSpec s, NormalizadorExpresiones n) {
        if (s.lanza != null) {
            return "!" + NormalizadorExpresiones.nombreExcepcion(s.lanza);
        }
        if (s.devuelveSecuencia != null) {
            List<String> v = new ArrayList<>();
            s.devuelveSecuencia.forEach(x -> v.add(n.normalizar(x)));
            return String.join(",", v);
        }
        if (s.responde != null) {
            return "answer";
        }
        return n.normalizar(s.devuelve);
    }

    private static String quitarPrefijo(String llamada, String clase) {
        if (llamada == null) {
            return "";
        }
        String l = llamada.trim();
        String simple = Textos.nombreSimple(clase);
        if (l.startsWith(clase + ".")) {
            return l.substring(clase.length() + 1);
        }
        if (l.startsWith(simple + ".")) {
            return l.substring(simple.length() + 1);
        }
        return l;
    }

    private String tipoMock(String nombre, AnalisisServicio an, CasoSpec c) {
        Dependencia d = an.dependencia(nombre);
        if (d != null) {
            return d.tipoFqn != null ? d.tipoFqn : d.tipo;
        }
        if (c.datos != null) {
            for (DatoSpec x : c.datos) {
                if (x != null && nombre.equals(x.nombre)) {
                    return idx.resolverFqn(x.tipo, an.clase);
                }
            }
        }
        return null;
    }

    /** Valida un stub y devuelve la forma resuelta ("when" o "do"). */
    private String validarStub(StubSpec st, String ruta, String mock, String tipoFqn) {
        boolean permiteCadena = profundosActuales.contains(mock);
        MethodCallExpr llamada = llamadaSobreMock(mock, st.llamada, ruta + ".llamada", permiteCadena);
        if (llamada != null && !(llamada.getScope().orElse(null) instanceof NameExpr)) {
            tipoFqn = null; // llamada encadenada: el tipo del receptor no se conoce sin symbol solver
        }
        int resultados = (st.devuelve != null ? 1 : 0) + (st.devuelveSecuencia != null ? 1 : 0) + (st.lanza != null ? 1 : 0)
                + (st.responde != null ? 1 : 0);
        if (resultados != 1) {
            res.error(ruta, "STUB_RESULTADO", "Cada stub necesita exactamente uno de: devuelve, devuelveSecuencia, lanza, responde.", null);
            return null;
        }
        if (st.devuelve != null) {
            expresion(st.devuelve, ruta + ".devuelve", true);
        }
        if (st.devuelveSecuencia != null) {
            if (st.devuelveSecuencia.isEmpty()) {
                res.error(ruta + ".devuelveSecuencia", "SECUENCIA_VACIA", "devuelveSecuencia no puede estar vacía.", null);
            }
            for (int i = 0; i < st.devuelveSecuencia.size(); i++) {
                expresion(st.devuelveSecuencia.get(i), ruta + ".devuelveSecuencia[" + i + "]", true);
            }
        }
        if (st.lanza != null) {
            expresion(st.lanza, ruta + ".lanza", true);
        }
        if (st.responde != null) {
            if (java < 8) {
                res.error(ruta + ".responde", "RESPONDE_JAVA8", "responde (lambda) requiere Java 8.", "Usa devuelve.");
            }
            expresion(st.responde, ruta + ".responde", true);
        }
        String forma = st.forma == null ? "auto" : st.forma;
        if (!Set.of("auto", "when", "do").contains(forma)) {
            res.error(ruta + ".forma", "FORMA_INVALIDA", "forma debe ser auto, when o do.", null);
            return null;
        }
        Boolean esVoid = llamada == null ? null : esMetodoVoid(tipoFqn, llamada.getNameAsString(), llamada.getArguments().size(), 0);
        if (Boolean.TRUE.equals(esVoid) && (st.devuelve != null || st.devuelveSecuencia != null)) {
            res.error(ruta, "DEVUELVE_EN_VOID", llamada.getNameAsString() + " es void: no puede devolver un valor.",
                    "Usa lanza, responde o elimina el stub.");
            return null;
        }
        if ("when".equals(forma) && Boolean.TRUE.equals(esVoid)) {
            res.error(ruta + ".forma", "WHEN_EN_VOID", "Un método void no admite la forma when(...).", "Usa forma do o auto.");
            return null;
        }
        if (!"auto".equals(forma)) {
            return forma;
        }
        if (Boolean.TRUE.equals(esVoid)) {
            return "do";
        }
        if (st.lanza != null && esVoid == null) {
            return "do";
        }
        return "when";
    }

    private Boolean esMetodoVoid(String fqn, String metodo, int aridad, int profundidad) {
        if (fqn == null || profundidad > 5) {
            return null;
        }
        InfoClase t = idx.get(fqn);
        if (t == null) {
            t = idx.anidada(fqn);
        }
        if (t == null) {
            return null;
        }
        Boolean r = null;
        for (InfoMetodo m : t.metodos) {
            if (m.nombre.equals(metodo) && (m.aridad() == aridad || m.tiposParametros().stream().anyMatch(x -> x.endsWith("...")))) {
                if (r == null) {
                    r = m.esVoid();
                } else if (r != m.esVoid()) {
                    return null;
                }
            }
        }
        if (r != null) {
            return r;
        }
        List<String> padres = new ArrayList<>(t.extiende);
        padres.addAll(t.implementa);
        for (String p : padres) {
            Boolean v = esMetodoVoid(idx.resolverFqn(p, t), metodo, aridad, profundidad + 1);
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    private MethodCallExpr llamadaSobreMock(String mock, String llamada, String ruta) {
        return llamadaSobreMock(mock, llamada, ruta, false);
    }

    /**
     * Valida {@code mock.llamada}. Con {@code permiteCadena} (mock con deep stubs) acepta también llamadas
     * encadenadas cuyo ámbito desciende solo por llamadas hasta el mock: {@code mock.a().b(x)}.
     */
    private MethodCallExpr llamadaSobreMock(String mock, String llamada, String ruta, boolean permiteCadena) {
        if (llamada == null || llamada.isBlank()) {
            res.error(ruta, "FALTA_LLAMADA", "Falta la llamada.", "Ejemplo: findById(1L)");
            return null;
        }
        Expression e = expresion(mock + "." + llamada.trim(), ruta, true);
        if (e == null) {
            return null;
        }
        if (!(e instanceof MethodCallExpr mc) || mc.getScope().isEmpty()) {
            errorLlamada(ruta, llamada);
            return null;
        }
        Expression ambito = mc.getScope().get();
        if (permiteCadena) {
            while (ambito instanceof MethodCallExpr sup && sup.getScope().isPresent()) {
                ambito = sup.getScope().get();
            }
        }
        if (!(ambito instanceof NameExpr ne) || !ne.getNameAsString().equals(mock)) {
            errorLlamada(ruta, llamada);
            return null;
        }
        return mc;
    }

    private void errorLlamada(String ruta, String llamada) {
        res.error(ruta, "LLAMADA_INVALIDA", "La llamada debe ser un único método del mock, sin encadenar: " + llamada,
                "Ejemplo: findById(anyLong()); para encadenar declara el mock en construccion.profundos");
    }

    /**
     * Claves de construccion.argumentos: dependencias (salvo estáticas y new internos sin campo) o campos de
     * instancia de la clase. Las dependencias mock pasan a {@code sv.reales}. Devuelve las claves válidas.
     */
    private List<String> clavesArgumentos(Map<String, String> argumentos, String ruta, AnalisisServicio an, InfoClase clase,
                                          ResultadoValidacion.ServicioValidado sv) {
        List<String> validas = new ArrayList<>();
        for (Map.Entry<String, String> e : argumentos.entrySet()) {
            String clave = e.getKey();
            Dependencia d = an.dependencia(clave);
            boolean dependencia = d != null && d.obtencion != Dependencia.TipoObtencion.ESTATICA
                    && !(d.obtencion == Dependencia.TipoObtencion.NEW_INTERNO && d.campo == null);
            InfoClase.InfoCampo f = clase.campo(clave);
            boolean campo = f != null && !f.estatico();
            if (!dependencia && !campo) {
                List<String> opciones = new ArrayList<>();
                for (Dependencia x : an.dependencias) {
                    if (x.obtencion != Dependencia.TipoObtencion.ESTATICA
                            && !(x.obtencion == Dependencia.TipoObtencion.NEW_INTERNO && x.campo == null)) {
                        opciones.add(x.nombre);
                    }
                }
                for (InfoClase.InfoCampo x : clase.campos) {
                    if (!x.estatico() && !opciones.contains(x.nombre())) {
                        opciones.add(x.nombre());
                    }
                }
                res.error(ruta + ".construccion.argumentos." + clave, "ARGUMENTO_DESCONOCIDO", "'" + clave
                                + "' no es una dependencia ni un campo de instancia de " + clase.simple + ".",
                        "Dependencias y campos válidos: " + opciones);
                continue;
            }
            if (dependencia && d.esMockDeclarado()) {
                sv.reales.put(clave, e.getValue());
            }
            validas.add(clave);
        }
        return validas;
    }

    /**
     * Avisa si las pistas de módulo (servicios[].modulo o proyecto.modulo) no coinciden con el módulo real de la clase
     * y canoniza {@code s.modulo} si venía informado. Nunca es error.
     */
    private void avisoModulo(ServicioSpec s, String ruta, ProyectoEscaneado proyecto, String moduloSpec,
                             ModuloEscaneado me, InfoClase clase) {
        if (s.modulo != null) {
            if (!s.modulo.isBlank()) {
                ModuloEscaneado pista = proyecto.modulo(s.modulo);
                if (pista == null) {
                    res.aviso(ruta + ".modulo", "MODULO_DESCONOCIDO", "No existe el módulo '" + s.modulo + "': "
                                    + clase.simple + " está en el módulo '" + me.modulo.nombre() + "' y se usa ese módulo.",
                            "Módulos: " + proyecto.modulos.stream().map(m -> m.modulo.nombre()).toList());
                } else if (pista != me) {
                    res.aviso(ruta + ".modulo", "MODULO_CORREGIDO", textoModuloCorregido(clase, me, s.modulo), null);
                }
            }
            s.modulo = me.modulo.rel.isEmpty() ? null : me.modulo.rel;
        } else if (moduloSpec != null) {
            ModuloEscaneado pista = proyecto.modulo(moduloSpec);
            if (pista != null && pista != me) {
                res.aviso(ruta + ".clase", "MODULO_CORREGIDO", textoModuloCorregido(clase, me, moduloSpec), null);
            }
        }
    }

    private static String textoModuloCorregido(InfoClase clase, ModuloEscaneado me, String pista) {
        return clase.simple + " está en el módulo '" + me.modulo.nombre() + "', no en '" + pista
                + "': se usa ese módulo; no hace falta cambiar nada.";
    }

    /**
     * Quita de construccion.argumentos las creaciones de mock ({@link ArgumentoMock}) sobre dependencias que junit-gen
     * ya declara como mock, con aviso. Sin Mockito, cualquier argumento con forma de mock es error. Devuelve los nombres
     * que pedían deep stubs.
     */
    private Set<String> normalizarArgumentosMock(ServicioSpec s, String ruta, AnalisisServicio an, ModuloEscaneado me) {
        Set<String> profundos = new LinkedHashSet<>();
        if (s.construccion == null || s.construccion.argumentos == null || s.construccion.argumentos.isEmpty()) {
            return profundos;
        }
        Set<String> excluidas = new LinkedHashSet<>();
        for (Map.Entry<String, String> e : s.construccion.argumentos.entrySet()) {
            String clave = e.getKey();
            ArgumentoMock.Forma forma = ArgumentoMock.forma(e.getValue());
            if (forma == ArgumentoMock.Forma.NO || clave == null) {
                continue;
            }
            String rutaArg = ruta + ".construccion.argumentos." + clave;
            if (!me.versiones.mockito) {
                res.error(rutaArg, "SIN_MOCKITO", "El módulo " + me.modulo.nombre() + " no tiene Mockito: '" + clave
                        + "' no puede ser un mock.", "Usa una instancia real o un valor en construccion.argumentos.");
                continue;
            }
            Dependencia d = an.dependencia(clave);
            if (d != null && d.esMockDeclarado() && d.realPorDefecto == null) {
                excluidas.add(clave);
                res.aviso(rutaArg, "ARGUMENTO_MOCK_IGNORADO", "'" + clave
                                + "' ya se declara como @Mock (con deep stubs si hace falta) y sus campos derivados se inyectan"
                                + " solos: se ignora el mock(...) de construccion.argumentos.",
                        "Quita esa clave: en argumentos solo van instancias reales o valores; para deep stubs usa"
                                + " construccion.profundos.");
                if (forma == ArgumentoMock.Forma.PROFUNDO) {
                    profundos.add(clave);
                }
            }
        }
        if (!excluidas.isEmpty()) {
            Map<String, String> limpio = new LinkedHashMap<>();
            for (Map.Entry<String, String> e : s.construccion.argumentos.entrySet()) {
                if (!excluidas.contains(e.getKey())) {
                    limpio.put(e.getKey(), e.getValue());
                }
            }
            s.construccion.argumentos = limpio;
        }
        return profundos;
    }

    /** construccion.profundos, construccion.stubs y construccion.postConstruct. */
    private void validarMontaje(ConstruccionSpec cs, String ruta, AnalisisServicio an, List<Dependencia> mocks,
                                ResultadoValidacion.ServicioValidado sv, Set<String> profundosDeArgumentos) {
        Set<String> nombresMocks = new LinkedHashSet<>();
        for (Dependencia d : mocks) {
            nombresMocks.add(d.nombre);
            if (d.profundo) {
                sv.profundos.add(d.nombre);
            }
        }
        for (String n : profundosDeArgumentos) {
            if (!nombresMocks.contains(n)) {
                continue;
            }
            sv.profundos.add(n);
            if (cs != null && (cs.profundos == null || !cs.profundos.contains(n))) {
                List<String> copia = cs.profundos == null ? new ArrayList<>() : new ArrayList<>(cs.profundos);
                copia.add(n);
                cs.profundos = copia;
            }
        }
        if (cs == null) {
            return;
        }
        if (cs.profundos != null) {
            for (int k = 0; k < cs.profundos.size(); k++) {
                String n = cs.profundos.get(k);
                if (n == null || !nombresMocks.contains(n)) {
                    res.error(ruta + ".profundos[" + k + "]", "PROFUNDO_DESCONOCIDO", "'" + n + "' no es un mock de "
                            + sv.clase.simple + ".", "Mocks: " + nombresMocks);
                    continue;
                }
                sv.profundos.add(n);
            }
        }
        if (cs.stubs != null) {
            if (cs.stubs.size() > MAX_STUBS_MONTAJE) {
                res.error(ruta + ".stubs", "DEMASIADOS_STUBS", "Máximo " + MAX_STUBS_MONTAJE + " stubs de montaje.", null);
            }
            for (int k = 0; k < cs.stubs.size() && k < MAX_STUBS_MONTAJE; k++) {
                StubSpec st = cs.stubs.get(k);
                String rs = ruta + ".stubs[" + k + "]";
                if (st == null) {
                    res.error(rs, "NULO", "Stub nulo.", null);
                    continue;
                }
                if (st.mock == null || !nombresMocks.contains(st.mock)) {
                    res.error(rs + ".mock", "MOCK_DESCONOCIDO", "'" + st.mock + "' no es un mock de " + sv.clase.simple + ".",
                            "Mocks disponibles: " + nombresMocks);
                    continue;
                }
                Dependencia d = an.dependencia(st.mock);
                String forma = validarStub(st, rs, st.mock, d.tipoFqn != null ? d.tipoFqn : d.tipo);
                if (forma != null) {
                    sv.formaStubMontaje.put(k, forma);
                }
            }
        }
        if (Boolean.TRUE.equals(cs.postConstruct) && an.postConstruct.isEmpty()) {
            res.aviso(ruta + ".postConstruct", "SIN_POSTCONSTRUCT", sv.clase.simple
                    + " no tiene métodos @PostConstruct sin parámetros: se ignora.", "Elimina postConstruct.");
        }
        sv.postConstruct = Boolean.TRUE.equals(cs.postConstruct) && !an.postConstruct.isEmpty();
    }

    private boolean usaDependencia(InfoClase clase, InfoMetodo m, String nombre) {
        Pattern p = Pattern.compile("\\b" + Pattern.quote(nombre) + "\\b");
        if (p.matcher(m.cuerpo()).find()) {
            return true;
        }
        for (InfoMetodo otro : clase.metodos) {
            if (!otro.publico && p.matcher(otro.cuerpo()).find()) {
                return true;
            }
        }
        return false;
    }

    private Expression expresion(String texto, String ruta, boolean obligatoria) {
        if (texto == null || texto.isBlank()) {
            if (obligatoria) {
                res.error(ruta, "FALTA_EXPRESION", "Falta la expresión Java.", null);
            }
            return null;
        }
        if (texto.length() > MAX_EXPRESION) {
            res.error(ruta, "EXPRESION_LARGA", "La expresión supera " + MAX_EXPRESION + " caracteres.", "Usa una fixture.");
            return null;
        }
        ParseResult<Expression> r = IndiceTipos.parser().parseExpression(texto);
        if (!r.isSuccessful() || r.getResult().isEmpty()) {
            res.error(ruta, "EXPRESION_INVALIDA", "No es una expresión Java válida: " + Textos.recortar(texto, 200),
                    r.getProblems().isEmpty() ? null : primera(r.getProblems().get(0).getMessage()));
            return null;
        }
        Expression e = r.getResult().get();
        compat(e, ruta);
        tiposUsados.addAll(ResolutorImports.tiposUsados(e, variables));
        return e;
    }

    private Statement sentencia(String texto, String ruta) {
        if (texto == null || texto.isBlank()) {
            res.error(ruta, "FALTA_SENTENCIA", "Sentencia vacía.", null);
            return null;
        }
        if (texto.length() > MAX_EXPRESION) {
            res.error(ruta, "SENTENCIA_LARGA", "La sentencia supera " + MAX_EXPRESION + " caracteres.", null);
            return null;
        }
        Statement st = parsearSentencia(texto);
        if (st == null) {
            res.error(ruta, "SENTENCIA_INVALIDA", "No es una sentencia Java válida: " + Textos.recortar(texto, 200), null);
            return null;
        }
        if (!(st instanceof ExpressionStmt || st instanceof IfStmt || st instanceof ForStmt || st instanceof ForEachStmt)) {
            res.error(ruta, "SENTENCIA_NO_PERMITIDA", "Solo se permiten expresiones, declaraciones, if y bucles for.", null);
            return null;
        }
        compat(st, ruta);
        tiposUsados.addAll(ResolutorImports.tiposUsados(st, variables));
        return st;
    }

    /**
     * Sustituye cada elemento de {@code preparacion} que contiene varias sentencias (y que como sentencia
     * única no parsea) por sus sentencias sueltas, si el conjunto parsea como bloque. En otro caso deja el
     * elemento tal cual (la validación posterior da el error de siempre). Devuelve una lista nueva.
     */
    private List<String> separarPreparacion(List<String> preparacion, String ruta) {
        List<String> r = new ArrayList<>();
        for (int k = 0; k < preparacion.size(); k++) {
            String texto = preparacion.get(k);
            List<String> trozos = trozosPreparacion(texto);
            if (trozos == null) {
                r.add(texto);
                continue;
            }
            r.addAll(trozos);
            res.aviso(ruta + ".preparacion[" + k + "]", "PREPARACION_SEPARADA", "Contenía " + trozos.size()
                    + " sentencias; se han separado (una por elemento).", "Escribe una sentencia por elemento.");
        }
        return r;
    }

    /** Sentencias sueltas de {@code texto} si contiene 2 o más y parsea como bloque; null en otro caso. */
    private static List<String> trozosPreparacion(String texto) {
        if (texto == null || texto.isBlank() || texto.length() > MAX_CADENA_PREPARACION
                || parsearSentencia(texto) != null) {
            return null;
        }
        String t = texto.trim();
        String bloque = "{" + t + (t.endsWith(";") || t.endsWith("}") ? "" : ";") + "\n}";
        ParseResult<BlockStmt> r = IndiceTipos.parser().parseBlock(bloque);
        if (!r.isSuccessful() || r.getResult().isEmpty()) {
            return null;
        }
        List<String> trozos = new ArrayList<>();
        for (Statement st : r.getResult().get().getStatements()) {
            if (!(st instanceof EmptyStmt)) {
                trozos.add(st.toString());
            }
        }
        return trozos.size() >= 2 ? trozos : null;
    }

    public static Statement parsearSentencia(String texto) {
        String t = texto.trim();
        if (!t.endsWith(";") && !t.endsWith("}")) {
            t = t + ";";
        }
        ParseResult<Statement> r = IndiceTipos.parser().parseStatement(t);
        return r.isSuccessful() ? r.getResult().orElse(null) : null;
    }

    private void tipo(String texto, String ruta) {
        if (texto == null || texto.isBlank()) {
            res.error(ruta, "FALTA_TIPO", "Falta el tipo.", null);
            return;
        }
        if (texto.length() > 300) {
            res.error(ruta, "TIPO_LARGO", "Tipo demasiado largo.", null);
            return;
        }
        if ("var".equals(texto.trim())) {
            return;
        }
        ParseResult<Type> r = IndiceTipos.parser().parseType(texto);
        if (!r.isSuccessful() || r.getResult().isEmpty()) {
            res.error(ruta, "TIPO_INVALIDO", "Tipo Java no válido: " + texto, null);
            return;
        }
        compat(r.getResult().get(), ruta);
        tiposUsados.addAll(ResolutorImports.tiposUsados(r.getResult().get(), Set.of()));
    }

    private void tiposEnTexto(String tipo) {
        ParseResult<Type> r = IndiceTipos.parser().parseType(tipo);
        if (r.isSuccessful() && r.getResult().isPresent()) {
            tiposUsados.addAll(ResolutorImports.tiposUsados(r.getResult().get(), Set.of()));
        }
    }

    private void compat(Node n, String ruta) {
        for (String p : CompatibilidadJava.comprobar(n, java)) {
            res.error(ruta, "INCOMPATIBLE_JAVA", "Incompatible con Java " + java + ": " + p + ".",
                    "Usa construcciones disponibles en Java " + java + ".");
        }
    }

    private static String primera(String s) {
        int i = s.indexOf('\n');
        return i >= 0 ? s.substring(0, i) : s;
    }

    /** Perfil que se usará para el módulo (expuesto para el informe). */
    public static PerfilPlantilla perfil(ModuloEscaneado me, EstiloSpec estilo) {
        return PerfilPlantilla.de(me.versiones, estilo);
    }
}
