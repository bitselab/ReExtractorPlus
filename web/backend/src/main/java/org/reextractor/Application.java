package org.reextractor;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class Application {

    public static void main(String[] args) throws InterruptedException {
//        SpringApplication.run(Application.class, args);
        for (int i = 0; i< 10000; i++) {
            Thread.sleep(1000);
            System.out.println(1);
        }
    }

}
