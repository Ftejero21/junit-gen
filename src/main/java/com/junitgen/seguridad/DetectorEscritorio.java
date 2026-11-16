package com.junitgen.seguridad;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Localiza la carpeta real del Escritorio del usuario (solo lectura). En Windows consulta el registro
 * con {@code %SystemRoot%\System32\reg.exe} por ruta absoluta y argumentos fijos (nunca por nombre ni
 * por PATH: el directorio de trabajo del servidor es el proyecto analizado). Es, junto a
 * {@link ProcesoSeguro}, el único punto de junit-gen que lanza un proceso.
 */
public final class DetectorEscritorio {

    static final Duration TIMEOUT = Duration.ofSeconds(5);
    static final int MAX_BYTES = 64 * 1024;
    static final String CLAVE = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\User Shell Folders";

    private static final Pattern LINEA = Pattern.compile("(?m)^\\s*Desktop\\s+REG_(?:EXPAND_)?SZ\\s+(.+?)\\s*$");
    private static final Pattern VARIABLE = Pattern.compile("%([^%]+)%");

    private DetectorEscritorio() {
    }

    /** Escritorio del usuario actual, o null si no se encuentra ninguno. */
    public static Path detectar() {
        String home = System.getProperty("user.home");
        Path h = null;
        if (home != null && !home.isBlank()) {
            try {
                h = Path.of(home);
            } catch (InvalidPathException e) {
                System.err.println("user.home no válido (" + home + "): " + e.getMessage());
            }
        }
        return detectar(RutaSegura.WINDOWS ? DetectorEscritorio::consultarRegistro : null, System::getenv, h);
    }

    /**
     * Versión inyectable: {@code consultaRegistro} devuelve la salida en bruto de {@code reg query}
     * (o null), {@code env} resuelve variables de entorno y {@code home} es la carpeta del usuario.
     */
    public static Path detectar(Supplier<byte[]> consultaRegistro, Function<String, String> env, Path home) {
        if (RutaSegura.WINDOWS && consultaRegistro != null) {
            byte[] bruto = consultaRegistro.get();
            Path delRegistro = desdeRegistro(bruto, env);
            if (delRegistro != null) {
                return delRegistro;
            }
        }
        for (Path c : candidatos(env, home)) {
            if (Files.isDirectory(c)) {
                return c;
            }
        }
        return null;
    }

    /** Fallbacks en orden de preferencia. */
    static List<Path> candidatos(Function<String, String> env, Path home) {
        List<Path> r = new ArrayList<>();
        if (home != null) {
            r.add(home.resolve("Desktop"));
            r.add(home.resolve("Escritorio"));
        }
        if (RutaSegura.WINDOWS) {
            String oneDrive = env == null ? null : env.apply("OneDrive");
            if (oneDrive != null && !oneDrive.isBlank()) {
                try {
                    Path od = Path.of(oneDrive.trim());
                    r.add(od.resolve("Desktop"));
                    r.add(od.resolve("Escritorio"));
                } catch (InvalidPathException e) {
                    System.err.println("%OneDrive% no es una ruta válida: " + e.getMessage());
                }
            }
            if (home != null) {
                r.add(home.resolve("OneDrive").resolve("Desktop"));
                r.add(home.resolve("OneDrive").resolve("Escritorio"));
            }
        }
        return r;
    }

    /** Prueba varias codificaciones y se queda con la primera que da un directorio existente. */
    static Path desdeRegistro(byte[] bruto, Function<String, String> env) {
        if (bruto == null || bruto.length == 0) {
            return null;
        }
        for (Charset cs : codificaciones()) {
            String valor = valorDeRegQuery(new String(bruto, cs));
            if (valor == null) {
                continue;
            }
            String expandido = expandir(valor, env);
            if (expandido == null || expandido.isBlank()) {
                continue;
            }
            try {
                Path p = Path.of(expandido.trim());
                if (p.isAbsolute() && Files.isDirectory(p)) {
                    return p;
                }
            } catch (InvalidPathException e) {
                System.err.println("Valor del Escritorio no válido con " + cs + ": " + e.getMessage());
            }
        }
        return null;
    }

    private static List<Charset> codificaciones() {
        Set<Charset> r = new LinkedHashSet<>();
        for (String nombre : new String[]{System.getProperty("native.encoding"), System.getProperty("sun.jnu.encoding"),
                "IBM850", "IBM437"}) {
            if (nombre == null || nombre.isBlank()) {
                continue;
            }
            try {
                r.add(Charset.forName(nombre));
            } catch (IllegalArgumentException e) {
                System.err.println("Codificación no disponible para leer el registro: " + nombre);
            }
        }
        r.add(StandardCharsets.UTF_8);
        return new ArrayList<>(r);
    }

    /** Valor de la línea {@code Desktop  REG_SZ|REG_EXPAND_SZ  <valor>} de la salida de reg query, o null. */
    public static String valorDeRegQuery(String salida) {
        if (salida == null) {
            return null;
        }
        Matcher m = LINEA.matcher(salida);
        return m.find() ? m.group(1) : null;
    }

    /** Expande {@code %VAR%}; si alguna variable no se resuelve devuelve null (el valor se descarta). */
    static String expandir(String valor, Function<String, String> env) {
        Matcher m = VARIABLE.matcher(valor);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String v = env == null ? null : env.apply(m.group(1));
            if (v == null || v.isBlank()) {
                return null;
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(v));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** reg.exe por ruta absoluta, argumentos fijos, stdin cerrado, timeout de 5 s y stdout limitado a 64 KB. */
    static byte[] consultarRegistro() {
        String systemRoot = System.getenv("SystemRoot");
        if (systemRoot == null || systemRoot.isBlank()) {
            System.err.println("SystemRoot no definido: no se consulta el registro para localizar el Escritorio.");
            return null;
        }
        Path system32;
        Path reg;
        try {
            system32 = Path.of(systemRoot.trim(), "System32");
            reg = system32.resolve("reg.exe");
        } catch (InvalidPathException e) {
            System.err.println("SystemRoot no es una ruta válida: " + e.getMessage());
            return null;
        }
        if (!reg.isAbsolute() || !Files.isRegularFile(reg)) {
            System.err.println("No existe " + reg + ": no se consulta el registro para localizar el Escritorio.");
            return null;
        }
        ProcessBuilder pb = new ProcessBuilder(reg.toString(), "query", CLAVE, "/v", "Desktop");
        pb.directory(system32.toFile());
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process p = null;
        try {
            p = pb.start();
            p.getOutputStream().close();
            InputStream salida = p.getInputStream();
            CompletableFuture<byte[]> lectura = CompletableFuture.supplyAsync(() -> leerLimitado(salida));
            if (!p.waitFor(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                System.err.println("reg.exe no respondió en " + TIMEOUT.toSeconds() + " s; se usan las rutas habituales del Escritorio.");
                p.destroyForcibly();
                return null;
            }
            return lectura.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (IOException | ExecutionException | TimeoutException e) {
            System.err.println("No se pudo consultar el Escritorio en el registro: " + e.getMessage());
            if (p != null) {
                p.destroyForcibly();
            }
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
            return null;
        }
    }

    private static byte[] leerLimitado(InputStream in) {
        try (in) {
            return in.readNBytes(MAX_BYTES);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
