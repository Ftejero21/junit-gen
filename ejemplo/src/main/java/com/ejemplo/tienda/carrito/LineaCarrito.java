package com.ejemplo.tienda.carrito;

import java.math.BigDecimal;

public class LineaCarrito {

    private Long productoId;
    private int unidades;
    private BigDecimal precioUnitario;

    public LineaCarrito() {
    }

    public LineaCarrito(Long productoId, int unidades, BigDecimal precioUnitario) {
        this.productoId = productoId;
        this.unidades = unidades;
        this.precioUnitario = precioUnitario;
    }

    public Long getProductoId() {
        return productoId;
    }

    public void setProductoId(Long productoId) {
        this.productoId = productoId;
    }

    public int getUnidades() {
        return unidades;
    }

    public void setUnidades(int unidades) {
        this.unidades = unidades;
    }

    public BigDecimal getPrecioUnitario() {
        return precioUnitario;
    }

    public void setPrecioUnitario(BigDecimal precioUnitario) {
        this.precioUnitario = precioUnitario;
    }
}
