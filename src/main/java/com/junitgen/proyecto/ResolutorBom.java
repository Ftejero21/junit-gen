package com.junitgen.proyecto;

import com.junitgen.seguridad.RutaSegura;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolución estática del modelo efectivo de un pom: cadena de parents (relativePath dentro de la
 * raíz o repositorio local de Maven, profundidad máxima 10), propiedades interpoladas, dependencias
 * heredadas y dependencyManagement con BOMs importados.
 */
public class ResolutorBom {

    private static final int PROFUNDIDAD_MAX = 10;
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}]+)}");

    public static class ModeloEfectivo {
        public final Map<String, String> propiedades = new LinkedHashMap<>();
        /** Propiedades de los BOMs importados (respaldo para versiones gestionadas). */
        public final Map<String, String> propiedadesBom = new LinkedHashMap<>();
        public final Map<String, String> gestion = new LinkedHashMap<>();
        public final List<Pom.Dep> dependencias = new ArrayList<>();
        public final List<String> avisos = new ArrayList<>();
        public final List<Pom> cadena = new ArrayList<>();

        public String interpolar(String s) {
            return ResolutorBom.interpolar(s, propiedades);
        }

        /** Versión declarada (interpolada) o gestionada; null si no se conoce. */
        public String version(Pom.Dep d) {
            String v = interpolar(d.version());
            if (v != null && !v.contains("${")) {
                return v;
            }
            String g = gestion.get(interpolar(d.groupId()) + ":" + interpolar(d.artifactId()));
            return g == null || g.contains("${") ? null : g;
        }

        public String versionGestionada(String clave) {
            String g = gestion.get(clave);
            return g == null || g.contains("${") ? null : g;
        }

        public String propiedad(String clave) {
            String v = propiedades.get(clave);
            if (v == null) {
                v = propiedadesBom.get(clave);
            }
            return v;
        }
    }

    private final Path raiz;
    private final Path repoLocal;
    private final Map<String, Pom> cachePorRuta = new HashMap<>();

    public ResolutorBom(Path raiz) {
        this(raiz, repositorioLocal());
    }

    public ResolutorBom(Path raiz, Path repoLocal) {
        this.raiz = raiz;
        this.repoLocal = repoLocal;
    }

    public static Path repositorioLocal() {
        String home = System.getProperty("user.home");
        Path def = Paths.get(home, ".m2", "repository");
        Path settings = Paths.get(home, ".m2", "settings.xml");
        if (Files.isRegularFile(settings)) {
            try {
                var doc = com.junitgen.util.XmlSeguro.parsear(settings);
                String lr = com.junitgen.util.XmlSeguro.texto(doc.getDocumentElement(), "localRepository");
                if (lr != null && !lr.isBlank() && !lr.contains("${")) {
                    return Paths.get(lr.trim());
                }
            } catch (IOException | RuntimeException e) {
                System.err.println("Aviso: no se pudo leer " + settings + ": " + e.getMessage());
            }
        }
        return def;
    }

    public Path repoLocal() {
        return repoLocal;
    }

    public Path rutaEnRepo(String g, String a, String v, String ext) {
        if (g == null || a == null || v == null) {
            return null;
        }
        return repoLocal.resolve(g.replace('.', '/')).resolve(a).resolve(v).resolve(a + "-" + v + "." + ext);
    }

    private Pom leerPom(Path archivo) {
        Path clave = archivo.toAbsolutePath().normalize();
        if (cachePorRuta.containsKey(clave.toString())) {
            return cachePorRuta.get(clave.toString());
        }
        Pom p = null;
        try {
            if (Files.isRegularFile(clave)) {
                p = LectorPom.leer(clave);
            }
        } catch (IOException e) {
            System.err.println("Aviso: " + e.getMessage());
        }
        cachePorRuta.put(clave.toString(), p);
        return p;
    }

    public ModeloEfectivo resolver(Pom pom) {
        ModeloEfectivo m = new ModeloEfectivo();
        List<Pom> cadena = new ArrayList<>();
        cadena.add(pom);
        Pom actual = pom;
        int prof = 0;
        while (actual.tieneParent() && prof < PROFUNDIDAD_MAX) {
            Pom padre = localizarParent(actual, m);
            if (padre == null) {
                break;
            }
            cadena.add(padre);
            actual = padre;
            prof++;
        }
        m.cadena.addAll(cadena);
        // Propiedades: del ancestro más lejano al pom (el hijo prevalece).
        for (int i = cadena.size() - 1; i >= 0; i--) {
            Pom p = cadena.get(i);
            m.propiedades.putAll(p.propiedades);
        }
        Pom hoja = pom;
        ponerSiFalta(m.propiedades, "project.groupId", hoja.groupIdEfectivo());
        ponerSiFalta(m.propiedades, "project.artifactId", hoja.artifactId);
        ponerSiFalta(m.propiedades, "project.version", hoja.versionEfectiva());
        ponerSiFalta(m.propiedades, "pom.version", hoja.versionEfectiva());
        ponerSiFalta(m.propiedades, "project.parent.version", hoja.parentVersion);
        ponerSiFalta(m.propiedades, "project.parent.groupId", hoja.parentGroupId);
        if (pom.archivo != null && pom.archivo.getParent() != null) {
            ponerSiFalta(m.propiedades, "project.basedir", pom.archivo.getParent().toString());
            ponerSiFalta(m.propiedades, "basedir", pom.archivo.getParent().toString());
        }
        // Dependencias: heredadas de los parents + propias.
        for (int i = cadena.size() - 1; i >= 0; i--) {
            m.dependencias.addAll(cadena.get(i).dependencias);
        }
        // Gestión: el hijo prevalece; los BOM importados, en orden, sin sobrescribir.
        for (Pom p : cadena) {
            for (Pom.Dep d : p.gestion) {
                if ("import".equals(d.scope()) && "pom".equals(d.type())) {
                    continue;
                }
                String clave = m.interpolar(d.groupId()) + ":" + m.interpolar(d.artifactId());
                m.gestion.putIfAbsent(clave, m.interpolar(d.version()));
            }
        }
        for (Pom p : cadena) {
            for (Pom.Dep d : p.gestion) {
                if ("import".equals(d.scope()) && "pom".equals(d.type())) {
                    importarBom(m.interpolar(d.groupId()), m.interpolar(d.artifactId()), m.interpolar(d.version()),
                            m, 0);
                }
            }
        }
        return m;
    }

    private void importarBom(String g, String a, String v, ModeloEfectivo destino, int prof) {
        if (prof > PROFUNDIDAD_MAX || v == null || v.contains("${")) {
            destino.avisos.add("No se pudo resolver el BOM " + g + ":" + a + ":" + v);
            return;
        }
        Path ruta = rutaEnRepo(g, a, v, "pom");
        Pom bom = ruta == null ? null : leerPom(ruta);
        if (bom == null) {
            destino.avisos.add("BOM " + g + ":" + a + ":" + v + " no está en el repositorio local ("
                    + repoLocal + "); versiones gestionadas desconocidas.");
            return;
        }
        ModeloEfectivo mb = resolver(bom);
        for (Map.Entry<String, String> e : mb.propiedades.entrySet()) {
            destino.propiedadesBom.putIfAbsent(e.getKey(), e.getValue());
        }
        for (Map.Entry<String, String> e : mb.gestion.entrySet()) {
            destino.gestion.putIfAbsent(e.getKey(), e.getValue());
        }
        for (Map.Entry<String, String> e : mb.propiedadesBom.entrySet()) {
            destino.propiedadesBom.putIfAbsent(e.getKey(), e.getValue());
        }
    }

    private Pom localizarParent(Pom hijo, ModeloEfectivo m) {
        String rel = hijo.parentRelativePath;
        if (rel == null) {
            rel = "../pom.xml";
        }
        if (!rel.isEmpty() && hijo.archivo != null && hijo.archivo.getParent() != null) {
            Path cand = hijo.archivo.getParent().resolve(rel).normalize();
            if (Files.isDirectory(cand)) {
                cand = cand.resolve("pom.xml");
            }
            if (raiz == null || RutaSegura.dentroOIgual(cand, raiz)) {
                Pom p = leerPom(cand);
                if (p != null && hijo.parentArtifactId.equals(p.artifactId)
                        && (hijo.parentGroupId == null || hijo.parentGroupId.equals(p.groupIdEfectivo()))) {
                    return p;
                }
            }
        }
        Path enRepo = rutaEnRepo(hijo.parentGroupId, hijo.parentArtifactId, hijo.parentVersion, "pom");
        Pom p = enRepo == null ? null : leerPom(enRepo);
        if (p == null) {
            m.avisos.add("No se localiza el parent " + hijo.parentGroupId + ":" + hijo.parentArtifactId + ":"
                    + hijo.parentVersion + " (ni en la raíz ni en " + repoLocal + ").");
        }
        return p;
    }

    private static void ponerSiFalta(Map<String, String> m, String k, String v) {
        if (v != null && !m.containsKey(k)) {
            m.put(k, v);
        }
    }

    public static String interpolar(String s, Map<String, String> props) {
        if (s == null) {
            return null;
        }
        String r = s;
        for (int i = 0; i < 10 && r.contains("${"); i++) {
            Matcher mt = PLACEHOLDER.matcher(r);
            StringBuilder sb = new StringBuilder();
            boolean cambio = false;
            while (mt.find()) {
                String v = props.get(mt.group(1));
                if (v != null) {
                    cambio = true;
                    mt.appendReplacement(sb, Matcher.quoteReplacement(v));
                } else {
                    mt.appendReplacement(sb, Matcher.quoteReplacement(mt.group(0)));
                }
            }
            mt.appendTail(sb);
            r = sb.toString();
            if (!cambio) {
                break;
            }
        }
        return r.trim();
    }
}
