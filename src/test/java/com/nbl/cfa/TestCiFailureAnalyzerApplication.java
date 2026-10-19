package com.nbl.cfa;

import org.springframework.boot.SpringApplication;

public class TestCiFailureAnalyzerApplication {

	public static void main(String[] args) {
		SpringApplication.from(CiFailureAnalyzerApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
