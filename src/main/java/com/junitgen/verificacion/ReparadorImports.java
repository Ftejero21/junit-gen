package com.junitgen.verificacion;

import com.junitgen.escaneo.IndiceTipos;
import com.junitgen.generacion.CatalogoClases;
import com.junitgen.generacion.FusionadorArchivo;
import com.junitgen.generacion.PlantillaTest;
import com.junitgen.generacion.ResolutorImports;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Repara los imports de un test generado que no compila (solo texto, sin E/S): sustituye o quita las líneas
 * {@code import} de la cabecera en las que javac da error (sin interpretar el mensaje, que puede venir
 * traducido) y añade los imports de las clases que javac no encontró y se resuelven sin ambigüedad.
 */
final class ReparadorImports {

    private static final Pattern LINEA_IMPORT = Pattern.compile("^\\s*import\\s+(static\\s+)?([\\w.$]+?)(\\.\\*)?\\s*;\\s*$");
    private static final String JAVA_LANG = "java.lang";
    private static final String COMODIN = ".*";
    private static final String SEPARADOR_CANDIDATOS = " | ";

    /** Texto reparado y frases de los cambios hechos (vacía = sin cambios). */
    record Resultado(String texto, List<String> cambios) {
    }

    private ReparadorImports() {
    }

    static Resultado reparar(String texto, List<CompiladorTemporal.Diagnostico> diags, Set<String> clasesNoEncontradas, String paquete,
                             CatalogoClases catalogo, ResolutorImports ri, List<String> importsSpec, IndiceTipos indice,
                             Set<String> excluidos) {
        List<String> cambios = new ArrayList<>();
        if (texto == null || !texto.contains(PlantillaTest.M_IMPORTS_FIN)) {
            return new Resultado(texto, cambios);
        }
        String paq = paquete == null ? "" : paquete;
        List<String> spec = importsSpec == null ? List.of() : importsSpec;
        Set<String> tratados = new LinkedHashSet<>();
        String actual = texto;
        if (catalogo != null && diags != null && !diags.isEmpty()) {
            actual = repararLineas(actual, diags, paq, catalogo, ri, spec, excluidos, tratados, cambios);
        }
        if (clasesNoEncontradas != null && !clasesNoEncontradas.isEmpty()) {
            Set<String> fqns = new LinkedHashSet<>();
            for (String x : clasesNoEncontradas) {
                if (tratados.contains(x)) {
                    continue;
                }
                String fqn = resolver(x, ri, indice);
                if (fqn != null && fqn.isEmpty()) {
                    continue;
                }
                if (catalogo != null) {
                    if (fqn == null || fqn.endsWith(COMODIN) || !catalogo.existe(fqn) || excluidos.contains(fqn)) {
                        fqn = catalogo.elegir(x, List.of(), excluidos, null);
                    }
                }
                if (fqn == null || fqn.isEmpty() || fqn.endsWith(COMODIN)) {
                    continue;
                }
                int punto = fqn.lastIndexOf('.');
                if (punto <= 0) {
                    continue;
                }
                String pkg = fqn.substring(0, punto);
                if (pkg.equals(paq) || pkg.equals(JAVA_LANG)) {
                    continue;
                }
                fqns.add(fqn);
            }
            if (!fqns.isEmpty()) {
                List<String> anadidos = new ArrayList<>();
                actual = FusionadorArchivo.anadirImports(actual, fqns, anadidos);
                for (String a : anadidos) {
                    cambios.add("se añadió `" + a + "`");
                }
            }
        }
        return new Resultado(actual, cambios);
    }

    private static String resolver(String x, ResolutorImports ri, IndiceTipos indice) {
        if (ri != null) {
            return ri.resolver(x);
        }
        String fqn = indice == null ? null : indice.resolverFqn(x, null);
        return fqn != null ? fqn : ResolutorImports.JDK.get(x);
    }

    /** Sustituye o quita las líneas import de la cabecera con errores de javac. */
    private static String repararLineas(String texto, List<CompiladorTemporal.Diagnostico> diags, String paquete, CatalogoClases catalogo,
                                        ResolutorImports ri, List<String> spec, Set<String> excluidos, Set<String> tratados,
                                        List<String> cambios) {
        String[] lineas = texto.split("\n", -1);
        int fin = -1;
        for (int i = 0; i < lineas.length; i++) {
            if (lineas[i].contains(PlantillaTest.M_IMPORTS_FIN)) {
                fin = i;
                break;
            }
        }
        if (fin < 0) {
            return texto;
        }
        Set<Integer> rotas = new TreeSet<>();
        for (CompiladorTemporal.Diagnostico d : diags) {
            int idx = d.linea() - 1;
            if (idx >= 0 && idx < fin && LINEA_IMPORT.matcher(sinCr(lineas[idx])).matches()) {
                rotas.add(idx);
            }
        }
        if (rotas.isEmpty()) {
            return texto;
        }
        boolean[] quitar = new boolean[lineas.length];
        for (int idx : rotas) {
            String linea = sinCr(lineas[idx]);
            String cr = lineas[idx].endsWith("\r") ? "\r" : "";
            Matcher m = LINEA_IMPORT.matcher(linea);
            if (!m.matches()) {
                continue;
            }
            boolean estatico = m.group(1) != null;
            String nombre = m.group(2);
            boolean comodin = m.group(3) != null;
            if (!estatico && comodin) {
                quitar[idx] = true;
                cambios.add("se quitó `" + nombre + COMODIN + "` (el paquete no existe)");
                continue;
            }
            if (!estatico) {
                String simple = simple(nombre);
                excluidos.add(nombre);
                tratados.add(simple);
                String elegido = catalogo.elegir(simple, preferidos(simple, spec, ri), excluidos, nombre);
                if (elegido == null) {
                    quitar[idx] = true;
                    cambios.add("se quitó `" + nombre + "` (no existe" + candidatos(catalogo, simple, excluidos) + ")");
                    continue;
                }
                String pkg = paqueteDe(elegido);
                if (pkg.equals(paquete) || pkg.equals(JAVA_LANG) || importado(lineas, quitar, fin, idx, elegido)) {
                    quitar[idx] = true;
                } else {
                    lineas[idx] = sangria(linea) + PlantillaTest.lineaImport(elegido) + cr;
                }
                cambios.add("`" + nombre + "` → `" + elegido + "`");
                continue;
            }
            String clase = comodin ? nombre : paqueteDe(nombre);
            String miembro = comodin ? "*" : simple(nombre);
            String original = "static " + nombre + (comodin ? COMODIN : "");
            String elegido = null;
            if (!clase.isEmpty() && !catalogo.existe(clase)) {
                String simple = simple(clase);
                excluidos.add(clase);
                elegido = catalogo.elegir(simple, preferidos(simple, spec, ri), excluidos, clase);
            }
            if (elegido != null) {
                lineas[idx] = sangria(linea) + PlantillaTest.lineaEstatico(elegido + "." + miembro) + cr;
                cambios.add("`" + original + "` → `static " + elegido + "." + miembro + "`");
            } else {
                quitar[idx] = true;
                cambios.add("se quitó `" + original + "` (no compila)");
            }
        }
        StringBuilder sb = new StringBuilder();
        boolean primera = true;
        for (int i = 0; i < lineas.length; i++) {
            if (quitar[i]) {
                continue;
            }
            if (!primera) {
                sb.append('\n');
            }
            sb.append(lineas[i]);
            primera = false;
        }
        return sb.toString();
    }

    /** Imports del spec no estáticos que acaban en {@code .simple} y el que da el resolutor. */
    private static List<String> preferidos(String simple, List<String> spec, ResolutorImports ri) {
        List<String> r = new ArrayList<>();
        for (String i : spec) {
            if (i == null) {
                continue;
            }
            String t = i.trim();
            if (!t.startsWith("static ") && t.endsWith("." + simple) && !r.contains(t)) {
                r.add(t);
            }
        }
        if (ri != null) {
            String res = ri.resolver(simple);
            if (res != null && !res.isEmpty() && !res.endsWith(COMODIN) && !r.contains(res)) {
                r.add(res);
            }
        }
        return r;
    }

    /** true si otra línea import no estática de la cabecera (que se conserva) ya importa {@code fqn}. */
    private static boolean importado(String[] lineas, boolean[] quitar, int fin, int idx, String fqn) {
        for (int i = 0; i < fin; i++) {
            if (i == idx || quitar[i]) {
                continue;
            }
            Matcher m = LINEA_IMPORT.matcher(sinCr(lineas[i]));
            if (m.matches() && m.group(1) == null && m.group(3) == null && fqn.equals(m.group(2))) {
                return true;
            }
        }
        return false;
    }

    private static String candidatos(CatalogoClases catalogo, String simple, Set<String> excluidos) {
        List<String> r = new ArrayList<>();
        for (String c : catalogo.candidatos(simple)) {
            if (!excluidos.contains(c) && r.size() < CatalogoClases.MAX_CANDIDATOS) {
                r.add("`" + c + "`");
            }
        }
        return r.isEmpty() ? "" : "; candidatos: " + String.join(SEPARADOR_CANDIDATOS, r);
    }

    private static String sinCr(String s) {
        return s.endsWith("\r") ? s.substring(0, s.length() - 1) : s;
    }

    private static String sangria(String linea) {
        return linea.substring(0, linea.length() - linea.stripLeading().length());
    }

    private static String simple(String fqn) {
        int p = fqn.lastIndexOf('.');
        return p < 0 ? fqn : fqn.substring(p + 1);
    }

    private static String paqueteDe(String fqn) {
        int p = fqn.lastIndexOf('.');
        return p < 0 ? "" : fqn.substring(0, p);
    }
}
