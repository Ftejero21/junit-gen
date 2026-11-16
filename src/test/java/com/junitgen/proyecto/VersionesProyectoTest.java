package com.junitgen.proyecto;

import com.junitgen.Fixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class VersionesProyectoTest {

    @TempDir
    Path tmp;

    private static String pom(String cuerpo) {
        return "<?xml version=\"1.0\"?><project xmlns=\"http://maven.apache.org/POM/4.0.0\"><modelVersion>4.0.0</modelVersion>"
                + cuerpo + "</project>";
    }

    private void repoFalso(Path repo) throws Exception {
        Fixtures.escribir(repo.resolve("org/springframework/boot/spring-boot-starter-parent/9.9.9/spring-boot-starter-parent-9.9.9.pom"),
                pom("<parent><groupId>org.springframework.boot</groupId><artifactId>spring-boot-dependencies</artifactId>"
                        + "<version>9.9.9</version></parent><artifactId>spring-boot-starter-parent</artifactId><packaging>pom</packaging>"
                        + "<properties><java.version>17</java.version><maven.compiler.release>${java.version}</maven.compiler.release></properties>"));
        Fixtures.escribir(repo.resolve("org/springframework/boot/spring-boot-dependencies/9.9.9/spring-boot-dependencies-9.9.9.pom"),
                pom("<groupId>org.springframework.boot</groupId><artifactId>spring-boot-dependencies</artifactId><version>9.9.9</version>"
                        + "<packaging>pom</packaging><properties><assertj.version>3.27.3</assertj.version>"
                        + "<junit-jupiter.version>5.12.2</junit-jupiter.version><mockito.version>5.17.0</mockito.version></properties>"
                        + "<dependencyManagement><dependencies>"
                        + "<dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-test</artifactId><version>9.9.9</version></dependency>"
                        + "<dependency><groupId>org.assertj</groupId><artifactId>assertj-core</artifactId><version>${assertj.version}</version></dependency>"
                        + "<dependency><groupId>org.junit</groupId><artifactId>junit-bom</artifactId><version>${junit-jupiter.version}</version><type>pom</type><scope>import</scope></dependency>"
                        + "<dependency><groupId>org.mockito</groupId><artifactId>mockito-bom</artifactId><version>${mockito.version}</version><type>pom</type><scope>import</scope></dependency>"
                        + "</dependencies></dependencyManagement>"));
        Fixtures.escribir(repo.resolve("org/junit/junit-bom/5.12.2/junit-bom-5.12.2.pom"),
                pom("<groupId>org.junit</groupId><artifactId>junit-bom</artifactId><version>5.12.2</version><packaging>pom</packaging>"
                        + "<dependencyManagement><dependencies><dependency><groupId>org.junit.jupiter</groupId>"
                        + "<artifactId>junit-jupiter</artifactId><version>5.12.2</version></dependency></dependencies></dependencyManagement>"));
        Fixtures.escribir(repo.resolve("org/mockito/mockito-bom/5.17.0/mockito-bom-5.17.0.pom"),
                pom("<groupId>org.mockito</groupId><artifactId>mockito-bom</artifactId><version>5.17.0</version><packaging>pom</packaging>"
                        + "<dependencyManagement><dependencies><dependency><groupId>org.mockito</groupId>"
                        + "<artifactId>mockito-core</artifactId><version>5.17.0</version></dependency></dependencies></dependencyManagement>"));
    }

    @Test
    void starterTestMasBomDelParent() throws Exception {
        Path repo = tmp.resolve("repo");
        repoFalso(repo);
        Path proyecto = tmp.resolve("app");
        Fixtures.escribir(proyecto.resolve("pom.xml"), pom("<parent><groupId>org.springframework.boot</groupId>"
                + "<artifactId>spring-boot-starter-parent</artifactId><version>9.9.9</version><relativePath/></parent>"
                + "<groupId>com.app</groupId><artifactId>app</artifactId><version>1.0</version>"
                + "<properties><java.version>21</java.version></properties>"
                + "<dependencies><dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-test</artifactId>"
                + "<scope>test</scope></dependency></dependencies>"));
        Fixtures.escribir(proyecto.resolve("src/main/java/a/A.java"), "package a; public class A {}");
        Modulo m = LocalizadorModulos.localizar(proyecto).modulos.get(0);
        VersionesProyecto v = VersionesProyecto.calcular(m, proyecto, new ResolutorBom(proyecto, repo), List.of());
        assertThat(v.java).isEqualTo(21);
        assertThat(v.junitElegido).isEqualTo(5);
        assertThat(v.versionJunit5).isEqualTo("5.12.2");
        assertThat(v.versionMockito).isEqualTo("5.17.0");
        assertThat(v.mockitoJunitJupiter).isTrue();
        assertThat(v.versionAssertj).isEqualTo("3.27.3");
        assertThat(v.springBoot).isEqualTo("9.9.9");
        assertThat(v.permiteMockStatic()).isTrue();
    }

    @Test
    void legacyJava8Junit4YMockito1() throws Exception {
        Path p = Fixtures.copiar("proyecto-legacy", tmp.resolve("l"));
        Modulo m = LocalizadorModulos.localizar(p).modulos.get(0);
        VersionesProyecto v = VersionesProyecto.calcular(m, p, new ResolutorBom(p, tmp.resolve("repo-vacio")), List.of());
        assertThat(v.java).isEqualTo(8);
        assertThat(v.junitElegido).isEqualTo(4);
        assertThat(v.versionJunit4).isEqualTo("4.12");
        assertThat(v.versionMockito).isEqualTo("1.10.19");
        assertThat(v.assertj).isFalse();
        assertThat(v.permiteMockStatic()).isFalse();
        assertThat(v.permiteMockConstruction()).isFalse();
    }

    @Test
    void gradlePorRegex() throws Exception {
        Path raiz = Fixtures.copiar("multi", tmp.resolve("multi"));
        Modulo b = LocalizadorModulos.localizar(raiz).modulos.get(1);
        VersionesProyecto v = VersionesProyecto.calcular(b, raiz, new ResolutorBom(raiz, tmp.resolve("repo-vacio")), List.of());
        assertThat(v.java).isEqualTo(8);
        assertThat(v.junitElegido).isEqualTo(5);
        assertThat(v.versionJunit5).isEqualTo("5.9.3");
        assertThat(v.versionMockito).isEqualTo("4.11.0");
    }

    @Test
    void respaldoPorImportsYAvisos() throws Exception {
        Path p = tmp.resolve("sin-deps");
        Fixtures.escribir(p.resolve("pom.xml"), pom("<groupId>g</groupId><artifactId>a</artifactId><version>1</version>"));
        Fixtures.escribir(p.resolve("src/main/java/a/A.java"), "package a; public class A {}");
        Modulo m = LocalizadorModulos.localizar(p).modulos.get(0);
        VersionesProyecto v = VersionesProyecto.calcular(m, p, new ResolutorBom(p, tmp.resolve("repo-vacio")),
                List.of(List.of("org.junit.jupiter.api.Test", "org.mockito.Mock")));
        assertThat(v.junitElegido).isEqualTo(5);
        assertThat(v.mockito).isTrue();
        assertThat(v.avisos).anyMatch(a -> a.contains("deducido de los imports"));

        VersionesProyecto vacio = VersionesProyecto.calcular(m, p, new ResolutorBom(p, tmp.resolve("repo-vacio")), List.of());
        assertThat(vacio.avisos).anyMatch(a -> a.contains("no tiene JUnit")).anyMatch(a -> a.contains("no tiene Mockito"));
    }
}
