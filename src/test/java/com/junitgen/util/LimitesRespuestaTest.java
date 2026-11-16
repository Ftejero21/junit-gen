package com.junitgen.util;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class LimitesRespuestaTest {

    @Test
    void recortarCortaEnFronteraDeCaracterYDeclaraElRecorte() {
        String s = "ñandú ".repeat(100);
        String r = LimitesRespuesta.recortar(s, 51);
        assertThat(LimitesRespuesta.bytes(r)).isLessThanOrEqualTo(51);
        assertThat(r).endsWith(LimitesRespuesta.MARCA_RECORTE);
        assertThat(s).startsWith(r.substring(0, r.length() - LimitesRespuesta.MARCA_RECORTE.length()));
        assertThat(LimitesRespuesta.recortar("corto", 100)).isEqualTo("corto");
    }

    @Test
    void listaCortaUsaElFormatoDeListYCuentaElResto() {
        assertThat(LimitesRespuesta.listaCorta(List.of("a", "b"), 10)).isEqualTo(List.of("a", "b").toString());
        assertThat(LimitesRespuesta.listaCorta(List.of("a", "b", "c"), 2)).isEqualTo("[a, b, … (+1 más)]");
        assertThat(LimitesRespuesta.listaCorta(List.of(), 2)).isEqualTo("[]");
    }

    @Test
    void partirReproduceElOriginalSinSuperarElMaximo() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            sb.append("línea ").append(i).append(" con acentos: áéíóú €\n");
        }
        sb.append("x".repeat(5_000)).append("ñ".repeat(2_000)).append("\nfin sin salto");
        String texto = sb.toString();
        List<String> partes = LimitesRespuesta.partir(texto, 1_000);
        assertThat(String.join("", partes)).isEqualTo(texto);
        assertThat(partes).allSatisfy(p -> assertThat(LimitesRespuesta.bytes(p)).isLessThanOrEqualTo(1_000));
        assertThat(partes.size()).isGreaterThan(10);
        assertThat(LimitesRespuesta.partir("", 10)).containsExactly("");
    }

    @Test
    void paginarConservaTodosLosBloquesEnOrdenYRespetaLosLimites() {
        List<String> bloques = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            bloques.add("bloque " + i + " " + "z".repeat(i * 7) + "\n");
        }
        bloques.add("grande\n".repeat(400));
        List<List<String>> paginas = LimitesRespuesta.paginar(bloques, 600, 5);
        StringBuilder junto = new StringBuilder();
        for (List<String> p : paginas) {
            assertThat(p.size()).isLessThanOrEqualTo(5);
            assertThat(LimitesRespuesta.bytes(String.join("", p))).isLessThanOrEqualTo(600);
            p.forEach(junto::append);
        }
        assertThat(junto.toString()).isEqualTo(String.join("", bloques));
        assertThat(LimitesRespuesta.paginar(List.of(), 100, 0)).hasSize(1);
    }

    @Test
    void acotarJsonRecortaEnOrdenAnotaYNoTocaLasClavesProtegidas() {
        ObjectNode r = Json.objeto();
        ObjectNode resumen = r.putObject("resumenSesion");
        ArrayNode protegida = resumen.putArray("lista");
        for (int i = 0; i < 100; i++) {
            protegida.add("protegido-" + i);
        }
        ArrayNode avisos = r.putArray("avisos");
        ArrayNode errores = r.putArray("errores");
        for (int i = 0; i < 200; i++) {
            avisos.add("aviso número " + i + " con texto de relleno");
            errores.add("error número " + i + " con texto de relleno");
        }
        r.put("texto", "t".repeat(3_000));
        int protegidoAntes = Json.compacto(resumen).length();
        boolean recortado = LimitesRespuesta.acotarJson(r, 4_000, List.of("avisos", "errores"), Set.of("errores"), "pide el resto");
        assertThat(recortado).isTrue();
        assertThat(LimitesRespuesta.bytes(Json.compacto(r))).isLessThanOrEqualTo(4_000);
        assertThat(Json.compacto(r.get("resumenSesion")).length()).isEqualTo(protegidoAntes);
        assertThat(r.get("avisos").size()).isEqualTo(1);
        assertThat(r.get("errores").size()).isGreaterThanOrEqualTo(LimitesRespuesta.MIN_LISTA_MARCADA);
        assertThat(r.get("recortes").get("avisos").asText()).isEqualTo("mostrados 1 de 200");
        assertThat(r.get("comoVerElResto").asText()).isEqualTo("pide el resto");
        assertThat(r.get("avisos").get(0).asText()).isEqualTo("aviso número 0 con texto de relleno");

        ObjectNode pequeno = Json.objeto();
        pequeno.putArray("avisos").add("a");
        assertThat(LimitesRespuesta.acotarJson(pequeno, 4_000, List.of("avisos"), "x")).isFalse();
        assertThat(pequeno.has("recortes")).isFalse();
    }
}
