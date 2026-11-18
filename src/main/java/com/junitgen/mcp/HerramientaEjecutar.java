package com.junitgen.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.junitgen.Fachada;
import com.junitgen.config.CargadorConfig;
import com.junitgen.config.ConfigProyecto;
import com.junitgen.generacion.AlmacenSpec;
import com.junitgen.generacion.RutasSalida;
import com.junitgen.generacion.Tanda;
import com.junitgen.seguridad.EscritorSeguro;
import com.junitgen.seguridad.RutaSegura;
import com.junitgen.spec.CasoSpec;
import com.junitgen.spec.ServicioSpec;
import com.junitgen.spec.Spec;
import com.junitgen.util.Json;
import com.junitgen.util.LimitesRespuesta;
import com.junitgen.util.Textos;
import com.junitgen.verificacion.ClasificadorFallos;
import com.junitgen.verificacion.ResultadoTest;
import com.junitgen.verificacion.ResultadoVerificacion;
import com.junitgen.verificacion.VerificadorTests;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** ejecutar_tests: compila y ejecuta los tests generados en el temporal de la salida. */
public class HerramientaEjecutar implements Herramienta {

    public static final String BUG = "Es un bug, mantener los tests";
    public static final String AJUSTAR = "Ajustar lo esperado";
    public static final String ELIMINAR = "Eliminar casos";
    public static final String REVISAR_IA = "Revisar con IA";
    public static final String REVISAR_EN_CHAT = "Revisar uno a uno en el chat";
    static final List<String> OPCIONES = List.of(BUG, AJUSTAR, ELIMINAR, REVISAR_IA, REVISAR_EN_CHAT);
    static final int MAX_LINEAS = 20;
    static final int MAX_RESUMEN = 120;
    static final String DECISION_REVISAR_IA = "revisar_ia";
    static final String DECISION_PENDIENTE = "pendiente";
    /** Prefijo de accionRequerida cuando a la IA le queda trabajo propio (la respuesta va sin respuestaFinal). */
    static final String ACCION_PENDIENTE_IA = "NO HAS TERMINADO: esta respuesta no trae respuestaFinal porque queda trabajo "
            + "tuyo. No escribas el mensaje final; haz AHORA lo siguiente: ";
    /** resumenSesion.instruccion cuando a la IA le queda trabajo propio. */
    static final String RESUMEN_PENDIENTE_IA = "Cifras de control. Aún NO es el final: sigue accionRequerida.";
    /** Máximo de ids en las listas incrustadas en textos. */
    static final int MAX_IDS = 10;
    /** «expected: X but was: Y» (JUnit 5), «expected:<X> but was:<Y>» (JUnit 4) y «expected: X … but was: Y» (AssertJ). */
    private static final Pattern ESPERADO_OBTENIDO = Pattern.compile(
            "expected:?\\s*<?(.*?)>?\\s*,?\\s*but was:?\\s*<?(.*?)>?\\s*$", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern ASSERTJ = Pattern.compile(
            "expected:\\s*(.*?)\\s*(?:but was|but found|actual):\\s*(.*)", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    @Override
    public String nombre() {
        return "ejecutar_tests";
    }

    @Override
    public String titulo() {
        return "Ejecutar tests generados";
    }

    @Override
    public String descripcion() {
        return "Cierra la tanda: generar_tests ya prueba los tests en segundo plano (fuera del proyecto, que no se modifica). "
                + "Llámala sin argumentos solo cuando plan.restantes = 0 (espera hasta " + ESPERA_CIERRE.toSeconds() + " s; si "
                + "devuelve verificando=true, repítela). Si hay casos que fallaron abre la fase de arreglo: corrígelos con "
                + "arreglar_caso (o registrar_analisis_ia con bug_servicio si es un bug real). Nunca preguntes al usuario. Termina "
                + "con respuestaFinal: tu último mensaje es exactamente ese texto; si falta, no has terminado. En paralelo, cada "
                + "trabajador la llama con `bloque` y responde «BLOQUE K TERMINADO». Sigue accionRequerida. `todos`, `casos` y "
                + "`parcial` solo si el usuario lo pide.";
    }

    @Override
    public ObjectNode esquema() {
        ObjectNode s = Herramienta.esquemaBase();
        Herramienta.propiedad(s, "rutaProyecto", "string", "Ruta del proyecto; por defecto, la de la sesión.", false);
        Herramienta.propiedad(s, "carpetaTests", "string", "No la pases: se usa la carpeta de salida de la sesión.", false);
        Herramienta.propiedad(s, "modulo", "string", "Solo este módulo.", false);
        Herramienta.propiedad(s, "clases", "array", "Solo estas clases de test o servicios.", false);
        Herramienta.propiedad(s, "todos", "boolean", "Solo si el usuario pidió verificar también tests de sesiones anteriores.", false);
        Herramienta.propiedad(s, "casos", "array", "Solo estos ids de caso: fuerza su reejecución; solo si el usuario lo pide.", false);
        Herramienta.propiedad(s, "parcial", "boolean", "Ejecutar con el plan incompleto: solo si el usuario lo pidió expresamente.", false);
        Herramienta.propiedad(s, "paginaResultados", "integer", "Página (1..) de los resultados de la última ejecución; no reejecuta.", false);
        Herramienta.propiedad(s, "bloque", "integer", "Solo en paralelo: id del bloque que cierras; responde «BLOQUE K TERMINADO».", false);
        ((ObjectNode) s.get("properties").get("bloque")).put("minimum", 1);
        return s;
    }

    @Override
    public boolean soloLectura() {
        return false;
    }

    @Override
    public String aviso(JsonNode args, SesionMcp sesion) {
        // Solo la salida de la sesión: nunca la de carpetaTests (que puede haberse rechazado).
        Path proyecto = RutasSesion.proyectoConocido(args, sesion, "rutaProyecto");
        return Fachada.aviso(proyecto == null ? Path.of("(proyecto de la sesión)") : proyecto, sesion.salidaDe(proyecto));
    }

    @Override
    public Resultado ejecutar(JsonNode args, SesionMcp sesion) {
        Set<String> casos = new LinkedHashSet<>();
        for (String c : Herramienta.lista(args, "casos")) {
            String id = c == null ? "" : c.trim();
            if (!Textos.ID_CASO.matcher(id).matches()) {
                throw new IllegalArgumentException("Id de caso no válido en 'casos': " + c);
            }
            casos.add(id);
        }
        int paginaResultados = Herramienta.entero(args, "paginaResultados", 0);
        Path proyecto = RutaSegura.validarProyecto(RutasSesion.proyecto(args, sesion, "rutaProyecto", false));
        Path salidaSesion = sesion.salidaDe(proyecto);
        if (paginaResultados >= 1) {
            return paginaCache(sesion, proyecto, salidaSesion, paginaResultados);
        }
        Integer bloque = BloquesTanda.bloqueArg(args);
        if (bloque != null) {
            return ejecutarBloque(sesion, proyecto, salidaSesion, bloque);
        }
        // ---- Puertas: se evalúan antes de cualquier efecto (ni salida recordada, ni decisión, ni temporal).
        Path carpetaArg = Herramienta.ruta(args, "carpetaTests");
        if (carpetaArg != null && salidaSesion != null) {
            Path normalizada = RutaSegura.normalizar(carpetaArg);
            if (!RutaSegura.iguales(normalizada, salidaSesion)) {
                return rechazo(sesion, proyecto, salidaSesion, "ejecutar_tests no ha ejecutado nada: la carpeta de salida de esta "
                        + "sesión es " + salidaSesion + ", no " + carpetaArg + ". Repite la llamada sin `carpetaTests`. No verifiques ni "
                        + "leas carpetas de sesiones anteriores; para cambiar de carpeta, cambiar_ruta_salida (solo si el usuario lo pide).");
            }
        }
        boolean todos = Herramienta.bool(args, "todos");
        boolean parcial = Herramienta.bool(args, "parcial");
        if (!todos && !parcial && BloquesTanda.activo(sesion, proyecto)) {
            List<Integer> sinGenerar = BloquesTanda.sinGenerar(sesion, proyecto);
            if (!sinGenerar.isEmpty()) {
                return rechazo(sesion, proyecto, salidaSesion, "ejecutar_tests no ha ejecutado nada: bloques sin generar: "
                        + BloquesTanda.listaIds(sinGenerar) + ". Lanza (o relanza) un subagente por cada uno con el promptTrabajador "
                        + "(cambia <K> por el bloque) o haz tú cada bloque con tomar_bloque {bloque: K, instrucciones: true} y sigue sus "
                        + "instrucciones; cuando estén todos generados vuelve a llamar a ejecutar_tests {}.");
            }
        }
        SesionMcp.ProgresoPlan pp = sesion.progresoPlan(proyecto);
        List<String> deSesion = salidaSesion == null ? List.of() : sesion.casosDeSesion(salidaSesion);
        if (pp != null) {
            if (pp.casosEscritos() == 0) {
                if (pp.restantes() == 0) {
                    return rechazo(sesion, proyecto, salidaSesion, "ejecutar_tests no ha ejecutado nada: no hay nada generado en esta "
                            + "sesión que ejecutar (el plan se completó solo con casos descartados por duplicado); tu mensaje final es "
                            + "EXACTAMENTE respuestaFinal, tal cual.", true);
                }
                return rechazo(sesion, proyecto, salidaSesion, "ejecutar_tests no ha ejecutado nada: hay un plan activo (" + pp.servicios()
                        + (pp.servicios() == 1 ? " servicio, " : " servicios, ") + pp.metodosTotal() + (pp.metodosTotal() == 1 ? " método, " : " métodos, ")
                        + pp.total() + (pp.total() == 1 ? " caso" : " casos") + ") y en esta sesión no se ha generado ningún caso. "
                        + pendientesPlan(sesion, proyecto, pp) + " Genera primero (por cada servicio: seleccionar_funciones con "
                        + "funciones si falta (trae el paquete de trabajo; detalle_servicio solo si paginacion.siguiente; sin selección: "
                        + "detalle_servicio {clase, paquete: 1}) → spec → generar_tests) "
                        + "y solo al final ejecutar_tests.");
            }
            if (pp.restantes() > 0 && !parcial && !sesion.ejecucionParcialAutorizada()) {
                return rechazo(sesion, proyecto, salidaSesion, "ejecutar_tests no ha ejecutado nada: el plan no está completo (generados "
                        + pp.generados() + " de " + pp.total() + " casos; faltan " + pp.restantes() + "). " + pendientesPlan(sesion, proyecto, pp)
                        + " Sigue generando hasta plan.restantes = 0. Solo si el USUARIO pidió expresamente ejecutar ya lo generado, "
                        + "repite con parcial=true; no lo decidas tú.");
            }
        } else if (deSesion.isEmpty() && !todos) {
            return rechazo(sesion, proyecto, salidaSesion, SIN_GENERAR_SIN_PLAN);
        }
        Set<String> alcance = null;
        if (!todos) {
            List<String> ajenos = new ArrayList<>();
            for (String c : casos) {
                if (!deSesion.contains(c)) {
                    ajenos.add(c);
                }
            }
            if (!ajenos.isEmpty()) {
                return rechazo(sesion, proyecto, salidaSesion, "ejecutar_tests no ha ejecutado nada: los casos "
                        + LimitesRespuesta.listaCorta(ajenos, 10) + " no se han generado en esta sesión. Pasa en `casos` solo ids "
                        + "generados en esta sesión (o llama sin `casos`).");
            }
            alcance = new LinkedHashSet<>(casos.isEmpty() ? deSesion : casos);
            if (alcance.isEmpty()) {
                return rechazo(sesion, proyecto, salidaSesion, SIN_GENERAR_SIN_PLAN);
            }
        }
        if (parcial && pp != null && pp.restantes() > 0) {
            sesion.autorizarEjecucionParcial();
        }
        // ---- Efectos: salida de la sesión (o la del argumento si la sesión aún no tiene ninguna), decisión y ejecución.
        Path salida = RutasSesion.salida(proyecto, salidaSesion == null ? carpetaArg : null, sesion, true);
        if (sesion.decisionVerificacion() == null) {
            if (sesion.verificacionSinRespuesta()
                    && CargadorConfig.modoVerificacion(proyecto) == ConfigProyecto.ModoVerificacion.PREGUNTAR) {
                // Solo con «verificacion: preguntar»: [Probar tests] [Descartar] quedó sin responder; se repite la
                // pregunta y sin respuesta no se ejecuta.
                Resultado sinEjecutar = repreguntarVerificacion(sesion, proyecto, salida);
                if (sinEjecutar != null) {
                    return sinEjecutar;
                }
            } else {
                // Ejecutar es en sí la decisión de probar (no se pregunta): vale para el resto de la sesión.
                sesion.fijarDecisionVerificacion(HerramientaGenerar.DECISION_PROBAR);
            }
        }
        List<String> clases = Herramienta.lista(args, "clases");
        String moduloArg = Herramienta.texto(args, "modulo");
        boolean heredada = todos || !clases.isEmpty() || (moduloArg != null && !moduloArg.isBlank());
        if (!heredada) {
            // Ruta de cierre de la tanda: reutiliza las verificaciones en segundo plano (solo reejecuta lo pedido o pendiente).
            return cierre(sesion, proyecto, salida, casos, alcance);
        }
        // Ruta heredada (todos, clases o modulo): espera a la cola en segundo plano y ejecuta de forma síncrona.
        if (!sesion.verificaciones().esperarInactiva(salida, restoEspera(sesion))) {
            return verificando(sesion, proyecto, salida);
        }
        if (clases.isEmpty() && !todos) {
            clases = new ArrayList<>(sesion.clasesGeneradas(salida));
        }
        ResultadoVerificacion rv = sesion.fachada().ejecutar(proyecto, salida, Herramienta.texto(args, "modulo"), clases,
                todos ? (casos.isEmpty() ? null : casos) : alcance);
        if (!todos) {
            rv.avisos.add(0, "Se verifican solo los " + alcance.size() + " casos generados en esta sesión.");
        }
        Set<String> idsSesion = new LinkedHashSet<>(sesion.casosDeSesion(salida));
        Map<String, String> estados = new LinkedHashMap<>();
        int ajenos = 0;
        for (ResultadoTest t : rv.resultados) {
            t.deEstaSesion = t.caso != null && idsSesion.contains(t.caso);
            if (t.deEstaSesion) {
                estados.put(t.caso, t.estado);
            } else {
                ajenos++;
            }
        }
        sesion.registrarEstados(salida, estados);
        decidirAserciones(sesion, salida, rv, todos ? null : idsSesion, revisionAutomatica(proyecto));
        rv.resumenSesion = sesion.resumenSesion(proyecto, salida);
        rv.respuestaFinal = sesion.respuestaFinal(proyecto, salida);
        rv.resultadosDeOtrasSesiones = ajenos;
        if (ajenos > 0) {
            rv.resumen = rv.resultados.size() + " resultados (" + (rv.resultados.size() - ajenos) + " de esta sesión, " + ajenos
                    + " de sesiones anteriores no contados): usa resumenSesion";
        }
        if (!rv.resultados.isEmpty() && ajenos == rv.resultados.size()) {
            rv.resumenSesion.put("advertencia", "Ningún resultado es de esta sesión: no informes de tests generados ni de que pasan "
                    + "como resultado de esta ejecución.");
        }
        sesion.guardarTanda(proyecto, salida);
        return new Resultado(Json.compacto(vistaCompacta(sesion, proyecto, salida, rv)), false);
    }

    /** Espera máxima a las verificaciones en segundo plano dentro de una llamada a ejecutar_tests. */
    static final Duration ESPERA_CIERRE = Duration.ofSeconds(60);

    /** Espera máxima a las verificaciones de un bloque dentro de una llamada con bloque (hay otros bloques en la cola). */
    static final Duration ESPERA_BLOQUE = Duration.ofSeconds(120);

    /** Lo que queda de {@link #ESPERA_CIERRE} (o, con bloque, de {@link #ESPERA_BLOQUE}) en la llamada en curso (nunca negativo). */
    static Duration restoEspera(SesionMcp sesion) {
        Duration total = sesion.bloqueLlamada() != null ? ESPERA_BLOQUE : ESPERA_CIERRE;
        Duration resto = total.minus(sesion.transcurridoLlamada());
        return resto.isNegative() ? Duration.ZERO : resto;
    }

    /**
     * Cierre de un bloque (ejecutar_tests {bloque}): con todos sus servicios generados, encola y espera las pruebas de sus
     * servicios; si quedan casos por arreglar entrega el siguiente grupo de un servicio del bloque; si no, el bloque queda
     * terminado (nunca hay respuestaFinal: la da el agente principal con ejecutar_tests {}).
     */
    private static Resultado ejecutarBloque(SesionMcp sesion, Path proyecto, Path salida, int bloque) {
        Tanda t = sesion.tanda(proyecto);
        if (!BloquesTanda.activo(t)) {
            throw new IllegalArgumentException("'bloque' solo vale en una tanda en paralelo (escanear_proyecto con paralelo: true) de "
                    + "este proceso de junit-gen.");
        }
        if (BloquesTanda.de(t, bloque) == null) {
            throw new IllegalArgumentException(BloquesTanda.errorBloqueInexistente(t, bloque));
        }
        sesion.fijarBloqueLlamada(bloque);
        if (salida == null) {
            throw new IllegalArgumentException("No hay carpeta de salida en esta sesión: la tanda en paralelo la necesita.");
        }
        if (BloquesTanda.terminado(sesion, proyecto, bloque)) {
            return new Resultado(Json.compacto(BloquesTanda.nodoTerminado(sesion, proyecto, salida, bloque, null)), false);
        }
        // Cola de trabajo: si no le queda nada por generar y hay servicios sin asignar (o sin tomar), le toca el siguiente.
        BloquesTanda.reclamar(sesion, proyecto, bloque);
        SesionMcp.ProgresoPlan pp = sesion.progresoPlan(proyecto);
        if (pp == null || pp.restantes() > 0) {
            return rechazo(sesion, proyecto, salida, "ejecutar_tests {bloque: " + bloque + "} no ha ejecutado nada: al bloque le faltan "
                    + "casos por generar. " + (pp == null ? "" : pendientesPlan(sesion, proyecto, pp) + " ")
                    + GuiaTanda.pasoServicio(sesion, proyecto, salida, null, null));
        }
        if (sesion.decisionVerificacion() == null) {
            sesion.fijarDecisionVerificacion(HerramientaGenerar.DECISION_PROBAR);
        }
        Set<String> servicios = BloquesTanda.serviciosDe(t, bloque);
        VerificacionesSesion ver = sesion.verificaciones();
        ver.encolarPendientes(proyecto, salida, serviciosSinEstado(sesion, salida, servicios));
        if (!ver.esperarInactiva(salida, restoEspera(sesion))) {
            return verificandoBloque(sesion, salida);
        }
        // Un solo reintento por bloque: los servicios que quedaron con casos sin estado se vuelven a verificar antes de cerrar.
        Set<String> reintento = serviciosSinEstado(sesion, salida, servicios);
        if (!reintento.isEmpty() && sesion.contarSinCorregir("reverificar-bloque|" + t.creada + "|" + bloque, 0) == 1) {
            ver.encolar(proyecto, salida, reintento);
            if (!ver.esperarInactiva(salida, restoEspera(sesion))) {
                return verificandoBloque(sesion, salida);
            }
        }
        if (GuiaTanda.arreglosAlFinal(sesion, proyecto)) {
            for (String fqn : servicios) {
                ArreglosFinal.sincronizar(sesion, proyecto, salida, fqn);
            }
            ObjectNode carga = ArreglosFinal.respuesta(sesion, proyecto, salida, null);
            if (carga != null) {
                sesion.guardarTanda(proyecto, salida, Boolean.FALSE);
                return new Resultado(Json.compacto(carga), false);
            }
        }
        ObjectNode fin = BloquesTanda.nodoTerminado(sesion, proyecto, salida, bloque, null);
        sesion.guardarTanda(proyecto, salida, Boolean.FALSE);
        return new Resultado(Json.compacto(fin), false);
    }

    /** FQN de los servicios dados con algún caso generado sin estado de verificación. */
    private static Set<String> serviciosSinEstado(SesionMcp sesion, Path salida, Set<String> servicios) {
        Map<String, String> estados = sesion.estadosDeSesion(salida);
        Set<String> sinEstado = new LinkedHashSet<>();
        sesion.serviciosDeCasos(salida).forEach((id, fqn) -> {
            if (fqn != null && servicios.contains(fqn) && !estados.containsKey(id)) {
                sinEstado.add(fqn);
            }
        });
        return sinEstado;
    }

    /** Las pruebas de los servicios del bloque siguen en marcha: hay que volver a llamar con el mismo bloque. */
    private static Resultado verificandoBloque(SesionMcp sesion, Path salida) {
        VerificacionesSesion.Progreso p = sesion.verificaciones().progreso(salida);
        ObjectNode r = Json.objeto();
        r.put("accionRequerida", ACCION_PENDIENTE_IA + "Las pruebas de tu bloque no han terminado (" + p.verificados() + " de "
                + p.total() + " servicios con tests verificados). Llama OTRA VEZ a " + BloquesTanda.llamadaEjecutar(sesion)
                + " (cada llamada espera hasta " + ESPERA_BLOQUE.toSeconds() + " s). No generes nada más.");
        r.put("verificando", true);
        ObjectNode pg = r.putObject("progreso");
        pg.put("serviciosConTests", p.total());
        pg.put("verificados", p.verificados());
        pg.put("enCola", p.enCola());
        return new Resultado(Json.compacto(r), false);
    }

    /**
     * Cierre de la tanda (sin todos/clases/modulo): con {@code casos} fuerza la reejecución de los que ya tenían estado;
     * encola los servicios con casos sin estado que no estén en cola, en curso ni ya verificados; espera como máximo lo
     * que quede de {@link #ESPERA_CIERRE} y, si todo terminó, arma el resultado agregado y deja los fallos de aserción
     * sin veredicto para que los revise la IA (o, con {@code fallosAsercion: preguntar}, hace la pregunta única).
     */
    private static Resultado cierre(SesionMcp sesion, Path proyecto, Path salida, Set<String> casos, Set<String> alcance) {
        VerificacionesSesion ver = sesion.verificaciones();
        Map<String, String> servicios = sesion.serviciosDeCasos(salida);
        if (!casos.isEmpty()) {
            Map<String, String> estados = sesion.estadosDeSesion(salida);
            List<String> conEstado = new ArrayList<>();
            Set<String> fqns = new LinkedHashSet<>();
            for (String id : casos) {
                if (estados.containsKey(id)) {
                    conEstado.add(id);
                    fqns.add(servicios.get(id));
                }
            }
            if (!conEstado.isEmpty()) {
                sesion.olvidarEstados(salida, conEstado);
                ver.encolar(proyecto, salida, fqns);
            }
        }
        Map<String, String> estados = sesion.estadosDeSesion(salida);
        Set<String> sinEstado = new LinkedHashSet<>();
        servicios.forEach((id, fqn) -> {
            if (fqn != null && !estados.containsKey(id)) {
                sinEstado.add(fqn);
            }
        });
        ver.encolarPendientes(proyecto, salida, sinEstado);
        if (!ver.esperarInactiva(salida, restoEspera(sesion))) {
            return verificando(sesion, proyecto, salida);
        }
        boolean alFinal = GuiaTanda.arreglosAlFinal(sesion, proyecto);
        if (alFinal) {
            // Fase de arreglo: mientras quede algún caso apuntado, se entrega uno (con su error, su spec y su código).
            ArreglosFinal.sincronizar(sesion, proyecto, salida, null);
            ObjectNode carga = ArreglosFinal.respuesta(sesion, proyecto, salida, null);
            if (carga != null) {
                sesion.guardarTanda(proyecto, salida, Boolean.FALSE);
                return new Resultado(Json.compacto(carga), false);
            }
        }
        boolean auto = revisionAutomatica(proyecto);
        ResultadoVerificacion rv = agregado(sesion, salida, alcance, auto);
        rv.avisos.add(0, "Se verifican solo los " + alcance.size() + " casos generados en esta sesión.");
        Set<String> idsSesion = new LinkedHashSet<>(sesion.casosDeSesion(salida));
        decidirAserciones(sesion, salida, rv, idsSesion, auto);
        if (alFinal && trabajoIaPendiente(sesion, salida, rv)) {
            // Red de seguridad: con la fase de arreglo terminada no se devuelve trabajo de corrección (la tanda converge).
            System.err.println("junit-gen: el cierre de la tanda tenía correcciones pendientes fuera de la fase de arreglo; se ignoran.");
            rv.montajeReintentables.clear();
            rv.asercionesPendientes.clear();
            rv.revisionIaPendiente.clear();
            rv.decisiones.removeIf(d -> DECISION_REVISAR_IA.equals(d.get("decision")) || "ajustar".equals(d.get("decision")));
            rv.accionRequerida = "";
            ver.olvidarCorrecciones(salida);
        }
        rv.resumenSesion = sesion.resumenSesion(proyecto, salida);
        rv.respuestaFinal = sesion.respuestaFinal(proyecto, salida);
        if (alFinal && !trabajoIaPendiente(sesion, salida, rv) && pasoPendiente(sesion, proyecto, salida, true) == null) {
            ArreglosFinal.informar(sesion, proyecto, salida);
        }
        boolean cerrada = tandaCerrada(sesion, salida, rv) && pasoPendiente(sesion, proyecto, salida, true) == null;
        if (cerrada) {
            MedicionTanda.informar(sesion, proyecto, salida);
        }
        sesion.guardarTanda(proyecto, salida, cerrada);
        return new Resultado(Json.compacto(vistaCompacta(sesion, proyecto, salida, rv)), false);
    }

    /**
     * true si tras el cierre no quedan correcciones de montaje ni fallos de aserción pendientes (sin responder, en el
     * chat, en revisión de la IA o por ajustar): la tanda queda cerrada en tanda.json.
     */
    static boolean tandaCerrada(SesionMcp sesion, Path salida, ResultadoVerificacion rv) {
        if (!rv.montajeReintentables.isEmpty() || !rv.revisionIaPendiente.isEmpty()
                || !sesion.verificaciones().correcciones(salida).isEmpty() || sesion.asercionesSinRespuesta(salida) > 0) {
            return false;
        }
        for (Map<String, String> d : rv.decisiones) {
            String decision = d.get("decision");
            if (!"bug".equals(decision) && !"eliminar".equals(decision)) {
                return false;
            }
        }
        return true;
    }

    /**
     * true si queda trabajo propio de la IA: montaje reintentable, revisión con IA, correcciones de montaje pendientes
     * o decisiones de ajustar / revisar con IA.
     */
    static boolean trabajoIaPendiente(SesionMcp sesion, Path salida, ResultadoVerificacion rv) {
        if (!rv.montajeReintentables.isEmpty() || !rv.revisionIaPendiente.isEmpty()
                || !sesion.verificaciones().correcciones(salida).isEmpty()) {
            return true;
        }
        for (Map<String, String> d : rv.decisiones) {
            String decision = d.get("decision");
            if ("ajustar".equals(decision) || DECISION_REVISAR_IA.equals(decision)) {
                return true;
            }
        }
        return false;
    }

    /** true si los fallos de aserción del proyecto los revisa la IA sola ({@code fallosAsercion: revisarIa}, por defecto). */
    static boolean revisionAutomatica(Path proyecto) {
        return CargadorConfig.modoFallosAsercion(proyecto) == ConfigProyecto.ModoFallosAsercion.REVISAR_IA;
    }

    /**
     * Casos de la sesión en fallo_asercion según spec.json: {@code revisar} (sin autorización de cambio: falta el
     * veredicto) y {@code reemplazar} (con cambio autorizado sin aplicar).
     */
    record PendientesAsercion(List<String> revisar, List<String> reemplazar) {
    }

    /**
     * Pendientes de aserción de la sesión con spec.json como fuente de verdad; null si el spec no se puede leer (sin
     * información: nunca se interpreta como «nada pendiente»).
     */
    static PendientesAsercion pendientesAsercion(SesionMcp sesion, Path salida) {
        if (salida == null) {
            return null;
        }
        Spec spec;
        try {
            spec = AlmacenSpec.leer(salida);
        } catch (RuntimeException e) {
            // spec.json ilegible o bloqueado: sin información (no se borra ningún estado).
            return null;
        }
        List<String> revisar = new ArrayList<>();
        List<String> reemplazar = new ArrayList<>();
        if (spec == null) {
            return new PendientesAsercion(revisar, reemplazar);
        }
        for (String id : sesion.casosDeSesion(salida)) {
            AlmacenSpec.Ubicacion u = AlmacenSpec.buscar(spec, id);
            CasoSpec c = u == null ? null : u.caso();
            if (c == null || !CasoSpec.ESTADO_FALLO_ASERCION.equals(c.estado)) {
                continue;
            }
            (c.autorizacionCambio != null ? reemplazar : revisar).add(id);
        }
        return new PendientesAsercion(revisar, reemplazar);
    }

    /**
     * Calcula {@link #pendientesAsercion} y reconcilia con ello la pista de revisiones en memoria (solo quita). Devuelve
     * null si el spec no se puede leer (la pista no se toca).
     */
    static PendientesAsercion sincronizarRevisiones(SesionMcp sesion, Path salida) {
        PendientesAsercion p = pendientesAsercion(sesion, salida);
        if (p != null) {
            Set<String> ids = new LinkedHashSet<>(p.revisar());
            ids.addAll(p.reemplazar());
            sesion.verificaciones().retenerRevisiones(salida, ids);
        }
        return p;
    }

    /**
     * Siguiente paso de trabajo propio de la IA, o null si no queda nada: con decisión «probar», correcciones de
     * montaje, cambios autorizados sin aplicar y fallos de aserción sin veredicto (o, con {@code fallosAsercion:
     * preguntar}, sin preguntar o sin resolver); después, casos restantes del plan; por último (solo si
     * {@code !trasCierre}) verificaciones en curso o casos sin ejecutar.
     */
    static String pasoPendiente(SesionMcp sesion, Path proyecto, Path salida, boolean trasCierre) {
        Paso p = pasoDetallado(sesion, proyecto, salida, trasCierre);
        return p == null ? null : p.texto();
    }

    /** Tipo del siguiente paso pendiente de la IA (ver {@link #pasoDetallado}). */
    enum TipoPaso { MONTAJE, REEMPLAZAR, REVISAR, PREGUNTA, PLAN, CIERRE, ARREGLO }

    /** Siguiente paso pendiente: tipo, texto de {@link #pasoPendiente}, servicio (FQN, si aplica) e ids de casos afectados. */
    record Paso(TipoPaso tipo, String texto, String fqn, List<String> ids) {
    }

    /** Como {@link #pasoPendiente} con el tipo de paso, el servicio y los ids afectados; null si no queda nada. */
    static Paso pasoDetallado(SesionMcp sesion, Path proyecto, Path salida, boolean trasCierre) {
        boolean probar = HerramientaGenerar.DECISION_PROBAR.equals(sesion.decisionVerificacion());
        boolean alFinal = GuiaTanda.arreglosAlFinal(sesion, proyecto);
        Integer bloque = sesion.bloqueLlamada();
        if (bloque != null && sesion.ambito(proyecto) != null) {
            return pasoBloque(sesion, proyecto, salida, bloque, probar && alFinal);
        }
        if (salida != null && probar && !alFinal) {
            List<VerificacionesSesion.Correccion> cs = sesion.verificaciones().correcciones(salida);
            if (!cs.isEmpty()) {
                VerificacionesSesion.Correccion c = cs.get(0);
                String ids = LimitesRespuesta.listaCorta(c.ids(), MAX_IDS);
                int n = c.ids().size();
                return new Paso(TipoPaso.MONTAJE, "corrige " + (n == 1 ? "el caso" : "los " + n + " casos") + " de `"
                        + Textos.nombreSimple(c.fqn()) + "` que " + (n == 1 ? "no compila o falla" : "no compilan o fallan")
                        + " al montarse " + ids + ": estado_verificacion {} (errores y pistas) → corrige el spec → generar_tests con "
                        + "reemplazarCasos=[esos ids].",
                        c.fqn(), List.copyOf(c.ids()));
            }
            PendientesAsercion pa = pendientesAsercion(sesion, salida);
            if (pa != null && !pa.reemplazar().isEmpty()) {
                String ids = LimitesRespuesta.listaCorta(pa.reemplazar(), MAX_IDS);
                return new Paso(TipoPaso.REEMPLAZAR, "los casos " + ids + " tienen un cambio autorizado sin aplicar: corrige su "
                        + "esperado/aserciones en el spec según el código (estado_verificacion trae su spec) y llama a generar_tests "
                        + "con reemplazarCasos=" + ids + "; se vuelven a probar solos. Si no consigues corregirlo, registra dudoso "
                        + "con registrar_analisis_ia.", null, List.copyOf(pa.reemplazar()));
            }
            if (pa != null && !pa.revisar().isEmpty()) {
                if (revisionAutomatica(proyecto)) {
                    return new Paso(TipoPaso.REVISAR, instruccionRevisarIa(pa.revisar()), null, List.copyOf(pa.revisar()));
                }
                if (sesion.asercionesSinRespuesta(salida) == 0) {
                    boolean todosPreguntados = true;
                    for (String id : pa.revisar()) {
                        todosPreguntados &= sesion.asercionPreguntada(salida, id);
                    }
                    if (!todosPreguntados && !trasCierre) {
                        return new Paso(TipoPaso.PREGUNTA, "llama a ejecutar_tests sin argumentos: hará la pregunta única de "
                                + "fallos de aserción.", null, List.copyOf(pa.revisar()));
                    }
                    if (todosPreguntados) {
                        return new Paso(TipoPaso.PREGUNTA, "quedan " + pa.revisar().size() + " fallos de aserción sin resolver ("
                                + LimitesRespuesta.listaCorta(pa.revisar(), MAX_IDS) + "): aplica la decisión del usuario con "
                                + "resolver_fallo por caso (si eligió «" + REVISAR_IA + "», registrar_analisis_ia).", null,
                                List.copyOf(pa.revisar()));
                    }
                }
            }
        }
        SesionMcp.ProgresoPlan pp = sesion.progresoPlan(proyecto);
        if (pp != null && pp.restantes() > 0 && !sesion.ejecucionParcialAutorizada()) {
            return new Paso(TipoPaso.PLAN, "quedan " + pp.restantes() + (pp.restantes() == 1 ? " caso del plan (" : " casos del plan (")
                    + GuiaTanda.posicion(sesion, proyecto) + "): " + GuiaTanda.pasoServicio(sesion, proyecto, salida, null, null),
                    HerramientaGenerar.siguienteServicio(sesion, proyecto), List.of());
        }
        if (salida != null && probar && alFinal) {
            if (!trasCierre && !sesion.verificaciones().inactiva(salida)) {
                return new Paso(TipoPaso.CIERRE, "llama a ejecutar_tests sin argumentos para cerrar la tanda (repítela mientras "
                        + "devuelva verificando=true).", null, List.of());
            }
            List<Tanda.ArregloTanda> pendientes = sesion.arreglosPendientes(proyecto);
            if (!pendientes.isEmpty()) {
                List<String> ids = new ArrayList<>();
                for (Tanda.ArregloTanda a : pendientes) {
                    ids.add(a.caso);
                }
                int n = ids.size();
                return new Paso(TipoPaso.ARREGLO, "fase de arreglo: " + (n == 1 ? "queda 1 caso" : "quedan " + n + " casos")
                        + " por arreglar: llama a ejecutar_tests sin argumentos (devuelve los casos fallidos del siguiente servicio, "
                        + "con su error, pistas, spec y código) y corrígelos en una llamada a arreglar_caso.", pendientes.get(0).servicio, List.copyOf(ids));
            }
        }
        if (salida != null && probar && !trasCierre) {
            Object sinEjecutar = sesion.resumenSesion(proyecto, salida).get("sinEjecutar");
            if (!sesion.verificaciones().inactiva(salida) || (sinEjecutar instanceof Number n && n.intValue() > 0)) {
                return new Paso(TipoPaso.CIERRE, "llama a ejecutar_tests sin argumentos para cerrar la tanda (repítela mientras "
                        + "devuelva verificando=true).", null, List.of());
            }
        }
        return null;
    }

    /**
     * Siguiente paso de un trabajador de bloque: PLAN si quedan casos de su bloque; si el bloque no está terminado, ARREGLO
     * (casos del bloque por arreglar con las pruebas ya hechas) o CIERRE ({@code ejecutar_tests {bloque}}); terminado → null.
     */
    private static Paso pasoBloque(SesionMcp sesion, Path proyecto, Path salida, int bloque, boolean probarAlFinal) {
        SesionMcp.ProgresoPlan pp = sesion.progresoPlan(proyecto);
        if (pp != null && pp.restantes() > 0 && !sesion.ejecucionParcialAutorizada()) {
            return new Paso(TipoPaso.PLAN, "quedan " + pp.restantes() + (pp.restantes() == 1 ? " caso del plan (" : " casos del plan (")
                    + GuiaTanda.posicion(sesion, proyecto) + "): " + GuiaTanda.pasoServicio(sesion, proyecto, salida, null, null),
                    HerramientaGenerar.siguienteServicio(sesion, proyecto), List.of());
        }
        if (BloquesTanda.terminado(sesion, proyecto, bloque) || salida == null || !probarAlFinal) {
            return null;
        }
        String llamada = BloquesTanda.llamadaEjecutar(sesion);
        List<Tanda.ArregloTanda> pendientes = sesion.arreglosPendientes(proyecto);
        if (!pendientes.isEmpty() && sesion.verificaciones().inactiva(salida)) {
            List<String> ids = new ArrayList<>();
            for (Tanda.ArregloTanda a : pendientes) {
                ids.add(a.caso);
            }
            int n = ids.size();
            return new Paso(TipoPaso.ARREGLO, "fase de arreglo: " + (n == 1 ? "queda 1 caso" : "quedan " + n + " casos")
                    + " por arreglar: llama a " + llamada + " (devuelve los casos fallidos del siguiente servicio, con su error, pistas, "
                    + "spec y código) y corrígelos en una llamada a arreglar_caso.", pendientes.get(0).servicio, List.copyOf(ids));
        }
        return new Paso(TipoPaso.CIERRE, "llama a " + llamada + " para cerrar tu bloque (repítela mientras devuelva verificando=true).",
                null, List.of());
    }

    /**
     * Cierre condicional de una respuesta: si queda trabajo ({@link #pasoPendiente}), devuelve un nodo nuevo con
     * {@code accionRequerida} = {@link #ACCION_PENDIENTE_IA} + paso como primera clave seguida del cuerpo, sin
     * respuestaFinal; si no, añade {@code respuestaFinal} al cuerpo.
     */
    static ObjectNode conCierre(SesionMcp sesion, Path proyecto, Path salida, ObjectNode cuerpo) {
        String paso = pasoPendiente(sesion, proyecto, salida, false);
        if (paso == null) {
            cuerpo.put("respuestaFinal", sesion.respuestaFinal(proyecto, salida));
            return cuerpo;
        }
        ObjectNode r = Json.objeto();
        JsonNode previa = cuerpo.get("accionRequerida");
        String extra = previa != null && previa.isTextual() && !previa.asText().isBlank() ? " " + previa.asText() : "";
        r.put("accionRequerida", ACCION_PENDIENTE_IA + paso + extra);
        cuerpo.fields().forEachRemaining(e -> {
            if (!"accionRequerida".equals(e.getKey()) && !"respuestaFinal".equals(e.getKey())) {
                r.set(e.getKey(), e.getValue());
            }
        });
        if (r.get("resumenSesion") instanceof ObjectNode rs && rs.has("instruccion")) {
            rs.put("instruccion", RESUMEN_PENDIENTE_IA);
        }
        return r;
    }

    /** Respuesta sin cerrar: las pruebas en segundo plano siguen en marcha (isError=false, sin respuestaFinal). */
    private static Resultado verificando(SesionMcp sesion, Path proyecto, Path salida) {
        VerificacionesSesion.Progreso p = sesion.verificaciones().progreso(salida);
        ObjectNode r = Json.objeto();
        r.put("accionRequerida", "Las pruebas en segundo plano no han terminado (" + p.verificados() + " de " + p.total()
                + " servicios). Llama OTRA VEZ a ejecutar_tests sin argumentos (cada llamada espera hasta "
                + ESPERA_CIERRE.toSeconds() + " s). No generes nada más ni des el mensaje final todavía.");
        r.set("resumenSesion", Json.MAPPER.valueToTree(resumenPendiente(sesion, proyecto, salida)));
        r.put("verificando", true);
        ObjectNode pg = r.putObject("progreso");
        pg.put("serviciosConTests", p.total());
        pg.put("verificados", p.verificados());
        if (p.enCurso() != null) {
            pg.put("verificando", p.enCurso());
        } else {
            pg.putNull("verificando");
        }
        pg.put("enCola", p.enCola());
        LimitesRespuesta.acotarJson(r, LimitesRespuesta.PRESUPUESTO_CUERPO, List.of(), "El detalle completo está en informe.md.");
        return new Resultado(Json.compacto(r), false);
    }

    /**
     * Resultado agregado del cierre a partir de las verificaciones en segundo plano: resultados de los casos del alcance
     * con estado (el último resultado si coincide con el estado de sesión; si no, uno mínimo), clasificados con spec.json
     * (leído una vez) y los estados de sesión, con avisos, alertas y JDK de las verificaciones.
     */
    static ResultadoVerificacion agregado(SesionMcp sesion, Path salida, Set<String> alcance) {
        return agregado(sesion, salida, alcance, false);
    }

    /**
     * Como {@link #agregado(SesionMcp, Path, Set)}; con {@code revisionAutomatica} el texto de los fallos de aserción
     * indica a la IA que los revise ella con el código, sin preguntar al usuario.
     */
    static ResultadoVerificacion agregado(SesionMcp sesion, Path salida, Set<String> alcance, boolean revisionAutomatica) {
        VerificacionesSesion ver = sesion.verificaciones();
        ResultadoVerificacion rv = new ResultadoVerificacion();
        Map<String, String> estados = sesion.estadosDeSesion(salida);
        Spec spec = leerSpec(salida);
        List<String> montaje = new ArrayList<>();
        List<String> fallidos = new ArrayList<>();
        List<String> aserciones = new ArrayList<>();
        List<String> revisionIa = new ArrayList<>();
        List<String> ajustar = new ArrayList<>();
        int maxIntento = 0;
        int sinEstado = 0;
        Map<String, Long> cuenta = new LinkedHashMap<>();
        for (String id : alcance) {
            String estado = estados.get(id);
            if (estado == null) {
                sinEstado++;
            } else {
                ResultadoTest t = ver.ultimo(salida, id);
                if (t == null || !estado.equals(t.estado)) {
                    t = new ResultadoTest();
                    t.caso = id;
                    t.estado = estado;
                }
                t.deEstaSesion = true;
                rv.resultados.add(t);
                cuenta.merge(estado, 1L, Long::sum);
            }
            AlmacenSpec.Ubicacion u = spec == null ? null : AlmacenSpec.buscar(spec, id);
            CasoSpec c = u == null ? null : u.caso();
            if (c == null) {
                continue;
            }
            int intentos = c.intentosMontaje == null ? 0 : c.intentosMontaje;
            if ((ClasificadorFallos.ERROR_MONTAJE.equals(estado) || ClasificadorFallos.NO_COMPILA.equals(estado))
                    && ESTADO_SPEC_GENERADO.equals(c.estado) && intentos >= 1 && intentos < VerificadorTests.MAX_INTENTOS) {
                montaje.add(id);
                maxIntento = Math.max(maxIntento, intentos);
            }
            if (ESTADO_SPEC_MONTAJE_FALLIDO.equals(c.estado)) {
                fallidos.add(id);
            }
            if (CasoSpec.ESTADO_FALLO_ASERCION.equals(c.estado) && c.autorizacionCambio == null) {
                if (!CasoSpec.AUTORIZACION_ERROR_IA.equals(c.veredictoIa)) {
                    if (!sesion.asercionPreguntada(salida, id)) {
                        aserciones.add(id);
                    }
                } else if (ClasificadorFallos.FALLO_ASERCION.equals(estado)) {
                    revisionIa.add(id);
                }
            }
            if (CasoSpec.AUTORIZACION_AJUSTAR.equals(c.autorizacionCambio)) {
                ajustar.add(id);
            }
        }
        rv.intentoMontaje = maxIntento;
        rv.montajeReintentables.addAll(montaje);
        rv.asercionesPendientes.addAll(aserciones);
        rv.revisionIaPendiente.addAll(revisionIa);
        rv.accionRequerida = VerificadorTests.textoAcciones(montaje, maxIntento, fallidos, revisionIa, ajustar, aserciones,
                rv.contar(ClasificadorFallos.DESCONOCIDO) > 0, salida.resolve(EscritorSeguro.CARPETA_TMP), rv.todoPasa(),
                revisionAutomatica);
        rv.jdkUsado = ver.jdkUsado(salida);
        rv.avisos.addAll(ver.avisos(salida));
        if (sinEstado > 0) {
            rv.avisos.add(sinEstado + (sinEstado == 1 ? " caso no se pudo verificar" : " casos no se pudieron verificar") + " (ver avisos).");
        }
        List<String> alertas = ver.alertas(salida);
        if (!alertas.isEmpty()) {
            rv.alertaIntegridad = new ArrayList<>(alertas);
        }
        rv.resumen = rv.resultados.size() + " tests: " + cuenta;
        Path informe = salida.resolve(RutasSalida.INFORME);
        if (Files.isRegularFile(informe)) {
            rv.informe = informe.toString();
        }
        return rv;
    }

    /** Estados del caso en spec.json usados por la clasificación del cierre. */
    static final String ESTADO_SPEC_GENERADO = "generado";
    static final String ESTADO_SPEC_MONTAJE_FALLIDO = "montaje_fallido";

    static final String SIN_GENERAR_SIN_PLAN = "ejecutar_tests no ha ejecutado nada: en esta sesión no se ha generado ningún test (los "
            + "que haya en la carpeta son de sesiones anteriores y no cuentan). Llama a escanear_proyecto y, por cada servicio, a "
            + "detalle_servicio y generar_tests primero.";
    static final int MAX_PENDIENTES_PUERTA = 15;
    static final int MAX_LINEAS_CHAT = 8;
    static final int MAX_MENSAJE = 300;
    static final int MIN_PAGINA_RESULTADOS = 1_200;
    static final int RESERVA_PAGINACION = 150;
    static final String COMO_VER_RESULTADOS = "ejecutar_tests {paginaResultados: 2} devuelve la siguiente página de esta misma "
            + "ejecución sin volver a ejecutar";
    static final List<String> ORDEN_RECORTE = List.of("avisos", "jdkUsado", "resultados", "decisiones", "revisionIaPendiente",
            "asercionesPendientes", "montajeReintentables");

    /** Si el usuario elige Probar en la repregunta tras más de este tiempo de llamada, no se ejecuta en ella. */
    static final Duration MARGEN_EJECUCION = Duration.ofSeconds(30);
    static final String SIN_EJECUTAR_PROBAR = "El usuario eligió [Probar tests]; esta llamada agotó su tiempo esperando la respuesta "
            + "y no ha ejecutado nada. Llama AHORA otra vez a ejecutar_tests sin argumentos: ejecutará sin volver a preguntar.";
    static final String SIN_EJECUTAR_DESCARTAR = "El usuario eligió descartar: no se ha ejecutado nada ni se ejecutará salvo petición "
            + "expresa. Tu mensaje final es EXACTAMENTE respuestaFinal, tal cual.";
    static final String SIN_EJECUTAR_SIN_RESPUESTA = "El usuario no respondió a [Probar tests] [Descartar] (la pregunta quedó sin "
            + "responder): no se ha ejecutado nada. NO decidas por él ni vuelvas a llamar a ejecutar_tests por tu cuenta: tu mensaje "
            + "final es EXACTAMENTE respuestaFinal, tal cual. Cuando el usuario pida ejecutarlos, llama a ejecutar_tests sin argumentos "
            + "(volverá a preguntárselo).";
    static final String SIN_EJECUTAR_NO_DISPONIBLE = "No se pudo mostrar la pregunta [Probar tests] [Descartar] y no se ha ejecutado "
            + "nada. Pregúntala tú en el chat una sola vez y espera su respuesta: solo si responde «Probar tests» vuelve a llamar a "
            + "ejecutar_tests sin argumentos; si no, no ejecutes nada.";

    /**
     * Repite [Probar tests] [Descartar] tras una pregunta sin responder (va después de las puertas). Devuelve null
     * si se debe ejecutar ya (el usuario eligió Probar con margen), o el resultado sin ejecución en otro caso.
     * Sin respuesta nunca se fija la decisión de la sesión.
     */
    private static Resultado repreguntarVerificacion(SesionMcp sesion, Path proyecto, Path salida) {
        int n = sesion.casosDeSesion(salida).size();
        Elicitacion.Respuesta r = Elicitacion.consultar(sesion, "junit-gen ha generado " + n + " casos en " + salida
                + ". ¿Quieres compilarlos y ejecutarlos ahora (sin modificar el proyecto)? La decisión vale para todos los tests "
                + "de esta sesión.", List.of(HerramientaGenerar.PROBAR, HerramientaGenerar.DESCARTAR), Elicitacion.TIMEOUT);
        Elicitacion.Estado estado = r.estado();
        if (estado == Elicitacion.Estado.ELEGIDA && HerramientaGenerar.PROBAR.equals(r.opcion())) {
            sesion.fijarDecisionVerificacion(HerramientaGenerar.DECISION_PROBAR);
            if (sesion.transcurridoLlamada().compareTo(MARGEN_EJECUCION) <= 0) {
                return null;
            }
            return sinEjecutar(sesion, proyecto, salida, HerramientaGenerar.DECISION_PROBAR, SIN_EJECUTAR_PROBAR, false);
        }
        if (estado == Elicitacion.Estado.ELEGIDA || estado == Elicitacion.Estado.DECLINE || estado == Elicitacion.Estado.CANCEL) {
            sesion.fijarDecisionVerificacion(HerramientaGenerar.DECISION_DESCARTAR);
            return sinEjecutar(sesion, proyecto, salida, HerramientaGenerar.DECISION_DESCARTAR, SIN_EJECUTAR_DESCARTAR, true);
        }
        if (estado == Elicitacion.Estado.SIN_RESPUESTA || estado == Elicitacion.Estado.SIN_TIEMPO) {
            return sinEjecutar(sesion, proyecto, salida, HerramientaGenerar.DECISION_PENDIENTE_USUARIO, SIN_EJECUTAR_SIN_RESPUESTA, true);
        }
        sesion.olvidarVerificacionSinRespuesta();
        return sinEjecutar(sesion, proyecto, salida, HerramientaGenerar.DECISION_PENDIENTE, SIN_EJECUTAR_NO_DISPONIBLE, false);
    }

    /** Resultado sin ejecución (isError=false): resumenSesion, respuestaFinal (si procede), ejecutado=false y verificacion. */
    private static Resultado sinEjecutar(SesionMcp sesion, Path proyecto, Path salida, String decision, String instruccion,
                                         boolean conRespuestaFinal) {
        ObjectNode r = Json.objeto();
        r.set("resumenSesion", Json.MAPPER.valueToTree(sesion.resumenSesion(proyecto, salida)));
        if (conRespuestaFinal) {
            r.put("respuestaFinal", sesion.respuestaFinal(proyecto, salida));
        }
        r.put("ejecutado", false);
        ObjectNode v = r.putObject("verificacion");
        v.put("decision", decision);
        v.put("instruccion", instruccion);
        v.put("alcance", "sesion");
        LimitesRespuesta.acotarJson(r, LimitesRespuesta.PRESUPUESTO_CUERPO, List.of(), "El detalle completo está en informe.md.");
        return new Resultado(Json.compacto(r), false);
    }

    /** Error de puerta: texto breve + resumenSesion en JSON compacto. No ejecuta ni escribe nada. */
    private static Resultado rechazo(SesionMcp sesion, Path proyecto, Path salidaSesion, String texto) {
        return rechazo(sesion, proyecto, salidaSesion, texto, false);
    }

    /** Como el anterior; con {@code conRespuestaFinal} añade el markdown final exacto (plan completado sin nada que ejecutar). */
    private static Resultado rechazo(SesionMcp sesion, Path proyecto, Path salidaSesion, String texto, boolean conRespuestaFinal) {
        Map<String, Object> resumen = conRespuestaFinal ? sesion.resumenSesion(proyecto, salidaSesion)
                : resumenPendiente(sesion, proyecto, salidaSesion);
        return new Resultado(texto + "\n\nresumenSesion: " + Json.compacto(resumen)
                + (conRespuestaFinal ? "\n\nrespuestaFinal:\n" + sesion.respuestaFinal(proyecto, salidaSesion) : ""), true);
    }

    /** resumenSesion con instruccion = {@link #RESUMEN_PENDIENTE_IA} (respuestas sin respuestaFinal: queda trabajo). */
    private static Map<String, Object> resumenPendiente(SesionMcp sesion, Path proyecto, Path salida) {
        Map<String, Object> resumen = sesion.resumenSesion(proyecto, salida);
        if (resumen.containsKey("instruccion")) {
            resumen.put("instruccion", RESUMEN_PENDIENTE_IA);
        }
        return resumen;
    }

    /** «Pendiente: servicios [...]; métodos [...]» con listas acotadas. */
    static String pendientesPlan(SesionMcp sesion, Path proyecto, SesionMcp.ProgresoPlan pp) {
        List<String> metodos = new ArrayList<>();
        for (SesionMcp.EstadoMetodo m : sesion.estadoPlan(proyecto)) {
            if (m.faltan() > 0) {
                metodos.add(Textos.nombreSimple(m.fqn()) + "." + m.metodo() + " (faltan " + m.faltan() + ")");
            }
        }
        return "Pendiente: servicios " + LimitesRespuesta.listaCorta(pp.serviciosPendientes(), MAX_PENDIENTES_PUERTA) + "; métodos "
                + LimitesRespuesta.listaCorta(metodos, MAX_PENDIENTES_PUERTA) + ".";
    }

    /** Página de los resultados de la última ejecución de la sesión, sin volver a ejecutar. */
    private Resultado paginaCache(SesionMcp sesion, Path proyecto, Path salidaSesion, int pagina) {
        List<List<Map<String, Object>>> paginas = salidaSesion == null ? null : sesion.paginasResultados(salidaSesion);
        if (paginas == null) {
            throw new IllegalArgumentException("No hay ninguna ejecución de ejecutar_tests en esta sesión: paginaResultados solo "
                    + "pagina los resultados de la última ejecución.");
        }
        ObjectNode r = Json.objeto();
        String paso = pasoPendiente(sesion, proyecto, salidaSesion, true);
        if (paso != null) {
            r.put("accionRequerida", ACCION_PENDIENTE_IA + paso);
            ObjectNode resumen = Json.MAPPER.valueToTree(sesion.resumenSesion(proyecto, salidaSesion));
            if (resumen.has("instruccion")) {
                resumen.put("instruccion", RESUMEN_PENDIENTE_IA);
            }
            r.set("resumenSesion", resumen);
        } else {
            r.set("resumenSesion", Json.MAPPER.valueToTree(sesion.resumenSesion(proyecto, salidaSesion)));
            r.put("respuestaFinal", sesion.respuestaFinal(proyecto, salidaSesion));
        }
        int p = Math.min(pagina, paginas.size());
        r.set("resultados", Json.MAPPER.valueToTree(paginas.get(p - 1)));
        r.set("paginacion", paginacion(p, paginas.size()));
        if (pagina > paginas.size()) {
            r.put("nota", "Solo hay " + paginas.size() + (paginas.size() == 1 ? " página" : " páginas") + "; se muestra la última.");
        }
        LimitesRespuesta.acotarJson(r, LimitesRespuesta.PRESUPUESTO_CUERPO, List.of("resultados"), COMO_VER_RESULTADOS);
        return new Resultado(Json.compacto(r), false);
    }

    private static ObjectNode paginacion(int pagina, int paginas) {
        ObjectNode pg = Json.objeto();
        pg.put("pagina", pagina);
        pg.put("paginas", paginas);
        if (pagina < paginas) {
            pg.putObject("siguiente").put("paginaResultados", pagina + 1);
        } else {
            pg.putNull("siguiente");
        }
        return pg;
    }

    /**
     * Páginas de resultados compactos: la primera de como máximo {@code primera} bytes (lo que cabe en la
     * respuesta de la ejecución) y las demás de como máximo {@link LimitesRespuesta#PAGINA_RESULTADOS}.
     */
    static List<List<Map<String, Object>>> paginasResultados(List<Map<String, Object>> resultados, int primera) {
        List<List<Map<String, Object>>> r = new ArrayList<>();
        List<Map<String, Object>> actual = new ArrayList<>();
        int bytes = 0;
        for (Map<String, Object> m : resultados) {
            int b = LimitesRespuesta.bytes(Json.compacto(m)) + 1;
            int limite = r.isEmpty() ? primera : LimitesRespuesta.PAGINA_RESULTADOS;
            if (!actual.isEmpty() && bytes + b > limite) {
                r.add(actual);
                actual = new ArrayList<>();
                bytes = 0;
            }
            actual.add(m);
            bytes += b;
        }
        if (!actual.isEmpty() || r.isEmpty()) {
            r.add(actual);
        }
        return r;
    }

    /**
     * Vista compacta para el chat: resultados ordenados (los que no pasan primero, con mensaje y traza
     * recortados; los que pasan solo {caso, estado, deEstaSesion}), guardados en la sesión para
     * paginaResultados; la respuesta lleva la página 1 y se acota al presupuesto.
     */
    static ObjectNode vistaCompacta(SesionMcp sesion, Path proyecto, Path salida, ResultadoVerificacion rv) {
        ObjectNode arbol = Json.MAPPER.valueToTree(rv);
        List<Map<String, Object>> fallan = new ArrayList<>();
        List<Map<String, Object>> pasan = new ArrayList<>();
        for (ResultadoTest t : rv.resultados) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("caso", t.caso);
            if (ClasificadorFallos.PASA.equals(t.estado)) {
                m.put("estado", t.estado);
                m.put("deEstaSesion", t.deEstaSesion);
                pasan.add(m);
                continue;
            }
            m.put("clase", Textos.nombreSimple(t.clase));
            m.put("metodoTest", t.metodoTest);
            m.put("estado", t.estado);
            m.put("deEstaSesion", t.deEstaSesion);
            m.put("tipoError", t.tipoError);
            m.put("mensaje", LimitesRespuesta.recortar(t.mensaje, MAX_MENSAJE));
            m.put("pista", t.pista);
            m.put("traza", LimitesRespuesta.recortar(t.traza, MAX_MENSAJE));
            m.values().removeIf(v -> v == null);
            fallan.add(m);
        }
        List<Map<String, Object>> todos = new ArrayList<>(fallan);
        todos.addAll(pasan);
        arbol.remove("resultados");
        arbol.remove("versionesReales");
        ObjectNode r = Json.objeto();
        boolean propio = trabajoIaPendiente(sesion, salida, rv);
        String paso = propio ? null : pasoPendiente(sesion, proyecto, salida, true);
        if (propio || paso != null) {
            // Queda trabajo de la IA: sin respuestaFinal y con accionRequerida imperativa como primera clave.
            String accion = rv.accionRequerida == null ? "" : rv.accionRequerida;
            r.put("accionRequerida", ACCION_PENDIENTE_IA + (propio ? accion : paso + (accion.isBlank() ? "" : " " + accion)));
            JsonNode resumen = arbol.remove("resumenSesion");
            if (resumen instanceof ObjectNode on && on.has("instruccion")) {
                on.put("instruccion", RESUMEN_PENDIENTE_IA);
            }
            r.set("resumenSesion", resumen);
            arbol.fields().forEachRemaining(e -> {
                if (!"accionRequerida".equals(e.getKey()) && !"respuestaFinal".equals(e.getKey())) {
                    r.set(e.getKey(), e.getValue());
                }
            });
        } else {
            r.set("resumenSesion", arbol.remove("resumenSesion"));
            arbol.fields().forEachRemaining(e -> {
                if (!"accionRequerida".equals(e.getKey())) {
                    r.set(e.getKey(), e.getValue());
                }
            });
            if (rv.accionRequerida != null && !rv.accionRequerida.isBlank()) {
                r.put("accionRequerida", rv.accionRequerida);
            }
        }
        // Primero se acota el resto para dejar sitio a una página de resultados; la página 1 es lo que cabe.
        LimitesRespuesta.acotarJson(r, LimitesRespuesta.PRESUPUESTO_CUERPO - MIN_PAGINA_RESULTADOS - RESERVA_PAGINACION,
                ORDEN_RECORTE, COMO_VER_RESULTADOS);
        int libre = LimitesRespuesta.PRESUPUESTO_CUERPO - LimitesRespuesta.bytes(Json.compacto(r)) - RESERVA_PAGINACION;
        List<List<Map<String, Object>>> paginas = paginasResultados(todos,
                Math.max(MIN_PAGINA_RESULTADOS, Math.min(LimitesRespuesta.PAGINA_RESULTADOS, libre)));
        sesion.guardarPaginasResultados(salida, paginas);
        r.set("resultados", Json.MAPPER.valueToTree(paginas.get(0)));
        if (paginas.size() > 1) {
            r.set("paginacion", paginacion(1, paginas.size()));
        }
        LimitesRespuesta.acotarJson(r, LimitesRespuesta.PRESUPUESTO_CUERPO, ORDEN_RECORTE, COMO_VER_RESULTADOS);
        return r;
    }

    /** «esperado X, obtenido Y» si el mensaje encaja (JUnit 4/5, AssertJ); si no, el mensaje aplanado y recortado. */
    static String resumenAsercion(String mensaje) {
        if (mensaje == null || mensaje.isBlank()) {
            return "sin mensaje";
        }
        String plano = mensaje.replaceAll("\\s+", " ").trim();
        for (Pattern p : List.of(ESPERADO_OBTENIDO, ASSERTJ)) {
            Matcher m = p.matcher(plano);
            if (m.find() && !m.group(1).isBlank()) {
                return Textos.recortar("esperado " + m.group(1).trim() + ", obtenido " + m.group(2).trim(), MAX_RESUMEN);
            }
        }
        return Textos.recortar(plano, MAX_RESUMEN);
    }

    /**
     * Modo «preguntar» ({@code fallosAsercion: preguntar}): pregunta única sobre {@code asercionesPendientes} (nunca decide por el usuario). Añade los casos
     * persistidos en fallo_asercion (sin autorización ni veredicto error_ia) que no están en los resultados
     * de esta ejecución y aún no se preguntaron en la sesión. Si quedan casos de montaje reintentables no
     * pregunta (se preguntará al final). Si no, una sola elicitation con la lista (máximo
     * {@link #MAX_LINEAS} líneas) y las 5 opciones, aplicadas a todos los casos, y los marca como
     * preguntados. Decline, cancel o sin soporte dejan la misma pregunta para el chat en {@code accionRequerida}.
     */
    static void decidirAserciones(SesionMcp sesion, Path salida, ResultadoVerificacion rv) {
        decidirAserciones(sesion, salida, rv, null);
    }

    /** Como {@link #decidirAserciones(SesionMcp, Path, ResultadoVerificacion)}; con {@code soloCasos} solo añade persistidos de esos ids. */
    static void decidirAserciones(SesionMcp sesion, Path salida, ResultadoVerificacion rv, Set<String> soloCasos) {
        decidirAserciones(sesion, salida, rv, soloCasos, false);
    }

    /**
     * Como {@link #decidirAserciones(SesionMcp, Path, ResultadoVerificacion, Set)}; con {@code revisionAutomatica}
     * (modo {@code fallosAsercion: revisarIa}) no pregunta ni marca nada: deja cada caso pendiente en
     * {@code decisiones} como revisar_ia y la instrucción de revisión sin preguntar al usuario en accionRequerida
     * (también con casos de montaje reintentables, que van primero).
     */
    static void decidirAserciones(SesionMcp sesion, Path salida, ResultadoVerificacion rv, Set<String> soloCasos,
                                  boolean revisionAutomatica) {
        Spec spec = leerSpec(salida);
        anadirPersistidos(sesion, salida, rv, spec, soloCasos);
        List<String> pendientes = new ArrayList<>(rv.asercionesPendientes);
        if (pendientes.isEmpty()) {
            return;
        }
        String ids = LimitesRespuesta.listaCorta(pendientes, MAX_IDS);
        String base = rv.accionRequerida == null || rv.accionRequerida.isBlank() ? "" : rv.accionRequerida + " ";
        if (revisionAutomatica) {
            decisionesRevisarIa(rv, pendientes, fallosPorCaso(rv), spec);
            rv.accionRequerida = base + instruccionRevisarIa(pendientes);
            return;
        }
        if (!rv.montajeReintentables.isEmpty()) {
            rv.accionRequerida = base + "Los fallos de aserción " + ids + " se preguntarán al final, cuando no queden casos de "
                    + "montaje reintentables: corrige antes el montaje y vuelve a llamar a ejecutar_tests. No preguntes todavía.";
            return;
        }
        Map<String, ResultadoTest> porCaso = fallosPorCaso(rv);
        String lista = lista(pendientes, porCaso, spec, MAX_LINEAS);
        Elicitacion.Respuesta resp = Elicitacion.consultar(sesion, "Hay " + pendientes.size()
                + (pendientes.size() == 1 ? " caso que falla" : " casos que fallan")
                + " por aserción (pueden ser bugs reales del servicio):\n" + lista + "¿Qué hacemos con todos ellos?", OPCIONES,
                Elicitacion.TIMEOUT);
        if (resp.estado() == Elicitacion.Estado.SIN_RESPUESTA) {
            // Sin respuesta: nada se ajusta ni elimina, los casos siguen en fallo_asercion y la pregunta es repetible.
            sesion.marcarAsercionesSinRespuesta(salida, pendientes);
            pendientesEnChat(rv, pendientes);
            rv.accionRequerida = base + "El usuario no respondió a la pregunta sobre los fallos de aserción " + ids + " (quedó sin "
                    + "responder). NO decidas por él: los casos siguen en fallo_asercion, sin ajustar ni eliminar; no llames a "
                    + "resolver_fallo, registrar_analisis_ia ni a generar_tests con reemplazarCasos/eliminarCasos sobre ellos. Tu "
                    + "mensaje final es EXACTAMENTE respuestaFinal, tal cual. Cuando el usuario vuelva: si indica qué hacer, aplícalo "
                    + "con resolver_fallo por caso (ajustar y eliminar con confirmadoPorUsuario=true); si pide que se lo preguntes de "
                    + "nuevo, llama a ejecutar_tests con casos=" + ids + ".";
            return;
        }
        pendientes.forEach(c -> sesion.marcarAsercionPreguntada(salida, c));
        sesion.resolverAsercionesSinRespuesta(salida, pendientes);
        String r = resp.estado() == Elicitacion.Estado.ELEGIDA ? resp.opcion() : null;
        if (BUG.equals(r) || AJUSTAR.equals(r) || ELIMINAR.equals(r)) {
            String decision = BUG.equals(r) ? "bug" : AJUSTAR.equals(r) ? "ajustar" : "eliminar";
            for (String caso : pendientes) {
                aplicar(sesion, salida, rv, caso, decision);
            }
            rv.accionRequerida = base + "El usuario eligió [" + r + "] para " + ids + " (ver 'decisiones')."
                    + (AJUSTAR.equals(r) ? " Aplica las instrucciones de 'decisiones': corrige el esperado en el spec, llama a "
                    + "generar_tests con reemplazarCasos=" + ids + " y después a ejecutar_tests con casos=" + ids + "." : "");
        } else if (REVISAR_IA.equals(r)) {
            decisionesRevisarIa(rv, pendientes, porCaso, spec);
            rv.accionRequerida = base + instruccionRevisarIa(pendientes);
        } else if (REVISAR_EN_CHAT.equals(r)) {
            pendientesEnChat(rv, pendientes);
            rv.accionRequerida = base + "El usuario quiere revisar los casos " + ids + " uno a uno: pregúntale en el chat caso a "
                    + "caso (con su «esperado/obtenido») [Es un bug, mantener el test] [Ajustar lo esperado] [Eliminar caso] y llama a "
                    + "resolver_fallo por caso solo tras su respuesta (ajustar y eliminar con confirmadoPorUsuario=true). No decidas "
                    + "por el usuario.";
        } else {
            pendientesEnChat(rv, pendientes);
            rv.accionRequerida = base + "Pregunta al usuario en el chat, en un único mensaje, qué hacer con TODOS estos casos que fallan "
                    + "por aserción:\n" + lista(pendientes, porCaso, spec, MAX_LINEAS_CHAT) + "Opciones: [" + BUG + "] → resolver_fallo decision=bug por caso; [" + AJUSTAR
                    + "] → resolver_fallo decision=ajustar con confirmadoPorUsuario=true por caso y sigue sus instrucciones; [" + ELIMINAR + "] → resolver_fallo "
                    + "decision=eliminar con confirmadoPorUsuario=true por caso (la elección del usuario cuenta como confirmación "
                    + "explícita); ["
                    + REVISAR_IA + "] → " + instruccionRevisarIa(pendientes) + " [" + REVISAR_EN_CHAT + "] → pregunta caso a caso y "
                    + "llama a resolver_fallo por caso. No decidas por el usuario ni llames a resolver_fallo antes de su respuesta.";
        }
    }

    /**
     * Añade a {@code asercionesPendientes} los casos del spec.json en fallo_asercion sin autorización ni
     * veredicto error_ia que no aparecen en los resultados de esta ejecución y no se preguntaron en la sesión.
     */
    static void anadirPersistidos(SesionMcp sesion, Path salida, ResultadoVerificacion rv, Spec spec) {
        anadirPersistidos(sesion, salida, rv, spec, null);
    }

    /** Como {@link #anadirPersistidos(SesionMcp, Path, ResultadoVerificacion, Spec)}; con {@code soloCasos} (no null), solo esos ids. */
    static void anadirPersistidos(SesionMcp sesion, Path salida, ResultadoVerificacion rv, Spec spec, Set<String> soloCasos) {
        if (spec == null || spec.servicios == null) {
            return;
        }
        Set<String> enResultados = new LinkedHashSet<>();
        for (ResultadoTest t : rv.resultados) {
            if (t.caso != null) {
                enResultados.add(t.caso);
            }
        }
        for (ServicioSpec sv : spec.servicios) {
            if (sv == null || sv.casos == null) {
                continue;
            }
            for (CasoSpec c : sv.casos) {
                if (c == null || c.id == null || !CasoSpec.ESTADO_FALLO_ASERCION.equals(c.estado) || c.autorizacionCambio != null
                        || CasoSpec.AUTORIZACION_ERROR_IA.equals(c.veredictoIa) || enResultados.contains(c.id)
                        || rv.asercionesPendientes.contains(c.id) || sesion.asercionPreguntada(salida, c.id)
                        || (soloCasos != null && !soloCasos.contains(c.id))) {
                    continue;
                }
                rv.asercionesPendientes.add(c.id);
            }
        }
    }

    private static String lista(List<String> pendientes, Map<String, ResultadoTest> porCaso, Spec spec, int maxLineas) {
        StringBuilder msg = new StringBuilder();
        int mostrados = Math.min(maxLineas, pendientes.size());
        for (int i = 0; i < mostrados; i++) {
            String caso = pendientes.get(i);
            ResultadoTest t = porCaso.get(caso);
            msg.append("- ").append(caso);
            if (t != null) {
                msg.append(" (").append(Textos.nombreSimple(t.clase)).append('.').append(t.metodoTest).append("): ")
                        .append(resumenAsercion(t.mensaje));
            } else {
                AlmacenSpec.Ubicacion u = spec == null ? null : AlmacenSpec.buscar(spec, caso);
                if (u != null) {
                    msg.append(" (").append(Textos.nombreSimple(u.servicio().clase)).append('.').append(u.caso().metodo).append(')');
                }
                msg.append(": pendiente de una ejecución anterior");
            }
            msg.append('\n');
        }
        if (pendientes.size() > mostrados) {
            msg.append("… y ").append(pendientes.size() - mostrados).append(" más (ver informe.md)\n");
        }
        return msg.toString();
    }

    /** Primer resultado con fallo de aserción de cada caso de la ejecución. */
    private static Map<String, ResultadoTest> fallosPorCaso(ResultadoVerificacion rv) {
        Map<String, ResultadoTest> porCaso = new LinkedHashMap<>();
        for (ResultadoTest t : rv.resultados) {
            if (ClasificadorFallos.FALLO_ASERCION.equals(t.estado) && t.caso != null) {
                porCaso.putIfAbsent(t.caso, t);
            }
        }
        return porCaso;
    }

    /** Añade a {@code rv.decisiones} una entrada revisar_ia (con servicio, método y método de test si se conocen) por caso. */
    private static void decisionesRevisarIa(ResultadoVerificacion rv, List<String> casos, Map<String, ResultadoTest> porCaso,
                                            Spec spec) {
        for (String caso : casos) {
            ResultadoTest t = porCaso.get(caso);
            AlmacenSpec.Ubicacion u = spec == null ? null : AlmacenSpec.buscar(spec, caso);
            Map<String, String> d = new LinkedHashMap<>();
            d.put("caso", caso);
            d.put("decision", DECISION_REVISAR_IA);
            if (u != null) {
                d.put("servicio", u.servicio().clase);
                d.put("metodo", u.caso().metodo);
            }
            if (t != null) {
                d.put("metodoTest", t.metodoTest);
            }
            rv.decisiones.add(d);
        }
    }

    static String instruccionRevisarIa(List<String> casos) {
        return "Fallos de aserción " + LimitesRespuesta.listaCorta(casos, MAX_IDS) + ": revísalos TÚ ahora, SIN preguntar al "
                + "usuario (no uses ask_user ni preguntes en el chat). 1) estado_verificacion {} (trae cada caso con su error y su "
                + "spec). 2) Por cada caso relee el código real con detalle_servicio {clase, metodos: [método del caso]}. 3) Decide "
                + "con un motivo que cite el código (la condición, cálculo o línea que produce el valor): error_ia = el test está "
                + "mal planteado (el código es correcto y hace otra cosa); bug_servicio = el código contradice lo que la función "
                + "debe hacer (nombre, contrato, documentación, validaciones); dudoso solo si no puedes decidir. 4) Registra TODOS "
                + "los veredictos en UNA llamada a registrar_analisis_ia y sigue su accionRequerida (error_ia: corregir el spec y "
                + "generar_tests con reemplazarCasos; máximo 3 intentos por caso). Prohibido poner como esperado el valor obtenido "
                + "sin justificarlo con el código. Nunca toques el proyecto.";
    }

    private static void pendientesEnChat(ResultadoVerificacion rv, List<String> casos) {
        for (String caso : casos) {
            Map<String, String> d = new LinkedHashMap<>();
            d.put("caso", caso);
            d.put("decision", DECISION_PENDIENTE);
            rv.decisiones.add(d);
        }
    }

    private static Spec leerSpec(Path salida) {
        try {
            return AlmacenSpec.leer(salida);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static void aplicar(SesionMcp sesion, Path salida, ResultadoVerificacion rv, String caso, String decision) {
        Map<String, String> d = new LinkedHashMap<>();
        d.put("caso", caso);
        d.put("decision", decision);
        Map<String, Object> res = sesion.fachada().resolverFallo(salida, caso, decision, true);
        if ("eliminar".equals(decision)) {
            sesion.olvidarCaso(salida, caso);
        }
        Object instr = res.get("instruccion");
        if (instr != null) {
            d.put("instruccion", instr.toString());
        }
        rv.decisiones.add(d);
    }
}
