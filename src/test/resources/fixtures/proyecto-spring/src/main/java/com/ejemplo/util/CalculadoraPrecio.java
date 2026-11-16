package com.ejemplo.util;

public class CalculadoraPrecio {

    public double calcular(double base, int unidades) {
        if (unidades > 10) {
            return base * unidades * 0.9;
        }
        return base * unidades;
    }
}