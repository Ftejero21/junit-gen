package com.junitgen.seguridad;

import com.junitgen.Fixtures;
import com.junitgen.cli.CliRunner;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SeguridadRutasTest {

    @TempDir
    Path tmp;

    Path proyecto;

    @BeforeEach
    void preparar() throws IOException {
        proyecto = tmp.resolve("proyecto");
        Fixtures.escribir(proyecto.resolve("src/main/java/a/A.java"), "package a; public class A {}");
    }

    /** Crea un enlace a {@code destino}: symlink o, si falta privilegio, junction. Si ambos fallan, se omite el test. */
    static void enlace(Path enlace, Path destino) throws Exception {
        try {
            Files.createSymbolicLink(enlace, destino);
            return;
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            System.out.println("Symlink no disponible (" + e.getMessage() + "); se prueba con junction.");
        }
        if (RutaSegura.WINDOWS) {
            Process p = new ProcessBuilder("cmd", "/c", "mklink", "/J", enlace.toString(), destino.toString())
                    .redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            if (p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0 && Files.exists(enlace)) {
                return;
            }
        }
        Assumptions.assumeTrue(false, "No se pudo crear ni symlink ni junction");
    }

    @Test
    void rechazaSalidaIgualDentroOQueContieneAlProyecto() {
        assertThatThrownBy(() -> new EscritorSeguro(proyecto, proyecto)).isInstanceOf(SeguridadException.class)
                .hasMessageContaining("misma");
        assertThatThrownBy(() -> new EscritorSeguro(proyecto, proyecto.resolve("salida"))).isInstanceOf(SeguridadException.class)
                .hasMessageContaining("dentro del proyecto");
        assertThatThrownBy(() -> new EscritorSeguro(proyecto, tmp)).isInstanceOf(SeguridadException.class)
                .hasMessageContaining("contiene al proyecto");
        assertThat(proyecto.resolve("salida")).doesNotExist();
    }

    @Test
    void rechazaRutasConPuntosQueAcabanEnElProyecto() {
        Path salida = tmp.resolve("otra").resolve("..").resolve("proyecto").resolve("x");
        assertThatThrownBy(() -> new EscritorSeguro(proyecto, salida)).isInstanceOf(SeguridadException.class)
                .hasMessageContaining("dentro del proyecto");
        assertThat(proyecto.resolve("x")).doesNotExist();
    }

    @Test
    void rechazaEnlaceQueAcabaDentroDelProyecto() throws Exception {
        Path link = tmp.resolve("atajo");
        enlace(link, proyecto);
        assertThatThrownBy(() -> new EscritorSeguro(proyecto, link.resolve("salida"))).isInstanceOf(SeguridadException.class);
        assertThat(proyecto.resolve("salida")).doesNotExist();
    }

    @Test
    void enlaceInternoDeLaSalidaHaciaElProyectoAbortaLaEscritura() throws Exception {
        Path salida = tmp.resolve("salida");
        EscritorSeguro esc = new EscritorSeguro(proyecto, salida);
        esc.crearDirectorios(salida);
        enlace(salida.resolve("trampa"), proyecto);
        assertThatThrownBy(() -> esc.escribir(salida.resolve("trampa").resolve("x.txt"), "x")).isInstanceOf(SeguridadException.class);
        assertThatThrownBy(() -> esc.escribirTodo(Map.of(salida.resolve("ok.txt"), "ok", salida.resolve("trampa").resolve("y.txt"), "y")))
                .isInstanceOf(SeguridadException.class);
        assertThat(proyecto.resolve("x.txt")).doesNotExist();
        assertThat(proyecto.resolve("y.txt")).doesNotExist();
        assertThat(salida.resolve("ok.txt")).doesNotExist();
    }

    @Test
    void rechazaAccesoDirectoLnkYArchivosComoCarpeta() throws Exception {
        Fixtures.escribir(tmp.resolve("acceso.lnk"), "no es una carpeta");
        assertThatThrownBy(() -> new EscritorSeguro(proyecto, tmp.resolve("acceso.lnk").resolve("x")))
                .isInstanceOf(SeguridadException.class).hasMessageContaining("acceso directo");
        assertThatThrownBy(() -> new EscritorSeguro(proyecto, tmp.resolve("acceso")))
                .isInstanceOf(SeguridadException.class).hasMessageContaining("acceso directo");
        Fixtures.escribir(tmp.resolve("archivo.txt"), "x");
        assertThatThrownBy(() -> new EscritorSeguro(proyecto, tmp.resolve("archivo.txt").resolve("x")))
                .isInstanceOf(SeguridadException.class).hasMessageContaining("archivo");
    }

    @Test
    void borrarTemporalNoSigueEnlacesNiBorraFuera() throws Exception {
        Path salida = tmp.resolve("salida");
        EscritorSeguro esc = new EscritorSeguro(proyecto, salida);
        Path t = esc.carpetaTemporal(esc.temporal().resolve("mod"));
        esc.escribir(t.resolve("a.txt"), "a");
        enlace(t.resolve("enlace"), proyecto);
        esc.borrarTemporal(esc.temporal());
        assertThat(proyecto.resolve("src/main/java/a/A.java")).exists();
        assertThat(esc.temporal()).doesNotExist();
        assertThatThrownBy(() -> esc.borrarTemporal(salida)).isInstanceOf(SeguridadException.class);
        assertThatThrownBy(() -> esc.borrarTemporal(proyecto)).isInstanceOf(SeguridadException.class);
    }

    @Test
    void escrituraFueraDeLaSalidaSeRechaza() {
        EscritorSeguro esc = new EscritorSeguro(proyecto, tmp.resolve("salida"));
        assertThatThrownBy(() -> esc.escribir(tmp.resolve("fuera.txt"), "x")).isInstanceOf(SeguridadException.class);
        assertThatThrownBy(() -> esc.escribir(tmp.resolve("salida").resolve("..").resolve("proyecto").resolve("p.txt"), "x"))
                .isInstanceOf(SeguridadException.class);
        assertThat(tmp.resolve("fuera.txt")).doesNotExist();
        assertThat(proyecto.resolve("p.txt")).doesNotExist();
    }

    @Test
    void cliTerminaConCodigo3YNoCreaNada() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int codigo = new CliRunner(new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8))
                .ejecutar(new String[]{"scan", "--proyecto", proyecto.toString(), "--salida", proyecto.resolve("x").toString()});
        assertThat(codigo).isEqualTo(3);
        assertThat(proyecto.resolve("x")).doesNotExist();
        assertThat(out.toString(StandardCharsets.UTF_8)).startsWith("Modo solo lectura sobre");
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("Rechazo de seguridad");
        int uso = new CliRunner(new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8))
                .ejecutar(new String[]{"scan"});
        assertThat(uso).isEqualTo(2);
    }

    @Test
    void procesoSeguroSoloPermiteEjecutablesValidados() throws Exception {
        EscritorSeguro esc = new EscritorSeguro(proyecto, tmp.resolve("salida"));
        ProcesoSeguro ps = new ProcesoSeguro(esc);
        Path mvnw = tmp.resolve("mvnw.cmd");
        Fixtures.escribir(mvnw, "@echo off");
        assertThatThrownBy(() -> ps.maven(mvnw, ProcesoSeguro.GoalMaven.GET, null, Map.of(), null,
                Path.of(System.getProperty("java.home")), esc.temporal().resolve("r"), esc.temporal().resolve("l.log")))
                .isInstanceOf(SeguridadException.class).hasMessageContaining("mvnw");
        assertThatThrownBy(() -> ps.javac(tmp, java.util.List.of("-version"), esc.temporal().resolve("r"), esc.temporal().resolve("l.log")))
                .isInstanceOf(SeguridadException.class);
        Path jh = Path.of(System.getProperty("java.home"));
        assertThatThrownBy(() -> ps.java(jh, java.util.List.of("-version"), proyecto, esc.temporal().resolve("l.log"), null))
                .isInstanceOf(SeguridadException.class);
    }
}
