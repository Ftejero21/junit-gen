package com.junitgen.verificacion;

import com.junitgen.util.XmlSeguro;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Lee los informes XML legacy (TEST-*.xml) del ConsoleLauncher de JUnit Platform. */
public final class LectorResultadosXml {

    private LectorResultadosXml() {
    }

    public static List<ResultadoTest> leer(Path dir) throws IOException {
        List<ResultadoTest> r = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return r;
        }
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "TEST-*.xml")) {
            for (Path xml : ds) {
                Document doc = XmlSeguro.parsear(xml);
                for (Element tc : XmlSeguro.descendientes(doc.getDocumentElement(), "testcase")) {
                    ResultadoTest t = new ResultadoTest();
                    t.clase = tc.getAttribute("classname");
                    String nombre = tc.getAttribute("name");
                    int par = nombre.indexOf('(');
                    t.metodoTest = par >= 0 ? nombre.substring(0, par) : nombre;
                    Element f = XmlSeguro.hijo(tc, "failure");
                    Element e = XmlSeguro.hijo(tc, "error");
                    Element s = XmlSeguro.hijo(tc, "skipped");
                    Element problema = f != null ? f : e;
                    if (problema != null) {
                        t.tipoError = problema.getAttribute("type");
                        t.mensaje = problema.getAttribute("message");
                        t.traza = ClasificadorFallos.traza(problema.getTextContent());
                        t.causa = ClasificadorFallos.causa(problema.getTextContent());
                        t.estado = ClasificadorFallos.clasificar(f != null ? "failure" : "error", t.tipoError);
                        if ((t.mensaje == null || t.mensaje.isEmpty()) && t.traza != null) {
                            t.mensaje = t.traza.split("\\R")[0];
                        }
                        if (ClasificadorFallos.ERROR_MONTAJE.equals(t.estado)) {
                            t.pista = ClasificadorFallos.pista(t.tipoError, t.mensaje, t.traza);
                        }
                    } else if (s != null) {
                        t.estado = ClasificadorFallos.DESCONOCIDO;
                        t.mensaje = "test omitido: " + s.getAttribute("message");
                    } else {
                        t.estado = ClasificadorFallos.PASA;
                    }
                    r.add(t);
                }
            }
        }
        return r;
    }
}
