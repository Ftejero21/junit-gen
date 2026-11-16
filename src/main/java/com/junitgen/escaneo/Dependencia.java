package com.junitgen.escaneo;

import java.util.ArrayList;
import java.util.List;

/** Dependencia de un servicio y cómo la obtiene. */
public class Dependencia {

    public enum TipoObtencion {
        CONSTRUCTOR, VALOR_CONSTRUCTOR, SETTER, CAMPO_ANOTADO, CAMPO_SIN_ANOTAR, NEW_INTERNO, ESTATICA, CAMPO_DERIVADO,
        /** Mock del único elemento de una colección/proveedor de beans ({@link #contenedor}). */
        ELEMENTO_COLECCION
    }

    public enum ModoMontaje { SIN_MOCKS, INJECT_MOCKS, EXPLICITO }

    public String nombre;
    public String tipo;
    public String tipoFqn;
    public TipoObtencion obtencion;
    public boolean mockeable;
    public String nota;
    /** Posición en el constructor elegido (CONSTRUCTOR / VALOR_CONSTRUCTOR). */
    public Integer posicionConstructor;
    /** Setter que la recibe (SETTER). */
    public String setter;
    /** Campo del servicio donde acaba (para inyección por reflexión). */
    public String campo;
    /** true si el spec debe aportar el valor en construccion.argumentos. */
    public boolean requiereValor;
    /** true si el test debe declararlo con {@code RETURNS_DEEP_STUBS}. */
    public boolean profundo;
    /** true si el constructor elegido usa el parámetro (más allá de guardarlo). */
    public boolean usadaEnConstructor;
    /** Parámetros del constructor de los que deriva (CAMPO_DERIVADO). */
    public List<String> derivaDe = new ArrayList<>();
    /** Expresión Java del valor por defecto de {@code @Value("${clave:defecto}")} (convertida con reglas cerradas). */
    public String valorPorDefecto;
    /** Expresión de la instancia real por receta (RecetasMontaje) si el spec no da construccion.argumentos. */
    public String realPorDefecto;
    /** FQN que necesita {@link #realPorDefecto}. */
    public List<String> importsReal = new ArrayList<>();
    /** ELEMENTO_COLECCION: nombre de la dependencia contenedora. */
    public String contenedor;
    /** Clase padre de la que se hereda (dependencias heredadas); null si es propia. */
    public String heredadoDe;
    /** true si la nota aporta una receta o aviso que debe mostrarse aunque la dependencia sea mock. */
    public boolean notaVisible;

    /** true si se declara como {@code @Mock} en el test. */
    public boolean esMockDeclarado() {
        if (!mockeable) {
            return false;
        }
        return switch (obtencion) {
            case CONSTRUCTOR, SETTER, CAMPO_ANOTADO, CAMPO_SIN_ANOTAR -> true;
            case NEW_INTERNO -> campo != null;
            case CAMPO_DERIVADO, ELEMENTO_COLECCION -> true;
            default -> false;
        };
    }
}
