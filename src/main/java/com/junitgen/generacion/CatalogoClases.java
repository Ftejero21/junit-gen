package com.junitgen.generacion;

import com.junitgen.escaneo.IndiceTipos;
import com.junitgen.escaneo.InfoClase;

import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Catálogo de nombres de clases de primer nivel disponibles para compilar un test: JDK, jars y carpetas del
 * classpath de verificación e índice del proyecto. Solo lee nombres de entradas (no carga ni inicializa
 * clases, no extrae archivos). La base (JDK + jars) se cachea por JDK y por ruta/tamaño/fecha de cada jar.
 * Nunca lanza: lo que no se puede leer se ignora con un mensaje en stderr.
 */
public final class CatalogoClases {

    /** Máximo de candidatos que se muestran en pistas y mensajes. */
    public static final int MAX_CANDIDATOS = 8;
    /** Máximo de entradas que se leen de un jar o carpeta. */
    static final int MAX_ENTRADAS = 200_000;
    /** Máximo de catálogos base (JDK + jars) en caché. */
    static final int MAX_CACHE_BASE = 4;
    /** Máximo de JDK en caché. */
    static final int MAX_CACHE_JDK = 4;
    private static final String EXT_CLASS = ".class";
    private static final String PREFIJO_VERSIONES = "META-INF/versions/";
    private static final String MODULOS_JRT = "/modules";
    private static final String JRT_FS_JAR = "jrt-fs.jar";
    private static final String RT_JAR = "rt.jar";
    private static final int SEGMENTOS_COMUNES = 2;

    /** Nombres simples → paquetes, y paquetes conocidos. Inmutable una vez construido. */
    private record Tabla(Map<String, List<String>> porSimple, Set<String> paquetes, boolean completa) {
        static final Tabla VACIA = new Tabla(Map.of(), Set.of(), true);
    }

    /** Acumulador mutable para construir una {@link Tabla}. */
    private static final class Acumulador {
        final Map<String, LinkedHashSet<String>> porSimple = new HashMap<>();
        final Set<String> paquetes = new HashSet<>();
        boolean completa = true;

        void anadir(String paquete, String simple) {
            porSimple.computeIfAbsent(simple, k -> new LinkedHashSet<>()).add(paquete);
            paquetes.add(paquete);
        }

        void anadir(Tabla t) {
            for (Map.Entry<String, List<String>> e : t.porSimple().entrySet()) {
                porSimple.computeIfAbsent(e.getKey(), k -> new LinkedHashSet<>()).addAll(e.getValue());
            }
            paquetes.addAll(t.paquetes());
            completa &= t.completa();
        }

        Tabla tabla() {
            Map<String, List<String>> m = new HashMap<>();
            for (Map.Entry<String, LinkedHashSet<String>> e : porSimple.entrySet()) {
                m.put(e.getKey(), List.copyOf(e.getValue()));
            }
            return new Tabla(Map.copyOf(m), Set.copyOf(paquetes), completa);
        }
    }

    private static final Map<String, Tabla> JDKS = new ConcurrentHashMap<>();
    private static final Map<String, Tabla> BASES = new ConcurrentHashMap<>();

    private final Tabla base;
    private final Tabla carpetas;
    private final IndiceTipos indice;
    private final boolean conClasspath;

    private CatalogoClases(Tabla base, Tabla carpetas, IndiceTipos indice, boolean conClasspath) {
        this.base = base;
        this.carpetas = carpetas;
        this.indice = indice;
        this.conClasspath = conClasspath;
    }

    /**
     * Catálogo del JDK {@code jdkHome} (null = el de ejecución), el classpath indicado y el índice del
     * proyecto (puede ser null). Nunca lanza.
     */
    public static CatalogoClases de(Path jdkHome, List<Path> classpath, IndiceTipos indice) {
        List<Path> cp = classpath == null ? List.of() : classpath;
        Tabla tablaBase;
        Tabla tablaCarpetas;
        try {
            List<Path> jars = new ArrayList<>();
            List<Path> dirs = new ArrayList<>();
            for (Path p : cp) {
                if (p == null) {
                    continue;
                }
                if (Files.isDirectory(p)) {
                    dirs.add(p);
                } else {
                    jars.add(p);
                }
            }
            tablaBase = base(jdkHome, jars);
            Acumulador acc = new Acumulador();
            for (Path d : dirs) {
                leerCarpeta(d, acc);
            }
            tablaCarpetas = acc.tabla();
        } catch (RuntimeException e) {
            System.err.println("junit-gen: no se pudo construir el catálogo de clases: " + e.getMessage());
            tablaBase = Tabla.VACIA;
            tablaCarpetas = new Tabla(Map.of(), Set.of(), false);
        }
        boolean con = !cp.isEmpty() && tablaBase.completa() && tablaCarpetas.completa();
        return new CatalogoClases(tablaBase, tablaCarpetas, indice, con);
    }

    private static Tabla base(Path jdkHome, List<Path> jars) {
        StringBuilder clave = new StringBuilder(claveJdk(jdkHome)).append('|');
        for (Path j : jars) {
            clave.append(j.toAbsolutePath().normalize()).append('|');
            try {
                clave.append(Files.size(j)).append('|').append(Files.getLastModifiedTime(j).toMillis());
            } catch (IOException | RuntimeException e) {
                clave.append('?');
            }
            clave.append(';');
        }
        String k = clave.toString();
        Tabla t = BASES.get(k);
        if (t != null) {
            return t;
        }
        Acumulador acc = new Acumulador();
        acc.anadir(jdk(jdkHome));
        for (Path j : jars) {
            leerJar(j, acc);
        }
        t = acc.tabla();
        acotar(BASES, MAX_CACHE_BASE);
        BASES.put(k, t);
        return t;
    }

    private static void acotar(Map<String, Tabla> cache, int max) {
        Iterator<String> it = cache.keySet().iterator();
        while (cache.size() >= max && it.hasNext()) {
            it.next();
            it.remove();
        }
    }

    private static String claveJdk(Path jdkHome) {
        if (jdkHome == null || esRuntime(jdkHome)) {
            return "<runtime>";
        }
        return jdkHome.toAbsolutePath().normalize().toString().toLowerCase(Locale.ROOT);
    }

    private static boolean esRuntime(Path home) {
        try {
            Path actual = Path.of(System.getProperty("java.home")).toAbsolutePath().normalize();
            return actual.equals(home.toAbsolutePath().normalize());
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static Tabla jdk(Path jdkHome) {
        String k = claveJdk(jdkHome);
        Tabla t = JDKS.get(k);
        if (t != null) {
            return t;
        }
        t = leerJdk(jdkHome);
        acotar(JDKS, MAX_CACHE_JDK);
        JDKS.put(k, t);
        return t;
    }

    private static Tabla leerJdk(Path home) {
        if (home != null && !esRuntime(home)) {
            Path jrtFs = home.resolve("lib").resolve(JRT_FS_JAR);
            if (Files.isRegularFile(jrtFs)) {
                Acumulador acc = new Acumulador();
                try (FileSystem fs = FileSystems.newFileSystem(URI.create("jrt:/"), Map.of("java.home", home.toString()))) {
                    leerJrt(fs, acc);
                    return acc.tabla();
                } catch (IOException | RuntimeException e) {
                    System.err.println("junit-gen: no se pudo leer el JDK " + home + ": " + e.getMessage());
                }
            } else {
                for (Path rt : List.of(home.resolve("jre").resolve("lib").resolve(RT_JAR), home.resolve("lib").resolve(RT_JAR))) {
                    if (Files.isRegularFile(rt)) {
                        Acumulador acc = new Acumulador();
                        leerJar(rt, acc);
                        if (acc.completa) {
                            return acc.tabla();
                        }
                    }
                }
            }
        }
        Acumulador acc = new Acumulador();
        try {
            leerJrt(FileSystems.getFileSystem(URI.create("jrt:/")), acc);
            return acc.tabla();
        } catch (IOException | RuntimeException e) {
            System.err.println("junit-gen: no se pudo leer el JDK de ejecución: " + e.getMessage());
            return new Tabla(Map.of(), Set.of(), false);
        }
    }

    private static void leerJrt(FileSystem fs, Acumulador acc) throws IOException {
        Path modulos = fs.getPath(MODULOS_JRT);
        try (Stream<Path> mods = Files.list(modulos)) {
            for (Iterator<Path> it = mods.iterator(); it.hasNext(); ) {
                Path mod = it.next();
                try (Stream<Path> s = Files.walk(mod)) {
                    s.limit(MAX_ENTRADAS).forEach(f -> {
                        String rel = mod.relativize(f).toString().replace('\\', '/');
                        anadirEntrada(rel, acc);
                    });
                }
            }
        }
    }

    private static void leerJar(Path jar, Acumulador acc) {
        try (ZipFile zf = new ZipFile(jar.toFile())) {
            Enumeration<? extends ZipEntry> en = zf.entries();
            int n = 0;
            while (en.hasMoreElements() && n < MAX_ENTRADAS) {
                ZipEntry e = en.nextElement();
                n++;
                if (!e.isDirectory()) {
                    String nombre = e.getName();
                    if (nombre.startsWith(PREFIJO_VERSIONES)) {
                        int barra = nombre.indexOf('/', PREFIJO_VERSIONES.length());
                        if (barra < 0) {
                            continue;
                        }
                        nombre = nombre.substring(barra + 1);
                    }
                    anadirEntrada(nombre, acc);
                }
            }
        } catch (IOException | RuntimeException e) {
            System.err.println("junit-gen: se ignora " + jar + " al catalogar clases: " + e.getMessage());
            acc.completa = false;
        }
    }

    private static void leerCarpeta(Path dir, Acumulador acc) {
        try (Stream<Path> s = Files.walk(dir)) {
            s.limit(MAX_ENTRADAS).filter(Files::isRegularFile).forEach(f -> {
                String rel = dir.relativize(f).toString().replace('\\', '/');
                anadirEntrada(rel, acc);
            });
        } catch (IOException | RuntimeException e) {
            System.err.println("junit-gen: se ignora " + dir + " al catalogar clases: " + e.getMessage());
            acc.completa = false;
        }
    }

    /** Registra una entrada {@code a/b/C.class} si es una clase de primer nivel de un paquete no interno. */
    private static void anadirEntrada(String rel, Acumulador acc) {
        if (!rel.endsWith(EXT_CLASS)) {
            return;
        }
        String sin = rel.substring(0, rel.length() - EXT_CLASS.length());
        int barra = sin.lastIndexOf('/');
        String simple = sin.substring(barra + 1);
        if (simple.isEmpty() || simple.indexOf('$') >= 0 || simple.equals("module-info") || simple.equals("package-info")) {
            return;
        }
        String paquete = barra < 0 ? "" : sin.substring(0, barra).replace('/', '.');
        if (paquete.startsWith("META-INF") || paquete.indexOf('-') >= 0 || interno(paquete)) {
            return;
        }
        acc.anadir(paquete, simple);
    }

    private static boolean interno(String paquete) {
        if (paquete.startsWith("sun.") || paquete.equals("sun") || paquete.startsWith("jdk.internal")) {
            return true;
        }
        for (String seg : paquete.split("\\.")) {
            if (seg.equals("internal")) {
                return true;
            }
        }
        return false;
    }

    /** true si el classpath no está vacío y se ha leído sin fallos. */
    public boolean conClasspath() {
        return conClasspath;
    }

    private boolean primerNivel(String fqn) {
        int p = fqn.lastIndexOf('.');
        String paquete = p < 0 ? "" : fqn.substring(0, p);
        String simple = fqn.substring(p + 1);
        if (contiene(base, paquete, simple) || contiene(carpetas, paquete, simple)) {
            return true;
        }
        return indice != null && indice.get(fqn) != null;
    }

    private static boolean contiene(Tabla t, String paquete, String simple) {
        List<String> l = t.porSimple().get(simple);
        return l != null && l.contains(paquete);
    }

    /**
     * true si {@code fqn} es una clase de primer nivel conocida o alguno de sus prefijos lo es (clases
     * anidadas: permisivo). Sin punto → true.
     */
    public boolean existe(String fqn) {
        if (fqn == null || fqn.indexOf('.') < 0) {
            return true;
        }
        String f = fqn;
        while (f.indexOf('.') >= 0) {
            if (primerNivel(f)) {
                return true;
            }
            f = f.substring(0, f.lastIndexOf('.'));
        }
        return false;
    }

    /** true si el paquete es conocido o es una clase conocida (import {@code Outer.*}). */
    public boolean existePaquete(String paquete) {
        if (paquete == null || paquete.isEmpty()) {
            return false;
        }
        if (base.paquetes().contains(paquete) || carpetas.paquetes().contains(paquete)) {
            return true;
        }
        if (indice != null) {
            for (InfoClase c : indice.todas()) {
                if (paquete.equals(c.paquete)) {
                    return true;
                }
            }
        }
        return paquete.indexOf('.') >= 0 && existe(paquete);
    }

    /** FQN de primer nivel con ese nombre simple, sin duplicados: proyecto, luego java./javax., luego el resto. */
    public List<String> candidatos(String simple) {
        LinkedHashSet<String> proyecto = new LinkedHashSet<>(delProyecto(simple));
        LinkedHashSet<String> jdk = new LinkedHashSet<>();
        LinkedHashSet<String> resto = new LinkedHashSet<>();
        if (simple != null) {
            for (Tabla t : List.of(base, carpetas)) {
                for (String paq : t.porSimple().getOrDefault(simple, List.of())) {
                    String fqn = paq.isEmpty() ? simple : paq + "." + simple;
                    if (proyecto.contains(fqn)) {
                        continue;
                    }
                    if (esJdk(fqn)) {
                        jdk.add(fqn);
                    } else {
                        resto.add(fqn);
                    }
                }
            }
        }
        LinkedHashSet<String> r = new LinkedHashSet<>(proyecto);
        r.addAll(jdk);
        r.addAll(resto);
        return List.copyOf(r);
    }

    private List<String> delProyecto(String simple) {
        List<String> r = new ArrayList<>();
        if (indice == null || simple == null) {
            return r;
        }
        for (InfoClase c : indice.porNombreSimple(simple)) {
            String esperado = c.paquete == null || c.paquete.isEmpty() ? simple : c.paquete + "." + simple;
            if (esperado.equals(c.fqn) && !r.contains(c.fqn)) {
                r.add(c.fqn);
            }
        }
        return r;
    }

    private static boolean esJdk(String fqn) {
        return fqn.startsWith("java.") || fqn.startsWith("javax.");
    }

    /**
     * Elige la clase a importar para {@code simple}: un preferido existente; el único candidato; el único del
     * JDK si {@code original} era del JDK; el único que comparte los dos primeros segmentos con
     * {@code original}; el único del proyecto; el de la tabla JDK de {@link ResolutorImports}; o null.
     */
    public String elegir(String simple, Collection<String> preferidos, Set<String> excluidos, String original) {
        if (simple == null || simple.isEmpty()) {
            return null;
        }
        Set<String> fuera = excluidos == null ? Set.of() : excluidos;
        if (preferidos != null) {
            for (String p : preferidos) {
                if (p != null && !p.isEmpty() && !fuera.contains(p) && !p.equals(original) && existe(p)) {
                    return p;
                }
            }
        }
        List<String> cands = new ArrayList<>();
        for (String c : candidatos(simple)) {
            if (!fuera.contains(c) && !c.equals(original)) {
                cands.add(c);
            }
        }
        if (cands.isEmpty()) {
            return null;
        }
        if (cands.size() == 1) {
            return cands.get(0);
        }
        if (original != null) {
            if (esJdk(original)) {
                String unico = unico(cands, CatalogoClases::esJdk);
                if (unico != null) {
                    return unico;
                }
            }
            String prefijo = prefijo(original);
            if (prefijo != null) {
                String unico = unico(cands, c -> prefijo.equals(prefijo(c)));
                if (unico != null) {
                    return unico;
                }
            }
        }
        List<String> proyecto = delProyecto(simple);
        String unico = unico(cands, proyecto::contains);
        if (unico != null) {
            return unico;
        }
        String tabla = ResolutorImports.JDK.get(simple);
        if (tabla != null && cands.contains(tabla)) {
            return tabla;
        }
        return null;
    }

    private static String unico(List<String> cands, java.util.function.Predicate<String> cond) {
        String r = null;
        for (String c : cands) {
            if (cond.test(c)) {
                if (r != null) {
                    return null;
                }
                r = c;
            }
        }
        return r;
    }

    /** Dos primeros segmentos de paquete de un FQN ({@code a.b}); null si no los tiene. */
    private static String prefijo(String fqn) {
        String[] segs = fqn.split("\\.");
        if (segs.length - 1 < SEGMENTOS_COMUNES) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < SEGMENTOS_COMUNES; i++) {
            if (i > 0) {
                sb.append('.');
            }
            sb.append(segs[i]);
        }
        return sb.toString();
    }
}
