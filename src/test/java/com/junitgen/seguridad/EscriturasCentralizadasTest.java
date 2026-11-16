package com.junitgen.seguridad;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ninguna clase fuera del paquete seguridad escribe, borra, crea carpetas, abre streams de salida a
 * archivos ni lanza procesos.
 */
class EscriturasCentralizadasTest {

    private static final List<Pattern> PROHIBIDOS = List.of(
            Pattern.compile("Files\\.(write|writeString|delete|deleteIfExists|createDirector(y|ies)|createFile|newOutputStream|"
                    + "newBufferedWriter|copy|move|createTempFile|createTempDirectory|createSymbolicLink|createLink|setLastModifiedTime)\\s*\\("),
            Pattern.compile("new\\s+(FileOutputStream|FileWriter|RandomAccessFile|PrintWriter|FileChannel)\\s*\\((?!\\s*FileDescriptor\\.)"),
            Pattern.compile("\\.(delete|deleteOnExit|mkdir|mkdirs|renameTo|setLastModified)\\s*\\(\\s*\\)"),
            Pattern.compile("new\\s+ProcessBuilder\\s*\\("),
            Pattern.compile("Runtime\\.getRuntime\\(\\)\\.exec"),
            Pattern.compile("FileChannel\\.open"));

    @Test
    void escriturasYProcesosSoloEnSeguridad() throws IOException {
        Path raiz = Paths.get("src", "main", "java");
        assertThat(raiz).isDirectory();
        List<String> infracciones = new ArrayList<>();
        int revisados = 0;
        try (Stream<Path> s = Files.walk(raiz)) {
            for (Path f : (Iterable<Path>) s.filter(p -> p.toString().endsWith(".java"))::iterator) {
                String rel = raiz.relativize(f).toString().replace('\\', '/');
                if (rel.startsWith("com/junitgen/seguridad/")) {
                    continue;
                }
                revisados++;
                String[] lineas = Files.readString(f, StandardCharsets.UTF_8).split("\\R");
                for (int i = 0; i < lineas.length; i++) {
                    for (Pattern p : PROHIBIDOS) {
                        Matcher m = p.matcher(lineas[i]);
                        if (m.find()) {
                            infracciones.add(rel + ":" + (i + 1) + " → " + lineas[i].trim());
                        }
                    }
                }
            }
        }
        assertThat(revisados).isGreaterThan(40);
        assertThat(infracciones).as("Escrituras, borrados o procesos fuera del paquete seguridad").isEmpty();
    }
}
