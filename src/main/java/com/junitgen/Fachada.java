package com.junitgen;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.junitgen.escaneo.BuscadorServicios;
import com.junitgen.escaneo.DetalleServicio;
import com.junitgen.escaneo.InfoClase;
import com.junitgen.escaneo.Escaner;
import com.junitgen.escaneo.InformeEscaneo;
import com.junitgen.escaneo.PaqueteTrabajo;
import com.junitgen.escaneo.ProyectoEscaneado;
import com.junitgen.escaneo.SeleccionFunciones;
import com.junitgen.generacion.AlmacenSpec;
import com.junitgen.generacion.EscritorInforme;
import com.junitgen.generacion.GeneradorTests;
import com.junitgen.generacion.ResultadoGeneracion;
import com.junitgen.generacion.RutasSalida;
import com.junitgen.perfil.AlmacenPerfil;
import com.junitgen.perfil.ConstructorPerfil;
import com.junitgen.perfil.PerfilProyecto;
import com.junitgen.perfil.RecetasAprendidas;
import com.junitgen.seguridad.BloqueosSalida;
import com.junitgen.seguridad.EscritorSeguro;
import com.junitgen.seguridad.RutaSegura;
import com.junitgen.seguridad.SeguridadException;
import com.junitgen.spec.ErrorValidacion;
import com.junitgen.spec.LectorSpec;
import com.junitgen.spec.ResultadoValidacion;
import com.junitgen.spec.SeleccionSpec;
import com.junitgen.spec.Spec;
import com.junitgen.spec.ValidadorSpec;
import com.junitgen.util.Json;
import com.junitgen.verificacion.ResultadoTest;
import com.junitgen.verificacion.ResultadoVerificacion;
import com.junitgen.verificacion.VerificadorTests;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Única API de junit-gen, usada por la CLI y por el servidor MCP. */
public class Fachada {

    private final Map<String, ProyectoEscaneado> cache = new ConcurrentHashMap<>();
    private volatile Path ultimaRuta;

    public static String aviso(Path proyecto, Path salida) {
        return EscritorSeguro.aviso(proyecto, salida);
    }

    public Path ultimaRuta() {
        return ultimaRuta;
    }

    /** Escanea (o devuelve de caché) el proyecto. La ruta se valida y se usa solo en lectura. */
    public ProyectoEscaneado proyecto(Path ruta, boolean refrescar) {
        Path real = RutaSegura.validarProyecto(ruta);
        String clave = real.toString().toLowerCase();
        if (refrescar) {
            cache.remove(clave);
        }
        ProyectoEscaneado p = cache.computeIfAbsent(clave, k -> Escaner.escanear(real, null));
        ultimaRuta = real;
        return p;
    }

    public record Escaneo(ProyectoEscaneado proyecto, Path salida, String markdown) {
    }

    public Escaneo escanear(Path ruta, Path salida, InformeEscaneo.Opciones op, boolean refrescar) {
        Path proyectoReal = RutaSegura.validarProyecto(ruta);
        Path salidaReal = salida == null ? null : RutaSegura.validarSalida(proyectoReal, salida);
        ProyectoEscaneado p = proyecto(proyectoReal, refrescar);
        return new Escaneo(p, salidaReal, InformeEscaneo.markdown(p, salidaReal, op));
    }

    /** Escribe escaneo.md y escaneo.json en la salida (CLI scan --salida). */
    public List<Path> escribirEscaneo(Escaneo e) {
        EscritorSeguro esc = new EscritorSeguro(e.proyecto().raiz, e.salida());
        Map<Path, String> plan = new LinkedHashMap<>();
        plan.put(esc.salida().resolve(RutasSalida.ESCANEO_MD), "> " + esc.aviso() + "\n\n" + e.markdown());
        plan.put(esc.salida().resolve(RutasSalida.ESCANEO_JSON), Json.bonito(InformeEscaneo.json(e.proyecto(), esc.salida())) + "\n");
        esc.escribirTodo(plan);
        List<Path> escritos = new ArrayList<>(plan.keySet());
        ConstructorPerfil.Resultado perfil = AlmacenPerfil.sincronizar(esc, e.proyecto());
        if (perfil != null && perfil.cambiado()) {
            escritos.add(AlmacenPerfil.ruta(esc.salida()));
        }
        return List.copyOf(escritos);
    }

    /**
     * Crea o actualiza perfil-proyecto.json en la salida (si es una carpeta existente). null si no hay salida, no
     * existe o falla (el error se registra en stderr y la operación principal sigue).
     */
    public ConstructorPerfil.Resultado sincronizarPerfil(ProyectoEscaneado p, Path salidaReal) {
        if (p == null || salidaReal == null || !Files.isDirectory(salidaReal)) {
            return null;
        }
        try {
            return AlmacenPerfil.sincronizar(new EscritorSeguro(p.raiz, salidaReal), p);
        } catch (RuntimeException e) {
            System.err.println("Perfil: no se pudo sincronizar (" + e.getClass().getSimpleName() + ")");
            return null;
        }
    }

    public ObjectNode detalle(String clase, Path ruta, String modulo, List<String> metodos) {
        return detalle(clase, ruta, modulo, metodos, List.of());
    }

    /**
     * Como {@link #detalle(String, Path, String, List)}; si la clase es ambigua y exactamente un candidato
     * está en {@code modulosPreferidos} (rel de los módulos de trabajo de la sesión), se elige ese.
     */
    public ObjectNode detalle(String clase, Path ruta, String modulo, List<String> metodos, Collection<String> modulosPreferidos) {
        return detalle(clase, ruta, modulo, metodos, modulosPreferidos, null);
    }

    /**
     * Como {@link #detalle(String, Path, String, List, Collection)}; con {@code salida} (nullable) añade por
     * método los casos ya generados en el spec.json de la salida ({@code casosGeneradosEnSalida}).
     */
    public ObjectNode detalle(String clase, Path ruta, String modulo, List<String> metodos, Collection<String> modulosPreferidos,
                              Path salida) {
        return detalle(clase, ruta, modulo, metodos, modulosPreferidos, salida, false);
    }

    /** Como el anterior; con {@code todas}, sin filtro de métodos recorre el 100 % de las funciones (CLI --todas). */
    public ObjectNode detalle(String clase, Path ruta, String modulo, List<String> metodos, Collection<String> modulosPreferidos,
                              Path salida, boolean todas) {
        Localizada l = localizar(clase, ruta, modulo, modulosPreferidos);
        return DetalleServicio.detalle(l.proyecto(), l.clase().modulo(), l.clase().clase(), metodos, salida, todas);
    }

    /**
     * Detalle paginado por presupuesto (MCP): misma resolución de la clase que
     * {@link #detalle(String, Path, String, List, Collection, Path)}, con las opciones de paginación.
     */
    public ObjectNode detalle(String clase, Path ruta, String modulo, Collection<String> modulosPreferidos, Path salida,
                              DetalleServicio.Opciones op) {
        Localizada l = localizar(clase, ruta, modulo, modulosPreferidos);
        if (salida != null && op != null && op.desde == 1 && (op.tipos == null || op.tipos.isEmpty())
                && (op.recetas == null || op.recetas.isEmpty())) {
            try {
                InfoClase c = l.clase().clase();
                op.recetas = RecetasAprendidas.paraContexto(AlmacenPerfil.cargar(salida, l.proyecto()), c.fqn,
                        l.clase().modulo().analisis(c), c);
            } catch (RuntimeException e) {
                System.err.println("Perfil: no se pudieron leer las recetas (" + e.getClass().getSimpleName() + ")");
                op.recetas = new ArrayList<>();
            }
        }
        return DetalleServicio.detalle(l.proyecto(), l.clase().modulo(), l.clase().clase(), salida, op);
    }

    /**
     * Paquete de trabajo del servicio (MCP): misma resolución de la clase que
     * {@link #detalle(String, Path, String, Collection, Path, DetalleServicio.Opciones)}; en la página 1 con plantilla
     * carga las recetas aprendidas del perfil.
     */
    public ObjectNode paquete(String clase, Path ruta, String modulo, Collection<String> modulosPreferidos, Path salida,
                              PaqueteTrabajo.Opciones op) {
        Localizada l = localizar(clase, ruta, modulo, modulosPreferidos);
        if (salida != null && op != null && op.pagina == 1 && op.conPlantilla && (op.recetas == null || op.recetas.isEmpty())) {
            try {
                InfoClase c = l.clase().clase();
                op.recetas = RecetasAprendidas.paraContexto(AlmacenPerfil.cargar(salida, l.proyecto()), c.fqn,
                        l.clase().modulo().analisis(c), c);
            } catch (RuntimeException e) {
                System.err.println("Perfil: no se pudieron leer las recetas (" + e.getClass().getSimpleName() + ")");
                op.recetas = new ArrayList<>();
            }
        }
        return PaqueteTrabajo.pagina(l.proyecto(), l.clase().modulo(), l.clase().clase(), salida,
                op == null ? new PaqueteTrabajo.Opciones() : op);
    }

    private record Localizada(ProyectoEscaneado proyecto, ProyectoEscaneado.Encontrada clase) {
    }

    /** Resuelve la clase (FQN o nombre simple) con la misma lógica que detalle_servicio. */
    public ProyectoEscaneado.Encontrada localizarClase(String clase, Path ruta, String modulo, Collection<String> modulosPreferidos) {
        return localizar(clase, ruta, modulo, modulosPreferidos).clase();
    }

    /**
     * Funciones candidatas de un servicio en markdown (cabecera, todas las líneas y pie), para la CLI. La
     * selección mostrada es la guardada (origen ia) en el spec.json de {@code salida} o la automática.
     */
    public String candidatas(String clase, Path ruta, String modulo, Path salida) {
        Localizada l = localizar(clase, ruta, modulo, List.of());
        Path salidaReal = salida == null ? null : RutaSegura.validarSalida(l.proyecto().raiz, salida);
        Map<String, SeleccionSpec> guardadas = AlmacenSpec.selecciones(salidaReal);
        SeleccionFunciones sel = SeleccionFunciones.de(l.clase().modulo(), l.clase().clase(), false,
                guardadas.get(l.clase().clase().fqn));
        StringBuilder sb = new StringBuilder(sel.cabeceraCandidatas());
        sel.lineasCandidatas().forEach(sb::append);
        sb.append(sel.pieCandidatas());
        if (!SeleccionFunciones.ORIGEN_JAVA.equals(sel.origen())) {
            sb.append("\nSelección guardada (origen ").append(sel.origen()).append("): ")
                    .append(sel.seleccionados().stream().map(m -> m.clave()).toList()).append('\n');
        }
        return sb.toString();
    }

    private Localizada localizar(String clase, Path ruta, String modulo, Collection<String> modulosPreferidos) {
        Path r = ruta != null ? ruta : ultimaRuta;
        if (r == null) {
            throw new IllegalArgumentException("No hay ningún escaneo previo en esta sesión: llama antes a escanear_proyecto o indica 'ruta'.");
        }
        ProyectoEscaneado p = proyecto(r, false);
        List<ProyectoEscaneado.Encontrada> cands = p.buscarClase(clase, modulo);
        if (cands.isEmpty() || cands.stream().allMatch(e -> e.clase().tipo == InfoClase.Tipo.INTERFAZ)) {
            BuscadorServicios.Resultado b = BuscadorServicios.buscar(p, clase, modulo);
            if (b.resuelto()) {
                cands = b.objetivos();
            } else if (b.ambiguo()) {
                cands = b.candidatos();
            }
        }
        if (cands.isEmpty()) {
            throw new IllegalArgumentException("No se encuentra la clase '" + clase + "' en " + p.raiz + ".");
        }
        if (cands.size() > 1 && modulosPreferidos != null && !modulosPreferidos.isEmpty()) {
            List<ProyectoEscaneado.Encontrada> preferidos = cands.stream()
                    .filter(e -> modulosPreferidos.contains(e.modulo().modulo.rel)).toList();
            if (preferidos.size() == 1) {
                cands = preferidos;
            }
        }
        if (cands.size() > 1) {
            StringBuilder sb = new StringBuilder("La clase '" + clase + "' es ambigua; indica el FQN o el módulo. Candidatos: ");
            for (ProyectoEscaneado.Encontrada e : cands) {
                sb.append(e.modulo().modulo.nombre()).append(':').append(e.clase().fqn).append("; ");
            }
            throw new IllegalArgumentException(sb.toString());
        }
        return new Localizada(p, cands.get(0));
    }

    /** Lee el spec desde un objeto/cadena JSON o desde un archivo. */
    public LectorSpec.Lectura leerSpec(JsonNode spec, Path rutaSpec) {
        if (rutaSpec != null) {
            return LectorSpec.leerArchivo(rutaSpec);
        }
        return LectorSpec.leer(spec);
    }

    private Path rutaProyecto(Spec spec, Path ruta) {
        if (ruta != null) {
            return ruta;
        }
        if (spec != null && spec.proyecto != null && spec.proyecto.ruta != null && !spec.proyecto.ruta.isBlank()) {
            return RutaSegura.ruta(spec.proyecto.ruta);
        }
        if (ultimaRuta != null) {
            return ultimaRuta;
        }
        throw new IllegalArgumentException("No se sabe qué proyecto usar: indica la ruta del proyecto o proyecto.ruta en el spec.");
    }

    public ResultadoValidacion validar(JsonNode spec, Path rutaSpec, Path ruta, Path salida) {
        return validar(spec, rutaSpec, ruta, salida, false);
    }

    /** Como {@link #validar(JsonNode, Path, Path, Path)}; con {@code todas}, el 100 % de las funciones es válido. */
    public ResultadoValidacion validar(JsonNode spec, Path rutaSpec, Path ruta, Path salida, boolean todas) {
        return validar(spec, rutaSpec, ruta, salida, todas, null);
    }

    /**
     * Como {@link #validar(JsonNode, Path, Path, Path, boolean)}; {@code selecciones} = selecciones de la sesión
     * MCP (FQN → ia/usuario); null = modo CLI (la {@code seleccion} del spec se valida).
     */
    public ResultadoValidacion validar(JsonNode spec, Path rutaSpec, Path ruta, Path salida, boolean todas,
                                       Map<String, SeleccionSpec> selecciones) {
        LectorSpec.Lectura l = leerSpec(spec, rutaSpec);
        if (!l.ok()) {
            ResultadoValidacion r = new ResultadoValidacion();
            r.errores.addAll(l.errores());
            r.valido = false;
            return r;
        }
        Path proyecto = RutaSegura.validarProyecto(rutaProyecto(l.spec(), ruta));
        Path salidaReal = salida == null ? null : RutaSegura.validarSalida(proyecto, salida);
        ProyectoEscaneado p = proyecto(proyecto, false);
        return validarConRecetas(l, spec, rutaSpec, p, salidaReal, todas, selecciones, new ArrayList<>());
    }

    /**
     * Valida el spec fusionando antes las recetas aprendidas de perfil-proyecto.json (si hay perfil en la salida). Si
     * con las recetas el spec es inválido, se vuelve a leer el original y se valida sin ellas: las recetas nunca
     * convierten un spec válido en inválido. {@code aplicadasOut} recibe las recetas realmente aplicadas.
     */
    private ResultadoValidacion validarConRecetas(LectorSpec.Lectura l, JsonNode specJson, Path rutaSpec, ProyectoEscaneado p,
                                                  Path salidaReal, boolean todas, Map<String, SeleccionSpec> selecciones,
                                                  List<RecetasAprendidas.Aplicada> aplicadasOut) {
        Spec spec = l.spec();
        List<RecetasAprendidas.Aplicada> aplicadas = List.of();
        boolean alterado = false;
        PerfilProyecto perfil = salidaReal == null ? null : AlmacenPerfil.cargar(salidaReal, p);
        if (perfil != null) {
            try {
                aplicadas = RecetasAprendidas.aplicar(spec, p, perfil);
            } catch (RuntimeException e) {
                System.err.println("Perfil: no se pudieron aplicar las recetas (" + e.getClass().getSimpleName() + ")");
                aplicadas = List.of();
                alterado = true;
            }
        }
        if (alterado) {
            LectorSpec.Lectura otra = leerSpec(specJson, rutaSpec);
            if (otra.ok()) {
                spec = otra.spec();
            }
        }
        ResultadoValidacion v = ValidadorSpec.validar(spec, p, salidaReal, todas, selecciones);
        if (aplicadas.isEmpty() || v.valido) {
            for (RecetasAprendidas.Aplicada a : aplicadas) {
                v.avisos.add(ErrorValidacion.aviso(rutaConstruccion(v, a.servicio()), "RECETA_APLICADA", a.descripcion(),
                        "Ya aplicada: no la repitas; un valor explícito en construccion.argumentos la sustituye."));
            }
            aplicadasOut.addAll(aplicadas);
            return v;
        }
        LectorSpec.Lectura original = leerSpec(specJson, rutaSpec);
        if (!original.ok()) {
            return v;
        }
        ResultadoValidacion sin = ValidadorSpec.validar(original.spec(), p, salidaReal, todas, selecciones);
        if (sin.valido) {
            sin.avisos.add(ErrorValidacion.aviso("$.servicios", "RECETAS_NO_APLICADAS",
                    "Las recetas aprendidas de " + RutasSalida.PERFIL + " hacían inválido el spec: se ha usado tal cual.",
                    null));
        }
        return sin;
    }

    private static String rutaConstruccion(ResultadoValidacion v, String fqn) {
        for (ResultadoValidacion.ServicioValidado sv : v.servicios) {
            if (sv.clase != null && fqn.equals(sv.clase.fqn)) {
                return "$.servicios[" + sv.indice + "].construccion";
            }
        }
        return "$.servicios";
    }

    public record Generacion(ResultadoGeneracion resultado, ResultadoValidacion validacion, EscritorSeguro escritor) {
    }

    public Generacion generar(JsonNode spec, Path rutaSpec, Path salida, Path ruta, GeneradorTests.Opciones op) {
        LectorSpec.Lectura l = leerSpec(spec, rutaSpec);
        if (!l.ok()) {
            ResultadoGeneracion g = new ResultadoGeneracion();
            g.errores.addAll(l.errores());
            ResultadoValidacion v = new ResultadoValidacion();
            v.errores.addAll(l.errores());
            return new Generacion(g, v, null);
        }
        Path proyecto = rutaProyecto(l.spec(), ruta);
        EscritorSeguro esc = new EscritorSeguro(proyecto, salida);
        ProyectoEscaneado p = proyecto(esc.proyecto(), false);
        return BloqueosSalida.conEscritura(esc.salida(), () -> {
            List<RecetasAprendidas.Aplicada> aplicadas = new ArrayList<>();
            ResultadoValidacion v = validarConRecetas(l, spec, rutaSpec, p, esc.salida(), op != null && op.todas,
                    op == null ? null : op.selecciones, aplicadas);
            ResultadoGeneracion g = GeneradorTests.generar(v, esc, op);
            if (g.errores.isEmpty()) {
                AlmacenPerfil.sincronizar(esc, p);
                anotarRecetasEnPrueba(esc, p, g, aplicadas);
            }
            return new Generacion(g, v, esc);
        });
    }

    /** Anota en el perfil las recetas de tipo aplicadas a servicios con casos generados o remontados (se confirman al verificar). */
    private static void anotarRecetasEnPrueba(EscritorSeguro esc, ProyectoEscaneado p, ResultadoGeneracion g,
                                              List<RecetasAprendidas.Aplicada> aplicadas) {
        try {
            Set<String> tocados = new HashSet<>(g.remontados.keySet());
            for (Map<String, String> m : g.generados) {
                if (m.get("servicio") != null) {
                    tocados.add(m.get("servicio"));
                }
            }
            Map<String, List<String>> ids = new LinkedHashMap<>();
            for (RecetasAprendidas.Aplicada a : aplicadas) {
                boolean deTipo = PerfilProyecto.Receta.ARGUMENTO_TIPO.equals(a.forma())
                        || PerfilProyecto.Receta.PROFUNDO_TIPO.equals(a.forma());
                if (deTipo && tocados.contains(a.servicio())) {
                    List<String> l = ids.computeIfAbsent(a.servicio(), k -> new ArrayList<>());
                    if (!l.contains(a.recetaId())) {
                        l.add(a.recetaId());
                    }
                }
            }
            if (ids.isEmpty()) {
                return;
            }
            Map.Entry<Path, String> perfil = AlmacenPerfil.modificar(esc.salida(), p, x -> RecetasAprendidas.anotarEnPrueba(x, ids));
            if (perfil != null) {
                esc.escribir(perfil.getKey(), perfil.getValue());
            }
        } catch (RuntimeException e) {
            System.err.println("Perfil: no se pudieron anotar las recetas en prueba (" + e.getClass().getSimpleName() + ")");
        }
    }

    public ResultadoVerificacion ejecutar(Path proyecto, Path salida, String modulo, List<String> clases) {
        return ejecutar(proyecto, salida, modulo, clases, null);
    }

    /** Como {@link #ejecutar(Path, Path, String, List)}, limitando los resultados a esos ids de caso (null = todos). */
    public ResultadoVerificacion ejecutar(Path proyecto, Path salida, String modulo, List<String> clases, Collection<String> casos) {
        EscritorSeguro esc = new EscritorSeguro(proyecto, salida);
        ProyectoEscaneado p = proyecto(esc.proyecto(), false);
        return VerificadorTests.verificar(p, esc, modulo, clases, casos);
    }

    /**
     * Verifica solo los tests generados de un servicio ({@code casos} = sus ids de caso): compila su archivo de
     * test y ejecuta sus clases reutilizando la preparación cacheada del módulo.
     */
    public ResultadoVerificacion verificarServicio(Path proyecto, Path salida, String fqnServicio, Collection<String> casos) {
        return verificarServicio(proyecto, salida, fqnServicio, casos, null);
    }

    /**
     * Como {@link #verificarServicio(Path, Path, String, Collection)}; solo los casos de {@code contarIntentos}
     * suman un intento de montaje si fallan (null = todos).
     */
    public ResultadoVerificacion verificarServicio(Path proyecto, Path salida, String fqnServicio, Collection<String> casos,
                                                   Collection<String> contarIntentos) {
        EscritorSeguro esc = new EscritorSeguro(proyecto, salida);
        ProyectoEscaneado p = proyecto(esc.proyecto(), false);
        return VerificadorTests.verificarServicio(p, esc, fqnServicio, casos, contarIntentos);
    }

    /** Proyecto registrado en spec.json de la salida (para resolver_fallo). */
    public static Path proyectoDeSalida(Path salida) {
        Path s = RutaSegura.normalizar(salida);
        Path spec = s.resolve(RutasSalida.SPEC);
        if (!Files.isRegularFile(spec)) {
            throw new IllegalArgumentException("No existe " + spec + ": no hay nada generado en esa carpeta.");
        }
        LectorSpec.Lectura l = LectorSpec.leerArchivo(spec);
        if (!l.ok() || l.spec().proyecto == null || l.spec().proyecto.ruta == null) {
            throw new IllegalArgumentException("spec.json de " + s + " no indica proyecto.ruta o está corrupto: "
                    + (l.errores().isEmpty() ? "" : l.errores().get(0).mensaje));
        }
        return RutaSegura.ruta(l.spec().proyecto.ruta);
    }

    public Map<String, Object> resolverFallo(Path salida, String caso, String decision, boolean confirmado) {
        Path proyecto = proyectoDeSalida(salida);
        EscritorSeguro esc = new EscritorSeguro(proyecto, salida);
        return BloqueosSalida.conEscritura(esc.salida(), () -> VerificadorTests.resolverFallo(esc, caso, decision, confirmado));
    }

    /**
     * Aparta casos que no compilan o no montan y que no se han podido arreglar (montaje_fallido; bloque
     * comentado en su test, nunca borrado; anotado en informe.md). Escribe solo en la salida.
     */
    public Map<String, Object> apartarCasos(Path proyecto, Path salida, Map<String, ResultadoTest> casos) {
        EscritorSeguro esc = new EscritorSeguro(proyecto, salida);
        return BloqueosSalida.conEscritura(esc.salida(), () -> VerificadorTests.apartarCasos(esc, casos));
    }

    /** Añade a informe.md la sección «Arreglos al final» (solo escribe en la salida). Devuelve la ruta del informe. */
    public String anotarArreglos(Path proyecto, Path salida, List<VerificadorTests.ArregloInforme> filas) {
        return anotarArreglos(proyecto, salida, filas, List.of());
    }

    /** Como {@link #anotarArreglos(Path, Path, List)}, con los imports corregidos automáticamente durante la tanda. */
    public String anotarArreglos(Path proyecto, Path salida, List<VerificadorTests.ArregloInforme> filas, List<String> importsReparados) {
        EscritorSeguro esc = new EscritorSeguro(proyecto, salida);
        return BloqueosSalida.conEscritura(esc.salida(), () -> VerificadorTests.anotarArreglos(esc, filas, importsReparados));
    }

    /** Título de la sección de medición de informe.md. */
    public static final String TITULO_MEDICION = "Medición de la tanda";

    /** Añade a informe.md la sección «Medición de la tanda» (solo escribe en la salida). Devuelve la ruta del informe. */
    public String anotarMedicion(Path proyecto, Path salida, String cuerpoMd) {
        EscritorSeguro esc = new EscritorSeguro(proyecto, salida);
        return BloqueosSalida.conEscritura(esc.salida(), () -> {
            Path rutaInforme = esc.salida().resolve(RutasSalida.INFORME);
            Map<Path, String> plan = new LinkedHashMap<>();
            plan.put(rutaInforme, EscritorInforme.anadir(esc.salida(), esc.aviso(),
                    EscritorInforme.seccionTexto(TITULO_MEDICION, cuerpoMd == null ? "" : cuerpoMd)));
            esc.escribirTodo(plan);
            return rutaInforme.toString();
        });
    }

    /** registrar_analisis_ia: registra los veredictos de la IA sobre casos con fallo de aserción. */
    public Map<String, Object> registrarAnalisisIa(Path salida, List<VerificadorTests.AnalisisIa> analisis) {
        Path proyecto = proyectoDeSalida(salida);
        EscritorSeguro esc = new EscritorSeguro(proyecto, salida);
        return BloqueosSalida.conEscritura(esc.salida(), () -> VerificadorTests.registrarAnalisisIa(esc, analisis));
    }

    public static String resumenErrores(List<ErrorValidacion> errores) {
        StringBuilder sb = new StringBuilder();
        for (ErrorValidacion e : errores) {
            sb.append("- ").append(e).append('\n');
        }
        return sb.toString();
    }

    /** Lanza si la salida no es válida respecto al proyecto, sin crear nada. */
    public static void validarRutas(Path proyecto, Path salida) {
        Path p = RutaSegura.validarProyecto(proyecto);
        if (salida != null) {
            RutaSegura.validarSalida(p, salida);
        }
    }

    public static boolean esRechazoSeguridad(Throwable t) {
        return t instanceof SeguridadException;
    }
}
