package io.github.ghgongjin.sitemap;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * @ClassName SitemapGeneratorApplication
 * @Description 站点地图生成器应用
 * @Author gj
 * @Date 2026/3/8
 * @Version 1.0
 */
@SpringBootApplication
@EnableScheduling
public class SitemapGeneratorApplication {

    public static void main(String[] args) {
        SpringApplication.run(SitemapGeneratorApplication.class, args);
        System.out.println("==========================================");
        System.out.println("站点地图生成器启动成功！");
        System.out.println("访问地址: http://localhost:8080");
        System.out.println("==========================================");
    }
}