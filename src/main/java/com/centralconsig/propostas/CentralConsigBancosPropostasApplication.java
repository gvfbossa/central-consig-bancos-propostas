package com.centralconsig.propostas;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = {
		"com.centralconsig.propostas",
		"com.centralconsig.core"
})
@EnableScheduling
public class CentralConsigBancosPropostasApplication {

	public static void main(String[] args) {
		SpringApplication.run(CentralConsigBancosPropostasApplication.class, args);
	}

}
