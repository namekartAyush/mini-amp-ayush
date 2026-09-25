package com.namekart.auction_api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class AuctionApiApplication {

	public static void main(String[] args) {
		SpringApplication.run(AuctionApiApplication.class, args);
	}

}

