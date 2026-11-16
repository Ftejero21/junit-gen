package com.junitgen.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.junitgen.generacion.Tanda;
import com.junitgen.spec.AsercionSpec;
import com.junitgen.spec.CasoSpec;
import com.junitgen.spec.DatoSpec;
import com.junitgen.spec.EstaticoSpec;
import com.junitgen.spec.StubSpec;
import com.junitgen.util.LimitesRespuesta;
import com.junitgen.util.Textos;
import com.junitgen.verificacion.ClasificadorFallos;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pistas por tipo de fallo para la fase de arreglo: origen de la excepción en el código, estáticos de una clase con
 * {@code mockStatic} que el código llama y no están simulados, lectura de expected/actual con los {@code return} del
 * método probado y símbolo inexistente con los datos disponibles. Deterministas; nunca lanzan.
 */
final class PistasArreglo {

    /** Pistas por caso. */
    static final int MAX_PISTAS = 3;
    /** Tamaño máximo (bytes UTF-8) de cada pista. */
    static final int MAX_PISTA = 320;
    /** {@code return} distintos del método probado que se citan. */
    static final int MAX_RETURNS = 3;
    private static final String SEP_RETURNS = " | ";
    private static final Pattern OBTENIDO = Pattern.compile("but was: <(.*)>\\s*$", Pattern.DOTALL);
    private static final Pattern ESPERADO = Pattern.compile("expected: <(.*?)> but was: <", Pattern.DOTALL);
    private static final Pattern RETURN = Pattern.compile("return\\s+([^;]{1,160});");
    private static final Pattern SIMBOLO = Pattern.compile("symbol:\\s*(method|variable)\\s+(\\w+)");
    private static final Pattern NUMERICO = Pattern.compile("-?\\d+(\\.\\d+)?[LlFfDd]?");
    private static final Set<String> OBTENIDOS_VACIOS = Set.of("0", "[]", "{}", "");
    private static final Set<String> ASERCIONES_TAMANO = Set.of("tamano", "vacio");
    private static final String TAMANO_LLAMADA = "size()";
    private static final List<String> CLAVES_CUERPO = List.of("metodos", "metodosInternos", "helpersExternos");

    private PistasArreglo() {
    }

    /** Hasta {@link #MAX_PISTAS} pistas (cada una ≤ {@link #MAX_PISTA} bytes) del caso; {@code paquete} puede ser null. */
    static List<String> de(Tanda.ArregloTanda a, CasoSpec c, JsonNode paquete) {
        List<String> r = new ArrayList<>();
        if (a == null) {
            return r;
        }
        try {
            anadir(r, a.origen);
            if (ClasificadorFallos.ERROR_MONTAJE.equals(a.tipo) || ClasificadorFallos.FALLO_ASERCION.equals(a.tipo)) {
                anadir(r, campoDerivado(c, paquete));
            }
            boolean asercion = ClasificadorFallos.FALLO_ASERCION.equals(a.tipo);
            if ((ClasificadorFallos.ERROR_MONTAJE.equals(a.tipo) || asercion) && c != null && c.estaticos != null
                    && !c.estaticos.isEmpty() && paquete != null) {
                for (String p : estaticos(c, paquete)) {
                    anadir(r, p);
                }
            }
            if (asercion) {
                anadir(r, asercion(a, c));
                anadir(r, returns(a, paquete));
            }
            if (ClasificadorFallos.NO_COMPILA.equals(a.tipo)) {
                anadir(r, simbolo(a, c));
            }
        } catch (RuntimeException e) {
            System.err.println("junit-gen: no se pudieron calcular las pistas del caso " + a.caso + ": " + e.getClass().getSimpleName()
                    + ": " + e.getMessage());
        }
        return r;
    }

    /** Valor obtenido de un fallo de aserción ({@code but was: <…>} al final), o null. */
    static String obtenido(String error) {
        if (error == null) {
            return null;
        }
        Matcher m = OBTENIDO.matcher(error);
        return m.find() ? m.group(1) : null;
    }

    private static void anadir(List<String> r, String pista) {
        if (pista == null || pista.isBlank() || r.size() >= MAX_PISTAS) {
            return;
        }
        String p = LimitesRespuesta.recortar(pista.strip(), MAX_PISTA);
        if (!r.contains(p)) {
            r.add(p);
        }
    }

    /**
     * Caso con stubs sobre un mock profundo de constructor del que el constructor derivó un campo: los stubs no llegan al campo
     * ya guardado. Null si falta algún dato (cuerpo del constructor ausente o recortado, sin coincidencia, caso ya corregido).
     */
    private static String campoDerivado(CasoSpec c, JsonNode paquete) {
        if (c == null || c.stubs == null || c.stubs.isEmpty() || paquete == null) {
            return null;
        }
        JsonNode cu = paquete.path("montaje").get("cuerpoConstructor");
        if (cu == null || !cu.isTextual() || cu.asText().isBlank()) {
            return null;
        }
        Set<String> profundos = new LinkedHashSet<>();
        for (JsonNode d : paquete.path("dependencias")) {
            JsonNode n = d.get("nombre");
            if (n != null && n.isTextual() && d.path("profundo").asBoolean(false)
                    && "CONSTRUCTOR".equals(d.path("obtencion").asText(null))) {
                profundos.add(n.asText());
            }
        }
        if (profundos.isEmpty()) {
            return null;
        }
        for (StubSpec st : c.stubs) {
            if (st == null || st.mock == null || !profundos.contains(st.mock)) {
                continue;
            }
            String m = st.mock;
            Matcher mt = Pattern.compile("this\\.(\\w+)\\s*=[^;]*(?<![\\w$])" + Pattern.quote(m) + "(?![\\w$])[^;]*;").matcher(cu.asText());
            if (!mt.find()) {
                continue;
            }
            String f = mt.group(1);
            String tipo = null;
            boolean conocido = false;
            for (JsonNode x : paquete.path("mocks")) {
                if (x.isTextual() && x.asText().startsWith(f + ": ")) {
                    conocido = true;
                    tipo = x.asText().substring(f.length() + 2).strip();
                    break;
                }
            }
            if (!conocido) {
                for (JsonNode d : paquete.path("dependencias")) {
                    if (d.path("nombre").isTextual() && f.equals(d.path("nombre").asText())) {
                        conocido = true;
                        break;
                    }
                }
            }
            if (!conocido || f.equals(m) || mencionaCampo(c, f)) {
                continue;
            }
            String t = tipo == null || tipo.isEmpty() ? "" : " `" + tipo + "`";
            String texto = textoCampoDerivado(f, m, t);
            if (texto.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_PISTA) {
                texto = textoCampoDerivado(f, m, "");
            }
            return texto;
        }
        return null;
    }

    private static String textoCampoDerivado(String f, String m, String tipo) {
        return "El constructor ya guardó `" + f + "` a partir de `" + m + "`: el stub del caso sobre `" + m
                + "` no le llega salvo con la misma cadena y argumentos. Declara en `datos` un mock" + tipo
                + ", asígnalo por reflexión al campo `" + f + "` del servicio en `preparacion` y pon los stubs sobre él.";
    }

    /** true si el caso ya trata el campo: lo nombra entre comillas en la preparación o un stub usa un mock con ese nombre. */
    private static boolean mencionaCampo(CasoSpec c, String f) {
        if (c.preparacion != null) {
            for (String p : c.preparacion) {
                if (p != null && p.contains("\"" + f + "\"")) {
                    return true;
                }
            }
        }
        for (StubSpec st : c.stubs) {
            if (st != null && f.equals(st.mock)) {
                return true;
            }
        }
        return false;
    }

    /** P2: por cada clase con mockStatic, los estáticos que el código llama y no están simulados. */
    private static List<String> estaticos(CasoSpec c, JsonNode paquete) {
        Map<String, Set<String>> simulados = new LinkedHashMap<>();
        for (EstaticoSpec e : c.estaticos) {
            if (e == null || e.clase == null || e.clase.isBlank()) {
                continue;
            }
            String x = Textos.nombreSimple(e.clase.strip());
            Set<String> s = simulados.computeIfAbsent(x, k -> new LinkedHashSet<>());
            if (e.llamada != null) {
                String ll = e.llamada.strip();
                if (ll.startsWith(x + ".")) {
                    ll = ll.substring(x.length() + 1);
                }
                int par = ll.indexOf('(');
                s.add((par < 0 ? ll : ll.substring(0, par)).strip());
            }
        }
        List<String> cuerpos = new ArrayList<>();
        for (String k : CLAVES_CUERPO) {
            for (JsonNode n : paquete.path(k)) {
                JsonNode cu = n.get("cuerpo");
                if (cu != null && cu.isTextual()) {
                    cuerpos.add(cu.asText());
                }
            }
        }
        List<String> r = new ArrayList<>();
        for (Map.Entry<String, Set<String>> en : simulados.entrySet()) {
            String x = en.getKey();
            Set<String> llamados = new LinkedHashSet<>();
            for (JsonNode h : paquete.path("helpersExternos")) {
                JsonNode m = h.get("metodo");
                if (m != null && m.isTextual() && m.asText().startsWith(x + ".")) {
                    llamados.add(m.asText().substring(x.length() + 1));
                }
            }
            Pattern p = Pattern.compile("\\b" + Pattern.quote(x) + "\\.(\\w+)\\s*\\(");
            for (String cuerpo : cuerpos) {
                Matcher m = p.matcher(cuerpo);
                while (m.find()) {
                    llamados.add(m.group(1));
                }
            }
            llamados.removeAll(en.getValue());
            if (llamados.isEmpty()) {
                continue;
            }
            List<String> faltan = new ArrayList<>();
            for (String m : llamados) {
                faltan.add("`" + x + "." + m + "(…)`");
            }
            r.add("`mockStatic(" + x + ")` sustituye TODOS los métodos estáticos de " + x + ": el código también llama a "
                    + String.join(", ", faltan) + ", que sin simular devuelven null (0/false en primitivos). Añádelos en `estaticos` "
                    + "con un valor real construido con los datos del caso; no cambies el escenario ni rebajes las comprobaciones.");
        }
        return r;
    }

    /** P3: lectura de expected/actual. */
    private static String asercion(Tanda.ArregloTanda a, CasoSpec c) {
        String ob = obtenido(a.error);
        if (ob == null) {
            return null;
        }
        String o = ob.strip();
        if ("null".equals(o)) {
            return "El código devolvió null: mira en `codigo` qué rama devuelve null y qué dato, stub o campo de configuración falta "
                    + "en la preparación; corrígela en vez de esperar null.";
        }
        String esp = null;
        Matcher m = ESPERADO.matcher(a.error);
        if (m.find()) {
            esp = m.group(1).strip();
        } else if (c != null && c.esperado != null && c.esperado.valor != null) {
            esp = c.esperado.valor.strip();
        }
        boolean esperadoNumerico = esp != null && NUMERICO.matcher(esp).matches();
        if ((esperadoNumerico && "0".equals(o)) || (conTamano(c) && OBTENIDOS_VACIOS.contains(o))) {
            return "El resultado salió vacío: algún paso intermedio (stub, estático sin simular o dato) devuelve null o vacío.";
        }
        if (esp != null && !esp.isEmpty() && (o.length() > esp.length() || o.contains(esp))) {
            return "El valor real es más largo que el esperado: compara con el valor completo que produce el código (su constante "
                    + "real o el objeto construido con los mismos datos), no con una parte.";
        }
        return null;
    }

    private static boolean conTamano(CasoSpec c) {
        if (c == null || c.aserciones == null) {
            return false;
        }
        for (AsercionSpec as : c.aserciones) {
            if (as != null && ((as.tipo != null && ASERCIONES_TAMANO.contains(as.tipo))
                    || (as.real != null && as.real.contains(TAMANO_LLAMADA)))) {
                return true;
            }
        }
        return false;
    }

    /** P3: los {@code return} del método probado (entrada de {@code metodos} cuya clave es ese método). */
    private static String returns(Tanda.ArregloTanda a, JsonNode paquete) {
        if (paquete == null || a.metodo == null || a.metodo.isBlank()) {
            return null;
        }
        Set<String> vistos = new LinkedHashSet<>();
        for (JsonNode n : paquete.path("metodos")) {
            JsonNode clave = n.get("clave");
            JsonNode cuerpo = n.get("cuerpo");
            if (clave == null || !clave.isTextual() || cuerpo == null || !cuerpo.isTextual()) {
                continue;
            }
            String k = clave.asText();
            if (!k.equals(a.metodo) && !k.startsWith(a.metodo + "(")) {
                continue;
            }
            Matcher m = RETURN.matcher(cuerpo.asText());
            while (m.find() && vistos.size() < MAX_RETURNS) {
                vistos.add("`return " + m.group(1).strip() + ";`");
            }
        }
        return vistos.isEmpty() ? null : "El método devuelve: " + String.join(SEP_RETURNS, vistos);
    }

    /** P4: símbolo inexistente en la clase de test con los datos disponibles. */
    private static String simbolo(Tanda.ArregloTanda a, CasoSpec c) {
        String n = null;
        for (String t : new String[]{a.pista, a.error}) {
            if (t != null) {
                Matcher m = SIMBOLO.matcher(t);
                if (m.find()) {
                    n = m.group(2);
                    break;
                }
            }
        }
        if (n == null) {
            return null;
        }
        List<String> datos = new ArrayList<>();
        if (c != null && c.datos != null) {
            for (DatoSpec d : c.datos) {
                if (d != null && d.nombre != null) {
                    datos.add(d.nombre + ": " + (d.tipo == null ? "?" : d.tipo));
                }
            }
        }
        return "`" + n + "` no existe en la clase de test (junit-gen no crea métodos auxiliares): usa un dato de `datos` (disponibles: "
                + (datos.isEmpty() ? "ninguno" : String.join(", ", datos)) + ") o decláralo en `datos`/`servicio.fixtures`.";
    }
}
