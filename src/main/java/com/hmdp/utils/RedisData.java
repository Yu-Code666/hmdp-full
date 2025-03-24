package com.hmdp.utils;

import lombok.Data;

import java.time.LocalDateTime;

@Data // 使用Lombok的@Data注解自动生成getter、setter、toString等方法
public class RedisData {
    private LocalDateTime expireTime; // 逻辑过期时间,用于判断缓存是否过期
    private Object data; // 实际存储的数据,使用Object类型可以存储任意类型的数据
}
