package com.junitgen.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.junitgen.config.CargadorConfig;
import com.junitgen.config.ConfigProyecto;
import com.junitgen.escaneo.InfoClase;
import com.junitgen.escaneo.PaqueteTrabajo;
import com.junitgen.escaneo.PlanGeneracion;
import com.junitgen.escaneo.ProyectoEscaneado;
import com.junitgen.escaneo.SeleccionFunciones;
import com.junitgen.generacion.Tanda;
import com.junitgen.util.Json;
import com.junitgen.util.LimitesRespuesta;
import com.junitgen.util.Textos;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Modo paralelo de la tanda (solo Claude Code con subagentes): reparte el plan en bloques de servicios persistidos en
 * {@link Tanda#bloques} (tanda.json) y ofrece las consultas, el ámbito de bloque de cada llamada, el paquete de trabajo con
 * la selección de funciones incluida y los textos de la guía. Todo acceso a {@code t.bloques} va bajo {@code synchronized (t)}.
 * Sin bloques en la tanda, nada de esto interviene.
 */
final class BloquesTanda {

    /** Servicios por bloque que busca el reparto (ajustable con {@link #ENV_SERVICIOS_BLOQUE}). */
    static final int SERVICIOS_POR_BLOQUE = 4;
    /** Máximo de bloques (ajustable con {@link #ENV_MAX_BLOQUES}). */
    static final int MAX_BLOQUES = 6;
    /** Con menos servicios que este número, todo el plan es un solo bloque. */
    static final int MIN_SERVICIOS_PARALELO = 3;
    /** Peso base de un servicio en el reparto (además de sus casos previstos). */
    static final int PESO_BASE_SERVICIO = 3;
    /** Tamaño máximo (bytes) de la lista de candidatas del paquete de un bloque. */
    static final int MAX_CANDIDATAS = 2500;
    /** Margen reservado para la estructura JSON añadida al paquete (selección y esqueleto). */
    static final int RESERVA_ESTRUCTURA = 120;
    /** Servicios nombrados como máximo por bloque en la tabla de bloques. */
    static final int MAX_NOMBRES_BLOQUE = 8;
    static final int MIN_ENTORNO = 1;
    static final int MAX_ENTORNO = 10;
    static final String ENV_SERVICIOS_BLOQUE = "JUNITGEN_SERVICIOS_BLOQUE";
    static final String ENV_MAX_BLOQUES = "JUNITGEN_MAX_BLOQUES";
    /** Código del error de tomar_bloque cuando este proceso no tiene la tanda en bloques (sesión MCP no compartida). */
    static final String SIN_SESION_COMPARTIDA = "SIN_SESION_COMPARTIDA";

    /** Prompt del subagente de un bloque; {@code <K>} lo cambia el principal (junit-gen recuerda las rutas: no se pasan). */
    static final String PROMPT_TRABAJADOR = "Trabajador de junit-gen: completa el BLOQUE <K> usando SOLO las herramientas MCP de "
            + "junit-gen (tomar_bloque, generar_tests, ejecutar_tests, arreglar_caso, registrar_analisis_ia, detalle_servicio; si "
            + "tu cliente las carga bajo demanda, cárgalas todas en UNA búsqueda). Llama a tomar_bloque {bloque: <K>, "
            + "instrucciones: true} y después haz en cada turno exactamente la llamada de «accionRequerida», sin texto. Si pierdes "
            + "el hilo: tomar_bloque {bloque: <K>}. Al recibir «BLOQUE <K> TERMINADO», responde solo eso. Prohibido: terminal, "
            + "archivos, escanear_proyecto, otras herramientas, subagentes y preguntar al usuario; no pases rutas.";

    private BloquesTanda() {
    }

    // ------------------------------------------------------------- configuración y reparto

    /** Entero de la variable de entorno en [1, 10]; ausente, no numérico o fuera de rango → {@code defecto}. */
    private static int entorno(String nombre, int defecto) {
        String v;
        try {
            v = System.getenv(nombre);
        } catch (SecurityException e) {
            System.err.println("junit-gen: no se pudo leer " + nombre + " (" + e.getClass().getSimpleName() + ")");
            return defecto;
        }
        if (v == null || v.isBlank()) {
            return defecto;
        }
        try {
            int n = Integer.parseInt(v.trim());
            if (n >= MIN_ENTORNO && n <= MAX_ENTORNO) {
                return n;
            }
        } catch (NumberFormatException e) {
            System.err.println("junit-gen: " + nombre + " no es un entero; se usa " + defecto);
            return defecto;
        }
        System.err.println("junit-gen: " + nombre + " fuera de rango [" + MIN_ENTORNO + ", " + MAX_ENTORNO + "]; se usa " + defecto);
        return defecto;
    }

    /** Número de bloques para {@code n} servicios: 1 si hay muy pocos; si no, entre 2 y el máximo configurado. */
    static int bloquesPara(int n) {
        if (n < MIN_SERVICIOS_PARALELO) {
            return 1;
        }
        int porBloque = entorno(ENV_SERVICIOS_BLOQUE, SERVICIOS_POR_BLOQUE);
        int max = entorno(ENV_MAX_BLOQUES, MAX_BLOQUES);
        int k = Math.min(max, Math.max(2, (n + porBloque - 1) / porBloque));
        return Math.min(k, n);
    }

    /**
     * null si el modo paralelo se puede aplicar; si no, el motivo (para una nota). Requiere carpeta de salida conocida,
     * {@code verificacion: siempre}, {@code fallosAsercion: revisarIa}, {@code arreglos: alFinal} y un cliente que no sea
     * GitHub Copilot ni Visual Studio Code.
     */
    static String motivoNoPermitido(SesionMcp sesion, Path proyecto, Path salida) {
        if (salida == null) {
            return "Modo paralelo sin aplicar: falta la carpeta de salida; repite con carpetaSalida y paralelo: true.";
        }
        if (CargadorConfig.modoVerificacion(proyecto) != ConfigProyecto.ModoVerificacion.SIEMPRE) {
            return "Modo paralelo sin aplicar: requiere `verificacion: siempre` en junit-gen.yml (se sigue en secuencial).";
        }
        if (!HerramientaEjecutar.revisionAutomatica(proyecto)) {
            return "Modo paralelo sin aplicar: requiere `fallosAsercion: revisarIa` en junit-gen.yml (se sigue en secuencial).";
        }
        if (CargadorConfig.modoArreglos(proyecto) != ConfigProyecto.ModoArreglos.AL_FINAL) {
            return "Modo paralelo sin aplicar: requiere `arreglos: alFinal` en junit-gen.yml (se sigue en secuencial).";
        }
        String cliente = sesion.cliente();
        if (cliente != null) {
            String c = cliente.toLowerCase(Locale.ROOT);
            if (c.contains("copilot") || c.contains("visual studio code") || c.contains("vscode")) {
                return "Modo paralelo sin aplicar: el cliente (" + cliente + ") no tiene subagentes (se sigue en secuencial).";
            }
        }
        return null;
    }

    /**
     * Paralelo por defecto (sin argumento {@code paralelo}): solo si el cliente MCP declara ser Claude Code, que tiene
     * subagentes. {@code JUNITGEN_PARALELO=0|1} lo fuerza. Así no depende de que el modelo recuerde pasar el argumento.
     */
    static boolean clienteConSubagentes(SesionMcp sesion) {
        String forzado = System.getenv("JUNITGEN_PARALELO");
        if (forzado != null && !forzado.isBlank()) {
            return "1".equals(forzado.strip()) || "true".equalsIgnoreCase(forzado.strip());
        }
        String cliente = sesion.cliente();
        return cliente != null && cliente.toLowerCase(Locale.ROOT).contains("claude");
    }

    static boolean permitido(SesionMcp sesion, Path proyecto, Path salida) {
        return motivoNoPermitido(sesion, proyecto, salida) == null;
    }

    /**
     * Reparte los servicios del plan en bloques (asignación del más pesado al bloque menos cargado) y los guarda en la
     * tanda en memoria; el peso de un servicio es {@code casos + PESO_BASE_SERVICIO}. Dentro de cada bloque, orden del plan.
     */
    static void repartir(SesionMcp sesion, Path proyecto, PlanGeneracion plan) {
        Tanda t = sesion.tanda(proyecto);
        if (t == null || plan == null) {
            return;
        }
        List<PlanGeneracion.ServicioPlan> servicios = plan.servicios();
        int n = servicios.size();
        if (n == 0) {
            return;
        }
        int k = bloquesPara(n);
        List<Integer> orden = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            orden.add(i);
        }
        orden.sort(Comparator.<Integer>comparingInt(i -> -(servicios.get(i).casos() + PESO_BASE_SERVICIO)).thenComparingInt(i -> i));
        List<List<Integer>> reparto = new ArrayList<>();
        int[] carga = new int[k];
        for (int b = 0; b < k; b++) {
            reparto.add(new ArrayList<>());
        }
        for (int i : orden) {
            int destino = 0;
            for (int b = 1; b < k; b++) {
                if (carga[b] < carga[destino]) {
                    destino = b;
                }
            }
            reparto.get(destino).add(i);
            carga[destino] += servicios.get(i).casos() + PESO_BASE_SERVICIO;
        }
        List<Tanda.BloqueTanda> bloques = new ArrayList<>();
        for (int b = 0; b < k; b++) {
            List<Integer> indices = reparto.get(b);
            indices.sort(Comparator.naturalOrder());
            Tanda.BloqueTanda bt = new Tanda.BloqueTanda();
            bt.id = b + 1;
            bt.estado = Tanda.BLOQUE_PENDIENTE;
            for (int i : indices) {
                bt.servicios.add(servicios.get(i).clase.fqn);
            }
            bloques.add(bt);
        }
        synchronized (t) {
            t.bloques = bloques;
        }
    }

    // ------------------------------------------------------------- consultas

    static boolean activo(Tanda t) {
        if (t == null) {
            return false;
        }
        synchronized (t) {
            return t.bloques != null && !t.bloques.isEmpty();
        }
    }

    static boolean activo(SesionMcp sesion, Path proyecto) {
        return sesion != null && proyecto != null && activo(sesion.tanda(proyecto));
    }

    private static Tanda.BloqueTanda buscar(Tanda t, int id) {
        if (t.bloques == null) {
            return null;
        }
        for (Tanda.BloqueTanda b : t.bloques) {
            if (b != null && b.id == id) {
                return b;
            }
        }
        return null;
    }

    private static Tanda.BloqueTanda copia(Tanda.BloqueTanda b) {
        Tanda.BloqueTanda c = new Tanda.BloqueTanda();
        c.id = b.id;
        c.servicios = new ArrayList<>(b.servicios == null ? List.of() : b.servicios);
        c.estado = b.estado;
        c.tomado = b.tomado;
        c.terminado = b.terminado;
        return c;
    }

    /** Copia del bloque {@code id}, o null si no existe. */
    static Tanda.BloqueTanda de(Tanda t, int id) {
        if (t == null) {
            return null;
        }
        synchronized (t) {
            Tanda.BloqueTanda b = buscar(t, id);
            return b == null ? null : copia(b);
        }
    }

    /** Servicios (FQN) del bloque {@code id}, o null si no hay tanda en bloques o el bloque no existe. */
    static Set<String> serviciosDe(Tanda t, int id) {
        Tanda.BloqueTanda b = de(t, id);
        return b == null ? null : new LinkedHashSet<>(b.servicios);
    }

    /** Ids de todos los bloques, en orden. */
    static List<Integer> ids(Tanda t) {
        List<Integer> r = new ArrayList<>();
        if (t == null) {
            return r;
        }
        synchronized (t) {
            if (t.bloques != null) {
                for (Tanda.BloqueTanda b : t.bloques) {
                    if (b != null) {
                        r.add(b.id);
                    }
                }
            }
        }
        return r;
    }

    /** Ids de los bloques que aún no están terminados. */
    static List<Integer> sinTerminar(Tanda t) {
        List<Integer> r = new ArrayList<>();
        if (t == null) {
            return r;
        }
        synchronized (t) {
            if (t.bloques != null) {
                for (Tanda.BloqueTanda b : t.bloques) {
                    if (b != null && !Tanda.BLOQUE_TERMINADO.equals(b.estado)) {
                        r.add(b.id);
                    }
                }
            }
        }
        return r;
    }

    static boolean terminado(SesionMcp sesion, Path proyecto, int id) {
        Tanda.BloqueTanda b = de(sesion.tanda(proyecto), id);
        return b != null && Tanda.BLOQUE_TERMINADO.equals(b.estado);
    }

    /** Id del bloque que contiene el servicio (FQN o nombre simple, sin distinguir mayúsculas), o null. */
    static Integer deServicio(Tanda t, String nombre) {
        if (t == null || nombre == null || nombre.isBlank()) {
            return null;
        }
        String n = nombre.trim();
        synchronized (t) {
            if (t.bloques == null) {
                return null;
            }
            for (Tanda.BloqueTanda b : t.bloques) {
                if (b == null || b.servicios == null) {
                    continue;
                }
                for (String fqn : b.servicios) {
                    if (fqn.equals(n) || Textos.nombreSimple(fqn).equalsIgnoreCase(n)) {
                        return b.id;
                    }
                }
            }
        }
        return null;
    }

    /** Ids de los bloques no terminados con casos del plan sin generar (visión global del plan). */
    static List<Integer> sinGenerar(SesionMcp sesion, Path proyecto) {
        Tanda t = sesion.tanda(proyecto);
        List<Integer> r = new ArrayList<>();
        if (t == null) {
            return r;
        }
        Set<String> pendientes = new LinkedHashSet<>();
        for (SesionMcp.EstadoMetodo m : sesion.estadoPlanGlobal(proyecto)) {
            if (m.faltan() > 0) {
                pendientes.add(m.fqn());
            }
        }
        synchronized (t) {
            if (t.bloques != null) {
                for (Tanda.BloqueTanda b : t.bloques) {
                    if (b == null || Tanda.BLOQUE_TERMINADO.equals(b.estado) || b.servicios == null) {
                        continue;
                    }
                    for (String fqn : b.servicios) {
                        if (pendientes.contains(fqn)) {
                            r.add(b.id);
                            break;
                        }
                    }
                }
            }
        }
        return r;
    }

    /** Cambia el estado del bloque (en_curso fija {@code tomado} la primera vez; terminado fija {@code terminado}). */
    static void marcar(SesionMcp sesion, Path proyecto, int id, String estado) {
        Tanda t = sesion.tanda(proyecto);
        if (t == null) {
            return;
        }
        synchronized (t) {
            Tanda.BloqueTanda b = buscar(t, id);
            if (b == null) {
                return;
            }
            if (Tanda.BLOQUE_EN_CURSO.equals(estado) && Tanda.BLOQUE_TERMINADO.equals(b.estado)) {
                return;
            }
            b.estado = estado;
            String ahora = Instant.now().toString();
            if (Tanda.BLOQUE_EN_CURSO.equals(estado) && b.tomado == null) {
                b.tomado = ahora;
            }
            if (Tanda.BLOQUE_TERMINADO.equals(estado) && b.terminado == null) {
                b.terminado = ahora;
            }
        }
    }

    // ------------------------------------------------------------- argumento y ámbito de la llamada

    /** El argumento {@code bloque} como entero, o null si no viene; un valor que no es un entero lanza IllegalArgumentException. */
    static Integer bloqueArg(JsonNode args) {
        JsonNode v = args == null ? null : args.get("bloque");
        if (v == null || v.isNull() || (v.isTextual() && v.asText().isBlank())) {
            return null;
        }
        try {
            if (v.isIntegralNumber()) {
                return v.asInt();
            }
            if (v.isTextual()) {
                return Integer.parseInt(v.asText().trim());
            }
        } catch (NumberFormatException e) {
            // se informa abajo con el mensaje común
        }
        throw new IllegalArgumentException("'bloque' debe ser un entero (el id de un bloque de la tanda).");
    }

    /** Mensaje de bloque inexistente con los ids válidos. */
    static String errorBloqueInexistente(Tanda t, int id) {
        return "El bloque " + id + " no existe. Bloques válidos: " + ids(t).stream().map(String::valueOf)
                .reduce((a, b) -> a + ", " + b).orElse("(ninguno)") + ".";
    }

    /**
     * Bloque al que pertenece la llamada, deducido de sus argumentos (sin bloques en la tanda, null): {@code bloque} si
     * viene y existe; el primer servicio de {@code spec} (generar_tests, validar_spec); {@code clase} (seleccionar_funciones,
     * detalle_servicio); el servicio del primer caso (arreglar_caso, registrar_analisis_ia).
     */
    static Integer resolver(SesionMcp sesion, String herramienta, JsonNode args) {
        Path proyecto = sesion.fachada().ultimaRuta();
        Tanda t = proyecto == null ? null : sesion.tanda(proyecto);
        if (!activo(t)) {
            return null;
        }
        JsonNode b = args == null ? null : args.get("bloque");
        if (b != null && !b.isNull()) {
            Integer id = bloqueArg(args);
            return id != null && de(t, id) != null ? id : null;
        }
        if (args == null || herramienta == null) {
            return null;
        }
        String clase = switch (herramienta) {
            case "generar_tests", "validar_spec" -> claseDelSpec(args.get("spec"));
            case "seleccionar_funciones", "detalle_servicio" -> Herramienta.texto(args, "clase");
            case "arreglar_caso", "registrar_analisis_ia" -> servicioDelCaso(sesion, proyecto, primerCaso(herramienta, args));
            default -> null;
        };
        return deServicio(t, clase);
    }

    private static JsonNode comoJson(JsonNode n) {
        if (n != null && n.isTextual() && !n.asText().isBlank()) {
            try {
                return Json.MAPPER.readTree(n.asText());
            } catch (java.io.IOException e) {
                return null;
            }
        }
        return n;
    }

    private static String claseDelSpec(JsonNode spec) {
        JsonNode s = comoJson(spec);
        JsonNode servicios = s == null ? null : s.get("servicios");
        if (servicios == null || !servicios.isArray()) {
            return null;
        }
        for (JsonNode sv : servicios) {
            String c = sv == null ? null : Herramienta.texto(sv, "clase");
            if (c != null) {
                return c;
            }
        }
        return null;
    }

    private static String primerCaso(String herramienta, JsonNode args) {
        String campo = "registrar_analisis_ia".equals(herramienta) ? "analisis" : "casos";
        JsonNode lista = comoJson(args.get(campo));
        if (lista != null && lista.isArray()) {
            for (JsonNode x : lista) {
                String c = x == null ? null : Herramienta.texto(x, "caso");
                if (c != null) {
                    return c;
                }
            }
        }
        return "arreglar_caso".equals(herramienta) ? Herramienta.texto(args, "caso") : null;
    }

    private static String servicioDelCaso(SesionMcp sesion, Path proyecto, String caso) {
        if (caso == null) {
            return null;
        }
        for (Tanda.ArregloTanda a : sesion.arreglosGlobal(proyecto)) {
            if (caso.equals(a.caso) && a.servicio != null) {
                return a.servicio;
            }
        }
        Path salida = sesion.salidaDe(proyecto);
        return salida == null ? null : sesion.serviciosDeCasos(salida).get(caso);
    }

    // ------------------------------------------------------------- textos y nodos de la guía

    /** «ejecutar_tests {}» o, en una llamada con bloque, «ejecutar_tests {bloque: b}». */
    static String llamadaEjecutar(SesionMcp sesion) {
        Integer b = sesion.bloqueLlamada();
        return b == null ? "ejecutar_tests {}" : "ejecutar_tests {bloque: " + b + "}";
    }

    /** Prompt del trabajador con la ruta del proyecto y la carpeta de salida reales (con {@code <K>} literal). */
    static String promptTrabajador(Path proyecto, Path salida) {
        return PROMPT_TRABAJADOR.replace("{ruta}", String.valueOf(proyecto)).replace("{salida}", String.valueOf(salida));
    }

    /**
     * Marca el bloque como terminado y devuelve su nodo: {bloqueTerminado: true, bloque, resumen, [resultadoAnterior],
     * instruccion}. Sirve igual al trabajador (responde «BLOQUE b TERMINADO») y al agente principal (llama a ejecutar_tests {}).
     */
    static ObjectNode nodoTerminado(SesionMcp sesion, Path proyecto, Path salida, int id, ObjectNode anterior) {
        marcar(sesion, proyecto, id, Tanda.BLOQUE_TERMINADO);
        ObjectNode r = Json.objeto();
        r.put("bloqueTerminado", true);
        r.put("bloque", id);
        r.put("resumen", resumenBloque(sesion, proyecto, salida, id));
        if (anterior != null) {
            r.set("resultadoAnterior", anterior);
        }
        r.put("instruccion", "Si eres el trabajador de este bloque, responde solo «BLOQUE " + id + " TERMINADO» y termina. Si eres "
                + "el agente principal (o el bloque era el único), llama a ejecutar_tests {} y sigue accionRequerida hasta respuestaFinal.");
        if (anterior != null) {
            LimitesRespuesta.acotarJson(r, ArreglosFinal.presupuesto(), List.of(), "El detalle está en informe.md.");
        }
        return r;
    }

    private static String resumenBloque(SesionMcp sesion, Path proyecto, Path salida, int id) {
        Set<String> servicios = serviciosDe(sesion.tanda(proyecto), id);
        if (servicios == null) {
            return "Bloque " + id + ".";
        }
        int casos = 0;
        if (salida != null) {
            for (String fqn : sesion.serviciosDeCasos(salida).values()) {
                if (servicios.contains(fqn)) {
                    casos++;
                }
            }
        }
        int arreglados = 0;
        int bugs = 0;
        int apartados = 0;
        for (Tanda.ArregloTanda a : sesion.arreglosGlobal(proyecto)) {
            if (!servicios.contains(a.servicio)) {
                continue;
            }
            if (Tanda.ARREGLO_ARREGLADO.equals(a.estado)) {
                arreglados++;
            } else if (Tanda.ARREGLO_POSIBLE_BUG.equals(a.estado)) {
                bugs++;
            } else if (Tanda.ARREGLO_APARTADO.equals(a.estado) || Tanda.ARREGLO_DUDOSO.equals(a.estado)) {
                apartados++;
            }
        }
        String r = servicios.size() + (servicios.size() == 1 ? " servicio, " : " servicios, ") + casos
                + (casos == 1 ? " caso generado" : " casos generados");
        if (arreglados + bugs + apartados > 0) {
            r += "; arreglo: " + arreglados + " arreglados, " + bugs + " posibles bugs, " + apartados + " apartados";
        }
        return r;
    }

    /** Orden de lanzamiento, tabla de bloques y prompt del trabajador (o, con un solo bloque, la orden de hacerlo tú). */
    static String siguientePaso(SesionMcp sesion, Path proyecto, Path salida, Collection<Integer> ids) {
        Tanda t = sesion.tanda(proyecto);
        if (!activo(t)) {
            return "";
        }
        List<Tanda.BloqueTanda> elegidos = new ArrayList<>();
        int total;
        synchronized (t) {
            total = t.bloques.size();
            for (Tanda.BloqueTanda b : t.bloques) {
                if (b != null && (ids == null ? !Tanda.BLOQUE_TERMINADO.equals(b.estado) : ids.contains(b.id))) {
                    elegidos.add(copia(b));
                }
            }
        }
        if (total == 1) {
            return "Modo paralelo con un solo bloque (pocos servicios): lo trabajas tú, sin subagentes. Llama ahora a tomar_bloque "
                    + "{bloque: 1, instrucciones: true}, sigue sus `instrucciones` y cada línea accionRequerida sin escribir nada al "
                    + "usuario; al recibir «BLOQUE 1 TERMINADO» llama a ejecutar_tests {} y sigue hasta respuestaFinal.";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Modo paralelo: el plan se reparte en ").append(total).append(" bloques (guardados en tanda.json); ")
                .append(elegidos.size() == total ? "" : "faltan por terminar " + elegidos.size() + ". ")
                .append("Tú NO generes ningún servicio. En UN solo mensaje lanza un subagente por cada bloque de la tabla con la "
                        + "herramienta Agent (subagent_type: general-purpose, sin `model`, sin segundo plano), cada uno con el "
                        + "promptTrabajador de abajo cambiando <K> por el número de su bloque; espera a que vuelvan todos y no "
                        + "resumas lo que devuelven. Después llama a ejecutar_tests {} (repítela mientras devuelva verificando=true) y "
                        + "sigue accionRequerida hasta recibir respuestaFinal. Si un subagente falla o devuelve SIN_SESION_COMPARTIDA, "
                        + "ejecutar_tests {} indica los bloques sin generar: hazlos tú con tomar_bloque {bloque: K, instrucciones: "
                        + "true} y sigue sus instrucciones.\n\n");
        sb.append("| Bloque | Servicios |\n|---|---|\n");
        for (Tanda.BloqueTanda b : elegidos) {
            List<String> nombres = new ArrayList<>();
            for (String fqn : b.servicios) {
                if (nombres.size() < MAX_NOMBRES_BLOQUE) {
                    nombres.add(Textos.nombreSimple(fqn));
                }
            }
            sb.append("| ").append(b.id).append(" | ").append(b.servicios.size()).append(b.servicios.size() == 1 ? " servicio: " : " servicios: ")
                    .append(String.join(", ", nombres))
                    .append(b.servicios.size() > nombres.size() ? " +" + (b.servicios.size() - nombres.size()) : "").append(" |\n");
        }
        sb.append("\npromptTrabajador (cambia <K> por el número del bloque):\n\n").append(promptTrabajador(proyecto, salida)).append('\n');
        return sb.toString();
    }

    // ------------------------------------------------------------- paquete de trabajo con la selección incluida

    /**
     * Paquete de trabajo (página 1) del servicio para un trabajador de bloque. Si el servicio aún necesita selección, la clave
     * {@code seleccion} pasa a ser {minimo, maximo, candidatas} y la {@code plantillaSpec} trae en
     * {@code servicios[0].seleccion} la preselección automática (con motivo vacío) y las candidatas que escriben datos y
     * quedan fuera (en {@code omitidas}, con motivo vacío). {@code presupuesto} cuenta lo añadido.
     */
    static ObjectNode paquete(SesionMcp sesion, Path proyecto, Path salida, String fqn, int presupuesto, boolean plantillaCompleta) {
        boolean necesita = sesion.necesitaSeleccion(proyecto, salida, fqn);
        ObjectNode nodoSeleccion = null;
        ObjectNode esqueleto = null;
        if (necesita) {
            ProyectoEscaneado.Encontrada en = sesion.fachada().localizarClase(fqn, proyecto, null, sesion.modulosTrabajo(proyecto));
            SeleccionFunciones sel = SeleccionFunciones.de(en.modulo(), en.clase(), false);
            nodoSeleccion = nodoSeleccion(sel, en.clase());
            esqueleto = esqueletoSeleccion(sel);
        }
        int extra = !necesita ? 0 : LimitesRespuesta.bytes(Json.compacto(nodoSeleccion)) + LimitesRespuesta.bytes(Json.compacto(esqueleto))
                + RESERVA_ESTRUCTURA;
        PaqueteTrabajo.Opciones op = new PaqueteTrabajo.Opciones();
        op.pagina = 1;
        op.presupuesto = Math.max(PaqueteTrabajo.MIN_PAGINA, presupuesto - extra);
        Integer bloque = sesion.bloqueLlamada();
        if (bloque != null) {
            op.tiposYaEnviados = sesion.tiposPreviosDelServicio(proyecto, bloque, fqn);
        }
        ObjectNode d = HerramientaDetalle.paquete(sesion, proyecto, null, fqn, op, plantillaCompleta);
        if (necesita) {
            d.set("seleccion", nodoSeleccion);
            JsonNode sv = d.path("plantillaSpec").path("servicios").get(0);
            if (sv instanceof ObjectNode o) {
                o.set("seleccion", esqueleto);
            }
        }
        if (bloque != null) {
            registrarTiposEnviados(sesion, proyecto, bloque, d);
        }
        return d;
    }

    /** Anota como enviados al bloque los tipos auxiliares (FQN) realmente presentes en el paquete final. */
    static void registrarTiposEnviados(SesionMcp sesion, Path proyecto, int bloque, JsonNode paquete) {
        List<String> fqns = new ArrayList<>();
        for (JsonNode t : paquete.path(PaqueteTrabajo.TIPOS)) {
            String fqn = t.path("fqn").asText(null);
            if (fqn != null) {
                fqns.add(fqn);
            }
        }
        sesion.registrarTiposEnviados(proyecto, bloque, fqns);
    }

    /** {minimo, maximo, candidatas}: las líneas de candidatas que caben en {@link #MAX_CANDIDATAS}. */
    private static ObjectNode nodoSeleccion(SeleccionFunciones sel, InfoClase c) {
        ObjectNode s = Json.objeto();
        s.put("minimo", sel.minimo());
        s.put("maximo", sel.cupo());
        StringBuilder sb = new StringBuilder();
        List<String> lineas = sel.lineasCandidatas();
        int incluidas = 0;
        for (String l : lineas) {
            if (LimitesRespuesta.bytes(sb.toString()) + LimitesRespuesta.bytes(l) > MAX_CANDIDATAS && incluidas > 0) {
                break;
            }
            sb.append(l);
            incluidas++;
        }
        if (incluidas < lineas.size()) {
            sb.append("… y ").append(lineas.size() - incluidas).append(" más (seleccionar_funciones {clase: \"").append(c.fqn).append("\"})\n");
        }
        s.put("candidatas", sb.toString());
        return s;
    }

    /** {funciones: [{nombre, motivo: ""}…], omitidas: [{nombre, motivo: ""}…]}: preselección y candidatas con `escribe` fuera. */
    static ObjectNode esqueletoSeleccion(SeleccionFunciones sel) {
        ObjectNode r = Json.objeto();
        ArrayNode fs = r.putArray("funciones");
        ArrayNode os = r.putArray("omitidas");
        for (SeleccionFunciones.Funcion f : sel.funciones()) {
            if (f.seleccionada()) {
                ObjectNode e = fs.addObject();
                e.put("nombre", f.metodo().clave());
                e.put("motivo", "");
            } else if (f.datos().trivial() == null && f.datos().escribeDatos()) {
                ObjectNode e = os.addObject();
                e.put("nombre", f.metodo().clave());
                e.put("motivo", "");
            }
        }
        return r;
    }

    /** Copia de los ids de bloque como texto «1, 2, 3». */
    static String listaIds(Collection<Integer> ids) {
        return ids.stream().map(String::valueOf).reduce((a, b) -> a + ", " + b).orElse("");
    }
}
