package com.junitgen.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.junitgen.Fachada;
import com.junitgen.util.Json;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cliente MCP de prueba: servidor completo sobre pipes en memoria con un entorno hermético (directorio
 * de trabajo, carpeta del usuario, instalación y Escritorio inyectados).
 */
final class ClienteMcp implements AutoCloseable {

    private final PipedOutputStream haciaServidor = new PipedOutputStream();
    private final BufferedReader desdeServidor;
    private final Thread hilo;

    ClienteMcp(EntornoSesion entorno) throws IOException {
        PipedInputStream entrada = new PipedInputStream(haciaServidor, 1 << 20);
        PipedOutputStream salida = new PipedOutputStream();
        PipedInputStream lectura = new PipedInputStream(salida, 1 << 22);
        desdeServidor = new BufferedReader(new InputStreamReader(lectura, StandardCharsets.UTF_8));
        ServidorMcp servidor = new ServidorMcp(new TransporteStdio(entrada, salida), new Fachada(), entorno);
        hilo = new Thread(() -> {
            try {
                servidor.ejecutar();
            } catch (IOException e) {
                System.err.println("Servidor de prueba: " + e.getMessage());
            }
        }, "servidor-prueba");
        hilo.setDaemon(true);
        hilo.start();
    }

    /**
     * Entorno hermético dentro de {@code base}: cwd vacío (sin módulos), home, instalación y Escritorio
     * existentes y sin variables de entorno.
     */
    static EntornoSesion entorno(Path base) throws IOException {
        return EntornoSesion.para(Files.createDirectories(base.resolve("cwd")), Files.createDirectories(base.resolve("home")),
                Files.createDirectories(base.resolve("instalacion")), Files.createDirectories(base.resolve("Escritorio")), k -> null);
    }

    void enviar(String json) throws IOException {
        haciaServidor.write((json + "\n").getBytes(StandardCharsets.UTF_8));
        haciaServidor.flush();
    }

    void enviar(JsonNode n) throws IOException {
        enviar(Json.compacto(n));
    }

    JsonNode leer() throws IOException {
        String l = desdeServidor.readLine();
        assertThat(l).as("el servidor cerró la salida").isNotNull();
        return Json.leer(l);
    }

    void iniciar(String protocolo, String capacidades) throws IOException {
        enviar("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"" + protocolo
                + "\",\"capabilities\":" + capacidades + ",\"clientInfo\":{\"name\":\"t\",\"version\":\"1\"}}}");
        assertThat(leer().path("id").asInt()).isEqualTo(1);
        enviar("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
    }

    void llamar(int id, String herramienta, ObjectNode argumentos) throws IOException {
        ObjectNode m = Json.objeto();
        m.put("jsonrpc", "2.0");
        m.put("id", id);
        m.put("method", "tools/call");
        ObjectNode p = m.putObject("params");
        p.put("name", herramienta);
        p.set("arguments", argumentos == null ? Json.objeto() : argumentos);
        enviar(m);
    }

    /** Lee la siguiente petición servidor→cliente y comprueba su método. */
    JsonNode peticion(String metodo) throws IOException {
        JsonNode n = leer();
        assertThat(n.path("method").asText()).as("mensaje recibido: " + n).isEqualTo(metodo);
        assertThat(n.path("id").asText()).startsWith("srv-");
        return n;
    }

    void responder(JsonNode peticion, JsonNode resultado) throws IOException {
        ObjectNode r = Json.objeto();
        r.put("jsonrpc", "2.0");
        r.set("id", peticion.get("id"));
        r.set("result", resultado);
        enviar(r);
    }

    /** Lee el resultado de una llamada (falla si antes llega otra petición servidor→cliente). */
    JsonNode resultado(int id) throws IOException {
        JsonNode n = leer();
        assertThat(n.path("id").asText()).as("se esperaba el resultado " + id + " y llegó: " + n).isEqualTo(String.valueOf(id));
        return n.get("result");
    }

    void responderRaices(JsonNode peticion, Path... rutas) throws IOException {
        ObjectNode res = Json.objeto();
        ArrayNode roots = res.putArray("roots");
        for (Path p : rutas) {
            roots.addObject().put("uri", p.toUri().toString()).put("name", String.valueOf(p.getFileName()));
        }
        responder(peticion, res);
    }

    void aceptar(JsonNode peticion, Map<String, String> contenido) throws IOException {
        ObjectNode res = Json.objeto();
        res.put("action", "accept");
        ObjectNode c = res.putObject("content");
        contenido.forEach(c::put);
        responder(peticion, res);
    }

    void rechazar(JsonNode peticion) throws IOException {
        ObjectNode res = Json.objeto();
        res.put("action", "decline");
        responder(peticion, res);
    }

    static String texto(JsonNode resultado) {
        return resultado.get("content").get(0).get("text").asText();
    }

    static String primeraLinea(JsonNode resultado) {
        return texto(resultado).lines().findFirst().orElse("");
    }

    /** Etiquetas predefinidas del enum 'carpeta' de una petición elicitation/create (sin «Otra ruta…»). */
    static java.util.List<String> etiquetas(JsonNode peticion) {
        java.util.List<String> r = new java.util.ArrayList<>(enumCarpeta(peticion));
        r.remove(Elicitacion.OTRA_RUTA);
        return r;
    }

    /** Enum completo de la propiedad 'carpeta' (etiquetas + «Otra ruta…»). */
    static java.util.List<String> enumCarpeta(JsonNode peticion) {
        java.util.List<String> r = new java.util.ArrayList<>();
        JsonNode en = peticion.get("params").get("requestedSchema").get("properties").path("carpeta").path("enum");
        en.forEach(x -> r.add(x.asText()));
        return r;
    }

    /** Nombres de las propiedades del esquema de una petición elicitation/create. */
    static java.util.List<String> propiedades(JsonNode peticion) {
        java.util.List<String> r = new java.util.ArrayList<>();
        peticion.get("params").get("requestedSchema").get("properties").fieldNames().forEachRemaining(r::add);
        return r;
    }

    /**
     * Elige «Otra ruta…» en la primera pantalla y devuelve la segunda (comprueba que tiene una única
     * propiedad de texto 'ruta', obligatoria).
     */
    JsonNode elegirOtraRuta(JsonNode peticion) throws IOException {
        aceptar(peticion, Map.of(Elicitacion.CAMPO_CARPETA, Elicitacion.OTRA_RUTA));
        JsonNode texto = peticion("elicitation/create");
        assertThat(propiedades(texto)).containsExactly(Elicitacion.CAMPO_RUTA);
        assertThat(texto.get("params").get("requestedSchema").get("required").toString()).isEqualTo("[\"ruta\"]");
        return texto;
    }

    /** Elige «Otra ruta…» y escribe la ruta en la segunda pantalla. */
    void escribirOtraRuta(JsonNode peticion, String ruta) throws IOException {
        aceptar(elegirOtraRuta(peticion), Map.of(Elicitacion.CAMPO_RUTA, ruta));
    }

    @Override
    public void close() throws Exception {
        haciaServidor.close();
        hilo.join(30_000);
    }
}
