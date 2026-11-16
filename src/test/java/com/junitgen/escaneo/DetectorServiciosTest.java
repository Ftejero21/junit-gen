package com.junitgen.escaneo;

import com.junitgen.Fixtures;
import com.junitgen.proyecto.VersionesProyecto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class DetectorServiciosTest {

    @TempDir
    Path tmp;

    private static DetectorServicios.Candidato candidato(ModuloEscaneado m, String fqn) {
        return m.deteccion.servicios.stream().filter(c -> c.clase.fqn.equals(fqn)).findFirst().orElse(null);
    }

    @Test
    void puntuaCadaSenalConAnotaciones() throws Exception {
        ModuloEscaneado m = Fixtures.unico(Fixtures.escanear(Fixtures.copiar("proyecto-spring", tmp.resolve("p"))));
        DetectorServicios.Candidato c = candidato(m, "com.ejemplo.service.UsuarioServiceImpl");
        assertThat(c).isNotNull();
        assertThat(c.puntuacion).isEqualTo(130);
        assertThat(c.motivo).contains("@Service (+50)", "nombre *ServiceImpl (+20)", "paquete service (+20)",
                "implementa UsuarioService (+10)", "depende de UsuarioRepository (+10)", "lógica real (+20)", "= 130");
        assertThat(candidato(m, "com.ejemplo.service.Notificador").motivo).contains("@Component (+50)");
    }

    @Test
    void detectaBeanSinAnotacionPorMetodoBean() throws Exception {
        ModuloEscaneado m = Fixtures.unico(Fixtures.escanear(Fixtures.copiar("proyecto-spring", tmp.resolve("p"))));
        DetectorServicios.Candidato c = candidato(m, "com.ejemplo.otros.RelojSistema");
        assertThat(c).isNotNull();
        assertThat(c.motivo).contains("bean @Bean ConfiguracionApp.reloj() (+50)");
    }

    @Test
    void detectaBeanXmlYEjbYDependenciasSinAnotar() throws Exception {
        ModuloEscaneado m = Fixtures.unico(Fixtures.escanear(Fixtures.copiar("proyecto-legacy", tmp.resolve("l"))));
        DetectorServicios.Candidato factura = candidato(m, "com.legacy.negocio.FacturaManager");
        assertThat(factura).isNotNull();
        assertThat(factura.motivo).contains("<bean> en src/main/resources/applicationContext.xml (+50)", "nombre *Manager (+20)");
        DetectorServicios.Candidato cobro = candidato(m, "com.legacy.negocio.CobroBean");
        assertThat(cobro).isNotNull();
        assertThat(cobro.motivo).contains("@Stateless (+50)");
        AnalisisServicio af = m.analisis(factura.clase);
        assertThat(af.dependencia("clienteDao").obtencion).isEqualTo(Dependencia.TipoObtencion.SETTER);
        AnalisisServicio ac = m.analisis(cobro.clase);
        assertThat(ac.dependencia("gateway").obtencion).isEqualTo(Dependencia.TipoObtencion.CAMPO_SIN_ANOTAR);
        assertThat(ac.modo).isEqualTo(Dependencia.ModoMontaje.INJECT_MOCKS);
    }

    @Test
    void descartaPorCausa() throws Exception {
        ModuloEscaneado m = Fixtures.unico(Fixtures.escanear(Fixtures.copiar("proyecto-spring", tmp.resolve("p"))));
        assertThat(m.deteccion.descartes).containsKeys("enum", "excepción", "DTO / datos", "controller", "configuración",
                "clase main", "interfaz");
        List<String> servicios = m.deteccion.servicios.stream().map(c -> c.clase.simple).collect(Collectors.toList());
        assertThat(servicios).doesNotContain("Usuario", "UsuarioController", "EstadoPedido", "NegocioException",
                "ConfiguracionApp", "Aplicacion", "UsuarioRepository", "CalculadoraPrecio");
    }

    @Test
    void incluirYExcluirPorYml() throws Exception {
        Path p = Fixtures.copiar("proyecto-spring", tmp.resolve("p"));
        Fixtures.escribir(p.resolve("junit-gen.yml"), """
                incluir:
                  clases: ["com.ejemplo.util.CalculadoraPrecio"]
                excluir:
                  clases: ["*Notificador"]
                  paquetes: ["com.ejemplo.otros"]
                """);
        ModuloEscaneado m = Fixtures.unico(Fixtures.escanear(p));
        assertThat(candidato(m, "com.ejemplo.util.CalculadoraPrecio")).isNotNull();
        assertThat(candidato(m, "com.ejemplo.util.CalculadoraPrecio").motivo).contains("incluida por junit-gen.yml");
        assertThat(candidato(m, "com.ejemplo.service.Notificador")).isNull();
        assertThat(candidato(m, "com.ejemplo.otros.RelojSistema")).isNull();
        assertThat(m.deteccion.descartes.get("excluida por junit-gen.yml")).isEqualTo(2);
    }

    @Test
    void clasificaObtencionYRequiereRefactorSegunMockito() throws Exception {
        ModuloEscaneado m = Fixtures.unico(Fixtures.escanear(Fixtures.copiar("proyecto-spring", tmp.resolve("p"))));
        InfoClase pedido = m.indice.get("com.ejemplo.service.PedidoService");
        AnalisisServicio a = m.analisis(pedido);
        assertThat(a.dependencia("usuarios").obtencion).isEqualTo(Dependencia.TipoObtencion.CAMPO_ANOTADO);
        assertThat(a.dependencia("CalculadoraPrecio").obtencion).isEqualTo(Dependencia.TipoObtencion.NEW_INTERNO);
        assertThat(a.dependencia("LocalDate").obtencion).isEqualTo(Dependencia.TipoObtencion.ESTATICA);
        assertThat(a.modo).isEqualTo(Dependencia.ModoMontaje.INJECT_MOCKS);
        assertThat(a.requiereRefactor()).isFalse();

        VersionesProyecto antiguo = new VersionesProyecto();
        antiguo.java = 8;
        antiguo.mockito = true;
        antiguo.versionMockito = "2.23.0";
        antiguo.junitElegido = 4;
        AnalisisServicio viejo = AnalizadorDependencias.analizar(pedido, m.indice, antiguo);
        assertThat(viejo.refactor).containsKey("total(double,int)");
        assertThat(viejo.refactor).doesNotContainKey("nombreUsuario(Long)");

        AnalisisServicio usuario = m.analisis(m.indice.get("com.ejemplo.service.UsuarioServiceImpl"));
        assertThat(usuario.modo).isEqualTo(Dependencia.ModoMontaje.INJECT_MOCKS);
        assertThat(usuario.dependencia("repo").obtencion).isEqualTo(Dependencia.TipoObtencion.CONSTRUCTOR);
    }
}
