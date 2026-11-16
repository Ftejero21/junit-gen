package com.junitgen.generacion;

import com.junitgen.duplicados.ExtractorHuellasTests;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Amplía un test ya generado insertando solo antes de los marcadores. El resto del archivo queda
 * idéntico byte a byte. Un caso existente con el mismo contenido se omite; con contenido distinto no
 * se reescribe salvo que esté en reemplazarCasos. Sin marcadores (editado a mano) no se toca.
 */
public final class FusionadorArchivo {

    private static final Pattern MONTAJE = Pattern.compile("//\\s*junit-gen:montaje=(\\w+)");
    private static final Pattern IMPORT = Pattern.compile("(?m)^\\s*import\\s+(static\\s+)?([\\w.$*]+)\\s*;");
    private static final Pattern FIXTURE = Pattern.compile("//\\s*junit-gen:fixture-inicio\\s+nombre=(\\S+)");
    private static final Pattern METODO = Pattern.compile("\\bvoid\\s+([A-Za-z_$][\\w$]*)\\s*\\(");

    public static class Resultado {
        public String texto;
        public boolean modificado;
        public String motivoNoTocado;
        public final List<String> anadidos = new ArrayList<>();
        public final List<String> omitidos = new ArrayList<>();
        public final List<String> noReescritos = new ArrayList<>();
        public final List<String> reemplazados = new ArrayList<>();
        public final List<String> eliminados = new ArrayList<>();
        public final List<String> fixturesAnadidas = new ArrayList<>();
        /** Imports sustituidos por nombre simple («`viejo` → `nuevo`»; solo con reemplazarComun). */
        public final List<String> importsSustituidos = new ArrayList<>();
        /** Fixtures existentes reescritas con el texto nuevo (solo con reemplazarComun). */
        public final List<String> fixturesReemplazadas = new ArrayList<>();
        public final Map<String, String> metodos = new LinkedHashMap<>();
        /** true si se ha reescrito el montaje (casos y fixtures conservados). */
        public boolean remontado;
    }

    private FusionadorArchivo() {
    }

    public static String hashMontaje(String texto) {
        Matcher m = MONTAJE.matcher(texto);
        return m.find() ? m.group(1) : null;
    }

    public static boolean tieneMarcadores(String texto) {
        return texto.contains(PlantillaTest.M_IMPORTS_FIN) && texto.contains(PlantillaTest.M_CASOS_FIN)
                && texto.contains(PlantillaTest.M_FIXTURES_FIN) && hashMontaje(texto) != null;
    }

    public static Set<String> metodosExistentes(String texto) {
        Set<String> r = new LinkedHashSet<>();
        Matcher m = METODO.matcher(texto);
        while (m.find()) {
            r.add(m.group(1));
        }
        return r;
    }

    /**
     * @param casos    bloques candidatos (ya renderizados)
     * @param eliminar ids a eliminar (ya confirmados por el usuario)
     */
    public static Resultado fusionar(String original, String montajeNuevo, List<PlantillaTest.Bloque> casos,
                                     List<PlantillaTest.Bloque> fixtures, Set<String> imports, Set<String> estaticos,
                                     Set<String> reemplazar, Set<String> eliminar) {
        return fusionar(original, montajeNuevo, casos, fixtures, imports, estaticos, reemplazar, eliminar, null, null);
    }

    /**
     * Como {@link #fusionar(String, String, List, List, Set, Set, Set, Set)}; si el hash de montaje cambia y se
     * dan {@code cabeceraClase} y {@code pieClase}, reescribe solo el montaje (conserva casos y fixtures).
     */
    public static Resultado fusionar(String original, String montajeNuevo, List<PlantillaTest.Bloque> casos,
                                     List<PlantillaTest.Bloque> fixtures, Set<String> imports, Set<String> estaticos,
                                     Set<String> reemplazar, Set<String> eliminar, String cabeceraClase,
                                     String pieClase) {
        return fusionar(original, montajeNuevo, casos, fixtures, imports, estaticos, reemplazar, eliminar, cabeceraClase, pieClase,
                false, List.of());
    }

    /**
     * Como {@link #fusionar(String, String, List, List, Set, Set, Set, Set, String, String)}; con
     * {@code reemplazarComun} (arreglar_caso con {@code servicio}) además reescribe las fixtures existentes cuyo
     * texto cambia y sustituye los imports no estáticos que chocan por nombre simple con uno de {@code imports}
     * o {@code importsSpec}. Sin él, idéntico.
     */
    public static Resultado fusionar(String original, String montajeNuevo, List<PlantillaTest.Bloque> casos,
                                     List<PlantillaTest.Bloque> fixtures, Set<String> imports, Set<String> estaticos,
                                     Set<String> reemplazar, Set<String> eliminar, String cabeceraClase,
                                     String pieClase, boolean reemplazarComun, Collection<String> importsSpec) {
        Resultado r = new Resultado();
        r.texto = original;
        if (!tieneMarcadores(original)) {
            r.motivoNoTocado = "el archivo no tiene los marcadores junit-gen (¿editado a mano?); no se modifica";
            return r;
        }
        String montajeActual = hashMontaje(original);
        String sep = original.contains("\r\n") ? "\r\n" : "\n";
        String texto = original;
        if (!montajeActual.equals(montajeNuevo)) {
            String remontado = cabeceraClase == null || pieClase == null ? null
                    : remontar(original, montajeActual, montajeNuevo, cabeceraClase, pieClase, sep);
            if (remontado == null) {
                r.motivoNoTocado = "el montaje del servicio ha cambiado (dependencias, constructor o construccion.argumentos); "
                        + "el archivo no se toca. Genera en otra carpeta de salida o revisa el cambio a mano";
                return r;
            }
            texto = remontado;
            r.remontado = true;
        }
        Map<String, ExtractorHuellasTests.Marcador> existentes = new LinkedHashMap<>();
        for (ExtractorHuellasTests.Marcador m : ExtractorHuellasTests.marcadores(original)) {
            existentes.put(m.id(), m);
        }
        // Eliminaciones
        for (String id : eliminar) {
            if (existentes.containsKey(id)) {
                int[] reg = region(texto, id);
                if (reg != null) {
                    int fin = reg[1];
                    if (texto.startsWith(sep, fin)) {
                        fin += sep.length();
                    }
                    texto = texto.substring(0, reg[0]) + texto.substring(fin);
                    r.eliminados.add(id);
                }
            }
        }
        // Casos
        StringBuilder nuevos = new StringBuilder();
        for (PlantillaTest.Bloque b : casos) {
            ExtractorHuellasTests.Marcador m = existentes.get(b.id());
            if (m != null && !r.eliminados.contains(b.id())) {
                if (b.contenido().equals(m.contenido()) && !(r.remontado && reemplazar.contains(b.id()))) {
                    r.omitidos.add(b.id());
                } else if (reemplazar.contains(b.id())) {
                    int[] reg = region(texto, b.id());
                    if (reg != null) {
                        texto = texto.substring(0, reg[0]) + conSep(b.texto(), sep) + texto.substring(reg[1]);
                        r.reemplazados.add(b.id());
                        r.metodos.put(b.id(), b.nombreMetodo());
                    }
                } else {
                    r.noReescritos.add(b.id());
                }
                continue;
            }
            nuevos.append(conSep(b.texto(), sep)).append(sep).append(sep);
            r.anadidos.add(b.id());
            r.metodos.put(b.id(), b.nombreMetodo());
        }
        if (nuevos.length() > 0) {
            texto = insertarAntes(texto, PlantillaTest.M_CASOS_FIN, nuevos.toString());
        }
        // Fixtures
        Set<String> fixturesExistentes = new LinkedHashSet<>();
        Matcher mf = FIXTURE.matcher(texto);
        while (mf.find()) {
            fixturesExistentes.add(mf.group(1));
        }
        StringBuilder nuevasF = new StringBuilder();
        for (PlantillaTest.Bloque f : fixtures) {
            if (!fixturesExistentes.contains(f.id())) {
                nuevasF.append(conSep(f.texto(), sep)).append(sep).append(sep);
                r.fixturesAnadidas.add(f.id());
            } else if (reemplazarComun) {
                int[] reg = regionFixture(texto, f.id());
                if (reg != null && !texto.substring(reg[0], reg[1]).replace("\r\n", "\n").equals(conSep(f.texto(), "\n"))) {
                    texto = texto.substring(0, reg[0]) + conSep(f.texto(), sep) + texto.substring(reg[1]);
                    r.fixturesReemplazadas.add(f.id());
                }
            }
        }
        if (nuevasF.length() > 0) {
            texto = insertarAntes(texto, PlantillaTest.M_FIXTURES_FIN, nuevasF.toString());
        }
        if (reemplazarComun) {
            LinkedHashSet<String> nuevosImports = new LinkedHashSet<>(imports);
            if (importsSpec != null) {
                for (String i : importsSpec) {
                    if (i != null) {
                        nuevosImports.add(i.trim());
                    }
                }
            }
            texto = sustituirImports(texto, nuevosImports, r.importsSustituidos);
        }
        // Imports (también con todos los casos omitidos: repara archivos a los que les falte alguno)
        Set<String> presentes = importsPresentes(texto);
        StringBuilder imps = new StringBuilder();
        if (!r.anadidos.isEmpty() || !r.reemplazados.isEmpty() || !r.fixturesAnadidas.isEmpty()
                || !r.fixturesReemplazadas.isEmpty() || !r.omitidos.isEmpty() || r.remontado) {
            imps.append(lineasImportsNuevos(presentes, imports, sep, null));
            for (String e : estaticos) {
                if (!presentes.contains("static " + e)) {
                    imps.append(PlantillaTest.lineaEstatico(e)).append(sep);
                }
            }
        }
        if (imps.length() > 0) {
            texto = insertarAntes(texto, PlantillaTest.M_IMPORTS_FIN, imps.toString());
        }
        if (!CopiaAmpliada.intacta(original, texto)) {
            System.err.println("junit-gen: la fusión habría cambiado líneas del test original de una copia ampliada; no se toca");
            Resultado nr = new Resultado();
            nr.texto = original;
            nr.motivoNoTocado = "la ampliación habría cambiado líneas de tu test original (copia ampliada); el archivo no se toca";
            return nr;
        }
        r.texto = texto;
        r.modificado = !texto.equals(original);
        return r;
    }

    /**
     * Añade a la zona de imports (antes de {@link PlantillaTest#M_IMPORTS_FIN}) los {@code fqns} no
     * estáticos que falten y no choquen por nombre simple con un import presente. Los añadidos se
     * acumulan en {@code anadidos}. Sin el marcador devuelve {@code texto} sin cambios.
     */
    public static String anadirImports(String texto, Collection<String> fqns, List<String> anadidos) {
        if (!texto.contains(PlantillaTest.M_IMPORTS_FIN)) {
            return texto;
        }
        String sep = texto.contains("\r\n") ? "\r\n" : "\n";
        String lineas = lineasImportsNuevos(importsPresentes(texto), fqns, sep, anadidos);
        return lineas.isEmpty() ? texto : insertarAntes(texto, PlantillaTest.M_IMPORTS_FIN, lineas);
    }

    private static final Pattern LINEA_IMPORT_SIMPLE = Pattern.compile("^\\s*import\\s+([\\w.$]+)\\s*;\\s*$");

    /**
     * Sustituye en la cabecera cada import no estático y sin comodín que tenga el mismo nombre simple que uno de
     * {@code fqns} (no estático, sin comodín) y otro FQN; si el nuevo ya está importado, borra la línea vieja.
     */
    private static String sustituirImports(String texto, Collection<String> fqns, List<String> cambios) {
        int marca = texto.indexOf(PlantillaTest.M_IMPORTS_FIN);
        if (marca < 0) {
            return texto;
        }
        int inicioMarca = texto.lastIndexOf('\n', marca) + 1;
        String cabecera = texto.substring(0, inicioMarca);
        List<String> lineas = new ArrayList<>(List.of(cabecera.split("\n", -1)));
        // En una copia ampliada solo se tocan las líneas del bloque junit-gen (tras imports-inicio), nunca las del usuario.
        int desde = 0;
        if (cabecera.contains(CopiaAmpliada.M_IMPORTS_INICIO)) {
            for (int k = 0; k < lineas.size(); k++) {
                if (sinCr(lineas.get(k)).strip().equals(CopiaAmpliada.M_IMPORTS_INICIO)) {
                    desde = k + 1;
                }
            }
            if (desde == 0) {
                return texto;
            }
        }
        for (String fqn : fqns) {
            if (fqn == null || fqn.isEmpty() || fqn.startsWith("static ") || fqn.endsWith(".*") || fqn.indexOf('.') < 0) {
                continue;
            }
            String simple = fqn.substring(fqn.lastIndexOf('.') + 1);
            boolean yaEsta = false;
            for (String l : lineas) {
                Matcher m = LINEA_IMPORT_SIMPLE.matcher(sinCr(l));
                if (m.matches() && m.group(1).equals(fqn)) {
                    yaEsta = true;
                    break;
                }
            }
            for (int k = desde; k < lineas.size(); k++) {
                String l = lineas.get(k);
                Matcher m = LINEA_IMPORT_SIMPLE.matcher(sinCr(l));
                if (!m.matches()) {
                    continue;
                }
                String viejo = m.group(1);
                if (viejo.equals(fqn) || !viejo.endsWith("." + simple)) {
                    continue;
                }
                if (yaEsta) {
                    lineas.remove(k);
                    k--;
                } else {
                    String cr = l.endsWith("\r") ? "\r" : "";
                    String linea = sinCr(l);
                    lineas.set(k, linea.substring(0, linea.length() - linea.stripLeading().length()) + PlantillaTest.lineaImport(fqn) + cr);
                    yaEsta = true;
                }
                cambios.add("`" + viejo + "` → `" + fqn + "`");
            }
        }
        String nueva = String.join("\n", lineas);
        return nueva.equals(cabecera) ? texto : nueva + texto.substring(inicioMarca);
    }

    private static String sinCr(String s) {
        return s.endsWith("\r") ? s.substring(0, s.length() - 1) : s;
    }

    /** Imports de la cabecera anterior a M_IMPORTS_FIN; los estáticos con el prefijo "static ". */
    private static Set<String> importsPresentes(String texto) {
        String cabecera = texto.substring(0, texto.indexOf(PlantillaTest.M_IMPORTS_FIN));
        Set<String> presentes = new LinkedHashSet<>();
        Matcher mi = IMPORT.matcher(cabecera);
        while (mi.find()) {
            presentes.add((mi.group(1) != null ? "static " : "") + mi.group(2));
        }
        return presentes;
    }

    /**
     * Líneas de import para los FQN no estáticos que faltan: se salta el ya presente y el que choca por
     * nombre simple con un import no estático y no comodín. Actualiza {@code presentes}.
     */
    private static String lineasImportsNuevos(Set<String> presentes, Collection<String> fqns, String sep,
                                              List<String> anadidos) {
        StringBuilder sb = new StringBuilder();
        for (String fqn : fqns) {
            if (fqn == null || fqn.isEmpty() || fqn.startsWith("static ") || presentes.contains(fqn)) {
                continue;
            }
            Set<String> noEstaticos = new LinkedHashSet<>();
            for (String p : presentes) {
                if (!p.startsWith("static ")) {
                    noEstaticos.add(p);
                }
            }
            if (!fqn.endsWith(".*") && PlantillaTest.chocaNombreSimple(noEstaticos, fqn)) {
                continue;
            }
            presentes.add(fqn);
            sb.append(PlantillaTest.lineaImport(fqn)).append(sep);
            if (anadidos != null) {
                anadidos.add(fqn);
            }
        }
        return sb.toString();
    }

    /**
     * Sustituye la zona entre el fin de la línea de imports y el primer caso (o casos-fin), y la cola tras
     * fixtures-fin, por la cabecera y el pie nuevos; actualiza el hash de la línea 1. Null si las anclas no
     * están en orden.
     */
    private static String remontar(String original, String hashActual, String hashNuevo, String cabeceraClase,
                                   String pieClase, String sep) {
        int finImports = finLinea(original, original.indexOf(PlantillaTest.M_IMPORTS_FIN));
        Matcher mc = Pattern.compile("(?m)^[ \\t]*//\\s*junit-gen:caso-inicio\\b").matcher(original);
        int marcaCasos = mc.find(Math.max(finImports, 0)) ? mc.start() : -1;
        if (marcaCasos < 0) {
            int i = original.indexOf(PlantillaTest.M_CASOS_FIN, Math.max(finImports, 0));
            marcaCasos = i < 0 ? -1 : original.lastIndexOf('\n', i) + 1;
        }
        int marcaFixtures = original.indexOf(PlantillaTest.M_FIXTURES_FIN, Math.max(marcaCasos, 0));
        int finFixtures = marcaFixtures < 0 ? -1 : finLinea(original, marcaFixtures);
        if (finImports < 0 || marcaCasos < finImports || finFixtures < marcaCasos) {
            return null;
        }
        String texto = original.substring(0, finImports) + sep + conSepCompleto(cabeceraClase, sep)
                + original.substring(marcaCasos, finFixtures) + conSepCompleto(pieClase, sep);
        int finPrimera = texto.indexOf('\n');
        String primera = finPrimera < 0 ? texto : texto.substring(0, finPrimera);
        if (!primera.contains(hashActual)) {
            return null;
        }
        return primera.replace(hashActual, hashNuevo) + (finPrimera < 0 ? "" : texto.substring(finPrimera));
    }

    /** Índice tras el separador de la línea que contiene {@code i}; -1 si {@code i < 0}. */
    private static int finLinea(String texto, int i) {
        if (i < 0) {
            return -1;
        }
        int n = texto.indexOf('\n', i);
        return n < 0 ? texto.length() : n + 1;
    }

    private static String conSepCompleto(String texto, String sep) {
        return "\n".equals(sep) ? texto : texto.replace("\n", sep);
    }

    private static String conSep(String bloque, String sep) {
        String t = bloque.endsWith("\n") ? bloque.substring(0, bloque.length() - 1) : bloque;
        return t.replace("\n", sep);
    }

    /** Inserta {@code texto} al principio de la línea que contiene el marcador. */
    static String insertarAntes(String original, String marcador, String texto) {
        int i = original.indexOf(marcador);
        int inicioLinea = original.lastIndexOf('\n', i) + 1;
        return original.substring(0, inicioLinea) + texto + original.substring(inicioLinea);
    }

    /**
     * Aparta el caso {@code id}: deja intactas sus líneas caso-inicio y caso-fin, inserta tras caso-inicio
     * {@code <sangría>// junit-gen:apartado motivo=<motivo>} y comenta cada línea intermedia
     * ({@code <sangría>// <línea>}; en blanco → {@code <sangría>//}). Nunca borra nada. Sin región o ya
     * apartado devuelve {@code texto} sin cambios; el resto del archivo queda igual.
     */
    public static String apartar(String texto, String id, String motivo) {
        if (texto == null || id == null) {
            return texto;
        }
        int[] reg = region(texto, id);
        if (reg == null) {
            return texto;
        }
        String bloque = texto.substring(reg[0], reg[1]);
        if (bloque.contains(ExtractorHuellasTests.MARCA_APARTADO)) {
            return texto;
        }
        String[] lineas = bloque.split("\r?\n", -1);
        if (lineas.length < 2) {
            return texto;
        }
        String sep = texto.contains("\r\n") ? "\r\n" : "\n";
        String primera = lineas[0];
        String sangria = primera.substring(0, primera.length() - primera.stripLeading().length());
        String m = motivo == null ? "" : motivo.replaceAll("\\s+", "");
        StringBuilder sb = new StringBuilder(primera).append(sep)
                .append(sangria).append("// ").append(ExtractorHuellasTests.MARCA_APARTADO).append(" motivo=").append(m);
        for (int i = 1; i < lineas.length - 1; i++) {
            sb.append(sep).append(sangria).append(lineas[i].isBlank() ? "//" : "// " + lineas[i]);
        }
        sb.append(sep).append(lineas[lineas.length - 1]);
        String nuevo = texto.substring(0, reg[0]) + sb + texto.substring(reg[1]);
        if (!CopiaAmpliada.intacta(texto, nuevo)) {
            System.err.println("junit-gen: apartar el caso " + id + " habría cambiado líneas del test original; no se toca");
            return texto;
        }
        return nuevo;
    }

    /** [inicio de la línea de fixture-inicio, fin de la línea de fixture-fin (sin separador)] de la fixture {@code nombre}. */
    static int[] regionFixture(String texto, String nombre) {
        Matcher mi = Pattern.compile("(?m)^[ \\t]*//\\s*junit-gen:fixture-inicio\\s+nombre=" + Pattern.quote(nombre) + "[ \\t]*\\r?$")
                .matcher(texto);
        if (!mi.find()) {
            return null;
        }
        Matcher mf = Pattern.compile("(?m)^[ \\t]*//\\s*junit-gen:fixture-fin\\s+nombre=" + Pattern.quote(nombre) + "[ \\t]*\\r?$")
                .matcher(texto);
        if (!mf.find(mi.start())) {
            return null;
        }
        int fin = mf.end();
        if (fin > 0 && texto.charAt(fin - 1) == '\r') {
            fin--;
        }
        return new int[]{mi.start(), fin};
    }

    /** [inicio de la línea de caso-inicio, fin de la línea de caso-fin (sin separador)]. */
    static int[] region(String texto, String id) {
        Matcher mi = Pattern.compile("(?m)^[ \\t]*//\\s*junit-gen:caso-inicio\\s+id=" + Pattern.quote(id) + "(\\s|$)").matcher(texto);
        if (!mi.find()) {
            return null;
        }
        Matcher mf = Pattern.compile("(?m)^[ \\t]*//\\s*junit-gen:caso-fin\\s+id=" + Pattern.quote(id) + "[ \\t]*\\r?$").matcher(texto);
        if (!mf.find(mi.start())) {
            return null;
        }
        int fin = mf.end();
        if (fin > 0 && texto.charAt(fin - 1) == '\r') {
            fin--;
        }
        return new int[]{mi.start(), fin};
    }
}
