package com.junitgen.verificacion;

import com.junitgen.config.ConfigProyecto;
import com.junitgen.seguridad.RutaSegura;
import com.junitgen.util.Textos;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Localiza Maven (nunca mvnw): yml maven.home &gt; JUNIT_GEN_MAVEN_HOME, MAVEN_HOME, M2_HOME &gt; mvn
 * en PATH &gt; versión del distributionUrl de .mvn/wrapper (solo lectura) en ~/.m2/wrapper/dists &gt;
 * la versión más alta en caché.
 */
public final class LocalizadorMaven {

    private static final Pattern VERSION_URL = Pattern.compile("apache-maven-([\\d.]+)-bin");

    private LocalizadorMaven() {
    }

    private static String nombreMvn() {
        return RutaSegura.WINDOWS ? "mvn.cmd" : "mvn";
    }

    private static Path enHome(Path home) {
        if (home == null) {
            return null;
        }
        Path p = home.resolve("bin").resolve(nombreMvn());
        return Files.isRegularFile(p) && Files.isDirectory(home.resolve("boot")) ? p : null;
    }

    public static Path localizar(ConfigProyecto cfg, Path modulo, Path raiz) {
        if (cfg != null && cfg.mavenHome != null && !cfg.mavenHome.isBlank()) {
            Path p = enHome(Paths.get(cfg.mavenHome));
            if (p != null) {
                return p;
            }
        }
        for (String env : new String[]{"JUNIT_GEN_MAVEN_HOME", "MAVEN_HOME", "M2_HOME"}) {
            String v = System.getenv(env);
            if (v != null && !v.isBlank()) {
                Path p = enHome(Paths.get(v));
                if (p != null) {
                    return p;
                }
            }
        }
        String path = System.getenv("PATH");
        if (path != null) {
            for (String d : path.split(java.io.File.pathSeparator)) {
                if (d.isBlank()) {
                    continue;
                }
                try {
                    Path p = Paths.get(d.trim()).resolve(nombreMvn());
                    if (Files.isRegularFile(p) && p.getParent() != null && enHome(p.getParent().getParent()) != null) {
                        return p;
                    }
                } catch (RuntimeException e) {
                    // Entrada de PATH no válida: se ignora.
                    System.err.println("Aviso: entrada de PATH ignorada: " + d);
                }
            }
        }
        Path dists = Paths.get(System.getProperty("user.home"), ".m2", "wrapper", "dists");
        for (Path base : new Path[]{modulo, raiz}) {
            if (base == null) {
                continue;
            }
            String v = versionWrapper(base.resolve(".mvn").resolve("wrapper").resolve("maven-wrapper.properties"));
            if (v != null) {
                Path p = buscarEnDists(dists, v);
                if (p != null) {
                    return p;
                }
            }
        }
        List<String[]> todas = new ArrayList<>();
        if (Files.isDirectory(dists)) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(dists, "apache-maven-*")) {
                for (Path d : ds) {
                    String n = d.getFileName().toString().replace("apache-maven-", "").replace("-bin", "");
                    todas.add(new String[]{n});
                }
            } catch (IOException e) {
                System.err.println("Aviso: no se pudo listar " + dists + ": " + e.getMessage());
            }
        }
        todas.sort((a, b) -> Textos.compararVersiones(b[0], a[0]));
        for (String[] v : todas) {
            Path p = buscarEnDists(dists, v[0]);
            if (p != null) {
                return p;
            }
        }
        return null;
    }

    static String versionWrapper(Path props) {
        if (!Files.isRegularFile(props)) {
            return null;
        }
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(props)) {
            p.load(in);
        } catch (IOException e) {
            return null;
        }
        String url = p.getProperty("distributionUrl");
        if (url == null) {
            return null;
        }
        Matcher m = VERSION_URL.matcher(url);
        return m.find() ? m.group(1) : null;
    }

    /** Busca bin/mvn en los dos formatos de caché del wrapper. */
    static Path buscarEnDists(Path dists, String version) {
        for (String carpeta : new String[]{"apache-maven-" + version, "apache-maven-" + version + "-bin"}) {
            Path d = dists.resolve(carpeta);
            if (!Files.isDirectory(d)) {
                continue;
            }
            try (DirectoryStream<Path> hashes = Files.newDirectoryStream(d)) {
                for (Path h : hashes) {
                    Path directo = enHome(h);
                    if (directo != null) {
                        return directo;
                    }
                    Path anidado = enHome(h.resolve("apache-maven-" + version));
                    if (anidado != null) {
                        return anidado;
                    }
                }
            } catch (IOException e) {
                System.err.println("Aviso: no se pudo listar " + d + ": " + e.getMessage());
            }
        }
        return null;
    }
}
