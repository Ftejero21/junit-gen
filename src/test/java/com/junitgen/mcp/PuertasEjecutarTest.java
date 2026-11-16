package com.junitgen.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.junitgen.Fixtures;
import com.junitgen.util.Json;
import com.junitgen.util.LimitesRespuesta;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static com.junitgen.mcp.ClienteMcp.primeraLinea;
import static com.junitgen.mcp.ClienteMcp.texto;
import static org.assertj.core.api.Assertions.assertThat;

/** Puertas de ejecutar_tests (nada se ejecuta sin generar en la sesión) y resumenSesion calculado por junit-gen. */
class PuertasEjecutarTest {

    @TempDir
    Path tmp;

    private final List<ClienteMcp> clientes = new ArrayList<>();

    @AfterEach
    void cerrar() throws Exception {
        for (ClienteMcp c : clientes) {
            c.close();
        }
    }

    private ClienteMcp nuevoCliente(String carpeta) throws IOException {
        ClienteMcp c = new ClienteMcp(ClienteMcp.entorno(tmp.resolve(carpeta)));
        clientes.add(c);
        c.iniciar("2025-11-25", "{}");
        return c;
    }

    private Path spring() throws IOException {
        return Fixtures.copiar("proyecto-spring", tmp.resolve("Spring")).toRealPath();
    }

    private static ObjectNode args(Object... kv) {
        ObjectNode a = Json.objeto();
        for (int i = 0; i < kv.length; i += 2) {
            String k = (String) kv[i];
            Object v = kv[i + 1];
            if (v instanceof List<?> l) {
                ArrayNode arr = a.putArray(k);
                l.forEach(x -> arr.add(x.toString()));
            } else if (v instanceof Boolean b) {
                a.put(k, b);
            } else if (v instanceof Integer n) {
                a.put(k, n);
            } else {
                a.put(k, v.toString());
            }
        }
        return a;
    }

    /** Spec con un caso de UsuarioServiceImpl: «activar», «buscar» o una copia de «activar» con otro id (duplicado). */
    private static ObjectNode generar(Path p, String caso) {
        String c = switch (caso) {
            case "activar", "activar-copia" -> "{'id':'" + (caso.equals("activar") ? "activar-ok" : "activar-copia")
                    + "','metodo':'activar','escenario':'activaUsuarioInactivo','datos':[{'tipo':'Usuario',"
                    + "'nombre':'u','valor':'new Usuario(5L, \\'Leo\\')'}],'stubs':[{'mock':'repo','llamada':'findById(5L)',"
                    + "'devuelve':'Optional.of(u)'},{'mock':'repo','llamada':'save(u)','devuelve':'u'}],'argumentos':['5L'],"
                    + "'esperado':{'tipo':'noNulo'}}";
            default -> "{'id':'buscar-no-existe','metodo':'buscar','escenario':'lanzaSiNoExiste','stubs':[{'mock':'repo','llamada':"
                    + "'findById(9L)','devuelve':'Optional.empty()'}],'argumentos':['9L'],'esperado':{'tipo':'excepcion',"
                    + "'excepcion':'IllegalArgumentException','mensaje':'No existe 9'}}";
        };
        ObjectNode g = Json.objeto();
        g.set("spec", Json.leer(Fixtures.j("{'version':1,'proyecto':{'ruta':'" + p.toString().replace('\\', '/') + "'},'servicios':[{'clase':"
                + "'com.ejemplo.service.UsuarioServiceImpl','casos':[" + c + "]}]}")));
        return g;
    }

    static JsonNode cuerpoJson(JsonNode resultado) {
        String t = texto(resultado);
        return Json.leer(t.substring(t.indexOf("\n\n") + 2));
    }

    static JsonNode resumenDeRechazo(JsonNode resultado) {
        String t = texto(resultado);
        return Json.leer(t.substring(t.indexOf("resumenSesion: ") + "resumenSesion: ".length()));
    }

    private static void cabe(JsonNode resultado) {
        assertThat(LimitesRespuesta.bytes(texto(resultado))).as(texto(resultado)).isLessThanOrEqualTo(LimitesRespuesta.MAX_RESPUESTA);
    }

    private static List<String> listado(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return List.of();
        }
        try (Stream<Path> s = Files.walk(dir)) {
            return s.map(x -> dir.relativize(x) + "|" + (Files.isRegularFile(x) ? x.toFile().length() + "|" + x.toFile().lastModified() : "d"))
                    .sorted().toList();
        }
    }

    @Test
    @Timeout(300)
    void conPlanYSinGenerarSeRechazaInclusoConTodosOParcialSinEfectos() throws Exception {
        Path p = spring();
        Path salida = tmp.resolve("salida");
        ClienteMcp c = nuevoCliente("s1");
        c.llamar(2, "escanear_proyecto", args("ruta", p, "carpetaSalida", salida, "servicios", List.of("UsuarioServiceImpl")));
        assertThat(c.resultado(2).get("isError").asBoolean()).isFalse();
        int id = 3;
        for (ObjectNode a : List.of(args(), args("todos", true), args("parcial", true))) {
            c.llamar(id, "ejecutar_tests", a);
            JsonNode r = c.resultado(id++);
            cabe(r);
            assertThat(r.get("isError").asBoolean()).as(texto(r)).isTrue();
            assertThat(texto(r)).contains("ejecutar_tests no ha ejecutado nada", "hay un plan activo", "Pendiente: servicios",
                    "UsuarioServiceImpl", "Llama a detalle_servicio y generar_tests primero").doesNotContain("todos=", "`todos`");
            JsonNode resumen = resumenDeRechazo(r);
            assertThat(resumen.get("casosGenerados").asInt()).isZero();
            assertThat(resumen.get("plan").get("casosGenerados").asInt()).isZero();
        }
        assertThat(salida.resolve(".junit-gen-tmp")).doesNotExist();
        // La decisión de verificación no se fijó: la primera generación sigue «pendiente» (sin elicitation).
        c.llamar(id, "generar_tests", generar(p, "activar"));
        JsonNode g = cuerpoJson(c.resultado(id));
        assertThat(g.get("verificacion").get("decision").asText()).isEqualTo("pendiente");
    }

    @Test
    @Timeout(600)
    void planIncompletoSeRechazaYParcialAutorizaElRestoDeLaSesion() throws Exception {
        Path p = spring();
        Path salida = tmp.resolve("salida");
        ClienteMcp c = nuevoCliente("s1");
        c.llamar(2, "escanear_proyecto", args("ruta", p, "carpetaSalida", salida, "servicios", List.of("UsuarioServiceImpl")));
        c.resultado(2);
        c.llamar(3, "generar_tests", generar(p, "activar"));
        JsonNode g = cuerpoJson(c.resultado(3));
        assertThat(g.get("resumenSesion").get("casosGenerados").asInt()).isEqualTo(1);
        assertThat(g.get("plan").get("serviciosPendientes").toString()).contains("UsuarioServiceImpl: faltan");
        assertThat(g.get("plan").get("instruccion").asText()).contains("sigue con detalle_servicio {clase: \"com.ejemplo.service.UsuarioServiceImpl\"}");

        c.llamar(4, "ejecutar_tests", args());
        JsonNode r = c.resultado(4);
        assertThat(r.get("isError").asBoolean()).isTrue();
        assertThat(texto(r)).contains("el plan no está completo (generados 1 de", "Sigue generando hasta plan.restantes = 0",
                "repite con parcial=true");
        assertThat(salida.resolve(".junit-gen-tmp")).doesNotExist();

        c.llamar(5, "ejecutar_tests", args("parcial", true));
        JsonNode ok = c.resultado(5);
        cabe(ok);
        assertThat(ok.get("isError").asBoolean()).as(texto(ok)).isFalse();
        JsonNode cuerpo = cuerpoJson(ok);
        assertThat(cuerpo.get("resumenSesion").get("casosGenerados").asInt()).isEqualTo(1);
        assertThat(cuerpo.get("resumenSesion").get("instruccion").asText()).contains("Copia estas cifras literalmente");
        for (JsonNode t : cuerpo.path("resultados")) {
            assertThat(t.get("caso").asText()).isEqualTo("activar-ok");
            assertThat(t.get("deEstaSesion").asBoolean()).isTrue();
        }

        c.llamar(6, "ejecutar_tests", args());
        JsonNode otra = c.resultado(6);
        assertThat(otra.get("isError").asBoolean()).as("parcial se recuerda en la sesión: " + texto(otra)).isFalse();
    }

    @Test
    @Timeout(120)
    void sinPlanYSinGenerarSeRechaza() throws Exception {
        Path p = spring();
        ClienteMcp c = nuevoCliente("s1");
        c.llamar(2, "escanear_proyecto", args("ruta", p, "carpetaSalida", tmp.resolve("salida")));
        c.resultado(2);
        c.llamar(3, "ejecutar_tests", args());
        JsonNode r = c.resultado(3);
        assertThat(r.get("isError").asBoolean()).isTrue();
        assertThat(texto(r)).contains("en esta sesión no se ha generado ningún test", "son de sesiones anteriores y no cuentan")
                .doesNotContain("todos=", "`todos`");
        assertThat(resumenDeRechazo(r).has("plan")).isFalse();
        assertThat(tmp.resolve("salida").resolve(".junit-gen-tmp")).doesNotExist();
    }

    @Test
    @Timeout(120)
    void carpetaTestsDistintaSeRechazaSinCambiarLaSalidaNiTocarLaOtraCarpeta() throws Exception {
        Path p = spring();
        Path salida = Files.createDirectories(tmp.resolve("SalidaSesion")).toRealPath();
        Path otra = Files.createDirectories(tmp.resolve("MiProyecto-tests"));
        Fixtures.escribir(otra.resolve("src/test/java/com/x/ViejoTest.java"), "class ViejoTest {}\n");
        List<String> antes = listado(otra);
        ClienteMcp c = nuevoCliente("s1");
        c.llamar(2, "escanear_proyecto", args("ruta", p, "carpetaSalida", salida));
        c.resultado(2);
        c.llamar(3, "ejecutar_tests", args("carpetaTests", otra, "rutaProyecto", p));
        JsonNode r = c.resultado(3);
        assertThat(r.get("isError").asBoolean()).isTrue();
        assertThat(texto(r)).contains("la carpeta de salida de esta sesión es " + salida, "Repite la llamada sin `carpetaTests`");
        assertThat(primeraLinea(r)).contains(salida.toString()).doesNotContain(otra.toString());
        assertThat(listado(otra)).isEqualTo(antes);

        // La salida de la sesión no cambió; la misma carpeta con otra grafía pasa la puerta de carpeta.
        String otraGrafia = salida.toString().toUpperCase(Locale.ROOT).equals(salida.toString()) ? salida + "\\." : salida.toString().toUpperCase(Locale.ROOT);
        boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
        c.llamar(4, "ejecutar_tests", args("carpetaTests", windows ? otraGrafia : salida.toString()));
        JsonNode r2 = c.resultado(4);
        assertThat(r2.get("isError").asBoolean()).isTrue();
        assertThat(texto(r2)).contains("en esta sesión no se ha generado ningún test").doesNotContain("la carpeta de salida de esta sesión es");
        assertThat(resumenDeRechazo(r2).get("carpeta").asText()).isEqualTo(salida.toString());
        assertThat(listado(otra)).isEqualTo(antes);
    }

    @Test
    @Timeout(900)
    void losTestsDeSesionesAnterioresNoCuentan() throws Exception {
        Path p = spring();
        Path salida = tmp.resolve("salida");
        ClienteMcp vieja = nuevoCliente("vieja");
        vieja.llamar(2, "escanear_proyecto", args("ruta", p, "carpetaSalida", salida));
        vieja.resultado(2);
        vieja.llamar(3, "generar_tests", generar(p, "buscar"));
        assertThat(cuerpoJson(vieja.resultado(3)).get("casosGeneradosIds").toString()).contains("buscar-no-existe");

        ClienteMcp c = nuevoCliente("nueva");
        c.llamar(2, "escanear_proyecto", args("ruta", p, "carpetaSalida", salida));
        c.resultado(2);
        c.llamar(3, "generar_tests", generar(p, "activar"));
        JsonNode g = cuerpoJson(c.resultado(3));
        assertThat(g.get("resumenSesion").get("casosGenerados").asInt()).isEqualTo(1);
        assertThat(g.has("generados")).isFalse();
        assertThat(g.has("perfiles")).isFalse();

        c.llamar(4, "ejecutar_tests", args());
        JsonNode r = c.resultado(4);
        cabe(r);
        assertThat(r.get("isError").asBoolean()).as(texto(r)).isFalse();
        JsonNode cuerpo = cuerpoJson(r);
        List<String> casos = new ArrayList<>();
        cuerpo.path("resultados").forEach(t -> casos.add(t.get("caso").asText()));
        assertThat(casos).containsExactly("activar-ok");
        assertThat(cuerpo.get("resumenSesion").get("casosGenerados").asInt()).isEqualTo(1);
        assertThat(cuerpo.get("resumenSesion").get("ejecutados").asInt()).isEqualTo(1);

        c.llamar(5, "ejecutar_tests", args("todos", true));
        JsonNode t = c.resultado(5);
        cabe(t);
        JsonNode todos = cuerpoJson(t);
        boolean hayAntiguo = false;
        for (JsonNode x : todos.path("resultados")) {
            if (x.get("caso").asText().equals("buscar-no-existe")) {
                hayAntiguo = true;
                assertThat(x.get("deEstaSesion").asBoolean()).isFalse();
            } else {
                assertThat(x.get("deEstaSesion").asBoolean()).isTrue();
            }
        }
        assertThat(hayAntiguo).as(todos.toString()).isTrue();
        assertThat(todos.get("resultadosDeOtrasSesiones").asInt()).isEqualTo(1);
        assertThat(todos.get("resumen").asText()).contains("de sesiones anteriores no contados", "usa resumenSesion");
        assertThat(todos.get("resumenSesion").get("casosGenerados").asInt()).isEqualTo(1);
        assertThat(todos.get("resumenSesion").get("ejecutados").asInt()).isEqualTo(1);

        // paginaResultados no vuelve a ejecutar: devuelve la página de la última ejecución.
        c.llamar(6, "ejecutar_tests", args("paginaResultados", 1));
        JsonNode pag = cuerpoJson(c.resultado(6));
        assertThat(pag.get("paginacion").get("pagina").asInt()).isEqualTo(1);
        assertThat(pag.get("resultados").size()).isEqualTo(todos.get("resultados").size());

        // casos con ids que no son de esta sesión: error que los nombra.
        c.llamar(7, "ejecutar_tests", args("casos", List.of("buscar-no-existe")));
        JsonNode ajeno = c.resultado(7);
        assertThat(ajeno.get("isError").asBoolean()).isTrue();
        assertThat(texto(ajeno)).contains("[buscar-no-existe]", "no se han generado en esta sesión");
    }

    @Test
    @Timeout(300)
    void generarDevuelveResumenSesionConDuplicadosYServiciosDelPlan() throws Exception {
        Path p = spring();
        ClienteMcp c = nuevoCliente("s1");
        c.llamar(2, "escanear_proyecto", args("ruta", p, "carpetaSalida", tmp.resolve("salida"), "servicios",
                List.of("UsuarioServiceImpl")));
        c.resultado(2);
        c.llamar(3, "generar_tests", generar(p, "activar"));
        c.resultado(3);
        c.llamar(4, "generar_tests", generar(p, "activar-copia"));
        JsonNode r = c.resultado(4);
        cabe(r);
        JsonNode g = cuerpoJson(r);
        JsonNode resumen = g.get("resumenSesion");
        assertThat(resumen.get("casosGenerados").asInt()).isEqualTo(1);
        assertThat(resumen.get("descartadosPorDuplicado").asInt()).isEqualTo(1);
        assertThat(resumen.get("plan").get("serviciosTotal").asInt()).isEqualTo(1);
        assertThat(resumen.get("plan").get("serviciosPendientes").toString()).contains("UsuarioServiceImpl");
        assertThat(resumen.get("plan").get("serviciosCompletados").size()).isZero();
        List<String> claves = new ArrayList<>();
        resumen.fieldNames().forEachRemaining(claves::add);
        assertThat(claves).containsExactly("carpeta", "casosGenerados", "ejecutados", "pasan", "fallosAsercion", "erroresMontaje",
                "noCompilan", "desconocidos", "sinEjecutar", "descartadosPorDuplicado", "eliminados", "plan", "instruccion");
        assertThat(g.get("descartados").get(0).get("caso").asText()).isEqualTo("activar-copia");
    }

    @Test
    @Timeout(300)
    void seccionNoReiniciaElProgresoDelPlan() throws Exception {
        Path p = spring();
        ClienteMcp c = nuevoCliente("s1");
        c.llamar(2, "escanear_proyecto", args("ruta", p, "carpetaSalida", tmp.resolve("salida"), "servicios",
                List.of("UsuarioServiceImpl")));
        c.resultado(2);
        c.llamar(3, "generar_tests", generar(p, "activar"));
        int generados = cuerpoJson(c.resultado(3)).get("plan").get("generados").asInt();
        assertThat(generados).isEqualTo(1);
        c.llamar(4, "escanear_proyecto", args("seccion", "plan", "numServicios", 5, "servicios", List.of("PedidoService")));
        String plan = texto(c.resultado(4));
        assertThat(plan).contains("progreso de la sesión: 1 de", "- activar ×").doesNotContain("PedidoService");
        c.llamar(5, "escanear_proyecto", args("seccion", "servicios"));
        assertThat(texto(c.resultado(5))).contains("## Servicios detectados", "Página 1 de");
        c.llamar(6, "ejecutar_tests", args());
        assertThat(texto(c.resultado(6))).contains("el plan no está completo (generados 1 de");
    }

    @Test
    void laRedDeSeguridadRecortaCualquierRespuestaYLoDeclara() {
        String texto = RegistroHerramientas.componer("prueba", "Modo solo lectura sobre X.", List.of("n".repeat(2_000), "otra nota"),
                "cuerpo ñ ".repeat(5_000));
        assertThat(LimitesRespuesta.bytes(texto)).isLessThanOrEqualTo(LimitesRespuesta.MAX_RESPUESTA);
        assertThat(texto).startsWith("Modo solo lectura sobre X.\nNota: ").endsWith(LimitesRespuesta.AVISO_RED);
        String cabecera = texto.substring(0, texto.indexOf("\n\n"));
        assertThat(LimitesRespuesta.bytes(cabecera)).isLessThanOrEqualTo(LimitesRespuesta.MAX_CABECERA);
        assertThat(RegistroHerramientas.componer("prueba", "aviso", List.of(), "corto")).isEqualTo("aviso\n\ncorto");
    }
}
