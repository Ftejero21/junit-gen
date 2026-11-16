package com.junitgen.verificacion;

import com.junitgen.generacion.AlmacenSpec;
import com.junitgen.generacion.EscritorInforme;
import com.junitgen.generacion.TextosInforme;
import com.junitgen.spec.Spec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Texto en lenguaje llano de las secciones de informe.md sobre verificación, revisión de la IA y decisiones.
 * {@link VerificadorTests} solo reúne los datos; las instrucciones para la IA nunca se escriben aquí.
 */
final class InformeVerificacion {

    private static final int MAX_TEXTO = 1000;
    private static final String PREFIJO_CORRIGE = "Se corrige el test y se vuelve a probar";

    /** Datos de una verificación para escribir su sección. */
    record DatosVerificacion(ResultadoVerificacion rv, Spec spec, List<String> montaje, List<String> fallidos, List<String> revisionIa,
                             List<String> ajustarAutorizados, List<String> aserciones, int maxIntento, List<String> corregidos,
                             boolean corregidosPorIa, String tecnico, String archivosActualizados, boolean revisionAutomatica) {
    }

    /** Fila de la tabla «Revisión de la IA». El motivo llega ya saneado. */
    record FilaRevision(String caso, String veredicto, String motivo, String queSeHace) {
    }

    private InformeVerificacion() {
    }

    // ------------------------------------------------------------- verificación

    static String cuerpoVerificacion(DatosVerificacion d) {
        ResultadoVerificacion rv = d.rv();
        List<ResultadoTest> resultados = rv.resultados;
        StringBuilder sb = new StringBuilder(TextosInforme.RESUMEN).append(resumenVerificacion(d)).append("\n\n");

        sb.append("### Resultado de cada test\n\n");
        if (resultados.isEmpty()) {
            sb.append("No hay tests que probar.\n\n");
        } else {
            Set<String> clases = new LinkedHashSet<>();
            Map<String, List<ResultadoTest>> porClase = new LinkedHashMap<>();
            for (ResultadoTest t : resultados) {
                if (t.clase != null) {
                    clases.add(t.clase);
                }
                porClase.computeIfAbsent(Objects.toString(t.clase, ""), k -> new ArrayList<>()).add(t);
            }
            for (Map.Entry<String, List<ResultadoTest>> e : porClase.entrySet()) {
                sb.append("Clase de test: ").append(cod(TextosInforme.claseCorta(e.getKey(), clases))).append("\n\n");
                sb.append("| Test | Qué comprueba | Resultado |\n|---|---|---|\n");
                for (ResultadoTest t : e.getValue()) {
                    sb.append("| ").append(EscritorInforme.celda(cod(t.metodoTest))).append(" | ")
                            .append(EscritorInforme.celda(TextosInforme.texto(queComprueba(d.spec(), t.caso), 200))).append(" | ")
                            .append(TextosInforme.estado(t.estado)).append(" |\n");
                }
                sb.append('\n');
            }
        }

        List<ResultadoTest> noPasan = new ArrayList<>();
        for (ResultadoTest t : resultados) {
            if (!ClasificadorFallos.PASA.equals(t.estado)) {
                noPasan.add(t);
            }
        }
        if (!noPasan.isEmpty()) {
            sb.append("### Tests que no han pasado\n\n");
            for (ResultadoTest t : noPasan) {
                String nombre = t.metodoTest != null && !t.metodoTest.isBlank() ? t.metodoTest : t.caso;
                sb.append("- **").append(cod(nombre)).append("** — ").append(TextosInforme.estadoLargo(t.estado)).append('\n');
                sb.append("  - Identificador: ").append(cod(t.caso)).append('\n');
                String estadoSpec = estadoSpec(d.spec(), t.caso);
                if (VerificadorTests.ESTADO_POSIBLE_BUG.equals(estadoSpec)) {
                    sb.append("  - Situación: ya se revisó y se mantiene como aviso de un posible fallo en el código.\n");
                } else if (VerificadorTests.ESTADO_DUDOSO.equals(estadoSpec)) {
                    sb.append("  - Situación: ya se revisó y no quedó claro si falla el test o el código; se mantiene tal cual.\n");
                }
                String mensaje = TextosInforme.codigo(t.mensaje, 300);
                if (!mensaje.isEmpty()) {
                    sb.append("  - Detalle técnico: ").append(mensaje).append('\n');
                }
            }
            sb.append('\n');
        }

        sb.append("### Qué pasa ahora\n\n");
        Map<String, String> metodos = new LinkedHashMap<>();
        for (ResultadoTest t : resultados) {
            if (t.caso != null && t.metodoTest != null && !t.metodoTest.isBlank()) {
                metodos.putIfAbsent(t.caso, t.metodoTest);
            }
        }
        List<String> vinetas = new ArrayList<>();
        if (!d.montaje().isEmpty() && d.maxIntento() == 0) {
            vinetas.add("Se han apuntado para arreglarlos al final, cuando estén creados todos los tests: " + nombres(d.montaje(), metodos)
                    + ".");
        } else if (!d.montaje().isEmpty()) {
            vinetas.add("Se van a corregir y volver a probar (intento " + d.maxIntento() + " de " + VerificadorTests.MAX_INTENTOS
                    + ") los tests que no han podido ejecutarse: " + nombres(d.montaje(), metodos) + ".");
        }
        if (!d.fallidos().isEmpty()) {
            vinetas.add("No se han podido arreglar tras " + VerificadorTests.MAX_INTENTOS + " intentos y se han apartado: quedan "
                    + "comentados dentro de su archivo de test (no se borran) para que una persona los revise: "
                    + nombres(d.fallidos(), metodos) + ".");
        }
        if (!d.revisionIa().isEmpty()) {
            vinetas.add("La IA ya los corrigió una vez y siguen fallando; los volverá a revisar: " + nombres(d.revisionIa(), metodos) + ".");
        }
        if (!d.ajustarAutorizados().isEmpty()) {
            vinetas.add("El usuario ya decidió cambiar el resultado que esperan; falta aplicar el cambio y volver a probarlos: "
                    + nombres(d.ajustarAutorizados(), metodos) + ".");
        }
        if (!d.aserciones().isEmpty() && d.revisionAutomatica()) {
            vinetas.add("Fallan porque el resultado no es el esperado: " + nombres(d.aserciones(), metodos) + ". La IA los va a revisar "
                    + "con el código del servicio: si el test estaba mal planteado lo corregirá y lo volverá a probar; si ve un posible "
                    + "fallo del código, lo dejará marcado como aviso.");
        } else if (!d.aserciones().isEmpty()) {
            vinetas.add("Fallan porque el resultado no es el esperado: " + nombres(d.aserciones(), metodos) + ". Puede ser un fallo real "
                    + "del código o un test mal planteado. Hay que decidir qué hacer con cada uno: mantenerlo como aviso de un posible "
                    + "fallo, cambiar lo que espera el test, eliminarlo o pedir a la IA que lo revise.");
        }
        int desconocidos = (int) rv.contar(ClasificadorFallos.DESCONOCIDO);
        if (desconocidos > 0) {
            vinetas.add(TextosInforme.cuantos(desconocidos,
                    "De 1 test no se ha podido saber el resultado: mira los avisos del «Detalle técnico».",
                    "De %d tests no se ha podido saber el resultado: mira los avisos del «Detalle técnico»."));
        }
        if (vinetas.isEmpty() && rv.todoPasa()) {
            vinetas.add("Todos los tests funcionan. Cuando los hayas revisado, cópialos a mano al proyecto (tabla «Dónde copiar cada test»).");
        }
        if (vinetas.isEmpty()) {
            vinetas.add("Nada nuevo: los tests que fallan ya estaban revisados y se mantienen como están.");
        }
        for (String v : vinetas) {
            sb.append("- ").append(v).append('\n');
        }
        sb.append('\n');

        sb.append("### ¿Se ha modificado el proyecto?\n\n");
        if (rv.alertaIntegridad == null || rv.alertaIntegridad.isEmpty()) {
            sb.append("No. El proyecto está igual que antes de probar los tests.\n\n");
        } else {
            sb.append("**Alerta de integridad: sí.** Mientras se probaban los tests han cambiado archivos del proyecto (puede deberse al "
                    + "código que ejecutan los tests o a otro programa que estuviera trabajando a la vez):\n\n");
            for (String a : rv.alertaIntegridad) {
                sb.append("- ").append(plano(a)).append('\n');
            }
            sb.append('\n');
        }

        sb.append("### Detalle técnico\n\n");
        if (d.tecnico() != null && !d.tecnico().isBlank()) {
            sb.append(d.tecnico());
            if (!d.tecnico().endsWith("\n")) {
                sb.append('\n');
            }
        }
        for (String a : rv.avisos) {
            sb.append("- Aviso: ").append(plano(a)).append('\n');
        }
        sb.append("- Archivos actualizados: ").append(d.archivosActualizados()).append('\n');
        return sb.toString();
    }

    private static String resumenVerificacion(DatosVerificacion d) {
        ResultadoVerificacion rv = d.rv();
        int total = rv.resultados.size();
        if (total == 0) {
            return "No se ha probado ningún test.";
        }
        List<String> frases = new ArrayList<>();
        String base = total == 1 ? "Se ha probado 1 test" : "Se han probado " + total + " tests";
        boolean todoPasa = rv.todoPasa();
        if (todoPasa) {
            frases.add(base + (total == 1 ? " y funciona." : " y funcionan todos."));
        } else {
            List<String> partes = new ArrayList<>();
            parte(partes, (int) rv.contar(ClasificadorFallos.PASA), "1 funciona", "%d funcionan");
            parte(partes, (int) rv.contar(ClasificadorFallos.FALLO_ASERCION), "1 falla porque el resultado no es el esperado",
                    "%d fallan porque el resultado no es el esperado");
            parte(partes, (int) rv.contar(ClasificadorFallos.ERROR_MONTAJE), "1 estaba mal preparado", "%d estaban mal preparados");
            parte(partes, (int) rv.contar(ClasificadorFallos.NO_COMPILA), "1 no compila", "%d no compilan");
            parte(partes, (int) rv.contar(ClasificadorFallos.DESCONOCIDO), "de 1 no se ha podido saber el resultado",
                    "de %d no se ha podido saber el resultado");
            frases.add(partes.isEmpty() ? base + "." : base + ": " + TextosInforme.unir(partes) + ".");
        }
        int k = d.corregidos().size();
        if (k > 0) {
            String m1 = d.corregidosPorIa() ? "la IA vio que el test estaba mal planteado, lo corrigió y ahora pasa."
                    : "estaba mal preparado, se corrigió y ahora pasa.";
            String mN = d.corregidosPorIa() ? "la IA vio que estaban mal planteados, los corrigió y ahora pasan."
                    : "estaban mal preparados, se corrigieron y ahora pasan.";
            if (k == total && k == 1) {
                frases.add("Es un test que antes fallaba: " + m1);
            } else if (k == total) {
                frases.add("Son tests que antes fallaban: " + mN);
            } else if (k == 1) {
                frases.add("Uno de ellos antes fallaba: " + m1);
            } else {
                frases.add(k + " de ellos antes fallaban: " + mN);
            }
        }
        if (!d.aserciones().isEmpty() && d.revisionAutomatica()) {
            frases.add(TextosInforme.cuantos(d.aserciones().size(), "La IA va a revisar el test que falla.",
                    "La IA va a revisar los %d tests que fallan."));
        } else if (!d.aserciones().isEmpty()) {
            frases.add(TextosInforme.cuantos(d.aserciones().size(), "Queda por decidir qué hacer con el test que falla.",
                    "Queda por decidir qué hacer con los %d tests que fallan."));
        }
        if (!d.montaje().isEmpty() && d.maxIntento() == 0) {
            frases.add(TextosInforme.cuantos(d.montaje().size(),
                    "El test que no ha podido ejecutarse se arreglará al final.",
                    "Los %d tests que no han podido ejecutarse se arreglarán al final."));
        } else if (!d.montaje().isEmpty()) {
            frases.add(TextosInforme.cuantos(d.montaje().size(),
                    "El test que no ha podido ejecutarse se va a corregir y se volverá a probar.",
                    "Los %d tests que no han podido ejecutarse se van a corregir y se volverán a probar."));
        }
        if (todoPasa) {
            frases.add(total == 1 ? "De esta prueba no queda nada pendiente: solo falta revisarlo y copiarlo a mano al proyecto."
                    : "De esta prueba no queda nada pendiente: solo falta revisarlos y copiarlos a mano al proyecto.");
        }
        if (rv.alertaIntegridad != null && !rv.alertaIntegridad.isEmpty()) {
            frases.add("Atención: mientras se probaban han cambiado archivos del proyecto (ver «¿Se ha modificado el proyecto?»).");
        }
        return String.join(" ", frases);
    }

    private static void parte(List<String> partes, int n, String uno, String varios) {
        if (n > 0) {
            partes.add(TextosInforme.cuantos(n, uno, varios));
        }
    }

    // ------------------------------------------------------------- revisión de la IA

    static String queSeHaceBug() {
        return "El test se mantiene tal cual, como aviso de un posible fallo en el código. Conviene que alguien del equipo lo revise. "
                + "El proyecto no se ha tocado.";
    }

    static String queSeHaceDudoso() {
        return "El test se mantiene tal cual. Conviene que una persona lo revise.";
    }

    static String queSeHaceCorregir(int intento) {
        return PREFIJO_CORRIGE + " (intento " + intento + " de " + VerificadorTests.MAX_INTENTOS + ").";
    }

    static String queSeHaceLimite() {
        return "Ya se ha intentado corregir " + VerificadorTests.MAX_INTENTOS + " veces sin éxito: el test se mantiene tal cual y conviene "
                + "que una persona lo revise.";
    }

    static String cuerpoRevisionIa(List<FilaRevision> filas, String archivosActualizados) {
        int corrige = 0;
        int bug = 0;
        int dudoso = 0;
        for (FilaRevision f : filas) {
            if (VerificadorTests.VEREDICTO_BUG.equals(f.veredicto())) {
                bug++;
            } else if (VerificadorTests.VEREDICTO_ERROR_IA.equals(f.veredicto()) && f.queSeHace() != null
                    && f.queSeHace().startsWith(PREFIJO_CORRIGE)) {
                corrige++;
            } else {
                dudoso++;
            }
        }
        List<String> frases = new ArrayList<>();
        frases.add(TextosInforme.cuantos(filas.size(), "La IA ha revisado 1 test que fallaba.",
                "La IA ha revisado %d tests que fallaban."));
        if (corrige > 0) {
            frases.add(enCasos(corrige) + " la que se equivocó fue la propia IA al preparar el test: se corrige y se vuelve a probar.");
        }
        if (bug > 0) {
            frases.add(enCasos(bug) + " ve un posible fallo en el código del servicio: el test se mantiene como aviso.");
        }
        if (dudoso > 0) {
            frases.add(enCasos(dudoso) + " no está claro quién falla: el test se mantiene para que lo revise una persona.");
        }
        StringBuilder sb = new StringBuilder(TextosInforme.RESUMEN).append(String.join(" ", frases)).append("\n\n");
        sb.append("| Test | Conclusión de la IA | Por qué | Qué se hace ahora |\n|---|---|---|---|\n");
        for (FilaRevision f : filas) {
            sb.append("| ").append(EscritorInforme.celda(cod(f.caso()))).append(" | ")
                    .append(EscritorInforme.celda(TextosInforme.veredicto(f.veredicto()))).append(" | ")
                    .append(f.motivo()).append(" | ").append(EscritorInforme.celda(f.queSeHace())).append(" |\n");
        }
        sb.append("\nArchivos actualizados: ").append(archivosActualizados).append('\n');
        return sb.toString();
    }

    private static String enCasos(int n) {
        return n == 1 ? "En 1 caso" : "En " + n + " casos";
    }

    // ------------------------------------------------------------- arreglos al final

    private static final int MAX_ERROR_ARREGLO = 300;
    private static final int MAX_MOTIVO_ARREGLO = 300;
    private static final int MAX_CAMBIO_ARREGLO = 400;

    /** Cuerpo de la sección «Arreglos al final»: resumen coloquial y, por servicio, qué fallaba y qué se cambió en cada caso. */
    static String cuerpoArreglos(List<VerificadorTests.ArregloInforme> filas) {
        return cuerpoArreglos(filas, List.of());
    }

    /**
     * Como {@link #cuerpoArreglos(List)}, distinguiendo los apartados por error común y con la lista de imports
     * corregidos automáticamente al final (también sin filas).
     */
    static String cuerpoArreglos(List<VerificadorTests.ArregloInforme> filas, List<String> importsReparados) {
        List<VerificadorTests.ArregloInforme> lista = filas == null ? List.of() : filas;
        List<String> imports = importsReparados == null ? List.of() : importsReparados;
        int arreglados = 0;
        int bugs = 0;
        int apartados = 0;
        int apartadosComun = 0;
        int dudosos = 0;
        int automaticos = 0;
        int menosEstrictos = 0;
        for (VerificadorTests.ArregloInforme f : lista) {
            String e = f.estado() == null ? "" : f.estado();
            if ("arreglado".equals(e) && f.automatico()) {
                automaticos++;
            }
            if (f.menosEstricto()) {
                menosEstrictos++;
            }
            switch (e) {
                case "arreglado" -> arreglados++;
                case "posible_bug" -> bugs++;
                case "apartado" -> {
                    if (f.errorComun()) {
                        apartadosComun++;
                    } else {
                        apartados++;
                    }
                }
                case "dudoso" -> dudosos++;
                default -> {
                }
            }
        }
        StringBuilder sb = new StringBuilder(TextosInforme.RESUMEN);
        if (lista.isEmpty()) {
            sb.append("no ha hecho falta repasar ningún test; junit-gen ha corregido solo ")
                    .append(TextosInforme.cuantos(imports.size(), "un import", "%d imports")).append(".\n\n");
        } else {
            List<String> partes = new ArrayList<>();
            parte(partes, arreglados, "1 se ha arreglado y ya pasa", "%d se han arreglado y ya pasan");
            parte(partes, bugs, "1 queda como aviso de un posible fallo en el código", "%d quedan como aviso de un posible fallo en el código");
            parte(partes, apartados, "1 se ha apartado porque no se pudo arreglar", "%d se han apartado porque no se pudieron arreglar");
            parte(partes, apartadosComun, "1 se ha apartado porque su clase de test no compila por un error común",
                    "%d se han apartado porque su clase de test no compila por un error común");
            parte(partes, dudosos, "1 no está claro y queda para que lo revise una persona",
                    "%d no están claros y quedan para que los revise una persona");
            sb.append(TextosInforme.cuantos(lista.size(), "al terminar de crear todos los tests se ha repasado el que fallaba",
                            "al terminar de crear todos los tests se han repasado los %d que fallaban"))
                    .append(partes.isEmpty() ? "." : ": " + TextosInforme.unir(partes) + ".");
            if (automaticos > 0) {
                sb.append(" De los arreglados, ").append(TextosInforme.cuantos(automaticos, "1 lo corrigió", "%d los corrigió"))
                        .append(" junit-gen automáticamente, sin IA.");
            }
            if (menosEstrictos > 0) {
                sb.append(' ').append(TextosInforme.cuantos(menosEstrictos, "1 ha quedado menos estricto que el test original",
                        "%d han quedado menos estrictos que el test original")).append(": conviene revisarlos.");
            }
            sb.append("\n\n");
        }
        Map<String, List<VerificadorTests.ArregloInforme>> porServicio = new LinkedHashMap<>();
        for (VerificadorTests.ArregloInforme f : lista) {
            porServicio.computeIfAbsent(Objects.toString(f.servicio(), ""), k -> new ArrayList<>()).add(f);
        }
        for (Map.Entry<String, List<VerificadorTests.ArregloInforme>> e : porServicio.entrySet()) {
            sb.append("### ").append(cod(com.junitgen.util.Textos.nombreSimple(e.getKey()))).append("\n\n");
            if (e.getValue().stream().anyMatch(f -> f.errorComun() && "apartado".equals(f.estado()))) {
                sb.append("La clase de test de este servicio no compila por un error en su parte común (imports, campos o preparación), "
                        + "fuera de los casos: sus tests pendientes se han apartado sin tocar el archivo y junit-gen ha seguido con el resto.")
                        .append("\n\n");
            }
            for (VerificadorTests.ArregloInforme f : e.getValue()) {
                String nombre = f.metodoTest() != null && !f.metodoTest().isBlank() ? f.metodoTest() : f.caso();
                sb.append("- **").append(cod(nombre)).append("** (identificador ").append(cod(f.caso())).append(")\n");
                String error = TextosInforme.codigo(f.error(), MAX_ERROR_ARREGLO);
                sb.append("  - Qué fallaba: ").append(tipoArreglo(f.tipo())).append(". Detalle técnico: ")
                        .append(error.isEmpty() ? TextosInforme.SIN_DATO : error).append('\n');
                List<String> cambios = f.cambios() == null ? List.of() : f.cambios();
                if (cambios.isEmpty()) {
                    sb.append("  - Qué se cambió: ").append("posible_bug".equals(f.estado())
                            ? "nada: el test se mantiene tal cual porque el fallo parece estar en el código"
                            : "nada: no llegó a aplicarse ninguna corrección").append('\n');
                } else {
                    sb.append("  - Qué se cambió:\n");
                    for (String c : cambios) {
                        sb.append("    - ").append(plano(TextosInforme.texto(c, MAX_CAMBIO_ARREGLO))).append('\n');
                    }
                }
                boolean automatico = f.automatico() && "arreglado".equals(f.estado());
                sb.append("  - Intentos: ").append(automatico ? "0 (corrección automática)" : String.valueOf(f.intentos())).append('\n');
                List<String> avisos = f.avisosCalidad() == null ? List.of() : f.avisosCalidad();
                if (f.menosEstricto()) {
                    sb.append("  - Revisar: test menos estricto que el original — ").append(avisosArreglo(avisos)).append('\n');
                } else if (!avisos.isEmpty()) {
                    sb.append("  - Nota de calidad: ").append(avisosArreglo(avisos)).append('\n');
                }
                sb.append("  - Resultado: ").append(automatico
                        ? TextosInforme.PASA + " (arreglado automáticamente por junit-gen, sin IA)" : estadoArreglo(f.estado()));
                if (f.motivo() != null && !f.motivo().isBlank()) {
                    sb.append(". Motivo: ").append(plano(TextosInforme.texto(f.motivo(), MAX_MOTIVO_ARREGLO)));
                }
                sb.append('\n');
            }
            sb.append('\n');
        }
        if (!imports.isEmpty()) {
            sb.append("### Imports corregidos automáticamente\n\n")
                    .append("junit-gen ha corregido estos imports por su cuenta, sin pedírselo a la IA (el compilador no los encontraba):\n\n");
            for (String i : imports) {
                sb.append("- ").append(plano(i)).append('\n');
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /** Avisos de calidad de un caso unidos por «; ». */
    private static String avisosArreglo(List<String> avisos) {
        List<String> r = new ArrayList<>();
        for (String a : avisos) {
            if (a != null && !a.isBlank()) {
                r.add(plano(TextosInforme.texto(a, MAX_CAMBIO_ARREGLO)));
            }
        }
        return r.isEmpty() ? TextosInforme.SIN_DATO : String.join("; ", r);
    }

    private static String tipoArreglo(String tipo) {
        if (tipo == null) {
            return TextosInforme.SIN_DATO;
        }
        return switch (tipo) {
            case "no_compila" -> "el test no compilaba (su código tenía un error)";
            case "error_montaje" -> "el test estaba mal preparado (fallaba antes de comprobar nada)";
            case "fallo_asercion" -> "el resultado no era el que esperaba el test";
            default -> TextosInforme.texto(tipo, 80);
        };
    }

    private static String estadoArreglo(String estado) {
        if (estado == null) {
            return TextosInforme.SIN_DATO;
        }
        return switch (estado) {
            case "arreglado" -> TextosInforme.PASA + " (arreglado)";
            case "posible_bug" -> TextosInforme.estado("posible_bug");
            case "apartado" -> TextosInforme.estado("montaje_fallido") + " (no se ha borrado)";
            case "dudoso" -> TextosInforme.estado("dudoso") + "; se mantiene tal cual para que lo revise una persona";
            case "pendiente" -> "Sin terminar: la tanda se cerró antes de arreglarlo";
            default -> TextosInforme.texto(estado, 80);
        };
    }

    // ------------------------------------------------------------- decisión

    static String textoDecision(String decision, String caso, String archivo) {
        String c = cod(caso);
        return switch (decision) {
            case "bug" -> "El usuario ha decidido que el fallo del test " + c + " es un fallo real del código. El test se mantiene tal cual, "
                    + "como aviso de un posible fallo. El proyecto no se ha tocado.";
            case "ajustar" -> "El usuario ha decidido que el test " + c + " estaba mal planteado: se va a cambiar el resultado que espera y "
                    + "se volverá a probar.";
            case "eliminar" -> "El usuario ha confirmado que se elimine el test " + c
                    + (archivo != null && !archivo.isBlank() ? " (estaba en " + cod(archivo) + ")" : "") + ".";
            default -> "Decisión sobre el test " + c + ": " + TextosInforme.texto(decision, 80) + ".";
        };
    }

    // ------------------------------------------------------------- utilidades

    private static String queComprueba(Spec spec, String caso) {
        if (spec == null || caso == null) {
            return null;
        }
        AlmacenSpec.Ubicacion u = AlmacenSpec.buscar(spec, caso);
        return u == null || u.caso() == null ? null : u.caso().queComprueba;
    }

    private static String estadoSpec(Spec spec, String caso) {
        if (spec == null || caso == null) {
            return null;
        }
        AlmacenSpec.Ubicacion u = AlmacenSpec.buscar(spec, caso);
        return u == null || u.caso() == null ? null : u.caso().estado;
    }

    private static String nombres(List<String> ids, Map<String, String> metodos) {
        List<String> r = new ArrayList<>();
        for (String id : ids) {
            r.add(metodos.getOrDefault(id, id));
        }
        return TextosInforme.lista(r, 10);
    }

    /** Texto de una línea con «<» escapado para que el visor markdown no lo trate como HTML. */
    private static String plano(String s) {
        return TextosInforme.texto(s, MAX_TEXTO).replace("<", "&lt;");
    }

    private static String cod(String s) {
        String c = TextosInforme.codigo(s, 200);
        return c.isEmpty() ? TextosInforme.SIN_DATO : c;
    }
}
