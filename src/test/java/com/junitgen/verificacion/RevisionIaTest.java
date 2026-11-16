package com.junitgen.verificacion;

import com.junitgen.Fixtures;
import com.junitgen.generacion.AlmacenSpec;
import com.junitgen.seguridad.EscritorSeguro;
import com.junitgen.spec.CasoSpec;
import com.junitgen.spec.Spec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** registrar_analisis_ia (veredictos, límite de 3 intentos, informe y validaciones) y su efecto al reejecutar. */
class RevisionIaTest {

    @TempDir
    Path tmp;

    private Path proyecto;
    private Path salida;
    private EscritorSeguro esc;

    @BeforeEach
    void preparar() throws Exception {
        proyecto = Fixtures.copiar("proyecto-spring", tmp.resolve("p")).toRealPath();
        salida = tmp.resolve("salida");
        StringBuilder casos = new StringBuilder();
        String[][] defs = {{"c1", "fallo_asercion", "0"}, {"c2", "fallo_asercion", "0"}, {"c3", "fallo_asercion", "0"},
                {"c4", "fallo_asercion", "3"}, {"c5", "pasa", "0"}, {"c6", "fallo_asercion", "0"}};
        for (String[] d : defs) {
            if (casos.length() > 0) {
                casos.append(',');
            }
            casos.append("{'id':'").append(d[0]).append("','metodo':'buscar','escenario':'e").append(d[0])
                    .append("','argumentos':['1L'],'esperado':{'tipo':'noNulo'},'estado':'").append(d[1])
                    .append("','intentosMontaje':").append(d[2]).append('}');
        }
        Fixtures.escribir(salida.resolve("spec.json"), Fixtures.j("{'version':1,'proyecto':{'ruta':'" + proyecto.toString().replace('\\', '/')
                + "'},'servicios':[{'clase':'com.ejemplo.service.UsuarioServiceImpl','casos':[" + casos + "]}]}"));
        esc = new EscritorSeguro(proyecto, salida);
    }

    private CasoSpec caso(String id) {
        Spec s = AlmacenSpec.leer(salida);
        return AlmacenSpec.buscar(s, id).caso();
    }

    private static VerificadorTests.AnalisisIa a(String caso, String veredicto, String motivo) {
        return new VerificadorTests.AnalisisIa(caso, veredicto, motivo);
    }

    @Test
    @SuppressWarnings("unchecked")
    void tresVeredictosYSeccionDelInforme() throws Exception {
        Map<String, Object> r = VerificadorTests.registrarAnalisisIa(esc, List.of(
                a("c1", "error_ia", "El esperado no coincide con el código: devuelve el usuario guardado"),
                a("c2", "bug_servicio", "No valida | el id nulo\nni negativo"),
                a("c3", "dudoso", "No se puede saber sin más contexto")));
        assertThat((List<String>) r.get("corregir")).containsExactly("c1");
        assertThat(r.get("instruccion").toString()).contains("reemplazarCasos=[c1]", "ejecutar_tests con casos=[c1]");
        List<Map<String, Object>> res = (List<Map<String, Object>>) r.get("resultados");
        assertThat(res).extracting(m -> m.get("estado")).containsExactly("fallo_asercion", "posible_bug", "dudoso");
        assertThat(res.get(0).get("intento")).isEqualTo(1);

        assertThat(caso("c1").estado).isEqualTo("fallo_asercion");
        assertThat(caso("c1").intentosMontaje).isEqualTo(1);
        assertThat(caso("c1").veredictoIa).isEqualTo("error_ia");
        assertThat(caso("c2").estado).isEqualTo("posible_bug");
        assertThat(caso("c2").veredictoIa).isEqualTo("bug_servicio");
        assertThat(caso("c3").estado).isEqualTo("dudoso");
        String informe = Files.readString(salida.resolve("informe.md"), StandardCharsets.UTF_8);
        assertThat(informe).contains("## Revisión de la IA", "| Caso | Veredicto | Motivo | Acción |", "| c1 | error_ia |",
                "| c2 | bug_servicio | No valida \\| el id nulo ni negativo |", "| c3 | dudoso |");
    }

    @Test
    @SuppressWarnings("unchecked")
    void errorIaConTresIntentosQuedaDudoso() {
        Map<String, Object> r = VerificadorTests.registrarAnalisisIa(esc, List.of(a("c4", "error_ia", "Sigue mal")));
        assertThat((List<String>) r.get("corregir")).isEmpty();
        assertThat(caso("c4").estado).isEqualTo("dudoso");
        assertThat(caso("c4").intentosMontaje).isEqualTo(3);
        assertThat(((List<Map<String, Object>>) r.get("resultados")).get(0).get("accion").toString()).contains("Límite de 3 intentos");
    }

    @Test
    void validacionesSinEscribirNada() throws Exception {
        String antes = Files.readString(salida.resolve("spec.json"), StandardCharsets.UTF_8);
        List<List<VerificadorTests.AnalisisIa>> malos = new ArrayList<>();
        malos.add(List.of());
        malos.add(List.of(a("c1", "error_ia", "ok"), a("C/../x", "dudoso", "id no válido")));
        malos.add(List.of(a("c1", "error_ia", "ok"), a("c1", "dudoso", "repetido")));
        malos.add(List.of(a("c1", "otro", "veredicto desconocido")));
        malos.add(List.of(a("c1", "dudoso", "   ")));
        malos.add(List.of(a("c1", "dudoso", "ok"), a("c5", "dudoso", "no está en fallo_asercion")));
        malos.add(List.of(a("noexiste", "dudoso", "no está en el spec")));
        List<VerificadorTests.AnalisisIa> demasiados = new ArrayList<>();
        for (int i = 0; i <= VerificadorTests.MAX_ANALISIS; i++) {
            demasiados.add(a("x" + i, "dudoso", "m"));
        }
        malos.add(demasiados);
        for (List<VerificadorTests.AnalisisIa> l : malos) {
            assertThatThrownBy(() -> VerificadorTests.registrarAnalisisIa(esc, l)).as(l.toString()).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(Files.readString(salida.resolve("spec.json"), StandardCharsets.UTF_8)).isEqualTo(antes);
        assertThat(salida.resolve("informe.md")).doesNotExist();
    }

    @Test
    void motivoLargoSeRecorta() throws Exception {
        VerificadorTests.registrarAnalisisIa(esc, List.of(a("c1", "dudoso", "x".repeat(600))));
        String informe = Files.readString(salida.resolve("informe.md"), StandardCharsets.UTF_8);
        assertThat(informe).contains("x".repeat(VerificadorTests.MAX_MOTIVO) + "…").doesNotContain("x".repeat(VerificadorTests.MAX_MOTIVO + 1));
    }

    private static ResultadoTest resultado(String caso, String estado) {
        ResultadoTest t = new ResultadoTest();
        t.caso = caso;
        t.clase = "com.ejemplo.service.UsuarioServiceImplTest";
        t.metodoTest = "m_" + caso;
        t.estado = estado;
        t.mensaje = "expected: <1> but was: <2>";
        return t;
    }

    @Test
    void trasErrorIaLaRepeticionVaARevisionIaPendienteSinPreguntar() {
        VerificadorTests.registrarAnalisisIa(esc, List.of(a("c1", "error_ia", "spec mal"), a("c2", "bug_servicio", "bug")));
        ResultadoVerificacion rv = new ResultadoVerificacion();
        rv.resultados.add(resultado("c1", ClasificadorFallos.FALLO_ASERCION));
        rv.resultados.add(resultado("c2", ClasificadorFallos.FALLO_ASERCION));
        rv.resultados.add(resultado("c3", ClasificadorFallos.FALLO_ASERCION));
        rv.resultados.add(resultado("c6", ClasificadorFallos.ERROR_MONTAJE));
        VerificadorTests.actualizarSpecEInforme(esc, rv, new StringBuilder());
        assertThat(rv.revisionIaPendiente).containsExactly("c1");
        assertThat(rv.asercionesPendientes).as("c6 (fallo_asercion que deja de compilar) sigue pendiente").containsExactly("c3", "c6");
        assertThat(rv.montajeReintentables).isEmpty();
        assertThat(rv.accionRequerida).contains("registrar_analisis_ia", "no preguntes al usuario",
                "puede ser un bug real; no ajustes el esperado ni decidas por el usuario",
                "no los incluyas en reemplazarCasos: junit-gen lo rechaza");
        assertThat(caso("c2").estado).as("posible_bug se conserva").isEqualTo("posible_bug");
        assertThat(caso("c1").estado).isEqualTo("fallo_asercion");
        assertThat(caso("c6").estado).isEqualTo("fallo_asercion");
        assertThat(caso("c6").intentosMontaje).as("no suma intentos").isZero();
    }

    @Test
    void errorIaAutorizaUnUnicoReemplazoYElRestoDeVeredictosNo() {
        VerificadorTests.registrarAnalisisIa(esc, List.of(a("c1", "error_ia", "spec mal"), a("c2", "bug_servicio", "bug"),
                a("c4", "error_ia", "sin intentos")));
        assertThat(caso("c1").autorizacionCambio).isEqualTo(CasoSpec.AUTORIZACION_ERROR_IA);
        assertThat(caso("c2").autorizacionCambio).isNull();
        assertThat(caso("c4").autorizacionCambio).as("límite de intentos: queda dudoso, sin autorización").isNull();
    }

    @Test
    void ajustarExigeConfirmacionYAutorizaSinVolverAPreguntar() throws Exception {
        String antes = Files.readString(salida.resolve("spec.json"), StandardCharsets.UTF_8);
        assertThatThrownBy(() -> VerificadorTests.resolverFallo(esc, "c1", "ajustar", false))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("confirmadoPorUsuario=true");
        assertThat(Files.readString(salida.resolve("spec.json"), StandardCharsets.UTF_8)).isEqualTo(antes);

        Map<String, Object> r = VerificadorTests.resolverFallo(esc, "c1", "ajustar", true);
        assertThat(r.get("instruccion").toString()).contains("reemplazarCasos=[\"c1\"]");
        assertThat(caso("c1").autorizacionCambio).isEqualTo(CasoSpec.AUTORIZACION_AJUSTAR);

        ResultadoVerificacion rv = new ResultadoVerificacion();
        rv.resultados.add(resultado("c1", ClasificadorFallos.FALLO_ASERCION));
        VerificadorTests.actualizarSpecEInforme(esc, rv, new StringBuilder());
        assertThat(rv.asercionesPendientes).as("autorizado y sin reemplazar: no se vuelve a preguntar").isEmpty();
        assertThat(rv.accionRequerida).contains("tienen autorizado «Ajustar lo esperado»", "reemplazarCasos=[c1]");
        assertThat(caso("c1").autorizacionCambio).isEqualTo(CasoSpec.AUTORIZACION_AJUSTAR);

        VerificadorTests.resolverFallo(esc, "c1", "bug", false);
        assertThat(caso("c1").estado).isEqualTo("posible_bug");
        assertThat(caso("c1").autorizacionCambio).as("bug retira la autorización").isNull();
    }

    @Test
    void casoProtegidoQueDejaDeCompilarConservaElEstadoYPasaLimpiaLaAutorizacion() {
        VerificadorTests.registrarAnalisisIa(esc, List.of(a("c2", "bug_servicio", "bug"), a("c3", "error_ia", "spec mal")));
        ResultadoVerificacion rv = new ResultadoVerificacion();
        rv.resultados.add(resultado("c1", ClasificadorFallos.NO_COMPILA));
        rv.resultados.add(resultado("c2", ClasificadorFallos.ERROR_MONTAJE));
        rv.resultados.add(resultado("c3", ClasificadorFallos.NO_COMPILA));
        rv.resultados.add(resultado("c5", ClasificadorFallos.NO_COMPILA));
        VerificadorTests.actualizarSpecEInforme(esc, rv, new StringBuilder());
        assertThat(caso("c1").estado).isEqualTo("fallo_asercion");
        assertThat(caso("c1").intentosMontaje).isZero();
        assertThat(caso("c2").estado).isEqualTo("posible_bug");
        assertThat(caso("c3").estado).as("con error_ia sigue protegido, sin preguntar").isEqualTo("fallo_asercion");
        assertThat(rv.asercionesPendientes).containsExactly("c1");
        assertThat(rv.montajeReintentables).as("solo el caso no protegido").containsExactly("c5");
        assertThat(caso("c5").estado).isEqualTo("generado");

        ResultadoVerificacion pasa = new ResultadoVerificacion();
        pasa.resultados.add(resultado("c3", ClasificadorFallos.PASA));
        VerificadorTests.actualizarSpecEInforme(esc, pasa, new StringBuilder());
        assertThat(caso("c3").estado).isEqualTo("pasa");
        assertThat(caso("c3").autorizacionCambio).isNull();
    }
}
