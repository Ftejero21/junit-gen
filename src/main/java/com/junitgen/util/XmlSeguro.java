package com.junitgen.util;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Parser XML endurecido (pom.xml, applicationContext.xml): sin entidades externas, sin DTD
 * externas, sin XInclude ni expansión de entidades. Permite DOCTYPE (Spring antiguo lo usa).
 */
public final class XmlSeguro {

    private static final long MAX_BYTES = 10L * 1024 * 1024;

    private XmlSeguro() {
    }

    private static DocumentBuilder builder() throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        f.setFeature("http://xml.org/sax/features/external-general-entities", false);
        f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        f.setXIncludeAware(false);
        f.setExpandEntityReferences(false);
        f.setNamespaceAware(false);
        f.setValidating(false);
        try {
            f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        } catch (IllegalArgumentException e) {
            // Atributos no soportados por la implementación: las features anteriores ya bloquean el acceso externo.
            System.err.println("Aviso XML: " + e.getMessage());
        }
        DocumentBuilder b = f.newDocumentBuilder();
        b.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));
        b.setErrorHandler(null);
        return b;
    }

    public static Document parsear(Path archivo) throws IOException {
        if (Files.size(archivo) > MAX_BYTES) {
            throw new IOException("El XML " + archivo + " supera el tamaño máximo permitido.");
        }
        try (InputStream in = Files.newInputStream(archivo)) {
            return builder().parse(in);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("XML no válido en " + archivo + ": " + e.getMessage(), e);
        }
    }

    public static Document parsearTexto(String xml) throws IOException {
        try {
            return builder().parse(new InputSource(new StringReader(xml)));
        } catch (Exception e) {
            throw new IOException("XML no válido: " + e.getMessage(), e);
        }
    }

    public static String serializar(Document doc) throws IOException {
        try {
            TransformerFactory tf = TransformerFactory.newInstance();
            tf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            try {
                tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
                tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
            } catch (IllegalArgumentException e) {
                System.err.println("Aviso XML: " + e.getMessage());
            }
            Transformer t = tf.newTransformer();
            t.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            t.setOutputProperty(OutputKeys.INDENT, "no");
            StringWriter sw = new StringWriter();
            t.transform(new DOMSource(doc), new StreamResult(sw));
            return sw.toString();
        } catch (Exception e) {
            throw new IOException("No se pudo serializar el XML: " + e.getMessage(), e);
        }
    }

    /** Hijos directos con ese nombre de etiqueta (ignora prefijos de namespace). */
    public static List<Element> hijos(Element padre, String nombre) {
        List<Element> r = new ArrayList<>();
        if (padre == null) {
            return r;
        }
        NodeList nl = padre.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n instanceof Element e && local(e).equals(nombre)) {
                r.add(e);
            }
        }
        return r;
    }

    public static Element hijo(Element padre, String nombre) {
        List<Element> h = hijos(padre, nombre);
        return h.isEmpty() ? null : h.get(0);
    }

    public static String texto(Element padre, String nombre) {
        Element e = hijo(padre, nombre);
        return e == null ? null : e.getTextContent().trim();
    }

    public static String local(Element e) {
        String n = e.getTagName();
        int i = n.indexOf(':');
        return i >= 0 ? n.substring(i + 1) : n;
    }

    /** Todos los descendientes con ese nombre de etiqueta local. */
    public static List<Element> descendientes(Element raiz, String nombre) {
        List<Element> r = new ArrayList<>();
        recorrer(raiz, nombre, r);
        return r;
    }

    private static void recorrer(Element e, String nombre, List<Element> r) {
        NodeList nl = e.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n instanceof Element h) {
                if (local(h).equals(nombre)) {
                    r.add(h);
                }
                recorrer(h, nombre, r);
            }
        }
    }
}
