package com.junitgen.seguridad;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Hook {@code PreToolUse} del plugin de Claude Code que impide a la IA editar archivos o ejecutar comandos
 * de terminal mientras trabaja con junit-gen.
 *
 * <p>Uso: {@code java -cp junit-gen.jar com.junitgen.seguridad.GuardaTanda <uso|comprobar> <carpeta-datos>},
 * con la entrada JSON del hook por stdin ({@code session_id}, {@code prompt_id}, {@code cwd}, ...).
 *
 * <ul>
 *   <li>{@code uso} (antes de cada herramienta de junit-gen): anota la sesión en
 *       {@code <datos>/tandas/<session_id>} con el {@code prompt_id} del mensaje de usuario en curso y borra
 *       marcadores de más de 24 h. Sale siempre 0.</li>
 *   <li>{@code comprobar} (antes de Bash, PowerShell, Edit, Write, MultiEdit o NotebookEdit): bloquea (salida
 *       2, motivo por stderr) si la misma sesión ya usó junit-gen en el mismo mensaje del usuario. Si falta el
 *       {@code prompt_id} en el marcador o en la entrada, bloquea solo si la última llamada a junit-gen fue
 *       hace menos de 10 minutos. No escribe nada.</li>
 * </ul>
 *
 * <p>Alcance: el bloqueo dura lo que el turno en el que la IA usó junit-gen; el siguiente mensaje del usuario
 * trae otro {@code prompt_id} y lo libera. No bloquea lecturas ni otras herramientas. Es defensa en
 * profundidad y falla en abierto: ante datos no válidos (modo desconocido, carpeta no absoluta,
 * {@code session_id} no válido, carpeta de datos dentro del {@code cwd}, enlaces simbólicos) o cualquier
 * error, no hace nada y sale 0. Nunca escribe en stdout ni dentro del proyecto.
 */
public final class GuardaTanda {

    /** Validez del marcador cuando falta el {@code prompt_id}. */
    static final Duration VIGENCIA_SIN_PROMPT = Duration.ofMinutes(10);
    /** Antigüedad a partir de la cual se borran marcadores en el modo {@code uso}. */
    static final Duration CADUCIDAD_MARCADOR = Duration.ofHours(24);
    /** Máximo de entradas revisadas en cada limpieza. */
    static final int MAX_REVISADOS = 500;
    /** Bytes máximos leídos de un marcador. */
    private static final int MAX_BYTES_MARCADOR = 256;

    static final int SALIDA_BLOQUEO = 2;
    static final String CARPETA_TANDAS = "tandas";
    static final Pattern ID_VALIDO = Pattern.compile("[A-Za-z0-9_-]{1,100}");

    static final String MENSAJE_BLOQUEO = "junit-gen: bloqueado. Durante una tanda de junit-gen no se pueden editar "
            + "archivos ni ejecutar comandos de terminal. Sigue solo con las herramientas de junit-gen: haz la llamada "
            + "que indica accionRequerida. El bloqueo termina con el siguiente mensaje del usuario.";

    private static final Set<String> CLAVES = Set.of("session_id", "prompt_id", "cwd");

    private GuardaTanda() {
    }

    public static void main(String[] args) {
        boolean bloquear = false;
        try {
            bloquear = ejecutar(args, System.in);
        } catch (Throwable e) {
            System.err.println("junit-gen (GuardaTanda): sin efecto por un error: " + e);
        }
        if (bloquear) {
            System.err.println(MENSAJE_BLOQUEO);
            System.err.flush();
            System.exit(SALIDA_BLOQUEO);
        }
    }

    /** true si hay que bloquear la herramienta. */
    static boolean ejecutar(String[] args, InputStream entrada) throws IOException {
        if (args == null || args.length < 2) {
            aviso("faltan argumentos (modo y carpeta de datos)");
            return false;
        }
        String modo = args[0];
        if (!"uso".equals(modo) && !"comprobar".equals(modo)) {
            aviso("modo desconocido");
            return false;
        }
        String json = new String(entrada.readAllBytes(), StandardCharsets.UTF_8);

        Path datos = rutaAbsoluta(args[1]);
        if (datos == null) {
            aviso("la carpeta de datos no es una ruta absoluta");
            return false;
        }
        Map<String, String> campos = camposSuperiores(json);
        String sesion = campos.get("session_id");
        if (sesion == null || !ID_VALIDO.matcher(sesion).matches()) {
            aviso("session_id ausente o no valido");
            return false;
        }
        String prompt = campos.get("prompt_id");
        if (prompt != null && !ID_VALIDO.matcher(prompt).matches()) {
            prompt = null;
        }
        if (dentroDelCwd(datos, campos.get("cwd"))) {
            aviso("la carpeta de datos esta dentro del directorio de trabajo");
            return false;
        }
        if (existe(datos) && RutaSegura.esEnlace(datos)) {
            aviso("la carpeta de datos es un enlace");
            return false;
        }
        Path tandas = datos.resolve(CARPETA_TANDAS);
        if (existe(tandas) && RutaSegura.esEnlace(tandas)) {
            aviso("la carpeta de tandas es un enlace");
            return false;
        }
        Path marcador = tandas.resolve(sesion);
        if ("uso".equals(modo)) {
            anotar(datos, tandas, marcador, prompt);
            return false;
        }
        return debeBloquear(marcador, prompt, Instant.now());
    }

    private static void anotar(Path datos, Path tandas, Path marcador, String prompt) throws IOException {
        Files.createDirectories(tandas);
        if (RutaSegura.esEnlace(datos) || RutaSegura.esEnlace(tandas)
                || !Files.isDirectory(tandas, LinkOption.NOFOLLOW_LINKS)) {
            aviso("la carpeta de tandas no es una carpeta normal");
            return;
        }
        if (existe(marcador) && (RutaSegura.esEnlace(marcador) || !Files.isRegularFile(marcador, LinkOption.NOFOLLOW_LINKS))) {
            aviso("el marcador no es un archivo normal");
            return;
        }
        Files.write(marcador, (prompt == null ? "" : prompt).getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS);
        limpiar(tandas, marcador);
    }

    /** Borra marcadores de más de {@link #CADUCIDAD_MARCADOR}, revisando como mucho {@link #MAX_REVISADOS} entradas. */
    private static void limpiar(Path tandas, Path actual) {
        Instant limite = Instant.now().minus(CADUCIDAD_MARCADOR);
        int revisados = 0;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(tandas)) {
            for (Path p : ds) {
                if (++revisados > MAX_REVISADOS) {
                    break;
                }
                Path nombre = p.getFileName();
                if (nombre == null || p.equals(actual) || !ID_VALIDO.matcher(nombre.toString()).matches()) {
                    continue;
                }
                try {
                    BasicFileAttributes a = Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    if (a.isRegularFile() && a.lastModifiedTime().toInstant().isBefore(limite)) {
                        Files.deleteIfExists(p);
                    }
                } catch (IOException e) {
                    aviso("no se pudo revisar " + nombre + ": " + e.getMessage());
                }
            }
        } catch (IOException e) {
            aviso("no se pudo limpiar la carpeta de tandas: " + e.getMessage());
        }
    }

    static boolean debeBloquear(Path marcador, String prompt, Instant ahora) throws IOException {
        if (!existe(marcador) || RutaSegura.esEnlace(marcador)) {
            return false;
        }
        BasicFileAttributes a = Files.readAttributes(marcador, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!a.isRegularFile()) {
            return false;
        }
        String anotado;
        try (InputStream in = Files.newInputStream(marcador, LinkOption.NOFOLLOW_LINKS)) {
            anotado = new String(in.readNBytes(MAX_BYTES_MARCADOR), StandardCharsets.UTF_8).trim();
        }
        if (anotado.isEmpty() || !ID_VALIDO.matcher(anotado).matches()) {
            anotado = null;
        }
        if (anotado != null && prompt != null) {
            return anotado.equals(prompt);
        }
        Instant modificado = a.lastModifiedTime().toInstant();
        return modificado.isAfter(ahora.minus(VIGENCIA_SIN_PROMPT));
    }

    private static boolean dentroDelCwd(Path datos, String cwd) {
        Path dir = rutaAbsoluta(cwd);
        if (dir == null) {
            return false;
        }
        try {
            return RutaSegura.dentroOIgual(RutaSegura.normalizar(datos), RutaSegura.normalizar(dir));
        } catch (SeguridadException e) {
            return RutaSegura.dentroOIgual(datos, dir);
        }
    }

    private static Path rutaAbsoluta(String texto) {
        if (texto == null || texto.isBlank()) {
            return null;
        }
        try {
            Path p = Path.of(texto.trim());
            return p.isAbsolute() ? p.normalize() : null;
        } catch (InvalidPathException e) {
            return null;
        }
    }

    private static boolean existe(Path p) {
        return Files.exists(p, LinkOption.NOFOLLOW_LINKS);
    }

    private static void aviso(String motivo) {
        System.err.println("junit-gen (GuardaTanda): sin efecto, " + motivo + ".");
    }

    // --- Escáner JSON mínimo: solo los campos de texto del nivel superior ---

    /**
     * Valores de texto de {@code session_id}, {@code prompt_id} y {@code cwd} del nivel superior del objeto
     * JSON. Respeta cadenas, escapes y anidamiento; ante un JSON mal formado devuelve un mapa vacío.
     */
    static Map<String, String> camposSuperiores(String json) {
        Map<String, String> res = new HashMap<>();
        try {
            Escaner e = new Escaner(json);
            e.espacios();
            e.esperar('{');
            e.espacios();
            if (e.ver() == '}') {
                return res;
            }
            while (true) {
                e.espacios();
                String clave = e.cadena();
                e.espacios();
                e.esperar(':');
                e.espacios();
                if (e.ver() == '"') {
                    String valor = e.cadena();
                    if (CLAVES.contains(clave)) {
                        res.put(clave, valor);
                    }
                } else {
                    e.saltarValor();
                }
                e.espacios();
                char c = e.siguiente();
                if (c == '}') {
                    return res;
                }
                if (c != ',') {
                    return new HashMap<>();
                }
            }
        } catch (IllegalArgumentException | IndexOutOfBoundsException ex) {
            return new HashMap<>();
        }
    }

    private static final class Escaner {
        private final String s;
        private int i;

        Escaner(String s) {
            this.s = s == null ? "" : s;
        }

        char ver() {
            if (i >= s.length()) {
                throw new IllegalArgumentException("fin inesperado");
            }
            return s.charAt(i);
        }

        char siguiente() {
            char c = ver();
            i++;
            return c;
        }

        void espacios() {
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c != ' ' && c != '\t' && c != '\n' && c != '\r' && c != '\uFEFF') {
                    return;
                }
                i++;
            }
        }

        void esperar(char c) {
            if (siguiente() != c) {
                throw new IllegalArgumentException("se esperaba " + c);
            }
        }

        String cadena() {
            esperar('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = siguiente();
                if (c == '"') {
                    return sb.toString();
                }
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                char x = siguiente();
                switch (x) {
                    case '"', '\\', '/' -> sb.append(x);
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        if (i + 4 > s.length()) {
                            throw new IllegalArgumentException("escape incompleto");
                        }
                        sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                    }
                    default -> throw new IllegalArgumentException("escape no válido");
                }
            }
        }

        /** Salta un valor que no es una cadena: objeto, array o literal (sin recursión). */
        void saltarValor() {
            char c = ver();
            if (c == '{' || c == '[') {
                int profundidad = 0;
                while (true) {
                    char x = ver();
                    if (x == '"') {
                        saltarCadena();
                        continue;
                    }
                    i++;
                    if (x == '{' || x == '[') {
                        profundidad++;
                    } else if (x == '}' || x == ']') {
                        profundidad--;
                        if (profundidad == 0) {
                            return;
                        }
                    }
                }
            }
            int inicio = i;
            while (i < s.length()) {
                char x = s.charAt(i);
                if (x == ',' || x == '}' || x == ']' || x == ' ' || x == '\t' || x == '\n' || x == '\r') {
                    break;
                }
                i++;
            }
            if (i == inicio) {
                throw new IllegalArgumentException("valor vacío");
            }
        }

        private void saltarCadena() {
            esperar('"');
            while (true) {
                char c = siguiente();
                if (c == '"') {
                    return;
                }
                if (c == '\\') {
                    siguiente();
                }
            }
        }
    }
}
