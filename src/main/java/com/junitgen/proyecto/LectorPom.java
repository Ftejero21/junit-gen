package com.junitgen.proyecto;

import com.junitgen.util.XmlSeguro;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.IOException;
import java.nio.file.Path;

/** Lee pom.xml con el parser XML endurecido. */
public final class LectorPom {

    private LectorPom() {
    }

    public static Pom leer(Path archivo) throws IOException {
        Document doc = XmlSeguro.parsear(archivo);
        Element raiz = doc.getDocumentElement();
        if (raiz == null || !XmlSeguro.local(raiz).equals("project")) {
            throw new IOException("El archivo " + archivo + " no es un pom.xml válido.");
        }
        Pom p = new Pom();
        p.archivo = archivo;
        p.groupId = XmlSeguro.texto(raiz, "groupId");
        p.artifactId = XmlSeguro.texto(raiz, "artifactId");
        p.version = XmlSeguro.texto(raiz, "version");
        p.packaging = XmlSeguro.texto(raiz, "packaging");
        Element parent = XmlSeguro.hijo(raiz, "parent");
        if (parent != null) {
            p.parentGroupId = XmlSeguro.texto(parent, "groupId");
            p.parentArtifactId = XmlSeguro.texto(parent, "artifactId");
            p.parentVersion = XmlSeguro.texto(parent, "version");
            Element rp = XmlSeguro.hijo(parent, "relativePath");
            p.parentRelativePath = rp == null ? null : rp.getTextContent().trim();
        }
        Element props = XmlSeguro.hijo(raiz, "properties");
        if (props != null) {
            NodeList nl = props.getChildNodes();
            for (int i = 0; i < nl.getLength(); i++) {
                Node n = nl.item(i);
                if (n instanceof Element e) {
                    p.propiedades.put(XmlSeguro.local(e), e.getTextContent().trim());
                }
            }
        }
        Element deps = XmlSeguro.hijo(raiz, "dependencies");
        for (Element d : XmlSeguro.hijos(deps, "dependency")) {
            p.dependencias.add(dep(d));
        }
        Element dm = XmlSeguro.hijo(raiz, "dependencyManagement");
        if (dm != null) {
            for (Element d : XmlSeguro.hijos(XmlSeguro.hijo(dm, "dependencies"), "dependency")) {
                p.gestion.add(dep(d));
            }
        }
        for (Element m : XmlSeguro.hijos(XmlSeguro.hijo(raiz, "modules"), "module")) {
            p.modulos.add(m.getTextContent().trim());
        }
        Element build = XmlSeguro.hijo(raiz, "build");
        if (build != null) {
            p.sourceDirectory = XmlSeguro.texto(build, "sourceDirectory");
            p.testSourceDirectory = XmlSeguro.texto(build, "testSourceDirectory");
            Element plugins = XmlSeguro.hijo(build, "plugins");
            for (Element pl : XmlSeguro.hijos(plugins, "plugin")) {
                if ("maven-compiler-plugin".equals(XmlSeguro.texto(pl, "artifactId"))) {
                    Element conf = XmlSeguro.hijo(pl, "configuration");
                    if (conf != null) {
                        p.compilerRelease = XmlSeguro.texto(conf, "release");
                        p.compilerSource = XmlSeguro.texto(conf, "source");
                        p.compilerTarget = XmlSeguro.texto(conf, "target");
                    }
                }
            }
            Element pm = XmlSeguro.hijo(build, "pluginManagement");
            if (pm != null && p.compilerRelease == null && p.compilerSource == null) {
                for (Element pl : XmlSeguro.hijos(XmlSeguro.hijo(pm, "plugins"), "plugin")) {
                    if ("maven-compiler-plugin".equals(XmlSeguro.texto(pl, "artifactId"))) {
                        Element conf = XmlSeguro.hijo(pl, "configuration");
                        if (conf != null) {
                            p.compilerRelease = XmlSeguro.texto(conf, "release");
                            p.compilerSource = XmlSeguro.texto(conf, "source");
                            p.compilerTarget = XmlSeguro.texto(conf, "target");
                        }
                    }
                }
            }
        }
        return p;
    }

    private static Pom.Dep dep(Element d) {
        return new Pom.Dep(XmlSeguro.texto(d, "groupId"), XmlSeguro.texto(d, "artifactId"),
                XmlSeguro.texto(d, "version"), XmlSeguro.texto(d, "scope"), XmlSeguro.texto(d, "type"));
    }
}
