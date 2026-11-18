package com.junitgen.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.junitgen.config.CargadorConfig;
import com.junitgen.config.ConfigProyecto;
import com.junitgen.escaneo.BuscadorServicios;
import com.junitgen.escaneo.DetectorServicios;
import com.junitgen.escaneo.InfoClase;
import com.junitgen.escaneo.InformeEscaneo;
import com.junitgen.escaneo.InterpretePeticion;
import com.junitgen.escaneo.ModuloEscaneado;
import com.junitgen.escaneo.PlanGeneracion;
import com.junitgen.escaneo.ProyectoEscaneado;
import com.junitgen.escaneo.SeleccionFunciones;
import com.junitgen.generacion.AlmacenOleadas;
import com.junitgen.generacion.AlmacenSpec;
import com.junitgen.generacion.AlmacenTanda;
import com.junitgen.generacion.Oleadas;
import com.junitgen.generacion.Tanda;
import com.junitgen.perfil.AlmacenPerfil;
import com.junitgen.perfil.ConstructorPerfil;
import com.junitgen.seguridad.RutaSegura;
import com.junitgen.spec.CasoSpec;
import com.junitgen.spec.ErrorValidacion;
import com.junitgen.spec.SeleccionSpec;
import com.junitgen.spec.ServicioSpec;
import com.junitgen.spec.Spec;
import com.junitgen.util.LimitesRespuesta;
import com.junitgen.util.Textos;
import com.junitgen.verificacion.ClasificadorFallos;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * escanear_proyecto: resumen breve del proyecto (solo lectura) y, con 'numServicios' (N = número de servicios)
 * o con servicios nombrados, el plan de generación (provisional hasta que la IA elija las funciones con
 * seleccionar_funciones; con 'todas', el 100 %; con 'funciones', las que nombró el usuario). Las secciones largas se piden con
 * 'seccion' + 'pagina' sin recalcular el plan.
 */
public class HerramientaEscanear implements Herramienta {

    static final String NO_ENCONTRADO = "no encontrado en ningún módulo";
    static final String INTERFAZ_SIN_IMPL = "no encontrado (interfaz sin implementación)";
    /** Pregunta exacta cuando no hay argumentos (ni servicios ni número). */
    static final String PREGUNTA_SIN_ARGUMENTOS = "¿Cuántos servicios (N) o qué servicios quieres probar? junit-gen elegirá sus "
            + "funciones más relevantes (añade `todas` para probarlas todas)";
    /** Palabra del usuario que activa el modo 100 % de las funciones. */
    static final String TODAS = "todas";
    /** Texto libre del usuario tras /generartest, tal cual: lo interpreta {@link InterpretePeticion}. */
    static final String PETICION = "peticion";
    /** Máximo de caracteres de la petición devuelta en la respuesta. */
    static final int MAX_ECO = 160;
    /** Máximo de avisos de servicios no encontrados. */
    static final int MAX_AVISOS_NO_ENCONTRADOS = 5;
    /** Máximo de nombres parecidos por servicio no encontrado. */
    static final int MAX_PARECIDOS = 3;

    @Override
    public String nombre() {
        return "escanear_proyecto";
    }

    @Override
    public String titulo() {
        return "Escanear proyecto Java";
    }

    @Override
    public String descripcion() {
        return "Escanea en solo lectura el proyecto Java (Maven/Gradle, varios módulos) y devuelve resumen, plan de generación y "
                + "«Siguiente paso»: síguelo sin pedir confirmación. Llámala la primera. Pasa la frase del usuario tal cual en "
                + "'peticion' (junit-gen deduce numServicios, servicios, todas, funciones o continuar) y en 'ruta' la raíz del "
                + "workspace que ya conoces, nunca un módulo. Un número N del usuario es siempre numServicios. Sin 'carpetaSalida' "
                + "usa la ruta por defecto o la pregunta una vez. 'continuar'=true si el usuario escribe `continuar` o perdiste el "
                + "hilo de la tanda. 'paralelo'=true si tienes subagentes (Agent en Claude Code; task o runSubagent en Copilot): reparte el plan "
                + "en bloques y devuelve el promptTrabajador. Frase «todos» (todos los servicios, `todos máx N`): va por oleadas; sigue "
                + "accionRequerida. Cada caso nuevo: queHaceOriginal una vez por función y queComprueba "
                + "en cada caso. Sigue accionRequerida. Secciones largas: 'seccion'+'pagina', solo si las piden.";
    }

    @Override
    public ObjectNode esquema() {
        ObjectNode s = Herramienta.esquemaBase();
        Herramienta.propiedad(s, "ruta", "string", "Raíz del workspace (nunca un módulo); por defecto, la carpeta abierta en el IDE.", false);
        Herramienta.propiedad(s, PETICION, "string", "Frase del usuario tras /generartest, tal cual: junit-gen la interpreta.", false);
        Herramienta.propiedad(s, "servicios", "array", "Servicios nombrados por el usuario (nombre simple o FQN).", false);
        Herramienta.propiedad(s, "numServicios", "integer", "N = nº de servicios (" + PlanGeneracion.MIN_SERVICIOS + ".."
                + PlanGeneracion.MAX_SERVICIOS + "), nunca de tests.", false);
        ObjectNode num = (ObjectNode) s.get("properties").get("numServicios");
        num.put("minimum", PlanGeneracion.MIN_SERVICIOS);
        num.put("maximum", PlanGeneracion.MAX_SERVICIOS);
        Herramienta.propiedad(s, "todas", "boolean", "Solo si el usuario escribió `todas`: el 100 % de las funciones.", false);
        Herramienta.propiedad(s, "funciones", "array", "Solo si el usuario nombró funciones de UN servicio; sin seleccionar_funciones.", false);
        Herramienta.propiedad(s, "carpetaSalida", "string", "Carpeta de salida; por defecto, la de la sesión o la guardada.", false);
        Herramienta.propiedad(s, "guardarRutaPorDefecto", "boolean", "El usuario pidió guardar carpetaSalida como ruta por defecto.", false);
        Herramienta.propiedad(s, "seccion", "string", "Sección larga bajo petición (con 'pagina'); no recalcula el plan.", false);
        ArrayNode en = ((ObjectNode) s.get("properties").get("seccion")).putArray("enum");
        InformeEscaneo.SECCIONES.forEach(en::add);
        Herramienta.propiedad(s, "modulo", "string", "Limitar el informe a un módulo.", false);
        Herramienta.propiedad(s, "filtro", "string", "Texto que debe contener el FQN del servicio.", false);
        Herramienta.propiedad(s, "soloSinCubrir", "boolean", "Solo servicios con métodos sin tests.", false);
        Herramienta.propiedad(s, "pagina", "integer", "Página de 'seccion' (desde 1).", false);
        Herramienta.propiedad(s, "tamPagina", "integer", "Filas por página en seccion=servicios (por defecto 40, máx. 100).", false);
        Herramienta.propiedad(s, "refrescar", "boolean", "Ignora la caché y vuelve a escanear.", false);
        Herramienta.propiedad(s, "continuar", "boolean", "Retoma la tanda guardada: el usuario escribió `continuar` o perdiste el hilo.", false);
        Herramienta.propiedad(s, "paralelo", "boolean", "Si tienes subagentes (Agent, task o runSubagent): reparte el plan en bloques.", false);
        Herramienta.propiedad(s, "todosServicios", "boolean", "Solo si el usuario pidió todos los servicios (`todos`): modo por oleadas.", false);
        Herramienta.propiedad(s, "maxServicios", "integer", "Con todosServicios: máximo de servicios (`todos máx N`); sin confirmación.", false);
        ((ObjectNode) s.get("properties").get("maxServicios")).put("minimum", PlanGeneracion.MIN_SERVICIOS);
        Herramienta.propiedad(s, "confirmarTodos", "boolean", "Con todosServicios: el usuario ya confirmó la estimación.", false);
        return s;
    }

    @Override
    public boolean soloLectura() {
        return true;
    }

    @Override
    public String aviso(JsonNode args, SesionMcp sesion) {
        return RutasSesion.aviso(args, sesion, "ruta", "carpetaSalida", "(carpeta abierta en el IDE)");
    }

    /** 'numServicios' validado (1..200) o null si no se pasó. 'cantidad' ya no existe: error claro. */
    static Integer numServicios(JsonNode args) {
        if (args != null && args.has("cantidad")) {
            throw new IllegalArgumentException("'cantidad' ya no existe: usa 'numServicios' (N = nº de servicios; se generan tests "
                    + "de sus funciones seleccionadas).");
        }
        JsonNode v = args == null ? null : args.get("numServicios");
        if (v == null || v.isNull() || (v.isTextual() && v.asText().isBlank())) {
            return null;
        }
        String error = errorRangoNumServicios();
        int c;
        try {
            c = v.isIntegralNumber() ? v.asInt() : Integer.parseInt(v.asText().trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(error);
        }
        if (!v.isTextual() && !v.isIntegralNumber() || c < PlanGeneracion.MIN_SERVICIOS || c > PlanGeneracion.MAX_SERVICIOS) {
            throw new IllegalArgumentException(error);
        }
        return c;
    }

    /** Mensaje de error de 'numServicios' fuera de rango (también para el número deducido de 'peticion'). */
    static String errorRangoNumServicios() {
        return "numServicios debe ser un entero entre " + PlanGeneracion.MIN_SERVICIOS + " y " + PlanGeneracion.MAX_SERVICIOS + ".";
    }

    static final int MAX_PEDIDOS = 15;
    static final int MAX_MODULOS_RESUMEN = 5;
    static final String SECCIONES_BAJO_PETICION = "Secciones bajo petición (solo si el usuario las pide; no recalculan el plan): "
            + "escanear_proyecto {seccion: \"plan\" | \"servicios\" | \"estilo\" | \"avisos\" | \"refactor\" | \"dudosos\" | "
            + "\"descartes\", pagina: n}.\n";
    static final String PROHIBICIONES = "No uses terminal ni leas archivos; no abras carpetas de salida de sesiones anteriores.";

    @Override
    public Resultado ejecutar(JsonNode args, SesionMcp sesion) {
        String nombreSeccion = Herramienta.texto(args, "seccion");
        if (nombreSeccion != null) {
            return seccion(args, sesion, nombreSeccion.trim().toLowerCase(Locale.ROOT));
        }
        if (Herramienta.bool(args, CONTINUAR)) {
            return continuar(args, sesion);
        }
        String peticion = Herramienta.texto(args, PETICION);
        boolean serviciosEsFrase = false;
        if (peticion == null) {
            List<String> dados = Herramienta.lista(args, "servicios");
            for (String x : dados) {
                if (x != null && x.trim().chars().anyMatch(Character::isWhitespace)) {
                    serviciosEsFrase = true;
                    break;
                }
            }
            if (serviciosEsFrase) {
                // El modelo metió la frase del usuario en 'servicios': se trata como 'peticion'.
                peticion = String.join(" ", dados);
            }
        }
        InterpretePeticion.Lexico lx = null;
        if (peticion != null) {
            lx = InterpretePeticion.lexico(peticion);
            boolean hayEstructurados = presente(args, "numServicios")
                    || (!serviciosEsFrase && !Herramienta.lista(args, "servicios").isEmpty())
                    || !Herramienta.lista(args, "funciones").isEmpty();
            if (!hayEstructurados && lx.intencion() == InterpretePeticion.Intencion.RUTA) {
                return new Resultado("accionRequerida: el usuario pide cambiar la carpeta de salida («" + eco(lx) + "»): llama "
                        + "ahora a `cambiar_ruta_salida` (sin `carpetaSalida`). No se ha escaneado nada.\n", false);
            }
            if (!hayEstructurados && lx.intencion() == InterpretePeticion.Intencion.CONTINUAR && args instanceof ObjectNode o) {
                ObjectNode copia = o.deepCopy();
                copia.remove(List.of(PETICION, "servicios", "numServicios", "funciones", TODAS, "todosServicios", "maxServicios", "confirmarTodos"));
                return continuar(copia, sesion);
            }
        }
        Integer numServicios = numServicios(args);
        if (lx != null && lx.numero() != null && numServicios == null
                && (lx.numero() < PlanGeneracion.MIN_SERVICIOS || lx.numero() > PlanGeneracion.MAX_SERVICIOS)) {
            throw new IllegalArgumentException(errorRangoNumServicios());
        }
        Path proyecto = RutaSegura.validarProyecto(RutasSesion.proyecto(args, sesion, "ruta", true));
        Path salida = RutasSesion.salida(proyecto, Herramienta.ruta(args, "carpetaSalida"), sesion, false,
                Herramienta.bool(args, "guardarRutaPorDefecto"));
        InformeEscaneo.Opciones op = new InformeEscaneo.Opciones();
        op.modulo = Herramienta.texto(args, "modulo");
        op.filtro = Herramienta.texto(args, "filtro");
        op.soloSinCubrir = Herramienta.bool(args, "soloSinCubrir");
        op.pagina = Herramienta.entero(args, "pagina", 1);
        op.tamPagina = Math.min(100, Math.max(1, Herramienta.entero(args, "tamPagina", 40)));
        boolean refrescar = Herramienta.bool(args, "refrescar");
        boolean todas = Herramienta.bool(args, TODAS);
        List<String> servicios = new ArrayList<>();
        for (String x : serviciosEsFrase ? List.<String>of() : Herramienta.lista(args, "servicios")) {
            String n = normalizarNombre(x);
            if (n != null && n.equalsIgnoreCase(TODAS)) {
                todas = true;
                continue;
            }
            if (n != null && !servicios.contains(n)) {
                servicios.add(n);
            }
        }
        List<String> funciones = new ArrayList<>();
        for (String x : Herramienta.lista(args, "funciones")) {
            if (x != null && !x.isBlank() && !funciones.contains(x.trim())) {
                funciones.add(x.trim());
            }
        }
        sesion.olvidarSeleccionesUsuario(proyecto);
        sesion.fijarTodas(proyecto, todas);
        ProyectoEscaneado p = sesion.fachada().proyectoVigente(proyecto, refrescar);
        anotarPerfil(sesion, p, salida);
        Integer n = numServicios;
        InterpretePeticion.Resultado ip = null;
        if (lx != null) {
            ip = InterpretePeticion.interpretar(p, lx, op.modulo, servicios);
            if (n == null) {
                n = lx.numero();
            }
            if (servicios.isEmpty()) {
                for (String s : ip.servicios()) {
                    anadirSinRepetir(servicios, s);
                }
                for (String s : ip.noEncontrados()) {
                    anadirSinRepetir(servicios, s);
                }
            }
            if (!todas && lx.todas()) {
                todas = true;
                sesion.fijarTodas(proyecto, true);
            }
            if (funciones.isEmpty()) {
                funciones.addAll(ip.funciones());
            }
        }
        boolean todos = Herramienta.bool(args, "todosServicios") || (lx != null && lx.todosServicios());
        if (todos) {
            if (n != null || !servicios.isEmpty() || !funciones.isEmpty()) {
                sesion.anotar("«todos» se ignora porque la petición ya indica servicios, funciones o un número de servicios.");
            } else {
                Integer max = maxServicios(args);
                if (max == null && lx != null) {
                    max = lx.maxTodos();
                    if (max != null && max < PlanGeneracion.MIN_SERVICIOS) {
                        throw new IllegalArgumentException(errorMaxServicios());
                    }
                }
                return OleadasTodos.iniciar(args, sesion, p, proyecto, salida, op, todas, max, op.modulo, lx, ip);
            }
        }
        String accion = null;
        Resolucion res = null;
        if (!servicios.isEmpty()) {
            res = resolver(p, servicios, op.modulo);
        }
        if (!funciones.isEmpty() && !todas) {
            fijarFuncionesUsuario(sesion, proyecto, res, funciones);
        }
        PlanGeneracion plan = null;
        boolean hayPlan = n != null || (res != null && !res.fqnsObjetivos.isEmpty());
        if (hayPlan) {
            plan = PlanGeneracion.calcular(p, salida, n, op.modulo, res == null ? null : res.fqnsObjetivos, todas,
                    sesion.selecciones(proyecto), CargadorConfig.ordenServicios(proyecto));
            Set<String> modulos = !plan.modulosRel().isEmpty() ? plan.modulosRel() : res != null ? res.modulosTrabajo : Set.of();
            sesion.recordarModulos(proyecto, modulos);
            if (plan.total() > 0) {
                activarPlan(sesion, proyecto, salida, plan, n, todas, args, op, null, null);
            } else {
                sesion.quitarPlan(proyecto);
                sesion.iniciarTanda(proyecto, null);
                if (res != null) {
                    op.modulosRel.addAll(res.modulosFiltro);
                    op.clasesFqn.addAll(res.fqnsFiltro);
                }
                op.siguientePaso = "No hay casos que generar para el plan: informa al usuario del motivo (avisos de «Plan de generación»).";
            }
        } else if (res != null) {
            sesion.recordarModulos(proyecto, res.modulosTrabajo);
            sesion.quitarPlan(proyecto);
            sesion.iniciarTanda(proyecto, null);
            op.modulosRel.addAll(res.modulosFiltro);
            op.clasesFqn.addAll(res.fqnsFiltro);
            if (res.hayAmbiguos()) {
                op.siguientePaso = "Hay servicios ambiguos: pregunta al usuario cuál de los candidatos quiere (por su FQN) y continúa "
                        + "solo con los elegidos; el resto de servicios resueltos no necesita confirmación.";
            }
            if (lx != null && res.fqnsFiltro.isEmpty()) {
                op.siguientePaso = "Ningún servicio de la petición existe en el proyecto: díselo al usuario con los nombres parecidos "
                        + "de abajo y pregúntale exactamente: «" + PREGUNTA_SIN_ARGUMENTOS + "»; vuelve a llamar a "
                        + "escanear_proyecto con `peticion` = su respuesta tal cual.";
                accion = "accionRequerida: " + op.siguientePaso + "\n\n";
            }
        } else if (lx != null) {
            op.siguientePaso = "Pregunta al usuario exactamente: «" + PREGUNTA_SIN_ARGUMENTOS + "» y vuelve a llamar a "
                    + "escanear_proyecto con `peticion` = su respuesta tal cual.";
            accion = "accionRequerida: de «" + eco(lx) + "» no se deduce ni un número de servicios ni un servicio del proyecto. "
                    + op.siguientePaso + "\n\n";
        } else {
            op.siguientePaso = "Pregunta al usuario exactamente: «" + PREGUNTA_SIN_ARGUMENTOS + "» y vuelve a llamar con "
                    + "`numServicios` o `servicios`.";
        }
        if (hayPlan && res != null && res.hayAmbiguos() && op.siguientePaso != null) {
            op.siguientePaso = "Hay servicios ambiguos (no entran en el plan): pregunta al usuario cuál quiere y vuelve a llamar con su "
                    + "FQN. " + op.siguientePaso;
        }
        String seccionPedidos = null;
        if (lx != null) {
            seccionPedidos = (accion == null ? "" : accion) + bloquePeticion(lx, ip)
                    + (res == null ? "" : res.markdown(p, MAX_PEDIDOS) + avisoNoEncontrados(p, res));
        } else if (res != null) {
            seccionPedidos = res.markdown(p, MAX_PEDIDOS) + avisoNoEncontrados(p, res);
        }
        return new Resultado(resumen(p, salida, op, seccionPedidos, plan, proyecto, sesion), false);
    }

    /**
     * Fija el plan (y su tanda, con la oleada del modo «todos» si se indica) y, con subagentes, reparte los bloques. Rellena
     * módulos, clases y siguiente paso de {@code op} y devuelve el siguiente paso.
     */
    static String activarPlan(SesionMcp sesion, Path proyecto, Path salida, PlanGeneracion plan, Integer n, boolean todas,
                              JsonNode args, InformeEscaneo.Opciones op, Integer oleada, Integer oleadas) {
        List<SesionMcp.MetodoPlan> metodos = new ArrayList<>();
        for (PlanGeneracion.Objetivo o : plan.objetivos()) {
            metodos.add(new SesionMcp.MetodoPlan(o.servicio.fqn, o.metodo.nombre, o.casos, o.conTests));
        }
        sesion.fijarPlan(proyecto, metodos);
        iniciarTanda(sesion, proyecto, salida, plan, n, todas, oleada, oleadas);
        op.modulosRel.addAll(plan.modulosRel());
        op.clasesFqn.addAll(plan.fqns());
        op.siguientePaso = siguientePasoPlan(plan, sesion, proyecto, salida);
        boolean paralelo = args != null && args.has("paralelo")
                ? Herramienta.bool(args, "paralelo")
                : BloquesTanda.clienteConSubagentes(sesion);
        if (paralelo) {
            String motivo = BloquesTanda.motivoNoPermitido(sesion, proyecto, salida);
            if (motivo == null) {
                BloquesTanda.repartir(sesion, proyecto, plan);
                if (salida != null && Files.isDirectory(salida)) {
                    sesion.guardarTanda(proyecto, salida, Boolean.FALSE);
                }
                op.siguientePaso = BloquesTanda.siguientePaso(sesion, proyecto, salida, null);
            } else {
                sesion.anotar(motivo);
            }
        }
        return op.siguientePaso;
    }

    /** 'maxServicios' validado (entero >= 1) o null si no se pasó. */
    static Integer maxServicios(JsonNode args) {
        JsonNode v = args == null ? null : args.get("maxServicios");
        if (v == null || v.isNull() || (v.isTextual() && v.asText().isBlank())) {
            return null;
        }
        int c;
        try {
            c = v.isIntegralNumber() ? v.asInt() : Integer.parseInt(v.asText().trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(errorMaxServicios());
        }
        if (!v.isTextual() && !v.isIntegralNumber() || c < PlanGeneracion.MIN_SERVICIOS) {
            throw new IllegalArgumentException(errorMaxServicios());
        }
        return c;
    }

    static String errorMaxServicios() {
        return "maxServicios debe ser un entero mayor o igual que " + PlanGeneracion.MIN_SERVICIOS + ".";
    }

    /** El argumento está presente y no vacío (ni null, ni texto en blanco, ni lista vacía). */
    private static boolean presente(JsonNode args, String campo) {
        JsonNode v = args == null ? null : args.get(campo);
        return v != null && !v.isNull() && !(v.isTextual() && v.asText().isBlank()) && !(v.isArray() && v.isEmpty());
    }

    private static void anadirSinRepetir(List<String> l, String s) {
        if (s != null && l.stream().noneMatch(x -> x.equalsIgnoreCase(s))) {
            l.add(s);
        }
    }

    /** Petición saneada para devolverla: una línea, sin backticks ni «», máx. {@link #MAX_ECO} caracteres. */
    static String eco(InterpretePeticion.Lexico lx) {
        String s = lx.texto().replace("`", "").replace("«", "").replace("»", "").strip();
        return Textos.recortar(s, MAX_ECO);
    }

    /** «## Petición interpretada»: texto, lo deducido y los avisos; termina en línea en blanco. */
    static String bloquePeticion(InterpretePeticion.Lexico lx, InterpretePeticion.Resultado ip) {
        StringBuilder sb = new StringBuilder("## Petición interpretada\n\n- Texto: «").append(eco(lx)).append("»\n");
        List<String> partes = new ArrayList<>();
        if (lx.numero() != null) {
            partes.add("numServicios = " + lx.numero());
        }
        if (ip != null && !ip.servicios().isEmpty()) {
            partes.add("servicios = [" + String.join(", ", ip.servicios()) + "]");
        }
        if (lx.todas()) {
            partes.add("todas = sí");
        }
        if (lx.todosServicios()) {
            partes.add("todos los servicios = sí" + (lx.maxTodos() == null ? "" : " (máx " + lx.maxTodos() + ")"));
        }
        if (ip != null && !ip.funciones().isEmpty()) {
            partes.add("funciones = [" + String.join(", ", ip.funciones()) + "]");
        }
        sb.append("- Resultado: ").append(partes.isEmpty() ? "nada (ni número de servicios ni servicios del proyecto)"
                : String.join("; ", partes)).append('\n');
        List<String> avisos = new ArrayList<>(lx.avisos());
        if (ip != null) {
            avisos.addAll(ip.avisos());
        }
        for (String a : avisos) {
            sb.append("- Aviso: ").append(a).append('\n');
        }
        return sb.append('\n').toString();
    }

    /**
     * Una línea por servicio pedido que no existe en el proyecto (máx. {@link #MAX_AVISOS_NO_ENCONTRADOS}), con nombres
     * parecidos; cadena vacía si no hay. Es aditivo: no cambia la tabla «Servicios pedidos».
     */
    static String avisoNoEncontrados(ProyectoEscaneado p, Resolucion res) {
        if (res == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        int k = 0;
        for (Pedido x : res.pedidos) {
            BuscadorServicios.Resultado b = x.busqueda();
            if (!b.noEncontrado() || b.interfazSinImplementacion()) {
                continue;
            }
            if (k++ >= MAX_AVISOS_NO_ENCONTRADOS) {
                break;
            }
            List<String> parecidos = InterpretePeticion.parecidos(p, x.nombre(), MAX_PARECIDOS);
            sb.append("Aviso: `").append(x.nombre().replace("`", "")).append("` parece un servicio pero no existe en el proyecto: "
                    + "se ignora y se sigue con el resto. ");
            if (parecidos.isEmpty()) {
                sb.append("(sin nombres parecidos).\n");
            } else {
                sb.append("Parecidos: `").append(String.join("`, `", parecidos)).append("`. Si el usuario quería uno de ellos, "
                        + "vuelve a llamar con ese nombre.\n");
            }
        }
        return sb.toString();
    }

    /**
     * Funciones nombradas por el usuario ({@code funciones=a,b}) para el único servicio resuelto: se validan
     * (origen usuario) y se guardan en la sesión antes de calcular el plan. Errores → IllegalArgumentException.
     */
    static void fijarFuncionesUsuario(SesionMcp sesion, Path proyecto, Resolucion res, List<String> funciones) {
        if (res == null || res.fqnsObjetivos.size() != 1) {
            throw new IllegalArgumentException("`funciones` necesita exactamente un servicio en `servicios`; repite el comando por servicio.");
        }
        String fqn = res.fqnsObjetivos.iterator().next();
        ProyectoEscaneado.Encontrada en = null;
        for (Pedido x : res.pedidos) {
            for (ProyectoEscaneado.Encontrada c : x.busqueda().objetivos()) {
                if (c.clase().fqn.equals(fqn)) {
                    en = c;
                }
            }
        }
        if (en == null) {
            throw new IllegalArgumentException("`funciones` necesita exactamente un servicio en `servicios`; repite el comando por servicio.");
        }
        SeleccionSpec propuesta = new SeleccionSpec();
        propuesta.origen = SeleccionFunciones.ORIGEN_USUARIO;
        for (String f : funciones) {
            propuesta.funciones.add(new SeleccionSpec.Entrada(f, null));
        }
        SeleccionFunciones.Eleccion el = SeleccionFunciones.validarEleccion(en.modulo(), en.clase(), propuesta, "funciones");
        if (!el.valida()) {
            StringBuilder sb = new StringBuilder();
            for (ErrorValidacion e : el.errores()) {
                sb.append(e.codigo).append(": ").append(e.mensaje).append(e.sugerencia == null ? "" : " " + e.sugerencia).append(' ');
            }
            throw new IllegalArgumentException(sb.toString().trim());
        }
        sesion.fijarSeleccion(proyecto, fqn, el.normalizada());
    }

    /**
     * Crea o actualiza perfil-proyecto.json si la carpeta de salida ya existe y anota el resultado (nada si no
     * cambió). Nunca rompe el escaneo.
     */
    static void anotarPerfil(SesionMcp sesion, ProyectoEscaneado p, Path salida) {
        try {
            ConstructorPerfil.Resultado r = sesion.fachada().sincronizarPerfil(p, salida);
            String nota = notaPerfil(r, salida);
            if (nota != null) {
                sesion.anotar(nota);
            }
        } catch (RuntimeException e) {
            System.err.println("junit-gen: perfil del proyecto no actualizado (" + e.getClass().getSimpleName() + ")");
        }
    }

    static String notaPerfil(ConstructorPerfil.Resultado r, Path salida) {
        if (r == null || r.perfil() == null || ConstructorPerfil.SIN_CAMBIOS.equals(r.estado())) {
            return null;
        }
        String cifras = " (" + r.perfil().servicios.size() + " servicios, " + r.perfil().tipos.size() + " tipos de datos)";
        return switch (r.estado()) {
            case ConstructorPerfil.CREADO -> "Perfil del proyecto creado en " + AlmacenPerfil.ruta(salida) + cifras + ".";
            case ConstructorPerfil.ACTUALIZADO -> "Perfil del proyecto actualizado (" + r.recalculados() + " entradas recalculadas).";
            case ConstructorPerfil.REGENERADO -> "Perfil del proyecto " + (r.motivo() == null ? "inválido" : r.motivo())
                    + ": regenerado en " + AlmacenPerfil.ruta(salida) + cifras + ".";
            default -> null;
        };
    }

    /** Argumento que retoma la tanda guardada en la salida. */
    static final String CONTINUAR = "continuar";
    /** Flujo canónico por servicio (2-3 llamadas). */
    static final String FLUJO_SERVICIO = "Para CADA servicio: (1) elige las funciones de «Funciones candidatas» (en el escaneo o "
            + "en `siguienteServicio.candidatas`; si no vienen, `seleccionar_funciones {clase}`) y llama a `seleccionar_funciones "
            + "{clase, funciones:[{nombre, motivo}], omitidas:[{nombre, motivo}]}`: su respuesta ya trae el paquete de trabajo del "
            + "servicio; (2) solo si `paginacion.siguiente` no es null, `detalle_servicio` con esos argumentos (una vez); (3) "
            + "escribe el spec y llama a `generar_tests` (valida el spec: si devuelve `errores`, corrígelos y repite; "
            + "`validar_spec` es opcional). Con `todas`, `funciones=…` o selección guardada: `detalle_servicio {clase, paquete: 1}` "
            + "→ `generar_tests`.";
    /** Reglas de los casos del spec. */
    static final String REGLAS_SPEC = "En el spec, por cada función seleccionada, exactamente los casos metodo=N de "
            + "accionRequerida (= `casosPlan.faltan`; nunca más de su "
            + "`maxCasos`; `queHaceOriginal` una vez por función (en su primer caso) y `queComprueba` en cada caso, 1 frase de "
            + "máx. 160 caracteres; en las que ya tienen "
            + "tests, solo escenarios distintos de `casosExistentes` y `casosGeneradosEnSalida`).";
    /** Flujo por servicio, compacto, para la respuesta de `continuar` (la IA puede estar en un chat nuevo). */
    static final String FLUJO_CONTINUAR = FLUJO_SERVICIO + " Casos de casosPlan (máx. maxCasos; queHaceOriginal una vez "
            + "por función y queComprueba en cada caso; solo escenarios nuevos); sigue en cada respuesta la primera línea accionRequerida («NO HAS "
            + "TERMINADO (servicio k de N): llama ahora a …») sin escribir nada al usuario hasta plan.restantes = 0. Las pruebas van en segundo plano: no esperes; "
            + "no corrijas nada durante la tanda: los casos que fallen se apuntan solos y se arreglan al final (solo con "
            + "`arreglos: inmediato` se corrigen al llegar la nota «Verificación en segundo plano…»). "
            + "Con plan.restantes = 0, ejecutar_tests sin argumentos (repítela mientras devuelva verificando=true); si abre la fase "
            + "de arreglo, corrige cada caso que te dé con arreglar_caso; y termina con "
            + "EXACTAMENTE respuestaFinal solo cuando llegue (si llega accionRequerida «NO HAS TERMINADO: …», haz ese paso). ";

    /**
     * Tanda nueva en memoria para el plan recién fijado y, si la salida ya se conoce, su tanda.json. Si en la salida había
     * una tanda sin cerrar del mismo proyecto, se anota que se sustituye.
     */
    static void iniciarTanda(SesionMcp sesion, Path proyecto, Path salida, PlanGeneracion plan, Integer numServicios, boolean todas) {
        iniciarTanda(sesion, proyecto, salida, plan, numServicios, todas, null, null);
    }

    /** Como {@link #iniciarTanda(SesionMcp, Path, Path, PlanGeneracion, Integer, boolean)} con la oleada del modo «todos» (null = tanda normal). */
    static void iniciarTanda(SesionMcp sesion, Path proyecto, Path salida, PlanGeneracion plan, Integer numServicios, boolean todas,
                             Integer oleada, Integer oleadas) {
        if (salida != null) {
            try {
                Tanda previa = AlmacenTanda.leer(salida);
                if (previa != null && !previa.cerrada && mismoProyecto(previa, proyecto)
                        && !(oleada != null && previa.oleada != null)) {
                    sesion.anotar("Había una tanda sin terminar (" + previa.completos() + " de " + previa.servicios.size()
                            + " servicios); se sustituye. Los servicios ya generados no se repiten.");
                }
            } catch (RuntimeException e) {
                System.err.println("junit-gen: se ignora la tanda previa de la salida: " + e.getMessage());
            }
        }
        Tanda t = new Tanda();
        t.proyecto = proyecto.toString();
        t.creada = Instant.now().toString();
        t.actualizada = t.creada;
        t.orden = plan.orden() == null ? null : plan.orden().name().toLowerCase(Locale.ROOT);
        t.todas = todas ? Boolean.TRUE : null;
        t.numServicios = numServicios;
        t.oleada = oleada;
        t.oleadas = oleadas;
        for (PlanGeneracion.ServicioPlan s : plan.servicios()) {
            Tanda.ServicioTanda st = new Tanda.ServicioTanda();
            st.clase = s.clase.fqn;
            st.modulo = s.modulo == null ? null : s.modulo.modulo.rel;
            st.estado = Tanda.PENDIENTE;
            t.servicios.add(st);
        }
        sesion.iniciarTanda(proyecto, t);
        if (salida != null && Files.isDirectory(salida)) {
            // Solo si la carpeta ya existe (el escaneo no la crea; si no, generar_tests escribe la tanda).
            sesion.guardarTanda(proyecto, salida, Boolean.FALSE);
        }
    }

    private static boolean mismoProyecto(Tanda t, Path proyecto) {
        if (t.proyecto == null || t.proyecto.isBlank()) {
            return false;
        }
        try {
            return RutaSegura.iguales(Path.of(t.proyecto), proyecto);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * escanear_proyecto {continuar:true}: retoma la tanda guardada en la salida por el siguiente servicio. En el mismo
     * proceso (tanda y plan en memoria) solo informa del progreso; si no, restaura plan, casos y estados desde
     * tanda.json y spec.json y vuelve a encolar lo no verificado (solo con verificación «siempre»).
     */
    private Resultado continuar(JsonNode args, SesionMcp sesion) {
        for (String k : List.of("numServicios", "servicios", "funciones", TODAS, "todosServicios", "maxServicios", "confirmarTodos")) {
            JsonNode v = args.get(k);
            boolean vacio = v == null || v.isNull() || (v.isBoolean() && !v.asBoolean()) || (v.isArray() && v.isEmpty())
                    || (v.isTextual() && v.asText().isBlank());
            if (!vacio) {
                throw new IllegalArgumentException("'continuar' no se combina con 'numServicios', 'servicios', 'funciones' ni "
                        + "'todas': retoma la tanda guardada tal cual. Para una tanda nueva usa /generartest N (sin continuar).");
            }
        }
        Path proyecto = RutaSegura.validarProyecto(proyectoParaContinuar(args, sesion));
        Path salida = RutasSesion.salida(proyecto, Herramienta.ruta(args, "carpetaSalida"), sesion, false,
                Herramienta.bool(args, "guardarRutaPorDefecto"));
        if (salida == null) {
            return new Resultado(RutasSesion.instruccionPreguntar(proyecto, sesion) + "\n\nDespués vuelve a llamar a "
                    + "escanear_proyecto {continuar: true, ruta: " + proyecto + ", carpetaSalida: <la elegida>}.\n", false);
        }
        return continuarEn(args, sesion, proyecto, salida);
    }

    /**
     * Proyecto de `continuar`: argumento `ruta` → proyecto de la campaña o tanda guardadas en `carpetaSalida` → último
     * escaneo si la sesión tiene estado propio → raíces del IDE / directorio de trabajo. Así una llamada sin `ruta` no cae
     * en una raíz del IDE que no es el proyecto de la campaña (Claude Code: la raíz es el cwd).
     */
    private static Path proyectoParaContinuar(JsonNode args, SesionMcp sesion) {
        Path arg = Herramienta.ruta(args, "ruta");
        if (arg != null) {
            return arg;
        }
        Path carpeta = Herramienta.ruta(args, "carpetaSalida");
        if (carpeta != null) {
            Path delDisco = proyectoDeLaSalida(carpeta);
            if (delDisco != null) {
                return delDisco;
            }
        }
        Path ultima = sesion.fachada().ultimaRuta();
        if (ultima != null && (sesion.oleadas(ultima) != null || sesion.tanda(ultima) != null || sesion.progresoPlan(ultima) != null)) {
            return ultima;
        }
        return RutasSesion.proyecto(args, sesion, "ruta", true);
    }

    /** Proyecto de oleadas.json (sin cerrar) o, si no, de tanda.json de la salida, si es una carpeta válida; si no, null. */
    private static Path proyectoDeLaSalida(Path salida) {
        String ruta = null;
        try {
            Oleadas c = AlmacenOleadas.leer(salida);
            if (c != null && !c.cerrada) {
                ruta = c.proyecto;
            }
        } catch (RuntimeException e) {
            System.err.println("junit-gen: se ignora el oleadas.json de la salida al deducir el proyecto: " + e.getClass().getSimpleName());
        }
        if (ruta == null || ruta.isBlank()) {
            try {
                Tanda t = AlmacenTanda.leer(salida);
                ruta = t == null ? null : t.proyecto;
            } catch (RuntimeException e) {
                System.err.println("junit-gen: se ignora el tanda.json de la salida al deducir el proyecto: " + e.getClass().getSimpleName());
            }
        }
        if (ruta == null || ruta.isBlank()) {
            return null;
        }
        try {
            return RutaSegura.validarProyecto(Path.of(ruta));
        } catch (RuntimeException e) {
            System.err.println("junit-gen: el proyecto guardado en la salida no es válido y se ignora: " + e.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * Cuerpo de `continuar` con proyecto y salida ya resueltos: avanza la campaña «todos» si toca, informa de la tanda en
     * curso de la sesión o restaura la tanda guardada. No revalida las claves de petición (lo usa también «todos»).
     */
    static Resultado continuarEn(JsonNode args, SesionMcp sesion, Path proyecto, Path salida) {
        Oleadas campana = OleadasTodos.cargar(sesion, proyecto, salida);
        if (campana != null) {
            Tanda actual = OleadasTodos.tandaActual(sesion, proyecto, salida);
            if (OleadasTodos.debeAvanzar(campana, actual, proyecto)) {
                return OleadasTodos.continuarCampana(args, sesion, proyecto, salida, campana, actual);
            }
        }
        Tanda enMemoria = sesion.tanda(proyecto);
        if (enMemoria != null && sesion.progresoPlan(proyecto) != null) {
            sesion.guardarTanda(proyecto, salida);
            return new Resultado(respuestaContinuar(sesion, proyecto, salida, "## Tanda en curso (misma sesión: no se "
                    + "restaura nada)", 0), false);
        }
        Tanda t = AlmacenTanda.leer(salida);
        if (t == null || !mismoProyecto(t, proyecto) || (t.cerrada && t.todoCompleto())) {
            return new Resultado("No hay ninguna tanda pendiente en " + salida + "; usa /generartest N.\n", true);
        }
        if (t.mediciones == null) {
            // Se conserva la medición de la tanda leída; un tanda.json sin ella empieza vacío.
            t.mediciones = new LinkedHashMap<>();
        }
        sesion.olvidarSeleccionesUsuario(proyecto);
        sesion.fijarTodas(proyecto, Boolean.TRUE.equals(t.todas));
        Set<String> modulos = new LinkedHashSet<>();
        for (Tanda.ServicioTanda s : t.servicios) {
            if (s != null && s.modulo != null && !s.modulo.isBlank()) {
                modulos.add(s.modulo);
            }
        }
        sesion.recordarModulos(proyecto, modulos);
        sesion.restaurarPlan(proyecto, t);
        anotarPerfil(sesion, sesion.fachada().proyecto(proyecto, false), salida);
        sesion.iniciarTanda(proyecto, t);
        // Casos y estados de la sesión según spec.json (los eliminados no se restauran).
        Spec spec = AlmacenSpec.leer(salida);
        Map<String, String> casos = new LinkedHashMap<>();
        List<String> duplicados = new ArrayList<>();
        Map<String, String> estados = new LinkedHashMap<>();
        Set<String> serviciosTanda = new LinkedHashSet<>();
        for (Tanda.ServicioTanda s : t.servicios) {
            if (s == null || s.clase == null || s.metodos == null) {
                continue;
            }
            serviciosTanda.add(s.clase);
            for (Tanda.MetodoTanda m : s.metodos) {
                if (m == null) {
                    continue;
                }
                if (m.duplicados != null) {
                    m.duplicados.stream().filter(id -> id != null && !id.isBlank()).forEach(duplicados::add);
                }
                if (m.generados == null) {
                    continue;
                }
                for (String id : m.generados) {
                    AlmacenSpec.Ubicacion u = id == null || spec == null ? null : AlmacenSpec.buscar(spec, id);
                    if (u == null || ESTADO_ELIMINADO.equals(u.caso().estado)) {
                        continue;
                    }
                    casos.put(id, s.clase);
                    String estado = estadoDeSpec(u.caso().estado);
                    if (estado != null) {
                        estados.put(id, estado);
                    }
                }
            }
        }
        List<String> clases = new ArrayList<>();
        if (spec != null && spec.servicios != null) {
            Set<String> conCasos = new LinkedHashSet<>(casos.values());
            for (ServicioSpec sv : spec.servicios) {
                if (sv != null && sv.clase != null && sv.claseTest != null && serviciosTanda.contains(sv.clase)
                        && conCasos.contains(sv.clase) && !clases.contains(sv.claseTest)) {
                    clases.add(sv.claseTest);
                }
            }
        }
        sesion.registrarGenerados(salida, clases, casos, duplicados, List.of());
        sesion.registrarEstados(salida, estados);
        if (GuiaTanda.arreglosAlFinal(sesion, proyecto)) {
            // La lista de casos por arreglar ya viene en la tanda leída de disco: se reconcilia con spec.json.
            try {
                ArreglosFinal.sincronizar(sesion, proyecto, salida, null);
            } catch (RuntimeException e) {
                System.err.println("junit-gen: no se pudieron reconciliar los casos por arreglar: " + e.getClass().getSimpleName()
                        + ": " + e.getMessage());
            }
        }
        // Verificación según junit-gen.yml: siempre → se vuelven a probar los casos sin estado; nunca → descartar.
        Set<String> reprobar = new LinkedHashSet<>();
        ConfigProyecto.ModoVerificacion modo = CargadorConfig.modoVerificacion(proyecto);
        if (modo == ConfigProyecto.ModoVerificacion.NUNCA) {
            sesion.fijarDecisionVerificacion(HerramientaGenerar.DECISION_DESCARTAR);
        } else if (modo != ConfigProyecto.ModoVerificacion.PREGUNTAR) {
            sesion.fijarDecisionVerificacion(HerramientaGenerar.DECISION_PROBAR);
            casos.forEach((id, fqn) -> {
                if (!estados.containsKey(id)) {
                    reprobar.add(fqn);
                }
            });
            sesion.verificaciones().encolar(proyecto, salida, reprobar);
        }
        sesion.guardarTanda(proyecto, salida);
        return new Resultado(respuestaContinuar(sesion, proyecto, salida, "## Tanda reanudada", reprobar.size()), false);
    }

    /** Estado del caso en spec.json eliminado (no se restaura). */
    static final String ESTADO_ELIMINADO = "eliminado";

    /** Estado de sesión equivalente al estado del caso en spec.json; null = sin estado (se vuelve a probar). */
    static String estadoDeSpec(String estadoSpec) {
        if (estadoSpec == null) {
            return null;
        }
        return switch (estadoSpec) {
            case ClasificadorFallos.PASA -> ClasificadorFallos.PASA;
            case CasoSpec.ESTADO_FALLO_ASERCION, "posible_bug", "dudoso" -> ClasificadorFallos.FALLO_ASERCION;
            case HerramientaEjecutar.ESTADO_SPEC_MONTAJE_FALLIDO -> ClasificadorFallos.ERROR_MONTAJE;
            default -> null;
        };
    }

    /** Respuesta compacta de `continuar`: progreso de la tanda, siguiente servicio y siguiente paso concreto. */
    static String respuestaContinuar(SesionMcp sesion, Path proyecto, Path salida, String titulo, int reprobados) {
        Tanda t = sesion.tanda(proyecto);
        int n = t == null ? 0 : t.servicios.size();
        int completos = t == null ? 0 : t.completos();
        String siguiente = HerramientaGenerar.siguienteServicio(sesion, proyecto);
        StringBuilder sb = new StringBuilder(titulo).append("\n\n");
        sb.append("- Carpeta de salida: ").append(salida).append('\n');
        sb.append("- Servicios: ").append(n).append(" (completados: ").append(completos).append(")\n");
        sb.append(OleadasTodos.lineaProgreso(sesion, proyecto));
        if (siguiente != null && t != null) {
            int k = 0;
            for (int i = 0; i < t.servicios.size(); i++) {
                if (siguiente.equals(t.servicios.get(i).clase)) {
                    k = i + 1;
                    break;
                }
            }
            sb.append("- Siguiente: `").append(Textos.nombreSimple(siguiente)).append("` (").append(k == 0 ? completos + 1 : k)
                    .append(" de ").append(n).append(")\n");
        } else {
            sb.append("- Siguiente: ninguno (todos los servicios están generados)\n");
        }
        sb.append("- Casos generados en la tanda: ").append(sesion.casosDeSesion(salida).size()).append('\n');
        List<Integer> bloquesSinTerminar = BloquesTanda.sinTerminar(t);
        boolean enBloques = BloquesTanda.activo(t);
        if (enBloques) {
            sb.append("- Bloques: ").append(BloquesTanda.ids(t).size()).append(" (sin terminar: ")
                    .append(bloquesSinTerminar.isEmpty() ? "ninguno" : BloquesTanda.listaIds(bloquesSinTerminar)).append(")\n");
            int enCola = BloquesTanda.enCola(sesion, proyecto);
            if (enCola > 0) {
                sb.append("- En cola (sin asignar): ").append(enCola).append('\n');
            }
        }
        if (reprobados > 0) {
            sb.append("- Se vuelven a probar en segundo plano: ").append(reprobados).append(reprobados == 1 ? " servicio\n" : " servicios\n");
        }
        int[] arreglos = ArreglosFinal.cuenta(sesion, proyecto);
        boolean faseArreglo = arreglos[1] > 0 && ArreglosFinal.enFase(sesion, proyecto);
        if (arreglos[0] > 0) {
            sb.append("- Fase: ").append(siguiente != null ? "generación" : faseArreglo ? "arreglo (quedan " + arreglos[1] + " de "
                    + arreglos[0] + " casos por arreglar)" : "cierre").append('\n');
        }
        sb.append("\n## Siguiente paso\n\n");
        String paso = HerramientaGenerar.siguientePaso(sesion, proyecto);
        String decision = sesion.decisionVerificacion();
        boolean puerta = sesion.verificaciones().puertaActiva(salida);
        if (enBloques && !bloquesSinTerminar.isEmpty()) {
            sb.append(BloquesTanda.siguientePaso(sesion, proyecto, salida, bloquesSinTerminar)).append(' ').append(PROHIBICIONES)
                    .append('\n');
        } else if (paso != null) {
            List<VerificacionesSesion.Correccion> cs = puerta ? sesion.verificaciones().correcciones(salida) : List.of();
            if (!cs.isEmpty()) {
                sb.append("ANTES de seguir: corrige los errores de montaje de `").append(Textos.nombreSimple(cs.get(0).fqn()))
                        .append("` (estado_verificacion). ");
            } else {
                List<VerificacionesSesion.Revision> rs = puerta ? sesion.verificaciones().revisiones(salida) : List.of();
                if (!rs.isEmpty()) {
                    sb.append("ANTES de seguir: revisa los fallos de aserción de `").append(Textos.nombreSimple(rs.get(0).fqn()))
                            .append("` (estado_verificacion); no preguntes al usuario. ");
                }
            }
            sb.append("Sigue la tanda sin pedir confirmación ni repetir lo ya generado. Primera llamada: ")
                    .append(GuiaTanda.pasoServicio(sesion, proyecto, salida, null, null)).append(' ')
                    .append(FLUJO_CONTINUAR).append(PROHIBICIONES).append('\n');
        } else if (HerramientaGenerar.DECISION_DESCARTAR.equals(decision)) {
            sb.append("Todos los servicios de la tanda están generados y la verificación está desactivada (descartar): no "
                    + "ejecutes los tests. Tu mensaje final es EXACTAMENTE este respuestaFinal, tal cual:\n\n")
                    .append(sesion.respuestaFinal(proyecto, salida)).append('\n');
        } else if (faseArreglo) {
            sb.append("Fase de arreglo: quedan ").append(arreglos[1]).append(arreglos[1] == 1 ? " caso" : " casos")
                    .append(" por arreglar. Llama a ejecutar_tests sin argumentos: espera a las pruebas y devuelve los casos "
                            + "fallidos del siguiente servicio (con su error, pistas, spec y código); corrígelos en una llamada a "
                            + "arreglar_caso (bug real del código: "
                            + "registrar_analisis_ia con bug_servicio). No escribas al usuario hasta respuestaFinal. ")
                    .append(PROHIBICIONES).append('\n');
        } else {
            sb.append("Todos los servicios de la tanda están generados: llama a ejecutar_tests sin argumentos (cierra la tanda: "
                    + "espera a las pruebas en segundo plano y te devuelve lo que quede por revisar; no preguntes al usuario); si "
                    + "responde verificando=true, vuelve a llamarla; si trae accionRequerida «NO HAS TERMINADO: …», haz ese paso. "
                    + "Solo cuando llegue respuestaFinal muéstrala EXACTAMENTE. ").append(PROHIBICIONES)
                    .append('\n');
        }
        return LimitesRespuesta.recortar(sb.toString(), LimitesRespuesta.PRESUPUESTO_CUERPO);
    }

    /** Siguiente paso imperativo con plan: el flujo por servicio y la primera llamada concreta. */
    static String siguientePasoPlan(PlanGeneracion plan) {
        return siguientePasoPlan(plan, null, null, null);
    }

    /**
     * Como {@link #siguientePasoPlan(PlanGeneracion)}; con la sesión (plan ya fijado en ella) la primera llamada es el
     * paso exacto del servicio en curso ({@link GuiaTanda#pasoServicio}).
     */
    static String siguientePasoPlan(PlanGeneracion plan, SesionMcp sesion, Path proyecto, Path salida) {
        int n = plan.servicios().size();
        int m = plan.totalMetodos();
        String cifras = n + (n == 1 ? " servicio, " : " servicios, ") + m + (m == 1 ? " método, " : " métodos, ") + plan.total()
                + (plan.total() == 1 ? " caso" : " casos");
        String cierre = " Sigue en cada respuesta la primera línea accionRequerida («NO HAS TERMINADO (servicio k de N): llama "
                + "ahora a …») sin escribir nada al usuario hasta plan.restantes = 0, sin esperar a las pruebas (van en segundo "
                + "plano), y entonces llama a ejecutar_tests sin argumentos (repítela mientras devuelva verificando=true). "
                + "Primera llamada: ";
        String guiada = sesion == null || sesion.progresoPlan(proyecto) == null ? null
                : GuiaTanda.pasoServicio(sesion, proyecto, salida, null, null);
        if (guiada != null) {
            return "Genera el plan sin pedir confirmación: " + cifras + (plan.hayPendientes() ? " (provisional: se recalcula al "
                    + "elegir funciones). " : ". ") + FLUJO_SERVICIO + " " + REGLAS_SPEC + cierre + guiada + " " + PROHIBICIONES;
        }
        if (plan.hayPendientes()) {
            PlanGeneracion.ServicioPlan primero = plan.servicios().get(0);
            String fqn = primero.clase.fqn;
            String primera = plan.pendiente(primero)
                    ? "seleccionar_funciones {clase: \"" + fqn + "\", funciones, omitidas} eligiendo TÚ por importancia de "
                    + "negocio de las «Funciones candidatas» de abajo (si no aparecen, antes seleccionar_funciones {clase: \""
                    + fqn + "\"})"
                    : "detalle_servicio {clase: \"" + fqn + "\", paquete: 1}";
            return "Genera el plan sin pedir confirmación: " + cifras + " (provisional: se recalcula al elegir funciones). "
                    + FLUJO_SERVICIO + " " + REGLAS_SPEC + cierre + primera + ". " + PROHIBICIONES;
        }
        return "Genera el plan sin pedir confirmación: " + cifras + ". " + FLUJO_SERVICIO + " " + REGLAS_SPEC + cierre
                + "detalle_servicio {clase: \"" + plan.servicios().get(0).clase.fqn + "\", paquete: 1}. " + PROHIBICIONES;
    }

    /**
     * Resumen breve (≤ presupuesto del cuerpo): instrucción de carpeta, servicios pedidos, plan compacto (o
     * servicios detectados sin plan), resumen del proyecto, siguiente paso y secciones bajo petición. Las
     * filas del plan se rellenan al final con el presupuesto restante.
     */
    static String resumen(ProyectoEscaneado p, Path salida, InformeEscaneo.Opciones op, String seccionPedidos, PlanGeneracion plan,
                          Path proyecto, SesionMcp sesion) {
        StringBuilder inicio = new StringBuilder();
        if (salida == null) {
            inicio.append(RutasSesion.instruccionPreguntar(proyecto, sesion)).append("\n\n");
        }
        if (seccionPedidos != null) {
            inicio.append(seccionPedidos).append('\n');
        }
        String bloqueResumen = bloqueResumen(p, op);
        String siguiente = "## Siguiente paso\n\n" + (op.siguientePaso == null ? InformeEscaneo.SIGUIENTE_PASO : op.siguientePaso) + "\n\n";
        int libre = LimitesRespuesta.PRESUPUESTO_CUERPO - LimitesRespuesta.bytes(inicio.toString()) - LimitesRespuesta.bytes(bloqueResumen)
                - LimitesRespuesta.bytes(siguiente) - LimitesRespuesta.bytes(SECCIONES_BAJO_PETICION) - 2;
        String medio = plan != null ? plan.markdownCompacto(libre) : serviciosQueQuepan(p, salida, op, libre);
        String candidatas = "";
        if (plan != null && !plan.servicios().isEmpty() && plan.pendiente(plan.servicios().get(0))
                && !BloquesTanda.activo(sesion, proyecto)) {
            String bloque = bloqueCandidatas(plan.servicios().get(0));
            if (LimitesRespuesta.bytes(bloque) <= libre - LimitesRespuesta.bytes(medio) - 2) {
                candidatas = bloque;
            }
        }
        return inicio + medio + "\n" + bloqueResumen + "\n" + siguiente + candidatas + SECCIONES_BAJO_PETICION;
    }

    /** «Funciones candidatas» completas del servicio (cabecera, líneas y pie), con línea en blanco final. */
    static String bloqueCandidatas(PlanGeneracion.ServicioPlan s) {
        return bloqueCandidatas(s.modulo, s.clase);
    }

    /** «Funciones candidatas» completas de la clase (cabecera, líneas y pie), con línea en blanco final. */
    static String bloqueCandidatas(ModuloEscaneado me, InfoClase c) {
        SeleccionFunciones sel = SeleccionFunciones.de(me, c, false);
        StringBuilder sb = new StringBuilder(sel.cabeceraCandidatas());
        sel.lineasCandidatas().forEach(sb::append);
        sb.append(sel.pieCandidatas()).append('\n');
        return sb.toString();
    }

    /** «## Resumen»: proyecto, módulos y servicios detectados, versiones y estilo por módulo de trabajo, nº de advertencias. */
    static String bloqueResumen(ProyectoEscaneado p, InformeEscaneo.Opciones op) {
        StringBuilder sb = new StringBuilder("## Resumen\n\n");
        sb.append("- Proyecto: ").append(p.raiz).append('\n');
        sb.append("- Módulos: ").append(p.modulos.size()).append("; servicios detectados: ").append(InformeEscaneo.serviciosDetectados(p))
                .append('\n');
        List<ModuloEscaneado> trabajo = InformeEscaneo.modulosIncluidos(p, op);
        int mostrados = Math.min(MAX_MODULOS_RESUMEN, trabajo.size());
        for (int i = 0; i < mostrados; i++) {
            ModuloEscaneado me = trabajo.get(i);
            sb.append("- Módulo ").append(me.modulo.nombre()).append(": ").append(me.versiones.resumen()).append('\n');
        }
        for (int i = 0; i < mostrados; i++) {
            ModuloEscaneado me = trabajo.get(i);
            sb.append("- Estilo sugerido (").append(me.modulo.nombre()).append("): ").append(InformeEscaneo.lineaEstilo(me)).append('\n');
        }
        if (trabajo.size() > mostrados) {
            sb.append("- … y ").append(trabajo.size() - mostrados).append(" módulos más (escanear_proyecto {seccion:\"estilo\"}).\n");
        }
        int avisos = InformeEscaneo.avisos(p).size();
        sb.append("- Advertencias: ").append(avisos).append(avisos > 0 ? " (escanear_proyecto {seccion:\"avisos\"})" : "").append('\n');
        return sb.toString();
    }

    /** Sin plan: las primeras filas de «Servicios detectados» que quepan en {@code maxBytes}. */
    static String serviciosQueQuepan(ProyectoEscaneado p, Path salida, InformeEscaneo.Opciones op, int maxBytes) {
        InformeEscaneo.Seccion s = InformeEscaneo.seccion(p, salida, op, "servicios");
        if (s.bloques().isEmpty()) {
            return s.cabecera().substring(0, s.cabecera().indexOf("\n\n") + 2) + s.vacio() + "\n";
        }
        String mas = "\n… y 9999 servicios más: escanear_proyecto {seccion:\"servicios\"}\n";
        int libre = maxBytes - LimitesRespuesta.bytes(s.cabecera()) - LimitesRespuesta.bytes(mas);
        StringBuilder sb = new StringBuilder(s.cabecera());
        int usados = 0;
        int i = 0;
        for (String fila : s.bloques()) {
            int b = LimitesRespuesta.bytes(fila);
            if (usados + b > libre && i > 0) {
                break;
            }
            if (usados + b > libre) {
                fila = LimitesRespuesta.recortar(fila.stripTrailing(), Math.max(0, libre)) + "\n";
                b = LimitesRespuesta.bytes(fila);
            }
            sb.append(fila);
            usados += b;
            i++;
        }
        if (i < s.bloques().size()) {
            sb.append("\n… y ").append(s.bloques().size() - i).append(" servicios más: escanear_proyecto {seccion:\"servicios\"}\n");
        }
        return sb.toString();
    }

    /** Una sección paginada por presupuesto; nunca recalcula ni reinicia el plan. */
    private Resultado seccion(JsonNode args, SesionMcp sesion, String nombre) {
        if (!InformeEscaneo.SECCIONES.contains(nombre)) {
            throw new IllegalArgumentException("'seccion' debe ser una de " + InformeEscaneo.SECCIONES + ".");
        }
        Path proyecto = RutaSegura.validarProyecto(RutasSesion.proyecto(args, sesion, "ruta", true));
        int pagina = Math.max(1, Herramienta.entero(args, "pagina", 1));
        String cabecera;
        List<String> bloques = new ArrayList<>();
        String vacio;
        int maxBloques = 0;
        if ("plan".equals(nombre)) {
            List<SesionMcp.EstadoMetodo> estado = sesion.estadoPlan(proyecto);
            if (estado.isEmpty()) {
                return new Resultado("## Plan de generación\n\nNo hay plan activo en esta sesión para " + proyecto + ": llama a "
                        + "escanear_proyecto con `numServicios` o `servicios` (sin `seccion`) para crearlo.\n", false);
            }
            Map<String, List<SesionMcp.EstadoMetodo>> porServicio = new LinkedHashMap<>();
            estado.forEach(m -> porServicio.computeIfAbsent(m.fqn(), k -> new ArrayList<>()).add(m));
            int i = 1;
            for (Map.Entry<String, List<SesionMcp.EstadoMetodo>> e : porServicio.entrySet()) {
                int previstos = e.getValue().stream().mapToInt(SesionMcp.EstadoMetodo::previstos).sum();
                int faltan = e.getValue().stream().mapToInt(SesionMcp.EstadoMetodo::faltan).sum();
                StringBuilder b = new StringBuilder("### ").append(i++).append(". ").append(e.getKey()).append(" — ")
                        .append(faltan == 0 ? "completo" : "faltan " + faltan + " de " + previstos + " casos").append('\n');
                for (SesionMcp.EstadoMetodo m : e.getValue()) {
                    b.append("- ").append(m.metodo()).append(" ×").append(m.previstos())
                            .append(m.faltan() == 0 ? " (completo)" : " (faltan " + m.faltan() + ")").append('\n');
                }
                bloques.add(b.append('\n').toString());
            }
            SesionMcp.ProgresoPlan pp = sesion.progresoPlan(proyecto);
            cabecera = "## Plan de generación (progreso de la sesión: " + pp.generados() + " de " + pp.total() + " casos; faltan "
                    + pp.restantes() + ")\n\n";
            vacio = "";
        } else {
            ProyectoEscaneado p = sesion.fachada().proyecto(proyecto, Herramienta.bool(args, "refrescar"));
            InformeEscaneo.Opciones op = new InformeEscaneo.Opciones();
            op.modulo = Herramienta.texto(args, "modulo");
            op.filtro = Herramienta.texto(args, "filtro");
            op.soloSinCubrir = Herramienta.bool(args, "soloSinCubrir");
            if (op.modulo == null) {
                op.modulosRel.addAll(sesion.modulosTrabajo(proyecto));
            }
            InformeEscaneo.Seccion s = InformeEscaneo.seccion(p, sesion.salidaDe(proyecto), op, nombre);
            cabecera = s.cabecera();
            bloques.addAll(s.bloques());
            vacio = s.vacio();
            if ("servicios".equals(nombre)) {
                maxBloques = Math.min(100, Math.max(1, Herramienta.entero(args, "tamPagina", 40)));
            }
        }
        if (bloques.isEmpty()) {
            return new Resultado(cabecera + vacio + "\n", false);
        }
        int presupuesto = LimitesRespuesta.PRESUPUESTO_CUERPO - LimitesRespuesta.bytes(cabecera) - 200;
        List<List<String>> paginas = LimitesRespuesta.paginar(bloques, presupuesto, maxBloques);
        int k = Math.min(pagina, paginas.size());
        StringBuilder sb = new StringBuilder(cabecera);
        paginas.get(k - 1).forEach(sb::append);
        sb.append("\nPágina ").append(k).append(" de ").append(paginas.size()).append('.');
        if (pagina > paginas.size()) {
            sb.append(" (pediste la ").append(pagina).append("; se muestra la última.)");
        }
        if (k < paginas.size()) {
            sb.append(" Siguiente: escanear_proyecto {seccion:\"").append(nombre).append("\", pagina:").append(k + 1).append('}');
        } else {
            sb.append(" Última página.");
        }
        return new Resultado(sb.append('\n').toString(), false);
    }

    /** Quita espacios, comillas, backticks y la extensión .java. Devuelve null si queda vacío. */
    static String normalizarNombre(String s) {
        if (s == null) {
            return null;
        }
        String n = s.trim().replace("`", "").replace("\"", "").replace("'", "").trim();
        if (n.toLowerCase(Locale.ROOT).endsWith(".java")) {
            n = n.substring(0, n.length() - 5).trim();
        }
        return n.isEmpty() ? null : n;
    }

    /** Servicio pedido y el resultado de su búsqueda. */
    record Pedido(String nombre, BuscadorServicios.Resultado busqueda) {
    }

    static final class Resolucion {
        final List<Pedido> pedidos = new ArrayList<>();
        final Set<String> modulosTrabajo = new LinkedHashSet<>();
        final Set<String> modulosFiltro = new LinkedHashSet<>();
        final Set<String> fqnsFiltro = new LinkedHashSet<>();
        /** FQN de los objetivos resueltos (sin ambiguos). */
        final Set<String> fqnsObjetivos = new LinkedHashSet<>();

        boolean hayAmbiguos() {
            return pedidos.stream().anyMatch(x -> x.busqueda().ambiguo());
        }

        String markdown(ProyectoEscaneado p) {
            return markdown(p, Integer.MAX_VALUE);
        }

        /** Tabla de servicios pedidos con como máximo {@code maxFilas} filas (+N: seccion servicios). */
        String markdown(ProyectoEscaneado p, int maxFilas) {
            int filas = 0;
            int omitidas = 0;
            StringBuilder sb = new StringBuilder("## Servicios pedidos\n\n| Pedido | FQN | Módulo | Coincidencia | ¿Detectado como servicio? |\n"
                    + "|---|---|---|---|---|\n");
            for (Pedido x : pedidos) {
                BuscadorServicios.Resultado b = x.busqueda();
                int n = b.resuelto() && !b.ambiguo() && !b.interfazSinImplementacion() ? Math.max(1, b.objetivos().size()) : 1;
                if (filas + n > maxFilas) {
                    omitidas += n;
                    continue;
                }
                filas += n;
                String pedido = "| " + celda(x.nombre()) + " | ";
                if (b.ambiguo()) {
                    List<String> cs = new ArrayList<>();
                    b.candidatos().forEach(c -> cs.add(c.modulo().modulo.nombre() + ":" + c.clase().fqn));
                    sb.append(pedido).append("ambiguo: ").append(celda(String.join("; ", cs)))
                            .append(" | — | ").append(celda(b.via())).append(" | indica el FQN (o el módulo) para elegir uno |\n");
                } else if (b.interfazSinImplementacion()) {
                    ProyectoEscaneado.Encontrada c = b.candidatos().get(0);
                    sb.append(pedido).append(celda(c.clase().fqn)).append(" | ").append(celda(c.modulo().modulo.nombre())).append(" | ")
                            .append(INTERFAZ_SIN_IMPL).append(" | — |\n");
                } else if (!b.resuelto()) {
                    sb.append(pedido).append("— | — | ").append(NO_ENCONTRADO).append(" | — |\n");
                } else {
                    for (ProyectoEscaneado.Encontrada c : b.objetivos()) {
                        sb.append(pedido).append(celda(c.clase().fqn)).append(" | ").append(celda(c.modulo().modulo.nombre())).append(" | ")
                                .append(celda(b.via())).append(" | ").append(deteccion(c.modulo(), c.clase())).append(" |\n");
                    }
                }
            }
            if (omitidas > 0) {
                sb.append("| … | +").append(omitidas).append(": escanear_proyecto {seccion:\"servicios\"} | | | |\n");
            }
            sb.append('\n');
            List<String> nombres = new ArrayList<>();
            for (ModuloEscaneado me : p.modulos) {
                if (modulosTrabajo.contains(me.modulo.rel)) {
                    nombres.add(me.modulo.nombre());
                }
            }
            if (nombres.size() == 1) {
                sb.append("Módulo de trabajo deducido: ").append(nombres.get(0)).append(" (de ").append(p.modulos.size())
                        .append(p.modulos.size() == 1 ? " módulo)" : " módulos)").append('\n');
            } else if (nombres.size() > 1) {
                sb.append("Módulos de trabajo deducidos: ").append(String.join(", ", nombres)).append(" (de ")
                        .append(p.modulos.size()).append(" módulos)\n");
            } else {
                sb.append("No se pudo deducir el módulo de trabajo: ningún servicio pedido se resolvió de forma única.\n");
            }
            if (fqnsFiltro.isEmpty()) {
                sb.append("El informe no se limita: no se encontró ninguno de los servicios pedidos.\n");
            } else {
                sb.append("El informe se limita a esos módulos y a esas clases (las ambiguas se muestran con todos sus candidatos). "
                        + "No preguntes el módulo al usuario. Las clases no detectadas como servicio también admiten detalle_servicio.\n");
            }
            return sb.toString();
        }
    }

    private static String deteccion(ModuloEscaneado me, InfoClase c) {
        for (DetectorServicios.Candidato x : me.deteccion.servicios) {
            if (x.clase.fqn.equals(c.fqn)) {
                return "sí (puntuación " + x.puntuacion + ")";
            }
        }
        DetectorServicios.Candidato d = me.candidato(c.fqn);
        if (d != null) {
            return "dudoso (puntuación " + d.puntuacion + "); se trata igualmente, detalle_servicio disponible";
        }
        return "no, pero se trata igualmente: detalle_servicio disponible";
    }

    private static String celda(String s) {
        return s == null ? "" : s.replace("|", "\\|").replace("\n", " ");
    }

    /** Busca cada nombre en todos los módulos con {@link BuscadorServicios}. */
    static Resolucion resolver(ProyectoEscaneado p, List<String> nombres, String modulo) {
        Resolucion r = new Resolucion();
        for (String nombre : nombres) {
            BuscadorServicios.Resultado b = BuscadorServicios.buscar(p, nombre, modulo);
            r.pedidos.add(new Pedido(nombre, b));
            if (b.resuelto()) {
                for (ProyectoEscaneado.Encontrada c : b.objetivos()) {
                    r.modulosTrabajo.add(c.modulo().modulo.rel);
                    r.modulosFiltro.add(c.modulo().modulo.rel);
                    r.fqnsFiltro.add(c.clase().fqn);
                    r.fqnsObjetivos.add(c.clase().fqn);
                }
            } else if (b.ambiguo()) {
                for (ProyectoEscaneado.Encontrada c : b.candidatos()) {
                    r.modulosFiltro.add(c.modulo().modulo.rel);
                    r.fqnsFiltro.add(c.clase().fqn);
                }
            }
        }
        return r;
    }
}
