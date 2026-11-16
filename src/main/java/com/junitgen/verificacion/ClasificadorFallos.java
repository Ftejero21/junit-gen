package com.junitgen.verificacion;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Clasifica fallos: {@code <failure>} (AssertionError) → fallo_asercion; {@code <error>} (cualquier
 * otra excepción: NPE, UnnecessaryStubbing, mal montaje…) → error_montaje.
 */
public final class ClasificadorFallos {

    public static final String PASA = "pasa";
    public static final String FALLO_ASERCION = "fallo_asercion";
    public static final String ERROR_MONTAJE = "error_montaje";
    public static final String NO_COMPILA = "no_compila";
    public static final String DESCONOCIDO = "desconocido";

    private ClasificadorFallos() {
    }

    public static String clasificar(String elemento, String tipo) {
        if ("failure".equals(elemento)) {
            return FALLO_ASERCION;
        }
        if ("error".equals(elemento)) {
            if (tipo != null && (tipo.endsWith("AssertionFailedError") || tipo.equals("java.lang.AssertionError")
                    || tipo.endsWith("ComparisonFailure"))) {
                return FALLO_ASERCION;
            }
            return ERROR_MONTAJE;
        }
        return PASA;
    }

    /** Paquete de las excepciones de mal uso de Mockito (PotentialStubbingProblem, InvalidUseOfMatchersException…). */
    private static final String MOCKITO_MISUSING = "org.mockito.exceptions.misusing.";

    /**
     * Como {@link #clasificar(String, String)}; un fallo de aserción cuyo mensaje cita una excepción de mal uso de Mockito
     * (p. ej. capturada por un {@code assertThrows} que esperaba otra) es un error de montaje, no de aserción.
     */
    public static String clasificar(String elemento, String tipo, String mensaje) {
        String r = clasificar(elemento, tipo);
        if (FALLO_ASERCION.equals(r) && mensaje != null && mensaje.contains(MOCKITO_MISUSING)) {
            return ERROR_MONTAJE;
        }
        return r;
    }

    private static final int MAX_PISTA = 200;

    /**
     * Pista de diagnóstico (≤ 200 caracteres) para errores de montaje según el tipo de excepción; null si no hay.
     * No incluye rutas ni contenido del proyecto.
     */
    public static String pista(String tipoError, String mensaje, String traza) {
        String t = tipoError == null ? "" : tipoError.trim();
        String m = mensaje == null ? "" : mensaje;
        String tr = traza == null ? "" : traza;
        String r = null;
        if (t.endsWith("UnnecessaryStubbingException")) {
            r = "quita stubs no invocados o muévelos a construccion.stubs";
        } else if (t.endsWith("NotAMockException") || t.endsWith("MissingMethodInvocationException")) {
            r = "la dependencia es real: no admite stubs";
        } else if (t.endsWith("WrongTypeOfReturnValue")) {
            r = "tipo de devuelve incorrecto";
        } else if (t.endsWith("InvalidUseOfMatchersException")) {
            r = "matchers mezclados con valores: en esa llamada usa matchers en TODOS los argumentos (eq(valor) para los literales) o en ninguno";
        } else if (t.endsWith("PotentialStubbingProblem")) {
            r = "el stub no coincide con los argumentos reales de la llamada: usa los mismos valores o any()";
        } else if (t.endsWith("InjectMocksException") || m.contains("InjectMocksException") || tr.contains(".<init>(")) {
            r = "falla el constructor: construccion.stubs/profundos/argumentos";
        }
        return r == null || r.length() <= MAX_PISTA ? r : r.substring(0, MAX_PISTA);
    }

    /** Recorta la traza a 15 líneas como máximo. */
    public static String traza(String texto) {
        if (texto == null) {
            return null;
        }
        String[] l = texto.trim().split("\\R");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(15, l.length); i++) {
            sb.append(l[i].replace("\t", "    ")).append('\n');
        }
        if (l.length > 15) {
            sb.append("… (").append(l.length - 15).append(" líneas más)");
        }
        return sb.toString().trim();
    }

    /** Tamaño máximo (caracteres) del texto de una «Caused by». */
    private static final int MAX_CAUSA = 200;
    private static final String PREFIJO_CAUSA = "Caused by: ";
    private static final String TIPO_COMPILACION = "compilación";
    private static final String PREFIJO_MOCKITO = "org.mockito";
    /** Marco de pila {@code at pkg.Clase.metodo(Archivo.java:N)}. */
    private static final Pattern MARCO = Pattern.compile("^at\\s+([\\w.$]+)\\.([\\w$<>]+)\\(([\\w$]+)\\.java:(\\d+)\\)");

    /**
     * Última «Caused by» de la traza completa (texto tras el prefijo, ≤ 200 caracteres) seguida, si existe, de
     * {@code (en Clase.metodo:línea)} con el primer marco de esa causa. null si no hay.
     */
    public static String causa(String trazaCompleta) {
        if (trazaCompleta == null || trazaCompleta.isBlank()) {
            return null;
        }
        String[] l = trazaCompleta.split("\\R");
        int pos = -1;
        for (int i = 0; i < l.length; i++) {
            if (l[i].trim().startsWith(PREFIJO_CAUSA)) {
                pos = i;
            }
        }
        if (pos < 0) {
            return null;
        }
        String texto = l[pos].trim().substring(PREFIJO_CAUSA.length()).trim();
        if (texto.length() > MAX_CAUSA) {
            texto = texto.substring(0, MAX_CAUSA);
        }
        if (pos + 1 < l.length) {
            Matcher m = MARCO.matcher(l[pos + 1].trim());
            if (m.find()) {
                texto += " (en " + simple(m.group(1)) + "." + m.group(2) + ":" + m.group(4) + ")";
            }
        }
        return texto;
    }

    /**
     * Frase con el origen de la excepción en el código del servicio (tipo, clase.método, línea y causa); null si no hay
     * tipo de error, si es de Mockito o de compilación, o si no hay ni marco del servicio ni causa.
     */
    public static String origen(String fqnServicio, String tipoError, String traza, String causa) {
        if (tipoError == null || tipoError.isBlank()) {
            return null;
        }
        String tipo = tipoError.trim();
        if (tipo.startsWith(PREFIJO_MOCKITO) || TIPO_COMPILACION.equals(tipo)) {
            return null;
        }
        String metodo = null;
        String linea = null;
        if (fqnServicio != null && !fqnServicio.isBlank() && traza != null) {
            Pattern p = Pattern.compile("^at\\s+" + Pattern.quote(fqnServicio.trim())
                    + "(\\$[\\w$]+)?\\.([\\w$<>]+)\\(([\\w$]+)\\.java:(\\d+)\\)");
            for (String l : traza.split("\\R")) {
                Matcher m = p.matcher(l.trim());
                if (m.find()) {
                    metodo = m.group(2);
                    linea = m.group(4);
                    break;
                }
            }
        }
        boolean conCausa = causa != null && !causa.isBlank();
        if (metodo == null && !conCausa) {
            return null;
        }
        StringBuilder sb = new StringBuilder("El código lanzó `").append(simple(tipo)).append('`');
        if (metodo != null) {
            sb.append(" en `").append(simple(fqnServicio.trim())).append('.').append(metodo).append('`');
            sb.append(" (línea ").append(linea).append(')');
        }
        if (conCausa) {
            sb.append("; causa: ").append(causa.trim());
        }
        return sb.toString();
    }

    private static String simple(String fqn) {
        int k = fqn.lastIndexOf('.');
        return k < 0 ? fqn : fqn.substring(k + 1);
    }
}
