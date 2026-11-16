package com.junitgen.generacion;

import com.junitgen.spec.CasoSpec;
import com.junitgen.spec.LectorSpec;
import com.junitgen.spec.SeleccionSpec;
import com.junitgen.spec.ServicioSpec;
import com.junitgen.spec.Spec;
import com.junitgen.util.Json;
import com.junitgen.util.Textos;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** spec.json acumulado en la carpeta de salida (para revisar, retocar y regenerar sin IA). */
public final class AlmacenSpec {

    public record Ubicacion(ServicioSpec servicio, CasoSpec caso) {
    }

    private AlmacenSpec() {
    }

    public static Path ruta(Path salida) {
        return salida.resolve(RutasSalida.SPEC);
    }

    /** Lee el spec acumulado; null si no existe. Si está corrupto lanza un error y no se sobrescribe. */
    public static Spec leer(Path salida) {
        Path p = ruta(salida);
        if (!Files.isRegularFile(p)) {
            return null;
        }
        LectorSpec.Lectura l = LectorSpec.leerArchivo(p);
        if (!l.ok()) {
            throw new IllegalStateException("El spec.json previo (" + p + ") está corrupto y no se sobrescribe: "
                    + l.errores() + ". Revísalo o muévelo antes de volver a generar.");
        }
        return l.spec();
    }

    /**
     * FQN → selección de origen ia (con funciones) guardada en el spec.json de la salida. {@code salida} null,
     * spec ausente o corrupto → vacío (corrupto: línea en stderr).
     */
    public static Map<String, SeleccionSpec> selecciones(Path salida) {
        Map<String, SeleccionSpec> r = new LinkedHashMap<>();
        if (salida == null) {
            return r;
        }
        Spec spec;
        try {
            spec = leer(salida);
        } catch (RuntimeException e) {
            System.err.println("Se ignoran las selecciones del spec.json de la salida: " + e.getMessage());
            return r;
        }
        return selecciones(spec);
    }

    /** Como {@link #selecciones(Path)} sobre un spec ya leído (null → vacío). */
    public static Map<String, SeleccionSpec> selecciones(Spec spec) {
        Map<String, SeleccionSpec> r = new LinkedHashMap<>();
        if (spec == null || spec.servicios == null) {
            return r;
        }
        for (ServicioSpec sv : spec.servicios) {
            if (sv != null && sv.clase != null && sv.seleccion != null && "ia".equals(sv.seleccion.origen)
                    && sv.seleccion.funciones != null && !sv.seleccion.funciones.isEmpty()) {
                r.put(sv.clase, sv.seleccion);
            }
        }
        return r;
    }

    public static Ubicacion buscar(Spec s, String id) {
        if (s == null || s.servicios == null) {
            return null;
        }
        for (ServicioSpec sv : s.servicios) {
            if (sv.casos == null) {
                continue;
            }
            for (CasoSpec c : sv.casos) {
                if (c != null && id.equals(c.id)) {
                    return new Ubicacion(sv, c);
                }
            }
        }
        return null;
    }

    private static ServicioSpec servicio(Spec s, String clase) {
        for (ServicioSpec sv : s.servicios) {
            if (sv != null && clase.equals(sv.clase)) {
                return sv;
            }
        }
        return null;
    }

    /**
     * Servicio de {@code clase} en {@code modulo}: 1) misma clase y mismo módulo (null con null); 2) misma clase y
     * alguno de los dos módulos null; 3) null.
     */
    private static ServicioSpec servicio(Spec s, String clase, String modulo) {
        for (ServicioSpec sv : s.servicios) {
            if (sv != null && clase.equals(sv.clase)
                    && (modulo == null ? sv.modulo == null : modulo.equalsIgnoreCase(sv.modulo))) {
                return sv;
            }
        }
        for (ServicioSpec sv : s.servicios) {
            if (sv != null && clase.equals(sv.clase) && (modulo == null || sv.modulo == null)) {
                return sv;
            }
        }
        return null;
    }

    /**
     * Huella (16 hex) del contenido normalizado de una selección: origen, funciones y omitidas con nombre y
     * motivo, sin depender del orden ni de espacios sobrantes. null si no hay selección.
     */
    public static String huellaSeleccion(SeleccionSpec s) {
        if (s == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("origen=").append(norm(s.origen).toLowerCase(Locale.ROOT)).append('\n');
        lineasHuella(sb, "F\t", s.funciones);
        lineasHuella(sb, "O\t", s.omitidas);
        return Textos.sha256Hex(sb.toString(), 16);
    }

    /**
     * Huella de la última selección escrita en informe.md para {@code clase} según el spec.json previo; si no
     * se guardó, la de su selección guardada. null si no hay servicio o selección.
     */
    public static String huellaSeleccionInformada(Spec previo, String clase) {
        if (previo == null || previo.servicios == null || clase == null) {
            return null;
        }
        ServicioSpec sv = servicio(previo, clase);
        if (sv == null) {
            return null;
        }
        if (sv.seleccionInformada != null && !sv.seleccionInformada.isEmpty()) {
            return sv.seleccionInformada;
        }
        return huellaSeleccion(sv.seleccion);
    }

    private static void lineasHuella(StringBuilder sb, String prefijo, List<SeleccionSpec.Entrada> entradas) {
        if (entradas == null) {
            return;
        }
        List<String[]> filas = new ArrayList<>();
        for (SeleccionSpec.Entrada e : entradas) {
            if (e != null) {
                filas.add(new String[] {norm(e.nombre), norm(e.motivo)});
            }
        }
        filas.sort(Comparator.<String[], String>comparing(f -> f[0]).thenComparing(f -> f[1]));
        for (String[] f : filas) {
            sb.append(prefijo).append(f[0]).append('\t').append(f[1]).append('\n');
        }
    }

    private static String norm(String x) {
        return x == null ? "" : x.strip().replaceAll("\\s+", " ");
    }

    /**
     * Acumula: proyecto/estilo del nuevo; por servicio, los casos nuevos se añaden y los actualizados
     * (generados o reemplazados) sustituyen al previo. Los eliminados quedan con estado=eliminado.
     */
    public static Spec fusionar(Spec previo, Spec nuevo, Map<String, CasoSpec> actualizados, Set<String> eliminados) {
        return fusionar(previo, nuevo, actualizados, eliminados, false);
    }

    /**
     * Como {@link #fusionar(Spec, Spec, Map, Set)}; con {@code reemplazarFixtures} una fixture con el mismo
     * {@code nombre} sustituye a la guardada (arreglar_caso con {@code servicio}).
     */
    public static Spec fusionar(Spec previo, Spec nuevo, Map<String, CasoSpec> actualizados, Set<String> eliminados,
                                boolean reemplazarFixtures) {
        Spec r = previo != null ? previo : new Spec();
        r.version = 1;
        if (nuevo.proyecto != null) {
            r.proyecto = nuevo.proyecto;
        }
        if (nuevo.estilo != null) {
            r.estilo = nuevo.estilo;
        }
        if (r.servicios == null) {
            r.servicios = new ArrayList<>();
        }
        for (ServicioSpec sn : nuevo.servicios) {
            ServicioSpec destino = servicio(r, sn.clase, sn.modulo);
            if (destino == null) {
                destino = new ServicioSpec();
                destino.clase = sn.clase;
                destino.modulo = sn.modulo;
                r.servicios.add(destino);
            } else if (sn.modulo != null) {
                destino.modulo = sn.modulo;
            }
            destino.claseTest = sn.claseTest;
            destino.imports = sn.imports;
            destino.construccion = sn.construccion;
            if (sn.seleccion != null && ("ia".equals(sn.seleccion.origen) || destino.seleccion == null
                    || !"ia".equals(destino.seleccion.origen))) {
                destino.seleccion = sn.seleccion;
            }
            if (sn.seleccionInformada != null) {
                destino.seleccionInformada = sn.seleccionInformada;
            }
            if (sn.fixtures != null) {
                for (var f : sn.fixtures) {
                    int pos = -1;
                    for (int k = 0; k < destino.fixtures.size(); k++) {
                        var x = destino.fixtures.get(k);
                        if (x != null && x.nombre != null && x.nombre.equals(f.nombre)) {
                            pos = k;
                            break;
                        }
                    }
                    if (pos < 0) {
                        destino.fixtures.add(f);
                    } else if (reemplazarFixtures) {
                        destino.fixtures.set(pos, f);
                    }
                }
            }
            if (sn.casos == null) {
                continue;
            }
            for (CasoSpec c : sn.casos) {
                CasoSpec act = actualizados.get(c.id);
                if (act == null) {
                    continue;
                }
                boolean sustituido = false;
                for (int i = 0; i < destino.casos.size(); i++) {
                    if (c.id.equals(destino.casos.get(i).id)) {
                        destino.casos.set(i, act);
                        sustituido = true;
                    }
                }
                if (!sustituido) {
                    destino.casos.add(act);
                }
            }
        }
        for (String id : eliminados) {
            Ubicacion u = buscar(r, id);
            if (u != null) {
                u.caso().estado = "eliminado";
            }
        }
        return r;
    }

    public static String serializar(Spec s) {
        return Json.bonito(s) + "\n";
    }
}
