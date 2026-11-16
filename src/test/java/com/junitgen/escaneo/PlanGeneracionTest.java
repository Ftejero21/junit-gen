package com.junitgen.escaneo;

import com.junitgen.Fixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Plan determinista de N servicios: orden y selección, todas las funciones, fórmula de casos, M &lt; N y descartes. */
class PlanGeneracionTest {

    private static final Set<String> SINTETICOS = Set.of("com.p.service.AltaService", "com.p.service.BajaService");

    @TempDir
    Path tmp;

    /** Proyecto de un módulo: AltaService a1 (3 ramas), a2 (2), a3 (1), a4 (1); BajaService b1 (0). */
    private ProyectoEscaneado sintetico() throws Exception {
        Path p = tmp.resolve("sintetico");
        Files.createDirectories(p);
        Files.copy(Fixtures.origen("multi/modA/pom.xml"), p.resolve("pom.xml"), StandardCopyOption.REPLACE_EXISTING);
        Fixtures.escribir(p.resolve("src/main/java/com/p/service/AltaService.java"), "package com.p.service;\n\npublic class AltaService {\n\n"
                + "    public int a1(int x) {\n        int r = 0;\n        if (x > 0) {\n            r++;\n        }\n        if (x > 1) {\n"
                + "            r++;\n        }\n        if (x > 2) {\n            r++;\n        }\n        return r;\n    }\n\n"
                + "    public int a2(int x) {\n        int r = 0;\n        if (x > 0) {\n            r++;\n        }\n        if (x > 1) {\n"
                + "            r++;\n        }\n        return r;\n    }\n\n"
                + "    public int a3(int x) {\n        if (x > 0) {\n            return 1;\n        }\n        return 0;\n    }\n\n"
                + "    public int a4(int x) {\n        if (x < 0) {\n            return -1;\n        }\n        return 0;\n    }\n}\n");
        Fixtures.escribir(p.resolve("src/main/java/com/p/service/BajaService.java"), "package com.p.service;\n\npublic class BajaService {\n\n"
                + "    public int b1(int x) {\n        return x + 1;\n    }\n}\n");
        return Fixtures.escanear(p);
    }

    private static Map<String, Integer> reparto(PlanGeneracion plan) {
        Map<String, Integer> r = new LinkedHashMap<>();
        plan.objetivos().forEach(o -> r.put(o.metodo.nombre, o.casos));
        return r;
    }

    @Test
    void formulaDeCasosPorMetodo() {
        assertThat(PlanGeneracion.Objetivo.casos(0, 0)).isEqualTo(1);
        assertThat(PlanGeneracion.Objetivo.casos(1, 0)).isEqualTo(2);
        assertThat(PlanGeneracion.Objetivo.casos(2, 0)).isEqualTo(3);
        assertThat(PlanGeneracion.Objetivo.casos(7, 0)).as("máximo 1 + 2 ramas").isEqualTo(3);
        assertThat(PlanGeneracion.Objetivo.casos(3, 1)).isEqualTo(2);
        assertThat(PlanGeneracion.Objetivo.casos(3, 2)).isEqualTo(1);
        assertThat(PlanGeneracion.Objetivo.casos(3, 9)).as("con tests, al menos un escenario nuevo").isEqualTo(1);
        assertThat(PlanGeneracion.Objetivo.casos(0, 1)).isEqualTo(1);
    }

    @Test
    void ordenYSeleccionDeNServiciosConTodasSusFunciones() throws Exception {
        ProyectoEscaneado p = sintetico();
        PlanGeneracion uno = PlanGeneracion.calcular(p, null, 1, null, SINTETICOS);
        assertThat(uno.fqns()).containsExactly("com.p.service.AltaService");
        assertThat(uno.pedidos()).isEqualTo(1);
        assertThat(reparto(uno)).containsExactly(Map.entry("a1", 3), Map.entry("a2", 3), Map.entry("a3", 2), Map.entry("a4", 2));
        assertThat(uno.total()).isEqualTo(10);
        assertThat(uno.totalMetodos()).isEqualTo(4);
        assertThat(uno.avisos()).isEmpty();

        PlanGeneracion dos = PlanGeneracion.calcular(p, null, 2, null, SINTETICOS);
        assertThat(dos.fqns()).containsExactly("com.p.service.AltaService", "com.p.service.BajaService");
        assertThat(dos.servicios().get(0).sinTests).isEqualTo(4);
        assertThat(dos.servicios().get(0).ramas).isEqualTo(7);
        assertThat(dos.total()).isEqualTo(11);
        assertThat(dos.markdown()).contains("## Plan de generación", "| # | Servicio | Módulo | Métodos (casos) | Casos | Motivo |",
                "| 1 | com.p.service.AltaService |", "a1(int) ×3, a2(int) ×3, a3(int) ×2, a4(int) ×2 | 10 |",
                "4 de 4 métodos sin tests; 7 ramas; 0 tests; montaje", "| 2 | com.p.service.BajaService |", "b1(int) ×1 | 1 |",
                "**Total: 2 servicios, 5 métodos, 11 casos** (servicios pedidos: 2).").doesNotContain("ya tiene tests");
    }

    @Test
    void menosServiciosDisponiblesQueNConAviso() throws Exception {
        PlanGeneracion plan = PlanGeneracion.calcular(sintetico(), null, 5, null, SINTETICOS);
        assertThat(plan.servicios()).hasSize(2);
        assertThat(plan.pedidos()).isEqualTo(5);
        assertThat(plan.avisos()).anyMatch(a -> a.contains("Solo hay 2 servicios disponibles de los 5 pedidos"));
        assertThat(plan.markdown()).contains("Aviso: Solo hay 2 servicios disponibles");
    }

    @Test
    void serviciosNombradosSinNYValidacionDeRango() throws Exception {
        ProyectoEscaneado p = sintetico();
        PlanGeneracion plan = PlanGeneracion.calcular(p, null, null, null, SINTETICOS);
        assertThat(plan.pedidos()).isNull();
        assertThat(plan.fqns()).containsExactlyInAnyOrder("com.p.service.AltaService", "com.p.service.BajaService");
        assertThat(plan.markdown()).contains("**Total: 2 servicios, 5 métodos, 11 casos**.").doesNotContain("servicios pedidos");
        assertThatThrownBy(() -> PlanGeneracion.calcular(p, null, null, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PlanGeneracion.calcular(p, null, 0, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PlanGeneracion.calcular(p, null, 201, null, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void descartaRefactorEIncluyeLosMetodosConTestsDelProyectoYDelSpec() throws Exception {
        Path p = Fixtures.copiar("proyecto-spring", tmp.resolve("spring"));
        ProyectoEscaneado proyecto = Fixtures.escanear(p);
        ModuloEscaneado me = Fixtures.unico(proyecto);
        me.analisis(me.indice.get("com.ejemplo.service.PedidoService")).refactor.put("total(double,int)", List.of("forzado en el test"));
        PlanGeneracion plan = PlanGeneracion.calcular(proyecto, null, 200, null, null);
        assertThat(plan.fqns()).doesNotContain("com.ejemplo.service.PedidoService").contains("com.ejemplo.service.UsuarioServiceImpl");
        assertThat(plan.objetivos()).filteredOn(o -> o.servicio.fqn.equals("com.ejemplo.service.UsuarioServiceImpl"))
                .extracting(o -> o.metodo.nombre).containsExactlyInAnyOrder("buscar", "activar", "contarActivos");
        PlanGeneracion.Objetivo buscar = objetivo(plan, "buscar");
        assertThat(buscar.conTests).as("ya tiene tests en el proyecto: entra en el plan").isTrue();
        assertThat(buscar.casos).isEqualTo(PlanGeneracion.Objetivo.casos(buscar.ramas, buscar.existentes));
        assertThat(objetivo(plan, "activar").conTests).isFalse();
        assertThat(plan.avisos()).anyMatch(a -> a.contains("Descartados por requerir refactor: com.ejemplo.service.PedidoService"));
        assertThat(plan.markdown()).contains("buscar(Long)*", "* ya tiene tests: solo escenarios nuevos");
        assertThat(plan.modulosRel()).containsExactly(me.modulo.rel);

        Path salida = tmp.resolve("salida");
        String ruta = p.toString().replace('\\', '/');
        Fixtures.escribir(salida.resolve("spec.json"), Fixtures.j("{'version':1,'proyecto':{'ruta':'" + ruta + "'},'servicios':[{'clase':"
                + "'com.ejemplo.service.UsuarioServiceImpl','casos':["
                + "{'id':'activar-ok','metodo':'activar','escenario':'e1','argumentos':['5L'],'esperado':{'tipo':'noNulo'},'estado':'generado'},"
                + "{'id':'contar-0','metodo':'contarActivos','escenario':'e2','argumentos':[],'esperado':{'tipo':'noNulo'},'estado':'eliminado'}"
                + "]}]}"));
        PlanGeneracion conSpec = PlanGeneracion.calcular(proyecto, salida, 200, null, null);
        PlanGeneracion.Objetivo activar = objetivo(conSpec, "activar");
        assertThat(activar.conTests).as("casos del spec.json de la salida cuentan como tests").isTrue();
        assertThat(activar.existentes).isEqualTo(1);
        assertThat(objetivo(conSpec, "contarActivos").conTests).as("los eliminados no cuentan").isFalse();

        Fixtures.escribir(salida.resolve("spec.json"), "{ esto no es json");
        PlanGeneracion corrupto = PlanGeneracion.calcular(proyecto, salida, 1, null, null);
        assertThat(corrupto.avisos()).anyMatch(a -> a.startsWith("Se ignora el spec.json de la salida"));
        assertThat(corrupto.servicios()).hasSize(1);
    }

    private static PlanGeneracion.Objetivo objetivo(PlanGeneracion plan, String metodo) {
        return plan.objetivos().stream().filter(o -> o.metodo.nombre.equals(metodo)).findFirst().orElseThrow();
    }

    @Test
    void servicioSinMetodosElegiblesQuedaFueraYPlanVacioConAviso() throws Exception {
        Path p = tmp.resolve("accessors");
        Files.createDirectories(p);
        Files.copy(Fixtures.origen("multi/modA/pom.xml"), p.resolve("pom.xml"), StandardCopyOption.REPLACE_EXISTING);
        Fixtures.escribir(p.resolve("src/main/java/com/p/service/DatoService.java"), "package com.p.service;\n\npublic class DatoService {\n\n"
                + "    private int x;\n\n    public int getX() {\n        return x;\n    }\n\n    public void setX(int x) {\n"
                + "        this.x = x;\n    }\n}\n");
        PlanGeneracion plan = PlanGeneracion.calcular(Fixtures.escanear(p), null, 3, null, Set.of("com.p.service.DatoService"));
        assertThat(plan.servicios()).isEmpty();
        assertThat(plan.total()).isZero();
        assertThat(plan.avisos()).anyMatch(a -> a.contains("no tener métodos públicos elegibles") && a.contains("com.p.service.DatoService"))
                .anyMatch(a -> a.contains("No hay servicios con métodos elegibles"));
        assertThat(plan.markdown()).contains("Plan vacío");

        PlanGeneracion inexistente = PlanGeneracion.calcular(sintetico(), null, 5, null, Set.of("com.p.NoExiste"));
        assertThat(inexistente.objetivos()).isEmpty();
        assertThat(inexistente.markdown()).contains("Plan vacío");
    }
}
