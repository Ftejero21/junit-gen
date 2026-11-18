package com.junitgen.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.junitgen.config.CargadorConfig;
import com.junitgen.config.ConfigProyecto;
import com.junitgen.util.LimitesRespuesta;
import com.junitgen.util.Textos;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Guía de la tanda: único punto que formatea la cabecera «no pares» ({@code accionRequerida: NO HAS TERMINADO (servicio k
 * de N): llama ahora a …}), la posición en el plan y el paso concreto del servicio en curso. Solo formatea: los pendientes
 * los calcula {@link HerramientaEjecutar#pasoDetallado} y el estado vive en {@link SesionMcp}.
 */
final class GuiaTanda {

    /** Tamaño máximo (bytes UTF-8) de la línea de acción. */
    static final int MAX_ACCION = 480;
    /** Máximo de métodos listados en los casos por método requeridos. */
    static final int MAX_REQUERIDOS = 10;
    /** Máximo de métodos listados cuando la línea de acción no cabe con {@link #MAX_REQUERIDOS}. */
    static final int REQUERIDOS_CORTO = 3;
    static final String PREFIJO = "accionRequerida: NO HAS TERMINADO (";
    static final String SUFIJO = " No escribas ningún mensaje al usuario hasta recibir respuestaFinal.";

    private static final String SELECCIONAR = "seleccionar_funciones";
    private static final String DETALLE = "detalle_servicio";
    private static final String VALIDAR = "validar_spec";
    static final String GENERAR = "generar_tests";
    private static final String EJECUTAR = "ejecutar_tests";
    private static final String CAMBIAR_RUTA = "cambiar_ruta_salida";
    private static final String CIERRE = "cierre de la tanda";
    private static final String MAS = "…";
    /** Marca de {@code paginacion.siguiente} no nulo en el cuerpo JSON (dentro de cadenas las comillas van escapadas). */
    static final String MARCA_SIGUIENTE = "\"siguiente\":{";
    /** Marca de un paquete de trabajo en el cuerpo JSON (tomar_bloque o siguienteServicio de un bloque). */
    static final String MARCA_PAQUETE = "\"paquete\":{";
    /** Marca del nodo de bloque terminado en el cuerpo JSON. */
    static final String MARCA_BLOQUE_TERMINADO = "\"bloqueTerminado\":true";
    /** Grupo real de la primera línea de la carga de arreglo: «casos 1–6 de 6» o «caso 1 de 6». */
    private static final Pattern FASE_ARREGLO_EN_CUERPO = Pattern.compile("Fase de arreglo: (casos? \\d+(?:\u2013\\d+)? de \\d+)");
    private static final Pattern BLOQUE_EN_CUERPO =Pattern.compile("\"bloque\":(\\d+)");

    private GuiaTanda() {
    }

    /**
     * true si la cabecera se aplica: plan activo, carpeta de salida conocida, {@code verificacion} ≠ preguntar y
     * {@code fallosAsercion: revisarIa}. Cualquier fallo → false.
     */
    static boolean activa(SesionMcp sesion, Path proyecto) {
        try {
            return proyecto != null
                    && sesion.progresoPlan(proyecto) != null
                    && sesion.salidaDe(proyecto) != null
                    && CargadorConfig.modoVerificacion(proyecto) != ConfigProyecto.ModoVerificacion.PREGUNTAR
                    && HerramientaEjecutar.revisionAutomatica(proyecto);
        } catch (RuntimeException e) {
            System.err.println("junit-gen: no se pudo comprobar la guía de la tanda: " + e.getClass().getSimpleName() + ": "
                    + e.getMessage());
            return false;
        }
    }

    /**
     * true si la guía está activa ({@link #activa}) y los casos que fallan se arreglan al final de la tanda
     * ({@code arreglos: alFinal}, por defecto). Cualquier fallo → false.
     */
    static boolean arreglosAlFinal(SesionMcp sesion, Path proyecto) {
        try {
            return activa(sesion, proyecto)
                    && CargadorConfig.modoArreglos(proyecto) == ConfigProyecto.ModoArreglos.AL_FINAL;
        } catch (RuntimeException e) {
            System.err.println("junit-gen: no se pudo comprobar el modo de arreglos: " + e.getClass().getSimpleName() + ": "
                    + e.getMessage());
            return false;
        }
    }

    /** Bytes que hay que reservar en la cabecera para la línea de acción (con su salto de línea), o 0 si no aplica. */
    static int reserva(SesionMcp sesion) {
        return activa(sesion, sesion.fachada().ultimaRuta()) ? MAX_ACCION + 1 : 0;
    }

    /** «bloque b, » en una llamada con bloque; vacío en el resto. */
    static String prefijoBloque(SesionMcp sesion) {
        Integer b = sesion.bloqueLlamada();
        return b == null ? "" : "bloque " + b + ", ";
    }

    /**
     * «servicio k de N» (servicio en curso dentro del plan) o «cierre de la tanda» si no quedan casos; en una llamada con
     * bloque, con el prefijo «bloque b, » y k de N dentro del bloque.
     */
    static String posicion(SesionMcp sesion, Path proyecto) {
        return prefijoBloque(sesion) + posicionBase(sesion, proyecto);
    }

    private static String posicionBase(SesionMcp sesion, Path proyecto) {
        SesionMcp.ProgresoPlan pp = sesion.progresoPlan(proyecto);
        String actual = HerramientaGenerar.siguienteServicio(sesion, proyecto);
        if (pp == null || pp.restantes() == 0 || actual == null) {
            return CIERRE;
        }
        Set<String> fqns = new LinkedHashSet<>();
        for (SesionMcp.EstadoMetodo m : sesion.estadoPlan(proyecto)) {
            fqns.add(m.fqn());
        }
        int k = new ArrayList<>(fqns).indexOf(actual) + 1;
        return "servicio " + k + " de " + fqns.size();
    }

    /**
     * Como {@link #posicion(SesionMcp, Path)} pero con la posición de {@code fqn} (p. ej. el servicio con casos que corregir)
     * en vez de la del siguiente servicio del plan; si {@code fqn} no está en el plan, la posición normal.
     */
    static String posicion(SesionMcp sesion, Path proyecto, String fqn) {
        if (fqn == null || sesion.progresoPlan(proyecto) == null) {
            return posicion(sesion, proyecto);
        }
        Set<String> fqns = new LinkedHashSet<>();
        for (SesionMcp.EstadoMetodo m : sesion.estadoPlan(proyecto)) {
            fqns.add(m.fqn());
        }
        int k = new ArrayList<>(fqns).indexOf(fqn) + 1;
        return k <= 0 ? posicion(sesion, proyecto) : prefijoBloque(sesion) + "servicio " + k + " de " + fqns.size();
    }

    /** «metodo=faltan» de los métodos del servicio con casos pendientes, separados por «, » (máx. {@link #MAX_REQUERIDOS}). */
    static String requeridos(SesionMcp sesion, Path proyecto, String fqn) {
        return requeridos(sesion, proyecto, fqn, MAX_REQUERIDOS);
    }

    /** Como {@link #requeridos(SesionMcp, Path, String)} con como máximo {@code maxReq} métodos. */
    static String requeridos(SesionMcp sesion, Path proyecto, String fqn, int maxReq) {
        List<String> r = new ArrayList<>();
        int total = 0;
        for (SesionMcp.EstadoMetodo m : sesion.estadoPlan(proyecto)) {
            if (fqn != null && fqn.equals(m.fqn()) && m.faltan() > 0) {
                total++;
                if (r.size() < maxReq) {
                    r.add(m.metodo() + "=" + m.faltan());
                }
            }
        }
        return String.join(", ", r) + (total > maxReq ? ", " + MAS + " (resto en plan.faltan)" : "");
    }

    /** Texto del paso de plan para el servicio en curso según la herramienta que acaba de responder y sus argumentos. */
    static String pasoServicio(SesionMcp sesion, Path proyecto, Path salida, String herramienta, JsonNode args) {
        return pasoServicio(sesion, proyecto, salida, herramienta, args, MAX_REQUERIDOS);
    }

    /** Como {@link #pasoServicio(SesionMcp, Path, Path, String, JsonNode)} con como máximo {@code maxReq} métodos listados. */
    static String pasoServicio(SesionMcp sesion, Path proyecto, Path salida, String herramienta, JsonNode args, int maxReq) {
        return pasoServicio(sesion, proyecto, salida, herramienta, args, maxReq, false);
    }

    /**
     * Como {@link #pasoServicio(SesionMcp, Path, Path, String, JsonNode, int)}; {@code haySiguiente} = la respuesta trae
     * {@code paginacion.siguiente} no nulo (el paquete o el detalle continúan).
     */
    static String pasoServicio(SesionMcp sesion, Path proyecto, Path salida, String herramienta, JsonNode args, int maxReq,
                               boolean haySiguiente) {
        return pasoServicio(sesion, proyecto, salida, herramienta, args, maxReq, haySiguiente, false);
    }

    /**
     * Como {@link #pasoServicio(SesionMcp, Path, Path, String, JsonNode, int, boolean)}; {@code hayPaquete} = la respuesta
     * trae un paquete de trabajo (tomar_bloque o {@code siguienteServicio.paquete} de un bloque). Solo cambia el texto en una
     * llamada con bloque.
     */
    static String pasoServicio(SesionMcp sesion, Path proyecto, Path salida, String herramienta, JsonNode args, int maxReq,
                               boolean haySiguiente, boolean hayPaquete) {
        String s = HerramientaGenerar.siguienteServicio(sesion, proyecto);
        if (s == null) {
            return textoCierre(sesion);
        }
        Integer bloque = sesion.bloqueLlamada();
        if (bloque != null) {
            return pasoServicioBloque(sesion, proyecto, salida, herramienta, maxReq, haySiguiente, hayPaquete, s, bloque);
        }
        String simple = "`" + Textos.nombreSimple(s) + "`";
        String req = requeridos(sesion, proyecto, s, maxReq);
        String elegir = "llama ahora a seleccionar_funciones {clase: \"" + s + "\", funciones: [{nombre, motivo}], omitidas: "
                + "[{nombre, motivo}]} eligiendo de las candidatas";
        boolean sobreS = sobre(args, s);
        if (SELECCIONAR.equals(herramienta) && sobreS && !conFunciones(args)) {
            return elegir + " de abajo.";
        }
        if ((SELECCIONAR.equals(herramienta) || DETALLE.equals(herramienta)) && sobreS
                && !sesion.necesitaSeleccion(proyecto, salida, s)) {
            return haySiguiente
                    ? "el paquete continúa: llama ahora UNA vez a detalle_servicio con los argumentos exactos de paginacion.siguiente "
                    + "y después a generar_tests con el spec de " + simple + ". Casos por método: " + req + "."
                    : "ya tienes todo en el paquete de abajo: llama ahora a generar_tests con el spec de " + simple
                    + " (no llames a detalle_servicio). Casos por método: " + req + ".";
        }
        if (VALIDAR.equals(herramienta)) {
            return "llama ahora a generar_tests con ese mismo spec (casos por método de " + simple + ": " + req + ").";
        }
        if (sesion.necesitaSeleccion(proyecto, salida, s)) {
            return elegir + " de esta respuesta (si no vienen, llama antes a seleccionar_funciones solo con clase).";
        }
        if (GENERAR.equals(herramienta) && conAcreditados(sesion, proyecto, s)) {
            return simple + " aún no está completo: llama ahora a generar_tests con un spec de " + simple + " que AÑADA casos nuevos "
                    + "(otra rama, un error o un valor límite) por método: " + req + ".";
        }
        return "llama ahora a detalle_servicio {clase: \"" + s + "\", paquete: 1} y después a generar_tests con sus casos por "
                + "método: " + req + ".";
    }

    /** Paso del servicio en curso de un bloque (el trabajador no elige con seleccionar_funciones: la selección va en el spec). */
    private static String pasoServicioBloque(SesionMcp sesion, Path proyecto, Path salida, String herramienta, int maxReq,
                                             boolean haySiguiente, boolean hayPaquete, String s, int bloque) {
        String simple = "`" + Textos.nombreSimple(s) + "`";
        String req = requeridos(sesion, proyecto, s, maxReq);
        boolean elige = sesion.necesitaSeleccion(proyecto, salida, s);
        String casos = elige ? "Casos por método (de las funciones que elijas): " : "Casos por método: ";
        String conSeleccion = elige ? "(servicios[0].seleccion + casos)" : "(casos)";
        if (VALIDAR.equals(herramienta)) {
            return "llama ahora a generar_tests con ese mismo spec (casos por método de " + simple + ": " + req + ").";
        }
        if (haySiguiente) {
            return "el paquete continúa: llama ahora UNA vez a detalle_servicio con los argumentos exactos de paginacion.siguiente "
                    + "y después a generar_tests con el spec de " + simple + " " + conSeleccion + ". " + casos + req + ".";
        }
        if (GENERAR.equals(herramienta) && conAcreditados(sesion, proyecto, s)) {
            return simple + " aún no está completo: llama ahora a generar_tests con un spec de " + simple + " que AÑADA casos nuevos "
                    + "(otra rama, un error o un valor límite) por método: " + req + ".";
        }
        if (hayPaquete) {
            return "escribe el spec de " + simple + " con el paquete de abajo " + conSeleccion + " y llama ahora a generar_tests. "
                    + casos + req + ".";
        }
        if (DETALLE.equals(herramienta) || SELECCIONAR.equals(herramienta)) {
            return "con lo que ya tienes escribe el spec de " + simple + " " + conSeleccion + " y llama ahora a generar_tests. "
                    + casos + req + ".";
        }
        return "llama ahora a tomar_bloque {bloque: " + bloque + "} y después a generar_tests con el spec de " + simple + ".";
    }

    /** Texto de la llamada a hacer para el paso pendiente, según la herramienta que acaba de responder. */
    static String textoPaso(HerramientaEjecutar.Paso p, SesionMcp sesion, Path proyecto, Path salida, String herramienta,
                            JsonNode args) {
        return textoPaso(p, sesion, proyecto, salida, herramienta, args, MAX_REQUERIDOS);
    }

    /**
     * Como {@link #textoPaso(HerramientaEjecutar.Paso, SesionMcp, Path, Path, String, JsonNode)} con como máximo
     * {@code maxReq} métodos listados. Sin listas de ids: solo cuántos casos (el detalle va en el JSON).
     */
    static String textoPaso(HerramientaEjecutar.Paso p, SesionMcp sesion, Path proyecto, Path salida, String herramienta,
                            JsonNode args, int maxReq) {
        return textoPaso(p, sesion, proyecto, salida, herramienta, args, maxReq, false);
    }

    /** Como {@link #textoPaso(HerramientaEjecutar.Paso, SesionMcp, Path, Path, String, JsonNode, int)} con {@code haySiguiente}. */
    static String textoPaso(HerramientaEjecutar.Paso p, SesionMcp sesion, Path proyecto, Path salida, String herramienta,
                            JsonNode args, int maxReq, boolean haySiguiente) {
        return textoPaso(p, sesion, proyecto, salida, herramienta, args, maxReq, haySiguiente, false);
    }

    /** Como {@link #textoPaso(HerramientaEjecutar.Paso, SesionMcp, Path, Path, String, JsonNode, int, boolean)} con {@code hayPaquete}. */
    static String textoPaso(HerramientaEjecutar.Paso p, SesionMcp sesion, Path proyecto, Path salida, String herramienta,
                            JsonNode args, int maxReq, boolean haySiguiente, boolean hayPaquete) {
        int n = p.ids() == null ? 0 : p.ids().size();
        boolean uno = n == 1;
        boolean trasEstado = HerramientaEstadoVerificacion.NOMBRE.equals(herramienta);
        return switch (p.tipo()) {
            case PLAN -> pasoServicio(sesion, proyecto, salida, herramienta, args, maxReq, haySiguiente, hayPaquete);
            case CIERRE -> textoCierre(sesion);
            case MONTAJE -> trasEstado
                    ? "corrige en el spec " + (uno ? "el caso" : "los " + n + " casos") + " de `corregir` (ids, errores y pistas en el "
                    + "JSON de abajo) y llama ahora a generar_tests {spec, reemplazarCasos: [esos ids]}."
                    : "`" + Textos.nombreSimple(p.fqn()) + "` tiene " + n + (uno ? " caso que no compila o falla" : " casos que no "
                    + "compilan o fallan") + " al montarse: llama ahora a " + HerramientaEstadoVerificacion.NOMBRE + " {} (trae ids, "
                    + "errores y pistas), corrige el spec y llama a generar_tests con reemplazarCasos.";
            case REEMPLAZAR -> trasEstado
                    ? "corrige esperado/aserciones de " + (uno ? "el caso" : "los " + n + " casos") + " de `reemplazar` según el código "
                    + "(parte de errores[].spec) y llama ahora a generar_tests {spec, reemplazarCasos: [esos ids]}."
                    : "hay " + n + (uno ? " caso" : " casos") + " con un cambio autorizado sin aplicar: llama ahora a "
                    + HerramientaEstadoVerificacion.NOMBRE + " {} (trae su spec) y después generar_tests con reemplazarCasos.";
            case REVISAR -> trasEstado
                    ? "por cada caso de revisar llama ahora a detalle_servicio {clase, metodos: [método del caso]} y después "
                    + "registra TODOS los veredictos en una llamada a registrar_analisis_ia {analisis: [{caso, veredicto, motivo}]}; "
                    + "no preguntes al usuario."
                    : DETALLE.equals(herramienta) && conMetodos(args)
                    ? "cuando hayas releído los métodos de " + (uno ? "el caso" : "los " + n + " casos") + " por revisar, llama ahora a "
                    + "registrar_analisis_ia {analisis: [{caso, veredicto: error_ia|bug_servicio|dudoso, motivo}]}; no preguntes al "
                    + "usuario."
                    : "hay " + n + (uno ? " fallo de aserción" : " fallos de aserción") + " por revisar: llama ahora a "
                    + HerramientaEstadoVerificacion.NOMBRE + " {}; no preguntes al usuario.";
            case PREGUNTA -> p.texto();
            case ARREGLO -> "fase de arreglo, " + (uno ? "queda 1 caso" : "quedan " + n + " casos") + ": llama ahora a "
                    + BloquesTanda.llamadaEjecutar(sesion)
                    + " (devuelve los casos fallidos del siguiente servicio, con su error, pistas, spec y código) y corrígelos en una "
                    + "llamada a arreglar_caso.";
        };
    }

    /**
     * Primera línea «accionRequerida: NO HAS TERMINADO (…): llama ahora a … No escribas ningún mensaje al usuario hasta
     * recibir respuestaFinal.», o null si no aplica (cambiar_ruta_salida, guía inactiva, respuesta con respuestaFinal,
     * nada pendiente o pendiente de una pregunta al usuario).
     */
    static String cabecera(SesionMcp sesion, String herramienta, JsonNode args, boolean error, String cuerpo) {
        if (CAMBIAR_RUTA.equals(herramienta)) {
            return null;
        }
        Path proyecto = sesion.fachada().ultimaRuta();
        if (!activa(sesion, proyecto)) {
            return null;
        }
        Path salida = sesion.salidaDe(proyecto);
        String c = cuerpo == null ? "" : cuerpo;
        if (c.contains("\"respuestaFinal\":") || c.contains("\nrespuestaFinal:\n") || c.startsWith(OleadasTodos.MARCA_CONFIRMACION)) {
            return null;
        }
        Integer bloque = sesion.bloqueLlamada();
        if (c.contains(MARCA_BLOQUE_TERMINADO)) {
            return lineaBloqueTerminado(bloque, c);
        }
        boolean propia = c.startsWith("{\"accionRequerida\"");
        boolean haySiguiente = c.contains(MARCA_SIGUIENTE);
        boolean hayPaquete = c.contains(MARCA_PAQUETE);
        HerramientaEjecutar.Paso paso = HerramientaEjecutar.pasoDetallado(sesion, proyecto, salida, EJECUTAR.equals(herramienta));
        if (paso == null && !propia) {
            return null;
        }
        if (paso != null && paso.tipo() == HerramientaEjecutar.TipoPaso.PREGUNTA) {
            return null;
        }
        // Sin ámbito de bloque y con la tanda en bloques: lo pendiente es lanzar (o relanzar) los bloques sin generar.
        List<Integer> sinGenerar = List.of();
        if (bloque == null && paso != null && paso.tipo() == HerramientaEjecutar.TipoPaso.PLAN && BloquesTanda.activo(sesion, proyecto)) {
            sinGenerar = BloquesTanda.sinGenerar(sesion, proyecto);
        }
        String texto;
        boolean conPaso = false;
        if (propia) {
            texto = "haz ahora lo que indica accionRequerida (primer campo del JSON de abajo).";
        } else if (error) {
            texto = "esta llamada ha sido rechazada: haz lo que indica el mensaje de abajo (corrige y repítela, o da antes el paso "
                    + "que pide).";
        } else if (!sinGenerar.isEmpty()) {
            texto = "lanza AHORA, en un solo mensaje y todos a la vez, un subagente (Agent en Claude Code; task o runSubagent en "
                    + "Copilot) por cada bloque con promptTrabajador; NO hagas tú los bloques (solo si no tienes subagentes o uno "
                    + "falla: tomar_bloque {bloque: id}); cuando vuelvan, ejecutar_tests {}.";
        } else {
            texto = textoPaso(paso, sesion, proyecto, salida, herramienta, args, MAX_REQUERIDOS, haySiguiente, hayPaquete);
            conPaso = true;
        }
        String pos;
        if (!sinGenerar.isEmpty()) {
            pos = sinGenerar.size() + (sinGenerar.size() == 1 ? " bloque sin generar: " : " bloques sin generar: ")
                    + BloquesTanda.listaIds(sinGenerar);
        } else if (paso != null && paso.tipo() == HerramientaEjecutar.TipoPaso.ARREGLO) {
            Matcher grupoReal = propia ? FASE_ARREGLO_EN_CUERPO.matcher(c.substring(0, Math.min(c.length(), 600))) : null;
            if (grupoReal != null && grupoReal.find()) {
                pos = prefijoBloque(sesion) + "fase de arreglo: " + grupoReal.group(1);
            } else {
                int[] cu = ArreglosFinal.cuenta(sesion, proyecto);
                pos = prefijoBloque(sesion) + "fase de arreglo: caso " + Math.max(1, cu[0] - cu[1] + 1) + " de " + cu[0];
            }
        } else {
            pos = paso != null && paso.tipo() == HerramientaEjecutar.TipoPaso.MONTAJE && paso.fqn() != null
                    ? posicion(sesion, proyecto, paso.fqn()) : posicion(sesion, proyecto);
            if (paso != null && paso.tipo() == HerramientaEjecutar.TipoPaso.PLAN) {
                int pendientes = ArreglosFinal.cuenta(sesion, proyecto)[1];
                if (pendientes > 0) {
                    pos += pendientes == 1 ? "; 1 fallo apuntado para arreglar al final"
                            : "; " + pendientes + " fallos apuntados para arreglar al final";
                }
            }
        }
        pos = OleadasTodos.prefijoPosicion(sesion, proyecto) + pos;
        String prefijo = PREFIJO + pos + "): ";
        String sufijo = bloque == null ? SUFIJO : " No escribas ningún mensaje al usuario hasta recibir BLOQUE " + bloque + " TERMINADO.";
        int libre = MAX_ACCION - LimitesRespuesta.bytes(prefijo) - LimitesRespuesta.bytes(sufijo);
        if (conPaso && LimitesRespuesta.bytes(unaLinea(texto)) > libre) {
            texto = textoPaso(paso, sesion, proyecto, salida, herramienta, args, REQUERIDOS_CORTO, haySiguiente, hayPaquete);
        }
        // Última red: el texto ya debería caber.
        String linea = prefijo + LimitesRespuesta.recortar(unaLinea(texto), Math.max(0, libre)) + sufijo;
        return unaLinea(linea);
    }

    /** Línea fija de un bloque terminado: sirve al trabajador (termina) y al agente principal (cierra con ejecutar_tests {}). */
    private static String lineaBloqueTerminado(Integer bloque, String cuerpo) {
        String b = bloque == null ? null : String.valueOf(bloque);
        if (b == null) {
            Matcher m = BLOQUE_EN_CUERPO.matcher(cuerpo);
            b = m.find() ? m.group(1) : "?";
        }
        return "accionRequerida: BLOQUE " + b + " TERMINADO: si eres el trabajador de este bloque, responde solo «BLOQUE " + b
                + " TERMINADO» y termina; si eres el agente principal, llama ahora a ejecutar_tests {}.";
    }

    /**
     * Rechazo de la puerta de plan: no se empieza (ni se pre-lee) otro servicio del plan mientras el servicio en curso no
     * esté completo. null si no aplica (sin plan o completo, objetivo = servicio en curso o fuera del plan) o si la
     * puerta ya rechazó {@link SesionMcp#MAX_RECHAZOS_PUERTA} veces seguidas (se abre: nunca bucle infinito).
     */
    static String bloqueoPlan(SesionMcp sesion, Path proyecto, Path salida, String fqnObjetivo) {
        SesionMcp.ProgresoPlan pp = sesion.progresoPlan(proyecto);
        String s = HerramientaGenerar.siguienteServicio(sesion, proyecto);
        if (pp == null || pp.restantes() == 0 || s == null || s.equals(fqnObjetivo) || fqnObjetivo == null
                || !sesion.enPlan(proyecto, fqnObjetivo)) {
            sesion.reiniciarRechazosPuertaPlan();
            return null;
        }
        if (sesion.contarRechazoPuertaPlan() > SesionMcp.MAX_RECHAZOS_PUERTA) {
            sesion.reiniciarRechazosPuertaPlan();
            return null;
        }
        String paso = pasoServicio(sesion, proyecto, salida, null, null);
        String pasoMayuscula = paso.isEmpty() ? paso : paso.substring(0, 1).toUpperCase(Locale.ROOT) + paso.substring(1);
        String t = "`" + Textos.nombreSimple(fqnObjetivo) + "`";
        return "No empieces " + t + " todavía: el servicio en curso es `" + Textos.nombreSimple(s) + "` (" + posicion(sesion, proyecto)
                + ") y le faltan casos (" + requeridos(sesion, proyecto, s) + "). " + pasoMayuscula + " junit-gen te dará " + t
                + " cuando toque.";
    }

    private static String textoCierre(SesionMcp sesion) {
        Integer b = sesion.bloqueLlamada();
        return b == null
                ? "llama ahora a " + EJECUTAR + " {} (cierra la tanda; repítela mientras devuelva verificando=true)."
                : "llama ahora a " + BloquesTanda.llamadaEjecutar(sesion) + " (cierra tu bloque: prueba y arregla sus servicios; "
                + "repítela mientras devuelva verificando=true).";
    }

    private static String unaLinea(String s) {
        return s == null ? "" : s.replace('\r', ' ').replace('\n', ' ');
    }

    /** true si args.clase es el FQN o (sin distinguir mayúsculas) el nombre simple del servicio. */
    private static boolean sobre(JsonNode args, String fqn) {
        if (args == null || fqn == null) {
            return false;
        }
        JsonNode c = args.get("clase");
        if (c == null || !c.isTextual()) {
            return false;
        }
        String v = c.asText().trim();
        return v.equals(fqn) || v.equalsIgnoreCase(Textos.nombreSimple(fqn));
    }

    private static boolean conFunciones(JsonNode args) {
        JsonNode f = args == null ? null : args.get("funciones");
        return f != null && !f.isNull() && !(f.isArray() && f.isEmpty());
    }

    private static boolean conMetodos(JsonNode args) {
        JsonNode m = args == null ? null : args.get("metodos");
        return m != null && !m.isNull() && !(m.isArray() && m.isEmpty());
    }

    private static boolean conAcreditados(SesionMcp sesion, Path proyecto, String fqn) {
        for (SesionMcp.EstadoMetodo m : sesion.estadoPlan(proyecto)) {
            if (fqn.equals(m.fqn()) && m.acreditados() > 0) {
                return true;
            }
        }
        return false;
    }
}
