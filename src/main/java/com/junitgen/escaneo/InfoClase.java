package com.junitgen.escaneo;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.stmt.ExplicitConstructorInvocationStmt;
import com.github.javaparser.ast.type.ClassOrInterfaceType;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Tipo de primer nivel indexado (fuente de src/main/java o src/test/java). */
public class InfoClase {

    public enum Tipo { CLASE, INTERFAZ, ENUM, ANOTACION, RECORD }

    public record Parametro(String tipo, String nombre, Set<String> anotaciones) {
    }

    public record InfoCampo(String nombre, String tipo, Set<String> anotaciones, boolean estatico, boolean esFinal,
                            Expression inicializador) {
    }

    public static class InfoConstructor {
        public final List<Parametro> parametros = new ArrayList<>();
        public final Set<String> anotaciones = new LinkedHashSet<>();
        /** parámetro → campo al que se asigna. */
        public final Map<String, String> asignaciones = new LinkedHashMap<>();
        /** Parámetros que se guardan en un campo (directo, requireNonNull o pasados a this/super). */
        public final Set<String> almacenados = new LinkedHashSet<>();
        /** Si lo genera Lombok: nombre de la anotación; si no, null. */
        public String lombok;
        public boolean privado;
        public ConstructorDeclaration decl;

        public String firma() {
            StringBuilder sb = new StringBuilder("(");
            for (int i = 0; i < parametros.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(parametros.get(i).tipo()).append(' ').append(parametros.get(i).nombre());
            }
            return sb.append(')').append(lombok != null ? " [Lombok @" + lombok + "]" : "").toString();
        }
    }

    public String fqn;
    public String simple;
    public String paquete;
    public Path archivo;
    public Path raizFuentes;
    public boolean esTest;
    public Tipo tipo;
    public boolean abstracta;
    public CompilationUnit cu;
    public TypeDeclaration<?> decl;
    /** Tipo que declara a este como miembro (tipos anidados del índice); null en los de primer nivel. */
    public InfoClase contenedora;
    public final List<String> imports = new ArrayList<>();
    public final List<String> importsComodin = new ArrayList<>();
    public final List<String> importsEstaticos = new ArrayList<>();
    public final Set<String> anotaciones = new LinkedHashSet<>();
    public final List<String> extiende = new ArrayList<>();
    public final List<String> implementa = new ArrayList<>();
    public final List<InfoCampo> campos = new ArrayList<>();
    public final List<InfoConstructor> constructores = new ArrayList<>();
    public final List<InfoMetodo> metodos = new ArrayList<>();
    public final List<String> constantesEnum = new ArrayList<>();

    public static Set<String> nombresAnotaciones(NodeList<AnnotationExpr> anns) {
        Set<String> r = new LinkedHashSet<>();
        for (AnnotationExpr a : anns) {
            String n = a.getNameAsString();
            int i = n.lastIndexOf('.');
            r.add(i >= 0 ? n.substring(i + 1) : n);
        }
        return r;
    }

    public boolean tieneAnotacion(String... nombres) {
        for (String n : nombres) {
            if (anotaciones.contains(n)) {
                return true;
            }
        }
        return false;
    }

    public boolean lombok(String n) {
        return anotaciones.contains(n);
    }

    public List<InfoMetodo> metodosPublicos() {
        List<InfoMetodo> r = new ArrayList<>();
        for (InfoMetodo m : metodos) {
            if (m.publico && !m.estatico && !m.abstracto) {
                r.add(m);
            }
        }
        return r;
    }

    public List<InfoMetodo> metodosNoPrivados() {
        List<InfoMetodo> r = new ArrayList<>();
        for (InfoMetodo m : metodos) {
            if (!m.privado && !m.abstracto) {
                r.add(m);
            }
        }
        return r;
    }

    public InfoCampo campo(String nombre) {
        for (InfoCampo c : campos) {
            if (c.nombre().equals(nombre)) {
                return c;
            }
        }
        return null;
    }

    public boolean tieneMain() {
        for (InfoMetodo m : metodos) {
            if (m.nombre.equals("main") && m.estatico && m.publico && m.parametros.size() == 1
                    && m.parametros.get(0).tipo().replace(" ", "").matches("String(\\[]|\\.\\.\\.)")) {
                return true;
            }
        }
        return false;
    }

    /** Construye la info a partir de una declaración de tipo de primer nivel. */
    public static InfoClase de(CompilationUnit cu, TypeDeclaration<?> td, Path archivo, Path raiz, boolean esTest) {
        InfoClase c = new InfoClase();
        c.cu = cu;
        c.decl = td;
        c.archivo = archivo;
        c.raizFuentes = raiz;
        c.esTest = esTest;
        c.simple = td.getNameAsString();
        c.paquete = cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
        c.fqn = c.paquete.isEmpty() ? c.simple : c.paquete + "." + c.simple;
        for (ImportDeclaration i : cu.getImports()) {
            String n = i.getNameAsString();
            if (i.isStatic()) {
                c.importsEstaticos.add(n + (i.isAsterisk() ? ".*" : ""));
            } else if (i.isAsterisk()) {
                c.importsComodin.add(n);
            } else {
                c.imports.add(n);
            }
        }
        c.anotaciones.addAll(nombresAnotaciones(td.getAnnotations()));
        if (td instanceof ClassOrInterfaceDeclaration ci) {
            c.tipo = ci.isInterface() ? Tipo.INTERFAZ : Tipo.CLASE;
            c.abstracta = ci.isAbstract();
            for (ClassOrInterfaceType t : ci.getExtendedTypes()) {
                c.extiende.add(t.getNameAsString());
            }
            for (ClassOrInterfaceType t : ci.getImplementedTypes()) {
                c.implementa.add(t.getNameAsString());
            }
        } else if (td instanceof EnumDeclaration ed) {
            c.tipo = Tipo.ENUM;
            ed.getEntries().forEach(e -> c.constantesEnum.add(e.getNameAsString()));
            for (ClassOrInterfaceType t : ed.getImplementedTypes()) {
                c.implementa.add(t.getNameAsString());
            }
        } else if (td instanceof AnnotationDeclaration) {
            c.tipo = Tipo.ANOTACION;
        } else if (td instanceof RecordDeclaration rd) {
            c.tipo = Tipo.RECORD;
            InfoConstructor canon = new InfoConstructor();
            rd.getParameters().forEach(p -> {
                canon.parametros.add(new Parametro(p.getTypeAsString(), p.getNameAsString(), nombresAnotaciones(p.getAnnotations())));
                c.campos.add(new InfoCampo(p.getNameAsString(), p.getTypeAsString(), nombresAnotaciones(p.getAnnotations()),
                        false, true, null));
            });
            canon.lombok = null;
            c.constructores.add(canon);
            for (ClassOrInterfaceType t : rd.getImplementedTypes()) {
                c.implementa.add(t.getNameAsString());
            }
        } else {
            c.tipo = Tipo.CLASE;
        }
        for (BodyDeclaration<?> m : td.getMembers()) {
            if (m instanceof FieldDeclaration f) {
                Set<String> anns = nombresAnotaciones(f.getAnnotations());
                for (VariableDeclarator v : f.getVariables()) {
                    c.campos.add(new InfoCampo(v.getNameAsString(), v.getTypeAsString(), anns, f.isStatic(), f.isFinal(),
                            v.getInitializer().orElse(null)));
                }
            } else if (m instanceof MethodDeclaration md) {
                c.metodos.add(new InfoMetodo(md));
            } else if (m instanceof ConstructorDeclaration cd) {
                InfoConstructor ic = new InfoConstructor();
                ic.decl = cd;
                ic.privado = cd.isPrivate();
                ic.anotaciones.addAll(nombresAnotaciones(cd.getAnnotations()));
                cd.getParameters().forEach(p -> ic.parametros.add(new Parametro(p.getTypeAsString(), p.getNameAsString(),
                        nombresAnotaciones(p.getAnnotations()))));
                for (AssignExpr a : cd.getBody().findAll(AssignExpr.class)) {
                    String campo = null;
                    if (a.getTarget() instanceof FieldAccessExpr fa && fa.getScope() instanceof ThisExpr) {
                        campo = fa.getNameAsString();
                    } else if (a.getTarget() instanceof NameExpr ne) {
                        campo = ne.getNameAsString();
                    }
                    if (campo != null && a.getValue() instanceof NameExpr val) {
                        String param = val.getNameAsString();
                        boolean esParam = ic.parametros.stream().anyMatch(p -> p.nombre().equals(param));
                        if (esParam) {
                            ic.asignaciones.put(param, campo);
                            ic.almacenados.add(param);
                        }
                    } else if (campo != null) {
                        String param = parametroNoNulo(a.getValue());
                        if (param != null && ic.parametros.stream().anyMatch(p -> p.nombre().equals(param))) {
                            ic.almacenados.add(param);
                        }
                    }
                }
                for (ExplicitConstructorInvocationStmt inv : cd.getBody().findAll(ExplicitConstructorInvocationStmt.class)) {
                    for (Expression arg : inv.getArguments()) {
                        if (arg instanceof NameExpr ne && ic.parametros.stream().anyMatch(p -> p.nombre().equals(ne.getNameAsString()))) {
                            ic.almacenados.add(ne.getNameAsString());
                        }
                    }
                }
                c.constructores.add(ic);
            }
        }
        anadirConstructoresLombok(c);
        return c;
    }

    /** Métodos de comprobación de nulo cuyo primer argumento se devuelve tal cual. */
    public static final Set<String> NO_NULOS = Set.of("requireNonNull", "requireNonNullElse", "notNull", "checkNotNull");

    /** Si la expresión es {@code X.requireNonNull(param, ...)} (o equivalente), el nombre del parámetro; si no, null. */
    public static String parametroNoNulo(Expression e) {
        if (e instanceof MethodCallExpr mc && NO_NULOS.contains(mc.getNameAsString()) && !mc.getArguments().isEmpty()
                && mc.getArgument(0) instanceof NameExpr ne) {
            return ne.getNameAsString();
        }
        return null;
    }

    private static void anadirConstructoresLombok(InfoClase c) {
        if (c.tipo != Tipo.CLASE) {
            return;
        }
        boolean explicito = c.constructores.stream().anyMatch(k -> k.lombok == null);
        if (c.lombok("RequiredArgsConstructor") || (c.lombok("Data") && !explicito && !c.lombok("NoArgsConstructor")
                && !c.lombok("AllArgsConstructor"))) {
            InfoConstructor k = new InfoConstructor();
            k.lombok = c.lombok("RequiredArgsConstructor") ? "RequiredArgsConstructor" : "Data";
            for (InfoCampo f : c.campos) {
                if (!f.estatico() && f.inicializador() == null && (f.esFinal() || f.anotaciones().contains("NonNull"))) {
                    k.parametros.add(new Parametro(f.tipo(), f.nombre(), f.anotaciones()));
                    k.asignaciones.put(f.nombre(), f.nombre());
                }
            }
            c.constructores.add(k);
        }
        if (c.lombok("AllArgsConstructor") || c.lombok("Value") || (c.lombok("Builder") && !explicito
                && !c.lombok("AllArgsConstructor"))) {
            InfoConstructor k = new InfoConstructor();
            k.lombok = c.lombok("AllArgsConstructor") ? "AllArgsConstructor" : c.lombok("Value") ? "Value" : "Builder";
            for (InfoCampo f : c.campos) {
                if (!f.estatico() && !(f.esFinal() && f.inicializador() != null)) {
                    k.parametros.add(new Parametro(f.tipo(), f.nombre(), f.anotaciones()));
                    k.asignaciones.put(f.nombre(), f.nombre());
                }
            }
            c.constructores.add(k);
        }
        if (c.lombok("NoArgsConstructor")) {
            InfoConstructor k = new InfoConstructor();
            k.lombok = "NoArgsConstructor";
            c.constructores.add(k);
        }
    }

    /** Getters/setters que genera Lombok (para las notas de tipos auxiliares). */
    public List<String> notasLombok() {
        List<String> r = new ArrayList<>();
        boolean getters = lombok("Data") || lombok("Getter") || lombok("Value");
        boolean setters = lombok("Data") || lombok("Setter");
        if (getters) {
            r.add("Lombok genera getters para todos los campos (getX()/isX()).");
        }
        if (setters) {
            r.add("Lombok genera setters para los campos no finales (setX(valor)).");
        }
        if (lombok("Builder")) {
            r.add("Lombok @Builder: " + simple + ".builder().campo(valor).build().");
        }
        for (InfoConstructor k : constructores) {
            if (k.lombok != null) {
                r.add("Constructor Lombok " + k.firma());
            }
        }
        return r;
    }

    @Override
    public String toString() {
        return fqn;
    }
}
