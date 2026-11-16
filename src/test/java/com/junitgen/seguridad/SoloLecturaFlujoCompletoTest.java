package com.junitgen.seguridad;

import com.junitgen.Fachada;
import com.junitgen.Fixtures;
import com.junitgen.escaneo.InformeEscaneo;
import com.junitgen.generacion.GeneradorTests;
import com.junitgen.util.Json;
import com.junitgen.verificacion.LocalizadorMaven;
import com.junitgen.verificacion.ResultadoTest;
import com.junitgen.verificacion.ResultadoVerificacion;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Collectors;

import static com.junitgen.Fixtures.j;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Flujo completo (escaneo, generación y verificación real con Maven, javac y JUnit) sobre una copia
 * de proyecto-spring: el contenido (SHA-256) y las fechas de modificación de todos los archivos y
 * carpetas del proyecto deben quedar idénticos.
 */
class SoloLecturaFlujoCompletoTest {

    @TempDir
    Path tmp;

    @Test
    @Timeout(1200)
    void elProyectoNoCambiaTrasEscanearGenerarYVerificar() throws Exception {
        Path proyecto = Fixtures.copiar("proyecto-spring", tmp.resolve("proyecto"));
        Path mvn = LocalizadorMaven.localizar(null, proyecto, proyecto);
        Assumptions.assumeTrue(mvn != null, "Maven no localizado: no se puede ejecutar la verificación real");
        System.out.println("SoloLecturaFlujoCompletoTest: ejecutado (no saltado) con Maven " + mvn);
        Path salida = tmp.resolve("salida con espacios");
        // La copia y la instantánea inicial se separan en el tiempo para que cualquier cambio de mtime sea visible.
        Thread.sleep(1100);
        Map<String, GuardaIntegridad.Entrada> antes = GuardaIntegridad.instantanea(proyecto, true);

        Fachada f = new Fachada();
        InformeEscaneo.Opciones op = new InformeEscaneo.Opciones();
        Fachada.Escaneo e = f.escanear(proyecto, salida, op, false);
        f.escribirEscaneo(e);
        String spec = j("{'version':1,'proyecto':{'ruta':'" + proyecto.toString().replace('\\', '/') + "'},'servicios':[{'clase':"
                + "'com.ejemplo.service.UsuarioServiceImpl','casos':["
                + "{'id':'activar-ok','metodo':'activar','escenario':'activaUsuarioInactivo','datos':[{'tipo':'Usuario','nombre':'u',"
                + "'valor':'new Usuario(5L, \\'Leo\\')'}],'stubs':[{'mock':'repo','llamada':'findById(5L)','devuelve':'Optional.of(u)'},"
                + "{'mock':'repo','llamada':'save(u)','devuelve':'u'}],'argumentos':['5L'],'esperado':{'tipo':'aserciones'},"
                + "'aserciones':[{'real':'resultado.isActivo()','tipo':'verdadero'}],"
                + "'verificaciones':[{'mock':'notificador','llamada':'enviar(\\'activado 5\\')','veces':1}]},"
                + "{'id':'buscar-no-existe','metodo':'buscar','escenario':'lanzaSiNoExiste','stubs':[{'mock':'repo','llamada':"
                + "'findById(9L)','devuelve':'Optional.empty()'}],'argumentos':['9L'],'esperado':{'tipo':'excepcion',"
                + "'excepcion':'IllegalArgumentException','mensaje':'No existe 9'}},"
                + "{'id':'contar-falla','metodo':'contarActivos','escenario':'esperadoIncorrecto','stubs':[{'mock':'repo','llamada':"
                + "'findAll()','devuelve':'List.of(new Usuario(1L, \\'A\\'))'}],'argumentos':[],'esperado':{'tipo':'valor','valor':'1'}},"
                + "{'id':'montaje-roto','metodo':'activar','escenario':'stubInnecesario','stubs':[{'mock':'repo','llamada':'findById(6L)',"
                + "'devuelve':'Optional.empty()'},{'mock':'repo','llamada':'findAll()','devuelve':'List.of()'}],'argumentos':['6L'],"
                + "'esperado':{'tipo':'excepcion','excepcion':'IllegalArgumentException'}}"
                + "]}]}");
        Fachada.Generacion g = f.generar(Json.leer(spec), null, salida, null, new GeneradorTests.Opciones());
        assertThat(g.resultado().errores).isEmpty();
        assertThat(g.resultado().casosGenerados).isEqualTo(4);

        ResultadoVerificacion v = f.ejecutar(proyecto, salida, null, null);
        Map<String, String> estados = v.resultados.stream().collect(Collectors.toMap(r -> r.caso, r -> r.estado));
        System.out.println("Resultados de la verificación: " + estados + " avisos=" + v.avisos);
        assertThat(estados).containsEntry("activar-ok", "pasa").containsEntry("buscar-no-existe", "pasa")
                .containsEntry("contar-falla", "fallo_asercion").containsEntry("montaje-roto", "error_montaje");
        ResultadoTest falla = v.resultados.stream().filter(r -> r.caso.equals("contar-falla")).findFirst().orElseThrow();
        assertThat(falla.mensaje).contains("expected");
        assertThat(v.alertaIntegridad).isNull();
        assertThat(v.jdkUsado).contains("17");

        f.resolverFallo(salida, "contar-falla", "bug", false);
        assertThat(Files.readString(salida.resolve("spec.json"))).contains("posible_bug");
        assertThat(Files.readString(salida.resolve("informe.md"))).contains("Verificación", "contar-falla", "Decisión");
        assertThat(salida.resolve("escaneo.md")).exists();
        assertThat(salida.resolve("escaneo.json")).exists();

        Map<String, GuardaIntegridad.Entrada> despues = GuardaIntegridad.instantanea(proyecto, true);
        assertThat(GuardaIntegridad.comparar(antes, despues)).isEmpty();
        assertThat(despues.keySet()).doesNotContain("target");
    }
}
