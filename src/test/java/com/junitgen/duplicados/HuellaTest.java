package com.junitgen.duplicados;

import com.junitgen.Fixtures;
import com.junitgen.escaneo.ProyectoEscaneado;
import com.junitgen.spec.ResultadoValidacion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static com.junitgen.Fixtures.j;
import static org.assertj.core.api.Assertions.assertThat;

class HuellaTest {

    @TempDir
    Path tmp;

    @Test
    void normalizaLiteralesCastsMatchersYMocks() {
        NormalizadorExpresiones n = new NormalizadorExpresiones();
        n.mock("repo", "UsuarioRepository");
        assertThat(n.normalizar("(Long) 50L")).isEqualTo("50");
        assertThat(n.normalizar("Long.valueOf(5)")).isEqualTo("5");
        assertThat(n.normalizar("1_000")).isEqualTo("1000");
        assertThat(n.normalizar("((x))")).isEqualTo("x");
        assertThat(n.normalizar("repo.findById(anyLong())")).isEqualTo("UsuarioRepository.findById(<any>)");
        assertThat(n.normalizar("repo.save(eq(7L))")).isEqualTo("UsuarioRepository.save(7)");
        assertThat(n.normalizar("\"a b\"")).isEqualTo("\"a b\"");
    }

    @Test
    void sustituyeVariablesConMutacionesOrdenadas() {
        NormalizadorExpresiones a = new NormalizadorExpresiones();
        a.declararTexto("u", "new Usuario()");
        a.mutar("u", (com.github.javaparser.ast.expr.MethodCallExpr) NormalizadorExpresiones.parsear("u.setNombre(\"Ana\")"));
        a.mutar("u", (com.github.javaparser.ast.expr.MethodCallExpr) NormalizadorExpresiones.parsear("u.setId(1L)"));
        NormalizadorExpresiones b = new NormalizadorExpresiones();
        b.declararTexto("otroNombre", "new Usuario()");
        b.mutar("otroNombre", (com.github.javaparser.ast.expr.MethodCallExpr) NormalizadorExpresiones.parsear("otroNombre.setId(1)"));
        b.mutar("otroNombre", (com.github.javaparser.ast.expr.MethodCallExpr) NormalizadorExpresiones.parsear("otroNombre.setNombre(\"Ana\")"));
        assertThat(a.normalizar("Optional.of(u)")).isEqualTo(b.normalizar("Optional.of(otroNombre)"));
    }

    @Test
    void huellaEntradaYCompleta() {
        Huella h1 = Huella.calcular("a.B", "m", List.of("Long"), List.of("1"), List.of("X.y()->2", "X.z()->3"), "valor:2");
        Huella h2 = Huella.calcular("a.B", "m", List.of("Long"), List.of("1"), List.of("X.z()->3", "X.y()->2"), "valor:3");
        assertThat(h1.entrada).isEqualTo(h2.entrada);
        assertThat(h1.completa).isNotEqualTo(h2.completa);
        assertThat(h1.completa).hasSize(16).matches("[0-9a-f]{16}");
    }

    @Test
    void independienteDeNombresDeVariablesYDelMetodoDeTest() throws Exception {
        ProyectoEscaneado p = Fixtures.escanear(Fixtures.copiar("proyecto-spring", tmp.resolve("p")));
        String spec = j("""
                {'version':1,'servicios':[{'clase':'com.ejemplo.service.UsuarioServiceImpl','casos':[
                 {'id':'a','metodo':'activar','escenario':'uno','datos':[{'tipo':'Usuario','nombre':'u','valor':'new Usuario(5L, \\'Leo\\')'}],
                  'stubs':[{'mock':'repo','llamada':'findById(5L)','devuelve':'Optional.of(u)'},{'mock':'repo','llamada':'save(u)','devuelve':'u'}],
                  'argumentos':['5L'],'esperado':{'tipo':'noNulo'}},
                 {'id':'b','metodo':'activar','escenario':'otroNombreDeTest','datos':[{'tipo':'Usuario','nombre':'usuarioX','valor':'new Usuario(Long.valueOf(5), \\'Leo\\')'}],
                  'stubs':[{'mock':'repo','llamada':'save(usuarioX)','devuelve':'usuarioX'},{'mock':'repo','llamada':'findById(5)','devuelve':'Optional.of(usuarioX)'}],
                  'argumentos':['(Long) 5L'],'esperado':{'tipo':'noNulo'}}
                ]}]}""");
        ResultadoValidacion r = Fixtures.validar(spec, p, null);
        assertThat(r.errores).isEmpty();
        ResultadoValidacion.ServicioValidado sv = r.servicios.get(0);
        assertThat(sv.casos.get(0).huella.completa).isEqualTo(sv.casos.get(1).huella.completa);
    }

    @Test
    void extraeHuellaDeTestExistente() throws Exception {
        ProyectoEscaneado p = Fixtures.escanear(Fixtures.copiar("proyecto-spring", tmp.resolve("p")));
        var m = Fixtures.unico(p);
        var servicio = m.indice.get("com.ejemplo.service.UsuarioServiceImpl");
        var test = m.indice.get("com.ejemplo.service.UsuarioServiceImplTest");
        List<ExtractorHuellasTests.HuellaExistente> hs = ExtractorHuellasTests.extraer(test, servicio, m.indice);
        assertThat(hs).hasSize(1);
        assertThat(hs.get(0).huella().esperado).startsWith("valor:");
        assertThat(hs.get(0).huella().textoEntrada).contains("UsuarioRepository.findById(1)->Optional.of(newUsuario(1,\"Ana\"))");
    }
}
