package com.junitgen.perfil;

import com.junitgen.escaneo.AnalisisServicio;
import com.junitgen.escaneo.AnalizadorDependencias;
import com.junitgen.escaneo.Dependencia;
import com.junitgen.escaneo.InfoClase;
import com.junitgen.escaneo.ProyectoEscaneado;
import com.junitgen.generacion.AlmacenSpec;
import com.junitgen.seguridad.EscritorSeguro;
import com.junitgen.spec.CasoSpec;
import com.junitgen.spec.ConstruccionSpec;
import com.junitgen.spec.ServicioSpec;
import com.junitgen.spec.Spec;
import com.junitgen.spec.StubSpec;
import com.junitgen.util.Json;
import com.junitgen.util.Textos;
import com.junitgen.verificacion.ClasificadorFallos;
import com.junitgen.verificacion.ResultadoTest;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Recetas aprendidas del perfil del proyecto: se aprenden cuando un caso con error de montaje/compilación pasa a montar
 * tras corregir el spec, y se fusionan en el spec antes de validarlo (lo explícito del spec gana siempre). Nunca
 * lanzan desde {@link #registrar}: un error se registra en stderr y la operación principal sigue.
 */
public final class RecetasAprendidas {

    /** Máximo de recetas guardadas. */
    static final int MAX_RECETAS = 200;
    /** Máximo de recetas stubs_caso por servicio. */
    static final int MAX_PISTAS_SERVICIO = 5;
    /** Máximo de casos fallidos guardados por servicio. */
    static final int MAX_CASOS_FALLIDOS = 20;
    /** Máximo de stubs de montaje tras aplicar una receta. */
    static final int MAX_STUBS_MONTAJE = 30;
    /** Máximo de líneas de contexto y bytes por línea. */
    static final int MAX_LINEAS_CONTEXTO = 6;
    static final int MAX_BYTES_LINEA = 160;
    private static final int MAX_SINTOMA = 160;

    private static final Pattern IDENT = Pattern.compile("[A-Za-z_$][\\w$]*");
    private static final Pattern LITERAL = Pattern.compile("\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'");

    public record Aplicada(String servicio, String recetaId, String forma, String descripcion) {
    }

    private RecetasAprendidas() {
    }

    /** Tipo de la dependencia para casar recetas de tipo: FQN o nombre simple sin genéricos. */
    static String tipoClave(Dependencia d) {
        return ConstructorPerfil.tipoClave(d);
    }

    private static String ahora() {
        return OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS).toString();
    }

    private static ProyectoEscaneado.Encontrada unica(ProyectoEscaneado p, String clase, String moduloServicio,
                                                      String moduloProyecto) {
        if (p == null || clase == null || clase.isBlank()) {
            return null;
        }
        List<ProyectoEscaneado.Encontrada> r = p.resolverServicio(clase.trim(), moduloServicio, moduloProyecto);
        return r.size() == 1 ? r.get(0) : null;
    }

    private static String moduloSpec(Spec spec) {
        return spec.proyecto == null ? null : spec.proyecto.modulo;
    }

    /** Claves admitidas en construccion.argumentos (misma regla que ValidadorSpec). */
    private static Set<String> clavesValidas(AnalisisServicio an, InfoClase c) {
        Set<String> r = new LinkedHashSet<>();
        for (Dependencia d : an.dependencias) {
            if (d.obtencion != Dependencia.TipoObtencion.ESTATICA
                    && !(d.obtencion == Dependencia.TipoObtencion.NEW_INTERNO && d.campo == null)) {
                r.add(d.nombre);
            }
        }
        for (InfoClase.InfoCampo f : c.campos) {
            if (!f.estatico()) {
                r.add(f.nombre());
            }
        }
        return r;
    }

    private static boolean aplicableTipo(PerfilProyecto.Receta r, String fqn) {
        return r.activa && r.tipo != null && (PerfilProyecto.Receta.ARGUMENTO_TIPO.equals(r.forma)
                || PerfilProyecto.Receta.PROFUNDO_TIPO.equals(r.forma))
                && !fqn.equals(r.servicio) && (r.excluidaEn == null || !r.excluidaEn.contains(fqn))
                && (PerfilProyecto.Receta.PROFUNDO_TIPO.equals(r.forma) || (r.expresion != null && !r.expresion.isBlank()));
    }

    private static PerfilProyecto.Receta recetaServicio(PerfilProyecto perfil, String fqn) {
        for (PerfilProyecto.Receta r : perfil.recetas.aprendidas) {
            if (PerfilProyecto.Receta.CONSTRUCCION.equals(r.forma) && fqn.equals(r.servicio)) {
                return r;
            }
        }
        return null;
    }

    private static boolean contenedorFijado(Dependencia d, Map<String, String> argumentos) {
        return d.contenedor != null && argumentos.containsKey(d.contenedor);
    }

    private static boolean esMockFinal(Dependencia d, Map<String, String> argumentos) {
        return d.esMockDeclarado() && d.realPorDefecto == null && !argumentos.containsKey(d.nombre)
                && !contenedorFijado(d, argumentos) && Collections.disjoint(d.derivaDe, argumentos.keySet());
    }

    private static StubSpec copiar(StubSpec s) {
        return Json.MAPPER.convertValue(s, StubSpec.class);
    }

    private static ConstruccionSpec copiar(ConstruccionSpec c) {
        return c == null ? null : Json.MAPPER.convertValue(c, ConstruccionSpec.class);
    }

    private static String claveStub(StubSpec s) {
        return s.mock + "." + s.llamada;
    }

    /**
     * Fusiona en el spec las recetas activas que correspondan (montaje aprendido del propio servicio y recetas de tipo
     * de otros servicios). No toca servicios con correcciones de montaje pendientes ni sustituye nada explícito.
     */
    public static List<Aplicada> aplicar(Spec spec, ProyectoEscaneado p, PerfilProyecto perfil) {
        List<Aplicada> aplicadas = new ArrayList<>();
        if (spec == null || spec.servicios == null || p == null || perfil == null || perfil.recetas == null
                || perfil.recetas.aprendidas == null || perfil.recetas.aprendidas.isEmpty()) {
            return aplicadas;
        }
        String modulo = moduloSpec(spec);
        for (ServicioSpec s : spec.servicios) {
            if (s == null) {
                continue;
            }
            ProyectoEscaneado.Encontrada e = unica(p, s.clase, s.modulo, modulo);
            if (e == null) {
                continue;
            }
            String fqn = e.clase().fqn;
            if (perfil.recetas.fallosMontaje != null && perfil.recetas.fallosMontaje.containsKey(fqn)) {
                continue;
            }
            aplicarServicio(s, fqn, e.modulo().analisis(e.clase()), e.clase(), perfil, aplicadas);
        }
        return aplicadas;
    }

    private static void aplicarServicio(ServicioSpec s, String fqn, AnalisisServicio an, InfoClase c, PerfilProyecto perfil,
                                        List<Aplicada> aplicadas) {
        Set<String> validas = clavesValidas(an, c);
        ConstruccionSpec base = s.construccion;
        Map<String, String> args = new LinkedHashMap<>();
        if (base != null && base.argumentos != null) {
            args.putAll(base.argumentos);
        }
        List<StubSpec> stubs = base != null && base.stubs != null ? new ArrayList<>(base.stubs) : null;
        List<String> profundos = base != null && base.profundos != null ? new ArrayList<>(base.profundos) : null;
        Boolean post = base != null ? base.postConstruct : null;
        boolean cambiado = false;

        PerfilProyecto.Receta propia = recetaServicio(perfil, fqn);
        if (propia != null && (!propia.activa || propia.construccion == null)) {
            propia = null;
        }
        List<String> partesPropia = new ArrayList<>();
        if (propia != null) {
            List<String> anadidas = new ArrayList<>();
            if (propia.construccion.argumentos != null) {
                for (Map.Entry<String, String> a : propia.construccion.argumentos.entrySet()) {
                    if (a.getKey() != null && a.getValue() != null && validas.contains(a.getKey())
                            && !args.containsKey(a.getKey()) && (profundos == null || !profundos.contains(a.getKey()))) {
                        args.put(a.getKey(), a.getValue());
                        anadidas.add(a.getKey());
                    }
                }
            }
            if (!anadidas.isEmpty()) {
                partesPropia.add("argumentos " + anadidas);
                cambiado = true;
            }
            if (post == null && Boolean.TRUE.equals(propia.construccion.postConstruct) && !an.postConstruct.isEmpty()) {
                post = Boolean.TRUE;
                partesPropia.add("postConstruct");
                cambiado = true;
            }
        }

        for (PerfilProyecto.Receta r : perfil.recetas.aprendidas) {
            if (!aplicableTipo(r, fqn)) {
                continue;
            }
            for (Dependencia d : an.dependencias) {
                if (!d.esMockDeclarado() || d.realPorDefecto != null || !r.tipo.equals(tipoClave(d))) {
                    continue;
                }
                if (PerfilProyecto.Receta.ARGUMENTO_TIPO.equals(r.forma)) {
                    if (validas.contains(d.nombre) && !args.containsKey(d.nombre)
                            && (profundos == null || !profundos.contains(d.nombre))) {
                        args.put(d.nombre, r.expresion);
                        cambiado = true;
                        aplicadas.add(new Aplicada(fqn, r.id, r.forma, "receta " + r.id + " (tipo "
                                + Textos.nombreSimple(r.tipo) + "): construccion.argumentos." + d.nombre + " = "
                                + Textos.recortar(r.expresion, 80)));
                    }
                } else if (!d.profundo && !args.containsKey(d.nombre)
                        && (profundos == null || !profundos.contains(d.nombre))) {
                    if (profundos == null) {
                        profundos = new ArrayList<>();
                    }
                    profundos.add(d.nombre);
                    cambiado = true;
                    aplicadas.add(new Aplicada(fqn, r.id, r.forma, "receta " + r.id + " (tipo "
                            + Textos.nombreSimple(r.tipo) + "): construccion.profundos += " + d.nombre));
                }
            }
        }

        if (propia != null) {
            Set<String> mocksFinales = new LinkedHashSet<>();
            for (Dependencia d : an.dependencias) {
                if (esMockFinal(d, args)) {
                    mocksFinales.add(d.nombre);
                }
            }
            List<String> profAnadidos = new ArrayList<>();
            if (propia.construccion.profundos != null) {
                for (String n : propia.construccion.profundos) {
                    if (n != null && mocksFinales.contains(n) && (profundos == null || !profundos.contains(n))) {
                        if (profundos == null) {
                            profundos = new ArrayList<>();
                        }
                        profundos.add(n);
                        profAnadidos.add(n);
                    }
                }
            }
            if (!profAnadidos.isEmpty()) {
                partesPropia.add("profundos " + profAnadidos);
                cambiado = true;
            }
            int stubsAnadidos = 0;
            if (propia.construccion.stubs != null) {
                Set<String> existentes = new HashSet<>();
                if (stubs != null) {
                    for (StubSpec x : stubs) {
                        if (x != null) {
                            existentes.add(claveStub(x));
                        }
                    }
                }
                for (StubSpec x : propia.construccion.stubs) {
                    if (x == null || x.mock == null || x.llamada == null || !mocksFinales.contains(x.mock)
                            || existentes.contains(claveStub(x))) {
                        continue;
                    }
                    int total = stubs == null ? 0 : stubs.size();
                    if (total >= MAX_STUBS_MONTAJE) {
                        break;
                    }
                    if (stubs == null) {
                        stubs = new ArrayList<>();
                    }
                    stubs.add(copiar(x));
                    existentes.add(claveStub(x));
                    stubsAnadidos++;
                }
            }
            if (stubsAnadidos > 0) {
                partesPropia.add(stubsAnadidos + " stubs");
                cambiado = true;
            }
            if (!partesPropia.isEmpty()) {
                aplicadas.add(new Aplicada(fqn, propia.id, propia.forma, "receta " + propia.id
                        + " (montaje aprendido de " + Textos.nombreSimple(fqn) + "): " + String.join(", ", partesPropia)));
            }
        }

        if (!cambiado) {
            return;
        }
        if (s.construccion == null) {
            s.construccion = new ConstruccionSpec();
        }
        s.construccion.argumentos = args;
        s.construccion.stubs = stubs;
        s.construccion.profundos = profundos;
        s.construccion.postConstruct = post;
    }

    /**
     * Líneas de recetas para la cabecera del detalle (máx. 6, ≤ 160 bytes cada una); vacío sin perfil o si el servicio
     * tiene correcciones de montaje pendientes.
     */
    public static List<String> paraContexto(PerfilProyecto perfil, String fqn, AnalisisServicio an, InfoClase c) {
        List<String> r = new ArrayList<>();
        if (perfil == null || perfil.recetas == null || perfil.recetas.aprendidas == null || fqn == null || an == null || c == null) {
            return r;
        }
        if (perfil.recetas.fallosMontaje != null && perfil.recetas.fallosMontaje.containsKey(fqn)) {
            return r;
        }
        Set<String> validas = clavesValidas(an, c);
        PerfilProyecto.Receta propia = recetaServicio(perfil, fqn);
        if (propia != null && propia.activa && propia.construccion != null) {
            ConstruccionSpec k = propia.construccion;
            if (k.argumentos != null) {
                for (Map.Entry<String, String> a : k.argumentos.entrySet()) {
                    if (a.getKey() != null && a.getValue() != null && validas.contains(a.getKey())) {
                        anadirLinea(r, "auto: construccion.argumentos." + a.getKey() + " = " + a.getValue());
                    }
                }
            }
            if (k.profundos != null) {
                List<String> prof = new ArrayList<>();
                for (String n : k.profundos) {
                    if (n != null && an.dependencia(n) != null) {
                        prof.add(n);
                    }
                }
                if (!prof.isEmpty()) {
                    anadirLinea(r, "auto: construccion.profundos " + prof);
                }
            }
            if (k.stubs != null) {
                for (StubSpec x : k.stubs) {
                    if (x != null && x.mock != null && x.llamada != null && an.dependencia(x.mock) != null) {
                        anadirLinea(r, "auto: construccion.stubs " + claveStub(x));
                    }
                }
            }
        }
        for (PerfilProyecto.Receta x : perfil.recetas.aprendidas) {
            if (!aplicableTipo(x, fqn)) {
                continue;
            }
            for (Dependencia d : an.dependencias) {
                if (!d.esMockDeclarado() || d.realPorDefecto != null || !x.tipo.equals(tipoClave(d))) {
                    continue;
                }
                String t = Textos.nombreSimple(x.tipo);
                if (PerfilProyecto.Receta.ARGUMENTO_TIPO.equals(x.forma)) {
                    if (validas.contains(d.nombre)) {
                        anadirLinea(r, "auto (tipo " + t + "): construccion.argumentos." + d.nombre + " = " + x.expresion
                                + "; no admite stubs");
                    }
                } else if (!d.profundo) {
                    anadirLinea(r, "auto (tipo " + t + "): construccion.profundos [" + d.nombre + "]");
                }
            }
        }
        for (PerfilProyecto.Receta x : perfil.recetas.aprendidas) {
            if (!x.activa || !PerfilProyecto.Receta.STUBS_CASO.equals(x.forma) || !fqn.equals(x.servicio)) {
                continue;
            }
            StringBuilder sb = new StringBuilder("pista ").append(x.metodo == null ? "?" : x.metodo).append(": ");
            if (x.sintoma != null && !x.sintoma.isBlank()) {
                sb.append('[').append(x.sintoma).append("] ");
            }
            List<String> partes = new ArrayList<>();
            if (x.quitar != null) {
                x.quitar.forEach(q -> partes.add("quitar stub " + q));
            }
            if (x.anadir != null) {
                x.anadir.forEach(q -> partes.add("añadir stub " + q));
            }
            sb.append(String.join("; ", partes));
            anadirLinea(r, sb.toString());
        }
        return r.size() > MAX_LINEAS_CONTEXTO ? new ArrayList<>(r.subList(0, MAX_LINEAS_CONTEXTO)) : r;
    }

    private static void anadirLinea(List<String> r, String linea) {
        if (r.size() >= MAX_LINEAS_CONTEXTO) {
            return;
        }
        String l = recortarBytes(linea, MAX_BYTES_LINEA);
        if (!r.contains(l)) {
            r.add(l);
        }
    }

    /** Recorta a {@code max} bytes UTF-8 (con «…» si se recorta), sin partir caracteres. */
    static String recortarBytes(String s, int max) {
        if (s == null || s.getBytes(StandardCharsets.UTF_8).length <= max) {
            return s;
        }
        int limite = max - "…".getBytes(StandardCharsets.UTF_8).length;
        StringBuilder sb = new StringBuilder();
        int bytes = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            int n = new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8).length;
            if (bytes + n > limite) {
                break;
            }
            sb.appendCodePoint(cp);
            bytes += n;
            i += Character.charCount(cp);
        }
        return sb.append('…').toString();
    }

    /** Añade a {@code enPrueba} los ids de recetas aplicadas por servicio (sin repetir). */
    public static void anotarEnPrueba(PerfilProyecto perfil, Map<String, List<String>> idsPorServicio) {
        if (perfil == null || idsPorServicio == null) {
            return;
        }
        if (perfil.recetas.enPrueba == null) {
            perfil.recetas.enPrueba = new LinkedHashMap<>();
        }
        for (Map.Entry<String, List<String>> e : idsPorServicio.entrySet()) {
            if (e.getKey() == null || e.getValue() == null || e.getValue().isEmpty()) {
                continue;
            }
            List<String> ids = perfil.recetas.enPrueba.computeIfAbsent(e.getKey(), k -> new ArrayList<>());
            for (String id : e.getValue()) {
                if (id != null && !ids.contains(id)) {
                    ids.add(id);
                }
            }
        }
    }

    private record Resultado(ResultadoTest test, CasoSpec caso) {
    }

    private static final class Grupo {
        final ServicioSpec servicio;
        final AnalisisServicio analisis;
        final InfoClase clase;
        final List<Resultado> resultados = new ArrayList<>();

        Grupo(ServicioSpec servicio, AnalisisServicio analisis, InfoClase clase) {
            this.servicio = servicio;
            this.analisis = analisis;
            this.clase = clase;
        }
    }

    private static boolean falla(Resultado r) {
        return (ClasificadorFallos.ERROR_MONTAJE.equals(r.test.estado) || ClasificadorFallos.NO_COMPILA.equals(r.test.estado))
                && !r.caso.protegido();
    }

    private static boolean montado(Resultado r) {
        return ClasificadorFallos.PASA.equals(r.test.estado) || ClasificadorFallos.FALLO_ASERCION.equals(r.test.estado);
    }

    /**
     * Aprende de una verificación (con el bloqueo de escritura tomado y antes de que cambien los estados del spec):
     * registra montajes fallidos, crea recetas cuando un caso fallido pasa a montar y actualiza las recetas en prueba.
     * Devuelve {ruta, json} del perfil para escribirlo con el resto, o null si no hay nada que escribir. Nunca lanza.
     */
    public static Map.Entry<Path, String> registrar(EscritorSeguro esc, ProyectoEscaneado p, Spec spec, List<ResultadoTest> resultados) {
        if (esc == null || p == null || spec == null || resultados == null || resultados.isEmpty()) {
            return null;
        }
        try {
            String modulo = moduloSpec(spec);
            Map<String, Grupo> grupos = new LinkedHashMap<>();
            boolean hayFallos = false;
            for (ResultadoTest t : resultados) {
                if (t == null || t.caso == null || t.estado == null) {
                    continue;
                }
                AlmacenSpec.Ubicacion u = AlmacenSpec.buscar(spec, t.caso);
                if (u == null || u.servicio() == null || u.caso() == null) {
                    continue;
                }
                ProyectoEscaneado.Encontrada e = unica(p, u.servicio().clase, u.servicio().modulo, modulo);
                if (e == null) {
                    continue;
                }
                Grupo g = grupos.computeIfAbsent(e.clase().fqn,
                        k -> new Grupo(u.servicio(), e.modulo().analisis(e.clase()), e.clase()));
                Resultado r = new Resultado(t, u.caso());
                g.resultados.add(r);
                hayFallos |= falla(r);
            }
            if (grupos.isEmpty()) {
                return null;
            }
            Path salida = esc.salida();
            if (!hayFallos && AlmacenPerfil.cargar(salida, p) == null) {
                return null;
            }
            boolean[] cambio = {false};
            Map.Entry<Path, String> r = AlmacenPerfil.modificar(salida, p, perfil -> {
                String antes = Json.compacto(perfil.recetas);
                for (Map.Entry<String, Grupo> e : grupos.entrySet()) {
                    registrarServicio(perfil, e.getKey(), e.getValue());
                }
                limitar(perfil);
                cambio[0] = !antes.equals(Json.compacto(perfil.recetas));
            });
            if (r == null) {
                return null;
            }
            if (!cambio[0] && Files.isRegularFile(r.getKey())) {
                return null;
            }
            return r;
        } catch (RuntimeException e) {
            System.err.println("Perfil: no se pudieron registrar las recetas (" + e.getClass().getSimpleName() + ")");
            return null;
        }
    }

    private static List<String> stubsCaso(CasoSpec c) {
        List<String> r = new ArrayList<>();
        if (c.stubs != null) {
            for (StubSpec s : c.stubs) {
                if (s != null && s.mock != null && s.llamada != null) {
                    String k = claveStub(s);
                    if (!r.contains(k)) {
                        r.add(k);
                    }
                }
            }
        }
        return r;
    }

    private static String sintoma(String tipoError, String pista) {
        String t = tipoError == null ? "" : Textos.nombreSimple(tipoError);
        String s = t + ": " + (pista == null ? "" : pista);
        return recortarBytes(s, MAX_SINTOMA);
    }

    private static PerfilProyecto.Receta nueva(PerfilProyecto perfil, String forma, String servicio, String sintoma) {
        PerfilProyecto.Receta r = new PerfilProyecto.Receta();
        r.id = "r" + perfil.recetas.siguienteId++;
        r.forma = forma;
        r.servicio = servicio;
        r.sintoma = sintoma;
        r.aprendida = ahora();
        r.aciertos = 1;
        return r;
    }

    private static void registrarServicio(PerfilProyecto perfil, String fqn, Grupo g) {
        PerfilProyecto.Recetas rs = perfil.recetas;
        List<Resultado> fallan = new ArrayList<>();
        List<Resultado> montados = new ArrayList<>();
        for (Resultado r : g.resultados) {
            if (falla(r)) {
                fallan.add(r);
            } else if (montado(r)) {
                montados.add(r);
            }
        }
        ConstruccionSpec actual = g.servicio.construccion;
        PerfilProyecto.FalloMontaje fm = rs.fallosMontaje.get(fqn);
        if (fm != null) {
            boolean construccionAprendida = false;
            for (Resultado r : montados) {
                PerfilProyecto.CasoFallido cf = fm.casos.get(r.test.caso);
                if (cf == null) {
                    continue;
                }
                if (r.caso.veredictoIa == null) {
                    String sint = sintoma(cf.tipoError, cf.pista);
                    if (!Json.compacto(actual).equals(Json.compacto(fm.construccion))) {
                        if (!construccionAprendida) {
                            aprenderConstruccion(perfil, fqn, actual, fm.construccion, g, sint);
                            construccionAprendida = true;
                        }
                    } else {
                        aprenderStubs(perfil, fqn, r.caso, cf, sint);
                    }
                }
                fm.casos.remove(r.test.caso);
            }
            if (fm.casos.isEmpty()) {
                rs.fallosMontaje.remove(fqn);
            }
        }
        for (Resultado r : fallan) {
            PerfilProyecto.FalloMontaje f = rs.fallosMontaje.computeIfAbsent(fqn, k -> new PerfilProyecto.FalloMontaje());
            f.construccion = copiar(actual);
            if (f.casos.size() >= MAX_CASOS_FALLIDOS && !f.casos.containsKey(r.test.caso)) {
                continue;
            }
            PerfilProyecto.CasoFallido cf = new PerfilProyecto.CasoFallido();
            cf.metodo = r.caso.metodo;
            cf.tipoError = r.test.tipoError;
            cf.pista = r.test.pista == null ? null : recortarBytes(r.test.pista, MAX_SINTOMA);
            cf.stubs = stubsCaso(r.caso);
            f.casos.put(r.test.caso, cf);
        }
        List<String> ids = rs.enPrueba.get(fqn);
        if (ids != null && !ids.isEmpty()) {
            boolean culpable = false;
            for (Resultado r : fallan) {
                String pista = r.test.pista == null ? "" : r.test.pista;
                if (pista.startsWith("falla el constructor") || pista.startsWith("la dependencia es real")) {
                    culpable = true;
                    break;
                }
            }
            if (!culpable && !g.resultados.isEmpty()
                    && g.resultados.stream().allMatch(r -> ClasificadorFallos.NO_COMPILA.equals(r.test.estado))) {
                culpable = true;
            }
            if (culpable) {
                for (String id : ids) {
                    PerfilProyecto.Receta x = porId(perfil, id);
                    if (x == null) {
                        continue;
                    }
                    x.fallos++;
                    if (x.excluidaEn == null) {
                        x.excluidaEn = new ArrayList<>();
                    }
                    if (!x.excluidaEn.contains(fqn)) {
                        x.excluidaEn.add(fqn);
                    }
                    if (x.fallos >= 2 && x.fallos > x.aciertos) {
                        x.activa = false;
                    }
                }
                rs.enPrueba.remove(fqn);
            } else if (fallan.isEmpty() && !montados.isEmpty()) {
                for (String id : ids) {
                    PerfilProyecto.Receta x = porId(perfil, id);
                    if (x != null) {
                        x.aciertos++;
                    }
                }
                rs.enPrueba.remove(fqn);
            }
        } else if (ids != null) {
            rs.enPrueba.remove(fqn);
        }
    }

    private static PerfilProyecto.Receta porId(PerfilProyecto perfil, String id) {
        for (PerfilProyecto.Receta r : perfil.recetas.aprendidas) {
            if (id.equals(r.id)) {
                return r;
            }
        }
        return null;
    }

    private static PerfilProyecto.Receta deTipo(PerfilProyecto perfil, String forma, String tipo) {
        for (PerfilProyecto.Receta r : perfil.recetas.aprendidas) {
            if (forma.equals(r.forma) && tipo.equals(r.tipo)) {
                return r;
            }
        }
        return null;
    }

    private static void aprenderConstruccion(PerfilProyecto perfil, String fqn, ConstruccionSpec actual, ConstruccionSpec fallida,
                                             Grupo g, String sintoma) {
        List<PerfilProyecto.Receta> aprendidas = perfil.recetas.aprendidas;
        aprendidas.removeIf(r -> PerfilProyecto.Receta.CONSTRUCCION.equals(r.forma) && fqn.equals(r.servicio));
        if (actual == null) {
            return;
        }
        PerfilProyecto.Receta rc = nueva(perfil, PerfilProyecto.Receta.CONSTRUCCION, fqn, sintoma);
        rc.construccion = copiar(actual);
        aprendidas.add(rc);

        Set<String> nombres = new HashSet<>();
        for (Dependencia d : g.analisis.dependencias) {
            nombres.add(d.nombre);
        }
        for (InfoClase.InfoCampo f : g.clase.campos) {
            nombres.add(f.nombre());
        }
        Map<String, String> previos = fallida != null && fallida.argumentos != null ? fallida.argumentos : Map.of();
        if (actual.argumentos != null) {
            for (Map.Entry<String, String> a : actual.argumentos.entrySet()) {
                String k = a.getKey();
                String expr = a.getValue();
                if (k == null || expr == null || expr.isBlank() || expr.equals(previos.get(k))) {
                    continue;
                }
                Dependencia d = g.analisis.dependencia(k);
                if (d == null || !d.esMockDeclarado() || d.tipo == null) {
                    continue;
                }
                String simple = Textos.nombreSimple(Textos.quitarGenericos(d.tipo).trim());
                if (AnalizadorDependencias.TIPOS_VALOR.contains(simple) || !autocontenida(expr, nombres)) {
                    continue;
                }
                String tipo = tipoClave(d);
                PerfilProyecto.Receta existente = deTipo(perfil, PerfilProyecto.Receta.ARGUMENTO_TIPO, tipo);
                if (existente != null) {
                    if (expr.equals(existente.expresion)) {
                        existente.aciertos++;
                    }
                    continue;
                }
                PerfilProyecto.Receta rt = nueva(perfil, PerfilProyecto.Receta.ARGUMENTO_TIPO, fqn, sintoma);
                rt.tipo = tipo;
                rt.expresion = expr;
                aprendidas.add(rt);
            }
        }
        Set<String> profPrevios = fallida != null && fallida.profundos != null ? new HashSet<>(fallida.profundos) : Set.of();
        if (actual.profundos != null) {
            for (String n : actual.profundos) {
                if (n == null || profPrevios.contains(n)) {
                    continue;
                }
                Dependencia d = g.analisis.dependencia(n);
                if (d == null || !d.esMockDeclarado()) {
                    continue;
                }
                String tipo = tipoClave(d);
                PerfilProyecto.Receta existente = deTipo(perfil, PerfilProyecto.Receta.PROFUNDO_TIPO, tipo);
                if (existente != null) {
                    existente.aciertos++;
                    continue;
                }
                PerfilProyecto.Receta rt = nueva(perfil, PerfilProyecto.Receta.PROFUNDO_TIPO, fqn, sintoma);
                rt.tipo = tipo;
                aprendidas.add(rt);
            }
        }
    }

    /** true si ningún identificador de la expresión (fuera de literales) es una dependencia o campo del servicio. */
    static boolean autocontenida(String expr, Set<String> nombres) {
        String sinLiterales = LITERAL.matcher(expr).replaceAll("\"\"");
        Matcher m = IDENT.matcher(sinLiterales);
        while (m.find()) {
            if (nombres.contains(m.group())) {
                return false;
            }
        }
        return true;
    }

    private static void aprenderStubs(PerfilProyecto perfil, String fqn, CasoSpec caso, PerfilProyecto.CasoFallido cf, String sintoma) {
        List<String> actuales = stubsCaso(caso);
        List<String> previos = cf.stubs == null ? List.of() : cf.stubs;
        List<String> quitar = new ArrayList<>();
        for (String s : previos) {
            if (!actuales.contains(s)) {
                quitar.add(s);
            }
        }
        List<String> anadir = new ArrayList<>();
        for (String s : actuales) {
            if (!previos.contains(s)) {
                anadir.add(s);
            }
        }
        if (quitar.isEmpty() && anadir.isEmpty()) {
            return;
        }
        String metodo = caso.metodo != null ? caso.metodo : cf.metodo;
        List<PerfilProyecto.Receta> aprendidas = perfil.recetas.aprendidas;
        for (PerfilProyecto.Receta r : aprendidas) {
            if (PerfilProyecto.Receta.STUBS_CASO.equals(r.forma) && fqn.equals(r.servicio) && Objects.equals(metodo, r.metodo)
                    && Objects.equals(quitar, r.quitar == null ? List.of() : r.quitar)
                    && Objects.equals(anadir, r.anadir == null ? List.of() : r.anadir)) {
                r.aciertos++;
                return;
            }
        }
        List<PerfilProyecto.Receta> delServicio = new ArrayList<>();
        for (PerfilProyecto.Receta r : aprendidas) {
            if (PerfilProyecto.Receta.STUBS_CASO.equals(r.forma) && fqn.equals(r.servicio)) {
                delServicio.add(r);
            }
        }
        while (delServicio.size() >= MAX_PISTAS_SERVICIO) {
            aprendidas.remove(delServicio.remove(0));
        }
        PerfilProyecto.Receta r = nueva(perfil, PerfilProyecto.Receta.STUBS_CASO, fqn, sintoma);
        r.metodo = metodo;
        r.quitar = quitar.isEmpty() ? null : quitar;
        r.anadir = anadir.isEmpty() ? null : anadir;
        aprendidas.add(r);
    }

    /** Tope de recetas: se descartan primero las inactivas más antiguas y después las más antiguas. */
    private static void limitar(PerfilProyecto perfil) {
        List<PerfilProyecto.Receta> l = perfil.recetas.aprendidas;
        while (l.size() > MAX_RECETAS) {
            int idx = -1;
            for (int i = 0; i < l.size(); i++) {
                if (!l.get(i).activa) {
                    idx = i;
                    break;
                }
            }
            l.remove(idx >= 0 ? idx : 0);
        }
        Set<String> vigentes = new HashSet<>();
        l.forEach(x -> vigentes.add(x.id));
        for (List<String> ids : perfil.recetas.enPrueba.values()) {
            ids.removeIf(id -> !vigentes.contains(id));
        }
        perfil.recetas.enPrueba.values().removeIf(List::isEmpty);
    }
}
