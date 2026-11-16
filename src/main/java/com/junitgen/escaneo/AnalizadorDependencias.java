package com.junitgen.escaneo;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.comments.Comment;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.LiteralExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.NullLiteralExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ExplicitConstructorInvocationStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.junitgen.proyecto.VersionesProyecto;
import com.junitgen.util.Textos;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Clasifica cada dependencia de un servicio y calcula el modo de montaje del test. */
public final class AnalizadorDependencias {

    public static final Set<String> TIPOS_VALOR = Set.of("String", "int", "long", "double", "float", "boolean", "char",
            "byte", "short", "Integer", "Long", "Double", "Float", "Boolean", "Character", "Byte", "Short",
            "BigDecimal", "BigInteger", "Duration", "List", "Set", "Map", "Collection", "Optional", "CharSequence",
            "Object", "Iterable", "Number", "Path", "File", "URI", "URL", "Pattern", "Class");
    private static final Set<String> ANOT_CAMPO = Set.of("Autowired", "Inject", "Resource", "EJB", "PersistenceContext",
            "PersistenceUnit");
    private static final List<String> SUFIJOS_COLABORADOR = List.of("Service", "ServiceImpl", "Repository", "Repo", "Dao",
            "DAO", "Client", "Gateway", "Manager", "Helper", "Mapper", "Provider", "Factory", "Template", "Publisher",
            "Sender", "Validator", "Converter", "Handler", "Executor", "Encoder", "Facade", "Store", "Cache", "Resolver",
            "Clock", "Registry", "Engine", "Producer", "Consumer", "Notifier", "Processor", "Strategy", "Generator",
            "Calculator", "Parser", "Loader", "Writer", "Reader", "Utils", "Util", "UseCase", "Adapter", "Port",
            "Environment", "EntityManager", "DataSource", "ObjectMapper", "Bus", "Scheduler", "Storage", "Signer",
            "Verifier", "Checker", "Builder");
    /** Clientes externos (nombre simple sin genéricos) que, calculados en el constructor, se sustituyen por un mock. */
    public static final Set<String> CLIENTES_EXTERNOS = Set.of("RestTemplate", "RestOperations", "WebClient",
            "RestClient", "HttpClient", "CloseableHttpClient", "OkHttpClient", "JdbcTemplate",
            "NamedParameterJdbcTemplate", "JdbcOperations", "JdbcClient", "KafkaTemplate", "RabbitTemplate",
            "AmqpTemplate", "JmsTemplate", "RedisTemplate", "StringRedisTemplate", "MongoTemplate", "JavaMailSender",
            "EntityManager", "SimpMessagingTemplate");
    private static final Set<String> COMPROBACIONES_NULO = Set.of("requireNonNull", "requireNonNullElse", "notNull",
            "checkNotNull", "nonNull", "isNull");
    private static final int MAX_CUERPO_CONSTRUCTOR = 600;
    private static final Set<String> IMPURAS = Set.of("LocalDate.now", "LocalDateTime.now", "LocalTime.now",
            "Instant.now", "ZonedDateTime.now", "OffsetDateTime.now", "Year.now", "YearMonth.now",
            "System.currentTimeMillis", "System.nanoTime", "UUID.randomUUID", "ThreadLocalRandom.current", "Math.random");
    /** Contenedores de beans cuyo valor por defecto es una colección/Optional con el mock del elemento. */
    private static final Set<String> COLECCIONES_BEANS = Set.of("List", "Collection", "Set", "Iterable", "Map", "Optional");
    /** Contenedores de beans que siguen siendo mock y devuelven el mock del elemento. */
    private static final Set<String> PROVEEDORES_BEANS = Set.of("ObjectProvider", "Provider", "Supplier");
    private static final int MAX_NIVELES_HERENCIA = 5;
    private static final Pattern ENTERO = Pattern.compile("-?\\d+");
    private static final Pattern DECIMAL = Pattern.compile("-?\\d+(\\.\\d+)?([eE][-+]?\\d+)?");
    static final String NOTA_TIPO_FINAL = "tipo final: no mockeable sin mockito-inline; usa instancia real en construccion.argumentos";

    private AnalizadorDependencias() {
    }

    public static AnalisisServicio analizar(InfoClase c, IndiceTipos idx, VersionesProyecto v) {
        AnalisisServicio a = new AnalisisServicio();
        a.clase = c;
        a.permiteMockStatic = v.permiteMockStatic();
        a.permiteMockConstruction = v.permiteMockConstruction();
        Set<String> cubiertos = new LinkedHashSet<>();
        Map<Dependencia, String[]> contenedores = new LinkedHashMap<>();

        List<InfoClase.InfoConstructor> ks = new ArrayList<>(c.constructores);
        a.constructorSinArgs = ks.isEmpty() || ks.stream().anyMatch(k -> k.parametros.isEmpty() && !k.privado);
        InfoClase.InfoConstructor elegido = null;
        for (InfoClase.InfoConstructor k : ks) {
            if (k.anotaciones.contains("Autowired") || k.anotaciones.contains("Inject")) {
                elegido = k;
                break;
            }
        }
        if (elegido == null && ks.size() == 1) {
            elegido = ks.get(0);
        }
        if (elegido == null && !ks.isEmpty()) {
            for (InfoClase.InfoConstructor k : ks) {
                if (elegido == null || k.parametros.size() > elegido.parametros.size()) {
                    elegido = k;
                }
            }
        }
        a.constructor = elegido;
        if (elegido != null) {
            for (int i = 0; i < elegido.parametros.size(); i++) {
                InfoClase.Parametro p = elegido.parametros.get(i);
                Dependencia d = new Dependencia();
                String campo = elegido.asignaciones.get(p.nombre());
                d.nombre = campo != null ? campo : p.nombre();
                d.campo = campo;
                d.tipo = p.tipo();
                d.tipoFqn = idx.resolverFqn(p.tipo(), c);
                d.posicionConstructor = i;
                InfoClase.InfoCampo f = campo != null ? c.campo(campo) : null;
                boolean conValue = p.anotaciones().contains("Value") || (f != null && f.anotaciones().contains("Value"));
                String elemento = conValue ? null : tipoElemento(p.tipo(), c, idx);
                if (elemento != null && esColeccionBeans(p.tipo())) {
                    d.obtencion = Dependencia.TipoObtencion.VALOR_CONSTRUCTOR;
                    d.mockeable = false;
                    contenedores.put(d, new String[]{elemento, idx.resolverFqn(elemento, c)});
                } else if (conValue || esValor(p.tipo(), c, idx)) {
                    d.obtencion = Dependencia.TipoObtencion.VALOR_CONSTRUCTOR;
                    d.mockeable = false;
                    d.requiereValor = true;
                    d.nota = (conValue ? "@Value; " : "") + "aporta el literal en construccion.argumentos." + d.nombre;
                    if (conValue) {
                        AnnotationExpr av = elegido.decl != null && i < elegido.decl.getParameters().size()
                                ? anotacion(elegido.decl.getParameter(i).getAnnotations(), "Value") : null;
                        if (av == null && campo != null) {
                            av = anotacionCampo(c, campo, "Value");
                        }
                        if (av == null && elegido.lombok != null) {
                            av = anotacionCampo(c, p.nombre(), "Value");
                        }
                        aplicarDefectoValue(d, av, p.tipo(), c, idx);
                    }
                } else {
                    d.obtencion = Dependencia.TipoObtencion.CONSTRUCTOR;
                    d.mockeable = true;
                    d.nota = "parámetro " + (i + 1) + " del constructor" + (elegido.lombok != null ? " (Lombok @" + elegido.lombok + ")" : "");
                    if (elemento != null) {
                        contenedores.put(d, new String[]{elemento, idx.resolverFqn(elemento, c)});
                    }
                }
                a.dependencias.add(d);
                cubiertos.add(d.nombre);
            }
        }
        for (InfoMetodo m : c.metodos) {
            if (!m.nombre.startsWith("set") || m.nombre.length() < 4 || m.parametros.size() != 1 || m.estatico || m.privado) {
                continue;
            }
            String campo = Character.toLowerCase(m.nombre.charAt(3)) + m.nombre.substring(4);
            if (cubiertos.contains(campo)) {
                continue;
            }
            InfoClase.InfoCampo f = c.campo(campo);
            String propiedad = campo;
            if (f == null) {
                // Setter que asigna un campo de nombre distinto: la dependencia toma el nombre del campo.
                String asignado = campoAsignadoEnSetter(m, c);
                if (asignado != null) {
                    if (cubiertos.contains(asignado)) {
                        continue;
                    }
                    campo = asignado;
                    f = c.campo(asignado);
                }
            }
            String tipo = m.parametros.get(0).tipo();
            boolean anotado = m.anotaciones.stream().anyMatch(ANOT_CAMPO::contains);
            if (anotado || (f != null && f.inicializador() == null && !f.estatico() && esColaborador(tipo, c, idx))) {
                Dependencia d = new Dependencia();
                d.nombre = campo;
                d.campo = f != null ? campo : null;
                d.tipo = tipo;
                d.tipoFqn = idx.resolverFqn(tipo, c);
                d.obtencion = Dependencia.TipoObtencion.SETTER;
                d.setter = m.nombre;
                d.mockeable = !esValor(tipo, c, idx);
                d.nota = "se inyecta con " + m.nombre + "(...)";
                a.dependencias.add(d);
                cubiertos.add(campo);
                cubiertos.add(propiedad);
            }
        }
        Map<String, String> asignadosNewEnConstructor = new LinkedHashMap<>();
        for (InfoClase.InfoConstructor k : ks) {
            if (k.decl == null) {
                continue;
            }
            for (AssignExpr as : k.decl.getBody().findAll(AssignExpr.class)) {
                String campo = null;
                if (as.getTarget() instanceof FieldAccessExpr fa && fa.getScope() instanceof ThisExpr) {
                    campo = fa.getNameAsString();
                } else if (as.getTarget() instanceof NameExpr ne && c.campo(ne.getNameAsString()) != null) {
                    campo = ne.getNameAsString();
                }
                if (campo != null && as.getValue() instanceof ObjectCreationExpr oc) {
                    asignadosNewEnConstructor.put(campo, oc.getType().getNameAsString());
                }
            }
        }
        Map<String, Expression> asignadosEnElegido = asignacionesACampos(elegido, c);
        for (InfoClase.InfoCampo f : c.campos) {
            if (f.estatico() || cubiertos.contains(f.nombre())) {
                continue;
            }
            boolean anotado = f.anotaciones().stream().anyMatch(ANOT_CAMPO::contains);
            Dependencia d = new Dependencia();
            d.nombre = f.nombre();
            d.campo = f.nombre();
            d.tipo = f.tipo();
            d.tipoFqn = idx.resolverFqn(f.tipo(), c);
            if (f.anotaciones().contains("Value")) {
                d.obtencion = Dependencia.TipoObtencion.CAMPO_ANOTADO;
                d.mockeable = false;
                d.nota = "@Value en campo: valor opcional en construccion.argumentos." + f.nombre() + " (por reflexión)";
                aplicarDefectoValue(d, anotacionCampo(c, f.nombre(), "Value"), f.tipo(), c, idx);
            } else if (anotado) {
                d.obtencion = Dependencia.TipoObtencion.CAMPO_ANOTADO;
                d.mockeable = !esValor(f.tipo(), c, idx);
                d.nota = "@" + f.anotaciones().stream().filter(ANOT_CAMPO::contains).findFirst().orElse("") + " en campo";
                String elemento = tipoElemento(f.tipo(), c, idx);
                if (elemento != null) {
                    contenedores.put(d, new String[]{elemento, idx.resolverFqn(elemento, c)});
                }
            } else if (f.inicializador() instanceof ObjectCreationExpr oc
                    && esColaboradorProyecto(oc.getType().getNameAsString(), c, idx)) {
                d.obtencion = Dependencia.TipoObtencion.NEW_INTERNO;
                d.mockeable = true;
                d.nota = "campo inicializado con new " + oc.getType().getNameAsString() + "(...); el test lo sustituye por un mock por reflexión";
            } else if (asignadosNewEnConstructor.containsKey(f.nombre())
                    && esColaboradorProyecto(asignadosNewEnConstructor.get(f.nombre()), c, idx)) {
                d.obtencion = Dependencia.TipoObtencion.NEW_INTERNO;
                d.mockeable = true;
                d.nota = "creado con new en el constructor; el test lo sustituye por un mock por reflexión";
            } else if (esTipoDerivable(f.tipo(), c, idx) && esDerivado(f, asignadosEnElegido.get(f.nombre()), elegido)) {
                d.obtencion = Dependencia.TipoObtencion.CAMPO_DERIVADO;
                d.mockeable = true;
                d.derivaDe = parametrosEn(asignadosEnElegido.get(f.nombre()), elegido);
                d.nota = "calculado en el constructor/inicializador; el test lo sustituye por un mock por reflexión";
            } else if (f.inicializador() == null && !f.esFinal() && esColaborador(f.tipo(), c, idx)
                    && !asignadoEnMetodos(c, f.nombre())) {
                d.obtencion = Dependencia.TipoObtencion.CAMPO_SIN_ANOTAR;
                d.mockeable = true;
                d.nota = "campo sin anotar; inyección por reflexión";
            } else {
                continue;
            }
            a.dependencias.add(d);
            cubiertos.add(f.nombre());
        }
        analizarHerencia(c, idx, a, cubiertos, contenedores);
        crearElementos(a, c, v, contenedores);
        analizarCuerpos(c, idx, a, cubiertos);
        if (elegido != null && elegido.decl != null) {
            analizarConstructor(a, elegido, c, idx);
        }
        aplicarRecetas(a, c, idx, v);
        if (a.dependencias.stream().anyMatch(d -> d.obtencion == Dependencia.TipoObtencion.CAMPO_DERIVADO)) {
            a.constructorConLogica = true;
        }
        for (InfoMetodo m : c.metodos) {
            if (m.anotaciones.contains("PostConstruct") && m.parametros.isEmpty() && !m.estatico) {
                a.postConstruct.add(m.nombre);
            }
        }
        a.modo = calcularModo(a, ks);
        return a;
    }

    /**
     * Campo no estático (de nombre distinto a la propiedad) al que el setter asigna su parámetro
     * ({@code this.x = p}, {@code x = p} o {@code x = requireNonNull(p, ...)}); null si no hay.
     */
    private static String campoAsignadoEnSetter(InfoMetodo m, InfoClase c) {
        if (m.decl == null || m.decl.getBody().isEmpty()) {
            return null;
        }
        String param = m.parametros.get(0).nombre();
        for (AssignExpr as : m.decl.getBody().get().findAll(AssignExpr.class)) {
            if (as.getOperator() != AssignExpr.Operator.ASSIGN) {
                continue;
            }
            boolean desdeParam = (as.getValue() instanceof NameExpr v && v.getNameAsString().equals(param))
                    || param.equals(InfoClase.parametroNoNulo(as.getValue()));
            if (!desdeParam) {
                continue;
            }
            String x = null;
            if (as.getTarget() instanceof FieldAccessExpr fa && fa.getScope() instanceof ThisExpr) {
                x = fa.getNameAsString();
            } else if (as.getTarget() instanceof NameExpr ne && !ne.getNameAsString().equals(param)) {
                x = ne.getNameAsString();
            }
            InfoClase.InfoCampo f = x == null ? null : c.campo(x);
            if (f != null && !f.estatico()) {
                return x;
            }
        }
        return null;
    }

    /**
     * true si algún método sin {@code @PostConstruct} asigna el campo: {@code this.campo = ...} o
     * {@code campo = ...} sin parámetro ni variable local homónimos.
     */
    private static boolean asignadoEnMetodos(InfoClase c, String campo) {
        for (InfoMetodo m : c.metodos) {
            if (m.decl == null || m.anotaciones.contains("PostConstruct") || m.decl.getBody().isEmpty()) {
                continue;
            }
            Boolean sombreado = null;
            for (AssignExpr as : m.decl.getBody().get().findAll(AssignExpr.class)) {
                if (as.getTarget() instanceof FieldAccessExpr fa && fa.getScope() instanceof ThisExpr
                        && fa.getNameAsString().equals(campo)) {
                    return true;
                }
                if (as.getTarget() instanceof NameExpr ne && ne.getNameAsString().equals(campo)) {
                    if (sombreado == null) {
                        sombreado = m.decl.findAll(com.github.javaparser.ast.body.Parameter.class).stream()
                                .anyMatch(p -> p.getNameAsString().equals(campo))
                                || m.decl.findAll(VariableDeclarator.class).stream().anyMatch(v -> v.getNameAsString().equals(campo));
                    }
                    if (!sombreado) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** campo → expresión asignada en el constructor elegido (última asignación; ignora parámetros homónimos). */
    private static Map<String, Expression> asignacionesACampos(InfoClase.InfoConstructor k, InfoClase c) {
        Map<String, Expression> r = new LinkedHashMap<>();
        if (k == null || k.decl == null) {
            return r;
        }
        Set<String> params = nombresParametros(k);
        for (AssignExpr as : k.decl.getBody().findAll(AssignExpr.class)) {
            if (as.getOperator() != AssignExpr.Operator.ASSIGN) {
                continue;
            }
            String campo = null;
            if (as.getTarget() instanceof FieldAccessExpr fa && fa.getScope() instanceof ThisExpr) {
                campo = fa.getNameAsString();
            } else if (as.getTarget() instanceof NameExpr ne && !params.contains(ne.getNameAsString())
                    && c.campo(ne.getNameAsString()) != null) {
                campo = ne.getNameAsString();
            }
            if (campo != null) {
                r.put(campo, as.getValue());
            }
        }
        return r;
    }

    private static Set<String> nombresParametros(InfoClase.InfoConstructor k) {
        Set<String> r = new LinkedHashSet<>();
        if (k != null) {
            k.parametros.forEach(p -> r.add(p.nombre()));
        }
        return r;
    }

    private static boolean esTipoDerivable(String tipo, InfoClase c, IndiceTipos idx) {
        return CLIENTES_EXTERNOS.contains(Textos.nombreSimple(tipo))
                || (idx.resolverClase(tipo, c) != null && esColaborador(tipo, c, idx));
    }

    /** true si el campo se calcula en el constructor elegido o en su inicializador (no es un parámetro guardado). */
    private static boolean esDerivado(InfoClase.InfoCampo f, Expression asignada, InfoClase.InfoConstructor k) {
        if (asignada != null) {
            if (asignada instanceof LiteralExpr) {
                return false;
            }
            Set<String> params = nombresParametros(k);
            if (asignada instanceof NameExpr ne && params.contains(ne.getNameAsString())) {
                return false;
            }
            String noNulo = InfoClase.parametroNoNulo(asignada);
            return noNulo == null || !params.contains(noNulo);
        }
        return f.inicializador() instanceof ObjectCreationExpr || f.inicializador() instanceof MethodCallExpr;
    }

    private static List<String> parametrosEn(Expression e, InfoClase.InfoConstructor k) {
        List<String> r = new ArrayList<>();
        if (e == null || k == null) {
            return r;
        }
        Set<String> params = nombresParametros(k);
        List<NameExpr> nombres = new ArrayList<>(e.findAll(NameExpr.class));
        if (e instanceof NameExpr raiz && !nombres.contains(raiz)) {
            nombres.add(0, raiz);
        }
        for (NameExpr ne : nombres) {
            String p = ne.getNameAsString();
            // nombre de la dependencia del parámetro: el campo al que se asigna, o el propio parámetro
            String dep = k.asignaciones.getOrDefault(p, p);
            if (params.contains(p) && !r.contains(dep)) {
                r.add(dep);
            }
        }
        return r;
    }

    /** Usos de las dependencias en el constructor elegido: lógica, mocks profundos y cuerpo resumido. */
    private static void analizarConstructor(AnalisisServicio a, InfoClase.InfoConstructor elegido, InfoClase c,
                                            IndiceTipos idx) {
        BlockStmt cuerpo = elegido.decl.getBody();
        for (Dependencia d : a.dependencias) {
            if (d.obtencion != Dependencia.TipoObtencion.CONSTRUCTOR || !d.mockeable || d.posicionConstructor == null) {
                continue;
            }
            String param = elegido.parametros.get(d.posicionConstructor).nombre();
            boolean usada = false;
            boolean profundo = false;
            for (NameExpr ne : cuerpo.findAll(NameExpr.class)) {
                if (!ne.getNameAsString().equals(param) || !esUsoEnConstructor(ne)) {
                    continue;
                }
                usada = true;
                if (esLlamadaProfunda(ne, c, idx)) {
                    profundo = true;
                }
            }
            d.usadaEnConstructor = usada;
            d.profundo = usada && (profundo || !elegido.almacenados.contains(param));
        }
        a.constructorConLogica = a.dependencias.stream().anyMatch(d -> d.usadaEnConstructor)
                || a.dependencias.stream().anyMatch(d -> d.obtencion == Dependencia.TipoObtencion.CAMPO_DERIVADO);
        if (a.constructorConLogica) {
            a.cuerpoConstructor = resumirCuerpo(cuerpo, nombresParametros(elegido));
        }
    }

    /** true si el {@code NameExpr} del parámetro es un uso real (no guardarlo, comprobar nulo, this/super o lambda). */
    private static boolean esUsoEnConstructor(NameExpr ne) {
        Node padre = ne.getParentNode().orElse(null);
        if (padre instanceof AssignExpr as && as.getValue() == ne) {
            return false;
        }
        if (padre instanceof MethodCallExpr mc && COMPROBACIONES_NULO.contains(mc.getNameAsString())
                && mc.getArguments().stream().anyMatch(x -> x == ne)) {
            return false;
        }
        if (padre instanceof BinaryExpr be && (be.getOperator() == BinaryExpr.Operator.EQUALS
                || be.getOperator() == BinaryExpr.Operator.NOT_EQUALS)
                && (be.getLeft() instanceof NullLiteralExpr || be.getRight() instanceof NullLiteralExpr)) {
            return false;
        }
        if (padre instanceof ExplicitConstructorInvocationStmt) {
            return false;
        }
        Node n = padre;
        while (n != null && !(n instanceof ConstructorDeclaration)) {
            if (n instanceof LambdaExpr || n instanceof MethodReferenceExpr || n instanceof BodyDeclaration) {
                return false;
            }
            n = n.getParentNode().orElse(null);
        }
        return true;
    }

    /** Reglas (ii) y (iii): {@code p.m(..)} encadenada o guardada en un campo/variable de tipo no valor. */
    private static boolean esLlamadaProfunda(NameExpr ne, InfoClase c, IndiceTipos idx) {
        if (!(ne.getParentNode().orElse(null) instanceof MethodCallExpr mc) || mc.getScope().orElse(null) != ne) {
            return false;
        }
        Node padre = mc.getParentNode().orElse(null);
        if (padre instanceof MethodCallExpr sup && sup.getScope().orElse(null) == mc) {
            return true;
        }
        if (padre instanceof AssignExpr as && as.getValue() == mc) {
            String campo = as.getTarget() instanceof FieldAccessExpr fa && fa.getScope() instanceof ThisExpr ? fa.getNameAsString()
                    : as.getTarget() instanceof NameExpr t ? t.getNameAsString() : null;
            InfoClase.InfoCampo f = campo == null ? null : c.campo(campo);
            return f != null && !f.estatico() && !esValor(f.tipo(), c, idx);
        }
        if (padre instanceof VariableDeclarator vd && vd.getInitializer().orElse(null) == mc) {
            String tipo = vd.getTypeAsString();
            return tipo.equals("var") || !esValor(tipo, c, idx);
        }
        return false;
    }

    private static String resumirCuerpo(BlockStmt cuerpo, Set<String> params) {
        BlockStmt b = cuerpo.clone();
        for (Comment k : b.getAllContainedComments()) {
            k.remove();
        }
        StringBuilder sb = new StringBuilder();
        for (Statement st : b.getStatements()) {
            st.getComment().ifPresent(Comment::remove);
            if (esAsignacionSimple(st, params)) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(st.toString().replaceAll("\\s+", " ").trim());
        }
        if (sb.length() == 0) {
            return null;
        }
        return sb.length() > MAX_CUERPO_CONSTRUCTOR ? sb.substring(0, MAX_CUERPO_CONSTRUCTOR) : sb.toString();
    }

    private static boolean esAsignacionSimple(Statement st, Set<String> params) {
        if (!(st instanceof ExpressionStmt es) || !(es.getExpression() instanceof AssignExpr as)) {
            return false;
        }
        Expression v = as.getValue();
        if (v instanceof NameExpr ne && params.contains(ne.getNameAsString())) {
            return true;
        }
        String noNulo = InfoClase.parametroNoNulo(v);
        return noNulo != null && params.contains(noNulo);
    }

    private static void analizarCuerpos(InfoClase c, IndiceTipos idx, AnalisisServicio a, Set<String> cubiertos) {
        Set<String> nombresCampos = new LinkedHashSet<>();
        c.campos.forEach(f -> nombresCampos.add(f.nombre()));
        Map<String, Set<String>> newPorMetodo = new LinkedHashMap<>();
        Map<String, Set<String>> llamadasPorMetodo = new LinkedHashMap<>();
        Map<String, Set<String>> metodosPorNew = new LinkedHashMap<>();
        Map<String, Set<String>> metodosPorEstatica = new LinkedHashMap<>();
        Map<String, String> fqnEstatica = new LinkedHashMap<>();
        Set<String> nombresMetodos = new LinkedHashSet<>();
        c.metodos.forEach(m -> nombresMetodos.add(m.nombre));
        for (InfoMetodo m : c.metodos) {
            if (m.decl.getBody().isEmpty()) {
                continue;
            }
            Node cuerpo = m.decl.getBody().get();
            Set<String> locales = new LinkedHashSet<>();
            cuerpo.findAll(VariableDeclarator.class).forEach(vd -> locales.add(vd.getNameAsString()));
            m.parametros.forEach(p -> locales.add(p.nombre()));
            Set<String> news = new LinkedHashSet<>();
            for (ObjectCreationExpr oc : cuerpo.findAll(ObjectCreationExpr.class)) {
                if (oc.getAnonymousClassBody().isPresent()) {
                    continue;
                }
                String t = oc.getType().getNameAsString();
                if (esColaboradorProyecto(t, c, idx)) {
                    news.add(t);
                    metodosPorNew.computeIfAbsent(t, k -> new LinkedHashSet<>()).add(m.nombre);
                }
            }
            newPorMetodo.put(m.nombre, news);
            Set<String> llamadas = new LinkedHashSet<>();
            for (MethodCallExpr mc : cuerpo.findAll(MethodCallExpr.class)) {
                if (mc.getScope().isEmpty() || mc.getScope().get() instanceof ThisExpr) {
                    if (nombresMetodos.contains(mc.getNameAsString())) {
                        llamadas.add(mc.getNameAsString());
                    }
                    continue;
                }
                if (mc.getScope().get() instanceof NameExpr ne) {
                    String n = ne.getNameAsString();
                    if (n.isEmpty() || !Character.isUpperCase(n.charAt(0)) || locales.contains(n) || nombresCampos.contains(n)) {
                        continue;
                    }
                    String clave = n + "." + mc.getNameAsString();
                    InfoClase t = idx.resolverClase(n, c);
                    boolean impura = IMPURAS.contains(clave) || n.equals("Files");
                    boolean proyecto = t != null && t != c && t.tipo == InfoClase.Tipo.CLASE && !DetectorServicios.esDatos(t);
                    if (impura || proyecto || esHolder(n, t, c, idx)) {
                        metodosPorEstatica.computeIfAbsent(n, k -> new LinkedHashSet<>()).add(m.nombre + " → " + clave + "()");
                        fqnEstatica.putIfAbsent(n, t != null ? t.fqn : idx.resolverFqn(n, c));
                    }
                }
            }
            llamadasPorMetodo.put(m.nombre, llamadas);
        }
        for (Map.Entry<String, Set<String>> e : metodosPorNew.entrySet()) {
            Dependencia d = new Dependencia();
            d.nombre = e.getKey();
            d.tipo = e.getKey();
            d.tipoFqn = idx.resolverFqn(e.getKey(), c);
            d.obtencion = Dependencia.TipoObtencion.NEW_INTERNO;
            d.mockeable = a.permiteMockConstruction;
            d.nota = "new " + e.getKey() + "(...) en " + String.join(", ", e.getValue())
                    + (a.permiteMockConstruction ? "; usar construcciones[] (mockConstruction)"
                    : "; mockConstruction no disponible con esta versión de Mockito: requiere refactor");
            a.dependencias.add(d);
        }
        for (Map.Entry<String, Set<String>> e : metodosPorEstatica.entrySet()) {
            Dependencia d = new Dependencia();
            d.nombre = e.getKey();
            d.tipo = e.getKey();
            d.tipoFqn = fqnEstatica.get(e.getKey());
            d.obtencion = Dependencia.TipoObtencion.ESTATICA;
            d.mockeable = a.permiteMockStatic;
            RecetasMontaje.Holder h = esHolder(e.getKey(), idx.resolverClase(e.getKey(), c), c, idx)
                    ? RecetasMontaje.holder(e.getKey()) : null;
            if (h != null) {
                if (d.tipoFqn == null) {
                    d.tipoFqn = h.fqn();
                }
                d.nota = h.nota() + "; llamadas: " + String.join("; ", e.getValue());
                d.notaVisible = true;
                if (h.limpieza() != null) {
                    a.limpiezas.add(new AnalisisServicio.Limpieza(h.limpieza(), h.fqn()));
                }
            } else {
                d.nota = "llamadas estáticas: " + String.join("; ", e.getValue())
                        + (a.permiteMockStatic ? " (estaticos[] con mockStatic, o se dejan reales)" : " (se ejecutan reales; mockStatic no disponible)");
            }
            a.dependencias.add(d);
        }
        if (!a.permiteMockConstruction) {
            for (InfoMetodo m : c.metodosPublicos()) {
                Set<String> alcanzables = new LinkedHashSet<>();
                List<String> pendientes = new ArrayList<>(List.of(m.nombre));
                while (!pendientes.isEmpty()) {
                    String n = pendientes.remove(0);
                    if (alcanzables.add(n)) {
                        pendientes.addAll(llamadasPorMetodo.getOrDefault(n, Set.of()));
                    }
                }
                Set<String> news = new LinkedHashSet<>();
                for (String n : alcanzables) {
                    news.addAll(newPorMetodo.getOrDefault(n, Set.of()));
                }
                if (!news.isEmpty()) {
                    List<String> motivos = new ArrayList<>();
                    for (String t : news) {
                        motivos.add("crea " + t + " con new (mockConstruction requiere Mockito ≥ 5, o ≥ 3.5 con mockito-inline)");
                    }
                    a.refactor.put(m.clave(), motivos);
                }
            }
        }
    }

    /** true si {@code n} es un holder estático de Spring (no una clase del proyecto con ese nombre). */
    private static boolean esHolder(String n, InfoClase t, InfoClase c, IndiceTipos idx) {
        RecetasMontaje.Holder h = RecetasMontaje.holder(n);
        if (h == null || t != null) {
            return false;
        }
        String fqn = idx.resolverFqn(n, c);
        return fqn == null || fqn.equals(h.fqn());
    }

    // ---------------------------------------------------------------- @Value con valor por defecto

    private static AnnotationExpr anotacion(Iterable<AnnotationExpr> anns, String nombre) {
        for (AnnotationExpr an : anns) {
            String n = an.getNameAsString();
            if (n.equals(nombre) || n.endsWith("." + nombre)) {
                return an;
            }
        }
        return null;
    }

    /** Anotación {@code nombre} de la declaración del campo {@code campo} en el AST de la clase, o null. */
    private static AnnotationExpr anotacionCampo(InfoClase c, String campo, String nombre) {
        if (c == null || c.decl == null || campo == null) {
            return null;
        }
        for (FieldDeclaration fd : c.decl.getFields()) {
            if (fd.getVariables().stream().anyMatch(v -> v.getNameAsString().equals(campo))) {
                return anotacion(fd.getAnnotations(), nombre);
            }
        }
        return null;
    }

    /** Si la anotación {@code @Value} tiene valor por defecto convertible al tipo, lo guarda y ajusta la nota. */
    private static void aplicarDefectoValue(Dependencia d, AnnotationExpr av, String tipo, InfoClase ctx, IndiceTipos idx) {
        String defecto = convertirDefecto(textoValue(av, ctx, idx), tipo);
        if (defecto == null) {
            return;
        }
        d.valorPorDefecto = defecto;
        d.requiereValor = false;
        d.nota = "@Value; por defecto " + defecto + "; cámbialo en construccion.argumentos." + d.nombre;
    }

    /** Texto del atributo value: literal, o constante a un salto ({@code CONST} o {@code Clase.CONST}) con literal. */
    private static String textoValue(AnnotationExpr av, InfoClase ctx, IndiceTipos idx) {
        Expression v = null;
        if (av instanceof SingleMemberAnnotationExpr sm) {
            v = sm.getMemberValue();
        } else if (av instanceof NormalAnnotationExpr na) {
            for (var par : na.getPairs()) {
                if (par.getNameAsString().equals("value")) {
                    v = par.getValue();
                }
            }
        }
        if (v instanceof StringLiteralExpr sl) {
            return sl.asString();
        }
        InfoClase duena = null;
        String constante = null;
        if (v instanceof NameExpr ne) {
            duena = ctx;
            constante = ne.getNameAsString();
        } else if (v instanceof FieldAccessExpr fa && fa.getScope() instanceof NameExpr sc) {
            duena = idx.resolverClase(sc.getNameAsString(), ctx);
            constante = fa.getNameAsString();
        }
        InfoClase.InfoCampo f = duena == null ? null : duena.campo(constante);
        if (f != null && (f.estatico() || f.esFinal() || duena.tipo == InfoClase.Tipo.INTERFAZ)
                && f.inicializador() instanceof StringLiteralExpr sl) {
            return sl.asString();
        }
        return null;
    }

    /**
     * Valor por defecto como expresión Java según el tipo, solo con reglas cerradas: {@code ${clave:defecto}} sin
     * placeholders anidados ni SpEL, o un literal sin placeholder. null si no hay valor convertible.
     */
    static String convertirDefecto(String texto, String tipo) {
        if (texto == null || tipo == null || texto.contains("#{")) {
            return null;
        }
        String defecto;
        if (texto.startsWith("${")) {
            if (!texto.endsWith("}") || texto.indexOf("${", 2) >= 0) {
                return null;
            }
            String cuerpo = texto.substring(2, texto.length() - 1);
            int dp = cuerpo.indexOf(':');
            if (dp < 0 || cuerpo.contains("{") || cuerpo.contains("}")) {
                return null;
            }
            defecto = cuerpo.substring(dp + 1);
        } else if (texto.contains("${")) {
            return null;
        } else {
            defecto = texto;
        }
        String t = Textos.quitarGenericos(tipo).trim();
        if (t.startsWith("java.lang.")) {
            t = t.substring("java.lang.".length());
        }
        if (t.equals("String")) {
            return Textos.literalJava(defecto);
        }
        String x = defecto.trim();
        try {
            switch (t) {
                case "int", "Integer" -> {
                    return ENTERO.matcher(x).matches() ? String.valueOf(Integer.parseInt(x)) : null;
                }
                case "short", "Short" -> {
                    return ENTERO.matcher(x).matches() ? "(short) " + Short.parseShort(x) : null;
                }
                case "byte", "Byte" -> {
                    return ENTERO.matcher(x).matches() ? "(byte) " + Byte.parseByte(x) : null;
                }
                case "long", "Long" -> {
                    return ENTERO.matcher(x).matches() ? Long.parseLong(x) + "L" : null;
                }
                case "double", "Double" -> {
                    if (!DECIMAL.matcher(x).matches() || !Double.isFinite(Double.parseDouble(x))) {
                        return null;
                    }
                    return x.contains(".") || x.contains("e") || x.contains("E") ? x : x + ".0";
                }
                case "float", "Float" -> {
                    return DECIMAL.matcher(x).matches() && Float.isFinite(Float.parseFloat(x)) ? x + "f" : null;
                }
                case "boolean", "Boolean" -> {
                    return x.equalsIgnoreCase("true") ? "true" : x.equalsIgnoreCase("false") ? "false" : null;
                }
                default -> {
                    return null;
                }
            }
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- colecciones y proveedores de beans

    /** true si el tipo es List/Collection/Set/Iterable/Map/Optional (contenedor no mock). */
    public static boolean esColeccionBeans(String tipo) {
        return tipo != null && COLECCIONES_BEANS.contains(Textos.nombreSimple(tipo));
    }

    /** true si el tipo es ObjectProvider/Provider/Supplier (contenedor mock). */
    public static boolean esProveedorBeans(String tipo) {
        return tipo != null && PROVEEDORES_BEANS.contains(Textos.nombreSimple(tipo));
    }

    /**
     * Tipo X del elemento si {@code tipo} es {@code List|Collection|Set|Iterable<X>}, {@code Map<String,X>},
     * {@code Optional<X>} u {@code ObjectProvider|Provider|Supplier<X>} con X colaborador; si no, null.
     */
    static String tipoElemento(String tipo, InfoClase c, IndiceTipos idx) {
        if (tipo == null) {
            return null;
        }
        String t = tipo.trim();
        int a = t.indexOf('<');
        if (a <= 0 || !t.endsWith(">")) {
            return null;
        }
        String ext = Textos.nombreSimple(t.substring(0, a));
        if (!COLECCIONES_BEANS.contains(ext) && !PROVEEDORES_BEANS.contains(ext)) {
            return null;
        }
        List<String> args = argumentosGenericos(t.substring(a + 1, t.length() - 1));
        String x;
        if (ext.equals("Map")) {
            if (args.size() != 2 || !"String".equals(Textos.nombreSimple(args.get(0)))) {
                return null;
            }
            x = args.get(1);
        } else {
            if (args.size() != 1) {
                return null;
            }
            x = args.get(0);
        }
        if (x.startsWith("? extends ")) {
            x = x.substring("? extends ".length()).trim();
        }
        if (x.isEmpty() || x.startsWith("?") || x.contains("[") || x.contains("...")) {
            return null;
        }
        return esColaborador(x, c, idx) ? x : null;
    }

    /** Argumentos de tipo de primer nivel (separados por comas fuera de {@code <>}). */
    private static List<String> argumentosGenericos(String s) {
        List<String> r = new ArrayList<>();
        int nivel = 0;
        StringBuilder sb = new StringBuilder();
        for (char ch : s.toCharArray()) {
            if (ch == '<') {
                nivel++;
            } else if (ch == '>') {
                nivel--;
            } else if (ch == ',' && nivel == 0) {
                r.add(sb.toString().trim());
                sb.setLength(0);
                continue;
            }
            sb.append(ch);
        }
        r.add(sb.toString().trim());
        return r;
    }

    /** Crea un mock ELEMENTO_COLECCION por contenedor (nombre X en minúscula; sufijo Elemento si colisiona). */
    private static void crearElementos(AnalisisServicio a, InfoClase c, VersionesProyecto v, Map<Dependencia, String[]> contenedores) {
        if (contenedores.isEmpty()) {
            return;
        }
        Set<String> usados = new LinkedHashSet<>();
        a.dependencias.forEach(d -> usados.add(d.nombre));
        c.campos.forEach(f -> usados.add(f.nombre()));
        for (Map.Entry<Dependencia, String[]> e : contenedores.entrySet()) {
            Dependencia cont = e.getKey();
            String x = e.getValue()[0];
            String simple = Textos.nombreSimple(x);
            String base = Character.toLowerCase(simple.charAt(0)) + simple.substring(1);
            String nombre = base;
            if (usados.contains(nombre) || !Textos.esIdentificador(nombre)) {
                nombre = base + "Elemento";
                for (int k = 2; usados.contains(nombre); k++) {
                    nombre = base + "Elemento" + k;
                }
            }
            usados.add(nombre);
            Dependencia el = new Dependencia();
            el.nombre = nombre;
            el.tipo = x;
            el.tipoFqn = e.getValue()[1];
            el.obtencion = Dependencia.TipoObtencion.ELEMENTO_COLECCION;
            el.mockeable = true;
            el.contenedor = cont.nombre;
            el.heredadoDe = cont.heredadoDe;
            el.nota = "mock del elemento de " + cont.nombre;
            el.notaVisible = true;
            a.dependencias.add(el);
            if (esColeccionBeans(cont.tipo)) {
                cont.requiereValor = false;
                String expr = expresionColeccion(cont.tipo, nombre, v.javaEfectivo());
                cont.nota = "colección de beans: " + expr + " con el mock " + nombre + "; cámbiala en construccion.argumentos." + cont.nombre
                        + (cont.heredadoDe != null ? "; heredado de " + cont.heredadoDe : "");
            } else {
                anadirNota(cont, "proveedor de beans: devuelve el mock " + nombre);
            }
        }
    }

    /** Expresión de la colección/Optional con el elemento (Java &lt; 9: Collections.singleton*); null si no aplica. */
    public static String expresionColeccion(String tipoContenedor, String elemento, int java) {
        boolean j9 = java >= 9;
        return switch (Textos.nombreSimple(tipoContenedor)) {
            case "List", "Collection", "Iterable" -> j9 ? "List.of(" + elemento + ")" : "Collections.singletonList(" + elemento + ")";
            case "Set" -> j9 ? "Set.of(" + elemento + ")" : "Collections.singleton(" + elemento + ")";
            case "Map" -> (j9 ? "Map.of(" : "Collections.singletonMap(") + Textos.literalJava(elemento) + ", " + elemento + ")";
            case "Optional" -> "Optional.of(" + elemento + ")";
            default -> null;
        };
    }

    /** Métodos del proveedor que se stubbean para devolver el elemento. */
    public static List<String> metodosProveedor(String tipoContenedor) {
        return switch (Textos.nombreSimple(tipoContenedor)) {
            case "ObjectProvider" -> List.of("getIfAvailable", "getObject", "getIfUnique");
            case "Provider", "Supplier" -> List.of("get");
            default -> List.of();
        };
    }

    private static void anadirNota(Dependencia d, String texto) {
        d.nota = d.nota == null || d.nota.isEmpty() ? texto : d.nota + "; " + texto;
        d.notaVisible = true;
    }

    // ---------------------------------------------------------------- dependencias heredadas

    /** Campos anotados (ANOT_CAMPO o @Value) y setters anotados de las superclases del índice (hasta 5 niveles). */
    private static void analizarHerencia(InfoClase c, IndiceTipos idx, AnalisisServicio a, Set<String> cubiertos,
                                         Map<Dependencia, String[]> contenedores) {
        Set<String> vistos = new LinkedHashSet<>();
        c.campos.forEach(f -> vistos.add(f.nombre()));
        Set<InfoClase> recorridas = new LinkedHashSet<>();
        recorridas.add(c);
        InfoClase actual = c;
        for (int nivel = 0; nivel < MAX_NIVELES_HERENCIA && !actual.extiende.isEmpty(); nivel++) {
            InfoClase padre = idx.resolverClase(actual.extiende.get(0), actual);
            if (padre == null || padre.tipo != InfoClase.Tipo.CLASE || !recorridas.add(padre)) {
                break;
            }
            for (InfoClase.InfoCampo f : padre.campos) {
                if (f.estatico() || cubiertos.contains(f.nombre()) || vistos.contains(f.nombre())) {
                    continue;
                }
                boolean value = f.anotaciones().contains("Value");
                boolean anotado = f.anotaciones().stream().anyMatch(ANOT_CAMPO::contains);
                if (!value && !anotado) {
                    continue;
                }
                Dependencia d = new Dependencia();
                d.nombre = f.nombre();
                d.campo = f.nombre();
                d.tipo = f.tipo();
                d.tipoFqn = idx.resolverFqn(f.tipo(), padre);
                d.obtencion = Dependencia.TipoObtencion.CAMPO_ANOTADO;
                d.heredadoDe = padre.simple;
                if (value) {
                    d.mockeable = false;
                    d.nota = "@Value en campo: valor opcional en construccion.argumentos." + f.nombre() + " (por reflexión)";
                    aplicarDefectoValue(d, anotacionCampo(padre, f.nombre(), "Value"), f.tipo(), padre, idx);
                } else {
                    d.mockeable = !esValor(f.tipo(), padre, idx);
                    d.nota = "@" + f.anotaciones().stream().filter(ANOT_CAMPO::contains).findFirst().orElse("") + " en campo";
                    String elemento = tipoElemento(f.tipo(), padre, idx);
                    if (elemento != null) {
                        contenedores.put(d, new String[]{elemento, idx.resolverFqn(elemento, padre)});
                    }
                }
                anadirNota(d, "heredado de " + padre.simple);
                a.dependencias.add(d);
                cubiertos.add(d.nombre);
            }
            for (InfoMetodo m : padre.metodos) {
                if (!m.nombre.startsWith("set") || m.nombre.length() < 4 || m.parametros.size() != 1 || m.estatico || m.privado
                        || m.anotaciones.stream().noneMatch(ANOT_CAMPO::contains)) {
                    continue;
                }
                String campo = Character.toLowerCase(m.nombre.charAt(3)) + m.nombre.substring(4);
                if (cubiertos.contains(campo) || vistos.contains(campo) && padre.campo(campo) == null) {
                    continue;
                }
                InfoClase.InfoCampo f = padre.campo(campo);
                String tipo = m.parametros.get(0).tipo();
                Dependencia d = new Dependencia();
                d.nombre = campo;
                d.tipo = tipo;
                d.tipoFqn = idx.resolverFqn(tipo, padre);
                d.mockeable = !esValor(tipo, padre, idx);
                d.heredadoDe = padre.simple;
                if (f != null && !f.estatico() && !vistos.contains(campo)) {
                    d.campo = campo;
                    d.obtencion = Dependencia.TipoObtencion.CAMPO_ANOTADO;
                    d.nota = "@" + m.anotaciones.stream().filter(ANOT_CAMPO::contains).findFirst().orElse("") + " en " + m.nombre;
                } else if (m.publico) {
                    d.obtencion = Dependencia.TipoObtencion.SETTER;
                    d.setter = m.nombre;
                    d.nota = "se inyecta con " + m.nombre + "(...)";
                } else {
                    continue;
                }
                anadirNota(d, "heredado de " + padre.simple);
                a.dependencias.add(d);
                cubiertos.add(campo);
            }
            padre.campos.forEach(f -> vistos.add(f.nombre()));
            actual = padre;
        }
    }

    // ---------------------------------------------------------------- recetas por tipo

    /** Mocks profundos y reales por receta, notas de ObjectMapper/ExecutorService y tipos finales sin inline. */
    private static void aplicarRecetas(AnalisisServicio a, InfoClase c, IndiceTipos idx, VersionesProyecto v) {
        for (Dependencia d : a.dependencias) {
            if (!d.esMockDeclarado()) {
                continue;
            }
            if (RecetasMontaje.esProfundo(d.tipo, d.tipoFqn)) {
                d.profundo = true;
            }
            InfoClase t = d.tipoFqn != null ? idx.get(d.tipoFqn) : d.heredadoDe == null ? idx.resolverClase(d.tipo, c) : null;
            RecetasMontaje.Real r = v.javaEfectivo() >= 8 ? RecetasMontaje.real(d.tipo, d.tipoFqn, t != null) : null;
            if (r != null && r.expresion().contains("mock(") && !v.mockito) {
                r = null;
            }
            if (r != null) {
                d.realPorDefecto = r.expresion();
                d.importsReal.addAll(r.imports());
                anadirNota(d, "instancia real " + r.expresion() + "; cámbiala en construccion.argumentos");
                continue;
            }
            if (t == null && d.heredadoDe == null) {
                t = idx.resolverAnidada(d.tipo, c);
            }
            String simple = Textos.nombreSimple(d.tipo);
            if ("ObjectMapper".equals(simple)) {
                anadirNota(d, String.format(RecetasMontaje.NOTA_OBJECT_MAPPER, d.nombre));
            } else if ("ExecutorService".equals(simple)) {
                anadirNota(d, String.format(RecetasMontaje.NOTA_EXECUTOR_SERVICE, d.nombre));
            }
            if (!a.permiteMockStatic && t != null && esTipoFinal(t)) {
                anadirNota(d, NOTA_TIPO_FINAL);
            }
            if (t != null && esConfigurationPropertiesInstanciable(t)) {
                d.nota = String.format(NOTA_CONFIGURATION_PROPERTIES, d.nombre, t.simple)
                        + (d.nota == null || d.nota.isBlank() ? "" : "; " + d.nota);
                d.notaVisible = true;
            }
        }
    }

    static final String NOTA_CONFIGURATION_PROPERTIES = "@ConfigurationProperties: el mock devuelve null/0; real con sus valores "
            + "por defecto: construccion.argumentos.%s = new %s()";

    /** Clase concreta anotada {@code @ConfigurationProperties} con constructor sin argumentos accesible. */
    private static boolean esConfigurationPropertiesInstanciable(InfoClase t) {
        return t.tipo == InfoClase.Tipo.CLASE && !t.abstracta && t.tieneAnotacion("ConfigurationProperties")
                && (t.constructores.isEmpty() || t.constructores.stream().anyMatch(k -> k.parametros.isEmpty() && !k.privado));
    }

    private static boolean esTipoFinal(InfoClase t) {
        if (t.tipo == InfoClase.Tipo.RECORD || t.tipo == InfoClase.Tipo.ENUM) {
            return true;
        }
        return t.decl instanceof ClassOrInterfaceDeclaration ci && !ci.isInterface() && ci.isFinal();
    }

    private static Dependencia.ModoMontaje calcularModo(AnalisisServicio a, List<InfoClase.InfoConstructor> ks) {
        List<Dependencia> mocks = a.mocks();
        boolean hayValores = a.dependencias.stream().anyMatch(d -> d.obtencion == Dependencia.TipoObtencion.VALOR_CONSTRUCTOR);
        int nCtor = a.constructor == null ? 0 : a.constructor.parametros.size();
        boolean nuevoEnCampo = a.dependencias.stream().anyMatch(d -> d.obtencion == Dependencia.TipoObtencion.NEW_INTERNO && d.campo != null);
        boolean hayCampos = a.dependencias.stream().anyMatch(d -> d.esMockDeclarado()
                && (d.obtencion == Dependencia.TipoObtencion.SETTER || d.obtencion == Dependencia.TipoObtencion.CAMPO_ANOTADO
                || d.obtencion == Dependencia.TipoObtencion.CAMPO_SIN_ANOTAR));
        if (mocks.isEmpty() && !hayValores && nCtor == 0) {
            return Dependencia.ModoMontaje.SIN_MOCKS;
        }
        if (a.clase != null && a.clase.tipo == InfoClase.Tipo.RECORD && nCtor > 0) {
            return Dependencia.ModoMontaje.EXPLICITO;
        }
        if (a.dependencias.stream().anyMatch(d -> d.obtencion == Dependencia.TipoObtencion.ELEMENTO_COLECCION)) {
            return Dependencia.ModoMontaje.EXPLICITO;
        }
        if (a.constructorConLogica) {
            return Dependencia.ModoMontaje.EXPLICITO;
        }
        if (!hayValores && !nuevoEnCampo) {
            int maxParams = ks.stream().mapToInt(k -> k.parametros.size()).max().orElse(0);
            if (nCtor > 0 && !hayCampos && nCtor == maxParams) {
                return Dependencia.ModoMontaje.INJECT_MOCKS;
            }
            if (nCtor == 0 && a.constructorSinArgs && hayCampos && maxParams == 0) {
                return Dependencia.ModoMontaje.INJECT_MOCKS;
            }
        }
        return Dependencia.ModoMontaje.EXPLICITO;
    }

    public static boolean esValor(String tipo, InfoClase ctx, IndiceTipos idx) {
        if (tipo == null) {
            return true;
        }
        if (tipo.contains("[]") || tipo.endsWith("...")) {
            return true;
        }
        String s = Textos.nombreSimple(tipo);
        if (TIPOS_VALOR.contains(s)) {
            return true;
        }
        InfoClase t = idx.resolverClase(tipo, ctx);
        if (t == null) {
            t = idx.resolverAnidada(tipo, ctx);
        }
        return t != null && t.tipo == InfoClase.Tipo.ENUM;
    }

    public static boolean esColaborador(String tipo, InfoClase ctx, IndiceTipos idx) {
        if (esValor(tipo, ctx, idx)) {
            return false;
        }
        InfoClase t = idx.resolverClase(tipo, ctx);
        if (t != null) {
            return !DetectorServicios.esDatos(t) && !DetectorServicios.esControlador(t);
        }
        String s = Textos.nombreSimple(tipo);
        for (String suf : SUFIJOS_COLABORADOR) {
            if (s.endsWith(suf)) {
                return true;
            }
        }
        return false;
    }

    static boolean esColaboradorProyecto(String tipo, InfoClase ctx, IndiceTipos idx) {
        InfoClase t = idx.resolverClase(tipo, ctx);
        if (t == null || t == ctx || t.tipo != InfoClase.Tipo.CLASE) {
            return false;
        }
        return !DetectorServicios.esDatos(t) && !DetectorServicios.esControlador(t);
    }
}
