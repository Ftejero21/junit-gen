package com.junitgen.spec;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.InstanceOfExpr;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.SwitchExpr;
import com.github.javaparser.ast.expr.TextBlockLiteralExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.type.VarType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Versión mínima de Java de construcciones y APIs usadas en el spec.
 * <ul>
 *     <li>lambdas y method references: 8</li>
 *     <li>List/Set/Map.of, Map.entry: 9</li>
 *     <li>var, List.copyOf: 10</li>
 *     <li>String.isBlank/strip/repeat/lines, Optional.isEmpty: 11</li>
 *     <li>switch como expresión: 14; text blocks: 15; instanceof con patrón, Stream.toList: 16</li>
 * </ul>
 */
public final class CompatibilidadJava {

    public static final Map<String, Integer> TABLA = new LinkedHashMap<>();

    static {
        TABLA.put("diamante <>", 7);
        TABLA.put("lambdas y method references", 8);
        TABLA.put("List.of / Set.of / Map.of / Map.ofEntries / Map.entry", 9);
        TABLA.put("var / List.copyOf / Set.copyOf / Map.copyOf", 10);
        TABLA.put("String.isBlank / strip / repeat / lines, Optional.isEmpty", 11);
        TABLA.put("switch como expresión", 14);
        TABLA.put("text blocks \"\"\"", 15);
        TABLA.put("instanceof con patrón, Stream.toList()", 16);
    }

    private static final Set<String> COLECCIONES = Set.of("List", "Set", "Map");
    private static final Set<String> STRING_11 = Set.of("isBlank", "strip", "stripLeading", "stripTrailing", "repeat", "lines");

    private CompatibilidadJava() {
    }

    /** Restricciones aplicables a una versión de Java (para el detalle del servicio y los prompts). */
    public static List<String> restricciones(int java) {
        List<String> r = new ArrayList<>();
        for (Map.Entry<String, Integer> e : TABLA.entrySet()) {
            if (java < e.getValue()) {
                r.add("No usar " + e.getKey() + " (requiere Java " + e.getValue() + "; el proyecto usa Java " + java + ").");
            }
        }
        if (r.isEmpty()) {
            r.add("Sin restricciones de sintaxis relevantes para Java " + java + ".");
        }
        return r;
    }

    /** Problemas de compatibilidad del nodo con la versión de Java indicada. */
    public static List<String> comprobar(Node n, int java) {
        List<String> r = new ArrayList<>();
        if (java < 7 && n.findFirst(ObjectCreationExpr.class, oc -> oc.getType().isUsingDiamondOperator()).isPresent()) {
            r.add("el operador diamante <> requiere Java 7");
        }
        if (java < 8 && (n.findFirst(LambdaExpr.class).isPresent() || n.findFirst(MethodReferenceExpr.class).isPresent())) {
            r.add("las lambdas y method references requieren Java 8");
        }
        for (MethodCallExpr mc : n.findAll(MethodCallExpr.class)) {
            String nombre = mc.getNameAsString();
            String scope = mc.getScope().filter(s -> s instanceof NameExpr).map(s -> ((NameExpr) s).getNameAsString()).orElse(null);
            if (java < 9 && scope != null && COLECCIONES.contains(scope) && (nombre.equals("of") || nombre.equals("ofEntries"))) {
                r.add(scope + "." + nombre + "(...) requiere Java 9");
            }
            if (java < 9 && "Map".equals(scope) && nombre.equals("entry")) {
                r.add("Map.entry(...) requiere Java 9");
            }
            if (java < 10 && scope != null && COLECCIONES.contains(scope) && nombre.equals("copyOf")) {
                r.add(scope + ".copyOf(...) requiere Java 10");
            }
            if (java < 11 && STRING_11.contains(nombre) && mc.getArguments().size() <= 1 && mc.getScope().isPresent()) {
                r.add("String." + nombre + "() requiere Java 11");
            }
            if (java < 11 && nombre.equals("isEmpty") && mc.getScope().isPresent() && mc.getScope().get() instanceof MethodCallExpr sc
                    && sc.getScope().filter(s -> s instanceof NameExpr ne && ne.getNameAsString().equals("Optional")).isPresent()) {
                r.add("Optional.isEmpty() requiere Java 11");
            }
            if (java < 16 && nombre.equals("toList") && mc.getArguments().isEmpty() && mc.getScope().isPresent()
                    && !(mc.getScope().get() instanceof NameExpr)) {
                r.add("Stream.toList() requiere Java 16 (usa collect(Collectors.toList()))");
            }
        }
        if (java < 10 && (n.findFirst(VarType.class).isPresent()
                || n.findFirst(VariableDeclarationExpr.class, v -> v.getElementType().isVarType()).isPresent())) {
            r.add("'var' requiere Java 10");
        }
        if (java < 14 && n.findFirst(SwitchExpr.class).isPresent()) {
            r.add("switch como expresión requiere Java 14");
        }
        if (java < 15 && n.findFirst(TextBlockLiteralExpr.class).isPresent()) {
            r.add("los text blocks requieren Java 15");
        }
        if (java < 16 && n.findFirst(InstanceOfExpr.class, i -> i.getPattern().isPresent()).isPresent()) {
            r.add("instanceof con patrón requiere Java 16");
        }
        return r;
    }
}
