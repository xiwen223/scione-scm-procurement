package com.scione.scm.bill;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * 供应链单据服务启动入口。
 */
@SpringBootApplication
@EnableFeignClients(basePackages = "com.scione.api.data.client")
public class BillApplication {

    public static void main(String[] args) {
        SpringApplication.run(BillApplication.class, args);
    }
}
