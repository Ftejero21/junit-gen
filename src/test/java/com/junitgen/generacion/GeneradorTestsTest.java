package com.junitgen.generacion;

import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.junitgen.Fachada;
import com.junitgen.Fixtures;
import com.junitgen.escaneo.IndiceTipos;
import com.junitgen.spec.CasoSpec;
import com.junitgen.spec.Spec;
import com.junitgen.util.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static com.junitgen.Fixtures.j;
import static org.assertj.core.api.Assertions.assertThat;

class GeneradorTestsTest {

    @TempDir
    Path tmp;

    private static final String CASO_EXCEPCION = "{'id':'buscar-no-existe','metodo':'buscar','escenario':'lanzaSiNoExiste',"
            + "'stubs':[{'mock':'repo','llamada':'findById(9L)','devuelve':'Optional.empty()'}],'argumentos':['9L'],"
            + "'esperado':{'tipo':'excepcion','excepcion':'IllegalArgumentException','mensaje':'No existe 9'}}";
    private static final String CASO_VALOR = "{'id':'contar-cero','metodo':'contarActivos','escenario':'sinUsuarios',"
            + "'stubs':[{'mock':'repo','llamada':'findAll()','devuelve':'new ArrayList<>()'}],'argumentos':[],"
            + "'esperado':{'tipo':'valor','valor':'0'},'verificaciones':[{'mock':'notificador','sinInteracciones':true}]}";
    private static final String CASO_NUEVO = "{'id':'contar-uno','metodo':'contarActivos','escenario':'unActivo',"
            + "'datos':[{'tipo':'Usuario','nombre':'u','valor':'new Usuario(1L, \\'A\\')'}],'preparacion':['u.setActivo(true)'],"
            + "'stubs':[{'mock':'repo','llamada':'findAll()','devuelve':'new ArrayList<>(Arrays.asList(u))'}],'argumentos':[],"
            + "'esperado':{'tipo':'valor','valor':'1'}}";

    private static String spec(Path proyecto, String estilo, String... casos) {
        return j("{'version':1,'proyecto':{'ruta':'" + proyecto.toString().replace('\\', '/') + "'}" + (estilo == null ? "" : ",'estilo':" + estilo)
                + ",'servicios':[{'clase':'com.ejemplo.service.UsuarioServiceImpl','casos':[" + String.join(",", casos) + "]}]}");
    }

    private static Path archivo(Path salida) {
        return salida.resolve("src/test/java/com/ejemplo/service/UsuarioServiceImplGeneradoTest.java");
    }

    private static String generarYLeer(Path proyecto, Path salida, String spec) throws Exception {
        Fachada.Generacion g = new Fachada().generar(Json.leer(spec), null, salida, proyecto, new GeneradorTests.Opciones());
        assertThat(g.resultado().errores).as("errores: %s", g.resultado().errores).isEmpty();
        String texto = Files.readString(archivo(salida), StandardCharsets.UTF_8);
        ParseResult<CompilationUnit> pr = IndiceTipos.parser().parse(texto);
        assertThat(pr.isSuccessful()).as("el test generado debe ser Java válido: %s", pr.getProblems()).isTrue();
        return texto;
    }

    @Test
    void junit5ConExtensionYAsercionesJunit() throws Exception {
        Path p = Fixtures.copiar("proyecto-spring", tmp.resolve("p"));
        String t = generarYLeer(p, tmp.resolve("s"), spec(p, "{'aserciones':'junit'}", CASO_EXCEPCION, CASO_VALOR));
        assertThat(t).contains("@ExtendWith(MockitoExtension.class)", "@InjectMocks", "@Mock", "class UsuarioServiceImplGeneradoTest",
                "assertThrows(IllegalArgumentException.class, () -> service.buscar(9L))", "assertEquals(\"No existe 9\", ex.getMessage())",
                "assertEquals(0, resultado)", "verifyNoInteractions(notificador)", "void buscar_lanzaSiNoExiste()",
                "import java.util.ArrayList;", "// junit-gen:imports-fin", "// junit-gen:casos-fin", "// junit-gen:fixtures-fin");
        assertThat(t).doesNotContain("public class", "lenient");
        assertThat(t).startsWith("// junit-gen:montaje=");
    }

    @Test
    void junit5ConAssertj() throws Exception {
        Path p = Fixtures.copiar("proyecto-spring", tmp.resolve("p"));
        String t = generarYLeer(p, tmp.resolve("s"), spec(p, "{'aserciones':'assertj','displayName':true,'comentariosGwt':true}",
                CASO_EXCEPCION.replace("'escenario'", "'descripcion':'Lanza */ si\\nno existe','escenario'"), CASO_VALOR));
        assertThat(t).contains("assertThatThrownBy(() -> service.buscar(9L))", ".isInstanceOf(IllegalArgumentException.class)",
                ".hasMessage(\"No existe 9\")", "assertThat(resultado).isEqualTo(0)", "import static org.assertj.core.api.Assertions.assertThat;",
                "@DisplayName(\"Lanza * / si no existe\")", "// given", "// then");
    }

    @Test
    void junit5SinExtensionUsaOpenMocks() throws Exception {
        Path p = Fixtures.copiar("proyecto-spring", tmp.resolve("p"));
        String pom = Files.readString(p.resolve("pom.xml"), StandardCharsets.UTF_8);
        int i = pom.indexOf("<artifactId>mockito-junit-jupiter</artifactId>");
        int ini = pom.lastIndexOf("<dependency>", i);
        int fin = pom.indexOf("</dependency>", i) + "</dependency>".length();
        Files.writeString(p.resolve("pom.xml"), pom.substring(0, ini) + pom.substring(fin), StandardCharsets.UTF_8);
        String t = generarYLeer(p, tmp.resolve("s"), spec(p, null, CASO_EXCEPCION));
        assertThat(t).contains("mocks = MockitoAnnotations.openMocks(this);", "@AfterEach", "mocks.close();", "@BeforeEach");
        assertThat(t).doesNotContain("@ExtendWith");
    }

    @Test
    void junit4ConRunnerMockito1YTryCatch() throws Exception {
        Path p = Fixtures.copiar("proyecto-legacy", tmp.resolve("l"));
        String spec = j("{'version':1,'proyecto':{'ruta':'" + p.toString().replace('\\', '/') + "'},'servicios':[{'clase':"
                + "'com.legacy.negocio.FacturaManager','casos':["
                + "{'id':'negativa','metodo':'importeConIva','escenario':'baseNegativaConMensaje','argumentos':['2L','-1.0'],"
                + "'esperado':{'tipo':'excepcion','excepcion':'IllegalArgumentException','mensajeContiene':'negativa'}},"
                + "{'id':'exento','metodo':'importeConIva','escenario':'clienteExento','stubs':[{'mock':'clienteDao','llamada':"
                + "'tipoCliente(3L)','devuelve':'\\'EXENTO\\''}],'argumentos':['3L','100.0'],'esperado':{'tipo':'valor','valor':'100.0'}}"
                + "]}]}");
        Path salida = tmp.resolve("s");
        Fachada.Generacion g = new Fachada().generar(Json.leer(spec), null, salida, null, new GeneradorTests.Opciones());
        assertThat(g.resultado().errores).isEmpty();
        String t = Files.readString(salida.resolve("src/test/java/com/legacy/negocio/FacturaManagerGeneradoTest.java"), StandardCharsets.UTF_8);
        assertThat(IndiceTipos.parser().parse(t).isSuccessful()).isTrue();
        assertThat(t).contains("@RunWith(MockitoJUnitRunner.class)", "import org.mockito.runners.MockitoJUnitRunner;", "public class",
                "public void importeConIva_baseNegativaConMensaje()", "fail(\"Se esperaba IllegalArgumentException\")",
                "} catch (IllegalArgumentException ex) {", "assertTrue(ex.getMessage().contains(\"negativa\"))",
                "import static org.junit.Assert.assertEquals;", "assertEquals(100.0, resultado, 0.0);");
        assertThat(t).doesNotContain("->");
    }

    @Test
    void segundaEjecucionSoloAnadeYLoExistenteQuedaIdentico() throws Exception {
        Path p = Fixtures.copiar("proyecto-spring", tmp.resolve("p"));
        Path salida = tmp.resolve("s");
        String primero = generarYLeer(p, salida, spec(p, null, CASO_EXCEPCION, CASO_VALOR));
        byte[] bytesPrimero = Files.readAllBytes(archivo(salida));

        Fachada.Generacion g2 = new Fachada().generar(Json.leer(spec(p, null, CASO_EXCEPCION, CASO_VALOR, CASO_NUEVO)), null, salida, p,
                new GeneradorTests.Opciones());
        assertThat(g2.resultado().archivosAmpliados).hasSize(1);
        assertThat(g2.resultado().omitidos).containsExactlyInAnyOrder("buscar-no-existe", "contar-cero");
        assertThat(g2.resultado().casosGenerados).isEqualTo(1);
        String segundo = Files.readString(archivo(salida), StandardCharsets.UTF_8);
        int ini = segundo.indexOf("    // junit-gen:caso-inicio id=contar-uno");
        int fin = segundo.indexOf("// junit-gen:caso-fin id=contar-uno");
        fin = segundo.indexOf('\n', fin) + 1;
        String sinNuevo = segundo.substring(0, ini) + segundo.substring(fin + 1);
        String sinImports = sinNuevo.replace("import java.util.Arrays;\n", "");
        assertThat(sinImports).isEqualTo(primero);
        assertThat(segundo).contains("void contarActivos_unActivo()", "u.setActivo(true);");
        assertThat(new String(bytesPrimero, StandardCharsets.UTF_8)).isEqualTo(primero);

        Fachada.Generacion g3 = new Fachada().generar(Json.leer(spec(p, null, CASO_EXCEPCION, CASO_VALOR, CASO_NUEVO)), null, salida, p,
                new GeneradorTests.Opciones());
        assertThat(g3.resultado().archivosAmpliados).isEmpty();
        assertThat(Files.readString(archivo(salida), StandardCharsets.UTF_8)).isEqualTo(segundo);
        assertThat(Files.readString(salida.resolve("informe.md"), StandardCharsets.UTF_8))
                .contains("Modo solo lectura sobre", "Archivos creados o ampliados", "spec.json", "informe.md");
    }

    @Test
    void contenidoDistintoNoSeReescribeSalvoReemplazar() throws Exception {
        Path p = Fixtures.copiar("proyecto-spring", tmp.resolve("p"));
        Path salida = tmp.resolve("s");
        generarYLeer(p, salida, spec(p, null, CASO_VALOR));
        String cambiado = CASO_VALOR.replace("'valor':'0'", "'valor':'0 + 0'");
        Fachada.Generacion g = new Fachada().generar(Json.leer(spec(p, null, cambiado)), null, salida, p, new GeneradorTests.Opciones());
        assertThat(g.resultado().noReescritos).containsExactly("contar-cero");
        GeneradorTests.Opciones op = new GeneradorTests.Opciones();
        op.reemplazar = Set.of("contar-cero");
        Fachada.Generacion g2 = new Fachada().generar(Json.leer(spec(p, null, cambiado)), null, salida, p, op);
        assertThat(g2.resultado().reemplazados).containsExactly("contar-cero");
        assertThat(Files.readString(archivo(salida), StandardCharsets.UTF_8)).contains("assertEquals(0 + 0, resultado)");

        GeneradorTests.Opciones sinConfirmar = new GeneradorTests.Opciones();
        sinConfirmar.eliminar = Set.of("contar-cero");
        Fachada.Generacion g3 = new Fachada().generar(Json.leer(spec(p, null, cambiado)), null, salida, p, sinConfirmar);
        assertThat(g3.resultado().errores).anyMatch(e -> e.codigo.equals("REQUIERE_CONFIRMACION"));
        assertThat(Files.readString(archivo(salida), StandardCharsets.UTF_8)).contains("contar-cero");
    }

    // ------------------------------------------------------------- guardia anti-maquillaje

    /** Fija estado y autorización del caso en el spec.json de la salida (simula verificaciones previas). */
    private static void marcar(Path salida, String id, String estado, String autorizacion) throws Exception {
        Spec s = AlmacenSpec.leer(salida);
        CasoSpec c = AlmacenSpec.buscar(s, id).caso();
        c.estado = estado;
        c.autorizacionCambio = autorizacion;
        Files.writeString(AlmacenSpec.ruta(salida), AlmacenSpec.serializar(s), StandardCharsets.UTF_8);
    }

    private static Map<Path, String> instantanea(Path salida) throws Exception {
        Map<Path, String> r = new LinkedHashMap<>();
        try (Stream<Path> st = Files.walk(salida)) {
            for (Path f : st.filter(Files::isRegularFile).sorted().toList()) {
                r.put(f, Files.readString(f, StandardCharsets.UTF_8));
            }
        }
        return r;
    }

    @Test
    void guardiaRechazaReemplazarYEliminarCasosProtegidosSinEscribirNada() throws Exception {
        Path p = Fixtures.copiar("proyecto-spring", tmp.resolve("p"));
        Path salida = tmp.resolve("s");
        generarYLeer(p, salida, spec(p, null, CASO_VALOR, CASO_EXCEPCION));
        String cambiado = spec(p, null, CASO_VALOR.replace("'valor':'0'", "'valor':'1'"), CASO_EXCEPCION);
        for (String estado : List.of("fallo_asercion", "posible_bug", "dudoso")) {
            marcar(salida, "contar-cero", estado, null);
            Map<Path, String> antes = instantanea(salida);

            GeneradorTests.Opciones reemplazar = new GeneradorTests.Opciones();
            reemplazar.reemplazar = new LinkedHashSet<>(List.of("contar-cero", "buscar-no-existe", "no-existe"));
            ResultadoGeneracion g = new Fachada().generar(Json.leer(cambiado), null, salida, p, reemplazar).resultado();
            assertThat(g.errores).singleElement().satisfies(e -> {
                assertThat(e.codigo).isEqualTo(GeneradorTests.COD_DECISION_USUARIO);
                assertThat(e.ruta).isEqualTo("reemplazarCasos");
                assertThat(e.mensaje).contains("contar-cero (" + estado + ")").doesNotContain("buscar-no-existe", "no-existe");
                assertThat(e.sugerencia).contains("Quita esos ids", "no toques su esperado", "ejecutar_tests sin `casos`",
                        "«Ajustar lo esperado»", "resolver_fallo decision=eliminar con confirmadoPorUsuario=true");
            });
            assertThat(instantanea(salida)).as("no se escribe nada (" + estado + ")").isEqualTo(antes);

            GeneradorTests.Opciones eliminar = new GeneradorTests.Opciones();
            eliminar.eliminar = Set.of("contar-cero");
            eliminar.confirmado = true;
            ResultadoGeneracion g2 = new Fachada().generar(Json.leer(cambiado), null, salida, p, eliminar).resultado();
            assertThat(g2.errores).singleElement().satisfies(e -> {
                assertThat(e.codigo).isEqualTo(GeneradorTests.COD_DECISION_USUARIO);
                assertThat(e.ruta).isEqualTo("eliminarCasos");
            });
            assertThat(instantanea(salida)).isEqualTo(antes);
        }
        marcar(salida, "contar-cero", "fallo_asercion", CasoSpec.AUTORIZACION_AJUSTAR);
        GeneradorTests.Opciones eliminarAutorizado = new GeneradorTests.Opciones();
        eliminarAutorizado.eliminar = Set.of("contar-cero");
        eliminarAutorizado.confirmado = true;
        assertThat(new Fachada().generar(Json.leer(cambiado), null, salida, p, eliminarAutorizado).resultado().errores)
                .as("eliminar un caso protegido desde generar_tests nunca se autoriza")
                .anyMatch(e -> e.codigo.equals(GeneradorTests.COD_DECISION_USUARIO));
    }

    @Test
    void reemplazoAutorizadoSePermiteUnaVezYConsumeLaAutorizacion() throws Exception {
        Path p = Fixtures.copiar("proyecto-spring", tmp.resolve("p"));
        for (String autorizacion : List.of(CasoSpec.AUTORIZACION_AJUSTAR, CasoSpec.AUTORIZACION_ERROR_IA)) {
            Path salida = tmp.resolve("s-" + autorizacion);
            generarYLeer(p, salida, spec(p, null, CASO_VALOR));
            marcar(salida, "contar-cero", "fallo_asercion", autorizacion);
            String cambiado = spec(p, null, CASO_VALOR.replace("'valor':'0'", "'valor':'0 + 0'"));
            GeneradorTests.Opciones op = new GeneradorTests.Opciones();
            op.reemplazar = Set.of("contar-cero");
            ResultadoGeneracion g = new Fachada().generar(Json.leer(cambiado), null, salida, p, op).resultado();
            assertThat(g.errores).as(autorizacion).isEmpty();
            assertThat(g.reemplazados).containsExactly("contar-cero");
            assertThat(Files.readString(archivo(salida), StandardCharsets.UTF_8)).contains("assertEquals(0 + 0, resultado)");
            CasoSpec c = AlmacenSpec.buscar(AlmacenSpec.leer(salida), "contar-cero").caso();
            assertThat(c.autorizacionCambio).as("autorización consumida").isNull();
            assertThat(c.estado).isEqualTo("generado");

            marcar(salida, "contar-cero", "fallo_asercion", null);
            String otraVez = spec(p, null, CASO_VALOR.replace("'valor':'0'", "'valor':'0 * 1'"));
            assertThat(new Fachada().generar(Json.leer(otraVez), null, salida, p, op).resultado().errores)
                    .as("si vuelve a fallar hace falta otra decisión").anyMatch(e -> e.codigo.equals(GeneradorTests.COD_DECISION_USUARIO));
        }
    }

    @Test
    void autorizacionYEstadoDelSpecDeEntradaSeIgnoran() throws Exception {
        Path p = Fixtures.copiar("proyecto-spring", tmp.resolve("p"));
        Path salida = tmp.resolve("s");
        generarYLeer(p, salida, spec(p, null, CASO_VALOR));
        marcar(salida, "contar-cero", "fallo_asercion", null);
        String inyectado = spec(p, null, CASO_VALOR.replace("'valor':'0'}", "'valor':'1'},'estado':'pasa','autorizacionCambio':'ajustar'"));
        GeneradorTests.Opciones op = new GeneradorTests.Opciones();
        op.reemplazar = Set.of("contar-cero");
        assertThat(new Fachada().generar(Json.leer(inyectado), null, salida, p, op).resultado().errores)
                .anyMatch(e -> e.codigo.equals(GeneradorTests.COD_DECISION_USUARIO));

        String nuevo = spec(p, null, CASO_NUEVO.replace("'valor':'1'}", "'valor':'1'},'estado':'pasa','autorizacionCambio':'error_ia',"
                + "'veredictoIa':'error_ia','intentosMontaje':2"));
        ResultadoGeneracion g = new Fachada().generar(Json.leer(nuevo), null, salida, p, new GeneradorTests.Opciones()).resultado();
        assertThat(g.errores).isEmpty();
        CasoSpec c = AlmacenSpec.buscar(AlmacenSpec.leer(salida), "contar-uno").caso();
        assertThat(c.estado).isEqualTo("generado");
        assertThat(c.autorizacionCambio).isNull();
        assertThat(c.veredictoIa).isNull();
        assertThat(c.intentosMontaje).isZero();
    }

    @Test
    void specAntiguoSinAutorizacionSeLeeSinError() throws Exception {
        Path salida = tmp.resolve("antiguo");
        Fixtures.escribir(salida.resolve("spec.json"), j("{'version':1,'proyecto':{'ruta':'C:/x'},'servicios':[{'clase':'a.B','casos':["
                + "{'id':'c1','metodo':'m','escenario':'e','argumentos':[],'esperado':{'tipo':'noNulo'},'estado':'fallo_asercion',"
                + "'intentosMontaje':1,'veredictoIa':'dudoso'}]}]}"));
        CasoSpec c = AlmacenSpec.buscar(AlmacenSpec.leer(salida), "c1").caso();
        assertThat(c.autorizacionCambio).isNull();
        assertThat(c.protegido()).isTrue();
    }
}
