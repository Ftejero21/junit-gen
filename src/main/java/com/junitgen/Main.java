package com.junitgen;

import com.junitgen.cli.CliRunner;
import com.junitgen.mcp.ServidorMcp;
import com.junitgen.mcp.TransporteStdio;

import java.io.IOException;

/** Punto de entrada: "mcp" arranca el servidor MCP por stdio; el resto son comandos de CLI. */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        if (args.length > 0 && args[0].equals("mcp")) {
            // En modo MCP stdout es exclusivo de JSON-RPC: cualquier System.out va a stderr.
            System.setOut(System.err);
            System.err.println("junit-gen mcp: directorio de trabajo (user.dir) = " + System.getProperty("user.dir"));
            try {
                new ServidorMcp(TransporteStdio.sistema(), new Fachada()).ejecutar();
            } catch (IOException e) {
                System.err.println("junit-gen mcp: error de E/S: " + e.getMessage());
                System.exit(1);
            }
            System.exit(0);
        }
        System.exit(new CliRunner().ejecutar(args));
    }
}
