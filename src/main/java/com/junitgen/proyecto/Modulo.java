package com.junitgen.proyecto;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Un módulo Maven o Gradle con fuentes Java. */
public class Modulo {

    public enum TipoBuild { MAVEN, GRADLE }

    public final Path dir;
    /** Ruta relativa a la raíz con '/' ("" si la raíz es el módulo). */
    public final String rel;
    public final TipoBuild build;
    public final Path archivoBuild;
    public final List<Path> fuentesMain = new ArrayList<>();
    public final List<Path> fuentesTest = new ArrayList<>();
    public final List<Path> recursosMain = new ArrayList<>();
    public Pom pom;

    public Modulo(Path dir, String rel, TipoBuild build, Path archivoBuild) {
        this.dir = dir;
        this.rel = rel;
        this.build = build;
        this.archivoBuild = archivoBuild;
    }

    public String nombre() {
        if (!rel.isEmpty()) {
            return rel;
        }
        Path n = dir.getFileName();
        return n == null ? dir.toString() : n.toString();
    }

    @Override
    public String toString() {
        return nombre();
    }
}
