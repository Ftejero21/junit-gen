package com.ejemplo.tienda.carrito;

import java.util.ArrayList;
import java.util.List;

public class Carrito {

    private Long id;
    private List<LineaCarrito> lineas = new ArrayList<>();

    public Carrito() {
    }

    public Carrito(Long id) {
        this.id = id;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public List<LineaCarrito> getLineas() {
        return lineas;
    }

    public void setLineas(List<LineaCarrito> lineas) {
        this.lineas = lineas;
    }
}
