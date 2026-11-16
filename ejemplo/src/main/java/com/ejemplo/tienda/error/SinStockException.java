package com.ejemplo.tienda.error;

/** No hay unidades suficientes de un producto para atender un pedido. */
public class SinStockException extends RuntimeException {

    public SinStockException(String mensaje) {
        super(mensaje);
    }
}
