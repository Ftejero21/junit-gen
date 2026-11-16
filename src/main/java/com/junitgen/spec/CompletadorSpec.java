package com.junitgen.spec;

import com.junitgen.util.Textos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Completa el spec compacto que escribe la IA: versión, parámetros de la clave {@code nombre(Tipos)},
 * {@code queComprueba} desde {@code descripcion}, {@code queHaceOriginal} heredado del caso anterior de la misma
 * función e {@code id} deducido. Determinista, sin E/S y sin depender del proyecto; un spec completo no cambia.
 */
public final class CompletadorSpec {

    /** Versión del spec cuando no se indica. */
    static final int VERSION = 1;
    /** Longitud máxima de la parte legible del id deducido (el sufijo hash se añade aparte). */
    static final int MAX_BASE_ID = 40;
    /** Caracteres hexadecimales del sufijo hash del id deducido. */
    static final int LONGITUD_HASH_ID = 6;
    private static final String ID_VACIO = "caso";
    private static final Pattern CLAVE_METODO = Pattern.compile("\\s*([A-Za-z_$][\\w$]*)\\s*\\((.*)\\)\\s*", Pattern.DOTALL);
    private static final Pattern CAMEL = Pattern.compile("(?<=[a-z0-9])(?=[A-Z])");
    private static final Pattern NO_ALFANUMERICO = Pattern.compile("[^a-z0-9]+");

    private CompletadorSpec() {
    }

    public static void completar(Spec s) {
        if (s == null) {
            return;
        }
        if (s.version == null) {
            s.version = VERSION;
        }
        if (s.servicios == null) {
            return;
        }
        for (ServicioSpec sv : s.servicios) {
            if (sv == null || sv.casos == null) {
                continue;
            }
            completarServicio(sv);
        }
    }

    private static void completarServicio(ServicioSpec sv) {
        // Primer queHaceOriginal por nombre de método con sus parámetros (clave "nombre" o "nombre(tipos)").
        Map<String, String> porFirma = new HashMap<>();
        Map<String, String> porNombre = new HashMap<>();
        for (CasoSpec c : sv.casos) {
            if (c == null) {
                continue;
            }
            separarClave(c);
            if (esVacio(c.queComprueba) && !esVacio(c.descripcion)) {
                c.queComprueba = c.descripcion;
            }
            if (c.metodo != null) {
                String firma = firma(c.metodo, c.parametros);
                if (esVacio(c.queHaceOriginal)) {
                    String heredado = porFirma.get(firma);
                    if (heredado == null) {
                        heredado = porNombre.get(c.metodo);
                    }
                    if (heredado != null) {
                        c.queHaceOriginal = heredado;
                    }
                } else {
                    porFirma.putIfAbsent(firma, c.queHaceOriginal);
                    porNombre.putIfAbsent(c.metodo, c.queHaceOriginal);
                }
            }
            if (esVacio(c.id) && !esVacio(c.metodo) && !esVacio(c.escenario)) {
                c.id = idDeducido(sv.clase, c.metodo, c.escenario);
            }
        }
    }

    /** {@code nombre(T1,T2)} → metodo = nombre y, si no hay {@code parametros}, los tipos (vacío para {@code ()}). */
    private static void separarClave(CasoSpec c) {
        if (c.metodo == null) {
            return;
        }
        Matcher m = CLAVE_METODO.matcher(c.metodo);
        if (!m.matches()) {
            return;
        }
        c.metodo = m.group(1);
        if (c.parametros == null) {
            c.parametros = tiposDeNivelCero(m.group(2));
        }
    }

    private static List<String> tiposDeNivelCero(String texto) {
        List<String> tipos = new ArrayList<>();
        int nivel = 0;
        StringBuilder actual = new StringBuilder();
        for (int i = 0; i < texto.length(); i++) {
            char ch = texto.charAt(i);
            if (ch == '<') {
                nivel++;
            } else if (ch == '>' && nivel > 0) {
                nivel--;
            }
            if (ch == ',' && nivel == 0) {
                anadirTipo(tipos, actual);
                actual.setLength(0);
            } else {
                actual.append(ch);
            }
        }
        anadirTipo(tipos, actual);
        return tipos;
    }

    private static void anadirTipo(List<String> tipos, StringBuilder actual) {
        String t = actual.toString().strip();
        if (!t.isEmpty()) {
            tipos.add(t);
        }
    }

    private static String firma(String metodo, List<String> parametros) {
        if (parametros == null) {
            return metodo;
        }
        List<String> simples = new ArrayList<>();
        for (String p : parametros) {
            simples.add(Textos.nombreSimple(p));
        }
        return metodo + "(" + String.join(",", simples) + ")";
    }

    private static boolean esVacio(String s) {
        return s == null || s.isBlank();
    }

    /** Id determinista: kebab(metodo)-kebab(escenario) recortado a 40 caracteres y sufijo hash de 6. */
    public static String idDeducido(String clase, String metodo, String escenario) {
        String base = kebab(metodo) + "-" + kebab(escenario);
        base = recortarGuiones(base);
        if (base.isEmpty()) {
            base = ID_VACIO;
        }
        if (base.length() > MAX_BASE_ID) {
            base = recortarGuiones(base.substring(0, MAX_BASE_ID));
            if (base.isEmpty()) {
                base = ID_VACIO;
            }
        }
        return base + "-" + Textos.sha256Hex(clase + "#" + metodo + "#" + escenario, LONGITUD_HASH_ID);
    }

    private static String kebab(String s) {
        if (s == null) {
            return "";
        }
        String separado = CAMEL.matcher(s).replaceAll("-").toLowerCase(Locale.ROOT);
        return recortarGuiones(NO_ALFANUMERICO.matcher(separado).replaceAll("-"));
    }

    private static String recortarGuiones(String s) {
        int ini = 0;
        int fin = s.length();
        while (ini < fin && s.charAt(ini) == '-') {
            ini++;
        }
        while (fin > ini && s.charAt(fin - 1) == '-') {
            fin--;
        }
        return s.substring(ini, fin);
    }
}
