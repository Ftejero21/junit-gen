package com.ejemplo.tienda.pedidos;

/** Datos de un pedido nuevo: qué producto y cuántas unidades. */
public class PedidoRequest {

    private final Long productoId;
    private final int unidades;

    public PedidoRequest(Long productoId, int unidades) {
        this.productoId = productoId;
        this.unidades = unidades;
    }

    public Long getProductoId() {
        return productoId;
    }

    public int getUnidades() {
        return unidades;
    }
}
