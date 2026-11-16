package com.junitgen.cli;

import com.junitgen.Fachada;
import com.junitgen.Version;
import com.junitgen.config.CargadorConfig;
import com.junitgen.config.ConfigProyecto;
import com.junitgen.escaneo.InformeEscaneo;
import com.junitgen.generacion.GeneradorTests;
import com.junitgen.generacion.ResultadoGeneracion;
import com.junitgen.seguridad.RutaSegura;
import com.junitgen.seguridad.SeguridadException;
import com.junitgen.spec.ResultadoValidacion;
import com.junitgen.util.Json;
import com.junitgen.util.Recursos;
import com.junitgen.verificacion.ClasificadorFallos;
import com.junitgen.verificacion.ResultadoVerificacion;

import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Modo línea de comandos. Códigos de salida: 0 correcto, 1 errores de validación o tests fallidos,
 * 2 uso incorrecto, 3 rechazo de seguridad.
 */
public class CliRunner {

    public static final int OK = 0;
    public static final int FALLO = 1;
    public static final int USO = 2;
    public static final int SEGURIDAD = 3;

    private final PrintStream out;
    private final PrintStream err;
    private final Fachada fachada = new Fachada();

    public CliRunner() {
        this(System.out, System.err);
    }

    public CliRunner(PrintStream out, PrintStream err) {
        this.out = out;
        this.err = err;
    }

    public static String ayuda() {
        return """
                junit-gen %s — generador de tests unitarios JUnit asistido por IA (solo lectura sobre el proyecto)

                Uso: junit-gen <comando> [opciones]

                  mcp                      Servidor MCP por stdio (para Copilot y otros clientes MCP).
                  scan     --proyecto <ruta> [--salida <carpeta>] [--modulo <m>] [--json]
                           [--filtro <texto>] [--sin-cubrir] [--pagina N] [--tam N]
                  functions --proyecto <ruta> --clase <FQN> [--modulo <m>] [--salida <carpeta>]
                           Funciones candidatas del servicio (sin cuerpos) para que la IA elija por importancia
                           de negocio dentro del cupo; la elección va en servicios[].seleccion del spec.
                  detail   --proyecto <ruta> --clase <FQN> [--modulo <m>] [--metodos a,b] [--todas]
                  validate --proyecto <ruta> --spec <spec.json> [--salida <carpeta>] [--todas]
                  generate --spec <spec.json> --salida <carpeta> [--proyecto <ruta>] [--todas]
                           [--reemplazar id1,id2] [--eliminar id1,id2 --confirmar] [--probar | --no-probar]
                           --todas: 100 %% de las funciones públicas. Sin él, las de servicios[].seleccion del
                           spec (origen ia, dentro del cupo: porcentajeFunciones de junit-gen.yml, 60 %% de las no
                           triviales por defecto; origen usuario: las que nombró el usuario); sin seleccion, la
                           guardada (ia) en spec.json o la automática de junit-gen (top 60 %% por puntuación).
                           Por defecto prueba los tests generados en esa llamada, servicio a servicio (en
                           <salida>/.junit-gen-tmp, sin tocar el proyecto); --no-probar lo evita. La clave verificacion de junit-gen.yml
                           (siempre|preguntar|nunca) cambia ese valor por defecto.
                  verify   --proyecto <ruta> --salida <carpeta> [--modulo <m>] [--clase <c1,c2>]
                  resolve  --salida <carpeta> --caso <id> --decision bug|ajustar|eliminar [--confirmar]
                           (ajustar y eliminar requieren --confirmar: solo tras la respuesta explícita del usuario)
                  prompt                   Muestra el prompt para usar junit-gen sin MCP.
                  schema                   Muestra el esquema JSON del spec.
                  --help | --version

                Códigos de salida: 0 correcto, 1 errores de validación o tests fallidos, 2 uso incorrecto,
                3 rechazo de seguridad. El proyecto nunca se modifica: solo se escribe en la carpeta de salida.
                """.formatted(Version.version());
    }

    public int ejecutar(String[] args) {
        if (args.length == 0 || args[0].equals("--help") || args[0].equals("-h") || args[0].equals("help")) {
            out.println(ayuda());
            return args.length == 0 ? USO : OK;
        }
        if (args[0].equals("--version") || args[0].equals("version")) {
            out.println("junit-gen " + Version.version());
            return OK;
        }
        try {
            return switch (args[0]) {
                case "scan" -> scan(Argumentos.parsear(args, 1, Set.of("proyecto", "salida", "modulo", "filtro", "pagina", "tam"),
                        Set.of("json", "sin-cubrir")));
                case "functions" -> functions(Argumentos.parsear(args, 1, Set.of("proyecto", "clase", "modulo", "salida"), Set.of()));
                case "detail" -> detail(Argumentos.parsear(args, 1, Set.of("proyecto", "clase", "modulo", "metodos"), Set.of("todas")));
                case "validate" -> validate(Argumentos.parsear(args, 1, Set.of("proyecto", "spec", "salida"), Set.of("todas")));
                case "generate" -> generate(Argumentos.parsear(args, 1, Set.of("spec", "salida", "proyecto", "reemplazar", "eliminar"),
                        Set.of("confirmar", "probar", "no-probar", "todas")));
                case "verify" -> verify(Argumentos.parsear(args, 1, Set.of("proyecto", "salida", "modulo", "clase"), Set.of()));
                case "resolve" -> resolve(Argumentos.parsear(args, 1, Set.of("salida", "caso", "decision"), Set.of("confirmar")));
                case "prompt" -> {
                    out.println(Recursos.promptSinMcp());
                    yield OK;
                }
                case "schema" -> {
                    out.println(Recursos.esquemaSpecTexto());
                    yield OK;
                }
                default -> {
                    err.println("Comando desconocido: " + args[0]);
                    err.println(ayuda());
                    yield USO;
                }
            };
        } catch (Argumentos.ErrorUso e) {
            err.println("Uso incorrecto: " + e.getMessage());
            err.println("Ejecuta 'junit-gen --help' para ver las opciones.");
            return USO;
        } catch (SeguridadException e) {
            err.println("Rechazo de seguridad: " + e.getMessage());
            return SEGURIDAD;
        } catch (IllegalArgumentException | IllegalStateException e) {
            err.println("Error: " + e.getMessage());
            return FALLO;
        }
    }

    private Path ruta(String s) {
        return s == null ? null : RutaSegura.ruta(s);
    }

    private void aviso(Path proyecto, Path salida) {
        out.println(Fachada.aviso(proyecto, salida));
        out.println();
    }

    private int scan(Argumentos a) {
        Path proyecto = ruta(a.obligatorio("proyecto"));
        Path salida = ruta(a.valor("salida"));
        aviso(proyecto, salida);
        InformeEscaneo.Opciones op = new InformeEscaneo.Opciones();
        op.modulo = a.valor("modulo");
        op.filtro = a.valor("filtro");
        op.soloSinCubrir = a.bandera("sin-cubrir");
        op.pagina = a.entero("pagina", 1);
        op.tamPagina = a.entero("tam", Integer.MAX_VALUE);
        Fachada.Escaneo e = fachada.escanear(proyecto, salida, op, false);
        if (a.bandera("json")) {
            out.println(Json.bonito(InformeEscaneo.json(e.proyecto(), e.salida())));
        } else {
            out.println(e.markdown());
        }
        if (salida != null) {
            InformeEscaneo.Opciones completo = new InformeEscaneo.Opciones();
            completo.tamPagina = Integer.MAX_VALUE;
            Fachada.Escaneo todo = new Fachada.Escaneo(e.proyecto(), e.salida(), InformeEscaneo.markdown(e.proyecto(), e.salida(), completo));
            List<Path> creados = fachada.escribirEscaneo(todo);
            out.println();
            out.println("Archivos creados:");
            creados.forEach(p -> out.println("  " + p));
        }
        return OK;
    }

    private int functions(Argumentos a) {
        Path proyecto = ruta(a.obligatorio("proyecto"));
        Path salida = ruta(a.valor("salida"));
        aviso(proyecto, salida);
        out.println(fachada.candidatas(a.obligatorio("clase"), proyecto, a.valor("modulo"), salida));
        return OK;
    }

    private int detail(Argumentos a) {
        Path proyecto = ruta(a.obligatorio("proyecto"));
        aviso(proyecto, null);
        out.println(Json.bonito(fachada.detalle(a.obligatorio("clase"), proyecto, a.valor("modulo"), a.lista("metodos"), null, null,
                a.bandera("todas"))));
        return OK;
    }

    private int validate(Argumentos a) {
        Path proyecto = ruta(a.obligatorio("proyecto"));
        Path salida = ruta(a.valor("salida"));
        aviso(proyecto, null);
        ResultadoValidacion r = fachada.validar(null, ruta(a.obligatorio("spec")), proyecto, salida, a.bandera("todas"));
        out.println(Json.bonito(r));
        return r.valido ? OK : FALLO;
    }

    private int generate(Argumentos a) {
        Path salida = ruta(a.obligatorio("salida"));
        Path spec = ruta(a.obligatorio("spec"));
        Path proyecto = ruta(a.valor("proyecto"));
        if (a.bandera("probar") && a.bandera("no-probar")) {
            throw new Argumentos.ErrorUso("--probar y --no-probar son incompatibles.");
        }
        Path proyectoAviso = proyecto;
        if (proyectoAviso == null) {
            var l = fachada.leerSpec(null, spec);
            if (l.ok() && l.spec().proyecto != null && l.spec().proyecto.ruta != null) {
                proyectoAviso = ruta(l.spec().proyecto.ruta);
            }
        }
        aviso(proyectoAviso == null ? Path.of("(proyecto del spec)") : proyectoAviso, salida);
        GeneradorTests.Opciones op = new GeneradorTests.Opciones();
        op.reemplazar = new LinkedHashSet<>(a.lista("reemplazar"));
        op.eliminar = new LinkedHashSet<>(a.lista("eliminar"));
        op.confirmado = a.bandera("confirmar");
        op.todas = a.bandera("todas");
        Fachada.Generacion g = fachada.generar(null, spec, salida, proyecto, op);
        ResultadoGeneracion r = g.resultado();
        if (!r.errores.isEmpty()) {
            out.println(Json.bonito(r));
            return FALLO;
        }
        boolean hayTests = !r.archivosCreados.isEmpty() || !r.archivosAmpliados.isEmpty();
        boolean probar = a.bandera("probar");
        boolean noProbar = a.bandera("no-probar");
        if (!probar && !noProbar && hayTests && g.escritor() != null) {
            // Sin flags: decide la clave verificacion de junit-gen.yml (por defecto siempre).
            ConfigProyecto.ModoVerificacion modo = CargadorConfig.modoVerificacion(g.escritor().proyecto());
            if (modo == ConfigProyecto.ModoVerificacion.SIEMPRE) {
                probar = true;
            } else if (modo == ConfigProyecto.ModoVerificacion.NUNCA) {
                noProbar = true;
            } else if (System.console() != null) {
                probar = preguntarProbar();
            }
        }
        r.verificacion.put("decision", !hayTests ? "descartar" : probar ? "probar" : noProbar ? "descartar" : "pendiente");
        out.println(Json.bonito(r));
        if (probar && hayTests) {
            // Solo se prueba lo generado en esta llamada, servicio a servicio (preparación del módulo cacheada).
            Map<String, List<String>> porServicio = new LinkedHashMap<>();
            for (Map<String, String> m : r.generados) {
                if (m.get("servicio") != null && m.get("caso") != null) {
                    porServicio.computeIfAbsent(m.get("servicio"), k -> new ArrayList<>()).add(m.get("caso"));
                }
            }
            // Servicios remontados: también se vuelven a probar sus casos existentes.
            for (Map.Entry<String, List<String>> e : r.remontados.entrySet()) {
                List<String> ids = porServicio.computeIfAbsent(e.getKey(), k -> new ArrayList<>());
                for (String id : e.getValue()) {
                    if (!ids.contains(id)) {
                        ids.add(id);
                    }
                }
            }
            boolean todoPasa = true;
            for (Map.Entry<String, List<String>> e : porServicio.entrySet()) {
                out.println();
                ResultadoVerificacion v = fachada.verificarServicio(g.escritor().proyecto(), g.escritor().salida(), e.getKey(),
                        e.getValue());
                out.println(Json.bonito(v));
                todoPasa &= v.todoPasa();
            }
            return todoPasa ? OK : FALLO;
        }
        return OK;
    }

    private boolean preguntarProbar() {
        Console c = System.console();
        out.print("¿Probar tests? [p]robar / [d]escartar: ");
        out.flush();
        String r = c != null ? c.readLine() : leerLinea();
        return r != null && r.trim().toLowerCase().startsWith("p");
    }

    private static String leerLinea() {
        try {
            return new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)).readLine();
        } catch (IOException e) {
            return null;
        }
    }

    private int verify(Argumentos a) {
        Path proyecto = ruta(a.obligatorio("proyecto"));
        Path salida = ruta(a.obligatorio("salida"));
        aviso(proyecto, salida);
        ResultadoVerificacion v = fachada.ejecutar(proyecto, salida, a.valor("modulo"), a.lista("clase"));
        out.println(Json.bonito(v));
        boolean ok = !v.resultados.isEmpty() && v.resultados.stream().allMatch(t -> ClasificadorFallos.PASA.equals(t.estado));
        return ok ? OK : FALLO;
    }

    private int resolve(Argumentos a) {
        Path salida = ruta(a.obligatorio("salida"));
        String decision = a.obligatorio("decision");
        if (!Set.of("bug", "ajustar", "eliminar").contains(decision)) {
            throw new Argumentos.ErrorUso("--decision debe ser bug, ajustar o eliminar.");
        }
        Path proyecto;
        try {
            proyecto = Fachada.proyectoDeSalida(salida);
        } catch (IllegalArgumentException e) {
            proyecto = Path.of("(proyecto de spec.json)");
        }
        aviso(proyecto, salida);
        Map<String, Object> r = fachada.resolverFallo(salida, a.obligatorio("caso"), decision, a.bandera("confirmar"));
        out.println(Json.bonito(r));
        return OK;
    }
}
