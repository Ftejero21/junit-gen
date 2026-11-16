package com.ejemplo.tienda.facturas;

import java.util.List;
import java.util.Optional;

public interface FacturaRepository {

    Optional<Factura> findById(Long id);

    List<Factura> findByCliente(String cliente);

    Factura save(Factura factura);
}
