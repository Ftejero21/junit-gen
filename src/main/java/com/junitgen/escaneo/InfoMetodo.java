package com.junitgen.escaneo;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.SwitchExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.SwitchEntry;
import com.github.javaparser.ast.stmt.SwitchStmt;
import com.github.javaparser.ast.stmt.ThrowStmt;
import com.github.javaparser.ast.stmt.WhileStmt;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Método de una clase del índice. */
public class InfoMetodo {

    public final String nombre;
    public final String tipoRetorno;
    public final List<InfoClase.Parametro> parametros;
    public final Set<String> anotaciones;
    public final boolean publico;
    public final boolean privado;
    public final boolean estatico;
    public final boolean abstracto;
    public final MethodDeclaration decl;
    private Integer ramasMemo;
    private String cuerpoCompactoMemo;

    public InfoMetodo(MethodDeclaration md) {
        this.decl = md;
        this.nombre = md.getNameAsString();
        this.tipoRetorno = md.getTypeAsString();
        this.parametros = md.getParameters().stream()
                .map(p -> new InfoClase.Parametro(p.getTypeAsString() + (p.isVarArgs() ? "..." : ""), p.getNameAsString(),
                        InfoClase.nombresAnotaciones(p.getAnnotations())))
                .collect(Collectors.toList());
        this.anotaciones = InfoClase.nombresAnotaciones(md.getAnnotations());
        this.publico = md.isPublic();
        this.privado = md.isPrivate();
        this.estatico = md.isStatic();
        this.abstracto = md.getBody().isEmpty();
    }

    public boolean esVoid() {
        return "void".equals(tipoRetorno);
    }

    public int aridad() {
        return parametros.size();
    }

    public List<String> tiposParametros() {
        return parametros.stream().map(InfoClase.Parametro::tipo).collect(Collectors.toList());
    }

    public String firma() {
        return tipoRetorno + " " + nombre + "(" + parametros.stream().map(p -> p.tipo() + " " + p.nombre())
                .collect(Collectors.joining(", ")) + ")";
    }

    /** Clave nombre + tipos simples sin genéricos, p. ej. {@code accept(Long,Long)}. */
    public String clave() {
        return nombre + "(" + parametros.stream().map(p -> com.junitgen.util.Textos.nombreSimple(p.tipo()))
                .collect(Collectors.joining(",")) + ")";
    }

    public String cuerpo() {
        return decl.getBody().map(BlockStmt::toString).orElse(";");
    }

    /**
     * Cuerpo compacto para enviar a la IA: sin comentarios, sin sentencias de log ({@code log}/{@code logger}
     * o {@code System.out}/{@code System.err}), sin líneas en blanco y con un espacio de sangría por cada
     * cuatro. Se calcula una vez (las partes paginadas son estables entre llamadas).
     */
    public synchronized String cuerpoCompacto() {
        if (cuerpoCompactoMemo == null) {
            if (decl.getBody().isEmpty()) {
                cuerpoCompactoMemo = ";";
            } else {
                BlockStmt b = decl.getBody().get().clone();
                for (com.github.javaparser.ast.comments.Comment c : b.getAllContainedComments()) {
                    c.remove();
                }
                b.getComment().ifPresent(com.github.javaparser.ast.comments.Comment::remove);
                for (ExpressionStmt es : b.findAll(ExpressionStmt.class)) {
                    if (esLog(es)) {
                        es.remove();
                    }
                }
                StringBuilder sb = new StringBuilder();
                for (String linea : b.toString().split("\\R")) {
                    if (linea.isBlank()) {
                        continue;
                    }
                    int i = 0;
                    while (i < linea.length() && linea.charAt(i) == ' ') {
                        i++;
                    }
                    if (sb.length() > 0) {
                        sb.append('\n');
                    }
                    sb.append(" ".repeat(i / 4)).append(linea.substring(i));
                }
                cuerpoCompactoMemo = sb.toString();
            }
        }
        return cuerpoCompactoMemo;
    }

    private static boolean esLog(ExpressionStmt es) {
        if (!(es.getExpression() instanceof MethodCallExpr mc) || mc.getScope().isEmpty()) {
            return false;
        }
        var scope = mc.getScope().get();
        if (scope instanceof NameExpr n) {
            String nom = n.getNameAsString().toLowerCase(java.util.Locale.ROOT);
            return (nom.equals("log") || nom.equals("logger"))
                    && mc.getNameAsString().matches("trace|debug|info|warn|error");
        }
        if (scope instanceof FieldAccessExpr f && f.getScope() instanceof NameExpr n
                && n.getNameAsString().equals("System")) {
            return f.getNameAsString().equals("out") || f.getNameAsString().equals("err");
        }
        return false;
    }

    /**
     * Motivo por el que la función es trivial y no merece tests propios, o {@code null} si no lo es: accessor,
     * vacía, delegación directa con los mismos parámetros o una línea sin lógica.
     */
    public String motivoTrivial() {
        return motivoTrivial(Set.of());
    }

    /**
     * Como {@link #motivoTrivial()}, pero una delegación directa sin scope o con {@code this} a un método
     * interno cuyo nombre está en {@code internosConLogica} no es trivial (la lógica vive en ese interno).
     */
    public String motivoTrivial(Set<String> internosConLogica) {
        if (esAccessor()) {
            return "accessor (getter/setter/toString/equals/hashCode)";
        }
        if (decl.getBody().isEmpty()) {
            return null;
        }
        BlockStmt b = decl.getBody().get();
        if (b.getStatements().isEmpty()) {
            return "vacía";
        }
        if (b.getStatements().size() != 1) {
            return null;
        }
        var st = b.getStatement(0);
        com.github.javaparser.ast.expr.Expression e = null;
        if (st instanceof ReturnStmt r) {
            e = r.getExpression().orElse(null);
        } else if (st instanceof ExpressionStmt es) {
            e = es.getExpression();
        }
        if (e instanceof MethodCallExpr mc && esDelegacionDirecta(mc)) {
            boolean interno = mc.getScope().isEmpty() || mc.getScope().get() instanceof ThisExpr;
            if (interno && internosConLogica != null && internosConLogica.contains(mc.getNameAsString())) {
                return null;
            }
            return "delegación directa";
        }
        if (st instanceof ThrowStmt || !st.findAll(ThrowStmt.class).isEmpty()) {
            return null;
        }
        if (st.findAll(MethodCallExpr.class).isEmpty()
                && st.findAll(com.github.javaparser.ast.expr.ObjectCreationExpr.class).isEmpty()
                && st.findAll(com.github.javaparser.ast.expr.BinaryExpr.class).isEmpty()
                && st.findAll(com.github.javaparser.ast.expr.LambdaExpr.class).isEmpty()
                && st.findAll(ConditionalExpr.class).isEmpty()) {
            return "una línea sin lógica";
        }
        return null;
    }

    private boolean esDelegacionDirecta(MethodCallExpr mc) {
        if (mc.getScope().isPresent()) {
            var s = mc.getScope().get();
            if (!(s instanceof NameExpr || s instanceof ThisExpr || s instanceof com.github.javaparser.ast.expr.SuperExpr
                    || s instanceof FieldAccessExpr)) {
                return false;
            }
        }
        if (mc.getArguments().size() != parametros.size()) {
            return false;
        }
        for (int i = 0; i < parametros.size(); i++) {
            var a = mc.getArgument(i);
            if (!(a instanceof NameExpr n) || !n.getNameAsString().equals(parametros.get(i).nombre())) {
                return false;
            }
        }
        return true;
    }

    /** Getter/setter/is trivial: una sentencia que devuelve o asigna un campo. */
    public boolean esAccessor() {
        if (decl.getBody().isEmpty()) {
            return false;
        }
        BlockStmt b = decl.getBody().get();
        boolean nombreAccessor = (nombre.startsWith("get") || nombre.startsWith("is") || nombre.startsWith("set")
                || nombre.equals("toString") || nombre.equals("hashCode") || nombre.equals("equals"));
        if (!nombreAccessor) {
            return false;
        }
        if (nombre.equals("toString") || nombre.equals("hashCode") || nombre.equals("equals")) {
            return true;
        }
        if (b.getStatements().size() != 1) {
            return false;
        }
        var st = b.getStatement(0);
        if (st instanceof ReturnStmt r) {
            return r.getExpression().map(e -> e instanceof NameExpr || e instanceof FieldAccessExpr).orElse(false);
        }
        if (st instanceof ExpressionStmt es && es.getExpression() instanceof AssignExpr) {
            return true;
        }
        return false;
    }

    /**
     * Número de ramas del cuerpo: if, etiquetas de switch (no default), ternarios, bucles y throw; 0 sin
     * cuerpo. Se calcula una vez.
     */
    public synchronized int ramas() {
        if (ramasMemo == null) {
            if (decl.getBody().isEmpty()) {
                ramasMemo = 0;
            } else {
                Node b = decl.getBody().get();
                long n = b.findAll(IfStmt.class).size()
                        + b.findAll(SwitchEntry.class).stream().filter(e -> !e.getLabels().isEmpty()).count()
                        + b.findAll(ConditionalExpr.class).size() + b.findAll(ForStmt.class).size()
                        + b.findAll(ForEachStmt.class).size() + b.findAll(WhileStmt.class).size()
                        + b.findAll(DoStmt.class).size() + b.findAll(ThrowStmt.class).size();
                ramasMemo = (int) Math.min(Integer.MAX_VALUE, n);
            }
        }
        return ramasMemo;
    }

    /** Lógica real: if, switch, ternario, bucle, throw o llamada a un campo dependencia. */
    public boolean tieneLogica(Set<String> camposDependencia) {
        if (decl.getBody().isEmpty()) {
            return false;
        }
        Node b = decl.getBody().get();
        if (!b.findAll(IfStmt.class).isEmpty() || !b.findAll(SwitchStmt.class).isEmpty()
                || !b.findAll(SwitchExpr.class).isEmpty() || !b.findAll(ConditionalExpr.class).isEmpty()
                || !b.findAll(ForStmt.class).isEmpty() || !b.findAll(ForEachStmt.class).isEmpty()
                || !b.findAll(WhileStmt.class).isEmpty() || !b.findAll(DoStmt.class).isEmpty()
                || !b.findAll(ThrowStmt.class).isEmpty()) {
            return true;
        }
        for (MethodCallExpr mc : b.findAll(MethodCallExpr.class)) {
            if (mc.getScope().isPresent()) {
                var s = mc.getScope().get();
                if (s instanceof NameExpr n && camposDependencia.contains(n.getNameAsString())) {
                    return true;
                }
                if (s instanceof FieldAccessExpr f && f.getScope() instanceof ThisExpr
                        && camposDependencia.contains(f.getNameAsString())) {
                    return true;
                }
            }
        }
        return false;
    }
}
