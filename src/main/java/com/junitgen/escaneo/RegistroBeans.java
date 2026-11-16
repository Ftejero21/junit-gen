package com.junitgen.escaneo;

import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.junitgen.proyecto.Modulo;
import com.junitgen.seguridad.RutaSegura;
import com.junitgen.util.XmlSeguro;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Beans registrados sin anotación de clase: tipo de retorno de métodos {@code @Bean} en clases
 * {@code @Configuration} y {@code <bean class="...">} en XML con raíz {@code beans}.
 */
public class RegistroBeans {

    /** fqn → origen ("@Bean en X" o "XML archivo"). */
    public final Map<String, String> beans = new LinkedHashMap<>();
    public final List<String> avisos = new ArrayList<>();

    public String origen(String fqn) {
        return beans.get(fqn);
    }

    public static RegistroBeans construir(IndiceTipos idx, Modulo modulo) {
        RegistroBeans r = new RegistroBeans();
        for (InfoClase c : idx.clasesMain()) {
            if (!c.tieneAnotacion("Configuration", "SpringBootConfiguration", "SpringBootApplication")) {
                continue;
            }
            for (InfoMetodo m : c.metodos) {
                if (!m.anotaciones.contains("Bean")) {
                    continue;
                }
                InfoClase ret = idx.resolverClase(m.tipoRetorno, c);
                if (ret != null) {
                    r.beans.putIfAbsent(ret.fqn, "@Bean " + c.simple + "." + m.nombre + "()");
                    if (ret.tipo == InfoClase.Tipo.INTERFAZ) {
                        for (InfoClase impl : idx.implementaciones(ret)) {
                            r.beans.putIfAbsent(impl.fqn, "@Bean " + c.simple + "." + m.nombre + "() (interfaz " + ret.simple + ")");
                        }
                    }
                }
                m.decl.getBody().ifPresent(b -> {
                    for (ReturnStmt rs : b.findAll(ReturnStmt.class)) {
                        rs.getExpression().ifPresent(e -> {
                            if (e instanceof ObjectCreationExpr oc) {
                                InfoClase t = idx.resolverClase(oc.getType().getNameAsString(), c);
                                if (t != null) {
                                    r.beans.putIfAbsent(t.fqn, "@Bean " + c.simple + "." + m.nombre + "()");
                                }
                            }
                        });
                    }
                });
            }
        }
        if (modulo != null) {
            List<Path> raices = new ArrayList<>(modulo.recursosMain);
            Path webapp = modulo.dir.resolve("src/main/webapp/WEB-INF");
            if (Files.isDirectory(webapp)) {
                raices.add(webapp);
            }
            Path webinf = modulo.dir.resolve("WEB-INF");
            if (Files.isDirectory(webinf)) {
                raices.add(webinf);
            }
            for (Path raiz : raices) {
                for (Path xml : listarXml(raiz, r)) {
                    leerXml(xml, idx, r, modulo.dir);
                }
            }
        }
        return r;
    }

    private static List<Path> listarXml(Path raiz, RegistroBeans r) {
        List<Path> lista = new ArrayList<>();
        try {
            Files.walkFileTree(raiz, EnumSet.noneOf(FileVisitOption.class), 8, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    return !dir.equals(raiz) && RutaSegura.esEnlace(dir) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (attrs.isRegularFile() && file.getFileName().toString().toLowerCase().endsWith(".xml")
                            && attrs.size() < 5_000_000) {
                        lista.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            r.avisos.add("No se pudo recorrer " + raiz + ": " + e.getMessage());
        }
        return lista;
    }

    private static void leerXml(Path xml, IndiceTipos idx, RegistroBeans r, Path base) {
        Document doc;
        try {
            doc = XmlSeguro.parsear(xml);
        } catch (IOException e) {
            return;
        }
        Element raiz = doc.getDocumentElement();
        if (raiz == null || !XmlSeguro.local(raiz).equals("beans")) {
            return;
        }
        String rel = base.relativize(xml).toString().replace('\\', '/');
        for (Element b : XmlSeguro.descendientes(raiz, "bean")) {
            String clase = b.getAttribute("class");
            if (clase != null && !clase.isBlank()) {
                String fqn = clase.trim();
                if (idx.get(fqn) != null) {
                    r.beans.putIfAbsent(fqn, "<bean> en " + rel);
                }
            }
        }
    }
}
