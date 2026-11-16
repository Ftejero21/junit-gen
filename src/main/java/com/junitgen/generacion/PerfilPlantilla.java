package com.junitgen.generacion;

import com.junitgen.proyecto.VersionesProyecto;
import com.junitgen.spec.EstiloSpec;
import com.junitgen.util.Textos;

/** Perfil de plantilla según JUnit 4/5, Mockito (runner/extensión/openMocks) y estilo de aserciones. */
public class PerfilPlantilla {

    public int junit;
    public boolean mockito;
    public String versionMockito;
    public boolean extension;
    public boolean openMocks;
    public boolean initMocks;
    public boolean runner;
    public String paqueteRunner;
    public boolean assertj;
    public int java;
    public boolean assertThrows;
    public boolean verifyNoInteractions;
    public boolean thenThrowClase;
    /** Mockito.lenient() disponible (Mockito ≥ 2.20.0 o versión desconocida). */
    public boolean lenient;
    public boolean displayName;
    public boolean comentariosGwt;
    public boolean clasePublica;
    public String patronNombre;
    public String nombreSut;

    public static PerfilPlantilla de(VersionesProyecto v, EstiloSpec estilo) {
        PerfilPlantilla p = new PerfilPlantilla();
        p.junit = v.junitElegido == 4 ? 4 : 5;
        p.mockito = v.mockito;
        p.versionMockito = v.versionMockito;
        p.java = v.javaEfectivo();
        boolean mockitoDesconocido = v.versionMockito == null;
        if (p.mockito) {
            if (p.junit == 5) {
                if (v.mockitoJunitJupiter) {
                    p.extension = true;
                } else if (mockitoDesconocido || Textos.versionAlMenos(v.versionMockito, "3.4.0")) {
                    p.openMocks = true;
                } else {
                    p.initMocks = true;
                }
            } else {
                p.runner = true;
                p.paqueteRunner = mockitoDesconocido || Textos.versionAlMenos(v.versionMockito, "2.1.0")
                        ? "org.mockito.junit" : "org.mockito.runners";
            }
        }
        p.assertj = estilo != null && "assertj".equals(estilo.aserciones) && v.assertj && p.java >= 8;
        p.assertThrows = p.java >= 8 && (p.junit == 5 || (v.versionJunit4 != null && Textos.versionAlMenos(v.versionJunit4, "4.13")));
        p.verifyNoInteractions = mockitoDesconocido || Textos.versionAlMenos(v.versionMockito, "3.0.1");
        p.thenThrowClase = mockitoDesconocido || Textos.versionAlMenos(v.versionMockito, "2.0.0");
        p.lenient = mockitoDesconocido || Textos.versionAlMenos(v.versionMockito, "2.20.0");
        p.displayName = estilo != null && Boolean.TRUE.equals(estilo.displayName) && p.junit == 5;
        p.comentariosGwt = estilo != null && Boolean.TRUE.equals(estilo.comentariosGwt);
        p.clasePublica = p.junit == 4 || (estilo != null && "public".equals(estilo.visibilidadClase));
        p.patronNombre = estilo != null && estilo.patronNombre != null ? estilo.patronNombre : "{metodo}_{escenario}";
        p.nombreSut = estilo != null && estilo.nombreCampoSut != null ? estilo.nombreCampoSut : "service";
        return p;
    }

    public String nombre() {
        StringBuilder sb = new StringBuilder("JUnit ").append(junit);
        if (extension) {
            sb.append(" + MockitoExtension");
        } else if (openMocks) {
            sb.append(" + MockitoAnnotations.openMocks");
        } else if (initMocks) {
            sb.append(" + MockitoAnnotations.initMocks");
        } else if (runner) {
            sb.append(" + MockitoJUnitRunner (").append(paqueteRunner).append(')');
        } else {
            sb.append(" sin Mockito");
        }
        sb.append(assertj ? " + AssertJ" : " + aserciones JUnit");
        sb.append(" (Java ").append(java).append(')');
        return sb.toString();
    }

    /** Asserts JUnit: paquete de la clase de aserciones. */
    public String claseAsserts() {
        return junit == 5 ? "org.junit.jupiter.api.Assertions" : "org.junit.Assert";
    }
}
