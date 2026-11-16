package com.junitgen.verificacion;

import com.junitgen.escaneo.IndiceTipos;
import com.junitgen.generacion.CatalogoClases;
import com.junitgen.escaneo.InfoClase;
import com.junitgen.escaneo.InfoMetodo;
import com.junitgen.generacion.ResolutorImports;
import com.junitgen.generacion.RutasSalida;
import com.junitgen.util.Textos;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pistas para errores de compilación de un caso: firmas reales de constructores y métodos del tipo
 * implicado (por reflexión sobre el classpath de verificación, sin inicializar clases; si falla, del
 * índice de tipos del proyecto) y el detalle de javac. Solo nombres de tipos y firmas: nunca rutas,
 * cuerpos ni valores. Una instancia por verificación; cierra su class loader en {@link #close()}.
 */
final class PistasCompilacion implements AutoCloseable {

    static final int MAX_PISTA = 500;
    static final int MAX_FIRMAS = 6;
    private static final int MAX_NOMBRES = 15;
    private static final int MAX_NIVELES = 3;

    private static final Pattern CONSTRUCTOR_APLICADO = Pattern.compile(
            "constructor (\\w+) in (class|record|enum) (\\S+) cannot be applied to given types");
    private static final Pattern CONSTRUCTOR_NINGUNO = Pattern.compile("no suitable constructor found for (\\w+)\\(");
    private static final Pattern SIMBOLO_CONSTRUCTOR = Pattern.compile("symbol:\\s+constructor (\\w+)\\(");
    private static final Pattern METODO_APLICADO = Pattern.compile(
            "method (\\w+) in (class|interface|record|enum) (\\S+) cannot be applied to given types");
    private static final Pattern METODO_NINGUNO = Pattern.compile("no suitable method found for (\\w+)\\(");
    private static final Pattern CANDIDATO_METODO = Pattern.compile("method ([\\w.$]+)\\.(\\w+)\\(");
    private static final Pattern SIMBOLO_METODO = Pattern.compile("symbol:\\s+method (\\w+)");
    private static final Pattern UBICACION = Pattern.compile("location:\\s+(variable \\w+ of type |class |interface )(.+)");
    private static final Pattern SIMBOLO_CLASE = Pattern.compile("symbol:\\s+class (\\w+)");
    private static final Pattern SIMBOLO_VARIABLE = Pattern.compile("symbol:\\s+variable (\\w+)");
    private static final Pattern PAQUETE = Pattern.compile("\\b(?:[a-z_][\\w]*\\.)+(?=[A-Za-z_$])");
    private static final Pattern DETALLE_JAVAC = Pattern.compile("^(required|found|reason)\\s*:");

    private static final Set<String> METODOS_OBJECT = new LinkedHashSet<>();

    static {
        for (Method m : Object.class.getMethods()) {
            METODOS_OBJECT.add(m.getName());
        }
    }

    private final IndiceTipos indice;
    private final List<Path> classpath;
    private final CatalogoClases catalogo;
    private URLClassLoader loader;
    private boolean loaderFallido;

    PistasCompilacion(IndiceTipos indice, List<Path> classpath) {
        this(indice, classpath, null);
    }

    /** Con {@code catalogo} (puede ser null) las clases no encontradas listan sus candidatos por nombre simple. */
    PistasCompilacion(IndiceTipos indice, List<Path> classpath, CatalogoClases catalogo) {
        this.indice = indice;
        this.classpath = classpath == null ? List.of() : List.copyOf(classpath);
        this.catalogo = catalogo;
    }

    @Override
    public void close() {
        if (loader != null) {
            try {
                loader.close();
            } catch (IOException e) {
                System.err.println("junit-gen: no se pudo cerrar el cargador de clases de las pistas (" + e.getClass().getSimpleName() + ").");
            }
            loader = null;
        }
    }

    /** Pista de una línea (≤ {@link #MAX_PISTA} caracteres) para el diagnóstico, o null si no hay. */
    String pista(CompiladorTemporal.Diagnostico d, String textoTest) {
        if (d == null || d.mensaje() == null) {
            return null;
        }
        String r;
        try {
            r = calcular(d, textoTest == null ? "" : textoTest);
        } catch (RuntimeException | LinkageError e) {
            System.err.println("junit-gen: no se pudo calcular la pista de compilación (" + e.getClass().getSimpleName() + ").");
            r = javacSolo(d.detalle());
        }
        if (r == null || r.isBlank()) {
            return null;
        }
        return Textos.recortar(r.replaceAll("\\s*\\R\\s*", " ").trim(), MAX_PISTA);
    }

    private String calcular(CompiladorTemporal.Diagnostico d, String textoTest) {
        String msg = d.mensaje();
        int k = msg.indexOf(" → ");
        String cab = k < 0 ? msg : msg.substring(0, k);
        String det = String.join("\n", d.detalle());
        boolean noEncontrado = cab.contains("cannot find symbol");
        Matcher m;
        String tipo = null;
        String metodo = null;
        boolean constructor = false;
        if ((m = CONSTRUCTOR_APLICADO.matcher(cab)).find()) {
            constructor = true;
            tipo = m.group(3);
        } else if ((m = CONSTRUCTOR_NINGUNO.matcher(cab)).find()) {
            constructor = true;
            tipo = m.group(1);
        } else if (noEncontrado && (m = SIMBOLO_CONSTRUCTOR.matcher(det)).find()) {
            constructor = true;
            tipo = m.group(1);
        } else if ((m = METODO_APLICADO.matcher(cab)).find()) {
            metodo = m.group(1);
            tipo = m.group(3);
        } else if ((m = METODO_NINGUNO.matcher(cab)).find()) {
            metodo = m.group(1);
            Matcher mc = CANDIDATO_METODO.matcher(det);
            tipo = mc.find() ? mc.group(1) : null;
        } else if (noEncontrado) {
            if ((m = SIMBOLO_METODO.matcher(det)).find()) {
                metodo = m.group(1);
                Matcher ml = UBICACION.matcher(det);
                tipo = ml.find() ? ml.group(2).trim() : null;
            } else if ((m = SIMBOLO_CLASE.matcher(det)).find()) {
                return textoClase(m.group(1));
            } else if ((m = SIMBOLO_VARIABLE.matcher(det)).find()) {
                return "`" + m.group(1) + "` no está declarado en el test: decláralo en `preparacion` del caso o usa un campo/mock existente.";
            }
        }
        if (tipo == null || (!constructor && metodo == null)) {
            return javacSolo(d.detalle());
        }
        String fqn = fqn(tipo, textoTest);
        String simple = simple(tipo);
        String sufijo = sufijoJavac(d.detalle());
        String texto = constructor ? textoConstructores(fqn, simple) : textoMetodos(fqn, simple, metodo);
        if (texto == null) {
            return javacSolo(d.detalle());
        }
        return texto + sufijo;
    }

    private String textoClase(String x) {
        String r = "No se encuentra la clase `" + x + "`: usa el nombre exacto de un tipo existente o añade su nombre completo en `imports` del servicio.";
        if (catalogo != null) {
            List<String> cands = catalogo.candidatos(x);
            if (!cands.isEmpty() && cands.size() <= CatalogoClases.MAX_CANDIDATOS) {
                List<String> fqns = new ArrayList<>();
                cands.forEach(c -> fqns.add("`" + c + "`"));
                r += " Candidatos: " + String.join(" | ", fqns);
            }
        } else if (indice != null) {
            List<InfoClase> cands = indice.porNombreSimple(x);
            if (cands.size() > 1) {
                List<String> fqns = new ArrayList<>();
                cands.forEach(c -> fqns.add(c.fqn));
                r += " Ambiguo: " + String.join(" | ", fqns);
            }
        }
        return r;
    }

    private static String javacSolo(List<String> detalle) {
        return detalle == null || detalle.isEmpty() ? null : "javac: " + String.join("; ", detalle);
    }

    /** « javac: required: …; found: …; reason: …» (o el resto del detalle si no trae esas líneas); "" sin detalle. */
    private static String sufijoJavac(List<String> detalle) {
        if (detalle == null || detalle.isEmpty()) {
            return "";
        }
        List<String> sel = new ArrayList<>();
        for (String l : detalle) {
            if (DETALLE_JAVAC.matcher(l).find()) {
                sel.add(l);
            }
        }
        if (sel.isEmpty()) {
            for (String l : detalle) {
                if (!l.startsWith("symbol") && !l.startsWith("location")) {
                    sel.add(l);
                }
            }
        }
        return sel.isEmpty() ? "" : " javac: " + String.join("; ", sel);
    }

    // ---- Firmas ----

    private String textoConstructores(String fqn, String simple) {
        List<String> firmas = constructoresReflexion(fqn);
        if (firmas == null) {
            firmas = constructoresIndice(fqn, simple);
        }
        if (firmas == null) {
            return null;
        }
        return "Constructores reales de `" + simple + "`: " + lista(firmas, MAX_FIRMAS, " | ")
                + ". Usa exactamente una de esas firmas (mismo número y tipos de argumentos).";
    }

    private String textoMetodos(String fqn, String simple, String metodo) {
        Firmas f = metodosReflexion(fqn, metodo);
        if (f == null) {
            f = metodosIndice(fqn, simple, metodo);
        }
        if (f == null) {
            return null;
        }
        if (!f.firmas.isEmpty()) {
            return "Métodos `" + metodo + "` reales de `" + simple + "`: " + lista(f.firmas, MAX_FIRMAS, " | ") + ".";
        }
        return "`" + simple + "` no tiene ningún método `" + metodo + "`. Métodos públicos: "
                + (f.nombres.isEmpty() ? "ninguno" : lista(new ArrayList<>(f.nombres), MAX_NOMBRES, ", ")) + ".";
    }

    private record Firmas(List<String> firmas, Set<String> nombres) {
    }

    private static String lista(List<String> l, int max, String sep) {
        if (l.isEmpty()) {
            return "ninguno accesible";
        }
        List<String> r = l.size() <= max ? l : l.subList(0, max);
        return String.join(sep, r) + (l.size() > max ? " … (+" + (l.size() - max) + ")" : "");
    }

    private List<String> constructoresReflexion(String fqn) {
        Class<?> c = cargar(fqn);
        if (c == null) {
            return null;
        }
        try {
            List<String> r = new ArrayList<>();
            for (Constructor<?> k : c.getDeclaredConstructors()) {
                if (Modifier.isPrivate(k.getModifiers())) {
                    continue;
                }
                r.add(c.getSimpleName() + "(" + tipos(k.getGenericParameterTypes()) + ")");
            }
            return r;
        } catch (RuntimeException | LinkageError e) {
            System.err.println("junit-gen: pista por reflexión no disponible (" + e.getClass().getSimpleName() + ").");
            return null;
        }
    }

    private Firmas metodosReflexion(String fqn, String metodo) {
        Class<?> c = cargar(fqn);
        if (c == null) {
            return null;
        }
        try {
            List<String> firmas = new ArrayList<>();
            Set<String> nombres = new LinkedHashSet<>();
            for (Method m : c.getMethods()) {
                if (m.getName().equals(metodo)) {
                    String f = nombreTipo(m.getGenericReturnType()) + " " + m.getName() + "(" + tipos(m.getGenericParameterTypes()) + ")";
                    if (!firmas.contains(f)) {
                        firmas.add(f);
                    }
                } else if (!METODOS_OBJECT.contains(m.getName())) {
                    nombres.add(m.getName());
                }
            }
            return new Firmas(firmas, nombres);
        } catch (RuntimeException | LinkageError e) {
            System.err.println("junit-gen: pista por reflexión no disponible (" + e.getClass().getSimpleName() + ").");
            return null;
        }
    }

    private List<String> constructoresIndice(String fqn, String simple) {
        InfoClase ic = claseIndice(fqn, simple);
        if (ic == null) {
            return null;
        }
        List<String> r = new ArrayList<>();
        for (InfoClase.InfoConstructor k : ic.constructores) {
            if (!k.privado) {
                r.add(ic.simple + k.firma());
            }
        }
        if (ic.constructores.isEmpty() && ic.tipo == InfoClase.Tipo.CLASE) {
            r.add(ic.simple + "()");
        }
        return r;
    }

    private Firmas metodosIndice(String fqn, String simple, String metodo) {
        InfoClase ic = claseIndice(fqn, simple);
        if (ic == null) {
            return null;
        }
        List<String> firmas = new ArrayList<>();
        Set<String> nombres = new LinkedHashSet<>();
        InfoClase actual = ic;
        for (int nivel = 0; actual != null && nivel <= MAX_NIVELES; nivel++) {
            for (InfoMetodo m : actual.metodos) {
                if (m.privado) {
                    continue;
                }
                if (m.nombre.equals(metodo)) {
                    String f = m.firma();
                    if (!firmas.contains(f)) {
                        firmas.add(f);
                    }
                } else if (!METODOS_OBJECT.contains(m.nombre)) {
                    nombres.add(m.nombre);
                }
            }
            InfoClase padre = null;
            for (String ext : actual.extiende) {
                padre = indice.resolverClase(ext, actual);
                if (padre != null) {
                    break;
                }
            }
            actual = padre;
        }
        return new Firmas(firmas, nombres);
    }

    private InfoClase claseIndice(String fqn, String simple) {
        if (indice == null) {
            return null;
        }
        InfoClase ic = fqn == null ? null : indice.get(fqn);
        if (ic == null) {
            ic = indice.resolverClase(simple, null);
        }
        return ic;
    }

    // ---- Reflexión (sin inicializar clases) ----

    private ClassLoader loader() {
        if (loader == null && !loaderFallido) {
            try {
                URL[] urls = new URL[classpath.size()];
                for (int i = 0; i < classpath.size(); i++) {
                    urls[i] = classpath.get(i).toUri().toURL();
                }
                loader = new URLClassLoader(urls, ClassLoader.getPlatformClassLoader());
            } catch (MalformedURLException | RuntimeException e) {
                loaderFallido = true;
                System.err.println("junit-gen: no se pudo preparar el cargador de clases de las pistas (" + e.getClass().getSimpleName() + ").");
            }
        }
        return loader;
    }

    /** Carga el tipo sin inicializarlo; prueba {@code $} en lugar de los últimos puntos (tipos anidados). */
    private Class<?> cargar(String fqn) {
        if (fqn == null || fqn.isEmpty()) {
            return null;
        }
        ClassLoader l = loader();
        if (l == null) {
            return null;
        }
        String n = fqn;
        try {
            for (int i = 0; i <= MAX_NIVELES; i++) {
                try {
                    return Class.forName(n, false, l);
                } catch (ClassNotFoundException e) {
                    int p = n.lastIndexOf('.');
                    if (p < 0) {
                        return null;
                    }
                    n = n.substring(0, p) + "$" + n.substring(p + 1);
                }
            }
        } catch (LinkageError | RuntimeException e) {
            System.err.println("junit-gen: no se pudo cargar un tipo para la pista (" + e.getClass().getSimpleName() + ").");
        }
        return null;
    }

    private static String tipos(Type[] ts) {
        List<String> r = new ArrayList<>();
        for (Type t : ts) {
            r.add(nombreTipo(t));
        }
        return String.join(", ", r);
    }

    private static String nombreTipo(Type t) {
        return PAQUETE.matcher(t.getTypeName()).replaceAll("").replace('$', '.');
    }

    // ---- Tipo → FQN ----

    private static String limpiar(String tipo) {
        return Textos.quitarGenericos(tipo).replace("[]", "").replace("...", "").replaceAll("\\s+", "");
    }

    private static String simple(String tipo) {
        String t = limpiar(tipo);
        if (t.contains(".") && Character.isLowerCase(t.charAt(0))) {
            return Textos.nombreSimple(t);
        }
        return t;
    }

    private String fqn(String tipo, String textoTest) {
        String t = limpiar(tipo);
        if (t.isEmpty()) {
            return null;
        }
        if (t.contains(".") && Character.isLowerCase(t.charAt(0))) {
            return t;
        }
        int punto = t.indexOf('.');
        String primero = punto < 0 ? t : t.substring(0, punto);
        String resto = punto < 0 ? "" : t.substring(punto);
        Matcher mi = Pattern.compile("(?m)^\\s*import\\s+([\\w.]+\\." + Pattern.quote(primero) + ")\\s*;").matcher(textoTest);
        if (mi.find()) {
            return mi.group(1) + resto;
        }
        String paquete = RutasSalida.paqueteDeclarado(textoTest);
        if (indice != null) {
            if (paquete != null && !paquete.isEmpty() && indice.get(paquete + "." + primero) != null) {
                return paquete + "." + t;
            }
            String r = indice.resolverFqn(t, null);
            if (r != null) {
                return punto < 0 || r.endsWith(resto) ? r : r + resto;
            }
        }
        String jdk = ResolutorImports.JDK.get(primero);
        if (jdk != null) {
            return jdk + resto;
        }
        return "java.lang." + t;
    }
}
