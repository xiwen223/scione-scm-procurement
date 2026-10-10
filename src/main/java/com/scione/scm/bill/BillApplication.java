package com.scione.scm.bill;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import com.scione.api.data.client.WeComClient;

/**
 * 供应链单据服务启动入口。
 */
@SpringBootApplication
@EnableFeignClients(clients = WeComClient.class)
public class BillApplication {

    public static void main(String[] args) {
        SpringApplication.run(BillApplication.class, args);
    }
}
