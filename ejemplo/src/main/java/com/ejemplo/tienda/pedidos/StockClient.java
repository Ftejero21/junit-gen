package com.ejemplo.tienda.pedidos;

/** Cliente del servicio de almacén. */
public interface StockClient {

    int disponible(Long productoId);

    void reservar(Long productoId, int unidades);

    void liberar(Long productoId, int unidades);
}
