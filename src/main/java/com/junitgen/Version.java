package com.junitgen;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/** Versión de la herramienta (version.properties filtrado por Maven). */
public final class Version {

    private static final String VERSION = cargar();

    private Version() {
    }

    private static String cargar() {
        try (InputStream in = Version.class.getResourceAsStream("/version.properties")) {
            if (in == null) {
                return "desarrollo";
            }
            Properties p = new Properties();
            p.load(in);
            String v = p.getProperty("version", "desarrollo");
            return v.contains("${") ? "desarrollo" : v;
        } catch (IOException e) {
            return "desarrollo";
        }
    }

    public static String version() {
        return VERSION;
    }
}
