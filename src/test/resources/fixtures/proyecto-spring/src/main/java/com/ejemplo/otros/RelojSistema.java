package com.ejemplo.otros;

import java.time.DayOfWeek;

public class RelojSistema {

    public boolean esFinDeSemana(DayOfWeek dia) {
        if (dia == null) {
            throw new IllegalArgumentException("día nulo");
        }
        return dia == DayOfWeek.SATURDAY || dia == DayOfWeek.SUNDAY;
    }
}