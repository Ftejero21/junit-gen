package com.junitgen.duplicados;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.ClassExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.junitgen.escaneo.EmparejadorTests;
import com.junitgen.escaneo.InfoClase;
import com.junitgen.escaneo.InfoMetodo;
import com.junitgen.escaneo.IndiceTipos;
import com.junitgen.util.Textos;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extrae huellas de los tests existentes (una por llamada al servicio bajo prueba) y lee los
 * marcadores de los tests ya generados en la carpeta de salida.
 */
public final class ExtractorHuellasTests {

    public record HuellaExistente(Huella huella, String test, String metodoTest) {
    }

    public record Marcador(String id, String huella, String entrada, String contenido, int lineaInicio, int lineaFin,
                           String metodoTest, boolean apartado) {
        public Marcador(String id, String huella, String entrada, String contenido, int lineaInicio, int lineaFin,
                        String metodoTest) {
            this(id, huella, entrada, contenido, lineaInicio, lineaFin, metodoTest, false);
        }
    }

    /** Marca (en comentario) de un caso apartado automáticamente: su cuerpo queda comentado. */
    public static final String MARCA_APARTADO = "junit-gen:apartado";

    private static final Pattern MARCADOR_INICIO = Pattern.compile("//\\s*junit-gen:caso-inicio\\s+(.*)$");
    private static final Pattern MARCADOR_FIN = Pattern.compile("//\\s*junit-gen:caso-fin\\s+id=(\\S+)");
    private static final Pattern METODO = Pattern.compile("\\bvoid\\s+([A-Za-z_$][\\w$]*)\\s*\\(");
    private static final Set<String> STUB_RESULTADO = Set.of("thenReturn", "thenThrow", "thenAnswer", "thenCallRealMethod",
            "willReturn", "willThrow", "willAnswer", "willCallRealMethod");
    private static final Set<String> STUB_DO = Set.of("doReturn", "doThrow", "doAnswer", "doNothing", "doCallRealMethod",
            "willReturn", "willThrow", "willAnswer", "willDoNothing");

    private ExtractorHuellasTests() {
    }

    /** Lee los marcadores {@code junit-gen:caso-inicio/fin} de un test generado. */
    public static List<Marcador> marcadores(String texto) {
        List<Marcador> r = new ArrayList<>();
        String[] lineas = texto.split("\\R", -1);
        for (int i = 0; i < lineas.length; i++) {
            Matcher m = MARCADOR_INICIO.matcher(lineas[i].trim());
            if (!m.find()) {
                continue;
            }
            Map<String, String> kv = new LinkedHashMap<>();
            for (String par : m.group(1).trim().split("\\s+")) {
                int eq = par.indexOf('=');
                if (eq > 0) {
                    kv.put(par.substring(0, eq), par.substring(eq + 1));
                }
            }
            String id = kv.get("id");
            int fin = -1;
            String metodo = null;
            boolean apartado = false;
            for (int j = i + 1; j < lineas.length; j++) {
                Matcher mf = MARCADOR_FIN.matcher(lineas[j].trim());
                if (mf.find() && mf.group(1).equals(id)) {
                    fin = j;
                    break;
                }
                String linea = lineas[j];
                if (!apartado && linea.trim().startsWith("// " + MARCA_APARTADO)) {
                    apartado = true;
                    continue;
                }
                if (apartado) {
                    String t = linea.trim();
                    linea = t.startsWith("// ") ? t.substring(3) : t.equals("//") ? "" : t;
                }
                if (metodo == null && !linea.trim().startsWith("//")) {
                    Matcher mm = METODO.matcher(linea);
                    if (mm.find()) {
                        metodo = mm.group(1);
                    }
                }
            }
            r.add(new Marcador(id, kv.get("huella"), kv.get("entrada"), kv.get("contenido"), i + 1, fin + 1, metodo, apartado));
        }
        return r;
    }

    public static List<HuellaExistente> extraer(InfoClase test, InfoClase servicio, IndiceTipos idx) {
        List<HuellaExistente> r = new ArrayList<>();
        Set<String> sut = EmparejadorTests.nombresSut(test, servicio, idx);
        if (sut.isEmpty()) {
            return r;
        }
        NormalizadorExpresiones base = new NormalizadorExpresiones();
        List<Statement> setUp = new ArrayList<>();
        for (FieldDeclaration f : test.decl.findAll(FieldDeclaration.class)) {
            Set<String> anns = InfoClase.nombresAnotaciones(f.getAnnotations());
            for (VariableDeclarator v : f.getVariables()) {
                if (anns.contains("Mock") || anns.contains("MockBean") || anns.contains("Spy") || anns.contains("MockitoBean")) {
                    base.mock(v.getNameAsString(), Textos.nombreSimple(v.getTypeAsString()));
                } else if (v.getInitializer().isPresent()) {
                    Expression ini = v.getInitializer().get();
                    String tipoMock = tipoMockDe(ini);
                    if (tipoMock != null) {
                        base.mock(v.getNameAsString(), tipoMock);
                    } else if (!sut.contains(v.getNameAsString())) {
                        base.declarar(v.getNameAsString(), ini);
                    }
                }
            }
        }
        for (MethodDeclaration md : test.decl.findAll(MethodDeclaration.class)) {
            Set<String> anns = InfoClase.nombresAnotaciones(md.getAnnotations());
            if ((anns.contains("BeforeEach") || anns.contains("Before")) && md.getBody().isPresent()) {
                setUp.addAll(md.getBody().get().getStatements());
            }
        }
        List<String> stubsBase = new ArrayList<>();
        procesarSentencias(setUp, base, sut, stubsBase, null);
        for (MethodDeclaration md : test.decl.findAll(MethodDeclaration.class)) {
            if (!EmparejadorTests.esMetodoTest(md) || md.getBody().isEmpty()) {
                continue;
            }
            NormalizadorExpresiones n = copia(base, test, setUp, sut);
            List<String> stubs = new ArrayList<>(stubsBase);
            List<LlamadaSut> llamadas = new ArrayList<>();
            List<Statement> sentencias = md.getBody().get().getStatements();
            procesarSentencias(sentencias, n, sut, stubs, llamadas);
            String esperadoAnotacion = excepcionEsperadaAnotacion(md);
            for (LlamadaSut ll : llamadas) {
                String esperado = ll.esperado;
                if (esperado == null && ll.variable != null) {
                    esperado = buscarAsercionSobreVariable(sentencias, ll.variable, n);
                }
                if (esperado == null && esperadoAnotacion != null) {
                    esperado = "excepcion:" + esperadoAnotacion;
                }
                InfoMetodo im = metodoUnico(servicio, ll.llamada.getNameAsString(), ll.llamada.getArguments().size());
                List<String> tipos = im == null ? null : simples(im.tiposParametros());
                List<String> args = new ArrayList<>();
                ll.argsNormalizados.forEach(args::add);
                Huella h = Huella.calcular(servicio.fqn, ll.llamada.getNameAsString(), tipos, args, stubs, esperado);
                r.add(new HuellaExistente(h, test.simple, md.getNameAsString()));
            }
        }
        return r;
    }

    private static NormalizadorExpresiones copia(NormalizadorExpresiones base, InfoClase test, List<Statement> setUp,
                                                 Set<String> sut) {
        NormalizadorExpresiones n = new NormalizadorExpresiones();
        for (FieldDeclaration f : test.decl.findAll(FieldDeclaration.class)) {
            for (VariableDeclarator v : f.getVariables()) {
                String nombre = v.getNameAsString();
                if (base.esMock(nombre)) {
                    n.mock(nombre, base.tipoMock(nombre));
                } else if (base.esVariable(nombre)) {
                    n.declararTexto(nombre, base.valor(nombre));
                }
            }
        }
        procesarSentencias(setUp, n, sut, new ArrayList<>(), null);
        return n;
    }

    public static List<String> simples(List<String> tipos) {
        List<String> r = new ArrayList<>();
        for (String t : tipos) {
            r.add(Textos.nombreSimple(t));
        }
        return r;
    }

    private static InfoMetodo metodoUnico(InfoClase servicio, String nombre, int aridad) {
        InfoMetodo unico = null;
        for (InfoMetodo m : servicio.metodos) {
            if (m.nombre.equals(nombre) && m.aridad() == aridad) {
                if (unico != null) {
                    return null;
                }
                unico = m;
            }
        }
        return unico;
    }

    private static String tipoMockDe(Expression ini) {
        if (ini instanceof MethodCallExpr mc && (mc.getNameAsString().equals("mock") || mc.getNameAsString().equals("spy"))
                && mc.getArguments().size() >= 1 && mc.getArgument(0) instanceof ClassExpr ce) {
            return Textos.nombreSimple(ce.getType().asString());
        }
        return null;
    }

    private static class LlamadaSut {
        MethodCallExpr llamada;
        String variable;
        String esperado;
        final List<String> argsNormalizados = new ArrayList<>();
    }

    /** Aplica declaraciones, asignaciones y mutaciones de variables al normalizador (sin servicio bajo prueba). */
    public static void aplicarSentencias(List<Statement> sentencias, NormalizadorExpresiones n) {
        procesarSentencias(sentencias, n, Set.of(), new ArrayList<>(), null);
    }

    private static void procesarSentencias(List<Statement> sentencias, NormalizadorExpresiones n, Set<String> sut,
                                           List<String> stubs, List<LlamadaSut> llamadas) {
        for (Statement st : sentencias) {
            if (!(st instanceof ExpressionStmt es)) {
                if (llamadas != null) {
                    registrarLlamadas(st, n, sut, llamadas, null);
                }
                continue;
            }
            Expression e = es.getExpression();
            if (e instanceof VariableDeclarationExpr vde) {
                for (VariableDeclarator v : vde.getVariables()) {
                    Optional<Expression> ini = v.getInitializer();
                    if (ini.isEmpty()) {
                        continue;
                    }
                    String tm = tipoMockDe(ini.get());
                    if (tm != null) {
                        n.mock(v.getNameAsString(), tm);
                    } else if (contieneSut(ini.get(), sut)) {
                        if (llamadas != null) {
                            registrarLlamadas(ini.get(), n, sut, llamadas, v.getNameAsString());
                        }
                        n.fijo(v.getNameAsString(), "RESULTADO");
                    } else {
                        n.declarar(v.getNameAsString(), ini.get());
                    }
                }
                continue;
            }
            if (e instanceof AssignExpr ae && ae.getTarget() instanceof NameExpr t) {
                if (contieneSut(ae.getValue(), sut)) {
                    if (llamadas != null) {
                        registrarLlamadas(ae.getValue(), n, sut, llamadas, t.getNameAsString());
                    }
                    n.fijo(t.getNameAsString(), "RESULTADO");
                } else if (!sut.contains(t.getNameAsString())) {
                    String tm = tipoMockDe(ae.getValue());
                    if (tm != null) {
                        n.mock(t.getNameAsString(), tm);
                    } else {
                        n.declarar(t.getNameAsString(), ae.getValue());
                    }
                }
                continue;
            }
            if (e instanceof MethodCallExpr mc) {
                String stub = stubDe(mc, n);
                if (stub != null) {
                    stubs.add(stub);
                    continue;
                }
                if (mc.getScope().isPresent() && mc.getScope().get() instanceof NameExpr sc
                        && n.esVariable(sc.getNameAsString()) && !sut.contains(sc.getNameAsString())
                        && !contieneSut(mc, sut)) {
                    n.mutar(sc.getNameAsString(), mc);
                    continue;
                }
            }
            if (llamadas != null) {
                registrarLlamadas(e, n, sut, llamadas, null);
            }
        }
    }

    private static boolean contieneSut(Node e, Set<String> sut) {
        if (e instanceof MethodCallExpr mc && esLlamadaSut(mc, sut)) {
            return true;
        }
        return e.findAll(MethodCallExpr.class).stream().anyMatch(mc -> esLlamadaSut(mc, sut));
    }

    private static boolean esLlamadaSut(MethodCallExpr mc, Set<String> sut) {
        if (mc.getScope().isEmpty()) {
            return false;
        }
        Expression s = mc.getScope().get();
        if (s instanceof NameExpr ne) {
            return sut.contains(ne.getNameAsString());
        }
        return s instanceof FieldAccessExpr fa && fa.getScope() instanceof ThisExpr && sut.contains(fa.getNameAsString());
    }

    private static void registrarLlamadas(Node raiz, NormalizadorExpresiones n, Set<String> sut, List<LlamadaSut> llamadas,
                                          String variable) {
        List<MethodCallExpr> todas = new ArrayList<>();
        if (raiz instanceof MethodCallExpr mc0) {
            todas.add(mc0);
        }
        todas.addAll(raiz.findAll(MethodCallExpr.class));
        for (MethodCallExpr mc : todas) {
            if (!esLlamadaSut(mc, sut) || llamadas.stream().anyMatch(l -> l.llamada == mc)) {
                continue;
            }
            LlamadaSut l = new LlamadaSut();
            l.llamada = mc;
            l.variable = variable;
            for (Expression a : mc.getArguments()) {
                l.argsNormalizados.add(n.normalizar(a));
            }
            l.esperado = esperadoEnContexto(mc, n);
            llamadas.add(l);
        }
    }

    /** Esperado deducible del contexto directo de la llamada (assert que la contiene, assertThrows…). */
    private static String esperadoEnContexto(MethodCallExpr llamada, NormalizadorExpresiones n) {
        Node actual = llamada;
        Node padre = llamada.getParentNode().orElse(null);
        while (padre != null) {
            if (padre instanceof LambdaExpr) {
                Node abuelo = padre.getParentNode().orElse(null);
                if (abuelo instanceof MethodCallExpr mc) {
                    String nombre = mc.getNameAsString();
                    if ((nombre.equals("assertThrows") || nombre.equals("assertThrowsExactly")) && !mc.getArguments().isEmpty()
                            && mc.getArgument(0) instanceof ClassExpr ce) {
                        return "excepcion:" + Textos.nombreSimple(ce.getType().asString());
                    }
                    if (nombre.equals("assertThatThrownBy") || nombre.equals("catchThrowable") || nombre.equals("isThrownBy")) {
                        String ex = excepcionEnCadena(mc);
                        return ex == null ? Huella.DESCONOCIDO : "excepcion:" + ex;
                    }
                    if (nombre.equals("assertDoesNotThrow")) {
                        return Huella.DESCONOCIDO;
                    }
                }
                return null;
            }
            if (padre instanceof MethodCallExpr mc) {
                String r = esperadoDeAsercion(mc, actual, n);
                if (r != null) {
                    return r;
                }
            }
            if (padre instanceof Statement) {
                return null;
            }
            actual = padre;
            padre = padre.getParentNode().orElse(null);
        }
        return null;
    }

    private static String excepcionEnCadena(MethodCallExpr inicio) {
        Node p = inicio;
        while (p != null) {
            if (p instanceof MethodCallExpr mc && (mc.getNameAsString().equals("isInstanceOf")
                    || mc.getNameAsString().equals("isExactlyInstanceOf")) && !mc.getArguments().isEmpty()
                    && mc.getArgument(0) instanceof ClassExpr ce) {
                return Textos.nombreSimple(ce.getType().asString());
            }
            if (p instanceof MethodCallExpr mc && mc.getNameAsString().equals("assertThatExceptionOfType")
                    && !mc.getArguments().isEmpty() && mc.getArgument(0) instanceof ClassExpr ce) {
                return Textos.nombreSimple(ce.getType().asString());
            }
            p = p.getParentNode().orElse(null);
            if (p instanceof Statement) {
                break;
            }
        }
        // assertThatExceptionOfType(X.class).isThrownBy(...): X está en el scope
        if (inicio.getScope().isPresent() && inicio.getScope().get() instanceof MethodCallExpr sc
                && sc.getNameAsString().equals("assertThatExceptionOfType") && !sc.getArguments().isEmpty()
                && sc.getArgument(0) instanceof ClassExpr ce) {
            return Textos.nombreSimple(ce.getType().asString());
        }
        return null;
    }

    /** Si {@code asercion} afirma algo sobre {@code real}, devuelve el esperado normalizado. */
    private static String esperadoDeAsercion(MethodCallExpr asercion, Node real, NormalizadorExpresiones n) {
        String nombre = asercion.getNameAsString();
        List<Expression> args = new ArrayList<>(asercion.getArguments());
        if (nombre.equals("assertEquals")) {
            if (args.size() == 3 && args.get(0) instanceof StringLiteralExpr && !(args.get(2) instanceof StringLiteralExpr)) {
                args.remove(0);
            }
            if (args.size() >= 2 && args.get(1) == real) {
                return "valor:" + n.normalizar(args.get(0));
            }
            return null;
        }
        String unario = switch (nombre) {
            case "assertTrue" -> "verdadero";
            case "assertFalse" -> "falso";
            case "assertNull" -> "nulo";
            case "assertNotNull" -> "noNulo";
            default -> null;
        };
        if (unario != null) {
            if (args.size() == 2 && args.get(0) instanceof StringLiteralExpr) {
                args.remove(0);
            }
            return !args.isEmpty() && args.get(0) == real ? unario : null;
        }
        if (nombre.equals("assertThat") && !args.isEmpty() && args.get(0) == real) {
            Node p = asercion.getParentNode().orElse(null);
            while (p instanceof MethodCallExpr mc) {
                switch (mc.getNameAsString()) {
                    case "isEqualTo":
                        return mc.getArguments().isEmpty() ? Huella.DESCONOCIDO : "valor:" + n.normalizar(mc.getArgument(0));
                    case "isTrue":
                        return "verdadero";
                    case "isFalse":
                        return "falso";
                    case "isNull":
                        return "nulo";
                    case "isNotNull":
                        break;
                    default:
                        return Huella.DESCONOCIDO;
                }
                p = mc.getParentNode().orElse(null);
            }
            return "noNulo";
        }
        return null;
    }

    private static String buscarAsercionSobreVariable(List<Statement> sentencias, String variable, NormalizadorExpresiones n) {
        for (Statement st : sentencias) {
            for (MethodCallExpr mc : st.findAll(MethodCallExpr.class)) {
                for (Expression a : mc.getArguments()) {
                    if (a instanceof NameExpr ne && ne.getNameAsString().equals(variable)) {
                        String r = esperadoDeAsercion(mc, a, n);
                        if (r != null) {
                            return r;
                        }
                    }
                }
            }
        }
        return null;
    }

    private static String excepcionEsperadaAnotacion(MethodDeclaration md) {
        for (AnnotationExpr a : md.getAnnotations()) {
            if (a.getNameAsString().endsWith("Test") && a instanceof NormalAnnotationExpr na) {
                for (MemberValuePair p : na.getPairs()) {
                    if (p.getNameAsString().equals("expected") && p.getValue() instanceof ClassExpr ce) {
                        return Textos.nombreSimple(ce.getType().asString());
                    }
                }
            }
        }
        return null;
    }

    /** Stub normalizado "Tipo.llamada->resultado" o null si la expresión no es un stub. */
    static String stubDe(MethodCallExpr exterior, NormalizadorExpresiones n) {
        List<MethodCallExpr> cadena = new ArrayList<>();
        Expression actual = exterior;
        while (actual instanceof MethodCallExpr mc) {
            cadena.add(mc);
            actual = mc.getScope().orElse(null);
            if (actual instanceof NameExpr ne && (ne.getNameAsString().equals("Mockito") || ne.getNameAsString().equals("BDDMockito"))) {
                break;
            }
        }
        if (cadena.isEmpty()) {
            return null;
        }
        MethodCallExpr raiz = cadena.get(cadena.size() - 1);
        String nr = raiz.getNameAsString();
        if ((nr.equals("when") || nr.equals("given")) && raiz.getArguments().size() == 1
                && raiz.getArgument(0) instanceof MethodCallExpr llamada) {
            List<String> resultados = new ArrayList<>();
            for (int i = cadena.size() - 2; i >= 0; i--) {
                MethodCallExpr r = cadena.get(i);
                if (!STUB_RESULTADO.contains(r.getNameAsString())) {
                    return null;
                }
                resultados.add(resultado(r, n));
            }
            if (resultados.isEmpty()) {
                return null;
            }
            return n.normalizar(llamada) + "->" + String.join(",", resultados);
        }
        if (STUB_DO.contains(nr) && cadena.size() >= 3) {
            MethodCallExpr whenCall = cadena.get(cadena.size() - 2);
            if (!(whenCall.getNameAsString().equals("when") || whenCall.getNameAsString().equals("given"))
                    || whenCall.getArguments().size() != 1) {
                return null;
            }
            if (nr.equals("doNothing") || nr.equals("willDoNothing")) {
                return null;
            }
            MethodCallExpr metodo = cadena.get(cadena.size() - 3);
            com.github.javaparser.ast.NodeList<Expression> args = new com.github.javaparser.ast.NodeList<>();
            metodo.getArguments().forEach(a -> args.add(a.clone()));
            MethodCallExpr reconstruida = new MethodCallExpr(whenCall.getArgument(0).clone(), metodo.getNameAsString(), args);
            return n.normalizar(reconstruida) + "->" + resultado(raiz, n);
        }
        return null;
    }

    private static String resultado(MethodCallExpr r, NormalizadorExpresiones n) {
        String nombre = r.getNameAsString();
        if (nombre.contains("Throw")) {
            List<String> ex = new ArrayList<>();
            for (Expression a : r.getArguments()) {
                ex.add("!" + NormalizadorExpresiones.nombreExcepcion(a.toString()));
            }
            return String.join(",", ex);
        }
        if (nombre.contains("Answer")) {
            return "answer";
        }
        if (nombre.contains("CallRealMethod")) {
            return "real";
        }
        List<String> vals = new ArrayList<>();
        for (Expression a : r.getArguments()) {
            vals.add(n.normalizar(a));
        }
        return String.join(",", vals);
    }
}
