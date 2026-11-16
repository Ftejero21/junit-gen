package com.junitgen.mcp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RaicesTest {

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void uriDeVsCodeConUnidadCodificadaYEspacios() {
        assertThat(Raices.rutaDeUri("file:///c%3A/Users/usuario/Mi%20Proyecto")).isEqualTo("C:\\Users\\usuario\\Mi Proyecto");
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void uriConUnidadSinCodificar() {
        assertThat(Raices.rutaDeUri("file:///C:/a/b")).isEqualTo("C:\\a\\b");
        assertThat(Raices.rutaDeUri("FILE:///C:/a/b/")).isEqualTo("C:\\a\\b");
        assertThat(Raices.rutaDeUri("file:///d:/")).isEqualTo("D:\\");
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void uriConLocalhost() {
        assertThat(Raices.rutaDeUri("file://localhost/c:/a")).isEqualTo("C:\\a");
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void uriUnc() {
        assertThat(Raices.rutaDeUri("file://srv/rec/dir")).isEqualTo("\\\\srv\\rec\\dir");
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void uriConUtf8YMasLiteral() {
        assertThat(Raices.rutaDeUri("file:///c%3A/Espa%C3%B1a/a+b")).isEqualTo("C:\\España\\a+b");
    }

    @Test
    void utf8YMasLiteralSinDependerDelSeparador() {
        assertThat(Raices.rutaDeUri("file:///c%3A/Espa%C3%B1a/a+b")).contains("España").endsWith("a+b");
    }

    @Test
    void esquemaQueNoEsFileLanzaExcepcion() {
        assertThatThrownBy(() -> Raices.rutaDeUri("vscode-remote://ssh-remote+host/home/x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Raices.rutaDeUri("untitled:Untitled-1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Raices.rutaDeUri("file:///c%3/x")).isInstanceOf(IllegalArgumentException.class);
    }
}
