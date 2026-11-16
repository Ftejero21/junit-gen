package com.ejemplo.tienda.pedidos;

import java.util.Optional;

public interface PedidoRepository {

    Optional<Pedido> findById(Long id);

    Pedido save(Pedido pedido);
}
