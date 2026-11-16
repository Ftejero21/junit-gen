package com.junitgen.generacion;

import com.junitgen.duplicados.Duplicado;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** informe.md en la carpeta de salida: se añade una sección por ejecución. */
public final class EscritorInforme {

    private static final DateTimeFormatter FECHA = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int MAX_TEXTO = 300;

    private EscritorInforme() {
    }

    public static String fecha() {
        return LocalDateTime.now().format(FECHA);
    }

    /** Contenido previo + nueva sección (con cabecera, aviso y glosario si es nuevo; glosario una sola vez si falta). */
    public static String anadir(Path salida, String aviso, String seccion) {
        Path p = salida.resolve(RutasSalida.INFORME);
        String previo = "";
        if (Files.isRegularFile(p)) {
            try {
                previo = Files.readString(p, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IllegalStateException("No se puede leer el informe previo " + p + ": " + e.getMessage(), e);
            }
        }
        StringBuilder sb = new StringBuilder(previo);
        if (previo.isBlank()) {
            sb.setLength(0);
            sb.append("# Informe de junit-gen\n\n> ").append(aviso).append("\n\n")
                    .append("Este informe cuenta, en orden, lo que ha hecho junit-gen: qué tests ha creado, si funcionan y qué se ha "
                            + "decidido con los que fallan. Lo más reciente está al final.\n\n")
                    .append("junit-gen no modifica el proyecto: los tests se guardan en esta carpeta y copiarlos al proyecto es siempre "
                            + "un paso manual. La ruta de destino de cada test está en su cabecera (`// Copiar a:`) y en la tabla "
                            + "«Dónde copiar cada test».\n\n")
                    .append(TextosInforme.glosario());
        } else if (!previo.contains(TextosInforme.TITULO_GLOSARIO)) {
            if (!sb.toString().endsWith("\n")) {
                sb.append('\n');
            }
            sb.append('\n').append(TextosInforme.glosario());
        }
        if (!sb.toString().endsWith("\n")) {
            sb.append('\n');
        }
        sb.append('\n').append(seccion);
        if (!seccion.endsWith("\n")) {
            sb.append('\n');
        }
        return sb.toString();
    }

    public static String celda(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("|", "\\|").replace("\r", " ").replace("\n", " ");
    }

    public static String seccionGeneracion(ResultadoGeneracion g, String proyecto, String salida, List<Path> archivos) {
        StringBuilder sb = new StringBuilder();
        sb.append("## Creación de tests — ").append(fecha()).append("\n\n");
        sb.append(TextosInforme.RESUMEN).append(resumenGeneracion(g)).append("\n\n");
        sb.append("- Proyecto analizado (no se modifica): ").append(ruta(proyecto)).append('\n');
        sb.append("- Carpeta de los tests: ").append(ruta(salida)).append('\n');
        sb.append("- Tipo de test: ").append(g.perfiles.isEmpty() ? TextosInforme.SIN_DATO
                : TextosInforme.texto(String.join("; ", g.perfiles), MAX_TEXTO)).append("\n\n");

        Set<String> servicios = new LinkedHashSet<>();
        for (Map<String, String> m : g.generados) {
            servicios.add(m.get("servicio"));
        }
        sb.append("### Tests creados\n\n");
        if (g.generados.isEmpty()) {
            sb.append("Ninguno.\n\n");
        } else {
            Map<String, List<Map<String, String>>> porArchivo = new LinkedHashMap<>();
            for (Map<String, String> m : g.generados) {
                porArchivo.computeIfAbsent(String.valueOf(m.get("archivo")), k -> new ArrayList<>()).add(m);
            }
            for (Map.Entry<String, List<Map<String, String>>> e : porArchivo.entrySet()) {
                String servicio = e.getValue().get(0).get("servicio");
                sb.append("Archivo ").append(cod(TextosInforme.rutaRelativa(salida, e.getKey())))
                        .append(" (servicio ").append(cod(TextosInforme.claseCorta(servicio, servicios))).append("):\n\n");
                sb.append("| Test | Qué comprueba | Identificador |\n|---|---|---|\n");
                for (Map<String, String> m : e.getValue()) {
                    String caso = m.get("caso");
                    sb.append("| ").append(celda(cod(m.get("metodoTest")))).append(" | ")
                            .append(celda(TextosInforme.texto(caso == null ? null : g.queComprueba.get(caso), 200))).append(" | ")
                            .append(celda(cod(caso))).append(" |\n");
                }
                sb.append('\n');
            }
        }

        if (!g.descartados.isEmpty()) {
            sb.append("### Tests que no se han creado porque ya existía uno igual\n\n");
            sb.append("| Identificador | Dónde estaba el repetido | Coincide con | Detalle técnico |\n|---|---|---|---|\n");
            for (Duplicado d : g.descartados) {
                String nivel = d.nivel == 1 ? "en los tests que ya tiene el proyecto"
                        : d.nivel == 2 ? "en esta misma petición (estaba repetido)" : "en los tests creados antes por junit-gen";
                sb.append("| ").append(celda(cod(d.caso))).append(" | ").append(nivel).append(" | ")
                        .append(celda(TextosInforme.texto(d.coincideCon, MAX_TEXTO))).append(" | ")
                        .append(celda(TextosInforme.texto(d.motivo, MAX_TEXTO))).append(" |\n");
            }
            sb.append('\n');
        }

        if (!g.funcionesOmitidas.isEmpty()) {
            sb.append("### Funciones que se han probado y por qué\n\n");
            bloques(sb, g.funcionesOmitidas);
        }

        if (!g.noReescritos.isEmpty() || !g.omitidos.isEmpty() || !g.reemplazados.isEmpty() || !g.eliminados.isEmpty()) {
            sb.append("### Tests que ya existían\n\n");
            if (!g.omitidos.isEmpty()) {
                sb.append("- Sin cambios (ya estaban creados y son iguales): ").append(TextosInforme.lista(g.omitidos, 50)).append('\n');
            }
            if (!g.noReescritos.isEmpty()) {
                sb.append("- Se han dejado como estaban, aunque ahora se pedían con otro contenido: ")
                        .append(TextosInforme.lista(g.noReescritos, 50)).append('\n');
            }
            if (!g.reemplazados.isEmpty()) {
                sb.append("- Se han rehecho (sustituyen a la versión anterior): ").append(TextosInforme.lista(g.reemplazados, 50)).append('\n');
            }
            if (!g.eliminados.isEmpty()) {
                sb.append("- Eliminados porque el usuario lo confirmó: ").append(TextosInforme.lista(g.eliminados, 50)).append('\n');
            }
            sb.append('\n');
        }

        if (!g.cambiosReemplazados.isEmpty()) {
            sb.append("### Qué ha cambiado en los tests rehechos\n\n");
            sb.append("Para cada test rehecho: por qué se rehízo y qué cambió respecto a la versión anterior.\n\n");
            bloques(sb, g.cambiosReemplazados);
        }

        if (!g.remontados.isEmpty()) {
            sb.append("### Montaje actualizado\n\n");
            sb.append("En estos tests ha cambiado la forma de preparar la clase probada (la parte común del principio del "
                    + "archivo). Se ha reescrito solo esa parte; los tests que ya había se conservan y se vuelven a probar:\n\n");
            for (Map.Entry<String, List<String>> e : g.remontados.entrySet()) {
                sb.append("- ").append(cod(e.getKey())).append(" (").append(e.getValue().size())
                        .append(e.getValue().size() == 1 ? " test)" : " tests)").append('\n');
            }
            sb.append('\n');
        }

        if (!g.noTocados.isEmpty()) {
            sb.append("### Archivos que no se han modificado\n\n");
            sb.append("junit-gen no ha tocado estos archivos para no estropear cambios hechos a mano:\n\n");
            for (String n : g.noTocados) {
                sb.append("- ").append(noTocado(salida, n)).append('\n');
            }
            sb.append('\n');
        }

        if (!g.requiereRefactor.isEmpty()) {
            sb.append("### Funciones que no se pueden probar bien sin cambiar el código\n\n");
            sb.append("Estas funciones crean por dentro cosas que un test no puede sustituir (por ejemplo, objetos creados con «new» o "
                    + "llamadas estáticas). Para probarlas bien habría que reorganizar un poco ese código (lo que los programadores "
                    + "llaman «refactorizar»). junit-gen no lo hace: solo lo avisa.\n\n");
            List<String> fqnsRefactor = new ArrayList<>();
            for (Map<String, Object> r : g.requiereRefactor) {
                if (r.get("servicio") instanceof String s) {
                    fqnsRefactor.add(s);
                }
            }
            for (Map<String, Object> r : g.requiereRefactor) {
                String servicio = r.get("servicio") instanceof String s ? s : null;
                sb.append("- ").append(cod(TextosInforme.claseCorta(servicio, fqnsRefactor))).append(":\n");
                Object metodos = r.get("metodos");
                if (metodos instanceof Map<?, ?> mapa) {
                    for (Map.Entry<?, ?> e : mapa.entrySet()) {
                        sb.append("  - ").append(cod(String.valueOf(e.getKey()))).append(": ")
                                .append(TextosInforme.texto(valores(e.getValue()), MAX_TEXTO)).append('\n');
                    }
                } else {
                    sb.append("  - ").append(TextosInforme.texto(String.valueOf(metodos), MAX_TEXTO)).append('\n');
                }
            }
            sb.append('\n');
        }

        sb.append("### Archivos creados o ampliados\n\n");
        sb.append("Todos están en la carpeta de los tests:\n\n");
        for (Path a : archivos) {
            String nombre = a.toString();
            String que = nombre.endsWith(RutasSalida.SPEC) ? "ficha interna de junit-gen con la definición de cada test"
                    : nombre.endsWith(RutasSalida.INFORME) ? "este informe" : "tests";
            sb.append("- ").append(cod(TextosInforme.rutaRelativa(salida, nombre))).append(" — ").append(que).append('\n');
        }

        if (!g.destinosCopia.isEmpty()) {
            sb.append("\n### Dónde copiar cada test\n\n");
            sb.append("| Archivo (en la carpeta de los tests) | Dónde copiarlo (dentro del proyecto) |\n|---|---|\n");
            for (Map.Entry<String, String> e : g.destinosCopia.entrySet()) {
                sb.append("| `").append(celda(e.getKey())).append("` | `").append(celda(e.getValue())).append("` |\n");
            }
            sb.append("\nLas rutas de la izquierda parten de la carpeta de los tests y las de la derecha, de la carpeta del proyecto. "
                    + "Copiarlos es siempre un paso manual: junit-gen nunca lo hace por ti.\n");
        }
        return sb.toString();
    }

    private static String resumenGeneracion(ResultadoGeneracion g) {
        List<String> frases = new ArrayList<>();
        int nuevos = Math.max(0, g.generados.size() - g.reemplazados.size());
        Set<String> todos = new LinkedHashSet<>();
        for (Map<String, String> m : g.generados) {
            todos.add(m.get("servicio"));
        }
        Set<String> cortos = new LinkedHashSet<>();
        for (Map<String, String> m : g.generados) {
            if (!g.reemplazados.contains(m.get("caso"))) {
                cortos.add(TextosInforme.claseCorta(m.get("servicio"), todos));
            }
        }
        String servicios = TextosInforme.lista(cortos, 5);
        if (nuevos == 1) {
            frases.add("Se ha creado 1 test nuevo para " + servicios + ".");
        } else if (nuevos > 1) {
            frases.add("Se han creado " + nuevos + " tests nuevos para " + servicios + ".");
        }
        int rehechos = g.reemplazados.size();
        if (rehechos == 1) {
            frases.add("Se ha rehecho 1 test que ya existía (" + cod(g.reemplazados.get(0)) + "); el motivo y lo que ha cambiado están en "
                    + "«Qué ha cambiado en los tests rehechos».");
        } else if (rehechos > 1) {
            frases.add("Se han rehecho " + rehechos + " tests que ya existían; el motivo y lo que ha cambiado en cada uno están en "
                    + "«Qué ha cambiado en los tests rehechos».");
        }
        if (nuevos == 0 && rehechos == 0) {
            frases.add("No se ha creado ningún test nuevo.");
        }
        if (!g.descartados.isEmpty()) {
            frases.add(TextosInforme.cuantos(g.descartados.size(), "1 test no se ha creado porque ya existía uno igual.",
                    "%d tests no se han creado porque ya existía uno igual."));
        }
        if (!g.noReescritos.isEmpty()) {
            frases.add(TextosInforme.cuantos(g.noReescritos.size(), "1 test ya existía con otro contenido y se ha dejado como estaba.",
                    "%d tests ya existían con otro contenido y se han dejado como estaban."));
        }
        if (!g.eliminados.isEmpty()) {
            frases.add(TextosInforme.cuantos(g.eliminados.size(), "Se ha eliminado 1 test porque el usuario lo confirmó.",
                    "Se han eliminado %d tests porque el usuario lo confirmó."));
        }
        if (!g.generados.isEmpty()) {
            frases.add("Aquí solo se crean; si después se prueban, el resultado aparece en el siguiente apartado «Verificación».");
        }
        return String.join(" ", frases);
    }

    private static void bloques(StringBuilder sb, List<String> bloques) {
        for (String b : bloques) {
            sb.append(b);
            if (!b.endsWith("\n")) {
                sb.append('\n');
            }
            sb.append('\n');
        }
    }

    private static String noTocado(String salida, String n) {
        if (n == null) {
            return TextosInforme.SIN_DATO;
        }
        int i = n.indexOf(": ");
        if (i <= 0) {
            return TextosInforme.texto(n, MAX_TEXTO);
        }
        return cod(TextosInforme.rutaRelativa(salida, n.substring(0, i))) + ": " + TextosInforme.texto(n.substring(i + 2), MAX_TEXTO);
    }

    private static String valores(Object v) {
        if (v instanceof Collection<?> c) {
            List<String> partes = new ArrayList<>();
            for (Object o : c) {
                partes.add(String.valueOf(o));
            }
            return String.join("; ", partes);
        }
        return String.valueOf(v);
    }

    private static String ruta(String r) {
        String c = TextosInforme.codigo(r, 500);
        return c.isEmpty() ? TextosInforme.SIN_DATO : c;
    }

    private static String cod(String s) {
        String c = TextosInforme.codigo(s, 200);
        return c.isEmpty() ? TextosInforme.SIN_DATO : c;
    }

    public static String seccionTexto(String titulo, String cuerpo) {
        return "## " + titulo + " — " + fecha() + "\n\n" + cuerpo + (cuerpo.endsWith("\n") ? "" : "\n");
    }
}
