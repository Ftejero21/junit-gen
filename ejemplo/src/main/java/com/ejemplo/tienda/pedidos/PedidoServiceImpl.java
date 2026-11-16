package com.ejemplo.tienda.pedidos;

import com.ejemplo.tienda.error.PedidoNoEncontradoException;
import com.ejemplo.tienda.error.SinStockException;
import org.springframework.stereotype.Service;

@Service
public class PedidoServiceImpl implements PedidoService {

    private final PedidoRepository pedidoRepository;
    private final StockClient stockClient;

    public PedidoServiceImpl(PedidoRepository pedidoRepository, StockClient stockClient) {
        this.pedidoRepository = pedidoRepository;
        this.stockClient = stockClient;
    }

    /** Crea un pedido nuevo reservando el stock del producto. */
    @Override
    public Pedido crear(PedidoRequest request) {
        if (request.getUnidades() <= 0) {
            throw new IllegalArgumentException("Las unidades deben ser mayores que cero");
        }
        Long productoId = request.getProductoId();
        if (stockClient.disponible(productoId) < request.getUnidades()) {
            throw new SinStockException("Producto " + productoId + " sin stock");
        }
        stockClient.reservar(productoId, request.getUnidades());
        Pedido pedido = new Pedido();
        pedido.setProductoId(productoId);
        pedido.setUnidades(request.getUnidades());
        pedido.setEstado(EstadoPedido.NUEVO);
        return pedidoRepository.save(pedido);
    }

    /** Cancela un pedido que aún no se ha enviado y libera su stock. */
    @Override
    public Pedido cancelar(Long id) {
        Pedido pedido = buscar(id);
        if (pedido.getEstado() == EstadoPedido.ENVIADO) {
            throw new IllegalStateException("El pedido " + id + " ya se ha enviado");
        }
        if (pedido.getEstado() == EstadoPedido.CANCELADO) {
            return pedido;
        }
        stockClient.liberar(pedido.getProductoId(), pedido.getUnidades());
        pedido.setEstado(EstadoPedido.CANCELADO);
        return pedidoRepository.save(pedido);
    }

    @Override
    public Pedido buscar(Long id) {
        return pedidoRepository.findById(id)
                .orElseThrow(() -> new PedidoNoEncontradoException("Pedido " + id + " no encontrado"));
    }
}
