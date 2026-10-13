package com.linetranslate.bot;

import com.linetranslate.bot.config.DotenvEnvironmentLoader;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.nio.file.Path;

@SpringBootApplication
public class LinebotTranslatorApplication {

	public static void main(String[] args) {
		DotenvEnvironmentLoader.load(Path.of(".env"));

		SpringApplication.run(LinebotTranslatorApplication.class, args);
	}
}
