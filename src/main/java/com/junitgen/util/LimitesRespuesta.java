package com.junitgen.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Único punto con los límites de tamaño de las respuestas MCP (en bytes UTF-8 del texto, como mide
 * Copilot) y las utilidades para recortar, partir y paginar sin perder contenido de forma silenciosa:
 * todo recorte queda declarado.
 */
public final class LimitesRespuesta {

    /** Máximo del texto completo de una respuesta de herramienta (aviso + notas + cuerpo). */
    public static final int MAX_RESPUESTA = 7_000;
    /** Máximo de la cabecera (aviso + líneas Nota:). */
    public static final int MAX_CABECERA = 900;
    /** Presupuesto del cuerpo que componen las herramientas. */
    public static final int PRESUPUESTO_CUERPO = 6_000;
    /** Un cuerpo de método mayor se sirve por partes. */
    public static final int MAX_CUERPO_PARTE = 3_500;
    /** Bytes de resultados por página en ejecutar_tests (la respuesta principal lleva la página 1). */
    public static final int PAGINA_RESULTADOS = 2_800;
    /** Máximo de un cuerpo de método interno en detalle_servicio. */
    public static final int MAX_CUERPO_INTERNO = 1_200;
    /** Máximo de cada aviso del plan en el resumen del escaneo. */
    public static final int MAX_AVISO_PLAN = 300;
    /** Mínimo al que se recorta una cadena en acotarJson. */
    static final int MIN_CADENA = 200;
    /** Mínimo de elementos de las listas marcadas por el llamador en acotarJson. */
    public static final int MIN_LISTA_MARCADA = 10;

    public static final String MARCA_RECORTE = " … [recortado]";
    public static final String AVISO_RED = "[junit-gen: respuesta recortada a 7 000 bytes]";
    /** Claves de primer nivel que acotarJson nunca recorta en la fase final. */
    public static final Set<String> PROTEGIDAS = Set.of("resumenSesion", "respuestaFinal", "plan", "verificacion", "paginacion", "accionRequerida",
            "recortes", "comoVerElResto");

    /** Variable de entorno / propiedad del sistema del límite por respuesta. */
    public static final String ENV_MAX_RESPUESTA = "JUNITGEN_MAX_RESPUESTA";
    public static final String PROP_MAX_RESPUESTA = "junitgen.maxRespuesta";
    /** Variable de entorno / propiedad del sistema del presupuesto total del paquete de trabajo (sumando sus páginas). */
    public static final String ENV_PRESUPUESTO_PAQUETE = "JUNITGEN_PRESUPUESTO_PAQUETE";
    public static final String PROP_PRESUPUESTO_PAQUETE = "junitgen.presupuestoPaquete";
    /** Rango admitido del límite por respuesta configurable. */
    public static final int MIN_MAX_RESPUESTA = MAX_RESPUESTA;
    public static final int TOPE_MAX_RESPUESTA = 32_000;
    /** Presupuesto total por defecto del paquete de trabajo y su rango admitido. */
    public static final int PRESUPUESTO_PAQUETE = 13_000;
    public static final int MIN_PRESUPUESTO_PAQUETE = 6_000;
    public static final int TOPE_PRESUPUESTO_PAQUETE = 32_000;
    /** Margen que se descuenta, además de la cabecera, para la página nominal del paquete. */
    public static final int MARGEN_PAGINA_PAQUETE = 300;

    private LimitesRespuesta() {
    }

    /** Valores configurables leídos una sola vez. */
    private static final class Config {
        static final int MAX_RESPUESTA_CONF = leer(ENV_MAX_RESPUESTA, PROP_MAX_RESPUESTA, MIN_MAX_RESPUESTA, TOPE_MAX_RESPUESTA,
                MAX_RESPUESTA);
        static final int PRESUPUESTO_PAQUETE_CONF = leer(ENV_PRESUPUESTO_PAQUETE, PROP_PRESUPUESTO_PAQUETE, MIN_PRESUPUESTO_PAQUETE,
                TOPE_PRESUPUESTO_PAQUETE, PRESUPUESTO_PAQUETE);

        private Config() {
        }

        /** Entero de la variable de entorno o, si falta, de la propiedad; ausente, no numérico o fuera de rango → por defecto. */
        private static int leer(String env, String prop, int min, int max, int porDefecto) {
            String v;
            try {
                v = System.getenv(env);
                if (v == null || v.isBlank()) {
                    v = System.getProperty(prop);
                }
            } catch (SecurityException e) {
                System.err.println("junit-gen: no se pudo leer " + env + " (" + e.getClass().getSimpleName() + "); se usa " + porDefecto);
                return porDefecto;
            }
            if (v == null || v.isBlank()) {
                return porDefecto;
            }
            try {
                int n = Integer.parseInt(v.trim().replace("_", ""));
                if (n >= min && n <= max) {
                    return n;
                }
            } catch (NumberFormatException e) {
                System.err.println("junit-gen: " + env + " no es un entero; se usa " + porDefecto);
                return porDefecto;
            }
            System.err.println("junit-gen: " + env + " fuera de rango [" + min + ", " + max + "]; se usa " + porDefecto);
            return porDefecto;
        }
    }

    /** Límite por respuesta: {@code JUNITGEN_MAX_RESPUESTA} o {@code -Djunitgen.maxRespuesta} en [7000, 32000]; por defecto {@link #MAX_RESPUESTA}. */
    public static int maxRespuesta() {
        return Config.MAX_RESPUESTA_CONF;
    }

    /** Presupuesto total del paquete de trabajo (sumando sus páginas): {@code JUNITGEN_PRESUPUESTO_PAQUETE} en [6000, 32000]; por defecto 13 000. */
    public static int presupuestoPaquete() {
        return Config.PRESUPUESTO_PAQUETE_CONF;
    }

    /** Presupuesto nominal de la página 1 del paquete. */
    public static int paginaPaqueteNominal() {
        return Math.min(presupuestoPaquete(), maxRespuesta() - MAX_CABECERA - MARGEN_PAGINA_PAQUETE);
    }

    /** Presupuesto de la página 2 del paquete (0 = el paquete va en una sola página). */
    public static int segundaPaginaPaquete() {
        return Math.max(0, presupuestoPaquete() - paginaPaqueteNominal());
    }

    /** {@link #AVISO_RED} con el límite real si se configuró otro. */
    public static String avisoRed() {
        int max = maxRespuesta();
        if (max == MAX_RESPUESTA) {
            return AVISO_RED;
        }
        String n = String.format(java.util.Locale.ROOT, "%,d", max).replace(',', ' ');
        return "[junit-gen: respuesta recortada a " + n + " bytes]";
    }

    public static int bytes(String s) {
        return s == null ? 0 : s.getBytes(StandardCharsets.UTF_8).length;
    }

    /** Prefijo de {@code s} de como máximo {@code maxBytes} bytes UTF-8, cortado en frontera de carácter. */
    public static String prefijo(String s, int maxBytes) {
        if (s == null) {
            return null;
        }
        int total = 0;
        int i = 0;
        while (i < s.length()) {
            int cp = s.codePointAt(i);
            int b = cp < 0x80 ? 1 : cp < 0x800 ? 2 : cp < 0x10000 ? 3 : 4;
            if (total + b > maxBytes) {
                break;
            }
            total += b;
            i += Character.charCount(cp);
        }
        return s.substring(0, i);
    }

    /** Recorta a {@code maxBytes} bytes UTF-8 (marca incluida) añadiendo « … [recortado]». */
    public static String recortar(String s, int maxBytes) {
        if (s == null || bytes(s) <= maxBytes) {
            return s;
        }
        int marca = bytes(MARCA_RECORTE);
        if (maxBytes <= marca) {
            return prefijo(s, Math.max(0, maxBytes));
        }
        return prefijo(s, maxBytes - marca) + MARCA_RECORTE;
    }

    /** «[a, b, … (+N más)]» con como máximo {@code max} elementos (mismo formato que List.toString sin recorte). */
    public static String listaCorta(Collection<?> c, int max) {
        if (c == null || c.isEmpty()) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder("[");
        int i = 0;
        for (Object o : c) {
            if (i >= max) {
                break;
            }
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(o);
            i++;
        }
        if (c.size() > max) {
            sb.append(", … (+").append(c.size() - max).append(" más)");
        }
        return sb.append(']').toString();
    }

    /** Lista con como máximo {@code max} elementos y, si hay más, una entrada final «+N más». */
    public static List<String> listaAcotada(Collection<String> c, int max) {
        List<String> r = new ArrayList<>();
        if (c == null) {
            return r;
        }
        for (String s : c) {
            if (r.size() >= max) {
                break;
            }
            r.add(s);
        }
        if (c.size() > max) {
            r.add("+" + (c.size() - max) + " más");
        }
        return r;
    }

    /**
     * Parte el texto en trozos de como máximo {@code maxBytes} bytes, por líneas (cada trozo conserva sus
     * saltos de línea); una línea mayor se parte en duro. Concatenar los trozos reproduce el original.
     */
    public static List<String> partir(String texto, int maxBytes) {
        List<String> r = new ArrayList<>();
        if (texto == null || texto.isEmpty()) {
            r.add(texto == null ? "" : texto);
            return r;
        }
        int max = Math.max(4, maxBytes);
        StringBuilder actual = new StringBuilder();
        int bytesActual = 0;
        int i = 0;
        while (i < texto.length()) {
            int fin = texto.indexOf('\n', i);
            String linea = fin < 0 ? texto.substring(i) : texto.substring(i, fin + 1);
            i = fin < 0 ? texto.length() : fin + 1;
            int bl = bytes(linea);
            if (bytesActual + bl <= max) {
                actual.append(linea);
                bytesActual += bl;
                continue;
            }
            if (bytesActual > 0) {
                r.add(actual.toString());
                actual.setLength(0);
                bytesActual = 0;
            }
            while (bytes(linea) > max) {
                String p = prefijo(linea, max);
                r.add(p);
                linea = linea.substring(p.length());
            }
            actual.append(linea);
            bytesActual = bytes(linea);
        }
        if (bytesActual > 0 || r.isEmpty()) {
            r.add(actual.toString());
        }
        return r;
    }

    /** Bytes UTF-8 de {@code s} escapado como cadena JSON, sin las comillas (0 si null). */
    public static int bytesJson(String s) {
        return s == null ? 0 : bytes(Json.compacto(s)) - 2;
    }

    /**
     * Como {@link #partir}, pero cada trozo mide como máximo {@code maxBytes} bytes una vez escapado como cadena
     * JSON ({@link #bytesJson}). Determinista; concatenar los trozos reproduce el original.
     */
    public static List<String> partirJson(String texto, int maxBytes) {
        List<String> r = new ArrayList<>();
        if (texto == null || texto.isEmpty()) {
            r.add(texto == null ? "" : texto);
            return r;
        }
        int max = Math.max(4, maxBytes);
        StringBuilder actual = new StringBuilder();
        int bytesActual = 0;
        int i = 0;
        while (i < texto.length()) {
            int fin = texto.indexOf('\n', i);
            String linea = fin < 0 ? texto.substring(i) : texto.substring(i, fin + 1);
            i = fin < 0 ? texto.length() : fin + 1;
            int bl = bytesJson(linea);
            if (bytesActual + bl <= max) {
                actual.append(linea);
                bytesActual += bl;
                continue;
            }
            if (bytesActual > 0) {
                r.add(actual.toString());
                actual.setLength(0);
                bytesActual = 0;
            }
            while (bytesJson(linea) > max) {
                String p = prefijoJson(linea, max);
                r.add(p);
                linea = linea.substring(p.length());
            }
            actual.append(linea);
            bytesActual = bytesJson(linea);
        }
        if (bytesActual > 0 || r.isEmpty()) {
            r.add(actual.toString());
        }
        return r;
    }

    /** Prefijo de {@code s} cuyo tamaño JSON-escapado es ≤ {@code max}, con al menos un carácter. */
    private static String prefijoJson(String s, int max) {
        String p = prefijo(s, max);
        while (p.length() > 1 && bytesJson(p) > max) {
            int corte = p.length() - Math.max(1, (p.length() * (bytesJson(p) - max)) / Math.max(1, bytesJson(p)));
            corte = Math.max(1, Math.min(p.length() - 1, corte));
            if (Character.isLowSurrogate(p.charAt(corte)) && corte > 1) {
                corte--;
            }
            p = p.substring(0, corte);
        }
        if (p.isEmpty()) {
            p = s.substring(0, Character.charCount(s.codePointAt(0)));
        }
        return p;
    }

    /**
     * Páginas rellenadas de forma voraz con bloques consecutivos sin superar {@code maxBytes} ni
     * {@code maxBloques} (≤ 0 = sin límite de bloques). Un bloque mayor que el presupuesto se divide con
     * {@link #partir}; nunca se descarta contenido. Siempre devuelve al menos una página (quizá vacía).
     */
    public static List<List<String>> paginar(List<String> bloques, int maxBytes, int maxBloques) {
        List<String> piezas = new ArrayList<>();
        for (String b : bloques) {
            if (bytes(b) > maxBytes) {
                piezas.addAll(partir(b, maxBytes));
            } else {
                piezas.add(b);
            }
        }
        List<List<String>> paginas = new ArrayList<>();
        List<String> actual = new ArrayList<>();
        int bytesActual = 0;
        for (String p : piezas) {
            int bp = bytes(p);
            boolean lleno = !actual.isEmpty() && (bytesActual + bp > maxBytes || (maxBloques > 0 && actual.size() >= maxBloques));
            if (lleno) {
                paginas.add(actual);
                actual = new ArrayList<>();
                bytesActual = 0;
            }
            actual.add(p);
            bytesActual += bp;
        }
        if (!actual.isEmpty() || paginas.isEmpty()) {
            paginas.add(actual);
        }
        return paginas;
    }

    public static boolean acotarJson(ObjectNode raiz, int presupuesto, List<String> ordenRecorte, String comoVerElResto) {
        return acotarJson(raiz, presupuesto, ordenRecorte, Set.of(), Set.of(), comoVerElResto);
    }

    public static boolean acotarJson(ObjectNode raiz, int presupuesto, List<String> ordenRecorte, Set<String> minimoDiez,
                                     String comoVerElResto) {
        return acotarJson(raiz, presupuesto, ordenRecorte, minimoDiez, Set.of(), comoVerElResto);
    }

    /**
     * Mientras el JSON compacto supere el presupuesto, recorre {@code ordenRecorte} (rutas «a.b.0.c» a
     * arrays o cadenas, de menos a más importante): un array se reduce a la mitad conservando el principio
     * (mínimo 1; las rutas de {@code minimoDiez}, mínimo 10) y una cadena a la mitad (mínimo 200 bytes),
     * anotando {@code recortes[ruta] = "mostrados X de Y"}. Si aún no cabe, recorta el mayor array o cadena
     * restante fuera de las claves protegidas ({@link #PROTEGIDAS} más {@code protegidasExtra}). Con algún
     * recorte añade {@code comoVerElResto}. Devuelve true si recortó algo.
     */
    public static boolean acotarJson(ObjectNode raiz, int presupuesto, List<String> ordenRecorte, Set<String> minimoDiez,
                                     Set<String> protegidasExtra, String comoVerElResto) {
        if (bytes(Json.compacto(raiz)) <= presupuesto) {
            return false;
        }
        Estado st = new Estado(raiz, comoVerElResto);
        for (String ruta : ordenRecorte) {
            while (bytes(Json.compacto(raiz)) > presupuesto) {
                JsonNode n = resolver(raiz, ruta);
                if (!st.reducir(raiz, ruta, n, minimoDiez.contains(ruta) ? MIN_LISTA_MARCADA : 1)) {
                    break;
                }
            }
            if (bytes(Json.compacto(raiz)) <= presupuesto) {
                return true;
            }
        }
        Set<String> protegidas = new LinkedHashSet<>(PROTEGIDAS);
        protegidas.addAll(protegidasExtra);
        int vueltas = 0;
        while (bytes(Json.compacto(raiz)) > presupuesto && vueltas++ < 500) {
            Candidato mayor = new Candidato();
            buscarMayor(raiz, "", protegidas, true, mayor);
            if (mayor.ruta == null || !st.reducir(raiz, mayor.ruta, resolver(raiz, mayor.ruta), 1)) {
                break;
            }
        }
        return true;
    }

    private static final class Candidato {
        String ruta;
        int tam = -1;
    }

    private static final class Estado {
        final ObjectNode raiz;
        final String comoVerElResto;
        final Map<String, Integer> originales = new HashMap<>();
        boolean marcado;

        Estado(ObjectNode raiz, String comoVerElResto) {
            this.raiz = raiz;
            this.comoVerElResto = comoVerElResto;
        }

        boolean reducir(ObjectNode r, String ruta, JsonNode n, int minimo) {
            if (n == null) {
                return false;
            }
            if (n.isArray()) {
                ArrayNode a = (ArrayNode) n;
                int tam = a.size();
                if (tam <= minimo) {
                    return false;
                }
                int nuevo = Math.max(minimo, tam / 2);
                originales.putIfAbsent(ruta, tam);
                while (a.size() > nuevo) {
                    a.remove(a.size() - 1);
                }
                anotar(ruta, "mostrados " + a.size() + " de " + originales.get(ruta));
                return true;
            }
            if (n.isTextual()) {
                String s = n.asText();
                int b = bytes(s);
                if (b <= MIN_CADENA) {
                    return false;
                }
                originales.putIfAbsent(ruta, b);
                String nuevo = recortar(s, Math.max(MIN_CADENA, b / 2));
                if (nuevo.equals(s)) {
                    return false;
                }
                if (!reemplazar(r, ruta, nuevo)) {
                    return false;
                }
                anotar(ruta, "mostrados " + bytes(nuevo) + " de " + originales.get(ruta) + " bytes");
                return true;
            }
            return false;
        }

        void anotar(String ruta, String texto) {
            ObjectNode rec = raiz.has("recortes") && raiz.get("recortes").isObject() ? (ObjectNode) raiz.get("recortes")
                    : raiz.putObject("recortes");
            rec.put(ruta, texto);
            if (!marcado && comoVerElResto != null) {
                raiz.put("comoVerElResto", comoVerElResto);
            }
            marcado = true;
        }
    }

    /** Busca el mayor array o cadena (por bytes de su JSON) fuera de las claves protegidas de primer nivel. */
    private static void buscarMayor(JsonNode n, String ruta, Set<String> protegidas, boolean nivelRaiz, Candidato c) {
        if (n.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = n.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                if (nivelRaiz && protegidas.contains(e.getKey())) {
                    continue;
                }
                buscarMayor(e.getValue(), ruta.isEmpty() ? e.getKey() : ruta + "." + e.getKey(), protegidas, false, c);
            }
        } else if (n.isArray()) {
            int tam = bytes(Json.compacto(n));
            if (n.size() > 1 && tam > c.tam) {
                c.tam = tam;
                c.ruta = ruta;
            }
            for (int i = 0; i < n.size(); i++) {
                buscarMayor(n.get(i), ruta + "." + i, protegidas, false, c);
            }
        } else if (n.isTextual()) {
            int tam = bytes(n.asText());
            if (tam > MIN_CADENA && tam > c.tam) {
                c.tam = tam;
                c.ruta = ruta;
            }
        }
    }

    static JsonNode resolver(JsonNode raiz, String ruta) {
        JsonNode n = raiz;
        for (String parte : ruta.split("\\.")) {
            if (n == null) {
                return null;
            }
            if (n.isArray()) {
                try {
                    n = n.get(Integer.parseInt(parte));
                } catch (NumberFormatException e) {
                    return null;
                }
            } else {
                n = n.get(parte);
            }
        }
        return n;
    }

    private static boolean reemplazar(ObjectNode raiz, String ruta, String valor) {
        int i = ruta.lastIndexOf('.');
        JsonNode padre = i < 0 ? raiz : resolver(raiz, ruta.substring(0, i));
        String ultimo = i < 0 ? ruta : ruta.substring(i + 1);
        if (padre instanceof ObjectNode o) {
            o.put(ultimo, valor);
            return true;
        }
        if (padre instanceof ArrayNode a) {
            try {
                a.set(Integer.parseInt(ultimo), TextNode.valueOf(valor));
                return true;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return false;
    }
}
