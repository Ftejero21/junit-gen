package com.junitgen.generacion;

import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.expr.ArrayCreationExpr;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.type.ArrayType;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.type.Type;
import com.junitgen.Version;
import com.junitgen.escaneo.AnalisisServicio;
import com.junitgen.escaneo.AnalizadorDependencias;
import com.junitgen.escaneo.Dependencia;
import com.junitgen.escaneo.IndiceTipos;
import com.junitgen.escaneo.InfoMetodo;
import com.junitgen.spec.AsercionSpec;
import com.junitgen.spec.CasoSpec;
import com.junitgen.spec.ConstruccionMockSpec;
import com.junitgen.spec.DatoSpec;
import com.junitgen.spec.EsperadoSpec;
import com.junitgen.spec.EstaticoSpec;
import com.junitgen.spec.FixtureSpec;
import com.junitgen.spec.ParametroSpec;
import com.junitgen.spec.ResultadoValidacion;
import com.junitgen.spec.StubSpec;
import com.junitgen.spec.ValidadorSpec;
import com.junitgen.spec.VerificacionSpec;
import com.junitgen.util.Textos;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Plantilla uniforme de tests. Toda expresión del spec se parsea con JavaParser y se reimprime
 * (nunca se copia texto libre), lo que impide inyectar código fuera de su sitio.
 */
public class PlantillaTest {

    public static final String M_IMPORTS_FIN = "// junit-gen:imports-fin";
    public static final String M_CASOS_FIN = "// junit-gen:casos-fin";
    public static final String M_FIXTURES_FIN = "// junit-gen:fixtures-fin";
    public static final String M_MONTAJE = "// junit-gen:montaje=";
    private static final String IND = "    ";
    private static final Set<String> WRAPPERS_NUM = Set.of("Long", "Integer", "Short", "Byte", "Character", "Double", "Float");
    private static final Pattern TIPO_DESNUDO = Pattern.compile("[A-Z][\\w$]*(\\.[A-Z][\\w$]*)*");

    public record Bloque(String id, String nombreMetodo, String texto, String contenido, Set<String> estaticos,
                         Set<String> imports) {
    }

    private final PerfilPlantilla p;
    private final ResultadoValidacion.ServicioValidado sv;
    private final String sut;

    public PlantillaTest(PerfilPlantilla p, ResultadoValidacion.ServicioValidado sv) {
        this.p = p;
        this.sv = sv;
        String nombre = p.nombreSut;
        for (Dependencia d : sv.mocks()) {
            if (d.nombre.equals(nombre)) {
                nombre = "sut";
            }
        }
        if (sv.reales.containsKey(nombre)) {
            nombre = "sut";
        }
        this.sut = nombre;
    }

    public String nombreSut() {
        return sut;
    }

    // ---------------------------------------------------------------- utilidades de impresión

    static String expr(String texto) {
        ParseResult<Expression> r = IndiceTipos.parser().parseExpression(texto);
        if (!r.isSuccessful() || r.getResult().isEmpty()) {
            throw new IllegalArgumentException("Expresión no válida (debería haberse validado): " + texto);
        }
        return r.getResult().get().toString();
    }

    static String sentencia(String texto) {
        Statement st = ValidadorSpec.parsearSentencia(texto);
        if (st == null) {
            throw new IllegalArgumentException("Sentencia no válida (debería haberse validado): " + texto);
        }
        return st.toString();
    }

    static String tipo(String texto) {
        if ("var".equals(texto.trim())) {
            return "var";
        }
        ParseResult<Type> r = IndiceTipos.parser().parseType(texto);
        if (!r.isSuccessful() || r.getResult().isEmpty()) {
            throw new IllegalArgumentException("Tipo no válido (debería haberse validado): " + texto);
        }
        return r.getResult().get().toString();
    }

    static String tipoSinGenericos(String texto) {
        return Textos.quitarGenericos(tipo(texto));
    }

    static MethodCallExpr llamada(String receptor, String llamada) {
        ParseResult<Expression> r = IndiceTipos.parser().parseExpression(receptor + "." + llamada.trim());
        if (!r.isSuccessful() || r.getResult().isEmpty() || !(r.getResult().get() instanceof MethodCallExpr mc)) {
            throw new IllegalArgumentException("Llamada no válida (debería haberse validado): " + llamada);
        }
        return mc;
    }

    private static String argumentos(MethodCallExpr mc) {
        List<String> a = new ArrayList<>();
        mc.getArguments().forEach(x -> a.add(x.toString()));
        return String.join(", ", a);
    }

    private static void anadir(List<String> lineas, String nivel, String texto) {
        for (String l : texto.split("\\R", -1)) {
            lineas.add(l.isEmpty() ? "" : nivel + l);
        }
    }

    // ---------------------------------------------------------------- nombres

    public String nombreMetodo(CasoSpec c, Set<String> ocupados) {
        String esc = Textos.aIdentificador(c.escenario == null ? "caso" : c.escenario.trim());
        String patron = p.patronNombre;
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < patron.length()) {
            if (patron.startsWith("{metodo}", i) || patron.startsWith("{escenario}", i)) {
                boolean metodo = patron.startsWith("{metodo}", i);
                String valor = metodo ? c.metodo : esc;
                if (sb.length() > 0 && Character.isLetterOrDigit(sb.charAt(sb.length() - 1)) && !valor.isEmpty()) {
                    valor = Character.toUpperCase(valor.charAt(0)) + valor.substring(1);
                }
                sb.append(valor);
                i += metodo ? "{metodo}".length() : "{escenario}".length();
            } else {
                sb.append(patron.charAt(i));
                i++;
            }
        }
        String base = Textos.aIdentificador(sb.toString());
        String nombre = base;
        int n = 2;
        while (ocupados.contains(nombre)) {
            nombre = base + "_" + n++;
        }
        return nombre;
    }

    // ---------------------------------------------------------------- casos

    public Bloque caso(ResultadoValidacion.CasoValidado cv, Set<String> ocupados) {
        CasoSpec c = cv.spec;
        Set<String> estaticos = new TreeSet<>();
        Set<String> imports = new TreeSet<>();
        String nombre = nombreMetodo(c, ocupados);
        List<String> l = new ArrayList<>();
        l.add(p.junit == 5 ? "@Test" : "@Test");
        if (p.displayName && c.descripcion != null && !c.descripcion.isBlank()) {
            l.add("@DisplayName(" + Textos.literalJava(Textos.sanearDescripcion(c.descripcion)) + ")");
            imports.add("org.junit.jupiter.api.DisplayName");
        }
        l.add((p.junit == 4 || p.clasePublica ? "public " : "") + "void " + nombre + "() throws Exception {");
        List<String> cuerpo = new ArrayList<>();
        if (p.comentariosGwt) {
            cuerpo.add("// given");
        }
        if (c.datos != null) {
            for (DatoSpec d : c.datos) {
                if (Boolean.TRUE.equals(d.mock)) {
                    String t = tipo(d.tipo);
                    String cast = t.contains("<") ? "(" + t + ") " : "";
                    cuerpo.add(t + " " + d.nombre + " = " + cast + "mock(" + tipoSinGenericos(d.tipo) + ".class);");
                } else {
                    anadir(cuerpo, "", tipo(d.tipo) + " " + d.nombre + " = " + expr(d.valor) + ";");
                }
            }
        }
        if (c.preparacion != null) {
            for (String s : c.preparacion) {
                anadir(cuerpo, "", sentencia(s));
            }
        }
        if (c.stubs != null) {
            for (int i = 0; i < c.stubs.size(); i++) {
                StubSpec s = c.stubs.get(i);
                anadir(cuerpo, "", stub(s, cv.formaStub.getOrDefault(i, "when"), s.mock));
            }
        }
        // Recursos mockStatic / mockConstruction agrupados por clase.
        Map<String, List<EstaticoSpec>> porClase = new LinkedHashMap<>();
        if (c.estaticos != null) {
            for (EstaticoSpec e : c.estaticos) {
                porClase.computeIfAbsent(e.clase.trim(), k -> new ArrayList<>()).add(e);
            }
        }
        List<String> recursos = new ArrayList<>();
        List<String> dentro = new ArrayList<>();
        int k = 0;
        for (Map.Entry<String, List<EstaticoSpec>> e : porClase.entrySet()) {
            String clase = e.getKey();
            String var = "mocked" + Textos.nombreSimple(clase) + (k++ == 0 ? "" : String.valueOf(k));
            recursos.add("MockedStatic<" + clase + "> " + var + " = mockStatic(" + clase + ".class)");
            imports.add("org.mockito.MockedStatic");
            for (EstaticoSpec es : e.getValue()) {
                String llam = quitarPrefijo(es.llamada, clase);
                String linea = var + ".when(() -> " + expr(clase + "." + llam) + ")";
                if (es.lanza != null) {
                    linea += ".thenThrow(" + excepcionStub(es.lanza) + ")";
                } else if (es.devuelve != null) {
                    linea += ".thenReturn(" + expr(es.devuelve) + ")";
                } else {
                    continue;
                }
                anadir(dentro, "", linea + ";");
            }
        }
        if (c.construcciones != null) {
            int q = 0;
            for (int ci = 0; ci < c.construcciones.size(); ci++) {
                ConstruccionMockSpec cm = c.construcciones.get(ci);
                String clase = cm.clase.trim();
                String var = "construccion" + Textos.nombreSimple(clase) + (q++ == 0 ? "" : String.valueOf(q));
                imports.add("org.mockito.MockedConstruction");
                if (cm.stubs == null || cm.stubs.isEmpty()) {
                    recursos.add("MockedConstruction<" + clase + "> " + var + " = mockConstruction(" + clase + ".class)");
                } else {
                    StringBuilder sb = new StringBuilder("MockedConstruction<" + clase + "> " + var + " = mockConstruction("
                            + clase + ".class, (construido, contexto) -> {\n");
                    Map<Integer, String> formas = cv.formaStubConstruccion.getOrDefault(ci, Map.of());
                    for (int si = 0; si < cm.stubs.size(); si++) {
                        StubSpec s = cm.stubs.get(si);
                        for (String linea : stub(s, formas.getOrDefault(si, "when"), "construido").split("\\R")) {
                            sb.append(IND).append(IND).append(linea).append('\n');
                        }
                    }
                    sb.append("})");
                    recursos.add(sb.toString());
                }
            }
        }
        List<String> accion = new ArrayList<>(dentro);
        accion.addAll(cuandoYEntonces(cv, estaticos));
        if (recursos.isEmpty()) {
            cuerpo.addAll(accion);
        } else {
            StringBuilder tr = new StringBuilder("try (");
            for (int i = 0; i < recursos.size(); i++) {
                if (i > 0) {
                    tr.append(";\n").append(IND).append(IND);
                }
                tr.append(recursos.get(i));
            }
            tr.append(") {");
            anadir(cuerpo, "", tr.toString());
            for (String a : accion) {
                cuerpo.add(a.isEmpty() ? "" : IND + a);
            }
            cuerpo.add("}");
        }
        for (String x : cuerpo) {
            l.add(x.isEmpty() ? "" : IND + x);
        }
        l.add("}");
        String metodoTexto = String.join("\n", l);
        String contenido = Textos.sha256Hex(metodoTexto, 16);
        StringBuilder bloque = new StringBuilder();
        bloque.append(IND).append("// junit-gen:caso-inicio id=").append(c.id).append(" huella=").append(cv.huella.completa)
                .append(" entrada=").append(cv.huella.entrada).append(" contenido=").append(contenido).append('\n');
        // Comentario del caso (fuera de metodoTexto y del hash 'contenido'): máx. 2 líneas por campo.
        comentario(bloque, "// Función: ", c.queHaceOriginal);
        comentario(bloque, "// Test: ", c.queComprueba);
        for (String x : l) {
            bloque.append(x.isEmpty() ? "" : IND + x).append('\n');
        }
        bloque.append(IND).append("// junit-gen:caso-fin id=").append(c.id).append('\n');
        return new Bloque(c.id, nombre, bloque.toString(), contenido, estaticos, imports);
    }

    static final int ANCHO_COMENTARIO = 100;
    static final int MAX_LINEAS_COMENTARIO = 2;

    /** Escribe un campo de comentario saneado (ancho 100, máx. 2 líneas, continuación «//   »); null/blanco → nada. */
    private static void comentario(StringBuilder bloque, String prefijo, String valor) {
        String san = Textos.sanearComentario(valor, 160);
        if (san == null || san.isBlank()) {
            return;
        }
        List<String> lineas = Textos.lineasComentario(san, ANCHO_COMENTARIO - prefijo.length(), MAX_LINEAS_COMENTARIO);
        for (int i = 0; i < lineas.size(); i++) {
            bloque.append(IND).append(i == 0 ? prefijo : "//   ").append(lineas.get(i)).append('\n');
        }
    }

    private static String quitarPrefijo(String llamada, String clase) {
        String l = llamada == null ? "" : llamada.trim();
        String simple = Textos.nombreSimple(clase);
        if (l.startsWith(clase + ".")) {
            return l.substring(clase.length() + 1);
        }
        if (l.startsWith(simple + ".")) {
            return l.substring(simple.length() + 1);
        }
        return l;
    }

    private String excepcionStub(String lanza) {
        String t = lanza.trim();
        if (TIPO_DESNUDO.matcher(t).matches()) {
            return p.thenThrowClase ? t + ".class" : "new " + t + "()";
        }
        return expr(t);
    }

    String stub(StubSpec s, String forma, String receptor) {
        MethodCallExpr mc = llamada(receptor, s.llamada);
        if ("do".equals(forma)) {
            String pre;
            if (s.lanza != null) {
                pre = "doThrow(" + excepcionStub(s.lanza) + ")";
            } else if (s.responde != null) {
                pre = "doAnswer(" + expr(s.responde) + ")";
            } else if (s.devuelveSecuencia != null) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < s.devuelveSecuencia.size(); i++) {
                    sb.append(i == 0 ? "doReturn(" : ".doReturn(").append(expr(s.devuelveSecuencia.get(i))).append(')');
                }
                pre = sb.toString();
            } else {
                pre = "doReturn(" + expr(s.devuelve) + ")";
            }
            return pre + ".when(" + mc.getScope().get() + ")." + mc.getNameAsString() + "(" + argumentos(mc) + ");";
        }
        String post;
        if (s.lanza != null) {
            post = ".thenThrow(" + excepcionStub(s.lanza) + ")";
        } else if (s.responde != null) {
            post = ".thenAnswer(" + expr(s.responde) + ")";
        } else if (s.devuelveSecuencia != null) {
            List<String> v = new ArrayList<>();
            s.devuelveSecuencia.forEach(x -> v.add(expr(x)));
            post = ".thenReturn(" + String.join(", ", v) + ")";
        } else {
            post = ".thenReturn(" + expr(s.devuelve) + ")";
        }
        return "when(" + mc + ")" + post + ";";
    }

    private String usarAssert(String metodo, Set<String> estaticos) {
        estaticos.add(p.claseAsserts() + "." + metodo);
        return metodo;
    }

    private String usarAssertj(String metodo, Set<String> estaticos) {
        estaticos.add("org.assertj.core.api.Assertions." + metodo);
        return metodo;
    }

    private List<String> cuandoYEntonces(ResultadoValidacion.CasoValidado cv, Set<String> estaticos) {
        CasoSpec c = cv.spec;
        List<String> r = new ArrayList<>();
        List<String> args = new ArrayList<>();
        if (c.argumentos != null) {
            c.argumentos.forEach(a -> args.add(expr(a)));
        }
        String invocacion = sut + "." + c.metodo + "(" + String.join(", ", args) + ")";
        EsperadoSpec e = c.esperado;
        boolean esVoid = cv.metodo.esVoid();
        if ("excepcion".equals(e.tipo)) {
            if (p.comentariosGwt) {
                r.add("// when / then");
            }
            String ex = e.excepcion.trim();
            if (p.assertj && p.java >= 8) {
                StringBuilder sb = new StringBuilder(usarAssertj("assertThatThrownBy", estaticos) + "(() -> " + invocacion
                        + ")\n" + IND + IND + ".isInstanceOf(" + ex + ".class)");
                if (e.mensaje != null) {
                    sb.append("\n").append(IND).append(IND).append(".hasMessage(").append(Textos.literalJava(e.mensaje)).append(')');
                } else if (e.mensajeContiene != null) {
                    sb.append("\n").append(IND).append(IND).append(".hasMessageContaining(").append(Textos.literalJava(e.mensajeContiene)).append(')');
                }
                anadir(r, "", sb + ";");
            } else if (p.assertThrows) {
                boolean conMensaje = e.mensaje != null || e.mensajeContiene != null;
                String llamadaAssert = usarAssert("assertThrows", estaticos) + "(" + ex + ".class, () -> " + invocacion + ");";
                r.add(conMensaje ? ex + " ex = " + llamadaAssert : llamadaAssert);
                if (e.mensaje != null) {
                    r.add(usarAssert("assertEquals", estaticos) + "(" + Textos.literalJava(e.mensaje) + ", ex.getMessage());");
                } else if (e.mensajeContiene != null) {
                    r.add(usarAssert("assertTrue", estaticos) + "(ex.getMessage().contains(" + Textos.literalJava(e.mensajeContiene) + "));");
                }
            } else {
                r.add("try {");
                r.add(IND + invocacion + ";");
                String fail = p.assertj ? usarAssertj("fail", estaticos) : usarAssert("fail", estaticos);
                r.add(IND + fail + "(" + Textos.literalJava("Se esperaba " + ex) + ");");
                r.add("} catch (" + ex + " ex) {");
                if (e.mensaje != null) {
                    r.add(IND + usarAssert("assertEquals", estaticos) + "(" + Textos.literalJava(e.mensaje) + ", ex.getMessage());");
                } else if (e.mensajeContiene != null) {
                    r.add(IND + usarAssert("assertTrue", estaticos) + "(ex.getMessage().contains(" + Textos.literalJava(e.mensajeContiene) + "));");
                } else {
                    r.add(IND + usarAssert("assertNotNull", estaticos) + "(ex);");
                }
                r.add("}");
            }
            if ((c.aserciones != null && !c.aserciones.isEmpty()) || (c.verificaciones != null && !c.verificaciones.isEmpty())) {
                if (p.comentariosGwt) {
                    r.add("// then");
                }
            }
        } else {
            if (p.comentariosGwt) {
                r.add("// when");
            }
            if (esVoid) {
                r.add(invocacion + ";");
            } else {
                r.add(cv.tipoResultado + " resultado = " + invocacion + ";");
            }
            if (p.comentariosGwt) {
                r.add("// then");
            }
            String tr = cv.tipoResultado == null ? "" : cv.tipoResultado;
            switch (e.tipo) {
                case "valor" -> {
                    String v = expr(e.valor);
                    String tipoArr = p.assertj ? null : tipoArray(tr, e.valor, c.datos);
                    if (p.assertj) {
                        r.add(usarAssertj("assertThat", estaticos) + "(resultado).isEqualTo(" + v + ");");
                    } else if (tipoArr != null) {
                        r.add(asercionArray(v, "resultado", tipoArr, "Object".equals(tr), estaticos));
                    } else if (p.junit == 4 && WRAPPERS_NUM.contains(tr)) {
                        r.add(usarAssert("assertEquals", estaticos) + "((Object) (" + v + "), resultado);");
                    } else if (p.junit == 4 && (tr.equals("double") || tr.equals("float"))) {
                        r.add(usarAssert("assertEquals", estaticos) + "(" + v + ", resultado, 0.0);");
                    } else {
                        r.add(usarAssert("assertEquals", estaticos) + "(" + v + ", resultado);");
                    }
                }
                case "nulo" -> r.add(p.assertj ? usarAssertj("assertThat", estaticos) + "(resultado).isNull();"
                        : usarAssert("assertNull", estaticos) + "(resultado);");
                case "noNulo" -> r.add(p.assertj ? usarAssertj("assertThat", estaticos) + "(resultado).isNotNull();"
                        : usarAssert("assertNotNull", estaticos) + "(resultado);");
                case "verdadero" -> r.add(p.assertj ? usarAssertj("assertThat", estaticos) + "(resultado).isTrue();"
                        : usarAssert("assertTrue", estaticos) + "(resultado);");
                case "falso" -> r.add(p.assertj ? usarAssertj("assertThat", estaticos) + "(resultado).isFalse();"
                        : usarAssert("assertFalse", estaticos) + "(resultado);");
                default -> {
                }
            }
        }
        if (c.aserciones != null) {
            for (AsercionSpec a : c.aserciones) {
                anadir(r, "", asercion(a, estaticos, cv.tipoResultado, c.datos));
            }
        }
        if (c.verificaciones != null) {
            for (VerificacionSpec v : c.verificaciones) {
                anadir(r, "", verificacion(v));
            }
        }
        return r;
    }

    private String asercion(AsercionSpec a, Set<String> estaticos, String tipoResultado, List<DatoSpec> datos) {
        String real = expr(a.real);
        String esp = a.esperado == null ? null : expr(a.esperado);
        if (p.assertj) {
            String at = usarAssertj("assertThat", estaticos) + "(" + real + ")";
            return at + switch (a.tipo) {
                case "igual" -> ".isEqualTo(" + esp + ");";
                case "noIgual" -> ".isNotEqualTo(" + esp + ");";
                case "nulo" -> ".isNull();";
                case "noNulo" -> ".isNotNull();";
                case "verdadero" -> ".isTrue();";
                case "falso" -> ".isFalse();";
                case "mismo" -> ".isSameAs(" + esp + ");";
                case "tamano" -> ".hasSize(" + esp + ");";
                case "vacio" -> ".isEmpty();";
                case "contiene" -> ".contains(" + esp + ");";
                default -> throw new IllegalArgumentException("Aserción desconocida: " + a.tipo);
            };
        }
        if ("igual".equals(a.tipo)) {
            boolean esResultado = a.real != null && "resultado".equals(a.real.trim());
            String tipoArr = tipoArray(esResultado ? tipoResultado : null, a.esperado, datos);
            if (tipoArr != null) {
                return asercionArray(esp, real, tipoArr, esResultado && "Object".equals(tipoResultado), estaticos);
            }
        }
        return switch (a.tipo) {
            case "igual" -> usarAssert("assertEquals", estaticos) + "(" + esp + ", " + real + ");";
            case "noIgual" -> usarAssert("assertNotEquals", estaticos) + "(" + esp + ", " + real + ");";
            case "nulo" -> usarAssert("assertNull", estaticos) + "(" + real + ");";
            case "noNulo" -> usarAssert("assertNotNull", estaticos) + "(" + real + ");";
            case "verdadero" -> usarAssert("assertTrue", estaticos) + "(" + real + ");";
            case "falso" -> usarAssert("assertFalse", estaticos) + "(" + real + ");";
            case "mismo" -> usarAssert("assertSame", estaticos) + "(" + esp + ", " + real + ");";
            case "tamano" -> usarAssert("assertEquals", estaticos) + "(" + esp + ", " + real + ".size());";
            case "vacio" -> usarAssert("assertTrue", estaticos) + "(" + real + ".isEmpty());";
            case "contiene" -> usarAssert("assertTrue", estaticos) + "(" + real + ".contains(" + esp + "));";
            default -> throw new IllegalArgumentException("Aserción desconocida: " + a.tipo);
        };
    }

    /**
     * Tipo array comparado por una aserción de igualdad, o null si no compara arrays: el tipo declarado si
     * termina en {@code []}; si no, el del valor esperado ({@code new X[]{…}}, cast a array o un dato del
     * caso declarado como array).
     */
    static String tipoArray(String tipoDeclarado, String exprEsperado, List<DatoSpec> datos) {
        if (tipoDeclarado != null && tipoDeclarado.trim().endsWith("[]")) {
            return tipoDeclarado.trim();
        }
        if (exprEsperado == null || exprEsperado.isBlank()) {
            return null;
        }
        ParseResult<Expression> r = IndiceTipos.parser().parseExpression(exprEsperado);
        if (!r.isSuccessful() || r.getResult().isEmpty()) {
            return null;
        }
        Expression ex = r.getResult().get();
        while (ex.isEnclosedExpr()) {
            ex = ex.asEnclosedExpr().getInner();
        }
        if (ex instanceof ArrayCreationExpr ac) {
            return ac.getElementType().asString() + "[]".repeat(ac.getLevels().size());
        }
        if (ex instanceof CastExpr ce && ce.getType() instanceof ArrayType at) {
            return at.asString();
        }
        if (ex instanceof NameExpr ne && datos != null) {
            String nombre = ne.getNameAsString();
            for (DatoSpec d : datos) {
                if (d != null && nombre.equals(d.nombre) && d.tipo != null && d.tipo.trim().endsWith("[]")) {
                    return d.tipo.trim();
                }
            }
        }
        return null;
    }

    /** Aserción de igualdad de arrays por contenido (rama JUnit; AssertJ compara arrays con isEqualTo). */
    private String asercionArray(String esp, String real, String tipoArray, boolean castReal, Set<String> estaticos) {
        String r = castReal ? "(" + tipoArray + ") " + real : real;
        if (p.junit == 4) {
            switch (tipoArray.replace(" ", "")) {
                case "double[]" -> {
                    return usarAssert("assertArrayEquals", estaticos) + "(" + esp + ", " + r + ", 0.0);";
                }
                case "float[]" -> {
                    return usarAssert("assertArrayEquals", estaticos) + "(" + esp + ", " + r + ", 0.0f);";
                }
                case "boolean[]" -> {
                    return usarAssert("assertTrue", estaticos) + "(java.util.Arrays.equals(" + esp + ", " + r + "));";
                }
                default -> {
                    // resto de arrays: assertArrayEquals sin delta
                }
            }
        }
        return usarAssert("assertArrayEquals", estaticos) + "(" + esp + ", " + r + ");";
    }

    private String verificacion(VerificacionSpec v) {
        if (Boolean.TRUE.equals(v.sinInteracciones)) {
            return (p.verifyNoInteractions ? "verifyNoInteractions(" : "verifyZeroInteractions(") + v.mock + ");";
        }
        MethodCallExpr mc = llamada(v.mock, v.llamada);
        int veces = v.veces == null ? 1 : v.veces;
        String modo = veces == 1 ? "" : veces == 0 ? ", never()" : ", times(" + veces + ")";
        return "verify(" + mc.getScope().get() + modo + ")." + mc.getNameAsString() + "(" + argumentos(mc) + ");";
    }

    // ---------------------------------------------------------------- fixtures

    public Bloque fixture(FixtureSpec f) {
        List<String> l = new ArrayList<>();
        List<String> ps = new ArrayList<>();
        if (f.parametros != null) {
            for (ParametroSpec x : f.parametros) {
                ps.add(tipo(x.tipo) + " " + x.nombre);
            }
        }
        l.add("private static " + tipo(f.tipo) + " " + f.nombre + "(" + String.join(", ", ps) + ") {");
        if (f.pasos != null) {
            for (String s : f.pasos) {
                anadir(l, IND, sentencia(s));
            }
        }
        l.add(IND + "return " + expr(f.devuelve) + ";");
        l.add("}");
        String texto = String.join("\n", l);
        StringBuilder b = new StringBuilder();
        b.append(IND).append("// junit-gen:fixture-inicio nombre=").append(f.nombre).append('\n');
        for (String x : l) {
            b.append(x.isEmpty() ? "" : IND + x).append('\n');
        }
        b.append(IND).append("// junit-gen:fixture-fin nombre=").append(f.nombre).append('\n');
        return new Bloque(f.nombre, f.nombre, b.toString(), Textos.sha256Hex(texto, 16), Set.of(), Set.of());
    }

    // ---------------------------------------------------------------- archivo

    private boolean necesitaInyectar() {
        Dependencia.ModoMontaje modo = modoConNovedades(sv.analisis.modo);
        Map<String, String> argumentos = argumentosConstruccion();
        for (Dependencia d : sv.analisis.dependencias) {
            boolean campo = esCampo(d);
            if (modo == Dependencia.ModoMontaje.EXPLICITO && sv.esMock(d) && campo) {
                return true;
            }
            if (campo && sv.reales.containsKey(d.nombre)) {
                return true;
            }
            if (!d.esMockDeclarado() && argumentos.containsKey(d.nombre) && d.obtencion != Dependencia.TipoObtencion.VALOR_CONSTRUCTOR
                    && d.obtencion != Dependencia.TipoObtencion.SETTER) {
                return true;
            }
            if (valorCampoPorDefecto(d, argumentos, new TreeSet<>()) != null) {
                return true;
            }
        }
        return !camposLibres().isEmpty();
    }

    /** true si hay que invocar por reflexión algún {@code @PostConstruct} privado. */
    private boolean necesitaInvocar() {
        if (!sv.postConstruct) {
            return false;
        }
        for (String n : sv.analisis.postConstruct) {
            InfoMetodo m = metodoPostConstruct(n);
            if (m != null && m.privado) {
                return true;
            }
        }
        return false;
    }

    private InfoMetodo metodoPostConstruct(String nombre) {
        for (InfoMetodo m : sv.clase.metodos) {
            if (m.nombre.equals(nombre) && m.parametros.isEmpty() && !m.estatico) {
                return m;
            }
        }
        return null;
    }

    /** Dependencia que acaba en un campo del servicio y se inyecta por reflexión. */
    private static boolean esCampo(Dependencia d) {
        return d.obtencion == Dependencia.TipoObtencion.CAMPO_ANOTADO
                || d.obtencion == Dependencia.TipoObtencion.CAMPO_SIN_ANOTAR
                || d.obtencion == Dependencia.TipoObtencion.CAMPO_DERIVADO
                || (d.obtencion == Dependencia.TipoObtencion.NEW_INTERNO && d.campo != null);
    }

    /** INJECT_MOCKS pasa a EXPLICITO si hay instancias reales o stubs de montaje. */
    private Dependencia.ModoMontaje modoConNovedades(Dependencia.ModoMontaje modo) {
        if (modo == Dependencia.ModoMontaje.INJECT_MOCKS && (!sv.reales.isEmpty() || !stubsMontaje().isEmpty())) {
            return Dependencia.ModoMontaje.EXPLICITO;
        }
        return modo;
    }

    private List<StubSpec> stubsMontaje() {
        return sv.spec.construccion == null || sv.spec.construccion.stubs == null ? List.of() : sv.spec.construccion.stubs;
    }

    /** Claves de construccion.argumentos que no son dependencias: campos de instancia que se fijan por reflexión. */
    private List<String> camposLibres() {
        List<String> r = new ArrayList<>();
        for (String clave : argumentosConstruccion().keySet()) {
            Dependencia d = sv.analisis.dependencia(clave);
            boolean dependencia = d != null && d.obtencion != Dependencia.TipoObtencion.ESTATICA
                    && !(d.obtencion == Dependencia.TipoObtencion.NEW_INTERNO && d.campo == null);
            if (!dependencia) {
                r.add(clave);
            }
        }
        return r;
    }

    /** Elemento ELEMENTO_COLECCION de un contenedor, si está declarado en el test (mock o real); si no, null. */
    private Dependencia elementoDe(Dependencia contenedor) {
        for (Dependencia e : sv.analisis.dependencias) {
            if (e.obtencion == Dependencia.TipoObtencion.ELEMENTO_COLECCION && contenedor.nombre.equals(e.contenedor)
                    && ((p.mockito && sv.esMock(e)) || sv.reales.containsKey(e.nombre))) {
                return e;
            }
        }
        return null;
    }

    /** Expresión por defecto de un contenedor de colección/Optional con su elemento (añade imports); null si no aplica. */
    private String expresionContenedor(Dependencia d, Set<String> imports) {
        if (!AnalizadorDependencias.esColeccionBeans(d.tipo)) {
            return null;
        }
        Dependencia e = elementoDe(d);
        if (e == null) {
            return null;
        }
        String x = AnalizadorDependencias.expresionColeccion(d.tipo, e.nombre, p.java);
        if (x == null) {
            return null;
        }
        String clase = x.substring(0, x.indexOf('.'));
        imports.add("java.util." + clase);
        return expr(x);
    }

    /**
     * Valor que se inyecta por reflexión en un campo no mock sin construccion.argumentos: defecto de {@code @Value}
     * o colección con el elemento. null si no aplica.
     */
    private String valorCampoPorDefecto(Dependencia d, Map<String, String> argumentos, Set<String> imports) {
        if (d.esMockDeclarado() || argumentos.containsKey(d.nombre) || d.obtencion != Dependencia.TipoObtencion.CAMPO_ANOTADO
                || d.campo == null) {
            return null;
        }
        if (d.valorPorDefecto != null) {
            return expr(d.valorPorDefecto);
        }
        return expresionContenedor(d, imports);
    }

    private Map<String, String> argumentosConstruccion() {
        return sv.spec.construccion == null || sv.spec.construccion.argumentos == null ? Map.of() : sv.spec.construccion.argumentos;
    }

    /** Líneas del montaje (campos, setUp, tearDown); su hash identifica el montaje. */
    private List<String> montaje(Set<String> imports) {
        List<String> l = new ArrayList<>();
        Dependencia.ModoMontaje modo = modoConNovedades(p.mockito ? sv.analisis.modo : Dependencia.ModoMontaje.SIN_MOCKS);
        String svc = sv.clase.simple;
        if (p.mockito) {
            for (Dependencia d : sv.mocks()) {
                if (sv.profundos.contains(d.nombre)) {
                    l.add("@Mock(answer = Answers.RETURNS_DEEP_STUBS)");
                    imports.add("org.mockito.Answers");
                } else {
                    l.add("@Mock");
                }
                l.add("private " + tipo(d.tipo) + " " + d.nombre + ";");
                l.add("");
                imports.add("org.mockito.Mock");
            }
        }
        for (String real : sv.reales.keySet()) {
            l.add("private " + tipo(sv.analisis.dependencia(real).tipo) + " " + real + ";");
            l.add("");
        }
        if (modo == Dependencia.ModoMontaje.INJECT_MOCKS) {
            l.add("@InjectMocks");
            imports.add("org.mockito.InjectMocks");
        }
        l.add("private " + svc + " " + sut + ";");
        if (p.openMocks) {
            l.add("");
            l.add("private AutoCloseable mocks;");
        }
        List<String> setUp = new ArrayList<>();
        if (p.openMocks) {
            setUp.add("mocks = MockitoAnnotations.openMocks(this);");
            imports.add("org.mockito.MockitoAnnotations");
        } else if (p.initMocks) {
            setUp.add("MockitoAnnotations.initMocks(this);");
            imports.add("org.mockito.MockitoAnnotations");
        }
        Map<String, String> argumentos = argumentosConstruccion();
        for (Map.Entry<String, String> real : sv.reales.entrySet()) {
            setUp.add(real.getKey() + " = " + expr(real.getValue()) + ";");
        }
        if (p.mockito && p.lenient) {
            for (Dependencia cont : sv.analisis.dependencias) {
                if (!AnalizadorDependencias.esProveedorBeans(cont.tipo) || !sv.esMock(cont)) {
                    continue;
                }
                Dependencia e = elementoDe(cont);
                if (e == null) {
                    continue;
                }
                for (String m : AnalizadorDependencias.metodosProveedor(cont.tipo)) {
                    setUp.add("lenient().when(" + cont.nombre + "." + m + "()).thenReturn(" + e.nombre + ");");
                }
            }
        }
        if (p.mockito) {
            List<StubSpec> stubs = stubsMontaje();
            for (int i = 0; i < stubs.size(); i++) {
                StubSpec s = stubs.get(i);
                setUp.add((p.lenient ? "lenient()." : "") + stub(s, sv.formaStubMontaje.getOrDefault(i, "when"), s.mock));
            }
        }
        if (modo != Dependencia.ModoMontaje.INJECT_MOCKS) {
            List<String> args = new ArrayList<>();
            if (sv.analisis.constructor != null) {
                for (int i = 0; i < sv.analisis.constructor.parametros.size(); i++) {
                    Dependencia d = dependenciaPosicion(i);
                    if (d == null) {
                        args.add("null");
                    } else if (sv.esMock(d) && p.mockito) {
                        args.add(d.nombre);
                    } else if (sv.reales.containsKey(d.nombre)) {
                        args.add(d.nombre);
                    } else if (argumentos.containsKey(d.nombre)) {
                        args.add(expr(argumentos.get(d.nombre)));
                    } else if (d.valorPorDefecto != null) {
                        args.add(expr(d.valorPorDefecto));
                    } else {
                        String cont = expresionContenedor(d, imports);
                        args.add(cont != null ? cont : valorPorDefecto(d.tipo));
                    }
                }
            }
            setUp.add(sut + " = new " + svc + "(" + String.join(", ", args) + ");");
            for (Dependencia d : sv.analisis.dependencias) {
                if (d.obtencion == Dependencia.TipoObtencion.SETTER) {
                    if ((sv.esMock(d) && p.mockito) || sv.reales.containsKey(d.nombre)) {
                        setUp.add(sut + "." + d.setter + "(" + d.nombre + ");");
                    } else if (argumentos.containsKey(d.nombre)) {
                        setUp.add(sut + "." + d.setter + "(" + expr(argumentos.get(d.nombre)) + ");");
                    }
                }
            }
            for (Dependencia d : sv.analisis.dependencias) {
                if (esCampo(d) && ((p.mockito && sv.esMock(d)) || sv.reales.containsKey(d.nombre))) {
                    setUp.add("inyectar(" + sut + ", " + Textos.literalJava(d.campo) + ", " + d.nombre + ");");
                }
            }
        } else {
            for (Dependencia d : sv.analisis.dependencias) {
                if (d.obtencion == Dependencia.TipoObtencion.SETTER && !d.esMockDeclarado() && argumentos.containsKey(d.nombre)) {
                    setUp.add(sut + "." + d.setter + "(" + expr(argumentos.get(d.nombre)) + ");");
                }
            }
        }
        for (Dependencia d : sv.analisis.dependencias) {
            if (!d.esMockDeclarado() && argumentos.containsKey(d.nombre) && d.obtencion != Dependencia.TipoObtencion.VALOR_CONSTRUCTOR
                    && d.obtencion != Dependencia.TipoObtencion.SETTER) {
                setUp.add("inyectar(" + sut + ", " + Textos.literalJava(d.campo != null ? d.campo : d.nombre) + ", "
                        + expr(argumentos.get(d.nombre)) + ");");
            } else {
                String defecto = valorCampoPorDefecto(d, argumentos, imports);
                if (defecto != null) {
                    setUp.add("inyectar(" + sut + ", " + Textos.literalJava(d.campo) + ", " + defecto + ");");
                }
            }
        }
        for (String campo : camposLibres()) {
            setUp.add("inyectar(" + sut + ", " + Textos.literalJava(campo) + ", " + expr(argumentos.get(campo)) + ");");
        }
        if (sv.postConstruct) {
            for (String n : sv.analisis.postConstruct) {
                InfoMetodo m = metodoPostConstruct(n);
                if (m == null) {
                    continue;
                }
                setUp.add(m.privado ? "invocar(" + sut + ", " + Textos.literalJava(n) + ");" : sut + "." + n + "();");
            }
        }
        String pub = p.junit == 4 || p.clasePublica ? "public " : "";
        if (!setUp.isEmpty()) {
            l.add("");
            l.add(p.junit == 5 ? "@BeforeEach" : "@Before");
            imports.add(p.junit == 5 ? "org.junit.jupiter.api.BeforeEach" : "org.junit.Before");
            l.add(pub + "void setUp() throws Exception {");
            for (String s : setUp) {
                anadir(l, IND, s);
            }
            l.add("}");
        }
        List<AnalisisServicio.Limpieza> limpiezas = sv.analisis.limpiezas;
        if (p.openMocks || !limpiezas.isEmpty()) {
            boolean j5 = p.junit == 5 || (p.openMocks && limpiezas.isEmpty());
            l.add("");
            l.add(j5 ? "@AfterEach" : "@After");
            imports.add(j5 ? "org.junit.jupiter.api.AfterEach" : "org.junit.After");
            l.add(pub + "void tearDown() throws Exception {");
            for (AnalisisServicio.Limpieza lp : limpiezas) {
                l.add(IND + sentencia(lp.sentencia()));
                imports.add(lp.importa());
            }
            if (p.openMocks) {
                l.add(IND + "mocks.close();");
            }
            l.add("}");
        }
        return l;
    }

    private Dependencia dependenciaPosicion(int i) {
        for (Dependencia d : sv.analisis.dependencias) {
            if (d.posicionConstructor != null && d.posicionConstructor == i) {
                return d;
            }
        }
        return null;
    }

    private static String valorPorDefecto(String tipo) {
        return switch (tipo) {
            case "int", "short", "byte" -> "0";
            case "long" -> "0L";
            case "double" -> "0.0";
            case "float" -> "0f";
            case "boolean" -> "false";
            case "char" -> "'\\0'";
            default -> "null";
        };
    }

    public String hashMontaje() {
        List<String> l = montaje(new TreeSet<>());
        return Textos.sha256Hex(p.nombre() + "|" + String.join("\n", l) + "|" + necesitaInyectar()
                + (necesitaInvocar() ? "|invocar" : ""), 16);
    }

    /** Imports base del archivo (plantilla + tipos del spec). */
    public Set<String> importsArchivo(List<Bloque> casos) {
        Set<String> imports = new TreeSet<>();
        montaje(imports);
        imports.add(p.junit == 5 ? "org.junit.jupiter.api.Test" : "org.junit.Test");
        if (p.extension) {
            imports.add("org.junit.jupiter.api.extension.ExtendWith");
            imports.add("org.mockito.junit.jupiter.MockitoExtension");
        }
        if (p.runner) {
            imports.add("org.junit.runner.RunWith");
            imports.add(p.paqueteRunner + ".MockitoJUnitRunner");
        }
        if (necesitaInyectar()) {
            imports.add("java.lang.reflect.Field");
        }
        if (necesitaInvocar()) {
            imports.add("java.lang.reflect.Method");
        }
        for (Bloque b : casos) {
            imports.addAll(b.imports());
        }
        // Tipos del spec: un FQN nuevo cuyo nombre simple ya está importado (plantilla o bloques) se omite.
        for (String fqn : sv.imports.values()) {
            if (fqn == null || fqn.isEmpty()) {
                continue;
            }
            if (fqn.endsWith(".*") || imports.contains(fqn) || !chocaNombreSimple(imports, fqn)) {
                imports.add(fqn);
            }
        }
        return imports;
    }

    /** true si algún import no comodín de {@code imports} tiene el mismo nombre simple que {@code fqn} con otro FQN. */
    static boolean chocaNombreSimple(Set<String> imports, String fqn) {
        String simple = fqn.substring(fqn.lastIndexOf('.') + 1);
        for (String i : imports) {
            if (!i.endsWith(".*") && !i.equals(fqn) && i.substring(i.lastIndexOf('.') + 1).equals(simple)) {
                return true;
            }
        }
        return false;
    }

    public Set<String> estaticosArchivo(List<Bloque> casos) {
        Set<String> est = new TreeSet<>();
        if (p.mockito) {
            est.add("org.mockito.Mockito.*");
        }
        for (Bloque b : casos) {
            est.addAll(b.estaticos());
        }
        return est;
    }

    public static String lineaImport(String fqn) {
        return "import " + fqn + ";";
    }

    public static String lineaEstatico(String fqn) {
        return "import static " + fqn + ";";
    }

    public String archivoNuevo(List<Bloque> casos, List<Bloque> fixtures) {
        StringBuilder sb = new StringBuilder();
        sb.append(M_MONTAJE).append(hashMontaje()).append('\n');
        sb.append("// Generado por junit-gen ").append(Version.version())
                .append(". Revísalo y cópialo a mano a src/test/java cuando te convenza.\n");
        sb.append("// Copiar a: ").append(RutasSalida.destinoCopia(sv.modulo, sv.clase, sv.claseTest)).append('\n');
        sb.append("// Los comentarios \"junit-gen:\" son marcadores: consérvalos para poder ampliar el archivo sin reescribirlo.\n");
        if (!sv.clase.paquete.isEmpty()) {
            sb.append("package ").append(sv.clase.paquete).append(";\n");
        }
        sb.append('\n');
        for (String i : importsArchivo(casos)) {
            sb.append(lineaImport(i)).append('\n');
        }
        sb.append('\n');
        for (String e : estaticosArchivo(casos)) {
            sb.append(lineaEstatico(e)).append('\n');
        }
        sb.append(M_IMPORTS_FIN).append("\n\n");
        sb.append(cabeceraClase());
        for (Bloque b : casos) {
            sb.append(b.texto()).append('\n');
        }
        sb.append(IND).append(M_CASOS_FIN).append("\n\n");
        for (Bloque b : fixtures) {
            sb.append(b.texto()).append('\n');
        }
        sb.append(IND).append(M_FIXTURES_FIN).append('\n');
        sb.append(pieClase());
        return sb.toString();
    }

    /** Anotación de clase, línea {@code class}, línea en blanco, montaje indentado y línea en blanco final. */
    public String cabeceraClase() {
        StringBuilder sb = new StringBuilder();
        if (p.extension) {
            sb.append("@ExtendWith(MockitoExtension.class)\n");
        } else if (p.runner) {
            sb.append("@RunWith(MockitoJUnitRunner.class)\n");
        }
        sb.append(p.clasePublica ? "public " : "").append("class ").append(sv.claseTest).append(" {\n\n");
        for (String x : montaje(new TreeSet<>())) {
            sb.append(x.isEmpty() ? "" : IND + x).append('\n');
        }
        sb.append('\n');
        return sb.toString();
    }

    /** Helpers de reflexión ({@code inyectar}, {@code invocar}) que necesite el montaje y la llave de cierre. */
    public String pieClase() {
        StringBuilder sb = new StringBuilder();
        if (necesitaInyectar()) {
            sb.append('\n');
            sb.append(IND).append("private static void inyectar(Object destino, String campo, Object valor) throws Exception {\n");
            sb.append(IND).append(IND).append("Class<?> tipo = destino.getClass();\n");
            sb.append(IND).append(IND).append("while (tipo != null) {\n");
            sb.append(IND).append(IND).append(IND).append("try {\n");
            sb.append(IND).append(IND).append(IND).append(IND).append("Field f = tipo.getDeclaredField(campo);\n");
            sb.append(IND).append(IND).append(IND).append(IND).append("f.setAccessible(true);\n");
            sb.append(IND).append(IND).append(IND).append(IND).append("f.set(destino, valor);\n");
            sb.append(IND).append(IND).append(IND).append(IND).append("return;\n");
            sb.append(IND).append(IND).append(IND).append("} catch (NoSuchFieldException e) {\n");
            sb.append(IND).append(IND).append(IND).append(IND).append("tipo = tipo.getSuperclass();\n");
            sb.append(IND).append(IND).append(IND).append("}\n");
            sb.append(IND).append(IND).append("}\n");
            sb.append(IND).append(IND).append("throw new NoSuchFieldException(campo);\n");
            sb.append(IND).append("}\n");
        }
        if (necesitaInvocar()) {
            sb.append('\n');
            sb.append(IND).append("private static void invocar(Object destino, String metodo) throws Exception {\n");
            sb.append(IND).append(IND).append("Class<?> tipo = destino.getClass();\n");
            sb.append(IND).append(IND).append("while (tipo != null) {\n");
            sb.append(IND).append(IND).append(IND).append("try {\n");
            sb.append(IND).append(IND).append(IND).append(IND).append("Method m = tipo.getDeclaredMethod(metodo);\n");
            sb.append(IND).append(IND).append(IND).append(IND).append("m.setAccessible(true);\n");
            sb.append(IND).append(IND).append(IND).append(IND).append("m.invoke(destino);\n");
            sb.append(IND).append(IND).append(IND).append(IND).append("return;\n");
            sb.append(IND).append(IND).append(IND).append("} catch (NoSuchMethodException e) {\n");
            sb.append(IND).append(IND).append(IND).append(IND).append("tipo = tipo.getSuperclass();\n");
            sb.append(IND).append(IND).append(IND).append("}\n");
            sb.append(IND).append(IND).append("}\n");
            sb.append(IND).append(IND).append("throw new NoSuchMethodException(metodo);\n");
            sb.append(IND).append("}\n");
        }
        sb.append("}\n");
        return sb.toString();
    }
}
