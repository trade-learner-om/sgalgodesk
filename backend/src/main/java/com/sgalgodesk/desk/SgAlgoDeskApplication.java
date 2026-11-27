package com.sgalgodesk.desk;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class SgAlgoDeskApplication {

	public static void main(String[] args) {
		SpringApplication.run(SgAlgoDeskApplication.class, args);
	}

}
