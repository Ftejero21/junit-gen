package com.junitgen.escaneo;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.BooleanLiteralExpr;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.ClassExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.junitgen.generacion.ResolutorImports;
import com.junitgen.spec.NormalizadorMatchers;
import com.junitgen.util.Textos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Llamadas de un método del servicio a sus dependencias mock (directas y en sus métodos internos alcanzables),
 * el tipo de retorno de cada una (si está declarado en el proyecto o es un repositorio Spring Data) y el stub
 * típico sugerido. Solo lee.
 */
public final class LlamadasDependencias {

    /** Llamada {@code dependencia.metodo(...)} con su aridad. */
    public record Llamada(String dependencia, String metodo, int aridad) {
    }

    /** Stub típico: {@code dep.metodo}, tipo de retorno, expresión sugerida y nota (p. ej. «usuario autenticado»). */
    public record Stub(String llamada, String retorno, String devuelve, String nota, String stub) {
        /** Sin la llamada de stub lista para copiar. */
        public Stub(String llamada, String retorno, String devuelve, String nota) {
            this(llamada, retorno, devuelve, nota, null);
        }
    }

    private static final int MAX_NIVELES_SUPERTIPOS = 5;
    private static final Set<String> REPOSITORIOS_SPRING_DATA = Set.of("JpaRepository", "CrudRepository",
            "PagingAndSortingRepository", "ListCrudRepository", "MongoRepository");
    private static final Pattern AUTENTICADO = Pattern.compile(
            "(?i).*(current|actual|autenticad|authenticated|logged|logueado|principal).*");
    private static final Set<String> COLECCIONES = Set.of("List", "Collection", "Iterable", "Set");

    private LlamadasDependencias() {
    }

    /** Llamadas a dependencias mock en {@code m} y sus métodos no públicos alcanzables, en orden y sin repetir. */
    public static List<Llamada> de(ModuloEscaneado me, InfoClase c, InfoMetodo m) {
        if (me == null || c == null || m == null) {
            return List.of();
        }
        Map<String, String> deps = new LinkedHashMap<>();
        for (Dependencia d : me.analisis(c).dependencias) {
            if (d.nombre != null && d.esMockDeclarado()) {
                deps.putIfAbsent(d.nombre, d.nombre);
                if (d.campo != null) {
                    deps.putIfAbsent(d.campo, d.nombre);
                }
            }
        }
        if (deps.isEmpty()) {
            return List.of();
        }
        Set<String> nombresInternos = new LinkedHashSet<>();
        for (InfoMetodo x : c.metodos) {
            if (!x.publico) {
                nombresInternos.add(x.nombre);
            }
        }
        List<InfoMetodo> recorrido = new ArrayList<>();
        recorrido.add(m);
        recorrido.addAll(SeleccionFunciones.alcanzables(c, m, nombresInternos));
        Set<Llamada> r = new LinkedHashSet<>();
        for (InfoMetodo x : recorrido) {
            if (x.decl == null || x.decl.getBody().isEmpty()) {
                continue;
            }
            for (MethodCallExpr mc : x.decl.getBody().get().findAll(MethodCallExpr.class)) {
                if (!SeleccionFunciones.llamadaADependencia(mc, deps.keySet())) {
                    continue;
                }
                var s = mc.getScope().get();
                String nombre = s instanceof NameExpr n ? n.getNameAsString() : ((FieldAccessExpr) s).getNameAsString();
                r.add(new Llamada(deps.get(nombre), mc.getNameAsString(), mc.getArguments().size()));
            }
        }
        return new ArrayList<>(r);
    }

    /**
     * Tipo de retorno de {@code metodo} con esa aridad en el tipo del proyecto de la dependencia o sus supertipos
     * del proyecto (≤ 5 niveles); si no está y es un repositorio Spring Data, el de sus métodos estándar. Tipo
     * externo o desconocido → null.
     */
    public static String retorno(Dependencia d, String metodo, int aridad, InfoClase ctx, IndiceTipos idx) {
        if (d == null || metodo == null || idx == null) {
            return null;
        }
        InfoClase t = idx.get(d.tipoFqn);
        if (t == null) {
            t = idx.resolverClase(d.tipo, ctx);
        }
        if (t == null) {
            return null;
        }
        List<InfoClase> jerarquia = jerarquia(t, idx);
        for (InfoClase x : jerarquia) {
            for (InfoMetodo im : x.metodos) {
                if (im.nombre.equals(metodo) && !im.estatico && coincideAridad(im, aridad)) {
                    return im.tipoRetorno;
                }
            }
        }
        for (InfoClase x : jerarquia) {
            String entidad = entidadSpringData(x);
            if (entidad != null) {
                return retornoSpringData(metodo, entidad);
            }
        }
        return null;
    }

    private static boolean coincideAridad(InfoMetodo im, int aridad) {
        if (im.aridad() == aridad) {
            return true;
        }
        return !im.parametros.isEmpty() && im.parametros.get(im.parametros.size() - 1).tipo().endsWith("...")
                && aridad >= im.aridad() - 1;
    }

    /** El tipo y sus supertipos del proyecto (extiende + implementa), en anchura, ≤ 5 niveles. */
    public static List<InfoClase> jerarquia(InfoClase t, IndiceTipos idx) {
        List<InfoClase> r = new ArrayList<>();
        Set<String> vistos = new HashSet<>();
        List<InfoClase> nivel = List.of(t);
        for (int p = 0; p <= MAX_NIVELES_SUPERTIPOS && !nivel.isEmpty(); p++) {
            List<InfoClase> siguiente = new ArrayList<>();
            for (InfoClase x : nivel) {
                if (!vistos.add(x.fqn)) {
                    continue;
                }
                r.add(x);
                List<String> sup = new ArrayList<>(x.extiende);
                sup.addAll(x.implementa);
                for (String s : sup) {
                    InfoClase st = idx.resolverClase(s, x);
                    if (st != null) {
                        siguiente.add(st);
                    }
                }
            }
            nivel = siguiente;
        }
        return r;
    }

    /** T de {@code JpaRepository<T, ID>} (o equivalente) si el tipo lo extiende directamente; si no, null. */
    private static String entidadSpringData(InfoClase x) {
        if (!(x.decl instanceof ClassOrInterfaceDeclaration ci)) {
            return null;
        }
        List<ClassOrInterfaceType> sup = new ArrayList<>(ci.getExtendedTypes());
        sup.addAll(ci.getImplementedTypes());
        for (ClassOrInterfaceType st : sup) {
            if (REPOSITORIOS_SPRING_DATA.contains(st.getNameAsString()) && st.getTypeArguments().isPresent()
                    && !st.getTypeArguments().get().isEmpty()) {
                return st.getTypeArguments().get().get(0).asString();
            }
        }
        return null;
    }

    private static String retornoSpringData(String metodo, String t) {
        if (metodo.startsWith("delete")) {
            return "void";
        }
        return switch (metodo) {
            case "findById" -> "Optional<" + t + ">";
            case "save", "saveAndFlush", "getReferenceById", "getById", "getOne" -> t;
            case "findAll", "saveAll", "findAllById" -> "List<" + t + ">";
            case "existsById" -> "boolean";
            case "count" -> "long";
            case "flush" -> "void";
            default -> null;
        };
    }

    /**
     * Expresión sugerida para devolver un valor de ese tipo; {@code <X>} = instancia de X (ver
     * {@code tiposAuxiliares[X].crear}). void o desconocido → null.
     */
    public static String sugerencia(String retorno, int java) {
        if (retorno == null) {
            return null;
        }
        String r = retorno.trim();
        if (r.isEmpty() || r.equals("void")) {
            return null;
        }
        int a = r.indexOf('<');
        if (a > 0 && r.endsWith(">")) {
            String ext = Textos.nombreSimple(r.substring(0, a));
            String arg = r.substring(a + 1, r.length() - 1).trim();
            if (ext.equals("Optional")) {
                return "Optional.of(" + elemento(arg, java) + ")";
            }
            if (COLECCIONES.contains(ext)) {
                return AnalizadorDependencias.expresionColeccion(ext, elemento(arg, java), java);
            }
            if (ext.equals("Page")) {
                return "new PageImpl<>(" + AnalizadorDependencias.expresionColeccion("List", elemento(arg, java), java) + ")";
            }
        }
        String simple = FabricaObjetos.valorSimple(r, java);
        if (simple != null) {
            return simple;
        }
        return marcador(r);
    }

    private static String elemento(String tipo, int java) {
        String t = tipo.startsWith("? extends ") ? tipo.substring("? extends ".length()).trim() : tipo;
        String simple = FabricaObjetos.valorSimple(t, java);
        return simple != null ? simple : marcador(t);
    }

    private static String marcador(String tipo) {
        return "<" + Textos.quitarGenericos(tipo).trim() + ">";
    }

    // ------------------------------------------------------------- sitios de llamada y tipo estático

    /** Llamada concreta a un método de un receptor, con el método de la clase que la contiene. */
    public record Sitio(MethodCallExpr llamada, InfoMetodo en) {
    }

    /** Tipos primitivos y envoltorios numéricos: nunca se infieren del sitio de llamada (el ensanchamiento los falsea). */
    private static final Set<String> NUMERICOS = Set.of("byte", "short", "int", "long", "float", "double", "char", "Byte",
            "Short", "Integer", "Long", "Float", "Double", "Character");

    /**
     * Llamadas {@code receptor.metodo(..)} con esa aridad (receptor = {@code x} o {@code this.x}, con {@code x} en
     * {@code receptores}) en {@code m} y sus métodos internos alcanzables; con {@code m} null, en todos los métodos de
     * {@code c}.
     */
    public static List<Sitio> sitios(InfoClase c, InfoMetodo m, Set<String> receptores, String metodo, int aridad) {
        if (c == null || receptores == null || receptores.isEmpty() || metodo == null) {
            return List.of();
        }
        List<InfoMetodo> recorrido = new ArrayList<>();
        if (m == null) {
            recorrido.addAll(c.metodos);
        } else {
            Set<String> internos = new LinkedHashSet<>();
            for (InfoMetodo x : c.metodos) {
                if (!x.publico) {
                    internos.add(x.nombre);
                }
            }
            recorrido.add(m);
            recorrido.addAll(SeleccionFunciones.alcanzables(c, m, internos));
        }
        List<Sitio> r = new ArrayList<>();
        for (InfoMetodo x : recorrido) {
            if (x.decl == null || x.decl.getBody().isEmpty()) {
                continue;
            }
            for (MethodCallExpr mc : x.decl.getBody().get().findAll(MethodCallExpr.class)) {
                if (mc.getNameAsString().equals(metodo) && mc.getArguments().size() == aridad && mc.getScope().isPresent()) {
                    Expression s = mc.getScope().get();
                    boolean receptor = (s instanceof NameExpr n && receptores.contains(n.getNameAsString()))
                            || (s instanceof FieldAccessExpr f && f.getScope() instanceof ThisExpr
                            && receptores.contains(f.getNameAsString()));
                    if (receptor) {
                        r.add(new Sitio(mc, x));
                    }
                }
            }
        }
        return r;
    }

    /**
     * Tipo estático (borrado, nombre tal como se escribe) de la expresión en el código de {@code en}, o null si no se sabe:
     * literales de texto o booleanos, {@code X.class}, {@code new T(..)}, casts, paréntesis, ternarios con ramas iguales,
     * variables (parámetro, local no {@code var}, campo), constantes {@code T.CONST} de tipos del índice y llamadas cuyo
     * retorno está en el índice. Numéricos, {@code null}, lambdas, referencias a método y variables de tipo → null.
     */
    public static String tipoEstatico(Expression e, InfoMetodo en, InfoClase c, IndiceTipos idx) {
        try {
            return tipoEstatico(e, en, c, idx, 0);
        } catch (RuntimeException ex) {
            System.err.println("junit-gen: no se pudo inferir el tipo de un argumento (" + ex.getClass().getSimpleName() + ")");
            return null;
        }
    }

    private static final int MAX_PROFUNDIDAD_TIPO = 6;

    private static String tipoEstatico(Expression e, InfoMetodo en, InfoClase c, IndiceTipos idx, int nivel) {
        if (e == null || nivel > MAX_PROFUNDIDAD_TIPO) {
            return null;
        }
        if (e instanceof StringLiteralExpr) {
            return "String";
        }
        if (e instanceof BooleanLiteralExpr) {
            return "boolean";
        }
        if (e instanceof ClassExpr) {
            return "Class";
        }
        if (e instanceof ObjectCreationExpr oc) {
            return limpio(oc.getType().asString(), en, c);
        }
        if (e instanceof CastExpr ce) {
            return limpio(ce.getType().asString(), en, c);
        }
        if (e instanceof EnclosedExpr ee) {
            return tipoEstatico(ee.getInner(), en, c, idx, nivel + 1);
        }
        if (e instanceof ConditionalExpr ce) {
            String a = tipoEstatico(ce.getThenExpr(), en, c, idx, nivel + 1);
            String b = tipoEstatico(ce.getElseExpr(), en, c, idx, nivel + 1);
            return a != null && a.equals(b) ? a : null;
        }
        if (e instanceof BinaryExpr be) {
            if (be.getOperator() != BinaryExpr.Operator.PLUS) {
                return null;
            }
            return "String".equals(tipoEstatico(be.getLeft(), en, c, idx, nivel + 1))
                    || "String".equals(tipoEstatico(be.getRight(), en, c, idx, nivel + 1)) ? "String" : null;
        }
        if (e instanceof NameExpr n) {
            return limpio(tipoDeVariable(n.getNameAsString(), en, c, idx), en, c);
        }
        if (e instanceof FieldAccessExpr f) {
            if (f.getScope() instanceof ThisExpr) {
                return limpio(tipoDeVariable(f.getNameAsString(), null, c, idx), en, c);
            }
            if (f.getScope() instanceof NameExpr sc && !sc.getNameAsString().isEmpty()
                    && Character.isUpperCase(sc.getNameAsString().charAt(0)) && idx != null) {
                InfoClase t = idx.resolverClase(sc.getNameAsString(), c);
                if (t == null) {
                    return null;
                }
                if (t.tipo == InfoClase.Tipo.ENUM && t.constantesEnum.contains(f.getNameAsString())) {
                    return t.simple;
                }
                InfoClase.InfoCampo campo = t.campo(f.getNameAsString());
                return campo != null && campo.estatico() ? limpio(campo.tipo(), null, t) : null;
            }
            return null;
        }
        if (e instanceof MethodCallExpr mc) {
            return tipoDeLlamada(mc, en, c, idx, nivel);
        }
        return null;
    }

    private static String tipoDeLlamada(MethodCallExpr mc, InfoMetodo en, InfoClase c, IndiceTipos idx, int nivel) {
        if (idx == null) {
            return null;
        }
        int aridad = mc.getArguments().size();
        if (mc.getScope().isEmpty() || mc.getScope().get() instanceof ThisExpr) {
            return retornoUnico(c, mc.getNameAsString(), aridad, idx);
        }
        String declarado = tipoEstatico(mc.getScope().get(), en, c, idx, nivel + 1);
        if (declarado == null) {
            return null;
        }
        InfoClase t = idx.resolverClase(declarado, c);
        if (t == null) {
            return null;
        }
        Dependencia d = new Dependencia();
        d.tipo = declarado;
        d.tipoFqn = t.fqn;
        String ret = retorno(d, mc.getNameAsString(), aridad, c, idx);
        if (ret == null || ret.trim().equals("void")) {
            return null;
        }
        return limpio(ret, null, t);
    }

    /** Tipo de retorno de un método de la jerarquía de {@code t} con ese nombre y aridad, si todas las sobrecargas coinciden. */
    private static String retornoUnico(InfoClase t, String nombre, int aridad, IndiceTipos idx) {
        String r = null;
        for (InfoClase x : jerarquia(t, idx)) {
            for (InfoMetodo im : x.metodos) {
                if (im.nombre.equals(nombre) && coincideAridad(im, aridad)) {
                    String l = limpio(im.tipoRetorno, null, x);
                    if (l == null || (r != null && !r.equals(l))) {
                        return null;
                    }
                    r = l;
                }
            }
            if (r != null) {
                return r;
            }
        }
        return r;
    }

    /** Tipo declarado de una variable: parámetro o local del método, o campo (de la clase o de sus supertipos). */
    private static String tipoDeVariable(String nombre, InfoMetodo en, InfoClase c, IndiceTipos idx) {
        if (en != null && en.decl != null) {
            for (InfoClase.Parametro p : en.parametros) {
                if (p.nombre().equals(nombre)) {
                    return p.tipo().endsWith("...") ? p.tipo().substring(0, p.tipo().length() - 3) + "[]" : p.tipo();
                }
            }
            for (VariableDeclarator v : en.decl.findAll(VariableDeclarator.class)) {
                if (v.getNameAsString().equals(nombre)) {
                    return v.getType().isVarType() ? null : v.getType().asString();
                }
            }
            for (Parameter p : en.decl.findAll(Parameter.class)) {
                if (p.getNameAsString().equals(nombre)) {
                    return p.getType().isUnknownType() || p.getType().isVarType() ? null : p.getType().asString();
                }
            }
        }
        if (c == null) {
            return null;
        }
        List<InfoClase> jer = idx == null ? List.of(c) : jerarquia(c, idx);
        for (InfoClase x : jer) {
            InfoClase.InfoCampo campo = x.campo(nombre);
            if (campo != null) {
                return campo.tipo();
            }
        }
        return null;
    }

    /** Tipo borrado de genéricos; null si está vacío, es numérico, es una variable de tipo o es {@code void}. */
    private static String limpio(String tipo, InfoMetodo en, InfoClase c) {
        if (tipo == null) {
            return null;
        }
        String t = Textos.quitarGenericos(tipo).trim();
        if (t.isEmpty() || t.equals("void") || NUMERICOS.contains(t)) {
            return null;
        }
        String base = t.endsWith("[]") ? t.substring(0, t.length() - 2) : t;
        if (en != null && en.decl != null && en.decl.getTypeParameters().stream().anyMatch(p -> p.getNameAsString().equals(base))) {
            return null;
        }
        if (c != null && c.decl instanceof ClassOrInterfaceDeclaration ci
                && ci.getTypeParameters().stream().anyMatch(p -> p.getNameAsString().equals(base))) {
            return null;
        }
        return t;
    }

    /** Stub típico de la llamada; null si el retorno es desconocido o void. */
    public static Stub stub(Dependencia d, Llamada l, InfoClase ctx, IndiceTipos idx, int java) {
        if (d == null || l == null) {
            return null;
        }
        String ret = retorno(d, l.metodo(), l.aridad(), ctx, idx);
        if (ret == null || ret.trim().equals("void")) {
            return null;
        }
        String nota = AUTENTICADO.matcher(l.metodo().toLowerCase(Locale.ROOT)).matches() ? "usuario autenticado" : null;
        return new Stub(d.nombre + "." + l.metodo(), ret.trim(), sugerencia(ret, java), nota);
    }

    /**
     * Stub típico con la llamada de stub lista para copiar ({@code stub}: firma real y matchers del tipo correcto). A
     * diferencia de {@link #stub(Dependencia, Llamada, InfoClase, IndiceTipos, int)}, también devuelve entrada para un
     * retorno desconocido (tipo externo: {@code retorno} = tipo de la variable que recibe la llamada, o sin retorno) y para
     * los métodos {@code void} ({@code retorno: "void"}, sin {@code devuelve}: sirven para las verificaciones).
     */
    public static Stub stub(Dependencia d, Llamada l, InfoClase ctx, IndiceTipos idx, int java, InfoMetodo probado,
                            NormalizadorMatchers.Politica politica) {
        if (d == null || l == null) {
            return null;
        }
        String ret = retorno(d, l.metodo(), l.aridad(), ctx, idx);
        boolean esVoid = ret != null && ret.trim().equals("void");
        String retorno = ret == null ? null : ret.trim();
        Set<String> receptores = FirmasReales.receptoresDe(d);
        if (retorno == null) {
            retorno = tipoDeVariableReceptora(ctx, probado, receptores, l);
        }
        String nota = AUTENTICADO.matcher(l.metodo().toLowerCase(Locale.ROOT)).matches() ? "usuario autenticado" : null;
        String devuelve = esVoid || ret == null ? null : sugerencia(ret, java);
        String llamada = l.metodo() + "(" + String.join(", ", Collections.nCopies(l.aridad(), "any()")) + ")";
        String texto = llamada;
        try (FirmasReales firmas = new FirmasReales(idx, List.of())) {
            ResolutorImports ri = new ResolutorImports(idx, ctx, List.of());
            texto = NormalizadorMatchers.normalizar(d.nombre, llamada, firmas, ctx, probado,
                    d.tipoFqn != null ? d.tipoFqn : d.tipo, receptores,
                    politica == null ? new NormalizadorMatchers.Politica(false) : politica, t -> {
                        String r = ri.resolver(Textos.nombreSimple(t));
                        return t.contains(".") ? t.equals(r) || (t.startsWith("java.lang.") && t.indexOf('.', "java.lang.".length()) < 0)
                                : r != null;
                    }, new ArrayList<>());
        } catch (RuntimeException e) {
            System.err.println("junit-gen: no se pudo calcular el stub de " + d.nombre + "." + l.metodo() + " ("
                    + e.getClass().getSimpleName() + ")");
        }
        return new Stub(d.nombre + "." + l.metodo(), esVoid ? "void" : retorno, devuelve, nota, texto);
    }

    /** Tipo de la variable que recibe la llamada si esta es su inicializador (sin {@code var}); null si no se sabe. */
    private static String tipoDeVariableReceptora(InfoClase ctx, InfoMetodo probado, Set<String> receptores, Llamada l) {
        for (Sitio s : sitios(ctx, probado, receptores, l.metodo(), l.aridad())) {
            if (s.llamada().getParentNode().orElse(null) instanceof VariableDeclarator v && v.getInitializer().isPresent()
                    && v.getInitializer().get() == s.llamada() && !v.getType().isVarType()) {
                return v.getType().asString();
            }
        }
        return null;
    }
}
