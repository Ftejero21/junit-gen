package com.junitgen;

import com.junitgen.escaneo.Escaner;
import com.junitgen.escaneo.ModuloEscaneado;
import com.junitgen.escaneo.ProyectoEscaneado;
import com.junitgen.spec.LectorSpec;
import com.junitgen.spec.ResultadoValidacion;
import com.junitgen.spec.Spec;
import com.junitgen.spec.ValidadorSpec;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;

/** Utilidades de test: copia de fixtures (recursos no compilados) a carpetas temporales. */
public final class Fixtures {

    private Fixtures() {
    }

    public static Path origen(String nombre) {
        try {
            return Paths.get(Fixtures.class.getResource("/fixtures/" + nombre).toURI());
        } catch (URISyntaxException | NullPointerException e) {
            throw new IllegalStateException("No existe la fixture " + nombre, e);
        }
    }

    public static Path copiar(String nombre, Path destino) throws IOException {
        Path src = origen(nombre);
        try (Stream<Path> s = Files.walk(src)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                Path d = destino.resolve(src.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(d);
                } else {
                    Files.createDirectories(d.getParent());
                    Files.copy(p, d);
                }
            }
        }
        return destino;
    }

    public static void escribir(Path p, String texto) throws IOException {
        Files.createDirectories(p.getParent());
        Files.writeString(p, texto, StandardCharsets.UTF_8);
    }

    public static ProyectoEscaneado escanear(Path raiz) {
        return Escaner.escanear(raiz, null);
    }

    public static ModuloEscaneado unico(ProyectoEscaneado p) {
        if (p.modulos.size() != 1) {
            throw new IllegalStateException("Se esperaba un único módulo y hay " + p.modulos.size());
        }
        return p.modulos.get(0);
    }

    public static Spec spec(String json) {
        LectorSpec.Lectura l = LectorSpec.leerTexto(json);
        if (!l.ok()) {
            throw new IllegalStateException("Spec de test no válido: " + l.errores());
        }
        return l.spec();
    }

    public static ResultadoValidacion validar(String json, ProyectoEscaneado p, Path salida) {
        return ValidadorSpec.validar(spec(json), p, salida);
    }

    /** JSON con comillas simples para escribir specs legibles en los tests. */
    public static String j(String s) {
        return s.replace('\'', '"');
    }
}
