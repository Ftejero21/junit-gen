package com.junitgen.duplicados;

import com.junitgen.escaneo.EmparejadorTests;
import com.junitgen.generacion.RutasSalida;
import com.junitgen.spec.ResultadoValidacion;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Duplicados por huella en 3 niveles: (1) tests del proyecto, (2) dentro del spec (se conserva el
 * primero), (3) casos ya generados en la carpeta de salida (sus marcadores son la verdad).
 */
public final class DetectorDuplicados {

    private DetectorDuplicados() {
    }

    @SuppressWarnings("unchecked")
    public static void detectar(ResultadoValidacion res, Path salida) {
        for (ResultadoValidacion.ServicioValidado sv : res.servicios) {
            List<ExtractorHuellasTests.HuellaExistente> existentes;
            synchronized (sv.modulo) {
                existentes = (List<ExtractorHuellasTests.HuellaExistente>) sv.modulo.cacheHuellas.get(sv.clase.fqn);
                if (existentes == null) {
                    existentes = new ArrayList<>();
                    EmparejadorTests.Emparejamiento e = sv.modulo.emparejamiento(sv.clase);
                    for (EmparejadorTests.TestEmparejado t : e.tests) {
                        try {
                            existentes.addAll(ExtractorHuellasTests.extraer(t.test, sv.clase, sv.modulo.indice));
                        } catch (RuntimeException ex) {
                            res.aviso("$.servicios[" + sv.indice + "]", "HUELLAS_NO_EXTRAIDAS",
                                    "No se pudieron extraer huellas de " + t.test.simple + ": " + ex.getMessage(), null);
                        }
                    }
                    sv.modulo.cacheHuellas.put(sv.clase.fqn, existentes);
                }
            }
            List<ExtractorHuellasTests.Marcador> generados = new ArrayList<>();
            if (salida != null) {
                Path archivo = RutasSalida.archivoTest(salida, sv.modulo, sv.clase, sv.claseTest);
                if (Files.isRegularFile(archivo)) {
                    try {
                        generados.addAll(ExtractorHuellasTests.marcadores(Files.readString(archivo, StandardCharsets.UTF_8)));
                    } catch (IOException ex) {
                        res.aviso("$.servicios[" + sv.indice + "]", "SALIDA_ILEGIBLE", "No se pudo leer " + archivo + ": " + ex.getMessage(), null);
                    }
                }
            }
            Map<String, ResultadoValidacion.CasoValidado> vistosCompleta = new LinkedHashMap<>();
            Map<String, ResultadoValidacion.CasoValidado> vistosEntrada = new LinkedHashMap<>();
            for (ResultadoValidacion.CasoValidado cv : sv.casos) {
                if (cv.huella == null) {
                    continue;
                }
                String id = cv.spec.id;
                String ruta = "$.servicios[" + sv.indice + "].casos[" + cv.indice + "]";
                boolean duplicado = false;
                // Nivel 1: tests del proyecto
                for (ExtractorHuellasTests.HuellaExistente h : existentes) {
                    if (h.huella().completa.equals(cv.huella.completa)) {
                        res.duplicados.add(new Duplicado(id, sv.clase.fqn, 1, "misma huella que un test existente",
                                h.test() + "." + h.metodoTest()));
                        duplicado = true;
                        break;
                    }
                    if (h.huella().entrada.equals(cv.huella.entrada)) {
                        if (h.huella().esperadoDesconocido()) {
                            res.duplicados.add(new Duplicado(id, sv.clase.fqn, 1,
                                    "mismas entradas y stubs que un test existente (esperado no deducible)",
                                    h.test() + "." + h.metodoTest()));
                            duplicado = true;
                            break;
                        }
                        res.aviso(ruta, "MISMO_ESCENARIO_ESPERADO_DISTINTO", "Mismo escenario con esperado distinto que "
                                + h.test() + "." + h.metodoTest() + " (" + h.huella().esperado + ").", "Revisa cuál es el comportamiento correcto.");
                    }
                }
                if (duplicado) {
                    continue;
                }
                // Nivel 3: generado antes en la salida (otro id)
                for (ExtractorHuellasTests.Marcador mk : generados) {
                    if (mk.id() != null && mk.id().equals(id)) {
                        continue;
                    }
                    if (cv.huella.completa.equals(mk.huella())) {
                        res.duplicados.add(new Duplicado(id, sv.clase.fqn, Duplicado.NIVEL_SALIDA, "misma huella que un caso ya generado en la salida",
                                "caso " + mk.id() + (mk.metodoTest() != null ? " (" + mk.metodoTest() + ")" : "")));
                        duplicado = true;
                        break;
                    }
                    if (cv.huella.entrada.equals(mk.entrada())) {
                        res.aviso(ruta, "MISMO_ESCENARIO_ESPERADO_DISTINTO", "Mismo escenario con esperado distinto que el caso generado "
                                + mk.id() + ".", "Revisa cuál es el comportamiento correcto.");
                    }
                }
                if (duplicado) {
                    continue;
                }
                // Nivel 2: dentro del spec (se conserva el primero)
                ResultadoValidacion.CasoValidado previo = vistosCompleta.get(cv.huella.completa);
                if (previo != null) {
                    res.duplicados.add(new Duplicado(id, sv.clase.fqn, 2, "misma huella que otro caso del spec", "caso " + previo.spec.id));
                    continue;
                }
                ResultadoValidacion.CasoValidado previoEntrada = vistosEntrada.get(cv.huella.entrada);
                if (previoEntrada != null) {
                    res.aviso(ruta, "MISMO_ESCENARIO_ESPERADO_DISTINTO", "Mismo escenario con esperado distinto que el caso "
                            + previoEntrada.spec.id + " del spec.", "Revisa cuál es el comportamiento correcto.");
                }
                vistosCompleta.put(cv.huella.completa, cv);
                vistosEntrada.putIfAbsent(cv.huella.entrada, cv);
            }
        }
    }
}
