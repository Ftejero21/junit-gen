package com.junitgen.proyecto;

import com.junitgen.util.Textos;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Versiones del proyecto relevantes para la plantilla: Java, JUnit 4/5, Mockito y AssertJ.
 * Se resuelven estáticamente (pom + parents + BOMs, Gradle por regex) con respaldo en los imports
 * de los tests existentes.
 */
public class VersionesProyecto {

    public Integer java;
    public String origenJava;
    public boolean junit5;
    public String versionJunit5;
    public boolean junit4;
    public String versionJunit4;
    /** 5, 4 o 0 si no hay JUnit. */
    public int junitElegido;
    public boolean mockito;
    public String versionMockito;
    public boolean mockitoJunitJupiter;
    public boolean mockitoInline;
    public boolean assertj;
    public String versionAssertj;
    public String springBoot;
    public String origen;
    public final List<String> avisos = new ArrayList<>();

    public int javaEfectivo() {
        return java == null ? 8 : java;
    }

    public boolean permiteMockStatic() {
        return mockito && (Textos.versionAlMenos(versionMockito, "5.0.0")
                || (mockitoInline && Textos.versionAlMenos(versionMockito, "3.4.0")));
    }

    public boolean permiteMockConstruction() {
        return mockito && (Textos.versionAlMenos(versionMockito, "5.0.0")
                || (mockitoInline && Textos.versionAlMenos(versionMockito, "3.5.0")));
    }

    public static Integer parsearJava(String v) {
        if (v == null) {
            return null;
        }
        String s = v.trim();
        if (s.startsWith("1.")) {
            s = s.substring(2);
        }
        int i = 0;
        while (i < s.length() && Character.isDigit(s.charAt(i))) {
            i++;
        }
        if (i == 0) {
            return null;
        }
        try {
            return Integer.parseInt(s.substring(0, i));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public String resumen() {
        StringBuilder sb = new StringBuilder();
        sb.append("Java ").append(java == null ? "desconocido (se asume 8)" : java);
        if (junitElegido == 5) {
            sb.append(", JUnit 5").append(versionJunit5 != null ? " (" + versionJunit5 + ")" : "");
        } else if (junitElegido == 4) {
            sb.append(", JUnit 4").append(versionJunit4 != null ? " (" + versionJunit4 + ")" : "");
        } else {
            sb.append(", sin JUnit");
        }
        if (junit4 && junit5) {
            sb.append(" [conviven JUnit 4 y 5]");
        }
        sb.append(mockito ? ", Mockito " + (versionMockito != null ? versionMockito : "(versión desconocida)") : ", sin Mockito");
        if (mockitoJunitJupiter) {
            sb.append(" + mockito-junit-jupiter");
        }
        if (mockitoInline) {
            sb.append(" + mockito-inline");
        }
        sb.append(assertj ? ", AssertJ " + (versionAssertj != null ? versionAssertj : "(versión desconocida)") : ", sin AssertJ");
        if (springBoot != null) {
            sb.append(", Spring Boot ").append(springBoot);
        }
        return sb.toString();
    }

    /** Calcula las versiones de un módulo. {@code importsTests}: imports de cada test existente. */
    public static VersionesProyecto calcular(Modulo m, Path raiz, ResolutorBom resolutor, List<List<String>> importsTests) {
        VersionesProyecto v = new VersionesProyecto();
        if (m.build == Modulo.TipoBuild.MAVEN && m.pom != null) {
            v.origen = "pom.xml";
            ResolutorBom.ModeloEfectivo me = resolutor.resolver(m.pom);
            v.avisos.addAll(me.avisos);
            javaMaven(v, m.pom, me);
            for (Pom.Dep d : me.dependencias) {
                String g = me.interpolar(d.groupId());
                String a = me.interpolar(d.artifactId());
                String ver = me.version(d);
                aplicarDependencia(v, g, a, ver, me);
            }
        } else if (m.build == Modulo.TipoBuild.GRADLE) {
            v.origen = m.archivoBuild.getFileName().toString();
            LectorGradle.InfoGradle g = LectorGradle.leer(m.dir, raiz);
            v.java = g.java;
            v.origenJava = g.java != null ? "build.gradle" : null;
            v.springBoot = g.springBoot;
            ResolutorBom.ModeloEfectivo bootBom = null;
            if (g.springBoot != null) {
                Path pomBoot = resolutor.rutaEnRepo("org.springframework.boot", "spring-boot-dependencies", g.springBoot, "pom");
                if (pomBoot != null && Files.isRegularFile(pomBoot)) {
                    try {
                        bootBom = resolutor.resolver(LectorPom.leer(pomBoot));
                    } catch (java.io.IOException e) {
                        v.avisos.add("No se pudo leer el BOM de Spring Boot: " + e.getMessage());
                    }
                }
            }
            for (LectorGradle.Coordenada c : g.dependencias) {
                String ver = c.version();
                if (ver == null && bootBom != null) {
                    ver = bootBom.versionGestionada(c.groupId() + ":" + c.artifactId());
                }
                aplicarDependencia(v, c.groupId(), c.artifactId(), ver, bootBom);
            }
            if (g.junitPlatform && !v.junit5) {
                v.junit5 = true;
            }
        }
        respaldoImports(v, importsTests);
        elegirJunit(v, importsTests);
        if (v.java == null) {
            v.avisos.add("No se pudo determinar la versión de Java; se asume Java 8 para el código generado.");
        }
        if (v.junitElegido == 0) {
            v.avisos.add("El proyecto no tiene JUnit como dependencia de test: añade JUnit 5 (o 4) antes de copiar los tests.");
        }
        if (!v.mockito) {
            v.avisos.add("El proyecto no tiene Mockito como dependencia de test: solo se podrán generar tests sin mocks.");
        }
        return v;
    }

    private static void javaMaven(VersionesProyecto v, Pom pom, ResolutorBom.ModeloEfectivo me) {
        String[][] orden = {
                {"maven.compiler.release", me.propiedad("maven.compiler.release")},
                {"maven.compiler.source", me.propiedad("maven.compiler.source")},
                {"maven.compiler.target", me.propiedad("maven.compiler.target")},
                {"java.version", me.propiedad("java.version")}};
        for (String[] o : orden) {
            Integer j = parsearJava(me.interpolar(o[1]));
            if (j != null) {
                v.java = j;
                v.origenJava = o[0];
                return;
            }
        }
        for (Pom p : me.cadena) {
            for (String c : new String[]{p.compilerRelease, p.compilerSource, p.compilerTarget}) {
                Integer j = parsearJava(me.interpolar(c));
                if (j != null) {
                    v.java = j;
                    v.origenJava = "maven-compiler-plugin";
                    return;
                }
            }
        }
    }

    private static void aplicarDependencia(VersionesProyecto v, String g, String a, String ver, ResolutorBom.ModeloEfectivo me) {
        if (g == null || a == null) {
            return;
        }
        if (g.equals("org.junit.jupiter")) {
            v.junit5 = true;
            if (v.versionJunit5 == null) {
                v.versionJunit5 = ver;
            }
        } else if (g.equals("junit") && a.equals("junit")) {
            v.junit4 = true;
            v.versionJunit4 = ver;
        } else if (g.equals("org.junit.vintage")) {
            v.junit4 = true;
        } else if (g.equals("org.mockito") && (a.equals("mockito-core") || a.equals("mockito-junit-jupiter")
                || a.equals("mockito-inline") || a.equals("mockito-all"))) {
            v.mockito = true;
            if (a.equals("mockito-junit-jupiter")) {
                v.mockitoJunitJupiter = true;
            }
            if (a.equals("mockito-inline")) {
                v.mockitoInline = true;
            }
            if (ver != null && (v.versionMockito == null || a.equals("mockito-core") || a.equals("mockito-all"))) {
                v.versionMockito = ver;
            }
        } else if (g.equals("org.assertj") && a.equals("assertj-core")) {
            v.assertj = true;
            v.versionAssertj = ver;
        } else if (g.equals("org.springframework.boot") && a.equals("spring-boot-starter-test")) {
            v.springBoot = ver;
            boolean boot22 = ver == null || Textos.versionAlMenos(ver, "2.2.0");
            String junit5 = gestionada(me, "org.junit.jupiter:junit-jupiter", "junit-jupiter.version");
            String mock = gestionada(me, "org.mockito:mockito-core", "mockito.version");
            String aj = gestionada(me, "org.assertj:assertj-core", "assertj.version");
            if (boot22) {
                v.junit5 = true;
                if (v.versionJunit5 == null) {
                    v.versionJunit5 = junit5;
                }
                v.mockitoJunitJupiter = true;
            } else {
                v.junit4 = true;
                if (v.versionJunit4 == null) {
                    v.versionJunit4 = gestionada(me, "junit:junit", "junit.version");
                }
            }
            v.mockito = true;
            if (v.versionMockito == null) {
                v.versionMockito = mock;
            }
            v.assertj = true;
            if (v.versionAssertj == null) {
                v.versionAssertj = aj;
            }
        }
    }

    private static String gestionada(ResolutorBom.ModeloEfectivo me, String clave, String propiedad) {
        if (me == null) {
            return null;
        }
        String v = me.versionGestionada(clave);
        if (v == null) {
            String p = me.propiedad(propiedad);
            if (p != null) {
                v = me.interpolar(p);
                if (v.contains("${")) {
                    v = null;
                }
            }
        }
        return v;
    }

    private static void respaldoImports(VersionesProyecto v, List<List<String>> importsTests) {
        if (importsTests == null || importsTests.isEmpty()) {
            return;
        }
        boolean hayDecl = v.junit4 || v.junit5 || v.mockito || v.assertj;
        boolean mockitoDeclarado = v.mockito;
        for (List<String> imps : importsTests) {
            for (String i : imps) {
                if (i.startsWith("org.junit.jupiter") && !v.junit5) {
                    v.junit5 = true;
                    v.avisos.add("JUnit 5 deducido de los imports de los tests (versión desconocida).");
                } else if ((i.equals("org.junit.Test") || i.startsWith("org.junit.Assert") || i.equals("org.junit.Before")
                        || i.startsWith("org.junit.runner")) && !v.junit4) {
                    v.junit4 = true;
                    v.avisos.add("JUnit 4 deducido de los imports de los tests (versión desconocida).");
                } else if (i.startsWith("org.mockito") && !v.mockito) {
                    v.mockito = true;
                    v.avisos.add("Mockito deducido de los imports de los tests (versión desconocida).");
                } else if (i.startsWith("org.mockito.junit.jupiter") && !mockitoDeclarado) {
                    v.mockitoJunitJupiter = true;
                } else if (i.startsWith("org.assertj") && !v.assertj) {
                    v.assertj = true;
                    v.avisos.add("AssertJ deducido de los imports de los tests (versión desconocida).");
                }
            }
        }
        if (!hayDecl && (v.junit4 || v.junit5)) {
            v.origen = (v.origen == null ? "" : v.origen + " + ") + "imports de tests";
        }
    }

    private static void elegirJunit(VersionesProyecto v, List<List<String>> importsTests) {
        if (v.junit5 && v.junit4) {
            int j4 = 0;
            int j5 = 0;
            if (importsTests != null) {
                for (List<String> imps : importsTests) {
                    boolean es5 = imps.stream().anyMatch(i -> i.startsWith("org.junit.jupiter"));
                    boolean es4 = imps.stream().anyMatch(i -> i.equals("org.junit.Test") || i.startsWith("org.junit.runner"));
                    if (es5) {
                        j5++;
                    } else if (es4) {
                        j4++;
                    }
                }
            }
            v.junitElegido = j4 > j5 ? 4 : 5;
        } else if (v.junit5) {
            v.junitElegido = 5;
        } else if (v.junit4) {
            v.junitElegido = 4;
        } else {
            v.junitElegido = 0;
        }
    }
}
