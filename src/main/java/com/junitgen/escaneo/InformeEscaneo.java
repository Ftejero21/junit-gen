package com.junitgen.escaneo;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.junitgen.duplicados.ExtractorHuellasTests;
import com.junitgen.generacion.RutasSalida;
import com.junitgen.seguridad.SeguridadException;
import com.junitgen.util.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Informe del escaneo en Markdown (paginado) y JSON (completo). */
public final class InformeEscaneo {

    public static class Opciones {
        public String modulo;
        public String filtro;
        public boolean soloSinCubrir;
        public int pagina = 1;
        public int tamPagina = 40;
        /** Limitar a estos módulos (rel); vacío = sin efecto. */
        public Set<String> modulosRel = new LinkedHashSet<>();
        /** Limitar las filas a estos FQN exactos; vacío = sin efecto. */
        public Set<String> clasesFqn = new LinkedHashSet<>();
        /** Texto de la sección «Siguiente paso»; null = pedir confirmación de la selección. */
        public String siguientePaso;
    }

    public static final String SIGUIENTE_PASO = "Confirma o ajusta la selección de servicios antes de continuar. Para cada servicio se pedirá "
            + "su detalle (detalle_servicio) y se escribirá un spec solo para los métodos o escenarios sin tests.";

    /** Fila del informe para un servicio. */
    public static class Fila {
        public ModuloEscaneado modulo;
        public DetectorServicios.Candidato candidato;
        public List<String> tests = new ArrayList<>();
        public List<String> conTests = new ArrayList<>();
        public List<String> sinTests = new ArrayList<>();
        public int generadosEnSalida;
    }

    private InformeEscaneo() {
    }

    /** Filtro de módulo común a filas y markdown: {@code modulo} y {@code modulosRel}. */
    static boolean incluido(ProyectoEscaneado p, ModuloEscaneado me, Opciones op) {
        if (op.modulo != null && !op.modulo.isBlank() && me != p.modulo(op.modulo)) {
            return false;
        }
        return enModulosPedidos(me, op);
    }

    private static boolean enModulosPedidos(ModuloEscaneado me, Opciones op) {
        return op.modulosRel == null || op.modulosRel.isEmpty() || op.modulosRel.contains(me.modulo.rel);
    }

    private static boolean filtraClases(Opciones op) {
        return op.clasesFqn != null && !op.clasesFqn.isEmpty();
    }

    public static List<Fila> filas(ProyectoEscaneado p, Path salida, Opciones op) {
        List<Fila> r = new ArrayList<>();
        for (ModuloEscaneado me : p.modulos) {
            if (!incluido(p, me, op)) {
                continue;
            }
            for (DetectorServicios.Candidato c : me.deteccion.servicios) {
                if (filtraClases(op) && !op.clasesFqn.contains(c.clase.fqn)) {
                    continue;
                }
                Fila f = fila(me, c, salida);
                if (op.filtro != null && !op.filtro.isBlank()
                        && !c.clase.fqn.toLowerCase(Locale.ROOT).contains(op.filtro.toLowerCase(Locale.ROOT))) {
                    continue;
                }
                if (op.soloSinCubrir && f.sinTests.isEmpty()) {
                    continue;
                }
                r.add(f);
            }
        }
        return r;
    }

    static Fila fila(ModuloEscaneado me, DetectorServicios.Candidato c, Path salida) {
        Fila f = new Fila();
        f.modulo = me;
        f.candidato = c;
        EmparejadorTests.Emparejamiento e = me.emparejamiento(c.clase);
        for (EmparejadorTests.TestEmparejado t : e.tests) {
            f.tests.add(t.test.simple + " (" + t.confianza + ", " + t.via + ")");
        }
        for (InfoMetodo m : c.clase.metodosPublicos()) {
            if (m.esAccessor()) {
                continue;
            }
            if (e.casosDe(m.nombre, m.aridad()).isEmpty()) {
                f.sinTests.add(m.nombre);
            } else {
                f.conTests.add(m.nombre);
            }
        }
        if (salida != null) {
            f.generadosEnSalida = generadosEnSalida(salida, me, c.clase);
        }
        return f;
    }

    static int generadosEnSalida(Path salida, ModuloEscaneado me, InfoClase c) {
        int n = 0;
        List<Path> dirs;
        try {
            dirs = RutasSalida.carpetasTest(salida, me, c);
        } catch (SeguridadException e) {
            return 0;
        }
        for (Path dir : dirs) {
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, c.simple + "*Test.java")) {
                for (Path f : ds) {
                    String texto = Files.readString(f, StandardCharsets.UTF_8);
                    if (c.paquete.equals(RutasSalida.paqueteDeclarado(texto))) {
                        n += ExtractorHuellasTests.marcadores(texto).size();
                    }
                }
            } catch (IOException e) {
                return n;
            }
        }
        return n;
    }

    private static String lista(List<String> l) {
        if (l.isEmpty()) {
            return "—";
        }
        List<String> v = l.size() > 15 ? l.subList(0, 15) : l;
        return String.join(", ", v) + (l.size() > 15 ? " +" + (l.size() - 15) : "");
    }

    private static String celda(String s) {
        return s == null ? "" : s.replace("|", "\\|").replace("\n", " ");
    }

    public static String markdown(ProyectoEscaneado p, Path salida, Opciones op) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Escaneo de ").append(p.raiz).append("\n\n");
        sb.append("## Módulos\n\n| Módulo | Build | Fuentes main / test | Versiones |\n|---|---|---|---|\n");
        for (ModuloEscaneado me : p.modulos) {
            sb.append("| ").append(celda(me.modulo.nombre())).append(" | ").append(me.modulo.build).append(" | ")
                    .append(me.indice.archivosMain).append(" / ").append(me.indice.archivosTest).append(" | ")
                    .append(celda(me.versiones.resumen())).append(" |\n");
        }
        sb.append('\n');
        List<String> avisos = new ArrayList<>(p.avisos);
        for (ModuloEscaneado me : p.modulos) {
            me.versiones.avisos.forEach(a -> avisos.add(me.modulo.nombre() + ": " + a));
            me.avisos.forEach(a -> avisos.add(me.modulo.nombre() + ": " + a));
        }
        sb.append("## Advertencias\n\n");
        if (avisos.isEmpty()) {
            sb.append("Ninguna.\n\n");
        } else {
            for (int i = 0; i < Math.min(30, avisos.size()); i++) {
                sb.append("- ").append(avisos.get(i)).append('\n');
            }
            if (avisos.size() > 30) {
                sb.append("- … y ").append(avisos.size() - 30).append(" más (ver escaneo.json).\n");
            }
            sb.append('\n');
        }
        for (ModuloEscaneado me : p.modulos) {
            if (!incluido(p, me, op)) {
                continue;
            }
            AnalizadorEstiloTests.EstiloTests e = me.estilo;
            sb.append("## Estructura de los tests existentes — ").append(me.modulo.nombre()).append("\n\n");
            sb.append("- Tests: ").append(e.totalTests).append(" (unitarios ").append(e.unitarios).append(", Spring ")
                    .append(e.springBoot).append(")\n");
            sb.append("- Porcentajes: ");
            List<String> ps = new ArrayList<>();
            for (Map.Entry<String, Integer> x : e.porcentajes.entrySet()) {
                ps.add(x.getKey() + " " + x.getValue() + "%");
            }
            sb.append(String.join(", ", ps)).append('\n');
            sb.append("- Anotación de clase dominante: ").append(e.anotacionClase).append('\n');
            sb.append("- Estilo sugerido: patronNombre `").append(e.patronNombre).append("`, aserciones ").append(e.aserciones)
                    .append(", visibilidad ").append(e.visibilidad).append(", campo SUT `").append(e.nombreCampoSut)
                    .append("`, @DisplayName ").append(e.displayName ? "sí" : "no").append(", comentarios given/when/then ")
                    .append(e.comentariosGwt ? "sí" : "no").append("\n\n");
            int i = 1;
            for (AnalizadorEstiloTests.Extracto x : e.extractos) {
                sb.append("### Extracto ").append(i++).append(": ").append(x.clase).append("\n\n```java\n").append(x.codigo)
                        .append("```\n\n");
            }
        }
        List<Fila> filas = filas(p, salida, op);
        int tam = Math.max(1, op.tamPagina);
        int paginas = Math.max(1, (filas.size() + tam - 1) / tam);
        int pagina = Math.max(1, Math.min(op.pagina, paginas));
        sb.append("## Servicios detectados (página ").append(pagina).append(" de ").append(paginas).append(", ")
                .append(filas.size()).append(" en total")
                .append(op.filtro != null && !op.filtro.isBlank() ? ", filtro '" + op.filtro + "'" : "")
                .append(op.soloSinCubrir ? ", solo con métodos sin tests" : "")
                .append(filtraClases(op) ? ", limitado a los servicios pedidos" : "").append(")\n\n");
        sb.append("| # | Clase | Módulo | Punt. | Motivo | Tests emparejados | Métodos con tests | Métodos sin tests |\n");
        sb.append("|---|---|---|---|---|---|---|---|\n");
        int desde = (pagina - 1) * tam;
        for (int k = desde; k < Math.min(filas.size(), desde + tam); k++) {
            Fila f = filas.get(k);
            String tests = lista(f.tests) + (f.generadosEnSalida > 0 ? " · " + f.generadosEnSalida + " casos ya generados en la salida" : "");
            String clase = f.candidato.clase.fqn + (f.modulo.analisis(f.candidato.clase).requiereRefactor() ? " ⚠ requiere refactor" : "");
            sb.append("| ").append(k + 1).append(" | ").append(celda(clase)).append(" | ").append(celda(f.modulo.modulo.nombre()))
                    .append(" | ").append(f.candidato.puntuacion).append(" | ").append(celda(f.candidato.motivo)).append(" | ")
                    .append(celda(tests)).append(" | ").append(celda(lista(f.conTests))).append(" | ")
                    .append(celda(lista(f.sinTests))).append(" |\n");
        }
        if (paginas > 1) {
            sb.append("\nHay más páginas: repite con `pagina` = ").append(pagina < paginas ? pagina + 1 : 1)
                    .append(" (o usa `filtro` / `soloSinCubrir`).\n");
        }
        sb.append('\n');
        boolean cabecera = false;
        for (ModuloEscaneado me : p.modulos) {
            if (!enModulosPedidos(me, op)) {
                continue;
            }
            for (DetectorServicios.Candidato c : me.deteccion.servicios) {
                if (filtraClases(op) && !op.clasesFqn.contains(c.clase.fqn)) {
                    continue;
                }
                AnalisisServicio a = me.analisis(c.clase);
                if (a.requiereRefactor()) {
                    if (!cabecera) {
                        sb.append("## Requieren refactor\n\n");
                        cabecera = true;
                    }
                    sb.append("- ").append(c.clase.fqn).append(": ");
                    List<String> ms = new ArrayList<>();
                    a.refactor.forEach((k, v) -> ms.add(k + " → " + String.join("; ", v)));
                    sb.append(String.join(" | ", ms)).append('\n');
                }
            }
        }
        if (cabecera) {
            sb.append('\n');
        }
        sb.append("## Dudosos (puntuación cercana al umbral, no seleccionados)\n\n");
        boolean hay = false;
        for (ModuloEscaneado me : p.modulos) {
            if (!enModulosPedidos(me, op)) {
                continue;
            }
            for (DetectorServicios.Candidato c : me.deteccion.dudosos) {
                if (!hay) {
                    sb.append("| Clase | Módulo | Punt. | Motivo |\n|---|---|---|---|\n");
                    hay = true;
                }
                sb.append("| ").append(celda(c.clase.fqn)).append(" | ").append(celda(me.modulo.nombre())).append(" | ")
                        .append(c.puntuacion).append(" | ").append(celda(c.motivo)).append(" |\n");
            }
        }
        sb.append(hay ? "\n" : "Ninguno.\n\n");
        sb.append("## Descartados por causa\n\n| Módulo | Causa | Nº |\n|---|---|---|\n");
        for (ModuloEscaneado me : p.modulos) {
            if (!enModulosPedidos(me, op)) {
                continue;
            }
            for (Map.Entry<String, Integer> d : me.deteccion.descartes.entrySet()) {
                sb.append("| ").append(celda(me.modulo.nombre())).append(" | ").append(celda(d.getKey())).append(" | ")
                        .append(d.getValue()).append(" |\n");
            }
        }
        sb.append("\n## Siguiente paso\n\n").append(op.siguientePaso == null ? SIGUIENTE_PASO : op.siguientePaso).append('\n');
        return sb.toString();
    }

    // ------------------------------------------------------------- secciones bajo petición (MCP)

    /** Secciones del informe que se piden por separado en escanear_proyecto {seccion}. */
    public static final List<String> SECCIONES = List.of("plan", "servicios", "estilo", "avisos", "refactor", "dudosos", "descartes");

    /** Sección del informe: cabecera (repetida en cada página), bloques paginables y texto si no hay bloques. */
    public record Seccion(String cabecera, List<String> bloques, String vacio) {
    }

    /** Módulos incluidos por {@code modulo} y {@code modulosRel}. */
    public static List<ModuloEscaneado> modulosIncluidos(ProyectoEscaneado p, Opciones op) {
        List<ModuloEscaneado> r = new ArrayList<>();
        for (ModuloEscaneado me : p.modulos) {
            if (incluido(p, me, op)) {
                r.add(me);
            }
        }
        return r;
    }

    /** Todas las advertencias del escaneo (proyecto, versiones y módulos), sin tope. */
    public static List<String> avisos(ProyectoEscaneado p) {
        List<String> avisos = new ArrayList<>(p.avisos);
        for (ModuloEscaneado me : p.modulos) {
            me.versiones.avisos.forEach(a -> avisos.add(me.modulo.nombre() + ": " + a));
            me.avisos.forEach(a -> avisos.add(me.modulo.nombre() + ": " + a));
        }
        return avisos;
    }

    public static int serviciosDetectados(ProyectoEscaneado p) {
        return p.modulos.stream().mapToInt(me -> me.deteccion.servicios.size()).sum();
    }

    /** «patronNombre `x`, aserciones y, …» del estilo sugerido del módulo (sin extractos). */
    public static String lineaEstilo(ModuloEscaneado me) {
        AnalizadorEstiloTests.EstiloTests e = me.estilo;
        return "patronNombre `" + e.patronNombre + "`, aserciones " + e.aserciones + ", visibilidad " + e.visibilidad
                + ", campo SUT `" + e.nombreCampoSut + "`, @DisplayName " + (e.displayName ? "sí" : "no")
                + ", comentarios given/when/then " + (e.comentariosGwt ? "sí" : "no");
    }

    private static String filaServicio(int numero, Fila f) {
        String tests = lista(f.tests) + (f.generadosEnSalida > 0 ? " · " + f.generadosEnSalida + " casos ya generados en la salida" : "");
        String clase = f.candidato.clase.fqn + (f.modulo.analisis(f.candidato.clase).requiereRefactor() ? " ⚠ requiere refactor" : "");
        return "| " + numero + " | " + celda(clase) + " | " + celda(f.modulo.modulo.nombre()) + " | " + f.candidato.puntuacion + " | "
                + celda(f.candidato.motivo) + " | " + celda(tests) + " | " + celda(lista(f.conTests)) + " | "
                + celda(lista(f.sinTests)) + " |\n";
    }

    /**
     * Una sección del informe como cabecera + bloques (filas, viñetas o extractos) para paginar por
     * presupuesto. «avisos» va sin el tope de 30. «plan» no se pinta aquí (es el progreso de la sesión).
     */
    public static Seccion seccion(ProyectoEscaneado p, Path salida, Opciones op, String nombre) {
        List<String> bloques = new ArrayList<>();
        switch (nombre) {
            case "servicios" -> {
                List<Fila> filas = filas(p, salida, op);
                for (int k = 0; k < filas.size(); k++) {
                    bloques.add(filaServicio(k + 1, filas.get(k)));
                }
                String cab = "## Servicios detectados (" + filas.size() + " en total"
                        + (op.filtro != null && !op.filtro.isBlank() ? ", filtro '" + op.filtro + "'" : "")
                        + (op.soloSinCubrir ? ", solo con métodos sin tests" : "")
                        + (filtraClases(op) ? ", limitado a los servicios pedidos" : "") + ")\n\n"
                        + "| # | Clase | Módulo | Punt. | Motivo | Tests emparejados | Métodos con tests | Métodos sin tests |\n"
                        + "|---|---|---|---|---|---|---|---|\n";
                return new Seccion(cab, bloques, "Ningún servicio detectado con esos filtros.");
            }
            case "estilo" -> {
                for (ModuloEscaneado me : modulosIncluidos(p, op)) {
                    AnalizadorEstiloTests.EstiloTests e = me.estilo;
                    List<String> ps = new ArrayList<>();
                    for (Map.Entry<String, Integer> x : e.porcentajes.entrySet()) {
                        ps.add(x.getKey() + " " + x.getValue() + "%");
                    }
                    bloques.add("### " + me.modulo.nombre() + "\n\n- Tests: " + e.totalTests + " (unitarios " + e.unitarios + ", Spring "
                            + e.springBoot + ")\n- Porcentajes: " + String.join(", ", ps) + "\n- Anotación de clase dominante: "
                            + e.anotacionClase + "\n- Estilo sugerido: " + lineaEstilo(me) + "\n\n");
                    int i = 1;
                    for (AnalizadorEstiloTests.Extracto x : e.extractos) {
                        bloques.add("#### Extracto " + i++ + ": " + x.clase + "\n\n```java\n" + x.codigo + "```\n\n");
                    }
                }
                return new Seccion("## Estructura de los tests existentes\n\n", bloques, "Ningún módulo con esos filtros.");
            }
            case "avisos" -> {
                avisos(p).forEach(a -> bloques.add("- " + a + "\n"));
                return new Seccion("## Advertencias\n\n", bloques, "Ninguna.");
            }
            case "refactor" -> {
                for (ModuloEscaneado me : p.modulos) {
                    if (!enModulosPedidos(me, op)) {
                        continue;
                    }
                    for (DetectorServicios.Candidato c : me.deteccion.servicios) {
                        if (filtraClases(op) && !op.clasesFqn.contains(c.clase.fqn)) {
                            continue;
                        }
                        AnalisisServicio a = me.analisis(c.clase);
                        if (a.requiereRefactor()) {
                            List<String> ms = new ArrayList<>();
                            a.refactor.forEach((k, v) -> ms.add(k + " → " + String.join("; ", v)));
                            bloques.add("- " + c.clase.fqn + ": " + String.join(" | ", ms) + "\n");
                        }
                    }
                }
                return new Seccion("## Requieren refactor\n\n", bloques, "Ninguno.");
            }
            case "dudosos" -> {
                for (ModuloEscaneado me : p.modulos) {
                    if (!enModulosPedidos(me, op)) {
                        continue;
                    }
                    for (DetectorServicios.Candidato c : me.deteccion.dudosos) {
                        bloques.add("| " + celda(c.clase.fqn) + " | " + celda(me.modulo.nombre()) + " | " + c.puntuacion + " | "
                                + celda(c.motivo) + " |\n");
                    }
                }
                return new Seccion("## Dudosos (puntuación cercana al umbral, no seleccionados)\n\n| Clase | Módulo | Punt. | Motivo |\n"
                        + "|---|---|---|---|\n", bloques, "Ninguno.");
            }
            case "descartes" -> {
                for (ModuloEscaneado me : p.modulos) {
                    if (!enModulosPedidos(me, op)) {
                        continue;
                    }
                    for (Map.Entry<String, Integer> d : me.deteccion.descartes.entrySet()) {
                        bloques.add("| " + celda(me.modulo.nombre()) + " | " + celda(d.getKey()) + " | " + d.getValue() + " |\n");
                    }
                }
                return new Seccion("## Descartados por causa\n\n| Módulo | Causa | Nº |\n|---|---|---|\n", bloques, "Ninguno.");
            }
            default -> throw new IllegalArgumentException("Sección desconocida '" + nombre + "': usa una de " + SECCIONES + ".");
        }
    }

    public static ObjectNode json(ProyectoEscaneado p, Path salida) {
        ObjectNode r = Json.objeto();
        r.put("proyecto", p.raiz.toString());
        if (salida != null) {
            r.put("salida", salida.toString());
        }
        ArrayNode avisos = r.putArray("avisos");
        p.avisos.forEach(avisos::add);
        ArrayNode mods = r.putArray("modulos");
        for (ModuloEscaneado me : p.modulos) {
            ObjectNode m = mods.addObject();
            m.put("nombre", me.modulo.nombre());
            m.put("rel", me.modulo.rel);
            m.put("build", me.modulo.build.toString());
            m.put("fuentesMain", me.indice.archivosMain);
            m.put("fuentesTest", me.indice.archivosTest);
            m.set("versiones", Json.MAPPER.valueToTree(me.versiones));
            ArrayNode av = m.putArray("avisos");
            me.avisos.forEach(av::add);
            ObjectNode est = m.putObject("estiloTests");
            est.put("total", me.estilo.totalTests);
            est.put("unitarios", me.estilo.unitarios);
            est.put("spring", me.estilo.springBoot);
            est.set("porcentajes", Json.MAPPER.valueToTree(me.estilo.porcentajes));
            est.set("sugerencia", Json.MAPPER.valueToTree(me.estilo.sugerencia));
            ArrayNode servs = m.putArray("servicios");
            for (DetectorServicios.Candidato c : me.deteccion.servicios) {
                Fila f = fila(me, c, salida);
                ObjectNode s = servs.addObject();
                s.put("clase", c.clase.fqn);
                s.put("puntuacion", c.puntuacion);
                s.put("motivo", c.motivo);
                AnalisisServicio a = me.analisis(c.clase);
                s.put("montaje", a.modo.toString());
                s.put("requiereRefactor", a.requiereRefactor());
                ArrayNode t = s.putArray("tests");
                f.tests.forEach(t::add);
                ArrayNode con = s.putArray("metodosConTests");
                f.conTests.forEach(con::add);
                ArrayNode sin = s.putArray("metodosSinTests");
                f.sinTests.forEach(sin::add);
                if (salida != null) {
                    s.put("casosGeneradosEnSalida", f.generadosEnSalida);
                }
            }
            ArrayNode dud = m.putArray("dudosos");
            for (DetectorServicios.Candidato c : me.deteccion.dudosos) {
                ObjectNode d = dud.addObject();
                d.put("clase", c.clase.fqn);
                d.put("puntuacion", c.puntuacion);
                d.put("motivo", c.motivo);
            }
            m.set("descartes", Json.MAPPER.valueToTree(me.deteccion.descartes));
        }
        return r;
    }
}
