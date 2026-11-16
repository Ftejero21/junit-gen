package com.junitgen.spec;

import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.ClassExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.junitgen.escaneo.IndiceTipos;

/**
 * Clasifica una expresión de {@code construccion.argumentos} como creación de mock de Mockito
 * ({@code mock(X.class)}, {@code Mockito.mock(X.class, RETURNS_DEEP_STUBS)}…). Usa JavaParser, nunca texto libre.
 */
public final class ArgumentoMock {

    public enum Forma { NO, MOCK, PROFUNDO }

    private ArgumentoMock() {
    }

    /** Forma de la expresión: {@link Forma#NO} si es nula, blanca, no parsea o no es una creación de mock reconocida. */
    public static Forma forma(String expresion) {
        if (expresion == null || expresion.isBlank()) {
            return Forma.NO;
        }
        Expression e;
        try {
            ParseResult<Expression> r = IndiceTipos.parser().parseExpression(expresion);
            if (!r.isSuccessful() || r.getResult().isEmpty()) {
                return Forma.NO;
            }
            e = r.getResult().get();
        } catch (RuntimeException ex) {
            return Forma.NO;
        }
        while (e instanceof EnclosedExpr || e instanceof CastExpr) {
            e = e instanceof EnclosedExpr en ? en.getInner() : ((CastExpr) e).getExpression();
        }
        if (!(e instanceof MethodCallExpr mc) || !mc.getNameAsString().equals("mock")) {
            return Forma.NO;
        }
        if (mc.getScope().isPresent()) {
            String scope = mc.getScope().get().toString();
            if (!scope.equals("Mockito") && !scope.equals("org.mockito.Mockito")) {
                return Forma.NO;
            }
        }
        int n = mc.getArguments().size();
        if (n == 0) {
            return Forma.MOCK;
        }
        if (!(mc.getArgument(0) instanceof ClassExpr)) {
            return Forma.NO;
        }
        if (n == 1) {
            return Forma.MOCK;
        }
        if (n == 2) {
            Expression segundo = mc.getArgument(1);
            String ultimo;
            if (segundo instanceof NameExpr ne) {
                ultimo = ne.getNameAsString();
            } else if (segundo instanceof FieldAccessExpr fa) {
                String s = fa.getScope().toString();
                if (!s.equals("Answers") && !s.equals("Mockito") && !s.equals("org.mockito.Answers")
                        && !s.equals("org.mockito.Mockito")) {
                    return Forma.NO;
                }
                ultimo = fa.getNameAsString();
            } else {
                return Forma.NO;
            }
            if (ultimo.equals("RETURNS_DEEP_STUBS")) {
                return Forma.PROFUNDO;
            }
            if (ultimo.equals("RETURNS_DEFAULTS")) {
                return Forma.MOCK;
            }
        }
        return Forma.NO;
    }
}
