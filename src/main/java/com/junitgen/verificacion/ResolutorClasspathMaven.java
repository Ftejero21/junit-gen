package com.junitgen.verificacion;

import com.junitgen.proyecto.LectorPom;
import com.junitgen.proyecto.Pom;
import com.junitgen.proyecto.ResolutorBom;
import com.junitgen.seguridad.ProcesoSeguro;
import com.junitgen.seguridad.SeguridadException;
import com.junitgen.util.XmlSeguro;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Classpath por Maven sin tocar el proyecto: se copian solo los pom.xml del árbol a un "pom espejo"
 * dentro del temporal (mismas rutas relativas) y se ejecuta
 * {@code maven-dependency-plugin:3.8.1:build-classpath} sobre el espejo. Las dependencias que son
 * módulos hermanos se quitan del espejo y se añade su target/classes real (lectura), resolviendo
 * recursivamente su propio classpath.
 */
public final class ResolutorClasspathMaven {

    private ResolutorClasspathMaven() {
    }

    public static List<Path> resolver(ContextoVerificacion ctx, Path mvn) {
        Path raiz = ctx.proyecto.raiz;
        Path espejo = ctx.tmp.resolve("pom-espejo");
        Map<String, Path> modulosPorCoord = new LinkedHashMap<>();
        Map<Path, Pom> poms = new LinkedHashMap<>();
        for (Path pomPath : ctx.proyecto.localizacion.poms) {
            try {
                Pom p = LectorPom.leer(pomPath);
                poms.put(pomPath, p);
                ResolutorBom.ModeloEfectivo me = new ResolutorBom(raiz).resolver(p);
                modulosPorCoord.put(me.interpolar(p.groupIdEfectivo()) + ":" + p.artifactId, pomPath.getParent());
            } catch (IOException e) {
                ctx.avisos.add("pom ilegible, se omite del espejo: " + pomPath + " (" + e.getMessage() + ")");
            }
        }
        // Copia de todos los pom.xml (solo lectura del proyecto, escritura en el temporal).
        for (Path pomPath : poms.keySet()) {
            Path rel = raiz.relativize(pomPath);
            try {
                ctx.escritor.escribir(espejo.resolve(rel), Files.readAllBytes(pomPath));
            } catch (IOException e) {
                throw new SeguridadException("No se pudo leer " + pomPath + ": " + e.getMessage(), e);
            }
        }
        List<Path> cp = new ArrayList<>();
        resolverModulo(ctx, mvn, ctx.modulo.modulo.dir, espejo, poms, modulosPorCoord, cp, new LinkedHashSet<>(), 0);
        return cp;
    }

    private static void resolverModulo(ContextoVerificacion ctx, Path mvn, Path dirModulo, Path espejo, Map<Path, Pom> poms,
                                       Map<String, Path> modulos, List<Path> cp, Set<Path> visitados, int prof) {
        if (!visitados.add(dirModulo) || prof > 10) {
            return;
        }
        Path raiz = ctx.proyecto.raiz;
        Path pomPath = dirModulo.resolve("pom.xml");
        Pom pom = poms.get(pomPath);
        if (pom == null) {
            throw new SeguridadException("No se encuentra el pom del módulo " + dirModulo);
        }
        ResolutorBom.ModeloEfectivo me = new ResolutorBom(raiz).resolver(pom);
        Path relPom = raiz.relativize(pomPath);
        Path pomEspejo = espejo.resolve(relPom);
        List<Path> hermanos = new ArrayList<>();
        try {
            Document doc = XmlSeguro.parsear(pomPath);
            Element deps = XmlSeguro.hijo(doc.getDocumentElement(), "dependencies");
            boolean cambiado = false;
            for (Element d : XmlSeguro.hijos(deps, "dependency")) {
                String g = me.interpolar(XmlSeguro.texto(d, "groupId"));
                String a = me.interpolar(XmlSeguro.texto(d, "artifactId"));
                Path hermano = modulos.get(g + ":" + a);
                if (hermano != null && !hermano.equals(dirModulo)) {
                    deps.removeChild(d);
                    hermanos.add(hermano);
                    cambiado = true;
                }
                String scope = XmlSeguro.texto(d, "scope");
                if ("system".equals(scope)) {
                    ctx.avisos.add("Dependencia 'system' " + g + ":" + a + ": en el pom espejo las rutas relativas al proyecto no se resuelven;"
                            + " si falla, indica 'classpath' en junit-gen.yml.");
                }
            }
            if (cambiado) {
                ctx.escritor.escribir(pomEspejo, XmlSeguro.serializar(doc).getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            throw new SeguridadException("No se pudo preparar el pom espejo de " + pomPath + ": " + e.getMessage(), e);
        }
        String nombre = raiz.relativize(dirModulo).toString().replace('\\', '_').replace('/', '_');
        if (nombre.isEmpty()) {
            nombre = "_raiz";
        }
        Path salidaCp = ctx.tmp.resolve("classpath-" + nombre + ".txt");
        Map<String, String> props = new LinkedHashMap<>();
        props.put("mdep.outputFile", salidaCp.toString());
        Path settings = null;
        if (ctx.modulo.config.mavenSettings != null && !ctx.modulo.config.mavenSettings.isBlank()) {
            settings = Path.of(ctx.modulo.config.mavenSettings);
        }
        ProcesoSeguro.Resultado r = ctx.proceso.maven(mvn, ProcesoSeguro.GoalMaven.BUILD_CLASSPATH, pomEspejo, props, settings,
                ctx.javaHerramienta, ctx.tmp.resolve("run-maven"), ctx.log("maven-classpath-" + nombre + ".log"));
        if (!r.correcto() || !Files.isRegularFile(salidaCp)) {
            throw new SeguridadException("Maven no pudo obtener el classpath (" + (r.timeout() ? "tiempo agotado" : "código " + r.codigo())
                    + "). Log: " + r.log() + ". Últimas líneas: " + ultimas(r.salida(), 15)
                    + " — Alternativa: indica 'classpath' en junit-gen.yml o compila el proyecto tú mismo.");
        }
        try {
            String texto = Files.readString(salidaCp, StandardCharsets.UTF_8).trim();
            for (String e : texto.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
                if (!e.isBlank()) {
                    Path p = Path.of(e.trim());
                    if (!cp.contains(p)) {
                        cp.add(p);
                    }
                }
            }
        } catch (IOException e) {
            throw new SeguridadException("No se pudo leer " + salidaCp + ": " + e.getMessage(), e);
        }
        for (Path h : hermanos) {
            Path clases = h.resolve("target").resolve("classes");
            if (Files.isDirectory(clases)) {
                cp.add(0, clases);
            } else {
                ctx.avisos.add("El módulo hermano " + h + " no tiene target/classes compilado; compílalo tú mismo si los tests lo necesitan.");
            }
            resolverModulo(ctx, mvn, h, espejo, poms, modulos, cp, visitados, prof + 1);
        }
    }

    static String ultimas(String texto, int n) {
        String[] l = texto.split("\\R");
        StringBuilder sb = new StringBuilder();
        for (int i = Math.max(0, l.length - n); i < l.length; i++) {
            sb.append(l[i]).append(" | ");
        }
        return sb.toString();
    }
}
