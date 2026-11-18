package com.junitgen.mcp;

import com.junitgen.generacion.Oleadas;
import com.junitgen.generacion.RutasSalida;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Markdown final exacto que la IA muestra al usuario tal cual ({@code respuestaFinal}): primera línea, tabla de
 * cifras de la sesión y enlace al informe. Lo construye junit-gen; la IA no añade, quita ni cambia nada.
 */
final class RespuestaFinal {

    static final int MAX_SERVICIOS = 15;
    /** Filas de la tabla por oleada de la respuesta global del modo «todos» (el resto va al informe). */
    static final int MAX_FILAS_OLEADAS = 40;

    /** Cifras de la sesión necesarias para la respuesta final. */
    record Cifras(int casosGenerados, int ejecutados, int pasan, int fallosAsercion, int erroresMontaje, int noCompilan,
                  int descartadosPorDuplicado) {
    }

    /** Servicio pendiente del plan: nombre simple y detalle («faltan 3 casos en 2 métodos»). */
    record Pendiente(String servicio, String detalle) {
    }

    /**
     * Preguntas de la sesión que quedaron sin responder: [Probar tests] [Descartar] ({@code ejecucion}; solo con
     * {@code verificacion: preguntar} en junit-gen.yml) y
     * número de casos con la pregunta de fallos de aserción sin responder ({@code aserciones}).
     */
    record SinResponder(boolean ejecucion, int aserciones) {
    }

    /** Casos apuntados durante la tanda y arreglados al final: arreglados, posibles bugs y apartados (incluye dudosos). */
    record Arreglos(int arreglados, int posiblesBugs, int apartados, int automaticos, int menosEstrictos) {

        /** Sin arreglos automáticos ni tests menos estrictos. */
        Arreglos(int arreglados, int posiblesBugs, int apartados) {
            this(arreglados, posiblesBugs, apartados, 0, 0);
        }
    }

    private RespuestaFinal() {
    }

    /** Como {@link #armar(Cifras, List, List, List, Path, SinResponder)} sin preguntas sin responder. */
    static String armar(Cifras c, List<String> serviciosSesion, List<String> completados, List<Pendiente> pendientes,
                        Path salida) {
        return armar(c, serviciosSesion, completados, pendientes, salida, new SinResponder(false, 0));
    }

    /**
     * @param serviciosSesion servicios (nombre simple) con ≥1 caso generado en la sesión, en orden
     * @param completados     servicios completados (null sin plan → los de la sesión)
     * @param pendientes      servicios pendientes del plan (null sin plan → ninguno)
     * @param salida          carpeta de salida real (null si no hay)
     * @param sr              preguntas sin responder (solo cambian la primera línea cuando hay casos generados)
     */
    static String armar(Cifras c, List<String> serviciosSesion, List<String> completados, List<Pendiente> pendientes,
                        Path salida, SinResponder sr) {
        return armar(c, serviciosSesion, completados, pendientes, List.of(), salida, sr);
    }

    /**
     * Como {@link #armar(Cifras, List, List, List, Path, SinResponder)} con los servicios cerrados por la salvaguarda
     * anti-bucle ({@code incompletos}: fila «Servicios incompletos (cerrados por atasco)» solo si no está vacía).
     */
    static String armar(Cifras c, List<String> serviciosSesion, List<String> completados, List<Pendiente> pendientes,
                        List<Pendiente> incompletos, Path salida, SinResponder sr) {
        return armar(c, serviciosSesion, completados, pendientes, incompletos, null, salida, sr);
    }

    /**
     * Como {@link #armar(Cifras, List, List, List, List, Path, SinResponder)}; con {@code arreglos} distinto de null
     * añade las filas «Arreglados al final», «Posibles bugs» y «Apartados (no se pudieron arreglar)».
     */
    static String armar(Cifras c, List<String> serviciosSesion, List<String> completados, List<Pendiente> pendientes,
                        List<Pendiente> incompletos, Arreglos arreglos, Path salida, SinResponder sr) {
        StringBuilder sb = new StringBuilder();
        if (c.casosGenerados() == 0) {
            sb.append("No se generaron tests nuevos.");
        } else {
            sb.append(c.ejecutados() > 0 ? "Se generaron y ejecutaron los tests de " : "Se generaron los tests de ")
                    .append(lista(codigos(serviciosSesion)));
            if (sr != null && sr.ejecucion() && c.ejecutados() == 0) {
                sb.append("; la ejecución quedó pendiente porque la pregunta [Probar tests] [Descartar] quedó sin responder. "
                        + "Pídeme que los ejecute cuando quieras.");
            } else if (sr != null && sr.aserciones() > 0 && c.fallosAsercion() > 0) {
                int n = sr.aserciones();
                sb.append("; queda pendiente tu decisión sobre ").append(n).append(n == 1 ? " fallo de aserción" : " fallos de aserción")
                        .append(" porque la pregunta quedó sin responder. Dime qué hacer con ellos o pídeme que te lo vuelva a preguntar.");
            } else {
                sb.append('.');
            }
        }
        sb.append("\n\n| Resultado | Cifra |\n|---|---|\n");
        fila(sb, "Casos generados", String.valueOf(c.casosGenerados()));
        fila(sb, "Ejecutados", String.valueOf(c.ejecutados()));
        fila(sb, "Pasan", String.valueOf(c.pasan()));
        fila(sb, "Fallos de aserción", String.valueOf(c.fallosAsercion()));
        fila(sb, "Errores de montaje", String.valueOf(c.erroresMontaje()));
        fila(sb, "No compilan", String.valueOf(c.noCompilan()));
        fila(sb, "Descartados por duplicado", String.valueOf(c.descartadosPorDuplicado()));
        if (arreglos != null) {
            fila(sb, "Arreglados al final", String.valueOf(arreglos.arreglados()));
            if (arreglos.automaticos() > 0) {
                fila(sb, "Arreglados automáticamente (sin IA)", String.valueOf(arreglos.automaticos()));
            }
            fila(sb, "Posibles bugs", String.valueOf(arreglos.posiblesBugs()));
            fila(sb, "Apartados (no se pudieron arreglar)", String.valueOf(arreglos.apartados()));
            if (arreglos.menosEstrictos() > 0) {
                fila(sb, "Tests menos estrictos (revisar en el informe)", String.valueOf(arreglos.menosEstrictos()));
            }
        }
        List<String> comp = codigos(completados == null ? serviciosSesion : completados);
        fila(sb, "Servicios completados", lista(comp));
        if (incompletos != null && !incompletos.isEmpty()) {
            fila(sb, "Servicios incompletos (cerrados por atasco)", lista(conDetalle(incompletos)));
        }
        fila(sb, "Servicios pendientes", lista(conDetalle(pendientes)));
        sb.append('\n');
        if (salida == null) {
            sb.append("No hay carpeta de salida en esta sesión.");
        } else {
            sb.append("Informe: [informe.md](").append(salida.resolve(RutasSalida.INFORME).toUri()).append("). Los tests están en `")
                    .append(salida).append("`; copiarlos a `src/test/java` del proyecto es un paso manual.");
        }
        return sb.toString();
    }

    /**
     * Respuesta final global del modo «todos»: primera línea con los totales, tabla de totales, tabla por oleada (máx.
     * {@link #MAX_FILAS_OLEADAS} filas) y el pie de {@link #armar} (enlace al informe y copia manual).
     */
    static String armarTodos(Oleadas c, Path salida) {
        int casos = 0;
        int pasan = 0;
        int fallos = 0;
        int montaje = 0;
        int sinVerificar = 0;
        int duplicados = 0;
        int arreglados = 0;
        int bugs = 0;
        int apartados = 0;
        synchronized (c) {
            for (Oleadas.ResumenOleada o : c.oleadas) {
                casos += o.casosGenerados;
                pasan += o.pasan;
                fallos += o.fallosAsercion;
                montaje += o.montajeFallido;
                sinVerificar += o.sinVerificar;
                duplicados += o.duplicados;
                arreglados += o.arreglados;
                bugs += o.posiblesBugs;
                apartados += o.apartados;
            }
            int hechos = 0;
            int omitidos = 0;
            int incompletos = 0;
            for (Oleadas.ServicioOleada s : c.servicios) {
                if (Oleadas.SERVICIO_HECHO.equals(s.estado)) {
                    hechos++;
                    if (Boolean.TRUE.equals(s.incompleto)) {
                        incompletos++;
                    }
                } else if (Oleadas.SERVICIO_OMITIDO.equals(s.estado)) {
                    omitidos++;
                }
            }
            StringBuilder sb = new StringBuilder();
            sb.append("Se generaron y ejecutaron los tests de ").append(hechos).append(hechos == 1 ? " servicio en " : " servicios en ")
                    .append(c.oleadas.size()).append(c.oleadas.size() == 1 ? " oleada." : " oleadas.");
            sb.append("\n\n| Resultado | Cifra |\n|---|---|\n");
            fila(sb, "Casos generados", String.valueOf(casos));
            fila(sb, "Pasan", String.valueOf(pasan));
            fila(sb, "Fallos de aserción", String.valueOf(fallos));
            fila(sb, "Apartados por error de montaje", String.valueOf(montaje));
            fila(sb, "Sin verificar", String.valueOf(sinVerificar));
            fila(sb, "Descartados por duplicado", String.valueOf(duplicados));
            fila(sb, "Arreglados al final", String.valueOf(arreglados));
            fila(sb, "Posibles bugs", String.valueOf(bugs));
            fila(sb, "Apartados (no se pudieron arreglar)", String.valueOf(apartados));
            fila(sb, "Servicios hechos", String.valueOf(hechos));
            fila(sb, "Servicios omitidos", String.valueOf(omitidos));
            fila(sb, "Servicios incompletos", String.valueOf(incompletos));
            sb.append("\n| Oleada | Servicios | Casos | Pasan | No pasan | Tiempo |\n|---|---|---|---|---|---|\n");
            int filas = 0;
            for (Oleadas.ResumenOleada o : c.oleadas) {
                if (filas++ >= MAX_FILAS_OLEADAS) {
                    break;
                }
                sb.append("| ").append(o.numero).append(" | ").append(o.servicios).append(" | ").append(o.casosGenerados).append(" | ")
                        .append(o.pasan).append(" | ").append(o.fallosAsercion + o.montajeFallido).append(" | ")
                        .append(OleadasTodos.tiempo(o.ms)).append(" |\n");
            }
            if (c.oleadas.size() > MAX_FILAS_OLEADAS) {
                sb.append("\n… y ").append(c.oleadas.size() - MAX_FILAS_OLEADAS).append(" más (informe.md).\n");
            }
            sb.append('\n');
            if (salida == null) {
                sb.append("No hay carpeta de salida en esta sesión.");
            } else {
                sb.append("Informe: [informe.md](").append(salida.resolve(RutasSalida.INFORME).toUri()).append("). Los tests están en `")
                        .append(salida).append("`; copiarlos a `src/test/java` del proyecto es un paso manual.");
            }
            return sb.toString();
        }
    }

    /** «`Servicio` (detalle)» de cada pendiente; null → vacío. */
    private static List<String> conDetalle(List<Pendiente> pendientes) {
        List<String> r = new ArrayList<>();
        if (pendientes != null) {
            for (Pendiente p : pendientes) {
                r.add("`" + p.servicio() + "`" + (p.detalle() == null || p.detalle().isBlank() ? "" : " (" + p.detalle() + ")"));
            }
        }
        return r;
    }

    private static void fila(StringBuilder sb, String nombre, String valor) {
        sb.append("| ").append(nombre).append(" | ").append(valor).append(" |\n");
    }

    private static List<String> codigos(List<String> nombres) {
        List<String> r = new ArrayList<>();
        if (nombres != null) {
            for (String n : nombres) {
                if (n != null && !n.isBlank()) {
                    r.add("`" + n + "`");
                }
            }
        }
        return r;
    }

    /** Elementos separados por «, » (máx. 15, luego «+N más»); vacío → «Ninguno». */
    private static String lista(List<String> l) {
        if (l == null || l.isEmpty()) {
            return "Ninguno";
        }
        if (l.size() <= MAX_SERVICIOS) {
            return String.join(", ", l);
        }
        return String.join(", ", l.subList(0, MAX_SERVICIOS)) + ", +" + (l.size() - MAX_SERVICIOS) + " más";
    }
}
