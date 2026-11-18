package com.junitgen.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.junitgen.config.CargadorConfig;
import com.junitgen.config.ConfigProyecto;
import com.junitgen.escaneo.InformeEscaneo;
import com.junitgen.escaneo.InterpretePeticion;
import com.junitgen.escaneo.PlanGeneracion;
import com.junitgen.escaneo.ProyectoEscaneado;
import com.junitgen.generacion.AlmacenOleadas;
import com.junitgen.generacion.AlmacenSpec;
import com.junitgen.generacion.AlmacenTanda;
import com.junitgen.generacion.Oleadas;
import com.junitgen.generacion.Tanda;
import com.junitgen.seguridad.RutaSegura;
import com.junitgen.spec.CasoSpec;
import com.junitgen.spec.Spec;
import com.junitgen.util.Json;
import com.junitgen.verificacion.ClasificadorFallos;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Modo «todos los servicios»: campaña persistente ({@code oleadas.json}) que recorre todos los servicios en oleadas de
 * tamaño fijo; cada oleada es una tanda normal (plan, bloques, cola y cierre con ejecutar_tests) y junit-gen las encadena
 * hasta un informe global. Todo son estáticos: el estado vive en {@link SesionMcp} y en la salida.
 */
final class OleadasTodos {

    static final String ENV_OLEADA = "JUNITGEN_OLEADA";
    static final String ENV_SIN_CONFIRMAR = "JUNITGEN_TODOS_SIN_CONFIRMAR";
    static final int TAM_DEFECTO = 25;
    static final int MIN_TAM = ConfigProyecto.MIN_OLEADA;
    static final int MAX_TAM = ConfigProyecto.MAX_OLEADA;
    /** Medición de referencia: Sonnet 5.5 high en paralelo, por cada {@link #SERVICIOS_MEDIDA} servicios. */
    static final int SERVICIOS_MEDIDA = 25;
    static final int MINUTOS_MEDIDA = 2;
    static final double USD_MEDIDA = 2.45;
    /** Primera línea de la respuesta que pide la confirmación única del modo «todos». */
    static final String MARCA_CONFIRMACION = "accionRequerida: CONFIRMACIÓN «todos»";
    static final String TITULO_INFORME = "Modo «todos»: resumen global";
    static final String MOTIVO_OMITIDO = "sin casos que generar (requiere refactor, sin funciones elegibles o ya en su máximo de casos)";

    private static final String MARCA_FINAL_JSON = "\"respuestaFinal\":";
    private static final String MARCA_FINAL_TEXTO = "\nrespuestaFinal:\n";
    private static final String CLAVE_ACCION = "accionRequerida";
    private static final String CLAVE_FINAL = "respuestaFinal";
    private static final String CLAVE_LLAMADA = "llamada";
    private static final String VALOR_VERDADERO = "true";
    private static final String VALOR_UNO = "1";
    private static final long MS_SEGUNDO = 1000L;
    private static final long SEGUNDOS_MINUTO = 60L;
    private static final AtomicBoolean AVISO_ENV = new AtomicBoolean();

    private OleadasTodos() {
    }

    // ------------------------------------------------------------------ configuración y estimación

    /** Valor de {@link #ENV_OLEADA} en [1, 100]; null si falta o no es válido (con aviso a stderr una sola vez). */
    private static Integer desdeEntorno() {
        String v;
        try {
            v = System.getenv(ENV_OLEADA);
        } catch (SecurityException e) {
            return null;
        }
        if (v == null || v.isBlank()) {
            return null;
        }
        try {
            int n = Integer.parseInt(v.trim());
            if (n >= MIN_TAM && n <= MAX_TAM) {
                return n;
            }
        } catch (NumberFormatException e) {
            // se avisa abajo
        }
        if (AVISO_ENV.compareAndSet(false, true)) {
            System.err.println("junit-gen: " + ENV_OLEADA + " debe ser un entero entre " + MIN_TAM + " y " + MAX_TAM
                    + "; se usa el valor por defecto (" + TAM_DEFECTO + ").");
        }
        return null;
    }

    /** Servicios por oleada: variable de entorno válida, si no la clave {@code oleada} de junit-gen.yml, si no 25. */
    static int tamOleada(Path proyecto) {
        Integer env = desdeEntorno();
        if (env != null) {
            return env;
        }
        try {
            Integer yml = CargadorConfig.oleada(proyecto);
            if (yml != null) {
                return yml;
            }
        } catch (RuntimeException e) {
            System.err.println("junit-gen: no se pudo leer 'oleada' de junit-gen.yml: " + e.getClass().getSimpleName());
        }
        return TAM_DEFECTO;
    }

    /** true si {@link #ENV_SIN_CONFIRMAR} vale 1 o true (necesario con `claude -p`: nadie responde a la confirmación). */
    static boolean sinConfirmarPorEntorno() {
        String v;
        try {
            v = System.getenv(ENV_SIN_CONFIRMAR);
        } catch (SecurityException e) {
            return false;
        }
        return v != null && (VALOR_UNO.equals(v.trim()) || VALOR_VERDADERO.equalsIgnoreCase(v.trim()));
    }

    static int numOleadas(int servicios, int tam) {
        return (servicios + tam - 1) / tam;
    }

    /** Estimación (texto) de servicios, oleadas, tiempo y coste con las cifras medidas. */
    static String estimacion(int servicios, int tam) {
        int oleadas = numOleadas(servicios, tam);
        int minutos = (servicios * MINUTOS_MEDIDA + SERVICIOS_MEDIDA - 1) / SERVICIOS_MEDIDA;
        double usd = servicios * USD_MEDIDA / SERVICIOS_MEDIDA;
        return servicios + (servicios == 1 ? " servicio" : " servicios") + " en " + oleadas + (oleadas == 1 ? " oleada" : " oleadas")
                + " de hasta " + tam + "; unos " + minutos + " min y unos " + String.format(Locale.ROOT, "%.2f", usd)
                + " USD en total (medido con Sonnet 5.5 high en paralelo: " + MINUTOS_MEDIDA + " min y "
                + String.format(Locale.ROOT, "%.2f", USD_MEDIDA) + " USD por cada " + SERVICIOS_MEDIDA
                + " servicios; sin subagentes tarda más)";
    }

    static String tiempo(long ms) {
        long s = Math.round(ms / (double) MS_SEGUNDO);
        if (s < SEGUNDOS_MINUTO) {
            return s + " s";
        }
        return (s / SEGUNDOS_MINUTO) + " min " + String.format(Locale.ROOT, "%02d", s % SEGUNDOS_MINUTO) + " s";
    }

    // ------------------------------------------------------------------ inicio de la campaña

    /**
     * Petición «todos» ya interpretada. Sin carpeta de salida pregunta por ella; sin límite explícito ni variable, pide UNA
     * confirmación (sin tocar plan, tanda ni disco); confirmada, crea la campaña y arranca la oleada 1.
     */
    static Herramienta.Resultado iniciar(JsonNode args, SesionMcp sesion, ProyectoEscaneado p, Path proyecto, Path salida,
                                         InformeEscaneo.Opciones op, boolean todas, Integer max, String modulo,
                                         InterpretePeticion.Lexico lx, InterpretePeticion.Resultado ip) {
        if (salida == null) {
            return new Herramienta.Resultado(RutasSesion.instruccionPreguntar(proyecto, sesion) + "\n\nDespués repite la llamada a "
                    + "escanear_proyecto con la misma petición y `carpetaSalida` = la elegida.\n", false);
        }
        String moduloLimpio = modulo == null || modulo.isBlank() ? null : modulo.trim();
        Oleadas previa = retomable(args, sesion, proyecto, salida, todas, moduloLimpio, max);
        if (previa != null) {
            int hechos;
            int total;
            synchronized (previa) {
                hechos = previa.hechos();
                total = previa.total();
            }
            sesion.anotar("Había una campaña «todos» sin cerrar con la misma petición (" + hechos + " de " + total + " servicios hechos): "
                    + "se retoma por su oleada pendiente; el orden de servicios no cambia. Para empezar otra: `todos` y confirmar.");
            return HerramientaEscanear.continuarEn(args, sesion, proyecto, salida);
        }
        PlanGeneracion plan = PlanGeneracion.calcularTodos(p, salida, moduloLimpio, todas, sesion.selecciones(proyecto), max);
        String pedidos = lx == null ? "" : HerramientaEscanear.bloquePeticion(lx, ip);
        if (plan.servicios().isEmpty() || plan.total() <= 0) {
            sesion.quitarPlan(proyecto);
            sesion.iniciarTanda(proyecto, null);
            op.siguientePaso = "No hay casos que generar para el plan: informa al usuario del motivo (avisos de «Plan de generación»).";
            return new Herramienta.Resultado(HerramientaEscanear.resumen(p, salida, op, pedidos, plan, proyecto, sesion), false);
        }
        int tam = tamOleada(proyecto);
        int servicios = plan.servicios().size();
        boolean confirmado = max != null || sinConfirmarPorEntorno() || Herramienta.bool(args, "confirmarTodos");
        if (!confirmado) {
            return new Herramienta.Resultado(confirmacion(sesion, proyecto, salida, servicios, tam, todas, moduloLimpio), false);
        }
        Oleadas sustituida = campanaPrevia(sesion, proyecto, salida);
        if (sustituida != null) {
            sesion.anotar("Se sustituye la campaña «todos» sin cerrar anterior (" + sustituida.hechos() + " de " + sustituida.total()
                    + " servicios hechos) por una nueva.");
        }
        Oleadas c = crear(proyecto, plan, todas, moduloLimpio, max, tam);
        sesion.fijarOleadas(proyecto, c);
        sesion.guardarOleadas(proyecto, salida);
        PlanGeneracion primera = siguienteOleada(sesion, proyecto, salida, c, args, p, op);
        if (primera == null) {
            return new Herramienta.Resultado(respuestaFinalJson(sesion, proyecto, salida, c), false);
        }
        String seccion = bloqueModo(c, estimacion(servicios, tam)) + pedidos;
        return new Herramienta.Resultado(HerramientaEscanear.resumen(p, salida, op, seccion, primera, proyecto, sesion), false);
    }

    private static String confirmacion(SesionMcp sesion, Path proyecto, Path salida, int servicios, int tam, boolean todas,
                                       String modulo) {
        StringBuilder llamada = new StringBuilder("escanear_proyecto {todosServicios: true, confirmarTodos: true");
        if (todas) {
            llamada.append(", todas: true");
        }
        if (modulo != null) {
            llamada.append(", modulo: \"").append(modulo.replace("\"", "")).append('"');
        }
        llamada.append("}");
        StringBuilder sb = new StringBuilder(MARCA_CONFIRMACION).append(": todavía no se ha generado nada. Pregunta al usuario "
                + "exactamente: «Hay ").append(servicios).append(servicios == 1 ? " servicio" : " servicios")
                .append(" y se trabajarán en ").append(numOleadas(servicios, tam)).append(" oleadas de hasta ").append(tam)
                .append(" servicios (estimación: ").append(estimacion(servicios, tam)).append("). ¿Seguimos? Puedes limitarlo con "
                        + "`todos máx N`.» Si dice que sí, llama a ").append(llamada).append(" (con la misma `ruta` y `carpetaSalida`; "
                        + "si limita el número, añade `maxServicios: N`). Si dice que no, no hagas nada; no se ha generado nada.");
        String previa = campanaSinCerrar(sesion, proyecto, salida);
        if (previa != null) {
            sb.append(' ').append(previa);
        }
        return sb.append('\n').toString();
    }

    /** Frase sobre una campaña «todos» sin cerrar del mismo proyecto, o null. */
    private static String campanaSinCerrar(SesionMcp sesion, Path proyecto, Path salida) {
        Oleadas c = campanaPrevia(sesion, proyecto, salida);
        if (c == null) {
            return null;
        }
        return "Hay una campaña «todos» sin cerrar (" + c.hechos() + " de " + c.total() + " servicios hechos): `continuar` la retoma; "
                + "confirmar la sustituye.";
    }

    /** Campaña «todos» sin cerrar del mismo proyecto (memoria o salida), o null. Una salida corrupta se ignora con aviso. */
    private static Oleadas campanaPrevia(SesionMcp sesion, Path proyecto, Path salida) {
        Oleadas c = sesion.oleadas(proyecto);
        if (c == null) {
            try {
                c = AlmacenOleadas.leer(salida);
                if (c != null && !mismoProyecto(c.proyecto, proyecto)) {
                    c = null;
                }
            } catch (RuntimeException e) {
                System.err.println("junit-gen: se ignora la campaña previa de la salida: " + e.getMessage());
                c = null;
            }
        }
        return c == null || c.cerrada ? null : c;
    }

    /**
     * Campaña sin cerrar que una petición «todos» debe retomar en vez de sustituir: solo con petición idéntica (máximo, todas y
     * módulo) y sin {@code confirmarTodos}, y solo cuando la petición ya sería inequívoca (máximo explícito o sin confirmar por
     * entorno). Si la salida tiene un oleadas.json del mismo proyecto con otra {@code creada}, manda el de disco. Si no, null.
     */
    static Oleadas retomable(JsonNode args, SesionMcp sesion, Path proyecto, Path salida, boolean todas, String modulo, Integer max) {
        if (Herramienta.bool(args, "confirmarTodos") || (max == null && !sinConfirmarPorEntorno())) {
            return null;
        }
        Oleadas c;
        try {
            c = cargar(sesion, proyecto, salida);
            if (c != null) {
                Oleadas d = AlmacenOleadas.leer(salida);
                if (d != null && !d.cerrada && mismoProyecto(d.proyecto, proyecto) && !Objects.equals(d.creada, c.creada)) {
                    sesion.fijarOleadas(proyecto, d);
                    c = d;
                }
            }
        } catch (RuntimeException e) {
            System.err.println("junit-gen: no se puede retomar la campaña «todos» previa: " + e.getClass().getSimpleName());
            return null;
        }
        if (c == null) {
            return null;
        }
        synchronized (c) {
            boolean igual = Objects.equals(c.maximo, max) && Boolean.TRUE.equals(c.todas) == todas && Objects.equals(c.modulo, modulo);
            return igual ? c : null;
        }
    }

    private static Oleadas crear(Path proyecto, PlanGeneracion plan, boolean todas, String modulo, Integer max, int tam) {
        Oleadas c = new Oleadas();
        c.proyecto = proyecto.toString();
        c.creada = Instant.now().toString();
        c.actualizada = c.creada;
        c.todas = todas ? Boolean.TRUE : null;
        c.modulo = modulo;
        c.maximo = max;
        c.tamOleada = tam;
        List<PlanGeneracion.ServicioPlan> sp = plan.servicios();
        for (int i = 0; i < sp.size(); i++) {
            Oleadas.ServicioOleada s = new Oleadas.ServicioOleada();
            s.clase = sp.get(i).clase.fqn;
            s.modulo = sp.get(i).modulo == null ? null : sp.get(i).modulo.modulo.rel;
            s.oleada = i / tam + 1;
            c.servicios.add(s);
        }
        int n = numOleadas(sp.size(), tam);
        for (int k = 1; k <= n; k++) {
            Oleadas.ResumenOleada o = new Oleadas.ResumenOleada();
            o.numero = k;
            o.servicios = (int) c.servicios.stream().filter(s -> s.oleada == o.numero).count();
            c.oleadas.add(o);
        }
        return c;
    }

    /** Bloque «## Modo «todos»» de la respuesta que arranca la campaña. */
    private static String bloqueModo(Oleadas c, String estimacion) {
        synchronized (c) {
            return "## Modo «todos»\n\n- Servicios: " + c.servicios.size() + (c.maximo == null ? "" : " (máx. " + c.maximo + ")") + "\n"
                    + "- Oleadas: " + c.oleadas.size() + " de hasta " + c.tamOleada + " servicios (cada una es una tanda; al cerrar "
                    + "una, junit-gen te indica la siguiente)\n- Estimación: " + estimacion + "\n\n";
        }
    }

    // ------------------------------------------------------------------ avance entre oleadas

    /**
     * Activa la primera oleada no cerrada: su plan (los servicios de la oleada que aún den casos) se fija como una tanda
     * normal. Las oleadas sin casos se cierran y se saltan. Devuelve el plan activado, o null si no quedan oleadas.
     */
    static PlanGeneracion siguienteOleada(SesionMcp sesion, Path proyecto, Path salida, Oleadas c, JsonNode args,
                                          ProyectoEscaneado p, InformeEscaneo.Opciones op) {
        while (true) {
            Oleadas.ResumenOleada o = c.actual();
            if (o == null) {
                return null;
            }
            List<String> fqns = new ArrayList<>();
            synchronized (c) {
                for (Oleadas.ServicioOleada s : c.servicios) {
                    if (s.oleada == o.numero && Oleadas.SERVICIO_PENDIENTE.equals(s.estado)) {
                        fqns.add(s.clase);
                    }
                }
            }
            boolean todas = Boolean.TRUE.equals(c.todas);
            PlanGeneracion plan = null;
            if (!fqns.isEmpty()) {
                plan = PlanGeneracion.calcular(p, salida, null, c.modulo, fqns, todas, sesion.selecciones(proyecto),
                        CargadorConfig.ordenServicios(proyecto));
            }
            Set<String> enPlan = plan == null ? Set.of() : new HashSet<>(plan.fqns());
            synchronized (c) {
                for (Oleadas.ServicioOleada s : c.servicios) {
                    if (s.oleada == o.numero && Oleadas.SERVICIO_PENDIENTE.equals(s.estado) && !enPlan.contains(s.clase)) {
                        s.estado = Oleadas.SERVICIO_OMITIDO;
                        s.motivo = MOTIVO_OMITIDO;
                    }
                }
            }
            if (plan == null || plan.total() <= 0) {
                synchronized (c) {
                    o.estado = Oleadas.OLEADA_CERRADA;
                    o.inicio = o.inicio == null ? Instant.now().toString() : o.inicio;
                    o.fin = Instant.now().toString();
                }
                sesion.guardarOleadas(proyecto, salida);
                continue;
            }
            sesion.fijarTodas(proyecto, todas);
            sesion.recordarModulos(proyecto, plan.modulosRel());
            synchronized (c) {
                o.estado = Oleadas.OLEADA_EN_CURSO;
                if (o.inicio == null) {
                    o.inicio = Instant.now().toString();
                }
            }
            HerramientaEscanear.activarPlan(sesion, proyecto, salida, plan, null, todas, args, op, o.numero, c.oleadas.size());
            sesion.guardarOleadas(proyecto, salida);
            return plan;
        }
    }

    /** Campaña del proyecto: la de memoria o, si no hay y la de la salida es del mismo proyecto y no está cerrada, esa. */
    static Oleadas cargar(SesionMcp sesion, Path proyecto, Path salida) {
        Oleadas c = sesion.oleadas(proyecto);
        if (c != null) {
            return c.cerrada ? null : c;
        }
        Oleadas d = AlmacenOleadas.leer(salida);
        if (d != null && !d.cerrada && mismoProyecto(d.proyecto, proyecto)) {
            sesion.fijarOleadas(proyecto, d);
            return d;
        }
        return null;
    }

    /** Tanda en memoria del proyecto o, si no hay, la de la salida si es del mismo proyecto; null si no hay. */
    static Tanda tandaActual(SesionMcp sesion, Path proyecto, Path salida) {
        Tanda t = sesion.tanda(proyecto);
        if (t != null) {
            return t;
        }
        Tanda d = AlmacenTanda.leer(salida);
        return d != null && mismoProyecto(d.proyecto, proyecto) ? d : null;
    }

    private static boolean mismoProyecto(String ruta, Path proyecto) {
        if (ruta == null || ruta.isBlank()) {
            return false;
        }
        try {
            return RutaSegura.iguales(Path.of(ruta), proyecto);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * true si `continuar` debe empezar la siguiente oleada: campaña del mismo proyecto sin cerrar y sin tanda, o con la tanda
     * de otra oleada, o con la tanda completa y cerrada (o sin verificación). Una tanda normal sin terminar no avanza.
     */
    static boolean debeAvanzar(Oleadas c, Tanda t, Path proyecto) {
        if (c == null || c.cerrada || !mismoProyecto(c.proyecto, proyecto)) {
            return false;
        }
        Oleadas.ResumenOleada a = c.actual();
        if (a == null || t == null) {
            return true;
        }
        if (t.oleada != null && t.oleada != a.numero) {
            return true;
        }
        return t.todoCompleto() && tandaTerminada(t, proyecto);
    }

    /** `continuar` que empieza la siguiente oleada (o cierra la campaña si no queda ninguna). */
    static Herramienta.Resultado continuarCampana(JsonNode args, SesionMcp sesion, Path proyecto, Path salida, Oleadas c, Tanda t) {
        Oleadas.ResumenOleada a = c.actual();
        if (a != null && t != null && t.oleada != null && t.oleada == a.numero && t.todoCompleto()) {
            consolidar(sesion, proyecto, salida, c, a.numero);
        }
        ProyectoEscaneado p = sesion.fachada().proyectoVigente(proyecto, false);
        HerramientaEscanear.anotarPerfil(sesion, p, salida);
        PlanGeneracion plan = siguienteOleada(sesion, proyecto, salida, c, args, p, new InformeEscaneo.Opciones());
        if (plan == null) {
            return new Herramienta.Resultado(respuestaFinalJson(sesion, proyecto, salida, c), false);
        }
        Tanda nueva = sesion.tanda(proyecto);
        int k = nueva == null || nueva.oleada == null ? 0 : nueva.oleada;
        return new Herramienta.Resultado(HerramientaEscanear.respuestaContinuar(sesion, proyecto, salida,
                "## Oleada " + k + " de " + c.oleadas.size() + " («todos»)", 0), false);
    }

    // ------------------------------------------------------------------ cierre de oleada

    /** Tanda de la oleada indicada (disco refrescado o, si no, memoria), o null. */
    private static Tanda tandaDeOleada(SesionMcp sesion, Path proyecto, Path salida, int numero) {
        Tanda mem = sesion.tanda(proyecto);
        if (mem != null && mem.oleada != null && mem.oleada == numero) {
            sesion.guardarTanda(proyecto, salida);
        }
        try {
            Tanda d = AlmacenTanda.leer(salida);
            if (d != null && d.oleada != null && d.oleada == numero) {
                return d;
            }
        } catch (RuntimeException e) {
            System.err.println("junit-gen: no se pudo leer la tanda para cerrar la oleada: " + e.getMessage());
        }
        return mem != null && mem.oleada != null && mem.oleada == numero ? mem : null;
    }

    /**
     * Da por cerrada la oleada: cifras desde la tanda de la oleada y spec.json (no del acumulado de la sesión), servicios
     * hechos, tiempo y guardado. Idempotente por número de oleada.
     */
    static void consolidar(SesionMcp sesion, Path proyecto, Path salida, Oleadas c, int numero) {
        Oleadas.ResumenOleada o = c.oleada(numero);
        if (o == null) {
            return;
        }
        synchronized (c) {
            if (Oleadas.OLEADA_CERRADA.equals(o.estado)) {
                return;
            }
        }
        Tanda t = tandaDeOleada(sesion, proyecto, salida, numero);
        Spec spec = null;
        try {
            spec = AlmacenSpec.leer(salida);
        } catch (RuntimeException e) {
            System.err.println("junit-gen: no se pudo leer spec.json para cerrar la oleada: " + e.getMessage());
        }
        Oleadas.ResumenOleada cifras = new Oleadas.ResumenOleada();
        Set<String> incompletos = new HashSet<>();
        Set<String> enTanda = new HashSet<>();
        if (t != null) {
            for (Tanda.ServicioTanda s : t.servicios) {
                if (s == null || s.clase == null) {
                    continue;
                }
                enTanda.add(s.clase);
                if (s.cierre != null || !s.completo) {
                    incompletos.add(s.clase);
                }
                if (s.metodos == null) {
                    continue;
                }
                for (Tanda.MetodoTanda m : s.metodos) {
                    if (m == null) {
                        continue;
                    }
                    cifras.duplicados += m.duplicados == null ? 0 : m.duplicados.size();
                    if (m.generados != null) {
                        for (String id : m.generados) {
                            contar(cifras, spec, id);
                        }
                    }
                }
            }
            for (Tanda.ArregloTanda a : t.arreglos) {
                if (a == null) {
                    continue;
                }
                if (Tanda.ARREGLO_ARREGLADO.equals(a.estado)) {
                    cifras.arreglados++;
                } else if (Tanda.ARREGLO_POSIBLE_BUG.equals(a.estado)) {
                    cifras.posiblesBugs++;
                } else if (Tanda.ARREGLO_APARTADO.equals(a.estado) || Tanda.ARREGLO_DUDOSO.equals(a.estado)) {
                    cifras.apartados++;
                }
            }
        }
        synchronized (c) {
            if (Oleadas.OLEADA_CERRADA.equals(o.estado)) {
                return;
            }
            o.casosGenerados = cifras.casosGenerados;
            o.pasan = cifras.pasan;
            o.fallosAsercion = cifras.fallosAsercion;
            o.montajeFallido = cifras.montajeFallido;
            o.sinVerificar = cifras.sinVerificar;
            o.duplicados = cifras.duplicados;
            o.arreglados = cifras.arreglados;
            o.posiblesBugs = cifras.posiblesBugs;
            o.apartados = cifras.apartados;
            o.serviciosIncompletos = incompletos.size();
            for (Oleadas.ServicioOleada s : c.servicios) {
                if (s.oleada == numero && Oleadas.SERVICIO_PENDIENTE.equals(s.estado)) {
                    s.estado = Oleadas.SERVICIO_HECHO;
                    if (incompletos.contains(s.clase)) {
                        s.incompleto = Boolean.TRUE;
                    }
                }
            }
            Instant fin = Instant.now();
            o.fin = fin.toString();
            o.ms = o.inicio == null ? 0 : duracionMs(o.inicio, fin);
            o.estado = Oleadas.OLEADA_CERRADA;
        }
        sesion.guardarOleadas(proyecto, salida);
    }

    private static long duracionMs(String inicio, Instant fin) {
        try {
            return Math.max(0, Duration.between(Instant.parse(inicio), fin).toMillis());
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** Suma un caso generado a las cifras según su estado en spec.json (los eliminados y los que no están no cuentan). */
    private static void contar(Oleadas.ResumenOleada r, Spec spec, String id) {
        AlmacenSpec.Ubicacion u = id == null || spec == null ? null : AlmacenSpec.buscar(spec, id);
        if (u == null || HerramientaEscanear.ESTADO_ELIMINADO.equals(u.caso().estado)) {
            return;
        }
        r.casosGenerados++;
        String e = u.caso().estado;
        if (ClasificadorFallos.PASA.equals(e)) {
            r.pasan++;
        } else if (CasoSpec.ESTADO_FALLO_ASERCION.equals(e) || Tanda.ARREGLO_POSIBLE_BUG.equals(e) || Tanda.ARREGLO_DUDOSO.equals(e)) {
            r.fallosAsercion++;
        } else if (HerramientaEjecutar.ESTADO_SPEC_MONTAJE_FALLIDO.equals(e)) {
            r.montajeFallido++;
        } else {
            r.sinVerificar++;
        }
    }

    // ------------------------------------------------------------------ interceptación de respuestas

    /**
     * Punto único (bajo el cerrojo de sesión) por el que pasa el texto de cada herramienta: si es el cierre de la tanda de
     * una oleada del modo «todos» (respuestaFinal, sin trabajo ni preguntas pendientes), lo sustituye por la orden de seguir con
     * la siguiente oleada o, tras la última, por el informe global. En cualquier otro caso devuelve texto y error tal cual.
     */
    static Herramienta.Resultado trasRespuesta(SesionMcp sesion, String herramienta, String texto, boolean error) {
        Herramienta.Resultado original = new Herramienta.Resultado(texto, error);
        if (texto == null || sesion.bloqueLlamada() != null
                || !(texto.contains(MARCA_FINAL_JSON) || texto.contains(MARCA_FINAL_TEXTO))) {
            return original;
        }
        Path proyecto = sesion.fachada().ultimaRuta();
        if (proyecto == null) {
            return original;
        }
        Oleadas c = sesion.oleadas(proyecto);
        Tanda t = sesion.tanda(proyecto);
        if (c == null || t == null || t.oleada == null) {
            return original;
        }
        // La tanda es la de la oleada en curso, o la de una ya cerrada (llamada repetida tras el cierre: mismo resultado).
        Oleadas.ResumenOleada deTanda = c.oleada(t.oleada);
        if (deTanda == null || (!Oleadas.OLEADA_CERRADA.equals(deTanda.estado) && deTanda != c.actual())) {
            return original;
        }
        Path salida = sesion.salidaDe(proyecto);
        if (salida == null) {
            return original;
        }
        SesionMcp.ProgresoPlan pp = sesion.progresoPlanGlobal(proyecto);
        String motivo = null;
        if (pp != null && pp.restantes() != 0) {
            motivo = "quedan servicios por completar en el plan";
        } else if (sesion.asercionesSinRespuesta(salida) != 0) {
            motivo = "hay fallos de aserción sin respuesta";
        } else if (sesion.verificacionSinRespuesta()) {
            motivo = "hay una decisión de verificación sin respuesta";
        } else if (!tandaTerminada(t, proyecto)) {
            motivo = "la tanda no está cerrada";
        }
        if (motivo != null) {
            System.err.println("junit-gen: la oleada " + t.oleada + " no se cierra con esta respuesta: " + motivo + ".");
            return original;
        }
        int numero = deTanda.numero;
        consolidar(sesion, proyecto, salida, c, numero);
        if (c.actual() == null) {
            return new Herramienta.Resultado(respuestaFinalJson(sesion, proyecto, salida, c), false);
        }
        return new Herramienta.Resultado(oleadaCerrada(c, numero, proyecto, salida, t), false);
    }

    /** true si la tanda está cerrada (ejecutar_tests la cerró) o la verificación del proyecto es «nunca». */
    private static boolean tandaTerminada(Tanda t, Path proyecto) {
        return t.cerrada || CargadorConfig.modoVerificacion(proyecto) == ConfigProyecto.ModoVerificacion.NUNCA;
    }

    /**
     * JSON «OLEADA k de N CERRADA»: accionRequerida con los argumentos exactos de la llamada siguiente, cifras de la oleada y
     * progreso de la campaña. accionRequerida debe seguir siendo la primera clave (GuiaTanda.cabecera la detecta).
     */
    private static String oleadaCerrada(Oleadas c, int numero, Path proyecto, Path salida, Tanda tanda) {
        synchronized (c) {
            Oleadas.ResumenOleada o = c.oleada(numero);
            ObjectNode r = Json.objeto();
            r.put(CLAVE_ACCION, "NO HAS TERMINADO: OLEADA " + numero + " de " + c.oleadas.size() + " CERRADA (" + c.hechos() + " de "
                    + c.total() + " servicios). Llama ahora a escanear_proyecto {continuar: true, ruta: \"" + proyecto
                    + "\", carpetaSalida: \"" + salida + "\"} (argumentos exactos en `llamada`): empieza la oleada " + (numero + 1)
                    + " (subagentes NUEVOS; no reutilices los anteriores). No escribas nada al usuario hasta respuestaFinal.");
            ObjectNode llamada = r.putObject(CLAVE_LLAMADA);
            llamada.put("herramienta", "escanear_proyecto");
            ObjectNode argumentos = llamada.putObject("argumentos");
            argumentos.put("continuar", true);
            argumentos.put("ruta", proyecto.toString());
            argumentos.put("carpetaSalida", salida.toString());
            argumentos.put("paralelo", BloquesTanda.activo(tanda));
            ObjectNode ol = Json.MAPPER.valueToTree(o);
            ol.put("tiempo", tiempo(o.ms));
            r.set("oleada", ol);
            ObjectNode todos = r.putObject("todos");
            todos.put("oleadas", c.oleadas.size());
            todos.put("oleadasCerradas", (int) c.oleadas.stream().filter(x -> Oleadas.OLEADA_CERRADA.equals(x.estado)).count());
            todos.put("serviciosHechos", c.hechos());
            todos.put("serviciosTotal", c.total());
            return Json.compacto(r);
        }
    }

    // ------------------------------------------------------------------ informe global

    /** JSON {respuestaFinal, instruccion} del informe global (lo genera y escribe una sola vez). */
    private static String respuestaFinalJson(SesionMcp sesion, Path proyecto, Path salida, Oleadas c) {
        String md = cerrar(sesion, proyecto, salida, c);
        ObjectNode r = Json.objeto();
        r.put(CLAVE_FINAL, md);
        r.put("instruccion", SesionMcp.INSTRUCCION_RESUMEN);
        return Json.compacto(r);
    }

    /**
     * Cierra la campaña: sección global de informe.md (una sola vez), marca de cerrada y guardado. Devuelve el markdown de la
     * respuesta final global.
     */
    static String cerrar(SesionMcp sesion, Path proyecto, Path salida, Oleadas c) {
        String cuerpo = null;
        synchronized (c) {
            if (!c.informada) {
                c.informada = true;
                cuerpo = informeMd(c);
            }
        }
        if (cuerpo != null && salida != null) {
            try {
                sesion.fachada().anotarSeccion(proyecto, salida, TITULO_INFORME, cuerpo);
            } catch (RuntimeException e) {
                synchronized (c) {
                    c.informada = false;
                }
                System.err.println("junit-gen: no se pudo escribir el resumen global en el informe: " + e.getClass().getSimpleName());
            }
        }
        synchronized (c) {
            c.cerrada = true;
        }
        sesion.guardarOleadas(proyecto, salida);
        return RespuestaFinal.armarTodos(c, salida);
    }

    /** Tabla de totales y una fila por oleada para informe.md. */
    private static String informeMd(Oleadas c) {
        int casos = 0;
        int pasan = 0;
        int fallos = 0;
        int montaje = 0;
        int sinVerificar = 0;
        int duplicados = 0;
        int arreglados = 0;
        int bugs = 0;
        int apartados = 0;
        int incompletos = 0;
        long ms = 0;
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
            incompletos += o.serviciosIncompletos;
            ms += o.ms;
        }
        int omitidos = (int) c.servicios.stream().filter(s -> Oleadas.SERVICIO_OMITIDO.equals(s.estado)).count();
        StringBuilder sb = new StringBuilder();
        sb.append("- Servicios: ").append(c.servicios.size()).append(" (hechos: ").append(c.hechos()).append(", omitidos: ")
                .append(omitidos).append(", incompletos: ").append(incompletos).append(")\n");
        sb.append("- Oleadas: ").append(c.oleadas.size()).append(" de hasta ").append(c.tamOleada).append(" servicios; tiempo total: ")
                .append(tiempo(ms)).append('\n');
        sb.append("- Casos generados: ").append(casos).append("; pasan: ").append(pasan).append("; fallos de aserción: ")
                .append(fallos).append("; apartados por montaje: ").append(montaje).append("; sin verificar: ").append(sinVerificar)
                .append("; duplicados: ").append(duplicados).append('\n');
        sb.append("- Arreglados al final: ").append(arreglados).append("; posibles bugs: ").append(bugs).append("; apartados: ")
                .append(apartados).append("\n\n");
        sb.append("| Oleada | Servicios | Casos | Pasan | Fallos de aserción | Montaje fallido | Sin verificar | Duplicados | Arreglados "
                + "| Posibles bugs | Apartados | Incompletos | Tiempo |\n|---|---|---|---|---|---|---|---|---|---|---|---|---|\n");
        for (Oleadas.ResumenOleada o : c.oleadas) {
            sb.append("| ").append(o.numero).append(" | ").append(o.servicios).append(" | ").append(o.casosGenerados).append(" | ")
                    .append(o.pasan).append(" | ").append(o.fallosAsercion).append(" | ").append(o.montajeFallido).append(" | ")
                    .append(o.sinVerificar).append(" | ").append(o.duplicados).append(" | ").append(o.arreglados).append(" | ")
                    .append(o.posiblesBugs).append(" | ").append(o.apartados).append(" | ").append(o.serviciosIncompletos)
                    .append(" | ").append(tiempo(o.ms)).append(" |\n");
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ textos de posición y progreso

    /** «oleada k de N; » para la línea de acción de una tanda de oleada; vacío en el resto. */
    static String prefijoPosicion(SesionMcp sesion, Path proyecto) {
        try {
            Oleadas c = sesion.oleadas(proyecto);
            Tanda t = sesion.tanda(proyecto);
            if (c == null || c.cerrada || t == null || t.oleada == null) {
                return "";
            }
            return "oleada " + t.oleada + " de " + (t.oleadas != null ? t.oleadas : c.oleadas.size()) + "; ";
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** Línea «- Modo «todos»: oleada k de N; servicios hechos x de T» de `continuar`, o vacía si no hay campaña activa. */
    static String lineaProgreso(SesionMcp sesion, Path proyecto) {
        try {
            Oleadas c = sesion.oleadas(proyecto);
            if (c == null || c.cerrada) {
                return "";
            }
            Tanda t = sesion.tanda(proyecto);
            Oleadas.ResumenOleada a = c.actual();
            int k = t != null && t.oleada != null ? t.oleada : a == null ? c.oleadas.size() : a.numero;
            return "- Modo «todos»: oleada " + k + " de " + c.oleadas.size() + "; servicios hechos " + c.hechos() + " de " + c.total()
                    + "\n";
        } catch (RuntimeException e) {
            return "";
        }
    }
}
