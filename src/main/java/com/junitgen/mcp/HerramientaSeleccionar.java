package com.junitgen.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.junitgen.escaneo.InfoClase;
import com.junitgen.escaneo.InfoMetodo;
import com.junitgen.escaneo.ModuloEscaneado;
import com.junitgen.escaneo.PaqueteTrabajo;
import com.junitgen.escaneo.PlanGeneracion;
import com.junitgen.escaneo.ProyectoEscaneado;
import com.junitgen.escaneo.SeleccionFunciones;
import com.junitgen.generacion.AlmacenSpec;
import com.junitgen.spec.ErrorValidacion;
import com.junitgen.spec.SeleccionSpec;
import com.junitgen.util.Json;
import com.junitgen.util.LimitesRespuesta;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * seleccionar_funciones: sin {@code funciones}, lista las funciones candidatas de un servicio (datos objetivos,
 * sin cuerpos, paginadas); con {@code funciones}, valida la elección de la IA (cupo, mínimo, triviales, motivos),
 * la guarda en la sesión y recalcula el plan del servicio. No escribe archivos: la selección llega al spec.json
 * solo al generar.
 */
public class HerramientaSeleccionar implements Herramienta {

    static final String CODIGO_FIJADA_USUARIO = "SELECCION_FIJADA_POR_USUARIO";
    static final int MAX_ERRORES = 10;

    @Override
    public String nombre() {
        return "seleccionar_funciones";
    }

    @Override
    public String titulo() {
        return "Seleccionar funciones";
    }

    @Override
    public String descripcion() {
        return "Paso obligatorio por servicio antes de generar_tests (salvo `todas`, funciones nombradas por el usuario o selección "
                + "guardada). Sin 'funciones' lista las candidatas (puntuación, escribe, tx, excepciones, cupo). Con 'funciones' "
                + "registra TU elección por importancia de negocio (lo que más daño haría si fallara; motivo de 1 frase; toda "
                + "función con escribe que omitas va en 'omitidas' con motivo). Corrige y repite si hay errores. La respuesta trae "
                + "el paquete de trabajo: sigue con generar_tests. Sigue accionRequerida.";
    }

    @Override
    public ObjectNode esquema() {
        ObjectNode s = Herramienta.esquemaBase();
        ObjectNode props = (ObjectNode) s.get("properties");
        Herramienta.propiedad(s, "clase", "string", "FQN o nombre simple del servicio.", true);
        entradas(props, "funciones", "Elegidas por importancia de negocio: {nombre, motivo}. Sin ellas, devuelve las candidatas.");
        entradas(props, "omitidas", "Funciones que dejas fuera con motivo; obligatorio si escriben datos (escribe).");
        Herramienta.propiedad(s, "pagina", "integer", "Página de las candidatas (desde 1).", false);
        ((ObjectNode) props.get("pagina")).put("minimum", 1);
        Herramienta.propiedad(s, "ruta", "string", "Ruta del proyecto; por defecto, la del último escaneo.", false);
        Herramienta.propiedad(s, "modulo", "string", "Módulo, si la clase existe en varios.", false);
        return s;
    }

    private static void entradas(ObjectNode props, String nombre, String descripcion) {
        ObjectNode a = props.putObject(nombre);
        a.put("type", "array");
        a.put("description", descripcion);
        a.put("maxItems", SeleccionFunciones.MAX_ENTRADAS);
        ObjectNode item = a.putObject("items");
        item.put("type", "object");
        ObjectNode ip = item.putObject("properties");
        ip.putObject("nombre").put("type", "string").put("description", "Nombre o clave nombre(Tipos).");
        ip.putObject("motivo").put("type", "string").put("description", "1 frase (máx. " + SeleccionFunciones.MAX_MOTIVO
                + " caracteres).");
        item.putArray("required").add("nombre");
    }

    @Override
    public boolean soloLectura() {
        return true;
    }

    @Override
    public String aviso(JsonNode args, SesionMcp sesion) {
        return HerramientaDetalle.avisoDetalle(args, sesion);
    }

    @Override
    public Resultado ejecutar(JsonNode args, SesionMcp sesion) {
        String clase = Herramienta.obligatorio(args, "clase");
        Path ruta = Herramienta.ruta(args, "ruta");
        if (ruta == null && sesion.fachada().ultimaRuta() == null) {
            ruta = RutasSesion.proyectoPorDefecto(sesion);
        }
        Path base = ruta != null ? ruta : sesion.fachada().ultimaRuta();
        String modulo = Herramienta.texto(args, "modulo");
        ProyectoEscaneado.Encontrada en = sesion.fachada().localizarClase(clase, ruta, modulo, sesion.modulosTrabajo(base));
        ModuloEscaneado me = en.modulo();
        InfoClase c = en.clase();
        Path salida = sesion.salidaDe(base);
        String bloqueo = sesion.verificaciones().bloqueo(salida, c.fqn);
        if (bloqueo != null) {
            return new Resultado(bloqueo, true);
        }
        if (salida == null || !sesion.verificaciones().puertaActiva(salida)) {
            // Puerta de plan: no se empieza (ni se pre-lee) otro servicio del plan mientras el servicio en curso no esté completo.
            String bloqueoPlan = GuiaTanda.bloqueoPlan(sesion, base, salida, c.fqn);
            if (bloqueoPlan != null) {
                return new Resultado(bloqueoPlan, true);
            }
        }
        if (sesion.todas(base)) {
            return new Resultado("Modo `todas` activo: no hace falta selección. Siguiente paso: detalle_servicio {clase: \""
                    + c.fqn + "\", paquete: 1}" + casosRequeridos(sesion, base, c.fqn) + "\n", false);
        }
        JsonNode fs = args == null ? null : args.get("funciones");
        if (fs == null || fs.isNull()) {
            return new Resultado(lista(sesion, base, salida, me, c, Math.max(1, Herramienta.entero(args, "pagina", 1))), false);
        }
        return eleccion(args, sesion, base, ruta, salida, me, c);
    }

    /** Modo lista: cabecera + líneas paginadas + pie + página + selección actual (ia/usuario). */
    private static String lista(SesionMcp sesion, Path base, Path salida, ModuloEscaneado me, InfoClase c, int pagina) {
        SeleccionSpec actual = SeleccionFunciones.guardada(sesion.selecciones(base), AlmacenSpec.selecciones(salida), c.fqn);
        SeleccionFunciones sel = SeleccionFunciones.de(me, c, false, actual);
        String cabecera = sel.cabeceraCandidatas();
        String pie = sel.pieCandidatas();
        String actualMd = "";
        if (!SeleccionFunciones.ORIGEN_JAVA.equals(sel.origen())) {
            List<String> claves = new ArrayList<>();
            sel.seleccionados().forEach(m -> claves.add(m.clave()));
            actualMd = "\nSelección actual (origen " + sel.origen() + "): "
                    + LimitesRespuesta.recortar(String.join(", ", claves), 600) + "\n";
        }
        List<String> lineas = sel.lineasCandidatas();
        if (lineas.isEmpty()) {
            return cabecera + "Sin funciones no triviales: no hay nada que elegir (solo con `todas` o si el usuario las nombra).\n"
                    + pie + actualMd;
        }
        int presupuesto = LimitesRespuesta.PRESUPUESTO_CUERPO - LimitesRespuesta.bytes(cabecera) - LimitesRespuesta.bytes(pie)
                - LimitesRespuesta.bytes(actualMd) - 200;
        List<List<String>> paginas = LimitesRespuesta.paginar(lineas, Math.max(500, presupuesto), 0);
        int k = Math.min(pagina, paginas.size());
        StringBuilder sb = new StringBuilder(cabecera);
        paginas.get(k - 1).forEach(sb::append);
        sb.append(pie);
        sb.append("\nPágina ").append(k).append(" de ").append(paginas.size()).append('.');
        if (pagina > paginas.size()) {
            sb.append(" (pediste la ").append(pagina).append("; se muestra la última.)");
        }
        if (k < paginas.size()) {
            sb.append(" Siguiente: seleccionar_funciones {clase: \"").append(c.fqn).append("\", pagina: ").append(k + 1).append('}');
        }
        return sb.append('\n').append(actualMd).toString();
    }

    /** Resultado de registrar una elección: la validación y, si es válida, la selección efectiva y sus objetivos. */
    private record Registro(SeleccionFunciones.Eleccion eleccion, SeleccionFunciones seleccion,
                            List<PlanGeneracion.Objetivo> objetivos) {
    }

    /**
     * Valida la elección {@code propuesta} del servicio (mismas reglas de siempre) y, si es válida, la guarda en la sesión
     * ({@code fijarSeleccion}) y recalcula el plan del servicio ({@code reemplazarPlanServicio}) cuando está en el plan.
     * La usan seleccionar_funciones y generar_tests (selección dentro del spec).
     */
    static SeleccionFunciones.Eleccion registrarEleccion(SesionMcp sesion, Path base, Path salida, ModuloEscaneado me, InfoClase c,
                                                         SeleccionSpec propuesta) {
        return registrar(sesion, base, salida, me, c, propuesta).eleccion();
    }

    private static Registro registrar(SesionMcp sesion, Path base, Path salida, ModuloEscaneado me, InfoClase c,
                                      SeleccionSpec propuesta) {
        SeleccionFunciones.Eleccion el = SeleccionFunciones.validarEleccion(me, c, propuesta);
        if (!el.valida()) {
            return new Registro(el, null, List.of());
        }
        sesion.fijarSeleccion(base, c.fqn, el.normalizada());
        SeleccionFunciones sel = SeleccionFunciones.de(me, c, false, el.normalizada());
        List<PlanGeneracion.Objetivo> objetivos = PlanGeneracion.objetivosDe(me, c, salida, sel);
        if (sesion.enPlan(base, c.fqn)) {
            List<SesionMcp.MetodoPlan> metodos = new ArrayList<>();
            for (PlanGeneracion.Objetivo o : objetivos) {
                metodos.add(new SesionMcp.MetodoPlan(o.servicio.fqn, o.metodo.nombre, o.casos, o.conTests));
            }
            sesion.reemplazarPlanServicio(base, c.fqn, metodos);
        }
        return new Registro(el, sel, objetivos);
    }

    /** Modo elección: valida (origen ia), guarda en la sesión y recalcula el plan del servicio. */
    private static Resultado eleccion(JsonNode args, SesionMcp sesion, Path base, Path ruta, Path salida, ModuloEscaneado me,
                                      InfoClase c) {
        SeleccionSpec actual = sesion.selecciones(base).get(c.fqn);
        SeleccionFunciones ref = SeleccionFunciones.de(me, c, false);
        if (actual != null && SeleccionFunciones.ORIGEN_USUARIO.equals(actual.origen)) {
            return errores(List.of(ErrorValidacion.error("funciones", CODIGO_FIJADA_USUARIO,
                    "El usuario nombró las funciones en esta petición; continúa con detalle_servicio.",
                    "Siguiente paso: detalle_servicio {clase: \"" + c.fqn + "\", paquete: 1}")), ref);
        }
        SeleccionSpec propuesta = new SeleccionSpec();
        propuesta.origen = SeleccionFunciones.ORIGEN_IA;
        propuesta.funciones = leerEntradas(args.get("funciones"));
        JsonNode om = args.get("omitidas");
        propuesta.omitidas = om == null || om.isNull() ? null : leerEntradas(om);
        Registro reg = registrar(sesion, base, salida, me, c, propuesta);
        SeleccionFunciones.Eleccion el = reg.eleccion();
        if (!el.valida()) {
            return errores(el.errores(), ref);
        }
        SeleccionFunciones sel = reg.seleccion();
        List<PlanGeneracion.Objetivo> objetivos = reg.objetivos();
        boolean enPlan = sesion.enPlan(base, c.fqn);
        Map<InfoMetodo, Integer> casosPorMetodo = new LinkedHashMap<>();
        for (PlanGeneracion.Objetivo o : objetivos) {
            casosPorMetodo.merge(o.metodo, o.casos, Integer::sum);
        }
        StringBuilder sb = new StringBuilder("Selección registrada para `").append(c.fqn).append("` (origen ia): ")
                .append(sel.seleccionados().size()).append(" de cupo ").append(sel.cupo()).append("\n\n");
        StringBuilder lineas = new StringBuilder();
        for (InfoMetodo m : sel.seleccionados()) {
            SeleccionFunciones.Funcion f = sel.de(m);
            lineas.append("- ").append(m.clave()).append(": máx. ").append(f == null ? 2 : f.maxCasos()).append(" casos, plan ")
                    .append(casosPorMetodo.getOrDefault(m, 0)).append('\n');
        }
        sb.append(LimitesRespuesta.recortar(lineas.toString(), LimitesRespuesta.PRESUPUESTO_CUERPO - 1200));
        if (objetivos.isEmpty()) {
            sb.append("\n`").append(c.fqn).append("`: sus funciones elegidas ya tienen el máximo de casos.\n");
        }
        SesionMcp.ProgresoPlan pp = enPlan ? sesion.progresoPlan(base) : null;
        if (!objetivos.isEmpty()) {
            String conContexto = conContexto(args, sesion, base, ruta, c, sel, pp);
            if (conContexto != null) {
                return new Resultado(conContexto, false);
            }
        }
        if (pp != null) {
            sb.append("\nPlan actualizado: ").append(pp.total()).append(pp.total() == 1 ? " caso" : " casos").append(" (faltan ")
                    .append(pp.restantes()).append(")\n");
        }
        sb.append("\nSiguiente paso: detalle_servicio {clase: \"").append(c.fqn).append("\", paquete: 1}")
                .append(casosRequeridos(sesion, base, c.fqn)).append('\n');
        return new Resultado(sb.toString(), false);
    }

    /** « y después generar_tests con los casos por método: metodo=N, …» si el servicio tiene casos del plan pendientes. */
    private static String casosRequeridos(SesionMcp sesion, Path base, String fqn) {
        String req = GuiaTanda.requeridos(sesion, base, fqn);
        return req.isEmpty() ? "" : " y después generar_tests con los casos por método: " + req;
    }

    /**
     * Respuesta fusionada: 1 línea de confirmación + JSON del paquete de trabajo del servicio (página 1) dentro del
     * presupuesto. null si no se pudo construir (se usa la respuesta clásica con «Siguiente paso»).
     */
    private static String conContexto(JsonNode args, SesionMcp sesion, Path base, Path ruta, InfoClase c, SeleccionFunciones sel,
                                      SesionMcp.ProgresoPlan pp) {
        StringBuilder prefijo = new StringBuilder("Selección registrada para `").append(c.fqn).append("` (origen ia): ")
                .append(sel.seleccionados().size()).append(" de cupo ").append(sel.cupo()).append('.');
        if (pp != null) {
            prefijo.append(" Plan actualizado: ").append(pp.total()).append(pp.total() == 1 ? " caso" : " casos")
                    .append(" (faltan ").append(pp.restantes()).append(").");
        }
        prefijo.append(" Paquete de trabajo del servicio (todo lo necesario para escribir el spec; si paginacion.siguiente es "
                + "null llama ya a generar_tests, no a detalle_servicio):");
        try {
            PaqueteTrabajo.Opciones op = new PaqueteTrabajo.Opciones();
            op.pagina = 1;
            op.modulo = Herramienta.texto(args, "modulo");
            op.presupuesto = HerramientaDetalle.presupuestoPaquete(args, sesion) - LimitesRespuesta.bytes(prefijo.toString()) - 2;
            ObjectNode d = HerramientaDetalle.paquete(sesion, base, ruta, c.fqn, op);
            return prefijo + "\n" + Json.compacto(d);
        } catch (RuntimeException e) {
            System.err.println("seleccionar_funciones: no se pudo adjuntar el paquete de trabajo del servicio: " + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage()));
            return null;
        }
    }

    /** {"valido":false, errores (máx. 10), cupo, minimo, instruccion}, acotado al presupuesto. */
    private static Resultado errores(List<ErrorValidacion> errores, SeleccionFunciones ref) {
        ObjectNode r = Json.objeto();
        r.put("valido", false);
        ArrayNode arr = r.putArray("errores");
        int n = 0;
        for (ErrorValidacion e : errores) {
            if (n++ >= MAX_ERRORES) {
                break;
            }
            ObjectNode o = arr.addObject();
            o.put("codigo", e.codigo);
            o.put("mensaje", e.mensaje);
            if (e.sugerencia != null) {
                o.put("sugerencia", e.sugerencia);
            }
        }
        if (errores.size() > MAX_ERRORES) {
            r.put("erroresTotales", errores.size());
        }
        r.put("cupo", ref.cupo());
        r.put("minimo", ref.minimo());
        r.put("instruccion", "Corrige y vuelve a llamar a seleccionar_funciones.");
        LimitesRespuesta.acotarJson(r, LimitesRespuesta.PRESUPUESTO_CUERPO, List.of("errores"),
                "Corrige los errores mostrados y vuelve a llamar: aparecerán los siguientes.");
        return new Resultado(Json.compacto(r), true);
    }

    /** Entradas {nombre, motivo}; tolera ítems string (solo nombre) y una cadena separada por comas. */
    static List<SeleccionSpec.Entrada> leerEntradas(JsonNode v) {
        List<SeleccionSpec.Entrada> r = new ArrayList<>();
        if (v == null || v.isNull()) {
            return r;
        }
        if (!v.isArray()) {
            for (String s : v.asText().split(",")) {
                if (!s.isBlank()) {
                    r.add(new SeleccionSpec.Entrada(s.trim(), null));
                }
            }
            return r;
        }
        for (JsonNode x : v) {
            if (x == null || x.isNull()) {
                continue;
            }
            if (x.isObject()) {
                JsonNode nom = x.get("nombre");
                JsonNode mot = x.get("motivo");
                r.add(new SeleccionSpec.Entrada(nom == null || nom.isNull() ? null : nom.asText(),
                        mot == null || mot.isNull() ? null : mot.asText()));
            } else {
                r.add(new SeleccionSpec.Entrada(x.asText(), null));
            }
        }
        return r;
    }
}
