package com.junitgen.generacion;

import com.junitgen.escaneo.InfoClase;
import com.junitgen.escaneo.ModuloEscaneado;
import com.junitgen.seguridad.RutaSegura;
import com.junitgen.seguridad.SeguridadException;
import com.junitgen.util.Textos;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rutas dentro de la carpeta de salida. Cada test se escribe en una carpeta por servicio:
 * {@code <salida>/<rel del módulo>/<Servicio>/<Clase>Test.java}, donde {@code <Servicio>} es el nombre simple
 * del servicio sin el sufijo {@code Impl}. Si otro tipo del módulo de un paquete distinto produce la misma
 * carpeta, se usa {@code <Servicio>__<paquete>}. Los archivos del layout antiguo
 * ({@code <salida>/<rel>/src/test/java/<paquete>/<Clase>Test.java}) se siguen encontrando y ampliando donde están.
 * Las rutas se construyen solo con nombres validados del escaneo, nunca con texto libre. Esta clase solo lee.
 */
public final class RutasSalida {

    public static final String SPEC = "spec.json";
    public static final String INFORME = "informe.md";
    public static final String ESCANEO_MD = "escaneo.md";
    public static final String ESCANEO_JSON = "escaneo.json";
    /** Estado persistente de la tanda (plan, índice y estado por servicio) para retomarla con `continuar`. */
    public static final String TANDA = "tanda.json";
    /** Campaña del modo «todos los servicios»: oleadas, servicios y progreso, para retomarla con `continuar`. */
    public static final String OLEADAS = "oleadas.json";
    /** Perfil del proyecto (montaje, fábricas de datos, stubs típicos, huellas y recetas aprendidas). */
    public static final String PERFIL = "perfil-proyecto.json";
    /** Separador entre la carpeta del servicio y su paquete cuando hay colisión. */
    public static final String SEP_COLISION = "__";

    private static final String IMPL = "Impl";
    private static final Pattern PACKAGE = Pattern.compile("(?m)^\\s*package\\s+([\\w.$]+)\\s*;");
    private static final Pattern NOMBRE_CARPETA = Pattern.compile("[A-Za-z0-9_$.-]+");
    private static final Set<String> RESERVADOS_WINDOWS = Set.of("CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9");

    private RutasSalida() {
    }

    /**
     * Archivo de test del servicio. Busca primero un archivo existente del mismo paquete en la carpeta nueva,
     * en su variante alterna y en el layout antiguo; si no hay, devuelve la ruta nueva.
     */
    public static Path archivoTest(Path salida, ModuloEscaneado modulo, InfoClase servicio, String claseTest) {
        if (!Textos.esIdentificador(claseTest)) {
            throw new SeguridadException("Nombre de clase de test no válido: " + claseTest);
        }
        String nombre = claseTest + ".java";
        for (Path dir : carpetasTest(salida, modulo, servicio)) {
            Path f = dir.resolve(nombre);
            if (Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS) && servicio.paquete.equals(paqueteDeclarado(leer(f)))) {
                return f;
            }
        }
        Path base = baseModulo(salida, modulo);
        Path canonica = base.resolve(carpetaServicio(modulo, servicio)).resolve(nombre);
        if (!Files.exists(canonica, LinkOption.NOFOLLOW_LINKS)) {
            return canonica;
        }
        return base.resolve(conSufijo(carpetaBase(servicio.simple), servicio.paquete)).resolve(nombre);
    }

    /** Carpeta del módulo dentro de la salida (la propia salida si el módulo es la raíz). */
    public static Path baseModulo(Path salida, ModuloEscaneado modulo) {
        Path base = salida;
        String rel = modulo.modulo.rel;
        if (!rel.isEmpty()) {
            for (String seg : rel.split("/")) {
                if (seg.isEmpty() || seg.equals("..") || seg.equals(".") || seg.contains(":") || seg.contains("\\")) {
                    throw new SeguridadException("Ruta de módulo no válida: " + rel);
                }
                base = base.resolve(seg);
            }
        }
        return base;
    }

    /** Raíz de los tests del layout antiguo ({@code <rel>/src/test/java}). */
    public static Path raizTestsModulo(Path salida, ModuloEscaneado modulo) {
        return baseModulo(salida, modulo).resolve("src").resolve("test").resolve("java");
    }

    /** Nombre de carpeta base: el nombre simple sin el sufijo final {@code Impl}. */
    public static String carpetaBase(String simple) {
        String r = simple;
        if (r.length() > IMPL.length() && r.endsWith(IMPL)) {
            r = r.substring(0, r.length() - IMPL.length());
        }
        if (RESERVADOS_WINDOWS.contains(r.toUpperCase(Locale.ROOT))) {
            r = r + "_";
        }
        return r;
    }

    /**
     * Carpeta del servicio. Si otro tipo main del módulo (que no sea interfaz ni anotación) de un paquete distinto
     * produce la misma carpeta, se añade {@code __<paquete>} para todos los implicados.
     */
    public static String carpetaServicio(ModuloEscaneado modulo, InfoClase servicio) {
        if (!Textos.esIdentificador(servicio.simple)) {
            throw new SeguridadException("Nombre de servicio no válido: " + servicio.simple);
        }
        String base = carpetaBase(servicio.simple);
        boolean colision = false;
        for (String p : modulo.paquetesDeCarpeta(base)) {
            if (!p.equals(servicio.paquete)) {
                colision = true;
                break;
            }
        }
        return colision ? conSufijo(base, servicio.paquete) : comprobarCarpeta(base);
    }

    /**
     * Carpetas donde puede estar el test del servicio, en orden de preferencia y sin repetidos: la carpeta nueva,
     * su variante alterna (con o sin sufijo de paquete) y la carpeta del layout antiguo.
     */
    public static List<Path> carpetasTest(Path salida, ModuloEscaneado modulo, InfoClase servicio) {
        Path base = baseModulo(salida, modulo);
        String canonica = carpetaServicio(modulo, servicio);
        String sinSufijo = comprobarCarpeta(carpetaBase(servicio.simple));
        String alterna = canonica.equals(sinSufijo) ? conSufijo(sinSufijo, servicio.paquete) : sinSufijo;
        Path antigua = raizTestsModulo(salida, modulo);
        for (String seg : segmentosPaquete(servicio.paquete)) {
            antigua = antigua.resolve(seg);
        }
        Set<Path> r = new LinkedHashSet<>();
        r.add(base.resolve(canonica));
        r.add(base.resolve(alterna));
        r.add(antigua);
        return new ArrayList<>(r);
    }

    /** Paquete declarado en el código fuente, o {@code ""} si no hay declaración. */
    public static String paqueteDeclarado(String textoJava) {
        Matcher m = PACKAGE.matcher(textoJava);
        return m.find() ? m.group(1) : "";
    }

    /**
     * Archivos {@code .java} candidatos a test generado del módulo, en ambos layouts. Layout nuevo: los
     * {@code .java} directos de cada subcarpeta de la carpeta del módulo (sin recursión, sin enlaces y sin carpetas
     * que empiezan por {@code .}). Layout antiguo: todo {@code src/test/java} sin seguir enlaces.
     */
    public static List<Path> archivosTestModulo(Path salida, ModuloEscaneado modulo) {
        Path base = baseModulo(salida, modulo);
        List<Path> r = new ArrayList<>();
        if (!Files.isDirectory(base)) {
            return r;
        }
        try {
            List<Path> carpetas = new ArrayList<>();
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(base)) {
                for (Path d : ds) {
                    if (!d.getFileName().toString().startsWith(".") && !RutaSegura.esEnlace(d)
                            && Files.isDirectory(d, LinkOption.NOFOLLOW_LINKS)) {
                        carpetas.add(d);
                    }
                }
            }
            carpetas.sort(null);
            for (Path d : carpetas) {
                List<Path> archivos = new ArrayList<>();
                try (DirectoryStream<Path> ds = Files.newDirectoryStream(d, "*.java")) {
                    for (Path f : ds) {
                        if (Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS)) {
                            archivos.add(f);
                        }
                    }
                }
                archivos.sort(null);
                r.addAll(archivos);
            }
            Path raiz = raizTestsModulo(salida, modulo);
            if (Files.isDirectory(raiz)) {
                Files.walkFileTree(raiz, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes attrs) {
                        return !d.equals(raiz) && RutaSegura.esEnlace(d) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path f, BasicFileAttributes attrs) {
                        if (attrs.isRegularFile() && f.toString().endsWith(".java")) {
                            r.add(f);
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
            }
        } catch (IOException e) {
            throw new IllegalStateException("No se pudieron leer los tests generados en " + base + ": " + e.getMessage(), e);
        }
        return r;
    }

    /**
     * Ruta destino del test dentro del proyecto, relativa a su raíz y siempre con {@code /}:
     * {@code [rel/]<dirTests>/<paquete con />/<claseTest>.java}.
     */
    public static String destinoCopia(ModuloEscaneado modulo, InfoClase servicio, String claseTest) {
        String dirTests = "src/test/java";
        if (!modulo.modulo.fuentesTest.isEmpty()) {
            try {
                String d = modulo.modulo.dir.relativize(modulo.modulo.fuentesTest.get(0)).toString().replace('\\', '/');
                if (!d.isEmpty()) {
                    dirTests = d;
                }
            } catch (IllegalArgumentException e) {
                dirTests = "src/test/java";
            }
        }
        StringBuilder sb = new StringBuilder();
        String rel = modulo.modulo.rel;
        if (!rel.isEmpty()) {
            sb.append(rel.replace('\\', '/')).append('/');
        }
        sb.append(dirTests).append('/');
        if (!servicio.paquete.isEmpty()) {
            sb.append(servicio.paquete.replace('.', '/')).append('/');
        }
        return sb.append(claseTest).append(".java").toString();
    }

    private static String conSufijo(String base, String paquete) {
        segmentosPaquete(paquete);
        return comprobarCarpeta(base + SEP_COLISION + (paquete.isEmpty() ? "default" : paquete));
    }

    private static List<String> segmentosPaquete(String paquete) {
        List<String> r = new ArrayList<>();
        if (!paquete.isEmpty()) {
            for (String seg : paquete.split("\\.")) {
                if (!Textos.IDENTIFICADOR.matcher(seg).matches()) {
                    throw new SeguridadException("Segmento de paquete no válido: " + seg);
                }
                r.add(seg);
            }
        }
        return r;
    }

    private static String comprobarCarpeta(String nombre) {
        if (!NOMBRE_CARPETA.matcher(nombre).matches() || nombre.endsWith(".")) {
            throw new SeguridadException("Nombre de carpeta de test no válido: " + nombre);
        }
        return nombre;
    }

    private static String leer(Path f) {
        try {
            return Files.readString(f, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("No se pudo leer " + f + ": " + e.getMessage(), e);
        }
    }
}
