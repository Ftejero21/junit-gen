package com.junitgen.escaneo;

import com.junitgen.proyecto.LocalizadorModulos;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Resultado del escaneo de una raíz (uno o varios módulos). */
public class ProyectoEscaneado {

    public Path raiz;
    public LocalizadorModulos.Resultado localizacion;
    public final List<ModuloEscaneado> modulos = new ArrayList<>();
    public final List<String> avisos = new ArrayList<>();
    public long instante = System.currentTimeMillis();

    public record Encontrada(ModuloEscaneado modulo, InfoClase clase) {
    }

    public ModuloEscaneado modulo(String rel) {
        if (rel == null) {
            return modulos.size() == 1 ? modulos.get(0) : null;
        }
        String r = rel.replace('\\', '/').replaceAll("^/+|/+$", "");
        for (ModuloEscaneado m : modulos) {
            if (m.modulo.rel.equals(r) || m.modulo.nombre().equals(r)) {
                return m;
            }
        }
        for (ModuloEscaneado m : modulos) {
            if (m.modulo.rel.equalsIgnoreCase(r) || m.modulo.nombre().equalsIgnoreCase(r)) {
                return m;
            }
        }
        return null;
    }

    /** Busca una clase de main por FQN o nombre simple (opcionalmente en un módulo). */
    public List<Encontrada> buscarClase(String clase, String modulo) {
        List<Encontrada> r = new ArrayList<>();
        for (ModuloEscaneado m : modulos) {
            if (modulo != null && !modulo.isBlank() && m != modulo(modulo)) {
                continue;
            }
            InfoClase c = m.indice.get(clase);
            if (c != null && !c.esTest) {
                r.add(new Encontrada(m, c));
                continue;
            }
            if (!clase.contains(".")) {
                for (InfoClase x : m.indice.porNombreSimple(clase)) {
                    if (!x.esTest) {
                        r.add(new Encontrada(m, x));
                    }
                }
            }
        }
        return r;
    }

    /**
     * Busca la clase de un servicio en todos los módulos; los módulos del servicio y del proyecto solo son pistas para
     * desambiguar (en ese orden). Devuelve vacío, un único resultado o los candidatos que siguen ambiguos.
     */
    public List<Encontrada> resolverServicio(String clase, String moduloServicio, String moduloProyecto) {
        List<Encontrada> todas = buscarClase(clase, null);
        if (todas.size() <= 1) {
            return todas;
        }
        for (String pista : new String[]{moduloServicio, moduloProyecto}) {
            if (pista == null || pista.isBlank()) {
                continue;
            }
            ModuloEscaneado m = modulo(pista);
            if (m == null) {
                continue;
            }
            List<Encontrada> filtro = new ArrayList<>();
            for (Encontrada e : todas) {
                if (e.modulo() == m) {
                    filtro.add(e);
                }
            }
            if (!filtro.isEmpty()) {
                todas = filtro;
                if (todas.size() == 1) {
                    return todas;
                }
            }
        }
        return todas;
    }
}
