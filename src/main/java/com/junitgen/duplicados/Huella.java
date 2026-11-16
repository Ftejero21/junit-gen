package com.junitgen.duplicados;

import com.junitgen.util.Textos;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Huella de un caso (SHA-256 truncado a 16 hex).
 * <ul>
 *     <li>huellaEntrada = FQN#metodo(tiposParam)|args=[…]|stubs=ordenados[Tipo.llamada->devuelve|lanza]</li>
 *     <li>huella = huellaEntrada|esperado=tipo:valor</li>
 * </ul>
 */
public final class Huella {

    public static final String DESCONOCIDO = "?";

    public final String entrada;
    public final String completa;
    public final String esperado;
    public final String textoEntrada;

    private Huella(String entrada, String completa, String esperado, String textoEntrada) {
        this.entrada = entrada;
        this.completa = completa;
        this.esperado = esperado;
        this.textoEntrada = textoEntrada;
    }

    public boolean esperadoDesconocido() {
        return DESCONOCIDO.equals(esperado);
    }

    public static Huella calcular(String fqn, String metodo, List<String> tiposParam, List<String> args,
                                  Collection<String> stubs, String esperado) {
        List<String> ordenados = new ArrayList<>(stubs);
        Collections.sort(ordenados);
        String tipos = tiposParam == null ? "?" : String.join(",", tiposParam);
        String texto = fqn + "#" + metodo + "(" + tipos + ")|args=[" + String.join(",", args) + "]|stubs=["
                + String.join(";", ordenados) + "]";
        String esp = esperado == null ? DESCONOCIDO : esperado;
        String entrada = Textos.sha256Hex(texto, 16);
        String completa = Textos.sha256Hex(texto + "|esperado=" + esp, 16);
        return new Huella(entrada, completa, esp, texto);
    }

    public static Huella desdeMarcador(String huella, String entrada) {
        return new Huella(entrada, huella, "(generado)", null);
    }
}
