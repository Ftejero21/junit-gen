package com.junitgen.proyecto;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Lectura estática (regex) de build.gradle(.kts) y gradle/libs.versions.toml. */
public final class LectorGradle {

    public record Coordenada(String groupId, String artifactId, String version) {
    }

    public static class InfoGradle {
        public Integer java;
        public boolean junitPlatform;
        public String springBoot;
        public final List<Coordenada> dependencias = new ArrayList<>();
        public boolean usaBuildSrc;
        public boolean usaIncludeBuild;
    }

    private static final Pattern SOURCE_COMPAT = Pattern.compile(
            "sourceCompatibility\\s*=?\\s*['\"]?(?:JavaVersion\\.VERSION_)?([0-9][0-9._]*)");
    private static final Pattern LANG_VERSION = Pattern.compile("JavaLanguageVersion\\.of\\(\\s*(\\d+)\\s*\\)");
    private static final Pattern RELEASE = Pattern.compile("options\\.release\\s*(?:=|\\.set\\()\\s*(\\d+)");
    private static final Pattern COORD = Pattern.compile("['\"]([\\w.\\-]+):([\\w.\\-]+)(?::([\\w.\\-${}]+))?['\"]");
    private static final Pattern BOOT = Pattern.compile("org\\.springframework\\.boot['\"]\\)?\\s*version\\s*\\(?['\"]([\\w.\\-]+)['\"]");
    private static final Pattern TOML_VERSION = Pattern.compile("^\\s*([\\w.\\-]+)\\s*=\\s*\"([^\"]+)\"\\s*$");
    private static final Pattern TOML_MODULE = Pattern.compile("module\\s*=\\s*\"([\\w.\\-]+):([\\w.\\-]+)\"");
    private static final Pattern TOML_GROUP = Pattern.compile("group\\s*=\\s*\"([\\w.\\-]+)\"\\s*,\\s*name\\s*=\\s*\"([\\w.\\-]+)\"");
    private static final Pattern TOML_VREF = Pattern.compile("version\\.ref\\s*=\\s*\"([\\w.\\-]+)\"");
    private static final Pattern TOML_V = Pattern.compile("version\\s*=\\s*\"([^\"]+)\"");

    private LectorGradle() {
    }

    public static InfoGradle leer(Path modulo, Path raiz) {
        InfoGradle info = new InfoGradle();
        Path archivo = Files.isRegularFile(modulo.resolve("build.gradle")) ? modulo.resolve("build.gradle")
                : modulo.resolve("build.gradle.kts");
        List<String> textos = new ArrayList<>();
        textos.add(leerTexto(archivo));
        if (raiz != null && !raiz.equals(modulo)) {
            textos.add(leerTexto(raiz.resolve("build.gradle")));
            textos.add(leerTexto(raiz.resolve("build.gradle.kts")));
        }
        for (Path settings : new Path[]{modulo.resolve("settings.gradle"), modulo.resolve("settings.gradle.kts"),
                raiz == null ? modulo.resolve("settings.gradle") : raiz.resolve("settings.gradle"),
                raiz == null ? modulo.resolve("settings.gradle.kts") : raiz.resolve("settings.gradle.kts")}) {
            if (leerTexto(settings).contains("includeBuild")) {
                info.usaIncludeBuild = true;
            }
        }
        info.usaBuildSrc = Files.isDirectory(modulo.resolve("buildSrc"))
                || (raiz != null && Files.isDirectory(raiz.resolve("buildSrc")));
        for (String t : textos) {
            if (t.isEmpty()) {
                continue;
            }
            Matcher m = LANG_VERSION.matcher(t);
            if (info.java == null && m.find()) {
                info.java = Integer.parseInt(m.group(1));
            }
            m = RELEASE.matcher(t);
            if (info.java == null && m.find()) {
                info.java = Integer.parseInt(m.group(1));
            }
            m = SOURCE_COMPAT.matcher(t);
            if (info.java == null && m.find()) {
                info.java = VersionesProyecto.parsearJava(m.group(1).replace('_', '.'));
            }
            if (t.contains("useJUnitPlatform")) {
                info.junitPlatform = true;
            }
            m = BOOT.matcher(t);
            if (info.springBoot == null && m.find()) {
                info.springBoot = m.group(1);
            }
            m = COORD.matcher(t);
            while (m.find()) {
                info.dependencias.add(new Coordenada(m.group(1), m.group(2), m.group(3)));
            }
        }
        Path toml = (raiz != null ? raiz : modulo).resolve("gradle").resolve("libs.versions.toml");
        leerToml(toml, info);
        return info;
    }

    private static void leerToml(Path toml, InfoGradle info) {
        String t = leerTexto(toml);
        if (t.isEmpty()) {
            return;
        }
        Map<String, String> versiones = new HashMap<>();
        String seccion = "";
        for (String linea : t.split("\\R")) {
            String l = linea.trim();
            if (l.startsWith("[")) {
                seccion = l;
                continue;
            }
            if (seccion.equals("[versions]")) {
                Matcher m = TOML_VERSION.matcher(l);
                if (m.matches()) {
                    versiones.put(m.group(1), m.group(2));
                }
            } else if (seccion.equals("[libraries]")) {
                String g = null;
                String a = null;
                Matcher mm = TOML_MODULE.matcher(l);
                if (mm.find()) {
                    g = mm.group(1);
                    a = mm.group(2);
                } else {
                    Matcher mg = TOML_GROUP.matcher(l);
                    if (mg.find()) {
                        g = mg.group(1);
                        a = mg.group(2);
                    }
                }
                if (g == null) {
                    Matcher mc = COORD.matcher(l);
                    if (mc.find()) {
                        info.dependencias.add(new Coordenada(mc.group(1), mc.group(2), mc.group(3)));
                    }
                    continue;
                }
                String v = null;
                Matcher vr = TOML_VREF.matcher(l);
                if (vr.find()) {
                    v = versiones.get(vr.group(1));
                } else {
                    Matcher vv = TOML_V.matcher(l);
                    if (vv.find()) {
                        v = vv.group(1);
                    }
                }
                info.dependencias.add(new Coordenada(g, a, v));
            }
        }
    }

    private static String leerTexto(Path p) {
        try {
            if (Files.isRegularFile(p) && Files.size(p) < 2_000_000) {
                return Files.readString(p, StandardCharsets.UTF_8);
            }
        } catch (IOException | RuntimeException e) {
            System.err.println("Aviso: no se pudo leer " + p + ": " + e.getMessage());
        }
        return "";
    }
}
