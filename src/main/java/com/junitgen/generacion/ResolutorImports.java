package com.junitgen.generacion;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.junitgen.escaneo.IndiceTipos;
import com.junitgen.escaneo.InfoClase;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Resuelve nombres simples a imports: imports del spec &gt; imports del archivo del servicio
 * (incluidos los comodín) &gt; nombre simple único en el índice &gt; tabla JDK.
 */
public class ResolutorImports {

    public static final Set<String> JAVA_LANG = Set.of("String", "Object", "Integer", "Long", "Double", "Float",
            "Boolean", "Character", "Byte", "Short", "Math", "StrictMath", "System", "Exception", "RuntimeException",
            "IllegalArgumentException", "IllegalStateException", "NullPointerException", "UnsupportedOperationException",
            "ArithmeticException", "IndexOutOfBoundsException", "ArrayIndexOutOfBoundsException", "ClassCastException",
            "NumberFormatException", "SecurityException", "StringBuilder", "StringBuffer", "Thread", "Class", "Void",
            "Number", "Iterable", "Comparable", "Runnable", "Throwable", "Error", "AssertionError", "Enum", "Record",
            "Override", "Deprecated", "SuppressWarnings", "CharSequence", "Cloneable", "AutoCloseable",
            "InterruptedException", "CloneNotSupportedException", "ReflectiveOperationException",
            "NoSuchFieldException", "NoSuchMethodException", "ClassNotFoundException", "StackOverflowError",
            "OutOfMemoryError", "ArrayStoreException", "NegativeArraySizeException", "StringIndexOutOfBoundsException");

    public static final Map<String, String> JDK = new LinkedHashMap<>();

    static {
        for (String s : List.of("List", "ArrayList", "LinkedList", "Map", "HashMap", "LinkedHashMap", "TreeMap", "Set",
                "HashSet", "LinkedHashSet", "TreeSet", "Optional", "OptionalInt", "OptionalLong", "OptionalDouble",
                "Collections", "Arrays", "Objects", "UUID", "Date", "Collection", "Iterator", "Locale", "Random",
                "Comparator", "Deque", "ArrayDeque", "Queue", "Calendar", "GregorianCalendar", "Properties",
                "StringJoiner", "Base64", "Currency", "EnumMap", "EnumSet", "Stack", "Vector", "Hashtable",
                "NoSuchElementException", "ConcurrentModificationException", "TimeZone", "BitSet", "PriorityQueue",
                "SortedMap", "SortedSet", "NavigableMap", "NavigableSet", "AbstractMap")) {
            JDK.put(s, "java.util." + s);
        }
        for (String s : List.of("Function", "Supplier", "Consumer", "Predicate", "BiFunction", "BiConsumer",
                "UnaryOperator", "BinaryOperator")) {
            JDK.put(s, "java.util.function." + s);
        }
        for (String s : List.of("Stream", "Collectors", "IntStream", "LongStream")) {
            JDK.put(s, "java.util.stream." + s);
        }
        for (String s : List.of("LocalDate", "LocalDateTime", "LocalTime", "Instant", "Duration", "Period",
                "ZonedDateTime", "ZoneId", "ZoneOffset", "OffsetDateTime", "Clock", "DayOfWeek", "Month", "Year",
                "YearMonth")) {
            JDK.put(s, "java.time." + s);
        }
        JDK.put("DateTimeFormatter", "java.time.format.DateTimeFormatter");
        JDK.put("ChronoUnit", "java.time.temporal.ChronoUnit");
        for (String s : List.of("BigDecimal", "BigInteger", "RoundingMode")) {
            JDK.put(s, "java.math." + s);
        }
        for (String s : List.of("CompletableFuture", "TimeUnit", "ConcurrentHashMap", "ExecutorService", "Executors",
                "Callable", "Future", "CopyOnWriteArrayList", "ExecutionException", "TimeoutException")) {
            JDK.put(s, "java.util.concurrent." + s);
        }
        for (String s : List.of("AtomicInteger", "AtomicLong", "AtomicBoolean", "AtomicReference")) {
            JDK.put(s, "java.util.concurrent.atomic." + s);
        }
        for (String s : List.of("Path", "Paths", "Files")) {
            JDK.put(s, "java.nio.file." + s);
        }
        for (String s : List.of("IOException", "File", "InputStream", "OutputStream", "ByteArrayInputStream",
                "ByteArrayOutputStream", "UncheckedIOException", "Serializable", "FileNotFoundException")) {
            JDK.put(s, "java.io." + s);
        }
        JDK.put("StandardCharsets", "java.nio.charset.StandardCharsets");
        JDK.put("Timestamp", "java.sql.Timestamp");
        JDK.put("URI", "java.net.URI");
        JDK.put("URL", "java.net.URL");
        JDK.put("Pattern", "java.util.regex.Pattern");
        JDK.put("Field", "java.lang.reflect.Field");
        for (String s : List.of("ArgumentCaptor", "Mockito", "MockedStatic", "MockedConstruction", "ArgumentMatchers",
                "InOrder", "Mock", "InjectMocks", "Spy", "Captor", "MockitoAnnotations")) {
            JDK.put(s, "org.mockito." + s);
        }
        JDK.put("Answer", "org.mockito.stubbing.Answer");
        JDK.put("InvocationOnMock", "org.mockito.invocation.InvocationOnMock");
    }

    private final IndiceTipos idx;
    private final InfoClase servicio;
    private final List<String> importsSpec;

    public ResolutorImports(IndiceTipos idx, InfoClase servicio, List<String> importsSpec) {
        this.idx = idx;
        this.servicio = servicio;
        this.importsSpec = importsSpec == null ? List.of() : importsSpec;
    }

    /**
     * FQN a importar; "" si no hace falta import (java.lang o mismo paquete que el servicio); null si no
     * se puede resolver.
     */
    public String resolver(String simple) {
        if (simple == null || simple.isEmpty()) {
            return "";
        }
        if (JAVA_LANG.contains(simple) || Character.isLowerCase(simple.charAt(0))) {
            return "";
        }
        for (String i : importsSpec) {
            String imp = i.startsWith("static ") ? null : i.trim();
            if (imp != null && (imp.endsWith("." + simple))) {
                return imp;
            }
        }
        if (simple.equals(servicio.simple)) {
            return "";
        }
        InfoClase miembro = idx.miembro(simple, servicio);
        if (miembro != null && !miembro.decl.isPrivate()) {
            return miembro.fqn;
        }
        for (String imp : servicio.imports) {
            if (imp.endsWith("." + simple)) {
                return imp;
            }
        }
        String mismo = servicio.paquete.isEmpty() ? simple : servicio.paquete + "." + simple;
        if (idx.get(mismo) != null) {
            return "";
        }
        for (String pkg : servicio.importsComodin) {
            if (idx.get(pkg + "." + simple) != null) {
                return pkg + "." + simple;
            }
            String jdk = JDK.get(simple);
            if (jdk != null && jdk.equals(pkg + "." + simple)) {
                return jdk;
            }
        }
        String fqn = idx.resolverFqn(simple, null);
        if (fqn != null) {
            InfoClase c = idx.get(fqn);
            if (c != null && c.paquete.equals(servicio.paquete)) {
                return "";
            }
            return fqn;
        }
        String jdk = JDK.get(simple);
        if (jdk != null) {
            return jdk;
        }
        for (String i : importsSpec) {
            if (i.trim().endsWith(".*") && !i.startsWith("static ")) {
                return i.trim();
            }
        }
        return null;
    }

    /**
     * Nombres simples de tipos usados en un nodo (tipos, scopes en mayúscula de llamadas/campos/
     * method references), excluyendo variables conocidas. Incluye los argumentos genéricos a cualquier
     * profundidad ({@code List<X>}, {@code Map<K, V>}, {@code Optional<List<X>>}), los tipos de arrays
     * ({@code X[]}) y los límites de wildcards ({@code ? extends X}). De un tipo calificado
     * ({@code Map.Entry}) solo se toma la raíz.
     */
    public static Set<String> tiposUsados(Node n, Set<String> variables) {
        Set<String> r = new LinkedHashSet<>();
        for (ClassOrInterfaceType t : n.findAll(ClassOrInterfaceType.class)) {
            Node padre = t.getParentNode().orElse(null);
            if (padre instanceof ClassOrInterfaceType p && p.getScope().isPresent() && p.getScope().get() == t) {
                continue;
            }
            ClassOrInterfaceType raiz = t;
            while (raiz.getScope().isPresent()) {
                raiz = raiz.getScope().get();
            }
            String nombre = raiz.getNameAsString();
            if (!nombre.isEmpty() && Character.isUpperCase(nombre.charAt(0)) && !variables.contains(nombre)) {
                r.add(nombre);
            }
        }
        if (n instanceof NameExpr ne0) {
            anadirScope(ne0, r, variables, n);
        }
        for (NameExpr ne : n.findAll(NameExpr.class)) {
            anadirScope(ne, r, variables, null);
        }
        return r;
    }

    private static void anadirScope(NameExpr ne, Set<String> r, Set<String> variables, Node raiz) {
        String nombre = ne.getNameAsString();
        if (nombre.isEmpty() || !Character.isUpperCase(nombre.charAt(0)) || variables.contains(nombre)) {
            return;
        }
        Node p = ne.getParentNode().orElse(null);
        boolean esScope = (p instanceof MethodCallExpr mc && mc.getScope().isPresent() && mc.getScope().get() == ne)
                || (p instanceof FieldAccessExpr fa && fa.getScope() == ne)
                || (p instanceof MethodReferenceExpr mr && mr.getScope() == ne);
        if (esScope) {
            r.add(nombre);
        }
    }

    /** Importaciones comodín del archivo del servicio (último recurso para tipos externos). */
    public List<String> comodinesServicio() {
        return servicio.importsComodin;
    }
}
