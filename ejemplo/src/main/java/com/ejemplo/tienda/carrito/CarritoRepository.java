package com.ejemplo.tienda.carrito;

import java.util.Optional;

public interface CarritoRepository {

    Optional<Carrito> findById(Long id);

    Carrito save(Carrito carrito);
}
