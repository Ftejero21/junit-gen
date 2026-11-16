package com.junitgen.escaneo;

import com.junitgen.config.CargadorConfig;
import com.junitgen.config.ConfigProyecto;
import com.junitgen.proyecto.LocalizadorModulos;
import com.junitgen.proyecto.Modulo;
import com.junitgen.proyecto.ResolutorBom;
import com.junitgen.proyecto.VersionesProyecto;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/** Fase 1: escaneo sin IA de una raíz con uno o varios módulos. Solo lee. */
public final class Escaner {

    private Escaner() {
    }

    public static ProyectoEscaneado escanear(Path raiz, String soloModulo) {
        ProyectoEscaneado p = new ProyectoEscaneado();
        p.raiz = raiz;
        p.localizacion = LocalizadorModulos.localizar(raiz);
        p.avisos.addAll(p.localizacion.avisos);
        if (p.localizacion.modulos.isEmpty()) {
            p.avisos.add("No se ha encontrado ningún módulo Maven/Gradle con src/main/java bajo " + raiz + ".");
        }
        ResolutorBom resolutor = new ResolutorBom(raiz);
        for (Modulo m : p.localizacion.modulos) {
            if (soloModulo != null && !soloModulo.isBlank()) {
                String s = soloModulo.replace('\\', '/');
                if (!m.rel.equalsIgnoreCase(s) && !m.nombre().equalsIgnoreCase(s)) {
                    continue;
                }
            }
            p.modulos.add(escanearModulo(raiz, m, resolutor));
        }
        if (soloModulo != null && !soloModulo.isBlank() && p.modulos.isEmpty()) {
            p.avisos.add("No existe el módulo '" + soloModulo + "'. Módulos disponibles: "
                    + p.localizacion.modulos.stream().map(Modulo::nombre).collect(Collectors.joining(", ")));
        }
        return p;
    }

    static ModuloEscaneado escanearModulo(Path raiz, Modulo m, ResolutorBom resolutor) {
        ModuloEscaneado me = new ModuloEscaneado();
        me.modulo = m;
        me.config = CargadorConfig.cargar(raiz, m.dir);
        me.avisos.addAll(me.config.avisos);
        me.indice = IndiceTipos.construir(m.fuentesMain, m.fuentesTest);
        me.avisos.addAll(me.indice.avisos);
        List<List<String>> importsTests = new ArrayList<>();
        for (InfoClase t : me.indice.clasesTest()) {
            List<String> imps = new ArrayList<>(t.imports);
            imps.addAll(t.importsEstaticos);
            imps.addAll(t.importsComodin);
            importsTests.add(imps);
        }
        me.versiones = VersionesProyecto.calcular(m, raiz, resolutor, importsTests);
        me.beans = RegistroBeans.construir(me.indice, m);
        me.avisos.addAll(me.beans.avisos);
        ConfigProyecto cfg = me.config;
        me.deteccion = DetectorServicios.detectar(me.indice, me.beans, cfg);
        List<InfoClase> servicios = me.deteccion.servicios.stream().map(c -> c.clase).collect(Collectors.toList());
        me.deteccion.dudosos.forEach(c -> servicios.add(c.clase));
        for (InfoClase s : servicios) {
            me.analisis(s);
        }
        me.emparejamientos.putAll(EmparejadorTests.emparejar(servicios, me.indice));
        me.estilo = AnalizadorEstiloTests.analizar(me.indice, me.versiones);
        return me;
    }
}
