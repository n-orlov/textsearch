package com.example.textsearch;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

@SpringBootApplication
@EnableAspectJAutoProxy
public class TextsearchApplication {

	public static void main(String[] args) {
		SpringApplication.run(TextsearchApplication.class, args);
	}
}
