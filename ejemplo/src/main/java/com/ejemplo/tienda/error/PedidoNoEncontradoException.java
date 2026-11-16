package com.ejemplo.tienda.error;

/** No existe ningún pedido con el identificador indicado. */
public class PedidoNoEncontradoException extends RuntimeException {

    public PedidoNoEncontradoException(String mensaje) {
        super(mensaje);
    }
}
