package com.junitgen.spec;

import com.junitgen.Fixtures;
import com.junitgen.escaneo.ProyectoEscaneado;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import static com.junitgen.Fixtures.j;
import static org.assertj.core.api.Assertions.assertThat;

class ValidadorSpecTest {

    @TempDir
    Path tmp;

    private static List<String> codigos(ResultadoValidacion r) {
        return r.errores.stream().map(e -> e.codigo).collect(Collectors.toList());
    }

    private ProyectoEscaneado spring() throws Exception {
        return Fixtures.escanear(Fixtures.copiar("proyecto-spring", tmp.resolve("p")));
    }

    private static String servicio(String clase, String casos) {
        return j("{'version':1,'servicios':[{'clase':'" + clase + "','casos':[" + casos + "]}]}");
    }

    @Test
    void specCorrectoEsValido() throws Exception {
        ResultadoValidacion r = Fixtures.validar(servicio("com.ejemplo.service.UsuarioServiceImpl",
                "{'id':'contar','metodo':'contarActivos','escenario':'ninguno','stubs':[{'mock':'repo','llamada':'findAll()',"
                        + "'devuelve':'new ArrayList<>()'}],'argumentos':[],'esperado':{'tipo':'valor','valor':'0'}}"), spring(), null);
        assertThat(r.errores).isEmpty();
        assertThat(r.valido).isTrue();
        assertThat(r.servicios.get(0).imports).containsEntry("ArrayList", "java.util.ArrayList");
    }

    @Test
    void erroresClarosDeEstructura() throws Exception {
        ResultadoValidacion r = Fixtures.validar(servicio("com.ejemplo.service.UsuarioServiceImpl",
                "{'id':'Mal Id','metodo':'noExiste','escenario':'x','argumentos':[],'esperado':{'tipo':'valor','valor':'1'}},"
                        + "{'id':'args','metodo':'buscar','escenario':'x','argumentos':[],'esperado':{'tipo':'nulo'}},"
                        + "{'id':'mock','metodo':'buscar','escenario':'x','stubs':[{'mock':'otro','llamada':'x()','devuelve':'1'}],"
                        + "'argumentos':['1L'],'esperado':{'tipo':'nulo'}},"
                        + "{'id':'void','metodo':'contarActivos','escenario':'x','stubs':[{'mock':'repo','llamada':'deleteById(1L)',"
                        + "'devuelve':'1'}],'argumentos':[],'esperado':{'tipo':'sinRetorno'}},"
                        + "{'id':'tipo','metodo':'buscar','escenario':'x','datos':[{'tipo':'TipoInventado','nombre':'t','valor':'null'}],"
                        + "'argumentos':['1L'],'esperado':{'tipo':'nulo'}},"
                        + "{'id':'expr','metodo':'buscar','escenario':'x','argumentos':['1L); } static { System.exit(0'],'esperado':{'tipo':'nulo'}}"),
                spring(), null);
        assertThat(r.valido).isFalse();
        assertThat(codigos(r)).contains("ID_INVALIDO", "METODO_NO_EXISTE", "NUM_ARGUMENTOS", "MOCK_DESCONOCIDO", "DEVUELVE_EN_VOID",
                "SIN_RETORNO_EN_NO_VOID", "TIPO_NO_RESUELTO", "EXPRESION_INVALIDA");
        assertThat(r.errores).allMatch(e -> e.mensaje != null && !e.mensaje.isBlank() && e.ruta.startsWith("$"));
    }

    @Test
    void compatibilidadConJava8() throws Exception {
        ProyectoEscaneado legacy = Fixtures.escanear(Fixtures.copiar("proyecto-legacy", tmp.resolve("l")));
        ResultadoValidacion r = Fixtures.validar(servicio("com.legacy.negocio.FacturaManager",
                "{'id':'iva','metodo':'importeConIva','escenario':'x','datos':[{'tipo':'var','nombre':'v','valor':'List.of(1)'}],"
                        + "'stubs':[{'mock':'clienteDao','llamada':'tipoCliente(1L)','devuelve':'\\'EXENTO\\'','forma':'when'}],"
                        + "'argumentos':['1L','10.0'],'esperado':{'tipo':'valor','valor':'10.0'}}"), legacy, null);
        assertThat(codigos(r)).contains("INCOMPATIBLE_JAVA", "VAR_NO_SOPORTADO");
    }

    @Test
    void camposDesconocidosYJsonInvalido() {
        LectorSpec.Lectura l = LectorSpec.leerTexto(j("{'version':1,'servicios':[],'inventado':true}"));
        assertThat(l.ok()).isFalse();
        assertThat(l.errores().get(0).codigo).isEqualTo("CAMPO_DESCONOCIDO");
        LectorSpec.Lectura l2 = LectorSpec.leerTexto("{no es json");
        assertThat(l2.errores().get(0).codigo).isEqualTo("JSON_INVALIDO");
    }

    @Test
    void mockStaticNoDisponibleEnMockito1() throws Exception {
        ProyectoEscaneado legacy = Fixtures.escanear(Fixtures.copiar("proyecto-legacy", tmp.resolve("l")));
        ResultadoValidacion r = Fixtures.validar(servicio("com.legacy.negocio.FacturaManager",
                "{'id':'est','metodo':'importeConIva','escenario':'x','estaticos':[{'clase':'Math','llamada':'abs(1)','devuelve':'2'}],"
                        + "'argumentos':['1L','-1.0'],'esperado':{'tipo':'excepcion','excepcion':'IllegalArgumentException'}}"), legacy, null);
        assertThat(codigos(r)).contains("MOCKSTATIC_NO_DISPONIBLE");
    }
}
