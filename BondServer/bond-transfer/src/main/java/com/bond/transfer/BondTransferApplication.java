package com.bond.transfer;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

@SpringBootApplication
@ComponentScan(basePackages = {"com.bond.transfer", "com.bond.common"})
@MapperScan("com.bond.transfer.mapper")
public class BondTransferApplication {
    public static void main(String[] args) {
        SpringApplication.run(BondTransferApplication.class, args);
    }
}
