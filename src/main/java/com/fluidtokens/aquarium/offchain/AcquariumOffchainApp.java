package com.fluidtokens.aquarium.offchain;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.fluidtokens.aquarium.offchain")
public class AcquariumOffchainApp {

    public static void main(String[] args) {
        SpringApplication.run(AcquariumOffchainApp.class, args);
    }

}
