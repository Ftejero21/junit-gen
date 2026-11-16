package com.junitgen.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.junitgen.Fachada;
import com.junitgen.Fixtures;
import com.junitgen.util.Json;
import com.junitgen.verificacion.ClasificadorFallos;
import com.junitgen.verificacion.ResultadoTest;
import com.junitgen.verificacion.ResultadoVerificacion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.junitgen.mcp.ClienteMcp.primeraLinea;
import static com.junitgen.mcp.ClienteMcp.texto;
import static org.assertj.core.api.Assertions.assertThat;

/** Proyecto deducido del directorio de trabajo, módulo por servicios y decisión única de verificación. */
class FlujoSesionTest {

    @TempDir
    Path tmp;

    private ClienteMcp cliente;

    @AfterEach
    void cerrar() throws Exception {
        if (cliente != null) {
            cliente.close();
        }
    }

    private Path spring() throws IOException {
        return Fixtures.copiar("proyecto-spring", tmp.resolve("Spring")).toRealPath();
    }

    private Path multi() throws IOException {
        return Fixtures.copiar("multi", tmp.resolve("Multi")).toRealPath();
    }

    private EntornoSesion entorno(Path cwd, Path home, Path instalacion, Function<String, String> env) throws IOException {
        return EntornoSesion.para(cwd, home, instalacion, Files.createDirectories(tmp.resolve("Escritorio")), env);
    }

    private Path dir(String nombre) throws IOException {
        return Files.createDirectories(tmp.resolve(nombre)).toRealPath();
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
            } else {
                a.put(k, v.toString());
            }
        }
        return a;
    }

    // ------------------------------------------------------------- directorio de trabajo

    @Test
    @Timeout(120)
    void cwdValidoSeUsaSinRutaConNota() throws Exception {
        Path p = spring();
        cliente = new ClienteMcp(entorno(p, dir("home"), dir("instalacion"), k -> null));
        cliente.iniciar("2025-11-25", "{}");
        cliente.llamar(2, "escanear_proyecto", null);
        JsonNode r = cliente.resultado(2);
        assertThat(r.get("isError").asBoolean()).as(texto(r)).isFalse();
        assertThat(primeraLinea(r)).isEqualTo("Modo solo lectura sobre " + p + ". Esta operación no crea archivos.");
        assertThat(texto(r)).contains("\nNota: Proyecto deducido del directorio de trabajo del servidor: " + p + "\n", "UsuarioServiceImpl");
    }

    @Test
    void cwdExcluidoHomeInstalacionSistemaRaizYSinModulos() throws Exception {
        Path p = spring();
        Path home = dir("home");
        Path inst = dir("instalacion");
        Function<String, String> sinEnv = k -> null;
        assertThat(RutasSesion.cwdValido(entorno(p, home, inst, sinEnv))).isEqualTo(p);
        assertThat(RutasSesion.cwdValido(entorno(p, p, inst, sinEnv))).as("igual al home").isNull();
        assertThat(RutasSesion.cwdValido(entorno(p, p.resolve("usuario"), inst, sinEnv))).as("ancestro del home").isNull();
        assertThat(RutasSesion.cwdValido(entorno(p, home, p, sinEnv))).as("igual a la instalación").isNull();
        assertThat(RutasSesion.cwdValido(entorno(p, home, p.resolve("inst"), sinEnv))).as("contiene la instalación").isNull();
        assertThat(RutasSesion.cwdValido(entorno(p, home, p.getParent(), sinEnv))).as("dentro de la instalación").isNull();
        assertThat(RutasSesion.cwdValido(entorno(p, home, inst, k -> "LOCALAPPDATA".equals(k) ? tmp.toString() : null)))
                .as("dentro de una carpeta del sistema").isNull();
        assertThat(RutasSesion.cwdValido(entorno(dir("vacio"), home, inst, sinEnv))).as("sin módulos").isNull();
        assertThat(RutasSesion.cwdValido(entorno(p.getRoot(), home, inst, sinEnv))).as("raíz de unidad").isNull();
        assertThat(RutasSesion.cwdValido(entorno(null, home, inst, sinEnv))).isNull();
    }

    @Test
    @Timeout(120)
    void cwdNoValidoNoSeUsa() throws Exception {
        Path p = spring();
        cliente = new ClienteMcp(entorno(p, p, dir("instalacion"), k -> null));
        cliente.iniciar("2025-11-25", "{}");
        cliente.llamar(2, "escanear_proyecto", null);
        JsonNode r = cliente.resultado(2);
        assertThat(r.get("isError").asBoolean()).isTrue();
        assertThat(texto(r)).contains("No se pudo deducir el proyecto");
    }

    @Test
    @Timeout(120)
    void lasRaicesTienenPrioridadSobreElCwd() throws Exception {
        Path p = spring();
        Path m = multi();
        cliente = new ClienteMcp(entorno(p, dir("home"), dir("instalacion"), k -> null));
        cliente.iniciar("2025-11-25", "{\"roots\":{}}");
        cliente.llamar(2, "escanear_proyecto", null);
        cliente.responderRaices(cliente.peticion("roots/list"), m);
        JsonNode r = cliente.resultado(2);
        assertThat(primeraLinea(r)).startsWith("Modo solo lectura sobre " + m + ".");
        assertThat(texto(r)).doesNotContain("directorio de trabajo del servidor");
    }

    // ------------------------------------------------------------- módulo por servicios

    @Test
    @Timeout(120)
    void serviciosDeducenElModuloYLimitanElInforme() throws Exception {
        Path m = multi();
        cliente = new ClienteMcp(ClienteMcp.entorno(tmp));
        cliente.iniciar("2025-11-25", "{}");
        cliente.llamar(2, "escanear_proyecto", args("ruta", m, "servicios", List.of(" `BService.java` ", "NoExiste")));
        JsonNode r = cliente.resultado(2);
        String t = texto(r);
        assertThat(r.get("isError").asBoolean()).as(t).isFalse();
        assertThat(t).contains("## Servicios pedidos", "| BService | com.b.BService | modB |", "| NoExiste | — | — | no encontrado",
                "Módulo de trabajo deducido: modB (de 2 módulos)");
        assertThat(t.indexOf("## Servicios pedidos")).isLessThan(t.indexOf("## Resumen"));
        assertThat(t).contains("Estilo sugerido (modB)").doesNotContain("com.a.AService", "Estilo sugerido (modA)");
    }

    @Test
    @Timeout(120)
    void servicioAmbiguoListaCandidatosYDetalleUsaElModuloDeTrabajo() throws Exception {
        Path m = multi();
        Fixtures.escribir(m.resolve("modB/src/main/java/com/b/AService.java"),
                "package com.b;\n\npublic class AService {\n\n    public int triple(int x) {\n        return x * 3;\n    }\n}\n");
        cliente = new ClienteMcp(ClienteMcp.entorno(tmp));
        cliente.iniciar("2025-11-25", "{}");
        cliente.llamar(2, "escanear_proyecto", args("ruta", m, "servicios", List.of("aservice")));
        String t = texto(cliente.resultado(2));
        assertThat(t).contains("ambiguo: modA:com.a.AService; modB:com.b.AService", "No se pudo deducir el módulo de trabajo");

        cliente.llamar(3, "detalle_servicio", args("clase", "AService"));
        JsonNode d = cliente.resultado(3);
        assertThat(d.get("isError").asBoolean()).isTrue();
        assertThat(texto(d)).contains("es ambigua");

        cliente.llamar(4, "escanear_proyecto", args("servicios", List.of("BService")));
        assertThat(texto(cliente.resultado(4))).contains("Módulo de trabajo deducido: modB");
        cliente.llamar(5, "detalle_servicio", args("clase", "AService"));
        JsonNode d2 = cliente.resultado(5);
        assertThat(d2.get("isError").asBoolean()).as(texto(d2)).isFalse();
        assertThat(texto(d2)).contains("com.b.AService").doesNotContain("com.a.AService");
    }

    // ------------------------------------------------------------- decisión única de verificación

    private String spec(Path p, String caso) {
        String c = switch (caso) {
            case "activar" -> "{'id':'activar-ok','metodo':'activar','escenario':'activaUsuarioInactivo','datos':[{'tipo':'Usuario',"
                    + "'nombre':'u','valor':'new Usuario(5L, \\'Leo\\')'}],'stubs':[{'mock':'repo','llamada':'findById(5L)',"
                    + "'devuelve':'Optional.of(u)'},{'mock':'repo','llamada':'save(u)','devuelve':'u'}],'argumentos':['5L'],"
                    + "'esperado':{'tipo':'noNulo'}}";
            default -> "{'id':'buscar-no-existe','metodo':'buscar','escenario':'lanzaSiNoExiste','stubs':[{'mock':'repo','llamada':"
                    + "'findById(9L)','devuelve':'Optional.empty()'}],'argumentos':['9L'],'esperado':{'tipo':'excepcion',"
                    + "'excepcion':'IllegalArgumentException','mensaje':'No existe 9'}}";
        };
        return Fixtures.j("{'version':1,'proyecto':{'ruta':'" + p.toString().replace('\\', '/') + "'},'servicios':[{'clase':"
                + "'com.ejemplo.service.UsuarioServiceImpl','casos':[" + c + "]}]}");
    }

    private ObjectNode generar(Path p, String caso) {
        ObjectNode g = Json.objeto();
        g.set("spec", Json.leer(spec(p, caso)));
        return g;
    }

    private static JsonNode verificacion(JsonNode resultado) {
        String t = texto(resultado);
        return Json.leer(t.substring(t.indexOf("\n\n") + 2)).get("verificacion");
    }

    @Test
    @Timeout(120)
    void dosGeneracionesUnaSolaPreguntaYLaMismaDecision() throws Exception {
        Path p = spring();
        cliente = new ClienteMcp(ClienteMcp.entorno(tmp));
        cliente.iniciar("2025-11-25", "{\"elicitation\":{}}");
        cliente.llamar(2, "escanear_proyecto", args("ruta", p, "carpetaSalida", tmp.resolve("salida")));
        assertThat(cliente.resultado(2).get("isError").asBoolean()).isFalse();

        cliente.llamar(3, "generar_tests", generar(p, "activar"));
        JsonNode e = cliente.peticion("elicitation/create");
        assertThat(e.get("params").get("message").asText()).contains("vale para todos los tests de esta sesión");
        cliente.aceptar(e, Map.of("accion", "Probar tests"));
        JsonNode r1 = cliente.resultado(3);
        assertThat(r1.get("isError").asBoolean()).as(texto(r1)).isFalse();
        JsonNode v1 = verificacion(r1);
        assertThat(v1.get("decision").asText()).isEqualTo("probar");
        assertThat(v1.get("alcance").asText()).isEqualTo("sesion");
        assertThat(v1.get("instruccion").asText()).contains("llama una sola vez a ejecutar_tests sin `clases` ni `modulo`");

        cliente.llamar(4, "generar_tests", generar(p, "buscar"));
        JsonNode r2 = cliente.resultado(4);
        assertThat(verificacion(r2).get("decision").asText()).isEqualTo("probar");
    }

    @Test
    @Timeout(120)
    void sinElicitacionPendienteYDecisionDelChatPersisteDescartada() throws Exception {
        Path p = spring();
        cliente = new ClienteMcp(ClienteMcp.entorno(tmp));
        cliente.iniciar("2025-11-25", "{}");
        cliente.llamar(2, "escanear_proyecto", args("ruta", p, "carpetaSalida", tmp.resolve("salida")));
        cliente.resultado(2);

        cliente.llamar(3, "generar_tests", generar(p, "activar"));
        JsonNode v1 = verificacion(cliente.resultado(3));
        assertThat(v1.get("decision").asText()).isEqualTo("pendiente");
        assertThat(v1.get("instruccion").asText()).contains("decisionVerificacion");

        ObjectNode g2 = generar(p, "buscar");
        g2.put("decisionVerificacion", "descartar");
        cliente.llamar(4, "generar_tests", g2);
        JsonNode v2 = verificacion(cliente.resultado(4));
        assertThat(v2.get("decision").asText()).isEqualTo("descartar");
        assertThat(v2.get("instruccion").asText()).isEqualTo("No preguntes ni ejecutes salvo petición expresa.");

        cliente.llamar(5, "generar_tests", generar(p, "buscar"));
        JsonNode v3 = verificacion(cliente.resultado(5));
        assertThat(v3.get("decision").asText()).as("sin tests nuevos devuelve la decisión de la sesión").isEqualTo("descartar");
    }

    @Test
    @Timeout(120)
    void declineCuentaComoDescartarYSinTestsSinDecision() throws Exception {
        Path p = spring();
        cliente = new ClienteMcp(ClienteMcp.entorno(tmp));
        cliente.iniciar("2025-11-25", "{\"elicitation\":{}}");
        cliente.llamar(2, "escanear_proyecto", args("ruta", p, "carpetaSalida", tmp.resolve("salida")));
        cliente.resultado(2);
        cliente.llamar(3, "generar_tests", generar(p, "activar"));
        cliente.rechazar(cliente.peticion("elicitation/create"));
        assertThat(verificacion(cliente.resultado(3)).get("decision").asText()).isEqualTo("descartar");
        cliente.llamar(4, "generar_tests", generar(p, "buscar"));
        assertThat(verificacion(cliente.resultado(4)).get("decision").asText()).isEqualTo("descartar");
    }

    // ------------------------------------------------------------- fallos de aserción: pregunta única

    /** Salida con spec.json (c1 y c2 en fallo_asercion) del proyecto Spring. */
    private Path salidaConFallos() throws IOException {
        Path p = spring();
        Path salida = tmp.resolve("salida-fallos");
        String caso = "{'id':'%s','metodo':'buscar','escenario':'e%s','argumentos':['1L'],'esperado':{'tipo':'noNulo'},'estado':'fallo_asercion'}";
        Fixtures.escribir(salida.resolve("spec.json"), Fixtures.j("{'version':1,'proyecto':{'ruta':'" + p.toString().replace('\\', '/')
                + "'},'servicios':[{'clase':'com.ejemplo.service.UsuarioServiceImpl','casos':[" + String.format(caso, "c1", "1") + ","
                + String.format(caso, "c2", "2") + "]}]}"));
        return salida;
    }

    private static ResultadoVerificacion dosFallos() {
        ResultadoVerificacion rv = new ResultadoVerificacion();
        String[] mensajes = {"org.opentest4j.AssertionFailedError: expected: <1> but was: <2>", "expected:<[Leo]> but was:<[Ana]>"};
        for (int i = 1; i <= 2; i++) {
            ResultadoTest r = new ResultadoTest();
            r.caso = "c" + i;
            r.clase = "com.ejemplo.service.UsuarioServiceImplTest";
            r.metodoTest = "m" + i;
            r.estado = ClasificadorFallos.FALLO_ASERCION;
            r.mensaje = mensajes[i - 1];
            rv.resultados.add(r);
            rv.asercionesPendientes.add(r.caso);
        }
        rv.accionRequerida = "Fallos de aserción.";
        return rv;
    }

    /**
     * Ejecuta decidirAserciones con una sesión con elicitation, comprueba que hay una sola pregunta con la
     * lista y las 5 opciones y responde con {@code opcion} (null = decline).
     */
    private ResultadoVerificacion responder(Path salida, ResultadoVerificacion rv, String opcion) throws Exception {
        PipedInputStream lecturaServidor = new PipedInputStream(1 << 16);
        PipedOutputStream salidaServidor = new PipedOutputStream(lecturaServidor);
        TransporteStdio t = new TransporteStdio(new ByteArrayInputStream(new byte[0]), salidaServidor);
        SesionMcp s = new SesionMcp(t, new Fachada(), ClienteMcp.entorno(tmp));
        s.inicializar("2025-11-25", Json.leer("{\"elicitation\":{}}"));
        CompletableFuture<Void> f = CompletableFuture.runAsync(() -> HerramientaEjecutar.decidirAserciones(s, salida, rv));
        BufferedReader br = new BufferedReader(new InputStreamReader(lecturaServidor, StandardCharsets.UTF_8));
        JsonNode peticion = Json.leer(br.readLine());
        assertThat(peticion.get("method").asText()).isEqualTo("elicitation/create");
        assertThat(peticion.get("params").get("message").asText()).contains("Hay 2 casos",
                "- c1 (UsuarioServiceImplTest.m1): esperado 1, obtenido 2", "- c2 (UsuarioServiceImplTest.m2): esperado [Leo], obtenido [Ana]");
        List<String> opciones = new java.util.ArrayList<>();
        peticion.get("params").get("requestedSchema").get("properties").get("accion").get("enum").forEach(x -> opciones.add(x.asText()));
        assertThat(opciones).containsExactly(HerramientaEjecutar.BUG, HerramientaEjecutar.AJUSTAR, HerramientaEjecutar.ELIMINAR,
                HerramientaEjecutar.REVISAR_IA, HerramientaEjecutar.REVISAR_EN_CHAT);
        String res = opcion == null ? "{\"action\":\"decline\"}" : "{\"action\":\"accept\",\"content\":{\"accion\":\"" + opcion + "\"}}";
        s.completar(Json.leer("{\"jsonrpc\":\"2.0\",\"id\":\"" + peticion.get("id").asText() + "\",\"result\":" + res + "}"));
        f.get(30, TimeUnit.SECONDS);
        assertThat(br.ready()).as("una sola elicitation").isFalse();
        return rv;
    }

    private static List<String> decisiones(ResultadoVerificacion rv) {
        return rv.decisiones.stream().map(d -> d.get("caso") + ":" + d.get("decision")).toList();
    }

    private static String estado(Path salida, String caso) {
        return com.junitgen.generacion.AlmacenSpec.buscar(com.junitgen.generacion.AlmacenSpec.leer(salida), caso).caso().estado;
    }

    @Test
    @Timeout(60)
    void dosFallosUnaPreguntaYBugSeAplicaATodos() throws Exception {
        Path salida = salidaConFallos();
        ResultadoVerificacion rv = responder(salida, dosFallos(), HerramientaEjecutar.BUG);
        assertThat(decisiones(rv)).containsExactly("c1:bug", "c2:bug");
        assertThat(estado(salida, "c1")).isEqualTo("posible_bug");
        assertThat(estado(salida, "c2")).isEqualTo("posible_bug");
    }

    @Test
    @Timeout(60)
    void ajustarSeAplicaATodosConLaInstruccionDeReejecutarEsosCasos() throws Exception {
        ResultadoVerificacion rv = responder(salidaConFallos(), dosFallos(), HerramientaEjecutar.AJUSTAR);
        assertThat(decisiones(rv)).containsExactly("c1:ajustar", "c2:ajustar");
        assertThat(rv.decisiones).allMatch(d -> d.get("instruccion") != null && d.get("instruccion").contains("reemplazarCasos"));
        assertThat(rv.accionRequerida).contains("reemplazarCasos=[c1, c2]", "ejecutar_tests con casos=[c1, c2]");
    }

    @Test
    @Timeout(60)
    void eliminarCuentaComoConfirmacionExplicita() throws Exception {
        Path salida = salidaConFallos();
        ResultadoVerificacion rv = responder(salida, dosFallos(), HerramientaEjecutar.ELIMINAR);
        assertThat(decisiones(rv)).containsExactly("c1:eliminar", "c2:eliminar");
        assertThat(estado(salida, "c1")).isEqualTo("eliminado");
        assertThat(estado(salida, "c2")).isEqualTo("eliminado");
    }

    @Test
    @Timeout(60)
    void revisarConIaDevuelveServicioMetodoYLaInstruccionDeAnalisis() throws Exception {
        Path salida = salidaConFallos();
        ResultadoVerificacion rv = responder(salida, dosFallos(), HerramientaEjecutar.REVISAR_IA);
        assertThat(decisiones(rv)).containsExactly("c1:revisar_ia", "c2:revisar_ia");
        assertThat(rv.decisiones.get(0)).containsEntry("servicio", "com.ejemplo.service.UsuarioServiceImpl")
                .containsEntry("metodo", "buscar").containsEntry("metodoTest", "m1");
        assertThat(rv.accionRequerida).contains("detalle_servicio", "registrar_analisis_ia", "error_ia", "bug_servicio", "dudoso",
                "Nunca toques el proyecto");
        assertThat(estado(salida, "c1")).as("no se decide nada todavía").isEqualTo("fallo_asercion");
    }

    @Test
    @Timeout(60)
    void revisarUnoAUnoDejaPendientesParaElChat() throws Exception {
        ResultadoVerificacion rv = responder(salidaConFallos(), dosFallos(), HerramientaEjecutar.REVISAR_EN_CHAT);
        assertThat(decisiones(rv)).containsExactly("c1:pendiente", "c2:pendiente");
        assertThat(rv.accionRequerida).contains("uno a uno", "resolver_fallo", "No decidas por el usuario");
    }

    @Test
    @Timeout(60)
    void declineDejaLaMismaPreguntaConLasCincoOpcionesParaElChat() throws Exception {
        ResultadoVerificacion rv = responder(salidaConFallos(), dosFallos(), null);
        assertThat(decisiones(rv)).containsExactly("c1:pendiente", "c2:pendiente");
        assertThat(rv.accionRequerida).contains("en un único mensaje", "- c1 (UsuarioServiceImplTest.m1): esperado 1, obtenido 2",
                HerramientaEjecutar.BUG, HerramientaEjecutar.AJUSTAR, HerramientaEjecutar.ELIMINAR, HerramientaEjecutar.REVISAR_IA,
                HerramientaEjecutar.REVISAR_EN_CHAT, "confirmadoPorUsuario=true", "No decidas por el usuario");
    }

    @Test
    @Timeout(60)
    void conMontajeReintentableNoSePreguntaHastaElFinal() throws Exception {
        PipedInputStream lecturaServidor = new PipedInputStream(1 << 16);
        PipedOutputStream salidaServidor = new PipedOutputStream(lecturaServidor);
        SesionMcp s = new SesionMcp(new TransporteStdio(new ByteArrayInputStream(new byte[0]), salidaServidor), new Fachada(),
                ClienteMcp.entorno(tmp));
        s.inicializar("2025-11-25", Json.leer("{\"elicitation\":{}}"));
        ResultadoVerificacion rv = dosFallos();
        rv.montajeReintentables.add("c3");
        HerramientaEjecutar.decidirAserciones(s, tmp, rv);
        assertThat(lecturaServidor.available()).as("ninguna elicitation").isZero();
        assertThat(rv.decisiones).isEmpty();
        assertThat(rv.accionRequerida).contains("se preguntarán al final", "[c1, c2]");
    }

    @Test
    void resumenDeAsercion() {
        assertThat(HerramientaEjecutar.resumenAsercion("expected: <5> but was: <7>")).isEqualTo("esperado 5, obtenido 7");
        assertThat(HerramientaEjecutar.resumenAsercion("\nexpected: \"a\"\n but was: \"b\"")).isEqualTo("esperado \"a\", obtenido \"b\"");
        assertThat(HerramientaEjecutar.resumenAsercion("algo raro\nen dos líneas")).isEqualTo("algo raro en dos líneas");
        assertThat(HerramientaEjecutar.resumenAsercion("z".repeat(300))).hasSize(HerramientaEjecutar.MAX_RESUMEN + 1);
    }

    @Test
    @Timeout(120)
    void ejecutarSinDecisionFijaProbarParaLaSesion() throws Exception {
        Path p = spring();
        cliente = new ClienteMcp(ClienteMcp.entorno(tmp));
        cliente.iniciar("2025-11-25", "{}");
        cliente.llamar(2, "escanear_proyecto", args("ruta", p, "carpetaSalida", tmp.resolve("vacia")));
        cliente.resultado(2);
        // Sin nada generado en la sesión, ejecutar_tests se rechaza sin fijar la decisión ni crear el temporal.
        cliente.llamar(3, "ejecutar_tests", null);
        JsonNode rechazo = cliente.resultado(3);
        assertThat(rechazo.get("isError").asBoolean()).isTrue();
        assertThat(texto(rechazo)).contains("ejecutar_tests no ha ejecutado nada", "resumenSesion");
        assertThat(tmp.resolve("vacia").resolve(".junit-gen-tmp")).doesNotExist();
        cliente.llamar(4, "generar_tests", generar(p, "activar"));
        assertThat(verificacion(cliente.resultado(4)).get("decision").asText()).isEqualTo("pendiente");
        // Tras generar, ejecutar es en sí la decisión de probar para el resto de la sesión.
        cliente.llamar(5, "ejecutar_tests", null);
        JsonNode ej = cliente.resultado(5);
        assertThat(ej.get("isError").asBoolean()).as(texto(ej)).isFalse();
        cliente.llamar(6, "generar_tests", generar(p, "buscar"));
        assertThat(verificacion(cliente.resultado(6)).get("decision").asText()).isEqualTo("probar");
    }

    @Test
    @Timeout(120)
    void casosNoValidosEnEjecutarSonUnError() throws Exception {
        Path p = spring();
        cliente = new ClienteMcp(ClienteMcp.entorno(tmp));
        cliente.iniciar("2025-11-25", "{}");
        cliente.llamar(2, "ejecutar_tests", args("rutaProyecto", p, "carpetaTests", tmp.resolve("vacia"), "casos", List.of("../x")));
        JsonNode r = cliente.resultado(2);
        assertThat(r.get("isError").asBoolean()).isTrue();
        assertThat(texto(r)).contains("Id de caso no válido");
    }

    // ------------------------------------------------------------- servicios y plan por MCP

    @Test
    @Timeout(120)
    void interfazEnMinusculasSeResuelveSinPreguntas() throws Exception {
        Path p = spring();
        cliente = new ClienteMcp(ClienteMcp.entorno(tmp));
        cliente.iniciar("2025-11-25", "{}");
        cliente.llamar(2, "escanear_proyecto", args("ruta", p, "carpetaSalida", tmp.resolve("salida"), "servicios", List.of("usuarioService")));
        String t = texto(cliente.resultado(2));
        assertThat(t).contains("| usuarioService | com.ejemplo.service.UsuarioServiceImpl | ", "interfaz → implementación",
                "Módulo de trabajo deducido:", "Genera el plan sin pedir confirmación", "## Plan de generación");
        cliente.llamar(3, "detalle_servicio", args("clase", "usuarioService"));
        JsonNode d = cliente.resultado(3);
        assertThat(d.get("isError").asBoolean()).as(texto(d)).isFalse();
        assertThat(texto(d)).contains("com.ejemplo.service.UsuarioServiceImpl");
    }

    @Test
    @Timeout(120)
    void numServiciosDevuelveElPlanPorServiciosSinPedirConfirmacion() throws Exception {
        Path p = spring();
        cliente = new ClienteMcp(ClienteMcp.entorno(tmp));
        cliente.iniciar("2025-11-25", "{}");
        ObjectNode a = args("ruta", p, "carpetaSalida", tmp.resolve("salida"));
        a.put("numServicios", 1);
        cliente.llamar(2, "escanear_proyecto", a);
        String t = texto(cliente.resultado(2));
        assertThat(t).contains("## Plan de generación", "| # | Servicio | Módulo | Métodos | Casos | Sin tests |",
                "**Total: 1 servicio, ", "(servicios pedidos: 1)", "Genera el plan sin pedir confirmación: 1 servicio, ",
                "`paginacion.siguiente`", "`casosPlan`", "`casosGeneradosEnSalida`", "Sigue hasta `plan.restantes` = 0",
                "UNA vez a ejecutar_tests sin argumentos", "Primera llamada: detalle_servicio {clase: \"",
                "No uses terminal ni leas archivos", "Secciones bajo petición");
        assertThat(t.indexOf("## Plan de generación")).isLessThan(t.indexOf("## Resumen"));
        assertThat(t.indexOf("## Resumen")).isLessThan(t.indexOf("## Siguiente paso"));
    }

    @Test
    @Timeout(120)
    void serviciosNombradosDanPlanConTodasSusFuncionesYGenerarInformaDelProgreso() throws Exception {
        Path p = spring();
        cliente = new ClienteMcp(ClienteMcp.entorno(tmp));
        cliente.iniciar("2025-11-25", "{}");
        cliente.llamar(2, "escanear_proyecto", args("ruta", p, "carpetaSalida", tmp.resolve("salida"), "servicios",
                List.of("UsuarioServiceImpl")));
        String t = texto(cliente.resultado(2));
        assertThat(t).contains("## Plan de generación", "**Total: 1 servicio, 3 métodos, ").doesNotContain("(servicios pedidos:");
        ObjectNode sec = args("seccion", "plan");
        cliente.llamar(30, "escanear_proyecto", sec);
        String sp = texto(cliente.resultado(30));
        assertThat(sp).contains("UsuarioServiceImpl", "- buscar ×", "- activar ×", "- contarActivos ×", "Página 1 de 1.");

        cliente.llamar(3, "generar_tests", generar(p, "activar"));
        String g = texto(cliente.resultado(3));
        JsonNode plan = Json.leer(g.substring(g.indexOf("\n\n") + 2)).get("plan");
        int total = plan.get("total").asInt();
        assertThat(plan.get("generados").asInt()).isEqualTo(1);
        assertThat(plan.get("restantes").asInt()).isEqualTo(total - 1);
        assertThat(plan.get("servicios").asInt()).isEqualTo(1);
        assertThat(plan.get("metodos").get("total").asInt()).isEqualTo(3);
        assertThat(plan.get("pendientes").toString()).contains("UsuarioServiceImpl.contarActivos: faltan", "UsuarioServiceImpl.buscar: faltan");
        assertThat(plan.get("instruccion").asText()).contains("un descarte por duplicado cuenta como atendido");

        cliente.llamar(4, "escanear_proyecto", args("ruta", p, "servicios", List.of("NoExisteService")));
        cliente.resultado(4);
        cliente.llamar(5, "generar_tests", generar(p, "buscar"));
        String g2 = texto(cliente.resultado(5));
        assertThat(Json.leer(g2.substring(g2.indexOf("\n\n") + 2)).has("plan")).as("servicios sin resolver quitan el plan").isFalse();
    }

    @Test
    @Timeout(120)
    void cantidadSeRechazaYNumServiciosFueraDeRangoEsUnError() throws Exception {
        Path p = spring();
        cliente = new ClienteMcp(ClienteMcp.entorno(tmp));
        cliente.iniciar("2025-11-25", "{}");
        ObjectNode c = args("ruta", p);
        c.put("cantidad", 2);
        cliente.llamar(2, "escanear_proyecto", c);
        JsonNode r = cliente.resultado(2);
        assertThat(r.get("isError").asBoolean()).isTrue();
        assertThat(texto(r)).contains("'cantidad' ya no existe: usa 'numServicios' (N = nº de servicios; se generan tests de todas sus funciones).");

        ObjectNode fuera = args("ruta", p);
        fuera.put("numServicios", 201);
        cliente.llamar(3, "escanear_proyecto", fuera);
        JsonNode r2 = cliente.resultado(3);
        assertThat(r2.get("isError").asBoolean()).isTrue();
        assertThat(texto(r2)).contains("numServicios debe ser un entero entre 1 y 200");
    }

    @Test
    @Timeout(120)
    void sinServiciosNiNumeroElSiguientePasoEsLaPreguntaExacta() throws Exception {
        Path p = spring();
        cliente = new ClienteMcp(ClienteMcp.entorno(tmp));
        cliente.iniciar("2025-11-25", "{}");
        cliente.llamar(2, "escanear_proyecto", args("ruta", p, "carpetaSalida", tmp.resolve("salida")));
        String t = texto(cliente.resultado(2));
        assertThat(t).contains("Pregunta al usuario exactamente: «¿Cuántos servicios (N) o qué servicios quieres probar? Se generarán "
                + "tests de todas sus funciones»").doesNotContain("## Plan de generación");
    }

    @Test
    void progresoDelPlanAcreditaDuplicadosSoloEnMetodosConTestsYFusionaSobrecargas() throws Exception {
        SesionMcp s = new SesionMcp(new TransporteStdio(new ByteArrayInputStream(new byte[0]), new java.io.ByteArrayOutputStream()),
                new Fachada(), ClienteMcp.entorno(tmp));
        Path proyecto = tmp.toRealPath();
        s.fijarPlan(proyecto, List.of(new SesionMcp.MetodoPlan("com.x.S", "a", 2, true),
                new SesionMcp.MetodoPlan("com.x.S", "b", 1, false), new SesionMcp.MetodoPlan("com.x.S", "b", 1, true),
                new SesionMcp.MetodoPlan("com.x.T", "c", 1, false)));
        SesionMcp.ProgresoPlan inicial = s.progresoPlan(proyecto);
        assertThat(inicial.total()).isEqualTo(5);
        assertThat(inicial.metodosTotal()).as("sobrecargas fusionadas por nombre").isEqualTo(3);
        assertThat(inicial.servicios()).isEqualTo(2);
        String a = SesionMcp.clavePlan("com.x.S", "a");
        String b = SesionMcp.clavePlan("com.x.S", "b");
        s.registrarCasosPlan(proyecto, Map.of("g1", a, "fuera", SesionMcp.clavePlan("com.x.Z", "z")), Map.of("d1", a, "d2", b), List.of());
        SesionMcp.ProgresoPlan p = s.progresoPlan(proyecto);
        assertThat(p.generados()).as("a: 1 generado + 1 duplicado (con tests); b: el duplicado no cuenta (no todas con tests)").isEqualTo(2);
        assertThat(p.restantes()).isEqualTo(3);
        assertThat(p.metodosCompletos()).isEqualTo(1);
        assertThat(p.pendientes()).containsExactly("S.b: faltan 2", "T.c: faltan 1");
        s.registrarCasosPlan(proyecto, Map.of(), Map.of(), List.of("g1"));
        assertThat(s.progresoPlan(proyecto).generados()).as("los eliminados dejan de contar").isEqualTo(1);
        s.quitarPlan(proyecto);
        assertThat(s.progresoPlan(proyecto)).isNull();
    }

    // ------------------------------------------------------------- pregunta única: pendientes persistidos

    /** Salida con c1, c2 en fallo_asercion, c3 autorizado (ajustar) y c4 con veredicto error_ia. */
    private Path salidaConPersistidos() throws IOException {
        Path p = spring();
        Path salida = tmp.resolve("salida-persistidos");
        String caso = "{'id':'%s','metodo':'buscar','escenario':'e%s','argumentos':['1L'],'esperado':{'tipo':'noNulo'},'estado':'fallo_asercion'%s}";
        Fixtures.escribir(salida.resolve("spec.json"), Fixtures.j("{'version':1,'proyecto':{'ruta':'" + p.toString().replace('\\', '/')
                + "'},'servicios':[{'clase':'com.ejemplo.service.UsuarioServiceImpl','casos':[" + String.format(caso, "c1", "1", "") + ","
                + String.format(caso, "c2", "2", "") + "," + String.format(caso, "c3", "3", ",'autorizacionCambio':'ajustar'") + ","
                + String.format(caso, "c4", "4", ",'veredictoIa':'error_ia'") + "]}]}"));
        return salida;
    }

    @Test
    @Timeout(60)
    void preguntaTambienPorPendientesPersistidosFueraDeLaEjecucionYSoloUnaVezPorSesion() throws Exception {
        Path salida = salidaConPersistidos();
        PipedInputStream lecturaServidor = new PipedInputStream(1 << 16);
        SesionMcp s = new SesionMcp(new TransporteStdio(new ByteArrayInputStream(new byte[0]), new PipedOutputStream(lecturaServidor)),
                new Fachada(), ClienteMcp.entorno(tmp));
        s.inicializar("2025-11-25", Json.leer("{\"elicitation\":{}}"));
        ResultadoVerificacion rv = dosFallos();
        rv.resultados.remove(1);
        rv.asercionesPendientes.remove("c2");
        CompletableFuture<Void> f = CompletableFuture.runAsync(() -> HerramientaEjecutar.decidirAserciones(s, salida, rv));
        BufferedReader br = new BufferedReader(new InputStreamReader(lecturaServidor, StandardCharsets.UTF_8));
        JsonNode peticion = Json.leer(br.readLine());
        String msg = peticion.get("params").get("message").asText();
        assertThat(msg).contains("Hay 2 casos", "- c1 (UsuarioServiceImplTest.m1): esperado 1, obtenido 2",
                "- c2 (UsuarioServiceImpl.buscar): pendiente de una ejecución anterior").doesNotContain("c3", "c4");
        s.completar(Json.leer("{\"jsonrpc\":\"2.0\",\"id\":\"" + peticion.get("id").asText() + "\",\"result\":{\"action\":\"decline\"}}"));
        f.get(30, TimeUnit.SECONDS);
        assertThat(rv.asercionesPendientes).containsExactly("c1", "c2");
        assertThat(decisiones(rv)).containsExactly("c1:pendiente", "c2:pendiente");

        ResultadoVerificacion otra = new ResultadoVerificacion();
        HerramientaEjecutar.decidirAserciones(s, salida, otra);
        assertThat(lecturaServidor.available()).as("ya preguntados en la sesión: ninguna elicitation").isZero();
        assertThat(otra.asercionesPendientes).isEmpty();
    }

    @Test
    @Timeout(60)
    void pendientesPersistidosNoSePreguntanMientrasHayaMontajeReintentable() throws Exception {
        Path salida = salidaConPersistidos();
        PipedInputStream lecturaServidor = new PipedInputStream(1 << 16);
        SesionMcp s = new SesionMcp(new TransporteStdio(new ByteArrayInputStream(new byte[0]), new PipedOutputStream(lecturaServidor)),
                new Fachada(), ClienteMcp.entorno(tmp));
        s.inicializar("2025-11-25", Json.leer("{\"elicitation\":{}}"));
        ResultadoVerificacion rv = new ResultadoVerificacion();
        rv.montajeReintentables.add("c9");
        HerramientaEjecutar.decidirAserciones(s, salida, rv);
        assertThat(lecturaServidor.available()).as("ninguna elicitation").isZero();
        assertThat(rv.accionRequerida).contains("se preguntarán al final", "[c1, c2]");
        assertThat(s.asercionPreguntada(salida, "c1")).as("diferir no cuenta como preguntado").isFalse();
    }
}
