package com.junitgen.escaneo;

import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.ThisExpr;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Empareja servicios con tests existentes: (1) por nombre, (2) por la clase que instancia el test
 * (prevalece sobre el nombre) y (3) por métodos cubiertos.
 */
public final class EmparejadorTests {

    public static final Set<String> ANOT_TEST = Set.of("Test", "ParameterizedTest", "RepeatedTest");
    public static final Set<String> ANOT_SPRING_TEST = Set.of("SpringBootTest", "WebMvcTest", "DataJpaTest",
            "SpringJUnitConfig", "ContextConfiguration", "WebFluxTest", "JsonTest", "RestClientTest");

    public static class TestEmparejado {
        public InfoClase test;
        public String confianza;
        public String via;
    }

    public static class CasoExistente {
        public String test;
        public String metodoTest;
        public String metodo;
        public int aridad;
    }

    public static class Emparejamiento {
        public final List<TestEmparejado> tests = new ArrayList<>();
        public final List<CasoExistente> casos = new ArrayList<>();

        public List<CasoExistente> casosDe(String metodo, int aridad) {
            List<CasoExistente> r = new ArrayList<>();
            for (CasoExistente c : casos) {
                if (c.metodo.equals(metodo) && (c.aridad == aridad || c.aridad < 0)) {
                    r.add(c);
                }
            }
            return r;
        }
    }

    private EmparejadorTests() {
    }

    public static Map<String, Emparejamiento> emparejar(List<InfoClase> servicios, IndiceTipos idx) {
        Map<String, Emparejamiento> r = new LinkedHashMap<>();
        Map<String, InfoClase> porFqn = new LinkedHashMap<>();
        for (InfoClase s : servicios) {
            r.put(s.fqn, new Emparejamiento());
            porFqn.put(s.fqn, s);
        }
        for (InfoClase test : idx.clasesTest()) {
            if (test.tipo != InfoClase.Tipo.CLASE) {
                continue;
            }
            Map<String, String> inst = instanciados(test, idx, porFqn);
            if (!inst.isEmpty()) {
                for (Map.Entry<String, String> e : inst.entrySet()) {
                    TestEmparejado te = new TestEmparejado();
                    te.test = test;
                    te.confianza = "alta";
                    te.via = "instancia (" + e.getValue() + ")";
                    r.get(e.getKey()).tests.add(te);
                }
                continue;
            }
            String base = quitarSufijoTest(test.simple);
            String norm = quitarImpl(base);
            InfoClase exacto = null;
            for (InfoClase s : servicios) {
                if (quitarImpl(s.simple).equals(norm)) {
                    exacto = s;
                    break;
                }
            }
            if (exacto != null) {
                TestEmparejado te = new TestEmparejado();
                te.test = test;
                te.confianza = "alta";
                te.via = "nombre";
                r.get(exacto.fqn).tests.add(te);
                continue;
            }
            InfoClase mejor = null;
            int largo = 0;
            for (InfoClase s : servicios) {
                for (String pref : new String[]{s.simple, quitarImpl(s.simple)}) {
                    if (base.length() > pref.length() && base.startsWith(pref)
                            && Character.isUpperCase(base.charAt(pref.length())) && pref.length() > largo) {
                        mejor = s;
                        largo = pref.length();
                    }
                }
            }
            if (mejor != null) {
                TestEmparejado te = new TestEmparejado();
                te.test = test;
                te.confianza = "media";
                te.via = "prefijo de nombre";
                r.get(mejor.fqn).tests.add(te);
            }
        }
        for (InfoClase s : servicios) {
            Emparejamiento e = r.get(s.fqn);
            for (TestEmparejado te : e.tests) {
                e.casos.addAll(metodosCubiertos(te.test, s, idx));
            }
        }
        return r;
    }

    public static String quitarSufijoTest(String n) {
        for (String suf : new String[]{"ITCase", "Tests", "Test", "IT"}) {
            if (n.endsWith(suf) && n.length() > suf.length()) {
                return n.substring(0, n.length() - suf.length());
            }
        }
        return n;
    }

    public static String quitarImpl(String n) {
        return n.endsWith("Impl") && n.length() > 4 ? n.substring(0, n.length() - 4) : n;
    }

    public static boolean esSpringTest(InfoClase test) {
        return test.anotaciones.stream().anyMatch(ANOT_SPRING_TEST::contains);
    }

    /** Servicios que instancia el test: fqn → vía. */
    static Map<String, String> instanciados(InfoClase test, IndiceTipos idx, Map<String, InfoClase> servicios) {
        Map<String, String> r = new LinkedHashMap<>();
        boolean spring = esSpringTest(test);
        for (BodyDeclaration<?> m : test.decl.getMembers()) {
            if (m instanceof FieldDeclaration f) {
                Set<String> anns = InfoClase.nombresAnotaciones(f.getAnnotations());
                boolean inject = anns.contains("InjectMocks");
                boolean autowired = spring && (anns.contains("Autowired") || anns.contains("Inject"));
                if (!inject && !autowired) {
                    continue;
                }
                for (VariableDeclarator v : f.getVariables()) {
                    String fqn = servicioDeTipo(v.getTypeAsString(), test, idx, servicios);
                    if (fqn != null) {
                        r.putIfAbsent(fqn, inject ? "@InjectMocks" : "@Autowired en test Spring");
                    }
                }
            }
        }
        for (ObjectCreationExpr oc : test.decl.findAll(ObjectCreationExpr.class)) {
            String fqn = servicioDeTipo(oc.getType().getNameAsString(), test, idx, servicios);
            if (fqn != null) {
                r.putIfAbsent(fqn, "new " + oc.getType().getNameAsString());
            }
        }
        return r;
    }

    private static String servicioDeTipo(String tipo, InfoClase ctx, IndiceTipos idx, Map<String, InfoClase> servicios) {
        String fqn = idx.resolverFqn(tipo, ctx);
        if (fqn == null) {
            return null;
        }
        if (servicios.containsKey(fqn)) {
            return fqn;
        }
        InfoClase t = idx.get(fqn);
        if (t != null && t.tipo == InfoClase.Tipo.INTERFAZ) {
            String unico = null;
            for (InfoClase impl : idx.implementaciones(t)) {
                if (servicios.containsKey(impl.fqn)) {
                    if (unico != null) {
                        return null;
                    }
                    unico = impl.fqn;
                }
            }
            return unico;
        }
        return null;
    }

    /** Nombres de variable/campo con los que el test se refiere al servicio. */
    public static Set<String> nombresSut(InfoClase test, InfoClase servicio, IndiceTipos idx) {
        Set<String> tipos = new LinkedHashSet<>();
        tipos.add(servicio.simple);
        tipos.addAll(servicio.implementa);
        Set<String> r = new LinkedHashSet<>();
        for (VariableDeclarator v : test.decl.findAll(VariableDeclarator.class)) {
            String t = com.junitgen.util.Textos.nombreSimple(v.getTypeAsString());
            if (tipos.contains(t)) {
                r.add(v.getNameAsString());
            } else if (v.getInitializer().isPresent() && v.getInitializer().get() instanceof ObjectCreationExpr oc
                    && oc.getType().getNameAsString().equals(servicio.simple)) {
                r.add(v.getNameAsString());
            }
        }
        return r;
    }

    public static boolean esMetodoTest(MethodDeclaration md) {
        return InfoClase.nombresAnotaciones(md.getAnnotations()).stream().anyMatch(ANOT_TEST::contains);
    }

    static List<CasoExistente> metodosCubiertos(InfoClase test, InfoClase servicio, IndiceTipos idx) {
        List<CasoExistente> r = new ArrayList<>();
        Set<String> sut = nombresSut(test, servicio, idx);
        if (sut.isEmpty()) {
            return r;
        }
        for (MethodDeclaration md : test.decl.findAll(MethodDeclaration.class)) {
            if (!esMetodoTest(md) || md.getBody().isEmpty()) {
                continue;
            }
            for (MethodCallExpr mc : md.getBody().get().findAll(MethodCallExpr.class)) {
                if (mc.getScope().isEmpty()) {
                    continue;
                }
                String nombreScope = null;
                if (mc.getScope().get() instanceof NameExpr ne) {
                    nombreScope = ne.getNameAsString();
                } else if (mc.getScope().get() instanceof FieldAccessExpr fa && fa.getScope() instanceof ThisExpr) {
                    nombreScope = fa.getNameAsString();
                }
                if (nombreScope != null && sut.contains(nombreScope)) {
                    CasoExistente c = new CasoExistente();
                    c.test = test.simple;
                    c.metodoTest = md.getNameAsString();
                    c.metodo = mc.getNameAsString();
                    c.aridad = mc.getArguments().size();
                    r.add(c);
                }
            }
            for (MethodReferenceExpr mr : md.getBody().get().findAll(MethodReferenceExpr.class)) {
                if (mr.getScope() instanceof NameExpr ne && sut.contains(ne.getNameAsString())) {
                    CasoExistente c = new CasoExistente();
                    c.test = test.simple;
                    c.metodoTest = md.getNameAsString();
                    c.metodo = mr.getIdentifier();
                    c.aridad = -1;
                    r.add(c);
                }
            }
        }
        return r;
    }
}
