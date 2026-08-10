package com.linetranslate.bot;

import com.linetranslate.bot.config.DotenvEnvironmentLoader;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableAsync;

import java.nio.file.Path;

@SpringBootApplication
@EnableAsync
@EnableCaching
public class LinebotTranslatorApplication {

	public static void main(String[] args) {
		DotenvEnvironmentLoader.load(Path.of(".env"));

		SpringApplication.run(LinebotTranslatorApplication.class, args);
	}
}
