package com.hmdp.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedissonConfig {

    @Bean
    public RedissonClient redissonClient(){
        // 配置Redisson
        Config config = new Config();
        // 使用单节点模式连接Redis服务器，设置Redis地址和密码
        config.useSingleServer().setAddress("redis://192.168.150.101:6379").setPassword("123321");
        // 创建RedissonClient对象并返回，用于后续分布式锁等功能的实现
        return Redisson.create(config);
    }
}
