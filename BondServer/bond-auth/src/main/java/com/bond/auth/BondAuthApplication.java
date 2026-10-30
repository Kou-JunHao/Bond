package com.bond.auth;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

@SpringBootApplication
@ComponentScan(basePackages = {"com.bond.auth", "com.bond.common"})
@MapperScan("com.bond.auth.mapper")
public class BondAuthApplication {
    public static void main(String[] args) {
        SpringApplication.run(BondAuthApplication.class, args);
    }
}
