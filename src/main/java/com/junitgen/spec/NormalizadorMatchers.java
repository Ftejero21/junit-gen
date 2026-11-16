package com.junitgen.spec;

import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.ClassExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.junitgen.escaneo.FirmasReales;
import com.junitgen.escaneo.IndiceTipos;
import com.junitgen.escaneo.InfoClase;
import com.junitgen.escaneo.InfoMetodo;
import com.junitgen.proyecto.VersionesProyecto;
import com.junitgen.util.Textos;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Normalización determinista de los matchers de Mockito de una llamada de stub o verificación, antes de generar: valores
 * mezclados con matchers → {@code eq(valor)}; {@code any()} sin tipo en un parámetro primitivo → {@code anyInt()} y
 * similares; {@code any()} sin tipo en un método sobrecargado → matcher tipado con el tipo real. Puro: sin E/S.
 */
public final class NormalizadorMatchers {

    /** Matchers de Mockito reconocidos. */
    public static final Set<String> MATCHERS = Set.of("any", "anyBoolean", "anyByte", "anyChar", "anyCollection", "anyDouble",
            "anyFloat", "anyInt", "anyIterable", "anyList", "anyLong", "anyMap", "anySet", "anyShort", "anyString", "argThat",
            "booleanThat", "byteThat", "charThat", "contains", "doubleThat", "endsWith", "eq", "floatThat", "intThat", "isA",
            "isNotNull", "isNull", "longThat", "matches", "notNull", "nullable", "refEq", "same", "shortThat", "startsWith", "and",
            "or", "not", "gt", "geq", "lt", "leq");
    /** Clases cuyo método estático es un matcher. */
    public static final Set<String> SCOPES_MATCHERS = Set.of("ArgumentMatchers", "Mockito", "Matchers", "BDDMockito",
            "AdditionalMatchers");
    /** Matchers que aceptan cualquier valor del tipo (con o sin tipo). */
    private static final Set<String> CUALQUIER_VALOR = Set.of("any", "anyObject", "anyBoolean", "anyByte", "anyChar",
            "anyCollection", "anyDouble", "anyFloat", "anyInt", "anyIterable", "anyList", "anyLong", "anyMap", "anySet",
            "anyShort", "anyString", "isA", "nullable");
    private static final String CAPTURE = "capture";
    private static final String EQ = "eq";
    private static final String RECEPTOR = "x";
    private static final String PREFIJO_RECEPTOR = RECEPTOR + ".";
    private static final String ANY = "any";
    private static final String NULLABLE = "nullable";
    /** Versión de Mockito desde la que existe {@code nullable}. */
    private static final String VERSION_NULLABLE = "2.1.0";
    private static final Set<String> PRIMITIVOS = Set.of("int", "long", "boolean", "double", "float", "short", "byte", "char");
    private static final String OBJECT = "Object";

    /** Decisiones que dependen de la versión de Mockito del proyecto. */
    public record Politica(boolean nullable) {
        /** {@code nullable} solo con versión conocida ≥ 2.1; si no, {@code any(T.class)}. */
        public static Politica de(VersionesProyecto v) {
            return new Politica(v != null && Textos.versionAlMenos(v.versionMockito, VERSION_NULLABLE));
        }
    }

    private NormalizadorMatchers() {
    }

    /**
     * Llamada normalizada ({@code llamada} es la parte tras el mock: {@code metodo(args)}); la misma si nada cambia, no
     * parsea o es encadenada (deep stubs). Añade a {@code frases} una frase por cambio. {@code f} puede ser null.
     * {@code tipoUsable} decide si un tipo (FQN, o nombre simple si no hay FQN) puede escribirse en el test.
     */
    public static String normalizar(String mock, String llamada, FirmasReales.Firma f, Politica p,
                                    Predicate<String> tipoUsable, List<String> frases) {
        if (llamada == null || llamada.isBlank()) {
            return llamada;
        }
        try {
            MethodCallExpr mc = parsear(llamada);
            if (mc == null) {
                return llamada;
            }
            String donde = (mock == null || mock.isEmpty() ? "" : mock + ".") + llamada.trim();
            List<String> nuevas = new ArrayList<>();
            boolean cambia = envolverValores(mc, nuevas, donde);
            cambia |= tipar(mc, f, p == null ? new Politica(false) : p, tipoUsable, nuevas, donde);
            if (!cambia) {
                return llamada;
            }
            String texto = mc.toString();
            if (!texto.startsWith(PREFIJO_RECEPTOR)) {
                return llamada;
            }
            if (frases != null) {
                frases.addAll(nuevas);
            }
            return texto.substring(PREFIJO_RECEPTOR.length());
        } catch (RuntimeException e) {
            System.err.println("junit-gen: no se pudieron normalizar los matchers de una llamada (" + e.getClass().getSimpleName() + ")");
            return llamada;
        }
    }

    /**
     * Como {@link #normalizar(String, String, FirmasReales.Firma, Politica, Predicate, List)} calculando la firma real con
     * {@code firmas} (índice, reflexión y sitios de llamada del servicio); {@code firmas} null = solo el paso de {@code eq}.
     * La firma solo se calcula si la llamada trae algún {@code any()} sin tipo.
     */
    public static String normalizar(String mock, String llamada, FirmasReales firmas, InfoClase servicio, InfoMetodo probado,
                                    String tipoMock, Set<String> receptores, Politica p, Predicate<String> tipoUsable,
                                    List<String> frases) {
        if (llamada == null || llamada.isBlank()) {
            return llamada;
        }
        FirmasReales.Firma f = null;
        try {
            MethodCallExpr mc = parsear(llamada);
            if (mc != null && firmas != null && mc.getArguments().stream().anyMatch(a -> a instanceof MethodCallExpr m && esAnySinTipo(m))) {
                f = firmas.de(servicio, probado, tipoMock, receptores, mc.getNameAsString(), new ArrayList<>(mc.getArguments()));
            }
        } catch (RuntimeException e) {
            System.err.println("junit-gen: no se pudo calcular la firma de una llamada (" + e.getClass().getSimpleName() + ")");
        }
        return normalizar(mock, llamada, f, p, tipoUsable, frases);
    }

    /** Llamada {@code x.llamada} con receptor simple; null si no parsea o está encadenada. */
    private static MethodCallExpr parsear(String llamada) {
        ParseResult<Expression> pr = IndiceTipos.parser().parseExpression(PREFIJO_RECEPTOR + llamada.trim());
        if (!pr.isSuccessful() || pr.getResult().isEmpty() || !(pr.getResult().get() instanceof MethodCallExpr mc)) {
            return null;
        }
        return mc.getScope().isPresent() && mc.getScope().get() instanceof NameExpr n && RECEPTOR.equals(n.getNameAsString())
                ? mc : null;
    }

    /** Paso 1: matchers mezclados con valores → los valores envueltos en {@code eq(..)}. */
    private static boolean envolverValores(MethodCallExpr mc, List<String> frases, String donde) {
        NodeList<Expression> args = mc.getArguments();
        int matchers = 0;
        int valores = 0;
        for (Expression e : args) {
            if (esMatcher(e)) {
                matchers++;
            } else {
                valores++;
            }
        }
        if (matchers == 0 || valores == 0) {
            return false;
        }
        for (int i = 0; i < args.size(); i++) {
            Expression e = args.get(i);
            if (!esMatcher(e)) {
                args.set(i, new MethodCallExpr(null, EQ, new NodeList<>(e.clone())));
            }
        }
        frases.add("Se envolvieron con `eq(...)` los valores de `" + donde
                + "`: Mockito no admite mezclar matchers y valores en la misma llamada");
        return true;
    }

    /** Paso 2: {@code any()} sin tipo → matcher primitivo o tipado según la firma. */
    private static boolean tipar(MethodCallExpr mc, FirmasReales.Firma f, Politica p, Predicate<String> tipoUsable,
                                 List<String> frases, String donde) {
        if (f == null || f.tipos().size() != mc.getArguments().size()) {
            return false;
        }
        boolean cambia = false;
        NodeList<Expression> args = mc.getArguments();
        for (int i = 0; i < args.size(); i++) {
            if (!(args.get(i) instanceof MethodCallExpr m) || !esAnySinTipo(m)) {
                continue;
            }
            String tipo = f.tipos().get(i);
            if (tipo == null) {
                continue;
            }
            Expression scope = m.getScope().map(Expression::clone).orElse(null);
            if (f.primitivo()[i] && PRIMITIVOS.contains(tipo)) {
                String nombre = ANY + Character.toUpperCase(tipo.charAt(0)) + tipo.substring(1);
                args.set(i, new MethodCallExpr(scope, nombre));
                frases.add("Se cambió `any()` por `" + nombre + "()` en `" + donde + "`: el parámetro es primitivo y `any()` "
                        + "devuelve null");
                cambia = true;
            } else if (!f.primitivo()[i] && f.sobrecargado() && !OBJECT.equals(tipo) && !PRIMITIVOS.contains(tipo)) {
                String fqn = f.fqns().get(i);
                if (!tipoUsable.test(fqn != null ? fqn : tipo)) {
                    continue;
                }
                String nombre = p.nullable() ? NULLABLE : ANY;
                NodeList<Expression> a = new NodeList<>(new ClassExpr(new ClassOrInterfaceType(null, tipo)));
                args.set(i, new MethodCallExpr(scope, nombre, a));
                frases.add("Se cambió `any()` por `" + nombre + "(" + tipo + ".class)` en `" + donde + "`: el método está "
                        + "sobrecargado y `any()` sin tipo puede resolverse a otra sobrecarga");
                cambia = true;
            }
        }
        return cambia;
    }

    /** {@code any()} o {@code anyObject()} sin argumentos y sin cast, sin ámbito o de la clase de matchers. */
    private static boolean esAnySinTipo(MethodCallExpr m) {
        if (!m.getArguments().isEmpty() || !(ANY.equals(m.getNameAsString()) || "anyObject".equals(m.getNameAsString()))) {
            return false;
        }
        return m.getScope().isEmpty() || SCOPES_MATCHERS.contains(ultimoNombre(m.getScope().get()));
    }

    private static String ultimoNombre(Expression s) {
        return s instanceof NameExpr n ? n.getNameAsString() : s instanceof FieldAccessExpr fa ? fa.getNameAsString() : "";
    }

    // ------------------------------------------------------------- utilidades compartidas con AutoArreglos

    /** Llamada con los valores envueltos en {@code eq(…)} si mezcla matchers y valores; null si no parsea o no aplica. */
    public static String conEq(String llamada) {
        if (llamada == null || llamada.isBlank()) {
            return null;
        }
        ParseResult<Expression> pr = IndiceTipos.parser().parseExpression(PREFIJO_RECEPTOR + llamada.trim());
        if (!pr.isSuccessful() || pr.getResult().isEmpty() || !(pr.getResult().get() instanceof MethodCallExpr mc)) {
            return null;
        }
        if (!envolverValores(mc, new ArrayList<>(), "")) {
            return null;
        }
        String texto = mc.toString();
        return texto.startsWith(PREFIJO_RECEPTOR) ? texto.substring(PREFIJO_RECEPTOR.length()) : null;
    }

    /** true si la expresión es un matcher de Mockito (con cast opcional) o un {@code captor.capture()}. */
    public static boolean esMatcher(Expression e) {
        if (e instanceof CastExpr ce) {
            return esMatcher(ce.getExpression());
        }
        if (!(e instanceof MethodCallExpr m)) {
            return false;
        }
        String nombre = m.getNameAsString();
        if (CAPTURE.equals(nombre) && m.getScope().isPresent()) {
            return true;
        }
        if (!MATCHERS.contains(nombre)) {
            return false;
        }
        return m.getScope().isEmpty() || SCOPES_MATCHERS.contains(ultimoNombre(m.getScope().get()));
    }

    /**
     * Forma canónica de una llamada para compararla: {@code eq(x)} → {@code x} y cualquier matcher «de cualquier valor»
     * ({@code any()}, {@code any(T.class)}, {@code nullable(T.class)}, {@code anyX()}, {@code isA(T.class)}, {@code (T) any()})
     * → {@code any}. Sin espacios; si no parsea, la llamada tal cual sin espacios.
     */
    public static String clave(String llamada) {
        if (llamada == null) {
            return "";
        }
        try {
            ParseResult<Expression> pr = IndiceTipos.parser().parseExpression(PREFIJO_RECEPTOR + llamada.trim());
            if (!pr.isSuccessful() || pr.getResult().isEmpty() || !(pr.getResult().get() instanceof MethodCallExpr mc)) {
                return sinEspacios(llamada);
            }
            MethodCallExpr copia = mc.clone();
            NodeList<Expression> args = copia.getArguments();
            for (int i = 0; i < args.size(); i++) {
                args.set(i, canonico(args.get(i)));
            }
            String texto = copia.toString();
            return sinEspacios(texto.startsWith(PREFIJO_RECEPTOR) ? texto.substring(PREFIJO_RECEPTOR.length()) : texto);
        } catch (RuntimeException e) {
            return sinEspacios(llamada);
        }
    }

    private static Expression canonico(Expression e) {
        Expression x = e;
        while (x instanceof CastExpr ce) {
            x = ce.getExpression();
        }
        if (x instanceof MethodCallExpr m && (m.getScope().isEmpty() || SCOPES_MATCHERS.contains(ultimoNombre(m.getScope().get())))) {
            if (CUALQUIER_VALOR.contains(m.getNameAsString())) {
                return new NameExpr(ANY);
            }
            if (EQ.equals(m.getNameAsString()) && m.getArguments().size() == 1) {
                return m.getArgument(0);
            }
        }
        return e;
    }

    private static String sinEspacios(String s) {
        return s.replaceAll("\\s+", "");
    }
}
