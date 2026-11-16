package com.junitgen.escaneo;

import com.junitgen.Fixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class EmparejadorTestsTest {

    @TempDir
    Path tmp;

    @Test
    void porClaseInstanciadaPrevaleceSobreElNombre() throws Exception {
        ModuloEscaneado m = Fixtures.unico(Fixtures.escanear(Fixtures.copiar("proyecto-spring", tmp.resolve("p"))));
        EmparejadorTests.Emparejamiento pedido = m.emparejamiento(m.indice.get("com.ejemplo.service.PedidoService"));
        assertThat(pedido.tests).hasSize(1);
        assertThat(pedido.tests.get(0).test.simple).isEqualTo("PruebasPedidosTest");
        assertThat(pedido.tests.get(0).via).contains("@InjectMocks");
    }

    @Test
    void porMetodosCubiertos() throws Exception {
        ModuloEscaneado m = Fixtures.unico(Fixtures.escanear(Fixtures.copiar("proyecto-spring", tmp.resolve("p"))));
        EmparejadorTests.Emparejamiento e = m.emparejamiento(m.indice.get("com.ejemplo.service.UsuarioServiceImpl"));
        assertThat(e.casosDe("buscar", 1)).hasSize(1);
        assertThat(e.casosDe("buscar", 1).get(0).metodoTest).isEqualTo("buscar_devuelveUsuarioSiExiste");
        assertThat(e.casosDe("activar", 1)).isEmpty();
        EmparejadorTests.Emparejamiento p = m.emparejamiento(m.indice.get("com.ejemplo.service.PedidoService"));
        assertThat(p.casosDe("nombreUsuario", 1)).hasSize(1);
        assertThat(p.casosDe("total", 2)).isEmpty();
    }

    @Test
    void prefijoServicioImplEscenarioConConfianzaMedia() throws Exception {
        ModuloEscaneado m = Fixtures.unico(Fixtures.escanear(Fixtures.copiar("proyecto-spring", tmp.resolve("p"))));
        EmparejadorTests.Emparejamiento e = m.emparejamiento(m.indice.get("com.ejemplo.service.UsuarioServiceImpl"));
        assertThat(e.tests.stream().map(t -> t.test.simple + ":" + t.confianza).collect(Collectors.toList()))
                .contains("UsuarioServiceImplTest:alta", "UsuarioServiceImplListarTest:media");
    }

    @Test
    void porNombreSinInstancia() throws Exception {
        Path p = tmp.resolve("n");
        Fixtures.escribir(p.resolve("pom.xml"), "<project><modelVersion>4.0.0</modelVersion><groupId>g</groupId><artifactId>a</artifactId>"
                + "<version>1</version></project>");
        Fixtures.escribir(p.resolve("src/main/java/com/x/service/FooServiceImpl.java"), """
                package com.x.service;
                import org.springframework.stereotype.Service;
                @Service
                public class FooServiceImpl {
                    public int uno(int a) { if (a > 0) { return 1; } return 0; }
                }
                """);
        Fixtures.escribir(p.resolve("src/test/java/com/x/service/FooServiceTest.java"), """
                package com.x.service;
                import org.junit.jupiter.api.Test;
                class FooServiceTest {
                    @Test void algo() { }
                }
                """);
        Fixtures.escribir(p.resolve("src/test/java/com/x/service/FooServiceImplCasosRarosTest.java"), """
                package com.x.service;
                import org.junit.jupiter.api.Test;
                class FooServiceImplCasosRarosTest {
                    @Test void otro() { }
                }
                """);
        ModuloEscaneado m = Fixtures.unico(Fixtures.escanear(p));
        EmparejadorTests.Emparejamiento e = m.emparejamiento(m.indice.get("com.x.service.FooServiceImpl"));
        assertThat(e.tests.stream().map(t -> t.test.simple + ":" + t.confianza + ":" + t.via).collect(Collectors.toList()))
                .containsExactlyInAnyOrder("FooServiceTest:alta:nombre", "FooServiceImplCasosRarosTest:media:prefijo de nombre");
    }

    @Test
    void normalizaSufijos() {
        assertThat(EmparejadorTests.quitarImpl(EmparejadorTests.quitarSufijoTest("Prueba1ServiceImplTest"))).isEqualTo("Prueba1Service");
        assertThat(EmparejadorTests.quitarImpl(EmparejadorTests.quitarSufijoTest("Prueba1ServiceTests"))).isEqualTo("Prueba1Service");
        assertThat(EmparejadorTests.quitarSufijoTest("Prueba1ServiceIT")).isEqualTo("Prueba1Service");
        assertThat(EmparejadorTests.quitarSufijoTest("Prueba1ServiceITCase")).isEqualTo("Prueba1Service");
    }
}
