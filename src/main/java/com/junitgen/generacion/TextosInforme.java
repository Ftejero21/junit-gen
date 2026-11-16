package com.junitgen.generacion;

import com.junitgen.util.Textos;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Textos de informe.md en lenguaje llano (estados, veredictos, símbolos, glosario) y utilidades para volcar
 * datos de forma segura. Único sitio con las etiquetas humanas del informe; las respuestas MCP no las usan.
 */
public final class TextosInforme {

    public static final String SIM_PASA = "✅";
    public static final String SIM_FALLA = "❌";
    public static final String SIM_MAL_PREPARADO = "🔧";
    public static final String SIM_NO_COMPILA = "⛔";
    public static final String SIM_SIN_RESULTADO = "❓";

    public static final String PASA = SIM_PASA + " Pasa";
    public static final String FALLA = SIM_FALLA + " Falla: el resultado no es el esperado";
    public static final String FALLA_LARGO = FALLA + " (puede ser un fallo real del código)";
    public static final String MAL_PREPARADO = SIM_MAL_PREPARADO + " El test estaba mal preparado";
    public static final String NO_COMPILA = SIM_NO_COMPILA + " No compila";
    public static final String SIN_RESULTADO = SIM_SIN_RESULTADO + " Sin resultado";

    public static final String VEREDICTO_ERROR_IA = "La IA se equivocó al preparar el test";
    public static final String VEREDICTO_BUG = "Posible fallo en el código del servicio";
    public static final String VEREDICTO_DUDOSO = "No está claro";

    public static final String TITULO_GLOSARIO = "### Cómo leer este informe";
    public static final String SIN_DATO = "—";
    public static final String RESUMEN = "**En resumen:** ";

    private TextosInforme() {
    }

    /** Estado de un caso o de un resultado en lenguaje llano (con símbolo cuando procede). */
    public static String estado(String estado) {
        if (estado == null || estado.isBlank()) {
            return SIN_DATO;
        }
        return switch (estado) {
            case "pasa" -> PASA;
            case "fallo_asercion" -> FALLA;
            case "error_montaje" -> MAL_PREPARADO;
            case "no_compila" -> NO_COMPILA;
            case "desconocido" -> SIN_RESULTADO;
            case "posible_bug" -> SIM_FALLA + " Falla: se mantiene como aviso de un posible fallo en el código";
            case "dudoso" -> SIM_SIN_RESULTADO + " No está claro si falla el test o el código";
            case "montaje_fallido" -> SIM_MAL_PREPARADO + " Apartado: no se pudo arreglar; queda comentado en el test";
            case "generado" -> "Creado, todavía sin probar";
            case "eliminado" -> "Eliminado";
            default -> texto(estado, 80);
        };
    }

    /** Como {@link #estado(String)}, con la versión larga del fallo de aserción. */
    public static String estadoLargo(String estado) {
        return "fallo_asercion".equals(estado) ? FALLA_LARGO : estado(estado);
    }

    public static String veredicto(String veredicto) {
        if (veredicto == null) {
            return SIN_DATO;
        }
        return switch (veredicto) {
            case "error_ia" -> VEREDICTO_ERROR_IA;
            case "bug_servicio" -> VEREDICTO_BUG;
            case "dudoso" -> VEREDICTO_DUDOSO;
            default -> texto(veredicto, 80);
        };
    }

    public static String origenSeleccion(String origen) {
        if (origen == null) {
            return SIN_DATO;
        }
        return switch (origen) {
            case "ia" -> "la IA";
            case "usuario" -> "el usuario";
            case "java" -> "junit-gen (automático)";
            case "todas" -> "se incluyen todas";
            default -> texto(origen, 80);
        };
    }

    /** Nombre simple de la clase, o el FQN si otro FQN distinto de {@code todas} comparte nombre simple. */
    public static String claseCorta(String fqn, Collection<String> todas) {
        if (fqn == null || fqn.isBlank()) {
            return SIN_DATO;
        }
        String simple = Textos.nombreSimple(fqn);
        if (todas != null) {
            for (String otra : todas) {
                if (otra != null && !otra.equals(fqn) && simple.equals(Textos.nombreSimple(otra))) {
                    return fqn;
                }
            }
        }
        return simple;
    }

    /** Ruta relativa a {@code base} con {@code /} si cuelga de ella; si no, la ruta tal cual. */
    public static String rutaRelativa(String base, String ruta) {
        if (ruta == null) {
            return SIN_DATO;
        }
        if (base == null) {
            return ruta;
        }
        try {
            Path b = Path.of(base).toAbsolutePath().normalize();
            Path r = Path.of(ruta).toAbsolutePath().normalize();
            if (r.startsWith(b) && !r.equals(b)) {
                return b.relativize(r).toString().replace('\\', '/');
            }
        } catch (InvalidPathException e) {
            return ruta;
        }
        return ruta;
    }

    /** Una línea, sin espacios sobrantes y recortado; nulo o vacío → {@link #SIN_DATO}. */
    public static String texto(String s, int max) {
        if (s == null) {
            return SIN_DATO;
        }
        String r = s.replaceAll("[\\r\\n\\t]+", " ").strip();
        if (r.isEmpty()) {
            return SIN_DATO;
        }
        return Textos.recortar(r, max);
    }

    /** Como {@link #texto(String, int)} entre backticks (los internos pasan a {@code '}); nulo o vacío → "". */
    public static String codigo(String s, int max) {
        if (s == null || s.isBlank()) {
            return "";
        }
        return "`" + texto(s.replace('`', '\''), max) + "`";
    }

    /** Elementos entre backticks unidos con «, »; si hay más de {@code max}, « y N más». */
    public static String lista(Collection<String> elems, int max) {
        if (elems == null || elems.isEmpty()) {
            return SIN_DATO;
        }
        List<String> partes = new ArrayList<>();
        int i = 0;
        for (String e : elems) {
            if (i++ >= max) {
                break;
            }
            String c = codigo(e, 200);
            partes.add(c.isEmpty() ? SIN_DATO : c);
        }
        String r = String.join(", ", partes);
        if (elems.size() > max) {
            r += " y " + (elems.size() - max) + " más";
        }
        return r;
    }

    /** «a», «a y b», «a, b y c». */
    public static String unir(List<String> partes) {
        if (partes == null || partes.isEmpty()) {
            return "";
        }
        if (partes.size() == 1) {
            return partes.get(0);
        }
        return String.join(", ", partes.subList(0, partes.size() - 1)) + " y " + partes.get(partes.size() - 1);
    }

    public static String cuantos(int n, String uno, String varios) {
        return n == 1 ? uno : String.format(varios, n);
    }

    /** Glosario de símbolos y palabras (cabecera de informe.md). */
    public static String glosario() {
        return TITULO_GLOSARIO + "\n\n"
                + "| Símbolo o palabra | Qué significa |\n"
                + "|---|---|\n"
                + "| " + PASA + " | El test funciona: el código hace lo que el test espera. |\n"
                + "| " + FALLA + " | El código ha devuelto algo distinto de lo que el test esperaba. Puede ser un fallo real del código o un "
                + "test mal planteado: hay que revisarlo. |\n"
                + "| " + MAL_PREPARADO + " | El test no ha llegado a comprobar nada porque estaba mal montado. No dice nada sobre el código: "
                + "se corrige el test y se vuelve a probar (hasta 3 intentos). |\n"
                + "| " + NO_COMPILA + " | El test tiene un error de escritura y no se puede ejecutar. Se corrige y se vuelve a probar "
                + "(hasta 3 intentos). |\n"
                + "| " + SIN_RESULTADO + " | No se ha podido saber si el test pasa o falla. |\n"
                + "| Test | Comprobación automática de que una función del código hace lo que debe. |\n"
                + "| Servicio | La clase del proyecto cuyas funciones se prueban. |\n"
                + "| Identificador | Nombre interno con el que junit-gen reconoce cada test. |\n"
                + "| Respuesta simulada | Durante un test no se usa la base de datos ni otros sistemas reales: se «simula» lo que "
                + "contestarían. |\n"
                + "| Detalle técnico | Información para programadores (mensajes de error, rutas, versiones). Se puede saltar. |\n";
    }
}
