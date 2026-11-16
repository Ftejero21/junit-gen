package com.junitgen.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.junitgen.Fachada;
import com.junitgen.Fixtures;
import com.junitgen.util.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class ServidorMcpTest {

    @TempDir
    Path tmp;

    private Map<String, JsonNode> ejecutar(String... lineas) throws Exception {
        String entrada = "\uFEFF" + String.join("\n", lineas) + "\n";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        TransporteStdio t = new TransporteStdio(new ByteArrayInputStream(entrada.getBytes(StandardCharsets.UTF_8)), out);
        new ServidorMcp(t, new Fachada(), ClienteMcp.entorno(tmp)).ejecutar();
        Map<String, JsonNode> porId = new LinkedHashMap<>();
        for (String l : out.toString(StandardCharsets.UTF_8).split("\n")) {
            if (l.isBlank()) {
                continue;
            }
            JsonNode n = Json.leer(l);
            assertThat(n.get("jsonrpc").asText()).isEqualTo("2.0");
            porId.put(n.path("id").asText(), n);
        }
        return porId;
    }

    @Test
    @Timeout(120)
    void handshakeHerramientasYPrompt() throws Exception {
        Map<String, JsonNode> r = ejecutar(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},\"clientInfo\":{\"name\":\"t\",\"version\":\"1\"}}}",
                "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}",
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}",
                "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"prompts/list\"}",
                "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"prompts/get\",\"params\":{\"name\":\"GenerarTest\",\"arguments\":{\"rutaProyecto\":\"C:/p\",\"carpetaSalida\":\"C:/s\"}}}",
                "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"metodo/inexistente\"}",
                "{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"ping\"}",
                "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"prompts/get\",\"params\":{\"name\":\"GenerarTest\"}}",
                "{\"jsonrpc\":\"2.0\",\"id\":8,\"method\":\"prompts/get\",\"params\":{\"name\":\"GenerarTest\",\"arguments\":{\"rutaProyecto\":\" \",\"carpetaSalida\":\"\"}}}",
                "esto no es json");
        JsonNode init = r.get("1").get("result");
        assertThat(init.get("protocolVersion").asText()).isEqualTo("2025-06-18");
        assertThat(init.path("capabilities").has("tools")).isTrue();
        assertThat(init.path("capabilities").has("prompts")).isTrue();
        assertThat(init.path("serverInfo").path("name").asText()).isEqualTo("junit-gen");
        assertThat(init.path("instructions").asText()).contains("solo lectura", "GenerarTest");
        List<String> nombres = new ArrayList<>();
        r.get("2").get("result").get("tools").forEach(t -> nombres.add(t.get("name").asText()));
        assertThat(nombres).containsExactly("escanear_proyecto", "detalle_servicio", "validar_spec", "generar_tests", "ejecutar_tests",
                "resolver_fallo", "registrar_analisis_ia", "cambiar_ruta_salida");
        r.get("2").get("result").get("tools").forEach(t -> {
            assertThat(t.path("inputSchema").path("type").asText()).isEqualTo("object");
            JsonNode a = t.get("annotations");
            if (List.of("escanear_proyecto", "detalle_servicio", "validar_spec").contains(t.get("name").asText())) {
                assertThat(a.get("readOnlyHint").asBoolean()).isTrue();
            } else {
                assertThat(a.get("destructiveHint").asBoolean()).isFalse();
            }
        });
        JsonNode prompt = r.get("3").get("result").get("prompts").get(0);
        assertThat(prompt.get("name").asText()).isEqualTo("GenerarTest");
        assertThat(prompt.get("title").asText()).isEqualTo("Generar Test");
        prompt.get("arguments").forEach(a -> assertThat(a.get("required").asBoolean()).as(a.get("name").asText()).isFalse());
        String texto = r.get("4").get("result").get("messages").get(0).get("content").get("text").asText();
        assertThat(texto).contains("No puedes editar archivos del proyecto analizado", "No hagas ninguna operación de git",
                "Mover los tests al proyecto es siempre un paso manual del usuario", "No escribas archivos `.java` de test",
                "C:/p", "C:/s", "\"servicios\"");
        for (String id : List.of("7", "8")) {
            assertThat(r.get(id).has("error")).as("prompts/get sin rutas (" + id + ")").isFalse();
            String sinRutas = r.get(id).get("result").get("messages").get(0).get("content").get("text").asText();
            assertThat(sinRutas).contains("la raíz del workspace/carpeta de trabajo que ya conoces",
                    "`ruta` = la raíz del workspace que ya conoces, sin preguntar al usuario", "sin `carpetaSalida`",
                    "¿Dónde exportamos los tests?", "No hace falta repetir rutas", "No puedes editar archivos del proyecto analizado",
                    "**una sola vez** a `ejecutar_tests`", "Establecer como ruta por defecto", "cambiar_ruta_salida", "`numServicios`", "Otra ruta…",
                    "Revisar con IA", "registrar_analisis_ia")
                    .doesNotContain("{{", "`servicios` = [");
        }
        JsonNode conServicios = ejecutar(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{}}}",
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"prompts/get\",\"params\":{\"name\":\"GenerarTest\",\"arguments\":{\"servicios\":\"UsuarioService, `PedidoService`\"}}}")
                .get("2");
        assertThat(conServicios.get("result").get("messages").get(0).get("content").get("text").asText())
                .contains("`servicios` = [\"UsuarioService\", \"PedidoService\"]", "Servicios pedidos: UsuarioService, PedidoService");
        assertThat(r.get("7").get("result").get("description").asText()).isEqualTo("Flujo de junit-gen");
        assertThat(r.get("5").get("error").get("code").asInt()).isEqualTo(-32601);
        assertThat(r.get("6").has("result")).isTrue();
        assertThat(r.values()).anyMatch(n -> n.has("error") && n.get("error").get("code").asInt() == -32700);
    }

    @Test
    @Timeout(120)
    void versionNoSoportadaYAvisoEnPrimeraLinea() throws Exception {
        Path p = Fixtures.copiar("proyecto-spring", tmp.resolve("p"));
        String ruta = p.toString().replace("\\", "\\\\");
        Map<String, JsonNode> r = ejecutar(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"1999-01-01\",\"capabilities\":{}}}",
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"escanear_proyecto\",\"arguments\":{\"ruta\":\"" + ruta + "\"}}}",
                "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"detalle_servicio\",\"arguments\":{\"clase\":\"com.ejemplo.service.UsuarioServiceImpl\"}}}",
                "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\",\"params\":{\"name\":\"escanear_proyecto\",\"arguments\":{\"ruta\":\"" + ruta
                        + "\",\"carpetaSalida\":\"" + ruta + "\\\\dentro\"}}}");
        assertThat(r.get("1").get("result").get("protocolVersion").asText()).isEqualTo("2025-06-18");
        String escaneo = r.get("2").get("result").get("content").get(0).get("text").asText();
        assertThat(escaneo.lines().findFirst().orElse("")).startsWith("Modo solo lectura sobre").endsWith("Esta operación no crea archivos.");
        assertThat(escaneo).contains("UsuarioServiceImpl");
        assertThat(r.get("2").get("result").get("isError").asBoolean()).isFalse();
        String detalle = r.get("3").get("result").get("content").get(0).get("text").asText();
        String json = detalle.substring(detalle.indexOf('{'));
        assertThat(Json.leer(json).get("dependencias").size()).isEqualTo(2);
        assertThat(r.get("4").get("result").get("isError").asBoolean()).isTrue();
        assertThat(r.get("4").get("result").get("content").get(0).get("text").asText()).contains("Rechazo de seguridad");
    }

    @Test
    @Timeout(60)
    void elicitationConStreamsEnMemoria() throws Exception {
        PipedInputStream lecturaServidor = new PipedInputStream(1 << 16);
        PipedOutputStream salidaServidor = new PipedOutputStream(lecturaServidor);
        TransporteStdio t = new TransporteStdio(new ByteArrayInputStream(new byte[0]), salidaServidor);
        SesionMcp s = new SesionMcp(t, new Fachada(), ClienteMcp.entorno(tmp));
        s.inicializar("2025-06-18", Json.leer("{\"elicitation\":{}}"));
        CompletableFuture<String> respuesta = CompletableFuture.supplyAsync(() ->
                Elicitacion.preguntar(s, "¿Probar?", List.of("Probar tests", "Descartar"), Duration.ofSeconds(30)));
        BufferedReader br = new BufferedReader(new InputStreamReader(lecturaServidor, StandardCharsets.UTF_8));
        JsonNode peticion = Json.leer(br.readLine());
        assertThat(peticion.get("method").asText()).isEqualTo("elicitation/create");
        assertThat(peticion.get("id").asText()).startsWith("srv-");
        JsonNode schema = peticion.get("params").get("requestedSchema");
        assertThat(schema.get("properties").get("accion").get("enum").get(0).asText()).isEqualTo("Probar tests");
        assertThat(schema.get("required").get(0).asText()).isEqualTo("accion");
        s.completar(Json.leer("{\"jsonrpc\":\"2.0\",\"id\":\"" + peticion.get("id").asText()
                + "\",\"result\":{\"action\":\"accept\",\"content\":{\"accion\":\"Probar tests\"}}}"));
        assertThat(respuesta.get(10, TimeUnit.SECONDS)).isEqualTo("Probar tests");

        CompletableFuture<String> rechazo = CompletableFuture.supplyAsync(() ->
                Elicitacion.preguntar(s, "¿Probar?", List.of("Probar tests", "Descartar"), Duration.ofSeconds(30)));
        JsonNode p2 = Json.leer(br.readLine());
        s.completar(Json.leer("{\"jsonrpc\":\"2.0\",\"id\":\"" + p2.get("id").asText() + "\",\"result\":{\"action\":\"decline\"}}"));
        assertThat(rechazo.get(10, TimeUnit.SECONDS)).isEqualTo(Elicitacion.DECLINE);

        SesionMcp sinSoporte = new SesionMcp(t, new Fachada(), ClienteMcp.entorno(tmp));
        sinSoporte.inicializar("2025-06-18", Json.leer("{}"));
        assertThat(Elicitacion.preguntar(sinSoporte, "x", List.of("a"), Duration.ofSeconds(1))).isNull();
    }
}
