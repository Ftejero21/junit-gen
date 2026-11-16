package com.junitgen.generacion;

import com.junitgen.seguridad.EscritorSeguro;
import com.junitgen.spec.ErrorValidacion;
import com.junitgen.spec.ResultadoValidacion;
import com.junitgen.util.Textos;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pre-paso de la generación (sin efectos): decide, para cada servicio cuyo {@code <Servicio>Test} ya existe en el
 * proyecto, si sus casos van a la copia ampliada de ese test (nueva, ampliada o rehecha) o a
 * {@code <Servicio>GeneradoTest}. Las copias nuevas o rehechas sin veredicto quedan pendientes de comprobación.
 * El test del proyecto solo se lee.
 */
final class MezclasGeneracion {

    enum Tipo { NUEVA, AMPLIAR, REHECHA, NO_TOCAR, GENERADO }

    /**
     * Decisión de un servicio. {@code base}: texto sobre el que se fusionan los casos (NUEVA, AMPLIAR, REHECHA);
     * {@code motivo}: por qué no se toca (NO_TOCAR), por qué no se actualizó una copia desfasada (AMPLIAR) o por qué
     * no se mezcló (GENERADO; null = no se intentó).
     */
    record Decision(Tipo tipo, PlanMezcla plan, String base, String relOriginal, String motivo, int originalesPasan) {
    }

    private static final Pattern SIN_MEZCLA = Pattern.compile("(?m)^\\s*" + Pattern.quote(CopiaAmpliada.M_SIN_MEZCLA) + "[ \\t]+(.*?)\\s*$");
    private static final String SUGERENCIA = "Informativo: no corrijas nada ni repitas la llamada.";

    private MezclasGeneracion() {
    }

    static Map<ResultadoValidacion.ServicioValidado, Decision> decidir(ResultadoValidacion val, EscritorSeguro esc,
                                                                      GeneradorTests.Opciones op, ResultadoGeneracion g) {
        Map<ResultadoValidacion.ServicioValidado, Decision> r = new IdentityHashMap<>();
        for (ResultadoValidacion.ServicioValidado sv : val.servicios) {
            if (sv.testExistente == null) {
                continue;
            }
            Decision d = sv.copiaAmpliada ? copiaExistente(val, esc, op, g, sv) : sinCopia(val, esc, op, g, sv);
            if (d != null) {
                r.put(sv, d);
            }
        }
        return r;
    }

    /** A) La salida ya tiene la copia ampliada: se amplía (o se rehace si el original cambió y se puede). */
    private static Decision copiaExistente(ResultadoValidacion val, EscritorSeguro esc, GeneradorTests.Opciones op,
                                           ResultadoGeneracion g, ResultadoValidacion.ServicioValidado sv) {
        PerfilPlantilla perfil = PerfilPlantilla.de(sv.modulo.versiones, sv.estilo);
        String base = sv.clase.simple + "Test";
        String relOriginal = relOriginal(val, sv, base);
        Path archivo = RutasSalida.archivoTest(esc.salida(), sv.modulo, sv.clase, base);
        String copia;
        try {
            copia = Files.readString(archivo, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("No se puede leer " + archivo + ": " + e.getMessage(), e);
        }
        String nucleoCopia = CopiaAmpliada.nucleo(copia);
        PlanMezcla planCopia = PlanMezcla.analizar(nucleoCopia, sv, perfil);
        if (!planCopia.ok) {
            return new Decision(Tipo.NO_TOCAR, null, null, relOriginal, "este servicio usa la copia ampliada de tu test: no admite "
                    + "`construccion` ni mocks profundos (pon los stubs dentro de cada caso). Motivo: " + planCopia.motivo, 0);
        }
        String textoOriginal = leerEstricto(sv.testExistente.archivo);
        String nucleoOriginal = textoOriginal == null ? null : CopiaAmpliada.nucleo(textoOriginal);
        if (nucleoOriginal == null || nucleoOriginal.equals(nucleoCopia)) {
            return new Decision(Tipo.AMPLIAR, planCopia, copia, relOriginal, null, 0);
        }
        // Copia desfasada: el original del proyecto ha cambiado.
        String motivo;
        if (!op.verificarMezcla) {
            motivo = "en esta ejecución no se prueban los tests (sin esa comprobación no se rehace)";
        } else {
            PlanMezcla planNuevo = PlanMezcla.analizar(nucleoOriginal, sv, perfil);
            if (!planNuevo.ok) {
                motivo = planNuevo.motivo;
            } else if (!planNuevo.compatibleCon(planCopia)) {
                motivo = "tu test ha cambiado de estilo, de campo del servicio o de mocks";
            } else if (!CopiaAmpliada.idsEnBloques(copia).containsAll(CopiaAmpliada.idsEnBloques(textoOriginal))) {
                motivo = "tu test tiene casos de junit-gen que no están en la copia";
            } else {
                String trasplantada = CopiaAmpliada.trasplantar(nucleoOriginal, copia, planNuevo.lineaTrasImports, planNuevo.lineaCierre);
                if (trasplantada == null) {
                    motivo = "no se pudieron pasar los casos de junit-gen a tu versión actual sin tocarla";
                } else {
                    GeneradorTests.Render rd = GeneradorTests.render(val, sv, new PlantillaTest(perfil, sv, planNuevo), trasplantada, op,
                            true);
                    if (rd.fr().motivoNoTocado != null) {
                        motivo = rd.fr().motivoNoTocado;
                    } else if (!nucleoOriginal.equals(CopiaAmpliada.nucleo(rd.fr().texto))) {
                        motivo = "la copia rehecha no conservaba tu archivo intacto";
                    } else {
                        CopiaAmpliada.Veredicto v = veredicto(op, g, sv, nucleoOriginal, rd.fr().texto);
                        if (v == null) {
                            return null;
                        }
                        if (v.ok()) {
                            return new Decision(Tipo.REHECHA, planNuevo, trasplantada, relOriginal, null, v.originalesPasan());
                        }
                        motivo = v.motivo();
                    }
                }
            }
        }
        return new Decision(Tipo.AMPLIAR, planCopia, copia, relOriginal, motivo == null ? "no se pudo comprobar" : motivo, 0);
    }

    /** B) Sin copia en la salida: se intenta crearla (si está permitido) o los casos van al GeneradoTest. */
    private static Decision sinCopia(ResultadoValidacion val, EscritorSeguro esc, GeneradorTests.Opciones op, ResultadoGeneracion g,
                                     ResultadoValidacion.ServicioValidado sv) {
        Path salida = esc.salida();
        String base = sv.clase.simple + "Test";
        String generado = sv.clase.simple + "GeneradoTest";
        String relOriginal = relOriginal(val, sv, base);
        Path archivoGenerado = RutasSalida.archivoTest(salida, sv.modulo, sv.clase, generado);
        if (Files.isRegularFile(archivoGenerado, LinkOption.NOFOLLOW_LINKS)) {
            String motivo = motivoCabecera(archivoGenerado);
            if (motivo == null && op.verificarMezcla) {
                motivo = "los casos de este servicio ya iban en " + generado + " desde una ejecución anterior (para intentar la copia "
                        + "ampliada usa una carpeta de salida nueva)";
            }
            return new Decision(Tipo.GENERADO, null, null, relOriginal, motivo, 0);
        }
        if (!op.verificarMezcla) {
            return new Decision(Tipo.GENERADO, null, null, relOriginal, null, 0);
        }
        Path archivoBase = RutasSalida.archivoTest(salida, sv.modulo, sv.clase, base);
        if (Files.exists(archivoBase, LinkOption.NOFOLLOW_LINKS)) {
            return fallback(sv, relOriginal, "la carpeta de salida ya tiene otro " + base + ".java que no es una copia ampliada");
        }
        String textoOriginal = leerEstricto(sv.testExistente.archivo);
        if (textoOriginal == null) {
            return fallback(sv, relOriginal, "tu test no se ha podido leer como texto UTF-8");
        }
        String nucleo = CopiaAmpliada.nucleo(textoOriginal);
        PerfilPlantilla perfil = PerfilPlantilla.de(sv.modulo.versiones, sv.estilo);
        PlanMezcla plan = PlanMezcla.analizar(nucleo, sv, perfil);
        if (!plan.ok) {
            return fallback(sv, relOriginal, plan.motivo);
        }
        String baseTexto = CopiaAmpliada.es(textoOriginal)
                ? CopiaAmpliada.trasplantar(nucleo, textoOriginal, plan.lineaTrasImports, plan.lineaCierre)
                : CopiaAmpliada.crear(nucleo, plan.lineaTrasImports, plan.lineaCierre);
        if (baseTexto == null) {
            return fallback(sv, relOriginal, "no se pudo preparar la copia sin tocar tu archivo");
        }
        String claseAntes = sv.claseTest;
        sv.claseTest = base;
        GeneradorTests.Render rd = GeneradorTests.render(val, sv, new PlantillaTest(perfil, sv, plan), baseTexto, op, true);
        if (rd.fr().motivoNoTocado != null) {
            sv.claseTest = claseAntes;
            return fallback(sv, relOriginal, rd.fr().motivoNoTocado);
        }
        if (rd.fr().anadidos.isEmpty()) {
            sv.claseTest = claseAntes;
            return null;
        }
        String texto = rd.fr().texto;
        if (!nucleo.equals(CopiaAmpliada.nucleo(texto))) {
            sv.claseTest = claseAntes;
            return fallback(sv, relOriginal, "la copia no conservaba tu archivo intacto");
        }
        CopiaAmpliada.Veredicto v = veredicto(op, g, sv, nucleo, texto);
        if (v == null || !v.ok()) {
            sv.claseTest = claseAntes;
            return v == null ? null : fallback(sv, relOriginal, v.motivo() == null ? "no se pudo comprobar" : v.motivo());
        }
        return new Decision(Tipo.NUEVA, plan, baseTexto, relOriginal, null, v.originalesPasan());
    }

    private static Decision fallback(ResultadoValidacion.ServicioValidado sv, String relOriginal, String motivo) {
        sv.motivoSinMezcla = motivo;
        return new Decision(Tipo.GENERADO, null, null, relOriginal, motivo, 0);
    }

    /** Veredicto ya calculado de la copia; si falta, la deja pendiente y devuelve null. */
    private static CopiaAmpliada.Veredicto veredicto(GeneradorTests.Opciones op, ResultadoGeneracion g,
                                                     ResultadoValidacion.ServicioValidado sv, String nucleo, String texto) {
        String clave = CopiaAmpliada.huella(nucleo) + '\0' + texto;
        CopiaAmpliada.Veredicto v = op.veredictos.get(clave);
        if (v == null) {
            g.mezclasPendientes.add(new CopiaAmpliada.Pendiente(clave, sv.modulo, sv.testExistente.fqn, nucleo, texto));
        }
        return v;
    }

    /** Lee el test del proyecto (UTF-8 estricto); null si no es un archivo regular, no se puede leer o no decodifica. */
    static String leerEstricto(Path f) {
        if (f == null || !Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        try {
            return Files.readString(f, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Motivo anotado en la cabecera de un GeneradoTest ({@code // junit-gen:sin-mezcla …}); null si no hay. */
    private static String motivoCabecera(Path archivo) {
        try {
            String texto = Files.readString(archivo, StandardCharsets.UTF_8);
            Matcher m = SIN_MEZCLA.matcher(texto);
            return m.find() && !m.group(1).isBlank() ? m.group(1) : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Ruta del test del usuario relativa al proyecto (con {@code /}). */
    private static String relOriginal(ResultadoValidacion val, ResultadoValidacion.ServicioValidado sv, String base) {
        try {
            if (sv.testExistente.archivo != null && val.proyecto != null && val.proyecto.raiz != null) {
                Path raiz = val.proyecto.raiz.toAbsolutePath().normalize();
                Path f = sv.testExistente.archivo.toAbsolutePath().normalize();
                if (f.startsWith(raiz)) {
                    return raiz.relativize(f).toString().replace('\\', '/');
                }
            }
        } catch (RuntimeException e) {
            // se usa el destino por defecto
        }
        return RutasSalida.destinoCopia(sv.modulo, sv.clase, base);
    }

    /** Línea de informe y aviso de un servicio con test existente, según lo que se ha hecho en esta llamada. */
    static void anotar(ResultadoGeneracion g, ResultadoValidacion.ServicioValidado sv, Decision d, Path salida, Path archivo,
                       String textoFinal, int anadidosAhora, boolean escrito) {
        String original = cod(d.relOriginal());
        String rel = cod(TextosInforme.rutaRelativa(salida.toString(), archivo.toString()));
        String ruta = "servicios[" + sv.indice + "]";
        String linea;
        String codigo;
        switch (d.tipo()) {
            case NUEVA -> {
                String h = CopiaAmpliada.huellaAnotada(textoFinal);
                linea = "Sustituye tu " + original + " por " + rel + ": contiene el tuyo intacto + "
                        + TextosInforme.cuantos(anadidosAhora, "1 caso nuevo", "%d casos nuevos") + " al final. Comprobado: compila y "
                        + TextosInforme.cuantos(d.originalesPasan(), "tu test sigue pasando", "tus %d tests siguen pasando")
                        + ". Huella de tu original: " + cod(h == null ? null : h.substring(0, 12)) + ".";
                codigo = "COPIA_AMPLIADA";
            }
            case REHECHA -> {
                Set<String> ids = CopiaAmpliada.idsEnBloques(textoFinal);
                linea = "Tu " + original + " había cambiado: " + rel + " se ha rehecho a partir de tu versión actual y conserva "
                        + TextosInforme.cuantos(ids.size(), "el caso de junit-gen", "los %d casos de junit-gen")
                        + " (se vuelven a probar). Sustituye el tuyo por este.";
                codigo = "COPIA_AMPLIADA";
            }
            case AMPLIAR -> {
                if (d.motivo() != null) {
                    linea = "ATENCIÓN: tu " + original + " ha cambiado y " + rel + " no se ha podido actualizar porque " + motivo(d.motivo())
                            + ". Sigue basada en la versión anterior de tu test: no la copies encima del tuyo sin revisar las diferencias.";
                    codigo = "COPIA_DESFASADA";
                } else if (escrito || anadidosAhora > 0) {
                    Set<String> ids = CopiaAmpliada.idsEnBloques(textoFinal);
                    linea = "Sustituye tu " + original + " por " + rel + ": contiene el tuyo intacto + "
                            + TextosInforme.cuantos(ids.size(), "1 caso de junit-gen", "%d casos de junit-gen") + " ("
                            + TextosInforme.cuantos(anadidosAhora, "1 añadido ahora", "%d añadidos ahora") + ").";
                    codigo = "COPIA_AMPLIADA";
                } else {
                    return;
                }
            }
            case GENERADO -> {
                if (anadidosAhora <= 0) {
                    return;
                }
                linea = d.motivo() != null
                        ? "No se pudo mezclar con tu " + original + " porque " + motivo(d.motivo()) + "; los casos nuevos están en " + rel + "."
                        : "No se intentó ampliar tu " + original + " porque en esta ejecución no se prueban los tests (sin esa "
                        + "comprobación no se mezcla); los casos nuevos están en " + rel + ".";
                codigo = "SIN_MEZCLA";
            }
            default -> {
                return;
            }
        }
        g.mezclas.add(linea);
        // Sin intento de mezcla (no se prueban los tests) solo queda la línea del informe: el resultado JSON no cambia.
        if (d.tipo() != Tipo.GENERADO || d.motivo() != null) {
            g.avisos.add(ErrorValidacion.aviso(ruta, codigo, linea.replace("`", ""), SUGERENCIA));
        }
    }

    private static String motivo(String m) {
        return TextosInforme.texto(Textos.sanearComentario(m, 300), 300);
    }

    private static String cod(String s) {
        String c = TextosInforme.codigo(s, 300);
        return c.isEmpty() ? TextosInforme.SIN_DATO : c;
    }
}
