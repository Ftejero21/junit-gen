package com.junitgen.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.junitgen.Fixtures;
import com.junitgen.util.Json;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.junitgen.mcp.ClienteMcp.enumCarpeta;
import static com.junitgen.mcp.ClienteMcp.etiquetas;
import static com.junitgen.mcp.ClienteMcp.propiedades;
import static com.junitgen.mcp.ClienteMcp.primeraLinea;
import static com.junitgen.mcp.ClienteMcp.texto;
import static org.assertj.core.api.Assertions.assertThat;

/** MCP roots y pregunta única de la carpeta de salida, con el servidor completo sobre pipes en memoria. */
class RutasSesionTest {

    @TempDir
    Path tmp;

    private ClienteMcp cliente;

    @AfterEach
    void cerrar() throws Exception {
        if (cliente != null) {
            cliente.close();
        }
    }

    private ClienteMcp nuevoCliente() throws IOException {
        cliente = new ClienteMcp(ClienteMcp.entorno(tmp));
        return cliente;
    }

    private Path proyecto() throws IOException {
        return Fixtures.copiar("proyecto-spring", tmp.resolve("Proyecto Demo")).toRealPath();
    }

    /** {@code <Escritorio>\<NombreProyecto>Test} del entorno hermético. */
    private Path escritorio(Path proyectoReal) throws IOException {
        return Files.createDirectories(tmp.resolve("Escritorio")).toRealPath().resolve(proyectoReal.getFileName() + "Test");
    }

    private static String etiquetaEscritorio(Path salida) {
        return "Crear carpeta en el Escritorio: " + salida;
    }

    private Path archivoRutas() {
        return tmp.resolve("instalacion").resolve(EntornoSesion.ARCHIVO_RUTAS);
    }

    private static ObjectNode ruta(Path p) {
        ObjectNode a = Json.objeto();
        a.put("ruta", p.toString());
        return a;
    }

    private static final String ROOTS_Y_ELICITATION = "{\"roots\":{\"listChanged\":true},\"elicitation\":{}}";

    // ------------------------------------------------------------- roots

    @Test
    @Timeout(120)
    void unaRaizSeEscaneaSinRutaYSinElicitationDevuelveLaNota() throws Exception {
        Path p = proyecto();
        nuevoCliente().iniciar("2025-06-18", "{\"roots\":{\"listChanged\":true}}");
        cliente.llamar(2, "escanear_proyecto", null);
        JsonNode roots = cliente.peticion("roots/list");
        assertThat(roots.has("params")).isFalse();
        cliente.responderRaices(roots, p);
        JsonNode r = cliente.resultado(2);
        assertThat(r.get("isError").asBoolean()).as(texto(r)).isFalse();
        assertThat(primeraLinea(r)).isEqualTo("Modo solo lectura sobre " + p + ". Esta operación no crea archivos.");
        assertThat(texto(r)).contains("UsuarioServiceImpl", "Carpeta de salida pendiente", "[" + etiquetaEscritorio(escritorio(p)) + "]",
                "guardarRutaPorDefecto=true");
    }

    @Test
    @Timeout(120)
    void variasRaicesDevuelvenErrorConTodasLasRutas() throws Exception {
        Path a = Files.createDirectories(tmp.resolve("a")).toRealPath();
        Path b = Files.createDirectories(tmp.resolve("b")).toRealPath();
        nuevoCliente().iniciar("2025-06-18", ROOTS_Y_ELICITATION);
        cliente.llamar(2, "escanear_proyecto", null);
        cliente.responderRaices(cliente.peticion("roots/list"), a, b);
        JsonNode r = cliente.resultado(2);
        assertThat(r.get("isError").asBoolean()).isTrue();
        assertThat(texto(r)).contains("Hay varias carpetas abiertas en el IDE", "- " + a, "- " + b);
    }

    @Test
    @Timeout(120)
    void sinCapabilityNiCwdValidoSePideLaRutaConocida() throws Exception {
        nuevoCliente().iniciar("2025-06-18", "{\"elicitation\":{}}");
        cliente.llamar(2, "escanear_proyecto", null);
        JsonNode r = cliente.resultado(2);
        assertThat(r.get("isError").asBoolean()).isTrue();
        assertThat(texto(r)).contains("No se pudo deducir el proyecto", "MCP roots",
                "Pasa en 'ruta' la carpeta de trabajo/workspace que ya conoces", "sin preguntar al usuario");
    }

    @Test
    @Timeout(120)
    void listaDeRaicesVaciaSinCwdValidoPideLaRuta() throws Exception {
        nuevoCliente().iniciar("2025-06-18", ROOTS_Y_ELICITATION);
        cliente.llamar(2, "escanear_proyecto", null);
        cliente.responderRaices(cliente.peticion("roots/list"));
        JsonNode r = cliente.resultado(2);
        assertThat(r.get("isError").asBoolean()).isTrue();
        assertThat(texto(r)).contains("No se pudo deducir el proyecto");
    }

    @Test
    @Timeout(120)
    void listChangedInvalidaLaCacheDeRaices() throws Exception {
        Path p = proyecto();
        nuevoCliente().iniciar("2025-06-18", "{\"roots\":{\"listChanged\":true}}");
        cliente.llamar(2, "escanear_proyecto", null);
        cliente.responderRaices(cliente.peticion("roots/list"), p);
        assertThat(cliente.resultado(2).get("isError").asBoolean()).isFalse();
        cliente.llamar(3, "escanear_proyecto", null);
        assertThat(cliente.resultado(3).get("isError").asBoolean()).isFalse();
        cliente.enviar("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/roots/list_changed\"}");
        cliente.llamar(4, "escanear_proyecto", null);
        cliente.responderRaices(cliente.peticion("roots/list"), p);
        assertThat(cliente.resultado(4).get("isError").asBoolean()).isFalse();
    }

    // ------------------------------------------------------------- carpeta de salida

    @Test
    @Timeout(120)
    void elegirElEscritorioLaGuardaNoLaCreaYNoVuelveAPreguntar() throws Exception {
        Path p = proyecto();
        Path esc = escritorio(p);
        nuevoCliente().iniciar("2025-06-18", ROOTS_Y_ELICITATION);
        cliente.llamar(2, "escanear_proyecto", null);
        cliente.responderRaices(cliente.peticion("roots/list"), p);
        JsonNode e = cliente.peticion("elicitation/create");
        JsonNode schema = e.get("params").get("requestedSchema");
        assertThat(propiedades(e)).as("una sola propiedad por pantalla").containsExactly("carpeta");
        assertThat(schema.get("required").toString()).isEqualTo("[\"carpeta\"]");
        JsonNode carpeta = schema.get("properties").get("carpeta");
        assertThat(carpeta.get("title").asText()).isEqualTo("¿Dónde exportamos los tests?");
        assertThat(enumCarpeta(e)).containsExactly(etiquetaEscritorio(esc), "Otra ruta\u2026");
        assertThat(e.get("params").get("message").asText()).endsWith("Elige una opción.");
        assertThat(carpeta.has("default")).as("sin default antes de 2025-11-25").isFalse();
        cliente.aceptar(e, Map.of("carpeta", etiquetaEscritorio(esc)));
        JsonNode guardar = cliente.peticion("elicitation/create");
        assertThat(guardar.get("params").get("requestedSchema").get("properties").get("accion").get("enum").toString())
                .isEqualTo("[\"Establecer como ruta por defecto\",\"Solo esta vez\"]");
        cliente.aceptar(guardar, Map.of("accion", "Solo esta vez"));
        JsonNode r = cliente.resultado(2);
        assertThat(r.get("isError").asBoolean()).as(texto(r)).isFalse();
        assertThat(primeraLinea(r)).isEqualTo("Modo solo lectura sobre " + p + ". Los archivos se crearán únicamente en " + esc);
        assertThat(texto(r)).doesNotContain("Carpeta de salida pendiente")
                .contains("Nota: La carpeta de salida " + esc + " aún no existe: se creará al generar.");
        assertThat(esc).as("validar la salida no crea carpetas").doesNotExist();
        assertThat(archivoRutas()).as("solo esta vez no guarda nada").doesNotExist();

        ObjectNode pagina2 = Json.objeto();
        pagina2.put("pagina", 2);
        cliente.llamar(3, "escanear_proyecto", pagina2);
        JsonNode r2 = cliente.resultado(3);
        assertThat(primeraLinea(r2)).endsWith("Los archivos se crearán únicamente en " + esc);
    }

    @Test
    @Timeout(120)
    void otraRutaLlevaAUnaSegundaPantallaDeTextoYSoloEstaVezNoGuarda() throws Exception {
        Path p = proyecto();
        Path otra = tmp.resolve("otra salida");
        nuevoCliente().iniciar("2025-11-25", ROOTS_Y_ELICITATION);
        cliente.llamar(2, "escanear_proyecto", null);
        cliente.responderRaices(cliente.peticion("roots/list"), p);
        JsonNode e = cliente.peticion("elicitation/create");
        assertThat(e.get("params").get("requestedSchema").get("properties").get("carpeta").get("default").asText())
                .isEqualTo(etiquetaEscritorio(escritorio(p)));
        JsonNode texto = cliente.elegirOtraRuta(e);
        assertThat(texto.get("params").get("message").asText())
                .contains("Escribe la ruta completa de una carpeta fuera del proyecto (solo lectura): " + p);
        cliente.aceptar(texto, Map.of("ruta", otra.toString()));
        JsonNode guardar = cliente.peticion("elicitation/create");
        assertThat(guardar.get("params").get("requestedSchema").get("properties").get("accion").get("enum").toString())
                .contains("Establecer como ruta por defecto", "Solo esta vez");
        cliente.aceptar(guardar, Map.of("accion", "Solo esta vez"));
        JsonNode r = cliente.resultado(2);
        assertThat(primeraLinea(r)).isEqualTo("Modo solo lectura sobre " + p + ". Los archivos se crearán únicamente en "
                + tmp.toRealPath().resolve("otra salida"));
        assertThat(archivoRutas()).doesNotExist();
    }

    @Test
    @Timeout(120)
    void textoLibreEnCarpetaFueraDelEnumSeAceptaYDeclineNoGuarda() throws Exception {
        Path p = proyecto();
        Path libre = tmp.resolve("libre");
        nuevoCliente().iniciar("2025-06-18", ROOTS_Y_ELICITATION);
        cliente.llamar(2, "escanear_proyecto", null);
        cliente.responderRaices(cliente.peticion("roots/list"), p);
        cliente.aceptar(cliente.peticion("elicitation/create"), Map.of("carpeta", libre.toString()));
        cliente.rechazar(cliente.peticion("elicitation/create"));
        JsonNode r = cliente.resultado(2);
        assertThat(primeraLinea(r)).endsWith("Los archivos se crearán únicamente en " + tmp.toRealPath().resolve("libre"));
        assertThat(archivoRutas()).doesNotExist();
    }

    @Test
    @Timeout(120)
    void rutaRelativaODentroDelProyectoVuelveAPreguntarConElMotivo() throws Exception {
        Path p = proyecto();
        nuevoCliente().iniciar("2025-06-18", ROOTS_Y_ELICITATION);
        cliente.llamar(2, "escanear_proyecto", null);
        cliente.responderRaices(cliente.peticion("roots/list"), p);
        cliente.escribirOtraRuta(cliente.peticion("elicitation/create"), "relativa");
        JsonNode e2 = cliente.peticion("elicitation/create");
        assertThat(e2.get("params").get("message").asText()).contains("Indica una ruta absoluta", "¿Dónde exportamos los tests?");
        assertThat(propiedades(e2)).as("tras un rechazo se repite la pantalla de texto").containsExactly("ruta");
        cliente.aceptar(e2, Map.of("ruta", p.resolve("dentro").toString()));
        JsonNode e3 = cliente.peticion("elicitation/create");
        assertThat(e3.get("params").get("message").asText()).contains("está dentro del proyecto");
        assertThat(propiedades(e3)).containsExactly("ruta");
        cliente.aceptar(e3, Map.of("ruta", escritorio(p).toString()));
        cliente.rechazar(cliente.peticion("elicitation/create"));
        JsonNode r = cliente.resultado(2);
        assertThat(r.get("isError").asBoolean()).isFalse();
        assertThat(primeraLinea(r)).endsWith("Los archivos se crearán únicamente en " + escritorio(p));
        assertThat(p.resolve("dentro")).doesNotExist();
    }

    @Test
    @Timeout(120)
    void tresRechazosDeTextoAgotanLosIntentos() throws Exception {
        Path p = proyecto();
        nuevoCliente().iniciar("2025-06-18", "{\"elicitation\":{}}");
        cliente.llamar(2, "escanear_proyecto", ruta(p));
        cliente.escribirOtraRuta(cliente.peticion("elicitation/create"), "relativa1");
        cliente.aceptar(cliente.peticion("elicitation/create"), Map.of("ruta", "relativa2"));
        JsonNode e3 = cliente.peticion("elicitation/create");
        assertThat(e3.get("params").get("message").asText()).contains("«relativa2» es relativa");
        cliente.aceptar(e3, Map.of("ruta", "relativa3"));
        JsonNode r = cliente.resultado(2);
        assertThat(r.get("isError").asBoolean()).isTrue();
        assertThat(texto(r)).contains("«relativa3» es relativa");
        assertThat(archivoRutas()).doesNotExist();
    }

    @Test
    @Timeout(120)
    void otraRutaEscritaEnElEnumConPuntosSeTrataComoOtraRuta() throws Exception {
        Path p = proyecto();
        nuevoCliente().iniciar("2025-06-18", "{\"elicitation\":{}}");
        cliente.llamar(2, "escanear_proyecto", ruta(p));
        cliente.aceptar(cliente.peticion("elicitation/create"), Map.of("carpeta", " otra ruta... "));
        JsonNode texto = cliente.peticion("elicitation/create");
        assertThat(propiedades(texto)).containsExactly("ruta");
        cliente.rechazar(texto);
        JsonNode r = cliente.resultado(2);
        assertThat(r.get("isError").asBoolean()).isFalse();
        assertThat(texto(r)).contains("Carpeta de salida pendiente");
    }

    @Test
    @Timeout(120)
    void declineEscaneaConLaNotaYNoVuelveAPreguntar() throws Exception {
        Path p = proyecto();
        nuevoCliente().iniciar("2025-06-18", ROOTS_Y_ELICITATION);
        cliente.llamar(2, "escanear_proyecto", null);
        cliente.responderRaices(cliente.peticion("roots/list"), p);
        cliente.rechazar(cliente.peticion("elicitation/create"));
        JsonNode r = cliente.resultado(2);
        assertThat(r.get("isError").asBoolean()).isFalse();
        assertThat(primeraLinea(r)).endsWith("Esta operación no crea archivos.");
        assertThat(texto(r)).startsWith(primeraLinea(r) + "\n\nCarpeta de salida pendiente")
                .contains("con estas opciones:", etiquetaEscritorio(escritorio(p)), "[Otra ruta\u2026]",
                        "Solo si elige [Otra ruta\u2026], pídele la ruta completa", "UsuarioServiceImpl");
        cliente.llamar(3, "escanear_proyecto", null);
        assertThat(texto(cliente.resultado(3))).contains("Carpeta de salida pendiente");
    }

    @Test
    @Timeout(120)
    void otraRutaEstablecidaSeUsaSinPreguntarEnUnaSesionNueva() throws Exception {
        Path p = proyecto();
        Path propia = tmp.resolve("mis tests");
        Path propiaReal = tmp.toRealPath().resolve("mis tests");
        nuevoCliente().iniciar("2025-11-25", "{\"elicitation\":{}}");
        cliente.llamar(2, "escanear_proyecto", ruta(p));
        cliente.escribirOtraRuta(cliente.peticion("elicitation/create"), propia.toString());
        JsonNode guardarPrimera = cliente.peticion("elicitation/create");
        assertThat(acciones(guardarPrimera)).containsExactly("Establecer como ruta por defecto", "Solo esta vez");
        cliente.aceptar(guardarPrimera, Map.of("accion", "Establecer como ruta por defecto"));
        JsonNode r = cliente.resultado(2);
        assertThat(primeraLinea(r)).endsWith("Los archivos se crearán únicamente en " + propiaReal);
        assertThat(texto(r)).contains("Nota: Ruta por defecto guardada para Proyecto Demo: " + propiaReal);
        assertThat(archivoRutas()).exists();
        assertThat(Files.readString(archivoRutas(), StandardCharsets.UTF_8)).contains("\"version\"", "\"rutas\"");
        cliente.close();

        nuevoCliente().iniciar("2025-11-25", "{\"elicitation\":{}}");
        cliente.llamar(2, "escanear_proyecto", ruta(p));
        JsonNode r2 = cliente.resultado(2);
        assertThat(r2.get("isError").asBoolean()).as(texto(r2)).isFalse();
        assertThat(primeraLinea(r2)).endsWith("Los archivos se crearán únicamente en " + propiaReal);
        assertThat(texto(r2)).contains("Nota: Ruta por defecto de Proyecto Demo: " + propiaReal + " (para cambiarla: /generartest ruta)")
                .doesNotContain("Carpeta de salida pendiente");
    }

    @Test
    @Timeout(120)
    void rutaGuardadaInvalidaNoSeOfreceYSinEscritorioSoloHayCampoLibre() throws Exception {
        Path p = proyecto();
        Path inst = Files.createDirectories(tmp.resolve("instalacion"));
        new RutasPorDefecto(new com.junitgen.seguridad.PreferenciasSeguras(inst, EntornoSesion.ARCHIVO_RUTAS))
                .guardar(p, p.resolve("dentro"));
        cliente = new ClienteMcp(EntornoSesion.para(Files.createDirectories(tmp.resolve("cwd")),
                Files.createDirectories(tmp.resolve("home")), inst, null, k -> null));
        cliente.iniciar("2025-06-18", "{\"elicitation\":{}}");
        cliente.llamar(2, "escanear_proyecto", ruta(p));
        JsonNode e = cliente.peticion("elicitation/create");
        JsonNode props = e.get("params").get("requestedSchema").get("properties");
        assertThat(props.has("carpeta")).as("sin opciones predefinidas se va directo al texto").isFalse();
        assertThat(propiedades(e)).containsExactly("ruta");
        cliente.rechazar(e);
        JsonNode r = cliente.resultado(2);
        assertThat(texto(r)).contains("Carpeta de salida pendiente", "no hay opciones predefinidas", "Nota: Ruta por defecto guardada ignorada (",
                "está dentro del proyecto");
    }

    @Test
    @Timeout(120)
    void sinElicitationLaInstruccionListaLasOpcionesYGuardarDesdeElChatPersiste() throws Exception {
        Path p = proyecto();
        nuevoCliente().iniciar("2025-06-18", "{}");
        cliente.llamar(2, "escanear_proyecto", ruta(p));
        JsonNode r = cliente.resultado(2);
        assertThat(texto(r)).contains("Carpeta de salida pendiente", "[" + etiquetaEscritorio(escritorio(p)) + "] [Otra ruta\u2026]",
                "se creará al generar", "Elija lo que elija", "[Establecer como ruta por defecto] [Solo esta vez]", "guardarRutaPorDefecto=true");
        Path chat = tmp.resolve("desde el chat");
        ObjectNode args = ruta(p);
        args.put("carpetaSalida", chat.toString());
        args.put("guardarRutaPorDefecto", true);
        cliente.llamar(3, "escanear_proyecto", args);
        JsonNode r2 = cliente.resultado(3);
        assertThat(texto(r2)).contains("Nota: Ruta por defecto guardada");
        assertThat(new RutasPorDefecto(new com.junitgen.seguridad.PreferenciasSeguras(tmp.resolve("instalacion"),
                EntornoSesion.ARCHIVO_RUTAS)).de(p)).isEqualTo(tmp.toRealPath().resolve("desde el chat"));
    }

    @Test
    @Timeout(120)
    void generarSinCarpetaSalidaNiGuardadaDevuelveLaInstruccion() throws Exception {
        Path p = proyecto();
        nuevoCliente().iniciar("2025-06-18", "{}");
        cliente.llamar(2, "escanear_proyecto", ruta(p));
        assertThat(texto(cliente.resultado(2))).contains("Carpeta de salida pendiente");
        String spec = Fixtures.j("{'version':1,'proyecto':{'ruta':'" + p.toString().replace('\\', '/') + "'},'servicios':[{'clase':"
                + "'com.ejemplo.service.UsuarioServiceImpl','casos':["
                + "{'id':'activar-ok','metodo':'activar','escenario':'activaUsuarioInactivo','datos':[{'tipo':'Usuario','nombre':'u',"
                + "'valor':'new Usuario(5L, \\'Leo\\')'}],'stubs':[{'mock':'repo','llamada':'findById(5L)','devuelve':'Optional.of(u)'},"
                + "{'mock':'repo','llamada':'save(u)','devuelve':'u'}],'argumentos':['5L'],'esperado':{'tipo':'noNulo'}}]}]}");
        ObjectNode gen = Json.objeto();
        gen.set("spec", Json.leer(spec));
        cliente.llamar(3, "generar_tests", gen);
        JsonNode r = cliente.resultado(3);
        assertThat(r.get("isError").asBoolean()).isTrue();
        assertThat(texto(r)).contains("Error: Carpeta de salida pendiente", escritorio(p).toString());
        assertThat(escritorio(p)).doesNotExist();
    }

    @Test
    @Timeout(120)
    void esquemasConRutasOpcionales() throws Exception {
        nuevoCliente().iniciar("2025-06-18", "{}");
        cliente.enviar("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}");
        JsonNode tools = cliente.resultado(2).get("tools");
        for (JsonNode t : tools) {
            List<String> req = new ArrayList<>();
            t.get("inputSchema").get("required").forEach(x -> req.add(x.asText()));
            JsonNode props = t.get("inputSchema").get("properties");
            switch (t.get("name").asText()) {
                case "escanear_proyecto" -> {
                    assertThat(req).isEmpty();
                    assertThat(props.get("servicios").get("type").asText()).isEqualTo("array");
                    assertThat(props.has("guardarRutaPorDefecto")).isTrue();
                    assertThat(props.get("numServicios").get("type").asText()).isEqualTo("integer");
                    assertThat(props.get("numServicios").get("maximum").asInt()).isEqualTo(200);
                    assertThat(props.has("cantidad")).isFalse();
                }
                case "ejecutar_tests" -> {
                    assertThat(req).isEmpty();
                    assertThat(props.get("todos").get("type").asText()).isEqualTo("boolean");
                    assertThat(props.get("casos").get("type").asText()).isEqualTo("array");
                }
                case "cambiar_ruta_salida" -> {
                    assertThat(req).isEmpty();
                    assertThat(props.has("carpetaSalida")).isTrue();
                    assertThat(props.has("guardarRutaPorDefecto")).isTrue();
                }
                case "registrar_analisis_ia" -> {
                    assertThat(req).containsExactly("analisis");
                    assertThat(props.get("analisis").get("items").get("properties").get("veredicto").get("enum").toString())
                            .contains("error_ia", "bug_servicio", "dudoso");
                }
                case "generar_tests" -> {
                    assertThat(req).doesNotContain("carpetaSalida");
                    assertThat(props.get("decisionVerificacion").get("enum").toString()).contains("probar", "descartar");
                    assertThat(props.has("guardarRutaPorDefecto")).isTrue();
                }
                case "resolver_fallo" -> assertThat(req).containsExactly("caso", "decision");
                default -> {
                }
            }
        }
    }

    // ------------------------------------------------------------- ruta por defecto y cambiar_ruta_salida

    private RutasPorDefecto preferencias() throws IOException {
        return new RutasPorDefecto(new com.junitgen.seguridad.PreferenciasSeguras(Files.createDirectories(tmp.resolve("instalacion")),
                EntornoSesion.ARCHIVO_RUTAS));
    }

    private static List<String> acciones(JsonNode peticion) {
        List<String> r = new ArrayList<>();
        peticion.get("params").get("requestedSchema").get("properties").get("accion").get("enum").forEach(x -> r.add(x.asText()));
        return r;
    }

    @Test
    @Timeout(120)
    void escritorioYEstablecerLaGuardaComoRutaPorDefecto() throws Exception {
        Path p = proyecto();
        Path esc = escritorio(p);
        nuevoCliente().iniciar("2025-11-25", "{\"elicitation\":{}}");
        cliente.llamar(2, "escanear_proyecto", ruta(p));
        cliente.aceptar(cliente.peticion("elicitation/create"), Map.of("carpeta", etiquetaEscritorio(esc)));
        JsonNode guardar = cliente.peticion("elicitation/create");
        assertThat(acciones(guardar)).containsExactly("Establecer como ruta por defecto", "Solo esta vez");
        cliente.aceptar(guardar, Map.of("accion", "Establecer como ruta por defecto"));
        JsonNode r = cliente.resultado(2);
        assertThat(texto(r)).contains("Nota: Ruta por defecto guardada para Proyecto Demo: " + esc);
        assertThat(preferencias().de(p)).isEqualTo(esc);
    }

    @Test
    @Timeout(120)
    void rutaPorDefectoValidaSeUsaSinPreguntarTambienSinElicitation() throws Exception {
        Path p = proyecto();
        Path otra = Files.createDirectories(tmp.resolve("guardada")).toRealPath();
        preferencias().guardar(p, otra);
        nuevoCliente().iniciar("2025-11-25", "{}");
        cliente.llamar(2, "escanear_proyecto", ruta(p));
        JsonNode r = cliente.resultado(2);
        assertThat(primeraLinea(r)).endsWith("Los archivos se crearán únicamente en " + otra);
        assertThat(texto(r)).contains("Nota: Ruta por defecto de Proyecto Demo: " + otra + " (para cambiarla: /generartest ruta)")
                .doesNotContain("Carpeta de salida pendiente");
    }

    @Test
    @Timeout(120)
    void cambiarRutaMantenerLaRutaPorDefecto() throws Exception {
        Path p = proyecto();
        Path otra = Files.createDirectories(tmp.resolve("guardada")).toRealPath();
        preferencias().guardar(p, otra);
        nuevoCliente().iniciar("2025-11-25", "{\"elicitation\":{}}");
        cliente.llamar(2, "cambiar_ruta_salida", ruta(p));
        JsonNode e = cliente.peticion("elicitation/create");
        String mantener = "Mantener la ruta por defecto: " + otra;
        assertThat(enumCarpeta(e)).containsExactly(mantener, "Otra ruta\u2026");
        assertThat(propiedades(e)).containsExactly("carpeta");
        assertThat(e.get("params").get("requestedSchema").get("properties").get("carpeta").get("default").asText()).isEqualTo(mantener);
        cliente.aceptar(e, Map.of("carpeta", mantener));
        JsonNode r = cliente.resultado(2);
        assertThat(r.get("isError").asBoolean()).as(texto(r)).isFalse();
        assertThat(texto(r)).contains("Se mantiene la ruta por defecto: " + otra);
        assertThat(preferencias().de(p)).isEqualTo(otra);
    }

    @Test
    @Timeout(120)
    void cambiarRutaOtraSoloEstaVezNoGuardaYSeRecuerdaEnLaSesion() throws Exception {
        Path p = proyecto();
        Path otraReal = tmp.toRealPath().resolve("otra");
        nuevoCliente().iniciar("2025-11-25", "{\"elicitation\":{}}");
        cliente.llamar(2, "cambiar_ruta_salida", ruta(p));
        JsonNode e = cliente.peticion("elicitation/create");
        assertThat(etiquetas(e)).containsExactly("Mantener la ruta por defecto: " + escritorio(p));
        cliente.escribirOtraRuta(e, tmp.resolve("otra").toString());
        JsonNode guardar = cliente.peticion("elicitation/create");
        assertThat(acciones(guardar)).containsExactly("Solo esta vez", "Establecer como ruta por defecto");
        cliente.aceptar(guardar, Map.of("accion", "Solo esta vez"));
        JsonNode r = cliente.resultado(2);
        assertThat(texto(r)).contains("Carpeta de salida cambiada solo esta vez (esta sesión): " + otraReal);
        assertThat(archivoRutas()).doesNotExist();
        cliente.llamar(3, "escanear_proyecto", ruta(p));
        assertThat(primeraLinea(cliente.resultado(3))).endsWith("Los archivos se crearán únicamente en " + otraReal);
    }

    @Test
    @Timeout(120)
    void cambiarRutaOtraEstablecerComoRutaPorDefecto() throws Exception {
        Path p = proyecto();
        Path otraReal = tmp.toRealPath().resolve("otra");
        nuevoCliente().iniciar("2025-11-25", "{\"elicitation\":{}}");
        cliente.llamar(2, "cambiar_ruta_salida", ruta(p));
        cliente.escribirOtraRuta(cliente.peticion("elicitation/create"), tmp.resolve("otra").toString());
        cliente.aceptar(cliente.peticion("elicitation/create"), Map.of("accion", "Establecer como ruta por defecto"));
        JsonNode r = cliente.resultado(2);
        assertThat(texto(r)).contains("Ruta por defecto establecida: " + otraReal);
        assertThat(preferencias().de(p)).isEqualTo(otraReal);
    }

    @Test
    @Timeout(120)
    void cambiarRutaRechazoVuelveAPreguntarConElMotivoYDeclineNoCambiaNada() throws Exception {
        Path p = proyecto();
        nuevoCliente().iniciar("2025-11-25", "{\"elicitation\":{}}");
        cliente.llamar(2, "cambiar_ruta_salida", ruta(p));
        cliente.escribirOtraRuta(cliente.peticion("elicitation/create"), p.resolve("dentro").toString());
        JsonNode e2 = cliente.peticion("elicitation/create");
        assertThat(e2.get("params").get("message").asText()).contains("está dentro del proyecto", "¿Dónde exportamos los tests?");
        assertThat(propiedades(e2)).containsExactly("ruta");
        cliente.rechazar(e2);
        JsonNode r = cliente.resultado(2);
        assertThat(r.get("isError").asBoolean()).isFalse();
        assertThat(texto(r)).contains("Sin cambios: no hay ruta por defecto guardada.");
        assertThat(archivoRutas()).doesNotExist();
        assertThat(p.resolve("dentro")).doesNotExist();
    }

    @Test
    @Timeout(120)
    void cambiarRutaSinElicitationDevuelveLaInstruccionYDespuesEstablece() throws Exception {
        Path p = proyecto();
        nuevoCliente().iniciar("2025-11-25", "{}");
        cliente.llamar(2, "cambiar_ruta_salida", ruta(p));
        JsonNode r = cliente.resultado(2);
        assertThat(texto(r)).contains("Cambio de ruta pendiente", "[Mantener la ruta por defecto: " + escritorio(p) + "] [Otra ruta\u2026]",
                "Solo si elige [Otra ruta\u2026], pídele la ruta completa",
                "[Solo esta vez] [Establecer como ruta por defecto]", "guardarRutaPorDefecto=true");
        ObjectNode args = ruta(p);
        args.put("carpetaSalida", tmp.resolve("desde el chat").toString());
        args.put("guardarRutaPorDefecto", true);
        cliente.llamar(3, "cambiar_ruta_salida", args);
        JsonNode r2 = cliente.resultado(3);
        assertThat(texto(r2)).contains("Ruta por defecto establecida: " + tmp.toRealPath().resolve("desde el chat"));
        assertThat(preferencias().de(p)).isEqualTo(tmp.toRealPath().resolve("desde el chat"));

        ObjectNode dentro = ruta(p);
        dentro.put("carpetaSalida", p.resolve("dentro").toString());
        cliente.llamar(4, "cambiar_ruta_salida", dentro);
        JsonNode r3 = cliente.resultado(4);
        assertThat(r3.get("isError").asBoolean()).isTrue();
        assertThat(texto(r3)).contains("Rechazo de seguridad", "está dentro del proyecto");
    }
}
