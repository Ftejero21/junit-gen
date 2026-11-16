package com.b;

public class BService {

    public String saludo(String nombre) {
        return nombre == null ? "hola" : "hola " + nombre;
    }
}