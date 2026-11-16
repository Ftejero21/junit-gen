package com.junitgen.duplicados;

import com.junitgen.Fachada;
import com.junitgen.Fixtures;
import com.junitgen.generacion.GeneradorTests;
import com.junitgen.spec.ResultadoValidacion;
import com.junitgen.util.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static com.junitgen.Fixtures.j;
import static org.assertj.core.api.Assertions.assertThat;

class DetectorDuplicadosTest {

    @TempDir
    Path tmp;

    private static String caso(String id, String escenario, String variable, long idUsuario) {
        return j("{'id':'" + id + "','metodo':'buscar','escenario':'" + escenario + "','datos':[{'tipo':'Usuario','nombre':'" + variable
                + "','valor':'new Usuario(" + idUsuario + "L, \\'Ana\\')'}],'stubs':[{'mock':'repo','llamada':'findById(" + idUsuario
                + "L)','devuelve':'Optional.of(" + variable + ")'}],'argumentos':['" + idUsuario + "L'],'esperado':{'tipo':'valor','valor':'"
                + variable + "'}}");
    }

    private static String spec(Path proyecto, String... casos) {
        return j("{'version':1,'proyecto':{'ruta':'" + proyecto.toString().replace('\\', '/') + "'},'servicios':[{'clase':"
                + "'com.ejemplo.service.UsuarioServiceImpl','casos':[" + String.join(",", casos) + "]}]}");
    }

    @Test
    void nivel1ContraTestsDelProyecto() throws Exception {
        Path p = Fixtures.copiar("proyecto-spring", tmp.resolve("p"));
        ResultadoValidacion r = Fixtures.validar(spec(p, caso("dup", "otroEscenario", "cualquierNombre", 1)), Fixtures.escanear(p), null);
        assertThat(r.valido).isTrue();
        assertThat(r.duplicados).hasSize(1);
        assertThat(r.duplicados.get(0).nivel).isEqualTo(1);
        assertThat(r.duplicados.get(0).coincideCon).isEqualTo("UsuarioServiceImplTest.buscar_devuelveUsuarioSiExiste");
    }

    @Test
    void nivel2DentroDelSpecConservaElPrimero() throws Exception {
        Path p = Fixtures.copiar("proyecto-spring", tmp.resolve("p"));
        ResultadoValidacion r = Fixtures.validar(spec(p, caso("primero", "a", "x", 3), caso("segundo", "b", "y", 3)),
                Fixtures.escanear(p), null);
        assertThat(r.duplicados).hasSize(1);
        assertThat(r.duplicados.get(0).caso).isEqualTo("segundo");
        assertThat(r.duplicados.get(0).nivel).isEqualTo(2);
        assertThat(r.duplicados.get(0).coincideCon).contains("primero");
    }

    @Test
    void nivel3ContraLoGeneradoAntes() throws Exception {
        Path p = Fixtures.copiar("proyecto-spring", tmp.resolve("p"));
        Path salida = tmp.resolve("salida");
        Fachada f = new Fachada();
        Fachada.Generacion g = f.generar(Json.leer(spec(p, caso("original", "a", "x", 4))), null, salida, null, new GeneradorTests.Opciones());
        assertThat(g.resultado().errores).isEmpty();
        assertThat(g.resultado().casosGenerados).isEqualTo(1);
        ResultadoValidacion r = f.validar(Json.leer(spec(p, caso("copia", "otro", "z", 4))), null, null, salida);
        assertThat(r.duplicados).hasSize(1);
        assertThat(r.duplicados.get(0).nivel).isEqualTo(3);
        assertThat(r.duplicados.get(0).coincideCon).contains("original");
    }

    @Test
    void mismoEscenarioConEsperadoDistintoNoEsDuplicado() throws Exception {
        Path p = Fixtures.copiar("proyecto-spring", tmp.resolve("p"));
        String distinto = j("{'id':'distinto','metodo':'buscar','escenario':'e','datos':[{'tipo':'Usuario','nombre':'u','valor':"
                + "'new Usuario(1L, \\'Ana\\')'}],'stubs':[{'mock':'repo','llamada':'findById(1L)','devuelve':'Optional.of(u)'}],"
                + "'argumentos':['1L'],'esperado':{'tipo':'nulo'}}");
        ResultadoValidacion r = Fixtures.validar(spec(p, distinto), Fixtures.escanear(p), null);
        assertThat(r.duplicados).isEmpty();
        assertThat(r.avisos).anyMatch(a -> a.codigo.equals("MISMO_ESCENARIO_ESPERADO_DISTINTO"));
    }
}
