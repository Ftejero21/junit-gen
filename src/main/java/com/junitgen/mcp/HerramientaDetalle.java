package com.junitgen.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.junitgen.escaneo.DetalleServicio;
import com.junitgen.escaneo.PaqueteTrabajo;
import com.junitgen.util.Json;
import com.junitgen.util.LimitesRespuesta;

import java.nio.file.Path;

/**
 * detalle_servicio: JSON paginado por métodos con dependencias, métodos internos, tipos auxiliares
 * (resumidos, ampliables con 'tipos') y plantilla de spec, siempre dentro del presupuesto de tamaño.
 */
public class HerramientaDetalle implements Herramienta {

    @Override
    public String nombre() {
        return "detalle_servicio";
    }

    @Override
    public String titulo() {
        return "Detalle de un servicio";
    }

    @Override
    public String descripcion() {
        return "Zoom de un servicio (solo lectura). Tras seleccionar_funciones solo llámala si paginacion.siguiente no es null "
                + "(repítela con esos argumentos exactos) o para ver más: `metodos` (completos), `tipos` (auxiliares), `parteCuerpo` "
                + "(cuerpos largos). Con `paquete` (1 o 2) devuelve el paquete de trabajo. Trae firma, cuerpo compactado, estado, "
                + "maxCasos (nunca lo superes), casosExistentes y casosGeneradosEnSalida (escribe solo escenarios distintos), "
                + "casosPlan, montaje, plantillaSpec y recetas. Cada caso nuevo: queHaceOriginal una vez por función y queComprueba "
                + "en cada caso. Sigue accionRequerida.";
    }

    @Override
    public ObjectNode esquema() {
        ObjectNode s = Herramienta.esquemaBase();
        Herramienta.propiedad(s, "clase", "string", "FQN o nombre simple del servicio.", true);
        Herramienta.propiedad(s, "ruta", "string", "Ruta del proyecto; por defecto, la del último escaneo.", false);
        Herramienta.propiedad(s, "modulo", "string", "Módulo, si la clase existe en varios.", false);
        Herramienta.propiedad(s, "metodos", "array", "Solo estos métodos (nombre o nombre(Tipos)), incluidos no seleccionados.", false);
        Herramienta.propiedad(s, "desde", "integer", "Primer método del tramo (1-based): el de paginacion.siguiente.", false);
        ((ObjectNode) s.get("properties").get("desde")).put("minimum", 1);
        Herramienta.propiedad(s, "tipos", "array", "Tipos auxiliares completos (FQN o nombre simple), paginados.", false);
        Herramienta.propiedad(s, "parteCuerpo", "integer", "Parte del cuerpo del método 'desde': la de paginacion.siguiente.", false);
        ((ObjectNode) s.get("properties").get("parteCuerpo")).put("minimum", 1);
        Herramienta.propiedad(s, "paquete", "integer", "Página del paquete de trabajo (1 o 2): la de paginacion.siguiente.", false);
        ((ObjectNode) s.get("properties").get("paquete")).put("minimum", 1).put("maximum", PaqueteTrabajo.MAX_PAGINAS);
        return s;
    }

    @Override
    public boolean soloLectura() {
        return true;
    }

    @Override
    public String aviso(JsonNode args, SesionMcp sesion) {
        return avisoDetalle(args, sesion);
    }

    @Override
    public Resultado ejecutar(JsonNode args, SesionMcp sesion) {
        String clase = Herramienta.obligatorio(args, "clase");
        Path ruta = Herramienta.ruta(args, "ruta");
        if (ruta == null && sesion.fachada().ultimaRuta() == null) {
            ruta = RutasSesion.proyectoPorDefecto(sesion);
        }
        Path base = ruta != null ? ruta : sesion.fachada().ultimaRuta();
        JsonNode paquete = args == null ? null : args.get("paquete");
        if (paquete != null && !paquete.isNull()) {
            return ejecutarPaquete(args, sesion, clase, ruta, base);
        }
        DetalleServicio.Opciones op = new DetalleServicio.Opciones();
        op.metodos = Herramienta.lista(args, "metodos");
        op.desde = Herramienta.entero(args, "desde", 1);
        op.tipos = Herramienta.lista(args, "tipos");
        JsonNode parte = args == null ? null : args.get("parteCuerpo");
        op.parteCuerpo = parte == null || parte.isNull() ? null : Herramienta.entero(args, "parteCuerpo", 1);
        op.modulo = Herramienta.texto(args, "modulo");
        if (op.desde == 1 && (op.parteCuerpo == null || op.parteCuerpo <= 1) && op.tipos.isEmpty() && op.metodos.isEmpty()) {
            Resultado bloqueo = puertas(sesion, clase, ruta, base, op.modulo);
            if (bloqueo != null) {
                return bloqueo;
            }
        }
        op.presupuesto = presupuesto(args, sesion);
        ObjectNode d = pagina(sesion, base, ruta, clase, op);
        return new Resultado(Json.compacto(d), false);
    }

    /**
     * Puerta de montaje y de revisión: no se empieza otro servicio con correcciones de montaje o fallos de aserción por
     * revisar de la verificación en segundo plano. Si no las hay, puerta de plan: no se empieza (ni se pre-lee) otro
     * servicio del plan mientras el servicio en curso no esté completo. null si no bloquea.
     */
    private static Resultado puertas(SesionMcp sesion, String clase, Path ruta, Path base, String modulo) {
        Path salida = sesion.salidaDe(base);
        if (salida != null && sesion.verificaciones().puertaActiva(salida)) {
            String fqn = sesion.fachada().localizarClase(clase, ruta, modulo, sesion.modulosTrabajo(base)).clase().fqn;
            String bloqueo = sesion.verificaciones().bloqueo(salida, fqn);
            if (bloqueo != null) {
                return new Resultado(bloqueo, true);
            }
        } else if (sesion.progresoPlan(base) != null) {
            String fqn = sesion.fachada().localizarClase(clase, ruta, modulo, sesion.modulosTrabajo(base)).clase().fqn;
            String bloqueo = GuiaTanda.bloqueoPlan(sesion, base, salida, fqn);
            if (bloqueo != null) {
                return new Resultado(bloqueo, true);
            }
        }
        return null;
    }

    /** detalle_servicio con {@code paquete}: página 1 o 2 del paquete de trabajo del servicio. */
    private static Resultado ejecutarPaquete(JsonNode args, SesionMcp sesion, String clase, Path ruta, Path base) {
        int pagina;
        try {
            pagina = Herramienta.entero(args, "paquete", 1);
        } catch (NumberFormatException e) {
            pagina = 0;
        }
        if (pagina < 1 || pagina > PaqueteTrabajo.MAX_PAGINAS) {
            throw new IllegalArgumentException("'paquete' debe ser 1 o 2 (usa el de paginacion.siguiente).");
        }
        JsonNode parte = args.get("parteCuerpo");
        if (!Herramienta.lista(args, "tipos").isEmpty() || (parte != null && !parte.isNull())) {
            throw new IllegalArgumentException("'paquete' no se combina con 'tipos' ni 'parteCuerpo': para ampliar un tipo o un "
                    + "método llama sin 'paquete'.");
        }
        PaqueteTrabajo.Opciones op = new PaqueteTrabajo.Opciones();
        op.pagina = pagina;
        op.metodos = Herramienta.lista(args, "metodos");
        op.modulo = Herramienta.texto(args, "modulo");
        JsonNode desde = args.get("desde");
        if (desde != null && !desde.isNull()) {
            try {
                op.desde = Herramienta.entero(args, "desde", 1);
            } catch (NumberFormatException e) {
                op.desde = 0;
            }
            if (op.desde < 1) {
                throw new IllegalArgumentException("'desde' debe ser un entero ≥ 1.");
            }
        }
        if (pagina == 1 && op.metodos.isEmpty()) {
            Resultado bloqueo = puertas(sesion, clase, ruta, base, op.modulo);
            if (bloqueo != null) {
                return bloqueo;
            }
        }
        int presupuesto = presupuestoPaquete(args, sesion);
        op.presupuesto = pagina == 2 ? Math.min(presupuesto, LimitesRespuesta.segundaPaginaPaquete()) : presupuesto;
        // En un bloque, la misma lista de tipos que vio la página 1 del servicio (el cursor de la página 2 depende de ella).
        Integer bloque = sesion.bloqueLlamada();
        boolean sinRepetirTipos = bloque != null && base != null && op.metodos.isEmpty();
        if (sinRepetirTipos) {
            String fqn;
            try {
                fqn = sesion.fachada().localizarClase(clase, ruta, op.modulo, sesion.modulosTrabajo(base)).clase().fqn;
            } catch (RuntimeException e) {
                fqn = clase;
            }
            op.tiposYaEnviados = sesion.tiposPreviosDelServicio(base, bloque, fqn);
        }
        ObjectNode d = paquete(sesion, base, ruta, clase, op);
        if (sinRepetirTipos) {
            BloquesTanda.registrarTiposEnviados(sesion, base, bloque, d);
        }
        return new Resultado(Json.compacto(d), false);
    }

    /**
     * Paquete de trabajo con el estado de la sesión: rellena {@code todas}, {@code selecciones}, {@code casosPlan},
     * {@code plantillaCompleta} (solo en el primer servicio de la sesión) y {@code hayPagina2} de {@code op} (el
     * presupuesto, la página y el cursor los fija el llamador) y llama a la fachada.
     */
    static ObjectNode paquete(SesionMcp sesion, Path base, Path ruta, String clase, PaqueteTrabajo.Opciones op) {
        return paquete(sesion, base, ruta, clase, op, null);
    }

    /**
     * Como {@link #paquete(SesionMcp, Path, Path, String, PaqueteTrabajo.Opciones)}; {@code plantillaCompleta} no nulo
     * sustituye al cálculo por defecto (esqueleto de caso solo en el primer servicio de la sesión).
     */
    static ObjectNode paquete(SesionMcp sesion, Path base, Path ruta, String clase, PaqueteTrabajo.Opciones op,
                              Boolean plantillaCompleta) {
        op.todas = sesion.todas(base);
        op.selecciones = sesion.selecciones(base);
        for (SesionMcp.EstadoMetodo m : sesion.estadoPlan(base)) {
            op.casosPlan.put(m.fqn() + "#" + m.metodo(), new int[]{m.previstos(), m.faltan()});
        }
        Path salida = sesion.salidaDe(base);
        op.plantillaCompleta = plantillaCompleta != null ? plantillaCompleta
                : salida == null || sesion.casosDeSesion(salida).isEmpty();
        op.hayPagina2 = LimitesRespuesta.segundaPaginaPaquete() >= PaqueteTrabajo.MIN_PAGINA;
        return sesion.fachada().paquete(clase, ruta, op.modulo, sesion.modulosTrabajo(base), salida, op);
    }

    /**
     * Página del detalle con el estado de la sesión: rellena {@code todas}, {@code selecciones} y {@code casosPlan} de
     * {@code op} (el presupuesto y la paginación los fija el llamador) y llama a la fachada.
     */
    static ObjectNode pagina(SesionMcp sesion, Path base, Path ruta, String clase, DetalleServicio.Opciones op) {
        op.todas = sesion.todas(base);
        op.selecciones = sesion.selecciones(base);
        for (SesionMcp.EstadoMetodo m : sesion.estadoPlan(base)) {
            op.casosPlan.put(m.fqn() + "#" + m.metodo(), new int[]{m.previstos(), m.faltan()});
        }
        return sesion.fachada().detalle(clase, ruta, op.modulo, sesion.modulosTrabajo(base), sesion.salidaDe(base), op);
    }

    static final int PRESUPUESTO_MAX_DETALLE = 6_600;
    static final int MARGEN_DETALLE = 150;

    /**
     * Presupuesto del cuerpo: con escaneo previo, lo que deja libre MAX_RESPUESTA tras el aviso, las notas
     * pendientes y un margen, entre PRESUPUESTO_CUERPO y 6600; si no (o si el aviso falla), PRESUPUESTO_CUERPO.
     */
    static int presupuesto(JsonNode args, SesionMcp sesion) {
        if (sesion.fachada().ultimaRuta() == null) {
            return LimitesRespuesta.PRESUPUESTO_CUERPO;
        }
        try {
            int libre = LimitesRespuesta.MAX_RESPUESTA - 2 - LimitesRespuesta.bytes(avisoDetalle(args, sesion))
                    - sesion.bytesNotasPendientes() - GuiaTanda.reserva(sesion) - MARGEN_DETALLE;
            return Math.max(LimitesRespuesta.PRESUPUESTO_CUERPO, Math.min(PRESUPUESTO_MAX_DETALLE, libre));
        } catch (RuntimeException e) {
            System.err.println("detalle_servicio: no se pudo calcular el aviso para el presupuesto: " + e.getMessage());
            return LimitesRespuesta.PRESUPUESTO_CUERPO;
        }
    }

    /**
     * Presupuesto del paquete de trabajo: como {@link #presupuesto} pero con {@link LimitesRespuesta#maxRespuesta()} y
     * acotado por {@link LimitesRespuesta#presupuestoPaquete()}; nunca menos que PRESUPUESTO_CUERPO.
     */
    static int presupuestoPaquete(JsonNode args, SesionMcp sesion) {
        if (sesion.fachada().ultimaRuta() == null) {
            return LimitesRespuesta.PRESUPUESTO_CUERPO;
        }
        try {
            int libre = LimitesRespuesta.maxRespuesta() - 2 - LimitesRespuesta.bytes(avisoDetalle(args, sesion))
                    - sesion.bytesNotasPendientes() - GuiaTanda.reserva(sesion) - MARGEN_DETALLE;
            return Math.max(LimitesRespuesta.PRESUPUESTO_CUERPO, Math.min(libre, LimitesRespuesta.presupuestoPaquete()));
        } catch (RuntimeException e) {
            System.err.println("detalle_servicio: no se pudo calcular el aviso para el presupuesto: " + e.getMessage());
            return LimitesRespuesta.PRESUPUESTO_CUERPO;
        }
    }

    /** El aviso depende del proyecto resuelto (argumento o último escaneo). */
    static String avisoDetalle(JsonNode args, SesionMcp sesion) {
        return RutasSesion.aviso(args, sesion, "ruta", null, "(sin escaneo previo)");
    }
}
