package com.nbl.cfa;

import java.util.TimeZone;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class CiFailureAnalyzerApplication {

	public static void main(String[] args) {
		// PostgreSQL không nhận alias Asia/Saigon; lưu timestamp nhất quán theo UTC.
		TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
		SpringApplication.run(CiFailureAnalyzerApplication.class, args);
	}

}
