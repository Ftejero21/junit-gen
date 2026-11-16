package com.junitgen.escaneo;

import com.junitgen.util.Textos;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Recetas de montaje por tipo (nombre simple sin genéricos; los tipos anidados {@code WebClient.Builder} y
 * {@code RestClient.Builder} por texto completo): mocks profundos, instancias reales por defecto y holders
 * estáticos con sus notas y limpiezas. Las expresiones son constantes: nunca se copia texto del proyecto.
 */
public final class RecetasMontaje {

    /** Instancia real por defecto: expresión constante, FQN esperado del tipo e imports que necesita. */
    public record Real(String expresion, String fqnTipo, List<String> imports) {
    }

    /** Holder estático: FQN, nota y limpieza para tearDown (null si no hace falta). */
    public record Holder(String fqn, String nota, String limpieza) {
    }

    private static final Set<String> PROFUNDOS = Set.of("WebClient", "RestClient", "RestTemplateBuilder",
            "RedisTemplate", "StringRedisTemplate", "ReactiveRedisTemplate", "EntityManager", "MongoTemplate",
            "JdbcClient", "DSLContext");
    private static final Set<String> PROFUNDOS_ANIDADOS = Set.of("WebClient.Builder", "RestClient.Builder");

    private static final Map<String, Real> REALES = Map.ofEntries(
            Map.entry("Clock", new Real("Clock.fixed(Instant.parse(\"2024-01-15T10:00:00Z\"), ZoneOffset.UTC)", "java.time.Clock",
                    List.of("java.time.Clock", "java.time.Instant", "java.time.ZoneOffset"))),
            Map.entry("MeterRegistry", new Real("new SimpleMeterRegistry()", "io.micrometer.core.instrument.MeterRegistry",
                    List.of("io.micrometer.core.instrument.simple.SimpleMeterRegistry"))),
            Map.entry("TransactionOperations", new Real("TransactionOperations.withoutTransaction()",
                    "org.springframework.transaction.support.TransactionOperations",
                    List.of("org.springframework.transaction.support.TransactionOperations"))),
            Map.entry("TransactionTemplate", new Real("new TransactionTemplate(mock(PlatformTransactionManager.class))",
                    "org.springframework.transaction.support.TransactionTemplate",
                    List.of("org.springframework.transaction.support.TransactionTemplate",
                            "org.springframework.transaction.PlatformTransactionManager"))),
            Map.entry("Executor", new Real("Runnable::run", "java.util.concurrent.Executor", List.of())),
            Map.entry("TaskExecutor", new Real("Runnable::run", "org.springframework.core.task.TaskExecutor", List.of())),
            Map.entry("Instant", new Real("Instant.parse(\"2024-01-15T10:00:00Z\")", "java.time.Instant",
                    List.of("java.time.Instant"))),
            Map.entry("LocalDate", new Real("LocalDate.of(2024, 1, 15)", "java.time.LocalDate", List.of("java.time.LocalDate"))),
            Map.entry("LocalDateTime", new Real("LocalDateTime.of(2024, 1, 15, 10, 0)", "java.time.LocalDateTime",
                    List.of("java.time.LocalDateTime"))),
            Map.entry("LocalTime", new Real("LocalTime.of(10, 0)", "java.time.LocalTime", List.of("java.time.LocalTime"))),
            Map.entry("OffsetDateTime", new Real("OffsetDateTime.of(2024, 1, 15, 10, 0, 0, 0, ZoneOffset.UTC)",
                    "java.time.OffsetDateTime", List.of("java.time.OffsetDateTime", "java.time.ZoneOffset"))),
            Map.entry("ZonedDateTime", new Real("ZonedDateTime.of(2024, 1, 15, 10, 0, 0, 0, ZoneOffset.UTC)",
                    "java.time.ZonedDateTime", List.of("java.time.ZonedDateTime", "java.time.ZoneOffset"))),
            Map.entry("ZoneId", new Real("ZoneOffset.UTC", "java.time.ZoneId", List.of("java.time.ZoneOffset"))),
            Map.entry("UUID", new Real("UUID.fromString(\"00000000-0000-0000-0000-000000000001\")", "java.util.UUID",
                    List.of("java.util.UUID"))),
            Map.entry("Locale", new Real("Locale.ROOT", "java.util.Locale", List.of("java.util.Locale"))),
            Map.entry("Charset", new Real("StandardCharsets.UTF_8", "java.nio.charset.Charset",
                    List.of("java.nio.charset.StandardCharsets"))));

    private static final Map<String, Holder> HOLDERS = Map.of(
            "SecurityContextHolder", new Holder("org.springframework.security.core.context.SecurityContextHolder",
                    "en preparacion: SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(...)); "
                            + "junit-gen limpia en tearDown",
                    "SecurityContextHolder.clearContext();"),
            "RequestContextHolder", new Holder("org.springframework.web.context.request.RequestContextHolder",
                    "en preparacion: RequestContextHolder.setRequestAttributes(...); junit-gen limpia en tearDown",
                    "RequestContextHolder.resetRequestAttributes();"),
            "TransactionSynchronizationManager", new Holder(
                    "org.springframework.transaction.support.TransactionSynchronizationManager",
                    "sin transacción activa en el test; registerSynchronization lanza IllegalStateException → estaticos[]",
                    null),
            "LocaleContextHolder", new Holder("org.springframework.context.i18n.LocaleContextHolder",
                    "en preparacion: LocaleContextHolder.setLocale(...) si el método depende del idioma", null));

    public static final String NOTA_OBJECT_MAPPER = "si el método navega JSON usa instancia real: construccion.argumentos.%s = new ObjectMapper()";
    public static final String NOTA_EXECUTOR_SERVICE = "mock: submit/execute no ejecutan la tarea; si hace falta usa instancia real en construccion.argumentos.%s";

    private RecetasMontaje() {
    }

    /** true si un mock de este tipo debe declararse con {@code RETURNS_DEEP_STUBS}. */
    public static boolean esProfundo(String tipo, String tipoFqn) {
        if (tipo == null) {
            return false;
        }
        String t = Textos.quitarGenericos(tipo).trim();
        for (String anidado : PROFUNDOS_ANIDADOS) {
            if (t.equals(anidado) || t.endsWith("." + anidado) || (tipoFqn != null && tipoFqn.endsWith("." + anidado))) {
                return true;
            }
        }
        if (t.contains(".") && !Character.isLowerCase(t.charAt(0))) {
            // Tipo anidado escrito como Externo.Interno: solo los anidados de la tabla.
            return false;
        }
        return PROFUNDOS.contains(Textos.nombreSimple(t));
    }

    /**
     * Receta de instancia real para el tipo, o null. Si el FQN resuelto no coincide con el esperado (tipo propio
     * homónimo) no aplica; si no se resolvió, solo aplica cuando el tipo no es del proyecto.
     */
    public static Real real(String tipo, String tipoFqn, boolean delProyecto) {
        if (tipo == null || Textos.quitarGenericos(tipo).contains(".") && tipoFqn == null) {
            return null;
        }
        Real r = REALES.get(Textos.nombreSimple(tipo));
        if (r == null) {
            return null;
        }
        if (tipoFqn != null ? !tipoFqn.equals(r.fqnTipo()) : delProyecto) {
            return null;
        }
        return r;
    }

    /** Holder estático por nombre simple, o null. */
    public static Holder holder(String nombre) {
        return nombre == null ? null : HOLDERS.get(nombre);
    }
}
