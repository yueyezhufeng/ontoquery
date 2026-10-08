package com.ontoquery;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class OntoQueryApplication {

    public static void main(String[] args) {
        SpringApplication.run(OntoQueryApplication.class, args);
    }
}
