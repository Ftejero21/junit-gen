package com.junitgen.duplicados;

import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.DoubleLiteralExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.LongLiteralExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.visitor.ModifierVisitor;
import com.github.javaparser.ast.visitor.Visitable;
import com.junitgen.escaneo.IndiceTipos;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Normaliza expresiones para la huella: sustituye variables locales por su valor (inicialización +
 * mutaciones ordenadas), nombres de mock por el tipo de la dependencia, literales canónicos, sin
 * espacios, casts ni paréntesis redundantes; matchers any*() → &lt;any&gt; y eq(x) → x.
 */
public class NormalizadorExpresiones {

    private static final Set<String> WRAPPERS = Set.of("Long", "Integer", "Short", "Byte", "Double", "Float",
            "Boolean", "Character", "BigDecimal", "BigInteger", "String");
    private static final int PROFUNDIDAD_MAX = 6;

    private final Map<String, String> iniciales = new LinkedHashMap<>();
    private final Map<String, TreeSet<String>> mutaciones = new LinkedHashMap<>();
    private final Map<String, String> mocks = new LinkedHashMap<>();
    private final Map<String, String> fijos = new LinkedHashMap<>();
    private int profundidad;

    /** Registra un mock: nombre de variable → tipo simple de la dependencia. */
    public void mock(String nombre, String tipo) {
        mocks.put(nombre, tipo);
    }

    public boolean esMock(String nombre) {
        return mocks.containsKey(nombre);
    }

    public String tipoMock(String nombre) {
        return mocks.get(nombre);
    }

    /** Nombre fijo (p. ej. la variable de resultado). */
    public void fijo(String nombre, String token) {
        fijos.put(nombre, token);
    }

    public boolean esVariable(String nombre) {
        return iniciales.containsKey(nombre);
    }

    public void declarar(String nombre, Expression valor) {
        iniciales.put(nombre, valor == null ? "?" : normalizar(valor));
        mutaciones.remove(nombre);
    }

    public void declararTexto(String nombre, String valor) {
        Expression e = parsear(valor);
        iniciales.put(nombre, e == null ? sinEspacios(valor) : normalizar(e));
        mutaciones.remove(nombre);
    }

    public void mutar(String variable, MethodCallExpr llamada) {
        if (!iniciales.containsKey(variable)) {
            return;
        }
        StringBuilder sb = new StringBuilder(llamada.getNameAsString()).append('(');
        for (int i = 0; i < llamada.getArguments().size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(normalizar(llamada.getArgument(i)));
        }
        sb.append(')');
        mutaciones.computeIfAbsent(variable, k -> new TreeSet<>()).add(sb.toString());
    }

    public String valor(String nombre) {
        String ini = iniciales.getOrDefault(nombre, nombre);
        TreeSet<String> m = mutaciones.get(nombre);
        if (m == null || m.isEmpty()) {
            return ini;
        }
        return ini + "{" + String.join(";", m) + "}";
    }

    public static Expression parsear(String texto) {
        if (texto == null) {
            return null;
        }
        ParseResult<Expression> r = IndiceTipos.parser().parseExpression(texto);
        return r.isSuccessful() ? r.getResult().orElse(null) : null;
    }

    public String normalizar(String texto) {
        if (texto == null) {
            return "";
        }
        Expression e = parsear(texto);
        return e == null ? sinEspacios(texto) : normalizar(e);
    }

    public String normalizar(Expression original) {
        if (profundidad > PROFUNDIDAD_MAX) {
            return "…";
        }
        profundidad++;
        try {
            Expression e = original.clone();
            Map<String, String> sustituciones = new LinkedHashMap<>();
            List<int[]> contador = new ArrayList<>();
            contador.add(new int[]{0});
            Node r = (Node) e.accept(new ModifierVisitor<Void>() {
                @Override
                public Visitable visit(NameExpr n, Void arg) {
                    String nombre = n.getNameAsString();
                    String sust = null;
                    if (fijos.containsKey(nombre)) {
                        sust = fijos.get(nombre);
                    } else if (mocks.containsKey(nombre)) {
                        sust = mocks.get(nombre);
                    } else if (iniciales.containsKey(nombre)) {
                        sust = valor(nombre);
                    }
                    if (sust == null) {
                        return n;
                    }
                    String token = "ZZJG" + (contador.get(0)[0]++) + "ZZ";
                    sustituciones.put(token, sust);
                    return new NameExpr(token);
                }

                @Override
                public Visitable visit(LongLiteralExpr n, Void arg) {
                    return new IntegerLiteralExpr(canonicoEntero(n.getValue()));
                }

                @Override
                public Visitable visit(IntegerLiteralExpr n, Void arg) {
                    return new IntegerLiteralExpr(canonicoEntero(n.getValue()));
                }

                @Override
                public Visitable visit(DoubleLiteralExpr n, Void arg) {
                    String v = n.getValue().replace("_", "");
                    if (v.endsWith("d") || v.endsWith("D") || v.endsWith("f") || v.endsWith("F")) {
                        v = v.substring(0, v.length() - 1);
                    }
                    if (!v.contains(".") && !v.contains("e") && !v.contains("E")) {
                        v = v + ".0";
                    }
                    return new DoubleLiteralExpr(v);
                }

                @Override
                public Visitable visit(CastExpr n, Void arg) {
                    return n.getExpression().accept(this, arg);
                }

                @Override
                public Visitable visit(EnclosedExpr n, Void arg) {
                    return n.getInner().accept(this, arg);
                }

                @Override
                public Visitable visit(MethodCallExpr n, Void arg) {
                    Visitable v = super.visit(n, arg);
                    if (!(v instanceof MethodCallExpr mc)) {
                        return v;
                    }
                    String nombre = mc.getNameAsString();
                    boolean sinScope = mc.getScope().isEmpty() || esScopeMatchers(mc.getScope().get());
                    if (sinScope && (nombre.startsWith("any") || nombre.equals("notNull") || nombre.equals("isNotNull"))) {
                        String token = "ZZJG" + (contador.get(0)[0]++) + "ZZ";
                        sustituciones.put(token, "<any>");
                        return new NameExpr(token);
                    }
                    if (sinScope && nombre.equals("eq") && mc.getArguments().size() == 1) {
                        return mc.getArgument(0);
                    }
                    if (mc.getScope().isPresent() && mc.getScope().get() instanceof NameExpr s
                            && WRAPPERS.contains(s.getNameAsString()) && nombre.equals("valueOf")
                            && mc.getArguments().size() == 1 && (mc.getArgument(0).isLiteralExpr())) {
                        return mc.getArgument(0);
                    }
                    return mc;
                }
            }, null);
            String texto = sinEspacios(r.toString());
            for (Map.Entry<String, String> s : sustituciones.entrySet()) {
                texto = texto.replace(s.getKey(), s.getValue());
            }
            return texto;
        } finally {
            profundidad--;
        }
    }

    private static boolean esScopeMatchers(Expression scope) {
        if (scope instanceof NameExpr n) {
            String s = n.getNameAsString();
            return s.equals("Mockito") || s.equals("ArgumentMatchers") || s.equals("Matchers") || s.equals("BDDMockito");
        }
        return false;
    }

    static String canonicoEntero(String v) {
        String s = v.replace("_", "");
        if (s.endsWith("L") || s.endsWith("l")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    /** Elimina espacios fuera de literales de cadena y de carácter. */
    public static String sinEspacios(String s) {
        StringBuilder sb = new StringBuilder();
        boolean enCadena = false;
        char delim = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (enCadena) {
                sb.append(c);
                if (c == '\\' && i + 1 < s.length()) {
                    sb.append(s.charAt(++i));
                } else if (c == delim) {
                    enCadena = false;
                }
            } else if (c == '"' || c == '\'') {
                enCadena = true;
                delim = c;
                sb.append(c);
            } else if (!Character.isWhitespace(c)) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Nombre simple de una excepción escrita como X, X.class o new X(...). */
    public static String nombreExcepcion(String texto) {
        if (texto == null) {
            return "?";
        }
        Expression e = parsear(texto);
        if (e != null) {
            if (e.isObjectCreationExpr()) {
                return e.asObjectCreationExpr().getType().getNameAsString();
            }
            if (e.isClassExpr()) {
                return com.junitgen.util.Textos.nombreSimple(e.asClassExpr().getType().asString());
            }
        }
        return com.junitgen.util.Textos.nombreSimple(texto.trim().replace(".class", ""));
    }
}
