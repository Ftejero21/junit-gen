package com.junitgen.escaneo;

import com.github.javaparser.ast.expr.BooleanLiteralExpr;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.ClassExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.junitgen.generacion.ResolutorImports;
import com.junitgen.util.Textos;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Firma real (tipos de los parámetros) del método de un mock al que apunta un stub o una verificación: del índice de
 * tipos del proyecto y su jerarquía, o por reflexión sobre el classpath del módulo (sin inicializar clases ni ejecutar
 * código del proyecto), afinada con los tipos que la pista del propio stub y los sitios de llamada del servicio aportan.
 * Una instancia por validación; cierra su class loader en {@link #close()}. Con classpath vacío no hay reflexión sobre
 * el tipo del mock. Nunca lanza: ante cualquier fallo devuelve null.
 */
public final class FirmasReales implements AutoCloseable {

    /**
     * Firma de una llamada: por posición, el nombre simple del tipo del parámetro (null = no se sabe, varargs o variable
     * de tipo), su FQN si se conoce y si es primitivo. {@code sobrecargado}: puede resolverse a otra sobrecarga;
     * {@code exacta}: una única candidata.
     */
    public record Firma(List<String> tipos, List<String> fqns, boolean[] primitivo, boolean sobrecargado, boolean exacta) {
    }

    private static final int MAX_NIVELES = 5;
    private static final Map<String, String> ENVOLTORIOS = Map.of("int", "Integer", "long", "Long", "boolean", "Boolean",
            "double", "Double", "float", "Float", "short", "Short", "byte", "Byte", "char", "Character");
    private static final Map<String, String> MATCHERS_PRIMITIVOS = Map.of("anyInt", "int", "anyLong", "long", "anyBoolean",
            "boolean", "anyDouble", "double", "anyFloat", "float", "anyShort", "short", "anyByte", "byte", "anyChar", "char");
    private static final Map<String, String> MATCHERS_REFERENCIA = Map.of("anyString", "String", "anyList", "List", "anyMap",
            "Map", "anySet", "Set", "anyCollection", "Collection", "anyIterable", "Iterable");
    private static final Set<String> MATCHERS_DE_TIPO = Set.of("any", "isA", "nullable");
    /** Supertipos sin métodos de interés: no vuelven incompleta una jerarquía. */
    private static final Set<String> SUPERTIPOS_TRIVIALES = Set.of("Object", "Serializable", "Cloneable", "AutoCloseable",
            "Closeable");

    private final IndiceTipos idx;
    private final List<Path> classpath;
    private URLClassLoader loader;
    private boolean loaderFallido;

    /** Candidata: nombres simples, FQN, primitivos y clases (si vienen de reflexión) de los parámetros. */
    private record Cand(List<String> simples, List<String> fqns, boolean[] prim, boolean varargs) {
        String simple(int i) {
            int fijos = simples.size() - (varargs ? 1 : 0);
            return i < fijos ? simples.get(i) : null;
        }

        String fqn(int i) {
            int fijos = simples.size() - (varargs ? 1 : 0);
            return i < fijos ? fqns.get(i) : null;
        }

        boolean primitivo(int i) {
            int fijos = simples.size() - (varargs ? 1 : 0);
            return i < fijos && prim[i];
        }

        String clave() {
            return String.join(",", simples.stream().map(s -> s == null ? "?" : s).toList()) + (varargs ? "..." : "");
        }
    }

    public FirmasReales(IndiceTipos idx, List<Path> classpath) {
        this.idx = idx;
        this.classpath = classpath == null ? List.of() : List.copyOf(classpath);
    }

    @Override
    public void close() {
        if (loader != null) {
            try {
                loader.close();
            } catch (IOException e) {
                System.err.println("junit-gen: no se pudo cerrar el cargador de clases de las firmas (" + e.getClass().getSimpleName() + ").");
            }
            loader = null;
        }
    }

    /** Nombres con los que el servicio se refiere a la dependencia (parámetro o variable y campo). */
    public static Set<String> receptoresDe(Dependencia d) {
        Set<String> r = new LinkedHashSet<>();
        if (d != null && d.nombre != null) {
            r.add(d.nombre);
        }
        if (d != null && d.campo != null) {
            r.add(d.campo);
        }
        return r;
    }

    /**
     * Receptor de un mock declarado como dato de un caso: el único campo del servicio con el mismo tipo simple; si hay
     * varios o ninguno, vacío.
     */
    public static Set<String> receptoresDeDato(InfoClase servicio, String tipoDato) {
        if (servicio == null || tipoDato == null) {
            return Set.of();
        }
        String simple = Textos.nombreSimple(tipoDato);
        String unico = null;
        for (InfoClase.InfoCampo c : servicio.campos) {
            if (!c.estatico() && simple.equals(Textos.nombreSimple(c.tipo()))) {
                if (unico != null) {
                    return Set.of();
                }
                unico = c.nombre();
            }
        }
        return unico == null ? Set.of() : Set.of(unico);
    }

    /**
     * Firma de {@code receptor.metodo(argsStub)} sobre un mock de tipo {@code tipoMock} (FQN o nombre simple). {@code
     * servicio} y {@code metodoProbado} sitúan los sitios de llamada (el segundo puede ser null = todo el servicio).
     * null si no hay nada que saber.
     */
    public Firma de(InfoClase servicio, InfoMetodo metodoProbado, String tipoMock, Set<String> receptores, String metodo,
                    List<Expression> argsStub) {
        if (metodo == null || argsStub == null) {
            return null;
        }
        try {
            return calcular(servicio, metodoProbado, tipoMock, receptores, metodo, argsStub);
        } catch (RuntimeException | LinkageError e) {
            System.err.println("junit-gen: no se pudo calcular la firma de " + metodo + " (" + e.getClass().getSimpleName() + ").");
            return null;
        }
    }

    private Firma calcular(InfoClase servicio, InfoMetodo metodoProbado, String tipoMock, Set<String> receptores, String metodo,
                           List<Expression> argsStub) {
        int n = argsStub.size();
        String[] pistas = new String[n];
        for (int i = 0; i < n; i++) {
            pistas[i] = pistaDeArgumento(argsStub.get(i));
        }
        List<LlamadasDependencias.Sitio> sitios = servicio == null || receptores == null || receptores.isEmpty() ? List.of()
                : LlamadasDependencias.sitios(servicio, metodoProbado, receptores, metodo, n);
        for (int i = 0; i < n && !sitios.isEmpty(); i++) {
            if (pistas[i] != null) {
                continue;
            }
            String comun = null;
            boolean ok = true;
            for (LlamadasDependencias.Sitio s : sitios) {
                String t = LlamadasDependencias.tipoEstatico(s.llamada().getArgument(i), s.en(), servicio, idx);
                if (t == null || (comun != null && !comun.equals(t))) {
                    ok = false;
                    break;
                }
                comun = t;
            }
            if (ok) {
                pistas[i] = comun;
            }
        }
        InfoClase t = tipoMock == null ? null : (idx.get(tipoMock) != null ? idx.get(tipoMock) : idx.resolverClase(tipoMock, servicio));
        List<Cand> cands = new ArrayList<>();
        boolean incompleta = false;
        if (t != null) {
            List<InfoClase> jer = LlamadasDependencias.jerarquia(t, idx);
            incompleta = jerarquiaIncompleta(jer);
            for (InfoClase x : jer) {
                for (InfoMetodo im : x.metodos) {
                    if (im.nombre.equals(metodo) && !im.privado && !im.estatico && aridadCompatible(im, n)) {
                        cands.add(deIndice(im, x));
                    }
                }
            }
        }
        if ((t == null || incompleta) && !classpath.isEmpty()) {
            String fqn = t != null ? t.fqn : tipoMock;
            Class<?> k = fqn == null ? null : cargar(fqn);
            if (k != null) {
                for (Method m : k.getMethods()) {
                    if (m.getName().equals(metodo) && !java.lang.reflect.Modifier.isStatic(m.getModifiers())
                            && aridadCompatible(m.getParameterCount(), m.isVarArgs(), n)) {
                        cands.add(deReflexion(m));
                    }
                }
            }
        }
        List<Cand> unicas = new ArrayList<>();
        Set<String> claves = new LinkedHashSet<>();
        for (Cand c : cands) {
            if (claves.add(c.clave())) {
                unicas.add(c);
            }
        }
        if (unicas.isEmpty()) {
            return soloPistas(servicio, pistas);
        }
        List<Cand> filtradas = new ArrayList<>();
        for (Cand c : unicas) {
            if (compatible(c, pistas, servicio)) {
                filtradas.add(c);
            }
        }
        if (filtradas.isEmpty()) {
            filtradas = unicas;
        }
        List<String> tipos = new ArrayList<>();
        List<String> fqns = new ArrayList<>();
        boolean[] prim = new boolean[n];
        for (int i = 0; i < n; i++) {
            String s = filtradas.get(0).simple(i);
            boolean p = filtradas.get(0).primitivo(i);
            for (Cand c : filtradas) {
                if (s == null || !s.equals(c.simple(i)) || p != c.primitivo(i)) {
                    s = null;
                    break;
                }
            }
            tipos.add(s);
            fqns.add(s == null ? null : filtradas.get(0).fqn(i));
            prim[i] = s != null && p;
        }
        return new Firma(tipos, fqns, prim, unicas.size() > 1 || incompleta, filtradas.size() == 1);
    }

    /** Firma solo con las pistas (sin candidatas conocidas): sobrecargado, sin primitivos; null si no hay ninguna. */
    private Firma soloPistas(InfoClase servicio, String[] pistas) {
        List<String> tipos = new ArrayList<>();
        List<String> fqns = new ArrayList<>();
        boolean alguna = false;
        for (String p : pistas) {
            boolean usable = p != null && !ENVOLTORIOS.containsKey(p);
            tipos.add(usable ? p : null);
            fqns.add(usable ? fqnDe(p, servicio) : null);
            alguna |= usable;
        }
        return alguna ? new Firma(tipos, fqns, new boolean[pistas.length], true, false) : null;
    }

    // ------------------------------------------------------------- pistas del stub

    private static String pistaDeArgumento(Expression a) {
        if (a instanceof CastExpr ce) {
            return Textos.nombreSimple(ce.getType().asString());
        }
        if (a instanceof MethodCallExpr m) {
            String nombre = m.getNameAsString();
            if ("eq".equals(nombre) && m.getArguments().size() == 1 && m.getScope().isEmpty()) {
                return pistaDeValor(m.getArgument(0));
            }
            if (MATCHERS_DE_TIPO.contains(nombre) && m.getArguments().size() == 1 && m.getArgument(0) instanceof ClassExpr ce) {
                return Textos.nombreSimple(ce.getType().asString());
            }
            if (m.getArguments().isEmpty()) {
                String p = MATCHERS_PRIMITIVOS.get(nombre);
                return p != null ? p : MATCHERS_REFERENCIA.get(nombre);
            }
            return null;
        }
        return pistaDeValor(a);
    }

    private static String pistaDeValor(Expression v) {
        if (v instanceof ClassExpr) {
            return "Class";
        }
        if (v instanceof StringLiteralExpr) {
            return "String";
        }
        if (v instanceof BooleanLiteralExpr) {
            return "boolean";
        }
        if (v instanceof CastExpr ce) {
            return Textos.nombreSimple(ce.getType().asString());
        }
        return null;
    }

    // ------------------------------------------------------------- candidatas

    private boolean jerarquiaIncompleta(List<InfoClase> jer) {
        for (InfoClase x : jer) {
            List<String> sup = new ArrayList<>(x.extiende);
            sup.addAll(x.implementa);
            for (String s : sup) {
                if (SUPERTIPOS_TRIVIALES.contains(Textos.nombreSimple(s))) {
                    continue;
                }
                if (idx.resolverClase(s, x) == null) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean aridadCompatible(InfoMetodo im, int n) {
        boolean varargs = !im.parametros.isEmpty() && im.parametros.get(im.parametros.size() - 1).tipo().endsWith("...");
        return aridadCompatible(im.aridad(), varargs, n);
    }

    private static boolean aridadCompatible(int parametros, boolean varargs, int n) {
        return varargs ? n >= parametros - 1 : n == parametros;
    }

    private Cand deIndice(InfoMetodo im, InfoClase owner) {
        List<String> simples = new ArrayList<>();
        List<String> fqns = new ArrayList<>();
        boolean[] prim = new boolean[im.parametros.size()];
        boolean varargs = false;
        for (int i = 0; i < im.parametros.size(); i++) {
            String tipo = im.parametros.get(i).tipo().trim();
            boolean vararg = tipo.endsWith("...");
            varargs |= vararg;
            String base = Textos.quitarGenericos(tipo).trim();
            boolean array = vararg || base.endsWith("[]");
            String simple = Textos.nombreSimple(base);
            if (array || esVariableDeTipo(simple, im, owner)) {
                simples.add(null);
                fqns.add(null);
            } else if (ENVOLTORIOS.containsKey(simple)) {
                simples.add(simple);
                fqns.add(null);
                prim[i] = true;
            } else {
                simples.add(simple);
                String f = idx.resolverFqn(base, owner);
                fqns.add(f != null && f.contains(".") ? f : fqnDe(simple, owner));
            }
        }
        return new Cand(simples, fqns, prim, varargs);
    }

    private static boolean esVariableDeTipo(String simple, InfoMetodo im, InfoClase owner) {
        if (im.decl != null && im.decl.getTypeParameters().stream().anyMatch(p -> p.getNameAsString().equals(simple))) {
            return true;
        }
        return owner.decl instanceof com.github.javaparser.ast.body.ClassOrInterfaceDeclaration ci
                && ci.getTypeParameters().stream().anyMatch(p -> p.getNameAsString().equals(simple));
    }

    private static Cand deReflexion(Method m) {
        Class<?>[] ps = m.getParameterTypes();
        List<String> simples = new ArrayList<>();
        List<String> fqns = new ArrayList<>();
        boolean[] prim = new boolean[ps.length];
        for (int i = 0; i < ps.length; i++) {
            Class<?> p = ps[i];
            if (p.isArray()) {
                simples.add(null);
                fqns.add(null);
            } else if (p.isPrimitive()) {
                simples.add(p.getName());
                fqns.add(null);
                prim[i] = true;
            } else {
                simples.add(p.getSimpleName());
                fqns.add(p.getCanonicalName());
            }
        }
        return new Cand(simples, fqns, prim, m.isVarArgs());
    }

    // ------------------------------------------------------------- compatibilidad con las pistas

    private boolean compatible(Cand c, String[] pistas, InfoClase servicio) {
        for (int i = 0; i < pistas.length; i++) {
            if (pistas[i] != null && !compatible(c, i, pistas[i], servicio)) {
                return false;
            }
        }
        return true;
    }

    private boolean compatible(Cand c, int i, String pista, InfoClase servicio) {
        String p = c.simple(i);
        if (p == null || "Object".equals(p) || pista.equals(p)) {
            return true;
        }
        if (equivalenteBoxing(pista, p)) {
            return true;
        }
        if (c.primitivo(i)) {
            return false;
        }
        Set<String> supertipos = supertiposIndice(pista, servicio);
        if (supertipos.contains(p)) {
            return true;
        }
        Class<?> a = cargarTipo(pista, servicio);
        Class<?> b = cargarTipoFqn(c.fqn(i));
        if (a != null && b != null) {
            return b.isAssignableFrom(a);
        }
        boolean pistaProyecto = idx.resolverClase(pista, servicio) != null;
        boolean paramProyecto = c.fqn(i) != null && idx.get(c.fqn(i)) != null;
        if (pistaProyecto && paramProyecto) {
            return false;
        }
        return !(a != null && paramProyecto);
    }

    private static boolean equivalenteBoxing(String a, String b) {
        return ENVOLTORIOS.getOrDefault(a, a).equals(ENVOLTORIOS.getOrDefault(b, b));
    }

    /** Nombres simples de los supertipos (índice) del tipo, sin resolver los que no están en él. */
    private Set<String> supertiposIndice(String simple, InfoClase ctx) {
        Set<String> r = new LinkedHashSet<>();
        InfoClase t = idx.resolverClase(simple, ctx);
        if (t == null) {
            return r;
        }
        for (InfoClase x : LlamadasDependencias.jerarquia(t, idx)) {
            r.add(x.simple);
            x.extiende.forEach(s -> r.add(Textos.nombreSimple(s)));
            x.implementa.forEach(s -> r.add(Textos.nombreSimple(s)));
        }
        return r;
    }

    private String fqnDe(String simple, InfoClase ctx) {
        if (simple == null || simple.isEmpty()) {
            return null;
        }
        String f = idx.resolverFqn(simple, ctx);
        if (f != null && f.contains(".")) {
            return f;
        }
        String jdk = ResolutorImports.JDK.get(simple);
        if (jdk != null) {
            return jdk;
        }
        return Character.isUpperCase(simple.charAt(0)) && esLang(simple) ? "java.lang." + simple : null;
    }

    private static boolean esLang(String simple) {
        try {
            Class.forName("java.lang." + simple, false, ClassLoader.getPlatformClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    // ------------------------------------------------------------- reflexión (sin inicializar clases)

    private ClassLoader loader() {
        if (loader == null && !loaderFallido && !classpath.isEmpty()) {
            try {
                URL[] urls = new URL[classpath.size()];
                for (int i = 0; i < classpath.size(); i++) {
                    urls[i] = classpath.get(i).toUri().toURL();
                }
                loader = new URLClassLoader(urls, ClassLoader.getPlatformClassLoader());
            } catch (MalformedURLException | RuntimeException e) {
                loaderFallido = true;
                System.err.println("junit-gen: no se pudo preparar el cargador de clases de las firmas (" + e.getClass().getSimpleName() + ").");
            }
        }
        return loader;
    }

    private Class<?> cargarTipo(String simple, InfoClase ctx) {
        return cargarTipoFqn(fqnDe(simple, ctx));
    }

    private Class<?> cargarTipoFqn(String fqn) {
        if (fqn == null || fqn.isEmpty()) {
            return null;
        }
        if (fqn.startsWith("java.") || fqn.startsWith("javax.")) {
            return forName(fqn, ClassLoader.getPlatformClassLoader());
        }
        return cargar(fqn);
    }

    /** Carga el tipo sin inicializarlo; prueba {@code $} en lugar de los últimos puntos (tipos anidados). */
    private Class<?> cargar(String fqn) {
        ClassLoader l = loader();
        if (l == null || fqn == null || fqn.isEmpty()) {
            return null;
        }
        String n = fqn;
        for (int i = 0; i <= MAX_NIVELES; i++) {
            Class<?> c = forName(n, l);
            if (c != null) {
                return c;
            }
            int p = n.lastIndexOf('.');
            if (p < 0) {
                return null;
            }
            n = n.substring(0, p) + "$" + n.substring(p + 1);
        }
        return null;
    }

    private static Class<?> forName(String nombre, ClassLoader l) {
        try {
            return Class.forName(nombre, false, l);
        } catch (ClassNotFoundException e) {
            return null;
        } catch (LinkageError | RuntimeException e) {
            System.err.println("junit-gen: no se pudo cargar un tipo para la firma (" + e.getClass().getSimpleName() + ").");
            return null;
        }
    }
}
