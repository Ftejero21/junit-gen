package com.junitgen.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.junitgen.generacion.AlmacenSpec;
import com.junitgen.seguridad.RutaSegura;
import com.junitgen.spec.CasoSpec;
import com.junitgen.spec.Spec;
import com.junitgen.util.Json;
import com.junitgen.util.LimitesRespuesta;
import com.junitgen.util.Textos;
import com.junitgen.verificacion.ClasificadorFallos;
import com.junitgen.verificacion.ResultadoTest;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * estado_verificacion: consulta ligera (no ejecuta nada) de las verificaciones en segundo plano de la sesión:
 * progreso, casos de montaje o compilación a corregir con su error y, en el modo por defecto
 * ({@code fallosAsercion: revisarIa}), los fallos de aserción que la IA debe revisar ({@code revisar}) o corregir
 * tras un veredicto error_ia ({@code reemplazar}), con su error y su spec. Reconcilia con spec.json la pista en
 * memoria de revisiones pendientes (solo quitando).
 */
public class HerramientaEstadoVerificacion implements Herramienta {

    public static final String NOMBRE = "estado_verificacion";
    /** Reserva para la paginación al repartir los errores en páginas. */
    static final int RESERVA_PAGINACION = 150;
    static final String COMO_VER_RESTO = "estado_verificacion {pagina: 2} devuelve la siguiente página de errores.";
    static final List<String> ORDEN_RECORTE = List.of("errores", "corregir", "revisar", "reemplazar");

    @Override
    public String nombre() {
        return NOMBRE;
    }

    @Override
    public String titulo() {
        return "Estado de la verificación en segundo plano";
    }

    @Override
    public String descripcion() {
        return "Consulta ligera (no ejecuta nada) de las pruebas en segundo plano de los tests generados en esta sesión: "
                + "progreso {serviciosConTests, verificados, verificando, enCola}, corregir [{servicio, intento, reemplazarCasos}] "
                + "con los casos que no compilan o fallan al montarse, revisar [{servicio, casos}] con los fallos de aserción sin "
                + "veredicto, reemplazar [{servicio, reemplazarCasos}] con los que ya tienen error_ia registrado, errores por caso "
                + "(mensaje recortado y `pista` con las firmas reales de constructores y métodos en los que no compilan; en los "
                + "de aserción también tipo, metodo, motivoIa, intento y su spec; paginado con 'pagina') y "
                + "apartados.fallosAsercion. Llámala cuando una respuesta traiga la nota «Verificación en segundo plano…»; el "
                + "rechazo de seleccionar_funciones/detalle_servicio ya trae errores, pistas e ids (no hace falta llamarla antes). "
                + "Corrige en el spec los casos de 'corregir' (nunca el .java ni el proyecto) y llama a generar_tests (valida el "
                + "spec) con reemplazarCasos = esos ids (un servicio por llamada): se vuelven a probar solos (máximo 3 intentos; "
                + "después, o si avanzas con casos aún rotos, junit-gen los aparta solo). Los fallos "
                + "de aserción los revisas tú, sin preguntar al usuario (no uses ask_user): por cada caso de 'revisar' relee el "
                + "código con detalle_servicio {clase, metodos} y registra todos los veredictos (error_ia | bug_servicio | dudoso, "
                + "con motivo basado en el código) en UNA llamada a registrar_analisis_ia; los de 'reemplazar' corrígelos en el "
                + "spec y generar_tests con reemplazarCasos. Solo con `fallosAsercion: preguntar` en junit-gen.yml se preguntan al "
                + "usuario al final. No la uses para sondear entre servicios. Con `arreglos: alFinal` (por defecto) solo devuelve "
                + "progreso y contadores: los fallos se arreglan al final con arreglar_caso.";
    }

    @Override
    public ObjectNode esquema() {
        ObjectNode s = Herramienta.esquemaBase();
        Herramienta.propiedad(s, "rutaProyecto", "string", "Ruta del proyecto analizado (por defecto, el de la sesión; no hace falta).", false);
        Herramienta.propiedad(s, "pagina", "integer", "Página (1..) de la lista de errores.", false);
        ((ObjectNode) s.get("properties").get("pagina")).put("minimum", 1);
        return s;
    }

    @Override
    public boolean soloLectura() {
        return true;
    }

    @Override
    public String aviso(JsonNode args, SesionMcp sesion) {
        return RutasSesion.aviso(args, sesion, "rutaProyecto", null, "(proyecto de la sesión)");
    }

    @Override
    public Resultado ejecutar(JsonNode args, SesionMcp sesion) {
        Path conocido = RutasSesion.proyectoConocido(args, sesion, "rutaProyecto");
        if (conocido == null) {
            throw new IllegalArgumentException("No hay ningún proyecto en esta sesión: llama antes a escanear_proyecto.");
        }
        Path proyecto = RutaSegura.validarProyecto(conocido);
        Path salida = sesion.salidaDe(proyecto);
        if (salida == null) {
            return new Resultado("Aún no hay carpeta de salida ni tests generados en esta sesión: no hay nada que verificar. "
                    + "Sigue con el plan.", false);
        }
        int pagina = Math.max(1, Herramienta.entero(args, "pagina", 1));
        VerificacionesSesion ver = sesion.verificaciones();
        VerificacionesSesion.Progreso pr = ver.progreso(salida);
        List<VerificacionesSesion.Correccion> cs = ver.correcciones(salida);
        Map<String, String> estados = sesion.estadosDeSesion(salida);
        Map<String, String> servicios = sesion.serviciosDeCasos(salida);

        ObjectNode r = Json.objeto();
        ObjectNode p = r.putObject("progreso");
        p.put("serviciosConTests", pr.total());
        p.put("verificados", pr.verificados());
        if (pr.enCurso() != null) {
            p.put("verificando", pr.enCurso());
        } else {
            p.putNull("verificando");
        }
        p.put("enCola", pr.enCola());
        if (GuiaTanda.arreglosAlFinal(sesion, proyecto)) {
            // Arreglos al final: sin correcciones ahora; solo progreso y contadores de los casos apuntados.
            int[] cu = ArreglosFinal.cuenta(sesion, proyecto);
            ObjectNode a = r.putObject("arreglos");
            a.put("pendientes", cu[1]);
            a.put("arreglados", cu[2]);
            a.put("posiblesBugs", cu[3]);
            a.put("apartados", cu[4]);
            r.put("instruccion", ArreglosFinal.enFase(sesion, proyecto)
                    ? "Fase de arreglo: llama a ejecutar_tests {}: devuelve los casos fallidos del siguiente servicio (con su error, "
                    + "pistas, spec y código); corrígelos en una llamada a arreglar_caso."
                    : "No corrijas nada ahora: sigue la primera línea accionRequerida; los casos que fallen se arreglan al final, "
                    + "agrupados por servicio.");
            return new Resultado(Json.compacto(r), false);
        }
        ArrayNode corregir = r.putArray("corregir");
        List<Map<String, Object>> errores = new ArrayList<>();
        for (VerificacionesSesion.Correccion c : cs) {
            ObjectNode o = corregir.addObject();
            o.put("servicio", c.fqn());
            o.put("intento", c.intento());
            ArrayNode ids = o.putArray("reemplazarCasos");
            c.ids().forEach(ids::add);
            for (String id : c.ids()) {
                ResultadoTest t = ver.ultimo(salida, id);
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("caso", id);
                m.put("servicio", Textos.nombreSimple(c.fqn()));
                m.put("metodoTest", t == null ? null : t.metodoTest);
                m.put("estado", estados.get(id));
                m.put("mensaje", t == null ? null : LimitesRespuesta.recortar(t.mensaje, HerramientaEjecutar.MAX_MENSAJE));
                m.put("pista", t == null ? null : t.pista);
                m.values().removeIf(v -> v == null);
                errores.add(m);
            }
        }
        int asercion = 0;
        for (Map.Entry<String, String> e : estados.entrySet()) {
            if (ClasificadorFallos.FALLO_ASERCION.equals(e.getValue()) && servicios.containsKey(e.getKey())) {
                asercion++;
            }
        }
        boolean auto = HerramientaEjecutar.revisionAutomatica(proyecto);
        int enRevision = 0;
        if (auto) {
            HerramientaEjecutar.PendientesAsercion pa = HerramientaEjecutar.sincronizarRevisiones(sesion, salida);
            if (pa != null && (!pa.revisar().isEmpty() || !pa.reemplazar().isEmpty())) {
                Spec spec = leerSpec(salida);
                grupos(r.putArray("revisar"), pa.revisar(), servicios, "casos");
                grupos(r.putArray("reemplazar"), pa.reemplazar(), servicios, "reemplazarCasos");
                for (String id : pa.revisar()) {
                    errores.add(errorAsercion(id, "revisar", spec, servicios, estados, ver.ultimo(salida, id)));
                }
                for (String id : pa.reemplazar()) {
                    errores.add(errorAsercion(id, "reemplazar", spec, servicios, estados, ver.ultimo(salida, id)));
                }
                enRevision = pa.revisar().size() + pa.reemplazar().size();
            }
        }
        int apartadas = Math.max(0, asercion - enRevision);
        SesionMcp.ProgresoPlan pp = sesion.progresoPlan(proyecto);
        boolean planCompleto = pp == null || pp.restantes() == 0;
        String instruccion;
        if (!cs.isEmpty()) {
            instruccion = "Corrige en el spec los casos de `corregir` (nunca el .java ni el proyecto) y llama a generar_tests "
                    + "(valida el spec; si devuelve errores, corrige y repite) con reemplazarCasos = esos ids (un servicio por "
                    + "llamada); se vuelven a probar solos. Mientras corriges un servicio no se aplican recetas automáticas: "
                    + "incluye en `construccion` todo lo que necesite. Después continúa con el plan. Cada error trae `pista` "
                    + "(firmas reales de constructores y métodos): úsala tal cual. Si no consigues arreglar un caso, sigue con el "
                    + "plan: junit-gen lo apartará solo; nunca escribas al usuario.";
            if (enRevision > 0) {
                instruccion += " " + INSTRUCCION_ASERCION;
            }
        } else if (enRevision > 0) {
            instruccion = INSTRUCCION_ASERCION + (planCompleto ? " Después llama a ejecutar_tests sin argumentos."
                    : " Después continúa con el plan.");
        } else {
            instruccion = planCompleto ? "Nada que corregir; llama a ejecutar_tests sin argumentos." : "Nada que corregir; sigue con el plan.";
        }
        if (asercion > 0 && !auto) {
            instruccion += " Los fallos de aserción se preguntan al usuario al final; no los toques.";
        }
        // Errores paginados por bytes: lo que queda libre tras el resto de la respuesta.
        ObjectNode sinErrores = r.deepCopy();
        sinErrores.putObject("apartados").put("fallosAsercion", apartadas);
        sinErrores.put("instruccion", instruccion);
        int libre = LimitesRespuesta.PRESUPUESTO_CUERPO - LimitesRespuesta.bytes(Json.compacto(sinErrores)) - RESERVA_PAGINACION;
        int primera = Math.max(HerramientaEjecutar.MIN_PAGINA_RESULTADOS, Math.min(LimitesRespuesta.PAGINA_RESULTADOS, libre));
        List<List<Map<String, Object>>> paginas = HerramientaEjecutar.paginasResultados(errores, primera);
        int pg = Math.min(pagina, paginas.size());
        r.set("errores", Json.MAPPER.valueToTree(paginas.get(pg - 1)));
        if (paginas.size() > 1) {
            ObjectNode pag = r.putObject("paginacion");
            pag.put("pagina", pg);
            pag.put("paginas", paginas.size());
            if (pg < paginas.size()) {
                pag.putObject("siguiente").put("pagina", pg + 1);
            } else {
                pag.putNull("siguiente");
            }
        }
        r.putObject("apartados").put("fallosAsercion", apartadas);
        r.put("instruccion", instruccion);
        LimitesRespuesta.acotarJson(r, LimitesRespuesta.PRESUPUESTO_CUERPO, ORDEN_RECORTE, COMO_VER_RESTO);
        return new Resultado(Json.compacto(r), false);
    }

    /** Campos del caso que añade la herramienta y no se devuelven en {@code errores[].spec}. */
    static final List<String> CAMPOS_INTERNOS = List.of("huella", "huellaEntrada", "estado", "intentosMontaje", "veredictoIa",
            "motivoIa", "autorizacionCambio");
    static final String INSTRUCCION_ASERCION = "Fallos de aserción (`revisar`): NO preguntes al usuario. Por cada caso relee "
            + "el código con detalle_servicio {clase, metodos:[método]} y decide con un motivo que cite el código: error_ia (test "
            + "mal planteado) o bug_servicio (el código contradice lo que la función debe hacer); dudoso solo si no puedes "
            + "decidir. Registra TODOS en UNA llamada a registrar_analisis_ia. Casos de `reemplazar` (error_ia ya registrado): "
            + "corrige esperado/aserciones partiendo de `errores[].spec` según el código y llama a generar_tests con "
            + "reemplazarCasos = esos ids (un servicio por llamada); se vuelven a probar solos (máximo 3 intentos). Prohibido "
            + "copiar el valor obtenido como esperado sin justificarlo con el código.";

    /** Agrupa los ids por servicio en {@code destino}: [{servicio, clave: [ids]}]. */
    private static void grupos(ArrayNode destino, List<String> ids, Map<String, String> servicios, String clave) {
        Map<String, List<String>> porServicio = new LinkedHashMap<>();
        for (String id : ids) {
            String fqn = servicios.get(id);
            porServicio.computeIfAbsent(fqn == null ? "" : fqn, k -> new ArrayList<>()).add(id);
        }
        porServicio.forEach((fqn, lista) -> {
            ObjectNode o = destino.addObject();
            if (!fqn.isEmpty()) {
                o.put("servicio", fqn);
            }
            ArrayNode a = o.putArray(clave);
            lista.forEach(a::add);
        });
    }

    /** Entrada de {@code errores} de un caso con fallo de aserción, con su spec limpio (sin campos internos ni arrays vacíos). */
    private static Map<String, Object> errorAsercion(String id, String tipo, Spec spec, Map<String, String> servicios,
                                                     Map<String, String> estados, ResultadoTest t) {
        AlmacenSpec.Ubicacion u = spec == null ? null : AlmacenSpec.buscar(spec, id);
        CasoSpec c = u == null ? null : u.caso();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("caso", id);
        String fqn = servicios.get(id);
        m.put("servicio", fqn == null ? null : Textos.nombreSimple(fqn));
        m.put("metodo", c == null ? null : c.metodo);
        m.put("metodoTest", t == null ? null : t.metodoTest);
        m.put("estado", estados.get(id));
        m.put("tipo", tipo);
        m.put("mensaje", t == null ? null : LimitesRespuesta.recortar(t.mensaje, HerramientaEjecutar.MAX_MENSAJE));
        if (c != null) {
            m.put("motivoIa", c.motivoIa);
            m.put("intento", c.intentosMontaje);
            m.put("spec", specLimpio(c));
        }
        m.values().removeIf(v -> v == null);
        return m;
    }

    /** Caso del spec como JSON sin {@link #CAMPOS_INTERNOS}, nulos ni arrays vacíos. */
    static ObjectNode specLimpio(CasoSpec c) {
        ObjectNode s = Json.MAPPER.valueToTree(c);
        CAMPOS_INTERNOS.forEach(s::remove);
        List<String> vacios = new ArrayList<>();
        s.fields().forEachRemaining(e -> {
            if (e.getValue().isNull() || (e.getValue().isArray() && e.getValue().isEmpty())) {
                vacios.add(e.getKey());
            }
        });
        vacios.forEach(s::remove);
        return s;
    }

    /** spec.json de la salida, o null si no existe o no se puede leer (sin información: no cambia ningún estado). */
    private static Spec leerSpec(Path salida) {
        try {
            return AlmacenSpec.leer(salida);
        } catch (RuntimeException e) {
            // spec.json ilegible o bloqueado: se responde sin el spec de cada caso.
            return null;
        }
    }
}
