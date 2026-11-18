package com.junitgen.config;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Lee {@code junit-gen.yml} con SnakeYAML seguro (SafeConstructor y límites). */
public final class CargadorConfig {

    public static final String ARCHIVO = "junit-gen.yml";
    private static final long MAX_BYTES = 256 * 1024;

    private CargadorConfig() {
    }

    /** Config de la raíz combinada con la del módulo (el módulo prevalece). */
    public static ConfigProyecto cargar(Path raiz, Path modulo) {
        ConfigProyecto base = leer(raiz.resolve(ARCHIVO));
        if (modulo != null && !modulo.equals(raiz)) {
            ConfigProyecto mod = leer(modulo.resolve(ARCHIVO));
            return base.combinar(mod);
        }
        return base;
    }

    /**
     * Modo de verificación del proyecto: solo se lee del {@code junit-gen.yml} de la raíz (sin caché);
     * {@link ConfigProyecto.ModoVerificacion#SIEMPRE} si {@code raiz} es null o no está configurado.
     */
    public static ConfigProyecto.ModoVerificacion modoVerificacion(Path raiz) {
        if (raiz == null) {
            return ConfigProyecto.ModoVerificacion.SIEMPRE;
        }
        return leer(raiz.resolve(ARCHIVO)).modoVerificacion();
    }

    /**
     * Modo de los fallos de aserción del proyecto: solo se lee del {@code junit-gen.yml} de la raíz (sin
     * caché); {@link ConfigProyecto.ModoFallosAsercion#REVISAR_IA} si {@code raiz} es null o no está configurado.
     */
    public static ConfigProyecto.ModoFallosAsercion modoFallosAsercion(Path raiz) {
        if (raiz == null) {
            return ConfigProyecto.ModoFallosAsercion.REVISAR_IA;
        }
        return leer(raiz.resolve(ARCHIVO)).modoFallosAsercion();
    }

    /**
     * Modo de los arreglos de la tanda: solo se lee del {@code junit-gen.yml} de la raíz (sin caché);
     * {@link ConfigProyecto.ModoArreglos#AL_FINAL} si {@code raiz} es null o no está configurado.
     */
    public static ConfigProyecto.ModoArreglos modoArreglos(Path raiz) {
        if (raiz == null) {
            return ConfigProyecto.ModoArreglos.AL_FINAL;
        }
        return leer(raiz.resolve(ARCHIVO)).modoArreglos();
    }

    /**
     * Verificaciones de servicio simultáneas (1..8): solo se lee del {@code junit-gen.yml} de la raíz (sin caché); null si
     * {@code raiz} es null o no está configurado.
     */
    public static Integer verificadores(Path raiz) {
        if (raiz == null) {
            return null;
        }
        return leer(raiz.resolve(ARCHIVO)).verificadores;
    }

    /**
     * Servicios por oleada del modo «todos» (1..100): solo se lee del {@code junit-gen.yml} de la raíz (sin caché); null si
     * {@code raiz} es null o no está configurado.
     */
    public static Integer oleada(Path raiz) {
        if (raiz == null) {
            return null;
        }
        return leer(raiz.resolve(ARCHIVO)).oleada;
    }

    /**
     * Orden de los servicios del plan: solo se lee del {@code junit-gen.yml} de la raíz (sin caché);
     * {@link ConfigProyecto.OrdenServicios#ALFABETICO} si {@code raiz} es null o no está configurado.
     */
    public static ConfigProyecto.OrdenServicios ordenServicios(Path raiz) {
        if (raiz == null) {
            return ConfigProyecto.OrdenServicios.ALFABETICO;
        }
        return leer(raiz.resolve(ARCHIVO)).ordenServicios();
    }

    public static ConfigProyecto leer(Path archivo) {
        ConfigProyecto c = new ConfigProyecto();
        if (!Files.isRegularFile(archivo)) {
            return c;
        }
        try {
            if (Files.size(archivo) > MAX_BYTES) {
                c.avisos.add(archivo + " supera 256 KB; se ignora.");
                return c;
            }
            String texto = Files.readString(archivo, StandardCharsets.UTF_8);
            LoaderOptions op = new LoaderOptions();
            op.setAllowDuplicateKeys(false);
            op.setMaxAliasesForCollections(20);
            op.setNestingDepthLimit(20);
            op.setCodePointLimit(300_000);
            op.setAllowRecursiveKeys(false);
            Yaml yaml = new Yaml(new SafeConstructor(op));
            Object raiz = yaml.load(texto);
            c.origenes.add(archivo.toString());
            if (raiz == null) {
                return c;
            }
            if (!(raiz instanceof Map<?, ?> m)) {
                c.avisos.add(archivo + ": la raíz debe ser un mapa; se ignora.");
                return c;
            }
            Object inc = m.get("incluir");
            if (inc instanceof Map<?, ?> mi) {
                c.incluirClases = lista(mi.get("clases"));
                c.incluirPaquetes = lista(mi.get("paquetes"));
            }
            Object exc = m.get("excluir");
            if (exc instanceof Map<?, ?> me) {
                c.excluirClases = lista(me.get("clases"));
                c.excluirPaquetes = lista(me.get("paquetes"));
            }
            Object as = m.get("aserciones");
            if (as != null) {
                String v = as.toString().trim().toLowerCase(Locale.ROOT);
                if (v.equals("assertj") || v.equals("junit") || v.equals("auto")) {
                    c.aserciones = v;
                } else {
                    c.avisos.add(archivo + ": 'aserciones' debe ser assertj, junit o auto; se usa auto.");
                }
            }
            c.jdk = cadena(m.get("jdk"));
            Object mv = m.get("maven");
            if (mv instanceof Map<?, ?> mm) {
                c.mavenHome = cadena(mm.get("home"));
                c.mavenSettings = cadena(mm.get("settings"));
            }
            c.classpath = lista(m.get("classpath"));
            Object um = m.get("umbralPuntuacion");
            if (um instanceof Number n) {
                c.umbralPuntuacion = n.intValue();
            } else if (um != null) {
                c.avisos.add(archivo + ": 'umbralPuntuacion' debe ser un número.");
            }
            Object pf = m.get("porcentajeFunciones");
            if (pf != null) {
                if (pf instanceof Number n && n.doubleValue() == Math.floor(n.doubleValue())
                        && n.doubleValue() >= 1 && n.doubleValue() <= 100) {
                    c.porcentajeFunciones = n.intValue();
                } else {
                    c.avisos.add(archivo + ": 'porcentajeFunciones' debe ser un entero entre 1 y 100; se usa 60.");
                }
            }
            Object vf = m.get("verificacion");
            if (vf != null) {
                ConfigProyecto.ModoVerificacion modo = ConfigProyecto.ModoVerificacion.de(vf.toString());
                if (modo != null) {
                    c.verificacion = modo;
                } else {
                    c.avisos.add(archivo + ": 'verificacion' debe ser siempre, preguntar o nunca; se usa siempre.");
                }
            }
            Object ord = m.get("orden");
            if (ord != null) {
                ConfigProyecto.OrdenServicios o = ConfigProyecto.OrdenServicios.de(ord.toString());
                if (o != null) {
                    c.orden = o;
                } else {
                    c.avisos.add(archivo + ": 'orden' debe ser alfabetico o prioridad; se usa alfabetico.");
                }
            }
            Object fa = m.get("fallosAsercion");
            if (fa != null) {
                ConfigProyecto.ModoFallosAsercion modoFa = ConfigProyecto.ModoFallosAsercion.de(fa.toString());
                if (modoFa != null) {
                    c.fallosAsercion = modoFa;
                } else {
                    c.avisos.add(archivo + ": 'fallosAsercion' debe ser revisarIa o preguntar; se usa revisarIa.");
                }
            }
            Object ar = m.get("arreglos");
            if (ar != null) {
                ConfigProyecto.ModoArreglos modoAr = ConfigProyecto.ModoArreglos.de(ar.toString());
                if (modoAr != null) {
                    c.arreglos = modoAr;
                } else {
                    c.avisos.add(archivo + ": 'arreglos' debe ser alFinal o inmediato; se usa alFinal.");
                }
            }
            Object vs = m.get("verificadores");
            if (vs != null) {
                if (vs instanceof Number n && n.doubleValue() == Math.floor(n.doubleValue()) && n.doubleValue() >= 1
                        && n.doubleValue() <= 8) {
                    c.verificadores = n.intValue();
                } else {
                    c.avisos.add(archivo + ": 'verificadores' debe ser un entero entre 1 y 8; se usa el valor automático.");
                }
            }
            Object ol = m.get("oleada");
            if (ol != null) {
                if (ol instanceof Number n && n.doubleValue() == Math.floor(n.doubleValue())
                        && n.doubleValue() >= ConfigProyecto.MIN_OLEADA && n.doubleValue() <= ConfigProyecto.MAX_OLEADA) {
                    c.oleada = n.intValue();
                } else {
                    c.avisos.add(archivo + ": 'oleada' debe ser un entero entre " + ConfigProyecto.MIN_OLEADA + " y "
                            + ConfigProyecto.MAX_OLEADA + "; se usa el valor automático.");
                }
            }
            for (Object k : m.keySet()) {
                String clave = String.valueOf(k);
                if (!List.of("incluir", "excluir", "aserciones", "jdk", "maven", "classpath", "umbralPuntuacion",
                        "porcentajeFunciones", "verificacion", "orden", "fallosAsercion", "arreglos", "verificadores", "oleada")
                        .contains(clave)) {
                    c.avisos.add(archivo + ": clave desconocida '" + clave + "' (se ignora).");
                }
            }
        } catch (IOException e) {
            c.avisos.add("No se pudo leer " + archivo + ": " + e.getMessage());
        } catch (RuntimeException e) {
            c.avisos.add("YAML no válido en " + archivo + ": " + e.getMessage());
        }
        return c;
    }

    private static String cadena(Object o) {
        return o == null ? null : o.toString().trim();
    }

    private static List<String> lista(Object o) {
        List<String> r = new ArrayList<>();
        if (o instanceof List<?> l) {
            for (Object x : l) {
                if (x != null) {
                    r.add(x.toString().trim());
                }
            }
        } else if (o != null) {
            r.add(o.toString().trim());
        }
        return r;
    }
}
