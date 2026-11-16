package com.junitgen.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.junitgen.Fachada;
import com.junitgen.Fixtures;
import com.junitgen.escaneo.InformeEscaneo;
import com.junitgen.util.Json;
import com.junitgen.util.LimitesRespuesta;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.junitgen.mcp.ClienteMcp.texto;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Presupuesto de tamaño: con un proyecto grande (servicio de 60+ métodos de cuerpo largo, uno mayor que
 * MAX_CUERPO_PARTE, muchos tipos auxiliares, muchas dependencias y muchos servicios) ninguna respuesta
 * supera MAX_RESPUESTA bytes UTF-8 y lo omitido se puede pedir por partes.
 */
class RespuestasCompactasTest {

    static final String GRAN = "com.ejemplo.grande.GranService";
    static final int METODOS = 60;
    static final int DTOS = 30;
    static final int REPOS = 20;
    static final int SERVICIOS = 70;

    @TempDir
    Path tmp;

    private ClienteMcp cliente;
    private int id = 2;

    @AfterEach
    void cerrar() throws Exception {
        if (cliente != null) {
            cliente.close();
        }
    }

    /** Proyecto grande: la fixture Spring más GranService, DTOs, repositorios y muchos servicios. */
    static Path proyectoGrande(Path destino) throws IOException {
        Path p = Fixtures.copiar("proyecto-spring", destino);
        Path g = p.resolve("src/main/java/com/ejemplo/grande");
        for (int i = 0; i < DTOS; i++) {
            StringBuilder sb = new StringBuilder("package com.ejemplo.grande;\n\npublic class Dto" + i + " {\n\n");
            for (int k = 0; k < 20; k++) {
                sb.append("    private String campoConNombreLargo").append(k).append(";\n");
            }
            sb.append("\n    public Dto").append(i).append("() {\n    }\n\n");
            sb.append("    public Dto").append(i).append("(String a) {\n        this.campoConNombreLargo0 = a;\n    }\n\n");
            sb.append("    public Dto").append(i).append("(String a, String b) {\n        this.campoConNombreLargo0 = a;\n        this.campoConNombreLargo1 = b;\n    }\n\n");
            sb.append("    public Dto").append(i).append("(String a, String b, String c) {\n        this.campoConNombreLargo2 = c;\n    }\n\n");
            sb.append("    public Dto").append(i).append("(int x) {\n        this.campoConNombreLargo3 = String.valueOf(x);\n    }\n\n");
            for (int k = 0; k < 20; k++) {
                sb.append("    public String getCampoConNombreLargo").append(k).append("() {\n        return campoConNombreLargo").append(k)
                        .append(";\n    }\n\n");
            }
            Fixtures.escribir(g.resolve("Dto" + i + ".java"), sb.append("}\n").toString());
        }
        for (int i = 0; i < REPOS; i++) {
            Fixtures.escribir(g.resolve("Repositorio" + i + ".java"), "package com.ejemplo.grande;\n\npublic interface Repositorio" + i
                    + " {\n\n    Dto" + i + " buscar(String id);\n}\n");
        }
        StringBuilder s = new StringBuilder("package com.ejemplo.grande;\n\nimport org.springframework.stereotype.Service;\n\n@Service\n"
                + "public class GranService {\n\n");
        for (int i = 0; i < REPOS; i++) {
            s.append("    private final Repositorio").append(i).append(" repositorio").append(i).append(";\n");
        }
        s.append("\n    public GranService(");
        for (int i = 0; i < REPOS; i++) {
            s.append(i == 0 ? "" : ", ").append("Repositorio").append(i).append(" repositorio").append(i);
        }
        s.append(") {\n");
        for (int i = 0; i < REPOS; i++) {
            s.append("        this.repositorio").append(i).append(" = repositorio").append(i).append(";\n");
        }
        s.append("    }\n\n");
        for (int i = 0; i < METODOS; i++) {
            s.append(metodo("operacion" + String.format("%02d", i), i % REPOS, i % DTOS, 25, i));
        }
        s.append(metodo("operacionGigante", 0, 0, 70, 99));
        for (int i = 0; i < METODOS; i++) {
            s.append("    private String ayuda").append(i).append("(String s) {\n        String r = s.trim();\n");
            for (int k = 0; k < 20; k++) {
                s.append("        r = r + \"ayuda interna ").append(i).append(" paso ").append(k).append(" con texto de relleno\";\n");
            }
            s.append("        return r;\n    }\n\n");
        }
        Fixtures.escribir(g.resolve("GranService.java"), s.append("}\n").toString());
        Path sv = p.resolve("src/main/java/com/ejemplo/servicios");
        for (int i = 0; i < SERVICIOS; i++) {
            String n = "ServicioDeNegocioConNombreLargo" + i + "Service";
            Fixtures.escribir(sv.resolve(n + ".java"), "package com.ejemplo.servicios;\n\nimport org.springframework.stereotype.Service;\n\n"
                    + "@Service\npublic class " + n + " {\n\n    public int calcular(int a) {\n        if (a > 0) {\n            return a * 2;\n"
                    + "        }\n        return -a;\n    }\n\n    public String describir(String a) {\n        return a == null ? \"vacio\" : a.trim();\n"
                    + "    }\n}\n");
        }
        return p.toRealPath();
    }

    private static String metodo(String nombre, int repo, int dto, int lineas, int ayuda) {
        StringBuilder s = new StringBuilder("    public String ").append(nombre).append("(String entrada) {\n");
        s.append("        String r = entrada == null ? \"\" : entrada;\n");
        s.append("        Dto").append(dto).append(" d = repositorio").append(repo).append(".buscar(r);\n");
        s.append("        r = r + (d == null ? \"-\" : d.getCampoConNombreLargo0());\n");
        for (int k = 0; k < lineas; k++) {
            s.append("        r = r + \"linea ").append(k).append(" del método ").append(nombre).append(" con texto de relleno largo\";\n");
        }
        if (ayuda < METODOS) {
            s.append("        r = ayuda").append(ayuda).append("(r);\n");
        }
        return s.append("        return r;\n    }\n\n").toString();
    }

    private JsonNode llamar(String herramienta, ObjectNode args) throws IOException {
        int n = id++;
        cliente.llamar(n, herramienta, args);
        JsonNode r = cliente.resultado(n);
        assertThat(LimitesRespuesta.bytes(texto(r))).as(herramienta + " " + args + " → " + LimitesRespuesta.bytes(texto(r)) + " bytes")
                .isLessThanOrEqualTo(LimitesRespuesta.MAX_RESPUESTA);
        return r;
    }

    private static JsonNode cuerpo(JsonNode r) {
        return PuertasEjecutarTest.cuerpoJson(r);
    }

    private static final Pattern SIGUIENTE_SECCION = Pattern.compile("Siguiente: escanear_proyecto \\{seccion:\"(\\w+)\", pagina:(\\d+)}");

    @Test
    @Timeout(900)
    void ningunaRespuestaSuperaElPresupuestoYTodoSePuedePedirPorPartes() throws Exception {
        Path p = proyectoGrande(tmp.resolve("Grande"));
        cliente = new ClienteMcp(ClienteMcp.entorno(tmp));
        cliente.iniciar("2025-11-25", "{}");

        // Escaneo sin argumentos y con un plan de 200 servicios: resúmenes dentro del presupuesto.
        JsonNode sinArgs = llamar("escanear_proyecto", obj("ruta", p.toString(), "carpetaSalida", tmp.resolve("salida").toString()));
        assertThat(texto(sinArgs)).contains("## Servicios detectados", "¿Cuántos servicios (N) o qué servicios quieres probar?");
        ObjectNode plan = obj("ruta", p.toString());
        plan.put("numServicios", 200);
        String resumen = texto(llamar("escanear_proyecto", plan));
        assertThat(resumen).contains("## Plan de generación", "**Total: ", "## Resumen", "## Siguiente paso", "Primera llamada: detalle_servicio",
                "servicios más: escanear_proyecto {seccion:\"plan\", pagina:2}", "Secciones bajo petición");

        // Todas las páginas de todas las secciones.
        for (String seccion : InformeEscaneo.SECCIONES) {
            int pagina = 1;
            for (int vueltas = 0; vueltas < 200; vueltas++) {
                ObjectNode a = obj("seccion", seccion);
                a.put("pagina", pagina);
                String t = texto(llamar("escanear_proyecto", a));
                Matcher m = SIGUIENTE_SECCION.matcher(t);
                if (!m.find()) {
                    break;
                }
                assertThat(m.group(1)).isEqualTo(seccion);
                pagina = Integer.parseInt(m.group(2));
            }
        }

        // Detalle de GranService siguiendo paginacion.siguiente: cada método exactamente una vez y el cuerpo largo reconstruido.
        List<String> claves = new ArrayList<>();
        Map<String, StringBuilder> partes = new LinkedHashMap<>();
        Map<String, Integer> totalPartes = new LinkedHashMap<>();
        JsonNode args = obj("clase", GRAN);
        boolean cabecera = false;
        for (int vueltas = 0; vueltas < 200 && args != null && !args.isNull(); vueltas++) {
            JsonNode r = llamar("detalle_servicio", (ObjectNode) args);
            assertThat(r.get("isError").asBoolean()).as(texto(r)).isFalse();
            JsonNode d = cuerpo(r);
            if (d.has("plantillaSpec")) {
                assertThat(cabecera).as("la cabecera solo llega con desde=1").isFalse();
                cabecera = true;
                assertThat(d.get("dependencias").size()).isGreaterThanOrEqualTo(REPOS);
            }
            for (JsonNode m : d.get("metodos")) {
                String clave = m.get("clave").asText();
                if (m.has("cuerpoParte")) {
                    partes.computeIfAbsent(clave, k -> new StringBuilder()).append(m.get("cuerpo").asText());
                    totalPartes.put(clave, m.get("cuerpoPartes").asInt());
                    if (m.get("cuerpoParte").asInt() > 1) {
                        continue;
                    }
                }
                claves.add(clave);
            }
            assertThat(d.has("ampliar")).isTrue();
            args = d.get("paginacion").get("siguiente");
        }
        assertThat(cabecera).isTrue();
        assertThat(claves).doesNotHaveDuplicates().hasSize(METODOS + 1);
        assertThat(totalPartes).containsKey("operacionGigante(String)");
        assertThat(totalPartes.get("operacionGigante(String)")).isGreaterThan(1);
        ObjectNode completo = new Fachada().detalle(GRAN, p, null, List.of("operacionGigante"));
        assertThat(partes.get("operacionGigante(String)").toString()).isEqualTo(completo.get("metodos").get(0).get("cuerpo").asText());

        // desde > total: respuesta vacía con siguiente null.
        ObjectNode fuera = obj("clase", GRAN);
        fuera.put("desde", 500);
        JsonNode vacio = cuerpo(llamar("detalle_servicio", fuera));
        assertThat(vacio.get("metodos").size()).isZero();
        assertThat(vacio.get("paginacion").get("siguiente").isNull()).isTrue();

        // Tipos auxiliares completos, paginados con 'siguiente'.
        ObjectNode tipos = obj("clase", GRAN);
        ArrayNode lista = tipos.putArray("tipos");
        for (int i = 0; i < DTOS; i++) {
            lista.add("com.ejemplo.grande.Dto" + i);
        }
        List<String> recibidos = new ArrayList<>();
        JsonNode targs = tipos;
        for (int vueltas = 0; vueltas < 100 && targs != null && !targs.isNull(); vueltas++) {
            JsonNode d = cuerpo(llamar("detalle_servicio", (ObjectNode) targs));
            d.get("tiposAuxiliares").forEach(t -> {
                recibidos.add(t.get("fqn").asText());
                assertThat(t.get("metodos").size()).isEqualTo(20);
            });
            targs = d.get("siguiente");
        }
        assertThat(recibidos).doesNotHaveDuplicates().hasSize(DTOS);

        // validar_spec con muchos errores.
        StringBuilder malos = new StringBuilder();
        for (int i = 0; i < 80; i++) {
            malos.append(i == 0 ? "" : ",").append("{'id':'malo-").append(i).append("','metodo':'noExiste").append(i)
                    .append("','escenario':'escenarioInventado','argumentos':[],'esperado':{'tipo':'nulo'}}");
        }
        ObjectNode val = Json.objeto();
        val.set("spec", Json.leer(spec(p, malos.toString())));
        JsonNode v = cuerpo(llamar("validar_spec", val));
        assertThat(v.get("valido").asBoolean()).isFalse();
        assertThat(v.get("totales").get("errores").asInt()).isGreaterThanOrEqualTo(80);
        assertThat(v.has("recortes")).isTrue();
        assertThat(v.get("comoVerElResto").asText()).contains("vuelve a llamar a validar_spec");

        // generar_tests con un caso por método (todos fallan por aserción) y ejecutar_tests con todas sus páginas.
        StringBuilder casos = new StringBuilder();
        for (int i = 0; i <= METODOS; i++) {
            String metodo = i == METODOS ? "operacionGigante" : "operacion" + String.format("%02d", i);
            casos.append(i == 0 ? "" : ",").append("{'id':'op-").append(i).append("','metodo':'").append(metodo)
                    .append("','escenario':'devuelveTexto").append(i).append("','argumentos':['\\'x\\''],'esperado':{'tipo':'valor',")
                    .append("'valor':'\\'no\\''}}");
        }
        ObjectNode gen = Json.objeto();
        gen.set("spec", Json.leer(spec(p, casos.toString())));
        JsonNode g = cuerpo(llamar("generar_tests", gen));
        assertThat(g.get("resumenSesion").get("casosGenerados").asInt()).as(g.toString()).isEqualTo(METODOS + 1);

        ObjectNode ej = Json.objeto();
        ej.put("parcial", true);
        JsonNode r = llamar("ejecutar_tests", ej);
        assertThat(r.get("isError").asBoolean()).as(texto(r)).isFalse();
        JsonNode e = cuerpo(r);
        assertThat(e.get("resumenSesion").get("casosGenerados").asInt()).isEqualTo(METODOS + 1);
        List<String> vistos = new ArrayList<>();
        e.get("resultados").forEach(x -> vistos.add(x.get("caso").asText()));
        JsonNode sig = e.path("paginacion").path("siguiente");
        for (int vueltas = 0; vueltas < 100 && sig.isObject(); vueltas++) {
            JsonNode pag = cuerpo(llamar("ejecutar_tests", (ObjectNode) sig));
            pag.get("resultados").forEach(x -> vistos.add(x.get("caso").asText()));
            sig = pag.path("paginacion").path("siguiente");
        }
        assertThat(vistos).doesNotHaveDuplicates().hasSize(METODOS + 1);
        assertThat(e.get("resumenSesion").get("ejecutados").asInt()).as(e.toString()).isEqualTo(METODOS + 1);
        assertThat(e.get("paginacion").get("paginas").asInt()).isGreaterThan(1);
    }

    private static String spec(Path p, String casos) {
        return Fixtures.j("{'version':1,'proyecto':{'ruta':'" + p.toString().replace('\\', '/') + "'},'servicios':[{'clase':'" + GRAN
                + "','casos':[" + casos + "]}]}");
    }

    private static ObjectNode obj(String... kv) {
        ObjectNode a = Json.objeto();
        for (int i = 0; i < kv.length; i += 2) {
            a.put(kv[i], kv[i + 1]);
        }
        return a;
    }
}
