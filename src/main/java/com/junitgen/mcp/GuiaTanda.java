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

    /** «servicio k de N» (servicio en curso dentro del plan) o «cierre de la tanda» si no quedan casos. */
    static String posicion(SesionMcp sesion, Path proyecto) {
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
        return k <= 0 ? posicion(sesion, proyecto) : "servicio " + k + " de " + fqns.size();
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
        String s = HerramientaGenerar.siguienteServicio(sesion, proyecto);
        if (s == null) {
            return textoCierre();
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
        int n = p.ids() == null ? 0 : p.ids().size();
        boolean uno = n == 1;
        boolean trasEstado = HerramientaEstadoVerificacion.NOMBRE.equals(herramienta);
        return switch (p.tipo()) {
            case PLAN -> pasoServicio(sesion, proyecto, salida, herramienta, args, maxReq, haySiguiente);
            case CIERRE -> textoCierre();
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
            case ARREGLO -> "fase de arreglo, " + (uno ? "queda 1 caso" : "quedan " + n + " casos") + ": llama ahora a " + EJECUTAR
                    + " {} (devuelve los casos fallidos del siguiente servicio, con su error, pistas, spec y código) y corrígelos en una "
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
        if (c.contains("\"respuestaFinal\":") || c.contains("\nrespuestaFinal:\n")) {
            return null;
        }
        boolean propia = c.startsWith("{\"accionRequerida\"");
        boolean haySiguiente = c.contains(MARCA_SIGUIENTE);
        HerramientaEjecutar.Paso paso = HerramientaEjecutar.pasoDetallado(sesion, proyecto, salida, EJECUTAR.equals(herramienta));
        if (paso == null && !propia) {
            return null;
        }
        if (paso != null && paso.tipo() == HerramientaEjecutar.TipoPaso.PREGUNTA) {
            return null;
        }
        String texto;
        boolean conPaso = false;
        if (propia) {
            texto = "haz ahora lo que indica accionRequerida (primer campo del JSON de abajo).";
        } else if (error) {
            texto = "esta llamada ha sido rechazada: haz lo que indica el mensaje de abajo (corrige y repítela, o da antes el paso "
                    + "que pide).";
        } else {
            texto = textoPaso(paso, sesion, proyecto, salida, herramienta, args, MAX_REQUERIDOS, haySiguiente);
            conPaso = true;
        }
        String pos;
        if (paso != null && paso.tipo() == HerramientaEjecutar.TipoPaso.ARREGLO) {
            int[] cu = ArreglosFinal.cuenta(sesion, proyecto);
            pos = "fase de arreglo: caso " + Math.max(1, cu[0] - cu[1] + 1) + " de " + cu[0];
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
        String prefijo = PREFIJO + pos + "): ";
        int libre = MAX_ACCION - LimitesRespuesta.bytes(prefijo) - LimitesRespuesta.bytes(SUFIJO);
        if (conPaso && LimitesRespuesta.bytes(unaLinea(texto)) > libre) {
            texto = textoPaso(paso, sesion, proyecto, salida, herramienta, args, REQUERIDOS_CORTO, haySiguiente);
        }
        // Última red: el texto ya debería caber.
        String linea = prefijo + LimitesRespuesta.recortar(unaLinea(texto), Math.max(0, libre)) + SUFIJO;
        return unaLinea(linea);
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

    private static String textoCierre() {
        return "llama ahora a " + EJECUTAR + " {} (cierra la tanda; repítela mientras devuelva verificando=true).";
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
