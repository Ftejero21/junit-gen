package com.junitgen.perfil;

import com.junitgen.Main;
import com.junitgen.Version;
import com.junitgen.escaneo.AnalisisServicio;
import com.junitgen.escaneo.Dependencia;
import com.junitgen.escaneo.DetectorServicios;
import com.junitgen.escaneo.FabricaObjetos;
import com.junitgen.escaneo.InfoClase;
import com.junitgen.escaneo.InfoMetodo;
import com.junitgen.escaneo.LlamadasDependencias;
import com.junitgen.escaneo.ModuloEscaneado;
import com.junitgen.escaneo.ProyectoEscaneado;
import com.junitgen.seguridad.RutaSegura;
import com.junitgen.util.Json;
import com.junitgen.util.LimitesRespuesta;
import com.junitgen.util.Textos;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Construye o actualiza de forma incremental el {@link PerfilProyecto} a partir del escaneo: solo recalcula las
 * entradas cuyos fuentes cambiaron de huella (o de entorno). Las rutas del perfil previo solo se comparan como
 * cadenas: los archivos que se leen son siempre los del escaneo. Solo lee.
 */
public final class ConstructorPerfil {

    public static final String CREADO = "CREADO";
    public static final String ACTUALIZADO = "ACTUALIZADO";
    public static final String REGENERADO = "REGENERADO";
    public static final String SIN_CAMBIOS = "SIN_CAMBIOS";

    static final int MAX_STUBS = 500;
    private static final int MAX_NIVELES_SUPER = 5;
    private static final int MAX_NOTA = 160;

    public record Resultado(PerfilProyecto perfil, String estado, int recalculados, int eliminados, String motivo,
                            boolean cambiado) {
    }

    private ConstructorPerfil() {
    }

    /**
     * Sincroniza el perfil previo (null si no hay o es inválido, con {@code motivoInvalido}) con el escaneo actual.
     * Nunca modifica {@code previo}.
     */
    public static Resultado sincronizar(PerfilProyecto previo, String motivoInvalido, ProyectoEscaneado p) {
        String ahora = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS).toString();
        String motivo = motivoInvalido;
        if (previo != null && !mismoProyecto(previo, p)) {
            previo = null;
            motivo = "era de otro proyecto";
        }
        if (previo == null) {
            PerfilProyecto nuevo = base(p, ahora, ahora);
            Calculo k = new Calculo(p);
            int n = k.rellenar(nuevo, null, false);
            nuevo.stubs = k.stubs();
            return new Resultado(nuevo, motivo != null ? REGENERADO : CREADO, n, 0, motivo, true);
        }
        boolean reutilizable = previo.esquema == PerfilProyecto.ESQUEMA && motor().equals(previo.motor);
        PerfilProyecto nuevo = base(p, previo.creado != null ? previo.creado : ahora, previo.actualizado);
        nuevo.recetas = copiarRecetas(previo.recetas);
        Calculo k = new Calculo(p);
        int recalculados = k.rellenar(nuevo, previo, reutilizable);
        int eliminados = 0;
        for (String fqn : previo.servicios == null ? Set.<String>of() : previo.servicios.keySet()) {
            if (!nuevo.servicios.containsKey(fqn)) {
                eliminados++;
            }
        }
        for (String fqn : previo.tipos == null ? Set.<String>of() : previo.tipos.keySet()) {
            if (!nuevo.tipos.containsKey(fqn)) {
                eliminados++;
            }
        }
        podarRecetas(nuevo);
        boolean serviciosCambiados = k.serviciosCambiados || previo.servicios == null
                || !previo.servicios.keySet().equals(nuevo.servicios.keySet());
        if (serviciosCambiados || previo.stubs == null) {
            nuevo.stubs = k.stubs();
        } else {
            nuevo.stubs = previo.stubs;
        }
        boolean cambiado = !Json.compacto(nuevo).equals(Json.compacto(previo));
        if (cambiado) {
            nuevo.actualizado = ahora;
        }
        return new Resultado(nuevo, cambiado ? ACTUALIZADO : SIN_CAMBIOS, recalculados, eliminados, null, cambiado);
    }

    private static boolean mismoProyecto(PerfilProyecto previo, ProyectoEscaneado p) {
        try {
            return previo.proyecto != null && previo.proyecto.ruta != null
                    && RutaSegura.iguales(Path.of(previo.proyecto.ruta), p.raiz);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static PerfilProyecto base(ProyectoEscaneado p, String creado, String actualizado) {
        PerfilProyecto r = new PerfilProyecto();
        r.generador = "junit-gen " + Version.version();
        r.motor = motor();
        r.proyecto = new PerfilProyecto.Identidad();
        r.proyecto.ruta = p.raiz.toString();
        Path nombre = p.raiz.getFileName();
        r.proyecto.nombre = nombre == null ? p.raiz.toString() : nombre.toString();
        Map<String, String> entorno = new TreeMap<>();
        for (ModuloEscaneado me : p.modulos) {
            r.proyecto.modulos.add(me.modulo.rel);
            entorno.put(me.modulo.rel, me.versiones == null ? "" : me.versiones.resumen());
        }
        r.entorno = new LinkedHashMap<>(entorno);
        r.creado = creado;
        r.actualizado = actualizado;
        return r;
    }

    /** Copia profunda de las recetas (vía JSON) para no modificar el perfil previo. */
    private static PerfilProyecto.Recetas copiarRecetas(PerfilProyecto.Recetas r) {
        if (r == null) {
            return new PerfilProyecto.Recetas();
        }
        try {
            PerfilProyecto.Recetas c = Json.MAPPER.readValue(Json.compacto(r), PerfilProyecto.Recetas.class);
            if (c.aprendidas == null) {
                c.aprendidas = new ArrayList<>();
            }
            if (c.enPrueba == null) {
                c.enPrueba = new LinkedHashMap<>();
            }
            if (c.fallosMontaje == null) {
                c.fallosMontaje = new LinkedHashMap<>();
            }
            return c;
        } catch (IOException e) {
            throw new IllegalStateException("No se pudieron copiar las recetas del perfil: " + e.getMessage(), e);
        }
    }

    /** Elimina las recetas, pruebas y fallos de servicios que ya no existen. */
    private static void podarRecetas(PerfilProyecto perfil) {
        PerfilProyecto.Recetas r = perfil.recetas;
        Set<String> vigentes = perfil.servicios.keySet();
        r.aprendidas.removeIf(x -> x.servicio != null && !vigentes.contains(x.servicio));
        r.enPrueba.keySet().removeIf(k -> !vigentes.contains(k));
        r.fallosMontaje.keySet().removeIf(k -> !vigentes.contains(k));
    }

    /**
     * Identidad del motor: versión + tamaño + fecha de modificación del JAR en ejecución; {@code dev} fuera de un
     * .jar (mismo método que EntornoSesion.carpetaInstalacion()).
     */
    static String motor() {
        try {
            CodeSource cs = Main.class.getProtectionDomain().getCodeSource();
            if (cs == null || cs.getLocation() == null) {
                return "dev";
            }
            Path jar = Path.of(cs.getLocation().toURI()).toAbsolutePath().normalize();
            Path nombre = jar.getFileName();
            if (nombre == null || !nombre.toString().toLowerCase(Locale.ROOT).endsWith(".jar") || !Files.isRegularFile(jar)) {
                return "dev";
            }
            return Version.version() + "-" + Files.size(jar) + "-" + Files.getLastModifiedTime(jar).toMillis();
        } catch (URISyntaxException | IOException | RuntimeException e) {
            System.err.println("No se pudo identificar el motor de junit-gen para el perfil: " + e.getClass().getSimpleName());
            return "dev";
        }
    }

    /** Cálculo de entradas, fuentes, huellas y stubs sobre un escaneo. */
    private static final class Calculo {
        private final ProyectoEscaneado p;
        private final Map<Path, String> huellasMemo = new HashMap<>();
        /** Servicios vigentes: FQN → (módulo, clase), en orden. */
        private final Map<String, Object[]> servicios = new LinkedHashMap<>();
        boolean serviciosCambiados;

        Calculo(ProyectoEscaneado p) {
            this.p = p;
        }

        /** Rellena servicios, tipos y huellas de {@code nuevo}; devuelve las entradas recalculadas. */
        int rellenar(PerfilProyecto nuevo, PerfilProyecto previo, boolean reutilizable) {
            int recalculados = 0;
            Map<String, PerfilProyecto.ServicioPerfil> srv = new TreeMap<>();
            Map<String, PerfilProyecto.TipoPerfil> tps = new TreeMap<>();
            Map<String, String> huellas = new TreeMap<>();
            for (ModuloEscaneado me : p.modulos) {
                String rel = me.modulo.rel;
                String entorno = nuevo.entorno.get(rel);
                boolean mismoEntorno = previo != null && previo.entorno != null && Objects.equals(previo.entorno.get(rel), entorno);
                if (me.deteccion != null) {
                    for (DetectorServicios.Candidato cand : me.deteccion.servicios) {
                        InfoClase c = cand.clase;
                        if (c == null || srv.containsKey(c.fqn)) {
                            continue;
                        }
                        servicios.put(c.fqn, new Object[]{me, c});
                        AnalisisServicio an = me.analisis(c);
                        List<String> fuentes = fuentesServicio(me, c, an);
                        anotarHuellas(fuentes, huellas);
                        PerfilProyecto.ServicioPerfil previa = previo == null || previo.servicios == null ? null
                                : previo.servicios.get(c.fqn);
                        if (reutilizable && mismoEntorno && previa != null && rel.equals(previa.modulo)
                                && fuentes.equals(previa.fuentes) && mismasHuellas(fuentes, previo)) {
                            srv.put(c.fqn, previa);
                        } else {
                            srv.put(c.fqn, servicio(me, c, an, fuentes));
                            recalculados++;
                            serviciosCambiados = true;
                        }
                    }
                }
                if (me.indice == null) {
                    continue;
                }
                int java = me.versiones == null ? 8 : me.versiones.javaEfectivo();
                for (InfoClase t : me.indice.clasesMain()) {
                    if (tps.containsKey(t.fqn) || srv.containsKey(t.fqn) || !FabricaObjetos.esTipoDeDatos(t, me)) {
                        continue;
                    }
                    List<String> fuentes = fuentesTipo(me, t);
                    anotarHuellas(fuentes, huellas);
                    PerfilProyecto.TipoPerfil previa = previo == null || previo.tipos == null ? null : previo.tipos.get(t.fqn);
                    if (reutilizable && mismoEntorno && previa != null && rel.equals(previa.modulo)
                            && fuentes.equals(previa.fuentes) && mismasHuellas(fuentes, previo)) {
                        tps.put(t.fqn, previa);
                    } else {
                        PerfilProyecto.TipoPerfil tp = tipo(me, t, fuentes, java);
                        if (tp != null) {
                            tps.put(t.fqn, tp);
                            recalculados++;
                        }
                    }
                }
            }
            nuevo.servicios = new LinkedHashMap<>(srv);
            nuevo.tipos = new LinkedHashMap<>(tps);
            nuevo.huellas = new LinkedHashMap<>(huellas);
            return recalculados;
        }

        private boolean mismasHuellas(List<String> fuentes, PerfilProyecto previo) {
            if (previo.huellas == null) {
                return false;
            }
            for (String f : fuentes) {
                String actual = huellaRel(f);
                if (actual == null || actual.isEmpty() || !actual.equals(previo.huellas.get(f))) {
                    return false;
                }
            }
            return true;
        }

        private final Map<String, String> huellaPorRel = new HashMap<>();

        private void anotarHuellas(List<String> fuentes, Map<String, String> destino) {
            for (String f : fuentes) {
                String h = huellaRel(f);
                destino.put(f, h == null ? "" : h);
            }
        }

        private String huellaRel(String rel) {
            return huellaPorRel.get(rel);
        }

        /** Relativa a la raíz con '/', y memoriza su huella (leída del archivo real del escaneo). */
        private String registrar(Path archivo) {
            if (archivo == null) {
                return null;
            }
            String rel = relativa(archivo);
            huellaPorRel.computeIfAbsent(rel, k -> huella(archivo));
            return rel;
        }

        private String huella(Path archivo) {
            return huellasMemo.computeIfAbsent(archivo, a -> {
                try {
                    return Textos.sha256Hex(Files.readAllBytes(a), 16);
                } catch (IOException | RuntimeException e) {
                    return "";
                }
            });
        }

        private String relativa(Path archivo) {
            try {
                Path abs = archivo.toAbsolutePath().normalize();
                Path raiz = p.raiz.toAbsolutePath().normalize();
                if (abs.startsWith(raiz)) {
                    return raiz.relativize(abs).toString().replace('\\', '/');
                }
            } catch (RuntimeException e) {
                // Raíces de distinto tipo: se usa el nombre del archivo.
                System.err.println("Ruta de fuente fuera de la raíz del proyecto: " + e.getClass().getSimpleName());
            }
            Path n = archivo.getFileName();
            return n == null ? archivo.toString().replace('\\', '/') : n.toString();
        }

        private List<String> fuentesServicio(ModuloEscaneado me, InfoClase c, AnalisisServicio an) {
            Set<String> r = new LinkedHashSet<>(fuentesTipo(me, c));
            for (Dependencia d : an.dependencias) {
                InfoClase t = me.indice.get(d.tipoFqn);
                if (t == null) {
                    t = me.indice.resolverClase(d.tipo, c);
                }
                if (t != null && t.archivo != null) {
                    String rel = registrar(t.archivo);
                    if (rel != null) {
                        r.add(rel);
                    }
                }
            }
            return new ArrayList<>(r);
        }

        private List<String> fuentesTipo(ModuloEscaneado me, InfoClase t) {
            Set<String> r = new LinkedHashSet<>();
            InfoClase propia = t.contenedora != null ? raizContenedora(t) : t;
            String rel = registrar(propia.archivo);
            if (rel != null) {
                r.add(rel);
            }
            Set<String> vistos = new HashSet<>();
            InfoClase actual = t;
            for (int n = 0; n < MAX_NIVELES_SUPER && actual != null && !actual.extiende.isEmpty(); n++) {
                InfoClase sup = me.indice.resolverClase(actual.extiende.get(0), actual);
                if (sup == null || !vistos.add(sup.fqn)) {
                    break;
                }
                String s = registrar(sup.archivo);
                if (s != null) {
                    r.add(s);
                }
                actual = sup;
            }
            return new ArrayList<>(r);
        }

        private InfoClase raizContenedora(InfoClase t) {
            InfoClase r = t;
            for (int n = 0; n < 10 && r.contenedora != null; n++) {
                r = r.contenedora;
            }
            return r;
        }

        private PerfilProyecto.ServicioPerfil servicio(ModuloEscaneado me, InfoClase c, AnalisisServicio an, List<String> fuentes) {
            PerfilProyecto.ServicioPerfil s = new PerfilProyecto.ServicioPerfil();
            s.modulo = me.modulo.rel;
            s.archivo = c.archivo == null ? null : relativa(c.archivo);
            s.fuentes = new ArrayList<>(fuentes);
            PerfilProyecto.MontajePerfil m = new PerfilProyecto.MontajePerfil();
            m.modo = an.modo == null ? null : an.modo.name();
            m.via = via(an.dependencias);
            m.constructor = an.constructor == null ? null : an.constructor.firma();
            m.constructorConLogica = an.constructorConLogica ? Boolean.TRUE : null;
            m.postConstruct = an.postConstruct.isEmpty() ? null : new ArrayList<>(an.postConstruct);
            List<String> limpieza = new ArrayList<>();
            for (AnalisisServicio.Limpieza l : an.limpiezas) {
                if (l.sentencia() != null) {
                    limpieza.add(l.sentencia());
                }
            }
            m.limpiezaTearDown = limpieza.isEmpty() ? null : limpieza;
            m.mockStatic = an.permiteMockStatic ? Boolean.TRUE : null;
            m.mockConstruction = an.permiteMockConstruction ? Boolean.TRUE : null;
            s.montaje = m;
            for (Dependencia d : an.dependencias) {
                PerfilProyecto.DependenciaPerfil dp = new PerfilProyecto.DependenciaPerfil();
                dp.nombre = d.nombre;
                dp.tipo = d.tipo;
                dp.tipoFqn = d.tipoFqn;
                dp.obtencion = d.obtencion == null ? null : d.obtencion.name();
                dp.mock = d.esMockDeclarado();
                dp.profundo = d.profundo;
                dp.requiereValor = d.requiereValor;
                dp.tieneValorPorDefecto = d.valorPorDefecto != null;
                dp.real = d.realPorDefecto;
                dp.contenedor = d.contenedor;
                dp.heredadoDe = d.heredadoDe;
                // La nota de @Value con valor por defecto lo incluye: no se guarda (puede ser un secreto).
                dp.nota = d.valorPorDefecto != null
                        ? "@Value con valor por defecto; cámbialo en construccion.argumentos." + d.nombre
                        : LimitesRespuesta.recortar(d.nota, MAX_NOTA);
                s.dependencias.add(dp);
            }
            return s;
        }

        private static String via(List<Dependencia> deps) {
            List<Dependencia.TipoObtencion> relevantes = new ArrayList<>();
            for (Dependencia d : deps) {
                if (d.obtencion != null && d.obtencion != Dependencia.TipoObtencion.ESTATICA
                        && d.obtencion != Dependencia.TipoObtencion.NEW_INTERNO
                        && d.obtencion != Dependencia.TipoObtencion.ELEMENTO_COLECCION) {
                    relevantes.add(d.obtencion);
                }
            }
            if (relevantes.isEmpty()) {
                return "sin_dependencias";
            }
            if (relevantes.stream().allMatch(o -> o == Dependencia.TipoObtencion.CONSTRUCTOR
                    || o == Dependencia.TipoObtencion.VALOR_CONSTRUCTOR)) {
                return "constructor";
            }
            if (relevantes.stream().allMatch(o -> o.name().startsWith("CAMPO_"))) {
                return "campos";
            }
            if (relevantes.stream().allMatch(o -> o == Dependencia.TipoObtencion.SETTER)) {
                return "setters";
            }
            return "mixto";
        }

        private PerfilProyecto.TipoPerfil tipo(ModuloEscaneado me, InfoClase t, List<String> fuentes, int java) {
            FabricaObjetos.Fabrica f = FabricaObjetos.de(t, me.indice, java);
            if (f == null) {
                return null;
            }
            PerfilProyecto.TipoPerfil tp = new PerfilProyecto.TipoPerfil();
            tp.modulo = me.modulo.rel;
            InfoClase propia = t.contenedora != null ? raizContenedora(t) : t;
            tp.archivo = propia.archivo == null ? null : relativa(propia.archivo);
            tp.fuentes = new ArrayList<>(fuentes);
            tp.clase = t.tipo.name();
            tp.modo = f.modo();
            tp.crear = f.crear();
            tp.setters = f.setters().isEmpty() ? null : new ArrayList<>(f.setters());
            List<String> campos = new ArrayList<>();
            for (InfoClase.InfoCampo c : t.campos) {
                if (!c.estatico()) {
                    campos.add(c.tipo() + " " + c.nombre());
                }
            }
            tp.campos = campos.isEmpty() ? null : campos;
            tp.entidad = f.entidad() ? Boolean.TRUE : null;
            return tp;
        }

        /** Stubs típicos agregados de todos los métodos de todos los servicios (≤ 500, solo con retorno conocido). */
        List<PerfilProyecto.StubPerfil> stubs() {
            Map<String, PerfilProyecto.StubPerfil> agregados = new LinkedHashMap<>();
            for (Object[] e : servicios.values()) {
                ModuloEscaneado me = (ModuloEscaneado) e[0];
                InfoClase c = (InfoClase) e[1];
                try {
                    AnalisisServicio an = me.analisis(c);
                    int java = me.versiones == null ? 8 : me.versiones.javaEfectivo();
                    Map<String, LlamadasDependencias.Stub> memo = new HashMap<>();
                    for (InfoMetodo m : c.metodos) {
                        for (LlamadasDependencias.Llamada l : LlamadasDependencias.de(me, c, m)) {
                            Dependencia d = an.dependencia(l.dependencia());
                            if (d == null) {
                                continue;
                            }
                            String clave = tipoClave(d) + "#" + l.metodo() + "#" + l.aridad();
                            LlamadasDependencias.Stub st = memo.computeIfAbsent(clave + "@" + d.nombre,
                                    k -> LlamadasDependencias.stub(d, l, c, me.indice, java));
                            if (st == null) {
                                continue;
                            }
                            PerfilProyecto.StubPerfil sp = agregados.get(clave);
                            if (sp == null) {
                                sp = new PerfilProyecto.StubPerfil();
                                sp.tipo = tipoClave(d);
                                sp.metodo = l.metodo();
                                sp.aridad = l.aridad();
                                sp.retorno = st.retorno();
                                sp.devuelve = st.devuelve();
                                sp.nota = st.nota();
                                agregados.put(clave, sp);
                            }
                            sp.usos++;
                        }
                    }
                } catch (RuntimeException ex) {
                    System.err.println("Perfil: no se pudieron calcular los stubs de un servicio ("
                            + ex.getClass().getSimpleName() + ")");
                }
            }
            List<PerfilProyecto.StubPerfil> r = new ArrayList<>(agregados.values());
            r.sort((a, b) -> b.usos != a.usos ? Integer.compare(b.usos, a.usos)
                    : (a.tipo + "#" + a.metodo + "#" + a.aridad).compareTo(b.tipo + "#" + b.metodo + "#" + b.aridad));
            return r.size() > MAX_STUBS ? new ArrayList<>(r.subList(0, MAX_STUBS)) : r;
        }
    }

    /** Tipo de la dependencia para agrupar: FQN o nombre simple sin genéricos. */
    static String tipoClave(Dependencia d) {
        return d.tipoFqn != null ? d.tipoFqn : Textos.quitarGenericos(d.tipo == null ? "" : d.tipo).trim();
    }
}
