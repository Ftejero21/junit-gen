package com.ejemplo.config;

import com.ejemplo.otros.RelojSistema;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ConfiguracionApp {

    @Bean
    public RelojSistema reloj() {
        return new RelojSistema();
    }
}