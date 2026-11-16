package com.junitgen.util;

import java.util.Set;

/** Palabras reservadas de Java (incluye literales y palabras contextuales problemáticas). */
public final class JavaReservadas {

    private static final Set<String> RESERVADAS = Set.of("abstract", "assert", "boolean", "break", "byte", "case",
            "catch", "char", "class", "const", "continue", "default", "do", "double", "else", "enum", "extends",
            "final", "finally", "float", "for", "goto", "if", "implements", "import", "instanceof", "int",
            "interface", "long", "native", "new", "package", "private", "protected", "public", "return", "short",
            "static", "strictfp", "super", "switch", "synchronized", "this", "throw", "throws", "transient", "try",
            "void", "volatile", "while", "true", "false", "null", "var", "record", "yield", "_");

    private JavaReservadas() {
    }

    public static boolean esReservada(String s) {
        return RESERVADAS.contains(s);
    }
}
