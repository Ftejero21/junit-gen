package com.ejemplo.tienda.carrito;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Service
public class CarritoService {

    private final CarritoRepository carritoRepository;

    public CarritoService(CarritoRepository carritoRepository) {
        this.carritoRepository = carritoRepository;
    }

    /** Añade unidades de un producto; si ya estaba en el carrito, suma las unidades. */
    public Carrito agregar(Long carritoId, Long productoId, int unidades, BigDecimal precioUnitario) {
        if (unidades <= 0) {
            throw new IllegalArgumentException("Las unidades deben ser mayores que cero");
        }
        if (precioUnitario == null || precioUnitario.signum() < 0) {
            throw new IllegalArgumentException("Precio no válido");
        }
        Carrito carrito = carritoRepository.findById(carritoId).orElseGet(() -> new Carrito(carritoId));
        LineaCarrito existente = null;
        for (LineaCarrito linea : carrito.getLineas()) {
            if (linea.getProductoId().equals(productoId)) {
                existente = linea;
            }
        }
        if (existente != null) {
            existente.setUnidades(existente.getUnidades() + unidades);
        } else {
            carrito.getLineas().add(new LineaCarrito(productoId, unidades, precioUnitario));
        }
        return carritoRepository.save(carrito);
    }

    /** Importe total del carrito; cero si el carrito no existe. */
    public BigDecimal total(Long carritoId) {
        Carrito carrito = carritoRepository.findById(carritoId).orElse(null);
        if (carrito == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal total = BigDecimal.ZERO;
        for (LineaCarrito linea : carrito.getLineas()) {
            total = total.add(linea.getPrecioUnitario().multiply(BigDecimal.valueOf(linea.getUnidades())));
        }
        return total;
    }

    /** Quita todas las líneas del carrito. */
    public Carrito vaciar(Long carritoId) {
        Carrito carrito = carritoRepository.findById(carritoId)
                .orElseThrow(() -> new IllegalArgumentException("No existe el carrito " + carritoId));
        if (carrito.getLineas().isEmpty()) {
            return carrito;
        }
        carrito.getLineas().clear();
        return carritoRepository.save(carrito);
    }
}
