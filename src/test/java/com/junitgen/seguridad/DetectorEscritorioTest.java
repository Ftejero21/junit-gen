package com.junitgen.seguridad;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class DetectorEscritorioTest {

    @TempDir
    Path tmp;

    private static final Function<String, String> SIN_ENV = k -> null;

    private static byte[] reg(String valor, String tipo, Charset cs) {
        return ("\r\nHKEY_CURRENT_USER\\Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\User Shell Folders\r\n"
                + "    Desktop    " + tipo + "    " + valor + "\r\n\r\n").getBytes(cs);
    }

    @Test
    void parseaLaLineaDelEscritorio() {
        String salida = new String(reg("%USERPROFILE%\\Desktop", "REG_EXPAND_SZ", StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        assertThat(DetectorEscritorio.valorDeRegQuery(salida)).isEqualTo("%USERPROFILE%\\Desktop");
        assertThat(DetectorEscritorio.valorDeRegQuery("    Desktop    REG_SZ    D:\\Mi Escritorio  \r\n")).isEqualTo("D:\\Mi Escritorio");
        assertThat(DetectorEscritorio.valorDeRegQuery("    Personal    REG_SZ    D:\\Docs\r\n")).isNull();
        assertThat(DetectorEscritorio.valorDeRegQuery("ERROR: El sistema no pudo encontrar la clave")).isNull();
        assertThat(DetectorEscritorio.valorDeRegQuery(null)).isNull();
    }

    @Test
    void expandeVariablesYDescartaLasNoResueltas() throws Exception {
        assumeTrue(RutaSegura.WINDOWS);
        Path perfil = Files.createDirectories(tmp.resolve("perfil"));
        Path esc = Files.createDirectories(perfil.resolve("MiEscritorio"));
        Path home = Files.createDirectories(tmp.resolve("home"));
        Path homeDesktop = Files.createDirectories(home.resolve("Desktop"));
        Map<String, String> env = Map.of("PERFIL", perfil.toString());
        assertThat(DetectorEscritorio.detectar(() -> reg("%PERFIL%\\MiEscritorio", "REG_EXPAND_SZ", StandardCharsets.UTF_8),
                env::get, home)).isEqualTo(esc);
        assertThat(DetectorEscritorio.detectar(() -> reg("%NO_EXISTE%\\MiEscritorio", "REG_EXPAND_SZ", StandardCharsets.UTF_8),
                env::get, home)).as("variable sin resolver: se usa el fallback").isEqualTo(homeDesktop);
        assertThat(DetectorEscritorio.detectar(() -> null, env::get, home)).isEqualTo(homeDesktop);
    }

    @Test
    void decodificaRutasConAcentosEnLaCodificacionDeConsola() throws Exception {
        assumeTrue(RutaSegura.WINDOWS);
        Path esc = Files.createDirectories(tmp.resolve("Área común"));
        Path home = Files.createDirectories(tmp.resolve("home"));
        assertThat(DetectorEscritorio.detectar(() -> reg(esc.toString(), "REG_SZ", Charset.forName("IBM850")), SIN_ENV, home))
                .isEqualTo(esc);
    }

    @Test
    void ordenDeFallbacksYNull() throws Exception {
        Path home = Files.createDirectories(tmp.resolve("home"));
        assertThat(DetectorEscritorio.detectar(null, SIN_ENV, home)).isNull();
        Path escritorio = Files.createDirectories(home.resolve("Escritorio"));
        assertThat(DetectorEscritorio.detectar(null, SIN_ENV, home)).isEqualTo(escritorio);
        Path desktop = Files.createDirectories(home.resolve("Desktop"));
        assertThat(DetectorEscritorio.detectar(null, SIN_ENV, home)).isEqualTo(desktop);
        assertThat(DetectorEscritorio.detectar(null, SIN_ENV, null)).isNull();
    }

    @Test
    void oneDriveComoUltimoRecurso() throws Exception {
        assumeTrue(RutaSegura.WINDOWS);
        Path home = Files.createDirectories(tmp.resolve("home"));
        Path deHome = Files.createDirectories(home.resolve("OneDrive").resolve("Escritorio"));
        assertThat(DetectorEscritorio.detectar(null, SIN_ENV, home)).isEqualTo(deHome);
        Path oneDrive = Files.createDirectories(tmp.resolve("od").resolve("Desktop"));
        Map<String, String> env = Map.of("OneDrive", tmp.resolve("od").toString());
        assertThat(DetectorEscritorio.detectar(null, env::get, home)).as("%OneDrive% antes que home\\OneDrive").isEqualTo(oneDrive);
    }
}
