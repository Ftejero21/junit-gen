package com.junitgen.escaneo;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Interpretación determinista (sin IA) de la petición libre del usuario tras /generartest: número de servicios (en cifra
 * o en letra), servicios del proyecto, «todas», funciones concretas de un servicio e intención (generar, continuar o
 * cambiar la carpeta de salida). También entiende los formatos cortos ({@code 5}, {@code 3 todas},
 * {@code X funciones=a,b}, {@code continuar}, {@code ruta}). Solo lee el índice en memoria; sin estado ni E/S.
 */
public final class InterpretePeticion {

    /** Intención deducida de la frase. */
    public enum Intencion { GENERAR, CONTINUAR, RUTA }

    /** Trozo de la frase: texto original, clave (minúsculas sin acentos) y si va detrás de un marcador de función. */
    public record Token(String texto, String clave, boolean trasMarcador) {
    }

    /**
     * Análisis léxico (sin proyecto): texto saneado, trozos, intención, número (sin validar rango), todas, modo «todos los
     * servicios» (con su máximo opcional) y avisos.
     */
    public record Lexico(String texto, List<Token> tokens, Intencion intencion, Integer numero, boolean todas,
            boolean todosServicios, Integer maxTodos, List<String> avisos) {
    }

    /** Resultado contra el proyecto: servicios, funciones, nombres con forma de clase no encontrados y avisos. */
    public record Resultado(List<String> servicios, List<String> funciones, List<String> noEncontrados, List<String> avisos) {
    }

    /** Longitud máxima de la petición (se recorta). */
    public static final int MAX_TEXTO = 500;

    private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}\\u2028\\u2029\\u0085]");
    private static final Pattern ESPACIOS = Pattern.compile("\\s+");
    private static final Pattern PARENTESIS = Pattern.compile("\\([^)]*\\)");
    private static final Pattern SIGNOS = Pattern.compile("[\"'`«»“”‘’¿?¡!\\[\\]{}*]");
    private static final Pattern SEPARADORES = Pattern.compile("[\\s,;:=|]+");
    private static final Pattern MARCAS = Pattern.compile("\\p{M}+");
    private static final Pattern CIFRAS = Pattern.compile("\\d{1,4}");
    private static final Pattern CIFRAS_PORCENTAJE = Pattern.compile("(\\d{1,4})%");
    private static final Pattern VERSION_PEGADA = Pattern.compile("(junit|java|jdk|mockito)\\d+");

    private static final String SUFIJO_JAVA = ".java";
    private static final String SUFIJO_SERVICE = "Service";
    private static final String CLAVE_SERVICE = "service";
    private static final String CLAVE_IMPL = "impl";
    private static final String PORCENTAJE = "%";
    private static final String POR = "por";
    private static final String Y = "y";
    private static final String TODAS = "todas";
    private static final String AVISO_TODOS_NUMERO = "con «todos» un número sin marca es el de servicios; para limitar usa `todos máx N`";
    private static final int CIEN = 100;
    private static final int MAX_FUNCIONES_AVISO = 15;
    private static final int MAX_NOMBRES_AVISO = 10;

    /** Palabras que inician una exclusión (no admitida): se descarta lo que sigue. */
    private static final Set<String> EXCLUSIONES = Set.of("menos", "excepto", "salvo", "excluye", "excluyendo", "quitando");
    /** Marcadores tras los que van nombres de funciones. */
    private static final Set<String> MARCADORES = Set.of("funcion", "funciones", "metodo", "metodos", "solo", "solamente",
            "unicamente");
    /** Palabras que indican que un número es de servicios. */
    private static final Set<String> PALABRAS_SERVICIO = Set.of("servicio", "servicios", "service", "services", "clase", "clases");
    /** Palabras de función (para «todos los métodos»). */
    private static final Set<String> PALABRAS_FUNCION = Set.of("funciones", "metodos", "funcion", "metodo");
    /** Artículos que se saltan tras «todas»/«todos». */
    private static final Set<String> ARTICULOS_TODAS = Set.of("las", "los", "sus");
    /** «todos»/«todo»: solo valen como «todas» si siguen funciones o métodos. */
    private static final Set<String> TODOS = Set.of("todos", "todo");
    /** Marcas que convierten el número siguiente en el máximo del modo «todos» (`todos máx 50`). */
    private static final Set<String> MARCAS_MAXIMO = Set.of("max", "maximo", "limite", "tope", "hasta");
    /** Palabras tras las que un número es una versión (JUnit 5, Java 17…), no un número de servicios. */
    private static final Set<String> PREFIJOS_VERSION = Set.of("junit", "java", "jdk", "mockito");
    /** «por ciento» / «por cien». */
    private static final Set<String> PALABRAS_CIEN = Set.of("ciento", "cien");
    /** Palabras que valen 1 solo si siguen palabras de servicio («un servicio»). */
    private static final Set<String> PALABRAS_UNO = Set.of("un", "una", "uno");
    /** Decenas que admiten «y unidad» (treinta y cinco). */
    private static final Set<String> DECENAS = Set.of("veinte", "treinta", "cuarenta", "cincuenta", "sesenta", "setenta",
            "ochenta", "noventa");
    /** Centenas que admiten un número detrás (ciento veinte). */
    private static final Set<String> CENTENAS = Set.of("ciento", "doscientos");
    /** Unidades para los compuestos «decena y unidad». */
    private static final Map<String, Integer> UNIDADES = Map.ofEntries(
            Map.entry("un", 1), Map.entry("una", 1), Map.entry("uno", 1), Map.entry("dos", 2), Map.entry("tres", 3),
            Map.entry("cuatro", 4), Map.entry("cinco", 5), Map.entry("seis", 6), Map.entry("siete", 7), Map.entry("ocho", 8),
            Map.entry("nueve", 9));
    /** Números en letra (sin acentos). */
    private static final Map<String, Integer> NUMEROS_EN_LETRA = numerosEnLetra();
    /** Palabras que piden retomar la tanda. */
    private static final Set<String> PALABRAS_CONTINUAR = Set.of("continuar", "continua", "continue", "continuemos", "sigue",
            "seguir", "sigamos", "prosigue", "proseguir", "reanuda", "reanudar", "retoma", "retomar");
    /** Palabras que piden cambiar la carpeta de salida. */
    private static final Set<String> PALABRAS_RUTA = Set.of("ruta", "carpeta", "directorio", "destino", "salida", "exportar",
            "exporta");
    /** Conectores (subconjunto de RELLENO) que nunca son funciones. */
    private static final Set<String> CONECTORES = Set.of("y", "e", "o", "u", "de", "del", "la", "las", "el", "los", "a", "al",
            "en", "con", "su", "sus", "que", "para", "por");
    /** Palabras de relleno: nunca son servicios ni tienen forma de clase. */
    private static final Set<String> RELLENO = relleno();

    private InterpretePeticion() {
    }

    private static Map<String, Integer> numerosEnLetra() {
        Map<String, Integer> m = new LinkedHashMap<>(UNIDADES);
        String[] diez = {"diez", "once", "doce", "trece", "catorce", "quince", "dieciseis", "diecisiete", "dieciocho",
                "diecinueve", "veinte"};
        for (int i = 0; i < diez.length; i++) {
            m.put(diez[i], 10 + i);
        }
        String[] veinti = {"veintiuno", "veintidos", "veintitres", "veinticuatro", "veinticinco", "veintiseis", "veintisiete",
                "veintiocho", "veintinueve"};
        for (int i = 0; i < veinti.length; i++) {
            m.put(veinti[i], 21 + i);
        }
        m.put("veintiun", 21);
        m.put("veintiuna", 21);
        String[] decenas = {"treinta", "cuarenta", "cincuenta", "sesenta", "setenta", "ochenta", "noventa"};
        for (int i = 0; i < decenas.length; i++) {
            m.put(decenas[i], 30 + 10 * i);
        }
        m.put("cien", CIEN);
        m.put("ciento", CIEN);
        m.put("doscientos", 2 * CIEN);
        return Map.copyOf(m);
    }

    private static Set<String> relleno() {
        Set<String> s = new HashSet<>(CONECTORES);
        s.addAll(List.of("a", "al", "de", "del", "el", "la", "las", "los", "lo", "un", "una", "uno", "unos", "unas", "y", "e",
                "o", "u", "en", "con", "para", "por", "que", "se", "su", "sus", "mi", "mis", "me", "te", "este", "esta", "estos",
                "estas"));
        s.addAll(List.of("genera", "generame", "generar", "genere", "haz", "hazme", "hacer", "crea", "creame", "escribe",
                "escribeme", "prueba", "pruebame", "probar", "testea", "testear", "quiero", "quisiera", "necesito", "puedes",
                "podrias", "dame", "pon", "lanza", "ejecuta"));
        s.addAll(List.of("test", "tests", "pruebas", "unitario", "unitarios", "unitaria", "unitarias", "junit", "servicio",
                "servicios", "service", "services", "clase", "clases", "funcion", "funciones", "metodo", "metodos", "proyecto",
                "codigo", "tanda", "favor", "porfa", "gracias", "hola", "ok", "vale", "solo", "solamente", "unicamente", "todas",
                "todos", "todo", "primeros", "primeras", "primer", "siguientes", "siguiente", "mas", "nuevos", "generartest"));
        return Set.copyOf(s);
    }

    // ------------------------------------------------------------------ léxico

    /** Análisis léxico de la petición (sin proyecto). */
    public static Lexico lexico(String texto) {
        String limpio = sanear(texto);
        List<String> trozos = trocear(limpio);
        List<String> avisos = new ArrayList<>();
        for (int i = 0; i < trozos.size(); i++) {
            if (EXCLUSIONES.contains(clave(trozos.get(i)))) {
                avisos.add("junit-gen no admite exclusiones: se ignora lo que sigue a `" + trozos.get(i) + "`");
                trozos = new ArrayList<>(trozos.subList(0, i));
                break;
            }
        }
        List<Token> tokens = new ArrayList<>();
        boolean marcador = false;
        for (String s : trozos) {
            String c = clave(s);
            tokens.add(new Token(s, c, marcador));
            if (MARCADORES.contains(c)) {
                marcador = true;
            }
        }
        Cantidades q = cantidades(tokens);
        if (q.avisoTodos()) {
            avisos.add(AVISO_TODOS_NUMERO);
        }
        return new Lexico(limpio, List.copyOf(tokens), intencion(tokens, q.numero()), q.numero(), q.todas(),
                q.todosServicios(), q.maxTodos(), List.copyOf(avisos));
    }

    /** Texto recortado a {@link #MAX_TEXTO}, en una línea (controles y saltos → espacio). */
    private static String sanear(String texto) {
        if (texto == null) {
            return "";
        }
        String s = texto.strip();
        if (s.length() > MAX_TEXTO) {
            int fin = Character.isHighSurrogate(s.charAt(MAX_TEXTO - 1)) ? MAX_TEXTO - 1 : MAX_TEXTO;
            s = s.substring(0, fin);
        }
        s = CONTROL.matcher(s).replaceAll(" ");
        return ESPACIOS.matcher(s).replaceAll(" ").strip();
    }

    private static List<String> trocear(String limpio) {
        String s = PARENTESIS.matcher(limpio).replaceAll(" ");
        s = SIGNOS.matcher(s).replaceAll(" ");
        List<String> r = new ArrayList<>();
        for (String trozo : SEPARADORES.split(s)) {
            String t = trozo.startsWith("/") ? trozo.substring(1) : trozo;
            t = recortarPuntosGuiones(t);
            if (t.toLowerCase(Locale.ROOT).endsWith(SUFIJO_JAVA)) {
                t = recortarPuntosGuiones(t.substring(0, t.length() - SUFIJO_JAVA.length()));
            }
            if (!t.isEmpty()) {
                r.add(t);
            }
        }
        return r;
    }

    private static String recortarPuntosGuiones(String t) {
        int ini = 0;
        int fin = t.length();
        while (ini < fin && (t.charAt(ini) == '.' || t.charAt(ini) == '-')) {
            ini++;
        }
        while (fin > ini && (t.charAt(fin - 1) == '.' || t.charAt(fin - 1) == '-')) {
            fin--;
        }
        return t.substring(ini, fin);
    }

    /** Minúsculas (Locale.ROOT) sin acentos. */
    private static String clave(String s) {
        return MARCAS.matcher(Normalizer.normalize(s.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)).replaceAll("");
    }

    private static boolean esRelleno(String clave) {
        return RELLENO.contains(clave) || VERSION_PEGADA.matcher(clave).matches();
    }

    private record Lectura(int valor, int fin, boolean porcentaje) {
    }

    private record Cantidades(Integer numero, boolean todas, boolean todosServicios, Integer maxTodos, boolean avisoTodos,
            Set<Integer> consumidos) {
    }

    /** Número suelto (no porcentaje ni versión): posición de inicio, valor, índice de fin y si lo sigue una palabra de servicio. */
    private record NumeroSuelto(int inicio, int valor, int fin, boolean conServicio) {
    }

    /** Número de servicios, «todas», «todos los servicios» (con máximo) y los índices de tokens consumidos por ellos. */
    private static Cantidades cantidades(List<Token> tokens) {
        List<String> c = new ArrayList<>();
        tokens.forEach(t -> c.add(t.clave()));
        Set<Integer> consumidos = new HashSet<>();
        boolean todas = false;
        boolean todosServicios = false;
        List<NumeroSuelto> sueltos = new ArrayList<>();
        int i = 0;
        while (i < c.size()) {
            Lectura l = leerNumero(c, i);
            if (l == null) {
                i++;
                continue;
            }
            int fin = l.fin();
            boolean porcentaje = l.porcentaje();
            if (!porcentaje && fin < c.size() && PORCENTAJE.equals(c.get(fin))) {
                porcentaje = true;
                fin++;
            } else if (!porcentaje && fin + 1 < c.size() && POR.equals(c.get(fin)) && PALABRAS_CIEN.contains(c.get(fin + 1))) {
                porcentaje = true;
                fin += 2;
            }
            for (int k = i; k < fin; k++) {
                consumidos.add(k);
            }
            boolean version = i > 0 && PREFIJOS_VERSION.contains(c.get(i - 1));
            if (porcentaje) {
                todas |= l.valor() == CIEN;
            } else if (!version) {
                sueltos.add(new NumeroSuelto(i, l.valor(), l.fin(), l.fin() < c.size() && PALABRAS_SERVICIO.contains(c.get(l.fin()))));
            }
            i = fin;
        }
        for (int k = 0; k < c.size(); k++) {
            if (consumidos.contains(k)) {
                continue;
            }
            String w = c.get(k);
            if (TODAS.equals(w)) {
                consumidos.add(k);
                if (PALABRAS_SERVICIO.contains(siguienteTrasArticulos(c, k))) {
                    todosServicios = true;
                } else {
                    todas = true;
                }
            } else if (TODOS.contains(w)) {
                consumidos.add(k);
                if (PALABRAS_FUNCION.contains(siguienteTrasArticulos(c, k))) {
                    todas = true;
                } else {
                    todosServicios = true;
                }
            }
        }
        Integer maxTodos = null;
        boolean avisoTodos = false;
        if (todosServicios) {
            List<NumeroSuelto> restantes = new ArrayList<>();
            for (NumeroSuelto n : sueltos) {
                if (maxTodos == null && n.inicio() > 0 && MARCAS_MAXIMO.contains(c.get(n.inicio() - 1))) {
                    maxTodos = n.valor();
                    consumidos.add(n.inicio() - 1);
                    for (int k = n.inicio(); k < n.fin(); k++) {
                        consumidos.add(k);
                    }
                } else {
                    restantes.add(n);
                }
            }
            sueltos = restantes;
            if (!sueltos.isEmpty()) {
                todosServicios = false;
                maxTodos = null;
                avisoTodos = true;
            }
        }
        Integer primero = null;
        Integer conServicio = null;
        for (NumeroSuelto n : sueltos) {
            if (primero == null) {
                primero = n.valor();
            }
            if (conServicio == null && n.conServicio()) {
                conServicio = n.valor();
            }
        }
        return new Cantidades(conServicio != null ? conServicio : primero, todas, todosServicios, maxTodos, avisoTodos, consumidos);
    }

    private static String siguienteTrasArticulos(List<String> c, int k) {
        int j = k + 1;
        while (j < c.size() && ARTICULOS_TODAS.contains(c.get(j))) {
            j++;
        }
        return j < c.size() ? c.get(j) : "";
    }

    /** Número que empieza en {@code i} (cifras, cifras con %, letra o compuesto) o null. */
    private static Lectura leerNumero(List<String> c, int i) {
        String w = c.get(i);
        if (CIFRAS.matcher(w).matches()) {
            return new Lectura(Integer.parseInt(w), i + 1, false);
        }
        Matcher pct = CIFRAS_PORCENTAJE.matcher(w);
        if (pct.matches()) {
            return new Lectura(Integer.parseInt(pct.group(1)), i + 1, true);
        }
        if (CENTENAS.contains(w)) {
            if (i > 0 && POR.equals(c.get(i - 1))) {
                return null;
            }
            int base = NUMEROS_EN_LETRA.get(w);
            Lectura resto = i + 1 < c.size() ? leerDecenas(c, i + 1) : null;
            if (resto != null && resto.valor() < CIEN) {
                return new Lectura(base + resto.valor(), resto.fin(), false);
            }
            return new Lectura(base, i + 1, false);
        }
        Lectura l = leerDecenas(c, i);
        if (l != null && l.fin() == i + 1 && PALABRAS_UNO.contains(w)
                && (i + 1 >= c.size() || !PALABRAS_SERVICIO.contains(c.get(i + 1)))) {
            return null;
        }
        return l;
    }

    /** Número en letra hasta 99 (incluido «decena y unidad») o el valor simple de la palabra; null si no es número. */
    private static Lectura leerDecenas(List<String> c, int i) {
        String w = c.get(i);
        Integer v = NUMEROS_EN_LETRA.get(w);
        if (v == null) {
            return null;
        }
        if (DECENAS.contains(w) && i + 2 < c.size() && Y.equals(c.get(i + 1)) && UNIDADES.containsKey(c.get(i + 2))) {
            return new Lectura(v + UNIDADES.get(c.get(i + 2)), i + 3, false);
        }
        return new Lectura(v, i + 1, false);
    }

    private static Intencion intencion(List<Token> tokens, Integer numero) {
        if (numero != null || tokens.stream().anyMatch(t -> formaDeClase(t.texto()))) {
            return Intencion.GENERAR;
        }
        if (tokens.stream().anyMatch(t -> PALABRAS_CONTINUAR.contains(t.clave()))) {
            return Intencion.CONTINUAR;
        }
        if (tokens.stream().anyMatch(t -> PALABRAS_RUTA.contains(t.clave()))) {
            return Intencion.RUTA;
        }
        return Intencion.GENERAR;
    }

    /**
     * Identificador Java (admite puntos de FQN) con aspecto de clase: FQN, camelCase/PascalCase o terminado en
     * service/impl. Las palabras de relleno nunca tienen forma de clase.
     */
    public static boolean formaDeClase(String texto) {
        if (texto == null || !esIdentificador(texto)) {
            return false;
        }
        String c = clave(texto);
        if (esRelleno(c)) {
            return false;
        }
        if (texto.indexOf('.') >= 0) {
            return true;
        }
        for (int k = 1; k < texto.length(); k++) {
            if (Character.isUpperCase(texto.charAt(k)) && (Character.isLowerCase(texto.charAt(k - 1))
                    || (k + 1 < texto.length() && Character.isLowerCase(texto.charAt(k + 1))))) {
                return true;
            }
        }
        return terminaEnServicio(c);
    }

    private static boolean terminaEnServicio(String clave) {
        return (clave.endsWith(CLAVE_SERVICE) && clave.length() > CLAVE_SERVICE.length())
                || (clave.endsWith(CLAVE_IMPL) && clave.length() > CLAVE_IMPL.length());
    }

    private static boolean esIdentificador(String texto) {
        if (texto.isEmpty()) {
            return false;
        }
        for (String seg : texto.split("\\.", -1)) {
            if (seg.isEmpty() || !Character.isJavaIdentifierStart(seg.charAt(0))) {
                return false;
            }
            for (int k = 1; k < seg.length(); k++) {
                if (!Character.isJavaIdentifierPart(seg.charAt(k)) || Character.isIdentifierIgnorable(seg.charAt(k))) {
                    return false;
                }
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ interpretación contra el proyecto

    /**
     * Servicios, funciones y nombres no encontrados de la petición. Con {@code serviciosDados} no vacío mandan ellos:
     * se devuelven servicios y no encontrados vacíos (las funciones se buscan en sus objetivos).
     */
    public static Resultado interpretar(ProyectoEscaneado p, Lexico lx, String modulo, List<String> serviciosDados) {
        List<Token> tokens = lx.tokens();
        Set<Integer> consumidos = cantidades(tokens).consumidos();
        List<Integer> candidatos = new ArrayList<>();
        for (int i = 0; i < tokens.size(); i++) {
            if (!consumidos.contains(i)) {
                candidatos.add(i);
            }
        }
        List<String> avisos = new ArrayList<>();
        // 1. Servicios.
        Map<Integer, String> servicioDe = new TreeMap<>();
        Set<Integer> aplazados = new HashSet<>();
        for (int i : candidatos) {
            Token t = tokens.get(i);
            if (esRelleno(t.clave()) || !esIdentificador(t.texto())) {
                continue;
            }
            boolean forma = formaDeClase(t.texto());
            List<String> nombres = forma ? List.of(t.texto()) : List.of(t.texto(), t.texto() + SUFIJO_SERVICE);
            for (String nombre : nombres) {
                BuscadorServicios.Resultado r = BuscadorServicios.buscar(p, nombre, modulo);
                if (!(r.resuelto() || r.ambiguo() || r.interfazSinImplementacion())) {
                    continue;
                }
                if (detectado(r)) {
                    servicioDe.put(i, nombre);
                    break;
                }
                if (forma) {
                    if (t.trasMarcador()) {
                        aplazados.add(i);
                    } else {
                        servicioDe.put(i, nombre);
                    }
                    break;
                }
            }
        }
        // 2. Servicio de las funciones (exactamente uno).
        List<String> fuente = serviciosDados == null || serviciosDados.isEmpty() ? new ArrayList<>(servicioDe.values())
                : serviciosDados;
        List<ProyectoEscaneado.Encontrada> objetivos = new ArrayList<>();
        for (String s : fuente) {
            for (ProyectoEscaneado.Encontrada e : BuscadorServicios.buscar(p, s, modulo).objetivos()) {
                if (objetivos.stream().noneMatch(x -> x.modulo() == e.modulo() && x.clase().fqn.equals(e.clase().fqn))) {
                    objetivos.add(e);
                }
            }
        }
        List<String> metodos = null;
        if (objetivos.size() == 1) {
            ProyectoEscaneado.Encontrada e = objetivos.get(0);
            metodos = new ArrayList<>();
            for (SeleccionFunciones.Funcion f : SeleccionFunciones.de(e.modulo(), e.clase(), true).funciones()) {
                if (!metodos.contains(f.metodo().nombre)) {
                    metodos.add(f.metodo().nombre);
                }
            }
        }
        boolean hayMarcador = tokens.stream().anyMatch(t -> MARCADORES.contains(t.clave()));
        // 3. Funciones.
        Map<Integer, String> funcionDe = new TreeMap<>();
        List<String> funciones = new ArrayList<>();
        if (metodos != null) {
            Map<String, String> porClave = new LinkedHashMap<>();
            metodos.forEach(m -> porClave.putIfAbsent(clave(m), m));
            for (int i : candidatos) {
                Token t = tokens.get(i);
                if (servicioDe.containsKey(i) || CONECTORES.contains(t.clave()) || MARCADORES.contains(t.clave())) {
                    continue;
                }
                boolean forma = formaDeClase(t.texto());
                String real = porClave.get(t.clave());
                String funcion = null;
                if (real != null && (t.trasMarcador() || forma)) {
                    funcion = real;
                } else if (t.trasMarcador() && forma && Character.isLowerCase(t.texto().charAt(0)) && t.texto().indexOf('.') < 0
                        && !terminaEnServicio(t.clave()) && !aplazados.contains(i)) {
                    funcion = t.texto();
                }
                if (funcion != null) {
                    funcionDe.put(i, funcion);
                    if (!funciones.contains(funcion)) {
                        funciones.add(funcion);
                    }
                }
            }
        }
        // 4. Aplazados que no son función → servicio.
        for (int i : aplazados) {
            if (!funcionDe.containsKey(i)) {
                servicioDe.put(i, tokens.get(i).texto());
            }
        }
        // 5. No encontrados y avisos de funciones.
        List<String> noEncontrados = new ArrayList<>();
        List<String> ignoradas = new ArrayList<>();
        List<String> sobrantes = new ArrayList<>();
        for (int i : candidatos) {
            if (servicioDe.containsKey(i) || funcionDe.containsKey(i)) {
                continue;
            }
            Token t = tokens.get(i);
            boolean forma = formaDeClase(t.texto());
            boolean pareceFuncion = t.trasMarcador() && esIdentificador(t.texto()) && Character.isLowerCase(t.texto().charAt(0))
                    && t.texto().indexOf('.') < 0 && !terminaEnServicio(t.clave());
            if (forma) {
                if (hayMarcador && metodos == null && pareceFuncion) {
                    anadirSinRepetir(ignoradas, t.texto());
                } else {
                    anadirSinRepetir(noEncontrados, t.texto());
                }
            } else if (!esRelleno(t.clave()) && pareceFuncion) {
                if (metodos == null && objetivos.size() > 1) {
                    anadirSinRepetir(ignoradas, t.texto());
                } else if (metodos != null) {
                    anadirSinRepetir(sobrantes, t.texto());
                }
            }
        }
        if (!ignoradas.isEmpty()) {
            avisos.add("Las funciones concretas solo se admiten con UN servicio: se ignoran " + listaCorta(ignoradas, MAX_NOMBRES_AVISO));
        }
        if (hayMarcador && metodos != null && !lx.todas() && funciones.isEmpty() && !sobrantes.isEmpty()) {
            avisos.add("Ningún nombre tras «solo/funciones» coincide con una función de `" + objetivos.get(0).clase().simple + "` ("
                    + listaCorta(sobrantes, MAX_NOMBRES_AVISO) + "): se usa la selección normal. Funciones válidas: "
                    + listaCorta(metodos, MAX_FUNCIONES_AVISO));
        }
        // 6. Servicios sin duplicados (sin distinguir mayúsculas), en orden de aparición.
        List<String> servicios = new ArrayList<>();
        for (String s : servicioDe.values()) {
            anadirSinRepetir(servicios, s);
        }
        boolean mandanDados = serviciosDados != null && !serviciosDados.isEmpty();
        return new Resultado(mandanDados ? List.of() : List.copyOf(servicios), List.copyOf(funciones),
                mandanDados ? List.of() : List.copyOf(noEncontrados), List.copyOf(avisos));
    }

    /** Todas las clases encontradas están detectadas como servicio (por FQN) en su módulo. */
    private static boolean detectado(BuscadorServicios.Resultado r) {
        List<ProyectoEscaneado.Encontrada> l = r.resuelto() ? r.objetivos() : r.candidatos();
        if (l.isEmpty()) {
            return false;
        }
        for (ProyectoEscaneado.Encontrada e : l) {
            if (!esServicioDetectado(e)) {
                return false;
            }
        }
        return true;
    }

    private static boolean esServicioDetectado(ProyectoEscaneado.Encontrada e) {
        if (e.modulo().deteccion == null) {
            return false;
        }
        for (DetectorServicios.Candidato c : e.modulo().deteccion.servicios) {
            if (c.clase != null && c.clase.fqn.equals(e.clase().fqn)) {
                return true;
            }
        }
        return false;
    }

    private static void anadirSinRepetir(List<String> l, String s) {
        if (l.stream().noneMatch(x -> x.equalsIgnoreCase(s))) {
            l.add(s);
        }
    }

    private static String listaCorta(List<String> l, int max) {
        String r = String.join(", ", l.subList(0, Math.min(max, l.size())));
        return l.size() > max ? r + ", …" : r;
    }

    // ------------------------------------------------------------------ parecidos

    /**
     * Hasta {@code max} servicios detectados (nombre simple) parecidos a {@code nombre}: iguales sin sufijos
     * Impl/Service, uno contiene al otro (≥ 3 caracteres) o a poca distancia de edición. Orden: distancia y nombre.
     */
    public static List<String> parecidos(ProyectoEscaneado p, String nombre, int max) {
        if (p == null || nombre == null || max <= 0) {
            return List.of();
        }
        String objetivo = base(nombre);
        if (objetivo.isEmpty()) {
            return List.of();
        }
        Set<String> universo = new TreeSet<>();
        for (ModuloEscaneado me : p.modulos) {
            if (me.deteccion == null) {
                continue;
            }
            for (DetectorServicios.Candidato c : me.deteccion.servicios) {
                if (c.clase != null && c.clase.simple != null) {
                    universo.add(c.clase.simple);
                }
            }
        }
        record Parecido(String nombre, int distancia) {
        }
        List<Parecido> l = new ArrayList<>();
        for (String s : universo) {
            String b = base(s);
            if (b.isEmpty()) {
                continue;
            }
            int d = levenshtein(objetivo, b);
            int mayor = Math.max(objetivo.length(), b.length());
            boolean contiene = objetivo.length() >= 3 && b.length() >= 3 && (objetivo.contains(b) || b.contains(objetivo));
            if (objetivo.equals(b) || contiene || d <= Math.max(1, Math.min(3, mayor / 4))) {
                l.add(new Parecido(s, d));
            }
        }
        l.sort(Comparator.comparingInt(Parecido::distancia).thenComparing(Parecido::nombre));
        List<String> r = new ArrayList<>();
        for (int i = 0; i < Math.min(max, l.size()); i++) {
            r.add(l.get(i).nombre());
        }
        return r;
    }

    /** Nombre simple en clave, sin sufijo impl y luego sin sufijo service. */
    private static String base(String nombre) {
        String n = nombre.contains(".") ? nombre.substring(nombre.lastIndexOf('.') + 1) : nombre;
        String c = clave(n.strip());
        if (c.endsWith(CLAVE_IMPL) && c.length() > CLAVE_IMPL.length()) {
            c = c.substring(0, c.length() - CLAVE_IMPL.length());
        }
        if (c.endsWith(CLAVE_SERVICE) && c.length() > CLAVE_SERVICE.length()) {
            c = c.substring(0, c.length() - CLAVE_SERVICE.length());
        }
        return c;
    }

    private static int levenshtein(String a, String b) {
        int[] previa = new int[b.length() + 1];
        int[] actual = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previa[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            actual[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int coste = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                actual[j] = Math.min(Math.min(actual[j - 1] + 1, previa[j] + 1), previa[j - 1] + coste);
            }
            int[] t = previa;
            previa = actual;
            actual = t;
        }
        return previa[b.length()];
    }
}
