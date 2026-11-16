package com.junitgen.mcp;

import com.junitgen.Fachada;
import com.junitgen.seguridad.RutaSegura;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;

/**
 * Pre-escaneo opcional (variable de entorno {@value #ENV}=1): al recibir {@code notifications/initialized} escanea en
 * segundo plano el proyecto candidato (el directorio de trabajo si es un proyecto con ruta por defecto guardada o, si
 * ese directorio no sirve, el único proyecto guardado) para que escanear_proyecto lo encuentre ya hecho. Solo lee; el
 * hilo es daemon, de prioridad mínima y de un solo uso, y nunca fija la última ruta de la sesión.
 */
final class PreEscaneo {

    static final String ENV = "JUNITGEN_PREESCANEO";
    private static final String ACTIVO = "1";
    private static final String NOMBRE_HILO = "junit-gen-preescaneo";

    private PreEscaneo() {
    }

    static void lanzar(SesionMcp sesion) {
        try {
            if (!ACTIVO.equals(sesion.entorno().env().apply(ENV)) || !sesion.rutasPorDefecto().disponible()) {
                return;
            }
            Path candidato = candidato(sesion);
            if (candidato == null || !sesion.reclamarPreEscaneo()) {
                return;
            }
            Fachada fachada = sesion.fachada();
            Thread hilo = new Thread(() -> {
                try {
                    fachada.precalentar(candidato);
                } catch (RuntimeException e) {
                    System.err.println("Pre-escaneo: fallo (" + e.getClass().getSimpleName() + ")");
                }
            }, NOMBRE_HILO);
            hilo.setDaemon(true);
            hilo.setPriority(Thread.MIN_PRIORITY);
            hilo.start();
        } catch (RuntimeException e) {
            System.err.println("Pre-escaneo: no se pudo preparar (" + e.getClass().getSimpleName() + ")");
        }
    }

    /** Proyecto a pre-escanear, ya validado, o null. Las rutas de rutas-por-defecto.json son entrada no confiable. */
    private static Path candidato(SesionMcp sesion) {
        List<String> guardados = sesion.rutasPorDefecto().proyectos();
        if (guardados.isEmpty()) {
            return null;
        }
        Path cwd = RutasSesion.cwdValido(sesion.entorno());
        Path elegido;
        if (cwd != null) {
            elegido = guardados.contains(RutasPorDefecto.clave(cwd)) ? cwd : null;
        } else if (guardados.size() == 1) {
            try {
                elegido = Path.of(guardados.get(0));
            } catch (InvalidPathException e) {
                return null;
            }
        } else {
            return null;
        }
        if (elegido == null) {
            return null;
        }
        try {
            Path real = RutaSegura.validarProyecto(elegido);
            return Files.isDirectory(real) ? real : null;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
