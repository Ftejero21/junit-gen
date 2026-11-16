package com.junitgen.generacion;

import com.junitgen.Version;
import com.junitgen.duplicados.ExtractorHuellasTests;
import com.junitgen.escaneo.ModuloEscaneado;
import com.junitgen.util.Textos;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Copia ampliada de un test que ya existe en el proyecto (solo texto, sin E/S): el archivo del usuario íntegro
 * (ni una línea cambiada ni reordenada) más dos bloques insertados por junit-gen: uno de imports tras la última
 * línea de import (o de package) y otro antes de la llave de cierre de la clase con los casos y las fixtures.
 * El «núcleo» es el texto sin esos bloques: en una copia correcta coincide byte a byte con el original.
 */
public final class CopiaAmpliada {

    public static final String M_IMPORTS_INICIO = "// junit-gen:imports-inicio";
    public static final String M_INICIO = "// junit-gen:ampliacion-inicio";
    /** Hash de montaje fijo de las copias ampliadas: el montaje es el del usuario y nunca se remonta. */
    public static final String HASH_MONTAJE = "ampliada";
    /** Marcador de cabecera del GeneradoTest con el motivo por el que no se mezcló con el test existente. */
    public static final String M_SIN_MEZCLA = "// junit-gen:sin-mezcla";
    /** Tamaño máximo (caracteres) de un test existente con el que se intenta mezclar. */
    public static final int MAX_CARACTERES = 400_000;

    private static final String IND = "    ";
    private static final Pattern ORIGINAL = Pattern.compile("junit-gen:ampliacion-inicio\\s+original=([0-9a-f]{64})");

    /** Copia nueva o rehecha que necesita un veredicto dinámico (compilar y ejecutar) antes de escribirse. */
    public record Pendiente(String clave, ModuloEscaneado modulo, String fqnTest, String nucleo, String texto) {
    }

    /** Resultado de comprobar una copia: {@code originalesPasan} = tests del usuario que pasan solos. */
    public record Veredicto(boolean ok, String motivo, int originalesPasan) {
    }

    private CopiaAmpliada() {
    }

    /** true si el texto contiene el bloque de ampliación (una línea que empieza por {@link #M_INICIO}). */
    public static boolean es(String texto) {
        if (texto == null || !texto.contains(M_INICIO)) {
            return false;
        }
        for (String l : lineas(texto)) {
            if (l.strip().startsWith(M_INICIO)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Texto sin las líneas {@code [imports-inicio … imports-fin]} ni {@code [ampliacion-inicio … fixtures-fin]};
     * null si algún bloque empieza y no termina. Sin marcadores devuelve el mismo texto.
     */
    public static String nucleo(String texto) {
        if (texto == null) {
            return null;
        }
        if (!texto.contains(M_IMPORTS_INICIO) && !texto.contains(M_INICIO)) {
            return texto;
        }
        StringBuilder sb = new StringBuilder(texto.length());
        int estado = 0;
        for (String l : lineas(texto)) {
            String t = l.strip();
            if (estado == 0) {
                if (t.equals(M_IMPORTS_INICIO)) {
                    estado = 1;
                } else if (esInicioAmpliacion(t)) {
                    estado = 2;
                } else {
                    sb.append(l);
                }
            } else if (estado == 1) {
                if (t.equals(PlantillaTest.M_IMPORTS_FIN)) {
                    estado = 0;
                }
            } else if (t.equals(PlantillaTest.M_FIXTURES_FIN)) {
                estado = 0;
            }
        }
        return estado == 0 ? sb.toString() : null;
    }

    public static String huella(String nucleo) {
        return Textos.sha256Hex(nucleo, 64);
    }

    /** Huella del original anotada en {@code ampliacion-inicio original=…}; null si no hay. */
    public static String huellaAnotada(String copia) {
        if (copia == null) {
            return null;
        }
        Matcher m = ORIGINAL.matcher(copia);
        return m.find() ? m.group(1) : null;
    }

    /**
     * Copia ampliada vacía de {@code nucleo}: bloque de clase justo antes de la línea {@code lineaCierre} (1-based) y
     * bloque de imports tras la línea {@code lineaTrasImports} (0 = al principio). Null si el resultado no conserva
     * el núcleo.
     */
    public static String crear(String nucleo, int lineaTrasImports, int lineaCierre) {
        if (nucleo == null) {
            return null;
        }
        String sep = separador(nucleo);
        List<String> clase = new ArrayList<>();
        clase.add(IND + M_INICIO + " original=" + huella(nucleo));
        clase.add(IND + "// Todo lo que está fuera de los bloques junit-gen es tu archivo original sin cambios (junit-gen "
                + Version.version() + ").");
        clase.add(IND + "// Conserva los comentarios \"junit-gen:\": son marcadores para poder ampliar el archivo sin reescribirlo.");
        clase.add(IND + PlantillaTest.M_MONTAJE + HASH_MONTAJE);
        clase.add("");
        clase.add(IND + PlantillaTest.M_CASOS_FIN);
        clase.add("");
        clase.add(IND + PlantillaTest.M_FIXTURES_FIN);
        List<String> imports = List.of(M_IMPORTS_INICIO, PlantillaTest.M_IMPORTS_FIN);
        return insertar(nucleo, sep, imports, clase, lineaTrasImports, lineaCierre);
    }

    /**
     * Pasa los bloques junit-gen de {@code copia} sobre {@code nucleoNuevo} (actualiza {@code original=}). Null si
     * la copia no tiene bloques completos o el resultado no conserva el núcleo nuevo.
     */
    public static String trasplantar(String nucleoNuevo, String copia, int lineaTrasImports, int lineaCierre) {
        if (nucleoNuevo == null || copia == null || nucleo(copia) == null) {
            return null;
        }
        List<String> imports = new ArrayList<>();
        List<String> clase = new ArrayList<>();
        int estado = 0;
        for (String l : lineas(copia)) {
            String t = l.strip();
            String sinFin = sinTerminador(l);
            if (estado == 0) {
                if (t.equals(M_IMPORTS_INICIO)) {
                    estado = 1;
                    imports.add(sinFin);
                } else if (esInicioAmpliacion(t)) {
                    estado = 2;
                    String sangria = sinFin.substring(0, sinFin.length() - sinFin.stripLeading().length());
                    clase.add(sangria + M_INICIO + " original=" + huella(nucleoNuevo));
                }
            } else if (estado == 1) {
                imports.add(sinFin);
                if (t.equals(PlantillaTest.M_IMPORTS_FIN)) {
                    estado = 0;
                }
            } else {
                clase.add(sinFin);
                if (t.equals(PlantillaTest.M_FIXTURES_FIN)) {
                    estado = 0;
                }
            }
        }
        if (clase.isEmpty()) {
            return null;
        }
        if (imports.isEmpty()) {
            imports = List.of(M_IMPORTS_INICIO, PlantillaTest.M_IMPORTS_FIN);
        }
        return insertar(nucleoNuevo, separador(nucleoNuevo), imports, clase, lineaTrasImports, lineaCierre);
    }

    /** true si {@code antes} no es una copia ampliada o si {@code despues} conserva su núcleo. */
    public static boolean intacta(String antes, String despues) {
        if (antes == null || !es(antes)) {
            return true;
        }
        String a = nucleo(antes);
        return a != null && despues != null && a.equals(nucleo(despues));
    }

    /** Ids de los casos marcados con {@code junit-gen:caso-inicio} en el texto. */
    public static Set<String> idsEnBloques(String texto) {
        Set<String> r = new LinkedHashSet<>();
        if (texto == null) {
            return r;
        }
        for (ExtractorHuellasTests.Marcador m : ExtractorHuellasTests.marcadores(texto)) {
            if (m.id() != null) {
                r.add(m.id());
            }
        }
        return r;
    }

    /** Separador de línea del texto ({@code \r\n} si aparece alguno; si no, {@code \n}). */
    static String separador(String texto) {
        return texto.contains("\r\n") ? "\r\n" : "\n";
    }

    /** Líneas del texto, cada una con su terminador ({@code \n} o {@code \r\n}); la última puede no tenerlo. */
    static List<String> lineas(String texto) {
        List<String> r = new ArrayList<>();
        int i = 0;
        while (i < texto.length()) {
            int n = texto.indexOf('\n', i);
            if (n < 0) {
                r.add(texto.substring(i));
                break;
            }
            r.add(texto.substring(i, n + 1));
            i = n + 1;
        }
        return r;
    }

    private static String sinTerminador(String linea) {
        String l = linea.endsWith("\n") ? linea.substring(0, linea.length() - 1) : linea;
        return l.endsWith("\r") ? l.substring(0, l.length() - 1) : l;
    }

    private static boolean esInicioAmpliacion(String lineaSinEspacios) {
        return lineaSinEspacios.equals(M_INICIO) || lineaSinEspacios.startsWith(M_INICIO + " ");
    }

    private static String insertar(String nucleo, String sep, List<String> imports, List<String> clase, int lineaTrasImports,
                                   int lineaCierre) {
        List<String> ls = new ArrayList<>(lineas(nucleo));
        if (lineaCierre < 1 || lineaCierre > ls.size() || lineaTrasImports < 0 || lineaTrasImports >= lineaCierre) {
            return null;
        }
        // La línea tras la que van los imports debe terminar en salto de línea (no es la última).
        if (lineaTrasImports > 0 && !ls.get(lineaTrasImports - 1).endsWith("\n")) {
            return null;
        }
        StringBuilder bc = new StringBuilder();
        for (String l : clase) {
            bc.append(l).append(sep);
        }
        ls.add(lineaCierre - 1, bc.toString());
        StringBuilder bi = new StringBuilder();
        for (String l : imports) {
            bi.append(l).append(sep);
        }
        ls.add(lineaTrasImports, bi.toString());
        String r = String.join("", ls);
        return nucleo.equals(nucleo(r)) ? r : null;
    }
}
