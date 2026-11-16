package com.junitgen.cli;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Parseo manual de argumentos "--clave valor" y "--bandera". */
public class Argumentos {

    public static class ErrorUso extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public ErrorUso(String mensaje) {
            super(mensaje);
        }
    }

    private final Map<String, String> valores = new LinkedHashMap<>();
    private final Set<String> banderas = new LinkedHashSet<>();

    public static Argumentos parsear(String[] args, int desde, Set<String> opcionesConValor, Set<String> banderasPermitidas) {
        Argumentos a = new Argumentos();
        for (int i = desde; i < args.length; i++) {
            String s = args[i];
            if (!s.startsWith("--")) {
                throw new ErrorUso("Argumento inesperado: " + s);
            }
            String clave = s.substring(2);
            String valor = null;
            int eq = clave.indexOf('=');
            if (eq > 0) {
                valor = clave.substring(eq + 1);
                clave = clave.substring(0, eq);
            }
            if (opcionesConValor.contains(clave)) {
                if (valor == null) {
                    if (i + 1 >= args.length) {
                        throw new ErrorUso("Falta el valor de --" + clave);
                    }
                    valor = args[++i];
                }
                a.valores.put(clave, valor);
            } else if (banderasPermitidas.contains(clave)) {
                a.banderas.add(clave);
            } else {
                throw new ErrorUso("Opción desconocida: --" + clave);
            }
        }
        return a;
    }

    public String valor(String clave) {
        return valores.get(clave);
    }

    public String obligatorio(String clave) {
        String v = valores.get(clave);
        if (v == null || v.isBlank()) {
            throw new ErrorUso("Falta la opción obligatoria --" + clave);
        }
        return v;
    }

    public boolean bandera(String clave) {
        return banderas.contains(clave);
    }

    public List<String> lista(String clave) {
        String v = valores.get(clave);
        if (v == null || v.isBlank()) {
            return new ArrayList<>();
        }
        List<String> r = new ArrayList<>();
        Arrays.stream(v.split(",")).map(String::trim).filter(x -> !x.isEmpty()).forEach(r::add);
        return r;
    }

    public int entero(String clave, int defecto) {
        String v = valores.get(clave);
        if (v == null) {
            return defecto;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            throw new ErrorUso("--" + clave + " debe ser un número: " + v);
        }
    }
}
