package com.junitgen.generacion;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.Position;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.InitializerDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.ClassExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MarkerAnnotationExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import com.junitgen.escaneo.Dependencia;
import com.junitgen.escaneo.EmparejadorTests;
import com.junitgen.escaneo.IndiceTipos;
import com.junitgen.spec.ResultadoValidacion;
import com.junitgen.util.Textos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Decide si los casos nuevos de un servicio se pueden añadir con seguridad al test que ya existe en el proyecto
 * (copia ampliada) y con qué nombres: el campo del servicio y el campo mock de cada dependencia. Inmutable.
 */
public final class PlanMezcla {

    /** Parser con tokens: la mezcla necesita las posiciones (líneas) de la clase, los imports y los nombres. */
    private static final ThreadLocal<JavaParser> PARSER_POSICIONES = ThreadLocal.withInitial(() -> {
        ParserConfiguration c = IndiceTipos.config(ParserConfiguration.LanguageLevel.BLEEDING_EDGE);
        c.setStoreTokens(true);
        return new JavaParser(c);
    });

    public enum Estilo { MOCKITO, SPRING }

    private static final Set<String> CICLO = Set.of("BeforeEach", "BeforeAll", "AfterEach", "AfterAll");
    private static final Set<String> ANOT_MOCK_MOCKITO = Set.of("Mock");
    private static final Set<String> ANOT_MOCK_SPRING = Set.of("MockBean", "MockitoBean");
    private static final Set<String> ANOT_PROTEGIDAS = Set.of("Mock", "MockBean", "MockitoBean", "Spy", "SpyBean", "InjectMocks");
    private static final String MOTIVO_MONTAJE = "este servicio necesita un montaje (valores, stubs o instancias) que tu test no prepara igual";

    public final boolean ok;
    public final String motivo;
    public final Estilo estilo;
    public final String campoSut;
    /** Nombre de la dependencia en junit-gen → nombre del campo mock en el test del usuario (solo mocks). */
    public final Map<String, String> alias;
    /** Línea (1-based) tras la que va el bloque de imports; 0 = al principio. */
    public final int lineaTrasImports;
    /** Línea (1-based, sobre el núcleo) de la llave de cierre de la clase. */
    public final int lineaCierre;

    private PlanMezcla(boolean ok, String motivo, Estilo estilo, String campoSut, Map<String, String> alias, int lineaTrasImports,
                       int lineaCierre) {
        this.ok = ok;
        this.motivo = motivo;
        this.estilo = estilo;
        this.campoSut = campoSut;
        this.alias = alias == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(alias));
        this.lineaTrasImports = lineaTrasImports;
        this.lineaCierre = lineaCierre;
    }

    private static PlanMezcla no(String motivo) {
        return new PlanMezcla(false, motivo, null, null, null, 0, 0);
    }

    /** true si ambos son mezclables con el mismo estilo, campo del servicio y alias de mocks. */
    public boolean compatibleCon(PlanMezcla otro) {
        return otro != null && ok && otro.ok && estilo == otro.estilo && Objects.equals(campoSut, otro.campoSut)
                && alias.equals(otro.alias);
    }

    /** Analiza el núcleo del test existente (criterios de seguridad de la mezcla); el primer incumplimiento es el motivo. */
    public static PlanMezcla analizar(String nucleo, ResultadoValidacion.ServicioValidado sv, PerfilPlantilla perfil) {
        if (nucleo == null) {
            return no("tu test tiene marcadores junit-gen incompletos");
        }
        if (nucleo.contains("junit-gen:")) {
            return no("tu test ya contiene marcadores de junit-gen (lo generó junit-gen entero)");
        }
        if (nucleo.length() > CopiaAmpliada.MAX_CARACTERES) {
            return no("tu test es demasiado grande (más de " + CopiaAmpliada.MAX_CARACTERES + " caracteres)");
        }
        String paraParsear = nucleo.startsWith("﻿") ? nucleo.substring(1) : nucleo;
        ParseResult<CompilationUnit> pr;
        try {
            pr = PARSER_POSICIONES.get().parse(paraParsear);
        } catch (RuntimeException e) {
            return no("tu test no se ha podido analizar");
        }
        if (!pr.isSuccessful() || pr.getResult().isEmpty()) {
            return no("tu test no se ha podido analizar");
        }
        CompilationUnit cu = pr.getResult().get();
        String nombreTest = sv.clase.simple + "Test";
        // 2. Un único tipo: clase concreta <Servicio>Test.
        if (cu.getTypes().size() != 1) {
            return no("tu archivo no declara exactamente una clase");
        }
        TypeDeclaration<?> td = cu.getType(0);
        if (!(td instanceof ClassOrInterfaceDeclaration cls) || cls.isInterface() || cls.isAbstract()
                || !nombreTest.equals(cls.getNameAsString())) {
            return no("tu archivo no declara una clase concreta llamada " + nombreTest);
        }
        // 3. Estructura de la clase.
        if (!cls.getExtendedTypes().isEmpty()) {
            return no("tu clase de test hereda de otra (extends)");
        }
        if (!cls.getConstructors().isEmpty()) {
            return no("tu clase de test tiene un constructor propio");
        }
        if (cls.getMembers().stream().anyMatch(m -> m instanceof InitializerDeclaration)) {
            return no("tu clase de test tiene un bloque inicializador");
        }
        for (Node m : cls.getMembers()) {
            if (m instanceof ClassOrInterfaceDeclaration interna && tiene(interna, Set.of("Nested"))) {
                return no("tu clase de test tiene clases @Nested");
            }
        }
        if (tiene(cls, Set.of("TestInstance"))) {
            return no("tu clase de test usa @TestInstance");
        }
        if (tiene(cls, Set.of("RunWith"))) {
            return no("tu clase de test usa @RunWith (JUnit 4)");
        }
        // 4. Imports.
        for (ImportDeclaration imp : cu.getImports()) {
            String n = imp.getNameAsString();
            String min = n.toLowerCase(Locale.ROOT);
            if (n.startsWith("org.junit.") && !n.startsWith("org.junit.jupiter.") && !n.startsWith("org.junit.platform.")) {
                return no("tu test usa JUnit 4 (" + n + ")");
            }
            if (min.contains("powermock") || n.startsWith("org.testng")) {
                return no("tu test usa " + (min.contains("powermock") ? "PowerMock" : "TestNG"));
            }
        }
        // 5. Estilo.
        Set<String> extensiones = extensiones(cls);
        boolean spring = cls.getAnnotations().stream().anyMatch(a -> EmparejadorTests.ANOT_SPRING_TEST.contains(simple(a.getNameAsString())))
                || extensiones.contains("SpringExtension");
        boolean mockito = !extensiones.isEmpty() && extensiones.stream().allMatch("MockitoExtension"::equals);
        if (spring && mockito) {
            return no("tu test mezcla Spring y MockitoExtension");
        }
        if (!spring && !mockito) {
            return no("no se reconoce cómo prepara tu test el servicio (ni @ExtendWith(MockitoExtension.class) ni un test de Spring)");
        }
        Estilo estilo = spring ? Estilo.SPRING : Estilo.MOCKITO;
        // 6. Campo del servicio.
        String servicio = sv.clase.simple;
        for (ImportDeclaration imp : cu.getImports()) {
            String n = imp.getNameAsString();
            if (!imp.isStatic() && !imp.isAsterisk() && n.endsWith("." + servicio) && !n.equals(sv.clase.fqn)) {
                return no("tu test importa otra clase llamada " + servicio + " (" + n + ")");
            }
        }
        List<Campo> campos = campos(cls);
        String campoSut;
        if (estilo == Estilo.MOCKITO) {
            List<Campo> inject = campos.stream().filter(c -> c.anotaciones.contains("InjectMocks")).toList();
            if (inject.size() != 1) {
                return no("tu test no tiene exactamente un campo @InjectMocks");
            }
            Campo c = inject.get(0);
            if (c.anotaciones.contains("Spy") || c.inicializado || !servicio.equals(c.tipoSimple)) {
                return no("el campo @InjectMocks de tu test no es un " + servicio + " sin inicializar y sin @Spy");
            }
            campoSut = c.nombre;
        } else {
            if (campos.stream().anyMatch(c -> c.anotaciones.contains("InjectMocks"))) {
                return no("tu test de Spring usa @InjectMocks");
            }
            List<Campo> aut = campos.stream().filter(c -> c.anotaciones.contains("Autowired") && servicio.equals(c.tipoSimple)).toList();
            if (aut.size() != 1) {
                return no("tu test no tiene exactamente un campo @Autowired de tipo " + servicio);
            }
            campoSut = aut.get(0).nombre;
        }
        // 7. Un mock del usuario por cada dependencia mock.
        Set<String> anotMock = estilo == Estilo.MOCKITO ? ANOT_MOCK_MOCKITO : ANOT_MOCK_SPRING;
        Map<String, String> alias = new LinkedHashMap<>();
        for (Dependencia d : sv.mocks()) {
            String tipo = Textos.nombreSimple(Textos.quitarGenericos(d.tipo));
            List<Campo> cands = new ArrayList<>();
            for (Campo c : campos) {
                if (c.mockSimple && !Collections.disjoint(c.anotaciones, anotMock) && tipo.equals(c.tipoSimple)) {
                    cands.add(c);
                }
            }
            if (cands.isEmpty()) {
                return no("tu test no tiene un mock de " + tipo + "; añadirlo cambiaría lo que reciben tus tests");
            }
            if (cands.size() == 1) {
                alias.put(d.nombre, cands.get(0).nombre);
            } else {
                Campo mismo = cands.stream().filter(c -> c.nombre.equals(d.nombre)).findFirst().orElse(null);
                if (mismo == null) {
                    return no("tu test tiene varios mocks de " + tipo + " y no se sabe cuál corresponde a '" + d.nombre + "'");
                }
                alias.put(d.nombre, mismo.nombre);
            }
        }
        // 8. Métodos de ciclo de vida sin tocar el servicio ni los mocks.
        Set<String> protegidos = new LinkedHashSet<>();
        protegidos.add(campoSut);
        for (Campo c : campos) {
            if (!Collections.disjoint(c.anotaciones, ANOT_PROTEGIDAS)) {
                protegidos.add(c.nombre);
            }
        }
        for (MethodDeclaration md : cls.getMethods()) {
            if (!tiene(md, CICLO) || md.getBody().isEmpty()) {
                continue;
            }
            Node cuerpo = md.getBody().get();
            boolean toca = cuerpo.findAll(NameExpr.class).stream().anyMatch(n -> protegidos.contains(n.getNameAsString()))
                    || cuerpo.findAll(FieldAccessExpr.class).stream()
                    .anyMatch(f -> f.getScope().isThisExpr() && protegidos.contains(f.getNameAsString()))
                    || cuerpo.findAll(MethodCallExpr.class).stream()
                    .anyMatch(m -> "openMocks".equals(m.getNameAsString()) || "initMocks".equals(m.getNameAsString()));
            if (toca) {
                return no("tu método " + md.getNameAsString() + " prepara o modifica el servicio o sus mocks");
            }
        }
        // 9. Montaje de junit-gen solo con mocks.
        String montaje = new PlantillaTest(perfil, sv).motivoMontajeNoSimple(estilo == Estilo.SPRING);
        if (montaje != null) {
            return no(montaje);
        }
        // 10. Formato: posiciones de inserción.
        List<String> lineas = CopiaAmpliada.lineas(nucleo);
        if (cls.getEnd().isEmpty() || cls.getBegin().isEmpty()) {
            return no("no se ha podido localizar la clase en tu archivo");
        }
        int cierre = cls.getEnd().get().line;
        if (cierre < 1 || cierre > lineas.size() || !"}".equals(lineas.get(cierre - 1).strip())) {
            return no("la llave de cierre de tu clase no está sola en su línea");
        }
        int trasImports = 0;
        for (ImportDeclaration imp : cu.getImports()) {
            if (imp.getEnd().isPresent()) {
                trasImports = Math.max(trasImports, imp.getEnd().get().line);
            }
        }
        if (cu.getImports().isEmpty() && cu.getPackageDeclaration().isPresent() && cu.getPackageDeclaration().get().getEnd().isPresent()) {
            trasImports = cu.getPackageDeclaration().get().getEnd().get().line;
        }
        int inicioClase = cls.getBegin().get().line;
        for (AnnotationExpr a : cls.getAnnotations()) {
            if (a.getBegin().isPresent()) {
                inicioClase = Math.min(inicioClase, a.getBegin().get().line);
            }
        }
        if (trasImports >= inicioClase || trasImports >= cierre) {
            return no("los imports y la clase de tu archivo comparten línea");
        }
        return new PlanMezcla(true, null, estilo, campoSut, alias, trasImports, cierre);
    }

    /**
     * Renombra en el método ya renderizado cada {@code NameExpr} cuyo nombre es clave de {@code mapa} y no está
     * declarado en el método (variable local o parámetro); si el nombre nuevo sí está declarado, usa
     * {@code this.<nuevo>}. Si no se puede analizar, devuelve el texto sin cambios.
     */
    public static String renombrar(String texto, Map<String, String> mapa) {
        if (texto == null || mapa == null || mapa.isEmpty()) {
            return texto;
        }
        ParseResult<BodyDeclaration<?>> pr;
        try {
            pr = PARSER_POSICIONES.get().parseBodyDeclaration(texto);
        } catch (RuntimeException e) {
            pr = null;
        }
        if (pr == null || !pr.isSuccessful() || pr.getResult().isEmpty()) {
            System.err.println("junit-gen: no se pudo analizar un caso para usar los nombres de tu test; se deja como está");
            return texto;
        }
        BodyDeclaration<?> bd = pr.getResult().get();
        Set<String> declarados = new LinkedHashSet<>();
        bd.findAll(VariableDeclarator.class).forEach(v -> declarados.add(v.getNameAsString()));
        bd.findAll(Parameter.class).forEach(p -> declarados.add(p.getNameAsString()));
        int[] inicioLinea = iniciosLinea(texto);
        List<int[]> rangos = new ArrayList<>();
        List<String> reemplazos = new ArrayList<>();
        for (NameExpr n : bd.findAll(NameExpr.class)) {
            String nombre = n.getNameAsString();
            String nuevo = mapa.get(nombre);
            if (nuevo == null || declarados.contains(nombre) || n.getRange().isEmpty()) {
                continue;
            }
            int ini = offset(inicioLinea, n.getRange().get().begin);
            int fin = offset(inicioLinea, n.getRange().get().end) + 1;
            if (ini < 0 || fin > texto.length() || ini >= fin || !texto.substring(ini, fin).equals(nombre)) {
                System.err.println("junit-gen: posiciones inesperadas al renombrar un caso; se deja como está");
                return texto;
            }
            rangos.add(new int[]{ini, fin});
            reemplazos.add(declarados.contains(nuevo) ? "this." + nuevo : nuevo);
        }
        if (rangos.isEmpty()) {
            return texto;
        }
        List<Integer> orden = new ArrayList<>();
        for (int i = 0; i < rangos.size(); i++) {
            orden.add(i);
        }
        orden.sort((a, b) -> Integer.compare(rangos.get(b)[0], rangos.get(a)[0]));
        StringBuilder sb = new StringBuilder(texto);
        int limite = Integer.MAX_VALUE;
        for (int i : orden) {
            int[] r = rangos.get(i);
            if (r[1] > limite) {
                System.err.println("junit-gen: rangos solapados al renombrar un caso; se deja como está");
                return texto;
            }
            sb.replace(r[0], r[1], reemplazos.get(i));
            limite = r[0];
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- utilidades

    private record Campo(String nombre, String tipoSimple, Set<String> anotaciones, boolean inicializado, boolean mockSimple) {
    }

    private static List<Campo> campos(ClassOrInterfaceDeclaration cls) {
        List<Campo> r = new ArrayList<>();
        for (FieldDeclaration fd : cls.getFields()) {
            Set<String> anot = new LinkedHashSet<>();
            boolean sinArgumentos = true;
            for (AnnotationExpr a : fd.getAnnotations()) {
                String s = simple(a.getNameAsString());
                anot.add(s);
                if (ANOT_PROTEGIDAS.contains(s) && !(a instanceof MarkerAnnotationExpr)) {
                    sinArgumentos = false;
                }
            }
            for (VariableDeclarator v : fd.getVariables()) {
                String tipo = Textos.nombreSimple(Textos.quitarGenericos(v.getType().asString()));
                r.add(new Campo(v.getNameAsString(), tipo, anot, v.getInitializer().isPresent(), sinArgumentos));
            }
        }
        return r;
    }

    /** Nombres simples de las clases de {@code @ExtendWith} de la clase. */
    private static Set<String> extensiones(ClassOrInterfaceDeclaration cls) {
        Set<String> r = new LinkedHashSet<>();
        for (AnnotationExpr a : cls.getAnnotations()) {
            if (!"ExtendWith".equals(simple(a.getNameAsString()))) {
                continue;
            }
            Expression valor = null;
            if (a instanceof SingleMemberAnnotationExpr s) {
                valor = s.getMemberValue();
            } else if (a instanceof NormalAnnotationExpr na) {
                for (MemberValuePair p : na.getPairs()) {
                    if ("value".equals(p.getNameAsString())) {
                        valor = p.getValue();
                    }
                }
            }
            if (valor == null) {
                r.add("?");
                continue;
            }
            NodeList<Expression> valores = valor instanceof ArrayInitializerExpr ai ? ai.getValues() : new NodeList<>(valor);
            for (Expression e : valores) {
                r.add(e instanceof ClassExpr ce ? simple(Textos.quitarGenericos(ce.getType().asString())) : "?");
            }
        }
        return r;
    }

    private static boolean tiene(NodeWithAnnotations<?> n, Set<String> nombres) {
        for (AnnotationExpr a : n.getAnnotations()) {
            if (nombres.contains(simple(a.getNameAsString()))) {
                return true;
            }
        }
        return false;
    }

    private static String simple(String nombre) {
        int p = nombre.lastIndexOf('.');
        return p < 0 ? nombre : nombre.substring(p + 1);
    }

    private static int[] iniciosLinea(String texto) {
        List<Integer> r = new ArrayList<>();
        r.add(0);
        for (int i = 0; i < texto.length(); i++) {
            if (texto.charAt(i) == '\n') {
                r.add(i + 1);
            }
        }
        int[] a = new int[r.size()];
        for (int i = 0; i < a.length; i++) {
            a[i] = r.get(i);
        }
        return a;
    }

    private static int offset(int[] inicioLinea, Position p) {
        if (p.line < 1 || p.line > inicioLinea.length || p.column < 1) {
            return -1;
        }
        return inicioLinea[p.line - 1] + p.column - 1;
    }
}
