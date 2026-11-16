package com.junitgen.verificacion;

import com.junitgen.duplicados.ExtractorHuellasTests;
import com.junitgen.escaneo.InfoClase;
import com.junitgen.escaneo.ModuloEscaneado;
import com.junitgen.escaneo.ProyectoEscaneado;
import com.junitgen.generacion.CopiaAmpliada;
import com.junitgen.seguridad.EscritorSeguro;
import com.junitgen.seguridad.GuardaIntegridad;
import com.junitgen.seguridad.ProcesoSeguro;
import com.junitgen.util.LimitesRespuesta;
import com.junitgen.util.Textos;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Comprueba en {@code <salida>/.junit-gen-tmp} que la copia ampliada de un test del proyecto es segura: el test del
 * usuario compila y se ejecuta solo (línea base) y, con los casos nuevos, la copia compila y todo test original que
 * pasaba solo sigue pasando. El proyecto solo se lee y se vigila con {@link GuardaIntegridad}.
 */
public final class VerificadorMezcla {

    /** Tiempo máximo de cada ejecución del test del usuario (solo o en la copia). */
    static final Duration TIMEOUT = Duration.ofMinutes(3);
    static final String CARPETA = "mezcla";

    private VerificadorMezcla() {
    }

    public static CopiaAmpliada.Veredicto comprobar(ProyectoEscaneado p, EscritorSeguro esc, CopiaAmpliada.Pendiente pend) {
        ModuloEscaneado me = pend.modulo();
        String simple = Textos.nombreSimple(pend.fqnTest());
        Map<String, GuardaIntegridad.Entrada> antes = GuardaIntegridad.instantanea(me.modulo.dir, false);
        CopiaAmpliada.Veredicto v;
        try {
            v = comprobarInterno(p, esc, me, pend, simple);
        } catch (RuntimeException e) {
            v = negativo("la comprobación se interrumpió: " + Textos.recortar(String.valueOf(e.getMessage()), 200));
        }
        List<String> dif = GuardaIntegridad.comparar(antes, GuardaIntegridad.instantanea(me.modulo.dir, false));
        if (!dif.isEmpty()) {
            System.err.println("junit-gen: ATENCIÓN: el proyecto cambió mientras se comprobaba la copia ampliada de " + simple + ": "
                    + LimitesRespuesta.listaCorta(dif, 5));
            v = negativo("el proyecto cambió mientras se ejecutaba tu test en la carpeta temporal (revisa: "
                    + Textos.recortar(String.join("; ", dif.subList(0, Math.min(3, dif.size()))), 200) + ")");
        }
        return v;
    }

    private static CopiaAmpliada.Veredicto comprobarInterno(ProyectoEscaneado p, EscritorSeguro esc, ModuloEscaneado me,
                                                            CopiaAmpliada.Pendiente pend, String simple) {
        PreparadorModulos.Preparacion prep;
        try {
            prep = PreparadorModulos.obtener(p, esc, me);
        } catch (RuntimeException e) {
            return negativo("no se pudo preparar el módulo para comprobarla: " + Textos.recortar(String.valueOf(e.getMessage()), 200));
        }
        Path dir = PreparadorModulos.dirModulo(esc, me).resolve(CARPETA).resolve(simple + "-" + Textos.sha256Hex(pend.fqnTest(), 8));
        esc.borrarTemporal(dir);
        List<Path> cp = PreparadorModulos.classpathCompilacion(prep, me);
        // Línea base: el test del usuario solo.
        ContextoVerificacion base = contexto(p, esc, me, prep, dir.resolve("base"));
        Path clasesBase = compilar(base, simple, pend.nucleo(), "javac-mezcla-base", base.tmp, cp);
        if (clasesBase == null) {
            return negativo("tu test no compila en la carpeta temporal (¿usa clases de test del proyecto sin compilar?)");
        }
        Map<String, Integer> pasaBase;
        try {
            pasaBase = pasan(EjecutorJUnit.ejecutar(base, List.of(pend.fqnTest()), conClases(clasesBase, cp), prep.mvn(), TIMEOUT),
                    pend.fqnTest(), null);
        } catch (RuntimeException e) {
            return negativo("no se pudo ejecutar tu test en la carpeta temporal: " + Textos.recortar(String.valueOf(e.getMessage()), 200));
        }
        if (pasaBase.isEmpty()) {
            return negativo("ninguno de tus tests pasa en la carpeta temporal (por ejemplo, el contexto de Spring no arranca ahí), "
                    + "así que no se puede comprobar");
        }
        // Copia: si no compila entera, se prueba su estructura (casos en blanco).
        ContextoVerificacion copia = contexto(p, esc, me, prep, dir.resolve("copia"));
        Path archivoCopia = escribirFuente(copia, copia.tmp.resolve("src"), simple, pend.texto());
        CompiladorTemporal.ResultadoArchivo rc = CompiladorTemporal.compilarArchivo(copia, archivoCopia, cp, copia.tmp.resolve("clases"),
                "javac-mezcla-copia");
        Path clasesCopia = copia.tmp.resolve("clases");
        if (!rc.correcto()) {
            Path dirEstructura = copia.tmp.resolve("estructura");
            Path archivoEstructura = escribirFuente(copia, dirEstructura.resolve("src"), simple, estructura(pend.texto()));
            CompiladorTemporal.ResultadoArchivo re = CompiladorTemporal.compilarArchivo(copia, archivoEstructura, cp,
                    dirEstructura.resolve("clases"), "javac-mezcla-estructura");
            if (!re.correcto()) {
                return negativo("la copia ampliada no compila: " + primerDiagnostico(re.diagnosticos().isEmpty() ? rc : re));
            }
            clasesCopia = dirEstructura.resolve("clases");
        }
        Map<String, Integer> pasaCopia;
        try {
            pasaCopia = pasan(EjecutorJUnit.ejecutar(copia, List.of(pend.fqnTest()), conClases(clasesCopia, cp), prep.mvn(), TIMEOUT),
                    pend.fqnTest(), null);
        } catch (RuntimeException e) {
            return negativo("no se pudo ejecutar la copia ampliada en la carpeta temporal: "
                    + Textos.recortar(String.valueOf(e.getMessage()), 200));
        }
        int total = 0;
        for (Map.Entry<String, Integer> e : pasaBase.entrySet()) {
            total += e.getValue();
            if (pasaCopia.getOrDefault(e.getKey(), 0) < e.getValue()) {
                String metodo = e.getKey().substring(e.getKey().indexOf('#') + 1);
                return negativo("al añadir los casos nuevos deja de pasar tu test " + metodo);
            }
        }
        return new CopiaAmpliada.Veredicto(true, null, total);
    }

    /**
     * Revisión de los tests originales al verificar una copia ampliada (no son casos): informa si siguen pasando,
     * avisa si alguno deja de pasar por la mezcla (comparando con el test del usuario solo) y si la copia está
     * desfasada respecto al test del proyecto. Nunca interrumpe la verificación.
     */
    static void revisarOriginales(ContextoVerificacion ctx, List<Path> cpCompilacion, Path mvn, String fqn, String textoCopia,
                                  EjecutorJUnit.Ejecucion ej, ResultadoVerificacion rv, StringBuilder informe) {
        String simple = Textos.nombreSimple(fqn);
        try {
            String nucleoCopia = CopiaAmpliada.nucleo(textoCopia);
            if (nucleoCopia == null || ej == null) {
                return;
            }
            Set<String> deCasos = new LinkedHashSet<>();
            for (ExtractorHuellasTests.Marcador m : ExtractorHuellasTests.marcadores(textoCopia)) {
                if (m.metodoTest() != null) {
                    deCasos.add(m.metodoTest());
                }
            }
            Map<String, Integer> pasan = new LinkedHashMap<>();
            Set<String> fallan = new LinkedHashSet<>();
            for (ResultadoTest t : ej.resultados()) {
                if (!fqn.equals(t.clase) || t.metodoTest == null || deCasos.contains(t.metodoTest)) {
                    continue;
                }
                String clave = t.clase + "#" + t.metodoTest;
                if (ClasificadorFallos.PASA.equals(t.estado)) {
                    pasan.merge(clave, 1, Integer::sum);
                } else {
                    fallan.add(clave);
                }
            }
            if (fallan.isEmpty()) {
                if (!pasan.isEmpty()) {
                    int n = pasan.values().stream().mapToInt(Integer::intValue).sum();
                    informe.append("  - `").append(simple).append("` (copia ampliada de tu test): ")
                            .append(n == 1 ? "tu test original sigue pasando." : "tus " + n + " tests originales siguen pasando.").append('\n');
                }
            } else {
                Map<String, Integer> base = lineaBase(ctx, cpCompilacion, mvn, fqn, simple, nucleoCopia);
                List<String> culpables = new ArrayList<>();
                for (String k : fallan) {
                    if (base.getOrDefault(k, 0) > pasan.getOrDefault(k, 0)) {
                        culpables.add(k.substring(k.indexOf('#') + 1));
                    }
                }
                if (!culpables.isEmpty()) {
                    String aviso = "ATENCIÓN: en `" + simple + "` (copia ampliada de tu test) dejan de pasar tests tuyos que pasan sin los "
                            + "casos nuevos (" + LimitesRespuesta.listaCorta(culpables, 5) + "): no sustituyas tu archivo por esta copia "
                            + "hasta revisarlo.";
                    anadirAviso(rv, informe, aviso);
                }
            }
            InfoClase info = ctx.modulo.indice.get(fqn);
            if (info != null && info.archivo != null && Files.isRegularFile(info.archivo, LinkOption.NOFOLLOW_LINKS)) {
                String orig;
                try {
                    orig = Files.readString(info.archivo, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    orig = null;
                }
                String nucleoOrig = orig == null ? null : CopiaAmpliada.nucleo(orig);
                if (nucleoOrig != null && !nucleoOrig.equals(nucleoCopia)) {
                    anadirAviso(rv, informe, "`" + simple + "`: tu test del proyecto ha cambiado y esta copia ampliada está desfasada: "
                            + "vuelve a generar para actualizarla.");
                }
            }
        } catch (RuntimeException e) {
            String aviso = "No se pudo revisar la copia ampliada de `" + simple + "`: " + Textos.recortar(String.valueOf(e.getMessage()), 200);
            if (!rv.avisos.contains(aviso)) {
                rv.avisos.add(aviso);
            }
        }
    }

    // ---------------------------------------------------------------- utilidades

    private static void anadirAviso(ResultadoVerificacion rv, StringBuilder informe, String aviso) {
        if (!rv.avisos.contains(aviso)) {
            rv.avisos.add(aviso);
        }
        informe.append("  - ").append(aviso).append('\n');
    }

    /** PASA por {@code clase#metodo} del test del usuario ejecutado solo; vacío si no compila o no se ejecuta. */
    private static Map<String, Integer> lineaBase(ContextoVerificacion ctx, List<Path> cp, Path mvn, String fqn, String simple,
                                                  String nucleo) {
        ContextoVerificacion c = new ContextoVerificacion();
        c.proyecto = ctx.proyecto;
        c.modulo = ctx.modulo;
        c.escritor = ctx.escritor;
        c.proceso = ctx.proceso;
        c.jdk = ctx.jdk;
        c.tmp = ctx.tmp.resolve("mezcla-base").resolve(simple + "-" + Textos.sha256Hex(fqn, 8));
        ctx.escritor.borrarTemporal(c.tmp);
        ctx.escritor.carpetaTemporal(c.tmp);
        Path clases = compilar(c, simple, nucleo, "javac-mezcla-base", c.tmp, cp);
        if (clases == null) {
            return Map.of();
        }
        return pasan(EjecutorJUnit.ejecutar(c, List.of(fqn), conClases(clases, cp), mvn, TIMEOUT), fqn, null);
    }

    private static ContextoVerificacion contexto(ProyectoEscaneado p, EscritorSeguro esc, ModuloEscaneado me,
                                                 PreparadorModulos.Preparacion prep, Path tmp) {
        ContextoVerificacion c = new ContextoVerificacion();
        c.proyecto = p;
        c.modulo = me;
        c.escritor = esc;
        c.proceso = new ProcesoSeguro(esc);
        c.jdk = prep.jdk();
        c.tmp = tmp;
        esc.carpetaTemporal(tmp);
        return c;
    }

    /** Compila {@code texto} como {@code simple}.java en {@code dir}/clases; null si no compila. */
    private static Path compilar(ContextoVerificacion ctx, String simple, String texto, String nombre, Path dir, List<Path> cp) {
        Path archivo = escribirFuente(ctx, dir.resolve("src"), simple, texto);
        Path clases = dir.resolve("clases");
        CompiladorTemporal.ResultadoArchivo r = CompiladorTemporal.compilarArchivo(ctx, archivo, cp, clases, nombre);
        return r.correcto() ? clases : null;
    }

    private static Path escribirFuente(ContextoVerificacion ctx, Path src, String simple, String texto) {
        Path dir = ctx.escritor.carpetaTemporal(src);
        Path archivo = dir.resolve(simple + ".java");
        ctx.escritor.escribir(archivo, texto);
        return archivo;
    }

    private static List<Path> conClases(Path clases, List<Path> cp) {
        List<Path> r = new ArrayList<>();
        r.add(clases);
        r.addAll(cp);
        return r;
    }

    /** Número de ejecuciones que pasan por {@code clase#metodoTest} de la clase {@code fqn}. */
    private static Map<String, Integer> pasan(EjecutorJUnit.Ejecucion ej, String fqn, Set<String> excluidos) {
        Map<String, Integer> r = new LinkedHashMap<>();
        if (ej == null) {
            return r;
        }
        for (ResultadoTest t : ej.resultados()) {
            if (fqn.equals(t.clase) && t.metodoTest != null && ClasificadorFallos.PASA.equals(t.estado)
                    && (excluidos == null || !excluidos.contains(t.metodoTest))) {
                r.merge(t.clase + "#" + t.metodoTest, 1, Integer::sum);
            }
        }
        return r;
    }

    /** Mismas líneas que {@code texto} con la región de cada caso junit-gen en blanco. */
    static String estructura(String texto) {
        String[] lineas = texto.split("\\R", -1);
        for (ExtractorHuellasTests.Marcador m : ExtractorHuellasTests.marcadores(texto)) {
            if (m.lineaFin() <= 0) {
                continue;
            }
            for (int l = m.lineaInicio(); l <= m.lineaFin() && l - 1 < lineas.length; l++) {
                lineas[l - 1] = "";
            }
        }
        return String.join("\n", lineas);
    }

    private static String primerDiagnostico(CompiladorTemporal.ResultadoArchivo r) {
        if (r.diagnosticos().isEmpty()) {
            return Textos.recortar(ResolutorClasspathMaven.ultimas(r.salida() == null ? "" : r.salida(), 3), 200);
        }
        CompiladorTemporal.Diagnostico d = r.diagnosticos().get(0);
        return Textos.recortar((d.linea() > 0 ? "línea " + d.linea() + ": " : "") + d.mensaje(), 200);
    }

    private static CopiaAmpliada.Veredicto negativo(String motivo) {
        return new CopiaAmpliada.Veredicto(false, motivo, 0);
    }
}
