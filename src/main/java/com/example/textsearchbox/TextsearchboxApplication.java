package com.example.textsearchbox;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

@SpringBootApplication
@EnableAspectJAutoProxy
public class TextsearchboxApplication {

	public static void main(String[] args) {
		SpringApplication.run(TextsearchboxApplication.class, args);
	}
}
