package com.junitgen.escaneo;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Collectors;

/**
 * Índice propio de tipos (nombre simple → FQN, más imports), sin JavaSymbolSolver. Parsea fuentes
 * Java 1 a 21 con {@code LanguageLevel.BLEEDING_EDGE}; los archivos con errores se omiten con aviso.
 */
public class IndiceTipos {

    private final Map<String, InfoClase> porFqn = new LinkedHashMap<>();
    private final Map<String, List<InfoClase>> porSimple = new LinkedHashMap<>();
    /** Tipos anidados (miembro) por FQN «pkg.Externa.Interna»; no entran en porFqn/porSimple. */
    private final Map<String, InfoClase> anidadasPorFqn = new LinkedHashMap<>();
    /** FQN del contenedor → nombre simple → tipo miembro. */
    private final Map<String, Map<String, InfoClase>> miembros = new LinkedHashMap<>();
    /** Nombres simples de todos los tipos anidados (salida rápida de {@link #miembro}). */
    private final Set<String> nombresAnidados = new HashSet<>();
    private static final int MAX_PROFUNDIDAD = 5;
    public final List<String> avisos = new ArrayList<>();
    public int archivosMain;
    public int archivosTest;

    private static final ThreadLocal<JavaParser> PARSER = ThreadLocal.withInitial(() -> new JavaParser(config(
            ParserConfiguration.LanguageLevel.BLEEDING_EDGE)));
    private static final ThreadLocal<JavaParser> PARSER_RAW = ThreadLocal.withInitial(() -> new JavaParser(config(
            ParserConfiguration.LanguageLevel.RAW)));

    public static ParserConfiguration config(ParserConfiguration.LanguageLevel nivel) {
        ParserConfiguration c = new ParserConfiguration();
        c.setLanguageLevel(nivel);
        c.setAttributeComments(false);
        c.setCharacterEncoding(StandardCharsets.UTF_8);
        c.setStoreTokens(false);
        return c;
    }

    public static JavaParser parser() {
        return PARSER.get();
    }

    public static IndiceTipos construir(List<Path> dirsMain, List<Path> dirsTest) {
        IndiceTipos idx = new IndiceTipos();
        List<Object[]> archivos = new ArrayList<>();
        for (Path d : dirsMain) {
            for (Path f : listarJava(d, idx)) {
                archivos.add(new Object[]{f, d, Boolean.FALSE});
                idx.archivosMain++;
            }
        }
        for (Path d : dirsTest) {
            for (Path f : listarJava(d, idx)) {
                archivos.add(new Object[]{f, d, Boolean.TRUE});
                idx.archivosTest++;
            }
        }
        ConcurrentLinkedQueue<InfoClase> clases = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<String> avisos = new ConcurrentLinkedQueue<>();
        archivos.parallelStream().forEach(a -> {
            Path f = (Path) a[0];
            Path raiz = (Path) a[1];
            boolean test = (Boolean) a[2];
            CompilationUnit cu = parsear(f, avisos);
            if (cu == null) {
                return;
            }
            for (TypeDeclaration<?> td : cu.getTypes()) {
                try {
                    clases.add(InfoClase.de(cu, td, f, raiz, test));
                } catch (RuntimeException e) {
                    avisos.add("No se pudo analizar " + td.getNameAsString() + " en " + f + ": " + e.getMessage());
                }
            }
        });
        List<InfoClase> ordenadas = new ArrayList<>(clases);
        ordenadas.sort((x, y) -> x.fqn.compareTo(y.fqn));
        for (InfoClase c : ordenadas) {
            InfoClase previa = idx.porFqn.get(c.fqn);
            if (previa != null && !previa.esTest && c.esTest) {
                // La clase de main tiene prioridad sobre un homónimo en test.
                idx.porSimple.computeIfAbsent(c.simple, k -> new ArrayList<>()).add(c);
                continue;
            }
            idx.porFqn.put(c.fqn, c);
            idx.porSimple.computeIfAbsent(c.simple, k -> new ArrayList<>()).add(c);
        }
        List<String> av = new ArrayList<>(avisos);
        Collections.sort(av);
        idx.avisos.addAll(av);
        for (InfoClase c : new ArrayList<>(idx.porFqn.values())) {
            idx.registrarMiembros(c, 1);
        }
        return idx;
    }

    /** Registra (recursivamente, profundidad ≤ 5) los tipos miembro de {@code cont}. Solo durante construir. */
    private void registrarMiembros(InfoClase cont, int profundidad) {
        if (profundidad > MAX_PROFUNDIDAD || cont.decl == null) {
            return;
        }
        for (BodyDeclaration<?> m : cont.decl.getMembers()) {
            if (!(m instanceof TypeDeclaration<?> td)) {
                continue;
            }
            String fqn = cont.fqn + "." + td.getNameAsString();
            if (anidadasPorFqn.containsKey(fqn)) {
                continue;
            }
            InfoClase n;
            try {
                n = InfoClase.de(cont.cu, td, cont.archivo, cont.raizFuentes, cont.esTest);
            } catch (RuntimeException e) {
                avisos.add("No se pudo analizar el tipo anidado " + fqn + " en " + cont.archivo + ": " + e.getMessage());
                continue;
            }
            n.fqn = fqn;
            n.contenedora = cont;
            anidadasPorFqn.put(fqn, n);
            miembros.computeIfAbsent(cont.fqn, k -> new LinkedHashMap<>()).putIfAbsent(n.simple, n);
            nombresAnidados.add(n.simple);
            registrarMiembros(n, profundidad + 1);
        }
    }

    /** Tipo anidado por su FQN «pkg.Externa.Interna», o null. */
    public InfoClase anidada(String fqn) {
        return fqn == null ? null : anidadasPorFqn.get(fqn);
    }

    /**
     * Tipo miembro de nombre {@code simple} visible desde {@code ctx}: propio, de sus contenedores o heredado
     * (no privado) de sus supertipos del proyecto ({@code extiende} + {@code implementa}, en anchura, ≤ 5 niveles).
     * Devuelve el tipo que lo declara; null si no hay.
     */
    public InfoClase miembro(String simple, InfoClase ctx) {
        if (simple == null || ctx == null || !nombresAnidados.contains(simple)) {
            return null;
        }
        Set<String> visitados = new HashSet<>();
        int profundidad = 0;
        for (InfoClase cadena = ctx; cadena != null && profundidad <= MAX_PROFUNDIDAD; cadena = cadena.contenedora, profundidad++) {
            InfoClase r = miembroEnJerarquia(simple, cadena, visitados);
            if (r != null) {
                return r;
            }
        }
        return null;
    }

    private InfoClase miembroEnJerarquia(String simple, InfoClase inicio, Set<String> visitados) {
        List<InfoClase> nivel = List.of(inicio);
        for (int p = 0; p <= MAX_PROFUNDIDAD && !nivel.isEmpty(); p++) {
            List<InfoClase> siguiente = new ArrayList<>();
            for (InfoClase t : nivel) {
                if (!visitados.add(t.fqn)) {
                    continue;
                }
                Map<String, InfoClase> ms = miembros.get(t.fqn);
                InfoClase m = ms == null ? null : ms.get(simple);
                if (m != null && (p == 0 || !m.decl.isPrivate())) {
                    return m;
                }
                List<String> supertipos = new ArrayList<>(t.extiende);
                supertipos.addAll(t.implementa);
                for (String s : supertipos) {
                    String fqn = resolverFqn(s, t, false);
                    InfoClase st = fqn == null ? null : porFqn.get(fqn);
                    if (st == null && fqn != null) {
                        st = anidadasPorFqn.get(fqn);
                    }
                    if (st != null) {
                        siguiente.add(st);
                    }
                }
            }
            nivel = siguiente;
        }
        return null;
    }

    /** Tipo anidado del proyecto para el tipo escrito en {@code ctx}, o null. */
    public InfoClase resolverAnidada(String tipo, InfoClase ctx) {
        String fqn = resolverFqn(tipo, ctx);
        return fqn == null ? null : anidadasPorFqn.get(fqn);
    }

    public static CompilationUnit parsear(Path f, java.util.Collection<String> avisos) {
        try {
            if (Files.size(f) > 3_000_000) {
                avisos.add("Fuente omitida por tamaño (> 3 MB): " + f);
                return null;
            }
            ParseResult<CompilationUnit> r = PARSER.get().parse(f);
            if (r.isSuccessful() && r.getResult().isPresent()) {
                return r.getResult().get();
            }
            ParseResult<CompilationUnit> r2 = PARSER_RAW.get().parse(f);
            if (r2.isSuccessful() && r2.getResult().isPresent()) {
                return r2.getResult().get();
            }
            String problema = r.getProblems().isEmpty() ? "error de sintaxis" : r.getProblems().get(0).getVerboseMessage();
            avisos.add("Fuente omitida por error de sintaxis: " + f + " → " + primeraLinea(problema));
        } catch (IOException | RuntimeException e) {
            avisos.add("Fuente omitida (" + e.getClass().getSimpleName() + "): " + f + " → " + e.getMessage());
        }
        return null;
    }

    private static String primeraLinea(String s) {
        int i = s.indexOf('\n');
        return i >= 0 ? s.substring(0, i) : s;
    }

    private static List<Path> listarJava(Path dir, IndiceTipos idx) {
        return listarJava(dir, idx.avisos);
    }

    /** Archivos .java de {@code dir} (sin enlaces ni module-info/package-info), ordenados; solo lee. */
    static List<Path> listarJava(Path dir) {
        return listarJava(dir, new ArrayList<>());
    }

    private static List<Path> listarJava(Path dir, Collection<String> avisos) {
        List<Path> r = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return r;
        }
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes attrs) {
                    if (!d.equals(dir) && com.junitgen.seguridad.RutaSegura.esEnlace(d)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (attrs.isRegularFile() && file.getFileName().toString().endsWith(".java")
                            && !file.getFileName().toString().equals("module-info.java")
                            && !file.getFileName().toString().equals("package-info.java")) {
                        r.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    avisos.add("No se pudo leer " + file + ": " + exc.getMessage());
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            avisos.add("No se pudo recorrer " + dir + ": " + e.getMessage());
        }
        Collections.sort(r);
        return r;
    }

    public InfoClase get(String fqn) {
        return fqn == null ? null : porFqn.get(fqn);
    }

    public List<InfoClase> porNombreSimple(String simple) {
        return porSimple.getOrDefault(simple, List.of());
    }

    public List<InfoClase> clasesMain() {
        return porFqn.values().stream().filter(c -> !c.esTest).collect(Collectors.toList());
    }

    public List<InfoClase> clasesTest() {
        return porFqn.values().stream().filter(c -> c.esTest).collect(Collectors.toList());
    }

    public List<InfoClase> todas() {
        return new ArrayList<>(porFqn.values());
    }

    /**
     * Resuelve un nombre de tipo escrito en {@code ctx} a FQN (proyecto o externo vía import). Devuelve
     * null si no se puede determinar.
     */
    public String resolverFqn(String tipo, InfoClase ctx) {
        return resolverFqn(tipo, ctx, true);
    }

    /** {@code conMiembros}: antes de los imports, prueba los tipos miembro visibles desde {@code ctx}. */
    private String resolverFqn(String tipo, InfoClase ctx, boolean conMiembros) {
        if (tipo == null) {
            return null;
        }
        String t = com.junitgen.util.Textos.quitarGenericos(tipo).replace("[]", "").replace("...", "").trim();
        if (t.isEmpty()) {
            return null;
        }
        if (porFqn.containsKey(t)) {
            return t;
        }
        String primero = t.contains(".") ? t.substring(0, t.indexOf('.')) : t;
        if (t.contains(".") && Character.isLowerCase(t.charAt(0))) {
            return t;
        }
        if (conMiembros && ctx != null) {
            InfoClase m = miembro(primero, ctx);
            if (m != null) {
                return t.contains(".") ? m.fqn + t.substring(primero.length()) : m.fqn;
            }
        }
        if (ctx != null) {
            for (String imp : ctx.imports) {
                if (imp.endsWith("." + primero) || imp.equals(primero)) {
                    return t.contains(".") ? imp + t.substring(primero.length()) : imp;
                }
            }
            String mismoPaquete = ctx.paquete.isEmpty() ? primero : ctx.paquete + "." + primero;
            if (porFqn.containsKey(mismoPaquete)) {
                return t.contains(".") ? mismoPaquete + t.substring(primero.length()) : mismoPaquete;
            }
            for (String pkg : ctx.importsComodin) {
                if (porFqn.containsKey(pkg + "." + primero)) {
                    return pkg + "." + primero;
                }
            }
        }
        List<InfoClase> cands = porSimple.getOrDefault(primero, List.of());
        List<InfoClase> main = cands.stream().filter(c -> !c.esTest).collect(Collectors.toList());
        if (main.size() == 1) {
            return main.get(0).fqn;
        }
        if (main.isEmpty() && cands.size() == 1) {
            return cands.get(0).fqn;
        }
        return null;
    }

    /** InfoClase del proyecto para el tipo escrito en {@code ctx}, o null si es externo/desconocido. */
    public InfoClase resolverClase(String tipo, InfoClase ctx) {
        String fqn = resolverFqn(tipo, ctx);
        return fqn == null ? null : porFqn.get(fqn);
    }

    /** Clases que implementan (directamente) la interfaz indicada. */
    public List<InfoClase> implementaciones(InfoClase interfaz) {
        List<InfoClase> r = new ArrayList<>();
        for (InfoClase c : porFqn.values()) {
            if (c.esTest || c.tipo != InfoClase.Tipo.CLASE) {
                continue;
            }
            for (String i : c.implementa) {
                if (i.equals(interfaz.simple) && interfaz.fqn.equals(resolverFqn(i, c))) {
                    r.add(c);
                }
            }
        }
        return r;
    }
}
