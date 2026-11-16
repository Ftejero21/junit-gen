package com.junitgen.generacion;

import com.fasterxml.jackson.databind.JsonNode;
import com.junitgen.spec.AsercionSpec;
import com.junitgen.spec.CasoSpec;
import com.junitgen.spec.ConstruccionMockSpec;
import com.junitgen.spec.DatoSpec;
import com.junitgen.spec.EsperadoSpec;
import com.junitgen.spec.EstaticoSpec;
import com.junitgen.spec.StubSpec;
import com.junitgen.spec.VerificacionSpec;
import com.junitgen.util.Json;
import com.junitgen.util.LimitesRespuesta;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Compara campo a campo el spec previo de un caso reemplazado con el nuevo y lo describe en markdown para
 * informe.md con frases («Se añadió…», «Se quitó…», «Se cambió… de X a Y»). Determinista y sin diff de texto Java. Los valores vienen de la IA: todo lo
 * que se vuelca pasa por {@link #san(String)}.
 */
public final class ComparadorCasos {

    /** Longitud máxima de cada valor volcado al informe. */
    static final int MAX_VALOR = 120;
    /** Máximo de líneas de cambios por caso. */
    static final int MAX_LINEAS = 15;

    /** Prefijo de la línea que indica que el resultado esperado no cambia. */
    private static final String PREFIJO_IGUAL = "El resultado esperado no cambia: ";
    private static final String SEP = ": ";

    /** Campos del caso que se comparan explícitamente. */
    private static final Set<String> CONOCIDOS = Set.of("metodo", "parametros", "escenario", "descripcion", "queHaceOriginal",
            "queComprueba", "datos", "preparacion", "stubs", "estaticos", "construcciones", "argumentos", "esperado", "aserciones",
            "verificaciones");
    /** Campos que gestiona la herramienta y no forman parte del contenido del caso. */
    private static final Set<String> EXCLUIDOS = Set.of("id", "huella", "huellaEntrada", "estado", "intentosMontaje", "veredictoIa",
            "motivoIa", "autorizacionCambio");

    private ComparadorCasos() {
    }

    /** Bloque markdown de un caso reemplazado: cabecera, origen, motivo, estado previo y cambios. */
    static String bloque(String id, String metodoTest, CasoSpec previo, CasoSpec nuevo) {
        StringBuilder sb = new StringBuilder("#### ");
        if (metodoTest != null && !metodoTest.isBlank()) {
            sb.append(cod(metodoTest)).append(" (identificador ").append(cod(id)).append(')');
        } else {
            sb.append(cod(id));
        }
        sb.append("\n\n");
        if (previo == null) {
            sb.append("- No se puede comparar con la versión anterior porque junit-gen no la tenía guardada.\n");
            return sb.toString();
        }
        origen(sb, previo);
        if (previo.estado != null) {
            boolean malPreparado = "generado".equals(previo.estado) && previo.intentosMontaje != null && previo.intentosMontaje > 0;
            sb.append("- Cómo estaba antes: ").append(malPreparado ? TextosInforme.MAL_PREPARADO : TextosInforme.estado(previo.estado))
                    .append('\n');
        }
        List<String> cambios = cambios(previo, nuevo);
        if (cambios.isEmpty() || (cambios.size() == 1 && cambios.get(0).startsWith(PREFIJO_IGUAL))) {
            sb.append("- Qué ha cambiado: nada en la definición del test (solo se ha vuelto a escribir su código).\n");
            return sb.toString();
        }
        sb.append("- Qué ha cambiado:\n");
        int mostrar = Math.min(cambios.size(), MAX_LINEAS);
        for (int i = 0; i < mostrar; i++) {
            sb.append("  - ").append(cambios.get(i)).append('\n');
        }
        if (cambios.size() > MAX_LINEAS) {
            sb.append("  - … y ").append(cambios.size() - MAX_LINEAS).append(" cambios más\n");
        }
        return sb.toString();
    }

    /** Líneas de cambios (frases, sin prefijo de lista) entre dos versiones del caso. */
    public static List<String> cambios(CasoSpec antes, CasoSpec despues) {
        CasoSpec a = antes != null ? antes : new CasoSpec();
        CasoSpec d = despues != null ? despues : new CasoSpec();
        List<String> out = new ArrayList<>();
        cambiosEsperado(out, a, d);
        diff(out, "la respuesta simulada", filasStubs(a.stubs), filasStubs(d.stubs));
        diff(out, "la respuesta simulada (método estático)", filasEstaticos(a.estaticos), filasEstaticos(d.estaticos));
        diff(out, "el objeto simulado", filasConstrucciones(a.construcciones), filasConstrucciones(d.construcciones));
        diff(out, "el dato de prueba", filasDatos(a.datos), filasDatos(d.datos));
        diff(out, "el paso de preparación", filasPreparacion(a.preparacion), filasPreparacion(d.preparacion));
        cambiosArgumentos(out, a, d);
        diff(out, "la comprobación", filasAserciones(a.aserciones), filasAserciones(d.aserciones));
        diff(out, "la comprobación de llamadas", filasVerificaciones(a.verificaciones), filasVerificaciones(d.verificaciones));
        cambiosMetodo(out, a, d);
        cambiosTextos(out, a, d);
        cambiosOtros(out, a, d);
        return out;
    }

    // ---- origen ----

    private static void origen(StringBuilder sb, CasoSpec previo) {
        int intentos = previo.intentosMontaje != null ? previo.intentosMontaje : 0;
        if (CasoSpec.AUTORIZACION_ERROR_IA.equals(previo.autorizacionCambio)) {
            sb.append("- Por qué se rehízo: la IA revisó el fallo y concluyó que el test estaba mal planteado");
            if (intentos > 0) {
                sb.append(" (intento ").append(intentos).append(')');
            }
            sb.append(".\n");
            if (previo.motivoIa != null && !previo.motivoIa.isBlank()) {
                sb.append("- Explicación de la IA: ").append(previo.motivoIa.replaceAll("[\\r\\n\\t]+", " ").strip()).append('\n');
            }
        } else if (CasoSpec.AUTORIZACION_AJUSTAR.equals(previo.autorizacionCambio)) {
            sb.append("- Por qué se rehízo: el usuario decidió cambiar el resultado que espera el test.\n");
        } else if (intentos > 0 && ("generado".equals(previo.estado) || "montaje_fallido".equals(previo.estado))) {
            sb.append("- Por qué se rehízo: el test estaba mal preparado o no compilaba y se ha corregido (intento ").append(intentos)
                    .append(").\n");
        } else {
            sb.append("- Por qué se rehízo: se pidió volver a crearlo.\n");
        }
    }

    // ---- categorías ----

    private static void cambiosEsperado(List<String> out, CasoSpec a, CasoSpec d) {
        String ra = esperado(a.esperado);
        String rd = esperado(d.esperado);
        if (ra.equals(rd)) {
            out.add(PREFIJO_IGUAL + ra);
        } else {
            out.add("Se cambió el resultado esperado: de " + ra + " a " + rd);
        }
    }

    private static String esperado(EsperadoSpec e) {
        if (e == null) {
            return "(sin esperado)";
        }
        if (e.tipo == null) {
            return "(sin tipo)" + (e.valor != null ? " " + cod(e.valor) : "");
        }
        return switch (e.tipo) {
            case "valor" -> "valor " + cod(e.valor);
            case "excepcion" -> "excepción " + cod(e.excepcion)
                    + (e.mensaje != null ? " con mensaje " + cod(e.mensaje) : "")
                    + (e.mensajeContiene != null ? " cuyo mensaje contiene " + cod(e.mensajeContiene) : "");
            case "nulo" -> "nulo";
            case "noNulo" -> "no nulo";
            case "verdadero" -> "verdadero";
            case "falso" -> "falso";
            case "sinRetorno" -> "sin retorno";
            case "aserciones" -> "solo aserciones";
            default -> cod(e.tipo) + (e.valor != null ? " " + cod(e.valor) : "");
        };
    }

    private static List<String[]> filasStubs(List<StubSpec> stubs) {
        List<String[]> filas = new ArrayList<>();
        for (StubSpec s : lista(stubs)) {
            if (s != null) {
                filas.add(new String[]{claveStub(s), efectoStub(s)});
            }
        }
        return filas;
    }

    private static String claveStub(StubSpec s) {
        return (s.mock != null ? s.mock + "." : "") + Objects.toString(s.llamada, "");
    }

    private static String efectoStub(StubSpec s) {
        List<String> partes = new ArrayList<>();
        if (s.devuelve != null) {
            partes.add(cod(s.devuelve));
        }
        if (s.devuelveSecuencia != null && !s.devuelveSecuencia.isEmpty()) {
            partes.add("secuencia " + cod(String.join(", ", s.devuelveSecuencia)));
        }
        if (s.lanza != null) {
            partes.add("lanza " + cod(s.lanza));
        }
        if (s.responde != null) {
            partes.add("responde " + cod(s.responde));
        }
        String r = partes.isEmpty() ? "sin efecto" : String.join(", ", partes);
        if (s.forma != null && !"auto".equals(s.forma)) {
            r += " (forma " + san(s.forma) + ")";
        }
        return r;
    }

    private static List<String[]> filasEstaticos(List<EstaticoSpec> estaticos) {
        List<String[]> filas = new ArrayList<>();
        for (EstaticoSpec e : lista(estaticos)) {
            if (e == null) {
                continue;
            }
            List<String> partes = new ArrayList<>();
            if (e.devuelve != null) {
                partes.add(cod(e.devuelve));
            }
            if (e.lanza != null) {
                partes.add("lanza " + cod(e.lanza));
            }
            filas.add(new String[]{e.clase + "." + e.llamada, partes.isEmpty() ? "sin efecto" : String.join(", ", partes)});
        }
        return filas;
    }

    private static List<String[]> filasConstrucciones(List<ConstruccionMockSpec> construcciones) {
        List<String[]> filas = new ArrayList<>();
        for (ConstruccionMockSpec cm : lista(construcciones)) {
            if (cm == null) {
                continue;
            }
            String base = "new " + cm.clase;
            List<StubSpec> stubs = lista(cm.stubs);
            if (stubs.isEmpty()) {
                filas.add(new String[]{base, "sin stubs"});
                continue;
            }
            for (StubSpec s : stubs) {
                if (s != null) {
                    filas.add(new String[]{base + " · " + claveStub(s), efectoStub(s)});
                }
            }
        }
        return filas;
    }

    private static List<String[]> filasDatos(List<DatoSpec> datos) {
        List<String[]> filas = new ArrayList<>();
        for (DatoSpec dt : lista(datos)) {
            if (dt == null) {
                continue;
            }
            String valor = Boolean.TRUE.equals(dt.mock) ? "mock" : dt.valor == null ? "(sin valor)" : cod(dt.valor);
            filas.add(new String[]{Objects.toString(dt.nombre, ""), cod(dt.tipo) + " = " + valor});
        }
        return filas;
    }

    private static List<String[]> filasPreparacion(List<String> preparacion) {
        List<String[]> filas = new ArrayList<>();
        for (String linea : lista(preparacion)) {
            if (linea != null) {
                filas.add(new String[]{linea, ""});
            }
        }
        return filas;
    }

    private static void cambiosArgumentos(List<String> out, CasoSpec a, CasoSpec d) {
        List<String> aa = lista(a.argumentos);
        List<String> ad = lista(d.argumentos);
        if (!aa.equals(ad)) {
            out.add("Se cambió los datos con los que se llama a la función: de " + cod("(" + unir(aa) + ")") + " a "
                    + cod("(" + unir(ad) + ")"));
        }
    }

    private static List<String[]> filasAserciones(List<AsercionSpec> aserciones) {
        List<String[]> filas = new ArrayList<>();
        for (AsercionSpec as : lista(aserciones)) {
            if (as != null) {
                filas.add(new String[]{Objects.toString(as.real, ""),
                        san(as.tipo) + (as.esperado != null ? " " + cod(as.esperado) : "")});
            }
        }
        return filas;
    }

    private static List<String[]> filasVerificaciones(List<VerificacionSpec> verificaciones) {
        List<String[]> filas = new ArrayList<>();
        for (VerificacionSpec v : lista(verificaciones)) {
            if (v == null) {
                continue;
            }
            String texto;
            if (Boolean.TRUE.equals(v.sinInteracciones)) {
                texto = "sin interacciones";
            } else if (v.veces == null || v.veces == 1) {
                texto = "1 vez";
            } else if (v.veces == 0) {
                texto = "nunca";
            } else {
                texto = v.veces + " veces";
            }
            filas.add(new String[]{Objects.toString(v.mock, "") + (v.llamada != null ? "." + v.llamada : ""), texto});
        }
        return filas;
    }

    private static void cambiosMetodo(List<String> out, CasoSpec a, CasoSpec d) {
        String fa = firma(a);
        String fd = firma(d);
        if (!fa.equals(fd)) {
            out.add("Se cambió la función que se prueba: de " + cod(fa) + " a " + cod(fd));
        }
    }

    private static String firma(CasoSpec c) {
        List<String> p = lista(c.parametros);
        return Objects.toString(c.metodo, "") + (p.isEmpty() ? "" : "(" + unir(p) + ")");
    }

    private static void cambiosTextos(List<String> out, CasoSpec a, CasoSpec d) {
        texto(out, "el escenario", a.escenario, d.escenario);
        texto(out, "la descripción", a.descripcion, d.descripcion);
        texto(out, "la explicación de qué hace la función", a.queHaceOriginal, d.queHaceOriginal);
        texto(out, "la explicación de qué comprueba el test", a.queComprueba, d.queComprueba);
    }

    private static void texto(List<String> out, String etiqueta, String antes, String despues) {
        String a = antes == null || antes.isBlank() ? null : antes;
        String d = despues == null || despues.isBlank() ? null : despues;
        if (Objects.equals(a, d)) {
            return;
        }
        if (a == null) {
            out.add("Se añadió " + etiqueta + SEP + txt(d));
        } else if (d == null) {
            out.add("Se quitó " + etiqueta + SEP + txt(a));
        } else {
            out.add("Se cambió " + etiqueta + ": de " + txt(a) + " a " + txt(d));
        }
    }

    /** Red de seguridad: campos del caso que no se comparan explícitamente (p. ej. añadidos en el futuro). */
    private static void cambiosOtros(List<String> out, CasoSpec a, CasoSpec d) {
        JsonNode na = Json.MAPPER.valueToTree(a);
        JsonNode nd = Json.MAPPER.valueToTree(d);
        Set<String> campos = new LinkedHashSet<>();
        na.fieldNames().forEachRemaining(campos::add);
        nd.fieldNames().forEachRemaining(campos::add);
        for (String campo : campos) {
            if (CONOCIDOS.contains(campo) || EXCLUIDOS.contains(campo)) {
                continue;
            }
            JsonNode va = na.get(campo);
            JsonNode vd = nd.get(campo);
            if (Objects.equals(va, vd)) {
                continue;
            }
            out.add("Se cambió el campo " + cod(campo) + ": de " + cod(va == null ? "(ausente)" : va.toString())
                    + " a " + cod(vd == null ? "(ausente)" : vd.toString()));
        }
    }

    // ---- diff genérico de listas {clave, texto} ----

    private static void diff(List<String> out, String etiqueta, List<String[]> antes, List<String[]> despues) {
        Map<String, String> a = indexar(antes);
        Map<String, String> d = indexar(despues);
        int inicial = out.size();
        for (Map.Entry<String, String> e : a.entrySet()) {
            String clave = e.getKey();
            String ta = e.getValue();
            if (!d.containsKey(clave)) {
                out.add("Se quitó " + conClave(etiqueta, clave) + (ta.isEmpty() ? "" : SEP + ta));
            } else if (!ta.equals(d.get(clave))) {
                out.add("Se cambió " + conClave(etiqueta, clave) + ": de " + ta + " a " + d.get(clave));
            }
        }
        for (Map.Entry<String, String> e : d.entrySet()) {
            if (!a.containsKey(e.getKey())) {
                String td = e.getValue();
                out.add("Se añadió " + conClave(etiqueta, e.getKey()) + (td.isEmpty() ? "" : SEP + td));
            }
        }
        if (out.size() == inicial && !new ArrayList<>(a.keySet()).equals(new ArrayList<>(d.keySet()))) {
            out.add("Mismos elementos en otro orden: " + etiqueta);
        }
    }

    /** Clave cruda → texto, en orden; las claves repetidas reciben sufijo « #2», « #3»… */
    private static Map<String, String> indexar(List<String[]> filas) {
        Map<String, String> m = new LinkedHashMap<>();
        Map<String, Integer> repeticiones = new LinkedHashMap<>();
        for (String[] f : filas) {
            String base = Objects.toString(f[0], "");
            int n = repeticiones.merge(base, 1, Integer::sum);
            String clave = n == 1 ? base : base + " #" + n;
            m.put(clave, Objects.toString(f[1], ""));
        }
        return m;
    }

    // ---- guardia de calidad ----

    /** Aviso de calidad de una corrección: tipo ({@link #AVISO_DEBILITA}, {@link #AVISO_ESCENARIO}, {@link #AVISO_COPIA}) y frase. */
    public record AvisoCalidad(String tipo, String frase) {
    }

    /** La corrección deja una comprobación menos estricta o la quita. */
    public static final String AVISO_DEBILITA = "debilita";
    /** La corrección cambia el escenario (valor ↔ excepción). */
    public static final String AVISO_ESCENARIO = "escenario";
    /** El nuevo valor esperado es el que devolvió el código en la prueba. */
    public static final String AVISO_COPIA = "copia_obtenido";

    /** Fuerza de una comprobación exacta, de tamaño, parcial o nula. */
    private static final int FUERZA_EXACTA = 3;
    private static final int FUERZA_TAMANO = 2;
    private static final int FUERZA_PARCIAL = 1;
    private static final int FUERZA_NULA = 0;
    /** Prefijo mínimo (caracteres) para comparar con un valor obtenido recortado. */
    private static final int MIN_PREFIJO_OBTENIDO = 8;
    private static final String TIPO_EXCEPCION = "excepcion";
    private static final Set<String> EXCEPCIONES_GENERICAS = Set.of("Exception", "RuntimeException", "Throwable", "Error");

    /**
     * Avisos de calidad al pasar de {@code antes} (caso guardado) a {@code despues} (caso propuesto): esperado menos
     * estricto, comprobación debilitada o quitada, verificación quitada, cambio de escenario y, si {@code obtenido} no es
     * null, esperado nuevo igual al valor obtenido. Determinista; nunca lanza (ante un fallo devuelve lo calculado).
     */
    public static List<AvisoCalidad> avisosCalidad(CasoSpec antes, CasoSpec despues, String obtenido) {
        List<AvisoCalidad> out = new ArrayList<>();
        try {
            CasoSpec a = antes != null ? antes : new CasoSpec();
            CasoSpec d = despues != null ? despues : new CasoSpec();
            avisosEsperado(out, a, d);
            avisosAserciones(out, a, d);
            avisosVerificaciones(out, a, d);
            if (obtenido != null) {
                avisosCopia(out, a, d, obtenido);
            }
        } catch (RuntimeException e) {
            System.err.println("junit-gen: no se pudo comparar la calidad de la corrección: " + e.getClass().getSimpleName() + ": "
                    + e.getMessage());
        }
        return out;
    }

    private static void avisosEsperado(List<AvisoCalidad> out, CasoSpec a, CasoSpec d) {
        boolean ea = esExcepcion(a.esperado);
        boolean ed = esExcepcion(d.esperado);
        if (ea != ed) {
            out.add(new AvisoCalidad(AVISO_ESCENARIO, "Cambia lo que comprueba el test: antes esperaba " + esperado(a.esperado)
                    + " y ahora " + esperado(d.esperado)));
            return;
        }
        if (ea) {
            String xa = simpleTipo(a.esperado.excepcion);
            String xd = simpleTipo(d.esperado.excepcion);
            if (EXCEPCIONES_GENERICAS.contains(xd) && !xd.equals(xa)) {
                out.add(new AvisoCalidad(AVISO_DEBILITA, "La excepción esperada pasa de " + cod(xa) + " a la genérica " + cod(xd)));
            }
            if (a.esperado.mensaje != null && d.esperado.mensaje == null) {
                out.add(new AvisoCalidad(AVISO_DEBILITA, "Ya no se comprueba el mensaje exacto de la excepción"));
            } else if (a.esperado.mensajeContiene != null && d.esperado.mensaje == null && d.esperado.mensajeContiene == null) {
                out.add(new AvisoCalidad(AVISO_DEBILITA, "Ya no se comprueba el mensaje de la excepción"));
            }
            return;
        }
        if (fuerza(d) < fuerza(a)) {
            out.add(new AvisoCalidad(AVISO_DEBILITA, "El resultado esperado pasa de " + esperado(a.esperado) + " a "
                    + esperado(d.esperado) + " (comprobación menos estricta)"));
        }
    }

    private static void avisosAserciones(List<AvisoCalidad> out, CasoSpec a, CasoSpec d) {
        Map<String, AsercionSpec> ia = indexarAserciones(a.aserciones);
        Map<String, AsercionSpec> id = indexarAserciones(d.aserciones);
        boolean esperadoMasFuerte = fuerza(d) == FUERZA_EXACTA && fuerza(a) != FUERZA_EXACTA;
        for (Map.Entry<String, AsercionSpec> e : ia.entrySet()) {
            AsercionSpec va = e.getValue();
            AsercionSpec vd = id.get(e.getKey());
            if (vd == null) {
                if (!esperadoMasFuerte) {
                    out.add(new AvisoCalidad(AVISO_DEBILITA, "Se quitó la comprobación de " + cod(va.real)));
                }
            } else if (rango(vd) < rango(va)) {
                out.add(new AvisoCalidad(AVISO_DEBILITA, "La comprobación de " + cod(va.real) + " pasa de " + cod(va.tipo) + " a "
                        + cod(vd.tipo)));
            }
        }
    }

    private static void avisosVerificaciones(List<AvisoCalidad> out, CasoSpec a, CasoSpec d) {
        Map<String, String> ia = indexarVerificaciones(a.verificaciones);
        Map<String, String> id = indexarVerificaciones(d.verificaciones);
        for (Map.Entry<String, String> e : ia.entrySet()) {
            if (!id.containsKey(e.getKey())) {
                out.add(new AvisoCalidad(AVISO_DEBILITA, "Se quitó la comprobación de llamadas " + cod(e.getValue())));
            }
        }
    }

    private static void avisosCopia(List<AvisoCalidad> out, CasoSpec a, CasoSpec d, String obtenido) {
        List<String> nuevos = new ArrayList<>();
        String vd = valorEsperado(d.esperado);
        if (vd != null && !vd.equals(valorEsperado(a.esperado))) {
            nuevos.add(vd);
        }
        Map<String, AsercionSpec> ia = indexarAserciones(a.aserciones);
        for (Map.Entry<String, AsercionSpec> e : indexarAserciones(d.aserciones).entrySet()) {
            String ev = e.getValue().esperado;
            AsercionSpec previa = ia.get(e.getKey());
            if (ev != null && (previa == null || !ev.equals(previa.esperado))) {
                nuevos.add(ev);
            }
        }
        boolean recortado = obtenido.endsWith(LimitesRespuesta.MARCA_RECORTE);
        String ob = normalizarValor(recortado
                ? obtenido.substring(0, obtenido.length() - LimitesRespuesta.MARCA_RECORTE.length()) : obtenido);
        for (String v : nuevos) {
            String nv = normalizarValor(v);
            boolean igual = recortado ? ob.length() >= MIN_PREFIJO_OBTENIDO && nv.startsWith(ob) : nv.equals(ob);
            if (igual) {
                out.add(new AvisoCalidad(AVISO_COPIA, "El nuevo valor esperado es el que devolvió el código en la prueba (" + cod(v) + ")"));
                return;
            }
        }
    }

    private static boolean esExcepcion(EsperadoSpec e) {
        return e != null && TIPO_EXCEPCION.equals(e.tipo);
    }

    /** Fuerza del esperado del caso (3 exacta, 1 no nulo, 0 sin retorno o sin esperado; aserciones: su máximo rango). */
    private static int fuerza(CasoSpec c) {
        EsperadoSpec e = c.esperado;
        if (e == null || e.tipo == null) {
            return FUERZA_NULA;
        }
        return switch (e.tipo) {
            case "valor", "nulo", "verdadero", "falso" -> FUERZA_EXACTA;
            case "noNulo" -> FUERZA_PARCIAL;
            case "aserciones" -> {
                int max = FUERZA_NULA;
                for (AsercionSpec as : lista(c.aserciones)) {
                    if (as != null) {
                        max = Math.max(max, rango(as));
                    }
                }
                yield max;
            }
            default -> FUERZA_NULA;
        };
    }

    /** Rango de una aserción según su tipo. */
    private static int rango(AsercionSpec as) {
        if (as == null || as.tipo == null) {
            return FUERZA_NULA;
        }
        return switch (as.tipo) {
            case "igual", "mismo", "nulo", "verdadero", "falso", "vacio" -> FUERZA_EXACTA;
            case "tamano" -> FUERZA_TAMANO;
            case "contiene" -> FUERZA_PARCIAL;
            default -> FUERZA_NULA;
        };
    }

    private static Map<String, AsercionSpec> indexarAserciones(List<AsercionSpec> aserciones) {
        Map<String, AsercionSpec> m = new LinkedHashMap<>();
        Map<String, Integer> repeticiones = new LinkedHashMap<>();
        for (AsercionSpec as : lista(aserciones)) {
            if (as == null) {
                continue;
            }
            String base = com.junitgen.duplicados.NormalizadorExpresiones.sinEspacios(Objects.toString(as.real, ""));
            int n = repeticiones.merge(base, 1, Integer::sum);
            m.put(n == 1 ? base : base + " #" + n, as);
        }
        return m;
    }

    /** Clave normalizada → texto visible de cada verificación. */
    private static Map<String, String> indexarVerificaciones(List<VerificacionSpec> verificaciones) {
        Map<String, String> m = new LinkedHashMap<>();
        Map<String, Integer> repeticiones = new LinkedHashMap<>();
        for (VerificacionSpec v : lista(verificaciones)) {
            if (v == null) {
                continue;
            }
            String texto = Boolean.TRUE.equals(v.sinInteracciones) || v.llamada == null
                    ? Objects.toString(v.mock, "") : Objects.toString(v.mock, "") + "." + v.llamada;
            // Con llamada, la clave usa la forma canónica de los matchers: normalizarlos (any() → any(T.class), eq(x) → x) no cuenta como quitar la comprobación.
            String base = Boolean.TRUE.equals(v.sinInteracciones) || v.llamada == null
                    ? com.junitgen.duplicados.NormalizadorExpresiones.sinEspacios(texto)
                    : com.junitgen.duplicados.NormalizadorExpresiones.sinEspacios(Objects.toString(v.mock, "") + ".")
                    + com.junitgen.spec.NormalizadorMatchers.clave(v.llamada);
            int n = repeticiones.merge(base, 1, Integer::sum);
            m.put(n == 1 ? base : base + " #" + n, texto);
        }
        return m;
    }

    /** Valor esperado comparable: el valor, o "null"/"true"/"false" según el tipo; null si no hay. */
    private static String valorEsperado(EsperadoSpec e) {
        if (e == null || e.tipo == null) {
            return null;
        }
        return switch (e.tipo) {
            case "valor" -> e.valor;
            case "nulo" -> "null";
            case "verdadero" -> "true";
            case "falso" -> "false";
            default -> null;
        };
    }

    /** Forma normalizada: sin comillas exteriores, sin sufijo numérico (L, f, d) y con los espacios colapsados. */
    private static String normalizarValor(String v) {
        String s = v == null ? "" : v.strip();
        if (s.length() >= 2 && ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("'") && s.endsWith("'")))) {
            s = s.substring(1, s.length() - 1);
        }
        if (s.length() >= 2 && "LlFfDd".indexOf(s.charAt(s.length() - 1)) >= 0 && Character.isDigit(s.charAt(s.length() - 2))) {
            s = s.substring(0, s.length() - 1);
        }
        return s.replaceAll("\\s+", " ").strip();
    }

    private static String simpleTipo(String tipo) {
        if (tipo == null) {
            return "";
        }
        String t = tipo.strip();
        if (t.endsWith(".class")) {
            t = t.substring(0, t.length() - ".class".length());
        }
        int k = t.lastIndexOf('.');
        return k < 0 ? t : t.substring(k + 1);
    }

    // ---- utilidades ----

    private static <T> List<T> lista(List<T> l) {
        return l != null ? l : List.of();
    }

    private static String unir(List<String> l) {
        List<String> partes = new ArrayList<>();
        for (String s : l) {
            partes.add(Objects.toString(s, "null"));
        }
        return String.join(", ", partes);
    }

    /** Sanea un valor para markdown: una línea, sin backticks y recortado a {@link #MAX_VALOR}. */
    static String san(String s) {
        if (s == null || s.isBlank()) {
            return "";
        }
        return TextosInforme.texto(s.replace('`', '\''), MAX_VALOR);
    }

    private static String cod(String s) {
        return TextosInforme.codigo(s, MAX_VALOR);
    }

    private static String conClave(String etiqueta, String clave) {
        String c = cod(clave);
        return c.isEmpty() ? etiqueta : etiqueta + " " + c;
    }

    private static String txt(String s) {
        return "«" + san(s) + "»";
    }
}
