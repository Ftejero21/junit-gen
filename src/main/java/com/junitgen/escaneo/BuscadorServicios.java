package com.junitgen.escaneo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Búsqueda de un servicio por nombre en todos los módulos, por niveles (gana el primero con resultado):
 * (1a) nombre simple o FQN exacto; (1b) igual sin distinguir mayúsculas; (2) solo nombre simple:
 * {@code nombre+"Impl"} o el nombre sin el sufijo {@code Impl}, sin distinguir mayúsculas. Una interfaz
 * se sustituye por todas sus implementaciones (en cualquier módulo). Solo es ambiguo si quedan varios
 * grupos distintos; una interfaz y sus implementaciones forman un único grupo.
 */
public final class BuscadorServicios {

    public static final String VIA_EXACTA = "exacta";
    public static final String VIA_SIN_MAYUSCULAS = "sin mayúsculas";
    public static final String VIA_INTERFAZ = "interfaz → implementación";
    public static final String VIA_SUFIJO_IMPL = "sufijo Impl";
    private static final String IMPL = "impl";

    /**
     * Resultado de la búsqueda: {@code objetivos} (clases a tratar; vacío si no se resolvió),
     * {@code candidatos} (coincidencias cuando es ambiguo), la vía de la coincidencia y si el único
     * grupo encontrado es una interfaz sin implementaciones.
     */
    public record Resultado(List<ProyectoEscaneado.Encontrada> objetivos, List<ProyectoEscaneado.Encontrada> candidatos,
                            String via, boolean interfazSinImplementacion) {

        public boolean resuelto() {
            return !objetivos.isEmpty();
        }

        public boolean ambiguo() {
            return objetivos.isEmpty() && candidatos.size() > 1;
        }

        public boolean noEncontrado() {
            return objetivos.isEmpty() && candidatos.size() <= 1;
        }
    }

    private BuscadorServicios() {
    }

    public static Resultado buscar(ProyectoEscaneado p, String nombre, String modulo) {
        if (nombre == null || nombre.isBlank()) {
            return new Resultado(List.of(), List.of(), null, false);
        }
        String via = VIA_EXACTA;
        List<ProyectoEscaneado.Encontrada> cands = p.buscarClase(nombre, modulo);
        if (cands.isEmpty()) {
            via = VIA_SIN_MAYUSCULAS;
            cands = sinMayusculas(p, nombre, modulo);
        }
        if (cands.isEmpty() && !nombre.contains(".")) {
            via = VIA_SUFIJO_IMPL;
            cands = porSufijoImpl(p, nombre, modulo);
        }
        if (cands.isEmpty()) {
            return new Resultado(List.of(), List.of(), null, false);
        }
        // Grupos: cada interfaz con sus implementaciones; una clase que implementa una interfaz encontrada se une a su grupo.
        Map<String, List<ProyectoEscaneado.Encontrada>> grupos = new LinkedHashMap<>();
        Map<String, Boolean> esInterfaz = new LinkedHashMap<>();
        for (ProyectoEscaneado.Encontrada c : cands) {
            if (c.clase().tipo == InfoClase.Tipo.INTERFAZ) {
                String k = clave(c);
                grupos.put(k, implementaciones(p, c.clase()));
                esInterfaz.put(k, true);
            }
        }
        for (ProyectoEscaneado.Encontrada c : cands) {
            if (c.clase().tipo == InfoClase.Tipo.INTERFAZ) {
                continue;
            }
            boolean unida = false;
            for (Map.Entry<String, List<ProyectoEscaneado.Encontrada>> g : grupos.entrySet()) {
                if (esInterfaz.getOrDefault(g.getKey(), false) && contiene(g.getValue(), c)) {
                    unida = true;
                    break;
                }
            }
            if (!unida) {
                grupos.putIfAbsent(clave(c), new ArrayList<>(List.of(c)));
                esInterfaz.putIfAbsent(clave(c), false);
            }
        }
        if (grupos.size() > 1) {
            return new Resultado(List.of(), List.copyOf(cands), via, false);
        }
        Map.Entry<String, List<ProyectoEscaneado.Encontrada>> unico = grupos.entrySet().iterator().next();
        boolean interfaz = esInterfaz.getOrDefault(unico.getKey(), false);
        if (interfaz && unico.getValue().isEmpty()) {
            return new Resultado(List.of(), List.copyOf(cands), via, true);
        }
        return new Resultado(List.copyOf(unico.getValue()), List.copyOf(cands), interfaz ? VIA_INTERFAZ : via, false);
    }

    private static String clave(ProyectoEscaneado.Encontrada e) {
        return e.modulo().modulo.rel + ":" + e.clase().fqn;
    }

    private static boolean contiene(List<ProyectoEscaneado.Encontrada> l, ProyectoEscaneado.Encontrada e) {
        for (ProyectoEscaneado.Encontrada x : l) {
            if (x.modulo() == e.modulo() && x.clase().fqn.equals(e.clase().fqn)) {
                return true;
            }
        }
        return false;
    }

    /** Implementaciones concretas (no abstractas) de la interfaz en todos los módulos. */
    static List<ProyectoEscaneado.Encontrada> implementaciones(ProyectoEscaneado p, InfoClase interfaz) {
        List<ProyectoEscaneado.Encontrada> r = new ArrayList<>();
        for (ModuloEscaneado me : p.modulos) {
            for (InfoClase c : me.indice.implementaciones(interfaz)) {
                if (!c.abstracta) {
                    r.add(new ProyectoEscaneado.Encontrada(me, c));
                }
            }
        }
        return r;
    }

    private static boolean enModulo(ProyectoEscaneado p, ModuloEscaneado me, String modulo) {
        return modulo == null || modulo.isBlank() || me == p.modulo(modulo);
    }

    private static List<ProyectoEscaneado.Encontrada> sinMayusculas(ProyectoEscaneado p, String nombre, String modulo) {
        List<ProyectoEscaneado.Encontrada> r = new ArrayList<>();
        boolean fqn = nombre.contains(".");
        for (ModuloEscaneado me : p.modulos) {
            if (!enModulo(p, me, modulo)) {
                continue;
            }
            for (InfoClase c : me.indice.clasesMain()) {
                if (fqn ? c.fqn.equalsIgnoreCase(nombre) : c.simple.equalsIgnoreCase(nombre)) {
                    r.add(new ProyectoEscaneado.Encontrada(me, c));
                }
            }
        }
        return r;
    }

    private static List<ProyectoEscaneado.Encontrada> porSufijoImpl(ProyectoEscaneado p, String nombre, String modulo) {
        String conImpl = nombre + "Impl";
        String sinImpl = nombre.toLowerCase(Locale.ROOT).endsWith(IMPL) && nombre.length() > IMPL.length()
                ? nombre.substring(0, nombre.length() - IMPL.length()) : null;
        List<ProyectoEscaneado.Encontrada> r = new ArrayList<>();
        for (ModuloEscaneado me : p.modulos) {
            if (!enModulo(p, me, modulo)) {
                continue;
            }
            for (InfoClase c : me.indice.clasesMain()) {
                if (c.simple.equalsIgnoreCase(conImpl) || (sinImpl != null && c.simple.equalsIgnoreCase(sinImpl))) {
                    r.add(new ProyectoEscaneado.Encontrada(me, c));
                }
            }
        }
        return r;
    }
}
