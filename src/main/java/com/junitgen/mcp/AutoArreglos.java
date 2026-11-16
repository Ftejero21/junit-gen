package com.junitgen.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.junitgen.Fachada;
import com.junitgen.duplicados.NormalizadorExpresiones;
import com.junitgen.generacion.AlmacenSpec;
import com.junitgen.generacion.ResultadoGeneracion;
import com.junitgen.generacion.Tanda;
import com.junitgen.spec.CasoSpec;
import com.junitgen.spec.DatoSpec;
import com.junitgen.spec.NormalizadorMatchers;
import com.junitgen.spec.ServicioSpec;
import com.junitgen.spec.Spec;
import com.junitgen.spec.StubSpec;
import com.junitgen.spec.VerificacionSpec;
import com.junitgen.verificacion.ClasificadorFallos;
import com.junitgen.verificacion.ResultadoTest;
import com.junitgen.verificacion.VerificadorTests;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Arreglos mecánicos sin IA de la fase de arreglo, antes de entregar nada: R1 matchers mezclados con valores →
 * {@code eq(valor)}; R2 stub innecesario → se quita; R3 argumento que llama a un auxiliar inexistente habiendo un único
 * dato del tipo del parámetro → ese dato; R4 matchers tipados con la firma real (por reflexión); R5 argumento real null
 * frente a un matcher de tipo → {@code nullable(T.class)}. Solo para {@code no_compila}/{@code error_montaje} no comunes y
 * no protegidos; nunca tocan {@code esperado}, {@code aserciones} ni quitan verificaciones. Nunca lanzan hacia fuera.
 */
final class AutoArreglos {

    /** Spec propuesto del caso (limpio, con los cambios) y frases de lo cambiado. */
    record Propuesta(ObjectNode spec, List<String> frases) {
    }

    /** Matchers de Mockito reconocidos. */
    static final Set<String> MATCHERS = NormalizadorMatchers.MATCHERS;
    /** Clases cuyo método estático es un matcher. */
    static final Set<String> SCOPES_MATCHERS = NormalizadorMatchers.SCOPES_MATCHERS;
    private static final String UNNECESSARY = "UnnecessaryStubbingException";
    private static final String UNNECESSARY_MENSAJE = "Unnecessary stubbings detected";
    private static final Pattern LINEA_STUB = Pattern.compile("->\\s*at\\s+[\\w.$]+\\.(\\w+)\\(([\\w$]+)\\.java:(\\d+)\\)");
    private static final Pattern AUXILIAR = Pattern.compile("symbol:\\s*method\\s+(\\w+)\\(\\)");
    private static final Pattern LOCALIZACION = Pattern.compile("location:\\s*class\\s+([\\w.$]+)");
    private static final String MARCA_INICIO = "junit-gen:caso-inicio id=";
    private static final String MARCA_FIN = "junit-gen:caso-fin id=";

    private AutoArreglos() {
    }

    /**
     * Propuesta de arreglo automático del caso (R3, R2 y R1, en ese orden), o null si nada cambia o el caso no es
     * elegible. Nunca lanza.
     */
    static Propuesta proponer(Tanda.ArregloTanda a, CasoSpec c, ResultadoTest ultimo, Path salida) {
        return proponer(a, c, ultimo, salida, null);
    }

    /**
     * Como {@link #proponer(Tanda.ArregloTanda, CasoSpec, ResultadoTest, Path)}; con {@code tipador} (R4/R5: matchers con la
     * firma real y {@code nullable} frente a un argumento real null; modifica el spec y devuelve sus frases) se aplica entre
     * R2 y R1. Ninguna propuesta cambia {@code esperado}, {@code aserciones} ni quita verificaciones.
     */
    static Propuesta proponer(Tanda.ArregloTanda a, CasoSpec c, ResultadoTest ultimo, Path salida,
                              Function<ObjectNode, List<String>> tipador) {
        if (a == null || c == null || a.comun || c.protegido()
                || !(ClasificadorFallos.NO_COMPILA.equals(a.tipo) || ClasificadorFallos.ERROR_MONTAJE.equals(a.tipo))) {
            return null;
        }
        try {
            ObjectNode spec = HerramientaEstadoVerificacion.specLimpio(c);
            ObjectNode original = spec.deepCopy();
            List<String> frases = new ArrayList<>();
            auxiliar(spec, a, c, frases);
            stubInnecesario(spec, a, c, ultimo, salida, frases);
            if (tipador != null) {
                List<String> tipadas = tipador.apply(spec);
                if (tipadas != null) {
                    frases.addAll(tipadas);
                }
            }
            matchers(spec, frases);
            if (frases.isEmpty()) {
                return null;
            }
            if (!sinDebilitar(original, spec)) {
                System.err.println("junit-gen: corrección automática descartada en " + a.caso + ": debilitaba el test.");
                return null;
            }
            return new Propuesta(spec, frases);
        } catch (RuntimeException e) {
            System.err.println("junit-gen: corrección automática no aplicable al caso " + a.caso + ": " + e.getClass().getSimpleName()
                    + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Aplica las correcciones automáticas de los casos pendientes del servicio {@code fqn} (una generación y una
     * verificación para todos). Nunca lanza; false = nada aplicado.
     */
    static boolean intentar(SesionMcp sesion, Path proyecto, Path salida, String fqn) {
        if (sesion == null || proyecto == null || salida == null || fqn == null) {
            return false;
        }
        try {
            Spec spec = AlmacenSpec.leer(salida);
            if (spec == null) {
                return false;
            }
            Map<String, ObjectNode> nuevos = new LinkedHashMap<>();
            Map<String, List<String>> frases = new LinkedHashMap<>();
            ServicioSpec servicio = null;
            for (Tanda.ArregloTanda a : sesion.arreglosPendientes(proyecto)) {
                if (!fqn.equals(a.servicio) || a.autoIntentos >= ArreglosFinal.MAX_AUTO_INTENTOS) {
                    continue;
                }
                AlmacenSpec.Ubicacion u = AlmacenSpec.buscar(spec, a.caso);
                if (u == null || u.caso() == null || u.servicio() == null || (servicio != null && servicio != u.servicio())) {
                    continue;
                }
                Propuesta p = proponer(a, u.caso(), sesion.verificaciones().ultimo(salida, a.caso), salida,
                        s -> sesion.fachada().tiparMatchers(proyecto, salida, fqn, s, sesion.verificaciones().ultimo(salida, a.caso)));
                if (p == null) {
                    continue;
                }
                servicio = u.servicio();
                p.spec().put("id", a.caso);
                nuevos.put(a.caso, p.spec());
                frases.put(a.caso, p.frases());
            }
            if (nuevos.isEmpty()) {
                return false;
            }
            for (String id : nuevos.keySet()) {
                sesion.actualizarArreglo(proyecto, id, x -> {
                    if (x != null) {
                        x.autoIntentos++;
                    }
                    return x;
                });
            }
            HerramientaArreglarCaso.Regeneracion rg = HerramientaArreglarCaso.regenerar(sesion, proyecto, salida, spec, servicio,
                    nuevos, null);
            Fachada.Generacion g = rg.g();
            ResultadoGeneracion res = g.resultado();
            Set<String> reescritos = new LinkedHashSet<>();
            for (var m : res.generados) {
                Object id = m.get("caso");
                if (id != null && nuevos.containsKey(id.toString())) {
                    reescritos.add(id.toString());
                }
            }
            Path salidaReal = g.escritor() != null ? g.escritor().salida() : salida;
            if (!res.errores.isEmpty() || reescritos.isEmpty()) {
                System.err.println("junit-gen: corrección automática no aplicada en " + nuevos.keySet() + " (" + res.errores.size()
                        + " errores de validación, " + reescritos.size() + " casos reescritos).");
                sesion.guardarTanda(proyecto, salidaReal, Boolean.FALSE);
                return false;
            }
            HerramientaArreglarCaso.trasGenerar(sesion, proyecto, g);
            for (String id : reescritos) {
                List<String> fr = frases.getOrDefault(id, List.of());
                sesion.actualizarArreglo(proyecto, id, x -> {
                    if (x == null) {
                        return null;
                    }
                    List<String> todos = new ArrayList<>(x.autoCambios == null ? List.of() : x.autoCambios);
                    for (String f : fr) {
                        if (!todos.contains(f)) {
                            todos.add(f);
                        }
                    }
                    while (todos.size() > ArreglosFinal.MAX_CAMBIOS) {
                        todos.remove(0);
                    }
                    x.autoCambios = todos;
                    return x;
                });
            }
            System.err.println("junit-gen: corrección automática aplicada a " + reescritos);
            sesion.verificaciones().encolarArreglo(proyecto, salidaReal, fqn, List.of());
            sesion.guardarTanda(proyecto, salidaReal, Boolean.FALSE);
            return true;
        } catch (RuntimeException e) {
            System.err.println("junit-gen: no se pudo aplicar la corrección automática en " + fqn + ": " + e.getClass().getSimpleName()
                    + ": " + e.getMessage());
            return false;
        }
    }

    // ------------------------------------------------------------- R3: auxiliar inexistente

    private static void auxiliar(ObjectNode spec, Tanda.ArregloTanda a, CasoSpec c, List<String> frases) {
        if (!ClasificadorFallos.NO_COMPILA.equals(a.tipo)) {
            return;
        }
        String n = null;
        String texto = null;
        for (String t : new String[]{a.pista, a.error}) {
            if (t != null) {
                Matcher m = AUXILIAR.matcher(t);
                if (m.find()) {
                    n = m.group(1);
                    texto = t;
                    break;
                }
            }
        }
        if (n == null) {
            return;
        }
        Matcher loc = LOCALIZACION.matcher(texto);
        if (loc.find() && (a.claseTest == null || !simple(loc.group(1)).equals(simple(a.claseTest)))) {
            return;
        }
        List<String> args = c.argumentos == null ? List.of() : c.argumentos;
        List<String> params = c.parametros;
        if (params == null || params.size() != args.size() || c.datos == null) {
            return;
        }
        String llamada = n + "()";
        List<String> nuevos = new ArrayList<>(args);
        Set<String> usados = new LinkedHashSet<>();
        for (String x : args) {
            usados.add(x == null ? "" : x.trim());
        }
        boolean cambia = false;
        Set<String> sustituidos = new TreeSet<>();
        for (int i = 0; i < args.size(); i++) {
            if (args.get(i) == null || !llamada.equals(args.get(i).trim())) {
                continue;
            }
            String t = simple(params.get(i));
            String elegido = null;
            int candidatos = 0;
            for (DatoSpec d : c.datos) {
                if (d == null || d.nombre == null || d.tipo == null || !t.equals(simple(d.tipo)) || usados.contains(d.nombre.trim())) {
                    continue;
                }
                candidatos++;
                elegido = d.nombre.trim();
            }
            if (candidatos == 1) {
                nuevos.set(i, elegido);
                usados.add(elegido);
                sustituidos.add(elegido);
                cambia = true;
            }
        }
        if (!cambia) {
            return;
        }
        ArrayNode an = spec.putArray("argumentos");
        nuevos.forEach(an::add);
        for (String d : sustituidos) {
            frases.add("Se cambió el argumento `" + llamada + "` por el dato `" + d + "` ya declarado en el caso: `" + llamada
                    + "` no existe en la clase de test");
        }
    }

    /** Nombre simple sin genéricos de un tipo. */
    private static String simple(String tipo) {
        if (tipo == null) {
            return "";
        }
        String t = tipo.trim();
        int g = t.indexOf('<');
        if (g >= 0) {
            t = t.substring(0, g);
        }
        int k = t.lastIndexOf('.');
        return (k < 0 ? t : t.substring(k + 1)).trim();
    }

    // ------------------------------------------------------------- R2: stub innecesario

    private static void stubInnecesario(ObjectNode spec, Tanda.ArregloTanda a, CasoSpec c, ResultadoTest ultimo, Path salida,
                                        List<String> frases) {
        if (ultimo == null || c.stubs == null || c.stubs.isEmpty() || a.metodoTest == null || salida == null) {
            return;
        }
        boolean innecesario = (ultimo.tipoError != null && ultimo.tipoError.trim().endsWith(UNNECESSARY))
                || (ultimo.mensaje != null && ultimo.mensaje.contains(UNNECESSARY_MENSAJE));
        if (!innecesario) {
            return;
        }
        Set<Integer> lineas = new TreeSet<>();
        Matcher m = LINEA_STUB.matcher(nulo(ultimo.mensaje) + "\n" + nulo(ultimo.traza));
        while (m.find()) {
            if (a.metodoTest.equals(m.group(1))) {
                lineas.add(Integer.parseInt(m.group(3)));
            }
        }
        if (lineas.isEmpty()) {
            return;
        }
        List<String> texto;
        try {
            Path archivo = VerificadorTests.archivoDeCaso(salida, a.caso);
            if (archivo == null) {
                return;
            }
            texto = Files.readAllLines(archivo, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            System.err.println("junit-gen: no se pudo leer el test del caso " + a.caso + " para quitar stubs: "
                    + e.getClass().getSimpleName() + ": " + e.getMessage());
            return;
        }
        int inicio = -1;
        int fin = -1;
        for (int i = 0; i < texto.size(); i++) {
            if (inicio < 0 && contieneMarca(texto.get(i), MARCA_INICIO + a.caso)) {
                inicio = i + 1;
            } else if (inicio >= 0 && contieneMarca(texto.get(i), MARCA_FIN + a.caso)) {
                fin = i + 1;
                break;
            }
        }
        if (inicio < 0 || fin < 0) {
            return;
        }
        Set<Integer> quitar = new TreeSet<>();
        for (int n : lineas) {
            if (n <= inicio || n >= fin || n > texto.size()) {
                return;
            }
            String l = NormalizadorExpresiones.sinEspacios(texto.get(n - 1));
            int unico = -1;
            int coincidencias = 0;
            for (int j = 0; j < c.stubs.size(); j++) {
                StubSpec s = c.stubs.get(j);
                if (s == null || s.mock == null || s.llamada == null) {
                    continue;
                }
                String directa = NormalizadorExpresiones.sinEspacios(s.mock + "." + s.llamada);
                String when = NormalizadorExpresiones.sinEspacios(".when(" + s.mock + ")." + s.llamada);
                if (l.contains(directa) || l.contains(when)) {
                    coincidencias++;
                    unico = j;
                }
            }
            if (coincidencias != 1) {
                return;
            }
            StubSpec s = c.stubs.get(unico);
            if (verificado(c, s)) {
                return;
            }
            quitar.add(unico);
        }
        JsonNode stubs = spec.get("stubs");
        if (!(stubs instanceof ArrayNode arr) || arr.size() != c.stubs.size()) {
            return;
        }
        List<Integer> orden = new ArrayList<>(quitar);
        for (int k = orden.size() - 1; k >= 0; k--) {
            arr.remove(orden.get(k).intValue());
        }
        for (int j : quitar) {
            StubSpec s = c.stubs.get(j);
            frases.add("Se quitó la respuesta simulada `" + s.mock + "." + s.llamada + "`: el código no llega a usarla en este caso");
        }
    }

    private static boolean contieneMarca(String linea, String marca) {
        int k = linea.indexOf(marca);
        if (k < 0) {
            return false;
        }
        int e = k + marca.length();
        return e >= linea.length() || Character.isWhitespace(linea.charAt(e));
    }

    /** true si hay una verificación del mismo mock y método que espera alguna llamada (veces null o > 0). */
    private static boolean verificado(CasoSpec c, StubSpec s) {
        if (c.verificaciones == null) {
            return false;
        }
        String metodo = nombreMetodo(s.llamada);
        for (VerificacionSpec v : c.verificaciones) {
            if (v != null && s.mock.equals(v.mock) && v.llamada != null && metodo.equals(nombreMetodo(v.llamada))
                    && (v.veces == null || v.veces > 0)) {
                return true;
            }
        }
        return false;
    }

    private static String nombreMetodo(String llamada) {
        String l = llamada == null ? "" : llamada.trim();
        int par = l.indexOf('(');
        return (par < 0 ? l : l.substring(0, par)).trim();
    }

    private static String nulo(String s) {
        return s == null ? "" : s;
    }

    // ------------------------------------------------------------- R1: matchers mezclados

    private static void matchers(ObjectNode spec, List<String> frases) {
        for (JsonNode s : spec.path("stubs")) {
            matchersEn(s, "mock", frases);
        }
        for (JsonNode cm : spec.path("construcciones")) {
            for (JsonNode s : cm.path("stubs")) {
                matchersEn(s, "mock", frases);
            }
        }
        for (JsonNode e : spec.path("estaticos")) {
            matchersEn(e, "clase", frases);
        }
        for (JsonNode v : spec.path("verificaciones")) {
            matchersEn(v, "mock", frases);
        }
    }

    private static void matchersEn(JsonNode nodo, String campoReceptor, List<String> frases) {
        if (!(nodo instanceof ObjectNode o) || o.get("llamada") == null || !o.get("llamada").isTextual()) {
            return;
        }
        String llamada = o.get("llamada").asText();
        String nueva = conEq(llamada);
        if (nueva == null || nueva.equals(llamada)) {
            return;
        }
        o.put("llamada", nueva);
        JsonNode r = o.get(campoReceptor);
        String receptor = r != null && r.isTextual() ? r.asText() : "";
        frases.add("Se envolvieron con `eq(...)` los valores de `" + (receptor.isEmpty() ? "" : receptor + ".") + llamada
                + "`: Mockito no admite mezclar matchers y valores en la misma llamada");
    }

    /** Llamada con los valores envueltos en {@code eq(…)} si mezcla matchers y valores; null si no parsea o no aplica. */
    private static String conEq(String llamada) {
        return NormalizadorMatchers.conEq(llamada);
    }

    // ------------------------------------------------------------- guardia: nunca se debilita el test

    /**
     * true si la propuesta mantiene intactos {@code esperado} y {@code aserciones} y las verificaciones (mismo número, mismo
     * mock, veces y sinInteracciones): los autoarreglos solo tocan stubs y llamadas.
     */
    static boolean sinDebilitar(ObjectNode original, ObjectNode propuesta) {
        if (original == null || propuesta == null) {
            return false;
        }
        for (String campo : List.of("esperado", "aserciones")) {
            if (!Objects.equals(original.get(campo), propuesta.get(campo))) {
                return false;
            }
        }
        JsonNode vo = original.path("verificaciones");
        JsonNode vp = propuesta.path("verificaciones");
        if (vo.size() != vp.size()) {
            return false;
        }
        for (int i = 0; i < vo.size(); i++) {
            for (String campo : List.of("mock", "veces", "sinInteracciones")) {
                if (!Objects.equals(vo.get(i).get(campo), vp.get(i).get(campo))) {
                    return false;
                }
            }
        }
        return true;
    }
}
