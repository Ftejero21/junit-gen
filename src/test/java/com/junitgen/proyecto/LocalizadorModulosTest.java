package com.junitgen.proyecto;

import com.junitgen.Fixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class LocalizadorModulosTest {

    @TempDir
    Path tmp;

    @Test
    void raizSinPomConVariosModulosIgnorandoNodeModulesYOcultas() throws Exception {
        Path raiz = Fixtures.copiar("multi", tmp.resolve("multi"));
        LocalizadorModulos.Resultado r = LocalizadorModulos.localizar(raiz);
        assertThat(r.modulos.stream().map(m -> m.rel).collect(Collectors.toList())).containsExactly("modA", "modB");
        assertThat(r.modulos.get(0).build).isEqualTo(Modulo.TipoBuild.MAVEN);
        assertThat(r.modulos.get(1).build).isEqualTo(Modulo.TipoBuild.GRADLE);
        assertThat(r.poms.stream().map(Path::toString).collect(Collectors.joining(";")))
                .doesNotContain("node_modules").doesNotContain(".oculto");
    }

    @Test
    void raizQueEsModuloYContieneSubmodulos() throws Exception {
        Path raiz = tmp.resolve("raiz");
        Fixtures.escribir(raiz.resolve("pom.xml"), "<project><modelVersion>4.0.0</modelVersion><groupId>g</groupId>"
                + "<artifactId>raiz</artifactId><version>1</version></project>");
        Fixtures.escribir(raiz.resolve("src/main/java/a/A.java"), "package a; public class A {}");
        Fixtures.escribir(raiz.resolve("sub/pom.xml"), "<project><modelVersion>4.0.0</modelVersion><groupId>g</groupId>"
                + "<artifactId>sub</artifactId><version>1</version></project>");
        Fixtures.escribir(raiz.resolve("sub/src/main/java/b/B.java"), "package b; public class B {}");
        Fixtures.escribir(raiz.resolve("agregador/pom.xml"), "<project><modelVersion>4.0.0</modelVersion><groupId>g</groupId>"
                + "<artifactId>agg</artifactId><version>1</version><packaging>pom</packaging></project>");
        Fixtures.escribir(raiz.resolve("target/x/pom.xml"), "<project/>");
        LocalizadorModulos.Resultado r = LocalizadorModulos.localizar(raiz);
        assertThat(r.modulos.stream().map(m -> m.rel).collect(Collectors.toList())).containsExactly("", "sub");
        assertThat(r.agregadores).hasSize(1);
        assertThat(r.modulos.get(0).nombre()).isEqualTo("raiz");
    }
}
