package com.nbl.cfa;

import java.util.TimeZone;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class CiFailureAnalyzerApplicationTests {

	static {
		TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
	}

	@Test
	void contextLoads() {
	}

}
