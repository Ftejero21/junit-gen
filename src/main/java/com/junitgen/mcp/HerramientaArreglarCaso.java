package com.junitgen.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.junitgen.Fachada;
import com.junitgen.duplicados.Duplicado;
import com.junitgen.generacion.AlmacenSpec;
import com.junitgen.generacion.ComparadorCasos;
import com.junitgen.generacion.EscritorInforme;
import com.junitgen.generacion.GeneradorTests;
import com.junitgen.generacion.ResultadoGeneracion;
import com.junitgen.generacion.Tanda;
import com.junitgen.seguridad.RutaSegura;
import com.junitgen.spec.CasoSpec;
import com.junitgen.spec.ErrorValidacion;
import com.junitgen.spec.ResultadoValidacion;
import com.junitgen.spec.ServicioSpec;
import com.junitgen.spec.Spec;
import com.junitgen.util.Json;
import com.junitgen.util.LimitesRespuesta;
import com.junitgen.util.Textos;
import com.junitgen.verificacion.ClasificadorFallos;
import com.junitgen.verificacion.VerificadorTests;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * arreglar_caso: fase de arreglo de la tanda ({@code arreglos: alFinal}). Reescribe solo los casos enviados (uno, o varios
 * del mismo servicio en {@code casos}) con una sola generación con reemplazarCasos, recompila y ejecuta una vez la clase
 * de test de su servicio, espera el resultado y devuelve el resultado de cada caso y los siguientes casos. Una guardia
 * de calidad rechaza los cambios que rebajan el test sin justificarlo con el código. Máximo
 * {@link VerificadorTests#MAX_INTENTOS} intentos por caso; después junit-gen lo aparta solo.
 */
public class HerramientaArreglarCaso implements Herramienta {

    public static final String NOMBRE = "arreglar_caso";
    /** Errores de validación devueltos en un rechazo. */
    static final int MAX_ERRORES = 5;
    /** Errores por caso en el resultado de una llamada con varios casos. */
    static final int MAX_ERRORES_RESULTADO = 3;
    /** Casos por llamada. */
    static final int MAX_CASOS_LLAMADA = 12;
    /** Descripción corta del campo `motivo` en el esquema de la herramienta. */
    private static final String MOTIVO_ESQUEMA = "Obligatorio en fallos de aserción o si algo queda menos estricto: por qué, citando el código.";
    static final String CAMBIO_COMUN = "Se cambió la parte común del test (construcción, fixtures o imports)";
    /** Rechazo: duplicado o archivo no tocado. */
    static final String COD_NO_REESCRITO = "NO_REESCRITO";
    /** Rechazo: lo enviado no cambia nada. */
    static final String COD_SIN_CAMBIOS = "SIN_CAMBIOS";
    /** Rechazo: el cambio rebaja el test sin justificarlo con el código. */
    static final String COD_DEBILITA = "DEBILITA_TEST";
    /** Rechazo: el nuevo esperado es el valor obtenido, sin justificarlo con el código. */
    static final String COD_COPIA = "COPIA_SIN_JUSTIFICAR";
    /** Rechazo: el caso es de otro servicio que el resto de la llamada. */
    static final String COD_OTRO_SERVICIO = "OTRO_SERVICIO";
    /** Rechazo: el caso no está en spec.json. */
    static final String COD_NO_EN_SPEC = "NO_EN_SPEC";
    /** Rechazo: falta el spec (o la parte común). */
    static final String COD_FALTA_SPEC = "FALTA_SPEC";
    /** Rechazo: falta el motivo de un fallo de aserción. */
    static final String COD_FALTA_MOTIVO = "FALTA_MOTIVO";
    static final String MENSAJE_DEBILITA = "Este cambio hace el test menos estricto o cambia lo que comprueba. Mantén la comprobación y "
            + "corrige la preparación (datos, preparacion, stubs, estaticos) para que el código produzca lo esperado; si lo esperado era "
            + "incorrecto, pon el valor exacto que produce el código (su constante real o el objeto construido con los mismos datos del "
            + "caso) o comprueba la interacción con verificaciones. Solo si el código hace imposible la comprobación exacta, repite con "
            + "`motivo` (mínimo " + ArreglosFinal.MIN_JUSTIFICACION + " caracteres) citando al menos dos nombres reales del código "
            + "(método, campo, tipo o constante) y la condición que lo impide. Si el código contradice lo que la función debe hacer, es un "
            + "posible bug: registrar_analisis_ia con bug_servicio.";
    private static final String MENSAJE_FALTA_MOTIVO = "Falta 'motivo': explica, citando el código, por qué el test estaba mal "
            + "planteado; si es un bug del código usa registrar_analisis_ia con bug_servicio.";
    private static final String MENSAJE_FALTA_SPEC = "Falta 'spec': el caso corregido (o 'servicio' si el error está en la parte común).";
    private static final String DETALLE_RECHAZADO = "No se aplicó: corrige lo indicado en errores y reenvíalo.";
    private static final String RESULTADO_RECHAZADO = "rechazado";
    private static final String RESULTADO_NO_PENDIENTE = "no_pendiente";
    /** Índice del caso en la ruta de un error de validación. */
    private static final Pattern RUTA_CASO = Pattern.compile("servicios\\[\\d+\\]\\.casos\\[(\\d+)\\]");
    /** Estados de spec.json tras los que se reconcilia el registro del caso antes de reescribirlo. */
    private static final Set<String> ESTADOS_RESINCRONIZAR = Set.of(CasoSpec.ESTADO_FALLO_ASERCION, VerificadorTests.ESTADO_POSIBLE_BUG,
            VerificadorTests.ESTADO_DUDOSO, HerramientaEjecutar.ESTADO_SPEC_MONTAJE_FALLIDO);
    /** Prefijo de la clave anti-bucle de los rechazos de la corrección común de un servicio. */
    private static final String CLAVE_RECHAZO_COMUN = "comun|";

    @Override
    public String nombre() {
        return NOMBRE;
    }

    @Override
    public String titulo() {
        return "Arreglar casos (fase de arreglo)";
    }

    @Override
    public String descripcion() {
        return "Fase de arreglo (solo si accionRequerida dice «Fase de arreglo»): corrige los casos fallidos. Varios del mismo "
                + "servicio: `casos` = [{caso, spec, motivo}] (o uno con `caso`, `spec`, `motivo`); `spec` = el caso completo o solo "
                + "lo que cambia; `servicio` {imports, construccion, fixtures} solo si el error está en la parte común. Devuelve el "
                + "resultado y los siguientes casos. No rebajes el test: corrige la preparación; `motivo` citando el código es "
                + "obligatorio en fallos de aserción. Máx. 3 intentos por caso. Bug real: registrar_analisis_ia con bug_servicio.";
    }

    @Override
    public ObjectNode esquema() {
        ObjectNode s = Herramienta.esquemaBase();
        Herramienta.propiedad(s, "caso", "string", "Id del caso (forma de un solo caso).", false);
        ObjectNode props = (ObjectNode) s.get("properties");
        ObjectNode casos = props.putObject("casos");
        casos.put("type", "array");
        casos.put("description", "Casos del mismo servicio: [{caso, spec, motivo}] (máx. " + MAX_CASOS_LLAMADA + ").");
        casos.put("minItems", 1);
        casos.put("maxItems", MAX_CASOS_LLAMADA);
        ObjectNode item = casos.putObject("items");
        item.put("type", "object");
        ObjectNode ip = item.putObject("properties");
        ip.putObject("caso").put("type", "string").put("description", "Id del caso.");
        ip.putObject("spec").put("type", "object").put("description", "Caso corregido (completo o solo los campos que cambian).");
        ip.putObject("motivo").put("type", "string").put("description", MOTIVO_ESQUEMA);
        item.putArray("required").add("caso");
        Herramienta.propiedad(s, "spec", "object", "Caso corregido (completo o solo los campos que cambian).", false);
        Herramienta.propiedad(s, "servicio", "object", "Solo si el error está en la parte común: {imports, construccion, fixtures}.", false);
        Herramienta.propiedad(s, "motivo", "string", MOTIVO_ESQUEMA, false);
        return s;
    }

    @Override
    public boolean soloLectura() {
        return false;
    }

    @Override
    public String aviso(JsonNode args, SesionMcp sesion) {
        Path proyecto = RutasSesion.proyectoConocido(args, sesion, "rutaProyecto");
        return Fachada.aviso(proyecto == null ? Path.of("(proyecto de la sesión)") : proyecto, sesion.salidaDe(proyecto));
    }

    /** Caso recibido: id, nodo con sus campos (spec, motivo) y motivo. */
    private record Entrada(String caso, JsonNode origen, String motivo) {
    }

    /** Estado de un caso durante la llamada. */
    private static final class Item {
        final Entrada e;
        Tanda.ArregloTanda a;
        AlmacenSpec.Ubicacion u;
        CasoSpec guardado;
        JsonNode specArg;
        ObjectNode nuevo;
        boolean asercionSinAutorizar;
        boolean aceptado;
        boolean aplicado;
        boolean rechazado;
        boolean contar;
        boolean terminado;
        final List<ErrorValidacion> errores = new ArrayList<>();
        List<ComparadorCasos.AvisoCalidad> avisos = List.of();
        /** Resultado ya decidido (no pendiente o resuelto al reconciliar). */
        ObjectNode fin;

        Item(Entrada e) {
            this.e = e;
        }

        String caso() {
            return e.caso();
        }

        void rechazar(boolean cuenta, List<ErrorValidacion> errs) {
            aceptado = false;
            rechazado = true;
            contar = cuenta;
            errores.clear();
            errores.addAll(errs);
        }

        void rechazar(boolean cuenta, String codigo, String mensaje) {
            rechazar(cuenta, List.of(ErrorValidacion.error(null, codigo, mensaje, null)));
        }
    }

    /** Resultado de regenerar casos: la generación y si cambió la parte común enviada. */
    record Regeneracion(Fachada.Generacion g, boolean servicioCambia) {
    }

    @Override
    public Resultado ejecutar(JsonNode args, SesionMcp sesion) {
        Path ultima = sesion.fachada().ultimaRuta();
        if (ultima == null) {
            throw new IllegalArgumentException("No hay ningún proyecto en esta sesión: llama antes a escanear_proyecto.");
        }
        Path proyecto = RutaSegura.validarProyecto(ultima);
        Path salida = sesion.salidaDe(proyecto);
        if (salida == null) {
            throw new IllegalArgumentException("Aún no hay carpeta de salida en esta sesión: llama antes a escanear_proyecto.");
        }
        List<Entrada> entradas = entradas(args);
        if (!ArreglosFinal.enFase(sesion, proyecto)) {
            return new Resultado("arreglar_caso solo se usa en la fase de arreglo (cuando accionRequerida dice «Fase de arreglo»). "
                    + "Sigue la primera línea accionRequerida.", true);
        }
        boolean unico = entradas.size() == 1;
        List<Item> items = new ArrayList<>();
        entradas.forEach(e -> items.add(new Item(e)));
        JsonNode servicioArg = null;
        Spec spec = null;
        boolean specLeido = false;
        String servicio = null;
        Map<String, Set<String>> identificadores = new HashMap<>();
        // 1. Validación por caso, sin generar.
        for (Item it : items) {
            String caso = it.caso();
            it.a = sesion.arreglo(proyecto, caso);
            if (it.a == null || !Tanda.ARREGLO_PENDIENTE.equals(it.a.estado)) {
                ObjectNode o = Json.objeto();
                o.put("caso", caso);
                o.put("resultado", RESULTADO_NO_PENDIENTE);
                o.put("detalle", "Ese caso no está pendiente de arreglo.");
                it.fin = o;
                continue;
            }
            if (!specLeido) {
                spec = AlmacenSpec.leer(salida);
                servicioArg = objeto(args, "servicio");
                specLeido = true;
            }
            AlmacenSpec.Ubicacion u = spec == null ? null : AlmacenSpec.buscar(spec, caso);
            if (u == null || u.caso() == null || u.servicio() == null) {
                String msg = "El caso " + caso + " no está en spec.json de " + salida + ".";
                if (unico) {
                    throw new IllegalArgumentException(msg);
                }
                it.rechazar(false, COD_NO_EN_SPEC, msg);
                continue;
            }
            if (servicio == null) {
                servicio = it.a.servicio;
            } else if (!servicio.equals(it.a.servicio)) {
                it.rechazar(false, COD_OTRO_SERVICIO, "Este caso es de otro servicio (" + Textos.nombreSimple(it.a.servicio)
                        + "): envíalo en otra llamada.");
                continue;
            }
            it.specArg = objeto(it.e.origen(), "spec");
            if (it.specArg == null && servicioArg == null) {
                if (unico) {
                    throw new IllegalArgumentException(MENSAJE_FALTA_SPEC);
                }
                it.rechazar(true, COD_FALTA_SPEC, MENSAJE_FALTA_SPEC);
                continue;
            }
            it.u = u;
            it.guardado = u.caso();
            it.asercionSinAutorizar = CasoSpec.ESTADO_FALLO_ASERCION.equals(it.guardado.estado) && it.guardado.autorizacionCambio == null;
            String motivo = it.e.motivo();
            if (it.asercionSinAutorizar && (motivo == null || motivo.isEmpty())) {
                if (unico) {
                    throw new IllegalArgumentException(MENSAJE_FALTA_MOTIVO);
                }
                it.rechazar(true, COD_FALTA_MOTIVO, MENSAJE_FALTA_MOTIVO);
                continue;
            }
            it.nuevo = nuevo(it.guardado, it.specArg, caso);
            // 2. Guardia de calidad: comparar con el caso guardado.
            if (!guardia(sesion, proyecto, it, identificadores)) {
                continue;
            }
            it.aceptado = true;
        }
        // 3. Fallos de aserción aceptados: veredicto error_ia (solo tras pasar la guardia) y reconciliación.
        List<VerificadorTests.AnalisisIa> analisis = new ArrayList<>();
        boolean resincronizar = false;
        for (Item it : items) {
            if (!it.aceptado) {
                continue;
            }
            if (it.asercionSinAutorizar) {
                analisis.add(new VerificadorTests.AnalisisIa(it.caso(), VerificadorTests.VEREDICTO_ERROR_IA, it.e.motivo()));
            }
            if (ESTADOS_RESINCRONIZAR.contains(it.guardado.estado)) {
                resincronizar = true;
            }
        }
        if (!analisis.isEmpty()) {
            sesion.fachada().registrarAnalisisIa(salida, analisis);
            HerramientaEjecutar.sincronizarRevisiones(sesion, salida);
        }
        if (resincronizar) {
            ArreglosFinal.sincronizar(sesion, proyecto, salida, servicio);
            boolean quedan = false;
            for (Item it : items) {
                if (!it.aceptado) {
                    continue;
                }
                Tanda.ArregloTanda tras = sesion.arreglo(proyecto, it.caso());
                if (tras == null || !Tanda.ARREGLO_PENDIENTE.equals(tras.estado)) {
                    it.aceptado = false;
                    it.fin = anterior(it.caso(), tras);
                } else {
                    quedan = true;
                }
            }
            if (quedan) {
                // El veredicto error_ia acaba de registrarse: se relee el spec (autorización de cambio vigente).
                spec = AlmacenSpec.leer(salida);
                for (Item it : items) {
                    if (!it.aceptado) {
                        continue;
                    }
                    AlmacenSpec.Ubicacion u = spec == null ? null : AlmacenSpec.buscar(spec, it.caso());
                    if (u == null || u.caso() == null || u.servicio() == null) {
                        String msg = "El caso " + it.caso() + " no está en spec.json de " + salida + ".";
                        if (unico) {
                            throw new IllegalArgumentException(msg);
                        }
                        it.rechazar(false, COD_NO_EN_SPEC, msg);
                        continue;
                    }
                    it.u = u;
                    it.guardado = u.caso();
                    it.nuevo = nuevo(it.guardado, it.specArg, it.caso());
                }
            }
        }
        // 4. Una generación con todos los aceptados (y otra sin los que tengan errores de validación propios).
        List<Item> aceptados = new ArrayList<>();
        for (Item it : items) {
            if (it.aceptado) {
                aceptados.add(it);
            }
        }
        Fachada.Generacion g = null;
        Path salidaReal = salida;
        if (!aceptados.isEmpty()) {
            VerificacionesSesion.PuntoComun puntoBase = servicioArg == null ? null
                    : capturarPuntoComun(sesion, salida, servicio, aceptados.get(0));
            Regeneracion rg = regenerar(sesion, proyecto, salida, spec, aceptados.get(0).u.servicio(), mapa(aceptados), servicioArg);
            ResultadoGeneracion res = rg.g().resultado();
            if (!res.errores.isEmpty()) {
                List<Item> resto = repartirErrores(aceptados, res.errores, unico);
                rg = null;
                if (!resto.isEmpty()) {
                    aceptados = resto;
                    rg = regenerar(sesion, proyecto, salida, spec, aceptados.get(0).u.servicio(), mapa(aceptados), servicioArg);
                    res = rg.g().resultado();
                    if (!res.errores.isEmpty()) {
                        for (Item it : aceptados) {
                            it.rechazar(true, res.errores);
                        }
                        rg = null;
                    }
                }
            }
            if (rg != null) {
                g = rg.g();
                Set<String> reescritos = new HashSet<>();
                for (var m : res.generados) {
                    Object id = m.get("caso");
                    if (id != null) {
                        reescritos.add(id.toString());
                    }
                }
                // Un cambio solo de la parte común (imports o fixtures) puede reescribir el archivo sin reescribir el caso, o
                // cambiar solo spec.json (la verificación repara después los imports): cuenta como corrección.
                boolean comunAplicada = servicioArg != null && (!res.archivosAmpliados.isEmpty() || !res.remontados.isEmpty()
                        || rg.servicioCambia());
                for (Item it : aceptados) {
                    if (reescritos.contains(it.caso()) || comunAplicada) {
                        it.aplicado = true;
                    } else {
                        Motivo m = motivoNoReescrito(res, it.caso(), it.a, servicioArg != null);
                        it.rechazar(true, m.codigo(), m.mensaje());
                    }
                }
                List<Item> aplicados = new ArrayList<>();
                for (Item it : aceptados) {
                    if (it.aplicado) {
                        aplicados.add(it);
                    }
                }
                if (!aplicados.isEmpty()) {
                    // Solo una corrección común sobre una clase que antes compilaba (sin error común apuntado) es reversible.
                    VerificacionesSesion.PuntoComun punto = comunAplicada && puntoBase != null
                            && aplicados.stream().noneMatch(it -> it.a.comun)
                            && ArreglosFinal.pendientesComunes(sesion, proyecto, servicio).isEmpty() ? puntoBase : null;
                    salidaReal = aplicar(sesion, proyecto, salida, g, aplicados, servicioArg != null, servicio, punto);
                }
            }
        }
        boolean hayAplicados = items.stream().anyMatch(it -> it.aplicado);
        // 5. Respuesta de un solo caso: formato de siempre.
        if (unico) {
            Item it = items.get(0);
            if (it.fin != null) {
                if (specLeido) {
                    sesion.guardarTanda(proyecto, salidaReal, Boolean.FALSE);
                }
                return cerrar(sesion, proyecto, salidaReal, it.fin);
            }
            if (it.rechazado) {
                return rechazo(sesion, proyecto, salidaReal, it.a, it.errores, it.caso(), it.nuevo);
            }
            if (!sesion.verificaciones().esperarInactiva(salidaReal, HerramientaEjecutar.restoEspera(sesion))) {
                return verificando(sesion, List.of());
            }
            return cerrar(sesion, proyecto, salidaReal, anterior(it.caso(), sesion.arreglo(proyecto, it.caso())));
        }
        // 6. Varios casos: rechazos contados (anti-bucle) y resultado de cada uno.
        for (Item it : items) {
            if (it.rechazado && it.contar && it.a != null) {
                it.terminado = contarRechazo(sesion, proyecto, salidaReal, it.a);
            }
        }
        if (hayAplicados && !sesion.verificaciones().esperarInactiva(salidaReal, HerramientaEjecutar.restoEspera(sesion))) {
            List<Item> pendientes = new ArrayList<>();
            for (Item it : items) {
                if (it.rechazado && !it.terminado) {
                    pendientes.add(it);
                }
            }
            return verificando(sesion, pendientes);
        }
        boolean todosRechazados = true;
        ArrayNode resultados = Json.MAPPER.createArrayNode();
        for (Item it : items) {
            if (it.fin != null) {
                resultados.add(it.fin);
                todosRechazados = false;
            } else if (it.rechazado && !it.terminado) {
                resultados.add(nodoRechazo(it));
            } else {
                resultados.add(anterior(it.caso(), sesion.arreglo(proyecto, it.caso())));
                todosRechazados = false;
            }
        }
        if (todosRechazados) {
            sesion.guardarTanda(proyecto, salidaReal, Boolean.FALSE);
            ObjectNode r = Json.objeto();
            r.put("accionRequerida", HerramientaEjecutar.ACCION_PENDIENTE_IA + "arreglar_caso no ha aplicado nada: corrige lo indicado en "
                    + "cada caso y repite la llamada arreglar_caso {casos: [{caso, spec, motivo}, …]}. Si no lo consigues, junit-gen los "
                    + "apartará solos; no escribas al usuario.");
            ArrayNode rech = r.putArray("rechazados");
            for (JsonNode n : resultados) {
                ObjectNode o = rech.addObject();
                o.set("caso", n.get("caso"));
                if (n.get("errores") != null) {
                    o.set("errores", n.get("errores"));
                }
            }
            LimitesRespuesta.acotarJson(r, LimitesRespuesta.PRESUPUESTO_CUERPO, List.of("rechazados"), Set.of(), Set.of(),
                    "Corrige primero los errores mostrados.");
            return new Resultado(Json.compacto(r), true);
        }
        ObjectNode anterior = Json.objeto();
        anterior.set("resultados", resultados);
        return cerrar(sesion, proyecto, salidaReal, anterior);
    }

    /** Casos recibidos: de {@code casos} si viene y no está vacío; si no, de {@code caso/spec/motivo}. */
    private static List<Entrada> entradas(JsonNode args) {
        JsonNode casos = args == null ? null : args.get("casos");
        if (casos != null && casos.isTextual()) {
            casos = casos.asText().isBlank() ? null : Json.leer(casos.asText());
        }
        List<Entrada> r = new ArrayList<>();
        if (casos != null && !casos.isNull()) {
            if (!casos.isArray()) {
                throw new IllegalArgumentException("'casos' debe ser una lista [{caso, spec, motivo}].");
            }
            if (casos.size() > MAX_CASOS_LLAMADA) {
                throw new IllegalArgumentException("Demasiados casos en 'casos' (" + casos.size() + "): máximo " + MAX_CASOS_LLAMADA
                        + " por llamada.");
            }
            Set<String> vistos = new HashSet<>();
            for (JsonNode n : casos) {
                if (n == null || !n.isObject()) {
                    throw new IllegalArgumentException("Cada elemento de 'casos' debe ser un objeto {caso, spec, motivo}.");
                }
                String caso = idCaso(Herramienta.obligatorio(n, "caso"));
                if (vistos.add(caso)) {
                    r.add(new Entrada(caso, n, motivo(n)));
                }
            }
        }
        if (r.isEmpty()) {
            r.add(new Entrada(idCaso(Herramienta.obligatorio(args, "caso")), args, motivo(args)));
        }
        return r;
    }

    private static String idCaso(String texto) {
        String caso = texto.trim();
        if (!Textos.ID_CASO.matcher(caso).matches()) {
            throw new IllegalArgumentException("Id de caso no válido en 'caso': " + LimitesRespuesta.recortar(caso, 80));
        }
        return caso;
    }

    private static String motivo(JsonNode n) {
        String m = Herramienta.texto(n, "motivo");
        return m == null ? null : m.strip();
    }

    /** Caso nuevo: el guardado (limpio) con las claves recibidas superpuestas; el id no cambia. */
    private static ObjectNode nuevo(CasoSpec guardado, JsonNode specArg, String caso) {
        ObjectNode nuevo = HerramientaEstadoVerificacion.specLimpio(guardado);
        if (specArg != null) {
            ObjectNode recibido = ((ObjectNode) specArg).deepCopy();
            HerramientaEstadoVerificacion.CAMPOS_INTERNOS.forEach(recibido::remove);
            nuevo.setAll(recibido);
        }
        nuevo.put("id", caso);
        return nuevo;
    }

    /**
     * Guardia de calidad: si el caso propuesto rebaja el test o copia el valor obtenido, exige un motivo que cite el código;
     * sin él rechaza el caso. true = aceptado (con los avisos guardados en el item). Un spec no tipable pasa sin guardia.
     */
    private static boolean guardia(SesionMcp sesion, Path proyecto, Item it, Map<String, Set<String>> identificadores) {
        CasoSpec propuesto;
        try {
            propuesto = Json.MAPPER.treeToValue(it.nuevo, CasoSpec.class);
        } catch (JsonProcessingException | RuntimeException e) {
            System.err.println("junit-gen: caso " + it.caso() + " sin guardia de calidad (spec no tipable): " + e.getClass().getSimpleName());
            return true;
        }
        String obtenido = ClasificadorFallos.FALLO_ASERCION.equals(it.a.tipo) ? PistasArreglo.obtenido(it.a.error) : null;
        List<ComparadorCasos.AvisoCalidad> avisos = ComparadorCasos.avisosCalidad(it.guardado, propuesto, obtenido);
        if (avisos.isEmpty()) {
            return true;
        }
        String modulo = it.u == null || it.u.servicio() == null ? null : it.u.servicio().modulo;
        Set<String> ids = identificadores.computeIfAbsent(it.a.servicio + "#" + it.a.metodo,
                k -> ArreglosFinal.identificadoresCodigo(sesion, proyecto, it.a, modulo));
        if (ArreglosFinal.justificacionValida(it.e.motivo(), ids)) {
            it.avisos = avisos;
            return true;
        }
        boolean soloCopia = true;
        List<String> frases = new ArrayList<>();
        for (ComparadorCasos.AvisoCalidad av : avisos) {
            frases.add(av.frase());
            if (!ComparadorCasos.AVISO_COPIA.equals(av.tipo())) {
                soloCopia = false;
            }
        }
        it.rechazar(true, soloCopia ? COD_COPIA : COD_DEBILITA, String.join("; ", frases) + ". " + MENSAJE_DEBILITA);
        return false;
    }

    /** ids → caso nuevo, en el orden de los items. */
    private static Map<String, ObjectNode> mapa(List<Item> items) {
        Map<String, ObjectNode> m = new LinkedHashMap<>();
        for (Item it : items) {
            m.put(it.caso(), it.nuevo);
        }
        return m;
    }

    /**
     * Reparte los errores de validación por caso (índice en la ruta = posición en los casos enviados): si alguno no es de
     * un caso (o hay un solo caso) se rechazan todos; si no, solo los culpables. Devuelve los que quedan sin errores.
     */
    private static List<Item> repartirErrores(List<Item> aceptados, List<ErrorValidacion> errores, boolean unico) {
        Map<Integer, List<ErrorValidacion>> porCaso = new LinkedHashMap<>();
        boolean ajenos = false;
        for (ErrorValidacion e : errores) {
            Matcher m = RUTA_CASO.matcher(e.ruta == null ? "" : e.ruta);
            int i = -1;
            if (m.find()) {
                try {
                    i = Integer.parseInt(m.group(1));
                } catch (NumberFormatException ex) {
                    System.err.println("junit-gen: índice de caso no válido en un error de validación: " + ex.getMessage());
                    i = -1;
                }
            }
            if (i < 0 || i >= aceptados.size()) {
                ajenos = true;
            } else {
                porCaso.computeIfAbsent(i, k -> new ArrayList<>()).add(e);
            }
        }
        if (ajenos || unico) {
            for (Item it : aceptados) {
                it.rechazar(true, errores);
            }
            return List.of();
        }
        List<Item> resto = new ArrayList<>();
        for (int i = 0; i < aceptados.size(); i++) {
            List<ErrorValidacion> propios = porCaso.get(i);
            if (propios == null) {
                resto.add(aceptados.get(i));
            } else {
                aceptados.get(i).rechazar(true, propios.size() > MAX_ERRORES ? propios.subList(0, MAX_ERRORES) : propios);
            }
        }
        return resto;
    }

    /**
     * Corrección aplicada a los casos: estado de sesión, cambios, motivo y avisos de calidad en la tanda, un intento por
     * caso y una verificación del servicio. Devuelve la salida real.
     */
    private static Path aplicar(SesionMcp sesion, Path proyecto, Path salida, Fachada.Generacion g, List<Item> aplicados,
                                boolean conServicio, String servicio, VerificacionesSesion.PuntoComun punto) {
        trasGenerar(sesion, proyecto, g);
        ResultadoGeneracion rg = g.resultado();
        Path salidaReal = g.escritor() != null ? g.escritor().salida() : salida;
        boolean comun = conServicio;
        for (Item it : aplicados) {
            comun |= it.a.comun;
            List<String> cambios = new ArrayList<>();
            CasoSpec validado = validado(g.validacion(), it.caso());
            if (validado != null) {
                for (String c : ComparadorCasos.cambios(it.guardado, validado)) {
                    if (cambios.size() >= ArreglosFinal.MAX_CAMBIOS) {
                        break;
                    }
                    cambios.add(LimitesRespuesta.recortar(c, ArreglosFinal.MAX_ERROR));
                }
            }
            for (String c : rg.cambiosComunes) {
                if (cambios.size() >= ArreglosFinal.MAX_CAMBIOS) {
                    break;
                }
                cambios.add(LimitesRespuesta.recortar(c, ArreglosFinal.MAX_ERROR));
            }
            if (conServicio && rg.cambiosComunes.isEmpty() && cambios.size() < ArreglosFinal.MAX_CAMBIOS) {
                cambios.add(CAMBIO_COMUN);
            }
            String motivo = it.e.motivo();
            String motivoGuardado = motivo == null || motivo.isEmpty() ? null
                    : EscritorInforme.celda(LimitesRespuesta.recortar(motivo, ArreglosFinal.MAX_MOTIVO));
            List<String> frasesAvisos = new ArrayList<>();
            boolean menos = false;
            for (ComparadorCasos.AvisoCalidad av : it.avisos) {
                frasesAvisos.add(LimitesRespuesta.recortar(av.frase(), ArreglosFinal.MAX_ERROR));
                if (ComparadorCasos.AVISO_DEBILITA.equals(av.tipo()) || ComparadorCasos.AVISO_ESCENARIO.equals(av.tipo())) {
                    menos = true;
                }
            }
            boolean menosEstricto = menos;
            sesion.actualizarArreglo(proyecto, it.caso(), x -> {
                if (x == null) {
                    return null;
                }
                List<String> todos = new ArrayList<>(x.cambios == null ? List.of() : x.cambios);
                for (String c : cambios) {
                    if (!todos.contains(c)) {
                        todos.add(c);
                    }
                }
                while (todos.size() > ArreglosFinal.MAX_CAMBIOS) {
                    todos.remove(0);
                }
                x.cambios = todos;
                x.correccionDeshecha = null;
                if (motivoGuardado != null) {
                    x.motivo = motivoGuardado;
                }
                if (!frasesAvisos.isEmpty()) {
                    List<String> av = new ArrayList<>(x.avisosCalidad == null ? List.of() : x.avisosCalidad);
                    for (String f : frasesAvisos) {
                        if (!av.contains(f) && av.size() < ArreglosFinal.MAX_AVISOS_CALIDAD) {
                            av.add(f);
                        }
                    }
                    x.avisosCalidad = av;
                    x.menosEstricto = x.menosEstricto || menosEstricto;
                }
                return x;
            });
        }
        Set<String> ids = new LinkedHashSet<>();
        for (Item it : aplicados) {
            ids.add(it.caso());
        }
        if (comun) {
            ids.addAll(ArreglosFinal.pendientesComunes(sesion, proyecto, servicio));
        }
        if (punto != null) {
            Set<String> propios = new LinkedHashSet<>();
            for (Item it : aplicados) {
                propios.add(it.caso());
            }
            sesion.verificaciones().fijarPuntoComun(salidaReal, servicio, new VerificacionesSesion.PuntoComun(
                    punto.archivoTest(), punto.textoPrevio(), punto.servicioPrevio(), propios, punto.estadosPrevios()));
        }
        ArreglosFinal.aplicada(sesion, proyecto, salidaReal, servicio, ids);
        sesion.guardarTanda(proyecto, salidaReal, Boolean.FALSE);
        return salidaReal;
    }

    /**
     * Punto de restauración previo a una corrección de la parte común: archivo de test del servicio y su texto, entrada del
     * servicio en spec.json y estados de sus casos. null si no se puede capturar (sin archivo de test): no habrá deshacer.
     */
    private static VerificacionesSesion.PuntoComun capturarPuntoComun(SesionMcp sesion, Path salida, String fqn, Item primero) {
        try {
            if (fqn == null || primero == null || primero.u == null || primero.u.servicio() == null) {
                return null;
            }
            Path archivo = VerificadorTests.archivoDeCaso(salida, primero.caso());
            if (archivo == null || !Files.isRegularFile(archivo)) {
                return null;
            }
            String texto = Files.readString(archivo, StandardCharsets.UTF_8);
            ObjectNode previo = Json.MAPPER.valueToTree(primero.u.servicio());
            Map<String, String> estados = new LinkedHashMap<>(sesion.estadosDeSesion(salida));
            estados.keySet().retainAll(new HashSet<>(sesion.casosDeServicio(salida, fqn)));
            return new VerificacionesSesion.PuntoComun(archivo, texto, previo, Set.of(), estados);
        } catch (IOException | RuntimeException e) {
            System.err.println("junit-gen: no se pudo guardar el punto de restauración de " + fqn + ": " + e.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * Genera de nuevo los casos {@code nuevos} del servicio (reemplazarCasos con sus ids; con {@code servicioArg}, también
     * la parte común fusionada).
     */
    static Regeneracion regenerar(SesionMcp sesion, Path proyecto, Path salida, Spec spec, ServicioSpec servicio,
                                  Map<String, ObjectNode> nuevos, JsonNode servicioArg) {
        ObjectNode specJson = Json.MAPPER.valueToTree(spec);
        ObjectNode gen = Json.objeto();
        for (String k : List.of("version", "proyecto", "estilo")) {
            JsonNode v = specJson.get(k);
            if (v != null && !v.isNull()) {
                gen.set(k, v);
            }
        }
        ObjectNode sv = Json.MAPPER.valueToTree(servicio);
        sv.remove("seleccion");
        sv.remove("seleccionInformada");
        boolean servicioCambia = false;
        if (servicioArg != null) {
            fusionarImports(sv, servicioArg.get("imports"));
            fusionarFixtures(sv, servicioArg.get("fixtures"));
            JsonNode construccion = servicioArg.get("construccion");
            if (construccion != null && !construccion.isNull() && !(construccion.isObject() && construccion.isEmpty())) {
                sv.set("construccion", construccion);
            }
            servicioCambia = servicioCambia(sv, servicio);
        }
        ArrayNode casos = sv.putArray("casos");
        nuevos.values().forEach(casos::add);
        gen.putArray("servicios").add(sv);
        GeneradorTests.Opciones op = new GeneradorTests.Opciones();
        op.reemplazarComun = servicioArg != null;
        op.reemplazar = new LinkedHashSet<>(nuevos.keySet());
        op.todas = sesion.todas(proyecto);
        op.selecciones = sesion.selecciones(proyecto);
        op.verificarMezcla = HerramientaGenerar.verificarMezcla(sesion, proyecto);
        Fachada.Generacion g = sesion.fachada().generar(gen, null, salida, proyecto, op);
        return new Regeneracion(g, servicioCambia);
    }

    /** Tras una generación aplicada: registro en sesión, progreso del plan y estados olvidados de los remontados. */
    static void trasGenerar(SesionMcp sesion, Path proyecto, Fachada.Generacion g) {
        HerramientaGenerar.registrarEnSesion(sesion, g);
        HerramientaGenerar.progresoPlan(sesion, proyecto, g);
        Path salidaReal = g.escritor() != null ? g.escritor().salida() : sesion.salidaDe(proyecto);
        for (List<String> ids : g.resultado().remontados.values()) {
            sesion.olvidarEstados(salidaReal, ids);
        }
    }

    /** Respuesta cuando la verificación no terminó dentro de la llamada (con los casos rechazados, si los hay). */
    private static Resultado verificando(SesionMcp sesion, List<Item> rechazados) {
        ObjectNode r = Json.objeto();
        r.put("accionRequerida", HerramientaEjecutar.ACCION_PENDIENTE_IA + "La corrección está aplicada y se está probando: "
                + "llama ahora a " + BloquesTanda.llamadaEjecutar(sesion) + " (te dará el resultado y los siguientes casos).");
        r.put("verificando", true);
        if (!rechazados.isEmpty()) {
            ArrayNode a = r.putArray("rechazados");
            rechazados.forEach(it -> a.add(nodoRechazo(it)));
            LimitesRespuesta.acotarJson(r, LimitesRespuesta.PRESUPUESTO_CUERPO, List.of("rechazados"), Set.of(), Set.of(),
                    "Corrige primero los errores mostrados.");
        }
        return new Resultado(Json.compacto(r), false);
    }

    /** {caso, resultado: rechazado, detalle, errores (máx. 3)} de un caso rechazado. */
    private static ObjectNode nodoRechazo(Item it) {
        ObjectNode o = Json.objeto();
        o.put("caso", it.caso());
        o.put("resultado", RESULTADO_RECHAZADO);
        o.put("detalle", DETALLE_RECHAZADO);
        ArrayNode errores = o.putArray("errores");
        for (ErrorValidacion e : it.errores) {
            if (errores.size() >= MAX_ERRORES_RESULTADO) {
                break;
            }
            errores.add(nodoError(e));
        }
        return o;
    }

    private static ObjectNode nodoError(ErrorValidacion e) {
        ObjectNode o = Json.objeto();
        ponerSiHay(o, "ruta", e.ruta);
        ponerSiHay(o, "codigo", e.codigo);
        ponerSiHay(o, "mensaje", e.mensaje);
        ponerSiHay(o, "sugerencia", e.sugerencia);
        return o;
    }

    /** Respuesta con el siguiente caso (o la orden de cerrar) y la tanda guardada. */
    private static Resultado cerrar(SesionMcp sesion, Path proyecto, Path salida, ObjectNode anterior) {
        ObjectNode r = ArreglosFinal.respuesta(sesion, proyecto, salida, anterior);
        sesion.guardarTanda(proyecto, salida, Boolean.FALSE);
        return new Resultado(Json.compacto(r), false);
    }

    /** resultadoAnterior {caso, resultado, detalle} según el registro del caso tras la prueba. */
    static ObjectNode anterior(String caso, Tanda.ArregloTanda a) {
        ObjectNode o = Json.objeto();
        o.put("caso", caso);
        String estado = a == null ? null : a.estado;
        if (Tanda.ARREGLO_ARREGLADO.equals(estado)) {
            o.put("resultado", Tanda.ARREGLO_ARREGLADO);
            o.put("detalle", a.menosEstricto ? "Ahora compila y pasa. Queda anotado en informe.md como test menos estricto, para "
                    + "revisión." : "Ahora compila y pasa.");
        } else if (Tanda.ARREGLO_PENDIENTE.equals(estado)) {
            o.put("resultado", "sigue_fallando");
            if (a.intentos >= 1 && a.error != null && a.error.equals(a.errorInicial)) {
                int quedan = Math.max(0, VerificadorTests.MAX_INTENTOS - a.intentos);
                o.put("detalle", "Sigue fallando con el mismo error que antes de tu cambio: lo que cambiaste no afecta al fallo. No "
                        + "repitas variantes de lo mismo; cambia de enfoque con `pistas` y `codigo`. Te " + (quedan == 1 ? "queda 1 intento"
                        : "quedan " + quedan + " intentos") + ".");
            } else {
                o.put("detalle", "Sigue fallando: mira el caso.error nuevo.");
            }
        } else if (Tanda.ARREGLO_APARTADO.equals(estado) && a.motivo != null && a.motivo.startsWith(ArreglosFinal.MOTIVO_COMUN)) {
            o.put("resultado", Tanda.ARREGLO_APARTADO);
            o.put("detalle", "La clase de test de este servicio no compila por un error común: se han apartado todos sus casos "
                    + "pendientes y quedan anotados en informe.md. No los corrijas más.");
        } else if (Tanda.ARREGLO_APARTADO.equals(estado)) {
            o.put("resultado", Tanda.ARREGLO_APARTADO);
            o.put("detalle", "No se pudo arreglar en 3 intentos: queda comentado en su test y anotado en informe.md. No lo "
                    + "corrijas más.");
        } else if (Tanda.ARREGLO_DUDOSO.equals(estado)) {
            o.put("resultado", Tanda.ARREGLO_DUDOSO);
            o.put("detalle", "No se pudo aclarar: el test se mantiene tal cual como dudoso para que lo revise una persona. No lo "
                    + "corrijas más.");
        } else if (Tanda.ARREGLO_POSIBLE_BUG.equals(estado)) {
            o.put("resultado", Tanda.ARREGLO_POSIBLE_BUG);
            o.put("detalle", "Queda como aviso de un posible fallo en el código: el test se mantiene tal cual.");
        } else {
            o.put("resultado", RESULTADO_NO_PENDIENTE);
            o.put("detalle", "Ese caso ya no está pendiente de arreglo.");
        }
        return o;
    }

    /**
     * Cuenta un rechazo del caso (anti-bucle, y el de la corrección común si el error es común): con demasiados seguidos
     * termina el caso (o aparta el servicio). true = terminado.
     */
    private static boolean contarRechazo(SesionMcp sesion, Path proyecto, Path salida, Tanda.ArregloTanda a) {
        if (a.comun) {
            int nc = sesion.contarSinCorregir(CLAVE_RECHAZO_COMUN + a.servicio, a.intentosComun);
            if (nc > ArreglosFinal.MAX_RECHAZOS_COMUN) {
                ArreglosFinal.apartarServicio(sesion, proyecto, salida, a.servicio, a.error);
                return true;
            }
        }
        int n = sesion.contarSinCorregir(a.caso, a.intentos);
        if (n > ArreglosFinal.MAX_SIN_CORREGIR) {
            ArreglosFinal.terminar(sesion, proyecto, salida, a, ArreglosFinal.MOTIVO_SIN_CORRECCION);
            return true;
        }
        return false;
    }

    /**
     * Corrección rechazada (errores del spec o caso no reescrito): cuenta el rechazo; con demasiados seguidos termina el caso
     * y sigue con el siguiente; si no, devuelve los errores para repetir.
     */
    private static Resultado rechazo(SesionMcp sesion, Path proyecto, Path salida, Tanda.ArregloTanda a, ResultadoGeneracion rg,
                                     String caso, ObjectNode nuevo, boolean conServicio) {
        List<ErrorValidacion> errores = new ArrayList<>(rg.errores);
        if (errores.isEmpty()) {
            Motivo m = motivoNoReescrito(rg, caso, a, conServicio);
            errores.add(ErrorValidacion.error(null, m.codigo(), m.mensaje(), null));
        }
        return rechazo(sesion, proyecto, salida, a, errores, caso, nuevo);
    }

    /** Como {@link #rechazo(SesionMcp, Path, Path, Tanda.ArregloTanda, ResultadoGeneracion, String, ObjectNode, boolean)} con los errores ya calculados. */
    private static Resultado rechazo(SesionMcp sesion, Path proyecto, Path salida, Tanda.ArregloTanda a, List<ErrorValidacion> lista,
                                     String caso, ObjectNode nuevo) {
        if (contarRechazo(sesion, proyecto, salida, a)) {
            return cerrar(sesion, proyecto, salida, anterior(caso, sesion.arreglo(proyecto, caso)));
        }
        ObjectNode r = Json.objeto();
        if (a.comun) {
            r.put("accionRequerida", HerramientaEjecutar.ACCION_PENDIENTE_IA + "arreglar_caso no ha cambiado nada: corrige estos "
                    + "errores y repite arreglar_caso {caso: \"" + caso + "\", servicio: {imports, construccion, fixtures}} (añade spec "
                    + "solo si también cambia el caso). Si no lo consigues, junit-gen apartará el servicio solo; no escribas al usuario.");
        } else {
            r.put("accionRequerida", HerramientaEjecutar.ACCION_PENDIENTE_IA + "arreglar_caso no ha cambiado nada: corrige estos errores "
                    + "del spec y repite arreglar_caso {caso: \"" + caso + "\", spec}. Si no lo consigues, junit-gen lo apartará solo; no "
                    + "escribas al usuario.");
        }
        ArrayNode errores = r.putArray("errores");
        for (ErrorValidacion e : lista) {
            if (errores.size() >= MAX_ERRORES) {
                break;
            }
            errores.add(nodoError(e));
        }
        ObjectNode c = r.putObject("caso");
        c.put("id", caso);
        ponerSiHay(c, "error", a.error);
        ponerSiHay(c, "pista", a.pista);
        if (nuevo != null) {
            r.set("spec", nuevo);
        }
        LimitesRespuesta.acotarJson(r, LimitesRespuesta.PRESUPUESTO_CUERPO, List.of("errores"), Set.of(), Set.of("spec"),
                "Corrige primero los errores mostrados.");
        return new Resultado(Json.compacto(r), true);
    }

    /** Código ({@link #COD_NO_REESCRITO} o {@link #COD_SIN_CAMBIOS}) y mensaje de un rechazo sin errores de validación. */
    private record Motivo(String codigo, String mensaje) {
    }

    /**
     * Por qué no se aplicó nada: duplicado o archivo no tocado ({@link #COD_NO_REESCRITO}); si no, nada que cambiar
     * ({@link #COD_SIN_CAMBIOS}) con lo que hay que enviar según dónde esté el error.
     */
    private static Motivo motivoNoReescrito(ResultadoGeneracion rg, String caso, Tanda.ArregloTanda a, boolean conServicio) {
        for (Duplicado d : rg.descartados) {
            if (caso.equals(d.caso)) {
                return new Motivo(COD_NO_REESCRITO, LimitesRespuesta.recortar("Descartado por duplicado: " + d.motivo, ArreglosFinal.MAX_ERROR));
            }
        }
        if (!rg.noTocados.isEmpty()) {
            return new Motivo(COD_NO_REESCRITO, LimitesRespuesta.recortar("Archivo no tocado: " + rg.noTocados.get(0), ArreglosFinal.MAX_ERROR));
        }
        if (a.comun && !conServicio) {
            return new Motivo(COD_SIN_CAMBIOS, "El error no está en este caso sino en la parte común del test: enviar solo `spec` no "
                    + "cambia nada. Envía `servicio` con lo que hay que corregir: `imports` (nombre completo exacto de cada tipo), "
                    + "`construccion` (cómo se crea el servicio) o `fixtures`.");
        }
        if (a.comun) {
            return new Motivo(COD_SIN_CAMBIOS, "La parte común enviada (`servicio`) es igual a la guardada y el caso tampoco cambia: no "
                    + "hay nada que aplicar. Cambia de verdad `servicio.imports`, `servicio.construccion` o `servicio.fixtures` según "
                    + "caso.error.");
        }
        if (conServicio && rg.omitidos.contains(caso)) {
            return new Motivo(COD_SIN_CAMBIOS, "El error está en este caso, no en la parte común: envía `spec` con el caso corregido "
                    + "(datos, stubs, esperado o aserciones).");
        }
        if (rg.omitidos.contains(caso)) {
            return new Motivo(COD_SIN_CAMBIOS, "El caso corregido es idéntico al actual: no ha cambiado nada. Cambia de verdad su contenido.");
        }
        return new Motivo(COD_SIN_CAMBIOS, "El caso no se reescribió: cambia de verdad su contenido (datos, stubs, esperado o aserciones).");
    }

    /**
     * Imports recibidos (en su orden) seguidos de los guardados cuyo nombre simple no aparece entre los recibidos. Sin
     * {@code recibidos} no cambia nada; si no es una lista se pasa tal cual (la validación lo rechaza).
     */
    private static void fusionarImports(ObjectNode sv, JsonNode recibidos) {
        if (recibidos == null || recibidos.isNull()) {
            return;
        }
        if (!recibidos.isArray()) {
            sv.set("imports", recibidos);
            return;
        }
        ArrayNode r = Json.MAPPER.createArrayNode();
        Set<String> claves = new HashSet<>();
        for (JsonNode i : recibidos) {
            r.add(i);
            if (i != null && i.isTextual()) {
                claves.add(claveImport(i.asText()));
            }
        }
        JsonNode guardados = sv.get("imports");
        if (guardados != null && guardados.isArray()) {
            for (JsonNode g : guardados) {
                if (g != null && g.isTextual() && !claves.contains(claveImport(g.asText()))) {
                    r.add(g);
                }
            }
        }
        sv.set("imports", r);
    }

    /** Nombre simple de un import (con «static » delante si lo es); los comodín se comparan enteros. */
    private static String claveImport(String imp) {
        String t = imp.trim();
        boolean estatico = t.startsWith("static ");
        String nombre = estatico ? t.substring("static ".length()).trim() : t;
        String simple = nombre.endsWith(".*") ? nombre : nombre.substring(nombre.lastIndexOf('.') + 1);
        return (estatico ? "static " : "") + simple;
    }

    /**
     * Fixtures guardadas con las recibidas sustituyendo a las del mismo {@code nombre} y añadiendo las nuevas. Sin
     * {@code recibidas} no cambia nada; si no es una lista se pasa tal cual (la validación lo rechaza).
     */
    private static void fusionarFixtures(ObjectNode sv, JsonNode recibidas) {
        if (recibidas == null || recibidas.isNull()) {
            return;
        }
        JsonNode guardadas = sv.get("fixtures");
        if (!recibidas.isArray() || guardadas == null || !guardadas.isArray()) {
            sv.set("fixtures", recibidas);
            return;
        }
        ArrayNode r = ((ArrayNode) guardadas).deepCopy();
        for (JsonNode f : recibidas) {
            String nombre = f != null && f.isObject() && f.get("nombre") != null && f.get("nombre").isTextual()
                    ? f.get("nombre").asText() : null;
            int pos = -1;
            if (nombre != null) {
                for (int k = 0; k < r.size(); k++) {
                    JsonNode g = r.get(k);
                    if (g != null && g.isObject() && g.get("nombre") != null && nombre.equals(g.get("nombre").asText())) {
                        pos = k;
                        break;
                    }
                }
            }
            if (pos < 0) {
                r.add(f);
            } else {
                r.set(pos, f);
            }
        }
        sv.set("fixtures", r);
    }

    /** true si la parte común (imports, construccion, fixtures) de {@code sv} difiere de la guardada; ante cualquier fallo, false. */
    private static boolean servicioCambia(ObjectNode sv, ServicioSpec guardado) {
        try {
            ObjectNode sinCasos = sv.deepCopy();
            sinCasos.remove("casos");
            ServicioSpec nuevo = Json.MAPPER.treeToValue(sinCasos, ServicioSpec.class);
            return !ArreglosFinal.parteComun(nuevo).equals(ArreglosFinal.parteComun(guardado));
        } catch (JsonProcessingException | RuntimeException e) {
            System.err.println("junit-gen: no se pudo comparar la parte común enviada: " + e.getClass().getSimpleName());
            return false;
        }
    }

    /** Caso validado con ese id de la generación, o null. */
    private static CasoSpec validado(ResultadoValidacion val, String caso) {
        if (val == null) {
            return null;
        }
        for (ResultadoValidacion.ServicioValidado sv : val.servicios) {
            for (ResultadoValidacion.CasoValidado cv : sv.casos) {
                if (cv.spec != null && caso.equals(cv.spec.id)) {
                    return cv.spec;
                }
            }
        }
        return null;
    }

    /** Argumento objeto (o cadena con el JSON de un objeto); null si falta. */
    private static JsonNode objeto(JsonNode args, String campo) {
        JsonNode v = args == null ? null : args.get(campo);
        if (v == null || v.isNull()) {
            return null;
        }
        if (v.isTextual()) {
            if (v.asText().isBlank()) {
                return null;
            }
            v = Json.leer(v.asText());
        }
        if (v == null || !v.isObject()) {
            throw new IllegalArgumentException("'" + campo + "' debe ser un objeto JSON.");
        }
        return v;
    }

    private static void ponerSiHay(ObjectNode n, String k, String v) {
        if (v != null && !v.isBlank()) {
            n.put(k, v);
        }
    }
}
