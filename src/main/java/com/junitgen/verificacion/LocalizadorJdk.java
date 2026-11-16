package com.junitgen.verificacion;

import com.junitgen.config.ConfigProyecto;
import com.junitgen.proyecto.VersionesProyecto;
import com.junitgen.seguridad.ProcesoSeguro;
import com.junitgen.seguridad.SeguridadException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Localiza el JDK del proyecto: yml {@code jdk} &gt; JAVA_HOME, C:\Program Files\Java\*, Eclipse
 * Adoptium, ~/.jdks y el java.home de la herramienta. Se elige el de major igual al del proyecto; si
 * no hay, el menor superior con --release N.
 */
public final class LocalizadorJdk {

    private static final Pattern VERSION = Pattern.compile("JAVA_VERSION=\"([^\"]+)\"");

    public record Jdk(Path home, int major, String version) {
    }

    public record Seleccion(Jdk jdk, Integer release, String aviso) {
    }

    private LocalizadorJdk() {
    }

    public static Jdk leer(Path home) {
        try {
            Path h = ProcesoSeguro.validarJdk(home);
            String rel = Files.readString(h.resolve("release"), StandardCharsets.ISO_8859_1);
            Matcher m = VERSION.matcher(rel);
            if (!m.find()) {
                return null;
            }
            Integer major = VersionesProyecto.parsearJava(m.group(1));
            return major == null ? null : new Jdk(h, major, m.group(1));
        } catch (IOException | SeguridadException e) {
            return null;
        }
    }

    public static List<Jdk> candidatos() {
        Map<String, Jdk> r = new LinkedHashMap<>();
        List<Path> homes = new ArrayList<>();
        String jh = System.getenv("JAVA_HOME");
        if (jh != null && !jh.isBlank()) {
            homes.add(Paths.get(jh));
        }
        for (String base : new String[]{"C:\\Program Files\\Java", "C:\\Program Files\\Eclipse Adoptium",
                "C:\\Program Files\\Microsoft", "C:\\Program Files\\Zulu", "C:\\Program Files\\Amazon Corretto",
                "C:\\Program Files\\BellSoft", System.getProperty("user.home") + "\\.jdks", "/usr/lib/jvm",
                "/Library/Java/JavaVirtualMachines"}) {
            Path b = Paths.get(base);
            if (!Files.isDirectory(b)) {
                continue;
            }
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(b)) {
                for (Path p : ds) {
                    homes.add(p);
                    homes.add(p.resolve("Contents").resolve("Home"));
                }
            } catch (IOException e) {
                System.err.println("Aviso: no se pudo listar " + b + ": " + e.getMessage());
            }
        }
        homes.add(Paths.get(System.getProperty("java.home")));
        for (Path h : homes) {
            Jdk j = leer(h);
            if (j != null) {
                r.putIfAbsent(j.home().toString().toLowerCase(), j);
            }
        }
        return new ArrayList<>(r.values());
    }

    public static Seleccion elegir(Integer javaProyecto, ConfigProyecto cfg) {
        int objetivo = javaProyecto == null ? 8 : javaProyecto;
        if (cfg != null && cfg.jdk != null && !cfg.jdk.isBlank()) {
            Jdk j = leer(Paths.get(cfg.jdk));
            if (j == null) {
                throw new SeguridadException("El JDK de junit-gen.yml (" + cfg.jdk + ") no es válido: faltan bin/java, bin/javac o release.");
            }
            Integer rel = j.major() > objetivo ? objetivo : null;
            String aviso = j.major() < objetivo ? "El JDK de junit-gen.yml (" + j.major() + ") es anterior al Java del proyecto ("
                    + objetivo + ")." : null;
            return new Seleccion(j, rel, aviso);
        }
        List<Jdk> cands = candidatos();
        for (Jdk j : cands) {
            if (j.major() == objetivo) {
                return new Seleccion(j, null, null);
            }
        }
        Jdk mejor = cands.stream().filter(j -> j.major() > objetivo).min(Comparator.comparingInt(Jdk::major)).orElse(null);
        if (mejor == null) {
            throw new SeguridadException("No se encontró un JDK " + objetivo + " o superior. Indica 'jdk' en junit-gen.yml.");
        }
        return new Seleccion(mejor, objetivo, "No hay JDK " + objetivo + " instalado; se usa el JDK " + mejor.major()
                + " (" + mejor.home() + ") con --release " + objetivo + ".");
    }
}
