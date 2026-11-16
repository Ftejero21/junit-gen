package com.legacy.negocio;

import javax.ejb.Stateless;

@Stateless
public class CobroBean {

    private PagoGateway gateway;

    public boolean cobrar(double importe) {
        if (importe <= 0) {
            return false;
        }
        return gateway.cargar(importe);
    }
}