package com.junitgen.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.junitgen.Fachada;
import com.junitgen.proyecto.LocalizadorModulos;
import com.junitgen.seguridad.RutaSegura;
import com.junitgen.seguridad.SeguridadException;
import com.junitgen.spec.LectorSpec;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Resolución del proyecto y de la carpeta de salida en la sesión MCP cuando no se pasan rutas:
 * carpeta abierta en el IDE (MCP roots), directorio de trabajo del servidor y pregunta
 * «¿Dónde exportamos los tests?» en una sola pantalla con una única propiedad (Escritorio, ruta por
 * defecto guardada u «Otra ruta…»); solo si elige «Otra ruta…», una segunda pantalla con un único campo
 * de texto. Toda ruta que venga del cliente o del archivo de preferencias se valida con RutaSegura; solo
 * se recuerda una salida ya validada.
 */
public final class RutasSesion {

    private static final int MAX_INTENTOS = 3;
    /** Comando para cambiar la ruta de salida (cambiar_ruta_salida). */
    static final String COMANDO_CAMBIAR = "/generartest ruta";
    /** Prefijo de la opción del modo cambio. */
    static final String MANTENER = "Mantener la ruta por defecto: ";
    /** Variables de entorno con carpetas del sistema donde nunca se deduce un proyecto. */
    static final List<String> CARPETAS_SISTEMA = List.of("SystemRoot", "ProgramFiles", "ProgramFiles(x86)", "ProgramW6432",
            "ProgramData", "APPDATA", "LOCALAPPDATA");

    private RutasSesion() {
    }

    // ------------------------------------------------------------- proyecto

    /**
     * Proyecto (sin validar). Con {@code raicesPrimero} (escanear_proyecto): argumento → raíces del
     * IDE → último escaneo → directorio de trabajo. En el resto: argumento → proyecto.ruta del spec →
     * último escaneo → raíces → directorio de trabajo.
     */
    public static Path proyecto(JsonNode args, SesionMcp sesion, String campo, boolean raicesPrimero) {
        Path arg = campo == null ? null : Herramienta.ruta(args, campo);
        if (arg != null) {
            return arg;
        }
        Fachada f = sesion.fachada();
        if (raicesPrimero) {
            if (sesion.soportaRaices()) {
                List<Raices.Raiz> raices = Raices.listar(sesion);
                if (!raices.isEmpty()) {
                    return Raices.proyectoUnico(raices);
                }
            }
            if (f.ultimaRuta() != null) {
                return f.ultimaRuta();
            }
            Path cwd = proyectoDeCwd(sesion);
            if (cwd != null) {
                return cwd;
            }
            throw new IllegalArgumentException(Raices.SIN_RAICES);
        }
        Path delSpec = rutaDelSpec(args, sesion, true);
        if (delSpec != null) {
            return delSpec;
        }
        if (f.ultimaRuta() != null) {
            return f.ultimaRuta();
        }
        return proyectoPorDefecto(sesion);
    }

    /** Proyecto sin argumentos ni escaneo previo: raíz única del IDE → directorio de trabajo → error. */
    public static Path proyectoPorDefecto(SesionMcp sesion) {
        List<Raices.Raiz> raices = Raices.listar(sesion);
        if (!raices.isEmpty()) {
            return Raices.proyectoUnico(raices);
        }
        Path cwd = proyectoDeCwd(sesion);
        if (cwd != null) {
            return cwd;
        }
        throw new IllegalArgumentException(Raices.SIN_RAICES);
    }

    private static Path proyectoDeCwd(SesionMcp sesion) {
        Path cwd = sesion.directorioTrabajoValido();
        if (cwd != null) {
            sesion.anotar("Proyecto deducido del directorio de trabajo del servidor: " + cwd);
        }
        return cwd;
    }

    /**
     * Directorio de trabajo real si sirve como proyecto, o null. Se excluyen: raíz de unidad, la carpeta
     * del usuario o un ancestro suyo, la carpeta de instalación (igual, dentro o contenedora) y las
     * carpetas del sistema; además debe contener al menos un módulo Maven/Gradle.
     */
    static Path cwdValido(EntornoSesion entorno) {
        Path real = normalizarONull(entorno.directorioTrabajo());
        if (real == null || !Files.isDirectory(real) || real.getNameCount() == 0 || real.getParent() == null) {
            return null;
        }
        Path home = normalizarONull(entorno.home());
        if (home != null && RutaSegura.dentroOIgual(home, real)) {
            return null;
        }
        Path instalacion = normalizarONull(entorno.instalacion());
        if (instalacion != null && (RutaSegura.dentroOIgual(real, instalacion) || RutaSegura.dentroOIgual(instalacion, real))) {
            return null;
        }
        for (String variable : CARPETAS_SISTEMA) {
            String v = entorno.env().apply(variable);
            if (v == null || v.isBlank()) {
                continue;
            }
            Path sistema;
            try {
                sistema = normalizarONull(Path.of(v.trim()));
            } catch (InvalidPathException e) {
                continue;
            }
            if (sistema != null && sistema.getNameCount() > 0 && RutaSegura.dentroOIgual(real, sistema)) {
                return null;
            }
        }
        if (LocalizadorModulos.localizar(real).modulos.isEmpty()) {
            System.err.println("El directorio de trabajo " + real + " no contiene módulos Maven/Gradle: no se usa como proyecto.");
            return null;
        }
        return real;
    }

    private static Path normalizarONull(Path p) {
        if (p == null) {
            return null;
        }
        try {
            return RutaSegura.normalizar(p);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** proyecto.ruta del spec de los argumentos (objeto, cadena JSON o, si se permite, archivo rutaSpec). */
    static Path rutaDelSpec(JsonNode args, SesionMcp sesion, boolean leerArchivo) {
        JsonNode spec = args == null ? null : args.get("spec");
        String r = null;
        if (spec != null && spec.isObject()) {
            r = spec.path("proyecto").path("ruta").asText(null);
        } else if ((spec != null && spec.isTextual()) || (leerArchivo && Herramienta.texto(args, "rutaSpec") != null)) {
            try {
                Path rutaSpec = spec != null && spec.isTextual() ? null : Herramienta.ruta(args, "rutaSpec");
                LectorSpec.Lectura l = sesion.fachada().leerSpec(spec, rutaSpec);
                if (l.ok() && l.spec().proyecto != null) {
                    r = l.spec().proyecto.ruta;
                }
            } catch (RuntimeException e) {
                r = null;
            }
        }
        if (r == null || r.isBlank()) {
            return null;
        }
        try {
            return RutaSegura.ruta(r);
        } catch (SeguridadException e) {
            return null;
        }
    }

    // ------------------------------------------------------------- carpeta de salida

    /** Opciones predefinidas de la pregunta de exportación (etiqueta → ruta, ya validadas). */
    record OpcionesSalida(LinkedHashMap<String, String> etiquetas, String preseleccionada, Path escritorio, Path porDefecto) {
    }

    /** {@code <Escritorio>/<NombreProyecto>Test} si hay Escritorio y la salida es válida, o null. */
    static Path salidaEscritorio(Path proyectoReal, SesionMcp sesion) {
        Path escritorio = sesion.escritorio();
        Path nombre = proyectoReal.getFileName();
        if (escritorio == null || nombre == null || nombre.toString().isBlank()) {
            return null;
        }
        try {
            return RutaSegura.validarSalida(proyectoReal, escritorio.resolve(nombre + "Test"));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Ruta por defecto guardada para el proyecto, revalidada; null si no hay o ya no es válida. */
    static Path porDefectoValida(Path proyectoReal, SesionMcp sesion) {
        Path guardada = sesion.rutasPorDefecto().de(proyectoReal);
        if (guardada == null) {
            return null;
        }
        if (!guardada.isAbsolute()) {
            System.err.println("Ruta por defecto guardada ignorada (no es absoluta): " + guardada);
            sesion.anotar("Ruta por defecto guardada ignorada (" + guardada + "): no es una ruta absoluta.");
            return null;
        }
        try {
            return RutaSegura.validarSalida(proyectoReal, guardada);
        } catch (RuntimeException e) {
            System.err.println("Ruta por defecto guardada ignorada (" + guardada + "): " + e.getMessage());
            sesion.anotar("Ruta por defecto guardada ignorada (" + guardada + "): " + e.getMessage());
            return null;
        }
    }

    /** Escritorio primero; después la ruta por defecto guardada (preseleccionada si existe). */
    static OpcionesSalida opcionesSalida(Path proyectoReal, SesionMcp sesion) {
        LinkedHashMap<String, String> m = new LinkedHashMap<>();
        String pre = null;
        Path escritorio = salidaEscritorio(proyectoReal, sesion);
        if (escritorio != null) {
            String et = "Crear carpeta en el Escritorio: " + escritorio;
            m.put(et, escritorio.toString());
            pre = et;
        }
        Path defecto = porDefectoValida(proyectoReal, sesion);
        if (defecto != null && !(escritorio != null && RutaSegura.iguales(defecto, escritorio))) {
            String et = "Ruta por defecto: " + defecto;
            m.put(et, defecto.toString());
            pre = et;
        }
        return new OpcionesSalida(m, pre, escritorio, defecto);
    }

    public static Path salida(Path proyectoReal, Path salidaArg, SesionMcp sesion, boolean obligatoria) {
        return salida(proyectoReal, salidaArg, sesion, obligatoria, false);
    }

    /**
     * Carpeta de salida validada: argumento → la recordada en la sesión → la ruta por defecto guardada
     * y válida (sin preguntar, con una nota visible) → pregunta por elicitation (una vez por proyecto, o
     * de nuevo si hubo un rechazo): pantalla con [Escritorio] [Ruta por defecto] [Otra ruta…] y, solo con
     * «Otra ruta…», pantalla de texto. Elija lo que elija, se pregunta después [Establecer como ruta por
     * defecto] [Solo esta vez]. Sin respuesta: con rechazo lanza SeguridadException; si es obligatoria
     * lanza IllegalArgumentException con la instrucción para preguntar en el chat; si no, devuelve null.
     * {@code guardarPorDefecto}: el agente pasa en el argumento la ruta elegida en el chat y el usuario
     * pidió establecerla como ruta por defecto.
     */
    public static Path salida(Path proyectoReal, Path salidaArg, SesionMcp sesion, boolean obligatoria, boolean guardarPorDefecto) {
        String[] rechazo = {null};
        if (salidaArg != null) {
            try {
                Path s = RutaSegura.validarSalida(proyectoReal, salidaArg);
                sesion.recordarSalida(proyectoReal, s);
                if (guardarPorDefecto) {
                    guardarPorDefecto(sesion, proyectoReal, s);
                }
                avisarSiNoExiste(sesion, s);
                return s;
            } catch (SeguridadException e) {
                rechazo[0] = e.getMessage();
            }
        } else {
            Path guardada = sesion.salidaDe(proyectoReal);
            if (guardada != null) {
                return guardada;
            }
            Path defecto = porDefectoValida(proyectoReal, sesion);
            if (defecto != null) {
                sesion.recordarSalida(proyectoReal, defecto);
                sesion.anotar("Ruta por defecto de " + proyectoReal.getFileName() + ": " + defecto + " (para cambiarla: "
                        + COMANDO_CAMBIAR + ")");
                avisarSiNoExiste(sesion, defecto);
                return defecto;
            }
        }
        boolean caducada = false;
        if (sesion.soportaElicitacion() && (rechazo[0] != null || !sesion.salidaPreguntada(proyectoReal))) {
            OpcionesSalida op = opcionesSalida(proyectoReal, sesion);
            String mensaje = Elicitacion.PREGUNTA_SALIDA + " Proyecto (solo lectura): " + proyectoReal + ". Elige una opción.";
            Eleccion e = preguntarValidando(sesion, proyectoReal, mensaje, op.etiquetas(), op.preseleccionada(), rechazo);
            caducada = e.caducada();
            if (e.ruta() != null) {
                sesion.recordarSalida(proyectoReal, e.ruta());
                preguntarGuardar(sesion, proyectoReal, e.ruta(), op.porDefecto(),
                        List.of(Elicitacion.GUARDAR_DEFECTO, Elicitacion.SOLO_ESTA_VEZ));
                avisarSiNoExiste(sesion, e.ruta());
                return e.ruta();
            }
        }
        String instruccion = instruccionPreguntar(proyectoReal, sesion) + (caducada ? SUFIJO_CADUCADA : "");
        if (rechazo[0] != null) {
            throw new SeguridadException(rechazo[0] + " " + instruccion);
        }
        if (obligatoria) {
            throw new IllegalArgumentException(instruccion);
        }
        return null;
    }

    /**
     * Respuesta del bucle de pregunta: ruta validada y su origen (o null), si el cliente respondió y si se
     * agotaron los intentos por rechazos.
     */
    private record Eleccion(Path ruta, Elicitacion.Origen origen, boolean respondida, boolean agotada, boolean caducada) {
    }

    /** Sufijo de la instrucción del chat cuando la pregunta de la carpeta caducó sin respuesta. */
    static final String SUFIJO_CADUCADA = " El usuario no respondió a la pregunta (caducó): NO elijas la carpeta por él; espera su "
            + "respuesta en el chat.";

    /**
     * Pregunta la carpeta por elicitation y la valida. Primera pantalla: una única propiedad enum con las
     * etiquetas y «Otra ruta…»; solo si se elige «Otra ruta…» (o no hay etiquetas), segunda pantalla con
     * una única propiedad de texto. Texto libre devuelto en el enum se trata como ruta escrita. Cada rechazo
     * de validación (ruta relativa o no válida) cuenta hacia {@link #MAX_INTENTOS} y vuelve a pedir el texto
     * con el motivo delante, que queda en {@code rechazo[0]}. Elegir «Otra ruta…» no consume intento.
     * Decline/cancel en cualquiera de las dos pantallas: respondida sin ruta. Las etiquetas nunca se
     * interpretan como ruta: se usa el mapa etiqueta→ruta.
     */
    private static Eleccion preguntarValidando(SesionMcp sesion, Path proyectoReal, String mensaje, LinkedHashMap<String, String> etiquetas,
                                               String preseleccionada, String[] rechazo) {
        String mensajeTexto = Elicitacion.PREGUNTA_SALIDA + " Escribe la ruta completa de una carpeta fuera del proyecto (solo lectura): "
                + proyectoReal;
        boolean pedirTexto = etiquetas.isEmpty();
        int rechazos = 0;
        // Cota de seguridad: cada vuelta o consume un intento o pasa de la primera pantalla a la de texto.
        for (int vuelta = 0; vuelta <= 2 * MAX_INTENTOS && rechazos < MAX_INTENTOS; vuelta++) {
            String prefijo = rechazo[0] == null ? "" : rechazo[0] + "\n\n";
            Elicitacion.RespuestaSalida r = pedirTexto
                    ? Elicitacion.preguntarRuta(sesion, prefijo + mensajeTexto, Elicitacion.TIMEOUT)
                    : Elicitacion.preguntarSalida(sesion, prefijo + mensaje, etiquetas, preseleccionada, Elicitacion.TIMEOUT);
            sesion.marcarSalidaPreguntada(proyectoReal);
            if (r == null) {
                return new Eleccion(null, null, false, false, false);
            }
            if (Elicitacion.SIN_RESPUESTA.equals(r.accion())) {
                return new Eleccion(null, null, false, false, true);
            }
            if (!"accept".equals(r.accion())) {
                return new Eleccion(null, null, true, false, false);
            }
            if (r.origen() == Elicitacion.Origen.OTRA) {
                pedirTexto = true;
                continue;
            }
            if (r.ruta() == null) {
                return new Eleccion(null, null, true, false, false);
            }
            if (r.origen() == Elicitacion.Origen.TEXTO_LIBRE) {
                pedirTexto = true;
            }
            try {
                Path elegida = RutaSegura.ruta(r.ruta());
                if (!elegida.isAbsolute()) {
                    rechazo[0] = "Indica una ruta absoluta: «" + r.ruta() + "» es relativa.";
                    rechazos++;
                    continue;
                }
                Path s = RutaSegura.validarSalida(proyectoReal, elegida);
                rechazo[0] = null;
                return new Eleccion(s, r.origen(), true, false, false);
            } catch (SeguridadException e) {
                rechazo[0] = e.getMessage();
                rechazos++;
            }
        }
        return new Eleccion(null, null, true, true, false);
    }

    private static void avisarSiNoExiste(SesionMcp sesion, Path salidaReal) {
        if (!Files.exists(salidaReal, LinkOption.NOFOLLOW_LINKS)) {
            sesion.anotar("La carpeta de salida " + salidaReal + " aún no existe: se creará al generar.");
        }
    }

    /**
     * Tras elegir una carpeta: [Establecer como ruta por defecto] [Solo esta vez] en el orden indicado.
     * Decline, cancel o sin respuesta = solo esta vez. Devuelve true si quedó establecida.
     */
    private static boolean preguntarGuardar(SesionMcp sesion, Path proyectoReal, Path salidaReal, Path porDefecto, List<String> orden) {
        if (!sesion.rutasPorDefecto().disponible()) {
            return false;
        }
        if (porDefecto != null && RutaSegura.iguales(porDefecto, salidaReal)) {
            return false;
        }
        String r = Elicitacion.preguntar(sesion, "¿Establecer " + salidaReal + " como ruta por defecto para exportar los tests de "
                        + proyectoReal.getFileName() + "? Se usará sin preguntar en las próximas sesiones (para cambiarla: "
                        + COMANDO_CAMBIAR + ").", orden, Elicitacion.TIMEOUT);
        return Elicitacion.GUARDAR_DEFECTO.equals(r) && guardarPorDefecto(sesion, proyectoReal, salidaReal);
    }

    /** Guarda la ruta por defecto; un fallo no aborta la herramienta: queda como nota visible. Devuelve si se guardó. */
    static boolean guardarPorDefecto(SesionMcp sesion, Path proyectoReal, Path salidaReal) {
        try {
            sesion.rutasPorDefecto().guardar(proyectoReal, salidaReal);
            sesion.anotar("Ruta por defecto guardada para " + proyectoReal.getFileName() + ": " + salidaReal);
            return true;
        } catch (RuntimeException e) {
            sesion.anotar("No se pudo guardar la ruta por defecto: " + e.getMessage());
            return false;
        }
    }

    /** Instrucción para que el agente pregunte la salida en el chat con las mismas opciones disponibles. */
    public static String instruccionPreguntar(Path proyectoReal, SesionMcp sesion) {
        OpcionesSalida op = opcionesSalida(proyectoReal, sesion);
        StringBuilder sb = new StringBuilder("Carpeta de salida pendiente: pregunta al usuario en el chat «")
                .append(Elicitacion.PREGUNTA_SALIDA).append("»");
        if (op.etiquetas().isEmpty()) {
            sb.append(" pidiéndole directamente la ruta completa de una carpeta fuera del proyecto (no hay opciones predefinidas).");
        } else {
            sb.append(" con estas opciones:");
            op.etiquetas().keySet().forEach(e -> sb.append(" [").append(e).append(']'));
            sb.append(" [").append(Elicitacion.OTRA_RUTA).append("]. Solo si elige [").append(Elicitacion.OTRA_RUTA)
                    .append("], pídele la ruta completa de una carpeta fuera del proyecto.");
            if (op.escritorio() != null && !Files.exists(op.escritorio(), LinkOption.NOFOLLOW_LINKS)) {
                sb.append(" La carpeta del Escritorio no existe aún: se creará al generar.");
            }
        }
        sb.append(" Pasa la elegida en `carpetaSalida`; junit-gen la recordará el resto de la sesión.");
        if (sesion.rutasPorDefecto().disponible()) {
            sb.append(" Elija lo que elija, pregúntale después [").append(Elicitacion.GUARDAR_DEFECTO).append("] [")
                    .append(Elicitacion.SOLO_ESTA_VEZ).append("] y, si establece, pasa también `guardarRutaPorDefecto=true`.");
        }
        return sb.toString();
    }

    // ------------------------------------------------------------- cambio de la ruta (cambiar_ruta_salida)

    /**
     * Modo cambio de la carpeta de salida. Con {@code salidaArg}: la valida, la recuerda y, si se pidió,
     * la establece como ruta por defecto. Sin ella, por elicitation: una pantalla con [Mantener la ruta por
     * defecto: X] (si no hay, la del Escritorio) [Otra ruta…] y, solo con «Otra ruta…» (o sin opción que
     * mantener), una pantalla de texto; si escribe otra, [Solo esta vez] [Establecer como ruta por defecto]. Decline/cancel = sin cambios; sin soporte o sin respuesta devuelve la instrucción
     * equivalente para el chat (si caducó, con el aviso de no elegir por el usuario). Devuelve un texto breve con el resultado.
     */
    public static String cambiarSalida(Path proyectoReal, Path salidaArg, boolean guardar, SesionMcp sesion) {
        Path defecto = porDefectoValida(proyectoReal, sesion);
        if (salidaArg != null) {
            Path s = RutaSegura.validarSalida(proyectoReal, salidaArg);
            sesion.recordarSalida(proyectoReal, s);
            sesion.marcarSalidaPreguntada(proyectoReal);
            avisarSiNoExiste(sesion, s);
            if (guardar) {
                return guardarPorDefecto(sesion, proyectoReal, s)
                        ? "Ruta por defecto establecida: " + s + " (se usará sin preguntar en las próximas sesiones)."
                        : "Carpeta de salida cambiada solo esta vez (esta sesión): " + s + "; no se pudo establecer como ruta por defecto.";
            }
            return "Carpeta de salida cambiada solo esta vez (esta sesión): " + s
                    + (defecto != null && !RutaSegura.iguales(defecto, s) ? "; la ruta por defecto sigue siendo " + defecto : "") + ".";
        }
        Path mantener = defecto != null ? defecto : salidaEscritorio(proyectoReal, sesion);
        if (!sesion.soportaElicitacion()) {
            return instruccionCambio(proyectoReal, mantener);
        }
        LinkedHashMap<String, String> etiquetas = new LinkedHashMap<>();
        String etiquetaMantener = null;
        if (mantener != null) {
            etiquetaMantener = MANTENER + mantener;
            etiquetas.put(etiquetaMantener, mantener.toString());
        }
        String mensaje = Elicitacion.PREGUNTA_SALIDA + " Proyecto (solo lectura): " + proyectoReal + ". Elige una opción.";
        String[] rechazo = {null};
        Eleccion e = preguntarValidando(sesion, proyectoReal, mensaje, etiquetas, etiquetaMantener, rechazo);
        if (!e.respondida()) {
            return instruccionCambio(proyectoReal, mantener) + (e.caducada() ? SUFIJO_CADUCADA : "");
        }
        if (e.ruta() == null) {
            if (e.agotada()) {
                throw new SeguridadException(rechazo[0] + " " + instruccionCambio(proyectoReal, mantener));
            }
            return "Sin cambios: " + (defecto != null ? "la ruta por defecto sigue siendo " + defecto + "."
                    : "no hay ruta por defecto guardada.");
        }
        Path s = e.ruta();
        sesion.recordarSalida(proyectoReal, s);
        avisarSiNoExiste(sesion, s);
        if (mantener != null && RutaSegura.iguales(mantener, s)) {
            return defecto != null ? "Se mantiene la ruta por defecto: " + s + "."
                    : "Se mantiene la carpeta del Escritorio: " + s + " (no hay ruta por defecto guardada).";
        }
        boolean establecida = preguntarGuardar(sesion, proyectoReal, s, defecto,
                List.of(Elicitacion.SOLO_ESTA_VEZ, Elicitacion.GUARDAR_DEFECTO));
        if (establecida) {
            return "Ruta por defecto establecida: " + s + " (se usará sin preguntar en las próximas sesiones).";
        }
        return "Carpeta de salida cambiada solo esta vez (esta sesión): " + s
                + (defecto != null ? "; la ruta por defecto sigue siendo " + defecto : "") + ".";
    }

    /** Instrucción equivalente al modo cambio para preguntar en el chat. */
    static String instruccionCambio(Path proyectoReal, Path mantener) {
        StringBuilder sb = new StringBuilder("Cambio de ruta pendiente: pregunta al usuario en el chat «").append(Elicitacion.PREGUNTA_SALIDA)
                .append("» (proyecto de solo lectura: ").append(proyectoReal).append(")");
        if (mantener != null) {
            sb.append(" con las opciones [").append(MANTENER).append(mantener).append("] [").append(Elicitacion.OTRA_RUTA)
                    .append("]. Si mantiene, no hace falta nada más. Solo si elige [").append(Elicitacion.OTRA_RUTA)
                    .append("], pídele la ruta completa de una carpeta fuera del proyecto.");
        } else {
            sb.append(" pidiéndole directamente la ruta completa de una carpeta fuera del proyecto.");
        }
        sb.append(" Si escribe otra, pregúntale [").append(Elicitacion.SOLO_ESTA_VEZ).append("] [").append(Elicitacion.GUARDAR_DEFECTO)
                .append("] y llama a cambiar_ruta_salida con `carpetaSalida` = esa ruta y, si establece, `guardarRutaPorDefecto=true`.");
        return sb.toString();
    }

    // ------------------------------------------------------------- aviso (no interactivo)

    /**
     * Aviso de la primera línea sin preguntar nada al cliente: proyecto = argumento → proyecto.ruta del
     * spec → último escaneo → marcador; salida (si se muestra) = argumento → la recordada en la sesión.
     */
    public static String aviso(JsonNode args, SesionMcp sesion, String campoProyecto, String campoSalida, String marcador) {
        Path proyecto = proyectoConocido(args, sesion, campoProyecto);
        Path salida = null;
        if (campoSalida != null) {
            salida = Herramienta.ruta(args, campoSalida);
            if (salida == null) {
                salida = sesion.salidaDe(proyecto);
            }
        }
        return Fachada.aviso(proyecto == null ? Path.of(marcador) : proyecto, salida);
    }

    /** Proyecto conocido sin interacción: argumento → proyecto.ruta del spec (objeto) → último escaneo, o null. */
    public static Path proyectoConocido(JsonNode args, SesionMcp sesion, String campo) {
        Path arg = campo == null ? null : Herramienta.ruta(args, campo);
        if (arg != null) {
            return arg;
        }
        Path delSpec = rutaDelSpec(args, sesion, false);
        return delSpec != null ? delSpec : sesion.fachada().ultimaRuta();
    }
}
