package com.junitgen.spec;

import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.InitializerDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.stmt.Statement;
import com.junitgen.escaneo.IndiceTipos;
import com.junitgen.escaneo.InfoClase;
import com.junitgen.escaneo.InfoMetodo;
import com.junitgen.escaneo.LlamadasDependencias;
import com.junitgen.util.Textos;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Comprobaciones previas sobre el código del spec con la corrección exacta en el error: identificadores no declarados,
 * métodos inexistentes en tipos del proyecto (también en {@code new T() {{ setX(..); }}}), accesos a miembros privados y
 * alias de la instancia bajo prueba. Solo lee el índice de tipos; ante la duda no da error.
 */
final class ComprobadorReferencias {

    /** Un hallazgo: código de error, mensaje y sugerencia con la corrección. */
    record Hallazgo(String codigo, String mensaje, String sugerencia) {
    }

    static final String COD_METODO_INEXISTENTE = "METODO_INEXISTENTE";
    static final String COD_ACCESO_PRIVADO = "ACCESO_PRIVADO";
    static final String COD_METODO_PRIVADO = "METODO_PRIVADO";

    /** Métodos públicos y campos listados en el error de un método inexistente. */
    static final int MAX_METODOS = 12;
    static final int MAX_CAMPOS = 8;

    private static final Set<String> LOMBOK_CLASE = Set.of("Data", "Value", "Getter", "Setter", "Builder", "SuperBuilder", "With",
            "Accessors");
    private static final Set<String> PREFIJOS_LOMBOK = Set.of("get", "set", "is", "with");
    private static final Set<String> NOMBRES_LOMBOK = Set.of("builder", "toBuilder");
    /** Supertipos sin métodos de interés: no vuelven incompleta una jerarquía. */
    private static final Set<String> SUPERTIPOS_TRIVIALES = Set.of("Object", "Serializable", "Cloneable");
    /** Métodos que un inicializador de instancia de un test llama sin ámbito y que no son del tipo creado. */
    private static final Set<String> AYUDANTES = Set.of("mock", "spy", "when", "verify", "doReturn", "doThrow", "doNothing",
            "doAnswer", "lenient", "given", "fail", "eq", "any", "anyString", "anyInt", "anyLong", "anyList", "isNull", "notNull",
            "nullable", "isA", "argThat", "times", "never", "atLeastOnce");
    private static final Set<String> METODOS_OBJECT = new HashSet<>();

    static {
        for (Method m : Object.class.getMethods()) {
            METODOS_OBJECT.add(m.getName());
        }
        METODOS_OBJECT.add("clone");
        METODOS_OBJECT.add("finalize");
    }

    private ComprobadorReferencias() {
    }

    // ------------------------------------------------------------- identificadores

    /**
     * Identificadores en minúscula usados como variable que no están en {@code conocidos}, ni se declaran en el propio nodo,
     * ni son la raíz de un nombre cualificado ({@code java.util.List}), ni están dentro de un cuerpo de clase anónima.
     */
    static Set<String> libres(Node n, Set<String> conocidos) {
        Set<String> declarados = new HashSet<>();
        n.findAll(VariableDeclarator.class).forEach(v -> declarados.add(v.getNameAsString()));
        n.findAll(Parameter.class).forEach(p -> declarados.add(p.getNameAsString()));
        Set<String> r = new TreeSet<>();
        for (NameExpr ne : n.findAll(NameExpr.class)) {
            String nombre = ne.getNameAsString();
            if (nombre.isEmpty() || !Character.isLowerCase(nombre.charAt(0)) || conocidos.contains(nombre)
                    || declarados.contains(nombre) || enCuerpoDeClase(ne) || raizCualificada(ne)) {
                continue;
            }
            r.add(nombre);
        }
        return r;
    }

    private static boolean enCuerpoDeClase(Node n) {
        Node p = n.getParentNode().orElse(null);
        while (p != null) {
            if (p instanceof BodyDeclaration<?>) {
                return true;
            }
            p = p.getParentNode().orElse(null);
        }
        return false;
    }

    /** true si el nombre es la raíz de un nombre cualificado con algún segmento en mayúscula ({@code java.util.List}). */
    private static boolean raizCualificada(NameExpr ne) {
        Node actual = ne;
        while (actual.getParentNode().orElse(null) instanceof FieldAccessExpr fa && fa.getScope() == actual) {
            actual = fa;
            String segmento = fa.getNameAsString();
            if (!segmento.isEmpty() && Character.isUpperCase(segmento.charAt(0))) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------- alias de la instancia bajo prueba

    /**
     * Texto con {@code alias} sustituido por {@code real} (por AST, solo como variable no declarada en el propio texto);
     * null si no hay nada que cambiar o no parsea.
     */
    static String renombrar(String texto, String alias, String real) {
        if (texto == null || texto.isBlank() || !texto.contains(alias)) {
            return null;
        }
        ParseResult<Expression> pr = IndiceTipos.parser().parseExpression(texto);
        Node nodo = pr.isSuccessful() ? pr.getResult().orElse(null) : null;
        boolean bloque = false;
        if (nodo == null) {
            Statement st = ValidadorSpec.parsearSentencia(texto);
            if (st == null) {
                // Varias sentencias en un solo elemento: se tratan como bloque y se devuelven una por línea.
                ParseResult<com.github.javaparser.ast.stmt.BlockStmt> b = IndiceTipos.parser().parseBlock("{" + texto + "\n}");
                if (!b.isSuccessful() || b.getResult().isEmpty()) {
                    return null;
                }
                st = b.getResult().get();
                bloque = true;
            }
            nodo = st;
        }
        Set<String> declarados = new HashSet<>();
        nodo.findAll(VariableDeclarator.class).forEach(v -> declarados.add(v.getNameAsString()));
        nodo.findAll(Parameter.class).forEach(p -> declarados.add(p.getNameAsString()));
        if (declarados.contains(alias)) {
            return null;
        }
        boolean cambia = false;
        for (NameExpr ne : nodo.findAll(NameExpr.class)) {
            if (ne.getNameAsString().equals(alias) && !enCuerpoDeClase(ne)) {
                ne.setName(real);
                cambia = true;
            }
        }
        if (!cambia) {
            return null;
        }
        if (bloque) {
            List<String> sentencias = new ArrayList<>();
            ((com.github.javaparser.ast.stmt.BlockStmt) nodo).getStatements().forEach(x -> sentencias.add(x.toString()));
            return String.join("\n", sentencias);
        }
        return nodo.toString();
    }

    // ------------------------------------------------------------- métodos inexistentes

    /**
     * Llamadas a métodos que no existen en un tipo del proyecto: las llamadas sin ámbito del inicializador de
     * {@code new T(..) {{ .. }}} y {@code v.m(..)} con {@code v} de tipo declarado conocido ({@code tiposDeclarados} o
     * declarado en el propio nodo). {@code ignorar}: nombres de métodos del test (fixtures) y de importaciones estáticas.
     */
    static List<Hallazgo> metodos(Node n, Map<String, String> tiposDeclarados, Set<String> ignorar, InfoClase servicio,
                                  IndiceTipos idx) {
        List<Hallazgo> r = new ArrayList<>();
        Set<String> avisados = new LinkedHashSet<>();
        for (ObjectCreationExpr oc : n.findAll(ObjectCreationExpr.class)) {
            if (oc.getAnonymousClassBody().isEmpty()) {
                continue;
            }
            InfoClase t = idx.resolverClase(oc.getType().getNameAsString(), servicio);
            Set<String> propios = new HashSet<>();
            for (BodyDeclaration<?> b : oc.getAnonymousClassBody().get()) {
                if (b instanceof MethodDeclaration md) {
                    propios.add(md.getNameAsString());
                }
            }
            for (BodyDeclaration<?> b : oc.getAnonymousClassBody().get()) {
                if (!(b instanceof InitializerDeclaration init)) {
                    continue;
                }
                for (MethodCallExpr mc : init.findAll(MethodCallExpr.class)) {
                    boolean sinAmbito = mc.getScope().isEmpty() || mc.getScope().get() instanceof ThisExpr;
                    String nombre = mc.getNameAsString();
                    if (sinAmbito && !propios.contains(nombre) && !ignorar.contains(nombre) && !AYUDANTES.contains(nombre)
                            && !nombre.startsWith("assert")) {
                        hallazgo(t, idx, nombre, avisados, r);
                    }
                }
            }
        }
        Map<String, String> locales = new java.util.HashMap<>(tiposDeclarados);
        for (VariableDeclarator v : n.findAll(VariableDeclarator.class)) {
            if (!v.getType().isVarType()) {
                locales.put(v.getNameAsString(), v.getType().asString());
            }
        }
        for (MethodCallExpr mc : n.findAll(MethodCallExpr.class)) {
            if (mc.getScope().isPresent() && mc.getScope().get() instanceof NameExpr v && !enCuerpoDeClase(mc)) {
                String tipo = locales.get(v.getNameAsString());
                if (tipo != null && !tipo.contains("[")) {
                    hallazgo(idx.resolverClase(Textos.nombreSimple(tipo), servicio), idx, mc.getNameAsString(), avisados, r);
                }
            }
        }
        return r;
    }

    private static void hallazgo(InfoClase t, IndiceTipos idx, String metodo, Set<String> avisados, List<Hallazgo> r) {
        if (t == null || t.tipo == InfoClase.Tipo.RECORD || t.tipo == InfoClase.Tipo.ENUM || t.tipo == InfoClase.Tipo.ANOTACION
                || METODOS_OBJECT.contains(metodo)) {
            return;
        }
        List<InfoClase> jer = LlamadasDependencias.jerarquia(t, idx);
        if (jer.isEmpty() || incompleta(jer, idx) || tieneMetodo(jer, metodo) || excusaLombok(jer, metodo)) {
            return;
        }
        if (!avisados.add(t.fqn + "#" + metodo)) {
            return;
        }
        List<String> publicos = new ArrayList<>();
        List<String> campos = new ArrayList<>();
        for (InfoClase x : jer) {
            for (InfoMetodo m : x.metodos) {
                if (m.publico && !m.estatico && publicos.size() < MAX_METODOS && !publicos.contains(m.clave())) {
                    publicos.add(m.clave());
                }
            }
            for (InfoClase.InfoCampo c : x.campos) {
                if (!c.estatico() && campos.size() < MAX_CAMPOS && !campos.contains(c.nombre())) {
                    campos.add(c.nombre());
                }
            }
        }
        String campoEjemplo = campos.isEmpty() ? "campo" : campos.get(0);
        r.add(new Hallazgo(COD_METODO_INEXISTENTE, "El método `" + metodo + "` no existe en " + t.simple + ".",
                "Métodos públicos de " + t.simple + ": " + (publicos.isEmpty() ? "ninguno" : publicos) + ". Campos: "
                        + (campos.isEmpty() ? "ninguno" : campos) + ". Para fijar un campo sin setter usa reflexión: "
                        + "`java.lang.reflect.Field f = " + t.simple + ".class.getDeclaredField(\"" + campoEjemplo
                        + "\"); f.setAccessible(true); f.set(objeto, valor);` (con la excepción comprobada en `throws` o dentro de "
                        + "una fixture)."));
    }

    private static boolean incompleta(List<InfoClase> jer, IndiceTipos idx) {
        for (InfoClase x : jer) {
            List<String> sup = new ArrayList<>(x.extiende);
            sup.addAll(x.implementa);
            for (String s : sup) {
                if (!SUPERTIPOS_TRIVIALES.contains(Textos.nombreSimple(s)) && idx.resolverClase(s, x) == null) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean tieneMetodo(List<InfoClase> jer, String nombre) {
        for (InfoClase x : jer) {
            for (InfoMetodo m : x.metodos) {
                if (m.nombre.equals(nombre)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** true si {@code nombre} parece un método generado por Lombok en el tipo o su jerarquía. */
    private static boolean excusaLombok(List<InfoClase> jer, String nombre) {
        boolean candidato = NOMBRES_LOMBOK.contains(nombre) || PREFIJOS_LOMBOK.stream().anyMatch(nombre::startsWith);
        if (!candidato) {
            return false;
        }
        for (InfoClase x : jer) {
            if (x.tieneAnotacion(LOMBOK_CLASE.toArray(new String[0]))) {
                return true;
            }
            for (InfoClase.InfoCampo c : x.campos) {
                if (c.anotaciones() != null && c.anotaciones().stream().anyMatch(LOMBOK_CLASE::contains)) {
                    return true;
                }
            }
        }
        return false;
    }

    // ------------------------------------------------------------- miembros privados

    /**
     * {@code T.CAMPO} con {@code T} del índice y campo privado, y {@code <instancia>.m()} con todas las sobrecargas de
     * {@code m} privadas en el servicio.
     */
    static List<Hallazgo> privados(Node n, String instancia, InfoClase servicio, IndiceTipos idx) {
        List<Hallazgo> r = new ArrayList<>();
        Set<String> avisados = new LinkedHashSet<>();
        for (FieldAccessExpr fa : n.findAll(FieldAccessExpr.class)) {
            if (!(fa.getScope() instanceof NameExpr sc) || sc.getNameAsString().isEmpty()
                    || !Character.isUpperCase(sc.getNameAsString().charAt(0))) {
                continue;
            }
            InfoClase t = idx.resolverClase(sc.getNameAsString(), servicio);
            if (t == null || t.decl == null || !campoPrivado(t, fa.getNameAsString())
                    || !avisados.add(t.fqn + "." + fa.getNameAsString())) {
                continue;
            }
            r.add(new Hallazgo(COD_ACCESO_PRIVADO, "`" + t.simple + "." + fa.getNameAsString() + "` es privado: el test no puede "
                    + "acceder a él.", "Usa otro valor (literal o constructor público) o, si es imprescindible, reflexión con "
                    + "`java.lang.reflect.Field` (getDeclaredField + setAccessible(true))."));
        }
        if (instancia != null && servicio != null) {
            for (MethodCallExpr mc : n.findAll(MethodCallExpr.class)) {
                if (!(mc.getScope().isPresent() && mc.getScope().get() instanceof NameExpr sc) || !instancia.equals(sc.getNameAsString())) {
                    continue;
                }
                String nombre = mc.getNameAsString();
                boolean alguno = false;
                boolean accesible = false;
                for (InfoMetodo m : servicio.metodos) {
                    if (m.nombre.equals(nombre)) {
                        alguno = true;
                        accesible |= !m.privado;
                    }
                }
                if (alguno && !accesible && avisados.add("#" + nombre)) {
                    r.add(new Hallazgo(COD_METODO_PRIVADO, "`" + nombre + "` es un método privado de " + servicio.simple
                            + ": el test no puede llamarlo.", "Llama al método público que lo usa (ver `metodos` del paquete) o, "
                            + "si es imprescindible, usa reflexión con `java.lang.reflect.Method`."));
                }
            }
        }
        return r;
    }

    private static boolean campoPrivado(InfoClase t, String campo) {
        for (FieldDeclaration f : t.decl.getFields()) {
            for (VariableDeclarator v : f.getVariables()) {
                if (v.getNameAsString().equals(campo)) {
                    return f.isPrivate();
                }
            }
        }
        return false;
    }
}
