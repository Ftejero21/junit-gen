package com.junitgen.generacion;

import com.junitgen.duplicados.ExtractorHuellasTests;
import com.junitgen.seguridad.EscritorSeguro;
import com.junitgen.spec.CasoSpec;
import com.junitgen.spec.ErrorValidacion;
import com.junitgen.spec.ResultadoValidacion;
import com.junitgen.spec.SeleccionSpec;
import com.junitgen.spec.Spec;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Fase 4: genera un test por servicio en la carpeta de salida, en una carpeta por servicio
 * ({@link RutasSalida}); el paquete se conserva en el código. En ejecuciones
 * posteriores solo añade casos nuevos. Planifica todo en memoria y escribe en dos fases con
 * {@link EscritorSeguro}.
 */
public final class GeneradorTests {

    public static class Opciones {
        public Set<String> reemplazar = new LinkedHashSet<>();
        public Set<String> eliminar = new LinkedHashSet<>();
        public boolean confirmado;
        /** Modo `todas`: el 100 % de las funciones (lo usa la validación previa a generar). */
        public boolean todas;
        /** Selecciones de funciones de la sesión MCP (FQN → ia/usuario); null = modo CLI (la del spec). */
        public Map<String, SeleccionSpec> selecciones;
        /**
         * Lo activa arreglar_caso con {@code servicio}: la parte común recibida se aplica de verdad (imports que
         * chocan por nombre simple se sustituyen y las fixtures con el mismo nombre se reescriben).
         */
        public boolean reemplazarComun;
        /**
         * Permiso para comprobar (compilar y ejecutar en el temporal) la copia ampliada de un test que ya existe en el
         * proyecto. false (por defecto) = sin intento de mezcla: los casos van a {@code <Servicio>GeneradoTest}.
         */
        public boolean verificarMezcla;
        /** Veredictos de las copias ampliadas ya comprobadas (clave = {@link CopiaAmpliada.Pendiente#clave()}). */
        public final Map<String, CopiaAmpliada.Veredicto> veredictos = new LinkedHashMap<>();
    }

    /** Casos y fixtures renderizados de un servicio y, si hay archivo existente, su fusión. */
    record Render(Map<String, ExtractorHuellasTests.Marcador> marcadores, List<PlantillaTest.Bloque> bloques,
                  List<PlantillaTest.Bloque> fixtures, Map<String, ResultadoValidacion.CasoValidado> porId,
                  FusionadorArchivo.Resultado fr) {
    }

    /** Código de error de la guardia: el caso necesita una decisión del usuario antes de tocarse. */
    public static final String COD_DECISION_USUARIO = "REQUIERE_DECISION_USUARIO";
    /** Qué debe hacer el agente cuando la guardia rechaza la llamada. */
    public static final String SUGERENCIA_DECISION = "Quita esos ids de reemplazarCasos/eliminarCasos. Un caso con fallo de "
            + "aserción solo se reemplaza después de registrar su veredicto error_ia con registrar_analisis_ia (motivo basado en "
            + "el código); no preguntes al usuario. Solo con `fallosAsercion: preguntar` en junit-gen.yml decide el usuario "
            + "(«Ajustar lo esperado» / resolver_fallo).";

    private GeneradorTests() {
    }

    public static ResultadoGeneracion generar(ResultadoValidacion val, EscritorSeguro esc, Opciones op) {
        ResultadoGeneracion g = new ResultadoGeneracion();
        g.avisos.addAll(val.avisos);
        if (!val.valido) {
            g.errores.addAll(val.errores);
            return g;
        }
        Path salida = esc.salida();
        Spec previo = AlmacenSpec.leer(salida);
        if (!op.eliminar.isEmpty() && !op.confirmado) {
            g.errores.add(ErrorValidacion.error("eliminarCasos", "REQUIERE_CONFIRMACION",
                    "Eliminar casos generados exige confirmación explícita del usuario.",
                    "Pregunta al usuario y repite con confirmadoPorUsuario=true (CLI: --confirmar)."));
            return g;
        }
        if (guardiaProtegidos(previo, op, g)) {
            return g;
        }
        // Pre-paso sin efectos: decide qué servicios usan la copia ampliada de su test existente.
        Map<ResultadoValidacion.ServicioValidado, MezclasGeneracion.Decision> decisiones = MezclasGeneracion.decidir(val, esc, op, g);
        if (!g.mezclasPendientes.isEmpty()) {
            return g;
        }
        Map<Path, String> plan = new LinkedHashMap<>();
        Map<String, CasoSpec> actualizados = new LinkedHashMap<>();
        Set<String> eliminadosTotal = new LinkedHashSet<>();
        Set<String> eliminarPendientes = new LinkedHashSet<>(op.eliminar);
        for (ResultadoValidacion.ServicioValidado sv : val.servicios) {
            PerfilPlantilla perfil = PerfilPlantilla.de(sv.modulo.versiones, sv.estilo);
            if (!g.perfiles.contains(perfil.nombre())) {
                g.perfiles.add(perfil.nombre());
            }
            MezclasGeneracion.Decision dec = decisiones.get(sv);
            PlanMezcla mezcla = dec == null ? null : dec.plan();
            PlantillaTest plantilla = mezcla != null ? new PlantillaTest(perfil, sv, mezcla) : new PlantillaTest(perfil, sv);
            Path archivo = RutasSalida.archivoTest(salida, sv.modulo, sv.clase, sv.claseTest);
            if (dec != null && dec.tipo() == MezclasGeneracion.Tipo.NO_TOCAR) {
                g.noTocados.add(archivo + ": " + dec.motivo());
                continue;
            }
            String existente = null;
            if (mezcla != null) {
                existente = dec.base();
            } else if (Files.isRegularFile(archivo)) {
                try {
                    existente = Files.readString(archivo, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    throw new IllegalStateException("No se puede leer " + archivo + ": " + e.getMessage(), e);
                }
            }
            Render rd = render(val, sv, plantilla, existente, op, mezcla != null);
            List<PlantillaTest.Bloque> bloques = rd.bloques();
            List<PlantillaTest.Bloque> fixtures = rd.fixtures();
            Map<String, ResultadoValidacion.CasoValidado> porId = rd.porId();
            String textoFinal = null;
            boolean escrito = false;
            List<String> anadidos = new ArrayList<>();
            Map<String, String> metodos = new LinkedHashMap<>();
            Set<String> reemplazadosAqui = new LinkedHashSet<>();
            if (existente == null) {
                if (bloques.isEmpty() && fixtures.isEmpty()) {
                    continue;
                }
                plan.put(archivo, plantilla.archivoNuevo(bloques, fixtures));
                escrito = true;
                g.archivosCreados.add(archivo.toString());
                g.destinosCopia.put(salida.relativize(archivo).toString().replace('\\', '/'),
                        RutasSalida.destinoCopia(sv.modulo, sv.clase, sv.claseTest));
                for (PlantillaTest.Bloque b : bloques) {
                    anadidos.add(b.id());
                    metodos.put(b.id(), b.nombreMetodo());
                }
            } else {
                FusionadorArchivo.Resultado fr = rd.fr();
                String claseTest = sv.claseTest;
                for (String c : fr.importsSustituidos) {
                    g.cambiosComunes.add("Import sustituido en `" + claseTest + "`: " + c);
                }
                for (String f : fr.fixturesReemplazadas) {
                    g.cambiosComunes.add("Fixture `" + f + "` de `" + claseTest + "` reescrita");
                }
                if (fr.motivoNoTocado != null) {
                    g.noTocados.add(archivo + ": " + fr.motivoNoTocado);
                    continue;
                }
                if (fr.remontado) {
                    List<String> ids = new ArrayList<>();
                    for (ExtractorHuellasTests.Marcador m : ExtractorHuellasTests.marcadores(fr.texto)) {
                        ids.add(m.id());
                    }
                    g.remontados.put(sv.clase.fqn, ids);
                    g.avisos.add(ErrorValidacion.aviso("servicios[" + sv.indice + "].construccion", "MONTAJE_ACTUALIZADO",
                            "montaje de " + sv.clase.simple + " actualizado; los casos existentes se conservan y se vuelven a probar",
                            null));
                }
                boolean nueva = dec != null && dec.tipo() == MezclasGeneracion.Tipo.NUEVA;
                boolean rehecha = dec != null && dec.tipo() == MezclasGeneracion.Tipo.REHECHA;
                if (rehecha) {
                    g.remontados.put(sv.clase.fqn, new ArrayList<>(CopiaAmpliada.idsEnBloques(fr.texto)));
                }
                textoFinal = fr.texto;
                if (fr.modificado || nueva || rehecha) {
                    plan.put(archivo, fr.texto);
                    escrito = true;
                    if (nueva) {
                        g.archivosCreados.add(archivo.toString());
                    } else {
                        g.archivosAmpliados.add(archivo.toString());
                    }
                    g.destinosCopia.put(salida.relativize(archivo).toString().replace('\\', '/'),
                            mezcla != null ? dec.relOriginal() : RutasSalida.destinoCopia(sv.modulo, sv.clase, sv.claseTest));
                }
                anadidos.addAll(fr.anadidos);
                anadidos.addAll(fr.reemplazados);
                metodos.putAll(fr.metodos);
                g.omitidos.addAll(fr.omitidos);
                g.noReescritos.addAll(fr.noReescritos);
                g.reemplazados.addAll(fr.reemplazados);
                reemplazadosAqui.addAll(fr.reemplazados);
                g.eliminados.addAll(fr.eliminados);
                eliminadosTotal.addAll(fr.eliminados);
                eliminarPendientes.removeAll(fr.eliminados);
            }
            if (dec != null) {
                MezclasGeneracion.anotar(g, sv, dec, salida, archivo, textoFinal, anadidos.size(), escrito);
            }
            for (String id : anadidos) {
                ResultadoValidacion.CasoValidado cv = porId.get(id);
                CasoSpec c = cv.spec;
                c.huella = cv.huella.completa;
                c.huellaEntrada = cv.huella.entrada;
                c.estado = "generado";
                if (c.queComprueba != null && !c.queComprueba.isBlank()) {
                    g.queComprueba.put(id, c.queComprueba);
                }
                AlmacenSpec.Ubicacion u = AlmacenSpec.buscar(previo, id);
                if (reemplazadosAqui.contains(id)) {
                    g.cambiosReemplazados.add(ComparadorCasos.bloque(id, metodos.get(id), u != null ? u.caso() : null, c));
                }
                c.intentosMontaje = u != null && u.caso().intentosMontaje != null ? u.caso().intentosMontaje : 0;
                c.veredictoIa = u != null ? u.caso().veredictoIa : null;
                c.motivoIa = u != null ? u.caso().motivoIa : null;
                // La autorización (si la había) se consume con este reemplazo; nunca se toma del spec de entrada.
                c.autorizacionCambio = null;
                actualizados.put(id, c);
                Map<String, String> m = new LinkedHashMap<>();
                m.put("caso", id);
                m.put("servicio", sv.clase.fqn);
                m.put("metodoTest", metodos.get(id));
                m.put("archivo", archivo.toString());
                g.generados.add(m);
            }
            if (sv.analisis.requiereRefactor()) {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("servicio", sv.clase.fqn);
                r.put("metodos", sv.analisis.refactor);
                g.requiereRefactor.add(r);
            }
        }
        for (String id : eliminarPendientes) {
            g.avisos.add(ErrorValidacion.aviso("eliminarCasos", "CASO_NO_ENCONTRADO", "No se encontró el caso " + id
                    + " en los tests generados del spec.", null));
        }
        g.descartados.addAll(val.duplicados);
        g.casosGenerados = g.generados.size();
        Map<String, String> informadas = new LinkedHashMap<>();
        for (ResultadoValidacion.ServicioValidado sv : val.servicios) {
            if (sv.seleccion == null) {
                sv.spec.seleccionInformada = null;
                continue;
            }
            SeleccionSpec usada = sv.seleccion.aSpec();
            String huella = AlmacenSpec.huellaSeleccion(usada);
            String clase = sv.spec.clase;
            String anterior = informadas.containsKey(clase) ? informadas.get(clase)
                    : AlmacenSpec.huellaSeleccionInformada(previo, clase);
            if (!huella.equals(anterior)) {
                g.funcionesOmitidas.add(sv.seleccion.resumenInforme());
            }
            informadas.put(clase, huella);
            sv.spec.seleccion = usada;
            sv.spec.seleccionInformada = huella;
        }
        Spec acumulado = AlmacenSpec.fusionar(previo, val.spec, actualizados, eliminadosTotal, op.reemplazarComun);
        Path rutaSpec = AlmacenSpec.ruta(salida);
        Path rutaInforme = salida.resolve(RutasSalida.INFORME);
        plan.put(rutaSpec, AlmacenSpec.serializar(acumulado));
        List<Path> archivos = new ArrayList<>(plan.keySet());
        archivos.add(rutaInforme);
        String proyecto = val.proyecto.raiz.toString();
        String seccion = EscritorInforme.seccionGeneracion(g, proyecto, salida.toString(), archivos);
        plan.put(rutaInforme, EscritorInforme.anadir(salida, esc.aviso(), seccion));
        esc.escribirTodo(plan);
        g.spec = rutaSpec.toString();
        g.informe = rutaInforme.toString();
        return g;
    }

    /**
     * Renderiza los casos y fixtures de {@code sv} y, si hay {@code existente}, los fusiona (sin escribir nada). Lo
     * usan el pre-paso de las copias ampliadas y el bucle principal, que obtienen así el mismo texto. Con
     * {@code mezcla} nunca se remonta (cabecera y pie null).
     */
    static Render render(ResultadoValidacion val, ResultadoValidacion.ServicioValidado sv, PlantillaTest plantilla, String existente,
                         Opciones op, boolean mezcla) {
        Map<String, ExtractorHuellasTests.Marcador> marcadores = new LinkedHashMap<>();
        Set<String> ocupados = new LinkedHashSet<>();
        if (existente != null) {
            for (ExtractorHuellasTests.Marcador m : ExtractorHuellasTests.marcadores(existente)) {
                marcadores.put(m.id(), m);
            }
            ocupados.addAll(FusionadorArchivo.metodosExistentes(existente));
        }
        List<PlantillaTest.Bloque> bloques = new ArrayList<>();
        Map<String, ResultadoValidacion.CasoValidado> porId = new LinkedHashMap<>();
        for (ResultadoValidacion.CasoValidado cv : sv.casos) {
            if (val.esDuplicado(cv.spec.id)) {
                continue;
            }
            Set<String> ocupadosCaso = new LinkedHashSet<>(ocupados);
            ExtractorHuellasTests.Marcador previoMarcador = marcadores.get(cv.spec.id);
            if (previoMarcador != null && previoMarcador.metodoTest() != null) {
                ocupadosCaso.remove(previoMarcador.metodoTest());
            }
            PlantillaTest.Bloque b = plantilla.caso(cv, ocupadosCaso);
            ocupados.add(b.nombreMetodo());
            bloques.add(b);
            porId.put(cv.spec.id, cv);
        }
        List<PlantillaTest.Bloque> fixtures = new ArrayList<>();
        if (sv.spec.fixtures != null) {
            sv.spec.fixtures.forEach(f -> fixtures.add(plantilla.fixture(f)));
        }
        FusionadorArchivo.Resultado fr = null;
        if (existente != null) {
            Set<String> eliminarAqui = new LinkedHashSet<>();
            for (String id : op.eliminar) {
                if (marcadores.containsKey(id)) {
                    eliminarAqui.add(id);
                }
            }
            fr = FusionadorArchivo.fusionar(existente, plantilla.hashMontaje(), bloques, fixtures,
                    plantilla.importsArchivo(bloques), plantilla.estaticosArchivo(bloques), op.reemplazar, eliminarAqui,
                    mezcla ? null : plantilla.cabeceraClase(), mezcla ? null : plantilla.pieClase(), op.reemplazarComun,
                    sv.spec.imports == null ? List.of() : sv.spec.imports);
        }
        return new Render(marcadores, bloques, fixtures, porId, fr);
    }

    /**
     * Guardia anti-maquillaje: rechaza (sin escribir nada) reemplazar o eliminar casos cuyo estado en el
     * spec.json previo de la salida es protegido ({@link CasoSpec#ESTADOS_PROTEGIDOS}). Reemplazar solo se
     * permite con una autorización registrada en ese spec previo (nunca en el de entrada); eliminar desde
     * generar_tests nunca (lo hace resolver_fallo con confirmación). Devuelve true si hay rechazo.
     */
    static boolean guardiaProtegidos(Spec previo, Opciones op, ResultadoGeneracion g) {
        if (previo == null) {
            return false;
        }
        List<String> reemplazo = new ArrayList<>();
        for (String id : op.reemplazar) {
            AlmacenSpec.Ubicacion u = id == null ? null : AlmacenSpec.buscar(previo, id);
            if (u != null && u.caso().protegido() && u.caso().autorizacionCambio == null) {
                reemplazo.add(id + " (" + u.caso().estado + ")");
            }
        }
        List<String> borrado = new ArrayList<>();
        for (String id : op.eliminar) {
            AlmacenSpec.Ubicacion u = id == null ? null : AlmacenSpec.buscar(previo, id);
            if (u != null && u.caso().protegido()) {
                borrado.add(id + " (" + u.caso().estado + ")");
            }
        }
        if (!reemplazo.isEmpty()) {
            g.errores.add(ErrorValidacion.error("reemplazarCasos", COD_DECISION_USUARIO,
                    "No se pueden reemplazar casos con un fallo de aserción, posible bug o dudoso sin veredicto error_ia registrado (ni decisión del usuario): "
                            + String.join(", ", reemplazo) + ". No se ha escrito nada.", SUGERENCIA_DECISION));
        }
        if (!borrado.isEmpty()) {
            g.errores.add(ErrorValidacion.error("eliminarCasos", COD_DECISION_USUARIO,
                    "No se pueden eliminar desde generar_tests casos con un fallo de aserción, posible bug o dudoso: "
                            + String.join(", ", borrado) + ". No se ha escrito nada.", SUGERENCIA_DECISION));
        }
        return !reemplazo.isEmpty() || !borrado.isEmpty();
    }
}
