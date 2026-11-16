package com.junitgen.escaneo;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.junitgen.util.Textos;

import java.util.ArrayList;
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
    public record Stub(String llamada, String retorno, String devuelve, String nota) {
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
    private static List<InfoClase> jerarquia(InfoClase t, IndiceTipos idx) {
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
}
