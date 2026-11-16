package com.junitgen.seguridad;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Punto único de ejecución de procesos. Solo permite:
 * <ul>
 *     <li>{@code java} y {@code javac} de un JDK validado;</li>
 *     <li>Maven con un conjunto cerrado de goals del maven-dependency-plugin;</li>
 *     <li>Gradle (nunca gradlew) con una única tarea de lectura del classpath.</li>
 * </ul>
 * El directorio de trabajo siempre está dentro de {@code <salida>/.junit-gen-tmp} y la salida del
 * proceso va a un archivo de log en el temporal. Nunca ejecuta mvnw, gradlew, fases Maven ni git.
 * <p>
 * El otro único punto que lanza un proceso es {@link DetectorEscritorio}: una consulta fija de solo
 * lectura al registro con {@code %SystemRoot%\System32\reg.exe} (ruta absoluta, sin entrada externa).
 */
public final class ProcesoSeguro {

    public static final Duration TIMEOUT_MAVEN = Duration.ofMinutes(10);
    public static final Duration TIMEOUT_JAVAC = Duration.ofMinutes(5);
    public static final Duration TIMEOUT_TESTS = Duration.ofMinutes(10);
    public static final String TAREA_GRADLE = "junitGenClasspath";

    private static final Pattern CLAVE_PROP = Pattern.compile("[A-Za-z][A-Za-z0-9_.-]*");
    private static final Pattern VALOR_PROP = Pattern.compile("[^\"&|<>^%\\r\\n]*");

    public enum GoalMaven {
        BUILD_CLASSPATH("org.apache.maven.plugins:maven-dependency-plugin:3.8.1:build-classpath"),
        GET("org.apache.maven.plugins:maven-dependency-plugin:3.8.1:get");

        private final String goal;

        GoalMaven(String goal) {
            this.goal = goal;
        }

        public String goal() {
            return goal;
        }
    }

    public record Resultado(int codigo, boolean timeout, Path log, String salida) {
        public boolean correcto() {
            return !timeout && codigo == 0;
        }
    }

    private final EscritorSeguro escritor;

    public ProcesoSeguro(EscritorSeguro escritor) {
        this.escritor = escritor;
    }

    /** Valida un JDK: deben existir bin/java, bin/javac y el archivo release. */
    public static Path validarJdk(Path home) {
        if (home == null) {
            throw new SeguridadException("JDK no indicado.");
        }
        Path h = home.toAbsolutePath().normalize();
        if (!Files.isRegularFile(ejecutable(h, "java")) || !Files.isRegularFile(ejecutable(h, "javac"))
                || !Files.isRegularFile(h.resolve("release"))) {
            throw new SeguridadException("La carpeta " + h + " no es un JDK válido (faltan bin/java, bin/javac o release).");
        }
        return h;
    }

    public static Path ejecutable(Path jdkHome, String nombre) {
        return jdkHome.resolve("bin").resolve(RutaSegura.WINDOWS ? nombre + ".exe" : nombre);
    }

    public Resultado javac(Path jdkHome, List<String> argumentos, Path cwd, Path log) {
        Path jdk = validarJdk(jdkHome);
        List<String> cmd = new ArrayList<>();
        cmd.add(ejecutable(jdk, "javac").toString());
        cmd.addAll(argumentos);
        return ejecutar(cmd, cwd, log, TIMEOUT_JAVAC, Map.of("JAVA_HOME", jdk.toString()));
    }

    public Resultado java(Path jdkHome, List<String> argumentos, Path cwd, Path log, Duration timeout) {
        Path jdk = validarJdk(jdkHome);
        for (String a : argumentos) {
            if (a.equals("-jar")) {
                throw new SeguridadException("No se permite ejecutar java -jar desde junit-gen.");
            }
        }
        List<String> cmd = new ArrayList<>();
        cmd.add(ejecutable(jdk, "java").toString());
        cmd.addAll(argumentos);
        return ejecutar(cmd, cwd, log, timeout == null ? TIMEOUT_TESTS : timeout, Map.of("JAVA_HOME", jdk.toString()));
    }

    public Resultado maven(Path mvn, GoalMaven goal, Path pom, Map<String, String> propiedades, Path settings,
                           Path javaHome, Path cwd, Path log) {
        String nombre = mvn.getFileName().toString().toLowerCase(Locale.ROOT);
        if (nombre.contains("mvnw") || !(nombre.equals("mvn.cmd") || nombre.equals("mvn.bat") || nombre.equals("mvn"))) {
            throw new SeguridadException("Ejecutable de Maven no permitido: " + mvn + " (nunca se usa mvnw).");
        }
        if (!Files.isRegularFile(mvn)) {
            throw new SeguridadException("No existe el ejecutable de Maven " + mvn);
        }
        if (RutaSegura.dentroOIgual(RutaSegura.normalizar(mvn), escritor.proyecto())) {
            throw new SeguridadException("No se ejecuta un Maven situado dentro del proyecto: " + mvn);
        }
        List<String> args = new ArrayList<>();
        args.add("-B");
        args.add("-q");
        if (pom != null) {
            Path pomReal = RutaSegura.normalizar(pom);
            if (!RutaSegura.dentroOIgual(pomReal, escritor.temporal())) {
                throw new SeguridadException("Maven solo se ejecuta sobre poms espejo del temporal, nunca sobre el proyecto: " + pom);
            }
            args.add("-f");
            args.add(pomReal.toString());
        } else if (goal == GoalMaven.BUILD_CLASSPATH) {
            throw new SeguridadException("build-classpath requiere un pom espejo.");
        }
        if (settings != null) {
            if (!Files.isRegularFile(settings)) {
                throw new SeguridadException("No existe el settings de Maven indicado: " + settings);
            }
            args.add("-s");
            args.add(settings.toAbsolutePath().normalize().toString());
        }
        args.add(goal.goal());
        for (Map.Entry<String, String> e : propiedades.entrySet()) {
            if (!CLAVE_PROP.matcher(e.getKey()).matches() || !VALOR_PROP.matcher(e.getValue()).matches()) {
                throw new SeguridadException("Propiedad de Maven no permitida: " + e.getKey());
            }
            args.add("-D" + e.getKey() + "=" + e.getValue());
        }
        Map<String, String> env = new LinkedHashMap<>();
        env.put("JAVA_HOME", validarJdkOJre(javaHome).toString());
        List<String> cmd = new ArrayList<>();
        Path mvnDir = mvn.toAbsolutePath().getParent();
        if (RutaSegura.WINDOWS) {
            // Se añade la carpeta de Maven al PATH y se invoca "mvn.cmd" sin ruta para evitar
            // problemas de comillas de cmd.exe con espacios.
            env.put("PATH", mvnDir + java.io.File.pathSeparator + System.getenv().getOrDefault("PATH", ""));
            cmd.add("cmd.exe");
            cmd.add("/c");
            cmd.add(mvn.getFileName().toString());
        } else {
            cmd.add(mvn.toAbsolutePath().toString());
        }
        cmd.addAll(args);
        return ejecutar(cmd, cwd, log, TIMEOUT_MAVEN, env);
    }

    public Resultado gradle(Path gradle, Path dirModulo, Path initScript, Path projectCacheDir, Path javaHome,
                            Path cwd, Path log) {
        String nombre = gradle.getFileName().toString().toLowerCase(Locale.ROOT);
        if (nombre.contains("gradlew") || !(nombre.equals("gradle.bat") || nombre.equals("gradle"))) {
            throw new SeguridadException("Ejecutable de Gradle no permitido: " + gradle + " (nunca se usa gradlew).");
        }
        if (RutaSegura.dentroOIgual(RutaSegura.normalizar(gradle), escritor.proyecto())) {
            throw new SeguridadException("No se ejecuta un Gradle situado dentro del proyecto: " + gradle);
        }
        Path init = RutaSegura.normalizar(initScript);
        Path cache = RutaSegura.normalizar(projectCacheDir);
        if (!RutaSegura.dentroOIgual(init, escritor.temporal()) || !RutaSegura.dentroOIgual(cache, escritor.temporal())) {
            throw new SeguridadException("El init script y la caché de Gradle deben estar en el temporal.");
        }
        List<String> cmd = new ArrayList<>();
        Map<String, String> env = new LinkedHashMap<>();
        env.put("JAVA_HOME", validarJdkOJre(javaHome).toString());
        if (RutaSegura.WINDOWS) {
            env.put("PATH", gradle.toAbsolutePath().getParent() + java.io.File.pathSeparator
                    + System.getenv().getOrDefault("PATH", ""));
            cmd.add("cmd.exe");
            cmd.add("/c");
            cmd.add(gradle.getFileName().toString());
        } else {
            cmd.add(gradle.toAbsolutePath().toString());
        }
        cmd.add("-p");
        cmd.add(dirModulo.toAbsolutePath().normalize().toString());
        cmd.add("--project-cache-dir");
        cmd.add(cache.toString());
        cmd.add("-I");
        cmd.add(init.toString());
        cmd.add(TAREA_GRADLE);
        cmd.add("-q");
        return ejecutar(cmd, cwd, log, TIMEOUT_MAVEN, env);
    }

    private static Path validarJdkOJre(Path home) {
        if (home == null) {
            throw new SeguridadException("Falta JAVA_HOME para el proceso.");
        }
        Path h = home.toAbsolutePath().normalize();
        if (!Files.isRegularFile(ejecutable(h, "java"))) {
            throw new SeguridadException("La carpeta " + h + " no contiene bin/java.");
        }
        return h;
    }

    private Resultado ejecutar(List<String> cmd, Path cwd, Path log, Duration timeout, Map<String, String> env) {
        Path cwdReal = escritor.carpetaTemporal(cwd);
        Path logReal = escritor.prepararArchivoTemporal(log);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(cwdReal.toFile());
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.to(logReal.toFile()));
        Map<String, String> entorno = pb.environment();
        entorno.remove("MAVEN_ARGS");
        entorno.remove("MAVEN_CONFIG");
        entorno.remove("JAVA_TOOL_OPTIONS");
        entorno.remove("_JAVA_OPTIONS");
        entorno.remove("JDK_JAVA_OPTIONS");
        entorno.putAll(env);
        Process p = null;
        boolean timeoutAlcanzado = false;
        int codigo = -1;
        try {
            p = pb.start();
            try (OutputStream in = p.getOutputStream()) {
                in.flush();
            }
            if (!p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                timeoutAlcanzado = true;
                p.descendants().forEach(ProcessHandle::destroyForcibly);
                p.destroyForcibly();
                p.waitFor(30, TimeUnit.SECONDS);
            } else {
                codigo = p.exitValue();
            }
        } catch (IOException e) {
            throw new SeguridadException("No se pudo ejecutar " + cmd.get(0) + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (p != null) {
                p.descendants().forEach(ProcessHandle::destroyForcibly);
                p.destroyForcibly();
            }
            throw new SeguridadException("Proceso interrumpido: " + cmd.get(0), e);
        } finally {
            if (p != null) {
                try {
                    p.getInputStream().close();
                } catch (IOException e) {
                    // El flujo de salida va a un archivo; un fallo al cerrar no afecta al resultado.
                    System.err.println("Aviso: no se pudo cerrar el flujo del proceso: " + e.getMessage());
                }
                try {
                    p.getErrorStream().close();
                } catch (IOException e) {
                    System.err.println("Aviso: no se pudo cerrar el flujo de errores del proceso: " + e.getMessage());
                }
            }
        }
        return new Resultado(codigo, timeoutAlcanzado, logReal, leerLog(logReal));
    }

    public static String leerLog(Path log) {
        try {
            byte[] datos = Files.readAllBytes(log);
            String enc = System.getProperty("native.encoding", System.getProperty("sun.jnu.encoding", "UTF-8"));
            Charset cs;
            try {
                cs = Charset.forName(enc);
            } catch (RuntimeException e) {
                cs = Charset.defaultCharset();
            }
            String texto = new String(datos, java.nio.charset.StandardCharsets.UTF_8);
            if (texto.indexOf('�') >= 0) {
                texto = new String(datos, cs);
            }
            return texto;
        } catch (IOException e) {
            return "(no se pudo leer el log " + log + ": " + e.getMessage() + ")";
        }
    }
}
