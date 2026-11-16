import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.core.util.Separators;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Instalador de junit-gen para GitHub Copilot (VS Code). Lo lanzan instalar-copilot.ps1 e instalar-copilot.sh:
 *
 * <pre>
 * java -cp &lt;repo&gt;/plugin/junit-gen.jar &lt;repo&gt;/instalador/InstalarCopilot.java --repo &lt;dir&gt; --java-cmd &lt;valor&gt;
 *      [--vscode-mcp &lt;archivo&gt;] [--copilot-dir &lt;carpeta&gt;]
 * </pre>
 *
 * Registra el servidor MCP "junit-gen" en el mcp.json de VS Code (clave "servers") y en
 * &lt;copilot-dir&gt;/mcp-config.json (clave "mcpServers"), conservando los dem&aacute;s servidores y claves, y copia la
 * skill a &lt;copilot-dir&gt;/skills/generartest/SKILL.md. Es idempotente: si nada cambia no escribe. Antes de
 * cambiar un archivo deja una copia &lt;archivo&gt;.bak y escribe de forma at&oacute;mica. Un JSON que no se puede
 * interpretar no se toca. No descarga nada.
 *
 * Salida: 0 correcto, 1 argumentos o repositorio no v&aacute;lidos, 2 si alg&uacute;n archivo no se pudo actualizar.
 */
public class InstalarCopilot {

    private static final String SERVIDOR = "junit-gen";

    private static final JsonMapper LECTOR = JsonMapper.builder()
            .enable(JsonReadFeature.ALLOW_JAVA_COMMENTS)
            .enable(JsonReadFeature.ALLOW_TRAILING_COMMA)
            .build();

    private final List<String> hechos = new ArrayList<>();
    private boolean fallos;

    public static void main(String[] args) {
        int codigo;
        try {
            codigo = new InstalarCopilot().ejecutar(args);
        } catch (RuntimeException e) {
            System.err.println("Error inesperado: " + e);
            codigo = 2;
        }
        System.exit(codigo);
    }

    private int ejecutar(String[] args) {
        String repoTxt = null;
        String javaCmd = null;
        String vscodeTxt = null;
        String copilotTxt = null;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (i + 1 >= args.length) {
                return uso("Falta el valor de " + a + ".");
            }
            String v = args[++i];
            switch (a) {
                case "--repo" -> repoTxt = v;
                case "--java-cmd" -> javaCmd = v;
                case "--vscode-mcp" -> vscodeTxt = v;
                case "--copilot-dir" -> copilotTxt = v;
                default -> {
                    return uso("Parametro desconocido: " + a);
                }
            }
        }
        if (repoTxt == null || repoTxt.isBlank() || javaCmd == null || javaCmd.isBlank()) {
            return uso("Faltan --repo o --java-cmd.");
        }
        Path repo;
        Path vscodeMcp;
        Path copilotDir;
        try {
            repo = Path.of(repoTxt).toAbsolutePath().normalize();
            vscodeMcp = vacio(vscodeTxt) ? mcpVsCodePorDefecto() : Path.of(vscodeTxt).toAbsolutePath().normalize();
            copilotDir = vacio(copilotTxt) ? Path.of(System.getProperty("user.home"), ".copilot")
                    : Path.of(copilotTxt).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            return uso("Ruta no valida: " + e.getMessage());
        }

        Path jar = repo.resolve("plugin").resolve("junit-gen.jar");
        Path skill = repo.resolve("src").resolve("main").resolve("resources").resolve("skills")
                .resolve("generartest").resolve("SKILL.md");
        if (!Files.isRegularFile(jar)) {
            System.err.println("No se encuentra el JAR de junit-gen: " + jar);
            return 1;
        }
        if (!Files.isRegularFile(skill)) {
            System.err.println("No se encuentra la skill de junit-gen: " + skill);
            return 1;
        }

        System.out.println("junit-gen: configurando GitHub Copilot");
        System.out.println("  JAR:  " + jar);
        System.out.println("  Java: " + javaCmd);
        System.out.println();

        registrar(vscodeMcp, "servers", jar, javaCmd, false);
        registrar(copilotDir.resolve("mcp-config.json"), "mcpServers", jar, javaCmd, true);
        copiarSkill(skill, copilotDir.resolve("skills").resolve("generartest").resolve("SKILL.md"));

        System.out.println();
        System.out.println("Resumen:");
        for (String h : hechos) {
            System.out.println("  - " + h);
        }
        System.out.println();
        if (fallos) {
            System.err.println("Algunos archivos no se pudieron actualizar (ver mensajes anteriores).");
            return 2;
        }
        System.out.println("Listo. Siguientes pasos:");
        System.out.println("  1. Reinicia VS Code.");
        System.out.println("  2. Abre el chat de Copilot en modo Agent.");
        System.out.println("  3. Escribe /generartest (por ejemplo: /generartest generame los tests de 2 servicios).");
        return 0;
    }

    private void registrar(Path archivo, String claveServidores, Path jar, String javaCmd, boolean conTools) {
        try {
            String original = Files.exists(archivo) ? Files.readString(archivo, StandardCharsets.UTF_8) : null;
            boolean vacio = original == null || original.replace("\ufeff", "").isBlank();
            ObjectNode raiz;
            if (vacio) {
                raiz = LECTOR.createObjectNode();
            } else {
                JsonNode leido;
                try {
                    leido = LECTOR.readTree(original.replace("\ufeff", ""));
                } catch (IOException e) {
                    error(archivo, "no es un JSON valido (" + primeraLinea(e.getMessage()) + "); no se modifica");
                    return;
                }
                if (!(leido instanceof ObjectNode o)) {
                    error(archivo, "su contenido no es un objeto JSON; no se modifica");
                    return;
                }
                raiz = o;
            }
            ObjectNode antes = raiz.deepCopy();

            JsonNode servidores = raiz.get(claveServidores);
            ObjectNode mapa;
            if (servidores == null || servidores.isNull()) {
                mapa = raiz.putObject(claveServidores);
            } else if (servidores instanceof ObjectNode o) {
                mapa = o;
            } else {
                error(archivo, "\"" + claveServidores + "\" no es un objeto; no se modifica");
                return;
            }
            JsonNode existente = mapa.get(SERVIDOR);
            ObjectNode entrada;
            if (existente == null || existente.isNull()) {
                entrada = mapa.putObject(SERVIDOR);
            } else if (existente instanceof ObjectNode o) {
                entrada = o;
            } else {
                error(archivo, "la entrada \"" + SERVIDOR + "\" no es un objeto; no se modifica");
                return;
            }
            entrada.put("type", "stdio");
            entrada.put("command", javaCmd);
            ArrayNode a = entrada.putArray("args");
            a.add("-Dfile.encoding=UTF-8");
            a.add("-jar");
            a.add(jar.toString());
            a.add("mcp");
            if (conTools && !entrada.has("tools")) {
                entrada.putArray("tools").add("*");
            }

            if (!vacio && raiz.equals(antes)) {
                hechos.add(archivo + ": ya estaba configurado");
                return;
            }
            String finLinea = original != null && original.contains("\r\n") ? "\r\n"
                    : original != null && original.contains("\n") ? "\n" : System.lineSeparator();
            boolean tabuladores = original != null && usaTabuladores(original);
            String texto = escribir(raiz, tabuladores, finLinea);
            if (original != null) {
                Files.copy(archivo, hermano(archivo, ".bak"), StandardCopyOption.REPLACE_EXISTING);
            }
            escribirAtomico(archivo, texto.getBytes(StandardCharsets.UTF_8));
            hechos.add(archivo + ": servidor \"" + SERVIDOR + "\" registrado"
                    + (original != null ? " (copia previa en " + hermano(archivo, ".bak").getFileName() + ")" : ""));
        } catch (IOException | RuntimeException e) {
            error(archivo, "no se pudo actualizar: " + e.getMessage());
        }
    }

    private void copiarSkill(Path origen, Path destino) {
        try {
            byte[] nuevo = Files.readAllBytes(origen);
            if (Files.isRegularFile(destino) && Arrays.equals(Files.readAllBytes(destino), nuevo)) {
                hechos.add(destino + ": la skill ya estaba al dia");
                return;
            }
            escribirAtomico(destino, nuevo);
            hechos.add(destino + ": skill copiada");
        } catch (IOException | RuntimeException e) {
            error(destino, "no se pudo copiar la skill: " + e.getMessage());
        }
    }

    private static String escribir(ObjectNode raiz, boolean tabuladores, String finLinea) throws IOException {
        DefaultIndenter sangria = new DefaultIndenter(tabuladores ? "\t" : "  ", finLinea);
        Separators sep = Separators.createDefaultInstance()
                .withObjectFieldValueSpacing(Separators.Spacing.AFTER)
                .withObjectEmptySeparator("")
                .withArrayEmptySeparator("");
        DefaultPrettyPrinter pp = new DefaultPrettyPrinter(sep);
        pp.indentObjectsWith(sangria);
        pp.indentArraysWith(sangria);
        return LECTOR.writer(pp).writeValueAsString(raiz) + finLinea;
    }

    private static void escribirAtomico(Path destino, byte[] contenido) throws IOException {
        Path carpeta = destino.toAbsolutePath().getParent();
        Files.createDirectories(carpeta);
        Path tmp = Files.createTempFile(carpeta, destino.getFileName().toString() + ".", ".tmp");
        try {
            Files.write(tmp, contenido);
            try {
                Files.move(tmp, destino, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, destino, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static boolean usaTabuladores(String texto) {
        for (String linea : texto.split("\\R")) {
            if (linea.startsWith("\t")) {
                return true;
            }
            if (linea.startsWith(" ")) {
                return false;
            }
        }
        return false;
    }

    private static Path mcpVsCodePorDefecto() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String home = System.getProperty("user.home");
        if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            Path base = vacio(appData) ? Path.of(home, "AppData", "Roaming") : Path.of(appData);
            return base.resolve("Code").resolve("User").resolve("mcp.json");
        }
        if (os.contains("mac")) {
            return Path.of(home, "Library", "Application Support", "Code", "User", "mcp.json");
        }
        return Path.of(home, ".config", "Code", "User", "mcp.json");
    }

    private static Path hermano(Path archivo, String sufijo) {
        return archivo.resolveSibling(archivo.getFileName().toString() + sufijo);
    }

    private static boolean vacio(String s) {
        return s == null || s.isBlank();
    }

    private static String primeraLinea(String s) {
        if (s == null) {
            return "";
        }
        int i = s.indexOf('\n');
        return (i < 0 ? s : s.substring(0, i)).trim();
    }

    private void error(Path archivo, String motivo) {
        fallos = true;
        System.err.println("ERROR en " + archivo + ": " + motivo);
        hechos.add(archivo + ": NO actualizado (" + motivo + ")");
    }

    private static int uso(String motivo) {
        System.err.println(motivo);
        System.err.println("Uso: java -cp <repo>/plugin/junit-gen.jar <repo>/instalador/InstalarCopilot.java"
                + " --repo <carpeta> --java-cmd <java> [--vscode-mcp <archivo>] [--copilot-dir <carpeta>]");
        return 1;
    }
}
