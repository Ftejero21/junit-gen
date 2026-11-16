package com.ejemplo.tienda.pedidos;

public interface PedidoService {

    Pedido crear(PedidoRequest request);

    Pedido cancelar(Long id);

    Pedido buscar(Long id);
}
