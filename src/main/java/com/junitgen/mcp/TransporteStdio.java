package com.junitgen.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.junitgen.util.Json;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;

/**
 * Transporte MCP por stdio: un mensaje JSON-RPC por línea, UTF-8, tolera BOM inicial. Usa los
 * descriptores reales de la consola porque System.out se redirige a stderr en modo mcp.
 */
public class TransporteStdio implements Closeable {

    private final BufferedReader entrada;
    private final Writer salida;
    private boolean primera = true;

    public TransporteStdio(InputStream in, OutputStream out) {
        this.entrada = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        this.salida = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));
    }

    public static TransporteStdio sistema() {
        return new TransporteStdio(new FileInputStream(FileDescriptor.in), new FileOutputStream(FileDescriptor.out));
    }

    /** Siguiente línea no vacía, o null en EOF. */
    public String leerLinea() throws IOException {
        while (true) {
            String l = entrada.readLine();
            if (l == null) {
                return null;
            }
            if (primera) {
                primera = false;
                if (l.startsWith("\uFEFF")) {
                    l = l.substring(1);
                }
            }
            if (!l.isBlank()) {
                return l;
            }
        }
    }

    public synchronized void enviar(JsonNode mensaje) throws IOException {
        String linea = Json.compacto(mensaje);
        salida.write(linea.replace("\n", "\\n").replace("\r", "\\r"));
        salida.write("\n");
        salida.flush();
    }

    @Override
    public void close() throws IOException {
        try {
            entrada.close();
        } finally {
            salida.close();
        }
    }
}
