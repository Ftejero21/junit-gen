package com.junitgen.seguridad;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PreferenciasSegurasTest {

    @TempDir
    Path tmp;

    @Test
    void idaYVuelta() throws Exception {
        Path base = Files.createDirectories(tmp.resolve("instalacion"));
        Path proyecto = Files.createDirectories(tmp.resolve("proyecto"));
        PreferenciasSeguras p = new PreferenciasSeguras(base, "rutas-por-defecto.json");
        assertThat(p.leer()).isNull();
        p.escribir("{\"a\":1}", proyecto);
        assertThat(p.leer()).isEqualTo("{\"a\":1}");
        assertThat(Files.readString(base.resolve("rutas-por-defecto.json"), StandardCharsets.UTF_8)).isEqualTo("{\"a\":1}");
    }

    @Test
    void baseDentroDelProyectoSeRechazaSinEscribir() throws Exception {
        Path proyecto = Files.createDirectories(tmp.resolve("proyecto"));
        Path base = Files.createDirectories(proyecto.resolve("sub"));
        PreferenciasSeguras p = new PreferenciasSeguras(base, "rutas-por-defecto.json");
        assertThatThrownBy(() -> p.escribir("{}", proyecto)).isInstanceOf(SeguridadException.class).hasMessageContaining("dentro del proyecto");
        PreferenciasSeguras igual = new PreferenciasSeguras(proyecto, "rutas-por-defecto.json");
        assertThatThrownBy(() -> igual.escribir("{}", proyecto)).isInstanceOf(SeguridadException.class);
        assertThat(base.resolve("rutas-por-defecto.json")).doesNotExist();
        assertThat(proyecto.resolve("rutas-por-defecto.json")).doesNotExist();
    }

    @Test
    void destinoCarpetaNombreInvalidoYLimiteDeLectura() throws Exception {
        Path base = Files.createDirectories(tmp.resolve("instalacion"));
        Files.createDirectories(base.resolve("rutas-por-defecto.json"));
        PreferenciasSeguras p = new PreferenciasSeguras(base, "rutas-por-defecto.json");
        assertThatThrownBy(() -> p.escribir("{}", null)).isInstanceOf(SeguridadException.class).hasMessageContaining("es una carpeta");
        assertThat(p.leer()).isNull();
        assertThatThrownBy(() -> new PreferenciasSeguras(base, "..\\fuera.json")).isInstanceOf(SeguridadException.class);
        assertThatThrownBy(() -> new PreferenciasSeguras(base, "sub/x.json")).isInstanceOf(SeguridadException.class);

        PreferenciasSeguras grande = new PreferenciasSeguras(base, "grande.json");
        Files.write(base.resolve("grande.json"), new byte[PreferenciasSeguras.MAX_BYTES + 1]);
        assertThat(grande.leer()).isNull();
    }
}
