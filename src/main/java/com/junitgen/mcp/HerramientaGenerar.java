package com.junitgen.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.junitgen.Fachada;
import com.junitgen.config.CargadorConfig;
import com.junitgen.config.ConfigProyecto;
import com.junitgen.generacion.GeneradorTests;
import com.junitgen.generacion.ResultadoGeneracion;
import com.junitgen.generacion.Tanda;
import com.junitgen.duplicados.Duplicado;
import com.junitgen.escaneo.ProyectoEscaneado;
import com.junitgen.seguridad.RutaSegura;
import com.junitgen.spec.ErrorValidacion;
import com.junitgen.spec.LectorSpec;
import com.junitgen.spec.ResultadoValidacion;
import com.junitgen.util.Json;
import com.junitgen.util.LimitesRespuesta;
import com.junitgen.util.Recursos;
import com.junitgen.util.Textos;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * generar_tests: escribe los tests en la carpeta de salida. Por defecto los tests se prueban siempre, sin
 * preguntar (decisión «probar» de toda la sesión); solo con {@code verificacion: preguntar} en
 * junit-gen.yml ofrece probarlos por elicitation una sola vez por sesión, y con {@code verificacion: nunca}
 * se descartan.
 */
public class HerramientaGenerar implements Herramienta {

    public static final String PROBAR = "Probar tests";
    public static final String DESCARTAR = "Descartar";
    public static final String DECISION_PROBAR = "probar";
    public static final String DECISION_DESCARTAR = "descartar";
    public static final String DECISION_PENDIENTE = "pendiente";
    /** Aviso de calidad de un caso de la fase de arreglo corregido con generar_tests en lugar de arreglar_caso. */
    static final String AVISO_SIN_GUARDIA = "Corregido con generar_tests (sin la comprobación de calidad de arreglar_caso)";
    public static final String DECISION_SIN_TESTS = "sin_tests";
    public static final String DECISION_PENDIENTE_USUARIO = "pendiente_usuario";

    static final int MAX_PENDIENTES = 20;
    static final String INSTRUCCION_PROBAR = "Estos tests se están probando en segundo plano: no esperes ni llames ahora a "
            + "ejecutar_tests; sigue la primera línea accionRequerida. No corrijas nada ahora: los casos que fallen se apuntan solos "
            + "y se arreglan al final, en la fase de arreglo (solo con `arreglos: inmediato` se corrigen al llegar la nota "
            + "«Verificación en segundo plano…»).";
    static final String INSTRUCCION_DESCARTAR = "No preguntes ni ejecutes salvo petición expresa.";
    static final String INSTRUCCION_PENDIENTE = "Pregunta una vez en el chat [Probar tests] [Descartar] y pasa la respuesta en "
            + "`decisionVerificacion` (en la siguiente llamada a generar_tests; si ya no quedan servicios por generar y el usuario "
            + "elige probar, llama una sola vez a ejecutar_tests).";
    static final String INSTRUCCION_PENDIENTE_USUARIO = "El usuario no respondió a [Probar tests] [Descartar] (la pregunta quedó "
            + "sin responder). NO decidas por él: no pases decisionVerificacion ni des por hecho que quiere probar o descartar. Los "
            + "tests SÍ están generados (ver resumenSesion): nunca digas que no se generaron ni repitas generar_tests con el mismo "
            + "spec. Si quedan casos del plan, sigue plan.instruccion; cuando no quede nada por generar llama UNA vez a "
            + "ejecutar_tests sin argumentos (junit-gen volverá a preguntárselo) o termina mostrando EXACTAMENTE respuestaFinal, tal cual.";

    @Override
    public String nombre() {
        return "generar_tests";
    }

    @Override
    public String titulo() {
        return "Generar tests";
    }

    @Override
    public String descripcion() {
        return "Genera (o amplía) un test por servicio en carpetaSalida a partir del spec (lo valida: validar_spec es opcional; "
                + "si devuelve errores, corrige y repite), con la plantilla adecuada "
                + "a JUnit 4/5, Mockito y AssertJ del proyecto. Guarda spec.json e informe.md. Solo añade casos nuevos; nunca "
                + "escribe en el proyecto. Sin 'carpetaSalida' usa la elegida en la sesión (o la pregunta si aún no se preguntó). "
                + "Llámala para cada servicio (o con varios servicios del mismo módulo en un spec) sin llamar a ejecutar_tests "
                + "entre ellos. No pregunta si probar los tests: devuelve verificacion.decision=probar (decisión de toda la "
                + "sesión, verificacion.alcance=sesion; también con plan) y encola la prueba de esos servicios en segundo plano: "
                + "responde enseguida, no esperes ni sondees y sigue con el siguiente servicio. No corrijas nada durante la tanda: "
                + "los casos que fallen se apuntan solos y se arreglan al final (fase de arreglo de ejecutar_tests, con "
                + "arreglar_caso); solo con `arreglos: inmediato` se corrigen al llegar la nota «Verificación en segundo plano…». Al terminar "
                + "todos los servicios llama a ejecutar_tests sin argumentos (repítela mientras devuelva verificando=true). Solo si "
                + "junit-gen.yml del proyecto indica `verificacion: nunca` devuelve 'descartar' (no se prueba nada ni ejecutes) y "
                + "con `verificacion: preguntar` pregunta una vez [Probar tests] [Descartar]. "
                + "Con un plan de escanear_proyecto(numServicios o servicios) activo la respuesta es compacta y empieza por la "
                + "línea accionRequerida «NO HAS TERMINADO (servicio k de N): llama ahora a …» (el paso exacto: completar ESE "
                + "servicio con los casos metodo=N que falten, o empezar el siguiente; si trae siguienteServicio {clase, candidatas}, "
                + "elige de ahí y llama a seleccionar_funciones con funciones): hazla sin escribir nada al usuario hasta "
                + "plan.restantes = 0. Trae 'plan' {total, generados, restantes, servicios, servicioEnCurso, faltan}; "
                + "servicioCerrado = servicio atascado que junit-gen cierra solo (no insistas). resumenSesion y respuestaFinal "
                + "solo llegan cuando no queda nada pendiente o no se van a ejecutar los tests. Cada caso nuevo va sobre una función listada por detalle_servicio (las elegidas con "
                + "seleccionar_funciones o por el usuario; sin elección, la selección automática de junit-gen), sin superar su maxCasos, y con "
                + "queHaceOriginal y queComprueba (1 frase, máx. 160 caracteres; se escriben como comentario del test); junit-gen "
                + "rechaza lo demás (FUNCION_NO_SELECCIONADA, EXCESO_CASOS, FALTA_COMENTARIO). Rechaza sin escribir nada (REQUIERE_DECISION_USUARIO) "
                + "reemplazar o eliminar casos en fallo_asercion, posible_bug o dudoso sin veredicto error_ia registrado con "
                + "registrar_analisis_ia (o decisión del usuario con `fallosAsercion: preguntar`): nunca los incluyas en "
                + "reemplazarCasos/eliminarCasos por tu cuenta. "
                + "Solo con `verificacion: preguntar`: si esa pregunta queda sin responder (caduca), devuelve "
                + "verificacion.decision=pendiente_usuario con respuestaFinal: no decidas por el usuario; ejecutar_tests la repite.";
    }

    @Override
    public ObjectNode esquema() {
        ObjectNode s = Herramienta.esquemaBase();
        ObjectNode props = (ObjectNode) s.get("properties");
        ObjectNode spec = Recursos.esquemaSpec();
        spec.put("description", "Spec JSON v1 validado (objeto, o cadena con el JSON).");
        props.set("spec", spec);
        Herramienta.propiedad(s, "rutaSpec", "string", "Alternativa: ruta de un archivo con el spec.", false);
        Herramienta.propiedad(s, "carpetaSalida", "string", "Carpeta de salida (fuera del proyecto); si se omite, la elegida en la sesión.", false);
        Herramienta.propiedad(s, "guardarRutaPorDefecto", "boolean", "Con carpetaSalida elegida en el chat: el usuario pidió guardarla como ruta por defecto del proyecto.", false);
        Herramienta.propiedad(s, "decisionVerificacion", "string", "Solo si el usuario indicó expresamente en el chat que quiere "
                + "probar o no ejecutar (descartar) los tests; se fija para toda la sesión. No la pases por tu cuenta.", false);
        ((ObjectNode) props.get("decisionVerificacion")).putArray("enum").add(DECISION_PROBAR).add(DECISION_DESCARTAR);
        Herramienta.propiedad(s, "reemplazarCasos", "array", "Ids de casos ya generados que se deben reescribir (tras corregir el "
                + "spec). Casos con fallo de aserción, posible_bug o dudoso: solo con veredicto error_ia registrado con "
                + "registrar_analisis_ia (o decisión del usuario con `fallosAsercion: preguntar`); si no, se rechaza toda la llamada.", false);
        Herramienta.propiedad(s, "eliminarCasos", "array", "Ids de casos generados a eliminar (requiere confirmadoPorUsuario). "
                + "Nunca casos con fallo de aserción, posible_bug o dudoso: esos solo se eliminan con resolver_fallo si el usuario "
                + "lo pide expresamente.", false);
        Herramienta.propiedad(s, "confirmadoPorUsuario", "boolean", "El usuario ha confirmado explícitamente la eliminación.", false);
        return s;
    }

    @Override
    public boolean soloLectura() {
        return false;
    }

    @Override
    public String aviso(JsonNode args, SesionMcp sesion) {
        return RutasSesion.aviso(args, sesion, null, "carpetaSalida", "(proyecto del spec)");
    }

    @Override
    public Resultado ejecutar(JsonNode args, SesionMcp sesion) {
        String decisionArg = Herramienta.texto(args, "decisionVerificacion");
        if (decisionArg != null) {
            String d = decisionArg.trim().toLowerCase(Locale.ROOT);
            if (!DECISION_PROBAR.equals(d) && !DECISION_DESCARTAR.equals(d)) {
                throw new IllegalArgumentException("decisionVerificacion debe ser 'probar' o 'descartar'.");
            }
            sesion.fijarDecisionVerificacion(d);
        }
        GeneradorTests.Opciones op = new GeneradorTests.Opciones();
        op.reemplazar = new LinkedHashSet<>(Herramienta.lista(args, "reemplazarCasos"));
        op.eliminar = new LinkedHashSet<>(Herramienta.lista(args, "eliminarCasos"));
        op.confirmado = Herramienta.bool(args, "confirmadoPorUsuario");
        Path rutaSpec = Herramienta.ruta(args, "rutaSpec");
        LectorSpec.Lectura lectura = sesion.fachada().leerSpec(args.get("spec"), rutaSpec);
        Path proyecto = null;
        Path salida = null;
        if (lectura.ok()) {
            proyecto = RutaSegura.validarProyecto(RutasSesion.proyecto(args, sesion, null, false));
            salida = RutasSesion.salida(proyecto, Herramienta.ruta(args, "carpetaSalida"), sesion, true,
                    Herramienta.bool(args, "guardarRutaPorDefecto"));
        }
        op.todas = proyecto != null && sesion.todas(proyecto);
        op.selecciones = proyecto == null ? Map.of() : sesion.selecciones(proyecto);
        // Casos acreditados por servicio antes de generar (salvaguarda anti-bucle); las correcciones no cuentan.
        Map<String, Integer> antes = sesion.acreditadosPorServicio(proyecto != null ? proyecto : sesion.fachada().ultimaRuta());
        boolean contar = op.reemplazar.isEmpty() && op.eliminar.isEmpty();
        Fachada.Generacion gen = sesion.fachada().generar(args.get("spec"), rutaSpec, salida, proyecto, op);
        ResultadoGeneracion g = gen.resultado();
        Path proyectoResumen = proyecto != null ? proyecto : sesion.fachada().ultimaRuta();
        Path salidaResumen = gen.escritor() != null ? gen.escritor().salida() : salida != null ? salida : sesion.salidaDe(proyectoResumen);
        if (!g.errores.isEmpty()) {
            Resultado cerrado = contar ? cerrarSiAtascado(sesion, proyectoResumen, salidaResumen, gen, antes) : null;
            if (cerrado != null) {
                return cerrado;
            }
            g.verificacion.put("decision", DECISION_PENDIENTE);
            boolean guardia = g.errores.stream().anyMatch(e -> GeneradorTests.COD_DECISION_USUARIO.equals(e.codigo));
            g.verificacion.put("instruccion", guardia ? GeneradorTests.SUGERENCIA_DECISION
                    : "Corrige los errores del spec y vuelve a llamar a generar_tests (valida igual que validar_spec).");
            if (!GuiaTanda.activa(sesion, proyectoResumen)) {
                g.resumenSesion = sesion.resumenSesion(proyectoResumen, salidaResumen);
            }
            return new Resultado(Json.compacto(vistaCompacta(g)), true);
        }
        boolean hayTests = !g.archivosCreados.isEmpty() || !g.archivosAmpliados.isEmpty();
        registrarEnSesion(sesion, gen);
        List<String> cerrados = progresoPlan(sesion, proyecto, gen, antes, contar);
        String decision = sesion.decisionVerificacion();
        if (!hayTests) {
            if (decision == null && !sesion.verificacionSinRespuesta()) {
                String instr = "No se ha generado ni ampliado ningún test en esta llamada; no hay nada nuevo que probar.";
                int previos = sesion.casosDeSesion(salidaResumen).size();
                if (previos > 0) {
                    instr += " Los " + previos + " casos ya generados en esta sesión siguen escritos (ver resumenSesion): no digas "
                            + "que no se generaron tests.";
                }
                g.verificacion.put("decision", DECISION_SIN_TESTS);
                g.verificacion.put("instruccion", instr);
                g.verificacion.put("alcance", "sesion");
            } else {
                rellenar(g.verificacion, decision, sesion.verificacionSinRespuesta());
            }
            instruccionPlan(sesion, proyecto, g, decision);
            g.resumenSesion = sesion.resumenSesion(proyectoResumen, salidaResumen);
            respuestaFinalSiNoHayVerificacion(sesion, proyectoResumen, salidaResumen, g, decision);
            guardarTanda(sesion, proyecto, gen, null);
            ObjectNode vista = vistaCompacta(g);
            if (g.respuestaFinal == null && GuiaTanda.activa(sesion, proyecto)) {
                compactarEnTanda(vista, sesion, proyecto, cerrados);
            }
            anadirSiguiente(vista, sesion, proyecto);
            return new Resultado(Json.compacto(vista), false);
        }
        ConfigProyecto.ModoVerificacion modo = CargadorConfig.modoVerificacion(proyecto);
        if (decision == null && modo != ConfigProyecto.ModoVerificacion.PREGUNTAR) {
            // Por defecto (siempre) se prueban sin preguntar; con «nunca» se descartan. Decisión de toda la sesión.
            decision = modo == ConfigProyecto.ModoVerificacion.NUNCA ? DECISION_DESCARTAR : DECISION_PROBAR;
            sesion.fijarDecisionVerificacion(decision);
        } else if (decision == null && !sesion.verificacionPreguntada()) {
            sesion.marcarVerificacionPreguntada();
            Elicitacion.Respuesta r = Elicitacion.consultar(sesion, "junit-gen ha generado " + g.casosGenerados + " casos en " + salida
                    + ". ¿Quieres compilarlos y ejecutarlos (sin modificar el proyecto)? La decisión vale para todos los tests de "
                    + "esta sesión: si eliges probar, se ejecutarán una sola vez al terminar de generar todos los servicios.",
                    List.of(PROBAR, DESCARTAR), Elicitacion.TIMEOUT);
            Elicitacion.Estado estado = r.estado();
            if (estado == Elicitacion.Estado.ELEGIDA && PROBAR.equals(r.opcion())) {
                decision = DECISION_PROBAR;
                sesion.fijarDecisionVerificacion(decision);
            } else if (estado == Elicitacion.Estado.ELEGIDA || estado == Elicitacion.Estado.DECLINE
                    || estado == Elicitacion.Estado.CANCEL) {
                decision = DECISION_DESCARTAR;
                sesion.fijarDecisionVerificacion(decision);
            } else if (estado == Elicitacion.Estado.SIN_RESPUESTA || estado == Elicitacion.Estado.SIN_TIEMPO) {
                // Sin respuesta nunca es consentimiento ni rechazo: la decisión queda pendiente del usuario.
                sesion.marcarVerificacionSinRespuesta();
            }
        }
        rellenar(g.verificacion, decision, sesion.verificacionSinRespuesta());
        if (DECISION_PROBAR.equals(decision) && gen.escritor() != null) {
            // Verificación de estos servicios en segundo plano: la IA sigue con el siguiente sin esperar.
            Set<String> fqns = new LinkedHashSet<>();
            for (Map<String, String> m : g.generados) {
                if (m.get("servicio") != null) {
                    fqns.add(m.get("servicio"));
                }
            }
            // Servicios remontados: sus casos existentes se vuelven a probar con el montaje nuevo.
            fqns.addAll(g.remontados.keySet());
            // Se vuelven a probar con el montaje nuevo: sin estados viejos en puerta y cabecera.
            for (List<String> idsRemontados : g.remontados.values()) {
                sesion.olvidarEstados(gen.escritor().salida(), idsRemontados);
            }
            if (!op.reemplazar.isEmpty() && ArreglosFinal.enFase(sesion, proyecto)) {
                // Fase de arreglo: corregir con reemplazarCasos también cuenta como intento del caso apuntado.
                Map<String, List<String>> porServicio = new LinkedHashMap<>();
                for (Map<String, String> m : g.generados) {
                    String id = m.get("caso");
                    if (id == null || !op.reemplazar.contains(id)) {
                        continue;
                    }
                    Tanda.ArregloTanda a = sesion.arreglo(proyecto, id);
                    if (a != null && Tanda.ARREGLO_PENDIENTE.equals(a.estado) && a.servicio != null) {
                        porServicio.computeIfAbsent(a.servicio, k -> new ArrayList<>()).add(id);
                        sesion.actualizarArreglo(proyecto, id, x -> {
                            if (x == null) {
                                return null;
                            }
                            List<String> avisos = new ArrayList<>(x.avisosCalidad == null ? List.of() : x.avisosCalidad);
                            if (!avisos.contains(AVISO_SIN_GUARDIA) && avisos.size() < ArreglosFinal.MAX_AVISOS_CALIDAD) {
                                avisos.add(AVISO_SIN_GUARDIA);
                            }
                            x.avisosCalidad = avisos;
                            return x;
                        });
                    }
                }
                for (Map.Entry<String, List<String>> en : porServicio.entrySet()) {
                    ArreglosFinal.aplicada(sesion, proyecto, gen.escritor().salida(), en.getKey(), en.getValue());
                }
            }
            sesion.verificaciones().encolar(proyecto, gen.escritor().salida(), fqns);
        }
        instruccionPlan(sesion, proyecto, g, decision);
        g.resumenSesion = sesion.resumenSesion(proyectoResumen, salidaResumen);
        respuestaFinalSiNoHayVerificacion(sesion, proyectoResumen, salidaResumen, g, decision);
        guardarTanda(sesion, proyecto, gen, Boolean.FALSE);
        ObjectNode vista = vistaCompacta(g);
        if (g.respuestaFinal == null && GuiaTanda.activa(sesion, proyecto)) {
            compactarEnTanda(vista, sesion, proyecto, cerrados);
        }
        anadirSiguiente(vista, sesion, proyecto);
        return new Resultado(Json.compacto(vista), false);
    }

    /** Registra en la sesión los casos generados, los descartados por duplicado y los eliminados de una generación correcta. */
    static void registrarEnSesion(SesionMcp sesion, Fachada.Generacion gen) {
        if (gen == null || gen.escritor() == null) {
            return;
        }
        ResultadoGeneracion g = gen.resultado();
        boolean hayTests = !g.archivosCreados.isEmpty() || !g.archivosAmpliados.isEmpty();
        Map<String, String> casos = new LinkedHashMap<>();
        for (Map<String, String> m : g.generados) {
            if (m.get("caso") != null) {
                casos.put(m.get("caso"), m.get("servicio"));
            }
        }
        List<String> duplicados = new ArrayList<>();
        for (Duplicado d : g.descartados) {
            if (d.caso != null) {
                duplicados.add(d.caso);
            }
        }
        sesion.registrarGenerados(gen.escritor().salida(), hayTests ? clasesDeTest(g) : List.of(), casos, duplicados, g.eliminados);
    }

    /**
     * Con plan y casos restantes, sin correcciones de montaje pendientes y si el siguiente servicio necesita selección:
     * añade {@code siguienteServicio {clase, candidatas}} solo si el bloque entero cabe en el presupuesto del cuerpo.
     */
    static void anadirSiguiente(ObjectNode vista, SesionMcp sesion, Path proyecto) {
        if (vista == null || proyecto == null) {
            return;
        }
        try {
            SesionMcp.ProgresoPlan pp = sesion.progresoPlan(proyecto);
            if (pp == null || pp.restantes() <= 0) {
                return;
            }
            Path salida = sesion.salidaDe(proyecto);
            if (salida != null && sesion.verificaciones().puertaActiva(salida)) {
                return;
            }
            String siguiente = siguienteServicio(sesion, proyecto);
            if (siguiente == null || !sesion.necesitaSeleccion(proyecto, salida, siguiente)) {
                return;
            }
            ProyectoEscaneado.Encontrada en = sesion.fachada().localizarClase(siguiente, proyecto, null,
                    sesion.modulosTrabajo(proyecto));
            String bloque = HerramientaEscanear.bloqueCandidatas(en.modulo(), en.clase());
            int libre = LimitesRespuesta.PRESUPUESTO_CUERPO - LimitesRespuesta.bytes(Json.compacto(vista));
            if (LimitesRespuesta.bytesJson(bloque) + 80 <= libre) {
                ObjectNode sig = vista.putObject("siguienteServicio");
                sig.put("clase", siguiente);
                sig.put("candidatas", bloque);
            }
        } catch (RuntimeException e) {
            System.err.println("generar_tests: no se pudieron adjuntar las candidatas del siguiente servicio: "
                    + e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage()));
        }
    }

    /**
     * Vuelca la tanda a tanda.json tras registrar con éxito (la crea en disco si la salida se eligió después del
     * escaneo). Con tests nuevos la tanda deja de estar cerrada.
     */
    private static void guardarTanda(SesionMcp sesion, Path proyecto, Fachada.Generacion gen, Boolean cerrada) {
        if (proyecto != null && gen.escritor() != null) {
            sesion.guardarTanda(proyecto, gen.escritor().salida(), cerrada);
        }
    }

    /**
     * Fija {@code respuestaFinal} solo si no habrá verificación: decisión «descartar» y (sin plan o plan
     * completo), o plan completo sin ningún caso escrito en la sesión.
     */
    static void respuestaFinalSiNoHayVerificacion(SesionMcp sesion, Path proyecto, Path salida, ResultadoGeneracion g,
                                                  String decision) {
        SesionMcp.ProgresoPlan p = sesion.progresoPlan(proyecto);
        boolean fin = (DECISION_DESCARTAR.equals(decision) && (p == null || p.restantes() == 0))
                || (p != null && p.restantes() == 0 && p.casosEscritos() == 0)
                || (decision == null && sesion.verificacionSinRespuesta());
        if (fin && HerramientaEjecutar.pasoPendiente(sesion, proyecto, salida, false) == null) {
            g.respuestaFinal = sesion.respuestaFinal(proyecto, salida);
        }
    }

    static final int MAX_MOTIVO_DESCARTE = 150;
    static final List<String> ORDEN_RECORTE = List.of("avisos", "noReescritos", "requiereRefactor", "descartados",
            "casosGeneradosIds", "archivosAmpliados", "archivosCreados", "errores");
    static final String COMO_VER_RESTO = "Las cifras de resumenSesion y plan son completas; el detalle está en informe.md (no lo "
            + "leas salvo que el usuario lo pida).";

    /**
     * Vista compacta para el chat: ids generados en lugar de la lista completa, contadores de noTocados y
     * omitidos, sin perfiles, descartados resumidos y acotada al presupuesto.
     */
    static ObjectNode vistaCompacta(ResultadoGeneracion g) {
        ObjectNode r = Json.MAPPER.valueToTree(g);
        r.remove("generados");
        r.remove("perfiles");
        ArrayNode ids = Json.lista();
        g.generados.forEach(m -> ids.add(m.get("caso")));
        r.set("casosGeneradosIds", ids);
        r.put("noTocados", g.noTocados.size());
        r.put("omitidos", g.omitidos.size());
        if (!g.remontados.isEmpty()) {
            r.put("remontados", g.remontados.size());
        }
        ArrayNode desc = Json.lista();
        for (Duplicado d : g.descartados) {
            ObjectNode o = desc.addObject();
            o.put("caso", d.caso);
            o.put("nivel", d.nivel);
            o.put("motivo", LimitesRespuesta.recortar(d.motivo, MAX_MOTIVO_DESCARTE));
        }
        r.set("descartados", desc);
        LimitesRespuesta.acotarJson(r, LimitesRespuesta.PRESUPUESTO_CUERPO, ORDEN_RECORTE, Set.of("errores"), COMO_VER_RESTO);
        return r;
    }

    /** Siguiente paso exacto del plan según el progreso y la decisión de verificación de la sesión. */
    static void instruccionPlan(SesionMcp sesion, Path proyecto, ResultadoGeneracion g, String decision) {
        if (g.plan == null) {
            return;
        }
        SesionMcp.ProgresoPlan p = sesion.progresoPlan(proyecto);
        if (p == null) {
            return;
        }
        if (p.restantes() > 0) {
            StringBuilder sb = new StringBuilder();
            Path salida = sesion.salidaDe(proyecto);
            boolean puerta = salida != null && sesion.verificaciones().puertaActiva(salida);
            List<VerificacionesSesion.Correccion> cs = !puerta ? List.of() : sesion.verificaciones().correcciones(salida);
            if (!cs.isEmpty()) {
                sb.append("ANTES de seguir: corrige los errores de montaje de `").append(Textos.nombreSimple(cs.get(0).fqn()))
                        .append("` (estado_verificacion). ");
            } else {
                List<VerificacionesSesion.Revision> rs = !puerta ? List.of() : sesion.verificaciones().revisiones(salida);
                if (!rs.isEmpty()) {
                    sb.append("ANTES de seguir: revisa los fallos de aserción de `").append(Textos.nombreSimple(rs.get(0).fqn()))
                            .append("` (estado_verificacion); no preguntes al usuario. ");
                }
            }
            sb.append("Quedan ").append(p.restantes()).append(p.restantes() == 1 ? " caso (" : " casos (")
                    .append(GuiaTanda.posicion(sesion, proyecto)).append("): ")
                    .append(GuiaTanda.pasoServicio(sesion, proyecto, salida, GuiaTanda.GENERAR, null));
            if (!g.descartados.isEmpty()) {
                sb.append(" Los descartados porque ya existen en la carpeta de salida cuentan como atendidos: no los repitas "
                        + "ni los sustituyas. En métodos sin tests sustituye los demás descartados por duplicado por otro "
                        + "escenario; en métodos que ya tienen tests cuentan como atendidos, no insistas.");
            }
            sb.append(" No llames a ejecutar_tests hasta plan.restantes = 0.");
            g.plan.put("instruccion", sb.toString());
        } else {
            String base = "Plan completo (" + p.total() + " casos de " + p.metodosTotal() + " métodos en " + p.servicios()
                    + " servicios): no generes más";
            g.plan.put("instruccion", DECISION_DESCARTAR.equals(decision)
                    ? base + ". La decisión de la sesión es descartar: no ejecutes los tests salvo petición expresa del usuario; "
                    + "tu mensaje final es EXACTAMENTE respuestaFinal, tal cual."
                    : base + "; llama a ejecutar_tests sin argumentos (cierra la tanda: espera a las pruebas en segundo plano y "
                    + "te devuelve lo que quede por revisar; no preguntes al usuario); si responde verificando=true, vuelve a llamarla.");
        }
    }

    /** FQN del siguiente servicio del plan con casos pendientes, o null si no queda ninguno (o no hay plan). */
    static String siguienteServicio(SesionMcp sesion, Path proyecto) {
        for (SesionMcp.EstadoMetodo m : sesion.estadoPlan(proyecto)) {
            if (m.faltan() > 0) {
                return m.fqn();
            }
        }
        return null;
    }

    /**
     * Siguiente paso concreto del plan: «seleccionar_funciones {clase, funciones, omitidas}» (su respuesta trae el paquete
     * de trabajo) si el siguiente servicio aún necesita selección, o «detalle_servicio {clase, paquete: 1}»; null si no queda ninguno.
     */
    static String siguientePaso(SesionMcp sesion, Path proyecto) {
        String siguiente = siguienteServicio(sesion, proyecto);
        if (siguiente == null) {
            return null;
        }
        boolean elegir = sesion.necesitaSeleccion(proyecto, sesion.salidaDe(proyecto), siguiente);
        return elegir
                ? "seleccionar_funciones {clase: \"" + siguiente + "\", funciones, omitidas} eligiendo de siguienteServicio.candidatas "
                + "(si no vienen, antes seleccionar_funciones {clase: \"" + siguiente + "\"}); su respuesta trae el paquete de "
                + "trabajo: después generar_tests (detalle_servicio solo si paginacion.siguiente no es null)"
                : "detalle_servicio {clase: \"" + siguiente + "\", paquete: 1}";
    }

    /**
     * Con plan activo: registra los casos generados, descartados por duplicado y eliminados por método
     * (clave fqn#metodo) y devuelve el progreso en {@code g.plan}.
     */
    static void progresoPlan(SesionMcp sesion, Path proyecto, Fachada.Generacion gen) {
        progresoPlan(sesion, proyecto, gen, null, false);
    }

    /**
     * Como {@link #progresoPlan(SesionMcp, Path, Fachada.Generacion)}; con {@code contar} anota además el intento en la
     * salvaguarda anti-bucle (servicios del spec que están en el plan, comparando con los acreditados {@code antes}).
     * Devuelve los FQN cerrados por la salvaguarda en esta llamada.
     */
    static List<String> progresoPlan(SesionMcp sesion, Path proyecto, Fachada.Generacion gen, Map<String, Integer> antes,
                                     boolean contar) {
        ResultadoGeneracion g = gen.resultado();
        if (sesion.progresoPlan(proyecto) == null) {
            return List.of();
        }
        Map<String, String> claves = new LinkedHashMap<>();
        ResultadoValidacion val = gen.validacion();
        if (val != null) {
            for (ResultadoValidacion.ServicioValidado sv : val.servicios) {
                if (sv.clase == null) {
                    continue;
                }
                for (ResultadoValidacion.CasoValidado cv : sv.casos) {
                    if (cv.spec != null && cv.spec.id != null && cv.spec.metodo != null) {
                        claves.put(cv.spec.id, SesionMcp.clavePlan(sv.clase.fqn, cv.spec.metodo));
                    }
                }
            }
        }
        Map<String, String> generados = new LinkedHashMap<>();
        for (Map<String, String> m : g.generados) {
            String id = m.get("caso");
            if (id != null && claves.containsKey(id)) {
                generados.put(id, claves.get(id));
            }
        }
        Map<String, String> duplicados = new LinkedHashMap<>();
        Map<String, String> enSalida = new LinkedHashMap<>();
        for (Duplicado d : g.descartados) {
            if (d.caso != null && claves.containsKey(d.caso)) {
                (d.nivel == Duplicado.NIVEL_SALIDA ? enSalida : duplicados).put(d.caso, claves.get(d.caso));
            }
        }
        sesion.registrarCasosPlan(proyecto, generados, duplicados, g.eliminados);
        sesion.registrarDuplicadosEnSalida(proyecto, enSalida);
        List<String> cerrados = List.of();
        if (contar) {
            cerrados = sesion.registrarIntento(proyecto, serviciosEnPlan(sesion, proyecto, val), antes, false);
        }
        sesion.reiniciarRechazosPuertaPlan();
        SesionMcp.ProgresoPlan p = sesion.progresoPlan(proyecto);
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("total", p.total());
        plan.put("generados", p.generados());
        plan.put("restantes", p.restantes());
        plan.put("servicios", p.servicios());
        Map<String, Object> metodos = new LinkedHashMap<>();
        metodos.put("total", p.metodosTotal());
        metodos.put("completos", p.metodosCompletos());
        plan.put("metodos", metodos);
        List<String> pendientes = new ArrayList<>(p.pendientes().subList(0, Math.min(MAX_PENDIENTES, p.pendientes().size())));
        if (p.pendientes().size() > MAX_PENDIENTES) {
            pendientes.add("… y " + (p.pendientes().size() - MAX_PENDIENTES) + " métodos más");
        }
        plan.put("pendientes", pendientes);
        String siguiente = siguienteServicio(sesion, proyecto);
        if (siguiente != null) {
            plan.put("siguiente", siguiente);
        }
        g.plan = plan;
        instruccionPlan(sesion, proyecto, g, sesion.decisionVerificacion());
        return cerrados;
    }

    /** FQN (no nulos, sin repetir, en orden) de los servicios del spec validado. */
    static List<String> serviciosDelSpec(ResultadoValidacion val) {
        Set<String> r = new LinkedHashSet<>();
        if (val != null) {
            for (ResultadoValidacion.ServicioValidado sv : val.servicios) {
                if (sv != null && sv.clase != null && sv.clase.fqn != null) {
                    r.add(sv.clase.fqn);
                }
            }
        }
        return new ArrayList<>(r);
    }

    /** Servicios del spec que forman parte del plan activo. */
    private static List<String> serviciosEnPlan(SesionMcp sesion, Path proyecto, ResultadoValidacion val) {
        List<String> r = new ArrayList<>();
        for (String fqn : serviciosDelSpec(val)) {
            if (sesion.enPlan(proyecto, fqn)) {
                r.add(fqn);
            }
        }
        return r;
    }

    /** Errores del spec mostrados al cerrar un servicio atascado. */
    static final int MAX_ERRORES_CIERRE = 3;

    /**
     * Spec rechazado con plan activo: anota el intento en la salvaguarda (servicios del spec que están en el plan o, si no
     * se pueden resolver, el servicio en curso). Si cierra algún servicio, guarda la tanda y devuelve una respuesta sin
     * error con {@code servicioCerrado}; si no, null (respuesta de errores normal).
     */
    private static Resultado cerrarSiAtascado(SesionMcp sesion, Path proyecto, Path salida, Fachada.Generacion gen,
                                              Map<String, Integer> antes) {
        if (sesion.progresoPlan(proyecto) == null) {
            return null;
        }
        List<String> fqns = serviciosEnPlan(sesion, proyecto, gen.validacion());
        if (fqns.isEmpty()) {
            String actual = siguienteServicio(sesion, proyecto);
            if (actual != null) {
                fqns.add(actual);
            }
        }
        List<String> cerrados = sesion.registrarIntento(proyecto, fqns, antes, true);
        if (cerrados.isEmpty()) {
            return null;
        }
        sesion.guardarTanda(proyecto, salida);
        Map<String, String> motivos = sesion.cerradosPorSalvaguarda(proyecto);
        String fqn = cerrados.get(0);
        ObjectNode r = Json.objeto();
        r.put("servicioCerrado", Textos.nombreSimple(fqn));
        r.put("motivo", motivos.get(fqn));
        r.put("instruccion", "No repitas este servicio: sigue la primera línea accionRequerida.");
        ArrayNode errores = r.putArray("errores");
        List<ErrorValidacion> lista = gen.resultado().errores;
        for (int i = 0; i < Math.min(MAX_ERRORES_CIERRE, lista.size()); i++) {
            ObjectNode o = errores.addObject();
            o.put("codigo", lista.get(i).codigo);
            o.put("mensaje", lista.get(i).mensaje);
        }
        LimitesRespuesta.acotarJson(r, LimitesRespuesta.PRESUPUESTO_CUERPO, List.of("errores"), COMO_VER_RESTO);
        return new Resultado(Json.compacto(r), false);
    }

    /** Claves de la vista de generar_tests que se quitan durante la tanda si están vacías (arrays) o a 0 (contadores). */
    static final List<String> VACIAS_EN_TANDA = List.of("archivosCreados", "archivosAmpliados", "descartados", "noReescritos",
            "requiereRefactor", "reemplazados", "eliminados", "avisos", "errores", "omitidos", "noTocados", "remontados");

    /**
     * Respuesta compacta durante la tanda (cabecera accionRequerida activa y sin respuestaFinal): sin resumenSesion,
     * informe ni spec; sin arrays vacíos ni contadores a 0; verificacion = {decision} si es probar; plan = {total,
     * generados, restantes, servicios, servicioEnCurso, faltan}; servicioCerrado si la salvaguarda cerró algún servicio.
     */
    static void compactarEnTanda(ObjectNode vista, SesionMcp sesion, Path proyecto, List<String> cerrados) {
        vista.remove("resumenSesion");
        vista.remove("informe");
        vista.remove("spec");
        for (String k : VACIAS_EN_TANDA) {
            JsonNode n = vista.get(k);
            if (n != null && ((n.isArray() && n.isEmpty()) || (n.isNumber() && n.asInt() == 0) || n.isNull())) {
                vista.remove(k);
            }
        }
        JsonNode v = vista.get("verificacion");
        if (v != null && DECISION_PROBAR.equals(v.path("decision").asText(null))) {
            ObjectNode nv = Json.objeto();
            nv.put("decision", DECISION_PROBAR);
            vista.set("verificacion", nv);
        }
        SesionMcp.ProgresoPlan pp = sesion.progresoPlan(proyecto);
        if (pp != null && vista.has("plan")) {
            ObjectNode plan = Json.objeto();
            plan.put("total", pp.total());
            plan.put("generados", pp.generados());
            plan.put("restantes", pp.restantes());
            plan.put("servicios", pp.servicios());
            String actual = siguienteServicio(sesion, proyecto);
            if (pp.restantes() > 0 && actual != null) {
                String pos = GuiaTanda.posicion(sesion, proyecto);
                String prefijo = "servicio ";
                plan.put("servicioEnCurso", (pos.startsWith(prefijo) ? pos.substring(prefijo.length()) : pos) + ": "
                        + Textos.nombreSimple(actual));
                plan.put("faltan", GuiaTanda.requeridos(sesion, proyecto, actual));
            }
            int porArreglar = sesion.arreglosPendientes(proyecto).size();
            if (porArreglar > 0) {
                plan.put("porArreglarAlFinal", porArreglar);
            }
            vista.set("plan", plan);
        }
        if (cerrados != null && !cerrados.isEmpty()) {
            Map<String, String> motivos = sesion.cerradosPorSalvaguarda(proyecto);
            List<String> nombres = new ArrayList<>();
            cerrados.forEach(f -> nombres.add(Textos.nombreSimple(f)));
            ObjectNode sc = vista.putObject("servicioCerrado");
            sc.put("servicio", String.join(", ", nombres));
            sc.put("motivo", motivos.get(cerrados.get(0)));
        }
    }

    private static void rellenar(Map<String, String> v, String decision, boolean sinRespuesta) {
        if (decision == null && sinRespuesta) {
            v.put("decision", DECISION_PENDIENTE_USUARIO);
            v.put("instruccion", INSTRUCCION_PENDIENTE_USUARIO);
        } else if (DECISION_PROBAR.equals(decision)) {
            v.put("decision", DECISION_PROBAR);
            v.put("instruccion", INSTRUCCION_PROBAR);
        } else if (DECISION_DESCARTAR.equals(decision)) {
            v.put("decision", DECISION_DESCARTAR);
            v.put("instruccion", INSTRUCCION_DESCARTAR);
        } else {
            v.put("decision", DECISION_PENDIENTE);
            v.put("instruccion", INSTRUCCION_PENDIENTE);
        }
        v.put("alcance", "sesion");
    }

    /** Nombres de las clases de test (archivo sin .java) creadas o ampliadas. */
    static List<String> clasesDeTest(ResultadoGeneracion g) {
        List<String> r = new ArrayList<>();
        List<String> archivos = new ArrayList<>(g.archivosCreados);
        archivos.addAll(g.archivosAmpliados);
        for (String a : archivos) {
            String n = a.substring(Math.max(a.lastIndexOf('/'), a.lastIndexOf('\\')) + 1);
            if (n.toLowerCase(Locale.ROOT).endsWith(".java")) {
                n = n.substring(0, n.length() - 5);
            }
            if (!n.isBlank() && !r.contains(n)) {
                r.add(n);
            }
        }
        return r;
    }
}
