package com.junitgen.perfil;

import com.junitgen.escaneo.ProyectoEscaneado;
import com.junitgen.generacion.RutasSalida;
import com.junitgen.seguridad.BloqueosSalida;
import com.junitgen.seguridad.EscritorSeguro;
import com.junitgen.seguridad.RutaSegura;
import com.junitgen.util.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * perfil-proyecto.json de la carpeta de salida: lectura (con límite de tamaño), caché de proceso por salida y
 * sincronización incremental. La escritura va siempre por {@link EscritorSeguro} del llamador; todo acceso se hace
 * con el bloqueo de escritura de la salida tomado. Nunca lanza desde la lectura ni la sincronización.
 */
public final class AlmacenPerfil {

    /** Tamaño máximo del archivo que se acepta leer. */
    static final long MAX_BYTES = 20L * 1024 * 1024;

    private static final class Entrada {
        PerfilProyecto perfil;
        ProyectoEscaneado sincronizadoCon;

        Entrada(PerfilProyecto perfil, ProyectoEscaneado sincronizadoCon) {
            this.perfil = perfil;
            this.sincronizadoCon = sincronizadoCon;
        }
    }

    private static final Map<String, Entrada> CACHE = new ConcurrentHashMap<>();

    public record Lectura(PerfilProyecto perfil, String motivoInvalido) {
    }

    private AlmacenPerfil() {
    }

    public static Path ruta(Path salida) {
        return salida.resolve(RutasSalida.PERFIL);
    }

    public static String serializar(PerfilProyecto p) {
        return Json.bonito(p) + "\n";
    }

    private static String clave(Path salida) {
        return salida.toAbsolutePath().normalize().toString().toLowerCase(Locale.ROOT);
    }

    /** Lee el perfil: ausente → (null, null); mayor de 20 MB, ilegible, esquema distinto o sin proyecto.ruta → (null, motivo). */
    public static Lectura leer(Path salida) {
        try {
            Path f = ruta(salida);
            if (!Files.isRegularFile(f)) {
                return new Lectura(null, null);
            }
            if (Files.size(f) > MAX_BYTES) {
                return new Lectura(null, "estaba corrupto (mayor de 20 MB)");
            }
            PerfilProyecto p;
            try {
                p = Json.MAPPER.readValue(Files.readString(f, StandardCharsets.UTF_8), PerfilProyecto.class);
            } catch (IOException | RuntimeException e) {
                return new Lectura(null, "estaba corrupto (JSON no válido)");
            }
            if (p == null) {
                return new Lectura(null, "estaba corrupto (vacío)");
            }
            if (p.esquema != PerfilProyecto.ESQUEMA) {
                return new Lectura(null, "estaba corrupto (esquema " + p.esquema + " distinto de " + PerfilProyecto.ESQUEMA + ")");
            }
            if (p.proyecto == null || p.proyecto.ruta == null || p.proyecto.ruta.isBlank()) {
                return new Lectura(null, "estaba corrupto (sin proyecto.ruta)");
            }
            normalizar(p);
            return new Lectura(p, null);
        } catch (IOException | RuntimeException e) {
            return new Lectura(null, "estaba corrupto (no se pudo leer: " + e.getClass().getSimpleName() + ")");
        }
    }

    private static void normalizar(PerfilProyecto p) {
        if (p.entorno == null) {
            p.entorno = new LinkedHashMap<>();
        }
        if (p.huellas == null) {
            p.huellas = new LinkedHashMap<>();
        }
        if (p.servicios == null) {
            p.servicios = new LinkedHashMap<>();
        }
        if (p.tipos == null) {
            p.tipos = new LinkedHashMap<>();
        }
        if (p.recetas == null) {
            p.recetas = new PerfilProyecto.Recetas();
        }
        if (p.recetas.aprendidas == null) {
            p.recetas.aprendidas = new ArrayList<>();
        }
        if (p.recetas.enPrueba == null) {
            p.recetas.enPrueba = new LinkedHashMap<>();
        }
        if (p.recetas.fallosMontaje == null) {
            p.recetas.fallosMontaje = new LinkedHashMap<>();
        }
        p.recetas.aprendidas.removeIf(r -> r == null || r.id == null || r.forma == null);
        for (PerfilProyecto.Receta r : p.recetas.aprendidas) {
            if (r.excluidaEn == null) {
                r.excluidaEn = new ArrayList<>();
            }
        }
        for (PerfilProyecto.FalloMontaje f : p.recetas.fallosMontaje.values()) {
            if (f != null && f.casos == null) {
                f.casos = new LinkedHashMap<>();
            }
        }
        p.recetas.fallosMontaje.values().removeIf(f -> f == null);
    }

    private static boolean mismoProyecto(PerfilProyecto perfil, ProyectoEscaneado p) {
        if (p == null) {
            return true;
        }
        try {
            return perfil.proyecto != null && perfil.proyecto.ruta != null
                    && RutaSegura.iguales(Path.of(perfil.proyecto.ruta), p.raiz);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Perfil vigente de la salida (caché o archivo), sin sincronizar ni escribir; null si no hay, es inválido o
     * (con {@code p} ≠ null) es de otro proyecto.
     */
    public static PerfilProyecto cargar(Path salidaReal, ProyectoEscaneado p) {
        if (salidaReal == null) {
            return null;
        }
        try {
            return BloqueosSalida.conEscritura(salidaReal, () -> {
                String k = clave(salidaReal);
                if (!Files.isRegularFile(ruta(salidaReal))) {
                    CACHE.remove(k);
                    return null;
                }
                Entrada e = CACHE.get(k);
                if (e == null) {
                    Lectura l = leer(salidaReal);
                    if (l.perfil() == null) {
                        return null;
                    }
                    e = new Entrada(l.perfil(), null);
                    CACHE.put(k, e);
                }
                return mismoProyecto(e.perfil, p) ? e.perfil : null;
            });
        } catch (RuntimeException e) {
            System.err.println("Perfil: no se pudo cargar (" + e.getClass().getSimpleName() + ")");
            return null;
        }
    }

    /**
     * Sincroniza el perfil de la salida de {@code esc} con el escaneo {@code p} y lo escribe si cambió o falta el
     * archivo. Si ya se sincronizó con esta misma instancia de {@code p} y el archivo existe, devuelve
     * {@code SIN_CAMBIOS} sin recalcular. Nunca lanza: ante un error devuelve null y lo registra en stderr.
     */
    public static ConstructorPerfil.Resultado sincronizar(EscritorSeguro esc, ProyectoEscaneado p) {
        if (esc == null || p == null) {
            return null;
        }
        try {
            Path salida = esc.salida();
            return BloqueosSalida.conEscritura(salida, () -> {
                String k = clave(salida);
                Path f = ruta(salida);
                boolean existe = Files.isRegularFile(f);
                Entrada e = CACHE.get(k);
                if (e != null && e.sincronizadoCon == p && existe) {
                    return new ConstructorPerfil.Resultado(e.perfil, ConstructorPerfil.SIN_CAMBIOS, 0, 0, null, false);
                }
                Lectura l = leer(salida);
                ConstructorPerfil.Resultado r = ConstructorPerfil.sincronizar(l.perfil(), l.motivoInvalido(), p);
                if (r.cambiado() || !existe) {
                    esc.escribir(f, serializar(r.perfil()));
                }
                CACHE.put(k, new Entrada(r.perfil(), p));
                return r;
            });
        } catch (RuntimeException e) {
            System.err.println("Perfil: no se pudo sincronizar (" + e.getClass().getSimpleName() + ": "
                    + (e.getMessage() == null ? "" : e.getMessage().lines().findFirst().orElse("")) + ")");
            return null;
        }
    }

    /**
     * Aplica {@code cambio} al perfil vigente (si no hay y {@code p} ≠ null, lo construye) y devuelve
     * {ruta, json} para que el llamador lo escriba con su EscritorSeguro; null si no hay perfil.
     */
    public static Map.Entry<Path, String> modificar(Path salidaReal, ProyectoEscaneado p, Consumer<PerfilProyecto> cambio) {
        if (salidaReal == null || cambio == null) {
            return null;
        }
        return BloqueosSalida.conEscritura(salidaReal, () -> {
            String k = clave(salidaReal);
            PerfilProyecto vigente = cargar(salidaReal, p);
            Entrada e = CACHE.get(k);
            if (vigente == null) {
                if (p == null) {
                    return null;
                }
                Lectura l = leer(salidaReal);
                ConstructorPerfil.Resultado r = ConstructorPerfil.sincronizar(l.perfil(), l.motivoInvalido(), p);
                vigente = r.perfil();
                e = new Entrada(vigente, p);
                CACHE.put(k, e);
            }
            cambio.accept(vigente);
            return new AbstractMap.SimpleImmutableEntry<>(ruta(salidaReal), serializar(vigente));
        });
    }
}
