package com.junitgen.escaneo;

import com.junitgen.Fixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Búsqueda de servicios por niveles: exacta, sin mayúsculas, interfaz → implementación y sufijo Impl. */
class BuscadorServiciosTest {

    @TempDir
    Path tmp;

    private static List<String> fqns(List<ProyectoEscaneado.Encontrada> l) {
        return l.stream().map(e -> e.clase().fqn).toList();
    }

    private ProyectoEscaneado spring() throws Exception {
        Path p = Fixtures.copiar("proyecto-spring", tmp.resolve("p"));
        Fixtures.escribir(p.resolve("src/main/java/com/ejemplo/service/AvisoServiceImpl.java"),
                "package com.ejemplo.service;\n\npublic class AvisoServiceImpl {\n\n    public int avisar(int x) {\n"
                        + "        return x > 0 ? 1 : 0;\n    }\n}\n");
        Fixtures.escribir(p.resolve("src/main/java/com/ejemplo/service/SinImplementacion.java"),
                "package com.ejemplo.service;\n\npublic interface SinImplementacion {\n\n    int hacer();\n}\n");
        return Fixtures.escanear(p);
    }

    @Test
    void interfazSeSustituyePorSuImplementacionConOSinMayusculas() throws Exception {
        ProyectoEscaneado p = spring();
        for (String nombre : List.of("UsuarioService", "usuarioservice", "usuarioService", "com.ejemplo.service.UsuarioService")) {
            BuscadorServicios.Resultado r = BuscadorServicios.buscar(p, nombre, null);
            assertThat(r.resuelto()).as(nombre).isTrue();
            assertThat(fqns(r.objetivos())).as(nombre).containsExactly("com.ejemplo.service.UsuarioServiceImpl");
            assertThat(r.via()).as(nombre).isEqualTo(BuscadorServicios.VIA_INTERFAZ);
            assertThat(r.ambiguo()).isFalse();
        }
    }

    @Test
    void exactaSinMayusculasYSufijoImpl() throws Exception {
        ProyectoEscaneado p = spring();
        BuscadorServicios.Resultado exacta = BuscadorServicios.buscar(p, "UsuarioServiceImpl", null);
        assertThat(fqns(exacta.objetivos())).containsExactly("com.ejemplo.service.UsuarioServiceImpl");
        assertThat(exacta.via()).isEqualTo(BuscadorServicios.VIA_EXACTA);

        BuscadorServicios.Resultado minusculas = BuscadorServicios.buscar(p, "pedidoservice", null);
        assertThat(fqns(minusculas.objetivos())).containsExactly("com.ejemplo.service.PedidoService");
        assertThat(minusculas.via()).isEqualTo(BuscadorServicios.VIA_SIN_MAYUSCULAS);

        BuscadorServicios.Resultado conImpl = BuscadorServicios.buscar(p, "avisoService", null);
        assertThat(fqns(conImpl.objetivos())).containsExactly("com.ejemplo.service.AvisoServiceImpl");
        assertThat(conImpl.via()).isEqualTo(BuscadorServicios.VIA_SUFIJO_IMPL);

        BuscadorServicios.Resultado sinImpl = BuscadorServicios.buscar(p, "PedidoServiceIMPL", null);
        assertThat(fqns(sinImpl.objetivos())).containsExactly("com.ejemplo.service.PedidoService");
        assertThat(sinImpl.via()).isEqualTo(BuscadorServicios.VIA_SUFIJO_IMPL);

        BuscadorServicios.Resultado nada = BuscadorServicios.buscar(p, "NoExiste", null);
        assertThat(nada.noEncontrado()).isTrue();
        assertThat(nada.objetivos()).isEmpty();
    }

    @Test
    void interfazSinImplementacionNoSeResuelve() throws Exception {
        BuscadorServicios.Resultado r = BuscadorServicios.buscar(spring(), "sinImplementacion", null);
        assertThat(r.resuelto()).isFalse();
        assertThat(r.ambiguo()).isFalse();
        assertThat(r.interfazSinImplementacion()).isTrue();
        assertThat(fqns(r.candidatos())).containsExactly("com.ejemplo.service.SinImplementacion");
    }

    @Test
    void implementacionEnOtroModuloYAmbiguoConClaseNoRelacionada() throws Exception {
        Path m = Fixtures.copiar("multi", tmp.resolve("m"));
        Fixtures.escribir(m.resolve("modA/src/main/java/com/a/Pago.java"), "package com.a;\n\npublic interface Pago {\n\n    int pagar(int x);\n}\n");
        Fixtures.escribir(m.resolve("modB/src/main/java/com/b/PagoTarjeta.java"), "package com.b;\n\nimport com.a.Pago;\n\n"
                + "public class PagoTarjeta implements Pago {\n\n    @Override\n    public int pagar(int x) {\n        return x > 0 ? x : 0;\n    }\n}\n");
        BuscadorServicios.Resultado r = BuscadorServicios.buscar(Fixtures.escanear(m), "pago", null);
        assertThat(fqns(r.objetivos())).containsExactly("com.b.PagoTarjeta");
        assertThat(r.objetivos().get(0).modulo().modulo.nombre()).isEqualTo("modB");
        assertThat(r.via()).isEqualTo(BuscadorServicios.VIA_INTERFAZ);

        Fixtures.escribir(m.resolve("modB/src/main/java/com/b/Pago.java"), "package com.b;\n\npublic class Pago {\n\n"
                + "    public int doble(int x) {\n        return x * 2;\n    }\n}\n");
        BuscadorServicios.Resultado amb = BuscadorServicios.buscar(Fixtures.escanear(m), "Pago", null);
        assertThat(amb.ambiguo()).isTrue();
        assertThat(fqns(amb.candidatos())).containsExactlyInAnyOrder("com.a.Pago", "com.b.Pago");
    }
}
