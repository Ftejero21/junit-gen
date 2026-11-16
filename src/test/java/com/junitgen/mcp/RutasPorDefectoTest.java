package com.junitgen.mcp;

import com.junitgen.seguridad.PreferenciasSeguras;
import com.junitgen.seguridad.SeguridadException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RutasPorDefectoTest {

    @TempDir
    Path tmp;

    private RutasPorDefecto rutas(Path base) {
        return new RutasPorDefecto(new PreferenciasSeguras(base, EntornoSesion.ARCHIVO_RUTAS));
    }

    @Test
    void idaYVueltaConservaOtrosProyectos() throws Exception {
        Path base = Files.createDirectories(tmp.resolve("instalacion")).toRealPath();
        Path a = Files.createDirectories(tmp.resolve("A")).toRealPath();
        Path b = Files.createDirectories(tmp.resolve("B")).toRealPath();
        RutasPorDefecto r = rutas(base);
        assertThat(r.disponible()).isTrue();
        assertThat(r.de(a)).isNull();
        r.guardar(a, tmp.resolve("salidaA"));
        rutas(base).guardar(b, tmp.resolve("salidaB"));
        assertThat(rutas(base).de(a)).isEqualTo(tmp.resolve("salidaA"));
        assertThat(rutas(base).de(b)).isEqualTo(tmp.resolve("salidaB"));
        String json = Files.readString(base.resolve(EntornoSesion.ARCHIVO_RUTAS), StandardCharsets.UTF_8);
        assertThat(com.junitgen.util.Json.leer(json).get("version").asInt()).isEqualTo(1);
        assertThat(com.junitgen.util.Json.leer(json).get("rutas").size()).isEqualTo(2);
    }

    @Test
    void archivoCorruptoSeIgnoraYSeReescribe() throws Exception {
        Path base = Files.createDirectories(tmp.resolve("instalacion")).toRealPath();
        Path a = Files.createDirectories(tmp.resolve("A")).toRealPath();
        Files.writeString(base.resolve(EntornoSesion.ARCHIVO_RUTAS), "{esto no es json", StandardCharsets.UTF_8);
        RutasPorDefecto r = rutas(base);
        assertThat(r.de(a)).isNull();
        r.guardar(a, tmp.resolve("salida"));
        assertThat(rutas(base).de(a)).isEqualTo(tmp.resolve("salida"));
        Files.writeString(base.resolve(EntornoSesion.ARCHIVO_RUTAS), "{\"version\":7,\"rutas\":{}}", StandardCharsets.UTF_8);
        assertThat(rutas(base).de(a)).as("versión desconocida").isNull();
    }

    @Test
    void sinInstalacionNoHayPersistenciaYBaseDentroDelProyectoSeRechaza() throws Exception {
        RutasPorDefecto sin = new RutasPorDefecto(null);
        assertThat(sin.disponible()).isFalse();
        assertThat(sin.de(tmp)).isNull();
        assertThatThrownBy(() -> sin.guardar(tmp, tmp.resolve("x"))).isInstanceOf(IllegalStateException.class);

        Path proyecto = Files.createDirectories(tmp.resolve("proyecto")).toRealPath();
        Path base = Files.createDirectories(proyecto.resolve("herramientas"));
        assertThatThrownBy(() -> rutas(base).guardar(proyecto, tmp.resolve("salida"))).isInstanceOf(SeguridadException.class);
        assertThat(base.resolve(EntornoSesion.ARCHIVO_RUTAS)).doesNotExist();
    }
}
