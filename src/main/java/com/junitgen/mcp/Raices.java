package com.junitgen.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.junitgen.seguridad.RutaSegura;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Carpetas abiertas en el IDE según MCP roots (roots/list servidor→cliente). Las URIs son entrada no
 * confiable: solo se aceptan file:// y el resultado se valida después con RutaSegura.
 */
public final class Raices {

    public static final Duration TIMEOUT = Duration.ofSeconds(30);

    public static final String SIN_RAICES = "No se pudo deducir el proyecto: no se indicó 'ruta', el cliente no informa de ninguna "
            + "carpeta abierta (MCP roots) y el directorio de trabajo del servidor no es un proyecto Maven/Gradle válido. Pasa en "
            + "'ruta' la carpeta de trabajo/workspace que ya conoces (su raíz, nunca un módulo o subcarpeta) sin preguntar al "
            + "usuario; pregúntale solo si no conoces ninguna.";

    public record Raiz(String uri, String nombre, Path ruta) {
    }

    private Raices() {
    }

    /**
     * Convierte una URI file:// en ruta del sistema: percent-decoding UTF-8 por bytes ('+' es literal),
     * letra de unidad en mayúscula, autoridad vacía o localhost = local, otra autoridad = UNC.
     */
    public static String rutaDeUri(String uri) {
        if (uri == null || uri.isBlank()) {
            throw new IllegalArgumentException("URI vacía.");
        }
        String u = uri.trim();
        int dosPuntos = u.indexOf(':');
        if (dosPuntos <= 0 || !"file".equalsIgnoreCase(u.substring(0, dosPuntos))) {
            throw new IllegalArgumentException("Solo se admiten URIs file://: " + uri);
        }
        String resto = u.substring(dosPuntos + 1);
        int corte = indiceCorte(resto);
        if (corte >= 0) {
            resto = resto.substring(0, corte);
        }
        String autoridad = "";
        String ruta;
        if (resto.startsWith("//")) {
            int barra = resto.indexOf('/', 2);
            autoridad = barra < 0 ? resto.substring(2) : resto.substring(2, barra);
            ruta = barra < 0 ? "" : resto.substring(barra);
        } else {
            ruta = resto;
        }
        autoridad = decodificar(autoridad);
        ruta = decodificar(ruta);
        boolean local = autoridad.isEmpty() || "localhost".equalsIgnoreCase(autoridad);
        String sep = RutaSegura.WINDOWS ? "\\" : "/";
        String r;
        if (local) {
            if (ruta.isEmpty()) {
                throw new IllegalArgumentException("URI sin ruta: " + uri);
            }
            if (ruta.length() >= 3 && ruta.charAt(0) == '/' && Character.isLetter(ruta.charAt(1)) && ruta.charAt(2) == ':'
                    && (ruta.length() == 3 || ruta.charAt(3) == '/' || ruta.charAt(3) == '\\')) {
                ruta = Character.toUpperCase(ruta.charAt(1)) + ruta.substring(2);
                if (ruta.length() == 2) {
                    ruta = ruta + "/";
                }
            }
            r = RutaSegura.WINDOWS ? ruta.replace('/', '\\') : ruta;
        } else {
            String cuerpo = RutaSegura.WINDOWS ? ruta.replace('/', '\\') : ruta;
            r = sep + sep + autoridad + cuerpo;
        }
        return quitarSeparadorFinal(r, sep);
    }

    private static int indiceCorte(String s) {
        int q = s.indexOf('?');
        int h = s.indexOf('#');
        if (q < 0) {
            return h;
        }
        return h < 0 ? q : Math.min(q, h);
    }

    private static String quitarSeparadorFinal(String r, String sep) {
        String s = r;
        while (s.length() > 1 && s.endsWith(sep) && !(s.length() == 3 && s.charAt(1) == ':')) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    private static String decodificar(String s) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '%') {
                if (i + 2 >= s.length()) {
                    throw new IllegalArgumentException("Secuencia % incompleta en la URI: " + s);
                }
                int alto = Character.digit(s.charAt(i + 1), 16);
                int bajo = Character.digit(s.charAt(i + 2), 16);
                if (alto < 0 || bajo < 0) {
                    throw new IllegalArgumentException("Secuencia % no válida en la URI: " + s);
                }
                bytes.write((alto << 4) | bajo);
                i += 3;
            } else {
                int cp = s.codePointAt(i);
                byte[] b = new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8);
                bytes.write(b, 0, b.length);
                i += Character.charCount(cp);
            }
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }

    /** Raíces file:// del cliente (caché de sesión). Vacía sin capability, con error o con timeout. */
    public static List<Raiz> listar(SesionMcp sesion) {
        if (!sesion.soportaRaices()) {
            return List.of();
        }
        List<Raiz> cache = sesion.raicesCache();
        if (cache != null) {
            return cache;
        }
        long generacion = sesion.generacionRaices();
        JsonNode r;
        try {
            r = sesion.solicitar("roots/list", null).get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            System.err.println("roots/list sin respuesta en " + TIMEOUT.toSeconds() + " s.");
            return List.of();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        } catch (ExecutionException | IOException e) {
            System.err.println("roots/list fallida: " + e.getMessage());
            return List.of();
        }
        if (r == null || r.has("error") || !r.has("result")) {
            System.err.println("roots/list rechazada por el cliente: " + (r == null ? "sin respuesta" : r.get("error")));
            return List.of();
        }
        Map<String, Raiz> unicas = new LinkedHashMap<>();
        for (JsonNode n : r.get("result").path("roots")) {
            String uri = n.path("uri").asText(null);
            if (uri == null) {
                continue;
            }
            try {
                Path ruta = RutaSegura.ruta(rutaDeUri(uri));
                String clave = RutaSegura.WINDOWS ? ruta.toString().toLowerCase(Locale.ROOT) : ruta.toString();
                unicas.putIfAbsent(clave, new Raiz(uri, n.path("name").asText(null), ruta));
            } catch (RuntimeException e) {
                System.err.println("Raíz ignorada (" + uri + "): " + e.getMessage());
            }
        }
        List<Raiz> lista = List.copyOf(unicas.values());
        sesion.guardarRaices(lista, generacion);
        return lista;
    }

    /** Proyecto a partir de las raíces: una → esa; varias → error con la lista; ninguna → error pidiendo la ruta. */
    public static Path proyectoUnico(SesionMcp sesion) {
        return proyectoUnico(listar(sesion));
    }

    static Path proyectoUnico(List<Raiz> raices) {
        if (raices.size() == 1) {
            return raices.get(0).ruta();
        }
        if (raices.isEmpty()) {
            throw new IllegalArgumentException(SIN_RAICES);
        }
        StringBuilder sb = new StringBuilder("Hay varias carpetas abiertas en el IDE; pregunta al usuario cuál analizar y pásala en 'ruta':");
        List<String> rutas = new ArrayList<>();
        raices.forEach(x -> rutas.add(x.ruta().toString()));
        for (String s : rutas) {
            sb.append("\n- ").append(s);
        }
        throw new IllegalArgumentException(sb.toString());
    }
}
