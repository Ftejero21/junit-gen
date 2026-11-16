package com.junitgen.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Utilidades de texto: hashes, saneado, comparación de versiones y globs. */
public final class Textos {

    public static final Pattern IDENTIFICADOR = Pattern.compile("[A-Za-z_$][\\w$]*");
    public static final Pattern ID_CASO = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");

    private Textos() {
    }

    public static String sha256Hex(String texto, int longitud) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            String hex = HexFormat.of().formatHex(md.digest(texto.getBytes(StandardCharsets.UTF_8)));
            return hex.substring(0, Math.min(longitud, hex.length()));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 no disponible", e);
        }
    }

    public static String sha256Hex(byte[] datos, int longitud) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            String hex = HexFormat.of().formatHex(md.digest(datos));
            return hex.substring(0, Math.min(longitud, hex.length()));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 no disponible", e);
        }
    }

    public static boolean esIdentificador(String s) {
        return s != null && IDENTIFICADOR.matcher(s).matches() && !JavaReservadas.esReservada(s);
    }

    /** Sin saltos de línea ni cierre de comentario; recorta a 300 caracteres. */
    public static String sanearDescripcion(String s) {
        if (s == null) {
            return null;
        }
        String r = s.replace("\r", " ").replace("\n", " ").replace("*/", "* /").replace(" ", " ")
                .replace(" ", " ").trim();
        return r.length() > 300 ? r.substring(0, 300) : r;
    }

    /**
     * Sanea un texto para un comentario de línea Java: colapsa todo espacio o salto (incl. U+0085, U+2028,
     * U+2029) a un espacio, elimina caracteres de control, cambia la barra invertida por {@code /} (evita
     * escapes Unicode que javac interpreta), {@code //} por {@code /} y {@code junit-gen:} por {@code junit-gen }
     * (impide falsificar marcadores) y recorta a {@code max} caracteres con «…». null → null.
     */
    public static String sanearComentario(String s, int max) {
        if (s == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        boolean espacio = false;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isWhitespace(cp) || Character.isSpaceChar(cp) || cp == 0x85 || cp == 0x2028 || cp == 0x2029) {
                espacio = true;
                continue;
            }
            if (Character.isISOControl(cp)) {
                continue;
            }
            if (espacio && sb.length() > 0) {
                sb.append(' ');
            }
            espacio = false;
            sb.appendCodePoint(cp);
        }
        String r = sb.toString().replace('\\', '/');
        while (r.contains("//")) {
            r = r.replace("//", "/");
        }
        r = Pattern.compile("junit-gen:", Pattern.CASE_INSENSITIVE).matcher(r).replaceAll("junit-gen ").trim();
        int max2 = Math.max(1, max);
        if (r.codePointCount(0, r.length()) > max2) {
            r = r.substring(0, r.offsetByCodePoints(0, max2 - 1)).trim() + "…";
        }
        return r;
    }

    /**
     * Parte un texto (ya saneado) en líneas de como máximo {@code ancho} caracteres por palabras; si no cabe
     * en {@code maxLineas}, la última línea se trunca con «…».
     */
    public static List<String> lineasComentario(String texto, int ancho, int maxLineas) {
        List<String> lineas = new java.util.ArrayList<>();
        if (texto == null || texto.isBlank() || maxLineas < 1) {
            return lineas;
        }
        int w = Math.max(2, ancho);
        StringBuilder actual = new StringBuilder();
        for (String palabra : texto.trim().split(" +")) {
            String resto = palabra;
            while (!resto.isEmpty()) {
                int libre = actual.length() == 0 ? w : w - actual.length() - 1;
                if (resto.length() <= libre) {
                    if (actual.length() > 0) {
                        actual.append(' ');
                    }
                    actual.append(resto);
                    resto = "";
                } else if (actual.length() > 0) {
                    lineas.add(actual.toString());
                    actual.setLength(0);
                } else {
                    lineas.add(resto.substring(0, w));
                    resto = resto.substring(w);
                }
            }
        }
        if (actual.length() > 0) {
            lineas.add(actual.toString());
        }
        if (lineas.size() > maxLineas) {
            while (lineas.size() > maxLineas) {
                lineas.remove(lineas.size() - 1);
            }
            String ult = lineas.get(maxLineas - 1);
            if (ult.length() >= w) {
                ult = ult.substring(0, w - 1);
            }
            lineas.set(maxLineas - 1, ult.trim() + "…");
        }
        return lineas;
    }

    /** Escapa para un literal de cadena Java. */
    public static String literalJava(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20 || (c >= 0x7f && c < 0xa0)) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    /** Convierte texto libre en un identificador Java válido. */
    public static String aIdentificador(String s) {
        if (s == null || s.isEmpty()) {
            return "caso";
        }
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            sb.append(Character.isLetterOrDigit(c) && c < 128 || c == '_' || c == '$' ? c : '_');
        }
        String r = sb.toString().replaceAll("_+", "_");
        if (r.isEmpty() || Character.isDigit(r.charAt(0))) {
            r = "_" + r;
        }
        if (JavaReservadas.esReservada(r)) {
            r = r + "_";
        }
        return r;
    }

    /** Compara versiones tipo 5.17.0, 4.13-beta-1, 1.8. Devuelve negativo, 0 o positivo. */
    public static int compararVersiones(String a, String b) {
        if (a == null && b == null) {
            return 0;
        }
        if (a == null) {
            return -1;
        }
        if (b == null) {
            return 1;
        }
        String[] pa = a.split("[.\\-_]");
        String[] pb = b.split("[.\\-_]");
        int n = Math.max(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            int va = i < pa.length ? numero(pa[i]) : 0;
            int vb = i < pb.length ? numero(pb[i]) : 0;
            if (va != vb) {
                return Integer.compare(va, vb);
            }
        }
        return 0;
    }

    private static int numero(String s) {
        int i = 0;
        while (i < s.length() && Character.isDigit(s.charAt(i))) {
            i++;
        }
        if (i == 0) {
            return 0;
        }
        try {
            return Integer.parseInt(s.substring(0, Math.min(i, 9)));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public static boolean versionAlMenos(String version, String minima) {
        return version != null && compararVersiones(version, minima) >= 0;
    }

    /** Glob con '*' (sin cruzar '.') y '**' (cualquier cosa). */
    public static boolean coincideGlob(String patron, String valor) {
        StringBuilder re = new StringBuilder();
        for (int i = 0; i < patron.length(); i++) {
            char c = patron.charAt(i);
            if (c == '*') {
                if (i + 1 < patron.length() && patron.charAt(i + 1) == '*') {
                    re.append(".*");
                    i++;
                } else {
                    re.append("[^.]*");
                }
            } else if (c == '?') {
                re.append("[^.]");
            } else {
                re.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(re.toString()).matcher(valor).matches();
    }

    public static boolean coincideAlguno(List<String> patrones, String valor) {
        if (patrones == null) {
            return false;
        }
        for (String p : patrones) {
            if (p != null && coincideGlob(p.trim(), valor)) {
                return true;
            }
        }
        return false;
    }

    public static String nombreSimple(String fqnOTipo) {
        if (fqnOTipo == null) {
            return null;
        }
        String t = quitarGenericos(fqnOTipo).trim();
        while (t.endsWith("[]")) {
            t = t.substring(0, t.length() - 2);
        }
        if (t.endsWith("...")) {
            t = t.substring(0, t.length() - 3);
        }
        int i = t.lastIndexOf('.');
        return i >= 0 ? t.substring(i + 1) : t;
    }

    public static String quitarGenericos(String tipo) {
        if (tipo == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        int nivel = 0;
        for (char c : tipo.toCharArray()) {
            if (c == '<') {
                nivel++;
            } else if (c == '>') {
                nivel--;
            } else if (nivel == 0) {
                sb.append(c);
            }
        }
        return sb.toString().trim();
    }

    public static String minus(String s) {
        return s == null ? null : s.toLowerCase(Locale.ROOT);
    }

    public static String recortar(String s, int max) {
        if (s == null || s.length() <= max) {
            return s;
        }
        return s.substring(0, max) + "…";
    }
}
